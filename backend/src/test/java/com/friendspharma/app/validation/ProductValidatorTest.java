package com.friendspharma.app.validation;

import com.friendspharma.app.dto.request.ProductMasterRequest;
import com.friendspharma.app.entity.*;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.InactiveEntityException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Business-rule validation for Product Master: master-data references,
 * uniqueness and the stock ladder.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductValidatorTest {

    private static final Long ORG_ID = 1L;

    @Mock private PosProductInfoRepository        productRepo;
    @Mock private PosGenericInfoRepository        genericRepo;
    @Mock private PosCompanyInfoRepository        companyRepo;
    @Mock private PosProductCategoryRepository    categoryRepo;
    @Mock private PosProductSubCategoryRepository subCategoryRepo;
    @Mock private PosProductUnitRepository        unitRepo;
    @Mock private PosBoxInfoRepository            boxRepo;

    @InjectMocks private ProductValidator validator;

    @BeforeEach
    void setUp() {
        when(genericRepo.findById(anyLong())).thenReturn(Optional.of(generic("Y")));
        when(companyRepo.findById(anyLong())).thenReturn(Optional.of(company("Y")));
        when(categoryRepo.findById(anyLong())).thenReturn(Optional.of(category("Y")));
        when(unitRepo.findById(anyLong())).thenReturn(Optional.of(unit("Y")));

        when(productRepo.existsByBarcodeIgnoreCase(anyLong(), anyString(), anyLong())).thenReturn(false);
        when(productRepo.existsByBusinessKey(anyLong(), anyString(), anyLong(), anyLong(), anyLong())).thenReturn(false);
    }

    @Test
    @DisplayName("a well-formed request passes")
    void validRequestPasses() {
        assertThatCode(() -> validator.validate(validRequest(), ORG_ID, null))
                .doesNotThrowAnyException();
    }

    /* ══════════════════════════════════════════════════════════════════════
       Master data
       ══════════════════════════════════════════════════════════════════════ */

    @Nested
    @DisplayName("Master-data references")
    class MasterData {

        @Test
        @DisplayName("a missing generic is rejected")
        void missingGenericRejected() {
            when(genericRepo.findById(anyLong())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> validator.validate(validRequest(), ORG_ID, null))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Generic");
        }

        @Test
        @DisplayName("an inactive generic is rejected")
        void inactiveGenericRejected() {
            when(genericRepo.findById(anyLong())).thenReturn(Optional.of(generic("N")));

            assertThatThrownBy(() -> validator.validate(validRequest(), ORG_ID, null))
                    .isInstanceOf(InactiveEntityException.class)
                    .hasMessageContaining("Generic");
        }

        @Test
        @DisplayName("an inactive company is rejected")
        void inactiveCompanyRejected() {
            when(companyRepo.findById(anyLong())).thenReturn(Optional.of(company("N")));

            assertThatThrownBy(() -> validator.validate(validRequest(), ORG_ID, null))
                    .isInstanceOf(InactiveEntityException.class)
                    .hasMessageContaining("Company");
        }

        @Test
        @DisplayName("an inactive category is rejected")
        void inactiveCategoryRejected() {
            when(categoryRepo.findById(anyLong())).thenReturn(Optional.of(category("N")));

            assertThatThrownBy(() -> validator.validate(validRequest(), ORG_ID, null))
                    .isInstanceOf(InactiveEntityException.class)
                    .hasMessageContaining("Category");
        }

        @Test
        @DisplayName("an inactive unit is rejected")
        void inactiveUnitRejected() {
            when(unitRepo.findById(anyLong())).thenReturn(Optional.of(unit("N")));

            assertThatThrownBy(() -> validator.validate(validRequest(), ORG_ID, null))
                    .isInstanceOf(InactiveEntityException.class)
                    .hasMessageContaining("Unit");
        }

        @Test
        @DisplayName("a master row belonging to another organization reads as not found")
        void crossTenantReferenceRejected() {
            // Reported as not-found rather than forbidden, so the API does not
            // confirm that another organization's generic exists.
            PosGenericInfo foreign = generic("Y");
            foreign.setPidOrg(999L);
            when(genericRepo.findById(anyLong())).thenReturn(Optional.of(foreign));

            assertThatThrownBy(() -> validator.validate(validRequest(), ORG_ID, null))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("not available for your organization");
        }

        @Test
        @DisplayName("a sub-category from a different category is rejected")
        void subCategoryMustBelongToCategory() {
            PosProductSubCategory sub = new PosProductSubCategory();
            sub.setPidSubCategory(9L);
            sub.setSubCategoryName("Tablets");
            sub.setPidCategory(88L);           // request says category 4
            sub.setStatus("Y");
            when(subCategoryRepo.findById(anyLong())).thenReturn(Optional.of(sub));

            ProductMasterRequest request = validRequest();
            request.setSubCategoryId(9L);

            assertThatThrownBy(() -> validator.validate(request, ORG_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("does not belong to the selected category");
        }

        @Test
        @DisplayName("box is optional")
        void boxIsOptional() {
            ProductMasterRequest request = validRequest();
            request.setBoxId(null);

            assertThatCode(() -> validator.validate(request, ORG_ID, null))
                    .doesNotThrowAnyException();
        }
    }

    /* ══════════════════════════════════════════════════════════════════════
       Uniqueness
       ══════════════════════════════════════════════════════════════════════ */

    @Nested
    @DisplayName("Uniqueness")
    class Uniqueness {

        @Test
        @DisplayName("[V72] a duplicate SKU is accepted — SKU is not unique")
        void duplicateSkuAccepted() {
            /* SKU is a shelf or supplier reference, not an identity: two products
               may carry the same one. The unique index was dropped in V72 and the
               probe with it, so nothing here asks. Asserted rather than deleted,
               because "we stopped checking" is exactly the kind of change that is
               quietly put back by somebody tidying up. */
            assertThatCode(() -> validator.validate(validRequest(), ORG_ID, null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a duplicate barcode is rejected with PRODUCT_BARCODE_EXISTS")
        void duplicateBarcodeRejected() {
            when(productRepo.existsByBarcodeIgnoreCase(anyLong(), anyString(), anyLong())).thenReturn(true);

            ProductMasterRequest request = validRequest();
            request.setBarcode("1234567890123");

            assertThatThrownBy(() -> validator.validate(request, ORG_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo("PRODUCT_BARCODE_EXISTS");
        }

        @Test
        @DisplayName("an empty barcode skips the uniqueness check")
        void blankBarcodeSkipsCheck() {
            // Otherwise every product without a barcode would collide with the
            // next one, since they would all share the empty string.
            when(productRepo.existsByBarcodeIgnoreCase(anyLong(), anyString(), anyLong())).thenReturn(true);

            ProductMasterRequest request = validRequest();
            request.setBarcode("   ");

            assertThatCode(() -> validator.validate(request, ORG_ID, null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("name + company + generic is the duplicate-product key")
        void duplicateBusinessKeyRejected() {
            when(productRepo.existsByBusinessKey(anyLong(), anyString(), anyLong(), anyLong(), anyLong()))
                    .thenReturn(true);

            assertThatThrownBy(() -> validator.validate(validRequest(), ORG_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("already exists for the selected company and generic")
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo("PRODUCT_DUPLICATE");
        }

        @Test
        @DisplayName("on update the product is excluded from its own uniqueness checks")
        void updateExcludesSelf() {
            // The id under edit is passed as excludeId, so "this barcode is
            // already taken — by me" cannot happen. Demonstrated on barcode,
            // which is still unique; SKU no longer has a check to exclude from.
            ProductMasterRequest request = validRequest();
            request.setBarcode("1234567890123");

            // Taken by somebody else (excludeId -1, the create case) but not by
            // this row (excludeId 42) — so editing it must not trip its own guard.
            when(productRepo.existsByBarcodeIgnoreCase(ORG_ID, "1234567890123", 42L)).thenReturn(false);
            when(productRepo.existsByBarcodeIgnoreCase(ORG_ID, "1234567890123", -1L)).thenReturn(true);

            assertThatCode(() -> validator.validate(request, ORG_ID, 42L))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> validator.validate(request, ORG_ID, null))
                    .isInstanceOf(BusinessException.class);
        }
    }

    /* ══════════════════════════════════════════════════════════════════════
       Stock ladder
       ══════════════════════════════════════════════════════════════════════ */

    @Nested
    @DisplayName("Stock ladder — minimum <= safety <= reorder <= maximum")
    class StockLadder {

        @Test
        @DisplayName("a correctly ordered ladder passes")
        void validLadderPasses() {
            assertThatCode(() -> validator.validate(
                    withStock("20", "50", "100", "500"), ORG_ID, null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("minimum above safety is rejected")
        void minimumAboveSafetyRejected() {
            assertThatThrownBy(() -> validator.validate(
                    withStock("60", "50", "100", "500"), ORG_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Minimum stock cannot be greater than safety stock");
        }

        @Test
        @DisplayName("safety above reorder is rejected")
        void safetyAboveReorderRejected() {
            assertThatThrownBy(() -> validator.validate(
                    withStock("20", "150", "100", "500"), ORG_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Safety stock cannot be greater than the reorder level");
        }

        @Test
        @DisplayName("reorder above maximum is rejected")
        void reorderAboveMaximumRejected() {
            assertThatThrownBy(() -> validator.validate(
                    withStock("20", "50", "600", "500"), ORG_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Reorder level cannot be greater than maximum stock");
        }

        @Test
        @DisplayName("equal values are allowed at every step")
        void equalValuesAllowed() {
            assertThatCode(() -> validator.validate(
                    withStock("100", "100", "100", "100"), ORG_ID, null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a maximum of zero means no ceiling, not a ceiling of zero")
        void zeroMaximumMeansUnset() {
            // Without this, any product that sets a reorder level but no maximum
            // would fail — which is the common case.
            assertThatCode(() -> validator.validate(
                    withStock("20", "50", "100", "0"), ORG_ID, null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("omitted stock levels are treated as zero and pass")
        void nullLevelsPass() {
            assertThatCode(() -> validator.validate(validRequest(), ORG_ID, null))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Pricing inputs")
    class PricingInputs {

        @Test
        @DisplayName("a conversion rate of 100% or more is rejected")
        void fullConversionRateRejected() {
            // It would drive trade price — and therefore IGTA and buy price — to
            // zero, which is a typo far more often than an intent.
            ProductMasterRequest request = validRequest();
            request.setConversionRate(new BigDecimal("100"));

            assertThatThrownBy(() -> validator.validate(request, ORG_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("reduce the trade price to zero");
        }

        @Test
        @DisplayName("a conversion rate just under 100 is allowed")
        void justUnderFullRateAllowed() {
            ProductMasterRequest request = validRequest();
            request.setConversionRate(new BigDecimal("99.99"));

            assertThatCode(() -> validator.validate(request, ORG_ID, null))
                    .doesNotThrowAnyException();
        }
    }

    /* ── Fixtures ────────────────────────────────────────────────────────── */

    private ProductMasterRequest validRequest() {
        ProductMasterRequest r = new ProductMasterRequest();
        r.setProductName("Napa 500mg");
        r.setSku("NAPA500");
        r.setGenericId(2L);
        r.setCompanyId(3L);
        r.setCategoryId(4L);
        r.setUnitId(7L);
        r.setMrp(new BigDecimal("100"));
        return r;
    }

    private ProductMasterRequest withStock(String min, String safety, String reorder, String max) {
        ProductMasterRequest r = validRequest();
        r.setMinimumStock(new BigDecimal(min));
        r.setSafetyStock(new BigDecimal(safety));
        r.setReorderLevel(new BigDecimal(reorder));
        r.setMaximumStock(new BigDecimal(max));
        return r;
    }

    private static PosGenericInfo generic(String status) {
        PosGenericInfo g = new PosGenericInfo();
        g.setPidGeneric(2L);
        g.setPidOrg(ORG_ID);
        g.setGenericName("Paracetamol");
        g.setStatus(status);
        return g;
    }

    private static PosCompanyInfo company(String status) {
        PosCompanyInfo c = new PosCompanyInfo();
        c.setPidCompany(3L);
        c.setPidOrg(ORG_ID);
        c.setCompanyName("Beximco Pharma");
        c.setStatus(status);
        return c;
    }

    private static PosProductCategory category(String status) {
        PosProductCategory c = new PosProductCategory();
        c.setPidCategory(4L);
        c.setPidOrg(ORG_ID);
        c.setCategoryName("Analgesic");
        c.setStatus(status);
        return c;
    }

    private static PosProductUnit unit(String status) {
        PosProductUnit u = new PosProductUnit();
        u.setPidUnit(7L);
        u.setPidOrg(ORG_ID);
        u.setUnitName("PCS");
        u.setStatus(status);
        return u;
    }
}
