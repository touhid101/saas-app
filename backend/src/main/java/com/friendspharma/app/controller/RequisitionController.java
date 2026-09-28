package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.request.RequisitionRequest;
import com.friendspharma.app.dto.response.RequisitionResponse;
import com.friendspharma.app.dto.response.RequisitionSplitResponse;
import com.friendspharma.app.service.impl.BranchScopeService;
import com.friendspharma.app.service.impl.RequisitionSplitServiceImpl;
import com.friendspharma.app.security.SecurityUtils;
import com.friendspharma.app.service.interfaces.RequisitionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/requisitions")
@RequiredArgsConstructor
@Tag(name = "Requisition", description = "Purchase requisition workflow: DRAFT→SUBMITTED→PENDING_APPROVAL→APPROVED→CONVERTED")
public class RequisitionController {

    private final RequisitionService requisitionService;
    private final RequisitionSplitServiceImpl splitService;
    private final BranchScopeService branchScope;

    // ── CRUD ───────────────────────────────────────────────────────────────

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new requisition (MANUAL source)")
    public ApiResponse<RequisitionResponse.RequisitionDto> create(
            @Valid @RequestBody RequisitionRequest.CreateRequisitionRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.success(requisitionService.create(req, userId), "Requisition created");
    }

    @PutMapping("/{pidRequisition}")
    @Operation(summary = "Update a DRAFT/SUBMITTED requisition")
    public ApiResponse<RequisitionResponse.RequisitionDto> update(
            @PathVariable Long pidRequisition,
            @Valid @RequestBody RequisitionRequest.UpdateRequisitionRequest req) {
        return ApiResponse.success(requisitionService.update(pidRequisition, req));
    }

    @PatchMapping("/{pidRequisition}/lines/{pidReqDtl}/qty")
    @Operation(summary = "Update a single line qty (used from the approval modal)")
    public ApiResponse<RequisitionResponse.RequisitionDto> updateLineQty(
            @PathVariable Long pidRequisition,
            @PathVariable Long pidReqDtl,
            @Valid @RequestBody RequisitionRequest.UpdateLineQtyRequest req) {
        return ApiResponse.success(
                requisitionService.updateLineQty(pidRequisition, pidReqDtl, req));
    }

    @DeleteMapping("/{pidRequisition}/lines/{pidReqDtl}")
    @Operation(summary = "Remove a line from a requisition that is not yet approved",
               description = "Allowed in the same stages as a quantity change — DRAFT, SUBMITTED "
                           + "and PENDING_APPROVAL. Refused once the requisition is approved, and "
                           + "refused for the last remaining line: cancel the requisition instead "
                           + "of emptying it.")
    public ApiResponse<RequisitionResponse.RequisitionDto> removeLine(
            @PathVariable Long pidRequisition,
            @PathVariable Long pidReqDtl) {
        return ApiResponse.success(requisitionService.removeLine(pidRequisition, pidReqDtl));
    }

    @DeleteMapping("/{pidRequisition}")
    @Operation(summary = "Cancel a requisition")
    public ApiResponse<Void> cancel(
            @PathVariable Long pidRequisition,
            @RequestParam(required = false, defaultValue = "Cancelled by user") String reason) {
        requisitionService.cancel(pidRequisition, reason);
        return ApiResponse.success(null, "Requisition cancelled");
    }

    @GetMapping("/{pidRequisition}")
    @Operation(summary = "Get requisition detail")
    public ApiResponse<RequisitionResponse.RequisitionDto> getById(
            @PathVariable Long pidRequisition) {
        return ApiResponse.success(requisitionService.getById(pidRequisition));
    }

