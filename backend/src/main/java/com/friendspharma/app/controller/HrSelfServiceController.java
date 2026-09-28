package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.request.HrRequests;
import com.friendspharma.app.dto.response.HrResponses;
import com.friendspharma.app.dto.response.HrSelfServiceResponses;
import com.friendspharma.app.service.impl.HrSelfServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Employee self-service — <b>my</b> HR.
 *
 * <p>Every path here is about the signed-in user and nobody else. There is no
 * {@code employeeId} anywhere in these URLs: the employee is whoever the login
 * is linked to, resolved on the server, and an id that does appear (a claim,
 * a loan, a day of attendance) names something that must already be theirs.
 * Anything that is not theirs answers <i>not found</i>.
 *
 * <p>The four filing bodies are validated in the service rather than with
 * {@code @Valid} here: their {@code employeeId} is required by the module, and
 * it is the server that fills it in — from the login — before the check runs.
 *
 * <p>No decision is reachable from here. Approving, rejecting, paying and
 * disbursing stay on the HR screens, behind the module rights.
 */
@RestController
@RequestMapping("/api/v1/hr/me")
@RequiredArgsConstructor
@Tag(name = "HR — Self-service",
     description = "The signed-in employee's own HR: their record, their figures, their requests, "
                 + "and the forms to file new ones. Ownership is resolved on the server; nothing "
                 + "here takes an employee id from the caller.")
public class HrSelfServiceController {

    private final HrSelfServiceImpl me;

    @GetMapping
    @Operation(summary = "Who I am, as an employee",
               description = "linked=false when this login has no employee record behind it.")
    public ApiResponse<HrSelfServiceResponses.Me> whoAmI() {
        return ApiResponse.success(me.me());
    }

    @GetMapping("/summary")
    @Operation(summary = "My figures: leave, requests, attendance, money, activity")
    public ApiResponse<HrSelfServiceResponses.Summary> summary() {
        return ApiResponse.success(me.summary());
    }

    @GetMapping("/requests")
    @Operation(summary = "My requests across every module, one shape, newest first",
               description = "type: LEAVE, EXPENSE, ADVANCE, LOAN, ATTENDANCE. status: DRAFT, PENDING, "
                           + "APPROVED, PROCESSING, COMPLETED, REJECTED, CANCELLED. q searches number, "
                           + "title, description and stage.")
    public ApiResponse<PageResponse<HrSelfServiceResponses.RequestItem>> requests(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(me.requests(type, status, q, page, Math.min(size, 100)));
    }

    @GetMapping("/requests/{type}/{id}")
    @Operation(summary = "One of my requests, with its lifecycle, chain and history")
    public ApiResponse<HrSelfServiceResponses.RequestDetail> detail(@PathVariable String type,
                                                                     @PathVariable Long id) {
        return ApiResponse.success(me.detail(type, id));
    }

    @PostMapping("/requests/{type}/{id}/cancel")
    @Operation(summary = "Withdraw one of my requests",
               description = "Only what is still mine to withdraw: a draft or a request nobody has decided. "
                           + "Attendance corrections cannot be withdrawn.")
    public ApiResponse<HrSelfServiceResponses.RequestItem> cancel(@PathVariable String type,
                                                                   @PathVariable Long id,
                                                                   @RequestBody(required = false) Map<String, String> body) {
        return ApiResponse.success(me.cancel(type, id, body == null ? null : body.get("reason")), "Request withdrawn");
    }

    /* ── Attendance ── */

    @GetMapping("/attendance")
    @Operation(summary = "My attendance for a month, with holidays, weekends and the summary",
               description = "month as YYYY-MM; the current month when omitted.")
    public ApiResponse<HrSelfServiceResponses.AttendanceMonth> attendance(@RequestParam(required = false) String month) {
        return ApiResponse.success(me.attendanceMonth(month));
    }

    @GetMapping("/calendar")
    @Operation(summary = "My calendar for a month: holidays, my leave, my dates")
    public ApiResponse<HrResponses.HrCalendarDto> calendar(@RequestParam(required = false) String month) {
        return ApiResponse.success(me.calendar(month));
    }

