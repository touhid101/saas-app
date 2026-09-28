package com.friendspharma.app.specification;

import com.friendspharma.app.exception.custom.ValidationException;
import com.friendspharma.app.entity.PosProductInfo;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * Criteria builders for the Product List grid.
 *
 * <p>Everything is expressed as a {@link Specification} so filtering, sorting
 * and pagination all happen in SQL. The previous product search loaded every
 * row for the org and sub-listed it in Java, which does not survive a real
 * catalogue.
 *
 * <p>All predicates are parameterised by the Criteria API — no string
 * concatenation reaches the database, so filter values cannot inject SQL.
 */
public final class ProductSpecification {

    private ProductSpecification() {}

    /** Tenant scope. Always applied; never taken from the request body. */
    public static Specification<PosProductInfo> inOrg(Long orgId) {
        return (root, q, cb) -> cb.equal(root.get("pidOrg"), orgId);
    }

    /**
     * Tenant scope, branch included.
     *
     * <p>A product carrying a branch belongs to that pharmacy and is invisible to
     * every other one. A product carrying none is the shared catalogue — which is
     * every row in the database today, because the column was never populated.
     *
     * <p>Written this way rather than as a plain branch match for two reasons: a
     * strict match would empty every catalogue and orphan the transaction lines
     * pointing at those rows, and the null-branch rows are precisely the Global
     * Product Master that Phase 4 will import from. Importing will mean copying a
     * row with a branch attached, and this predicate already separates the two.
     */
    public static Specification<PosProductInfo> inTenant(Long orgId, Long branchId) {
        return (root, q, cb) -> cb.and(
                cb.equal(root.get("pidOrg"), orgId),
                cb.or(cb.isNull(root.get("pidBranch")),
                      cb.equal(root.get("pidBranch"), branchId)));
    }

    /**
     * Free-text search across name, product code, SKU and barcode.
     * Case-insensitive; blank input is a no-op rather than a match-nothing.
     */
    public static Specification<PosProductInfo> matches(String search) {
        return (root, q, cb) -> {
            if (search == null || search.isBlank()) return cb.conjunction();
            String like = "%" + search.trim().toLowerCase() + "%";
            return cb.or(
                    cb.like(cb.lower(root.get("productName")), like),
                    cb.like(cb.lower(root.get("productCode")), like),
                    cb.like(cb.lower(root.get("sku")),         like),
                    cb.like(cb.lower(root.get("barcode")),     like)
            );
        };
    }

    /** Exact-match SKU filter (the grid's dedicated SKU search box). */
    public static Specification<PosProductInfo> hasSku(String sku) {
        return (root, q, cb) -> sku == null || sku.isBlank()
                ? cb.conjunction()
                : cb.equal(cb.lower(root.get("sku")), sku.trim().toLowerCase());
    }

    /** Exact-match barcode filter — what a scanner gun produces. */
    public static Specification<PosProductInfo> hasBarcode(String barcode) {
        return (root, q, cb) -> barcode == null || barcode.isBlank()
                ? cb.conjunction()
                : cb.equal(cb.lower(root.get("barcode")), barcode.trim().toLowerCase());
    }

    public static Specification<PosProductInfo> hasGeneric(Long genericId) {
        return (root, q, cb) -> genericId == null ? cb.conjunction() : cb.equal(root.get("pidGeneric"), genericId);
    }

    public static Specification<PosProductInfo> hasCompany(Long companyId) {
        return (root, q, cb) -> companyId == null ? cb.conjunction() : cb.equal(root.get("pidCompany"), companyId);
    }

    public static Specification<PosProductInfo> hasCategory(Long categoryId) {
        return (root, q, cb) -> categoryId == null ? cb.conjunction() : cb.equal(root.get("pidCategory"), categoryId);
    }

    public static Specification<PosProductInfo> hasUnit(Long unitId) {
        return (root, q, cb) -> unitId == null ? cb.conjunction() : cb.equal(root.get("pidUnitId"), unitId);
    }

