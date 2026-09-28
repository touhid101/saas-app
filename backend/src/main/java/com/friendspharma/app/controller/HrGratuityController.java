package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.request.HrRequests;
import com.friendspharma.app.dto.response.HrResponses;
import com.friendspharma.app.service.impl.HrGratuityServiceImpl;
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
 * Gratuity.
 *
 * <p>Authorisation is the matrix right on <b>Employees (MOD32)</b>, the module
 * separations already sit under: gratuity is part of somebody leaving.
 * Proposing needs Create; approving, paying and setting the policy need Approve
 * or Edit.
 *
 * <p><b>No formula is shipped.</b> Days per year, the wage basis, the
 * days-in-a-month divisor, the qualifying period and what a part year is worth
 * are all P-08, and with no policy set nothing is computed for anybody. The
 * estimate answers "not configured" rather than nought — the absence of an
 * answer is not the same as an answer of zero.
 */
@RestController
@RequestMapping("/api/v1/hr/gratuity")
@RequiredArgsConstructor
@Tag(name = "HR — Gratuity",
     description = "The formula, an estimate that writes nothing, and awards whose figures are "
                 + "frozen at approval.")
public class HrGratuityController {

    private final HrGratuityServiceImpl gratuity;

    /* ── The policy ────────────────────────────────────────────────────── */

    @GetMapping("/policy")
    @Operation(summary = "The policy in force, or nothing",
               description = "Null when the branch has none. A policy missing its days per year or "
                           + "its divisor comes back with complete=false, and still computes "
                           + "nothing — the blanks are not read as zero.")
    public ApiResponse<HrResponses.GratuityPolicyDto> policy() {
        return ApiResponse.success(gratuity.current());
    }

    @GetMapping("/policy/history")
    @Operation(summary = "Every policy this branch has had")
    public ApiResponse<List<HrResponses.GratuityPolicyDto>> policyHistory() {
        return ApiResponse.success(gratuity.history());
    }

    @PostMapping("/policy")
    @Operation(summary = "Set the policy",
               description = "Supersedes the live one rather than editing it: an award already "
                           + "approved was approved under the old one.")
    public ApiResponse<HrResponses.GratuityPolicyDto> setPolicy(
            @Valid @RequestBody HrRequests.GratuityPolicy req) {
        return ApiResponse.success(gratuity.setPolicy(req), "Gratuity policy set");
    }

    /* ── The estimate ──────────────────────────────────────────────────── */

    @GetMapping("/estimate/{employeeId}")
    @Operation(summary = "What somebody would be owed",
               description = "Writes nothing. Shows the whole working — service, the wage, the "
                           + "daily rate, the days — so the figure can be argued with rather than "
                           + "just believed.")
    public ApiResponse<HrResponses.GratuityEstimateDto> estimate(
            @PathVariable Long employeeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ApiResponse.success(gratuity.estimate(employeeId, asOf));
    }

    /* ── Awards ────────────────────────────────────────────────────────── */

    @GetMapping
    @Operation(summary = "Search awards")
    public ApiResponse<PageResponse<HrResponses.GratuityAwardDto>> search(
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(
                gratuity.search(employeeId, status, PageRequest.of(page, Math.min(size, 100))));
    }

    @GetMapping("/pending")
    @Operation(summary = "The approval queue")
    public ApiResponse<List<HrResponses.GratuityAwardDto>> pending() {
        return ApiResponse.success(gratuity.pending());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Award gratuity",
               description = "Refused where the branch has no complete policy, and where the "
                           + "employee has not served the qualifying period. Saved as a draft "
                           + "unless submit=true.")
    public ApiResponse<HrResponses.GratuityAwardDto> create(
            @Valid @RequestBody HrRequests.GratuityAward req) {
        return ApiResponse.created(gratuity.create(req));
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "Submit a draft award")
    public ApiResponse<HrResponses.GratuityAwardDto> submit(@PathVariable Long id) {
        return ApiResponse.success(gratuity.submit(id), "Award submitted");
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve an award",
               description = "The figures are worked out once more at this moment and then frozen. "
                           + "Nothing recomputes them afterwards — an award recalculated later is a "
                           + "decision changed after it was made.")
    public ApiResponse<HrResponses.GratuityAwardDto> approve(
            @PathVariable Long id, @RequestBody(required = false) HrRequests.Decision d) {
        return ApiResponse.success(gratuity.approve(id, d), "Gratuity approved");
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Reject an award")
    public ApiResponse<HrResponses.GratuityAwardDto> reject(
            @PathVariable Long id, @RequestBody(required = false) HrRequests.Decision d) {
        return ApiResponse.success(gratuity.reject(id, d), "Gratuity rejected");
    }

    @PostMapping("/{id}/pay")
    @Operation(summary = "Record the payment",
               description = "Nothing is recomputed; the amount was settled at approval. The final "
                           + "settlement on the separation is untouched — what belongs in one is "
                           + "still an open question (P-05).")
    public ApiResponse<HrResponses.GratuityAwardDto> pay(
            @PathVariable Long id, @Valid @RequestBody HrRequests.GratuityPayment req) {
        return ApiResponse.success(gratuity.pay(id, req), "Gratuity paid");
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel an award",
               description = "Not available once paid — cancelling would not take the money back.")
    public ApiResponse<HrResponses.GratuityAwardDto> cancel(
            @PathVariable Long id, @RequestBody(required = false) HrRequests.Decision d) {
        return ApiResponse.success(gratuity.cancel(id, d), "Award cancelled");
    }
}
