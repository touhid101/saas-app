-- One adjustment came back at a higher cost than the sale it reversed.
--
-- Movement 22 reversed 12 pieces of batch B-001 at 13.928083 when the sale that
-- put them out (movement 20) had costed them at 12.535250. Reversing a sale at
-- more than it went out for credits back more than was ever charged, and here
-- that understates August's cost of sales by 12 x 1.392833 = 16.71 taka.
--
-- Where the number came from: 13.928083 x 108 = 1504.233, and the batch's whole
-- value is 120 x 12.5352750 = 1504.233. The batch value was divided by the 108
-- pieces remaining after the sale rather than the 120 it was bought as — so
-- selling stock made what was left appear dearer.
--
-- NOT a live defect, and this is worth stating because the obvious reading is
-- that it is. Of the 36 adjustments in the database 35 agree with their batch,
-- including movement 21 — written by the same edit of the same invoice, seconds
-- apart, against a different lot, and correct. The current code copies the cost
-- of the movement it reverses, which is right. This row and the B-002 opening
-- corrected on the same day both sit on V9_BACKFILL batches from 22 August.
--
-- Quantities are untouched, so the ledger invariant is unaffected by
-- construction. To reverse: set it back to 13.928083.

\set ON_ERROR_STOP on

BEGIN;

UPDATE pharma.pos_stock_movement m
   SET cost_per_pcs = b.cost_per_pcs,
       remarks = left(COALESCE(m.remarks || ' | ', '')
                 || 'cost corrected 13.928083 -> 12.535250 on 2026-08-25 (reversal costed at '
                 || 'batch value / remaining qty)', 255)
  FROM pharma.pos_stock_batch b
 WHERE b.pid_stock = m.pid_stock
   AND m.pid_movement = 22
   AND m.movement_type = 'ADJUST'
   AND m.cost_per_pcs = 13.928083
   AND b.batch_no = 'B-001';

DO $$
DECLARE n int;
BEGIN
  SELECT count(*) INTO n FROM pharma.pos_stock_movement m
    JOIN pharma.pos_stock_batch b ON b.pid_stock = m.pid_stock
   WHERE m.pid_movement = 22 AND m.cost_per_pcs = b.cost_per_pcs;
  IF n <> 1 THEN
    RAISE EXCEPTION 'expected movement 22 to match its batch cost, found % row(s)', n;
  END IF;
END $$;

COMMIT;

-- Every adjustment should now agree with the lot it adjusted. The two rows that
-- remain out of step on batch 000026 are genuine weighted-average drift — its
-- cost is the average of a purchase at 6.5202 and a return at 6.96, so an
-- individual movement is expected to differ from it by under two taka.
SELECT m.pid_movement, b.batch_no, m.movement_type, m.qty_pcs,
       m.cost_per_pcs, b.cost_per_pcs AS batch_cost,
       ROUND(m.qty_pcs * (m.cost_per_pcs - b.cost_per_pcs), 2) AS out_by
  FROM pharma.pos_stock_movement m
  JOIN pharma.pos_stock_batch b ON b.pid_stock = m.pid_stock
 WHERE b.pid_branch = 1
   AND m.cost_per_pcs IS NOT NULL
   AND abs(m.qty_pcs * (m.cost_per_pcs - b.cost_per_pcs)) > 1
 ORDER BY abs(m.qty_pcs * (m.cost_per_pcs - b.cost_per_pcs)) DESC;
