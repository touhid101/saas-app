-- Removes everything the history seed wrote, and only that: invoices marked
-- FROM_SOURCE='SEED' and batches numbered 'SD…'. Anything the user entered is
-- matched by neither.
\set ON_ERROR_STOP on
BEGIN;

DELETE FROM pharma.pos_stock_movement m
 WHERE m.ref_type = 'TRANSACTION'
   AND m.ref_mst IN (SELECT pid_tran_mst FROM pharma.pos_transaction_mst WHERE from_source = 'SEED');

DELETE FROM pharma.pos_transaction_dtl d
 WHERE d.pid_tran_mst IN (SELECT pid_tran_mst FROM pharma.pos_transaction_mst WHERE from_source = 'SEED');

DELETE FROM pharma.pos_transaction_mst WHERE from_source = 'SEED';

-- Put back what the seeded sales drew out of batches that already existed. The
-- movements are gone, so without this a pre-existing batch keeps the balance
-- the seed left it at, and the reconciliation invariant breaks on rows nobody
-- seeded. Seeded batches are excluded: they are deleted outright below.
UPDATE pharma.pos_stock_batch b
   SET qty_pcs    = led.ledger,
       issued_pcs = GREATEST(COALESCE(b.issued_pcs, 0) - (led.ledger - b.qty_pcs), 0)
  FROM (SELECT bb.pid_stock,
               COALESCE(SUM(CASE WHEN m.direction = 'I' THEN m.qty_pcs ELSE 0 END), 0)
             - COALESCE(SUM(CASE WHEN m.direction = 'O' THEN m.qty_pcs ELSE 0 END), 0) AS ledger
          FROM pharma.pos_stock_batch bb
          LEFT JOIN pharma.pos_stock_movement m ON m.pid_stock = bb.pid_stock
         GROUP BY bb.pid_stock) led
 WHERE led.pid_stock = b.pid_stock
   AND b.batch_no NOT LIKE 'SD%'
   AND b.qty_pcs <> led.ledger;

-- The opening movements and the batches they opened.
DELETE FROM pharma.pos_stock_movement
 WHERE pid_stock IN (SELECT pid_stock FROM pharma.pos_stock_batch WHERE batch_no LIKE 'SD%');
DELETE FROM pharma.pos_stock_batch WHERE batch_no LIKE 'SD%';

COMMIT;

SELECT (SELECT count(*) FROM pharma.pos_transaction_mst WHERE from_source='SEED') AS seeded_invoices,
       (SELECT count(*) FROM pharma.pos_stock_batch WHERE batch_no LIKE 'SD%')    AS seeded_batches,
       (SELECT count(*) FROM (
          SELECT b.pid_stock FROM pharma.pos_stock_batch b
           LEFT JOIN pharma.pos_stock_movement m ON m.pid_stock = b.pid_stock
           GROUP BY b.pid_stock, b.qty_pcs
          HAVING b.qty_pcs <> COALESCE(SUM(CASE WHEN m.direction='I' THEN m.qty_pcs ELSE 0 END),0)
                            - COALESCE(SUM(CASE WHEN m.direction='O' THEN m.qty_pcs ELSE 0 END),0)) z)
                                                                                   AS unreconciled;