    @GetMapping
    @Operation(summary = "List requisitions with optional stage/date filters",
               description = "The organisation is taken from the signed-in user. It used to be a "
                           + "required query parameter, which both made every caller pass it and "
                           + "let anyone read another tenant's requisitions by changing a number.")
    public ApiResponse<PageResponse<RequisitionResponse.RequisitionSummaryDto>> list(
            @RequestParam(required = false) Long orgId,
            @RequestParam(required = false) Long branchId,
            @RequestParam(defaultValue = "false") boolean allBranches,
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        // A requisition belongs to a branch, so the list shows the caller's
        // branch unless they ask for another or for all of them. Resolved by the
        // same service the Copilot uses, so an unknown branch is a 404 here too
        // rather than an empty page that reads as "no requisitions".
        Long branch = branchScope.resolve(branchId, allBranches);
        var pg = requisitionService.list(SecurityUtils.getCurrentOrgId(), branch, stage, from, to,
                PageRequest.of(page, size, Sort.by("reqDate").descending()));
        return ApiResponse.success(PageResponse.of(pg));
    }

    // ── Workflow transitions ───────────────────────────────────────────────

    @PostMapping("/{pidRequisition}/submit")
    @Operation(summary = "Submit a DRAFT requisition for review")
    public ApiResponse<RequisitionResponse.RequisitionDto> submit(
            @PathVariable Long pidRequisition) {
        return ApiResponse.success(requisitionService.submit(pidRequisition), "Requisition submitted");
    }

    /*
     * No role is named on these three, on purpose.
     *
     * The gate is the Approve tick on Requisition in the RBAC screen, and the
     * service checks it — see RequisitionServiceImpl.requireApproveAuthority.
     * The annotation that was here named ADMIN and MANAGER: MANAGER is granted
     * to nobody, and ADMIN only to Super Admin, so the matrix and the server
     * disagreed for every other role that held the tick.
     */
    @PostMapping("/{pidRequisition}/pending-approval")
    @Operation(summary = "Move to PENDING_APPROVAL (manager review gate)")
    public ApiResponse<RequisitionResponse.RequisitionDto> pendingApproval(
            @PathVariable Long pidRequisition) {
        return ApiResponse.success(requisitionService.pendingApproval(pidRequisition));
    }

    @PostMapping("/{pidRequisition}/approve")
    @Operation(summary = "Approve a PENDING_APPROVAL requisition")
    public ApiResponse<RequisitionResponse.RequisitionDto> approve(
            @PathVariable Long pidRequisition,
            @RequestBody(required = false) RequisitionRequest.ApproveRequest req) {
        Long approverId = SecurityUtils.getCurrentUserId();
        if (req == null) req = new RequisitionRequest.ApproveRequest();
        return ApiResponse.success(requisitionService.approve(pidRequisition, req, approverId),
                "Requisition approved");
    }

    @PostMapping("/{pidRequisition}/reject")
    @Operation(summary = "Reject a requisition")
    public ApiResponse<RequisitionResponse.RequisitionDto> reject(
            @PathVariable Long pidRequisition,
            @Valid @RequestBody RequisitionRequest.RejectRequest req) {
        Long approverId = SecurityUtils.getCurrentUserId();
        return ApiResponse.success(requisitionService.reject(pidRequisition, req, approverId),
                "Requisition rejected");
    }

    /* ══════════════════════════════════════════════════════════════════════
       SPLIT — one requisition into the purchase orders it actually is
       ══════════════════════════════════════════════════════════════════════ */

    @GetMapping("/{pidRequisition}/split-preview")
    @Operation(summary = "Group an approved requisition by supplier, without creating anything",
               description = "Shows which supplier each line would go to and why, priced at what "
                           + "was last actually paid. Lines nobody can be found for are listed "
                           + "separately rather than dropped. Nothing is created by this call.")
    public ApiResponse<RequisitionSplitResponse.Preview> splitPreview(
            @PathVariable Long pidRequisition,
            @RequestParam(required = false) String overrides) {
        return ApiResponse.success(
                splitService.preview(pidRequisition, parseOverrides(overrides)));
    }

