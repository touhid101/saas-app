/* ─────────────────────────────────────────────────────────────────────────────
   Supplier bonus quantity — free units that arrive with a purchase line.

   "Buy 10 boxes, get 12" is how a great deal of pharmacy stock is actually
   bought, and the system had nowhere to put the 2. The choices open to a
   storekeeper were both wrong: key 10 and the shelf is short by two boxes that
   are physically there, or key 12 and the supplier is billed for two boxes
   nobody agreed to pay for. Most keyed 12 and corrected the price by hand,
   which put a fictional rate on the line and a fictional cost in the batch.

   ── why one column is enough ──

   The cost of a batch is already computed as

       cost_per_pcs = net line total ÷ pieces received      (StockPostingServiceImpl)

   and COGS reads that number back off the stock movement. So the effective unit
   cost the accountants want —

       net purchase cost ÷ (purchased + bonus)

   — is not a new calculation. It is the existing one, given the true number of
   pieces. BONUS_QTY carries the free units; the line's money is untouched; the
   division does the rest, and every report that reads cost downstream inherits
   it without being changed.

   ── what this migration does not do ──

   Nothing, for existing rows. A default of 0 means every purchase already in
   the database keeps exactly the cost it has today: 0 bonus, so the divisor is
   the purchased quantity, so the arithmetic is unchanged. The feature is
   invisible until somebody types a number into it.
   ───────────────────────────────────────────────────────────────────────────── */

ALTER TABLE pharma.pos_transaction_dtl
  ADD COLUMN IF NOT EXISTS bonus_qty numeric(14,4) NOT NULL DEFAULT 0;

COMMENT ON COLUMN pharma.pos_transaction_dtl.bonus_qty IS
    'Free units received with this line, in the line''s own sales unit. Adds to stock and lowers the effective cost per piece; never adds to any money total.';

/* Free, not negative. A supplier gives units away or does not; a negative bonus
   would silently reduce what reaches the shelf while the invoice still charged
   for it. */
ALTER TABLE pharma.pos_transaction_dtl
  DROP CONSTRAINT IF EXISTS ck_tran_dtl_bonus_nonneg;
ALTER TABLE pharma.pos_transaction_dtl
  ADD CONSTRAINT ck_tran_dtl_bonus_nonneg CHECK (COALESCE(bonus_qty, 0) >= 0);

SELECT count(*)                                    AS lines,
       count(*) FILTER (WHERE bonus_qty > 0)       AS with_bonus,
       COALESCE(SUM(bonus_qty), 0)                 AS bonus_units
  FROM pharma.pos_transaction_dtl;
