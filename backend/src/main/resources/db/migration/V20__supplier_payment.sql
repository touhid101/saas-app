-- ═══════════════════════════════════════════════════════════════════════════
-- V20 — Supplier payments, and one definition of what a supplier is owed
--
-- Until now the only record of paying a supplier was POS_TRANSACTION_MST.
-- GIVEN_AMOUNT — the cash handed over at the moment the invoice was keyed in.
-- There was nowhere to record a payment made afterwards, which is how almost
-- every supplier is actually paid. Outstanding could therefore only ever go up.
--
-- Three things follow from that, and this migration addresses all three:
--
--   1. POS_SUPPLIER_PAYMENT — a real payment ledger, so money paid after the
--      invoice has somewhere to live.
--
--   2. V_PURCHASE_PAYABLE — one row per APPROVED purchase with billed, paid
--      and due. The reports currently each do this arithmetic themselves, and
--      each of them counts draft and pending purchases as money owed. A view
--      means the payment screen and every report read the same number.
--
--   3. V_SUPPLIER_ACCOUNT — the roll-up, including the opening balance and
--      credits from purchase returns, which nothing has ever netted off.
-- ═══════════════════════════════════════════════════════════════════════════

/* ── Ledger ──────────────────────────────────────────────────────────────── */

CREATE SEQUENCE IF NOT EXISTS PHARMA.SQ_SUPPLIER_PAYMENT START WITH 1 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS PHARMA.POS_SUPPLIER_PAYMENT (
    PID_PAYMENT      BIGINT         NOT NULL,
    PID_ORG          BIGINT         NOT NULL,
    PID_COMPANY      BIGINT,
    PID_BRANCH       BIGINT         NOT NULL,

    PID_SUPPLIER     BIGINT         NOT NULL,
    -- Null means paid on account: money against the supplier's overall balance
    -- rather than one invoice. Real payments are often a single cheque covering
    -- several bills, and forcing a line-by-line split invents detail nobody has.
    PID_TRAN_MST     BIGINT,

    VOUCHER_NO       VARCHAR(50)    NOT NULL,
    PAYMENT_DATE     DATE           NOT NULL,
    AMOUNT           NUMERIC(14,4)  NOT NULL,
    PAYMENT_MODE     VARCHAR(30)    NOT NULL,

    BANK_NAME        VARCHAR(100),
    CHEQUE_NO        VARCHAR(50),
    CHEQUE_DATE      DATE,
    REFERENCE_NO     VARCHAR(100),
    REMARKS          VARCHAR(500),

    -- 'Y' live, 'C' cancelled. A payment is never deleted: it was money leaving
    -- the business, and the reason it was reversed is part of the record.
    STATUS           VARCHAR(1)     NOT NULL DEFAULT 'Y',
    CANCEL_REASON    VARCHAR(255),

    INSERTED_BY      VARCHAR(50),
    INSERTED_DATE    TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP,
    UPDATED_BY       VARCHAR(50),
    UPDATED_DATE     TIMESTAMP(6),

    CONSTRAINT PK_SUPPLIER_PAYMENT   PRIMARY KEY (PID_PAYMENT),
    CONSTRAINT UX_SUPPLIER_PAYMENT_VOUCHER UNIQUE (VOUCHER_NO),
    CONSTRAINT CK_SUPPLIER_PAYMENT_AMT     CHECK (AMOUNT > 0),
    CONSTRAINT CK_SUPPLIER_PAYMENT_STATUS  CHECK (STATUS IN ('Y','C')),
    CONSTRAINT FK_SUPPLIER_PAYMENT_SUP
        FOREIGN KEY (PID_SUPPLIER) REFERENCES PHARMA.POS_SUPPLIER_INFO (PID_SUPPLIER),
    CONSTRAINT FK_SUPPLIER_PAYMENT_TRAN
        FOREIGN KEY (PID_TRAN_MST) REFERENCES PHARMA.POS_TRANSACTION_MST (PID_TRAN_MST)
);

CREATE INDEX IF NOT EXISTS IX_SUP_PAY_SUPPLIER ON PHARMA.POS_SUPPLIER_PAYMENT (PID_SUPPLIER, PAYMENT_DATE DESC);
CREATE INDEX IF NOT EXISTS IX_SUP_PAY_TRAN     ON PHARMA.POS_SUPPLIER_PAYMENT (PID_TRAN_MST) WHERE PID_TRAN_MST IS NOT NULL;
CREATE INDEX IF NOT EXISTS IX_SUP_PAY_DATE     ON PHARMA.POS_SUPPLIER_PAYMENT (PID_ORG, PAYMENT_DATE DESC);
CREATE INDEX IF NOT EXISTS IX_SUP_PAY_LIVE     ON PHARMA.POS_SUPPLIER_PAYMENT (PID_ORG, STATUS) WHERE STATUS = 'Y';

