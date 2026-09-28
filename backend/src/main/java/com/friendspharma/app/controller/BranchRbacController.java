package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.service.impl.BranchScopeService;
import com.friendspharma.app.service.impl.TenantOnboardingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * RBAC for one pharmacy, run by the person who runs that pharmacy.
 *
 * <p><b>The same work the platform console does, from inside a branch.</b> Every
 * method here delegates to {@link TenantOnboardingService} — the service behind
 * the Pharmacies page — so a branch manager and a Super Admin setting the same
 * person's rights go through one implementation and cannot reach different
 * answers. No new tables, no second copy of the permission rules.
 *
 * <p><b>The branch is never in the URL.</b> It is resolved from the session on
 * every call. That is the whole isolation story: there is no path variable to
 * change, so a branch manager cannot address another pharmacy however the
 * request is constructed — not merely refused, but unable to ask. The console's
 * own endpoints keep the branch in the path because a platform operator is
 * legitimately choosing between pharmacies; here there is nothing to choose.
 *
 * <p><b>What a branch may not do.</b> Modules and roles are the platform's, and
 * are not writable from here or from {@code RbacController}, which now reserves
 * those to ROLE01. A branch works within the module grant its plan gives it —
 * {@link #modules()} reports that ceiling, and the permission rules below refuse
 * to exceed it, the same way they do for the console.
 */
@RestController
@RequestMapping("/api/v1/branch-rbac")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ROLE01','ROLE02')")
@Tag(name = "Branch RBAC", description = "Users and permissions within one pharmacy")
public class BranchRbacController {

    private final TenantOnboardingService svc;
    private final BranchScopeService branchScope;

    /**
     * The pharmacy this request is about.
     *
     * <p>{@code resolve(null)} is the session's own branch, and it refuses
     * rather than widening when there is none — an account with no branch has no
     * pharmacy to administer. A platform operator acting as a customer resolves
     * to that customer, so support sees exactly what the manager sees.
     */
    private Long myBranch() {
        return branchScope.resolve(null);
    }

    @GetMapping("/modules")
    @Operation(summary = "What this pharmacy's plan includes",
               description = "The ceiling the Super Admin granted. Everything the roles allow "
                           + "when nothing has been granted — an empty grant and an empty list "
                           + "mean opposite things, and the reply says which.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> modules() {
        return ResponseEntity.ok(ApiResponse.ok(svc.modulesFor(myBranch())));
    }

    @GetMapping("/users/{userId}/permissions")
    @Operation(summary = "One person's effective permissions",
               description = "Each right reports what the role grants, what the plan allows, what "
                           + "has been set for this person, and the effective answer. A user from "
                           + "another pharmacy is refused — the branch comes from the session.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> userPermissions(@PathVariable Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(svc.userPermissions(myBranch(), userId)));
    }

    @PutMapping("/users/{userId}/permissions")
    @Operation(summary = "Set what is different about this person",
               description = "Only exceptions are stored. A right matching the role is cleared, so "
                           + "that person keeps following the role if the role later changes. An "
                           + "override can narrow what the role grants, never widen it, and never "
                           + "past what the plan allows.")
    @SuppressWarnings("unchecked")
    public ResponseEntity<ApiResponse<Integer>> setUserPermissions(
            @PathVariable Long userId, @RequestBody Map<String, Object> body) {

        Object raw = body == null ? null : body.get("modules");
        List<Map<String, Object>> rows = raw instanceof List<?> l
                ? (List<Map<String, Object>>) l : List.of();

        int kept = svc.setUserPermissions(myBranch(), userId, rows);
        return ResponseEntity.ok(ApiResponse.ok(
                kept == 0 ? "This person now follows their role exactly."
                          : kept + " module(s) differ from the role.", kept));
    }
}
