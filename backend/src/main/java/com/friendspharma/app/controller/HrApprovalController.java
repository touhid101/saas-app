package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.dto.request.HrRequests;
import com.friendspharma.app.dto.response.HrResponses;
import com.friendspharma.app.service.impl.HrApprovalAdminServiceImpl;
import com.friendspharma.app.service.impl.HrScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Approval chains, and the one screen that says what is waiting.
 *
 * <p><b>Nothing is configured out of the box.</b> With no flow for a workflow,
 * that workflow decides the way it decided before this existed: one Approve
 * right, one decision. A chain only ever narrows who may sign — it can never
 * hand somebody an authority the RBAC screen did not give them.
 *
 * <p>The inbox is useful either way: it answers "what is waiting on me" across
 * every HR workflow, which was previously six screens.
 */
@RestController
@RequestMapping("/api/v1/hr/approvals")
@RequiredArgsConstructor
@Tag(name = "HR — Approvals",
     description = "Multi-level chains in front of the workflows that already work, and a single "
                 + "inbox across all of them.")
public class HrApprovalController {

    private final HrApprovalAdminServiceImpl approvals;

    @GetMapping("/workflows")
    @Operation(summary = "The workflows a chain can be configured for",
               description = "Payroll is absent on purpose: a run already has five signatures of "
                           + "its own, and a second chain would give it two answers to who signs "
                           + "next.")
    public ApiResponse<List<String>> workflows() {
        return ApiResponse.success(HrScope.WORKFLOWS);
    }

    @GetMapping("/flows")
    @Operation(summary = "The chains this branch has configured",
               description = "Empty is the shipped state and a valid one — every workflow then "
                           + "takes a single signature.")
    public ApiResponse<List<HrResponses.ApprovalFlowDto>> flows() {
        return ApiResponse.success(approvals.listFlows());
    }

    @PostMapping("/flows")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a chain",
               description = "Levels are signed in the order they are given. A level names a role, "
                           + "or MANAGER for the employee's own manager.")
    public ApiResponse<HrResponses.ApprovalFlowDto> createFlow(
            @Valid @RequestBody HrRequests.ApprovalFlow req) {
        return ApiResponse.created(approvals.saveFlow(null, req));
    }

    @PutMapping("/flows/{id}")
    @Operation(summary = "Change a chain",
               description = "Requests already travelling through it are unaffected — the number "
                           + "of levels was copied onto them when they started.")
    public ApiResponse<HrResponses.ApprovalFlowDto> updateFlow(
            @PathVariable Long id, @Valid @RequestBody HrRequests.ApprovalFlow req) {
        return ApiResponse.success(approvals.saveFlow(id, req), "Approval flow saved");
    }

    @DeleteMapping("/flows/{id}")
    @Operation(summary = "Retire a chain",
               description = "Deactivated, never deleted: requests already travelling through it "
                           + "point at it, and they finish under the levels they started with.")
    public ApiResponse<Void> retireFlow(@PathVariable Long id) {
        approvals.retireFlow(id);
        return ApiResponse.success(null, "Approval flow retired");
    }

    @GetMapping("/inbox")
    @Operation(summary = "Everything waiting on me, across every HR workflow",
               description = "Objects in a chain whose current level this reader can sign, and "
                           + "objects merely submitted where no chain is configured. A workflow "
                           + "whose module Approve right the reader lacks is left out entirely.")
    public ApiResponse<List<HrResponses.ApprovalInboxDto>> inbox() {
        return ApiResponse.success(approvals.inbox());
    }

    @GetMapping("/chain/{workflowCode}/{refId}")
    @Operation(summary = "The signatures on one object",
               description = "Including approvals given before a refusal — 'approved by the "
                           + "manager, refused by the administrator' is a different story from "
                           + "'refused'.")
    public ApiResponse<List<HrResponses.ApprovalChainDto>> chain(
            @PathVariable String workflowCode, @PathVariable Long refId) {
        return ApiResponse.success(approvals.chainFor(workflowCode.toUpperCase(), refId));
    }
}
