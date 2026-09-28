package com.friendspharma.app.service.impl;

import com.friendspharma.app.dto.request.ProductBulkRequest;
import com.friendspharma.app.dto.request.ProductBulkRequest.Row;
import com.friendspharma.app.dto.request.ProductMasterRequest;
import com.friendspharma.app.dto.response.ProductBulkResultResponse;
import com.friendspharma.app.dto.response.ProductBulkResultResponse.RowResult;
import com.friendspharma.app.dto.response.ProductMasterResponse;
import com.friendspharma.app.entity.*;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.repository.*;
import com.friendspharma.app.security.SecurityUtils;
import com.friendspharma.app.service.interfaces.ProductBulkService;
import com.friendspharma.app.service.interfaces.ProductService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Excel bulk import.
 *
 * <p>The spreadsheet is parsed in the browser and posted here as JSON. That
 * parsing is transport only — every row still goes through
 * {@link ProductService#create}, so the same validation, pricing calculation and
 * code generation apply whether a product arrives from the form or from a file.
 * Nothing about the import path is more trusting than the single-product path.
 *
 * <p>Each row is processed in its own transaction. {@code ProductService} is
 * injected as a proxy rather than called internally, so a row that fails rolls
 * back alone and leaves the rows around it committed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductBulkServiceImpl implements ProductBulkService {

    private static final int MAX_ROWS = 2000;

    private final ProductService                  productService;
    private final BranchScopeService branchScope;
    private final PosProductInfoRepository        productRepo;
    private final PosGenericInfoRepository        genericRepo;
    private final PosCompanyInfoRepository        companyRepo;
    private final PosProductCategoryRepository    categoryRepo;
    private final PosProductSubCategoryRepository subCategoryRepo;
    private final PosProductUnitRepository        unitRepo;
    private final PosBoxInfoRepository            boxRepo;
    private final Validator                       beanValidator;

    @Override
    public ProductBulkResultResponse importRows(ProductBulkRequest request) {
        Long orgId = SecurityUtils.getCurrentOrgId();
        if (orgId == null) {
            throw new BusinessException("No organization is associated with your session. Please log in again.",
                    HttpStatus.UNAUTHORIZED, "NO_ORG_CONTEXT");
        }

        List<Row> rows = request.getRows() == null ? List.of() : request.getRows();
        if (rows.isEmpty()) {
            throw new BusinessException("The file contains no data rows.",
                    HttpStatus.BAD_REQUEST, "EMPTY_IMPORT");
        }
        if (rows.size() > MAX_ROWS) {
            throw new BusinessException("A single import is limited to " + MAX_ROWS
                    + " rows. Please split the file.", HttpStatus.BAD_REQUEST, "IMPORT_TOO_LARGE");
        }

        boolean upsert = "UPSERT".equalsIgnoreCase(request.getMode());

        // Master data is small and read many times; resolve it once per import
        // rather than once per row.
        NameIndex names = NameIndex.build(orgId, branch(), genericRepo, companyRepo,
                categoryRepo, subCategoryRepo, unitRepo, boxRepo);

        // Duplicate SKUs *within the file* would otherwise surface as a
        // confusing "already exists" against a row the same upload just created.
        Set<String> seenSkus = new HashSet<>();

        List<RowResult> results = new ArrayList<>(rows.size());
        int created = 0, updated = 0, failed = 0;

        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int rowNumber = row.getRowNumber() != null ? row.getRowNumber() : i + 1;
            String sku = trim(row.getSku());

            try {
                if (isBlankRow(row)) {
                    results.add(RowResult.builder()
                            .rowNumber(rowNumber).status("SKIPPED")
                            .error("Empty row — skipped.").build());
                    continue;
                }

                if (sku != null && !seenSkus.add(sku.toUpperCase(Locale.ROOT))) {
                    throw new BusinessException("SKU '" + sku + "' appears more than once in this file.",
                            HttpStatus.CONFLICT, "DUPLICATE_IN_FILE");
                }

                ProductMasterRequest req = toRequest(row, names);
                assertBeanValid(req);

                /* [V72] SKU is no longer unique, so the match may return more
                   than one row. The importer takes the lowest id — the same row
                   on every run — rather than failing the whole file over data
                   the system now accepts. */
                Optional<PosProductInfo> existing = sku == null ? Optional.empty()
                        : productRepo.findAllBySkuAndPidOrg(sku, orgId).stream().findFirst();

                ProductMasterResponse saved;
                String status;
                if (existing.isPresent()) {
                    if (!upsert) {
                        throw new BusinessException("SKU '" + sku + "' is already used by "
                                + "\"" + existing.get().getProductName() + "\". "
                                + "Choose \"Update existing\" to overwrite it, or clear the SKU "
                                + "column — it is optional and no longer has to be unique.",
                                HttpStatus.CONFLICT, "PRODUCT_SKU_EXISTS");
                    }
                    saved = productService.update(existing.get().getPidProduct(), req,
                            new ProductService.PriceOverride(row.getIgta(), row.getBuyPrice()));
                    status = "UPDATED";
                    updated++;
                } else {
                    saved = productService.create(req,
                            new ProductService.PriceOverride(row.getIgta(), row.getBuyPrice()));
                    status = "CREATED";
                    created++;
                }

                results.add(RowResult.builder()
                        .rowNumber(rowNumber).sku(saved.getSku())
                        .productName(saved.getProductName())
                        .status(status).productCode(saved.getProductCode())
                        .build());

            } catch (BusinessException ex) {
                failed++;
                results.add(RowResult.builder()
                        .rowNumber(rowNumber).sku(sku).productName(trim(row.getProductName()))
                        .status("FAILED").error(ex.getMessage()).build());
            } catch (Exception ex) {
                // A row must never take the whole import down.
                failed++;
                log.warn("Bulk import row {} failed unexpectedly", rowNumber, ex);
                results.add(RowResult.builder()
                        .rowNumber(rowNumber).sku(sku).productName(trim(row.getProductName()))
                        .status("FAILED")
                        .error("Could not import this row: " + ex.getMessage()).build());
            }
        }

        log.info("Bulk import finished for org {}: {} created, {} updated, {} failed of {} rows",
                orgId, created, updated, failed, rows.size());

        return ProductBulkResultResponse.builder()
                .totalRows(rows.size()).created(created).updated(updated).failed(failed)
                .results(results).build();
    }

    /* ── Row -> request mapping ──────────────────────────────────────────── */

    private ProductMasterRequest toRequest(Row row, NameIndex names) {
        ProductMasterRequest req = new ProductMasterRequest();

        req.setProductName(trim(row.getProductName()));
        req.setSku(trim(row.getSku()));
        req.setBarcode(trim(row.getBarcode()));

        req.setGenericId(names.generic(row.getGenericName()));
        req.setCompanyId(names.company(row.getCompanyName()));
        req.setCategoryId(names.category(row.getCategoryName()));
        req.setUnitId(names.unit(row.getUnitName()));

        // Optional references: a blank cell means "none", but a value that does
        // not match any master row is a mistake worth reporting rather than
        // silently dropping.
        if (notBlank(row.getSubCategoryName())) {
            req.setSubCategoryId(names.subCategory(row.getSubCategoryName()));
            if (req.getSubCategoryId() == null) {
                throw unknown("Sub-Category", row.getSubCategoryName());
            }
        }
        if (notBlank(row.getBoxName())) {
            req.setBoxId(names.box(row.getBoxName()));
            if (req.getBoxId() == null) throw unknown("Box", row.getBoxName());
        }

        if (req.getGenericId()  == null) throw unknown("Generic",  row.getGenericName());
        if (req.getCompanyId()  == null) throw unknown("Company",  row.getCompanyName());
        if (req.getCategoryId() == null) throw unknown("Category", row.getCategoryName());
        if (req.getUnitId()     == null) throw unknown("Unit",     row.getUnitName());

        req.setMrp(row.getMrp());
        req.setConversionRate(row.getConversionRate());
        req.setVatPct(row.getVatPct());
        req.setDiscountPct(row.getDiscountPct());

        req.setReorderLevel(row.getReorderLevel());
        req.setSafetyStock(row.getSafetyStock());
        req.setMinimumStock(row.getMinimumStock());
        req.setMaximumStock(row.getMaximumStock());

        req.setBatchTracking(toBool(row.getBatchTracking()));
        req.setExpiryTracking(toBool(row.getExpiryTracking()));
        req.setPrescriptionRequired(toBool(row.getPrescriptionRequired()));

        String status = trim(row.getStatus());
        req.setStatus(status == null ? "ACTIVE"
                : ("INACTIVE".equalsIgnoreCase(status) || "N".equalsIgnoreCase(status)
                   || "NO".equalsIgnoreCase(status) ? "INACTIVE" : "ACTIVE"));

        return req;
    }

    /**
     * Runs the same Bean Validation the controller applies to a form submission,
     * so a spreadsheet cannot bypass @NotBlank / @DecimalMin by arriving through
     * a different endpoint.
     */
    private void assertBeanValid(ProductMasterRequest req) {
        Set<ConstraintViolation<ProductMasterRequest>> violations = beanValidator.validate(req);
        if (violations.isEmpty()) return;

        String message = violations.stream()
                .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                .map(ConstraintViolation::getMessage)
                .distinct()
                .collect(Collectors.joining(" "));
        throw new BusinessException(message, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    private BusinessException unknown(String label, String value) {
        String shown = (value == null || value.isBlank()) ? "(blank)" : value.trim();
        return new BusinessException(
                label + " '" + shown + "' does not match any active " + label.toLowerCase()
                        + ". Check the Reference sheet in the template.",
                HttpStatus.BAD_REQUEST, "UNKNOWN_MASTER_DATA");
    }

    /** A row is blank when it carries neither a name nor a SKU. */
    private boolean isBlankRow(Row row) {
        return !notBlank(row.getProductName()) && !notBlank(row.getSku());
    }

    /** Accepts the many things a person types for yes: Y, Yes, TRUE, 1. */
    private Boolean toBool(String v) {
        if (v == null) return Boolean.FALSE;
        String t = v.trim();
        if (t.isEmpty()) return Boolean.FALSE;
        return t.equalsIgnoreCase("Y") || t.equalsIgnoreCase("YES")
            || t.equalsIgnoreCase("TRUE") || t.equals("1");
    }

    private static boolean notBlank(String v) { return v != null && !v.trim().isEmpty(); }
    private static String  trim(String v)     { return notBlank(v) ? v.trim() : null; }

    /**
     * Case-insensitive name → id lookup for every master table the import
     * touches, built once per import.
     */
    private record NameIndex(Map<String, Long> generics,
                             Map<String, Long> companies,
                             Map<String, Long> categories,
                             Map<String, Long> subCategories,
                             Map<String, Long> units,
                             Map<String, Long> boxes) {

        static NameIndex build(Long orgId, Long branchId,
                               PosGenericInfoRepository genericRepo,
                               PosCompanyInfoRepository companyRepo,
                               PosProductCategoryRepository categoryRepo,
                               PosProductSubCategoryRepository subCategoryRepo,
                               PosProductUnitRepository unitRepo,
                               PosBoxInfoRepository boxRepo) {
            return new NameIndex(
                    index(genericRepo.findByPidOrgAndPidBranchAndStatusOrderByGenericName(orgId, branchId, "Y"),
                            PosGenericInfo::getGenericName, PosGenericInfo::getPidGeneric),
                    index(companyRepo.findByPidOrgAndPidBranchAndStatusOrderByCompanyName(orgId, branchId, "Y"),
                            PosCompanyInfo::getCompanyName, PosCompanyInfo::getPidCompany),
                    index(categoryRepo.findByPidOrgAndPidBranchAndStatusOrderByCategoryName(orgId, branchId, "Y"),
                            PosProductCategory::getCategoryName, PosProductCategory::getPidCategory),
                    index(subCategoryRepo.findByPidOrgAndPidBranchAndStatusOrderBySubCategoryName(orgId, branchId, "Y"),
                            PosProductSubCategory::getSubCategoryName, PosProductSubCategory::getPidSubCategory),
                    index(unitRepo.findByPidOrgAndPidBranchAndStatusOrderByUnitName(orgId, branchId, "Y"),
                            PosProductUnit::getUnitName, PosProductUnit::getPidUnit),
                    index(boxRepo.findByPidOrgAndPidBranchAndStatusOrderByBoxSizeTitle(orgId, branchId, "Y"),
                            PosBoxInfo::getBoxSizeTitle, PosBoxInfo::getPidBox));
        }

        private static <T> Map<String, Long> index(List<T> rows,
                                                   Function<T, String> name,
                                                   Function<T, Long> id) {
            Map<String, Long> map = new HashMap<>();
            for (T row : rows) {
                String key = name.apply(row);
                if (key != null) map.putIfAbsent(key.trim().toUpperCase(Locale.ROOT), id.apply(row));
            }
            return map;
        }

        private static Long get(Map<String, Long> map, String value) {
            return (value == null || value.isBlank()) ? null
                    : map.get(value.trim().toUpperCase(Locale.ROOT));
        }

        Long generic(String v)     { return get(generics, v); }
        Long company(String v)     { return get(companies, v); }
        Long category(String v)    { return get(categories, v); }
        Long subCategory(String v) { return get(subCategories, v); }
        Long unit(String v)        { return get(units, v); }
        Long box(String v)         { return get(boxes, v); }
    }

    /**
     * The pharmacy whose master data this request may see.
     *
     * <p>Deliberately not {@code SecurityUtils.getCurrentBranchId()}: that
     * returns null where an account has no branch, and a null branch widens the
     * lookups back to every pharmacy in the organisation.
     */
    private Long branch() { return branchScope.resolve(null); }
}
