package com.friendspharma.app.service.impl.accounting;

import com.friendspharma.app.entity.AccAccount;
import com.friendspharma.app.entity.AccCashVoucher;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.entity.AccCostCenter;
import com.friendspharma.app.repository.AccCashVoucherRepository;
import com.friendspharma.app.repository.AccCostCenterRepository;
import com.friendspharma.app.service.impl.DocumentNumberService;
import com.friendspharma.app.service.impl.PermissionGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Cash, bank and petty cash vouchers — the money movements no other module
 * records: other receipts and payments, transfers between the till, the bank
 * and the petty cash box, the controlled first-day opening balance, and
 * approved adjustments.
 *
 * <p>A voucher is a business document with its own number; posting it raises
 * a CASH_* event so the journal is the engine's, not this class's. Opening
 * balances and adjustments need a reason and Approve, because they create
 * cash the trading did not.
 */
@Service
@RequiredArgsConstructor
public class AccCashService {

    public static final Set<String> KINDS = Set.of("RECEIPT", "PAYMENT", "TRANSFER", "OPENING", "ADJUSTMENT_IN", "ADJUSTMENT_OUT");
    private static final Set<String> CASH_ROLES = Set.of("CASH_REGISTER", "PETTY_CASH", "BANK_DEFAULT", "MOBILE_WALLET", "CASH_IN_TRANSIT");

    private final AccScope                 scope;
    private final AccCashVoucherRepository repo;
    private final AccChartService          chart;
    private final AccountingEventService   engine;
    private final AccLedgerService         ledger;
    private final DocumentNumberService    numbers;
    private final AccCostCenterRepository  costCenters;

    @Transactional(readOnly = true)
    public Page<AccCashVoucher> search(Long branchId, String status, String kind, LocalDate from, LocalDate to, int page, int size) {
        scope.require(AccScope.MOD_CASH_BANK, PermissionGuard.Right.VIEW, "see cash vouchers");
        LocalDate f = from != null ? from : LocalDate.now().minusDays(30), t = to != null ? to : LocalDate.now();
        return repo.search(scope.orgId(), scope.branchFilter(branchId), blank(status), blank(kind), f, t, PageRequest.of(page, Math.min(size, 200)));
    }

    /** The cash-type accounts a voucher may move: till, petty cash, wallets, every bank account, cash in transit. */
    @Transactional(readOnly = true)
    public List<AccAccount> cashAccounts() {
        Long org = scope.orgId();
        chart.ensureProvisioned(org);
        List<AccAccount> out = new ArrayList<>();
        for (AccAccount a : chart.list(org)) {
            if (Integer.valueOf(1).equals(a.getIsGroup()) || !Integer.valueOf(1).equals(a.getIsActive())) continue;
            if ((a.getRoleKey() != null && CASH_ROLES.contains(a.getRoleKey())) || (a.getAccountCode().startsWith("113") && "ASSET".equals(a.getAccountType()))) out.add(a);
        }
        return out;
    }

