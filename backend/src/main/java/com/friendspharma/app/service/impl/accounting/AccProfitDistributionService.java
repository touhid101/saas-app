package com.friendspharma.app.service.impl.accounting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.friendspharma.app.entity.*;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.repository.*;
import com.friendspharma.app.service.impl.DocumentNumberService;
import com.friendspharma.app.service.impl.PermissionGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Sharing a period's profit with the investors.
 *
 * <pre>
 *   accounting profit (ledger: revenue − cost of sales − expenses ± other, for the period)
 *   − retained (policy %)  ± approved adjustment (with a reason)   = distributable profit
 *   × investor pool %                                             = the investors' share
 *   allocated by the policy method, snapshotted                    → one allocation per investor
 *   DRAFT → CALCULATED → REVIEWED → APPROVED → POSTED (liability) → PAID (investor transactions)
 * </pre>
 *
 * <p>Profit is never read from sales or cash; it is the ledger's revenue and
 * expense accounts for the window. The method, the inputs and every figure
 * are stored on the distribution, so a calculation can be reproduced after
 * the policy changes. One live distribution per scope and span.
 */
@Service
@RequiredArgsConstructor
public class AccProfitDistributionService {

    public static final Set<String> METHODS = Set.of("FIXED_PERCENTAGE", "CAPITAL_RATIO", "INVESTOR_SPECIFIC_PERCENTAGE", "WEIGHTED_AVERAGE_CAPITAL", "CUSTOM_APPROVED_METHOD");

    private final AccScope                       scope;
    private final AccProfitPolicyRepository      policyRepo;
    private final AccProfitPolicyLineRepository  policyLineRepo;
    private final AccProfitDistributionRepository distRepo;
    private final AccProfitAllocationRepository  allocationRepo;
    private final AccInvestorRepository          investorRepo;
    private final AccInvestorTxnRepository       txnRepo;
    private final AccPeriodRepository            periodRepo;
    private final AccLedgerService               ledger;
    private final AccInvestorService             investors;
    private final AccountingEventService         engine;
    private final DocumentNumberService          numbers;
    private final ObjectMapper                   json;

    /* ── policy ─────────────────────────────────────────────────────────── */

    @Transactional(readOnly = true)
    public List<Map<String, Object>> policies() {
        scope.require(AccScope.MOD_PROFIT, PermissionGuard.Right.VIEW, "see profit-share policies");
        List<Map<String, Object>> out = new ArrayList<>();
        for (AccProfitPolicy p : policyRepo.findByPidOrgOrderByEffectiveFromDesc(scope.orgId())) {
            Map<String, Object> m = new LinkedHashMap<>(); m.put("policy", p); m.put("lines", policyLineRepo.findByPidProfitPolicy(p.getPidProfitPolicy())); out.add(m);
        }
        return out;
    }

    @Transactional
    public AccProfitPolicy savePolicy(Long id, AccProfitPolicy in, List<AccProfitPolicyLine> lines) {
        scope.require(AccScope.MOD_PROFIT, PermissionGuard.Right.EDIT, "maintain profit-share policies");
        Long org = scope.orgId();
        if (in.getPolicyName() == null || in.getPolicyName().isBlank()) throw bad("A policy needs a name.");
        if (in.getShareMethod() == null || !METHODS.contains(in.getShareMethod())) throw bad("Method must be one of " + METHODS + ".");
        if (in.getEffectiveFrom() == null) throw bad("A policy needs an effective-from date.");
        BigDecimal pool = in.getInvestorPoolPct() == null ? new BigDecimal("100") : in.getInvestorPoolPct();
        BigDecimal retained = in.getRetainedPct() == null ? BigDecimal.ZERO : in.getRetainedPct();
        if (pct(pool) || pct(retained)) throw bad("Percentages must be between 0 and 100.");
        AccProfitPolicy p = id == null ? AccProfitPolicy.builder().pidOrg(org).isActive(1).build()
                : policyRepo.findById(id).filter(x -> x.getPidOrg().equals(org)).orElseThrow(() -> bad("Policy not found."));
        p.setPolicyName(in.getPolicyName().trim()); p.setShareMethod(in.getShareMethod()); p.setPidBranch(in.getPidBranch());
        p.setInvestorPoolPct(pool); p.setRetainedPct(retained); p.setEffectiveFrom(in.getEffectiveFrom()); p.setEffectiveTo(in.getEffectiveTo());
        if (in.getIsActive() != null) p.setIsActive(in.getIsActive()); p.setNotes(in.getNotes());
        p = policyRepo.save(p);
        if ("INVESTOR_SPECIFIC_PERCENTAGE".equals(p.getShareMethod())) {
            if (lines == null || lines.isEmpty()) throw bad("An investor-specific policy needs a percentage per investor.");
            BigDecimal total = BigDecimal.ZERO;
            for (AccProfitPolicyLine l : lines) {
                if (l.getPidInvestor() == null || l.getSharePct() == null || pct(l.getSharePct())) throw bad("Every line needs an investor and a percentage between 0 and 100.");
                investors.owned(l.getPidInvestor());
                total = total.add(l.getSharePct());
            }
            if (total.compareTo(new BigDecimal("100")) > 0) throw bad("The percentages add up to " + total.toPlainString() + "; they cannot exceed 100.");
            policyLineRepo.deleteByPidProfitPolicy(p.getPidProfitPolicy());
            for (AccProfitPolicyLine l : lines) policyLineRepo.save(AccProfitPolicyLine.builder().pidProfitPolicy(p.getPidProfitPolicy()).pidInvestor(l.getPidInvestor()).sharePct(l.getSharePct()).build());
        }
        return p;
    }

