package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.request.AdvanceRecoveryRequest;
import com.friendspharma.app.dto.request.SupplierPaymentRequest;
import com.friendspharma.app.dto.response.SupplierPaymentResponse;
import com.friendspharma.app.service.impl.SupplierPaymentServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/supplier-payments")
@RequiredArgsConstructor
@Tag(name = "Supplier Payments", description = "Money paid to suppliers, what is still owed, and how each supplier performs")
public class SupplierPaymentController {

    private final SupplierPaymentServiceImpl svc;

    /* ── Ledger ──────────────────────────────────────────────────────────── */

    @GetMapping
    @Operation(summary = "Payment history",
               description = "Cancelled payments are hidden unless asked for — they are kept, not deleted.")
    public ResponseEntity<ApiResponse<PageResponse<SupplierPaymentResponse>>> list(
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean includeCancelled,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(
                svc.list(supplierId, from, to, mode, q, includeCancelled, PageRequest.of(page, size)))));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE04','ROLE05')")
    @Operation(summary = "Record a payment to a supplier",
               description = "Naming a purchase settles that bill and is capped at what it still owes; "
                           + "omitting one records the money on account.")
    public ResponseEntity<ApiResponse<SupplierPaymentResponse>> pay(
            @Valid @RequestBody SupplierPaymentRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(svc.record(req)));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE04')")
    @Operation(summary = "Reverse a payment",
               description = "Marks it cancelled with a reason. Payments are never deleted.")
    public ResponseEntity<ApiResponse<SupplierPaymentResponse>> cancel(
            @PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        return ResponseEntity.ok(ApiResponse.ok(
                svc.cancel(id, body == null ? null : body.get("reason"))));
    }

    /* ── What is owed ────────────────────────────────────────────────────── */

    @GetMapping("/outstanding")
    @Operation(summary = "Supplier accounts with ageing",
               description = "Approved purchases only, net of ledger payments and adjusted purchase-return credits.")
    public ResponseEntity<ApiResponse<List<SupplierPaymentResponse.Account>>> outstanding(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean dueOnly,
            @RequestParam(defaultValue = "false") boolean includeDormant) {
        return ResponseEntity.ok(ApiResponse.ok(svc.accounts(q, dueOnly, includeDormant)));
    }

    @GetMapping("/supplier/{supplierId}")
    @Operation(summary = "One supplier's account, open bills and recent payments")
    public ResponseEntity<ApiResponse<SupplierPaymentResponse.SupplierDetail>> detail(
            @PathVariable Long supplierId) {
        return ResponseEntity.ok(ApiResponse.ok(svc.detail(supplierId)));
    }

    @GetMapping("/supplier/{supplierId}/invoices")
    @Operation(summary = "Approved purchases for a supplier",
               description = "Open bills by default; pass openOnly=false for the full history.")
    public ResponseEntity<ApiResponse<List<SupplierPaymentResponse.PayableInvoice>>> invoices(
            @PathVariable Long supplierId,
            @RequestParam(defaultValue = "true") boolean openOnly) {
        return ResponseEntity.ok(ApiResponse.ok(svc.openInvoices(supplierId, openOnly)));
    }

    @GetMapping("/supplier/{supplierId}/statement")
    @Operation(summary = "Running statement",
               description = "Purchases, adjusted returns and payments in date order with a running balance.")
    public ResponseEntity<ApiResponse<List<SupplierPaymentResponse.StatementLine>>> statement(
            @PathVariable Long supplierId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.ok(svc.statement(supplierId, from, to)));
    }

    /* ── Advances ────────────────────────────────────────────────────────── */

    @GetMapping("/advances")
    @Operation(summary = "Advances suppliers hold of ours",
               description = "Money paid beyond the bills — typically an invoice discount handed over "
                           + "on the understanding it comes back. Open balances only unless asked otherwise.")
    public ResponseEntity<ApiResponse<List<SupplierPaymentResponse.Advance>>> advances(
            @RequestParam(required = false) Long supplierId,
            @RequestParam(defaultValue = "true") boolean openOnly) {
        return ResponseEntity.ok(ApiResponse.ok(svc.advances(supplierId, openOnly)));
    }

    @PostMapping("/advances/restate")
    @PreAuthorize("hasRole('ROLE01')")
    @Operation(summary = "Restate invoice-time advances to the invoice discount",
               description = "Purchases saved when 'pay the invoice discount too' carved the whole discount "
                           + "(line discounts included) out of the cash are corrected to the invoice discount only; "
                           + "the mirrored payment and the ledger follow. Reports what changed. Safe to repeat.")
    public ResponseEntity<ApiResponse<List<SupplierPaymentResponse.AdvanceRestatement>>> restateAdvances() {
        return ResponseEntity.ok(ApiResponse.ok(svc.restateInvoiceAdvances()));
    }

    @PostMapping("/advances/recover")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE04','ROLE05')")
    @Operation(summary = "Record an advance coming back",
               description = "In money, or as a credit against a later bill when the mode is "
                           + "ADJUSTMENT and a purchase is named.")
    public ResponseEntity<ApiResponse<SupplierPaymentResponse>> recover(
            @Valid @RequestBody AdvanceRecoveryRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(svc.recoverAdvance(req)));
    }

    /* ── Performance ─────────────────────────────────────────────────────── */

    @GetMapping("/performance")
    @Operation(summary = "Supplier performance over a window",
               description = "Spend, outstanding, return rate, and amount-weighted days to pay.")
    public ResponseEntity<ApiResponse<List<SupplierPaymentResponse.Performance>>> performance(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q) {
        return ResponseEntity.ok(ApiResponse.ok(svc.performance(from, to, q)));
    }
}
