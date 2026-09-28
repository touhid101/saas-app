-- Removes the tenancy probe rows and nothing else.
--
-- Everything planted carries the marker ZZPROBE901 in a name column and sits in
-- the reserved 990,000 id block. Both are matched, so a row the application
-- created cannot be caught by this.
\set ON_ERROR_STOP on

BEGIN;

DELETE FROM pharma.acl_user            WHERE pid_user        BETWEEN 990000 AND 990999 AND full_name        LIKE 'ZZPROBE901%';
DELETE FROM pharma.pos_product_info     WHERE pid_product     BETWEEN 990000 AND 990999 AND product_name     LIKE 'ZZPROBE901%';
DELETE FROM pharma.hr_employees         WHERE pid_employee    BETWEEN 990000 AND 990999 AND emp_name         LIKE 'ZZPROBE901%';
DELETE FROM pharma.hr_designation       WHERE pid_designation BETWEEN 990000 AND 990999 AND designation_name LIKE 'ZZPROBE901%';
DELETE FROM pharma.all_departments      WHERE pid_department  BETWEEN 990000 AND 990999 AND department_name  LIKE 'ZZPROBE901%';
DELETE FROM pharma.pos_leaf_info        WHERE pid_leaf        BETWEEN 990000 AND 990999 AND leaf_type        LIKE 'ZZPROBE901%';
DELETE FROM pharma.pos_box_info         WHERE pid_box         BETWEEN 990000 AND 990999 AND box_size_title   LIKE 'ZZPROBE901%';
DELETE FROM pharma.pos_product_sub_category WHERE pid_sub_category BETWEEN 990000 AND 990999 AND sub_category_name LIKE 'ZZPROBE901%';
DELETE FROM pharma.pos_generic_info     WHERE pid_generic     BETWEEN 990000 AND 990999 AND generic_name     LIKE 'ZZPROBE901%';
DELETE FROM pharma.pos_product_unit     WHERE pid_unit        BETWEEN 990000 AND 990999 AND unit_name        LIKE 'ZZPROBE901%';
DELETE FROM pharma.pos_product_category WHERE pid_category    BETWEEN 990000 AND 990999 AND category_name    LIKE 'ZZPROBE901%';
DELETE FROM pharma.pos_supplier_info    WHERE pid_supplier    BETWEEN 990000 AND 990999 AND supplier_name    LIKE 'ZZPROBE901%';
DELETE FROM pharma.pos_company_info     WHERE pid_company     BETWEEN 990000 AND 990999 AND company_name     LIKE 'ZZPROBE901%';
DELETE FROM pharma.pos_customer_info    WHERE pid_customer    BETWEEN 990000 AND 990999 AND customer_name    LIKE 'ZZPROBE901%';

COMMIT;

SELECT 'probe rows remaining: ' || (
  (SELECT count(*) FROM pharma.pos_customer_info WHERE customer_name LIKE 'ZZPROBE901%') +
  (SELECT count(*) FROM pharma.pos_product_info  WHERE product_name  LIKE 'ZZPROBE901%') +
  (SELECT count(*) FROM pharma.pos_supplier_info WHERE supplier_name LIKE 'ZZPROBE901%')) AS left_over;
