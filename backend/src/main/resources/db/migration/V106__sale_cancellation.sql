/* ─────────────────────────────────────────────────────────────────────────────
   V106 — Record why a sale was cancelled, by whom, and when.

   Cancelling a sale was never implemented: POST /api/sales-activity/{id}/cancel
   called advanceStage("Cancelled"), and "Cancelled" is not one of the stages,
   so the call threw and the stock a mistaken invoice was holding stayed off the
   shelf until somebody raised a sales return. The real cancellation — reverse
   the stock posting, reverse the journals, mark the document — needs somewhere
   to keep the three facts an auditor asks for afterwards.

   Mirrors the RECEIVED_/DELIVERED_/PAID_BY+DATE pairs already on this table
   rather than inventing a shape. REMARKS is left alone: it holds what the
   counter typed when the sale was made, and overwriting it would destroy the
   record of the sale in the act of recording the cancellation.

   Nullable, no default, no backfill. Every existing row is a sale that was not
   cancelled, and stays exactly as it is.
   ───────────────────────────────────────────────────────────────────────────── */

ALTER TABLE pharma.pos_transaction_mst
    ADD COLUMN IF NOT EXISTS cancelled_by   VARCHAR(50),
    ADD COLUMN IF NOT EXISTS cancelled_date TIMESTAMP,
    ADD COLUMN IF NOT EXISTS cancel_reason  VARCHAR(300);

COMMENT ON COLUMN pharma.pos_transaction_mst.cancelled_by IS
  'Who cancelled the document: a staff username, or the e-commerce customer who called their own order off.';
COMMENT ON COLUMN pharma.pos_transaction_mst.cancelled_date IS
  'When it was cancelled. STAGE is then Cancelled and STATUS is N; the stock reversal and the journal reversal carry the same timestamp.';
COMMENT ON COLUMN pharma.pos_transaction_mst.cancel_reason IS
  'Why, in the words of whoever cancelled it. Shown on Sales Activity and on the customer''s order.';

/* A cancelled sale is STATUS = 'N'. The dashboards already read that as void
   (COALESCE(STATUS,'Y') <> 'N'); this index keeps the "what was cancelled"
   question off a sequential scan once the column is actually used. */
CREATE INDEX IF NOT EXISTS ix_tran_mst_cancelled
    ON pharma.pos_transaction_mst (pid_org, pid_branch, cancelled_date DESC)
 WHERE cancelled_date IS NOT NULL;

SELECT count(*) AS already_cancelled
  FROM pharma.pos_transaction_mst
 WHERE cancelled_date IS NOT NULL;