/* ── What each approved purchase still owes ──────────────────────────────── */

-- APPROVED ONLY, and this is the correction that matters most. A draft purchase
-- has received no stock and created no liability; a pending one has not been
-- agreed. Every purchase report today counts both as money owed, which in this
-- database inflates outstanding by several thousand taka and reports cash paid
-- against an invoice that was never approved.
CREATE OR REPLACE VIEW PHARMA.V_PURCHASE_PAYABLE AS
SELECT t.PID_TRAN_MST,
       t.PID_ORG,
       t.PID_BRANCH,
       t.PID_SUP_CUST                                   AS PID_SUPPLIER,
       t.INVOICE_NO,
       t.TRANSACTION_DATE,
       t.PAYMENT_TYPE,
       COALESCE(t.AMOUNT_TOBE_PAID, 0)                  AS BILLED,
       -- Capped: GIVEN_AMOUNT is what was handed over, and a figure above the
       -- bill is a keying error, not a prepayment to carry forward.
       LEAST(COALESCE(t.GIVEN_AMOUNT, 0),
             COALESCE(t.AMOUNT_TOBE_PAID, 0))           AS PAID_AT_INVOICE,
       COALESCE(pay.PAID, 0)                            AS PAID_LATER,
       COALESCE(cr.CREDITED, 0)                         AS RETURN_CREDIT,
       COALESCE(t.AMOUNT_TOBE_PAID, 0)
         - LEAST(COALESCE(t.GIVEN_AMOUNT,0), COALESCE(t.AMOUNT_TOBE_PAID,0))
         - COALESCE(pay.PAID, 0)
         - COALESCE(cr.CREDITED, 0)                     AS DUE,
       CURRENT_DATE - t.TRANSACTION_DATE                AS AGE_DAYS
  FROM PHARMA.POS_TRANSACTION_MST t
  LEFT JOIN (
        SELECT PID_TRAN_MST, SUM(AMOUNT) AS PAID
          FROM PHARMA.POS_SUPPLIER_PAYMENT
         WHERE STATUS = 'Y' AND PID_TRAN_MST IS NOT NULL
         GROUP BY PID_TRAN_MST
  ) pay ON pay.PID_TRAN_MST = t.PID_TRAN_MST
  LEFT JOIN (
        -- Only ADJUSTMENT credits reduce the bill. A return refunded in CASH is
        -- money coming back, not a smaller invoice, and a REPLACEMENT moves
        -- goods without touching what is owed. Treating all three the same is
        -- how a paid-up supplier ends up looking underpaid.
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

/* ── The supplier's account ──────────────────────────────────────────────── */

CREATE OR REPLACE VIEW PHARMA.V_SUPPLIER_ACCOUNT AS
SELECT s.PID_SUPPLIER,
       s.PID_ORG,
       s.SUPPLIER_NAME,
       s.MOBILE_NO,
       s.RESPONSIBLE_PERSON,
       s.CREDIT_LIMIT,
       COALESCE(s.OPENING_BAL, 0)                                       AS OPENING_BAL,
       COALESCE(p.INVOICES, 0)                                          AS INVOICES,
       COALESCE(p.BILLED, 0)                                            AS BILLED,
       COALESCE(p.PAID_AT_INVOICE, 0) + COALESCE(p.PAID_LATER, 0)       AS PAID,
       COALESCE(p.RETURN_CREDIT, 0)                                     AS RETURN_CREDIT,
       COALESCE(s.OPENING_BAL, 0) + COALESCE(p.DUE, 0)                  AS OUTSTANDING,
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
               SUM(PAID_AT_INVOICE)  AS PAID_AT_INVOICE,
               SUM(PAID_LATER)       AS PAID_LATER,
               SUM(RETURN_CREDIT)    AS RETURN_CREDIT,
               SUM(DUE)              AS DUE,
               -- Aged on the invoice date. Nothing in this schema records
               -- agreed credit terms, so the buckets count days since the bill
               -- rather than days past a due date.
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
        SELECT DISTINCT ON (PID_SUPPLIER)
               PID_SUPPLIER, PAYMENT_DATE AS LAST_PAYMENT_DATE, AMOUNT AS LAST_PAYMENT_AMOUNT
          FROM PHARMA.POS_SUPPLIER_PAYMENT
         WHERE STATUS = 'Y'
         ORDER BY PID_SUPPLIER, PAYMENT_DATE DESC, PID_PAYMENT DESC
  ) lp ON lp.PID_SUPPLIER = s.PID_SUPPLIER;
