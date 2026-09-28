package com.friendspharma.app.service.impl;

import com.friendspharma.app.dto.request.ProductMasterRequest;
import com.friendspharma.app.dto.response.ProductMasterResponse;
import com.friendspharma.app.entity.*;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ProductInUseException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.*;
import com.friendspharma.app.security.SecurityUtils;
import com.friendspharma.app.validation.ProductValidator;
import com.friendspharma.app.validation.ProductValidator.ValidatedReferences;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Product Master service behaviour that does not need a database.
 *
 * <p>The focus is the guarantees the module is built to hold: server-side price
 * recalculation, generated immutable product codes, the transaction-history
 * delete guard, and tenant isolation.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductServiceImplTest {

    private static final Long ORG_ID     = 1L;
    private static final Long BRANCH_ID  = 10L;
    private static final Long PRODUCT_ID = 42L;

    @Mock private PosProductInfoRepository        productRepo;
    @Mock private PosGenericInfoRepository        genericRepo;
    @Mock private PosCompanyInfoRepository        companyRepo;
    @Mock private PosProductCategoryRepository    categoryRepo;
    @Mock private PosProductSubCategoryRepository subCategoryRepo;
    @Mock private PosProductUnitRepository        unitRepo;
    @Mock private PosBoxInfoRepository            boxRepo;
    @Mock private PosLeafInfoRepository           leafRepo;
    @Mock private PosTransactionDtlRepository     transactionDtlRepo;
    @Mock private PosRequisitionDtlRepository     requisitionDtlRepo;
    @Mock private PosCartItemRepository           cartItemRepo;
    @Mock private ProductValidator                validator;
    @Mock private ProductImageStorageService      imageStorage;
    /* [V66] update() moves stock batches, cart lines and discount rules to the
       new version with bulk native statements. */
    @Mock private jakarta.persistence.EntityManager em;
    @Mock private jakarta.persistence.Query         bulkUpdate;

    @InjectMocks private ProductServiceImpl service;

    private MockedStatic<SecurityUtils> securityUtils;

    @BeforeEach
    void setUp() {
        securityUtils = Mockito.mockStatic(SecurityUtils.class);
        securityUtils.when(SecurityUtils::getCurrentOrgId).thenReturn(ORG_ID);
        securityUtils.when(SecurityUtils::getCurrentBranchId).thenReturn(BRANCH_ID);

        when(em.createNativeQuery(anyString())).thenReturn(bulkUpdate);
        when(bulkUpdate.setParameter(anyString(), any())).thenReturn(bulkUpdate);
        when(bulkUpdate.executeUpdate()).thenReturn(0);

        when(validator.validate(any(), any(), any())).thenReturn(references());

        // No transaction history unless a test says otherwise.
        when(transactionDtlRepo.countByProduct(anyLong())).thenReturn(0L);
        when(requisitionDtlRepo.countByProduct(anyLong())).thenReturn(0L);
        when(cartItemRepo.countByProduct(anyLong())).thenReturn(0L);
    }

    @AfterEach
    void tearDown() {
        securityUtils.close();
    }

    /* ══════════════════════════════════════════════════════════════════════
       Case 6 — the client cannot influence calculated values
       ══════════════════════════════════════════════════════════════════════ */

    @Nested
    @DisplayName("Case 6 — derived prices are always recalculated server-side")
    class DerivedPricesAreServerAuthoritative {

        @Test
        @DisplayName("the request DTO exposes no setter for tpPrice, igta or buyPrice")
        void requestDtoHasNoDerivedPriceFields() {
            // The strongest form of "do not trust the frontend": there is no
            // field to bind a forged value to, so no future edit can start
            // trusting one by accident.
            var fieldNames = java.util.Arrays.stream(ProductMasterRequest.class.getDeclaredFields())
                    .map(java.lang.reflect.Field::getName)
                    .toList();

            assertThat(fieldNames)
                    .doesNotContain("tpPrice", "igta", "buyPrice", "productCode");
        }

        @Test
        @DisplayName("create stores calculated prices, not anything the client implied")
        void createRecalculatesPrices() {
            ProductMasterRequest request = validRequest();
            request.setMrp(new BigDecimal("100"));
            request.setConversionRate(new BigDecimal("25.037"));
            request.setVatPct(new BigDecimal("17.40"));

            stubSaveAssigningId();

            service.create(request);

            PosProductInfo saved = captureLastSave();
            assertThat(saved.getTpPrice()).isEqualByComparingTo("74.96");
            assertThat(saved.getIgta()).isEqualByComparingTo("13.04");
            assertThat(saved.getBuyPrice()).isEqualByComparingTo("88.00");
        }

        @Test
        @DisplayName("update recalculates prices from the new inputs")
        void updateRecalculatesPrices() {
            PosProductInfo existing = existingProduct();
            // Pre-existing (now stale) values that must be overwritten.
            existing.setTpPrice(new BigDecimal("999.99"));
            existing.setIgta(new BigDecimal("999.99"));
            existing.setBuyPrice(new BigDecimal("999.99"));

            when(productRepo.findByIdAndOrg(PRODUCT_ID, ORG_ID)).thenReturn(Optional.of(existing));
            when(productRepo.save(any(PosProductInfo.class))).thenAnswer(i -> i.getArgument(0));
            when(productRepo.saveAndFlush(any(PosProductInfo.class))).thenAnswer(i -> i.getArgument(0));

            ProductMasterRequest request = validRequest();
            request.setMrp(new BigDecimal("200"));
            request.setConversionRate(new BigDecimal("25.037"));
            request.setVatPct(new BigDecimal("17.40"));

            service.update(PRODUCT_ID, request);

            PosProductInfo saved = captureLastSave();
            assertThat(saved.getTpPrice()).isEqualByComparingTo("149.93");
            assertThat(saved.getIgta()).isEqualByComparingTo("26.09");
            assertThat(saved.getBuyPrice()).isEqualByComparingTo("176.02");
        }

        @Test
        @DisplayName("omitted conversion rate and VAT fall back to the business defaults")
        void defaultsApplyWhenOmitted() {
            ProductMasterRequest request = validRequest();
            request.setMrp(new BigDecimal("100"));
            request.setConversionRate(null);
            request.setVatPct(null);

            stubSaveAssigningId();

            service.create(request);

            PosProductInfo saved = captureLastSave();
            assertThat(saved.getConversionRate()).isEqualByComparingTo("25.037");
            assertThat(saved.getVatPct()).isEqualByComparingTo("17.40");
            assertThat(saved.getTpPrice()).isEqualByComparingTo("74.96");
        }
    }

    /* ══════════════════════════════════════════════════════════════════════
       Product code
       ══════════════════════════════════════════════════════════════════════ */

    @Nested
    @DisplayName("Product code generation")
    class ProductCode {

        @Test
        @DisplayName("create derives the code from the generated primary key")
        void generatesCodeFromPrimaryKey() {
            stubSaveAssigningId();

            ProductMasterResponse created = service.create(validRequest());

            assertThat(created.getProductCode()).isEqualTo("PRD000042");
        }

        @Test
        @DisplayName("PRODUCT_CODE column must stay updatable")
        void productCodeColumnIsUpdatable() throws Exception {
            // Regression guard. The code is derived from the generated primary key,
            // so it can only be written by the UPDATE that follows the INSERT.
            // Marking the column updatable=false excluded it from that statement
            // and the code was silently never persisted — the mocks below cannot
            // catch that, because a mocked save() has no JPA semantics.
            var column = com.friendspharma.app.entity.PosProductInfo.class
                    .getDeclaredField("productCode")
                    .getAnnotation(jakarta.persistence.Column.class);

            assertThat(column).isNotNull();
            assertThat(column.updatable())
                    .as("PRODUCT_CODE must be updatable or the generated code is never written to the database")
                    .isTrue();
        }

        @Test
        @DisplayName("update never changes an existing product code")
        void updateLeavesProductCodeUntouched() {
            PosProductInfo existing = existingProduct();
            existing.setProductCode("PRD000042");

            when(productRepo.findByIdAndOrg(PRODUCT_ID, ORG_ID)).thenReturn(Optional.of(existing));
            when(productRepo.save(any(PosProductInfo.class))).thenAnswer(i -> i.getArgument(0));
            when(productRepo.saveAndFlush(any(PosProductInfo.class))).thenAnswer(i -> i.getArgument(0));

            service.update(PRODUCT_ID, validRequest());

            assertThat(captureLastSave().getProductCode()).isEqualTo("PRD000042");
        }
    }

    /* ══════════════════════════════════════════════════════════════════════
       Delete guard
       ══════════════════════════════════════════════════════════════════════ */

    @Nested
    @DisplayName("Delete protection")
    class DeleteGuard {

        @Test
        @DisplayName("a product with sales history cannot be deleted")
        void refusesDeleteWhenSalesHistoryExists() {
            when(productRepo.findByIdAndOrg(PRODUCT_ID, ORG_ID)).thenReturn(Optional.of(existingProduct()));
            when(transactionDtlRepo.countByProduct(PRODUCT_ID)).thenReturn(12L);

            assertThatThrownBy(() -> service.delete(PRODUCT_ID))
                    .isInstanceOf(ProductInUseException.class)
                    .hasMessageContaining("12 sales/purchase lines")
                    .hasMessageContaining("Deactivate the product instead");

            verify(productRepo, never()).delete(any(PosProductInfo.class));
        }

        @Test
        @DisplayName("requisition history also blocks deletion")
        void refusesDeleteWhenRequisitionHistoryExists() {
            when(productRepo.findByIdAndOrg(PRODUCT_ID, ORG_ID)).thenReturn(Optional.of(existingProduct()));
            when(requisitionDtlRepo.countByProduct(PRODUCT_ID)).thenReturn(1L);

            assertThatThrownBy(() -> service.delete(PRODUCT_ID))
                    .isInstanceOf(ProductInUseException.class)
                    // Singular, so the message reads naturally for one row.
                    .hasMessageContaining("1 requisition line");

            verify(productRepo, never()).delete(any(PosProductInfo.class));
        }

        @Test
        @DisplayName("a product with no history is deleted, and its image with it")
        void deletesWhenNoHistory() {
            PosProductInfo product = existingProduct();
            product.setProductImage("prd_42_abc.png");
            when(productRepo.findByIdAndOrg(PRODUCT_ID, ORG_ID)).thenReturn(Optional.of(product));

            service.delete(PRODUCT_ID);

            verify(productRepo).delete(product);
            verify(imageStorage).delete("prd_42_abc.png");
        }

        @Test
        @DisplayName("deactivation stays available for a product that cannot be deleted")
        void deactivateAlwaysAllowed() {
            PosProductInfo product = existingProduct();
            when(productRepo.findByIdAndOrg(PRODUCT_ID, ORG_ID)).thenReturn(Optional.of(product));
            when(productRepo.save(any(PosProductInfo.class))).thenAnswer(i -> i.getArgument(0));
            when(productRepo.saveAndFlush(any(PosProductInfo.class))).thenAnswer(i -> i.getArgument(0));
            when(transactionDtlRepo.countByProduct(PRODUCT_ID)).thenReturn(99L);

            ProductMasterResponse result = service.changeStatus(PRODUCT_ID, "INACTIVE");

            assertThat(result.getStatus()).isEqualTo("INACTIVE");
            assertThat(result.getDeletable()).isFalse();
            assertThat(captureLastSave().getStatus()).isEqualTo("N");
        }

        @Test
        @DisplayName("the response flags a product that has history as not deletable")
        void responseCarriesDeletableFlag() {
            when(productRepo.findByIdAndOrg(PRODUCT_ID, ORG_ID)).thenReturn(Optional.of(existingProduct()));
            when(transactionDtlRepo.countByProduct(PRODUCT_ID)).thenReturn(3L);

            ProductMasterResponse result = service.getById(PRODUCT_ID);

            assertThat(result.getDeletable()).isFalse();
            assertThat(result.getDeleteBlockedReason()).contains("3 sales/purchase lines");
        }
    }

    /* ══════════════════════════════════════════════════════════════════════
       Tenant isolation
       ══════════════════════════════════════════════════════════════════════ */

    @Nested
    @DisplayName("Tenant isolation")
    class TenantIsolation {

        @Test
        @DisplayName("a product from another organization reads as not found")
        void crossTenantAccessIsNotFound() {
            // findByIdAndOrg filters on the JWT org, so a foreign id returns empty.
            when(productRepo.findByIdAndOrg(PRODUCT_ID, ORG_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getById(PRODUCT_ID))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("create stamps the org from the session, not from the payload")
        void createUsesSessionOrg() {
            stubSaveAssigningId();

            service.create(validRequest());

            assertThat(captureLastSave().getPidOrg()).isEqualTo(ORG_ID);
        }

        @Test
        @DisplayName("a session with no organization is rejected")
        void missingOrgContextIsRejected() {
            securityUtils.when(SecurityUtils::getCurrentOrgId).thenReturn(null);

            assertThatThrownBy(() -> service.getById(PRODUCT_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("No organization is associated with your session");
        }
    }

    /* ══════════════════════════════════════════════════════════════════════
       Field handling
       ══════════════════════════════════════════════════════════════════════ */

    @Nested
    @DisplayName("Field handling")
    class FieldHandling {

        @Test
        @DisplayName("name and SKU are trimmed, and a blank barcode is stored as null")
        void trimsAndNormalises() {
            ProductMasterRequest request = validRequest();
            request.setProductName("  Napa 500mg  ");
            request.setSku("  NAPA500  ");
            request.setBarcode("   ");

            stubSaveAssigningId();
            service.create(request);

            PosProductInfo saved = captureLastSave();
            assertThat(saved.getProductName()).isEqualTo("Napa 500mg");
            assertThat(saved.getSku()).isEqualTo("NAPA500");
            // Null rather than "" so the partial unique index treats it as absent.
            assertThat(saved.getBarcode()).isNull();
        }

        @Test
        @DisplayName("booleans are stored as the schema's Y/N flags")
        void mapsBooleansToYnFlags() {
            ProductMasterRequest request = validRequest();
            request.setBatchTracking(true);
            request.setExpiryTracking(false);
            request.setPrescriptionRequired(true);

            stubSaveAssigningId();
            service.create(request);

            PosProductInfo saved = captureLastSave();
            assertThat(saved.getBatchTracking()).isEqualTo("Y");
            assertThat(saved.getExpiryTracking()).isEqualTo("N");
            assertThat(saved.getPrescriptionFlag()).isEqualTo("Y");
        }

        @Test
        @DisplayName("omitted tracking flags default to N rather than null")
        void trackingFlagsDefaultToNo() {
            ProductMasterRequest request = validRequest();
            request.setBatchTracking(null);
            request.setExpiryTracking(null);
            request.setPrescriptionRequired(null);

            stubSaveAssigningId();
            service.create(request);

            PosProductInfo saved = captureLastSave();
            assertThat(saved.getBatchTracking()).isEqualTo("N");
            assertThat(saved.getExpiryTracking()).isEqualTo("N");
            assertThat(saved.getPrescriptionFlag()).isEqualTo("N");
        }

        @Test
        @DisplayName("reorder level is mirrored into the legacy ALERT_QTY column")
        void mirrorsReorderLevelIntoAlertQty() {
            // The Inventory module and findLowStockProducts() still read ALERT_QTY,
            // so leaving it stale would silently break low-stock alerts.
            ProductMasterRequest request = validRequest();
            request.setReorderLevel(new BigDecimal("150"));

            stubSaveAssigningId();
            service.create(request);

            PosProductInfo saved = captureLastSave();
            assertThat(saved.getReorderLevel()).isEqualByComparingTo("150");
            assertThat(saved.getAlertQty()).isEqualTo(150);
        }

        @Test
        @DisplayName("both unit representations are written")
        void writesUnitIdAndUnitName() {
            stubSaveAssigningId();
            service.create(validRequest());

            PosProductInfo saved = captureLastSave();
            assertThat(saved.getPidUnitId()).isEqualTo(7L);
            // Denormalised name kept for the POS/transaction modules.
            assertThat(saved.getPidUnit()).isEqualTo("PCS");
        }

        @Test
        @DisplayName("a new product defaults to ACTIVE")
        void newProductIsActive() {
            ProductMasterRequest request = validRequest();
            request.setStatus(null);

            stubSaveAssigningId();
            ProductMasterResponse created = service.create(request);

            assertThat(created.getStatus()).isEqualTo("ACTIVE");
        }
    }

    /* ══════════════════════════════════════════════════════════════════════
       Image handling
       ══════════════════════════════════════════════════════════════════════ */

    @Nested
    @DisplayName("Image handling")
    class Images {

        @Test
        @DisplayName("replacing an image removes the previous file only after the new one is saved")
        void replacingImageCleansUpTheOldFile() {
            PosProductInfo product = existingProduct();
            product.setProductImage("old.png");

            when(productRepo.findByIdAndOrg(PRODUCT_ID, ORG_ID)).thenReturn(Optional.of(product));
            when(productRepo.save(any(PosProductInfo.class))).thenAnswer(i -> i.getArgument(0));
            when(productRepo.saveAndFlush(any(PosProductInfo.class))).thenAnswer(i -> i.getArgument(0));
            when(imageStorage.store(any(), eq(PRODUCT_ID), eq(product.getPidBranch()))).thenReturn(product.getPidBranch() + "/new.png");

            service.uploadImage(PRODUCT_ID, mock(org.springframework.web.multipart.MultipartFile.class));

            InOrder order = inOrder(productRepo, imageStorage);
            order.verify(productRepo).save(any(PosProductInfo.class));
            order.verify(imageStorage).delete("old.png");
        }

        @Test
        @DisplayName("uploading clears the legacy image BLOB")
        void uploadClearsLegacyBlob() {
            PosProductInfo product = existingProduct();
            product.setImage(new byte[]{1, 2, 3});
            product.setFileMimeType("image/png");

            when(productRepo.findByIdAndOrg(PRODUCT_ID, ORG_ID)).thenReturn(Optional.of(product));
            when(productRepo.save(any(PosProductInfo.class))).thenAnswer(i -> i.getArgument(0));
            when(productRepo.saveAndFlush(any(PosProductInfo.class))).thenAnswer(i -> i.getArgument(0));
            when(imageStorage.store(any(), eq(PRODUCT_ID), eq(product.getPidBranch()))).thenReturn(product.getPidBranch() + "/new.png");

            service.uploadImage(PRODUCT_ID, mock(org.springframework.web.multipart.MultipartFile.class));

            PosProductInfo saved = captureLastSave();
            assertThat(saved.getImage()).isNull();
            assertThat(saved.getFileMimeType()).isNull();
            // In the pharmacy's own folder — the product's branch, never a client value.
            assertThat(saved.getProductImage()).isEqualTo(product.getPidBranch() + "/new.png");
        }
    }

    @Test
    @DisplayName("changeStatus rejects a value that is neither ACTIVE nor INACTIVE")
    void changeStatusRejectsUnknownValue() {
        assertThatThrownBy(() -> service.changeStatus(PRODUCT_ID, "DELETED"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Status must be ACTIVE or INACTIVE");
    }

    /* ══════════════════════════════════════════════════════════════════════
       Fixtures
       ══════════════════════════════════════════════════════════════════════ */

    private ProductMasterRequest validRequest() {
        ProductMasterRequest r = new ProductMasterRequest();
        r.setProductName("Napa 500mg");
        r.setSku("NAPA500");
        r.setGenericId(2L);
        r.setCompanyId(3L);
        r.setCategoryId(4L);
        r.setUnitId(7L);
        r.setMrp(new BigDecimal("100"));
        r.setConversionRate(new BigDecimal("25.037"));
        r.setVatPct(new BigDecimal("17.40"));
        return r;
    }

    private PosProductInfo existingProduct() {
        PosProductInfo p = new PosProductInfo();
        p.setPidProduct(PRODUCT_ID);
        p.setPidOrg(ORG_ID);
        p.setProductCode("PRD000042");
        p.setProductName("Napa 500mg");
        p.setSku("NAPA500");
        p.setStatus("Y");
        p.setStockQty(BigDecimal.TEN);
        return p;
    }

    private ValidatedReferences references() {
        PosProductUnit unit = new PosProductUnit();
        unit.setPidUnit(7L);
        unit.setUnitName("PCS");
        return new ValidatedReferences(null, null, null, null, unit, null);
    }

    /** save() assigns the sequence-generated id, as the database would. */
    private void stubSaveAssigningId() {
        when(productRepo.save(any(PosProductInfo.class))).thenAnswer(invocation -> {
            PosProductInfo p = invocation.getArgument(0);
            if (p.getPidProduct() == null) p.setPidProduct(PRODUCT_ID);
            return p;
        });
    }

    /**
     * The row the service last wrote.
     *
     * <p>[V66] An update writes two rows — the retirement of the old one and the
     * insert of the new — and both go through saveAndFlush, because the old row
     * has to reach the database before the new one is inserted or the partial
     * unique indexes reject it. The row under test is the new version, which is
     * the last one flushed. Tests about create write through plain save(), so
     * the helper looks there when nothing was flushed.
     */
    private PosProductInfo captureLastSave() {
        boolean flushed = mockingDetails(productRepo).getInvocations().stream()
                .anyMatch(i -> "saveAndFlush".equals(i.getMethod().getName()));

        ArgumentCaptor<PosProductInfo> captor = ArgumentCaptor.forClass(PosProductInfo.class);
        if (flushed) {
            verify(productRepo, atLeastOnce()).saveAndFlush(captor.capture());
        } else {
            verify(productRepo, atLeastOnce()).save(captor.capture());
        }
        return captor.getValue();
    }
}
