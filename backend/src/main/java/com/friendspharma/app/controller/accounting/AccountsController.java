package com.friendspharma.app.controller.accounting;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.entity.*;
import com.friendspharma.app.repository.*;
import com.friendspharma.app.service.impl.PermissionGuard;
import com.friendspharma.app.service.impl.accounting.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/**
 * Accounts — dashboard, chart of accounts, periods, settings, tax, cost
 * centres, payment modes, bank accounts and rules. Every read is bounded to
 * the caller's organisation and branch by {@link AccScope}.
 */
@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
@Tag(name = "Accounts", description = "Double-entry books: chart, periods, settings, dashboard")
public class AccountsController {

    private final AccScope scope;
    private final AccDashboardService dashboard;
    private final AccChartService chart;
    private final AccPeriodService periods;
    private final AccSettings settings;
    private final AccTaxRateRepository taxRepo;
    private final AccCostCenterRepository ccRepo;
    private final AccRuleRepository ruleRepo;
    private final AccRuleLineRepository ruleLineRepo;
    private final AccountingEventService engine;

    @GetMapping("/dashboard")
    public ApiResponse<Map<String, Object>> dashboard(@RequestParam(required = false) Long branchId,
                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(dashboard.dashboard(branchId, from, to));
    }

    /* ── chart of accounts ──────────────────────────────────────────────── */

    @GetMapping("/chart")
    public ApiResponse<List<AccAccount>> chart() {
        scope.requireAnyView("see the chart of accounts");
        chart.ensureProvisioned(scope.orgId());
        return ApiResponse.success(chart.list(scope.orgId()));
    }

    @PostMapping("/chart")
    public ApiResponse<AccAccount> createAccount(@RequestBody AccAccount req) {
        scope.require(AccScope.MOD_COA, PermissionGuard.Right.CREATE, "add an account");
        return ApiResponse.success(chart.create(scope.orgId(), req), "Account added");
    }

    @PutMapping("/chart/{id}")
    public ApiResponse<AccAccount> updateAccount(@PathVariable Long id, @RequestBody AccAccount req) {
        scope.require(AccScope.MOD_COA, PermissionGuard.Right.EDIT, "edit an account");
        return ApiResponse.success(chart.update(scope.orgId(), id, req), "Account updated");
    }

    /* ── periods ────────────────────────────────────────────────────────── */

    @GetMapping("/periods")
    public ApiResponse<Map<String, Object>> periods(@RequestParam(required = false) Long yearId) {
        scope.require(AccScope.MOD_SETTINGS, PermissionGuard.Right.VIEW, "see periods");
        Long org = scope.orgId();
        periods.periodFor(org, LocalDate.now());   // the current year always exists
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("years", periods.years(org)); out.put("periods", periods.periods(org, yearId));
        return ApiResponse.success(out);
    }

