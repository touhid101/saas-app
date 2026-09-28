package com.friendspharma.app.service.impl.accounting;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * Reading the ledger. Every figure Accounts shows — dashboard, ledger, trial
 * balance, statements, ageing, profit, balance sheet, cash books — comes
 * through here, from {@code acc_v_ledger} and nothing else. No report reads a
 * sales or purchase table for money.
 *
 * <p>Returned as maps rather than DTOs: the report grid and the screens take
 * column/row maps, and a typed class per report would be forty classes that
 * say the same thing.
 */
@Service
@RequiredArgsConstructor
public class AccLedgerService {

    @PersistenceContext private EntityManager em;

    private static final String L = "pharma.acc_v_ledger";

    /**
     * Settings for the reads below, for this transaction only.
     *
     * <p>Postgres keeps a generic plan for a prepared statement once it has run
     * a few times, and for these queries the generic plan is a bad one: without
     * the actual organisation and party in hand it gave up the parallel scan and
     * hash anti-join for a nested loop over every line — the party balance went
     * from 190ms in a fresh session to 640ms in a warm one, and every dashboard
     * paid it. Custom plans cost a millisecond of planning each time and use
     * the indexes. The sorts these aggregates need spilled to disk at the
     * default 4MB; 64MB keeps them in memory. SET LOCAL ends with the
     * transaction, so nothing leaks into the next borrower of the connection.
     */
    private void analyticSession() {
        em.createNativeQuery("SET LOCAL plan_cache_mode = force_custom_plan").executeUpdate();
        em.createNativeQuery("SET LOCAL work_mem = '64MB'").executeUpdate();
    }

    /* ── one account, with a running balance ────────────────────────────── */

