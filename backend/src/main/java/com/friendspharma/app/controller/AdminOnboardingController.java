package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.entity.AclPermTemplate;
import com.friendspharma.app.entity.AllBranch;
import com.friendspharma.app.entity.TenantRegistration;
import com.friendspharma.app.repository.AclPermTemplateRepository;
import com.friendspharma.app.service.impl.TenantOnboardingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The approval queue: who has applied, and turning one of them into a pharmacy.
 *
 * <p>Super Admin only, and on the subscription filter's open list — an expired
 * branch must stay administrable, and that exemption is safe because the role
 * check here is what actually guards it.
 */
@RestController
@RequestMapping("/api/admin/onboarding")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ROLE01')")
@Tag(name = "Onboarding administration", description = "Approve or reject pharmacies — Super Admin only")
public class AdminOnboardingController {

    private final TenantOnboardingService svc;
    private final AclPermTemplateRepository templateRepo;

    @GetMapping("/registrations")
    @Operation(summary = "Who has applied",
               description = "status = PENDING, APPROVED, REJECTED or ALL.")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> queue(
            @RequestParam(defaultValue = "PENDING") String status) {
        return ResponseEntity.ok(ApiResponse.ok(svc.queue(status).stream().map(this::toRow).toList()));
    }

    @GetMapping("/registrations/pending-count")
    @Operation(summary = "How many are waiting, for the badge on the menu")
    public ResponseEntity<ApiResponse<Long>> pending() {
        return ResponseEntity.ok(ApiResponse.ok(svc.pendingCount()));
    }

    @GetMapping("/templates")
    @Operation(summary = "The permission templates a pharmacy can be activated on",
               description = "Active ones only by default — that is what the activation dropdown "
                           + "wants. Pass includeRetired=true for the screen that edits them, "
                           + "which has to show what has been taken off the list as well.")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> templates(
            @RequestParam(defaultValue = "false") boolean includeRetired) {

        Map<Long, Integer> counts = svc.templateLineCounts();
        List<AclPermTemplate> rows = includeRetired
                ? svc.allTemplates()
                : templateRepo.findByIsActiveOrderBySortOrder(1);

        return ResponseEntity.ok(ApiResponse.ok(
                rows.stream().map(t -> templateRow(t, counts)).toList()));
    }

    @PostMapping("/templates")
    @Operation(summary = "Add a permission template")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addTemplate(@RequestBody Map<String, Object> b) {
        AclPermTemplate t = saveTemplate(null, b);
        return ResponseEntity.ok(ApiResponse.ok("Template added.", templateRow(t, svc.templateLineCounts())));
    }

    @PutMapping("/templates/{templateId}")
    @Operation(summary = "Edit a permission template",
               description = "Changes what the next activation grants. A pharmacy already running "
                           + "keeps the permissions it was given — they were copied at activation, "
                           + "not linked. Use POST /branches/{branchId}/template to re-apply.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> editTemplate(
            @PathVariable Long templateId, @RequestBody Map<String, Object> b) {
        AclPermTemplate t = saveTemplate(templateId, b);
        return ResponseEntity.ok(ApiResponse.ok("Template updated.", templateRow(t, svc.templateLineCounts())));
    }

    @PatchMapping("/templates/{templateId}/active")
    @Operation(summary = "Take a template off the list, or put it back")
    public ResponseEntity<ApiResponse<Void>> setTemplateActive(
            @PathVariable Long templateId, @RequestBody Map<String, Object> b) {
        boolean active = Boolean.parseBoolean(String.valueOf(b.get("active")));
        svc.setTemplateActive(templateId, active);
        return ResponseEntity.ok(ApiResponse.noContent(
                active ? "Template back on the list." : "Template taken off the list."));
    }

    @DeleteMapping("/templates/{templateId}")
    @Operation(summary = "Delete a permission template and everything it grants")
    public ResponseEntity<ApiResponse<Void>> deleteTemplate(@PathVariable Long templateId) {
        svc.deleteTemplate(templateId);
        return ResponseEntity.ok(ApiResponse.noContent("Template deleted."));
    }

    @GetMapping("/templates/{templateId}/modules")
    @Operation(summary = "What a template grants, against every module",
               description = "The full module list, each marked with what this template gives — "
                           + "the editor needs to offer what is missing as much as show what is there.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> templateModules(@PathVariable Long templateId) {
        return ResponseEntity.ok(ApiResponse.ok(svc.templateModules(templateId)));
    }

