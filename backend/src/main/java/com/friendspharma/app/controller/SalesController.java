package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.dto.response.InvoiceDocumentResponse;
import com.friendspharma.app.service.impl.BranchScopeService;
import com.friendspharma.app.service.impl.InvoiceDocumentService;
import com.friendspharma.app.dto.request.InvoiceUnlockRequest;
import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.request.SalesRequest;
import com.friendspharma.app.dto.response.DeliveryPerformanceResponse;
import com.friendspharma.app.dto.response.DeliverySummaryResponse;
import com.friendspharma.app.dto.response.SalesPricingResponse;
import com.friendspharma.app.dto.response.TransactionResponse;
import com.friendspharma.app.service.impl.SalesServiceImpl;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sales")
@RequiredArgsConstructor
@Tag(name = "Sales", description = "FEFO-based batch-wise sales with discount engine")
public class SalesController {

    private final SalesServiceImpl svc;
    private final com.friendspharma.app.service.impl.InvoiceUnlockServiceImpl unlockSvc;
    private final BranchScopeService branchScope;
    private final InvoiceDocumentService invoiceDocuments;

    @GetMapping
    @Operation(summary = "Sales invoices",
               description = "Search matches the invoice number, the customer and the delivery man. "
                           + "Dates are inclusive on both ends.")
    public ResponseEntity<ApiResponse<PageResponse<TransactionResponse>>> list(
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) String paymentStatus,
            @RequestParam(required = false) Long branchId,
            @RequestParam(defaultValue = "false") boolean allBranches,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(
                svc.listSales(stage, paymentStatus, branchScope.resolve(branchId, allBranches),
                        customerId, from, to, q,
                        PageRequest.of(page, size, Sort.by("transactionDate").descending())))));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<TransactionResponse>> get(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(svc.getSale(id)));
    }

    @GetMapping("/pricing")
    @Operation(summary = "What this customer may buy of this product, and at what price",
               description = "The customer's type decides both the sellable units and the price list: "
                           + "Wholesaler gets BOX/STRIP/PCS at regular prices, Special the same units at "
                           + "special prices, Retailer PCS only at the wholesaler piece rate. Every unit "
                           + "is returned — the unavailable ones carry the reason — along with branch "
                           + "stock and, when a quantity is given, the batches FEFO would consume.")
    public ResponseEntity<ApiResponse<SalesPricingResponse>> pricing(
            @RequestParam Long customerId,
            @RequestParam Long productId,
            @RequestParam(required = false) BigDecimal qty,
            @RequestParam(defaultValue = "PCS") String unit,
            @RequestParam(required = false) Long branchId) {
        return ResponseEntity.ok(ApiResponse.ok(
                svc.getPricing(customerId, productId, qty, unit, branchId)));
    }

    @Operation(summary = "Create sale — batch-wise FEFO, priced by customer type",
               description = "Allocates earliest-expiry-first across whole batches and writes one detail "
                           + "line per batch consumed, so the invoice carries the batch numbers dispensed. "
                           + "The unit and price are validated against the customer's tier server-side.")
    @PostMapping
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE03','ROLE09')")
    public ResponseEntity<ApiResponse<TransactionResponse>> create(@Valid @RequestBody SalesRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(svc.createSale(req)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE03','ROLE09')")
    @Operation(summary = "Edit an unconfirmed invoice",
               description = "Allowed only while the invoice is at stage Submitted. The previous batch "
                           + "allocation is reversed first — every consumed piece goes back to the lot "
                           + "it came from — then the new lines are allocated afresh. Once confirmed, "
                           + "the stock movement is a fact and the correction is a sales return.")
    public ResponseEntity<ApiResponse<TransactionResponse>> update(
            @PathVariable Long id, @Valid @RequestBody SalesRequest req,
            @RequestHeader(value = "X-Unlock-Token", required = false) String unlockToken) {
        return ResponseEntity.ok(ApiResponse.ok("Invoice updated.", svc.updateSale(id, req, unlockToken)));
    }

    /**
     * Whether this user can edit a saved invoice, or needs a supervisor.
     *
     * <p>Asked by the screen so the button can say what will happen. It is not
     * the guard — the edit checks again — but a button that promises an edit
     * and then refuses one is its own kind of failure.
     */
    @GetMapping("/{id}/edit-authority")
    @Operation(summary = "Can the signed-in user edit this invoice unaided?")
    public ResponseEntity<ApiResponse<Map<String, Object>>> editAuthority(@PathVariable Long id) {
        boolean unaided = unlockSvc.mayEditUnaided(
                com.friendspharma.app.security.SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "canEdit", unaided,
                "needsUnlock", !unaided)));
    }

    /**
     * A supervisor authorises one correction to one invoice.
     *
     * <p>Deliberately not restricted by {@code @PreAuthorize}: the person
     * signed in is the one who cannot do this alone. The authority checked is
     * the supervisor's, against the credentials they type in.
     */
    @PostMapping("/{id}/unlock")
    @Operation(summary = "Unlock a saved invoice for editing",
               description = "Verifies a supervisor's credentials and returns a single-use token, "
                           + "valid for a few minutes and only for this invoice and this user. "
                           + "Send it back as the X-Unlock-Token header on the edit.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> unlock(
            @PathVariable Long id, @Valid @RequestBody InvoiceUnlockRequest req) {
        String token = svc.unlockForEdit(id, req);
        return ResponseEntity.ok(ApiResponse.ok("Invoice unlocked.", Map.of(
                "unlockToken", token,
                "validMinutes", 10)));
    }

    @PostMapping("/{id}/finalize")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE03')")
    public ResponseEntity<ApiResponse<TransactionResponse>> finalize(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok("Sale finalized", svc.finalizeSale(id)));
    }

    /**
     * One sale, shaped for printing.
     *
     * <p>Serves both the A4 invoice and the till receipt. They are two layouts
     * over one document, so the discount on the customer's receipt and the
     * discount on the office copy of the same invoice cannot disagree.
     *
     * <p>The letterhead — name, address, phone, logo — comes from the branch
     * that raised the sale, not from anything hard-coded, so each pharmacy
     * prints under its own identity.
     */
    @GetMapping("/{id}/document")
    @Operation(summary = "A sale, ready to print",
               description = "Everything both printed layouts need: the branch letterhead, the "
                           + "customer, the lines with pieces and discount worked out, the totals "
                           + "and the amount in words. Scoped to your own branch.")
    public ResponseEntity<ApiResponse<InvoiceDocumentResponse>> document(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(invoiceDocuments.forSale(id)));
    }
}

