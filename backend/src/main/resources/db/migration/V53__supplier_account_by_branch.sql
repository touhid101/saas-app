/* ─────────────────────────────────────────────────────────────────────────────
   Supplier Outstanding, per branch.

   V_SUPPLIER_ACCOUNT is grained per supplier per organisation. Supplier
   Outstanding and Supplier Ageing read it filtered on PID_ORG alone, so in an
   organisation running more than one pharmacy every branch saw the combined
   debt of all of them — 40,036 purchase documents across three branches
   reported to a user who has 17 of their own.

   BranchScopeService names suppliers explicitly as something one pharmacy must
   not see of another's, so the fix is to scope the report, not to relabel it.

   This view is the same aggregate at a finer grain. It is a companion rather
   than a replacement: the org-level view is still the honest answer to "what do
   we owe this supplier as a company", and the Supplier Payment screen reads it.
   Both derive from V_PURCHASE_PAYABLE and V_SUPPLIER_PAYMENT_LINE, so the two
   cannot drift apart.

   ── Two attribution decisions ──

   A payment is attributed to the branch of the invoice it settles, not to the
   branch that recorded it. Otherwise a head-office payment against a branch's
   bill would leave the debt on the branch and the credit at head office, and
   neither row would reconcile. Money paid on account settles no invoice, so it
   keeps the paying branch — that is the only branch it can honestly belong to.

   OPENING_BAL is not carried here. It is recorded per supplier with no branch
   against it, so it cannot be split; repeating it on every branch row would
   multiply the debt by the number of branches. It is zero for every supplier in
   this database today, so the branch rows currently sum exactly to the org
   figure. Should an opening balance ever be entered, this report will understate
   by it and the org-level view remains the place that figure is honoured.
   ───────────────────────────────────────────────────────────────────────────── */

CREATE OR REPLACE VIEW pharma.v_supplier_account_branch AS
WITH purchases AS (
    SELECT p.pid_supplier,
           p.pid_branch,
           count(*)                     AS invoices,
           sum(p.billed)                AS billed,
           sum(p.return_credit)         AS return_credit,
           sum(p.due)                   AS due,
           sum(CASE WHEN p.due > 0 AND p.age_days <= 30                       THEN p.due ELSE 0 END) AS due_0_30,
           sum(CASE WHEN p.due > 0 AND p.age_days BETWEEN 31 AND 60           THEN p.due ELSE 0 END) AS due_31_60,
           sum(CASE WHEN p.due > 0 AND p.age_days BETWEEN 61 AND 90           THEN p.due ELSE 0 END) AS due_61_90,
           sum(CASE WHEN p.due > 0 AND p.age_days > 90                        THEN p.due ELSE 0 END) AS due_90_plus,
           min(p.transaction_date)      AS first_purchase,
           max(p.transaction_date)      AS last_purchase
      FROM pharma.v_purchase_payable p
     GROUP BY p.pid_supplier, p.pid_branch
),
/* The branch each payment line belongs to: the invoice's, where it settles one. */
payment_lines AS (
    SELECT l.pid_supplier,
           COALESCE(inv.pid_branch, l.pid_branch) AS pid_branch,
           l.cash_out,
           l.advance_delta,
           CASE WHEN l.entry_type = 'PAYMENT' AND l.pid_tran_mst IS NULL
                THEN l.applied ELSE 0 END AS on_account
      FROM pharma.v_supplier_payment_line l
      LEFT JOIN pharma.pos_transaction_mst inv ON inv.pid_tran_mst = l.pid_tran_mst
),
payments AS (
    SELECT pid_supplier, pid_branch,
           sum(cash_out)      AS cash_out,
           sum(advance_delta) AS advance,
           sum(on_account)    AS on_account
      FROM payment_lines
     GROUP BY pid_supplier, pid_branch
),
/* Every supplier/branch pair that either side knows about. A branch that has
   only paid, or only bought, still has a position worth reporting. */
pairs AS (
    SELECT pid_supplier, pid_branch FROM purchases
    UNION
    SELECT pid_supplier, pid_branch FROM payments
),
last_paid AS (
    SELECT DISTINCT ON (sp.pid_supplier, sp.pid_branch)
           sp.pid_supplier, sp.pid_branch,
           sp.payment_date AS last_payment_date,
           sp.amount       AS last_payment_amount
      FROM pharma.pos_supplier_payment sp
     WHERE sp.status = 'Y' AND sp.entry_type = 'PAYMENT'
     ORDER BY sp.pid_supplier, sp.pid_branch, sp.payment_date DESC, sp.pid_payment DESC
)
SELECT s.pid_supplier,
       s.pid_org,
       x.pid_branch,
       s.supplier_name,
       s.mobile_no,
       s.responsible_person,
       s.credit_limit,
       COALESCE(p.invoices, 0)      AS invoices,
       COALESCE(p.billed, 0)        AS billed,
       COALESCE(l.cash_out, 0)      AS paid,
       COALESCE(p.return_credit, 0) AS return_credit,
       COALESCE(l.on_account, 0)    AS on_account,
       COALESCE(l.advance, 0)       AS advance_balance,
       COALESCE(p.due, 0) - COALESCE(l.on_account, 0) AS outstanding,
       COALESCE(p.due, 0) - COALESCE(l.on_account, 0) - COALESCE(l.advance, 0) AS net_position,
       COALESCE(p.due_0_30, 0)      AS due_0_30,
       COALESCE(p.due_31_60, 0)     AS due_31_60,
       COALESCE(p.due_61_90, 0)     AS due_61_90,
       COALESCE(p.due_90_plus, 0)   AS due_90_plus,
       p.first_purchase,
       p.last_purchase,
       lp.last_payment_date,
       lp.last_payment_amount
  FROM pairs x
  JOIN pharma.pos_supplier_info s ON s.pid_supplier = x.pid_supplier
  LEFT JOIN purchases p ON p.pid_supplier = x.pid_supplier AND p.pid_branch = x.pid_branch
  LEFT JOIN payments  l ON l.pid_supplier = x.pid_supplier AND l.pid_branch = x.pid_branch
  LEFT JOIN last_paid lp ON lp.pid_supplier = x.pid_supplier AND lp.pid_branch = x.pid_branch;

COMMENT ON VIEW pharma.v_supplier_account_branch IS
  'Supplier position per branch. Companion to V_SUPPLIER_ACCOUNT, which is the '
  'same aggregate for the whole organisation. Excludes OPENING_BAL, which has no '
  'branch recorded against it.';
