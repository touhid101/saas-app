-- ═══════════════════════════════════════════════════════════════════════════
-- V25 — Every approved purchase's cash must exist in the supplier ledger
--
-- Three ways a row could be missing or wrong, all of them leaving money the
-- supplier owes back invisible on the screens built to chase it:
--
--   1. V23 backfilled the cash on approved purchases, but capped each row at
--      the payable amount. Any invoice where the gross was handed over had the
--      excess trimmed off before it was ever recorded.
--
--   2. V23 wrote every backfilled row with ADVANCE_AMOUNT = 0, because the
--      purchase had nowhere to say otherwise until V24 added the column.
--
--   3. A purchase approved by a build between those two migrations got no
--      mirror row at all.
--
-- This resyncs all three against the purchase, which is the record of what
-- actually happened at the counter. It is idempotent: run it again and it
-- changes nothing.
-- ═══════════════════════════════════════════════════════════════════════════

/* ── Correct rows that already exist but disagree with their invoice ─────── */

UPDATE PHARMA.POS_SUPPLIER_PAYMENT p
   SET AMOUNT         = COALESCE(t.GIVEN_AMOUNT, 0),
       ADVANCE_AMOUNT = LEAST(COALESCE(t.ADVANCE_AMOUNT, 0), COALESCE(t.GIVEN_AMOUNT, 0)),
       REMARKS        = CASE WHEN COALESCE(t.ADVANCE_AMOUNT, 0) > 0
                             THEN 'Paid with the invoice, including BDT '
                                  || TO_CHAR(t.ADVANCE_AMOUNT, 'FM999999990.00') || ' recoverable'
                             ELSE 'Paid with the invoice' END,
       UPDATED_BY     = 'V25-resync',
       UPDATED_DATE   = CURRENT_TIMESTAMP
  FROM PHARMA.POS_TRANSACTION_MST t
 WHERE t.PID_TRAN_MST = p.PID_TRAN_MST
   AND p.SOURCE = 'INVOICE'
   AND p.STATUS = 'Y'
   AND t.TRANSACTION_TYPE = 'PU'
   AND t.STATUS = 'A'
   AND COALESCE(t.GIVEN_AMOUNT, 0) > 0
   AND (p.AMOUNT <> COALESCE(t.GIVEN_AMOUNT, 0)
        OR p.ADVANCE_AMOUNT <> LEAST(COALESCE(t.ADVANCE_AMOUNT, 0), COALESCE(t.GIVEN_AMOUNT, 0)));

/* ── Write the rows that were never mirrored at all ──────────────────────── */

INSERT INTO PHARMA.POS_SUPPLIER_PAYMENT
       (PID_PAYMENT, PID_ORG, PID_COMPANY, PID_BRANCH, PID_SUPPLIER, PID_TRAN_MST,
        VOUCHER_NO, PAYMENT_DATE, AMOUNT, PAYMENT_MODE, ENTRY_TYPE, SOURCE,
        ADVANCE_AMOUNT, REMARKS, STATUS, INSERTED_BY)
SELECT nextval('PHARMA.SQ_SUPPLIER_PAYMENT'),
       t.PID_ORG, t.PID_COMPANY, t.PID_BRANCH, t.PID_SUP_CUST, t.PID_TRAN_MST,
       'INV-' || t.INVOICE_NO,
       t.TRANSACTION_DATE,
       t.GIVEN_AMOUNT,
       COALESCE(t.PAYMENT_TYPE, 'CASH'),
       'PAYMENT', 'INVOICE',
       LEAST(COALESCE(t.ADVANCE_AMOUNT, 0), t.GIVEN_AMOUNT),
       CASE WHEN COALESCE(t.ADVANCE_AMOUNT, 0) > 0
            THEN 'Paid with the invoice, including BDT '
                 || TO_CHAR(t.ADVANCE_AMOUNT, 'FM999999990.00') || ' recoverable'
            ELSE 'Paid with the invoice' END,
       'Y', 'V25-resync'
  FROM PHARMA.POS_TRANSACTION_MST t
 WHERE t.TRANSACTION_TYPE = 'PU'
   AND t.STATUS = 'A'
   AND COALESCE(t.GIVEN_AMOUNT, 0) > 0
   AND NOT EXISTS (
       SELECT 1 FROM PHARMA.POS_SUPPLIER_PAYMENT p
        WHERE p.PID_TRAN_MST = t.PID_TRAN_MST AND p.SOURCE = 'INVOICE');

/* ── What is left disagreeing, if anything ───────────────────────────────── */

-- A standing check rather than a one-off. Any row here means a purchase and the
-- ledger describe different money, which is the condition that hides a rebate.
CREATE OR REPLACE VIEW PHARMA.V_INVOICE_PAYMENT_MISMATCH AS
SELECT t.PID_TRAN_MST,
       t.PID_ORG,
       t.INVOICE_NO,
       t.TRANSACTION_DATE,
       t.PID_SUP_CUST                     AS PID_SUPPLIER,
       COALESCE(t.GIVEN_AMOUNT, 0)        AS GIVEN_ON_INVOICE,
       COALESCE(t.ADVANCE_AMOUNT, 0)      AS ADVANCE_ON_INVOICE,
       p.AMOUNT                           AS AMOUNT_IN_LEDGER,
       p.ADVANCE_AMOUNT                   AS ADVANCE_IN_LEDGER,
       CASE WHEN p.PID_PAYMENT IS NULL THEN 'No ledger row for cash paid with this invoice'
            WHEN p.AMOUNT <> COALESCE(t.GIVEN_AMOUNT, 0) THEN 'Ledger amount differs from the invoice'
            ELSE 'Ledger advance differs from the invoice' END AS PROBLEM
  FROM PHARMA.POS_TRANSACTION_MST t
  LEFT JOIN PHARMA.POS_SUPPLIER_PAYMENT p
         ON p.PID_TRAN_MST = t.PID_TRAN_MST AND p.SOURCE = 'INVOICE' AND p.STATUS = 'Y'
 WHERE t.TRANSACTION_TYPE = 'PU'
   AND t.STATUS = 'A'
   AND COALESCE(t.GIVEN_AMOUNT, 0) > 0
   AND (p.PID_PAYMENT IS NULL
        OR p.AMOUNT <> COALESCE(t.GIVEN_AMOUNT, 0)
        OR p.ADVANCE_AMOUNT <> LEAST(COALESCE(t.ADVANCE_AMOUNT, 0), COALESCE(t.GIVEN_AMOUNT, 0)));
