/* ─────────────────────────────────────────────────────────────────────────────
   Purchase-return credit that names no invoice.

   V_PURCHASE_PAYABLE already nets a return against the bill it reverses:

       due = billed - paid_at_invoice - paid_later - return_credit

   but only where the return points at a purchase (PID_TRAN_MST_RETURN) and is
   settled as ADJUSTMENT. That is the common case and it works.

   What had nowhere to go is a credit note the supplier issues against the
   account rather than against one invoice — goods sent back after the bill was
   already settled, or a rebate to be taken off whatever is bought next. It sat
   in POS_TRANSACTION_MST reducing nothing, so the Supplier Payment screen and
   the Outstanding report both showed the full payable while the supplier's own
   ledger showed less. The difference is only ever found by argument.

   ── How it is treated ──

   Exactly as an unapplied payment already is. ON_ACCOUNT is money handed over
   and not yet matched to a bill; UNAPPLIED_CREDIT is value returned and not yet
   matched to a bill. Both reduce what is owed overall without belonging to any
   particular invoice, so both are subtracted from OUTSTANDING and neither
   touches the ageing buckets — which is why the report says the buckets less
   unapplied give outstanding.

   "Adjust with the next purchase" therefore needs no separate mechanism: the
   credit is already off the balance, and the next invoice raises the balance by
   less than its face value. Applying it to a specific bill remains possible and
   is what PID_TRAN_MST_RETURN records; until someone does, the money is still
   correctly ours.

   ── Which returns count ──

   ADJUSTMENT only, matching V_PURCHASE_PAYABLE. A return settled as CASH or BANK
   is one where the supplier gave the money back — counting it again as credit
   would collect it twice. REPLACEMENT is goods for goods and moves no money at
   all. A NULL payment type is read as ADJUSTMENT, because that is the form's
   default and the value predates the column being written consistently.
   ───────────────────────────────────────────────────────────────────────────── */

