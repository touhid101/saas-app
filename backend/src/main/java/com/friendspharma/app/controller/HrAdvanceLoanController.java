package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.request.HrRequests;
import com.friendspharma.app.dto.response.HrResponses;
import com.friendspharma.app.service.impl.HrAdvanceLoanServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * Advance and loan.
 *
 * <p>Two lifecycles on one screen, because a manager choosing between them is
 * choosing between two ways of lending the same money — but they are separate
 * records with separate rules, and only the loan carries a schedule.
 *
 * <p>Authorisation is the matrix right on Advance and Loan (MOD35), checked in
 * the service. Approving needs Approve; asking needs only Create.
 *
 * <p><b>No journal is written here.</b> Recording that the money went out is an
 * operational fact; the accounting entry belongs to Accounts.
 */
@RestController
@RequestMapping("/api/v1/hr")
@RequiredArgsConstructor
@Tag(name = "HR — Advance & Loan",
     description = "Money lent to staff and the plan by which payroll takes it back. Interest is "
                 + "recorded and never applied — no rate policy is decided by this application.")
public class HrAdvanceLoanController {

    private final HrAdvanceLoanServiceImpl service;

    /* ══ Advance ═══════════════════════════════════════════════════════ */

    @GetMapping("/advances")
    @Operation(summary = "Search advances")
    public ApiResponse<PageResponse<HrResponses.AdvanceDto>> searchAdvances(
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(
                service.searchAdvances(employeeId, status, PageRequest.of(page, Math.min(size, 100))));
    }

    @PostMapping("/advances")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Request an advance")
    public ApiResponse<HrResponses.AdvanceDto> createAdvance(
            @Valid @RequestBody HrRequests.Advance req) {
        return ApiResponse.created(service.createAdvance(req));
    }

    @PostMapping("/advances/{id}/submit")
    @Operation(summary = "Submit an advance request")
    public ApiResponse<HrResponses.AdvanceDto> submitAdvance(@PathVariable Long id) {
        return ApiResponse.success(service.submitAdvance(id), "Advance submitted");
    }

    @PostMapping("/advances/{id}/approve")
    @Operation(summary = "Approve an advance, at the amount asked for or at less",
               description = "The approved amount is kept separately from the requested one, and "
                           + "every recovery is computed from what was approved.")
    public ApiResponse<HrResponses.AdvanceDto> approveAdvance(
            @PathVariable Long id, @RequestBody(required = false) HrRequests.AdvanceDecision d) {
        return ApiResponse.success(service.approveAdvance(id, d), "Advance approved");
    }

    @PostMapping("/advances/{id}/reject")
    @Operation(summary = "Reject an advance")
    public ApiResponse<HrResponses.AdvanceDto> rejectAdvance(
            @PathVariable Long id, @RequestBody(required = false) HrRequests.Decision d) {
        return ApiResponse.success(service.rejectAdvance(id, d), "Advance rejected");
    }

    @PostMapping("/advances/{id}/pay")
    @Operation(summary = "Record that the advance has been paid out",
               description = "Recovery starts here: an approved advance is a promise, a paid one "
                           + "is a debt, and payroll only reads the second.")
    public ApiResponse<HrResponses.AdvanceDto> payAdvance(
            @PathVariable Long id, @Valid @RequestBody HrRequests.Disbursement req) {
        return ApiResponse.success(service.payAdvance(id, req), "Advance paid");
    }

    @PostMapping("/advances/{id}/cancel")
    @Operation(summary = "Cancel an advance",
               description = "Refused once any of it has been recovered from a payslip.")
    public ApiResponse<HrResponses.AdvanceDto> cancelAdvance(
            @PathVariable Long id, @RequestBody(required = false) HrRequests.Decision d) {
        return ApiResponse.success(service.cancelAdvance(id, d), "Advance cancelled");
    }

    /* ══ Loan ══════════════════════════════════════════════════════════ */

    @GetMapping("/loans")
    @Operation(summary = "Search loans")
    public ApiResponse<PageResponse<HrResponses.LoanDto>> searchLoans(
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(
                service.searchLoans(employeeId, status, PageRequest.of(page, Math.min(size, 100))));
    }

    @GetMapping("/loans/{id}")
    @Operation(summary = "One loan, with its repayment schedule")
    public ApiResponse<HrResponses.LoanDto> getLoan(@PathVariable Long id) {
        return ApiResponse.success(service.getLoan(id));
    }

    @PostMapping("/loans")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Request a loan")
    public ApiResponse<HrResponses.LoanDto> createLoan(@Valid @RequestBody HrRequests.Loan req) {
        return ApiResponse.created(service.createLoan(req));
    }

    @PostMapping("/loans/{id}/submit")
    @Operation(summary = "Submit a loan request")
    public ApiResponse<HrResponses.LoanDto> submitLoan(@PathVariable Long id) {
        return ApiResponse.success(service.submitLoan(id), "Loan submitted");
    }

    @PostMapping("/loans/{id}/approve")
    @Operation(summary = "Approve a loan and write its schedule",
               description = "The whole repayment plan is written now, so the employee can be "
                           + "shown it before accepting. Installments are equal and the last one "
                           + "carries the rounding, so the schedule adds up to the principal exactly.")
    public ApiResponse<HrResponses.LoanDto> approveLoan(
            @PathVariable Long id, @RequestBody(required = false) HrRequests.LoanDecision d) {
        return ApiResponse.success(service.approveLoan(id, d), "Loan approved");
    }

    @PostMapping("/loans/{id}/reject")
    @Operation(summary = "Reject a loan")
    public ApiResponse<HrResponses.LoanDto> rejectLoan(
            @PathVariable Long id, @RequestBody(required = false) HrRequests.Decision d) {
        return ApiResponse.success(service.rejectLoan(id, d), "Loan rejected");
    }

    @PostMapping("/loans/{id}/disburse")
    @Operation(summary = "Record that the loan has been paid out")
    public ApiResponse<HrResponses.LoanDto> disburseLoan(
            @PathVariable Long id, @Valid @RequestBody HrRequests.Disbursement req) {
        return ApiResponse.success(service.disburseLoan(id, req), "Loan disbursed");
    }

    @PostMapping("/loans/{id}/cancel")
    @Operation(summary = "Cancel a loan",
               description = "Refused once any of it has been recovered from a payslip.")
    public ApiResponse<HrResponses.LoanDto> cancelLoan(
            @PathVariable Long id, @RequestBody(required = false) HrRequests.Decision d) {
        return ApiResponse.success(service.cancelLoan(id, d), "Loan cancelled");
    }
}
