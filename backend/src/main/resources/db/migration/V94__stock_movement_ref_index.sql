/* ─────────────────────────────────────────────────────────────────────────────
   Stock movements by the document that posted them.

   Accounts reads every SALE / SALES_RETURN movement of an invoice to cost the
   goods (Σ qty × cost per piece), and StockPostingService reads the same rows
   to reverse a document. Neither had an index to read by: with 400,000
   movements each lookup was a sequential scan, which made a sale slower to
   book and the historical backfill run at eight documents a second.

   One covering index. Nothing else changes.
   ───────────────────────────────────────────────────────────────────────────── */
CREATE INDEX IF NOT EXISTS ix_stock_movement_ref_mst
    ON pharma.pos_stock_movement (ref_type, ref_mst, movement_type);