    @Transactional(readOnly = true)
    public Map<String, Object> ledger(Long orgId, Long branchId, Long accountId, LocalDate from, LocalDate to,
                                     Long costCenterId, String sourceModule, String q, int page, int size) {
        analyticSession();
        StringBuilder where = new StringBuilder(" WHERE l.pid_org = :org AND l.pid_account = :acc ");
        Map<String, Object> p = new HashMap<>(Map.of("org", orgId, "acc", accountId));
        if (branchId != null) { where.append(" AND l.pid_branch = :branch"); p.put("branch", branchId); }
        if (costCenterId != null) { where.append(" AND l.pid_cost_center = :cc"); p.put("cc", costCenterId); }
        if (sourceModule != null && !sourceModule.isBlank()) { where.append(" AND l.source_module = :src"); p.put("src", sourceModule); }
        if (q != null && !q.isBlank()) { where.append(" AND (lower(l.journal_no) LIKE :q OR lower(coalesce(l.reference,'')) LIKE :q OR lower(coalesce(l.description,'')) LIKE :q OR lower(coalesce(l.party_name,'')) LIKE :q)"); p.put("q", "%" + q.toLowerCase() + "%"); }

        Object[] open = (Object[]) bind(em.createNativeQuery("SELECT coalesce(sum(l.debit),0), coalesce(sum(l.credit),0), max(l.normal_side) FROM " + L + " l" + where + " AND l.journal_date < :from"), p)
                .setParameter("from", from).getSingleResult();
        String side = open[2] == null ? sideOf(accountId) : str(open[2]);
        BigDecimal opening = signed(side, (BigDecimal) open[0], (BigDecimal) open[1]);

        Object total = bind(em.createNativeQuery("SELECT count(*) FROM " + L + " l" + where + " AND l.journal_date BETWEEN :from AND :to"), p)
                .setParameter("from", from).setParameter("to", to).getSingleResult();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = bind(em.createNativeQuery(
                "SELECT l.journal_date, l.journal_no, l.reference, coalesce(l.description, l.journal_description), l.debit, l.credit, "
              + "l.source_module, l.source_type, l.source_id, l.pid_journal, l.party_type, l.party_name, l.pid_branch, l.journal_type, l.journal_status "
              + "FROM " + L + " l" + where + " AND l.journal_date BETWEEN :from AND :to ORDER BY l.journal_date, l.pid_journal, l.line_no"), p)
                .setParameter("from", from).setParameter("to", to).getResultList();

        // Running balance needs the rows before the page as well; the page is cut after.
        List<Map<String, Object>> all = new ArrayList<>();
        BigDecimal run = opening;
        BigDecimal dr = BigDecimal.ZERO, cr = BigDecimal.ZERO;
        for (Object[] r : rows) {
            BigDecimal d = (BigDecimal) r[4], c = (BigDecimal) r[5];
            run = run.add(signed(side, d, c));
            dr = dr.add(d); cr = cr.add(c);
            all.add(row("date", r[0], "journalNo", r[1], "reference", r[2], "description", r[3], "debit", d, "credit", c, "balance", run,
                    "sourceModule", r[6], "sourceType", r[7], "sourceId", r[8], "journalId", r[9], "partyType", r[10], "partyName", r[11],
                    "branchId", r[12], "journalType", r[13], "journalStatus", r[14]));
        }
        int fromIdx = Math.min(page * size, all.size());
        int toIdx = Math.min(fromIdx + size, all.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accountId", accountId); out.put("normalSide", side);
        out.put("openingBalance", opening); out.put("totalDebit", dr); out.put("totalCredit", cr); out.put("closingBalance", run);
        out.put("rows", all.subList(fromIdx, toIdx)); out.put("rowCount", ((Number) total).intValue());
        out.put("page", page); out.put("size", size);
        return out;
    }

    private String sideOf(Long accountId) {
        Object s = em.createNativeQuery("SELECT normal_side FROM pharma.acc_account WHERE pid_account = :a").setParameter("a", accountId).getSingleResult();
        return str(s);
    }

    /* ── balances ───────────────────────────────────────────────────────── */

    /** Every account with activity or a balance: opening, movement, closing for a window. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> trialBalance(Long orgId, Long branchId, LocalDate from, LocalDate to) {
        analyticSession();
        String branch = branchId == null ? "" : " AND l.pid_branch = :branch";
        @SuppressWarnings("unchecked")
        List<Object[]> rows = bind(em.createNativeQuery(
                /* The lines are summed per account first and the accounts joined to
                   the sums — not the other way round. Joining the chart to a million
                   lines and grouping the join ran single-threaded; the sum on its own
                   is a parallel scan, and the join is then forty rows. */
                "SELECT a.pid_account, a.account_code, a.account_name, a.account_type, a.normal_side, a.role_key, "
              + "  coalesce(x.open_dr,0), coalesce(x.open_cr,0), coalesce(x.dr,0), coalesce(x.cr,0) "
              + "FROM pharma.acc_account a LEFT JOIN ("
              + "  SELECT l.pid_account, "
              + "    sum(CASE WHEN l.journal_date < :from THEN l.debit END)  AS open_dr, "
              + "    sum(CASE WHEN l.journal_date < :from THEN l.credit END) AS open_cr, "
              + "    sum(CASE WHEN l.journal_date >= :from THEN l.debit END)  AS dr, "
              + "    sum(CASE WHEN l.journal_date >= :from THEN l.credit END) AS cr "
              + "  FROM " + L + " l WHERE l.pid_org = :org AND l.journal_date <= :to" + branch + " GROUP BY l.pid_account) x ON x.pid_account = a.pid_account "
              + "WHERE a.pid_org = :org AND a.is_group = 0 "
              + "ORDER BY a.sort_order, a.account_code"), branchId == null ? Map.of("org", orgId) : Map.of("org", orgId, "branch", branchId))
                .setParameter("from", from).setParameter("to", to).getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            BigDecimal openDr = (BigDecimal) r[6], openCr = (BigDecimal) r[7], dr = (BigDecimal) r[8], cr = (BigDecimal) r[9];
            if (openDr.signum() == 0 && openCr.signum() == 0 && dr.signum() == 0 && cr.signum() == 0) continue;
            String side = str(r[4]);
            BigDecimal opening = signed(side, openDr, openCr);
            BigDecimal closing = opening.add(signed(side, dr, cr));
            out.add(row("accountId", r[0], "code", r[1], "name", r[2], "type", r[3], "normalSide", side, "role", r[5],
                    "opening", opening, "debit", dr, "credit", cr, "closing", closing,
                    "closingDebit", closingSide(side, closing, "D"), "closingCredit", closingSide(side, closing, "C")));
        }
        return out;
    }

    /** Balance by posting role as at a date — what the dashboard tiles read. */
    @Transactional(readOnly = true)
    public Map<String, BigDecimal> balancesByRole(Long orgId, Long branchId, LocalDate asOf) {
        analyticSession();
        String branch = branchId == null ? "" : " AND l.pid_branch = :branch";
        @SuppressWarnings("unchecked")
        List<Object[]> rows = bind(em.createNativeQuery(
                "SELECT l.role_key, l.normal_side, coalesce(sum(l.debit),0), coalesce(sum(l.credit),0) FROM " + L + " l "
              + "WHERE l.pid_org = :org AND l.role_key IS NOT NULL AND l.journal_date <= :asof" + branch + " GROUP BY l.role_key, l.normal_side"),
                branchId == null ? Map.of("org", orgId) : Map.of("org", orgId, "branch", branchId)).setParameter("asof", asOf).getResultList();
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Object[] r : rows) out.put((String) r[0], signed(str(r[1]), (BigDecimal) r[2], (BigDecimal) r[3]));
        return out;
    }

    /**
     * One pass over the ledger: debits and credits per branch and account as at a date.
     *
     * <p>The dashboard once asked the ledger this question fourteen times over —
     * balances by role for the organisation, again per branch, and again per
     * bank account — and every one of those was a full scan of the journal
     * lines. A million lines at a time, that was the page. One scan grouped by
     * branch and account returns a few hundred rows, and every one of those
     * figures is a sum over them. Rows: pid_branch, pid_account, debit, credit.
     */
    @Transactional(readOnly = true)
    public List<Object[]> sumsByBranchAndAccount(Long orgId, Long branchId, LocalDate asOf) {
        analyticSession();
        String branch = branchId == null ? "" : " AND l.pid_branch = :branch";
        @SuppressWarnings("unchecked")
        List<Object[]> rows = bind(em.createNativeQuery(
                "SELECT l.pid_branch, l.pid_account, coalesce(sum(l.debit),0), coalesce(sum(l.credit),0) FROM " + L + " l "
              + "WHERE l.pid_org = :org AND l.journal_date <= :asof" + branch + " GROUP BY l.pid_branch, l.pid_account"),
                branchId == null ? Map.of("org", orgId) : Map.of("org", orgId, "branch", branchId)).setParameter("asof", asOf).getResultList();
        return rows;
    }

    /**
     * The same pass for a window, split into the window itself and the one
     * before it: rows carry a flag (1 = between from and to, 0 = between
     * prevFrom and the day before from). Rows: pid_branch, pid_account, flag, debit, credit.
     */
    @Transactional(readOnly = true)
    public List<Object[]> windowSumsByBranchAndAccount(Long orgId, Long branchId, LocalDate prevFrom, LocalDate from, LocalDate to) {
        analyticSession();
        String branch = branchId == null ? "" : " AND l.pid_branch = :branch";
        @SuppressWarnings("unchecked")
        List<Object[]> rows = bind(em.createNativeQuery(
                "SELECT l.pid_branch, l.pid_account, CASE WHEN l.journal_date >= :from THEN 1 ELSE 0 END AS cur, "
              + "coalesce(sum(l.debit),0), coalesce(sum(l.credit),0) FROM " + L + " l "
              + "WHERE l.pid_org = :org AND l.journal_date BETWEEN :pfrom AND :to" + branch + " GROUP BY 1, 2, 3"),
                branchId == null ? Map.of("org", orgId) : Map.of("org", orgId, "branch", branchId))
                .setParameter("pfrom", prevFrom).setParameter("from", from).setParameter("to", to).getResultList();
        return rows;
    }

    /** Sum by account type for a window: REVENUE, EXPENSE ... signed by normal side. */
    @Transactional(readOnly = true)
    public Map<String, BigDecimal> totalsByType(Long orgId, Long branchId, LocalDate from, LocalDate to) {
        analyticSession();
        String branch = branchId == null ? "" : " AND l.pid_branch = :branch";
        @SuppressWarnings("unchecked")
        List<Object[]> rows = bind(em.createNativeQuery(
                "SELECT l.account_type, coalesce(sum(l.debit),0), coalesce(sum(l.credit),0) FROM " + L + " l "
              + "WHERE l.pid_org = :org AND l.journal_date BETWEEN :from AND :to" + branch + " GROUP BY l.account_type"),
                branchId == null ? Map.of("org", orgId) : Map.of("org", orgId, "branch", branchId))
                .setParameter("from", from).setParameter("to", to).getResultList();
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Object[] r : rows) {
            String type = (String) r[0];
            String side = Set.of("ASSET", "EXPENSE").contains(type) ? "D" : "C";
            out.put(type, signed(side, (BigDecimal) r[1], (BigDecimal) r[2]));
        }
        return out;
    }

    /**
     * One row per day in the window: revenue, expense (cost of sales included), cash in and cash
     * out across the cash accounts — the dashboard's trend line, read from the same ledger.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> dailyTrend(Long orgId, Long branchId, LocalDate from, LocalDate to) {
        analyticSession();
        String branch = branchId == null ? "" : " AND l.pid_branch = :branch";
        @SuppressWarnings("unchecked")
        List<Object[]> rows = bind(em.createNativeQuery(
                "SELECT l.journal_date, "
              + "coalesce(sum(CASE WHEN l.account_type = 'REVENUE' THEN l.credit - l.debit END),0), "
              + "coalesce(sum(CASE WHEN l.account_type = 'EXPENSE' THEN l.debit - l.credit END),0), "
              + "coalesce(sum(CASE WHEN l.role_key IN ('CASH_REGISTER','BANK_DEFAULT','MOBILE_WALLET','PETTY_CASH') THEN l.debit END),0), "
              + "coalesce(sum(CASE WHEN l.role_key IN ('CASH_REGISTER','BANK_DEFAULT','MOBILE_WALLET','PETTY_CASH') THEN l.credit END),0) "
              + "FROM " + L + " l WHERE l.pid_org = :org AND l.journal_date BETWEEN :from AND :to" + branch
              + " GROUP BY l.journal_date ORDER BY l.journal_date"),
                branchId == null ? Map.of("org", orgId) : Map.of("org", orgId, "branch", branchId))
                .setParameter("from", from).setParameter("to", to).getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(row("date", r[0] instanceof java.sql.Date d ? d.toLocalDate() : r[0], "revenue", r[1], "expense", r[2], "cashIn", r[3], "cashOut", r[4]));
        }
        return out;
    }

    /** Profit and loss lines: every revenue and expense account with movement in the window. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> profitAndLoss(Long orgId, Long branchId, LocalDate from, LocalDate to) {
        analyticSession();
        String branch = branchId == null ? "" : " AND l.pid_branch = :branch";
        @SuppressWarnings("unchecked")
        List<Object[]> rows = bind(em.createNativeQuery(
                "SELECT a.pid_account, a.account_code, a.account_name, a.account_type, a.normal_side, a.role_key, "
              + "  coalesce(sum(l.debit),0), coalesce(sum(l.credit),0), p.account_name "
              + "FROM pharma.acc_account a LEFT JOIN pharma.acc_account p ON p.pid_account = a.pid_parent "
              + "JOIN " + L + " l ON l.pid_account = a.pid_account AND l.journal_date BETWEEN :from AND :to" + branch + " "
              + "WHERE a.pid_org = :org AND a.account_type IN ('REVENUE','EXPENSE') "
              + "GROUP BY a.pid_account, a.account_code, a.account_name, a.account_type, a.normal_side, a.role_key, a.sort_order, p.account_name "
              + "ORDER BY a.sort_order, a.account_code"), branchId == null ? Map.of("org", orgId) : Map.of("org", orgId, "branch", branchId))
                .setParameter("from", from).setParameter("to", to).getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            BigDecimal amount = signed(str(r[4]), (BigDecimal) r[6], (BigDecimal) r[7]);
            // Contra-revenue (Sales Returns, normal side D under REVENUE) shows negative within revenue.
            BigDecimal shown = "REVENUE".equals(r[3]) && "D".equals(str(r[4])) ? amount.negate() : amount;
            out.add(row("accountId", r[0], "code", r[1], "name", r[2], "type", r[3], "group", r[8], "role", r[5], "amount", shown));
        }
        return out;
    }

    /**
     * Expenses day by day, split by head and by cost centre.
     *
     * <p>Neither of the two reports that already touch this ground answers the
     * question: the daily summary gives one expense figure a day with no
     * breakdown, and the expense analysis breaks the window down by head with
     * no days in it. This is the intersection, plus the dimension that until
     * now nothing read back.
     *
     * <p>Cost centre is LEFT JOINed and comes back as null where a posting
     * carries none. That is most of them, and it is shown as such rather than
     * quietly bucketed into an "other" that would read like a real centre.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> dailyExpenses(Long orgId, Long branchId, LocalDate from, LocalDate to, Long costCenterId) {
        analyticSession();
        Map<String, Object> params = new HashMap<>();
        params.put("org", orgId);
        String branch = "";
        if (branchId != null) { branch = " AND l.pid_branch = :branch"; params.put("branch", branchId); }
        String cc = "";
        if (costCenterId != null) { cc = " AND l.pid_cost_center = :cc"; params.put("cc", costCenterId); }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = bind(em.createNativeQuery(
                "SELECT l.journal_date, a.account_code, a.account_name, "
              + "  c.cc_code, c.cc_name, "
              + "  coalesce(sum(l.debit),0) - coalesce(sum(l.credit),0), count(*) "
              + "FROM " + L + " l "
              + "JOIN pharma.acc_account a ON a.pid_account = l.pid_account "
              + "LEFT JOIN pharma.acc_cost_center c ON c.pid_cost_center = l.pid_cost_center "
              + "WHERE a.pid_org = :org AND a.account_type = 'EXPENSE' "
              + "  AND l.journal_date BETWEEN :from AND :to" + branch + cc + " "
              + "GROUP BY l.journal_date, a.account_code, a.account_name, c.cc_code, c.cc_name "
              + "HAVING coalesce(sum(l.debit),0) - coalesce(sum(l.credit),0) <> 0 "
              + "ORDER BY l.journal_date, a.account_code, c.cc_code NULLS FIRST"), params)
                .setParameter("from", from).setParameter("to", to).getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(row("date", r[0] instanceof java.sql.Date d ? d.toLocalDate() : r[0],
                    "code", r[1], "name", r[2],
                    "ccCode", r[3], "ccName", r[4],
                    "amount", r[5], "postings", r[6]));
        }
        return out;
    }

    /** Balance sheet lines as at a date. Current-period profit is derived and shown under equity. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> balanceSheet(Long orgId, Long branchId, LocalDate asOf) {
        analyticSession();
        String branch = branchId == null ? "" : " AND l.pid_branch = :branch";
        @SuppressWarnings("unchecked")
        List<Object[]> rows = bind(em.createNativeQuery(
                // Lines summed per account first (a parallel scan), the chart joined to the sums after — see trialBalance.
                "SELECT a.pid_account, a.account_code, a.account_name, a.account_type, a.normal_side, a.role_key, "
              + "  x.dr, x.cr, p.account_name "
              + "FROM pharma.acc_account a LEFT JOIN pharma.acc_account p ON p.pid_account = a.pid_parent "
              + "JOIN (SELECT l.pid_account, coalesce(sum(l.debit),0) AS dr, coalesce(sum(l.credit),0) AS cr FROM " + L + " l "
              + "      WHERE l.pid_org = :org AND l.journal_date <= :asof" + branch + " GROUP BY l.pid_account) x ON x.pid_account = a.pid_account "
              + "WHERE a.pid_org = :org AND a.account_type IN ('ASSET','LIABILITY','EQUITY') "
              + "ORDER BY a.sort_order, a.account_code"), branchId == null ? Map.of("org", orgId) : Map.of("org", orgId, "branch", branchId))
                .setParameter("asof", asOf).getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            BigDecimal amount = signed(str(r[4]), (BigDecimal) r[6], (BigDecimal) r[7]);
            if (amount.signum() == 0) continue;
            BigDecimal shown = "EQUITY".equals(r[3]) && "D".equals(str(r[4])) ? amount.negate() : amount;
            out.add(row("accountId", r[0], "code", r[1], "name", r[2], "type", r[3], "group", r[8], "role", r[5], "amount", shown));
        }
        Map<String, BigDecimal> pl = totalsByType(orgId, branchId, LocalDate.of(1900, 1, 1), asOf);
        BigDecimal profit = pl.getOrDefault("REVENUE", BigDecimal.ZERO).subtract(pl.getOrDefault("EXPENSE", BigDecimal.ZERO));
        out.add(row("accountId", null, "code", "", "name", "Profit to date (revenue less expenses)", "type", "EQUITY", "group", "Equity", "role", "PROFIT_TO_DATE", "amount", profit));
        return out;
    }

    /* ── parties: receivables, payables, statements, ageing ─────────────── */

    /**
     * A statement's role filter: one role, or several separated by commas.
     *
     * <p>A supplier's position is the payable <em>and</em> the advance held
     * against it (AP + SUPPLIER_ADVANCE): a payment above the bill books the
     * excess as an advance on 1410, party-tagged, and a statement that read
     * only AP showed the bill settled and nothing else — the 8,088.43 the
     * supplier owed back was invisible. With both roles in one statement the
     * running balance is the net: positive is owed to the supplier, negative
     * is an advance the supplier holds.
     */
    static List<String> roles(String roleKey) { return Arrays.stream(roleKey.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList(); }
    private static String roleFilter(String roleKey) { return roleKey == null ? "" : " AND l.role_key IN (:roles)"; }
    /** The side a mixed-role statement is read from: the party's primary role decides, never the last row. */
    static String sideOf(String roleKey) {
        if (roleKey == null) return null;
        List<String> r = roles(roleKey);
        if (r.size() < 2) return null;
        if (r.contains("AP")) return "C";
        if (r.contains("AR")) return "D";
        return null;
    }

    /** Balance per party of a type (CUSTOMER for AR, SUPPLIER for AP, INVESTOR, EMPLOYEE, TENANT). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> partyBalances(Long orgId, Long branchId, String partyType, String roleKey, LocalDate asOf, boolean openOnly) {
        analyticSession();
        String branch = branchId == null ? "" : " AND l.pid_branch = :branch";
        String role = roleFilter(roleKey);
        Map<String, Object> p = new HashMap<>(Map.of("org", orgId, "pt", partyType));
        if (branchId != null) p.put("branch", branchId);
        if (roleKey != null) p.put("roles", roles(roleKey));
        String fixedSide = sideOf(roleKey);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = bind(em.createNativeQuery(
                "SELECT l.party_id, max(l.party_name), max(l.normal_side), coalesce(sum(l.debit),0), coalesce(sum(l.credit),0), max(l.journal_date), count(*) "
              + "FROM " + L + " l WHERE l.pid_org = :org AND l.party_type = :pt AND l.party_id IS NOT NULL AND l.journal_date <= :asof" + branch + role + " "
              + "GROUP BY l.party_id ORDER BY max(l.party_name)"), p).setParameter("asof", asOf).getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            BigDecimal bal = signed(fixedSide != null ? fixedSide : str(r[2]), (BigDecimal) r[3], (BigDecimal) r[4]);
            if (openOnly && bal.signum() == 0) continue;
            out.add(row("partyId", r[0], "partyName", r[1], "debit", r[3], "credit", r[4], "balance", bal, "lastActivity", r[5], "lines", r[6]));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> partyStatement(Long orgId, Long branchId, String partyType, Long partyId, String roleKey, LocalDate from, LocalDate to) {
        analyticSession();
        String branch = branchId == null ? "" : " AND l.pid_branch = :branch";
        String role = roleFilter(roleKey);
        Map<String, Object> p = new HashMap<>(Map.of("org", orgId, "pt", partyType, "pid", partyId));
        if (branchId != null) p.put("branch", branchId);
        if (roleKey != null) p.put("roles", roles(roleKey));
        Object[] open = (Object[]) bind(em.createNativeQuery("SELECT coalesce(sum(l.debit),0), coalesce(sum(l.credit),0), max(l.normal_side) FROM " + L + " l "
              + "WHERE l.pid_org = :org AND l.party_type = :pt AND l.party_id = :pid AND l.journal_date < :from" + branch + role), p).setParameter("from", from).getSingleResult();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = bind(em.createNativeQuery(
                "SELECT l.journal_date, l.journal_no, l.reference, coalesce(l.description, l.journal_description), l.debit, l.credit, l.source_module, l.source_type, l.source_id, l.pid_journal, l.normal_side, l.account_name "
              + "FROM " + L + " l WHERE l.pid_org = :org AND l.party_type = :pt AND l.party_id = :pid AND l.journal_date BETWEEN :from AND :to" + branch + role + " "
              + "ORDER BY l.journal_date, l.pid_journal, l.line_no"), p).setParameter("from", from).setParameter("to", to).getResultList();
        String fixedSide = sideOf(roleKey);
        String side = fixedSide != null ? fixedSide : open[2] != null ? str(open[2]) : (rows.isEmpty() ? "D" : str(rows.get(0)[10]));
        BigDecimal run = signed(side, (BigDecimal) open[0], (BigDecimal) open[1]);
        BigDecimal opening = run;
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Object[] r : rows) {
            run = run.add(signed(side, (BigDecimal) r[4], (BigDecimal) r[5]));
            lines.add(row("date", r[0], "journalNo", r[1], "reference", r[2], "description", r[3], "debit", r[4], "credit", r[5], "balance", run,
                    "sourceModule", r[6], "sourceType", r[7], "sourceId", r[8], "journalId", r[9], "account", r[11]));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("partyType", partyType); out.put("partyId", partyId); out.put("normalSide", side); out.put("roles", roleKey == null ? List.of() : roles(roleKey));
        out.put("openingBalance", opening); out.put("closingBalance", run); out.put("rows", lines);
        return out;
    }

    /**
     * Ageing of what a party type owes / is owed, from the ledger: each party's
     * open balance is laid against its debits (AR) or credits (AP), newest
     * first, so the oldest unpaid value falls in the oldest bucket.
     *
     * <p>One pass over the party's lines, grouped by party and day. The daily
     * rows give the balance (their sum), the last activity (their newest
     * date) and the ageing walk (newest day first, each day's amount taken
     * until the balance is used up) — lines of one day always fall in the same
     * bucket, so a day is the finest grain the walk needs. This used to be two
     * scans of the same lines, one for the balances and one for the walk, and
     * on a customer ledger of three hundred thousand lines each was the
     * dashboard's single largest cost.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> ageing(Long orgId, Long branchId, String partyType, String roleKey, LocalDate asOf) {
        analyticSession();
        String branch = branchId == null ? "" : " AND l.pid_branch = :branch";
        String role = roleKey == null ? "" : " AND l.role_key = :role";
        boolean debitSide = "CUSTOMER".equals(partyType) || "EMPLOYEE".equals(partyType) || "TENANT".equals(partyType);
        Map<String, Object> p = new HashMap<>(Map.of("org", orgId, "pt", partyType));
        if (branchId != null) p.put("branch", branchId);
        if (roleKey != null) p.put("role", roleKey);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = bind(em.createNativeQuery(
                "SELECT l.party_id, l.journal_date, max(l.party_name), max(l.normal_side), coalesce(sum(l.debit),0), coalesce(sum(l.credit),0), count(*) "
              + "FROM " + L + " l WHERE l.pid_org = :org AND l.party_type = :pt AND l.party_id IS NOT NULL AND l.journal_date <= :asof" + branch + role + " "
              + "GROUP BY l.party_id, l.journal_date ORDER BY l.party_id, l.journal_date DESC"), p).setParameter("asof", asOf).getResultList();

        // Per party: the rows are already newest first.
        Map<Long, List<Object[]>> days = new LinkedHashMap<>();
        for (Object[] r : rows) days.computeIfAbsent(((Number) r[0]).longValue(), k -> new ArrayList<>()).add(r);

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<Long, List<Object[]>> e : days.entrySet()) {
            List<Object[]> d = e.getValue();
            String name = null, side = null;
            BigDecimal debit = BigDecimal.ZERO, credit = BigDecimal.ZERO;
            long lines = 0;
            Object last = null;
            for (Object[] r : d) {
                if (name == null) name = str(r[2]);
                if (side == null) side = str(r[3]);
                debit = debit.add((BigDecimal) r[4]); credit = credit.add((BigDecimal) r[5]);
                lines += ((Number) r[6]).longValue();
                if (last == null) last = r[1];
            }
            BigDecimal balance = signed(side, debit, credit);
            if (balance.signum() == 0) continue;
            Map<String, Object> party = row("partyId", e.getKey(), "partyName", name, "debit", debit, "credit", credit, "balance", balance, "lastActivity", last, "lines", lines);
            if (balance.signum() < 0) { out.add(withBuckets(party, balance, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)); continue; }

            BigDecimal left = balance;
            BigDecimal b0 = BigDecimal.ZERO, b30 = BigDecimal.ZERO, b60 = BigDecimal.ZERO, b90 = BigDecimal.ZERO;
            for (Object[] r : d) {
                if (left.signum() <= 0) break;
                BigDecimal amt = (BigDecimal) (debitSide ? r[4] : r[5]);
                if (amt.signum() <= 0) continue;
                BigDecimal take = amt.min(left);
                LocalDate day = r[1] instanceof java.sql.Date sd ? sd.toLocalDate() : (LocalDate) r[1];
                long age = java.time.temporal.ChronoUnit.DAYS.between(day, asOf);
                if (age <= 30) b0 = b0.add(take); else if (age <= 60) b30 = b30.add(take); else if (age <= 90) b60 = b60.add(take); else b90 = b90.add(take);
                left = left.subtract(take);
            }
            // A balance the documents do not cover (an opening balance, say) is the oldest money there is.
            if (left.signum() > 0) b90 = b90.add(left);
            out.add(withBuckets(party, balance, b0, b30, b60, b90));
        }
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("partyName"))));
        return out;
    }

    private static Map<String, Object> withBuckets(Map<String, Object> party, BigDecimal bal, BigDecimal b0, BigDecimal b30, BigDecimal b60, BigDecimal b90) {
        Map<String, Object> m = new LinkedHashMap<>(party);
        m.put("outstanding", bal); m.put("d0_30", b0); m.put("d31_60", b30); m.put("d61_90", b60); m.put("d90plus", b90);
        return m;
    }

    /* ── cash ───────────────────────────────────────────────────────────── */

    /** Movements on one account (a till, a bank, petty cash) in a window, grouped by source for a drill-down. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> cashBySource(Long orgId, Long branchId, Long accountId, LocalDate from, LocalDate to) {
        analyticSession();
        Query q = em.createNativeQuery(
                "SELECT l.source_module, l.source_type, coalesce(sum(l.debit),0), coalesce(sum(l.credit),0), count(*) FROM " + L + " l "
              + "WHERE l.pid_org = :org AND " + (branchId == null ? "1=1" : "l.pid_branch = :branch") + " AND l.pid_account = :acc AND l.journal_date BETWEEN :from AND :to "
              + "GROUP BY l.source_module, l.source_type ORDER BY 3 DESC, 4 DESC")
                .setParameter("org", orgId).setParameter("acc", accountId)
                .setParameter("from", from).setParameter("to", to);
        if (branchId != null) q.setParameter("branch", branchId);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) out.add(row("sourceModule", r[0], "sourceType", r[1], "cashIn", r[2], "cashOut", r[3], "count", r[4]));
        return out;
    }

    /** Debits, credits and closing balance of one account for one day. */
    @Transactional(readOnly = true)
    public BigDecimal[] dayMovement(Long branchId, Long accountId, LocalDate day) {
        Object[] r = (Object[]) em.createNativeQuery("SELECT coalesce(sum(l.debit),0), coalesce(sum(l.credit),0) FROM " + L + " l "
              + "WHERE l.pid_branch = :branch AND l.pid_account = :acc AND l.journal_date = :day")
                .setParameter("branch", branchId).setParameter("acc", accountId).setParameter("day", day).getSingleResult();
        return new BigDecimal[]{ (BigDecimal) r[0], (BigDecimal) r[1] };
    }

    @Transactional(readOnly = true)
    public BigDecimal accountBalance(Long branchId, Long accountId, LocalDate asOf, String side) {
        analyticSession();
        Query q = em.createNativeQuery("SELECT coalesce(sum(l.debit),0), coalesce(sum(l.credit),0) FROM " + L + " l "
              + "WHERE " + (branchId == null ? "1=1" : "l.pid_branch = :branch") + " AND l.pid_account = :acc AND l.journal_date <= :asof")
                .setParameter("acc", accountId).setParameter("asof", asOf);
        if (branchId != null) q.setParameter("branch", branchId);
        Object[] r = (Object[]) q.getSingleResult();
        return signed(side, (BigDecimal) r[0], (BigDecimal) r[1]);
    }

    /** Simplified direct-method cash flow: cash account movements by the type of the counter side. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> cashFlow(Long orgId, Long branchId, LocalDate from, LocalDate to) {
        analyticSession();
        String branch = branchId == null ? "" : " AND c.pid_branch = :branch";
        @SuppressWarnings("unchecked")
        List<Object[]> rows = bind(em.createNativeQuery(
                "SELECT coalesce(o.role_key, o.account_type) AS bucket, o.account_type, coalesce(sum(c.debit),0), coalesce(sum(c.credit),0) "
              + "FROM " + L + " c JOIN " + L + " o ON o.pid_journal = c.pid_journal AND o.pid_journal_line <> c.pid_journal_line "
              + "  AND coalesce(o.role_key,'') NOT IN ('CASH_REGISTER','PETTY_CASH','BANK_DEFAULT','MOBILE_WALLET') AND NOT (o.account_code LIKE '113%' AND o.account_type = 'ASSET') "
              + "WHERE c.pid_org = :org AND (c.role_key IN ('CASH_REGISTER','PETTY_CASH','BANK_DEFAULT','MOBILE_WALLET') "
              + "  OR (c.account_code LIKE '113%' AND c.account_type = 'ASSET')) "
              + "  AND c.journal_date BETWEEN :from AND :to" + branch + " "
              + "GROUP BY 1, 2 ORDER BY 2, 1"), branchId == null ? Map.of("org", orgId) : Map.of("org", orgId, "branch", branchId))
                .setParameter("from", from).setParameter("to", to).getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            String type = (String) r[1];
            String activity = switch (type) { case "REVENUE", "EXPENSE" -> "Operating"; case "ASSET" -> "Investing / working capital"; case "LIABILITY" -> "Financing / payables"; default -> "Financing / equity"; };
            out.add(row("activity", activity, "counterpart", r[0], "type", type, "inflow", r[2], "outflow", r[3], "net", ((BigDecimal) r[2]).subtract((BigDecimal) r[3])));
        }
        return out;
    }

    /* ── journal register ───────────────────────────────────────────────── */

    @Transactional(readOnly = true)
    public List<Map<String, Object>> journalLines(Long journalId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT l.line_no, l.pid_account, l.account_code, l.account_name, l.debit, l.credit, l.description, l.party_type, l.party_id, l.party_name, l.pid_branch, l.pid_cost_center "
              + "FROM " + L + " l WHERE l.pid_journal = :j ORDER BY l.line_no").setParameter("j", journalId).getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) out.add(row("lineNo", r[0], "accountId", r[1], "code", r[2], "account", r[3], "debit", r[4], "credit", r[5], "description", r[6],
                "partyType", r[7], "partyId", r[8], "partyName", r[9], "branchId", r[10], "costCenterId", r[11]));
        return out;
    }

    /** Daily totals of cash-account movements for a branch: the branch cash book. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> dailyCashBook(Long branchId, Long accountId, LocalDate from, LocalDate to) {
        analyticSession();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT l.journal_date, coalesce(sum(l.debit),0), coalesce(sum(l.credit),0), count(*) FROM " + L + " l "
              + "WHERE l.pid_branch = :branch AND l.pid_account = :acc AND l.journal_date BETWEEN :from AND :to GROUP BY l.journal_date ORDER BY l.journal_date")
                .setParameter("branch", branchId).setParameter("acc", accountId).setParameter("from", from).setParameter("to", to).getResultList();
        Object[] open = (Object[]) em.createNativeQuery("SELECT coalesce(sum(l.debit),0), coalesce(sum(l.credit),0) FROM " + L + " l WHERE l.pid_branch = :branch AND l.pid_account = :acc AND l.journal_date < :from")
                .setParameter("branch", branchId).setParameter("acc", accountId).setParameter("from", from).getSingleResult();
        BigDecimal run = ((BigDecimal) open[0]).subtract((BigDecimal) open[1]);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            BigDecimal opening = run;
            run = run.add((BigDecimal) r[1]).subtract((BigDecimal) r[2]);
            out.add(row("date", r[0], "opening", opening, "cashIn", r[1], "cashOut", r[2], "closing", run, "count", r[3]));
        }
        return out;
    }

    /* ── helpers ────────────────────────────────────────────────────────── */

    static BigDecimal signed(String side, BigDecimal debit, BigDecimal credit) {
        BigDecimal d = debit == null ? BigDecimal.ZERO : debit, c = credit == null ? BigDecimal.ZERO : credit;
        return ("D".equals(side) ? d.subtract(c) : c.subtract(d)).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal closingSide(String side, BigDecimal closing, String want) {
        // A debit-normal account with a positive balance shows under Debit; a negative one under Credit.
        boolean debit = "D".equals(side) ? closing.signum() >= 0 : closing.signum() < 0;
        BigDecimal abs = closing.abs();
        return "D".equals(want) ? (debit ? abs : BigDecimal.ZERO) : (debit ? BigDecimal.ZERO : abs);
    }

    private static Query bind(Query q, Map<String, Object> params) {
        params.forEach(q::setParameter);
        return q;
    }

    static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1] instanceof java.sql.Date d ? d.toLocalDate() : kv[i + 1]);
        return m;
    }
    /** Hibernate hands a VARCHAR(1) back as a Character; every side comparison goes through here. */
    static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
