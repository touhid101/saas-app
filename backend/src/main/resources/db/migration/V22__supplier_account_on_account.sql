-- ═══════════════════════════════════════════════════════════════════════════
-- V22 — On-account payments must reduce what a supplier is owed
--
-- V_SUPPLIER_ACCOUNT rolled up from V_PURCHASE_PAYABLE, which only sees
-- payments tied to an invoice. A payment made on account — money against the
-- relationship rather than one bill — therefore appeared in the running
-- statement but not in OUTSTANDING. The two disagreed by the amount paid, which
-- is the precise failure the shared view exists to make impossible.
--
-- Unapplied money is exposed as its own column rather than being spread across
-- the ageing buckets. Deciding which bill an unallocated cheque paid is an
-- allocation nobody made, and inventing one here would report an old invoice as
-- settled on no evidence. So the buckets stay strictly invoice-level and the
-- arithmetic reconciles in the open:
--
--     OUTSTANDING = OPENING_BAL + (0-30 + 31-60 + 61-90 + 90+) - ON_ACCOUNT
-- ═══════════════════════════════════════════════════════════════════════════

-- Dropped rather than replaced: ON_ACCOUNT sits between existing columns, and
-- CREATE OR REPLACE cannot insert one in the middle. Nothing else reads this
-- view, so recreating it is safe.
DROP VIEW IF EXISTS PHARMA.V_SUPPLIER_ACCOUNT;

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
       COALESCE(p.PAID_AT_INVOICE, 0) + COALESCE(p.PAID_LATER, 0)
                                      + COALESCE(oa.ON_ACCOUNT, 0)      AS PAID,
       COALESCE(p.RETURN_CREDIT, 0)                                     AS RETURN_CREDIT,
       /* Money paid but not attached to any bill. */
       COALESCE(oa.ON_ACCOUNT, 0)                                       AS ON_ACCOUNT,
       COALESCE(s.OPENING_BAL, 0) + COALESCE(p.DUE, 0)
                                  - COALESCE(oa.ON_ACCOUNT, 0)          AS OUTSTANDING,
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
        SELECT PID_SUPPLIER, SUM(AMOUNT) AS ON_ACCOUNT
          FROM PHARMA.POS_SUPPLIER_PAYMENT
         WHERE STATUS = 'Y' AND PID_TRAN_MST IS NULL
         GROUP BY PID_SUPPLIER
  ) oa ON oa.PID_SUPPLIER = s.PID_SUPPLIER
  LEFT JOIN (
        SELECT DISTINCT ON (PID_SUPPLIER)
               PID_SUPPLIER, PAYMENT_DATE AS LAST_PAYMENT_DATE, AMOUNT AS LAST_PAYMENT_AMOUNT
          FROM PHARMA.POS_SUPPLIER_PAYMENT
         WHERE STATUS = 'Y'
         ORDER BY PID_SUPPLIER, PAYMENT_DATE DESC, PID_PAYMENT DESC
  ) lp ON lp.PID_SUPPLIER = s.PID_SUPPLIER;