    @PostMapping("/attendance/{attendanceId}/correction")
    @Operation(summary = "Ask for one of my recorded days to be corrected",
               description = "The day keeps saying what it says until somebody with Approve decides.")
    public ApiResponse<HrResponses.AttendanceDto> correction(@PathVariable Long attendanceId,
                                                              @Valid @RequestBody HrRequests.AttendanceCorrection req) {
        return ApiResponse.success(me.requestCorrection(attendanceId, req), "Correction requested");
    }

    /* ── Leave ── */

    @GetMapping("/leave/balances")
    @Operation(summary = "My leave balances for this year")
    public ApiResponse<List<HrResponses.LeaveBalanceDto>> leaveBalances() {
        return ApiResponse.success(me.leaveBalances());
    }

    @GetMapping("/leave/types")
    @Operation(summary = "The kinds of leave I can apply for")
    public ApiResponse<List<HrResponses.LeaveTypeDto>> leaveTypes() {
        return ApiResponse.success(me.leaveTypes());
    }

    @PostMapping("/leave")
    @Operation(summary = "Apply for leave",
               description = "Filed and submitted in one step. The days are counted on the server from "
                           + "the working calendar; overlap and balance are checked there too.")
    public ApiResponse<HrResponses.LeaveRequestDto> applyLeave(@RequestBody HrRequests.LeaveRequest req) {
        return ApiResponse.success(me.fileLeave(req), "Leave request submitted");
    }

    /* ── Expenses ── */

    @GetMapping("/expenses/types")
    @Operation(summary = "The kinds of expense I can claim")
    public ApiResponse<List<HrResponses.ExpenseTypeDto>> expenseTypes() {
        return ApiResponse.success(me.expenseTypes());
    }

    @PostMapping("/expenses")
    @Operation(summary = "File an expense claim",
               description = "submit=false keeps it as a draft so receipts can be attached to its lines first.")
    public ApiResponse<HrResponses.ExpenseClaimDto> fileExpense(@RequestBody HrRequests.ExpenseClaim req) {
        return ApiResponse.success(me.fileExpense(req),
                Boolean.TRUE.equals(req.getSubmit()) ? "Expense claim submitted" : "Expense claim saved as a draft");
    }

    @PostMapping("/expenses/{claimId}/submit")
    @Operation(summary = "Submit one of my draft claims")
    public ApiResponse<HrResponses.ExpenseClaimDto> submitExpense(@PathVariable Long claimId) {
        return ApiResponse.success(me.submitExpense(claimId), "Expense claim submitted");
    }

    @PostMapping(value = "/expenses/lines/{lineId}/receipt", consumes = "multipart/form-data")
    @Operation(summary = "Attach a receipt to one line of my draft claim")
    public ApiResponse<HrResponses.ExpenseLineDto> attachReceipt(@PathVariable Long lineId,
                                                                  @RequestParam("file") MultipartFile file) {
        return ApiResponse.success(me.attachReceipt(lineId, file), "Receipt attached");
    }

    @DeleteMapping("/expenses/lines/{lineId}/receipt")
    @Operation(summary = "Remove the receipt from one line of my draft claim")
    public ApiResponse<Void> removeReceipt(@PathVariable Long lineId) {
        me.removeReceipt(lineId);
        return ApiResponse.success(null, "Receipt removed");
    }

    /* ── Money ── */

    @PostMapping("/advances")
    @Operation(summary = "Request a salary advance")
    public ApiResponse<HrResponses.AdvanceDto> requestAdvance(@RequestBody HrRequests.Advance req) {
        return ApiResponse.success(me.requestAdvance(req), "Advance requested");
    }

    @PostMapping("/loans")
    @Operation(summary = "Request a loan")
    public ApiResponse<HrResponses.LoanDto> requestLoan(@RequestBody HrRequests.Loan req) {
        return ApiResponse.success(me.requestLoan(req), "Loan requested");
    }
}