    @PutMapping("/templates/{templateId}/modules")
    @Operation(summary = "Replace what a template grants")
    @SuppressWarnings("unchecked")
    public ResponseEntity<ApiResponse<Integer>> saveTemplateModules(
            @PathVariable Long templateId, @RequestBody Map<String, Object> b) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) b.get("modules");
        int n = svc.saveTemplateModules(templateId, rows);
        return ResponseEntity.ok(ApiResponse.ok("Template now grants " + n + " module" + (n == 1 ? "" : "s") + ".", n));
    }

    /**
     * Turns an application into a working pharmacy.
     *
     * <p>One call and one transaction: organisation, branch, subscription, admin
     * account, module grant, seed master data, queue row, audit entry. A partial
     * result is not reachable, because a branch with no subscription cannot sign
     * in and a subscription with no admin is a door with no key.
     */
    @PostMapping("/registrations/{id}/activate")
    @Operation(summary = "Activate a pharmacy — the whole provisioning in one step",
               description = "Pass copyCatalogue=true to start them with the shared "
                           + "medicine list. Off by default: a pharmacy that wants to type "
                           + "its own should not have to delete two thousand rows first.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> activate(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {

        AllBranch branch = svc.activate(id,
                num(body.get("planId")),
                body.get("templateCode") == null ? null : String.valueOf(body.get("templateCode")),
                intOrNull(body.get("termMonths")),
                num(body.get("roleId")),
                Boolean.parseBoolean(String.valueOf(body.get("copyCatalogue"))));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("branchId", branch.getPidBranch());
        out.put("orgId", branch.getPidOrg());
        out.put("branchName", branch.getBranchName());

        return ResponseEntity.ok(ApiResponse.ok(
                branch.getBranchName() + " is live. Its administrator can sign in now.", out));
    }

    /**
     * Creates a pharmacy directly, live immediately.
     *
     * <p>For a customer signed up over the phone. Runs the same single transaction
     * as approving an application — organisation, branch, subscription, admin
     * account, permissions, seed data — so there is one provisioning path and not
     * two that can drift apart.
     */
    @PostMapping("/pharmacies")
    @Operation(summary = "Add a pharmacy without waiting for it to register")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addPharmacy(
            @RequestBody Map<String, Object> body) {

        AllBranch branch = svc.createDirectly(
                str(body.get("pharmacyName")), str(body.get("contactName")),
                str(body.get("contactEmail")), str(body.get("contactPhone")),
                str(body.get("address")),
                str(body.get("adminUsername")), str(body.get("adminPassword")),
                num(body.get("planId")), str(body.get("templateCode")),
                intOrNull(body.get("termMonths")),
                num(body.get("roleId")),
                Boolean.parseBoolean(String.valueOf(body.get("copyCatalogue"))));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("branchId", branch.getPidBranch());
        out.put("orgId", branch.getPidOrg());
        out.put("branchName", branch.getBranchName());
        return ResponseEntity.ok(ApiResponse.ok(
                branch.getBranchName() + " is live. Its administrator can sign in now.", out));
    }

    private static String str(Object o) { return blank(o); }

    @PostMapping("/registrations/{id}/reject")
    @Operation(summary = "Decline an application, with a reason")
    public ResponseEntity<ApiResponse<Void>> reject(
            @PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        svc.reject(id, body == null ? null : body.get("reason"));
        return ResponseEntity.ok(ApiResponse.noContent("Application declined."));
    }

    /**
     * What one person at this pharmacy can do, and what has been set for them alone.
     *
     * <p>Separate from the pharmacy-wide grant beside it: that one narrows every
     * role equally, so it cannot tell two people apart. This is what lets a Sales
     * Manager and a Branch Manager at the same pharmacy differ.
     */
    @GetMapping("/branches/{branchId}/users/{userId}/permissions")
    @Operation(summary = "One person's effective permissions",
               description = "Each right reports what the role grants, what the plan allows, "
                           + "what has been set for this person, and the effective answer.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> userPermissions(
            @PathVariable Long branchId, @PathVariable Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(svc.userPermissions(branchId, userId)));
    }

    @PutMapping("/branches/{branchId}/users/{userId}/permissions")
    @Operation(summary = "Set what is different about this person",
               description = "Only exceptions are stored. A right matching the role is cleared, "
                           + "so that person keeps following the role if the role later changes. "
                           + "An override can narrow what the role grants, never widen it.")
    @SuppressWarnings("unchecked")
    public ResponseEntity<ApiResponse<Integer>> setUserPermissions(
            @PathVariable Long branchId, @PathVariable Long userId,
            @RequestBody Map<String, Object> body) {
        Object raw = body.get("modules");
        List<Map<String, Object>> rows = raw instanceof List<?> l
                ? (List<Map<String, Object>>) l : List.of();
        int kept = svc.setUserPermissions(branchId, userId, rows);
        return ResponseEntity.ok(ApiResponse.ok(
                kept == 0 ? "This person now follows their role exactly."
                          : kept + " module(s) differ from the role.", kept));
    }

    @GetMapping("/branches/{branchId}/modules")
    @Operation(summary = "What this pharmacy's plan includes",
               description = "Every module, with what has been granted. A pharmacy with no "
                           + "grant at all is unrestricted, and says so — an empty grant and "
                           + "an empty list mean opposite things.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> modules(@PathVariable Long branchId) {
        return ResponseEntity.ok(ApiResponse.ok(svc.modulesFor(branchId)));
    }

    @PutMapping("/branches/{branchId}/modules")
    @Operation(summary = "Set exactly what this pharmacy has",
               description = "Replaces the grant wholesale. An empty list means unrestricted, "
                           + "which is the way back from a restriction applied by mistake.")
    @SuppressWarnings("unchecked")
    public ResponseEntity<ApiResponse<Integer>> setModules(
            @PathVariable Long branchId, @RequestBody Map<String, Object> body) {
        List<Map<String, Object>> modules =
                (List<Map<String, Object>>) body.get("modules");
        int n = svc.setModules(branchId, modules);
        return ResponseEntity.ok(ApiResponse.ok(
                n == 0 ? "This pharmacy is now unrestricted — everything its roles allow."
                       : n + " module(s) granted.", n));
    }
    @PostMapping("/branches/{branchId}/template")
    @Operation(summary = "Re-apply a permission template to a pharmacy already running",
               description = "Replaces its module grant wholesale. A template states what the "
                           + "pharmacy has, so leaving a module behind from a previous one would "
                           + "quietly give away something nobody chose to give.")
    public ResponseEntity<ApiResponse<Integer>> applyTemplate(
            @PathVariable Long branchId, @RequestBody Map<String, String> body) {
        int n = svc.applyTemplate(branchId, body.get("templateCode"));
        return ResponseEntity.ok(ApiResponse.ok(n + " module(s) granted.", n));
    }

    private Map<String, Object> toRow(TenantRegistration r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("registrationId", r.getPidRegistration());
        m.put("pharmacyName", r.getPharmacyName());
        m.put("contactName", r.getContactName());
        m.put("contactEmail", r.getContactEmail());
        m.put("contactPhone", r.getContactPhone());
        m.put("address", r.getAddress());
        m.put("adminUsername", r.getAdminUsername());
        // What they asked for on the sign-up form. A preference the reviewer can
        // accept or overrule, not a commitment — so the activation screen starts
        // here rather than at the top of the list.
        m.put("preferredPlanCode", r.getPreferredPlanCode());
        m.put("preferredTermMonths", r.getPreferredTermMonths());
        m.put("status", r.getRegStatus());
        m.put("rejectReason", r.getRejectReason());
        m.put("branchId", r.getPidBranch());
        m.put("appliedOn", r.getInsertedDate());
        m.put("reviewedBy", r.getReviewedBy());
        m.put("reviewedAt", r.getReviewedAt());
        return m;
    }

