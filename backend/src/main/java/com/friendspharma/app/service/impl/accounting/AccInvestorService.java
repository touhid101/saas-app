package com.friendspharma.app.service.impl.accounting;

import com.friendspharma.app.entity.*;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.repository.*;
import com.friendspharma.app.service.impl.DocumentNumberService;
import com.friendspharma.app.service.impl.PermissionGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Investors: the master, their capital transactions, and their ledger.
 *
 * <p>An investor's balance is never a column. Capital is the INVESTOR_CAPITAL
 * account read by party; profit owed is INVESTOR_PROFIT_PAYABLE read by
 * party. Every transaction here raises an accounting event; nothing else
 * moves either figure.
 */
@Service
@RequiredArgsConstructor
public class AccInvestorService {

    public static final Set<String> MANUAL_TYPES = Set.of("INITIAL_INVESTMENT", "ADDITIONAL_INVESTMENT", "CAPITAL_WITHDRAWAL", "CAPITAL_ADJUSTMENT", "REFUND", "OTHER_APPROVED_ADJUSTMENT", "PROFIT_DISTRIBUTION");

    private final AccScope                    scope;
    private final AccInvestorRepository       investorRepo;
    private final AccInvestorTxnRepository    txnRepo;
    private final AccProfitAllocationRepository allocationRepo;
    private final AccProfitDistributionRepository distributionRepo;
    private final AccountingEventService      engine;
    private final AccChartService             chart;
    private final AccLedgerService            ledger;
    private final DocumentNumberService       numbers;

    /* ── master ─────────────────────────────────────────────────────────── */

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        scope.require(AccScope.MOD_INVESTORS, PermissionGuard.Right.VIEW, "see investors");
        Long org = scope.orgId();
        Map<Long, BigDecimal> capital = byParty(org, "INVESTOR_CAPITAL");
        Map<Long, BigDecimal> payable = byParty(org, "INVESTOR_PROFIT_PAYABLE");
        List<Map<String, Object>> out = new ArrayList<>();
        for (AccInvestor i : investorRepo.findByPidOrgOrderByInvestorCodeAsc(org)) {
            BigDecimal allocated = BigDecimal.ZERO, paid = BigDecimal.ZERO;
            for (AccProfitAllocation a : allocationRepo.findByPidInvestorOrderByPidAllocationDesc(i.getPidInvestor())) {
                if ("DRAFT".equals(a.getAllocStatus())) continue;
                allocated = allocated.add(nvl(a.getAllocatedAmount())); paid = paid.add(nvl(a.getPaidAmount()));
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("investor", i);
            m.put("capital", capital.getOrDefault(i.getPidInvestor(), BigDecimal.ZERO));
            m.put("profitAllocated", allocated); m.put("profitPaid", paid);
            m.put("profitOutstanding", payable.getOrDefault(i.getPidInvestor(), BigDecimal.ZERO));
            out.add(m);
        }
        return out;
    }

    @Transactional
    public AccInvestor save(Long id, AccInvestor in) {
        scope.require(AccScope.MOD_INVESTORS, id == null ? PermissionGuard.Right.CREATE : PermissionGuard.Right.EDIT, "maintain investors");
        Long org = scope.orgId();
        if (in.getInvestorName() == null || in.getInvestorName().isBlank()) throw bad("An investor needs a name.");
        if (in.getSharePct() != null && (in.getSharePct().signum() < 0 || in.getSharePct().compareTo(new BigDecimal("100")) > 0)) throw bad("Share percentage must be between 0 and 100.");
        AccInvestor i = id == null ? AccInvestor.builder().pidOrg(org).investorStatus("ACTIVE").build() : owned(id);
        if (id == null) {
            String code = in.getInvestorCode() == null || in.getInvestorCode().isBlank() ? nextCode(org) : in.getInvestorCode().trim();
            investorRepo.findByPidOrgOrderByInvestorCodeAsc(org).stream().filter(x -> x.getInvestorCode().equalsIgnoreCase(code)).findAny()
                    .ifPresent(x -> { throw bad("Investor code " + code + " is already used by " + x.getInvestorName() + "."); });
            i.setInvestorCode(code);
        }
        i.setInvestorName(in.getInvestorName().trim());
        i.setPidBranch(in.getPidBranch()); i.setContactPhone(in.getContactPhone()); i.setContactEmail(in.getContactEmail()); i.setAddress(in.getAddress());
        i.setIdReference(in.getIdReference()); i.setInvestmentType(in.getInvestmentType() == null ? "EQUITY" : in.getInvestmentType());
        i.setStartDate(in.getStartDate()); i.setEndDate(in.getEndDate()); i.setSharePct(in.getSharePct());
        i.setBankName(in.getBankName()); i.setBankAccountNo(in.getBankAccountNo()); i.setMobileWallet(in.getMobileWallet()); i.setRemarks(in.getRemarks());
        if (in.getInvestorStatus() != null) i.setInvestorStatus(in.getInvestorStatus());
        return investorRepo.save(i);
    }

