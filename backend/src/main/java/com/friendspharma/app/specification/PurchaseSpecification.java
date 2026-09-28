package com.friendspharma.app.specification;

import com.friendspharma.app.entity.PosTransactionMst;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Criteria builders for the purchase history grid.
 *
 * <p>Filtering, sorting and pagination all happen in SQL. Everything is built
 * through the Criteria API, so no filter value is ever concatenated into a
 * query string.
 */
public final class PurchaseSpecification {

    /** This module only ever deals with purchase transactions. */
    public static final String TYPE_PURCHASE = "PU";

    private PurchaseSpecification() {}

    /** Tenant scope. Always applied; never taken from the request. */
    public static Specification<PosTransactionMst> inOrg(Long orgId) {
        return (root, q, cb) -> cb.equal(root.get("pidOrg"), orgId);
    }

    public static Specification<PosTransactionMst> isPurchase() {
        return (root, q, cb) -> cb.equal(root.get("transactionType"), TYPE_PURCHASE);
    }

    /** Free-text over invoice number, challan number and batch reference. */
    public static Specification<PosTransactionMst> matches(String search) {
        return (root, q, cb) -> {
            if (search == null || search.isBlank()) return cb.conjunction();
            String like = "%" + search.trim().toLowerCase() + "%";
            return cb.or(
                    cb.like(cb.lower(root.get("invoiceNo")), like),
                    cb.like(cb.lower(root.get("challanNo")), like),
                    cb.like(cb.lower(root.get("batchNo")),   like),
                    cb.like(cb.lower(root.get("remarks")),   like));
        };
    }

    public static Specification<PosTransactionMst> hasSupplier(Long supplierId) {
        return (root, q, cb) -> supplierId == null ? cb.conjunction()
                : cb.equal(root.get("pidSupCust"), supplierId);
    }

    public static Specification<PosTransactionMst> hasBranch(Long branchId) {
        return (root, q, cb) -> branchId == null ? cb.conjunction()
                : cb.equal(root.get("pidBranch"), branchId);
    }

    /**
     * Status filter. Accepts either the stored code (D/P/A) or the spelled-out
     * label, so a UI can send whichever it holds.
     */
    public static Specification<PosTransactionMst> hasStatus(String status) {
        return (root, q, cb) -> {
            if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) return cb.conjunction();
            return cb.equal(root.get("status"), normaliseStatus(status));
        };
    }

    public static Specification<PosTransactionMst> hasPurchaseType(String purchaseType) {
        return (root, q, cb) -> purchaseType == null || purchaseType.isBlank()
                || "ALL".equalsIgnoreCase(purchaseType)
                ? cb.conjunction()
                : cb.equal(root.get("purchaseType"), purchaseType.toUpperCase());
    }

    public static Specification<PosTransactionMst> hasProductType(String productType) {
        return (root, q, cb) -> productType == null || productType.isBlank()
                || "ALL".equalsIgnoreCase(productType)
                ? cb.conjunction()
                : cb.equal(root.get("productType"), productType.toUpperCase());
    }

    /** Inclusive date window; either end may be omitted. */
    public static Specification<PosTransactionMst> dateBetween(LocalDate from, LocalDate to) {
        return (root, q, cb) -> {
            if (from == null && to == null) return cb.conjunction();
            if (from == null) return cb.lessThanOrEqualTo(root.get("transactionDate"), to);
            if (to == null)   return cb.greaterThanOrEqualTo(root.get("transactionDate"), from);
            return cb.between(root.get("transactionDate"), from, to);
        };
    }

    /** Invoices with an outstanding balance. */
    public static Specification<PosTransactionMst> onlyDue(Boolean dueOnly) {
        return (root, q, cb) -> !Boolean.TRUE.equals(dueOnly) ? cb.conjunction()
                : cb.greaterThan(
                        cb.diff(cb.coalesce(root.get("amountTobePaid"), java.math.BigDecimal.ZERO),
                                cb.coalesce(root.get("givenAmount"),    java.math.BigDecimal.ZERO)),
                        java.math.BigDecimal.ZERO);
    }

    public static Specification<PosTransactionMst> build(Long orgId, PurchaseFilter f) {
        List<Specification<PosTransactionMst>> parts = new ArrayList<>();
        parts.add(inOrg(orgId));
        parts.add(isPurchase());
        parts.add(matches(f.search()));
        parts.add(hasSupplier(f.supplierId()));
        parts.add(hasBranch(f.branchId()));
        parts.add(hasStatus(f.status()));
        parts.add(hasPurchaseType(f.purchaseType()));
        parts.add(hasProductType(f.productType()));
        parts.add(dateBetween(f.dateFrom(), f.dateTo()));
        parts.add(onlyDue(f.dueOnly()));

        Specification<PosTransactionMst> spec = parts.get(0);
        for (int i = 1; i < parts.size(); i++) spec = spec.and(parts.get(i));
        return spec;
    }

    /** DRAFT/PENDING/APPROVED or D/P/A → the stored single-character code. */
    public static String normaliseStatus(String value) {
        if (value == null || value.isBlank()) return null;
        return switch (value.trim().toUpperCase()) {
            case "D", "DRAFT"    -> "D";
            case "A", "APPROVED" -> "A";
            default              -> "P";
        };
    }

    /** Stored code → display label. */
    public static String statusLabel(String code) {
        if (code == null) return null;
        return switch (code) {
            case "D" -> "DRAFT";
            case "A" -> "APPROVED";
            case "P" -> "PENDING";
            default  -> code;
        };
    }

    /** All purchase history filters, carried as one value object. */
    public record PurchaseFilter(
            String    search,
            Long      supplierId,
            Long      branchId,
            String    status,
            String    purchaseType,
            String    productType,
            LocalDate dateFrom,
            LocalDate dateTo,
            Boolean   dueOnly
    ) {}

    /**
     * Whitelist of sortable columns. A client-supplied sort field is never
     * handed to {@code Sort.by()} directly — an unrecognised property name
     * throws at query time and is a way to probe the entity graph.
     */
    public static String resolveSortField(String requested) {
        if (requested == null) return "transactionDate";
        return switch (requested) {
            case "invoiceNo", "batchNo", "challanNo", "transactionDate",
                 "totalAmount", "amountTobePaid", "givenAmount", "status",
                 "purchaseType", "insertedDate" -> requested;
            case "amountToBePaid" -> "amountTobePaid";
            case "createdDate"    -> "insertedDate";
            case "date"           -> "transactionDate";
            default               -> "transactionDate";
        };
    }
}