    /* ── distributions ──────────────────────────────────────────────────── */

    @Transactional(readOnly = true)
    public List<AccProfitDistribution> list() {
        scope.require(AccScope.MOD_PROFIT, PermissionGuard.Right.VIEW, "see profit distributions");
        return distRepo.findByPidOrgOrderByPeriodFromDescPidDistributionDesc(scope.orgId());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(Long id) {
        scope.require(AccScope.MOD_PROFIT, PermissionGuard.Right.VIEW, "see a profit distribution");
        AccProfitDistribution d = owned(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("distribution", d);
        List<Map<String, Object>> allocs = new ArrayList<>();
        for (AccProfitAllocation a : allocationRepo.findByPidDistributionOrderByPidAllocationAsc(id)) {
            Map<String, Object> m = new LinkedHashMap<>(); m.put("allocation", a);
            m.put("investor", investorRepo.findById(a.getPidInvestor()).orElse(null));
            m.put("payments", txnRepo.findByPidAllocationAndTxnStatus(a.getPidAllocation(), "POSTED"));
            allocs.add(m);
        }
        out.put("allocations", allocs);
        out.put("profitLines", ledger.profitAndLoss(d.getPidOrg(), d.getPidBranch(), d.getPeriodFrom(), d.getPeriodTo()));
        return out;
    }

    /** Step 1–3: create the distribution for a span and calculate it from the ledger. */
    @Transactional
    public Map<String, Object> calculate(Long existingId, Long branchId, LocalDate from, LocalDate to, Long policyId,
                                         BigDecimal adjustment, String adjustmentReason, String notes) {
        scope.require(AccScope.MOD_PROFIT, PermissionGuard.Right.CREATE, "calculate a profit distribution");
        Long org = scope.orgId();
        if (from == null || to == null || to.isBefore(from)) throw bad("Give the period the profit is for.");
        if (to.isAfter(LocalDate.now())) throw bad("The period cannot end in the future.");
        Long branch = branchId == null ? null : scope.branchId(branchId);
        AccProfitDistribution d;
        if (existingId != null) {
            d = owned(existingId);
            if (!Set.of("DRAFT", "CALCULATED").contains(d.getDistStatus())) throw new BusinessException("Distribution " + d.getDistributionNo() + " is " + d.getDistStatus().toLowerCase() + " and cannot be recalculated; cancel it and start again.", HttpStatus.CONFLICT, "ACC_DIST_FROZEN");
        } else {
            for (AccProfitDistribution o : distRepo.overlapping(org, branch, from, to)) {
                throw new BusinessException("Distribution " + o.getDistributionNo() + " already covers " + o.getPeriodFrom() + " to " + o.getPeriodTo() + " (" + o.getDistStatus().toLowerCase() + "). A period is distributed once.", HttpStatus.CONFLICT, "ACC_DIST_DUPLICATE");
            }
            d = AccProfitDistribution.builder().pidOrg(org).distributionNo(numbers.nextNumber("SEQ_PROFIT_DIST_NO", "PD")).distStatus("DRAFT").build();
        }
        AccProfitPolicy policy = policyId != null ? policyRepo.findById(policyId).filter(x -> x.getPidOrg().equals(org)).orElseThrow(() -> bad("Policy not found."))
                : policyRepo.findEffective(org, branch, to).stream().findFirst().orElseThrow(() ->
                        new BusinessException("No profit-share policy is in force for " + to + ". Set one up under Profit Distribution › Policies.", HttpStatus.CONFLICT, "ACC_NO_POLICY"));
        if (adjustment != null && adjustment.signum() != 0 && (adjustmentReason == null || adjustmentReason.isBlank())) throw bad("An adjustment needs a reason.");

        Map<String, BigDecimal> t = ledger.totalsByType(org, branch, from, to);
        List<Map<String, Object>> lines = ledger.profitAndLoss(org, branch, from, to);
        BigDecimal revenue = BigDecimal.ZERO, cogs = BigDecimal.ZERO, opex = BigDecimal.ZERO, otherInc = BigDecimal.ZERO, otherExp = BigDecimal.ZERO;
        for (Map<String, Object> l : lines) {
            BigDecimal amt = (BigDecimal) l.get("amount"); String role = (String) l.get("role"); String type = (String) l.get("type");
            if ("REVENUE".equals(type)) { if ("OTHER_INCOME".equals(role)) otherInc = otherInc.add(amt); else revenue = revenue.add(amt); }
            else if ("COGS".equals(role)) cogs = cogs.add(amt);
            else if ("OTHER_EXPENSE".equals(role) || "CASH_SHORT_OVER".equals(role) || "BANK_CHARGES".equals(role)) otherExp = otherExp.add(amt);
            else opex = opex.add(amt);
        }
        BigDecimal profit = t.getOrDefault("REVENUE", BigDecimal.ZERO).subtract(t.getOrDefault("EXPENSE", BigDecimal.ZERO)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal retainedPct = nvl(policy.getRetainedPct());
        BigDecimal retained = profit.signum() > 0 ? profit.multiply(retainedPct).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal adj = adjustment == null ? BigDecimal.ZERO : adjustment.setScale(2, RoundingMode.HALF_UP);
        BigDecimal distributable = profit.subtract(retained).add(adj).max(BigDecimal.ZERO);
        BigDecimal pool = distributable.multiply(nvl(policy.getInvestorPoolPct())).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);

        d.setPidBranch(branch); d.setPeriodFrom(from); d.setPeriodTo(to);
        d.setPidPeriodFrom(periodRepo.findCovering(org, from).map(AccPeriod::getPidPeriod).orElse(null));
        d.setPidPeriodTo(periodRepo.findCovering(org, to).map(AccPeriod::getPidPeriod).orElse(null));
        d.setPidProfitPolicy(policy.getPidProfitPolicy()); d.setShareMethod(policy.getShareMethod());
        d.setRevenue(revenue); d.setCostOfSales(cogs); d.setOperatingExpenses(opex); d.setOtherIncome(otherInc); d.setOtherExpense(otherExp);
        d.setAccountingProfit(profit); d.setRetainedPct(retainedPct); d.setRetainedAmount(retained);
        d.setAdjustmentAmount(adj); d.setAdjustmentReason(adjustmentReason); d.setDistributableProfit(distributable); d.setInvestorPoolPct(nvl(policy.getInvestorPoolPct()));
        d.setNotes(notes); d.setCalculatedBy(scope.username()); d.setCalculatedAt(LocalDateTime.now()); d.setDistStatus("CALCULATED");
        d = distRepo.save(d);

        allocationRepo.deleteByPidDistribution(d.getPidDistribution());
        List<Map<String, Object>> snapshot = allocate(d, policy, pool);
        BigDecimal allocated = BigDecimal.ZERO;
        for (Map<String, Object> s : snapshot) allocated = allocated.add((BigDecimal) s.get("allocated"));
        d.setAllocatedTotal(allocated);
        try {
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("method", policy.getShareMethod()); snap.put("policyId", policy.getPidProfitPolicy()); snap.put("pool", pool);
            snap.put("totals", t); snap.put("allocations", snapshot);
            d.setSnapshot(json.writeValueAsString(snap));
        } catch (Exception ignore) { }
        distRepo.save(d);
        return get(d.getPidDistribution());
    }

    /** One allocation row per eligible investor, by the policy method. Returns the snapshot rows. */
    List<Map<String, Object>> allocate(AccProfitDistribution d, AccProfitPolicy policy, BigDecimal pool) {   // package-private for the unit test
        Long org = d.getPidOrg();
        List<AccInvestor> all = investorRepo.findByPidOrgOrderByInvestorCodeAsc(org).stream()
                .filter(i -> "ACTIVE".equals(i.getInvestorStatus()))
                .filter(i -> d.getPidBranch() == null || i.getPidBranch() == null || i.getPidBranch().equals(d.getPidBranch()))
                .toList();
        if (all.isEmpty()) throw new BusinessException("No active investor to allocate to.", HttpStatus.CONFLICT, "ACC_NO_INVESTORS");
        // Capital figures: opening at period start, additions and withdrawals inside, eligible at period end.
        Map<Long, BigDecimal[]> capital = new HashMap<>();
        for (AccInvestor i : all) {
            Map<String, Object> st = ledger.partyStatement(org, null, "INVESTOR", i.getPidInvestor(), "INVESTOR_CAPITAL", d.getPeriodFrom(), d.getPeriodTo());
            BigDecimal opening = (BigDecimal) st.get("openingBalance"), closing = (BigDecimal) st.get("closingBalance");
            BigDecimal add = BigDecimal.ZERO, wd = BigDecimal.ZERO;
            @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) st.get("rows");
            BigDecimal weighted = BigDecimal.ZERO;
            long days = java.time.temporal.ChronoUnit.DAYS.between(d.getPeriodFrom(), d.getPeriodTo()) + 1;
            BigDecimal run = opening; LocalDate cursor = d.getPeriodFrom();
            for (Map<String, Object> r : rows) {
                BigDecimal cr = (BigDecimal) r.get("credit"), dr = (BigDecimal) r.get("debit");
                add = add.add(cr); wd = wd.add(dr);
                LocalDate when = (LocalDate) r.get("date");
                weighted = weighted.add(run.multiply(BigDecimal.valueOf(java.time.temporal.ChronoUnit.DAYS.between(cursor, when))));
                run = run.add(cr).subtract(dr); cursor = when;
            }
            weighted = weighted.add(run.multiply(BigDecimal.valueOf(java.time.temporal.ChronoUnit.DAYS.between(cursor, d.getPeriodTo()) + 1)));
            BigDecimal avg = days > 0 ? weighted.divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP) : closing;
            capital.put(i.getPidInvestor(), new BigDecimal[]{ opening, add, wd, closing, avg });
        }
        Map<Long, BigDecimal> specific = new HashMap<>();
        for (AccProfitPolicyLine l : policyLineRepo.findByPidProfitPolicy(policy.getPidProfitPolicy())) specific.put(l.getPidInvestor(), l.getSharePct());

        String method = policy.getShareMethod();
        boolean weightedAvg = "WEIGHTED_AVERAGE_CAPITAL".equals(method);
        BigDecimal totalEligible = BigDecimal.ZERO;
        for (AccInvestor i : all) totalEligible = totalEligible.add(capital.get(i.getPidInvestor())[weightedAvg ? 4 : 3].max(BigDecimal.ZERO));

        List<Map<String, Object>> snapshot = new ArrayList<>();
        BigDecimal allocatedSoFar = BigDecimal.ZERO;
        for (int idx = 0; idx < all.size(); idx++) {
            AccInvestor i = all.get(idx);
            BigDecimal[] c = capital.get(i.getPidInvestor());
            BigDecimal eligible = c[weightedAvg ? 4 : 3].max(BigDecimal.ZERO);
            BigDecimal sharePct;
            switch (method) {
                case "INVESTOR_SPECIFIC_PERCENTAGE" -> sharePct = specific.getOrDefault(i.getPidInvestor(), BigDecimal.ZERO);
                case "CUSTOM_APPROVED_METHOD" -> sharePct = nvl(i.getSharePct());
                default -> sharePct = totalEligible.signum() == 0 ? BigDecimal.ZERO : eligible.multiply(new BigDecimal("100")).divide(totalEligible, 4, RoundingMode.HALF_UP);
            }
            BigDecimal amount = pool.multiply(sharePct).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            // Rounding: the last investor on a ratio method takes the remainder, so the allocations add up to the pool exactly.
            if (idx == all.size() - 1 && !"INVESTOR_SPECIFIC_PERCENTAGE".equals(method) && !"CUSTOM_APPROVED_METHOD".equals(method) && totalEligible.signum() > 0) {
                amount = pool.subtract(allocatedSoFar).max(BigDecimal.ZERO);
            }
            allocatedSoFar = allocatedSoFar.add(amount);
            allocationRepo.save(AccProfitAllocation.builder().pidDistribution(d.getPidDistribution()).pidInvestor(i.getPidInvestor())
                    .openingCapital(c[0]).additions(c[1]).withdrawals(c[2]).eligibleCapital(eligible).sharePct(sharePct)
                    .allocatedAmount(amount).paidAmount(BigDecimal.ZERO).allocStatus("DRAFT").build());
            snapshot.add(AccLedgerService.row("investorId", i.getPidInvestor(), "investor", i.getInvestorName(), "opening", c[0], "additions", c[1],
                    "withdrawals", c[2], "closing", c[3], "average", c[4], "eligible", eligible, "sharePct", sharePct, "allocated", amount));
        }
        if (allocatedSoFar.compareTo(pool) > 0) throw new BusinessException("The allocations (" + allocatedSoFar + ") exceed the investors' share (" + pool + "). Check the policy percentages.", HttpStatus.CONFLICT, "ACC_OVER_ALLOCATED");
        return snapshot;
    }