/**
     * A number from a form field, or null if it was left blank.
     *
     * <p>An HTML form sends an untouched input as "", never as null, so parsing
     * whatever arrives threw the first time somebody left the user cap empty.
     * Blank means absent, which for most of these fields is a meaningful answer.
     */
    private static Long num(Object o) {
        String s = blank(o);
        return s == null ? null : Long.valueOf(s);
    }

    private static Integer intOrNull(Object o) {
        String s = blank(o);
        return s == null ? null : Integer.valueOf(s);
    }

    private static java.math.BigDecimal decOrNull(Object o) {
        String s = blank(o);
        return s == null ? null : new java.math.BigDecimal(s);
    }

    /** The trimmed text, or null where there was nothing worth reading. */
    private static String blank(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() || "null".equals(s) ? null : s;
    }


    /* The logo endpoints were briefly here and have moved to /api/branches,
       where branches are administered. This controller is Super Admin only —
       correct for approving a registration or setting a pharmacy's plan, but it
       meant a pharmacy's own administrator could not set their own mark. */

    private AclPermTemplate saveTemplate(Long templateId, Map<String, Object> b) {
        return svc.saveTemplate(templateId,
                str(b.get("templateCode")), str(b.get("templateName")),
                str(b.get("description")), str(b.get("planCode")),
                b.get("sortOrder") == null ? null : Integer.valueOf(String.valueOf(b.get("sortOrder"))));
    }

    private static Map<String, Object> templateRow(AclPermTemplate t, Map<Long, Integer> counts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("templateId", t.getPidTemplate());
        m.put("templateCode", t.getTemplateCode());
        m.put("templateName", t.getTemplateName());
        m.put("description", t.getDescription());
        m.put("planCode", t.getPlanCode());
        m.put("isActive", t.getIsActive());
        m.put("sortOrder", t.getSortOrder());
        m.put("moduleCount", counts.getOrDefault(t.getPidTemplate(), 0));
        return m;
    }
}
