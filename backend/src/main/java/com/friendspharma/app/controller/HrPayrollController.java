package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.request.HrRequests;
import com.friendspharma.app.dto.response.HrResponses;
import com.friendspharma.app.dto.response.PayslipDocumentResponse;
import com.friendspharma.app.service.impl.HrPayrollServiceImpl;
import com.friendspharma.app.service.impl.HrSetupServiceImpl;
import com.friendspharma.app.service.impl.PayslipDocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Payroll — salary structures, periods, the run, and paying it.
 *
 * <p>The lifecycle is DRAFT to CALCULATED to REVIEWED to APPROVED to POSTED to
 * PAID. <b>Posting freezes the run</b>: no recalculation, no edit, no delete.
 * A correction after that is a reversal and a fresh run.
 *
 * <p>Every response carries {@code allowedActions}, so the screen does not hold
 * a second copy of the lifecycle and cannot offer a button the server will
 * refuse.
 *
 * <p>Authorisation is the matrix right on Payroll (MOD36), checked in the
 * service. Calculating needs Create and approving needs Approve — a separation
 * of duties V77 expresses by granting Approve to ROLE01 and ROLE02 and
 * withholding it from ROLE04 Accounts.
 */
@RestController
@RequestMapping("/api/v1/hr/payroll")
@RequiredArgsConstructor
@Tag(name = "HR — Payroll",
     description = "Salary structures, pay periods, the monthly run and salary payment. No tax is "
                 + "computed: no rate is configured anywhere in this application.")
public class HrPayrollController {

    private final HrPayrollServiceImpl payroll;
    private final HrSetupServiceImpl   setup;
    private final PayslipDocumentService payslips;

    /* ══ Salary structures ═════════════════════════════════════════════ */

    @GetMapping("/structures")
    @Operation(summary = "Salary structures at this branch",
               description = "paysOvertime is false when no multiplier is set — overtime is then "
                           + "recorded but not valued, which is a decision rather than an omission.")
    public ApiResponse<List<HrResponses.SalaryStructureDto>> structures(
            @RequestParam(defaultValue = "true") boolean activeOnly) {
        return ApiResponse.success(setup.listStructures(activeOnly));
    }

    @GetMapping("/structures/{id}")
    @Operation(summary = "One structure and its components")
    public ApiResponse<HrResponses.SalaryStructureDto> structure(@PathVariable Long id) {
        return ApiResponse.success(setup.getStructure(id));
    }

