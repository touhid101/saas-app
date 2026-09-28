/* ─────────────────────────────────────────────────────────────────────────────
   Free gift products — a different item, given with the order.

   "Buy 10 Napa, get 2 bottles of oil." The oil is not a bonus on the Napa line:
   it is a different product, with its own batch, its own expiry and its own
   shelf. V67 could not express it, because a bonus belongs to the line it sits
   on and the oil belongs to no line.

   A gift is therefore a line of its own, with a quantity of zero and a bonus of
   two. That one rule is why this migration adds a single column and no table: a
   gift is an ordinary line whose money happens to be nothing.

   ── why LINE_KIND exists at all, when a zero would do ──

   Because "quantity 0" is also what a typo looks like. The kind states the
   intent, so the validator can hold each to its own rule — a PURCHASE line must
   have a quantity, a GIFT line must have a bonus — and so a report can find the
   gifts without inferring them from an absence.

   ── the part that is not free ──

   A gift at zero cost reports infinite margin and quietly poisons every
   product-profitability report it appears in. So when an invoice contains a
   gift, its whole net cost is spread across every line by selling value: each
   line takes a share in proportion to what it is worth on the shelf. The oil
   carries a real cost, the Napa carries correspondingly less, and both report an
   honest margin. See CostAllocation in StockPostingServiceImpl.

   That allocation only happens on invoices that actually have a gift. An
   ordinary invoice keeps costing its lines exactly as it did before.
   ───────────────────────────────────────────────────────────────────────────── */

ALTER TABLE pharma.pos_transaction_dtl
  ADD COLUMN IF NOT EXISTS line_kind varchar(10) NOT NULL DEFAULT 'PURCHASE';

COMMENT ON COLUMN pharma.pos_transaction_dtl.line_kind IS
    'PURCHASE — bought and paid for. GIFT — a different product given free, carried at an allocated share of the invoice cost.';

ALTER TABLE pharma.pos_transaction_dtl
  DROP CONSTRAINT IF EXISTS ck_tran_dtl_line_kind;
ALTER TABLE pharma.pos_transaction_dtl
  ADD CONSTRAINT ck_tran_dtl_line_kind
  CHECK (line_kind IN ('PURCHASE', 'GIFT'));

/* Finding the gifts on an invoice, and finding gifted products across a period
   for the supplier-benefit report. Partial, because gifts are rare and an index
   over four hundred thousand PURCHASE rows would earn nothing. */
CREATE INDEX IF NOT EXISTS ix_tran_dtl_gift
    ON pharma.pos_transaction_dtl (pid_tran_mst, pid_product)
 WHERE line_kind = 'GIFT';

/* Every existing line is a purchase — which is what they all are — so no cost,
   no batch and no report moves. */
SELECT line_kind, count(*)
  FROM pharma.pos_transaction_dtl
 GROUP BY line_kind
 ORDER BY line_kind;
