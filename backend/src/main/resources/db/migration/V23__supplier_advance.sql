-- ═══════════════════════════════════════════════════════════════════════════
-- V23 — Advances to suppliers, and one home for every payment
--
-- Two things this fixes, both raised from how the business actually pays.
--
-- 1. PAYING MORE THAN THE BILL.
--    On a discounted invoice the pharmacy sometimes pays the gross — invoice
--    plus the discount — with the supplier returning the discount later as a
--    rebate. That extra is not a payment: it is money the SUPPLIER now owes US.
--    Nothing in the schema could hold a receivable from a supplier, so the only
--    way to record it was to overpay an invoice, which the payment guard quite
--    correctly refuses, or to leave it off the books entirely.
--
--    An advance is therefore split out of the payment that carries it:
--    ADVANCE_AMOUNT is the part not settling any bill, and it is recovered by
--    an ADVANCE_RECOVERY entry when the supplier pays it back or credits it
--    against a later invoice.
--
-- 2. CASH PAID AT THE INVOICE LIVED OUTSIDE THE LEDGER.
--    GIVEN_AMOUNT sat on POS_TRANSACTION_MST alone, so "everything we paid this
--    supplier" could not be answered from the payment table. It is backfilled
--    here and written by the approval path from now on, and the payable view
--    stops reading GIVEN_AMOUNT so the same money is never counted twice.
-- ═══════════════════════════════════════════════════════════════════════════

/* ── Ledger columns ──────────────────────────────────────────────────────── */

