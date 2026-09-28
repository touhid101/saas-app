-- ═══════════════════════════════════════════════════════════════════════════
-- V11 — Landed cost must be net of the invoice-level discount
--
-- POS_STOCK_BATCH.COST_PER_PCS was computed as the line total over the pieces
-- received. The line total carries the trade price, IGTA and the *line*
-- discount, but an invoice-level discount is applied to the header — so every
-- lot bought on a discounted invoice was valued above what was actually paid,
-- and every margin measured against it was understated by the same percentage.
--
-- Example, invoice PU260800026 at 2%:
--     stored   58.24 / 24 pcs = 2.426667
--     correct  (58.24 - 1.16) / 24 = 2.378333
--
-- The legacy VW_PRODUCT_STOCK apportioned it correctly:
--     (TOTAL_PRICE - TOTAL_PRICE * INVOICE_DIS_PCT / 100) / QUANTITY
--
-- This restates the stored costs. Quantities are untouched — only valuation
-- was wrong, never the stock itself.
-- ═══════════════════════════════════════════════════════════════════════════

-- ── Recompute every lot from its own approved purchase lines ───────────────
-- Grouped exactly as V9 grouped them, so a lot fed by several lines gets the
-- blended rate those lines actually add up to.

WITH net AS (
    SELECT  m.PID_BRANCH,
            d.PID_PRODUCT,
            COALESCE(NULLIF(TRIM(d.BATCH_NO), ''), 'LEGACY-' || d.PID_TRAN_MST) AS batch_no,
            SUM(d.QUANTITY * COALESCE(d.NO_OF_PCS, 1))                          AS pcs,
            SUM(COALESCE(d.TOTAL_PRICE, 0)
                - ROUND(COALESCE(d.TOTAL_PRICE, 0)
                        * COALESCE(m.INVOICE_DIS_PCT, 0) / 100, 2))             AS net_value
      FROM  PHARMA.POS_TRANSACTION_DTL d
      JOIN  PHARMA.POS_TRANSACTION_MST m ON m.PID_TRAN_MST = d.PID_TRAN_MST
     WHERE  m.TRANSACTION_TYPE = 'PU'
       AND  m.STATUS           = 'A'
       AND  m.PID_BRANCH IS NOT NULL
     GROUP BY m.PID_BRANCH, d.PID_PRODUCT,
              COALESCE(NULLIF(TRIM(d.BATCH_NO), ''), 'LEGACY-' || d.PID_TRAN_MST)
    HAVING SUM(d.QUANTITY * COALESCE(d.NO_OF_PCS, 1)) > 0
)
UPDATE PHARMA.POS_STOCK_BATCH b
   SET COST_PER_PCS = ROUND(net.net_value / net.pcs, 6),
       UPDATED_DATE = CURRENT_TIMESTAMP,
       UPDATED_BY   = 'V11_COST_FIX'
  FROM net
 WHERE b.PID_BRANCH  = net.PID_BRANCH
   AND b.PID_PRODUCT = net.PID_PRODUCT
   AND b.BATCH_NO    = net.batch_no
   AND b.COST_PER_PCS IS DISTINCT FROM ROUND(net.net_value / net.pcs, 6);


-- ── Bring the movement log into line ──────────────────────────────────────
-- Inbound purchase movements carry the cost of what arrived. Left stale they
-- would disagree with the lot they fed, and the movement log is meant to be
-- the side that can be trusted.

UPDATE PHARMA.POS_STOCK_MOVEMENT mv
   SET COST_PER_PCS = ROUND(
           (COALESCE(d.TOTAL_PRICE, 0)
            - ROUND(COALESCE(d.TOTAL_PRICE, 0) * COALESCE(m.INVOICE_DIS_PCT, 0) / 100, 2))
           / NULLIF(mv.QTY_PCS, 0), 6)
  FROM PHARMA.POS_TRANSACTION_DTL d
  JOIN PHARMA.POS_TRANSACTION_MST m ON m.PID_TRAN_MST = d.PID_TRAN_MST
 WHERE mv.REF_DTL       = d.PID_TRAN_DTL
   AND mv.MOVEMENT_TYPE = 'PURCHASE'
   AND mv.QTY_PCS       > 0;

-- Opening rows written by the V9 backfill have no REF_DTL, so they take the
-- lot's corrected rate directly.
UPDATE PHARMA.POS_STOCK_MOVEMENT mv
   SET COST_PER_PCS = b.COST_PER_PCS
  FROM PHARMA.POS_STOCK_BATCH b
 WHERE mv.PID_STOCK     = b.PID_STOCK
   AND mv.MOVEMENT_TYPE = 'OPENING';


-- ── Outbound movements keep the cost of the lot they consumed ─────────────
-- A sale is costed at what the lot it took from was worth. Those lots have
-- just been restated, so the sales costed against them move with it — leaving
-- them alone would report a margin against a price never paid.

UPDATE PHARMA.POS_STOCK_MOVEMENT mv
   SET COST_PER_PCS = b.COST_PER_PCS
  FROM PHARMA.POS_STOCK_BATCH b
 WHERE mv.PID_STOCK     = b.PID_STOCK
   AND mv.DIRECTION     = 'O'
   AND mv.COST_PER_PCS IS DISTINCT FROM b.COST_PER_PCS;


COMMENT ON COLUMN PHARMA.POS_STOCK_BATCH.COST_PER_PCS IS
    'Landed cost of one piece: trade price, IGTA, line discount and the line''s '
    'share of the invoice-level discount, over the pieces received. What the '
    'stock is valued at and what COGS is measured against. Not '
    'POS_PRODUCT_INFO.BUY_PRICE, which is a current list price.';
