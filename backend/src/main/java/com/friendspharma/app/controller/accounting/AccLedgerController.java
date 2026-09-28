package com.friendspharma.app.controller.accounting;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.entity.AccCashVoucher;
import com.friendspharma.app.entity.AccJournal;
import com.friendspharma.app.service.impl.PermissionGuard;
import com.friendspharma.app.service.impl.accounting.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Journals, the general ledger, receivables and payables, cash vouchers and the daily cash sheet. */
@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
@Tag(name = "Accounts — ledger", description = "Journals, ledger, parties, cash")
public class AccLedgerController {

    private final AccScope scope;
    private final AccJournalService journals;
    private final AccLedgerService ledger;
    private final AccCashService cash;
    private final AccDailyCashService dailyCash;

    /* ── journals ───────────────────────────────────────────────────────── */

    @GetMapping("/journals")
    public ApiResponse<PageResponse<AccJournal>> journals(@RequestParam(required = false) Long branchId, @RequestParam(required = false) String status,
                                                          @RequestParam(required = false) String type, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to, @RequestParam(required = false) String q,
                                                          @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.success(PageResponse.of(journals.search(branchId, status, type, from, to, q, page, size)));
    }

    @GetMapping("/journals/{id}")
    public ApiResponse<Map<String, Object>> journal(@PathVariable Long id) { return ApiResponse.success(journals.get(id)); }

    @PostMapping("/journals")
    public ApiResponse<AccJournal> createJournal(@RequestBody AccJournalService.JournalIn in) { return ApiResponse.success(journals.createDraft(in), "Journal saved"); }

    @PutMapping("/journals/{id}")
    public ApiResponse<AccJournal> updateJournal(@PathVariable Long id, @RequestBody AccJournalService.JournalIn in) { return ApiResponse.success(journals.updateDraft(id, in), "Journal updated"); }

    @DeleteMapping("/journals/{id}")
    public ApiResponse<Void> discardJournal(@PathVariable Long id) { journals.discardDraft(id); return ApiResponse.success(null, "Draft discarded"); }

    @PostMapping("/journals/{id}/post")
    public ApiResponse<AccJournal> postJournal(@PathVariable Long id) { return ApiResponse.success(journals.post(id), "Journal posted"); }

    @PostMapping("/journals/{id}/reverse")
    public ApiResponse<AccJournal> reverseJournal(@PathVariable Long id, @RequestBody Map<String, String> body) {
        LocalDate d = body.get("date") == null || body.get("date").isBlank() ? null : LocalDate.parse(body.get("date"));
        return ApiResponse.success(journals.reverse(id, d, body.get("reason")), "Journal reversed");
    }

    /* ── ledger ─────────────────────────────────────────────────────────── */

    @GetMapping("/ledger")
    public ApiResponse<Map<String, Object>> ledger(@RequestParam Long accountId, @RequestParam(required = false) Long branchId,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                   @RequestParam(required = false) Long costCenterId, @RequestParam(required = false) String source, @RequestParam(required = false) String q,
                                                   @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        scope.require(AccScope.MOD_LEDGER, PermissionGuard.Right.VIEW, "see the ledger");
        LocalDate f = from != null ? from : LocalDate.now().withDayOfMonth(1), t = to != null ? to : LocalDate.now();
        return ApiResponse.success(ledger.ledger(scope.orgId(), scope.branchFilter(branchId), accountId, f, t, costCenterId, source, q, page, Math.min(size, 500)));
    }

    @GetMapping("/trial-balance")
    public ApiResponse<List<Map<String, Object>>> trialBalance(@RequestParam(required = false) Long branchId,
                                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        scope.require(AccScope.MOD_LEDGER, PermissionGuard.Right.VIEW, "see the trial balance");
        LocalDate f = from != null ? from : LocalDate.now().withDayOfMonth(1), t = to != null ? to : LocalDate.now();
        return ApiResponse.success(ledger.trialBalance(scope.orgId(), scope.branchFilter(branchId), f, t));
    }

    /* ── receivables & payables ─────────────────────────────────────────── */

    @GetMapping("/parties/{partyType}")
    public ApiResponse<List<Map<String, Object>>> parties(@PathVariable String partyType, @RequestParam(required = false) Long branchId,
                                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
                                                          @RequestParam(defaultValue = "true") boolean openOnly, @RequestParam(defaultValue = "false") boolean ageing) {
        scope.require(AccScope.MOD_PARTIES, PermissionGuard.Right.VIEW, "see receivables and payables");
        String pt = partyType.toUpperCase();
        String role = switch (pt) { case "CUSTOMER" -> "AR"; case "SUPPLIER" -> "AP"; case "TENANT" -> "AR"; default -> null; };
        LocalDate d = asOf != null ? asOf : LocalDate.now();
        // Balances show the net position, so a supplier holding an advance is listed (negative = advance held);
        // ageing stays on the payable alone, because an advance has no due date.
        String balanceRole = "SUPPLIER".equals(pt) ? "AP,SUPPLIER_ADVANCE" : role;
        return ApiResponse.success(ageing ? ledger.ageing(scope.orgId(), scope.branchFilter(branchId), pt, role, d)
                                          : ledger.partyBalances(scope.orgId(), scope.branchFilter(branchId), pt, balanceRole, d, openOnly));
    }

