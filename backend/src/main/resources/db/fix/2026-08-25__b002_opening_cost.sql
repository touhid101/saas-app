-- Batch B-002 carried an opening cost 8.6 times its real one.
--
-- The V9 backfill wrote POS_STOCK_MOVEMENT.COST_PER_PCS = 59.879750 for the
-- opening of batch B-002, whose true cost is 6.960000 -- the figure the batch
-- row itself holds and every later movement uses. The one sale drawn from that
-- batch before the value settled copied the wrong cost, so 40 pieces left at
-- 59.88 and came back through two credit notes at 6.96.
--
-- That asymmetry is the whole of the phantom COGS: 40 x (59.879750 - 6.960000)
-- = 2,116.79 taka, counted against SL260837032 and dragging August's margin
-- down by roughly six points.
--
-- Only the cost is wrong. Quantities are correct throughout, so this changes no
-- quantity and the ledger invariant -- qty_pcs = SUM(IN) - SUM(OUT) -- is
-- untouched by construction.
--
-- Deliberately NOT done here: deleting SL260837032. It is a real delivered sale,
-- fully returned through SR260839068 and SR260860675, whose lines point back at
-- it. Removing it would orphan both credit notes and leave 40 pieces in the
-- ledger that were never there, while leaving the bad opening row in place.
--
-- To reverse: set both rows back to 59.879750.

\set ON_ERROR_STOP on

BEGIN;

-- The two rows, named explicitly rather than matched by a pattern: this is a
-- correction to two known rows, not a sweep, and it should fail loudly if the
-- data is not what it was when this was written.
-- The note goes in REMARKS: the movement ledger is append-only and carries no
-- updated_by/updated_date, so the correction records itself on the row instead.
UPDATE pharma.pos_stock_movement m
   SET cost_per_pcs = b.cost_per_pcs,
       remarks      = left(COALESCE(m.remarks || ' | ', '')
                      || 'cost corrected 59.879750 -> 6.960000 on 2026-08-25 (V9 backfill error)', 255)
  FROM pharma.pos_stock_batch b
 WHERE b.pid_stock = m.pid_stock
   AND m.pid_movement IN (7, 38)
   AND m.cost_per_pcs = 59.879750
   AND b.batch_no = 'B-002';

-- Refuse to commit unless exactly the two intended rows moved.
DO $$
DECLARE n int;
BEGIN
  SELECT count(*) INTO n
    FROM pharma.pos_stock_movement
   WHERE pid_movement IN (7, 38) AND cost_per_pcs = 6.960000;
  IF n <> 2 THEN
    RAISE EXCEPTION 'expected 2 corrected movements, found %', n;
  END IF;
END $$;

COMMIT;

-- What it did, and proof the ledger still balances.
SELECT pid_movement, movement_type, qty_pcs, cost_per_pcs
  FROM pharma.pos_stock_movement WHERE pid_movement IN (7, 38) ORDER BY pid_movement;

SELECT count(*) AS unreconciled_batches FROM (
  SELECT b.pid_stock FROM pharma.pos_stock_batch b
   LEFT JOIN pharma.pos_stock_movement m ON m.pid_stock = b.pid_stock
   GROUP BY b.pid_stock, b.qty_pcs
  HAVING b.qty_pcs <> COALESCE(SUM(CASE WHEN m.direction='I' THEN m.qty_pcs ELSE 0 END),0)
                    - COALESCE(SUM(CASE WHEN m.direction='O' THEN m.qty_pcs ELSE 0 END),0)) z;
