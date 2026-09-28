-- Removes the load-test dataset and nothing else.
--
-- Matched three ways, all of which the generator set and nothing else uses:
-- FROM_SOURCE='PERF' on the transactions, branch 901/902, and batch numbers
-- beginning 'PF'. Rows the application created are matched by none of them.
\set ON_ERROR_STOP on
\timing on

BEGIN;

DELETE FROM pharma.pos_stock_movement
 WHERE inserted_by = 'perf' OR pid_branch IN (901, 902);

DELETE FROM pharma.pos_transaction_dtl d
 USING pharma.pos_transaction_mst t
 WHERE t.pid_tran_mst = d.pid_tran_mst
   AND (t.from_source = 'PERF' OR t.pid_branch IN (901, 902));

DELETE FROM pharma.pos_transaction_mst
 WHERE from_source = 'PERF' OR pid_branch IN (901, 902);

DELETE FROM pharma.pos_stock_batch
 WHERE batch_no LIKE 'PF%' OR pid_branch IN (901, 902);

DELETE FROM pharma.pos_requisition_dtl
 WHERE pid_requisition IN (SELECT pid_requisition FROM pharma.pos_requisition_mst
                            WHERE pid_branch IN (901, 902));
DELETE FROM pharma.pos_requisition_mst WHERE pid_branch IN (901, 902);

DELETE FROM pharma.acl_user   WHERE pid_user   IN (901, 902);
DELETE FROM pharma.all_branch WHERE pid_branch IN (901, 902);

COMMIT;

ANALYZE pharma.pos_transaction_mst;
ANALYZE pharma.pos_transaction_dtl;
ANALYZE pharma.pos_stock_movement;

SELECT (SELECT count(*) FROM pharma.pos_transaction_mst WHERE from_source='PERF') AS perf_rows,
       (SELECT count(*) FROM pharma.all_branch)                                   AS branches,
       (SELECT count(*) FROM pharma.pos_transaction_mst)                          AS transactions,
       (SELECT count(*) FROM (
          SELECT b.pid_stock FROM pharma.pos_stock_batch b
           LEFT JOIN pharma.pos_stock_movement m ON m.pid_stock = b.pid_stock
           GROUP BY b.pid_stock, b.qty_pcs
          HAVING b.qty_pcs <> COALESCE(SUM(CASE WHEN m.direction='I' THEN m.qty_pcs ELSE 0 END),0)
                            - COALESCE(SUM(CASE WHEN m.direction='O' THEN m.qty_pcs ELSE 0 END),0)) z)
                                                                                   AS unreconciled;