CREATE OR REPLACE VIEW pharma.v_supplier_account_branch AS
WITH purchases AS (
         SELECT p_1.pid_supplier,
            p_1.pid_branch,
            count(*) AS invoices,
            sum(p_1.billed) AS billed,
            sum(p_1.return_credit) AS return_credit,
            sum(p_1.due) AS due,
            sum(
                CASE
                    WHEN p_1.due > 0::numeric AND p_1.age_days <= 30 THEN p_1.due
                    ELSE 0::numeric
                END) AS due_0_30,
            sum(
                CASE
                    WHEN p_1.due > 0::numeric AND p_1.age_days >= 31 AND p_1.age_days <= 60 THEN p_1.due
                    ELSE 0::numeric
                END) AS due_31_60,
            sum(
                CASE
                    WHEN p_1.due > 0::numeric AND p_1.age_days >= 61 AND p_1.age_days <= 90 THEN p_1.due
                    ELSE 0::numeric
                END) AS due_61_90,
            sum(
                CASE
                    WHEN p_1.due > 0::numeric AND p_1.age_days > 90 THEN p_1.due
                    ELSE 0::numeric
                END) AS due_90_plus,
            min(p_1.transaction_date) AS first_purchase,
            max(p_1.transaction_date) AS last_purchase
           FROM v_purchase_payable p_1
          GROUP BY p_1.pid_supplier, p_1.pid_branch
        ), payment_lines AS (
         SELECT l_1.pid_supplier,
            COALESCE(inv.pid_branch, l_1.pid_branch) AS pid_branch,
            l_1.cash_out,
            l_1.advance_delta,
                CASE
                    WHEN l_1.entry_type::text = 'PAYMENT'::text AND l_1.pid_tran_mst IS NULL THEN l_1.applied
                    ELSE 0::numeric
                END AS on_account
           FROM v_supplier_payment_line l_1
             LEFT JOIN pos_transaction_mst inv ON inv.pid_tran_mst = l_1.pid_tran_mst
        ), payments AS (
         SELECT payment_lines.pid_supplier,
            payment_lines.pid_branch,
            sum(payment_lines.cash_out) AS cash_out,
            sum(payment_lines.advance_delta) AS advance,
            sum(payment_lines.on_account) AS on_account
           FROM payment_lines
          GROUP BY payment_lines.pid_supplier, payment_lines.pid_branch
        ), floating_credit AS (
         SELECT r.pid_sup_cust AS pid_supplier,
            r.pid_branch,
            sum(COALESCE(r.amount_tobe_paid, 0::numeric)) AS unapplied_credit
           FROM pos_transaction_mst r
          WHERE r.transaction_type::text = 'PR'::text
            AND r.status::text = 'A'::text
            AND r.pid_tran_mst_return IS NULL
            AND COALESCE(r.payment_type, 'ADJUSTMENT'::character varying)::text = 'ADJUSTMENT'::text
          GROUP BY r.pid_sup_cust, r.pid_branch
        ), pairs AS (
         SELECT purchases.pid_supplier,
            purchases.pid_branch
           FROM purchases
        UNION
         SELECT payments.pid_supplier,
            payments.pid_branch
           FROM payments
        UNION
         SELECT floating_credit.pid_supplier,
            floating_credit.pid_branch
           FROM floating_credit
        ), last_paid AS (
         SELECT DISTINCT ON (sp.pid_supplier, sp.pid_branch) sp.pid_supplier,
            sp.pid_branch,
            sp.payment_date AS last_payment_date,
            sp.amount AS last_payment_amount
           FROM pos_supplier_payment sp
          WHERE sp.status::text = 'Y'::text AND sp.entry_type::text = 'PAYMENT'::text
          ORDER BY sp.pid_supplier, sp.pid_branch, sp.payment_date DESC, sp.pid_payment DESC
        )
 SELECT s.pid_supplier,
    s.pid_org,
    x.pid_branch,
    s.supplier_name,
    s.mobile_no,
    s.responsible_person,
    s.credit_limit,
    COALESCE(p.invoices, 0::bigint) AS invoices,
    COALESCE(p.billed, 0::numeric) AS billed,
    COALESCE(l.cash_out, 0::numeric) AS paid,
    COALESCE(p.return_credit, 0::numeric) AS return_credit,
    COALESCE(l.on_account, 0::numeric) AS on_account,
    COALESCE(l.advance, 0::numeric) AS advance_balance,
    COALESCE(p.due, 0::numeric) - COALESCE(l.on_account, 0::numeric) - COALESCE(fc.unapplied_credit, 0::numeric) AS outstanding,
    COALESCE(p.due, 0::numeric) - COALESCE(l.on_account, 0::numeric) - COALESCE(fc.unapplied_credit, 0::numeric) - COALESCE(l.advance, 0::numeric) AS net_position,
    COALESCE(p.due_0_30, 0::numeric) AS due_0_30,
    COALESCE(p.due_31_60, 0::numeric) AS due_31_60,
    COALESCE(p.due_61_90, 0::numeric) AS due_61_90,
    COALESCE(p.due_90_plus, 0::numeric) AS due_90_plus,
    p.first_purchase,
    p.last_purchase,
    lp.last_payment_date,
    lp.last_payment_amount,
    COALESCE(fc.unapplied_credit, 0::numeric) AS unapplied_credit
   FROM pairs x
     JOIN pos_supplier_info s ON s.pid_supplier = x.pid_supplier
     LEFT JOIN purchases p ON p.pid_supplier = x.pid_supplier AND p.pid_branch = x.pid_branch
     LEFT JOIN payments l ON l.pid_supplier = x.pid_supplier AND l.pid_branch = x.pid_branch
     LEFT JOIN last_paid lp ON lp.pid_supplier = x.pid_supplier AND lp.pid_branch = x.pid_branch
     LEFT JOIN floating_credit fc ON fc.pid_supplier = x.pid_supplier AND fc.pid_branch = x.pid_branch;


/* The same treatment for the organisation-grained view. The Supplier Payment
   screen reads this one and the reports read the branch one; leaving either
   behind is how the payables screen and the outstanding report start disagreeing
   about the same supplier. */

