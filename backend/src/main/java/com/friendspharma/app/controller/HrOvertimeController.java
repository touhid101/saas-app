package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.request.HrRequests;
import com.friendspharma.app.dto.response.HrResponses;
import com.friendspharma.app.service.impl.HrOvertimeServiceImpl;
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
 * Overtime claims.
 *
 * <p>Authorisation is the matrix right on Attendance (MOD33), checked in the
 * service. Claiming needs Create; deciding needs Approve — so somebody can
 * claim their own overtime without being able to grant it.
 *
 * <p>An approved claim writes its minutes onto the attendance row for the day.
 * Payroll reads that row, as it always has; the claim itself never reaches a
 * payslip, which is what keeps an approved hour from being paid twice.
 */
@RestController
@RequestMapping("/api/v1/hr/overtime")
@RequiredArgsConstructor
@Tag(name = "HR — Overtime",
     description = "Claims and approvals. Approval writes minutes onto the attendance register, "
                 + "which is the single figure payroll values.")
public class HrOvertimeController {

    private final HrOvertimeServiceImpl overtime;

    @GetMapping
    @Operation(summary = "Search overtime claims")
    public ApiResponse<PageResponse<HrResponses.OvertimeDto>> search(
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(
                overtime.search(employeeId, status, from, to, PageRequest.of(page, Math.min(size, 100))));
    }

    @GetMapping("/pending")
    @Operation(summary = "The approval queue")
    public ApiResponse<List<HrResponses.OvertimeDto>> pending() {
        return ApiResponse.success(overtime.pending());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Claim overtime for a day",
               description = "Saved as a draft unless submit=true. Refused where the branch's "
                           + "working policy does not allow overtime, or where the claim is under "
                           + "the minimum that policy counts. What an hour is worth is a separate "
                           + "question, answered by the salary structure.")
    public ApiResponse<HrResponses.OvertimeDto> create(@Valid @RequestBody HrRequests.Overtime req) {
        return ApiResponse.created(overtime.create(req));
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "Submit a draft claim")
    public ApiResponse<HrResponses.OvertimeDto> submit(@PathVariable Long id) {
        return ApiResponse.success(overtime.submit(id), "Claim submitted");
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve overtime",
               description = "Adds the approved minutes to the attendance row for that date and "
                           + "records which row was written, so a later cancellation takes back "
                           + "exactly what this claim added. Fewer minutes than claimed may be "
                           + "approved; more may not. There must be attendance for the day.")
    public ApiResponse<HrResponses.OvertimeDto> approve(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) HrRequests.OvertimeDecision d) {
        return ApiResponse.success(overtime.approve(id, d), "Overtime approved");
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Reject overtime")
    public ApiResponse<HrResponses.OvertimeDto> reject(
            @PathVariable Long id, @RequestBody(required = false) HrRequests.Decision d) {
        return ApiResponse.success(overtime.reject(id, d), "Overtime rejected");
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a claim",
               description = "An approved claim subtracts its own minutes from the day it wrote "
                           + "them to — never zeroing the day, which would discard overtime "
                           + "recorded for another reason.")
    public ApiResponse<HrResponses.OvertimeDto> cancel(
            @PathVariable Long id, @RequestBody(required = false) HrRequests.Decision d) {
        return ApiResponse.success(overtime.cancel(id, d), "Claim cancelled");
    }
}