ALTER TABLE PHARMA.POS_SUPPLIER_PAYMENT
    ADD COLUMN IF NOT EXISTS ENTRY_TYPE     VARCHAR(20)   NOT NULL DEFAULT 'PAYMENT',
    -- MANUAL: entered on the payment screen.
    -- INVOICE: the cash recorded on the purchase itself, mirrored here on
    -- approval so one table answers "what have we paid this supplier".
    ADD COLUMN IF NOT EXISTS SOURCE         VARCHAR(20)   NOT NULL DEFAULT 'MANUAL',
    -- On a PAYMENT: the slice that settles no bill and is recoverable.
    -- On an ADVANCE_RECOVERY: unused — the whole AMOUNT is the recovery.
    ADD COLUMN IF NOT EXISTS ADVANCE_AMOUNT NUMERIC(14,4) NOT NULL DEFAULT 0;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_sup_pay_entry_type') THEN
        ALTER TABLE PHARMA.POS_SUPPLIER_PAYMENT
            ADD CONSTRAINT CK_SUP_PAY_ENTRY_TYPE CHECK (ENTRY_TYPE IN ('PAYMENT','ADVANCE_RECOVERY'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_sup_pay_source') THEN
        ALTER TABLE PHARMA.POS_SUPPLIER_PAYMENT
            ADD CONSTRAINT CK_SUP_PAY_SOURCE CHECK (SOURCE IN ('MANUAL','INVOICE'));
    END IF;
    -- An advance is carved out of the payment, so it can never exceed it.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_sup_pay_advance') THEN
        ALTER TABLE PHARMA.POS_SUPPLIER_PAYMENT
            ADD CONSTRAINT CK_SUP_PAY_ADVANCE
            CHECK (ADVANCE_AMOUNT >= 0 AND ADVANCE_AMOUNT <= AMOUNT);
    END IF;
    -- A recovery carries no advance of its own; the whole amount reduces one.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_sup_pay_recovery') THEN
        ALTER TABLE PHARMA.POS_SUPPLIER_PAYMENT
            ADD CONSTRAINT CK_SUP_PAY_RECOVERY
            CHECK (ENTRY_TYPE <> 'ADVANCE_RECOVERY' OR ADVANCE_AMOUNT = 0);
    END IF;
END $$;

-- One mirrored at-invoice row per purchase. Without this a re-approval or a
-- re-run of the backfill would double the cash and halve the debt.
CREATE UNIQUE INDEX IF NOT EXISTS UX_SUP_PAY_INVOICE_SOURCE
    ON PHARMA.POS_SUPPLIER_PAYMENT (PID_TRAN_MST)
 WHERE SOURCE = 'INVOICE';

CREATE INDEX IF NOT EXISTS IX_SUP_PAY_ADVANCE
    ON PHARMA.POS_SUPPLIER_PAYMENT (PID_SUPPLIER)
 WHERE STATUS = 'Y' AND (ADVANCE_AMOUNT > 0 OR ENTRY_TYPE = 'ADVANCE_RECOVERY');

/* ── Backfill the cash already recorded on approved purchases ────────────── */

-- Approved only, matching V_PURCHASE_PAYABLE. Cash keyed onto a draft is an
-- intention against a liability that does not exist yet; it is mirrored when
-- and if the purchase is approved.
INSERT INTO PHARMA.POS_SUPPLIER_PAYMENT
       (PID_PAYMENT, PID_ORG, PID_COMPANY, PID_BRANCH, PID_SUPPLIER, PID_TRAN_MST,
        VOUCHER_NO, PAYMENT_DATE, AMOUNT, PAYMENT_MODE, ENTRY_TYPE, SOURCE,
        ADVANCE_AMOUNT, REMARKS, STATUS, INSERTED_BY)
SELECT nextval('PHARMA.SQ_SUPPLIER_PAYMENT'),
       t.PID_ORG, t.PID_COMPANY, t.PID_BRANCH, t.PID_SUP_CUST, t.PID_TRAN_MST,
       -- Derived from the invoice it mirrors rather than drawn from the number
       -- series: this is not a voucher anyone wrote, and giving it one would
       -- imply a document that does not exist.
       'INV-' || t.INVOICE_NO,
       t.TRANSACTION_DATE,
       LEAST(t.GIVEN_AMOUNT, COALESCE(t.AMOUNT_TOBE_PAID, 0)),
       COALESCE(t.PAYMENT_TYPE, 'CASH'),
       'PAYMENT', 'INVOICE', 0,
       'Paid with the invoice',
       'Y', 'V23-backfill'
  FROM PHARMA.POS_TRANSACTION_MST t
 WHERE t.TRANSACTION_TYPE = 'PU'
   AND t.STATUS = 'A'
   AND COALESCE(t.GIVEN_AMOUNT, 0) > 0
   AND NOT EXISTS (
       SELECT 1 FROM PHARMA.POS_SUPPLIER_PAYMENT p
        WHERE p.PID_TRAN_MST = t.PID_TRAN_MST AND p.SOURCE = 'INVOICE');

/* ── What each ledger row actually does ──────────────────────────────────── */

-- The rules for reading a ledger row, stated once. Every view below builds on
-- this rather than repeating the CASE expressions — the last round of bugs in
-- this area were all separate copies of the same arithmetic disagreeing.
CREATE OR REPLACE VIEW PHARMA.V_SUPPLIER_PAYMENT_LINE AS
SELECT p.PID_PAYMENT,
       p.PID_ORG,
       p.PID_BRANCH,
       p.PID_SUPPLIER,
       p.PID_TRAN_MST,
       p.VOUCHER_NO,
       p.PAYMENT_DATE,
       p.AMOUNT,
       p.PAYMENT_MODE,
       p.ENTRY_TYPE,
       p.SOURCE,
       p.ADVANCE_AMOUNT,
       p.REMARKS,
       /* What this row puts against a bill. A payment settles its non-advance
          part; a recovery settles a bill only when it was taken as a credit
          note against one rather than returned in money. */
       CASE WHEN p.ENTRY_TYPE = 'PAYMENT'      THEN p.AMOUNT - p.ADVANCE_AMOUNT
            WHEN p.PID_TRAN_MST IS NOT NULL    THEN p.AMOUNT
            ELSE 0 END                                   AS APPLIED,
       /* How this row moves the balance the supplier owes us. */
       CASE WHEN p.ENTRY_TYPE = 'PAYMENT'      THEN p.ADVANCE_AMOUNT
            ELSE -p.AMOUNT END                           AS ADVANCE_DELTA,
       /* Cash direction, for a payment register that has to tie to a bank. */
       CASE WHEN p.ENTRY_TYPE = 'PAYMENT'      THEN p.AMOUNT
            ELSE -p.AMOUNT END                           AS CASH_OUT
  FROM PHARMA.POS_SUPPLIER_PAYMENT p
 WHERE p.STATUS = 'Y';

/* ── What each approved purchase still owes ──────────────────────────────── */

-- GIVEN_AMOUNT is deliberately gone from this view. It now lives in the ledger
-- as a SOURCE='INVOICE' row, and reading both would count the same cash twice.
-- Dropped rather than replaced: INVOICE_DIS_AMT is a new column in the middle
-- of the list, which CREATE OR REPLACE cannot do. V_SUPPLIER_ACCOUNT reads this
-- view, so it goes first and is rebuilt below.
DROP VIEW IF EXISTS PHARMA.V_SUPPLIER_ACCOUNT;
DROP VIEW IF EXISTS PHARMA.V_PURCHASE_PAYABLE;

CREATE VIEW PHARMA.V_PURCHASE_PAYABLE AS
SELECT t.PID_TRAN_MST,
       t.PID_ORG,
       t.PID_BRANCH,
       t.PID_SUP_CUST                                   AS PID_SUPPLIER,
       t.INVOICE_NO,
       t.TRANSACTION_DATE,
       t.PAYMENT_TYPE,
       COALESCE(t.AMOUNT_TOBE_PAID, 0)                  AS BILLED,
       COALESCE(t.INVOICE_DIS_AMT, 0)                   AS INVOICE_DIS_AMT,
       COALESCE(inv.PAID, 0)                            AS PAID_AT_INVOICE,
       COALESCE(later.PAID, 0)                          AS PAID_LATER,
       COALESCE(cr.CREDITED, 0)                         AS RETURN_CREDIT,
       COALESCE(t.AMOUNT_TOBE_PAID, 0)
         - COALESCE(inv.PAID, 0)
         - COALESCE(later.PAID, 0)
         - COALESCE(cr.CREDITED, 0)                     AS DUE,
       CURRENT_DATE - t.TRANSACTION_DATE                AS AGE_DAYS
  FROM PHARMA.POS_TRANSACTION_MST t
  LEFT JOIN (
        SELECT PID_TRAN_MST, SUM(APPLIED) AS PAID
          FROM PHARMA.V_SUPPLIER_PAYMENT_LINE
         WHERE SOURCE = 'INVOICE' AND PID_TRAN_MST IS NOT NULL
         GROUP BY PID_TRAN_MST
  ) inv ON inv.PID_TRAN_MST = t.PID_TRAN_MST
  LEFT JOIN (
        SELECT PID_TRAN_MST, SUM(APPLIED) AS PAID
          FROM PHARMA.V_SUPPLIER_PAYMENT_LINE
         WHERE SOURCE = 'MANUAL' AND PID_TRAN_MST IS NOT NULL
         GROUP BY PID_TRAN_MST
  ) later ON later.PID_TRAN_MST = t.PID_TRAN_MST
  LEFT JOIN (
        SELECT r.PID_TRAN_MST_RETURN AS PID_TRAN_MST, SUM(COALESCE(r.AMOUNT_TOBE_PAID,0)) AS CREDITED
          FROM PHARMA.POS_TRANSACTION_MST r
         WHERE r.TRANSACTION_TYPE = 'PR'
           AND r.STATUS = 'A'
           AND COALESCE(r.PAYMENT_TYPE,'ADJUSTMENT') = 'ADJUSTMENT'
           AND r.PID_TRAN_MST_RETURN IS NOT NULL
         GROUP BY r.PID_TRAN_MST_RETURN
  ) cr ON cr.PID_TRAN_MST = t.PID_TRAN_MST
 WHERE t.TRANSACTION_TYPE = 'PU'
   AND t.STATUS = 'A';

/* ── The supplier's account, both directions ─────────────────────────────── */

CREATE VIEW PHARMA.V_SUPPLIER_ACCOUNT AS
SELECT s.PID_SUPPLIER,
       s.PID_ORG,
       s.SUPPLIER_NAME,
       s.MOBILE_NO,
       s.RESPONSIBLE_PERSON,
       s.CREDIT_LIMIT,
       COALESCE(s.OPENING_BAL, 0)                                       AS OPENING_BAL,
       COALESCE(p.INVOICES, 0)                                          AS INVOICES,
       COALESCE(p.BILLED, 0)                                            AS BILLED,
       /* Every taka that left, however it was recorded. */
       COALESCE(l.CASH_OUT, 0)                                          AS PAID,
       COALESCE(p.RETURN_CREDIT, 0)                                     AS RETURN_CREDIT,
       /* Paid, meant for bills, not yet matched to one. */
       COALESCE(l.ON_ACCOUNT, 0)                                        AS ON_ACCOUNT,
       /* Paid beyond the bills — the supplier owes this back. */
       COALESCE(l.ADVANCE, 0)                                           AS ADVANCE_BALANCE,
       COALESCE(s.OPENING_BAL, 0) + COALESCE(p.DUE, 0)
                                  - COALESCE(l.ON_ACCOUNT, 0)           AS OUTSTANDING,
       /* What the relationship is worth on net. Positive: we owe them.
          Negative: they owe us, which is what an unrecovered advance means. */
       COALESCE(s.OPENING_BAL, 0) + COALESCE(p.DUE, 0)
                                  - COALESCE(l.ON_ACCOUNT, 0)
                                  - COALESCE(l.ADVANCE, 0)              AS NET_POSITION,
       COALESCE(p.DUE_0_30, 0)   AS DUE_0_30,
       COALESCE(p.DUE_31_60, 0)  AS DUE_31_60,
       COALESCE(p.DUE_61_90, 0)  AS DUE_61_90,
       COALESCE(p.DUE_90_PLUS,0) AS DUE_90_PLUS,
       p.FIRST_PURCHASE,
       p.LAST_PURCHASE,
       lp.LAST_PAYMENT_DATE,
       lp.LAST_PAYMENT_AMOUNT
  FROM PHARMA.POS_SUPPLIER_INFO s
  LEFT JOIN (
        SELECT PID_SUPPLIER,
               COUNT(*)              AS INVOICES,
               SUM(BILLED)           AS BILLED,
               SUM(RETURN_CREDIT)    AS RETURN_CREDIT,
               SUM(DUE)              AS DUE,
               SUM(CASE WHEN DUE > 0 AND AGE_DAYS <= 30             THEN DUE ELSE 0 END) AS DUE_0_30,
               SUM(CASE WHEN DUE > 0 AND AGE_DAYS BETWEEN 31 AND 60 THEN DUE ELSE 0 END) AS DUE_31_60,
               SUM(CASE WHEN DUE > 0 AND AGE_DAYS BETWEEN 61 AND 90 THEN DUE ELSE 0 END) AS DUE_61_90,
               SUM(CASE WHEN DUE > 0 AND AGE_DAYS > 90              THEN DUE ELSE 0 END) AS DUE_90_PLUS,
               MIN(TRANSACTION_DATE) AS FIRST_PURCHASE,
               MAX(TRANSACTION_DATE) AS LAST_PURCHASE
          FROM PHARMA.V_PURCHASE_PAYABLE
         GROUP BY PID_SUPPLIER
  ) p ON p.PID_SUPPLIER = s.PID_SUPPLIER
  LEFT JOIN (
        SELECT PID_SUPPLIER,
               SUM(CASH_OUT)       AS CASH_OUT,
               SUM(ADVANCE_DELTA)  AS ADVANCE,
               SUM(CASE WHEN ENTRY_TYPE = 'PAYMENT' AND PID_TRAN_MST IS NULL
                        THEN APPLIED ELSE 0 END) AS ON_ACCOUNT
          FROM PHARMA.V_SUPPLIER_PAYMENT_LINE
         GROUP BY PID_SUPPLIER
  ) l ON l.PID_SUPPLIER = s.PID_SUPPLIER
  LEFT JOIN (
        SELECT DISTINCT ON (PID_SUPPLIER)
               PID_SUPPLIER, PAYMENT_DATE AS LAST_PAYMENT_DATE, AMOUNT AS LAST_PAYMENT_AMOUNT
          FROM PHARMA.POS_SUPPLIER_PAYMENT
         WHERE STATUS = 'Y' AND ENTRY_TYPE = 'PAYMENT'
         ORDER BY PID_SUPPLIER, PAYMENT_DATE DESC, PID_PAYMENT DESC
  ) lp ON lp.PID_SUPPLIER = s.PID_SUPPLIER;
