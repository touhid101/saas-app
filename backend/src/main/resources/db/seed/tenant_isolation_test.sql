-- A second pharmacy, so isolation can be tested rather than assumed.
-- Everything here is named 'TENANT TEST' or numbered 900+ and is removed by
-- tenant-teardown.sql.
\set ON_ERROR_STOP on
BEGIN;

INSERT INTO pharma.all_branch
  (pid_branch, branch_name, branch_address, pid_org, pid_company, pid_region, status, inserted_date, inserted_by)
VALUES (900, 'TENANT TEST Pharmacy Two', 'Chattogram', 1, 1, 1, 'Y', now(), 'audit')
ON CONFLICT (pid_branch) DO NOTHING;

-- A branch manager who works only at the second pharmacy.
INSERT INTO pharma.acl_user
  (pid_user, user_name, full_name, password_hash, pid_branch, pid_org, pid_company,
   pid_role, is_admin, status, inserted_date, inserted_by)
VALUES (900, 'tenant2', 'TENANT TEST Manager', 'tenant2pass', 900, 1, 1, 2, 'N', 'Y', now(), 'audit')
ON CONFLICT (pid_user) DO NOTHING;

-- Stock that belongs to the second pharmacy alone.
INSERT INTO pharma.pos_stock_batch
  (pid_stock, pid_org, pid_branch, pid_product, batch_no, expiry_date, qty_pcs, received_pcs,
   issued_pcs, cost_per_pcs, mrp_price, pcs_per_box, pcs_per_strip, received_date, status,
   inserted_date, inserted_by)
VALUES (900, 1, 900, 13, 'TT0001', current_date + 400, 500, 500, 0,
        1.17, 78, 50, 10, current_date - 10, 'Y', now(), 'audit')
ON CONFLICT (pid_stock) DO NOTHING;

INSERT INTO pharma.pos_stock_movement
  (pid_movement, pid_stock, pid_org, pid_branch, pid_product, batch_no, movement_type,
   direction, qty_pcs, balance_after, cost_per_pcs, ref_type, movement_date, remarks,
   inserted_date, inserted_by)
VALUES (900, 900, 1, 900, 13, 'TT0001', 'OPENING', 'I', 500, 500, 1.17, 'MIGRATION',
        current_date - 10, 'TENANT TEST opening', now(), 'audit')
ON CONFLICT (pid_movement) DO NOTHING;

-- One sale at the second pharmacy, so it has a little history of its own.
INSERT INTO pharma.pos_transaction_mst
  (pid_tran_mst, pid_org, pid_branch, pid_company, pid_sup_cust, transaction_type, invoice_no,
   transaction_date, payment_type, stage, status, payment_status, from_source,
   total_amount, amount_tobe_paid, given_amount, advance_amount, remarks, inserted_date, inserted_by)
OVERRIDING SYSTEM VALUE
VALUES (900, 1, 900, 1, 1, 'SL', 'TT-SALE-001', current_date - 1, 'CASH', 'Collected', 'Y',
        'Paid', 'AUDIT', 100, 100, 100, 0, 'TENANT TEST sale', now(), 'audit')
ON CONFLICT (pid_tran_mst) DO NOTHING;

COMMIT;

SELECT 'branch 900 stock pcs: '||COALESCE(SUM(qty_pcs),0) FROM pharma.pos_stock_batch WHERE pid_branch=900;
SELECT 'branch 900 user: '||user_name||' (branch '||pid_branch||', role '||pid_role||')'
  FROM pharma.acl_user WHERE pid_user=900;