    /** Balance of every cash account for the caller's branch, as at today. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> positions(Long branchId) {
        scope.require(AccScope.MOD_CASH_BANK, PermissionGuard.Right.VIEW, "see cash positions");
        Long branch = scope.branchFilter(branchId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (AccAccount a : cashAccounts()) {
            out.add(AccLedgerService.row("accountId", a.getPidAccount(), "code", a.getAccountCode(), "name", a.getAccountName(), "role", a.getRoleKey(),
                    "balance", ledger.accountBalance(branch, a.getPidAccount(), LocalDate.now(), a.getNormalSide())));
        }
        return out;
    }

    @Transactional
    public AccCashVoucher create(AccCashVoucher in) {
        scope.require(AccScope.MOD_CASH_BANK, PermissionGuard.Right.CREATE, "create a cash voucher");
        Long org = scope.orgId();
        Long branch = scope.branchId(in.getPidBranch());
        chart.ensureProvisioned(org);
        validate(org, in);
        AccCashVoucher v = AccCashVoucher.builder()
                .pidOrg(org).pidBranch(branch).voucherNo(numbers.nextNumber("SEQ_CASH_VOUCHER_NO", "CV"))
                .voucherDate(in.getVoucherDate() != null ? in.getVoucherDate() : LocalDate.now()).voucherKind(in.getVoucherKind())
                .pidCashAccount(in.getPidCashAccount()).pidCounterAccount(in.getPidCounterAccount()).amount(in.getAmount())
                .partyType(in.getPartyType()).partyId(in.getPartyId()).partyName(in.getPartyName())
                .reference(in.getReference()).description(in.getDescription()).reason(in.getReason()).pidCostCenter(in.getPidCostCenter())
                .voucherStatus("DRAFT").build();
        v = repo.save(v);
        boolean needsApproval = Set.of("OPENING", "ADJUSTMENT_IN", "ADJUSTMENT_OUT").contains(v.getVoucherKind());
        if (!needsApproval || scope.has(AccScope.MOD_CASH_BANK, PermissionGuard.Right.APPROVE)) return post(v.getPidCashVoucher());
        return v;
    }

    @Transactional
    public AccCashVoucher approve(Long id) {
        scope.require(AccScope.MOD_CASH_BANK, PermissionGuard.Right.APPROVE, "approve a cash voucher");
        AccCashVoucher v = owned(id);
        if (!"DRAFT".equals(v.getVoucherStatus())) throw new BusinessException("Voucher " + v.getVoucherNo() + " is " + v.getVoucherStatus().toLowerCase() + ".", HttpStatus.CONFLICT, "ACC_NOT_DRAFT");
        v.setVoucherStatus("APPROVED"); v.setApprovedBy(scope.username()); v.setApprovedAt(LocalDateTime.now());
        repo.save(v);
        return post(id);
    }

    @Transactional
    public AccCashVoucher post(Long id) {
        AccCashVoucher v = owned(id);
        if ("POSTED".equals(v.getVoucherStatus())) return v;
        if ("CANCELLED".equals(v.getVoucherStatus())) throw new BusinessException("Voucher " + v.getVoucherNo() + " is cancelled.", HttpStatus.CONFLICT, "ACC_CANCELLED");
        boolean needsApproval = Set.of("OPENING", "ADJUSTMENT_IN", "ADJUSTMENT_OUT").contains(v.getVoucherKind());
        if (needsApproval && !"APPROVED".equals(v.getVoucherStatus())) scope.require(AccScope.MOD_CASH_BANK, PermissionGuard.Right.APPROVE, "post an opening balance or adjustment");
        String eventType = switch (v.getVoucherKind()) {
            case "RECEIPT" -> "CASH_RECEIPT"; case "PAYMENT" -> "CASH_PAYMENT"; case "TRANSFER" -> "CASH_TRANSFER";
            case "OPENING" -> "CASH_OPENING"; case "ADJUSTMENT_IN" -> "CASH_ADJUSTMENT_IN"; default -> "CASH_ADJUSTMENT_OUT";
        };
        FinancialEvent ev = FinancialEvent.of(AccountingHooks.M_CASH, "CASH_VOUCHER", v.getPidCashVoucher(), "POSTED", eventType)
                .in(v.getPidOrg(), v.getPidBranch()).on(v.getVoucherDate()).ref(v.getVoucherNo(), describe(v)).costCenter(v.getPidCostCenter())
                .party(v.getPartyType(), v.getPartyId(), v.getPartyName()).amount("AMOUNT", v.getAmount())
                .account("CASH_ACCOUNT", v.getPidCashAccount()).account("COUNTER_ACCOUNT", v.getPidCounterAccount())
                .account("FROM_ACCOUNT", v.getPidCashAccount()).account("TO_ACCOUNT", v.getPidCounterAccount());
        ev.journalType = "OPENING".equals(v.getVoucherKind()) ? "OPENING" : "AUTO";
        var journal = engine.post(ev);
        v.setVoucherStatus("POSTED"); v.setPostedBy(scope.username()); v.setPostedAt(LocalDateTime.now());
        if (journal != null) { v.setPidJournal(journal.getPidJournal()); v.setPidEvent(journal.getPidEvent()); }
        if (v.getApprovedBy() == null && needsApproval) { v.setApprovedBy(scope.username()); v.setApprovedAt(LocalDateTime.now()); }
        return repo.save(v);
    }

    @Transactional
    public AccCashVoucher cancel(Long id, String reason) {
        scope.require(AccScope.MOD_CASH_BANK, PermissionGuard.Right.APPROVE, "cancel a cash voucher");
        AccCashVoucher v = owned(id);
        if ("CANCELLED".equals(v.getVoucherStatus())) return v;
        if (reason == null || reason.isBlank()) throw bad("Say why the voucher is being cancelled.");
        if ("POSTED".equals(v.getVoucherStatus())) {
            engine.reverseSource(v.getPidOrg(), AccountingHooks.M_CASH, "CASH_VOUCHER", v.getPidCashVoucher(), LocalDate.now(), reason.trim());
        }
        v.setVoucherStatus("CANCELLED"); v.setCancelledBy(scope.username()); v.setCancelledAt(LocalDateTime.now()); v.setCancelReason(reason.trim());
        return repo.save(v);
    }

    private void validate(Long org, AccCashVoucher in) {
        if (in.getVoucherKind() == null || !KINDS.contains(in.getVoucherKind())) throw bad("Voucher kind must be one of " + KINDS + ".");
        /* The cost centre is optional, but if one is given it has to be this
           organisation's and still in use: a posting tagged to a number nobody
           can look up is worse than one tagged to nothing. */
        if (in.getPidCostCenter() != null) {
            AccCostCenter cc = costCenters.findById(in.getPidCostCenter())
                    .filter(c -> org.equals(c.getPidOrg()))
                    .orElseThrow(() -> bad("That cost centre does not belong to this organisation."));
            if (!Integer.valueOf(1).equals(cc.getIsActive())) throw bad(cc.getCcName() + " is a retired cost centre.");
        }
        if (in.getAmount() == null || in.getAmount().signum() <= 0) throw bad("Amount must be greater than zero.");
        if (in.getPidCashAccount() == null) throw bad("Choose the cash, bank or petty cash account.");
        AccAccount cash = chart.owned(org, in.getPidCashAccount());
        boolean cashLike = (cash.getRoleKey() != null && CASH_ROLES.contains(cash.getRoleKey())) || cash.getAccountCode().startsWith("113");
        if (!cashLike) throw bad(cash.getAccountName() + " is not a cash, bank or petty cash account.");
        switch (in.getVoucherKind()) {
            case "RECEIPT", "PAYMENT" -> {
                if (in.getPidCounterAccount() == null) throw bad("Choose the account the money is " + ("RECEIPT".equals(in.getVoucherKind()) ? "received for" : "paid for") + ".");
                AccAccount c = chart.owned(org, in.getPidCounterAccount());
                if (Integer.valueOf(1).equals(c.getIsGroup())) throw bad(c.getAccountName() + " is a group account.");
                if (c.getPidAccount().equals(cash.getPidAccount())) throw bad("The counter account must differ from the cash account.");
            }
            case "TRANSFER" -> {
                if (in.getPidCounterAccount() == null) throw bad("Choose the account the money goes to.");
                AccAccount to = chart.owned(org, in.getPidCounterAccount());
                boolean toCash = (to.getRoleKey() != null && CASH_ROLES.contains(to.getRoleKey())) || to.getAccountCode().startsWith("113");
                if (!toCash) throw bad("A transfer moves money between two cash, bank or petty cash accounts.");
                if (to.getPidAccount().equals(cash.getPidAccount())) throw bad("A transfer needs two different accounts.");
            }
            default -> { if (in.getReason() == null || in.getReason().isBlank()) throw bad("An opening balance or adjustment needs a reason; it creates cash the trading did not."); }
        }
    }

    private static String describe(AccCashVoucher v) {
        String base = switch (v.getVoucherKind()) {
            case "RECEIPT" -> "Cash receipt"; case "PAYMENT" -> "Cash payment"; case "TRANSFER" -> "Cash transfer";
            case "OPENING" -> "Opening cash balance"; case "ADJUSTMENT_IN" -> "Cash adjustment (in)"; default -> "Cash adjustment (out)";
        };
        return base + (v.getDescription() == null || v.getDescription().isBlank() ? "" : ": " + v.getDescription());
    }

    private AccCashVoucher owned(Long id) {
        AccCashVoucher v = repo.findByPidCashVoucherAndPidOrg(id, scope.orgId()).orElseThrow(() -> new BusinessException("Voucher not found.", HttpStatus.NOT_FOUND, "ACC_VOUCHER_NOT_FOUND"));
        Long b = scope.branchFilter(null);
        if (b != null && !b.equals(v.getPidBranch())) throw new BusinessException("Voucher not found.", HttpStatus.NOT_FOUND, "ACC_VOUCHER_NOT_FOUND");
        return v;
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s; }
    private static BusinessException bad(String m) { return new BusinessException(m, HttpStatus.UNPROCESSABLE_ENTITY, "ACC_VALIDATION"); }
}
