-- Stamps the branch on master rows that were created before it mattered.
--
-- These tables always had PID_BRANCH; nothing ever filled it in, because with a
-- single pharmacy an unowned row and an owned one behave identically. The moment
-- the lists became branch-scoped those rows belonged to nobody and vanished from
-- the screen that created them — categories and units both went to zero for the
-- real pharmacy.
--
-- Every one of them is branch 1's. Branch 901 and 902 are load-test data and were
-- created with their branch already set, so they are matched by nothing here.
--
-- Products are deliberately excluded. A null branch there means the shared
-- catalogue, which is a real distinction the product queries rely on, and
-- Phase 4's Global Product Master is built on it.
\set ON_ERROR_STOP on

BEGIN;

UPDATE pharma.pos_product_unit     SET pid_branch = 1 WHERE pid_branch IS NULL;
UPDATE pharma.pos_product_category SET pid_branch = 1 WHERE pid_branch IS NULL;
UPDATE pharma.pos_company_info     SET pid_branch = 1 WHERE pid_branch IS NULL;
UPDATE pharma.pos_leaf_info        SET pid_branch = 1 WHERE pid_branch IS NULL;
UPDATE pharma.pos_box_info         SET pid_branch = 1 WHERE pid_branch IS NULL;
UPDATE pharma.all_departments      SET pid_branch = 1 WHERE pid_branch IS NULL;
UPDATE pharma.hr_designation       SET pid_branch = 1 WHERE pid_branch IS NULL;
UPDATE pharma.pos_customer_info    SET pid_branch = 1 WHERE pid_branch IS NULL;
UPDATE pharma.pos_supplier_info    SET pid_branch = 1 WHERE pid_branch IS NULL;
UPDATE pharma.hr_employees         SET pid_branch = 1 WHERE pid_branch IS NULL;

COMMIT;

-- Nothing unowned should remain, on any of them.
SELECT 'units'          AS tbl, count(*) FILTER (WHERE pid_branch IS NULL) AS unowned FROM pharma.pos_product_unit
UNION ALL SELECT 'categories',   count(*) FILTER (WHERE pid_branch IS NULL) FROM pharma.pos_product_category
UNION ALL SELECT 'companies',    count(*) FILTER (WHERE pid_branch IS NULL) FROM pharma.pos_company_info
UNION ALL SELECT 'leafs',        count(*) FILTER (WHERE pid_branch IS NULL) FROM pharma.pos_leaf_info
UNION ALL SELECT 'boxes',        count(*) FILTER (WHERE pid_branch IS NULL) FROM pharma.pos_box_info
UNION ALL SELECT 'departments',  count(*) FILTER (WHERE pid_branch IS NULL) FROM pharma.all_departments
UNION ALL SELECT 'designations', count(*) FILTER (WHERE pid_branch IS NULL) FROM pharma.hr_designation
UNION ALL SELECT 'customers',    count(*) FILTER (WHERE pid_branch IS NULL) FROM pharma.pos_customer_info
UNION ALL SELECT 'suppliers',    count(*) FILTER (WHERE pid_branch IS NULL) FROM pharma.pos_supplier_info
UNION ALL SELECT 'employees',    count(*) FILTER (WHERE pid_branch IS NULL) FROM pharma.hr_employees;
