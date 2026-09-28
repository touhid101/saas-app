package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.dto.response.SubscriptionResponse;
import com.friendspharma.app.entity.SubPlan;
import com.friendspharma.app.entity.SubTerm;
import com.friendspharma.app.repository.SubPlanRepository;
import com.friendspharma.app.service.impl.SubscriptionServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Super Admin console: every pharmacy, and what it owes.
 *
 * <p>Every method is {@code ROLE01} only. The whole controller sits on
 * {@code TenantStatusFilter}'s open list — an expired branch has to remain
 * administrable, or the only way to reactivate a customer would be a hand-written
 * UPDATE — and that exemption is safe precisely because the role check below is
 * what actually guards it.
 */
@RestController
@RequestMapping("/api/admin/subscriptions")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ROLE01')")
@Tag(name = "Subscription administration", description = "Every pharmacy's subscription — Super Admin only")
public class AdminSubscriptionController {

    private final SubscriptionServiceImpl svc;
    private final SubPlanRepository       planRepo;

    @GetMapping
    @Operation(summary = "Every pharmacy, its plan, its expiry and what it owes")
    public ResponseEntity<ApiResponse<List<SubscriptionResponse.TenantRow>>> tenants() {
        return ResponseEntity.ok(ApiResponse.ok(svc.allTenants()));
    }

    @GetMapping("/{branchId}")
    @Operation(summary = "One pharmacy's subscription in full")
    public ResponseEntity<ApiResponse<SubscriptionResponse.Mine>> one(@PathVariable Long branchId) {
        return ResponseEntity.ok(ApiResponse.ok(svc.forBranch(branchId)));
    }

    @PostMapping("/{branchId}/invoice")
    @Operation(summary = "Raise the next period's invoice for a pharmacy")
    public ResponseEntity<ApiResponse<SubscriptionResponse.InvoiceDto>> raise(
            @PathVariable Long branchId,
            @RequestBody(required = false) Map<String, Object> body) {
        Long planId = body == null || body.get("planId") == null
                ? null : Long.valueOf(String.valueOf(body.get("planId")));
        return ResponseEntity.ok(ApiResponse.ok("Invoice raised.", svc.raiseRenewal(branchId, planId)));
    }

    /**
     * Records money against an invoice.
     *
     * <p>Payment is taken outside the application today — bKash, bank transfer,
     * cash — and marked here. The body is shaped for the gateway that does not
     * exist yet: pass an {@code idempotencyKey} and a repeat of the same call
     * returns the original payment instead of taking the money twice. A webhook
     * will post to this same method.
     */
    @PostMapping("/invoices/{invoiceId}/payment")
    @Operation(summary = "Record a payment and let the pharmacy back in")
    public ResponseEntity<ApiResponse<SubscriptionResponse.PaymentDto>> pay(
            @PathVariable Long invoiceId,
            @RequestBody(required = false) Map<String, Object> body) {

        Map<String, Object> b = body == null ? Map.of() : body;
        BigDecimal amount = decOrNull(b.get("amount"));

        return ResponseEntity.ok(ApiResponse.ok(
                "Payment recorded. The pharmacy can use the application again.",
                svc.recordPayment(invoiceId, amount,
                        str(b.get("method")), str(b.get("providerRef")),
                        str(b.get("idempotencyKey")), str(b.get("note")))));
    }

    @DeleteMapping("/invoices/{invoiceId}")
    @Operation(summary = "Cancel an invoice that should not have been raised",
               description = "Voided rather than deleted: an invoice is a document somebody "
                           + "may already have been shown, and a billing history with a hole "
                           + "in it cannot be reconciled. A paid invoice is refused — refund it.")
    public ResponseEntity<ApiResponse<Void>> voidInvoice(
            @PathVariable Long invoiceId,
            @RequestParam(required = false) String reason) {
        svc.voidInvoice(invoiceId, reason);
        return ResponseEntity.ok(ApiResponse.noContent("Invoice cancelled."));
    }

