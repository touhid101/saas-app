/* ─────────────────────────────────────────────────────────────────────────────
   Supplier cashback on a purchase invoice.

   A supplier who takes ৳50 off the bill as an incentive has done two things at
   once, and the system could record neither properly. The pharmacy owes ৳50
   less, and the goods cost ৳50 less — so every piece on the shelf is cheaper
   and every sale from it is more profitable than the books were saying.

   Keying it as an invoice discount would get the payable right and the story
   wrong: a discount is a price the supplier quoted, a cashback is an incentive
   they paid, and an accountant reading the invoice a year later needs to know
   which. They also behave differently over time — see below.

   ── how it reaches the cost ──

   Exactly the way the invoice discount already does. StockPostingServiceImpl
   .netLineTotal spreads a header-level reduction across the lines in proportion
   to what each is worth, and the batch cost is that net figure over the pieces
   received. Cashback joins that same allocation, so:

       effective unit cost = (line total − discount share − cashback share)
                             ÷ (purchased + bonus) pieces

   which is the formula the accountants asked for, assembled entirely from parts
   that already existed. Nothing downstream — COGS, margin, stock valuation —
   needs to know cashback exists.

   ── what this column is NOT for ──

   A rebate that arrives weeks later against a quarter's volume. That money
   cannot be pushed back into batches already sold; restating settled COGS is
   how a system stops reconciling with its own history. Later rebates are
   supplier credits, and they belong on the payment screen, not here.
   ───────────────────────────────────────────────────────────────────────────── */

ALTER TABLE pharma.pos_transaction_mst
  ADD COLUMN IF NOT EXISTS cashback_amount numeric(14,4) NOT NULL DEFAULT 0;

COMMENT ON COLUMN pharma.pos_transaction_mst.cashback_amount IS
    'Supplier incentive taken off this invoice. Reduces the payable and is allocated across the lines as a cost reduction. Not for rebates received after the invoice — those are supplier credits.';

/* Cashback is money coming back, never money owed. */
ALTER TABLE pharma.pos_transaction_mst
  DROP CONSTRAINT IF EXISTS ck_tran_mst_cashback_nonneg;
ALTER TABLE pharma.pos_transaction_mst
  ADD CONSTRAINT ck_tran_mst_cashback_nonneg CHECK (COALESCE(cashback_amount, 0) >= 0);

/* Every existing invoice has none, so every existing payable and every existing
   batch cost is exactly what it was before this migration ran. */
SELECT count(*)                                        AS transactions,
       count(*) FILTER (WHERE cashback_amount > 0)     AS with_cashback,
       COALESCE(SUM(cashback_amount), 0)               AS cashback_total
  FROM pharma.pos_transaction_mst
 WHERE transaction_type = 'PU';