    private String nextCode(Long org) {
        long n = investorRepo.countByPidOrg(org) + 1;
        return String.format("INV-%03d", n);
    }

    /* ── detail & ledger ────────────────────────────────────────────────── */

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id, LocalDate from, LocalDate to) {
        scope.require(AccScope.MOD_INVESTORS, PermissionGuard.Right.VIEW, "see an investor");
        AccInvestor i = owned(id);
        Long org = scope.orgId();
        LocalDate f = from != null ? from : LocalDate.of(2000, 1, 1), t = to != null ? to : LocalDate.now();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("investor", i);
        out.put("capital", byParty(org, "INVESTOR_CAPITAL").getOrDefault(id, BigDecimal.ZERO));
        out.put("profitOutstanding", byParty(org, "INVESTOR_PROFIT_PAYABLE").getOrDefault(id, BigDecimal.ZERO));
        out.put("transactions", txnRepo.findByPidInvestorOrderByTxnDateAscPidInvestorTxnAsc(id));
        out.put("allocations", allocationRepo.findByPidInvestorOrderByPidAllocationDesc(id));
        out.put("capitalLedger", ledger.partyStatement(org, null, "INVESTOR", id, "INVESTOR_CAPITAL", f, t));
        out.put("profitLedger", ledger.partyStatement(org, null, "INVESTOR", id, "INVESTOR_PROFIT_PAYABLE", f, t));
        out.put("ledger", ledger.partyStatement(org, null, "INVESTOR", id, null, f, t));
        return out;
    }

    /* ── transactions ───────────────────────────────────────────────────── */

    @Transactional(readOnly = true)
    public Page<AccInvestorTxn> transactions(Long investorId, String status, int page, int size) {
        scope.require(AccScope.MOD_INVESTORS, PermissionGuard.Right.VIEW, "see investor transactions");
        return txnRepo.search(scope.orgId(), investorId, status == null || status.isBlank() ? null : status, PageRequest.of(page, Math.min(size, 200)));
    }

    /** Creates a transaction. Investments and withdrawals post at once with Create; adjustments and refunds wait for Approve. */
    @Transactional
    public AccInvestorTxn create(AccInvestorTxn in) {
        scope.require(AccScope.MOD_INVESTORS, PermissionGuard.Right.CREATE, "record an investor transaction");
        Long org = scope.orgId();
        AccInvestor inv = owned(in.getPidInvestor());
        if (!"ACTIVE".equals(inv.getInvestorStatus())) throw bad(inv.getInvestorName() + " is " + inv.getInvestorStatus().toLowerCase() + "; no transaction can be recorded.");
        if (in.getTxnType() == null || !MANUAL_TYPES.contains(in.getTxnType())) throw bad("Transaction type must be one of " + MANUAL_TYPES + ".");
        if (in.getAmount() == null || in.getAmount().signum() <= 0) throw bad("Amount must be greater than zero.");
        Long branch = scope.branchId(in.getPidBranch() != null ? in.getPidBranch() : inv.getPidBranch());
        boolean adjustment = Set.of("CAPITAL_ADJUSTMENT", "OTHER_APPROVED_ADJUSTMENT").contains(in.getTxnType());
        if (adjustment && (in.getReason() == null || in.getReason().isBlank())) throw bad("An adjustment needs a reason.");
        if ("PROFIT_DISTRIBUTION".equals(in.getTxnType())) {
            if (in.getPidAllocation() == null) throw bad("A profit payment names the allocation it pays.");
            AccProfitAllocation a = allocationRepo.findById(in.getPidAllocation()).orElseThrow(() -> bad("Allocation not found."));
            if (!a.getPidInvestor().equals(inv.getPidInvestor())) throw bad("That allocation belongs to another investor.");
            if (!Set.of("POSTED", "PARTIAL").contains(a.getAllocStatus())) throw bad("The allocation is " + a.getAllocStatus().toLowerCase() + "; only a posted allocation can be paid.");
            BigDecimal left = nvl(a.getAllocatedAmount()).subtract(nvl(a.getPaidAmount()));
            if (in.getAmount().compareTo(left) > 0) throw bad("Only " + left.toPlainString() + " of this allocation is still unpaid.");
            in.setPidDistribution(a.getPidDistribution());
        }
        AccInvestorTxn t = AccInvestorTxn.builder()
                .pidOrg(org).pidBranch(branch).pidInvestor(inv.getPidInvestor()).txnNo(numbers.nextNumber("SEQ_INVESTOR_TXN_NO", "IV"))
                .txnDate(in.getTxnDate() != null ? in.getTxnDate() : LocalDate.now()).txnType(in.getTxnType()).amount(in.getAmount().setScale(2, RoundingMode.HALF_UP))
                .direction(in.getDirection() == null ? 1 : in.getDirection()).paymentMode(in.getPaymentMode()).reference(in.getReference())
                .description(in.getDescription()).reason(in.getReason()).pidDistribution(in.getPidDistribution()).pidAllocation(in.getPidAllocation())
                .txnStatus("DRAFT").build();
        t = txnRepo.save(t);
        if (!adjustment || scope.has(AccScope.MOD_INVESTORS, PermissionGuard.Right.APPROVE)) return post(t.getPidInvestorTxn());
        return t;
    }

    @Transactional
    public AccInvestorTxn approve(Long id) {
        scope.require(AccScope.MOD_INVESTORS, PermissionGuard.Right.APPROVE, "approve an investor transaction");
        AccInvestorTxn t = ownedTxn(id);
        if (!"DRAFT".equals(t.getTxnStatus())) throw new BusinessException("Transaction " + t.getTxnNo() + " is " + t.getTxnStatus().toLowerCase() + ".", HttpStatus.CONFLICT, "ACC_NOT_DRAFT");
        t.setTxnStatus("APPROVED"); t.setApprovedBy(scope.username()); t.setApprovedAt(LocalDateTime.now());
        txnRepo.save(t);
        return post(id);
    }

    @Transactional
    public AccInvestorTxn post(Long id) {
        AccInvestorTxn t = ownedTxn(id);
        if ("POSTED".equals(t.getTxnStatus())) return t;
        if ("CANCELLED".equals(t.getTxnStatus())) throw new BusinessException("Transaction " + t.getTxnNo() + " is cancelled.", HttpStatus.CONFLICT, "ACC_CANCELLED");
        AccInvestor inv = owned(t.getPidInvestor());
        String eventType;
        switch (t.getTxnType()) {
            case "INITIAL_INVESTMENT", "ADDITIONAL_INVESTMENT" -> eventType = "INVESTOR_INVESTMENT";
            case "CAPITAL_WITHDRAWAL", "REFUND" -> eventType = "INVESTOR_WITHDRAWAL";
            case "PROFIT_DISTRIBUTION" -> eventType = "PROFIT_PAID";
            default -> eventType = Integer.valueOf(-1).equals(t.getDirection()) ? "INVESTOR_ADJUSTMENT_DOWN" : "INVESTOR_ADJUSTMENT_UP";
        }
        if ("INVESTOR_WITHDRAWAL".equals(eventType)) {
            BigDecimal capital = byParty(t.getPidOrg(), "INVESTOR_CAPITAL").getOrDefault(inv.getPidInvestor(), BigDecimal.ZERO);
            if (t.getAmount().compareTo(capital) > 0) throw bad(inv.getInvestorName() + " holds " + capital.toPlainString() + " of capital; " + t.getAmount().toPlainString() + " cannot be withdrawn.");
        }
        AccJournal j = engine.post(FinancialEvent.of(AccountingHooks.M_INVESTOR, "INVESTOR_TXN", t.getPidInvestorTxn(), "POSTED", eventType)
                .in(t.getPidOrg(), t.getPidBranch()).on(t.getTxnDate()).mode(t.getPaymentMode())
                .ref(t.getTxnNo(), label(t.getTxnType()) + " — " + inv.getInvestorName() + (t.getDescription() == null ? "" : ": " + t.getDescription()))
                .party("INVESTOR", inv.getPidInvestor(), inv.getInvestorName()).amount("AMOUNT", t.getAmount()));
        t.setTxnStatus("POSTED"); t.setPostedBy(scope.username()); t.setPostedAt(LocalDateTime.now());
        if (j != null) { t.setPidJournal(j.getPidJournal()); t.setPidEvent(j.getPidEvent()); }
        if (t.getApprovedBy() == null) { t.setApprovedBy(scope.username()); t.setApprovedAt(LocalDateTime.now()); }
        t = txnRepo.save(t);
        if ("PROFIT_DISTRIBUTION".equals(t.getTxnType()) && t.getPidAllocation() != null) settlePaid(t.getPidAllocation(), t.getAmount());
        return t;
    }

    @Transactional
    public AccInvestorTxn cancel(Long id, String reason) {
        scope.require(AccScope.MOD_INVESTORS, PermissionGuard.Right.APPROVE, "cancel an investor transaction");
        AccInvestorTxn t = ownedTxn(id);
        if ("CANCELLED".equals(t.getTxnStatus())) return t;
        if ("PROFIT_ALLOCATION".equals(t.getTxnType())) throw bad("An allocation is cancelled through its profit distribution.");
        if (reason == null || reason.isBlank()) throw bad("Say why the transaction is being cancelled.");
        if ("POSTED".equals(t.getTxnStatus())) {
            engine.reverseSource(t.getPidOrg(), AccountingHooks.M_INVESTOR, "INVESTOR_TXN", t.getPidInvestorTxn(), LocalDate.now(), reason.trim());
            if ("PROFIT_DISTRIBUTION".equals(t.getTxnType()) && t.getPidAllocation() != null) settlePaid(t.getPidAllocation(), t.getAmount().negate());
        }
        t.setTxnStatus("CANCELLED"); t.setCancelledBy(scope.username()); t.setCancelledAt(LocalDateTime.now()); t.setCancelReason(reason.trim());
        return txnRepo.save(t);
    }

    private void settlePaid(Long allocationId, BigDecimal delta) {
        AccProfitAllocation a = allocationRepo.findById(allocationId).orElse(null);
        if (a == null) return;
        BigDecimal paid = nvl(a.getPaidAmount()).add(delta).max(BigDecimal.ZERO);
        a.setPaidAmount(paid);
        a.setAllocStatus(paid.compareTo(nvl(a.getAllocatedAmount())) >= 0 ? "PAID" : paid.signum() > 0 ? "PARTIAL" : "POSTED");
        allocationRepo.save(a);
        AccProfitDistribution d = distributionRepo.findById(a.getPidDistribution()).orElse(null);
        if (d != null) {
            BigDecimal total = BigDecimal.ZERO; boolean allPaid = true;
            for (AccProfitAllocation x : allocationRepo.findByPidDistributionOrderByPidAllocationAsc(d.getPidDistribution())) {
                total = total.add(nvl(x.getPaidAmount()));
                if (!"PAID".equals(x.getAllocStatus()) && nvl(x.getAllocatedAmount()).signum() > 0) allPaid = false;
            }
            d.setPaidTotal(total);
            if (Set.of("POSTED", "PAID").contains(d.getDistStatus())) d.setDistStatus(allPaid ? "PAID" : "POSTED");
            distributionRepo.save(d);
        }
    }

    /* ── helpers ────────────────────────────────────────────────────────── */

    /** Balance per investor of one posting role (capital or profit payable). */
    public Map<Long, BigDecimal> byParty(Long org, String role) {
        Map<Long, BigDecimal> out = new HashMap<>();
        for (Map<String, Object> r : ledger.partyBalances(org, null, "INVESTOR", role, LocalDate.now(), false)) {
            out.put(((Number) r.get("partyId")).longValue(), (BigDecimal) r.get("balance"));
        }
        return out;
    }

    static String label(String type) {
        return switch (type) {
            case "INITIAL_INVESTMENT" -> "Initial investment"; case "ADDITIONAL_INVESTMENT" -> "Additional investment";
            case "CAPITAL_WITHDRAWAL" -> "Capital withdrawal"; case "CAPITAL_ADJUSTMENT" -> "Capital adjustment";
            case "PROFIT_ALLOCATION" -> "Profit allocated"; case "PROFIT_DISTRIBUTION" -> "Profit paid"; case "LOSS_ALLOCATION" -> "Loss allocated";
            case "REFUND" -> "Refund"; default -> "Approved adjustment";
        };
    }

    AccInvestor owned(Long id) {
        return investorRepo.findByPidInvestorAndPidOrg(id, scope.orgId()).orElseThrow(() -> new BusinessException("Investor not found.", HttpStatus.NOT_FOUND, "ACC_INVESTOR_NOT_FOUND"));
    }
    private AccInvestorTxn ownedTxn(Long id) {
        return txnRepo.findByPidInvestorTxnAndPidOrg(id, scope.orgId()).orElseThrow(() -> new BusinessException("Investor transaction not found.", HttpStatus.NOT_FOUND, "ACC_TXN_NOT_FOUND"));
    }
    static BigDecimal nvl(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private static BusinessException bad(String m) { return new BusinessException(m, HttpStatus.UNPROCESSABLE_ENTITY, "ACC_VALIDATION"); }
}
