/* ═══════════════════════════════════════════════════════════════════════════
   V95 — posting rule for restating a supplier advance.

   "Pay the invoice discount too" on a purchase once carved the WHOLE discount
   (TP%, line discounts and the invoice cut) out of the cash as an advance the
   supplier owed back. Only the invoice discount is ever returned; the line
   discounts are the supplier's own price and were never charged. Purchases
   saved under the old reading carry too large an advance, and the mirrored
   supplier payment posted too much to SUPPLIER_ADVANCE and too little to AP.

   The repair (SupplierPaymentServiceImpl.restateInvoiceAdvances) corrects the
   purchase and its mirror row, and books the difference with this rule rather
   than editing the posted journal: the part that was booked as recoverable
   was in fact settling the bill.

       Dr AP                 DELTA   (the supplier is owed that much less)
       Cr SUPPLIER_ADVANCE   DELTA   (and holds that much less of ours)

   No cash line: what left the till was right; only its split was wrong.
   Rule rows are seeded the way V90 seeds them, so an organisation that has
   overridden the defaults is left alone.
   ═══════════════════════════════════════════════════════════════════════════ */

INSERT INTO pharma.acc_rule (pid_rule, pid_org, event_type, rule_name, description, is_active, status, inserted_by, inserted_date)
SELECT nextval('pharma.seq_acc_rule'), NULL, 'SUPPLIER_ADVANCE_RESTATED', 'Supplier advance restated',
       'Part of an invoice-time advance was really settling the bill; moved from the advance to the payable', 1, 'Y', 'V95', now()
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acc_rule r WHERE r.pid_org IS NULL AND r.event_type = 'SUPPLIER_ADVANCE_RESTATED');

INSERT INTO pharma.acc_rule_line (pid_rule_line, pid_rule, line_no, side, account_role, amount_key, party_type, description, status)
SELECT nextval('pharma.seq_acc_rule_line'), r.pid_rule, v.n, v.s, v.a, v.k, v.p, v.d, 'Y'
  FROM (VALUES
    ('SUPPLIER_ADVANCE_RESTATED', 1, 'D', 'AP',               'DELTA', 'SUPPLIER', 'Settled the bill after all'),
    ('SUPPLIER_ADVANCE_RESTATED', 2, 'C', 'SUPPLIER_ADVANCE', 'DELTA', 'SUPPLIER', 'No longer held by the supplier')
  ) AS v(t, n, s, a, k, p, d)
  JOIN pharma.acc_rule r ON r.pid_org IS NULL AND r.event_type = v.t
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acc_rule_line l WHERE l.pid_rule = r.pid_rule AND l.line_no = v.n);