    /**
     * The orders this requisition has already been split into.
     *
     * <p>So the supplier copies can be printed again. The split reports them
     * once, in the dialog that confirms it; a buyer who closed it — or who comes
     * back to re-send a sheet a supplier mislaid — needs them back.
     */
    @GetMapping("/{pidRequisition}/orders")
    @Operation(summary = "The purchase orders this requisition became",
               description = "One entry per supplier, for reprinting the copies sent to them. "
                           + "Empty until the requisition has been split.")
    public ApiResponse<List<RequisitionSplitResponse.Created>> orders(@PathVariable Long pidRequisition) {
        return ApiResponse.success(splitService.ordersOf(pidRequisition));
    }

    @PostMapping("/{pidRequisition}/split")
    @Operation(summary = "Create one draft purchase order per supplier",
               description = "Creates DRAFT purchases — no stock moves and no payable is raised "
                           + "until each one is approved through the normal purchase flow. Pass "
                           + "overrides as {reqLineId: supplierId} to move a line to a different "
                           + "supplier. Refuses while any line is unassigned unless allowPartial "
                           + "is set, because half a placed order is worse than none.")
    public ApiResponse<RequisitionSplitResponse.Result> split(
            @PathVariable Long pidRequisition,
            @RequestBody(required = false) Map<String, Object> body) {

        Map<String, Object> b = body == null ? Map.of() : body;
        Map<Long, Long> overrides = new LinkedHashMap<>();
        if (b.get("overrides") instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                if (k != null && v != null) {
                    overrides.put(Long.valueOf(k.toString()), Long.valueOf(v.toString()));
                }
            });
        }
        boolean allowPartial = Boolean.TRUE.equals(b.get("allowPartial"))
                || "true".equalsIgnoreCase(String.valueOf(b.get("allowPartial")));

        return ApiResponse.success(
                splitService.split(pidRequisition, overrides, allowPartial),
                "Purchase orders drafted — review each one before approving");
    }

    /** Query-string form: "12:901,13:1" — line id to supplier id. */
    private static Map<Long, Long> parseOverrides(String raw) {
        Map<Long, Long> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return out;
        for (String pair : raw.split(",")) {
            String[] kv = pair.split(":");
            if (kv.length == 2) {
                try { out.put(Long.valueOf(kv[0].trim()), Long.valueOf(kv[1].trim())); }
                catch (NumberFormatException ignored) { }
            }
        }
        return out;
    }

    /* Same gate, same reason: converting is what approving is for, and the
       annotation here named the same two authorities nobody outside Super Admin
       could hold. Checked in the service. */
    @PostMapping("/{pidRequisition}/convert")
    @Operation(summary = "Convert an APPROVED requisition to a purchase order")
    public ApiResponse<RequisitionResponse.RequisitionDto> convert(
            @PathVariable Long pidRequisition,
            @RequestParam Long pidPurchaseOrder) {
        return ApiResponse.success(
                requisitionService.convert(pidRequisition, pidPurchaseOrder),
                "Requisition converted to purchase order");
    }

    // ── Stats ──────────────────────────────────────────────────────────────

    /**
     * The approvals waiting in this branch.
     *
     * <p>The organisation came off a query parameter and the branch was not
     * considered at all, so the badge counted every pharmacy in the organisation
     * — and any caller could count a different organisation's by changing the
     * number in the URL. Both now come from the session, exactly as the list
     * endpoint above resolves them.
     */
    @GetMapping("/count/pending-approval")
    @Operation(summary = "Count requisitions pending approval — used by dashboard badge",
               description = "Scoped to your own branch. Only an administrator may name another "
                           + "or ask for all of them.")
    public ApiResponse<Long> countPendingApproval(
            @RequestParam(required = false) Long branchId,
            @RequestParam(defaultValue = "false") boolean allBranches) {
        return ApiResponse.success(requisitionService.countPendingApproval(
                branchScope.resolve(branchId, allBranches)));
    }
}