    @PostMapping("/structures")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a salary structure",
               description = "Components are evaluated in sort order, and the order is arithmetic: "
                           + "a percentage-of-gross line placed before the allowances that make up "
                           + "that gross computes a different, wrong number.")
    public ApiResponse<HrResponses.SalaryStructureDto> createStructure(
            @Valid @RequestBody HrRequests.SalaryStructure req) {
        return ApiResponse.created(setup.createStructure(req));
    }

    @PutMapping("/structures/{id}")
    @Operation(summary = "Change a salary structure",
               description = "Does not rewrite history: a payslip carries its own arithmetic, "
                           + "copied when it was calculated, so this changes what the next run "
                           + "computes and nothing about the runs already computed.")
    public ApiResponse<HrResponses.SalaryStructureDto> updateStructure(
            @PathVariable Long id, @Valid @RequestBody HrRequests.SalaryStructure req) {
        return ApiResponse.success(setup.updateStructure(id, req), "Structure updated");
    }

    @DeleteMapping("/structures/{id}")
    @Operation(summary = "Retire a salary structure",
               description = "Refused while anybody is still on it.")
    public ApiResponse<Void> retireStructure(@PathVariable Long id) {
        setup.deactivateStructure(id);
        return ApiResponse.success(null, "Structure retired");
    }

    /* ══ Periods ═══════════════════════════════════════════════════════ */

    @GetMapping("/periods")
    @Operation(summary = "Pay periods at this branch")
    public ApiResponse<List<HrResponses.PayrollPeriodDto>> periods(
            @RequestParam(required = false) Integer year) {
        return ApiResponse.success(payroll.listPeriods(year));
    }

    @PostMapping("/periods")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Open a pay period",
               description = "Working days are required, not defaulted: payroll divides by that "
                           + "figure to value an absence, and a guess changes what people are paid.")
    public ApiResponse<HrResponses.PayrollPeriodDto> createPeriod(
            @Valid @RequestBody HrRequests.PayrollPeriod req) {
        return ApiResponse.created(payroll.createPeriod(req));
    }

    @PostMapping("/periods/{id}/close")
    @Operation(summary = "Close a pay period",
               description = "Refused while its run is unfinished — closing would strand the run.")
    public ApiResponse<HrResponses.PayrollPeriodDto> closePeriod(@PathVariable Long id) {
        return ApiResponse.success(payroll.closePeriod(id), "Period closed");
    }

    /* ══ The run ═══════════════════════════════════════════════════════ */

    @GetMapping
    @Operation(summary = "Payroll runs at this branch")
    public ApiResponse<PageResponse<HrResponses.PayrollDto>> search(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(payroll.search(status, PageRequest.of(page, Math.min(size, 100))));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One run, with every payslip and its arithmetic")
    public ApiResponse<HrResponses.PayrollDto> get(@PathVariable Long id) {
        return ApiResponse.success(payroll.get(id));
    }

    @GetMapping("/{id}/lines/{lineId}/document")
    @Operation(summary = "One payslip, assembled for printing",
               description = "The letterhead, the lines the run wrote, the attendance it counted, "
                           + "every payment against it and the net in words — computed here, in the "
                           + "shape of the sales invoice document, so the printed sheet and the "
                           + "filed copy come from one place.")
    public ApiResponse<PayslipDocumentResponse> payslipDocument(@PathVariable Long id,
                                                                @PathVariable Long lineId) {
        return ApiResponse.success(payslips.forLine(id, lineId));
    }

    @PostMapping("/calculate")
    @Operation(summary = "Calculate, or recalculate, the run for a period",
               description = "Safe to repeat. The run releases every loan installment and advance "
                           + "amount it previously claimed before calculating again, so a second "
                           + "pass produces the same answer as the first rather than a smaller one.")
    public ApiResponse<HrResponses.PayrollDto> calculate(
            @Valid @RequestBody HrRequests.PayrollCalculate req) {
        return ApiResponse.success(payroll.calculate(req), "Payroll calculated");
    }

    @PostMapping("/{id}/review")
    @Operation(summary = "Mark a calculated run reviewed")
    public ApiResponse<HrResponses.PayrollDto> review(
            @PathVariable Long id, @RequestBody(required = false) HrRequests.Decision d) {
        return ApiResponse.success(payroll.review(id, d), "Payroll reviewed");
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve a reviewed run")
    public ApiResponse<HrResponses.PayrollDto> approve(
            @PathVariable Long id, @RequestBody(required = false) HrRequests.Decision d) {
        return ApiResponse.success(payroll.approve(id, d), "Payroll approved");
    }

    @PostMapping("/{id}/post")
    @Operation(summary = "Post the run, which freezes it",
               description = "From here the run is read-only. This is where Accounts will raise its "
                           + "financial event when it exists; nothing accounting happens yet.")
    public ApiResponse<HrResponses.PayrollDto> post(
            @PathVariable Long id, @RequestBody(required = false) HrRequests.Decision d) {
        return ApiResponse.success(payroll.post(id, d), "Payroll posted");
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a run that has not been posted",
               description = "The row stays as evidence, and everything it claimed is released so "
                           + "the month can be started again.")
    public ApiResponse<HrResponses.PayrollDto> cancel(
            @PathVariable Long id, @RequestBody(required = false) HrRequests.Decision d) {
        return ApiResponse.success(payroll.cancel(id, d), "Payroll cancelled");
    }

    /* ══ Salary payment ════════════════════════════════════════════════ */

    @GetMapping("/{id}/payments")
    @Operation(summary = "What has been paid against a run")
    public ApiResponse<List<HrResponses.SalaryPaymentDto>> payments(@PathVariable Long id) {
        return ApiResponse.success(payroll.payments(id));
    }

    @PostMapping("/pay")
    @Operation(summary = "Record a salary payment",
               description = "Only a posted run can be paid. Omit payrollLineId to pay everybody "
                           + "still owed — one row per payslip, so cancelling one person's payment "
                           + "later does not unpick everyone's.")
    public ApiResponse<List<HrResponses.SalaryPaymentDto>> pay(
            @Valid @RequestBody HrRequests.SalaryPayment req) {
        return ApiResponse.success(payroll.pay(req), "Payment recorded");
    }

    @PostMapping("/payments/{paymentId}/cancel")
    @Operation(summary = "Cancel a salary payment",
               description = "The payslip goes back to owing what it owed, and the run's paid total "
                           + "is recomputed from the live payments rather than adjusted.")
    public ApiResponse<HrResponses.SalaryPaymentDto> cancelPayment(
            @PathVariable Long paymentId, @RequestBody(required = false) HrRequests.Decision d) {
        return ApiResponse.success(payroll.cancelPayment(paymentId, d), "Payment cancelled");
    }
}
