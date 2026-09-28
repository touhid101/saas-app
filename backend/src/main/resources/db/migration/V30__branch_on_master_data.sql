-- Gives the last four tenant tables a branch of their own.
--
-- Under branch-as-tenant every table holding one pharmacy's data needs to say
-- which pharmacy. These four carried PID_ORG only, so with several branches under
-- one org they were shared by everyone: generics, sub-categories, sales discount
-- percentages and notifications.
--
-- Deliberately not touched: the detail tables (POS_TRANSACTION_DTL and friends)
-- carry neither column and inherit their tenant through the master row. Copying
-- the branch onto 400,000 detail rows would create a second copy of the truth
-- that can drift from the first. The rule is that a detail query joins its
-- master, which is what the reporting queries already do.
--
-- Backfill: every existing row belongs to branch 1, the only branch trading
-- before this. Branches 901 and 902 are load-test data and own none of it.
\set ON_ERROR_STOP on
\timing on

BEGIN;

ALTER TABLE pharma.pos_generic_info         ADD COLUMN IF NOT EXISTS pid_branch bigint;
ALTER TABLE pharma.pos_product_sub_category ADD COLUMN IF NOT EXISTS pid_branch bigint;
ALTER TABLE pharma.pos_sales_discount       ADD COLUMN IF NOT EXISTS pid_branch bigint;
ALTER TABLE pharma.pos_notification         ADD COLUMN IF NOT EXISTS pid_branch bigint;

UPDATE pharma.pos_generic_info         SET pid_branch = 1 WHERE pid_branch IS NULL;
UPDATE pharma.pos_product_sub_category SET pid_branch = 1 WHERE pid_branch IS NULL;
UPDATE pharma.pos_sales_discount       SET pid_branch = 1 WHERE pid_branch IS NULL;

-- A notification belongs to the branch of the user it was raised for, where that
-- user has one; anything older or unattributed falls to branch 1 with the rest.
UPDATE pharma.pos_notification n
   SET pid_branch = COALESCE((SELECT u.pid_branch FROM pharma.acl_user u
                               WHERE u.pid_user = n.pid_user), 1)
 WHERE n.pid_branch IS NULL;

ALTER TABLE pharma.pos_generic_info         ALTER COLUMN pid_branch SET NOT NULL;
ALTER TABLE pharma.pos_product_sub_category ALTER COLUMN pid_branch SET NOT NULL;
ALTER TABLE pharma.pos_sales_discount       ALTER COLUMN pid_branch SET NOT NULL;
ALTER TABLE pharma.pos_notification         ALTER COLUMN pid_branch SET NOT NULL;

COMMIT;

-- Every one of these is read as "this branch's rows, in name order", so the
-- index carries the branch first and the status beside it.
CREATE INDEX IF NOT EXISTS ix_generic_branch      ON pharma.pos_generic_info         (pid_branch, status);
CREATE INDEX IF NOT EXISTS ix_subcategory_branch  ON pharma.pos_product_sub_category (pid_branch, status);
CREATE INDEX IF NOT EXISTS ix_salesdiscount_branch ON pharma.pos_sales_discount      (pid_branch, status);
CREATE INDEX IF NOT EXISTS ix_notification_branch ON pharma.pos_notification         (pid_branch, pid_user, is_read);

-- The master tables that already had the column were never indexed on it.
CREATE INDEX IF NOT EXISTS ix_category_branch     ON pharma.pos_product_category (pid_branch, status);
CREATE INDEX IF NOT EXISTS ix_unit_branch         ON pharma.pos_product_unit     (pid_branch, status);
CREATE INDEX IF NOT EXISTS ix_prodcompany_branch  ON pharma.pos_company_info     (pid_branch, status);
CREATE INDEX IF NOT EXISTS ix_leaf_branch         ON pharma.pos_leaf_info        (pid_branch, status);
CREATE INDEX IF NOT EXISTS ix_box_branch          ON pharma.pos_box_info         (pid_branch, status);
CREATE INDEX IF NOT EXISTS ix_customer_branch     ON pharma.pos_customer_info    (pid_branch, status);
CREATE INDEX IF NOT EXISTS ix_supplier_branch     ON pharma.pos_supplier_info    (pid_branch, status);
CREATE INDEX IF NOT EXISTS ix_department_branch   ON pharma.all_departments      (pid_branch, status);
CREATE INDEX IF NOT EXISTS ix_designation_branch  ON pharma.hr_designation       (pid_branch, status);
CREATE INDEX IF NOT EXISTS ix_employee_branch     ON pharma.hr_employees         (pid_branch, status);
CREATE INDEX IF NOT EXISTS ix_product_branch      ON pharma.pos_product_info     (pid_branch, status);

ANALYZE pharma.pos_generic_info;
ANALYZE pharma.pos_product_sub_category;
ANALYZE pharma.pos_notification;
ANALYZE pharma.pos_product_info;

SELECT 'generics'      AS tbl, count(*) FILTER (WHERE pid_branch IS NULL) AS unassigned FROM pharma.pos_generic_info
UNION ALL SELECT 'sub-categories', count(*) FILTER (WHERE pid_branch IS NULL) FROM pharma.pos_product_sub_category
UNION ALL SELECT 'sales discount', count(*) FILTER (WHERE pid_branch IS NULL) FROM pharma.pos_sales_discount
UNION ALL SELECT 'notifications',  count(*) FILTER (WHERE pid_branch IS NULL) FROM pharma.pos_notification;