    @Transactional
    public Map<String, Object> review(Long id) { return step(id, "CALCULATED", "REVIEWED", PermissionGuard.Right.EDIT, "review a profit distribution"); }

    @Transactional
    public Map<String, Object> approve(Long id) { return step(id, "REVIEWED", "APPROVED", PermissionGuard.Right.APPROVE, "approve a profit distribution"); }

    private Map<String, Object> step(Long id, String from, String to, PermissionGuard.Right right, String action) {
        scope.require(AccScope.MOD_PROFIT, right, action);
        AccProfitDistribution d = owned(id);
        if (!from.equals(d.getDistStatus())) throw new BusinessException("Distribution " + d.getDistributionNo() + " is " + d.getDistStatus().toLowerCase() + "; expected " + from.toLowerCase() + ".", HttpStatus.CONFLICT, "ACC_DIST_STATE");
        if ("REVIEWED".equals(to)) { d.setReviewedBy(scope.username()); d.setReviewedAt(LocalDateTime.now()); }
        if ("APPROVED".equals(to)) { d.setApprovedBy(scope.username()); d.setApprovedAt(LocalDateTime.now()); }
        d.setDistStatus(to);
        distRepo.save(d);
        return get(id);
    }

    /** Posts the liability: Dr Profit Distributed, Cr Investor Profit Payable per investor; writes PROFIT_ALLOCATION rows. */
    @Transactional
    public Map<String, Object> post(Long id) {
        scope.require(AccScope.MOD_PROFIT, PermissionGuard.Right.APPROVE, "post a profit distribution");
        AccProfitDistribution d = owned(id);
        if (!"APPROVED".equals(d.getDistStatus())) throw new BusinessException("Distribution " + d.getDistributionNo() + " must be approved before posting.", HttpStatus.CONFLICT, "ACC_DIST_STATE");
        List<AccProfitAllocation> allocs = allocationRepo.findByPidDistributionOrderByPidAllocationAsc(id);
        BigDecimal total = BigDecimal.ZERO;
        for (AccProfitAllocation a : allocs) total = total.add(nvl(a.getAllocatedAmount()));
        if (total.compareTo(nvl(d.getDistributableProfit())) > 0) throw new BusinessException("Allocated " + total + " exceeds the distributable profit " + d.getDistributableProfit() + ".", HttpStatus.CONFLICT, "ACC_OVER_ALLOCATED");
        if (total.signum() == 0) throw bad("Nothing to post: no investor receives an allocation.");
        Long branch = d.getPidBranch() != null ? d.getPidBranch() : scope.branchId(null);
        FinancialEvent ev = FinancialEvent.of(AccountingHooks.M_INVESTOR, "PROFIT_DISTRIBUTION", d.getPidDistribution(), "POSTED", "PROFIT_ALLOCATED")
                .in(d.getPidOrg(), branch).on(d.getPeriodTo()).ref(d.getDistributionNo(), "Profit allocated for " + d.getPeriodFrom() + " to " + d.getPeriodTo());
        for (AccProfitAllocation a : allocs) {
            if (nvl(a.getAllocatedAmount()).signum() == 0) continue;
            AccInvestor i = investorRepo.findById(a.getPidInvestor()).orElseThrow();
            ev.split("AMOUNT", "INVESTOR", i.getPidInvestor(), i.getInvestorName(), a.getAllocatedAmount());
        }
        AccJournal j = engine.post(ev);
        for (AccProfitAllocation a : allocs) {
            AccInvestor i = investorRepo.findById(a.getPidInvestor()).orElseThrow();
            AccInvestorTxn t = txnRepo.save(AccInvestorTxn.builder().pidOrg(d.getPidOrg()).pidBranch(branch).pidInvestor(i.getPidInvestor())
                    .txnNo(numbers.nextNumber("SEQ_INVESTOR_TXN_NO", "IV")).txnDate(d.getPeriodTo()).txnType("PROFIT_ALLOCATION")
                    .amount(nvl(a.getAllocatedAmount()).max(new BigDecimal("0.01"))).direction(1).description("Allocation " + d.getDistributionNo())
                    .pidDistribution(d.getPidDistribution()).pidAllocation(a.getPidAllocation()).txnStatus("POSTED")
                    .approvedBy(d.getApprovedBy()).approvedAt(d.getApprovedAt()).postedBy(scope.username()).postedAt(LocalDateTime.now())
                    .pidJournal(j == null ? null : j.getPidJournal()).pidEvent(j == null ? null : j.getPidEvent()).build());
            a.setPidInvestorTxn(t.getPidInvestorTxn());
            a.setAllocStatus(nvl(a.getAllocatedAmount()).signum() == 0 ? "PAID" : "POSTED");
            allocationRepo.save(a);
        }
        d.setDistStatus("POSTED"); d.setPostedBy(scope.username()); d.setPostedAt(LocalDateTime.now());
        if (j != null) { d.setPidJournal(j.getPidJournal()); d.setPidEvent(j.getPidEvent()); }
        distRepo.save(d);
        return get(id);
    }