    @GetMapping("/parties/{partyType}/{partyId}/statement")
    public ApiResponse<Map<String, Object>> statement(@PathVariable String partyType, @PathVariable Long partyId, @RequestParam(required = false) Long branchId,
                                                      @RequestParam(required = false) String role,
                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        scope.require(AccScope.MOD_PARTIES, PermissionGuard.Right.VIEW, "see a statement");
        String pt = partyType.toUpperCase();
        String r = role != null ? role : switch (pt) { case "CUSTOMER", "TENANT" -> "AR"; case "SUPPLIER" -> "AP,SUPPLIER_ADVANCE"; default -> null; };
        LocalDate f = from != null ? from : LocalDate.now().minusMonths(3), t = to != null ? to : LocalDate.now();
        return ApiResponse.success(ledger.partyStatement(scope.orgId(), scope.branchFilter(branchId), pt, partyId, r, f, t));
    }

    /* ── cash & bank ────────────────────────────────────────────────────── */

    @GetMapping("/cash/positions")
    public ApiResponse<List<Map<String, Object>>> positions(@RequestParam(required = false) Long branchId) { return ApiResponse.success(cash.positions(branchId)); }

    @GetMapping("/cash/accounts")
    public ApiResponse<List<com.friendspharma.app.entity.AccAccount>> cashAccounts() { scope.requireAnyView("see cash accounts"); return ApiResponse.success(cash.cashAccounts()); }

    @GetMapping("/cash/vouchers")
    public ApiResponse<PageResponse<AccCashVoucher>> vouchers(@RequestParam(required = false) Long branchId, @RequestParam(required = false) String status, @RequestParam(required = false) String kind,
                                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                              @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.success(PageResponse.of(cash.search(branchId, status, kind, from, to, page, size)));
    }

    @PostMapping("/cash/vouchers")
    public ApiResponse<AccCashVoucher> createVoucher(@RequestBody AccCashVoucher in) { return ApiResponse.success(cash.create(in), "Voucher saved"); }

    @PostMapping("/cash/vouchers/{id}/approve")
    public ApiResponse<AccCashVoucher> approveVoucher(@PathVariable Long id) { return ApiResponse.success(cash.approve(id), "Voucher approved and posted"); }

    @PostMapping("/cash/vouchers/{id}/cancel")
    public ApiResponse<AccCashVoucher> cancelVoucher(@PathVariable Long id, @RequestBody Map<String, String> body) { return ApiResponse.success(cash.cancel(id, body.get("reason")), "Voucher cancelled"); }

    /* ── daily cash ─────────────────────────────────────────────────────── */

    @GetMapping("/daily-cash/current")
    public ApiResponse<Map<String, Object>> current(@RequestParam(required = false) Long branchId, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.success(dailyCash.sheet(branchId, date));
    }

    @GetMapping("/daily-cash/book")
    public ApiResponse<List<Map<String, Object>>> book(@RequestParam(required = false) Long branchId,
                                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(dailyCash.book(branchId, from, to));
    }

    @PostMapping("/daily-cash/open")
    public ApiResponse<Map<String, Object>> open(@RequestBody Map<String, String> body) {
        Long branch = body.get("branchId") == null ? null : Long.valueOf(body.get("branchId"));
        LocalDate d = body.get("date") == null || body.get("date").isBlank() ? null : LocalDate.parse(body.get("date"));
        return ApiResponse.success(dailyCash.open(branch, d), "Business day opened");
    }

    @PostMapping("/daily-cash/{dayId}/count")
    public ApiResponse<Map<String, Object>> count(@PathVariable Long dayId, @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked") List<Map<String, Object>> counts = (List<Map<String, Object>>) body.get("counts");
        java.math.BigDecimal total = body.get("physicalTotal") == null ? null : new java.math.BigDecimal(String.valueOf(body.get("physicalTotal")));
        return ApiResponse.success(dailyCash.count(dayId, counts, total), "Cash counted");
    }

    @PostMapping("/daily-cash/{dayId}/close")
    public ApiResponse<Map<String, Object>> close(@PathVariable Long dayId, @RequestBody(required = false) Map<String, String> body) {
        return ApiResponse.success(dailyCash.close(dayId, body == null ? null : body.get("differenceReason"), body == null ? null : body.get("notes")), "Business day closed");
    }

    @PostMapping("/daily-cash/{dayId}/reopen")
    public ApiResponse<Map<String, Object>> reopen(@PathVariable Long dayId, @RequestBody Map<String, String> body) {
        return ApiResponse.success(dailyCash.reopen(dayId, body.get("reason")), "Business day reopened");
    }
}