    @PatchMapping("/{branchId}/status")
    @Operation(summary = "Suspend a pharmacy, or let it back in",
               description = "Independent of the dates. An administrator holding a branch out "
                           + "and a subscription running out are separate decisions.")
    public ResponseEntity<ApiResponse<Void>> status(
            @PathVariable Long branchId,
            @RequestBody Map<String, String> body) {
        svc.setTenantStatus(branchId, body.get("status"), body.get("reason"));
        return ResponseEntity.ok(ApiResponse.noContent("Status updated."));
    }

    @GetMapping("/{branchId}/audit")
    @Operation(summary = "What has happened to this pharmacy's subscription")
    public ResponseEntity<ApiResponse<List<SubscriptionResponse.AuditDto>>> audit(@PathVariable Long branchId) {
        return ResponseEntity.ok(ApiResponse.ok(svc.auditFor(branchId)));
    }

    @PostMapping("/sweep")
    @Operation(summary = "Run the expiry sweep now",
               description = "The same job that runs nightly. Access does not depend on it — "
                           + "the gate compares dates on every request — but this makes the "
                           + "stored status agree with them immediately.")
    public ResponseEntity<ApiResponse<Integer>> sweep() {
        int n = svc.expireOverdue();
        return ResponseEntity.ok(ApiResponse.ok(n + " subscription(s) expired.", n));
    }

    /* ══════════════════════════════════════════════════════════════════════
       PLANS
       ══════════════════════════════════════════════════════════════════════ */

    @GetMapping("/plans")
    @Operation(summary = "Every plan, including ones taken off sale")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> plans() {
        return ResponseEntity.ok(ApiResponse.ok(
                planRepo.findAll().stream()
                        .sorted(java.util.Comparator.comparing(SubPlan::getSortOrder,
                                java.util.Comparator.nullsLast(Integer::compareTo)))
                        .map(AdminSubscriptionController::planRow).toList()));
    }

    @PostMapping("/plans")
    @Operation(summary = "Add a plan")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addPlan(@RequestBody Map<String, Object> b) {
        return ResponseEntity.ok(ApiResponse.ok("Plan added.", planRow(save(null, b))));
    }

    @PutMapping("/plans/{planId}")
    @Operation(summary = "Edit a plan",
               description = "Changes what the next invoice costs. Subscriptions already running "
                           + "keep the expiry and grace they were sold — raising a price must not "
                           + "move the date somebody already trading is locked out on.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> editPlan(
            @PathVariable Long planId, @RequestBody Map<String, Object> b) {
        return ResponseEntity.ok(ApiResponse.ok("Plan updated.", planRow(save(planId, b))));
    }

    @DeleteMapping("/plans/{planId}")
    @Operation(summary = "Take a plan off sale",
               description = "Refused while any pharmacy is on it — a live subscription pointing "
                           + "at a deleted plan has no price, no term and no grace.")
    public ResponseEntity<ApiResponse<Void>> retirePlan(@PathVariable Long planId) {
        svc.retirePlan(planId);
        return ResponseEntity.ok(ApiResponse.noContent("Plan taken off sale."));
    }

    /* ══════════════════════════════════════════════════════════════════════
       TERMS
       ══════════════════════════════════════════════════════════════════════ */

    @GetMapping("/terms")
    @Operation(summary = "Every term, including ones taken off the list",
               description = "The activation screen filters to the active ones; the screen that "
                           + "edits them needs to see what has been retired too.")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> terms() {
        return ResponseEntity.ok(ApiResponse.ok(
                svc.allTerms().stream().map(AdminSubscriptionController::termRow).toList()));
    }

    @PostMapping("/terms")
    @Operation(summary = "Add a term")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addTerm(@RequestBody Map<String, Object> b) {
        return ResponseEntity.ok(ApiResponse.ok("Term added.", termRow(saveTerm(null, b))));
    }

    @PutMapping("/terms/{termId}")
    @Operation(summary = "Edit a term",
               description = "Changes what is offered from now on. A pharmacy already activated "
                           + "keeps the expiry it was given — the term was read once to work that "
                           + "date out and is not consulted again.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> editTerm(
            @PathVariable Long termId, @RequestBody Map<String, Object> b) {
        return ResponseEntity.ok(ApiResponse.ok("Term updated.", termRow(saveTerm(termId, b))));
    }

