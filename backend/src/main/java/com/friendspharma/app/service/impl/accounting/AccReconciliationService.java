package com.friendspharma.app.service.impl.accounting;

import com.friendspharma.app.entity.*;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.repository.*;
import com.friendspharma.app.service.impl.PermissionGuard;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * Business records against the ledger, and the controlled way to bring
 * history into it.
 *
 * <p><b>Reconciliation</b> compares what the operational tables say with
 * what the ledger says — sales, purchases, payments, receivables, payables,
 * inventory, cash — and lists every business document that has no posted
 * journal. <b>Backfill</b> posts those documents, one transaction each,
 * dated when they happened, under a recorded batch, and never twice (the
 * event identity refuses a duplicate).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccReconciliationService {

    private final AccScope                    scope;
    private final AccEventRepository          eventRepo;
    private final AccBackfillBatchRepository  batchRepo;
    private final AccLedgerService            ledger;
    private final AccChartService             chart;
    private final AccountingHooks             hooks;
    private final PosTransactionMstRepository mstRepo;
    private final PosSupplierPaymentRepository payRepo;
    private final AccDailyCashService         dailyCash;
    private final AccBackfillSteps self;
    @PersistenceContext private EntityManager em;

    /* ── event monitor ──────────────────────────────────────────────────── */

    @Transactional(readOnly = true)
    public Page<AccEvent> monitor(Long branchId, String status, String module, LocalDate from, LocalDate to, int page, int size) {
        scope.require(AccScope.MOD_RECONCILE, PermissionGuard.Right.VIEW, "see the accounting event monitor");
        LocalDate f = from != null ? from : LocalDate.now().minusDays(30), t = to != null ? to : LocalDate.now();
        return eventRepo.monitor(scope.orgId(), scope.branchFilter(branchId), blank(status), blank(module), f, t, PageRequest.of(page, Math.min(size, 200)));
    }

    /** Re-runs a FAILED or SKIPPED event from its source document. */
    @Transactional
    public AccEvent retry(Long eventId) {
        scope.require(AccScope.MOD_RECONCILE, PermissionGuard.Right.EDIT, "retry an accounting event");
        AccEvent e = eventRepo.findById(eventId).filter(x -> x.getPidOrg().equals(scope.orgId()))
                .orElseThrow(() -> new BusinessException("Event not found.", HttpStatus.NOT_FOUND, "ACC_EVENT_NOT_FOUND"));
        if ("POSTED".equals(e.getEventStatus())) return e;
        repost(e.getSourceModule(), e.getSourceType(), e.getSourceId(), null);
        return eventRepo.findById(eventId).orElse(e);
    }

    private void repost(String module, String type, Long sourceId, Long batch) {
        switch (module + "/" + type) {
            case "SALES/SALE" -> mstRepo.findById(sourceId).ifPresent(m -> hooks.backfillSale(m, batch));
            case "SALES/SALES_RETURN" -> mstRepo.findById(sourceId).ifPresent(m -> hooks.backfillSalesReturn(m, batch));
            case "PURCHASE/PURCHASE" -> mstRepo.findById(sourceId).ifPresent(m -> hooks.backfillPurchase(m, batch));
            case "PURCHASE/PURCHASE_RETURN" -> mstRepo.findById(sourceId).ifPresent(m -> hooks.backfillPurchaseReturn(m, batch));
            case "SUPPLIER_PAYMENT/SUPPLIER_PAYMENT" -> payRepo.findById(sourceId).ifPresent(p -> hooks.backfillSupplierPayment(p, batch));
            default -> throw new BusinessException("Events from " + module + " are re-posted from their own screen.", HttpStatus.CONFLICT, "ACC_RETRY_UNSUPPORTED");
        }
    }

    /* ── reconciliation centre ──────────────────────────────────────────── */

    /** The backfill batches of the organisation, newest first — what the Backfill tab lists, without running the checks. */
    @Transactional(readOnly = true)
    public List<AccBackfillBatch> batches() {
        scope.require(AccScope.MOD_RECONCILE, PermissionGuard.Right.VIEW, "see the backfill history");
        return batchRepo.findByPidOrgOrderByPidBackfillBatchDesc(scope.orgId());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> reconcile(Long branchId, LocalDate from, LocalDate to) {
        scope.require(AccScope.MOD_RECONCILE, PermissionGuard.Right.VIEW, "run the reconciliation");
        Long org = scope.orgId();
        // Custom plans and room to sort: the same reasons as AccLedgerService.analyticSession.
        em.createNativeQuery("SET LOCAL plan_cache_mode = force_custom_plan").executeUpdate();
        em.createNativeQuery("SET LOCAL work_mem = '64MB'").executeUpdate();
        Long branch = scope.branchFilter(branchId);
        LocalDate f = from != null ? from : LocalDate.of(2000, 1, 1), t = to != null ? to : LocalDate.now();
        String b = branch == null ? "" : " AND m.pid_branch = :branch";
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> checks = new ArrayList<>();

        // Documents versus events, per module: count and money. The ledger side
        // of the four is one pass over the window, grouped by source and role,
        // instead of a scan per check.
        Map<String, BigDecimal[]> sums = ledgerSums(org, branch, f, t);
        checks.add(docCheck(sums, org, branch, f, t, "Sales invoices", "SL", "'Y'", "SALES", "SALE", "SALES_REVENUE", "amount_tobe_paid - coalesce(delivery_charge,0)", "AND coalesce(m.is_merge_invoice,'N') <> 'Y'"));
        checks.add(docCheck(sums, org, branch, f, t, "Sales returns", "SR", "'Y'", "SALES", "SALES_RETURN", "SALES_RETURNS", "amount_tobe_paid", ""));
        checks.add(docCheck(sums, org, branch, f, t, "Purchases (approved)", "PU", "'A'", "PURCHASE", "PURCHASE", "AP", "amount_tobe_paid", ""));
        checks.add(docCheck(sums, org, branch, f, t, "Purchase returns", "PR", "'A'", "PURCHASE", "PURCHASE_RETURN", "INVENTORY", "amount_tobe_paid", ""));
        checks.add(paymentCheck(org, branch, f, t));

        // Balances versus the ledger.
        Map<String, BigDecimal> bal = ledger.balancesByRole(org, branch, t);
        BigDecimal custDue = money(em.createNativeQuery("SELECT coalesce(sum(m.amount_tobe_paid - coalesce(m.given_amount,0)),0) FROM pharma.pos_transaction_mst m "
                + "WHERE m.pid_org = :org AND m.transaction_type = 'SL' AND m.status = 'Y' AND coalesce(m.is_merge_invoice,'N') <> 'Y' AND m.transaction_date <= :to" + b)
                .setParameter("org", org).setParameter("to", t), branch);
        checks.add(balanceCheck("Customer dues", "Σ (payable − given) on sales invoices", custDue, "Ledger receivables (AR)", bal.getOrDefault("AR", BigDecimal.ZERO), "Sales returns credited to the customer, and collections recorded on the delivery register, move the ledger without moving the invoice's given amount."));
        BigDecimal supDue = money(em.createNativeQuery("SELECT coalesce(sum(v.outstanding),0) FROM pharma.v_supplier_account_branch v WHERE v.pid_org = :org" + (branch == null ? "" : " AND v.pid_branch = :branch"))
                .setParameter("org", org), branch);
        checks.add(balanceCheck("Supplier outstanding", "Supplier account view", supDue, "Ledger payables (AP) less supplier advances", bal.getOrDefault("AP", BigDecimal.ZERO).subtract(bal.getOrDefault("SUPPLIER_ADVANCE", BigDecimal.ZERO)), "Return credits of type CASH/BANK/REPLACEMENT and cancelled payments are treated differently by the view (BR-028)."));
        BigDecimal stockValue = money(em.createNativeQuery("SELECT coalesce(sum(s.qty_pcs * coalesce(s.cost_per_pcs,0)),0) FROM pharma.pos_stock_batch s WHERE s.pid_org = :org" + (branch == null ? "" : " AND s.pid_branch = :branch"))
                .setParameter("org", org), branch);
        checks.add(balanceCheck("Inventory", "Σ batch quantity × cost", stockValue, "Ledger inventory", bal.getOrDefault("INVENTORY", BigDecimal.ZERO), "The ledger values receipts at landed cost and issues at movement cost; batches received before the books started are not in the ledger until backfilled."));
        out.put("checks", checks);

        // Integrity: the things that must be zero. Read over the window like
        // the document checks, not over the whole ledger: summing every line of
        // every journal the organisation has ever posted took four seconds, and
        // a month is what the screen asks about. Widen the range for a full audit.
        Map<String, Object> integrity = new LinkedHashMap<>();
        integrity.put("window", f + " to " + t);
        integrity.put("unbalancedJournals", ((Number) em.createNativeQuery("SELECT count(*) FROM pharma.acc_journal j WHERE j.pid_org = :org AND j.journal_status <> 'DRAFT' AND j.journal_date BETWEEN :from AND :to AND j.total_debit <> j.total_credit")
                .setParameter("org", org).setParameter("from", f).setParameter("to", t).getSingleResult()).longValue());
        // Lines summed once per journal and compared with the journal's own totals; a journal
        // whose lines are gone altogether counts too.
        integrity.put("journalsWithLineMismatch", ((Number) em.createNativeQuery("SELECT (SELECT count(*) FROM ("
                + "  SELECT j.pid_journal, j.total_debit, j.total_credit, sum(l.debit) AS d, sum(l.credit) AS c FROM pharma.acc_journal j "
                + "  JOIN pharma.acc_journal_line l ON l.pid_journal = j.pid_journal "
                + "  WHERE j.pid_org = :org AND j.journal_status <> 'DRAFT' AND j.journal_date BETWEEN :from AND :to GROUP BY 1, 2, 3) x "
                + "  WHERE x.total_debit <> x.d OR x.total_credit <> x.c) "
                + "+ (SELECT count(*) FROM pharma.acc_journal j WHERE j.pid_org = :org AND j.journal_status <> 'DRAFT' AND j.journal_date BETWEEN :from AND :to "
                + "   AND (j.total_debit <> 0 OR j.total_credit <> 0) AND NOT EXISTS (SELECT 1 FROM pharma.acc_journal_line l WHERE l.pid_journal = j.pid_journal))")
                .setParameter("org", org).setParameter("from", f).setParameter("to", t).getSingleResult()).longValue());
        integrity.put("failedEvents", eventRepo.countByPidOrgAndEventStatus(org, "FAILED"));
        integrity.put("skippedEvents", eventRepo.countByPidOrgAndEventStatus(org, "SKIPPED"));
        integrity.put("postedEventsWithoutJournal", ((Number) em.createNativeQuery("SELECT count(*) FROM pharma.acc_event e WHERE e.pid_org = :org AND e.event_status = 'POSTED' AND e.event_date BETWEEN :from AND :to AND NOT EXISTS (SELECT 1 FROM pharma.acc_journal j WHERE j.pid_journal = e.pid_journal AND j.journal_status IN ('POSTED','REVERSED'))").setParameter("org", org).setParameter("from", f).setParameter("to", t).getSingleResult()).longValue());
        integrity.put("linesOnWrongOrg", ((Number) em.createNativeQuery("SELECT count(*) FROM pharma.acc_journal_line l JOIN pharma.acc_journal j ON j.pid_journal = l.pid_journal WHERE j.pid_org = :org AND j.journal_date BETWEEN :from AND :to AND l.pid_org <> j.pid_org").setParameter("org", org).setParameter("from", f).setParameter("to", t).getSingleResult()).longValue());
        integrity.put("linesOnGroupAccounts", ((Number) em.createNativeQuery("SELECT count(*) FROM pharma.acc_journal_line l JOIN pharma.acc_account a ON a.pid_account = l.pid_account WHERE a.pid_org = :org AND a.is_group = 1 AND l.journal_date BETWEEN :from AND :to").setParameter("org", org).setParameter("from", f).setParameter("to", t).getSingleResult()).longValue());
        integrity.put("journalsInLockedPeriods", ((Number) em.createNativeQuery("SELECT count(*) FROM pharma.acc_journal j JOIN pharma.acc_period p ON p.pid_period = j.pid_period WHERE j.pid_org = :org AND p.period_status = 'LOCKED' AND j.posted_at > p.locked_at").setParameter("org", org).getSingleResult()).longValue());
        out.put("integrity", integrity);
        out.put("cash", scope.has(AccScope.MOD_DAILY_CASH, PermissionGuard.Right.VIEW) ? dailyCash.unclosed() : List.of());
        out.put("batches", batchRepo.findByPidOrgOrderByPidBackfillBatchDesc(org));
        return out;
    }

    private Map<String, Object> docCheck(Map<String, BigDecimal[]> sums, Long org, Long branch, LocalDate f, LocalDate t, String label, String type, String status, String module, String srcType, String role, String amountExpr, String extra) {
        String b = branch == null ? "" : " AND m.pid_branch = :branch";
        Object[] doc = (Object[]) bind(em.createNativeQuery("SELECT count(*), coalesce(sum(" + amountExpr + "),0) FROM pharma.pos_transaction_mst m WHERE m.pid_org = :org AND m.transaction_type = '" + type + "' AND m.status = " + status + " AND m.transaction_date BETWEEN :from AND :to" + b + " " + extra), org, branch, f, t).getSingleResult();
        // Documents in the window that have an event: the same filter as the
        // documents and the missing count, so docs = events + missing reads
        // true. Starting from the documents rather than the events keeps the
        // date range on the driving side.
        Number ev = (Number) bind(em.createNativeQuery("SELECT count(*) FROM pharma.pos_transaction_mst m WHERE m.pid_org = :org AND m.transaction_type = '" + type + "' AND m.status = " + status + " AND m.transaction_date BETWEEN :from AND :to" + b + " " + extra
                + " AND EXISTS (SELECT 1 FROM pharma.acc_event e WHERE e.source_module = '" + module + "' AND e.source_type = '" + srcType + "' AND e.source_id = m.pid_tran_mst AND e.event_status IN ('POSTED','SKIPPED'))"), org, branch, f, t).getSingleResult();
        Number missing = (Number) bind(em.createNativeQuery("SELECT count(*) FROM pharma.pos_transaction_mst m WHERE m.pid_org = :org AND m.transaction_type = '" + type + "' AND m.status = " + status + " AND m.transaction_date BETWEEN :from AND :to" + b + " " + extra
                + " AND NOT EXISTS (SELECT 1 FROM pharma.acc_event e WHERE e.source_module = '" + module + "' AND e.source_type = '" + srcType + "' AND e.source_id = m.pid_tran_mst AND e.event_status IN ('POSTED','SKIPPED'))"), org, branch, f, t).getSingleResult();
        BigDecimal ledgerAmt = ledgerAmount(sums, module, srcType, role);
        return check(label, ((Number) doc[0]).longValue(), (BigDecimal) doc[1], ev.longValue(), ledgerAmt, missing.longValue());
    }

    private Map<String, Object> paymentCheck(Long org, Long branch, LocalDate f, LocalDate t) {
        String b = branch == null ? "" : " AND p.pid_branch = :branch";
        Object[] doc = (Object[]) bind(em.createNativeQuery("SELECT count(*), coalesce(sum(p.amount),0) FROM pharma.pos_supplier_payment p WHERE p.pid_org = :org AND p.status = 'Y' AND p.entry_type = 'PAYMENT' AND p.payment_date BETWEEN :from AND :to" + b), org, branch, f, t).getSingleResult();
        Number ev = (Number) bind(em.createNativeQuery("SELECT count(*) FROM pharma.pos_supplier_payment p WHERE p.pid_org = :org AND p.status = 'Y' AND p.entry_type = 'PAYMENT' AND p.payment_date BETWEEN :from AND :to" + b
                + " AND EXISTS (SELECT 1 FROM pharma.acc_event e WHERE e.source_module = 'SUPPLIER_PAYMENT' AND e.source_id = p.pid_payment AND e.event_status IN ('POSTED','SKIPPED'))"), org, branch, f, t).getSingleResult();
        Number missing = (Number) bind(em.createNativeQuery("SELECT count(*) FROM pharma.pos_supplier_payment p WHERE p.pid_org = :org AND p.status = 'Y' AND p.payment_date BETWEEN :from AND :to" + b
                + " AND NOT EXISTS (SELECT 1 FROM pharma.acc_event e WHERE e.source_module = 'SUPPLIER_PAYMENT' AND e.source_id = p.pid_payment AND e.event_status IN ('POSTED','SKIPPED'))"), org, branch, f, t).getSingleResult();
        // Payments and advance recoveries share a module; only the payments are the documents counted above, so the
        // ledger side is the posted SUPPLIER_PAYMENT events (one journal each; a cancelled payment's event is REVERSED).
        String eb = branch == null ? "" : " AND e.pid_branch = :branch";
        BigDecimal ledgerAmt = money(bind(em.createNativeQuery("SELECT coalesce(sum(e.amount),0) FROM pharma.acc_event e WHERE e.pid_org = :org AND e.source_module = 'SUPPLIER_PAYMENT' AND e.event_type = 'SUPPLIER_PAYMENT' AND e.event_status = 'POSTED' AND e.event_date BETWEEN :from AND :to" + eb), org, branch, f, t), branch);
        return check("Supplier payments", ((Number) doc[0]).longValue(), (BigDecimal) doc[1], ev.longValue(), ledgerAmt, missing.longValue());
    }

    /** Debit and credit per (source module, source type, role) over the window — read once per reconciliation. */
    private Map<String, BigDecimal[]> ledgerSums(Long org, Long branch, LocalDate f, LocalDate t) {
        String b = branch == null ? "" : " AND l.pid_branch = :branch";
        @SuppressWarnings("unchecked")
        List<Object[]> rows = bind(em.createNativeQuery("SELECT l.source_module, l.source_type, l.role_key, coalesce(sum(l.debit),0), coalesce(sum(l.credit),0) "
                + "FROM pharma.acc_v_ledger l WHERE l.pid_org = :org AND l.journal_date BETWEEN :from AND :to AND l.role_key IS NOT NULL" + b
                + " GROUP BY l.source_module, l.source_type, l.role_key"), org, branch, f, t).getResultList();
        Map<String, BigDecimal[]> m = new HashMap<>();
        for (Object[] r : rows) m.put(r[0] + "/" + r[1] + "/" + r[2], new BigDecimal[]{ (BigDecimal) r[3], (BigDecimal) r[4] });
        return m;
    }

    /** Net posted amount for a module/type on one role's account, reversals cancelling. */
    private BigDecimal ledgerAmount(Map<String, BigDecimal[]> sums, String module, String srcType, String role) {
        BigDecimal d = BigDecimal.ZERO, c = BigDecimal.ZERO;
        if (role == null) {
            // The cash-side roles, credits only: a line is either a debit or a credit, so the credit rows carry no debit.
            for (String k : List.of("CASH_REGISTER", "BANK_DEFAULT", "MOBILE_WALLET", "SUPPLIER_ADVANCE")) {
                BigDecimal[] v = sums.get(module + "/" + srcType + "/" + k);
                if (v != null) c = c.add(v[1]);
            }
        } else {
            BigDecimal[] v = sums.get(module + "/" + srcType + "/" + role);
            if (v != null) { d = v[0]; c = v[1]; }
        }
        if (role == null) return c.subtract(d);
        // Each check reads the side the document naturally sits on: revenue and payables are credits, a purchase return credits inventory; the rest are debits.
        return switch (role) { case "SALES_REVENUE", "AP" -> c.subtract(d); case "INVENTORY" -> "PURCHASE_RETURN".equals(srcType) ? c.subtract(d) : d.subtract(c); default -> d.subtract(c); };
    }

    private static Map<String, Object> check(String label, long docs, BigDecimal docAmount, long events, BigDecimal ledgerAmount, long missing) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("check", label); m.put("documents", docs); m.put("documentAmount", docAmount); m.put("postedEvents", events); m.put("ledgerAmount", ledgerAmount);
        BigDecimal difference = docAmount.subtract(ledgerAmount);
        // Source amounts carry four decimals; each document is posted rounded HALF_UP to two, so a
        // difference of up to half a paisa per document is rounding, not a missing or wrong posting.
        BigDecimal tolerance = new BigDecimal("0.005").multiply(BigDecimal.valueOf(Math.max(docs, 1))).setScale(2, RoundingMode.HALF_UP);
        m.put("missingJournals", missing); m.put("difference", difference); m.put("roundingTolerance", tolerance);
        m.put("status", missing > 0 ? "MISSING_JOURNALS" : difference.abs().compareTo(tolerance) <= 0 ? "MATCHED" : "AMOUNT_DIFFERS");
        return m;
    }

    private static Map<String, Object> balanceCheck(String label, String businessLabel, BigDecimal business, String ledgerLabel, BigDecimal ledgerValue, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("check", label); m.put("businessLabel", businessLabel); m.put("documentAmount", business); m.put("ledgerLabel", ledgerLabel); m.put("ledgerAmount", ledgerValue);
        m.put("difference", business.subtract(ledgerValue)); m.put("status", business.compareTo(ledgerValue) == 0 ? "MATCHED" : "AMOUNT_DIFFERS"); m.put("note", note);
        return m;
    }

    /* ── backfill ───────────────────────────────────────────────────────── */

    /** Posts every historical document without a journal, one transaction each, under a recorded batch. */
    public AccBackfillBatch backfill(Long orgOverride, Long branchId, String module, LocalDate from, LocalDate to, boolean dryRun, int limit) {
        scope.require(AccScope.MOD_RECONCILE, PermissionGuard.Right.APPROVE, "run a historical backfill");
        // A platform operator may bring another pharmacy's history in; everyone else works in their own books.
        Long org = orgOverride != null && scope.isPlatformOperator() ? orgOverride : scope.orgId();
        Long branch = orgOverride != null && scope.isPlatformOperator() ? branchId : scope.branchFilter(branchId);
        chart.ensureProvisioned(org);
        AccBackfillBatch batch = self.startBatch(org, branch, module, from, to, dryRun);
        int scanned = 0, posted = 0, skipped = 0, failed = 0;
        StringBuilder summary = new StringBuilder();
        try {
            List<Object[]> work = pending(org, branch, module, from, to, limit);
            for (Object[] w : work) {
                scanned++;
                String mod = (String) w[0], type = (String) w[1]; Long id = ((Number) w[2]).longValue();
                if (dryRun) { skipped++; continue; }
                try {
                    self.postOne(mod, type, id, batch.getPidBackfillBatch());
                    posted++;
                } catch (RuntimeException e) {
                    failed++;
                    if (summary.length() < 4000) summary.append(mod).append('/').append(type).append('#').append(id).append(": ").append(e.getMessage()).append('\n');
                }
            }
            return self.finishBatch(batch.getPidBackfillBatch(), scanned, posted, skipped, failed, (dryRun ? "Dry run: " + scanned + " document(s) would be posted.\n" : "") + summary, "DONE");
        } catch (RuntimeException e) {
            log.error("Backfill batch {} failed", batch.getPidBackfillBatch(), e);
            return self.finishBatch(batch.getPidBackfillBatch(), scanned, posted, skipped, failed, summary + "\n" + e.getMessage(), "FAILED");
        }
    }

    @Transactional(readOnly = true)
    public List<Object[]> pending(Long org, Long branch, String module, LocalDate from, LocalDate to, int limit) {
        String b = branch == null ? "" : " AND m.pid_branch = :branch";
        String bp = branch == null ? "" : " AND p.pid_branch = :branch";
        LocalDate f = from != null ? from : LocalDate.of(2000, 1, 1), t = to != null ? to : LocalDate.now();
        String mod = blank(module);
        List<Object[]> out = new ArrayList<>();
        if (mod == null || "SALES".equals(mod) || "PURCHASE".equals(mod)) {
            String types = mod == null ? "'SL','SR','PU','PR'" : "SALES".equals(mod) ? "'SL','SR'" : "'PU','PR'";
            @SuppressWarnings("unchecked")
            List<Object[]> rows = bind(em.createNativeQuery(
                    "SELECT CASE WHEN m.transaction_type IN ('SL','SR') THEN 'SALES' ELSE 'PURCHASE' END, "
                  + "       CASE m.transaction_type WHEN 'SL' THEN 'SALE' WHEN 'SR' THEN 'SALES_RETURN' WHEN 'PU' THEN 'PURCHASE' ELSE 'PURCHASE_RETURN' END, "
                  + "       m.pid_tran_mst, m.transaction_date FROM pharma.pos_transaction_mst m "
                  + "WHERE m.pid_org = :org AND m.transaction_type IN (" + types + ") AND ((m.transaction_type IN ('SL','SR') AND m.status = 'Y') OR (m.transaction_type IN ('PU','PR') AND m.status = 'A')) "
                  + "  AND coalesce(m.is_merge_invoice,'N') <> 'Y' AND m.transaction_date BETWEEN :from AND :to" + b
                  + "  AND NOT EXISTS (SELECT 1 FROM pharma.acc_event e WHERE e.source_id = m.pid_tran_mst AND e.event_status IN ('POSTED','SKIPPED') "
                  + "        AND e.source_module = CASE WHEN m.transaction_type IN ('SL','SR') THEN 'SALES' ELSE 'PURCHASE' END "
                  + "        AND e.source_type = CASE m.transaction_type WHEN 'SL' THEN 'SALE' WHEN 'SR' THEN 'SALES_RETURN' WHEN 'PU' THEN 'PURCHASE' ELSE 'PURCHASE_RETURN' END) "
                  + "ORDER BY m.transaction_date, m.pid_tran_mst"), org, branch, f, t).setMaxResults(limit).getResultList();
            out.addAll(rows);
        }
        if (mod == null || "SUPPLIER_PAYMENT".equals(mod)) {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = bind(em.createNativeQuery(
                    "SELECT 'SUPPLIER_PAYMENT', 'SUPPLIER_PAYMENT', p.pid_payment, p.payment_date FROM pharma.pos_supplier_payment p "
                  + "WHERE p.pid_org = :org AND p.status = 'Y' AND p.payment_date BETWEEN :from AND :to" + bp
                  + "  AND NOT EXISTS (SELECT 1 FROM pharma.acc_event e WHERE e.source_module = 'SUPPLIER_PAYMENT' AND e.source_id = p.pid_payment AND e.event_status IN ('POSTED','SKIPPED')) "
                  + "ORDER BY p.payment_date, p.pid_payment"), org, branch, f, t).setMaxResults(limit).getResultList();
            out.addAll(rows);
        }
        // Purchases before their payments, sales before their returns: date order across both lists.
        out.sort(Comparator.comparing((Object[] r) -> ((java.sql.Date) r[3]).toLocalDate()).thenComparing(r -> ((Number) r[2]).longValue()));
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    /* ── helpers ────────────────────────────────────────────────────────── */

    private static jakarta.persistence.Query bind(jakarta.persistence.Query q, Long org, Long branch, LocalDate from, LocalDate to) {
        q.setParameter("org", org).setParameter("from", from).setParameter("to", to);
        if (branch != null) q.setParameter("branch", branch);
        return q;
    }
    private static BigDecimal money(jakarta.persistence.Query q, Long branch) {
        if (branch != null) q.setParameter("branch", branch);
        Object o = q.getSingleResult();
        return o == null ? BigDecimal.ZERO : new BigDecimal(o.toString()).setScale(2, java.math.RoundingMode.HALF_UP);
    }
    private static String blank(String s) { return s == null || s.isBlank() ? null : s; }
}