CREATE OR REPLACE VIEW pharma.v_supplier_account AS
SELECT s.pid_supplier,
    s.pid_org,
    s.supplier_name,
    s.mobile_no,
    s.responsible_person,
    s.credit_limit,
    COALESCE(s.opening_bal, 0::numeric) AS opening_bal,
    COALESCE(p.invoices, 0::bigint) AS invoices,
    COALESCE(p.billed, 0::numeric) AS billed,
    COALESCE(l.cash_out, 0::numeric) AS paid,
    COALESCE(p.return_credit, 0::numeric) AS return_credit,
    COALESCE(l.on_account, 0::numeric) AS on_account,
    COALESCE(l.advance, 0::numeric) AS advance_balance,
    COALESCE(s.opening_bal, 0::numeric) + COALESCE(p.due, 0::numeric) - COALESCE(l.on_account, 0::numeric) - COALESCE(fc.unapplied_credit, 0::numeric) AS outstanding,
    COALESCE(s.opening_bal, 0::numeric) + COALESCE(p.due, 0::numeric) - COALESCE(l.on_account, 0::numeric) - COALESCE(fc.unapplied_credit, 0::numeric) - COALESCE(l.advance, 0::numeric) AS net_position,
    COALESCE(p.due_0_30, 0::numeric) AS due_0_30,
    COALESCE(p.due_31_60, 0::numeric) AS due_31_60,
    COALESCE(p.due_61_90, 0::numeric) AS due_61_90,
    COALESCE(p.due_90_plus, 0::numeric) AS due_90_plus,
    p.first_purchase,
    p.last_purchase,
    lp.last_payment_date,
    lp.last_payment_amount,
    COALESCE(fc.unapplied_credit, 0::numeric) AS unapplied_credit
   FROM pos_supplier_info s
     LEFT JOIN ( SELECT v_purchase_payable.pid_supplier,
            count(*) AS invoices,
            sum(v_purchase_payable.billed) AS billed,
            sum(v_purchase_payable.return_credit) AS return_credit,
            sum(v_purchase_payable.due) AS due,
            sum(
                CASE
                    WHEN v_purchase_payable.due > 0::numeric AND v_purchase_payable.age_days <= 30 THEN v_purchase_payable.due
                    ELSE 0::numeric
                END) AS due_0_30,
            sum(
                CASE
                    WHEN v_purchase_payable.due > 0::numeric AND v_purchase_payable.age_days >= 31 AND v_purchase_payable.age_days <= 60 THEN v_purchase_payable.due
                    ELSE 0::numeric
                END) AS due_31_60,
            sum(
                CASE
                    WHEN v_purchase_payable.due > 0::numeric AND v_purchase_payable.age_days >= 61 AND v_purchase_payable.age_days <= 90 THEN v_purchase_payable.due
                    ELSE 0::numeric
                END) AS due_61_90,
            sum(
                CASE
                    WHEN v_purchase_payable.due > 0::numeric AND v_purchase_payable.age_days > 90 THEN v_purchase_payable.due
                    ELSE 0::numeric
                END) AS due_90_plus,
            min(v_purchase_payable.transaction_date) AS first_purchase,
            max(v_purchase_payable.transaction_date) AS last_purchase
           FROM v_purchase_payable
          GROUP BY v_purchase_payable.pid_supplier) p ON p.pid_supplier = s.pid_supplier
     LEFT JOIN ( SELECT v_supplier_payment_line.pid_supplier,
            sum(v_supplier_payment_line.cash_out) AS cash_out,
            sum(v_supplier_payment_line.advance_delta) AS advance,
            sum(
                CASE
                    WHEN v_supplier_payment_line.entry_type::text = 'PAYMENT'::text AND v_supplier_payment_line.pid_tran_mst IS NULL THEN v_supplier_payment_line.applied
                    ELSE 0::numeric
                END) AS on_account
           FROM v_supplier_payment_line
          GROUP BY v_supplier_payment_line.pid_supplier) l ON l.pid_supplier = s.pid_supplier
     LEFT JOIN ( SELECT DISTINCT ON (pos_supplier_payment.pid_supplier) pos_supplier_payment.pid_supplier,
            pos_supplier_payment.payment_date AS last_payment_date,
            pos_supplier_payment.amount AS last_payment_amount
           FROM pos_supplier_payment
          WHERE pos_supplier_payment.status::text = 'Y'::text AND pos_supplier_payment.entry_type::text = 'PAYMENT'::text
          ORDER BY pos_supplier_payment.pid_supplier, pos_supplier_payment.payment_date DESC, pos_supplier_payment.pid_payment DESC) lp ON lp.pid_supplier = s.pid_supplier
     LEFT JOIN ( SELECT r.pid_sup_cust AS pid_supplier,
            sum(COALESCE(r.amount_tobe_paid, 0::numeric)) AS unapplied_credit
           FROM pos_transaction_mst r
          WHERE r.transaction_type::text = 'PR'::text
            AND r.status::text = 'A'::text
            AND r.pid_tran_mst_return IS NULL
            AND COALESCE(r.payment_type, 'ADJUSTMENT'::character varying)::text = 'ADJUSTMENT'::text
          GROUP BY r.pid_sup_cust) fc ON fc.pid_supplier = s.pid_supplier;