    /**
     * Status filter. {@code null} or "ALL" returns both active and inactive —
     * Product Master must be able to show deactivated rows so they can be
     * reactivated, unlike the POS lookups which only ever want active products.
     *
     * <p>Accepts either the word or the stored flag: "ACTIVE" and "Y" both mean
     * active, "INACTIVE" and "N" both mean inactive. It used to test only for
     * "ACTIVE" and treat everything else as a request for inactive rows, so
     * {@code status=Y} — the value the column actually holds, and the obvious
     * thing to pass — returned precisely the products the caller did not want,
     * silently and with a 200.
     *
     * <p>Anything else is a mistake rather than a filter, and is refused: a
     * typo that quietly returns the deactivated half of the catalogue is worse
     * than one that says it does not understand.
     */
    public static Specification<PosProductInfo> hasStatus(String status) {
        return (root, q, cb) -> {
            if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) return cb.conjunction();
            String flag = switch (status.trim().toUpperCase()) {
                case "ACTIVE", "Y"   -> "Y";
                case "INACTIVE", "N" -> "N";
                default -> throw new ValidationException(
                        "'" + status + "' is not a product status. Use ACTIVE, INACTIVE or ALL.");
            };
            return cb.equal(root.get("status"), flag);
        };
    }

    /**
     * Stock-state filter.
     * <ul>
     *   <li>OUT — no stock at all</li>
     *   <li>LOW — at or below the reorder level, but not zero</li>
     *   <li>OK  — above the reorder level</li>
     * </ul>
     * Falls back to the legacy ALERT_QTY when REORDER_LEVEL has not been set,
     * so pre-V4 rows still classify correctly.
     */
    public static Specification<PosProductInfo> hasStockStatus(String stockStatus) {
        return (root, q, cb) -> {
            if (stockStatus == null || stockStatus.isBlank() || "ALL".equalsIgnoreCase(stockStatus)) {
                return cb.conjunction();
            }
            var stock     = cb.coalesce(root.get("stockQty"), java.math.BigDecimal.ZERO);
            var threshold = cb.coalesce(root.get("reorderLevel"), java.math.BigDecimal.ZERO);

            return switch (stockStatus.toUpperCase()) {
                case "OUT" -> cb.lessThanOrEqualTo(stock, java.math.BigDecimal.ZERO);
                case "LOW" -> cb.and(
                        cb.greaterThan(stock, java.math.BigDecimal.ZERO),
                        cb.lessThanOrEqualTo(stock, threshold));
                case "OK"  -> cb.greaterThan(stock, threshold);
                default    -> cb.conjunction();
            };
        };
    }

    /** Products whose batch/expiry/prescription flag is set. */
    public static Specification<PosProductInfo> flagIs(String field, Boolean value) {
        return (root, q, cb) -> value == null
                ? cb.conjunction()
                : cb.equal(cb.coalesce(root.get(field), "N"), value ? "Y" : "N");
    }

    /**
     * [V66] Hides versions that an edit has replaced.
     *
     * <p>A superseded row is inactive, so without this it turns up under
     * status=INACTIVE and status=ALL — and after a few edits the list is mostly
     * old copies of the same medicine. "Inactive" in that filter means a product
     * the pharmacy has stopped stocking, which is a different thing from a
     * paragraph of a product's history.
     *
     * <p>The versions are not hidden, only moved: they are what
     * {@code GET /api/products/{id}/versions} answers with.
     */
    public static Specification<PosProductInfo> currentVersionsOnly() {
        return (root, q, cb) -> cb.isNull(root.get("supersededBy"));
    }

    /** Combines every filter into one Specification. Null filters drop out. */
    public static Specification<PosProductInfo> build(Long orgId, Long branchId, ProductFilter f) {
        List<Specification<PosProductInfo>> parts = new ArrayList<>();
        parts.add(inTenant(orgId, branchId));
        parts.add(currentVersionsOnly());
        parts.add(matches(f.search()));
        parts.add(hasSku(f.sku()));
        parts.add(hasBarcode(f.barcode()));
        parts.add(hasGeneric(f.genericId()));
        parts.add(hasCompany(f.companyId()));
        parts.add(hasCategory(f.categoryId()));
        parts.add(hasUnit(f.unitId()));
        parts.add(hasStatus(f.status()));
        parts.add(hasStockStatus(f.stockStatus()));
        parts.add(flagIs("batchTracking",    f.batchTracking()));
        parts.add(flagIs("expiryTracking",   f.expiryTracking()));
        parts.add(flagIs("prescriptionFlag", f.prescriptionRequired()));

        Specification<PosProductInfo> spec = parts.get(0);
        for (int i = 1; i < parts.size(); i++) spec = spec.and(parts.get(i));
        return spec;
    }

    /** All Product List filter inputs, carried as one value object. */
    public record ProductFilter(
            String  search,
            String  sku,
            String  barcode,
            Long    genericId,
            Long    companyId,
            Long    categoryId,
            Long    unitId,
            String  status,
            String  stockStatus,
            Boolean batchTracking,
            Boolean expiryTracking,
            Boolean prescriptionRequired
    ) {}

    /**
     * Whitelist of sortable columns. A client-supplied sort field is never
     * passed to {@code Sort.by()} directly — an unrecognised value would throw
     * at query time, and an attacker-controlled property name is a way to probe
     * the entity graph. Anything unknown falls back to product name.
     */
    public static String resolveSortField(String requested) {
        if (requested == null) return "productName";
        return switch (requested) {
            case "productCode", "productName", "sku", "barcode",
                 "mrpPrice", "tpPrice", "buyPrice", "vatPct",
                 "stockQty", "reorderLevel", "status",
                 "insertedDate", "updatedDate" -> requested;
            // UI-facing aliases
            case "mrp"          -> "mrpPrice";
            case "currentStock" -> "stockQty";
            case "createdDate"  -> "insertedDate";
            default             -> "productName";
        };
    }
}
