package com.friendspharma.app.service.impl.accounting;

import com.friendspharma.app.entity.*;
import com.friendspharma.app.repository.*;
import com.friendspharma.app.service.impl.BranchScopeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * Where the business modules meet Accounts.
 *
 * <p>One method per business event, called from the service that commits the
 * transaction, inside that transaction. Each method reads the committed
 * figures off the entity — never off the request — turns them into a
 * {@link FinancialEvent} and hands it to the engine. Modules know nothing of
 * accounts or debits; the rules do.
 *
 * <p>Every method here is safe to call twice (the engine is idempotent) and
 * throws only when the books genuinely cannot take the transaction, in which
 * case the caller's transaction rolls back.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountingHooks {

    public static final String M_SALES = "SALES", M_PURCHASE = "PURCHASE", M_SUPPLIER_PAYMENT = "SUPPLIER_PAYMENT",
            M_COLLECTION = "COLLECTION", M_HR = "HR", M_SUBSCRIPTION = "SUBSCRIPTION", M_INVESTOR = "INVESTOR", M_CASH = "CASH";
    private static final String REF_TYPE = "TRANSACTION";

    private final AccountingEventService       engine;
    private final AccChartService              chart;
    private final AccSettings                  settings;
    private final PosStockMovementRepository   movementRepo;
    private final PosCustomerInfoRepository    customerRepo;
    private final PosSupplierInfoRepository    supplierRepo;
    private final HrEmployeeRepository         employeeRepo;
    private final HrPayrollLineRepository      payrollLineRepo;
    private final HrExpenseTypeRepository      expenseTypeRepo;
    private final AllBranchRepository          branchRepo;
    private final BranchScopeService           branchScope;

    /* ══════════════════════════════════════════════════════════════════════
       SALES
       ══════════════════════════════════════════════════════════════════════ */

    /** A sales invoice, on creation. Revenue, COGS, receivable — and the cash handed over, if any. */
    @Transactional
    public void saleCreated(PosTransactionMst m) {
        postSale(m, "POSTED", "RECEIPT", null);
    }

    /** The same invoice after its lines were rewritten: the earlier journals are reversed and it is posted afresh. */
    @Transactional
    public void saleUpdated(PosTransactionMst m) {
        engine.reverseSource(m.getPidOrg(), M_SALES, "SALE", m.getPidTranMst(), LocalDate.now(), "Invoice " + m.getInvoiceNo() + " was edited");
        String rev = engine.nextRevisionKey(m.getPidOrg(), M_SALES, "SALE", m.getPidTranMst(), "POSTED");
        String suffix = rev.contains(":") ? rev.substring(rev.indexOf(':')) : "";
        postSale(m, rev, "RECEIPT" + suffix, null);
    }

    /**
     * The invoice called off: every journal it raised is reversed, and nothing
     * is posted in their place.
     *
     * <p>Not a deletion. The sale was booked, and the books say so; the
     * reversal is a second set of entries dated today that takes the revenue,
     * the receivable and the cost of goods back out. A cancellation that erased
     * the original would leave an auditor with a gap where a document used to
     * be, which is the one thing posted history must never have.
     *
     * <p>The receipt is reversed with them — {@code reverseSource} takes every
     * journal for the document — so a sale that was paid at the counter cannot
     * be cancelled without the money going back too. That is why
     * {@code SalesServiceImpl.cancelSale} refuses one that has been paid and
     * asks for a sales return instead: the books can express it, but the cash
     * drawer is the reason it must be a deliberate act.
     */
    @Transactional
    public void saleCancelled(PosTransactionMst m, String reason) {
        engine.reverseSource(m.getPidOrg(), M_SALES, "SALE", m.getPidTranMst(), LocalDate.now(),
                "Invoice " + m.getInvoiceNo() + " cancelled"
                + (reason == null || reason.isBlank() ? "" : " — " + reason.trim()));
    }

    private void postSale(PosTransactionMst m, String postKey, String receiptKey, Long batch) {
        if (!"SL".equals(m.getTransactionType()) || "Y".equals(m.getIsMergeInvoice())) return;
        BigDecimal payable  = nvl(m.getAmountTobePaid());
        BigDecimal delivery = nvl(m.getDeliveryCharge());
        BigDecimal revenue  = payable.subtract(delivery);
        BigDecimal cogs     = costOf(m.getPidTranMst(), PosStockMovement.SALE);
        String customer = customerName(m.getPidSupCust());
        FinancialEvent sale = FinancialEvent.of(M_SALES, "SALE", m.getPidTranMst(), postKey, "SALE_POSTED")
                .in(m.getPidOrg(), m.getPidBranch()).on(m.getTransactionDate())
                .ref(m.getInvoiceNo(), "Sale " + m.getInvoiceNo() + " to " + customer)
                .party("CUSTOMER", m.getPidSupCust(), customer)
                .amount("RECEIVABLE", payable).amount("REVENUE", revenue).amount("DELIVERY_CHARGE", delivery).amount("COGS", cogs);
        if (batch != null) sale.historical(batch);
        engine.post(sale);

        BigDecimal given = nvl(m.getGivenAmount()).min(payable.max(BigDecimal.ZERO));
        if (given.signum() > 0) {
            FinancialEvent receipt = FinancialEvent.of(M_SALES, "SALE", m.getPidTranMst(), receiptKey, "SALE_RECEIPT")
                    .in(m.getPidOrg(), m.getPidBranch()).on(m.getTransactionDate()).mode(m.getPaymentType())
                    .ref(m.getInvoiceNo(), "Paid with sale " + m.getInvoiceNo()).party("CUSTOMER", m.getPidSupCust(), customer)
                    .amount("AMOUNT", given);
            if (batch != null) receipt.historical(batch);
            engine.post(receipt);
        }
    }

    /** Money received against an invoice after it was raised (Collect on Sales Activity / Delivery). */
    @Transactional
    public void saleCollected(PosTransactionMst m, BigDecimal amount, String paymentType) {
        if (amount == null || amount.signum() <= 0 || "Y".equals(m.getIsMergeInvoice())) return;
        String key = engine.nextRevisionKey(m.getPidOrg(), M_SALES, "SALE", m.getPidTranMst(), "COLLECTION");
        String customer = customerName(m.getPidSupCust());
        boolean viaRider = m.getDeliveryMan() != null && settings.is(m.getPidOrg(), AccSettings.USE_CASH_IN_TRANSIT);
        Long receiptAccount = viaRider
                ? chart.byRole(m.getPidOrg(), "CASH_IN_TRANSIT").getPidAccount()
                : chart.byPaymentMode(m.getPidOrg(), paymentType != null ? paymentType : m.getPaymentType())
                      .orElseGet(() -> chart.byRole(m.getPidOrg(), "CASH_REGISTER")).getPidAccount();
        engine.post(FinancialEvent.of(M_SALES, "SALE", m.getPidTranMst(), key, "SALE_COLLECTION")
                .in(m.getPidOrg(), m.getPidBranch()).on(LocalDate.now()).mode(paymentType)
                .ref(m.getInvoiceNo(), "Collected against " + m.getInvoiceNo() + (viaRider ? " by " + m.getDeliveryManName() : ""))
                .party("CUSTOMER", m.getPidSupCust(), customer)
                .account("RECEIPT_ACCOUNT", receiptAccount).amount("AMOUNT", amount));
    }

    /** The delivery man hands the cash in (the Collection register). Only meaningful with cash in transit. */
    @Transactional
    public void deliveryCashIn(PosDeliveryCollection c) {
        if (!settings.is(c.getPidOrg(), AccSettings.USE_CASH_IN_TRANSIT)) return;
        engine.post(FinancialEvent.of(M_COLLECTION, "DELIVERY_COLLECTION", c.getPidCollection(), "POSTED", "DELIVERY_CASH_IN")
                .in(c.getPidOrg(), c.getPidBranch()).on(c.getCollectionDate() != null ? c.getCollectionDate() : LocalDate.now())
                .mode(c.getPaymentMode()).ref(c.getInvoiceNo(), "Cash handed in by " + c.getDeliveryManName() + " for " + c.getInvoiceNo())
                .party("EMPLOYEE", c.getPidDeliveryMan(), c.getDeliveryManName()).amount("AMOUNT", nvl(c.getCollectedAmount())));
    }

    /** A collection whose amount or mode was corrected: the earlier booking is reversed and it is booked again under a revision. */
    @Transactional
    public void deliveryCashInAmended(PosDeliveryCollection c) {
        if (!settings.is(c.getPidOrg(), AccSettings.USE_CASH_IN_TRANSIT)) return;
        engine.reverseSource(c.getPidOrg(), M_COLLECTION, "DELIVERY_COLLECTION", c.getPidCollection(), LocalDate.now(), "Collection " + c.getPidCollection() + " amended");
        String key = engine.nextRevisionKey(c.getPidOrg(), M_COLLECTION, "DELIVERY_COLLECTION", c.getPidCollection(), "POSTED");
        engine.post(FinancialEvent.of(M_COLLECTION, "DELIVERY_COLLECTION", c.getPidCollection(), key, "DELIVERY_CASH_IN")
                .in(c.getPidOrg(), c.getPidBranch()).on(c.getCollectionDate() != null ? c.getCollectionDate() : LocalDate.now())
                .mode(c.getPaymentMode()).ref(c.getInvoiceNo(), "Cash handed in by " + c.getDeliveryManName() + " for " + c.getInvoiceNo() + " (amended)")
                .party("EMPLOYEE", c.getPidDeliveryMan(), c.getDeliveryManName()).amount("AMOUNT", nvl(c.getCollectedAmount())));
    }

    @Transactional
    public void deliveryCashInCancelled(PosDeliveryCollection c, String reason) {
        engine.reverseSource(c.getPidOrg(), M_COLLECTION, "DELIVERY_COLLECTION", c.getPidCollection(), LocalDate.now(),
                reason == null || reason.isBlank() ? "Collection cancelled" : reason);
    }

    /** Goods back from a customer: refund net of invoice discount, stock back at the cost it left at. */
    @Transactional
    public void salesReturnCreated(PosTransactionMst ret) {
        postSalesReturn(ret, null);
    }

    private void postSalesReturn(PosTransactionMst ret, Long batch) {
        BigDecimal refund = nvl(ret.getAmountTobePaid());
        BigDecimal cost   = costOf(ret.getPidTranMst(), PosStockMovement.SALES_RETURN);
        String customer   = customerName(ret.getPidSupCust());
        boolean credit    = "CREDIT".equalsIgnoreCase(AccChartService.normaliseMode(ret.getPaymentType()));
        Long refundAccount = credit ? chart.byRole(ret.getPidOrg(), "AR").getPidAccount()
                : chart.byPaymentMode(ret.getPidOrg(), ret.getPaymentType()).orElseGet(() -> chart.byRole(ret.getPidOrg(), "CASH_REGISTER")).getPidAccount();
        FinancialEvent ev = FinancialEvent.of(M_SALES, "SALES_RETURN", ret.getPidTranMst(), "POSTED", "SALES_RETURN_POSTED")
                .in(ret.getPidOrg(), ret.getPidBranch()).on(ret.getTransactionDate()).mode(ret.getPaymentType())
                .ref(ret.getInvoiceNo(), "Sales return " + ret.getInvoiceNo() + " from " + customer)
                .party("CUSTOMER", ret.getPidSupCust(), customer)
                .account("REFUND_ACCOUNT", refundAccount).amount("REFUND", refund).amount("COGS", cost);
        if (batch != null) ev.historical(batch);
        engine.post(ev);
    }

    /* ══════════════════════════════════════════════════════════════════════
       PURCHASE
       ══════════════════════════════════════════════════════════════════════ */

    /** A purchase approved: stock at landed cost, supplier owed. The cash handed over is booked by the mirrored supplier payment. */
    @Transactional
    public void purchaseApproved(PosTransactionMst pu) { postPurchase(pu, null); }

    private void postPurchase(PosTransactionMst pu, Long batch) {
        BigDecimal landed = nvl(pu.getAmountTobePaid());
        String supplier = supplierName(pu.getPidSupCust());
        FinancialEvent ev = FinancialEvent.of(M_PURCHASE, "PURCHASE", pu.getPidTranMst(), "POSTED", "PURCHASE_APPROVED")
                .in(pu.getPidOrg(), pu.getPidBranch()).on(pu.getTransactionDate())
                .ref(pu.getInvoiceNo(), "Purchase " + pu.getInvoiceNo() + " from " + supplier)
                .party("SUPPLIER", pu.getPidSupCust(), supplier)
                .amount("LANDED_COST", landed).amount("PAYABLE", landed);
        if (batch != null) ev.historical(batch);
        engine.post(ev);
    }

    /** Goods back to the supplier. ADJUSTMENT reduces the payable; CASH / BANK is money back; REPLACEMENT becomes an advance. */
    @Transactional
    public void purchaseReturnCreated(PosTransactionMst pr) { postPurchaseReturn(pr, null); }

    private void postPurchaseReturn(PosTransactionMst pr, Long batch) {
        BigDecimal credit = nvl(pr.getAmountTobePaid());
        String supplier = supplierName(pr.getPidSupCust());
        String kind = AccChartService.normaliseMode(pr.getPaymentType());
        Long creditAccount = switch (kind) {
            case "CASH", "BANK", "BANKTRANSFER", "CARD", "CHEQUE", "BKASH", "NAGAD", "ROCKET" ->
                    chart.byPaymentMode(pr.getPidOrg(), pr.getPaymentType()).orElseGet(() -> chart.byRole(pr.getPidOrg(), "CASH_REGISTER")).getPidAccount();
            case "REPLACEMENT" -> chart.byRole(pr.getPidOrg(), "SUPPLIER_ADVANCE").getPidAccount();
            default -> chart.byRole(pr.getPidOrg(), "AP").getPidAccount();
        };
        FinancialEvent ev = FinancialEvent.of(M_PURCHASE, "PURCHASE_RETURN", pr.getPidTranMst(), "POSTED", "PURCHASE_RETURN_POSTED")
                .in(pr.getPidOrg(), pr.getPidBranch()).on(pr.getTransactionDate()).mode(pr.getPaymentType())
                .ref(pr.getInvoiceNo(), "Purchase return " + pr.getInvoiceNo() + " to " + supplier)
                .party("SUPPLIER", pr.getPidSupCust(), supplier)
                .account("CREDIT_ACCOUNT", creditAccount).amount("CREDIT", credit);
        if (batch != null) ev.historical(batch);
        engine.post(ev);
    }

    /* ══════════════════════════════════════════════════════════════════════
       SUPPLIER PAYMENT
       ══════════════════════════════════════════════════════════════════════ */

    @Transactional
    public void supplierPaymentRecorded(PosSupplierPayment p) { postSupplierPayment(p, null); }

    private void postSupplierPayment(PosSupplierPayment p, Long batch) {
        String supplier = supplierName(p.getPidSupplier());
        BigDecimal amount  = nvl(p.getAmount());
        BigDecimal advance = nvl(p.getAdvanceAmount()).min(amount).max(BigDecimal.ZERO);
        FinancialEvent ev;
        if (PosSupplierPayment.ADVANCE_RECOVERY.equals(p.getEntryType())) {
            // Money back, or the advance consumed against a bill.
            Long recovery = p.getPidTranMst() != null ? chart.byRole(p.getPidOrg(), "AP").getPidAccount()
                    : chart.byPaymentMode(p.getPidOrg(), p.getPaymentMode()).orElseGet(() -> chart.byRole(p.getPidOrg(), "CASH_REGISTER")).getPidAccount();
            ev = FinancialEvent.of(M_SUPPLIER_PAYMENT, "SUPPLIER_PAYMENT", p.getPidPayment(), "POSTED", "SUPPLIER_ADVANCE_RECOVERY")
                    .in(p.getPidOrg(), p.getPidBranch()).on(p.getPaymentDate()).mode(p.getPaymentMode())
                    .ref(p.getVoucherNo(), "Advance recovered from " + supplier).party("SUPPLIER", p.getPidSupplier(), supplier)
                    .account("RECOVERY_ACCOUNT", recovery).amount("AMOUNT", amount);
        } else {
            ev = FinancialEvent.of(M_SUPPLIER_PAYMENT, "SUPPLIER_PAYMENT", p.getPidPayment(), "POSTED", "SUPPLIER_PAYMENT")
                    .in(p.getPidOrg(), p.getPidBranch()).on(p.getPaymentDate()).mode(p.getPaymentMode())
                    .ref(p.getVoucherNo(), "Paid " + supplier + (p.getPidTranMst() != null ? " against a bill" : " on account"))
                    .party("SUPPLIER", p.getPidSupplier(), supplier)
                    .amount("SETTLING", amount.subtract(advance)).amount("ADVANCE", advance).amount("AMOUNT", amount);
        }
        if (batch != null) ev.historical(batch);
        engine.post(ev);
    }

    /**
     * Part of an invoice-time advance turns out to have settled the bill.
     *
     * <p>The mirrored payment posted the advance as recoverable; this moves
     * {@code delta} of it back to the payable. Booked as its own journal,
     * dated today, rather than by editing or reversing the original: what
     * left the till was right, only its split was wrong, and the trail should
     * show both readings. Keyed as a revision so a second restatement of the
     * same payment is booked, not mistaken for the first.
     */
    @Transactional
    public void supplierAdvanceRestated(PosSupplierPayment p, BigDecimal delta, String reason) {
        if (delta == null || delta.signum() <= 0) return;
        String supplier = supplierName(p.getPidSupplier());
        String key = engine.nextRevisionKey(p.getPidOrg(), M_SUPPLIER_PAYMENT, "SUPPLIER_PAYMENT", p.getPidPayment(), "RESTATED");
        engine.post(FinancialEvent.of(M_SUPPLIER_PAYMENT, "SUPPLIER_PAYMENT", p.getPidPayment(), key, "SUPPLIER_ADVANCE_RESTATED")
                .in(p.getPidOrg(), p.getPidBranch()).on(LocalDate.now())
                .ref(p.getVoucherNo(), "Advance on " + p.getVoucherNo() + " restated: " + reason)
                .party("SUPPLIER", p.getPidSupplier(), supplier)
                .amount("DELTA", delta));
    }

    @Transactional
    public void supplierPaymentCancelled(PosSupplierPayment p, String reason) {
        engine.reverseSource(p.getPidOrg(), M_SUPPLIER_PAYMENT, "SUPPLIER_PAYMENT", p.getPidPayment(), LocalDate.now(),
                reason == null || reason.isBlank() ? "Payment cancelled" : reason);
    }

    /* ══════════════════════════════════════════════════════════════════════
       HR
       ══════════════════════════════════════════════════════════════════════ */

    /** Payroll posted: one journal, with the net, advance and loan lines split by employee. */
    @Transactional
    public void payrollPosted(HrPayroll run) {
        List<HrPayrollLine> lines = payrollLineRepo.findByPayroll_PidPayrollOrderByEmpName(run.getPidPayroll());
        FinancialEvent ev = FinancialEvent.of(M_HR, "PAYROLL", run.getPidPayroll(), "POSTED", "PAYROLL_POSTED")
                .in(run.getPidOrg(), run.getPidBranch()).on(run.getPostedAt() != null ? run.getPostedAt().toLocalDate() : LocalDate.now())
                .ref(run.getPayrollNo(), "Payroll " + run.getPayrollNo());
        BigDecimal cost = BigDecimal.ZERO, pfEmployer = BigDecimal.ZERO, tax = BigDecimal.ZERO, pfTotal = BigDecimal.ZERO;
        for (HrPayrollLine l : lines) {
            cost = cost.add(nvl(l.getGrossSalary()).subtract(nvl(l.getAbsenceDeduction())));
            pfEmployer = pfEmployer.add(nvl(l.getPfEmployerAmount()));
            tax = tax.add(nvl(l.getTaxAmount()));
            pfTotal = pfTotal.add(nvl(l.getPfEmployeeAmount())).add(nvl(l.getPfEmployerAmount()));
            ev.split("NET", "EMPLOYEE", l.getPidEmployee(), l.getEmpName(), nvl(l.getNetSalary()));
            ev.split("ADVANCE_RECOVERY", "EMPLOYEE", l.getPidEmployee(), l.getEmpName(), nvl(l.getAdvanceRecovery()));
            ev.split("LOAN_RECOVERY", "EMPLOYEE", l.getPidEmployee(), l.getEmpName(), nvl(l.getLoanRecovery()));
        }
        BigDecimal credits = ev.amount("NET").add(ev.amount("ADVANCE_RECOVERY")).add(ev.amount("LOAN_RECOVERY")).add(tax).add(pfTotal);
        BigDecimal other = cost.add(pfEmployer).subtract(credits);   // the residual, visible rather than hidden
        ev.amount("SALARY_COST", cost).amount("PF_EMPLOYER", pfEmployer).amount("TAX", tax).amount("PF_TOTAL", pfTotal)
          .amount("OTHER_DEDUCTION", other.setScale(2, RoundingMode.HALF_UP));
        engine.post(ev);
    }

    @Transactional
    public void salaryPaid(HrSalaryPayment p, Long orgId) {
        String emp = employeeName(p.getPidEmployee());
        engine.post(FinancialEvent.of(M_HR, "SALARY_PAYMENT", p.getPidSalaryPayment(), "POSTED", "SALARY_PAID")
                .in(orgId, p.getPidBranch()).on(p.getPaymentDate()).mode(p.getPaymentMode())
                .ref(p.getVoucherNo(), "Salary paid to " + emp).party("EMPLOYEE", p.getPidEmployee(), emp)
                .amount("AMOUNT", nvl(p.getAmount())));
    }

    @Transactional
    public void salaryPaymentCancelled(HrSalaryPayment p, Long orgId, String reason) {
        engine.reverseSource(orgId, M_HR, "SALARY_PAYMENT", p.getPidSalaryPayment(), LocalDate.now(), reason == null || reason.isBlank() ? "Salary payment cancelled" : reason);
    }

    /** A claim approved. On the accrual basis the expense is booked now; on the cash basis it waits for payment. */
    @Transactional
    public void expenseClaimApproved(HrExpenseClaim c) {
        if (!"ACCRUAL".equalsIgnoreCase(settings.get(c.getPidOrg(), AccSettings.EXPENSE_CLAIM_BASIS))) return;
        postExpenseAccrual(c);
    }

    @Transactional
    public void expenseClaimPaid(HrExpenseClaim c) {
        if (!"ACCRUAL".equalsIgnoreCase(settings.get(c.getPidOrg(), AccSettings.EXPENSE_CLAIM_BASIS))) postExpenseAccrual(c);
        String emp = employeeName(c.getPidEmployee());
        engine.post(FinancialEvent.of(M_HR, "EXPENSE_CLAIM", c.getPidExpenseClaim(), "PAID", "EXPENSE_CLAIM_PAID")
                .in(c.getPidOrg(), c.getPidBranch()).on(c.getPaidDate() != null ? c.getPaidDate() : LocalDate.now()).mode(c.getPaymentMode())
                .ref(c.getClaimNo(), "Expense claim " + c.getClaimNo() + " paid to " + emp).party("EMPLOYEE", c.getPidEmployee(), emp)
                .amount("AMOUNT", nvl(c.getApprovedAmount())));
    }

    private void postExpenseAccrual(HrExpenseClaim c) {
        String emp = employeeName(c.getPidEmployee());
        Long expenseAccount = null;
        for (HrExpenseClaimLine l : c.getLines()) {
            if (l.getPidExpenseType() == null) continue;
            Long acc = expenseTypeRepo.findById(l.getPidExpenseType()).map(HrExpenseType::getPidAccount).orElse(null);
            if (acc != null) { expenseAccount = acc; break; }
        }
        if (expenseAccount == null) expenseAccount = chart.byRole(c.getPidOrg(), "STAFF_EXPENSE").getPidAccount();
        engine.post(FinancialEvent.of(M_HR, "EXPENSE_CLAIM", c.getPidExpenseClaim(), "APPROVED", "EXPENSE_CLAIM_APPROVED")
                .in(c.getPidOrg(), c.getPidBranch()).on(c.getClaimDate()).ref(c.getClaimNo(), "Expense claim " + c.getClaimNo() + " of " + emp)
                .party("EMPLOYEE", c.getPidEmployee(), emp).account("EXPENSE_ACCOUNT", expenseAccount)
                .amount("AMOUNT", nvl(c.getApprovedAmount())));
    }

    @Transactional
    public void advancePaid(HrAdvance a) {
        String emp = employeeName(a.getPidEmployee());
        engine.post(FinancialEvent.of(M_HR, "ADVANCE", a.getPidAdvance(), "PAID", "ADVANCE_PAID")
                .in(a.getPidOrg(), a.getPidBranch()).on(a.getPaidDate() != null ? a.getPaidDate() : LocalDate.now()).mode(a.getPaymentMode())
                .ref(a.getAdvanceNo(), "Advance " + a.getAdvanceNo() + " paid to " + emp).party("EMPLOYEE", a.getPidEmployee(), emp)
                .amount("AMOUNT", nvl(a.getApprovedAmount())));
    }

    @Transactional
    public void loanDisbursed(HrLoan l) {
        String emp = employeeName(l.getPidEmployee());
        engine.post(FinancialEvent.of(M_HR, "LOAN", l.getPidLoan(), "DISBURSED", "LOAN_DISBURSED")
                .in(l.getPidOrg(), l.getPidBranch()).on(l.getDisbursedDate() != null ? l.getDisbursedDate() : LocalDate.now()).mode(l.getPaymentMode())
                .ref(l.getLoanNo(), "Loan " + l.getLoanNo() + " disbursed to " + emp).party("EMPLOYEE", l.getPidEmployee(), emp)
                .amount("AMOUNT", nvl(l.getApprovedPrincipal())));
    }

    @Transactional
    public void gratuityPaid(HrGratuityAward g) {
        String emp = employeeName(g.getPidEmployee());
        engine.post(FinancialEvent.of(M_HR, "GRATUITY", g.getPidGratuityAward(), "PAID", "GRATUITY_PAID")
                .in(g.getPidOrg(), g.getPidBranch()).on(g.getPaidDate() != null ? g.getPaidDate() : LocalDate.now()).mode(g.getPaymentMode())
                .ref(g.getAwardNo(), "Gratuity " + g.getAwardNo() + " paid to " + emp).party("EMPLOYEE", g.getPidEmployee(), emp)
                .amount("AMOUNT", nvl(g.getGratuityAmount())));
    }

    /* ══════════════════════════════════════════════════════════════════════
       SUBSCRIPTION — the platform organisation's books
       ══════════════════════════════════════════════════════════════════════ */

    @Transactional
    public void subscriptionInvoiced(SubInvoice inv) {
        Long org = branchScope.platformOrgId();
        Long branch = platformBranch(org);
        if (branch == null) return;
        engine.post(FinancialEvent.of(M_SUBSCRIPTION, "SUB_INVOICE", inv.getPidInvoice(), "POSTED", "SUBSCRIPTION_INVOICED")
                .in(org, branch).on(inv.getIssuedOn() != null ? inv.getIssuedOn() : LocalDate.now())
                .ref(inv.getInvoiceNo(), "Subscription invoice " + inv.getInvoiceNo() + " to " + branchName(inv.getPidBranch()))
                .party("TENANT", inv.getPidBranch(), branchName(inv.getPidBranch())).amount("AMOUNT", nvl(inv.getAmount())));
    }

    @Transactional
    public void subscriptionPaid(SubPayment p, SubInvoice inv) {
        Long org = branchScope.platformOrgId();
        Long branch = platformBranch(org);
        if (branch == null) return;
        engine.post(FinancialEvent.of(M_SUBSCRIPTION, "SUB_PAYMENT", p.getPidPayment(), "POSTED", "SUBSCRIPTION_PAID")
                .in(org, branch).on(p.getPaidOn() != null ? p.getPaidOn() : LocalDate.now()).mode(p.getMethod())
                .ref(inv == null ? null : inv.getInvoiceNo(), "Subscription paid by " + branchName(p.getPidBranch()))
                .party("TENANT", p.getPidBranch(), branchName(p.getPidBranch())).amount("AMOUNT", nvl(p.getAmount())));
    }

    @Transactional
    public void subscriptionVoided(SubInvoice inv, String reason) {
        engine.reverseSource(branchScope.platformOrgId(), M_SUBSCRIPTION, "SUB_INVOICE", inv.getPidInvoice(), LocalDate.now(),
                reason == null || reason.isBlank() ? "Invoice voided" : reason);
    }

    /* ══════════════════════════════════════════════════════════════════════
       BACKFILL — the same postings, dated historically
       ══════════════════════════════════════════════════════════════════════ */

    public void backfillSale(PosTransactionMst m, Long batch)            { postSale(m, "POSTED", "RECEIPT", batch); }
    public void backfillSalesReturn(PosTransactionMst r, Long batch)     { postSalesReturn(r, batch); }
    public void backfillPurchase(PosTransactionMst p, Long batch)        { postPurchase(p, batch); }
    public void backfillPurchaseReturn(PosTransactionMst p, Long batch)  { postPurchaseReturn(p, batch); }
    public void backfillSupplierPayment(PosSupplierPayment p, Long batch){ postSupplierPayment(p, batch); }

    /* ── helpers ────────────────────────────────────────────────────────── */

    /** Σ qty × cost over the stock movements a document posted — the snapshot cost the profit reports already use. */
    private BigDecimal costOf(Long mstId, String movementType) {
        BigDecimal total = BigDecimal.ZERO;
        for (PosStockMovement mv : movementRepo.findByRefTypeAndRefMstAndMovementType(REF_TYPE, mstId, movementType)) {
            if (mv.getCostPerPcs() == null || mv.getQtyPcs() == null) continue;
            total = total.add(mv.getQtyPcs().abs().multiply(mv.getCostPerPcs()));
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    private String customerName(Long id) { return id == null ? "walk-in" : customerRepo.findById(id).map(PosCustomerInfo::getCustomerName).orElse("customer " + id); }
    private String supplierName(Long id) { return id == null ? "supplier" : supplierRepo.findById(id).map(PosSupplierInfo::getSupplierName).orElse("supplier " + id); }
    private String employeeName(Long id) { return id == null ? "employee" : employeeRepo.findById(id).map(HrEmployee::getEmpName).orElse("employee " + id); }
    private String branchName(Long id)   { return id == null ? "" : branchRepo.findById(id).map(AllBranch::getBranchName).orElse("branch " + id); }

    private Long platformBranch(Long platformOrg) {
        return branchRepo.findByPidOrgAndStatusOrderByBranchName(platformOrg, "Y").stream().findFirst().map(AllBranch::getPidBranch).orElse(null);
    }

    private static BigDecimal nvl(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