    @Transactional
    public Map<String, Object> cancel(Long id, String reason) {
        scope.require(AccScope.MOD_PROFIT, PermissionGuard.Right.APPROVE, "cancel a profit distribution");
        AccProfitDistribution d = owned(id);
        if ("CANCELLED".equals(d.getDistStatus())) return get(id);
        if (reason == null || reason.isBlank()) throw bad("Say why the distribution is being cancelled.");
        if (nvl(d.getPaidTotal()).signum() > 0) throw new BusinessException("Profit has already been paid on " + d.getDistributionNo() + "; cancel the payments first.", HttpStatus.CONFLICT, "ACC_DIST_PAID");
        if (Set.of("POSTED", "PAID").contains(d.getDistStatus())) {
            engine.reverseSource(d.getPidOrg(), AccountingHooks.M_INVESTOR, "PROFIT_DISTRIBUTION", d.getPidDistribution(), LocalDate.now(), reason.trim());
            for (AccProfitAllocation a : allocationRepo.findByPidDistributionOrderByPidAllocationAsc(id)) {
                if (a.getPidInvestorTxn() != null) txnRepo.findById(a.getPidInvestorTxn()).ifPresent(t -> { t.setTxnStatus("CANCELLED"); t.setCancelReason(reason.trim()); t.setCancelledBy(scope.username()); t.setCancelledAt(LocalDateTime.now()); txnRepo.save(t); });
                a.setAllocStatus("DRAFT"); allocationRepo.save(a);
            }
        }
        d.setDistStatus("CANCELLED"); d.setCancelledBy(scope.username()); d.setCancelledAt(LocalDateTime.now()); d.setCancelReason(reason.trim());
        distRepo.save(d);
        return get(id);
    }

    private AccProfitDistribution owned(Long id) {
        return distRepo.findByPidDistributionAndPidOrg(id, scope.orgId()).orElseThrow(() -> new BusinessException("Distribution not found.", HttpStatus.NOT_FOUND, "ACC_DIST_NOT_FOUND"));
    }
    private static boolean pct(BigDecimal v) { return v.signum() < 0 || v.compareTo(new BigDecimal("100")) > 0; }
    private static BigDecimal nvl(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private static BusinessException bad(String m) { return new BusinessException(m, HttpStatus.UNPROCESSABLE_ENTITY, "ACC_VALIDATION"); }
}