    @PatchMapping("/terms/{termId}/active")
    @Operation(summary = "Take a term off the list, or put it back")
    public ResponseEntity<ApiResponse<Void>> setTermActive(
            @PathVariable Long termId, @RequestBody Map<String, Object> b) {
        boolean active = Boolean.parseBoolean(String.valueOf(b.get("active")));
        svc.setTermActive(termId, active);
        return ResponseEntity.ok(ApiResponse.noContent(
                active ? "Term back on the list." : "Term taken off the list."));
    }

    @DeleteMapping("/terms/{termId}")
    @Operation(summary = "Delete a term",
               description = "Removed outright rather than deactivated: no subscription refers to "
                           + "a term, so nothing is orphaned by it going away.")
    public ResponseEntity<ApiResponse<Void>> deleteTerm(@PathVariable Long termId) {
        svc.deleteTerm(termId);
        return ResponseEntity.ok(ApiResponse.noContent("Term deleted."));
    }

    /* ══════════════════════════════════════════════════════════════════════
       ONE PHARMACY'S SUBSCRIPTION
       ══════════════════════════════════════════════════════════════════════ */

    @PatchMapping("/{branchId}/plan")
    @Operation(summary = "Move a pharmacy to another plan",
               description = "Applies from the next renewal: they have paid for the term they "
                           + "are in.")
    public ResponseEntity<ApiResponse<Void>> changePlan(
            @PathVariable Long branchId, @RequestBody Map<String, Object> b) {
        svc.changePlan(branchId, num(b.get("planId")));
        return ResponseEntity.ok(ApiResponse.noContent("Plan changed from the next renewal."));
    }

    @PatchMapping("/{branchId}/expiry")
    @Operation(summary = "Move a subscription's expiry by hand",
               description = "The honest way to give a customer extra time. A reason is required, "
                           + "because a date moved without one cannot be told apart from a "
                           + "mistake six months later.")
    public ResponseEntity<ApiResponse<Void>> setExpiry(
            @PathVariable Long branchId, @RequestBody Map<String, String> b) {
        svc.setExpiry(branchId,
                b.get("expiresOn") == null ? null : java.time.LocalDate.parse(b.get("expiresOn")),
                b.get("reason"));
        return ResponseEntity.ok(ApiResponse.noContent("Expiry updated."));
    }

    private SubPlan save(Long planId, Map<String, Object> b) {
        return svc.savePlan(planId,
                blank(b.get("planCode")), blank(b.get("planName")), blank(b.get("description")),
                decOrNull(b.get("price")), blank(b.get("billingTerm")),
                intOrNull(b.get("maxUsers")), intOrNull(b.get("graceDays")));
    }

    private SubTerm saveTerm(Long termId, Map<String, Object> b) {
        return svc.saveTerm(termId,
                intOrNull(b.get("termMonths")), blank(b.get("termName")),
                blank(b.get("description")), decOrNull(b.get("discountPct")),
                intOrNull(b.get("sortOrder")));
    }

    private static Map<String, Object> termRow(SubTerm t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("termId", t.getPidTerm());
        m.put("termMonths", t.getTermMonths());
        m.put("termName", t.getTermName());
        m.put("description", t.getDescription());
        m.put("discountPct", t.getDiscountPct());
        m.put("isActive", t.getIsActive());
        m.put("sortOrder", t.getSortOrder());
        return m;
    }

    private static Map<String, Object> planRow(SubPlan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planId", p.getPidPlan());
        m.put("planCode", p.getPlanCode());
        m.put("planName", p.getPlanName());
        m.put("description", p.getDescription());
        m.put("price", p.getPrice());
        m.put("billingTerm", p.getBillingTerm());
        m.put("maxUsers", p.getMaxUsers());
        m.put("graceDays", p.getGraceDays());
        m.put("isActive", p.getIsActive());
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

    /** Kept for the call sites that read plain text fields. */
    private static String str(Object o) { return blank(o); }

    /** The trimmed text, or null where there was nothing worth reading. */
    private static String blank(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() || "null".equals(s) ? null : s;
    }

}
