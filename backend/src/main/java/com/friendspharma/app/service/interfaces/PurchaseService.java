package com.friendspharma.app.service.interfaces;

import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.request.PurchaseRequest;
import com.friendspharma.app.dto.response.PurchaseListItemResponse;
import com.friendspharma.app.dto.response.PurchaseResponse;
import com.friendspharma.app.specification.PurchaseSpecification.PurchaseFilter;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;

/**
 * Purchase invoice use cases.
 *
 * <p>Every method scopes itself to the caller's organisation, resolved from the
 * JWT inside the implementation. No method takes an orgId parameter, so a
 * controller cannot pass a client-supplied tenant through.
 */
public interface PurchaseService {

    /** Paginated, filtered, sorted purchase history. All work happens in SQL. */
    PageResponse<PurchaseListItemResponse> search(PurchaseFilter filter, Pageable pageable);

    /** Full invoice with its lines. */
    PurchaseResponse getById(Long id);

    /**
     * Creates a purchase. Invoice number, batch reference and every money value
     * are generated or calculated server-side.
     */
    PurchaseResponse create(PurchaseRequest request);

    /** Updates a DRAFT or PENDING invoice. An APPROVED invoice is frozen. */
    PurchaseResponse update(Long id, PurchaseRequest request);

    /**
     * Approves the invoice and moves stock. Idempotent: approving an already
     * approved invoice is rejected rather than double-counting stock.
     */
    PurchaseResponse approve(Long id);

    /** Cancels an unapproved invoice. Approved invoices cannot be cancelled. */
    void cancel(Long id);

    /**
     * Everything the purchase entry form needs in one call: suppliers, branches,
     * payment types and the costing/product-type option lists.
     */
    Map<String, List<?>> getFormLookups();

    /**
     * Product rows for the line-entry picker, carrying the price snapshot fields
     * the calculator needs (TP price, IGTA, MRP, pieces per box/strip).
     */
    /**
     * Products for line entry, from the caller's own branch.
     *
     * @param activeOnly false to include products that have been made inactive —
     *                   a requisition may legitimately ask for something the shop
     *                   stopped stocking, which a purchase form should not offer.
     */
    List<Map<String, Object>> searchProductsForEntry(String query, int limit, boolean activeOnly, Long companyId, Long supplierId);

    /** Manufacturers this branch stocks, each with a product count. */
    List<Map<String, Object>> productCompaniesForBranch(boolean activeOnly);
}
