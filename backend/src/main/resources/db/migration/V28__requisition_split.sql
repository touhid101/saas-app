-- ═══════════════════════════════════════════════════════════════════════════
-- Splitting a requisition into purchase orders.
--
-- A requisition answers "what are we short of" and a purchase order answers
-- "what are we buying, from whom". The first spans as many manufacturers as the
-- shortage does; the second is always one supplier. The step between them is
-- where a buyer decides, and it is the step this schema could not express.
--
-- POS_REQUISITION_MST.PID_PURCHASE_ORDER already existed, but it holds one id.
-- A requisition covering four manufacturers becomes four purchase orders, so
-- the link belongs on the line, which goes to exactly one of them. The master
-- column is kept and set only when the whole requisition went to a single
-- supplier — which is the common case and worth answering without a join.
-- ═══════════════════════════════════════════════════════════════════════════

ALTER TABLE PHARMA.POS_REQUISITION_DTL
    ADD COLUMN IF NOT EXISTS PID_PURCHASE_ORDER BIGINT;

CREATE INDEX IF NOT EXISTS IX_REQ_DTL_PURCHASE_ORDER
    ON PHARMA.POS_REQUISITION_DTL (PID_PURCHASE_ORDER)
 WHERE PID_PURCHASE_ORDER IS NOT NULL;

COMMENT ON COLUMN PHARMA.POS_REQUISITION_DTL.PID_PURCHASE_ORDER IS
    'The purchase order this line was placed on. Null until the requisition is '
    'split. One line goes to exactly one supplier, which is why the link lives '
    'here rather than only on the master.';

-- ── what a product was last bought on, and from whom ────────────────────────

-- The sourcing rule reads this: the supplier you last actually bought a product
-- from is the best evidence of who sells it, and it is already recorded on
-- every purchase. Rates are normalised to a per-box figure so a line entered in
-- pieces and one entered in boxes can be compared.
CREATE OR REPLACE VIEW PHARMA.V_PRODUCT_LAST_PURCHASE AS
SELECT DISTINCT ON (d.PID_PRODUCT, t.PID_ORG)
       d.PID_PRODUCT,
       t.PID_ORG,
       t.PID_SUP_CUST                         AS PID_SUPPLIER,
       s.SUPPLIER_NAME,
       t.TRANSACTION_DATE                     AS LAST_PURCHASED_ON,
       t.INVOICE_NO,
       d.SALES_TYPE,
       d.MNP_PRICE,
       d.NO_OF_PCS,
       -- Per piece, so the caller can scale it to whatever unit it needs.
       CASE WHEN COALESCE(d.NO_OF_PCS, 0) > 0
            THEN d.MNP_PRICE / d.NO_OF_PCS
            ELSE d.MNP_PRICE END              AS RATE_PER_PCS
  FROM PHARMA.POS_TRANSACTION_MST t
  JOIN PHARMA.POS_TRANSACTION_DTL d ON d.PID_TRAN_MST = t.PID_TRAN_MST
  JOIN PHARMA.POS_SUPPLIER_INFO   s ON s.PID_SUPPLIER = t.PID_SUP_CUST
 WHERE t.TRANSACTION_TYPE = 'PU'
   AND t.STATUS = 'A'                 -- received, not a draft somebody abandoned
   AND d.MNP_PRICE IS NOT NULL
 ORDER BY d.PID_PRODUCT, t.PID_ORG, t.TRANSACTION_DATE DESC, t.PID_TRAN_MST DESC;

COMMENT ON VIEW PHARMA.V_PRODUCT_LAST_PURCHASE IS
    'Most recent received purchase per product: who supplied it, when, and at '
    'what rate. Drafts are excluded — an abandoned draft is not evidence that '
    'anybody sells the thing.';
