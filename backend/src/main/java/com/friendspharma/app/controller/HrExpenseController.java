package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.request.HrRequests;
import com.friendspharma.app.dto.response.HrResponses;
import com.friendspharma.app.service.impl.HrExpenseServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;

/**
 * Conveyance and expense claims.
 *
 * <p>Authorisation is the matrix right on <b>Expense Claims (MOD37)</b>, checked
 * in the service. Filing needs Create; deciding and paying need Approve — so
 * somebody can claim their own fares without being able to allow them.
 *
 * <p><b>A reimbursement is never paid through a payslip.</b> Money spent on the
 * pharmacy's behalf is being returned, not earned; adding it to the gross would
 * put it inside the taxable base and inside the provident fund base, and the
 * pharmacy would deduct PF from a rickshaw fare.
 */
@RestController
@RequestMapping("/api/v1/hr/expenses")
@RequiredArgsConstructor
@Tag(name = "HR — Expense Claims",
     description = "What staff spent on the pharmacy's behalf, and getting it back. Paid on its "
                 + "own, never through payroll.")
public class HrExpenseController {

    private final HrExpenseServiceImpl expenses;

    /* ── Types ─────────────────────────────────────────────────────────── */

    @GetMapping("/types")
    @Operation(summary = "The kinds of expense this branch reimburses",
               description = "V83 seeds five names with no limits on them. A type whose caps are "
                           + "blank carries capped=false: no limit has been decided, which is not "
                           + "the same as a limit of nought, and nothing is refused against it.")
    public ApiResponse<List<HrResponses.ExpenseTypeDto>> types(
            @RequestParam(defaultValue = "true") boolean activeOnly) {
        return ApiResponse.success(expenses.listTypes(activeOnly));
    }

    @PostMapping("/types")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add an expense type")
    public ApiResponse<HrResponses.ExpenseTypeDto> createType(
            @Valid @RequestBody HrRequests.ExpenseType req) {
        return ApiResponse.created(expenses.createType(req));
    }

    @PutMapping("/types/{id}")
    @Operation(summary = "Change an expense type",
               description = "Sending a null cap clears it — removing a limit is a decision, and "
                           + "treating null as 'no change' would make one impossible to undo.")
    public ApiResponse<HrResponses.ExpenseTypeDto> updateType(
            @PathVariable Long id, @Valid @RequestBody HrRequests.ExpenseType req) {
        return ApiResponse.success(expenses.updateType(id, req), "Expense type updated");
    }

    @DeleteMapping("/types/{id}")
    @Operation(summary = "Retire an expense type",
               description = "Deactivated, never deleted: claims already filed point at it, and a "
                           + "report of last year's spending should still say what it was on.")
    public ApiResponse<Void> retireType(@PathVariable Long id) {
        expenses.retireType(id);
        return ApiResponse.success(null, "Expense type retired");
    }

    /* ── Claims ────────────────────────────────────────────────────────── */

    @GetMapping
    @Operation(summary = "Search claims")
    public ApiResponse<PageResponse<HrResponses.ExpenseClaimDto>> search(
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(
                expenses.search(employeeId, status, from, to, PageRequest.of(page, Math.min(size, 100))));
    }

    @GetMapping("/pending")
    @Operation(summary = "The approval queue")
    public ApiResponse<List<HrResponses.ExpenseClaimDto>> pending() {
        return ApiResponse.success(expenses.pending());
    }

    @GetMapping("/{id}")
    @Operation(summary = "One claim and every line on it")
    public ApiResponse<HrResponses.ExpenseClaimDto> get(@PathVariable Long id) {
        return ApiResponse.success(expenses.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "File a claim",
               description = "Header and lines in one request — a week of conveyance is six "
                           + "journeys, not six claims. Totals are derived from the lines, never "
                           + "taken from the body. Saved as a draft unless submit=true.")
    public ApiResponse<HrResponses.ExpenseClaimDto> create(
            @Valid @RequestBody HrRequests.ExpenseClaim req) {
        return ApiResponse.created(expenses.create(req));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Change a draft claim",
               description = "Only a draft. A submitted claim is cancelled and filed again rather "
                           + "than changed under the approver.")
    public ApiResponse<HrResponses.ExpenseClaimDto> update(
            @PathVariable Long id, @Valid @RequestBody HrRequests.ExpenseClaim req) {
        return ApiResponse.success(expenses.update(id, req), "Claim updated");
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "Submit a draft claim",
               description = "Refused where a type expects a receipt and a line has none.")
    public ApiResponse<HrResponses.ExpenseClaimDto> submit(@PathVariable Long id) {
        return ApiResponse.success(expenses.submit(id), "Claim submitted");
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve a claim",
               description = "Amounts may be set per line; a line left out is approved as claimed. "
                           + "The claim total is the sum of what was allowed, never a figure typed "
                           + "separately.")
    public ApiResponse<HrResponses.ExpenseClaimDto> approve(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) HrRequests.ExpenseDecision d) {
        return ApiResponse.success(expenses.approve(id, d), "Claim approved");
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Reject a claim")
    public ApiResponse<HrResponses.ExpenseClaimDto> reject(
            @PathVariable Long id, @RequestBody(required = false) HrRequests.Decision d) {
        return ApiResponse.success(expenses.reject(id, d), "Claim rejected");
    }

    @PostMapping("/{id}/pay")
    @Operation(summary = "Record the reimbursement",
               description = "Its own payment with its own reference. Nothing here touches a "
                           + "payroll run and nothing on a payslip changes.")
    public ApiResponse<HrResponses.ExpenseClaimDto> pay(
            @PathVariable Long id, @Valid @RequestBody HrRequests.ExpensePayment req) {
        return ApiResponse.success(expenses.pay(id, req), "Claim reimbursed");
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a claim",
               description = "Not available once reimbursed — cancelling would not take the money "
                           + "back.")
    public ApiResponse<HrResponses.ExpenseClaimDto> cancel(
            @PathVariable Long id, @RequestBody(required = false) HrRequests.Decision d) {
        return ApiResponse.success(expenses.cancel(id, d), "Claim cancelled");
    }

    /* ── Receipts ──────────────────────────────────────────────────────── */

    @PostMapping(value = "/lines/{lineId}/receipt", consumes = "multipart/form-data")
    @Operation(summary = "Attach a photograph of the receipt",
               description = "JPEG, PNG or WebP, checked by extension, declared type and magic "
                           + "bytes — the same checks a staff photograph goes through. Only while "
                           + "the claim is a draft: a receipt swapped after approval would change "
                           + "the evidence behind a decision already made.")
    public ApiResponse<HrResponses.ExpenseLineDto> attachReceipt(
            @PathVariable Long lineId, @RequestPart("file") MultipartFile file) {
        return ApiResponse.success(expenses.attachReceipt(lineId, file), "Receipt attached");
    }

    @DeleteMapping("/lines/{lineId}/receipt")
    @Operation(summary = "Remove a receipt")
    public ApiResponse<Void> removeReceipt(@PathVariable Long lineId) {
        expenses.removeReceipt(lineId);
        return ApiResponse.success(null, "Receipt removed");
    }
}
