-- Removes the second pharmacy created to test isolation. Everything it touches
-- is numbered 900 or was inserted by 'audit'.
\set ON_ERROR_STOP on
BEGIN;

DELETE FROM pharma.pos_transaction_dtl
 WHERE pid_tran_mst IN (SELECT pid_tran_mst FROM pharma.pos_transaction_mst WHERE pid_branch = 900);
DELETE FROM pharma.pos_transaction_mst WHERE pid_branch = 900;

DELETE FROM pharma.pos_stock_movement WHERE pid_branch = 900;
DELETE FROM pharma.pos_stock_batch    WHERE pid_branch = 900;

DELETE FROM pharma.pos_requisition_dtl
 WHERE pid_requisition IN (SELECT pid_requisition FROM pharma.pos_requisition_mst WHERE pid_branch = 900);
DELETE FROM pharma.pos_requisition_mst WHERE pid_branch = 900;

DELETE FROM pharma.acl_user  WHERE pid_user   = 900;
DELETE FROM pharma.all_branch WHERE pid_branch = 900;

COMMIT;

SELECT (SELECT count(*) FROM pharma.all_branch)                                   AS branches,
       (SELECT count(*) FROM pharma.acl_user)                                     AS users,
       (SELECT count(*) FROM pharma.pos_transaction_mst WHERE pid_branch = 900)   AS leftover_rows,
       (SELECT count(*) FROM (
          SELECT b.pid_stock FROM pharma.pos_stock_batch b
           LEFT JOIN pharma.pos_stock_movement m ON m.pid_stock = b.pid_stock
           GROUP BY b.pid_stock, b.qty_pcs
          HAVING b.qty_pcs <> COALESCE(SUM(CASE WHEN m.direction='I' THEN m.qty_pcs ELSE 0 END),0)
                            - COALESCE(SUM(CASE WHEN m.direction='O' THEN m.qty_pcs ELSE 0 END),0)) z)
                                                                                   AS unreconciled;
