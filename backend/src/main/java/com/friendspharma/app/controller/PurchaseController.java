package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.service.impl.BranchScopeService;
import com.friendspharma.app.service.impl.PurchaseOrderDocumentService;
import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.request.PurchaseRequest;
import com.friendspharma.app.dto.response.PurchaseListItemResponse;
import com.friendspharma.app.dto.response.PurchaseOrderDocumentResponse;
import com.friendspharma.app.dto.response.PurchaseResponse;
import com.friendspharma.app.service.interfaces.PurchaseService;
import com.friendspharma.app.specification.PurchaseSpecification;
import com.friendspharma.app.specification.PurchaseSpecification.PurchaseFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Purchase invoice REST API.
 *
 * <p>Authorisation follows the roles seeded in V3: ROLE01 Super Admin,
 * ROLE02 Branch Manager, ROLE05 Purchase Officer. Read is open to any
 * authenticated user because Inventory and Reports need purchase history;
 * writes are restricted, and approval — which moves stock — more tightly still.
 *
 * <p>There is no orgId parameter anywhere: tenant scope is resolved from the JWT
 * inside the service.
 */
@RestController
@RequestMapping("/api/purchases")
@RequiredArgsConstructor
@Tag(name = "Purchase", description = "Purchase invoice entry, history, approval")
public class PurchaseController {

    private static final int MAX_PAGE_SIZE = 200;

    private final PurchaseService purchaseService;
    private final BranchScopeService branchScope;
    private final PurchaseOrderDocumentService orderDocuments;

    /* ══════════════════════════════════════════════════════════════════════
       LIST
       ══════════════════════════════════════════════════════════════════════ */

