package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.request.HrRequests;
import com.friendspharma.app.dto.response.HrResponses;
import com.friendspharma.app.service.impl.HrLeaveServiceImpl;
import com.friendspharma.app.service.impl.HrSetupServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Leave — types, balances, requests and decisions.
 *
 * <p>Authorisation is the matrix right on Leave (MOD34), checked in the service.
 * Approve and reject need the Approve right; filing needs only Create, so an
 * employee can ask for their own leave without being able to grant it.
 */
@RestController
@RequestMapping("/api/v1/hr/leave")
@RequiredArgsConstructor
@Tag(name = "HR — Leave",
     description = "Requests, approvals and balances. A submitted request reserves its days so "
                 + "two requests filed the same morning cannot both be approved into an overdraft.")
public class HrLeaveController {

    private final HrLeaveServiceImpl leave;
    private final HrSetupServiceImpl setup;

    /* ── Types ─────────────────────────────────────────────────────────── */

    @GetMapping("/types")
    @Operation(summary = "The kinds of leave this branch grants",
               description = "A type whose annual quota has not been set carries quotaSet=false. "
                           + "Balances cannot be opened for it — the quota is policy, and V73 "
                           + "seeds the names without inventing the number.")
    public ApiResponse<List<HrResponses.LeaveTypeDto>> types(
            @RequestParam(defaultValue = "true") boolean activeOnly) {
        return ApiResponse.success(setup.listLeaveTypes(activeOnly));
    }

    @PostMapping("/types")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a leave type")
    public ApiResponse<HrResponses.LeaveTypeDto> createType(
            @Valid @RequestBody HrRequests.LeaveType req) {
        return ApiResponse.created(setup.createLeaveType(req));
    }

    @PutMapping("/types/{id}")
    @Operation(summary = "Change a leave type")
    public ApiResponse<HrResponses.LeaveTypeDto> updateType(
            @PathVariable Long id, @Valid @RequestBody HrRequests.LeaveType req) {
        return ApiResponse.success(setup.updateLeaveType(id, req), "Leave type updated");
    }

    @DeleteMapping("/types/{id}")
    @Operation(summary = "Retire a leave type")
    public ApiResponse<Void> retireType(@PathVariable Long id) {
        setup.deactivateLeaveType(id);
        return ApiResponse.success(null, "Leave type retired");
    }

    /* ── Balances ──────────────────────────────────────────────────────── */

    @GetMapping("/balances/{employeeId}")
    @Operation(summary = "One person's balances for a year")
    public ApiResponse<List<HrResponses.LeaveBalanceDto>> balances(
            @PathVariable Long employeeId, @RequestParam(required = false) Integer year) {
        return ApiResponse.success(leave.balances(employeeId, year));
    }

    @PostMapping("/balances")
    @Operation(summary = "Open or adjust a balance",
               description = "The entitlement is copied from the type's quota, not joined to it, so "
                           + "changing next year's policy does not rewrite what somebody was "
                           + "entitled to last year.")
    public ApiResponse<HrResponses.LeaveBalanceDto> openBalance(
            @Valid @RequestBody HrRequests.LeaveBalance req) {
        return ApiResponse.success(leave.openBalance(req), "Balance saved");
    }

    /* ── Requests ──────────────────────────────────────────────────────── */

    @GetMapping
    @Operation(summary = "Search leave requests")
    public ApiResponse<PageResponse<HrResponses.LeaveRequestDto>> search(
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(
                leave.search(employeeId, status, from, to, PageRequest.of(page, Math.min(size, 100))));
    }

    @GetMapping("/pending")
    @Operation(summary = "The approval queue")
    public ApiResponse<List<HrResponses.LeaveRequestDto>> pending() {
        return ApiResponse.success(leave.pending());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "File a leave request",
               description = "Saved as a draft unless submit=true. Submitting reserves the days "
                           + "against the balance, so a request that could not be honoured is "
                           + "refused when it is filed rather than a week later.")
    public ApiResponse<HrResponses.LeaveRequestDto> create(
            @Valid @RequestBody HrRequests.LeaveRequest req) {
        return ApiResponse.created(leave.create(req));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Change a draft request",
               description = "Only a draft. A submitted request is cancelled and filed again "
                           + "rather than changed under the approver.")
    public ApiResponse<HrResponses.LeaveRequestDto> update(
            @PathVariable Long id, @Valid @RequestBody HrRequests.LeaveRequest req) {
        return ApiResponse.success(leave.update(id, req), "Request updated");
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "Submit a draft request")
    public ApiResponse<HrResponses.LeaveRequestDto> submit(@PathVariable Long id) {
        return ApiResponse.success(leave.submit(id), "Request submitted");
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve leave",
               description = "Moves the reserved days from pending to taken and writes LEAVE "
                           + "attendance for the days covered, tagged with this request so "
                           + "cancelling later removes exactly those rows.")
    public ApiResponse<HrResponses.LeaveRequestDto> approve(
            @PathVariable Long id, @RequestBody(required = false) HrRequests.Decision d) {
        return ApiResponse.success(leave.approve(id, d), "Leave approved");
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Reject leave")
    public ApiResponse<HrResponses.LeaveRequestDto> reject(
            @PathVariable Long id, @RequestBody(required = false) HrRequests.Decision d) {
        return ApiResponse.success(leave.reject(id, d), "Leave rejected");
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel leave",
               description = "An approved request gives its days back and takes its attendance "
                           + "rows with it.")
    public ApiResponse<HrResponses.LeaveRequestDto> cancel(
            @PathVariable Long id, @RequestBody(required = false) HrRequests.Decision d) {
        return ApiResponse.success(leave.cancel(id, d), "Leave cancelled");
    }
}
