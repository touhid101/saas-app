-- The constraints that stop master data being created without a pharmacy again.
--
-- Split from V42 deliberately. V42 backfills and then reports what is still
-- unfilled; this runs only once that report is all zeros. Applying them together
-- would turn a data question ("which pharmacy did this row belong to?") into a
-- migration failure halfway through, with some tables constrained and some not.
--
-- POS_COMPANY_INFO gets PID_BRANCH only: its PID_COMPANY is the table's own
-- primary key, the manufacturer's id, not a pharmacy's company.
-- POS_GENERIC_INFO.POS_PID_COMPANY (the manufacturer) stays nullable — a generic
-- with no manufacturer recorded is a normal thing, and making it mandatory would
-- be a business rule change, not a tenancy fix.
\set ON_ERROR_STOP on

BEGIN;

ALTER TABLE pharma.pos_product_category     ALTER COLUMN pid_branch  SET NOT NULL,
                                            ALTER COLUMN pid_company SET NOT NULL;
ALTER TABLE pharma.pos_product_sub_category ALTER COLUMN pid_company SET NOT NULL;
ALTER TABLE pharma.pos_generic_info         ALTER COLUMN pid_company SET NOT NULL;
ALTER TABLE pharma.pos_company_info         ALTER COLUMN pid_branch  SET NOT NULL;
ALTER TABLE pharma.pos_leaf_info            ALTER COLUMN pid_org     SET NOT NULL,
                                            ALTER COLUMN pid_branch  SET NOT NULL,
                                            ALTER COLUMN pid_company SET NOT NULL;
ALTER TABLE pharma.pos_box_info             ALTER COLUMN pid_org     SET NOT NULL,
                                            ALTER COLUMN pid_branch  SET NOT NULL,
                                            ALTER COLUMN pid_company SET NOT NULL;
ALTER TABLE pharma.pos_product_unit         ALTER COLUMN pid_branch  SET NOT NULL,
                                            ALTER COLUMN pid_company SET NOT NULL;
ALTER TABLE pharma.all_departments          ALTER COLUMN pid_org     SET NOT NULL,
                                            ALTER COLUMN pid_branch  SET NOT NULL,
                                            ALTER COLUMN pid_company SET NOT NULL;
ALTER TABLE pharma.hr_designation           ALTER COLUMN pid_org     SET NOT NULL,
                                            ALTER COLUMN pid_branch  SET NOT NULL,
                                            ALTER COLUMN pid_company SET NOT NULL;
ALTER TABLE pharma.hr_employees             ALTER COLUMN pid_branch  SET NOT NULL,
                                            ALTER COLUMN pid_company SET NOT NULL;
ALTER TABLE pharma.pos_customer_info        ALTER COLUMN pid_company SET NOT NULL;
ALTER TABLE pharma.pos_supplier_info        ALTER COLUMN pid_branch  SET NOT NULL,
                                            ALTER COLUMN pid_company SET NOT NULL;

/* ── the reads that now rely on these ──
   Every list in these tabs filters on (pid_org, pid_branch). The columns are
   mandatory from here, so the index is worth having on the tables that did not
   already get one in V30. */
CREATE INDEX IF NOT EXISTS ix_leaf_branch     ON pharma.pos_leaf_info      (pid_org, pid_branch);
CREATE INDEX IF NOT EXISTS ix_box_branch      ON pharma.pos_box_info       (pid_org, pid_branch);
CREATE INDEX IF NOT EXISTS ix_dept_branch     ON pharma.all_departments    (pid_org, pid_branch);
CREATE INDEX IF NOT EXISTS ix_desig_branch    ON pharma.hr_designation     (pid_org, pid_branch);
CREATE INDEX IF NOT EXISTS ix_employee_branch ON pharma.hr_employees       (pid_org, pid_branch);
CREATE INDEX IF NOT EXISTS ix_supplier_branch ON pharma.pos_supplier_info  (pid_org, pid_branch);
CREATE INDEX IF NOT EXISTS ix_prodcomp_branch ON pharma.pos_company_info   (pid_org, pid_branch);

COMMIT;

SELECT c.table_name, c.column_name, c.is_nullable
  FROM information_schema.columns c
 WHERE c.table_schema = 'pharma'
   AND c.column_name IN ('pid_org', 'pid_branch', 'pid_company')
   AND c.table_name IN ('pos_product_category','pos_product_sub_category','pos_generic_info',
                        'pos_company_info','pos_leaf_info','pos_box_info','pos_product_unit',
                        'all_departments','hr_designation','hr_employees',
                        'pos_customer_info','pos_supplier_info')
 ORDER BY c.table_name, c.column_name;