    @GetMapping
    @Operation(summary = "Search purchase invoices",
               description = "Server-side pagination, filtering and sorting. Filters combine with AND.")
    public ResponseEntity<ApiResponse<PageResponse<PurchaseListItemResponse>>> list(
            @RequestParam(required = false) String  search,
            @RequestParam(required = false) Long    supplierId,
            @RequestParam(required = false) Long    branchId,
            @RequestParam(required = false) String  status,
            @RequestParam(required = false) String  purchaseType,
            @RequestParam(required = false) String  productType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) Boolean dueOnly,
            @RequestParam(defaultValue = "0")               int    page,
            @RequestParam(defaultValue = "25")              int    size,
            @RequestParam(defaultValue = "transactionDate") String sortBy,
            @RequestParam(defaultValue = "desc")            String sortDir) {

        PurchaseFilter filter = new PurchaseFilter(
                search, supplierId, branchScope.resolve(branchId), status, purchaseType, productType,
                dateFrom, dateTo, dueOnly);

        // Clamp the page size so a client cannot pull the whole ledger at once,
        // and whitelist the sort column (see resolveSortField).
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Sort.Direction dir = "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Sort sort = Sort.by(dir, PurchaseSpecification.resolveSortField(sortBy));

        return ResponseEntity.ok(ApiResponse.ok(
                purchaseService.search(filter, PageRequest.of(Math.max(page, 0), safeSize, sort))));
    }

    /* ══════════════════════════════════════════════════════════════════════
       LOOKUPS
       ══════════════════════════════════════════════════════════════════════ */

    @GetMapping("/lookups")
    @Operation(summary = "Dropdown data for the purchase entry form",
               description = "Active suppliers and branches, plus the costing basis, product type, "
                           + "payment type, sales type and status option lists with their defaults.")
    public ResponseEntity<ApiResponse<Map<String, List<?>>>> lookups() {
        return ResponseEntity.ok(ApiResponse.ok(purchaseService.getFormLookups()));
    }

    @GetMapping("/products")
    @Operation(summary = "Product picker for line entry",
               description = "Returns the price snapshot a line needs — TP price, IGTA, MRP — plus "
                           + "pieces per box/strip and the batch/expiry tracking flags.")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> products(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(defaultValue = "true") boolean activeOnly,
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) Long supplierId) {
        return ResponseEntity.ok(ApiResponse.ok(
                purchaseService.searchProductsForEntry(q, limit, activeOnly, companyId, supplierId)));
    }

    /**
     * Manufacturers this branch stocks, with a product count each.
     *
     * <p>Feeds "add every product from this company" on the requisition form.
     * The count is the point: it tells the buyer how many lines the button is
     * about to create before they press it.
     */
    @GetMapping("/product-companies")
    @Operation(summary = "Manufacturers stocked by this branch, with product counts")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> productCompanies(
            @RequestParam(defaultValue = "true") boolean activeOnly) {
        return ResponseEntity.ok(ApiResponse.ok(
                purchaseService.productCompaniesForBranch(activeOnly)));
    }

    /* ══════════════════════════════════════════════════════════════════════
       READ ONE
       ══════════════════════════════════════════════════════════════════════ */

    @GetMapping("/{id}")
    @Operation(summary = "Get one purchase invoice with its lines")
    public ResponseEntity<ApiResponse<PurchaseResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(purchaseService.getById(id)));
    }

    /**
     * The order as a document, ready to print and send to the supplier.
     *
     * <p>Separate from {@link #getById} because a screen and a piece of paper
     * want different things: this carries the branch letterhead, the supplier's
     * postal details, the requisition it came from and the amount in words, and
     * every figure on it is computed on the server — the same arrangement the
     * sales invoice uses, so the two documents leave the pharmacy looking alike.
     *
     * <p>Branch-scoped inside the service: an order belonging to another
     * pharmacy is a 404, not a printable document.
     */
    @GetMapping("/{id}/document")
    @Operation(summary = "The purchase order as a printable document for the supplier")
    public ResponseEntity<ApiResponse<PurchaseOrderDocumentResponse>> document(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(orderDocuments.forPurchase(id)));
    }

    /* ══════════════════════════════════════════════════════════════════════
       WRITE
       ══════════════════════════════════════════════════════════════════════ */

    @PostMapping
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE05')")
    @Operation(summary = "Create a purchase invoice",
               description = "Invoice number and batch reference are generated server-side. Every line "
                           + "total and invoice figure is calculated server-side and cannot be supplied "
                           + "by the client. Stock moves only when the invoice is APPROVED.")
    public ResponseEntity<ApiResponse<PurchaseResponse>> create(
            @Valid @RequestBody PurchaseRequest request) {
        PurchaseResponse created = purchaseService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Purchase invoice " + created.getInvoiceNo() + " created.", created));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE05')")
    @Operation(summary = "Update a draft or pending purchase invoice",
               description = "Returns 409 PURCHASE_ALREADY_APPROVED for an approved invoice — its stock "
                           + "has already moved, so it is corrected by a purchase return, not an edit. "
                           + "The invoice number and batch reference never change.")
    public ResponseEntity<ApiResponse<PurchaseResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody PurchaseRequest request) {
        return ResponseEntity.ok(
                ApiResponse.ok("Purchase invoice updated.", purchaseService.update(id, request)));
    }

    /*
     * No role is named here, on purpose.
     *
     * The gate is the Approve tick on Purchase Invoice in the RBAC screen, and
     * the service checks it — see PurchaseServiceImpl.requireApproveAuthority.
     * A @PreAuthorize naming roles would quietly override the matrix and put the
     * two back out of step, which is the fault this replaced.
     */
    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve a purchase invoice and receive its stock",
               description = "Increases product stock by each line's quantity converted to pieces. "
                           + "Rejected with 409 if the invoice is already approved, so stock cannot "
                           + "be counted twice.")
    public ResponseEntity<ApiResponse<PurchaseResponse>> approve(@PathVariable Long id) {
        return ResponseEntity.ok(
                ApiResponse.ok("Purchase invoice approved and stock received.", purchaseService.approve(id)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE05')")
    @Operation(summary = "Cancel an unapproved purchase invoice",
               description = "Returns 409 for an approved invoice; raise a purchase return instead.")
    public ResponseEntity<ApiResponse<Void>> cancel(@PathVariable Long id) {
        purchaseService.cancel(id);
        return ResponseEntity.ok(ApiResponse.noContent("Purchase invoice cancelled."));
    }
}