    @PostMapping("/periods/{id}/status")
    public ApiResponse<AccPeriod> periodStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        scope.require(AccScope.MOD_SETTINGS, PermissionGuard.Right.APPROVE, "close or lock a period");
        return ApiResponse.success(periods.setStatus(scope.orgId(), id, body.get("status"), scope.username()), "Period " + body.get("status").toLowerCase());
    }

    @PostMapping("/periods/years")
    public ApiResponse<AccFiscalYear> createYear(@RequestBody Map<String, String> body) {
        scope.require(AccScope.MOD_SETTINGS, PermissionGuard.Right.CREATE, "create a fiscal year");
        return ApiResponse.success(periods.createYear(scope.orgId(), LocalDate.parse(body.get("date"))), "Fiscal year created");
    }

    /* ── settings ───────────────────────────────────────────────────────── */

    @GetMapping("/settings")
    public ApiResponse<Map<String, Object>> settings() {
        scope.require(AccScope.MOD_SETTINGS, PermissionGuard.Right.VIEW, "see accounting settings");
        Long org = scope.orgId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settings", settings.all(org)); out.put("defaults", AccSettings.DEFAULTS);
        out.put("paymentModes", chart.paymentModes(org)); out.put("bankAccounts", chart.banks(org));
        out.put("taxRates", taxRepo.findByPidOrgOrderByTaxCodeAscEffectiveFromDesc(org)); out.put("costCenters", ccRepo.findByPidOrgOrderByCcCodeAsc(org));
        List<Map<String, Object>> rules = new ArrayList<>();
        for (AccRule r : ruleRepo.findAllEffective(org)) {
            Map<String, Object> m = new LinkedHashMap<>(); m.put("rule", r); m.put("lines", ruleLineRepo.findByPidRuleOrderByLineNoAsc(r.getPidRule())); rules.add(m);
        }
        out.put("rules", rules);
        return ApiResponse.success(out);
    }

    @PutMapping("/settings")
    public ApiResponse<Map<String, String>> saveSettings(@RequestBody Map<String, String> body) {
        scope.require(AccScope.MOD_SETTINGS, PermissionGuard.Right.EDIT, "change accounting settings");
        Long org = scope.orgId();
        body.forEach((k, v) -> settings.set(org, k, v));
        return ApiResponse.success(settings.all(org), "Settings saved");
    }

    @PostMapping("/settings/payment-modes")
    public ApiResponse<AccPaymentMode> savePaymentMode(@RequestBody AccPaymentMode req) {
        scope.require(AccScope.MOD_SETTINGS, PermissionGuard.Right.EDIT, "map a payment mode");
        return ApiResponse.success(chart.savePaymentMode(scope.orgId(), req), "Payment mode saved");
    }

    @PostMapping("/settings/bank-accounts")
    public ApiResponse<AccBankAccount> createBank(@RequestBody AccBankAccount req) {
        scope.require(AccScope.MOD_CASH_BANK, PermissionGuard.Right.CREATE, "add a bank account");
        return ApiResponse.success(chart.createBank(scope.orgId(), scope.branchId(null), req), "Bank account added");
    }

    @PutMapping("/settings/bank-accounts/{id}")
    public ApiResponse<AccBankAccount> updateBank(@PathVariable Long id, @RequestBody AccBankAccount req) {
        scope.require(AccScope.MOD_CASH_BANK, PermissionGuard.Right.EDIT, "edit a bank account");
        return ApiResponse.success(chart.updateBank(scope.orgId(), id, req), "Bank account updated");
    }

    @PostMapping("/settings/tax-rates")
    public ApiResponse<AccTaxRate> saveTax(@RequestBody AccTaxRate req) {
        scope.require(AccScope.MOD_SETTINGS, PermissionGuard.Right.EDIT, "maintain tax rates");
        Long org = scope.orgId();
        AccTaxRate t = req.getPidTaxRate() == null ? AccTaxRate.builder().pidOrg(org).isActive(1).build()
                : taxRepo.findById(req.getPidTaxRate()).filter(x -> x.getPidOrg().equals(org)).orElseThrow();
        if (req.getTaxCode() == null || req.getTaxName() == null || req.getRatePct() == null || req.getEffectiveFrom() == null)
            throw new com.friendspharma.app.exception.custom.ValidationException("A tax rate needs a code, a name, a rate and an effective-from date.");
        t.setTaxCode(req.getTaxCode().trim()); t.setTaxName(req.getTaxName().trim()); t.setTaxType(req.getTaxType() == null ? "VAT" : req.getTaxType());
        t.setRatePct(req.getRatePct()); t.setEffectiveFrom(req.getEffectiveFrom()); t.setEffectiveTo(req.getEffectiveTo());
        t.setPidAccount(req.getPidAccount() != null ? req.getPidAccount() : chart.byRoleIfAny(org, "VAT_PAYABLE").map(AccAccount::getPidAccount).orElse(null));
        t.setIsInclusive(req.getIsInclusive() == null ? 0 : req.getIsInclusive()); if (req.getIsActive() != null) t.setIsActive(req.getIsActive());
        return ApiResponse.success(taxRepo.save(t), "Tax rate saved");
    }

    /**
     * The cost centres an entry screen may offer, active ones only.
     *
     * <p>Separate from /settings, which carries periods, banks, tax rates and
     * rules as well: a voucher modal wants one short list, not the whole of the
     * settings screen. Read-only, so it asks for VIEW on cash and bank rather
     * than the settings right - the people who write vouchers are not the
     * people who maintain the list.
     */
    @GetMapping("/cost-centers")
    public ApiResponse<List<AccCostCenter>> costCenters() {
        scope.require(AccScope.MOD_CASH_BANK, PermissionGuard.Right.VIEW, "see cost centres");
        return ApiResponse.success(ccRepo.findByPidOrgAndIsActiveOrderByCcCodeAsc(scope.orgId(), 1), "Cost centres");
    }

    @PostMapping("/settings/cost-centers")
    public ApiResponse<AccCostCenter> saveCostCenter(@RequestBody AccCostCenter req) {
        scope.require(AccScope.MOD_SETTINGS, PermissionGuard.Right.EDIT, "maintain cost centres");
        Long org = scope.orgId();
        AccCostCenter c = req.getPidCostCenter() == null ? AccCostCenter.builder().pidOrg(org).isActive(1).build()
                : ccRepo.findById(req.getPidCostCenter()).filter(x -> x.getPidOrg().equals(org)).orElseThrow();
        if (req.getCcCode() == null || req.getCcName() == null) throw new com.friendspharma.app.exception.custom.ValidationException("A cost centre needs a code and a name.");
        c.setCcCode(req.getCcCode().trim()); c.setCcName(req.getCcName().trim()); c.setPidBranch(req.getPidBranch()); c.setDescription(req.getDescription());
        if (req.getIsActive() != null) c.setIsActive(req.getIsActive());
        return ApiResponse.success(ccRepo.save(c), "Cost centre saved");
    }

    /** Override a default rule for this organisation: copies the default lines, then applies the given lines. */
    @PostMapping("/settings/rules/{eventType}")
    public ApiResponse<Map<String, Object>> saveRule(@PathVariable String eventType, @RequestBody Map<String, Object> body) {
        scope.require(AccScope.MOD_SETTINGS, PermissionGuard.Right.APPROVE, "change an accounting rule");
        Long org = scope.orgId();
        AccRule rule = ruleRepo.findEffective(org, eventType).stream().filter(r -> org.equals(r.getPidOrg())).findFirst().orElseGet(() -> {
            AccRule def = ruleRepo.findEffective(org, eventType).stream().findFirst().orElseThrow(() -> new com.friendspharma.app.exception.custom.ValidationException("No rule '" + eventType + "'."));
            return ruleRepo.save(AccRule.builder().pidOrg(org).eventType(eventType).ruleName(def.getRuleName()).description(def.getDescription()).isActive(1).build());
        });
        @SuppressWarnings("unchecked") List<Map<String, Object>> lines = (List<Map<String, Object>>) body.get("lines");
        if (lines == null || lines.isEmpty()) throw new com.friendspharma.app.exception.custom.ValidationException("A rule needs lines.");
        ruleLineRepo.deleteAll(ruleLineRepo.findByPidRuleOrderByLineNoAsc(rule.getPidRule()));
        int n = 1;
        for (Map<String, Object> l : lines) {
            ruleLineRepo.save(AccRuleLine.builder().pidRule(rule.getPidRule()).lineNo(n++).side(String.valueOf(l.get("side")))
                    .accountRole(String.valueOf(l.get("accountRole"))).amountKey(String.valueOf(l.get("amountKey")))
                    .partyType(l.get("partyType") == null ? null : String.valueOf(l.get("partyType"))).description(l.get("description") == null ? null : String.valueOf(l.get("description"))).build());
        }
        if (body.get("isActive") != null) rule.setIsActive(Boolean.TRUE.equals(body.get("isActive")) ? 1 : 0);
        ruleRepo.save(rule);
        engine.evictRules();
        Map<String, Object> out = new LinkedHashMap<>(); out.put("rule", rule); out.put("lines", ruleLineRepo.findByPidRuleOrderByLineNoAsc(rule.getPidRule()));
        return ApiResponse.success(out, "Rule saved for this organisation");
    }
}