@RestController
@RequestMapping("/api/sales-activity")
@RequiredArgsConstructor
@Tag(name = "Sales Activity", description = "Order stage transitions, delivery man assignment, collection")
class SalesActivityController {

    private final SalesServiceImpl svc;
    private final BranchScopeService branchScope;

    @GetMapping
    @Operation(summary = "Sales Activity list",
               description = "Every filter is applied in the query, not to the returned page, "
                           + "so searching finds matches beyond the first page.")
    public ResponseEntity<ApiResponse<PageResponse<TransactionResponse>>> list(
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) String paymentStatus,
            @RequestParam(required = false) Long branchId,
            @RequestParam(defaultValue = "false") boolean allBranches,
            @RequestParam(required = false) Long deliveryManId,
            @RequestParam(defaultValue = "false") boolean unassigned,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String source,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(svc.listActivity(
                stage, paymentStatus, branchScope.resolve(branchId, allBranches),
                deliveryManId, unassigned, from, to, q, source,
                PageRequest.of(page, size)))));
    }

    @GetMapping("/stage-counts")
    @Operation(summary = "How many orders sit at each stage",
               description = "Counted across the branch rather than the current page, so the "
                           + "chips do not change as you page through.")
    public ResponseEntity<ApiResponse<Map<String, Long>>> stageCounts(
            @RequestParam(required = false) Long branchId) {
        return ResponseEntity.ok(ApiResponse.ok(svc.stageCounts(branchScope.resolve(branchId))));
    }

    @GetMapping("/summary")
    @Operation(summary = "Delivery dashboard totals",
               description = "Counts per stage plus billed, collected and outstanding. Scoped to "
                           + "the signed-in driver own round for delivery staff; ownRoundOnly says which.")
    public ResponseEntity<ApiResponse<DeliverySummaryResponse>> summary(
            @RequestParam(required = false) Long branchId) {
        return ResponseEntity.ok(ApiResponse.ok(svc.deliverySummary(branchScope.resolve(branchId))));
    }

    @GetMapping("/delivery-performance")
    @Operation(summary = "How each delivery man is doing",
               description = "Deliveries completed and cash actually collected against what was "
                           + "billed. Delivery staff see only their own record — comparing drivers "
                           + "is a management question.")
    public ResponseEntity<ApiResponse<List<DeliveryPerformanceResponse>>> deliveryPerformance(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.ok(
                svc.deliveryPerformance(branchScope.resolve(branchId), from, to)));
    }

    @PostMapping("/{id}/collect")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE03','ROLE06')")
    @Operation(summary = "Record money taken against an invoice",
               description = "Accumulates into the amount given. The payment status follows from "
                           + "what is left owing, and only a fully settled invoice becomes Collected.")
    public ResponseEntity<ApiResponse<TransactionResponse>> collect(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {
        Object amt = body.get("amount");
        BigDecimal amount = amt == null ? null : new BigDecimal(amt.toString());
        return ResponseEntity.ok(ApiResponse.ok("Collection recorded",
                svc.collect(id, amount, (String) body.get("paymentType"))));
    }

    @PostMapping("/{id}/advance")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE03','ROLE06')")
    public ResponseEntity<ApiResponse<TransactionResponse>> advance(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {
        String newStage     = (String) body.get("stage");
        Long deliveryManId  = body.containsKey("deliveryManId") ? ((Number)body.get("deliveryManId")).longValue() : null;
        return ResponseEntity.ok(ApiResponse.ok("Stage updated", svc.advanceStage(id, newStage, deliveryManId)));
    }

    @PostMapping("/{id}/assign-delivery-man")
    // Deliberately excludes ROLE06. Choosing who delivers is a management act:
    // a driver reassigning their own round would move it off their record and
    // put goods with someone nobody dispatched them to.
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02')")
    public ResponseEntity<ApiResponse<TransactionResponse>> assignDm(
            @PathVariable Long id, @RequestBody Map<String, Long> body) {
        Long dmId = body.get("deliveryManId");
        return ResponseEntity.ok(ApiResponse.ok("Delivery man assigned",
                svc.advanceStage(id, "Delivery Assigned", dmId)));
    }

    @PostMapping("/bulk-assign-delivery-man")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02')")
    @Operation(summary = "Bulk assign delivery man to multiple confirmed invoices in one call")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> bulkAssign(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Number> ids = (List<Number>) body.get("invoiceIds");
        Long dmId = ((Number) body.get("deliveryManId")).longValue();
        List<Long> invoiceIds = ids.stream().map(Number::longValue).toList();
        int count = svc.bulkAssignDeliveryMan(invoiceIds, dmId);
        return ResponseEntity.ok(ApiResponse.ok("Bulk assignment complete", Map.of("assignedCount", count)));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02')")
    @Operation(summary = "Call a sale off: stock back on the shelf, journals reversed",
               description = "Only before the goods leave the branch, and only while nothing has "
                           + "been paid against it. Once either is true the correction is a sales "
                           + "return, which says what actually happened.")
    public ResponseEntity<ApiResponse<TransactionResponse>> cancel(
            @PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        return ResponseEntity.ok(ApiResponse.ok("Order cancelled", svc.cancelSale(id, reason)));
    }
}
