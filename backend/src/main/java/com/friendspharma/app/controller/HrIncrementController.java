package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.request.HrRequests;
import com.friendspharma.app.dto.response.HrResponses;
import com.friendspharma.app.service.impl.HrIncrementServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Salary increments.
 *
 * <p>Authorisation is the matrix right on Employees (MOD32), checked in the
 * service. Proposing needs Create; approving and applying need Approve.
 *
 * <p>Approving freezes the three money figures. Applying writes the frozen new
 * salary onto the employee — and refuses if that salary has moved since the
 * decision, rather than overwriting a change nobody looked at.
 */
@RestController
@RequestMapping("/api/v1/hr/increments")
@RequiredArgsConstructor
@Tag(name = "HR — Increments",
     description = "Proposals, approvals and the salary history. The approved amount is frozen at "
                 + "the decision and never recomputed.")
public class HrIncrementController {

    private final HrIncrementServiceImpl increments;

    @GetMapping
    @Operation(summary = "Search increments")
    public ApiResponse<PageResponse<HrResponses.IncrementDto>> search(
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(
                increments.search(employeeId, status, PageRequest.of(page, Math.min(size, 100))));
    }

    @GetMapping("/pending")
    @Operation(summary = "The approval queue")
    public ApiResponse<List<HrResponses.IncrementDto>> pending() {
        return ApiResponse.success(increments.pending());
    }

    @GetMapping("/due")
    @Operation(summary = "Approved and now due to be applied",
               description = "Approved increments whose effective date has arrived and which have "
                           + "not yet been written to the employee's salary.")
    public ApiResponse<List<HrResponses.IncrementDto>> due() {
        return ApiResponse.success(increments.due());
    }

    @GetMapping("/employee/{employeeId}")
    @Operation(summary = "One person's salary history",
               description = "HR_EMPLOYEES holds only the current basic salary; these rows are the "
                           + "record of how it got there.")
    public ApiResponse<List<HrResponses.IncrementDto>> history(@PathVariable Long employeeId) {
        return ApiResponse.success(increments.history(employeeId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Propose an increment",
               description = "Saved as a draft unless submit=true. The money figures shown on a "
                           + "draft are indicative; approval is what freezes them.")
    public ApiResponse<HrResponses.IncrementDto> create(@Valid @RequestBody HrRequests.Increment req) {
        return ApiResponse.created(increments.create(req));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Change a draft proposal")
    public ApiResponse<HrResponses.IncrementDto> update(
            @PathVariable Long id, @Valid @RequestBody HrRequests.Increment req) {
        return ApiResponse.success(increments.update(id, req), "Increment updated");
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "Submit a draft proposal")
    public ApiResponse<HrResponses.IncrementDto> submit(@PathVariable Long id) {
        return ApiResponse.success(increments.submit(id), "Increment submitted");
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve an increment",
               description = "Freezes previous salary, increment amount and new salary. An amount "
                           + "in the body is the approver settling on a figure; after this the "
                           + "increment is that number, not a percentage to be worked out again.")
    public ApiResponse<HrResponses.IncrementDto> approve(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) HrRequests.IncrementDecision d) {
        return ApiResponse.success(increments.approve(id, d), "Increment approved");
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Reject an increment")
    public ApiResponse<HrResponses.IncrementDto> reject(
            @PathVariable Long id, @RequestBody(required = false) HrRequests.Decision d) {
        return ApiResponse.success(increments.reject(id, d), "Increment rejected");
    }

    @PostMapping("/{id}/apply")
    @Operation(summary = "Apply an approved increment to the salary",
               description = "Refused before the effective date, and refused when the basic salary "
                           + "has changed since approval — the figure moved underneath the "
                           + "decision, and overwriting would discard whatever change did that. "
                           + "Payroll runs already calculated are not touched.")
    public ApiResponse<HrResponses.IncrementDto> apply(@PathVariable Long id) {
        return ApiResponse.success(increments.apply(id), "Increment applied");
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel an increment",
               description = "Not available once applied: that row is the salary history. Propose "
                           + "a further increment instead.")
    public ApiResponse<HrResponses.IncrementDto> cancel(
            @PathVariable Long id, @RequestBody(required = false) HrRequests.Decision d) {
        return ApiResponse.success(increments.cancel(id, d), "Increment cancelled");
    }
}
