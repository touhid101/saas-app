-- Plants a record in branch 901 that branch 902 must never see.
--
-- Comparing two branches' responses is not enough on its own: the PERF branches
-- were seeded identically, so an endpoint that isolates correctly still returns
-- byte-identical data to both, and one that leaks looks exactly the same. That
-- ambiguity hid a real leak in inventory once already.
--
-- So instead of comparing, plant something. Every row here exists only in branch
-- 901 and carries the marker ZZPROBE901 in its name. Any endpoint that shows
-- that string to a branch 902 user is leaking, with no interpretation required.
--
-- Ids are in a reserved 990,000 block that nothing else uses.
-- Remove with tenancy_probe_teardown.sql.
\set ON_ERROR_STOP on

BEGIN;

INSERT INTO pharma.pos_customer_info (pid_customer, customer_name, mobile_no, pid_branch, pid_org, pid_company, status)
VALUES (990001, 'ZZPROBE901 Customer', '01700000901', 901, 1, (SELECT pid_company FROM pharma.all_branch WHERE pid_branch=901), 'Y');

INSERT INTO pharma.pos_company_info (pid_company, company_name, pid_branch, pid_org, status)
VALUES (990002, 'ZZPROBE901 Company', 901, 1, 'Y');

INSERT INTO pharma.pos_supplier_info (pid_supplier, supplier_name, pos_pid_company, pid_branch, pid_org, pid_company, status)
VALUES (990003, 'ZZPROBE901 Supplier', 990002, 901, 1, (SELECT pid_company FROM pharma.all_branch WHERE pid_branch=901), 'Y');

INSERT INTO pharma.pos_product_category (pid_category, category_name, pid_branch, pid_org, pid_company, status)
VALUES (990004, 'ZZPROBE901 Category', 901, 1, (SELECT pid_company FROM pharma.all_branch WHERE pid_branch=901), 'Y');

INSERT INTO pharma.pos_product_unit (pid_unit, unit_name, pid_branch, pid_org, pid_company, status)
VALUES (990005, 'ZZPROBE901 Unit', 901, 1, (SELECT pid_company FROM pharma.all_branch WHERE pid_branch=901), 'Y');

INSERT INTO pharma.pos_generic_info (pid_generic, generic_name, pid_branch, pid_org, pid_company, status)
VALUES (990006, 'ZZPROBE901 Generic', 901, 1, (SELECT pid_company FROM pharma.all_branch WHERE pid_branch=901), 'Y');

INSERT INTO pharma.pos_product_sub_category (pid_sub_category, sub_category_name, pid_branch, pid_org, pid_company, status)
VALUES (990007, 'ZZPROBE901 SubCategory', 901, 1, (SELECT pid_company FROM pharma.all_branch WHERE pid_branch=901), 'Y');

INSERT INTO pharma.pos_box_info (pid_box, box_size_title, no_of_leaf, pid_branch, pid_org, pid_company, status)
VALUES (990008, 'ZZPROBE901 Box', 10, 901, 1, (SELECT pid_company FROM pharma.all_branch WHERE pid_branch=901), 'Y');

INSERT INTO pharma.pos_leaf_info (pid_leaf, leaf_type, no_of_pcs, pid_branch, pid_org, pid_company, status)
VALUES (990009, 'ZZPROBE901 Leaf', 10, 901, 1, (SELECT pid_company FROM pharma.all_branch WHERE pid_branch=901), 'Y');

INSERT INTO pharma.all_departments (pid_department, department_name, pid_branch, pid_org, pid_company, status)
VALUES (990010, 'ZZPROBE901 Department', 901, 1, (SELECT pid_company FROM pharma.all_branch WHERE pid_branch=901), 'Y');

INSERT INTO pharma.hr_designation (pid_designation, designation_name, pid_branch, pid_org, pid_company, status)
VALUES (990011, 'ZZPROBE901 Designation', 901, 1, (SELECT pid_company FROM pharma.all_branch WHERE pid_branch=901), 'Y');

INSERT INTO pharma.hr_employees (pid_employee, emp_id, emp_name, pid_branch, pid_org, pid_company, status)
VALUES (990012, 'ZZP901', 'ZZPROBE901 Employee', 901, 1, (SELECT pid_company FROM pharma.all_branch WHERE pid_branch=901), 'Y');

INSERT INTO pharma.pos_product_info (pid_product, product_name, sku, pid_branch, pid_org, status)
VALUES (990013, 'ZZPROBE901 Product', 'ZZPROBE901-SKU', 901, 1, 'Y');

-- A person, because a user list is data too. The gate missed a leak here for
-- exactly this reason: users were never a probed entity, so every branch manager
-- could see all eleven accounts on the estate and nothing said so.
-- The company comes from the branch, as it does for a real account. Without it
-- the probe user is the only row in the database with a null company, and the
-- check for that fails on the test data rather than on the application.
INSERT INTO pharma.acl_user (pid_user, user_name, full_name, password_hash,
                             pid_role, pid_branch, pid_org, pid_company, status)
SELECT 990014, 'zzprobe901user', 'ZZPROBE901 Person',
       '{bcrypt}$2a$10$probeprobeprobeprobepro.beNEVERUSEDprobeprobeprobe',
       8, 901, 1, b.pid_company, 'Y'
  FROM pharma.all_branch b WHERE b.pid_branch = 901;

COMMIT;

SELECT 'probe rows in branch 901: ' || (
  (SELECT count(*) FROM pharma.pos_customer_info    WHERE customer_name     LIKE 'ZZPROBE901%') +
  (SELECT count(*) FROM pharma.pos_company_info     WHERE company_name      LIKE 'ZZPROBE901%') +
  (SELECT count(*) FROM pharma.pos_supplier_info    WHERE supplier_name     LIKE 'ZZPROBE901%') +
  (SELECT count(*) FROM pharma.pos_product_category WHERE category_name     LIKE 'ZZPROBE901%') +
  (SELECT count(*) FROM pharma.pos_product_unit     WHERE unit_name         LIKE 'ZZPROBE901%') +
  (SELECT count(*) FROM pharma.pos_generic_info     WHERE generic_name      LIKE 'ZZPROBE901%') +
  (SELECT count(*) FROM pharma.pos_product_sub_category WHERE sub_category_name LIKE 'ZZPROBE901%') +
  (SELECT count(*) FROM pharma.pos_box_info         WHERE box_size_title    LIKE 'ZZPROBE901%') +
  (SELECT count(*) FROM pharma.pos_leaf_info        WHERE leaf_type         LIKE 'ZZPROBE901%') +
  (SELECT count(*) FROM pharma.all_departments      WHERE department_name   LIKE 'ZZPROBE901%') +
  (SELECT count(*) FROM pharma.hr_designation       WHERE designation_name  LIKE 'ZZPROBE901%') +
  (SELECT count(*) FROM pharma.hr_employees         WHERE emp_name          LIKE 'ZZPROBE901%') +
  (SELECT count(*) FROM pharma.pos_product_info     WHERE product_name      LIKE 'ZZPROBE901%') +
  (SELECT count(*) FROM pharma.acl_user            WHERE full_name         LIKE 'ZZPROBE901%')) AS planted;
