package com.friendspharma.app.controller.accounting;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.entity.*;
import com.friendspharma.app.service.impl.accounting.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Investors, their transactions and ledger; profit-share policies and distributions; the event monitor and reconciliation. */
@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
@Tag(name = "Accounts — investors", description = "Investors, profit distribution, reconciliation")
public class AccInvestorController {

    private final AccInvestorService investors;
    private final AccProfitDistributionService profit;
    private final AccReconciliationService reconciliation;

    @GetMapping("/investors")
    public ApiResponse<List<Map<String, Object>>> investors() { return ApiResponse.success(investors.list()); }

    @PostMapping("/investors")
    public ApiResponse<AccInvestor> createInvestor(@RequestBody AccInvestor in) { return ApiResponse.success(investors.save(null, in), "Investor added"); }

    @PutMapping("/investors/{id}")
    public ApiResponse<AccInvestor> updateInvestor(@PathVariable Long id, @RequestBody AccInvestor in) { return ApiResponse.success(investors.save(id, in), "Investor updated"); }

    @GetMapping("/investors/{id}")
    public ApiResponse<Map<String, Object>> investor(@PathVariable Long id,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(investors.detail(id, from, to));
    }

    @GetMapping("/investors/{id}/ledger")
    public ApiResponse<Map<String, Object>> investorLedger(@PathVariable Long id,
                                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(investors.detail(id, from, to));
    }

    @GetMapping("/investor-transactions")
    public ApiResponse<PageResponse<AccInvestorTxn>> transactions(@RequestParam(required = false) Long investorId, @RequestParam(required = false) String status,
                                                                  @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.success(PageResponse.of(investors.transactions(investorId, status, page, size)));
    }

    @PostMapping("/investor-transactions")
    public ApiResponse<AccInvestorTxn> createTxn(@RequestBody AccInvestorTxn in) { return ApiResponse.success(investors.create(in), "Transaction recorded"); }

    @PostMapping("/investor-transactions/{id}/approve")
    public ApiResponse<AccInvestorTxn> approveTxn(@PathVariable Long id) { return ApiResponse.success(investors.approve(id), "Transaction approved and posted"); }

    @PostMapping("/investor-transactions/{id}/cancel")
    public ApiResponse<AccInvestorTxn> cancelTxn(@PathVariable Long id, @RequestBody Map<String, String> body) { return ApiResponse.success(investors.cancel(id, body.get("reason")), "Transaction cancelled"); }

    /* ── profit share ───────────────────────────────────────────────────── */

    @GetMapping("/profit/policies")
    public ApiResponse<List<Map<String, Object>>> policies() { return ApiResponse.success(profit.policies()); }

    @PostMapping("/profit/policies")
    public ApiResponse<AccProfitPolicy> savePolicy(@RequestBody Map<String, Object> body) {
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        AccProfitPolicy p = om.convertValue(body.get("policy"), AccProfitPolicy.class);
        List<AccProfitPolicyLine> lines = body.get("lines") == null ? List.of()
                : om.convertValue(body.get("lines"), om.getTypeFactory().constructCollectionType(List.class, AccProfitPolicyLine.class));
        Long id = body.get("id") == null ? null : Long.valueOf(String.valueOf(body.get("id")));
        return ApiResponse.success(profit.savePolicy(id, p, lines), "Policy saved");
    }

    @GetMapping("/profit/distributions")
    public ApiResponse<List<AccProfitDistribution>> distributions() { return ApiResponse.success(profit.list()); }

    @GetMapping("/profit/distributions/{id}")
    public ApiResponse<Map<String, Object>> distribution(@PathVariable Long id) { return ApiResponse.success(profit.get(id)); }

    @PostMapping("/profit/distributions/calculate")
    public ApiResponse<Map<String, Object>> calculate(@RequestBody Map<String, Object> body) {
        Long id = body.get("id") == null ? null : Long.valueOf(String.valueOf(body.get("id")));
        Long branch = body.get("branchId") == null ? null : Long.valueOf(String.valueOf(body.get("branchId")));
        Long policy = body.get("policyId") == null ? null : Long.valueOf(String.valueOf(body.get("policyId")));
        BigDecimal adj = body.get("adjustmentAmount") == null ? null : new BigDecimal(String.valueOf(body.get("adjustmentAmount")));
        return ApiResponse.success(profit.calculate(id, branch, LocalDate.parse(String.valueOf(body.get("from"))), LocalDate.parse(String.valueOf(body.get("to"))),
                policy, adj, (String) body.get("adjustmentReason"), (String) body.get("notes")), "Profit calculated");
    }

    @PostMapping("/profit/distributions/{id}/review")
    public ApiResponse<Map<String, Object>> review(@PathVariable Long id) { return ApiResponse.success(profit.review(id), "Reviewed"); }

    @PostMapping("/profit/distributions/{id}/approve")
    public ApiResponse<Map<String, Object>> approve(@PathVariable Long id) { return ApiResponse.success(profit.approve(id), "Approved"); }

    @PostMapping("/profit/distributions/{id}/post")
    public ApiResponse<Map<String, Object>> post(@PathVariable Long id) { return ApiResponse.success(profit.post(id), "Distribution posted"); }

    @PostMapping("/profit/distributions/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancel(@PathVariable Long id, @RequestBody Map<String, String> body) { return ApiResponse.success(profit.cancel(id, body.get("reason")), "Distribution cancelled"); }

    /* ── reconciliation & events ────────────────────────────────────────── */

    @GetMapping("/events")
    public ApiResponse<PageResponse<AccEvent>> events(@RequestParam(required = false) Long branchId, @RequestParam(required = false) String status, @RequestParam(required = false) String module,
                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.success(PageResponse.of(reconciliation.monitor(branchId, status, module, from, to, page, size)));
    }

    @PostMapping("/events/{id}/retry")
    public ApiResponse<AccEvent> retry(@PathVariable Long id) { return ApiResponse.success(reconciliation.retry(id), "Event re-posted"); }

    @GetMapping("/reconciliation")
    public ApiResponse<Map<String, Object>> reconcile(@RequestParam(required = false) Long branchId,
                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(reconciliation.reconcile(branchId, from, to));
    }

    @GetMapping("/backfill/batches")
    public ApiResponse<List<AccBackfillBatch>> backfillBatches() { return ApiResponse.success(reconciliation.batches()); }

    @PostMapping("/backfill")
    public ApiResponse<AccBackfillBatch> backfill(@RequestBody Map<String, Object> body) {
        Long branch = body.get("branchId") == null ? null : Long.valueOf(String.valueOf(body.get("branchId")));
        LocalDate from = body.get("from") == null ? null : LocalDate.parse(String.valueOf(body.get("from")));
        LocalDate to = body.get("to") == null ? null : LocalDate.parse(String.valueOf(body.get("to")));
        boolean dry = Boolean.TRUE.equals(body.get("dryRun"));
        int limit = body.get("limit") == null ? 500 : Math.min(Integer.parseInt(String.valueOf(body.get("limit"))), 5000);
        Long org = body.get("orgId") == null ? null : Long.valueOf(String.valueOf(body.get("orgId")));
        return ApiResponse.success(reconciliation.backfill(org, branch, (String) body.get("module"), from, to, dry, limit), dry ? "Dry run complete" : "Backfill batch complete");
    }
}
