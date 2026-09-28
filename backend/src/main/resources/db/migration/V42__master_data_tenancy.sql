-- Master data that never carried the pharmacy it belongs to.
--
-- Every create in the Products, Packaging, HR and Parties tabs set the
-- organisation and stopped. Because the read paths filter by branch, the outcome
-- depended only on whether the column happened to be NOT NULL:
--
--   · nullable  — the row saved, the API returned 201, and it was invisible from
--                 that moment on. Two leaf/strip rows in Srishti Pharmacy were
--                 sitting in exactly this state.
--   · NOT NULL  — the insert failed. Sub-Category and Generic could not be
--                 created at all; both returned 409 on every attempt.
--
-- The services now take the branch from the session and the company from the
-- branch. This is for the rows already written, and for the constraints that stop
-- it happening again.
--
-- ── on PID_COMPANY meaning two different things ──
-- This schema already distinguishes them on POS_SUPPLIER_INFO: PID_COMPANY is the
-- pharmacy's own company, POS_PID_COMPANY is the manufacturer. POS_GENERIC_INFO
-- was the one table using PID_COMPANY for a manufacturer. All twelve of its rows
-- are null, so the column is renamed to match the convention rather than left as
-- a trap. POS_COMPANY_INFO is not touched: there PID_COMPANY is the table's own
-- primary key — the manufacturer's id — and org plus branch carry its tenancy.
\set ON_ERROR_STOP on

BEGIN;

/* ── 1. Generic: separate the manufacturer from the pharmacy's company ── */
ALTER TABLE pharma.pos_generic_info RENAME COLUMN pid_company TO pos_pid_company;
ALTER TABLE pharma.pos_generic_info ADD COLUMN pid_company bigint;

COMMENT ON COLUMN pharma.pos_generic_info.pos_pid_company IS 'FK to POS_COMPANY_INFO — the manufacturer';
COMMENT ON COLUMN pharma.pos_generic_info.pid_company     IS 'FK to ALL_COMPANY — the pharmacy''s own company';

/* ── 2. Parties had no company column at all ── */
ALTER TABLE pharma.pos_customer_info ADD COLUMN IF NOT EXISTS pid_company bigint;
ALTER TABLE pharma.pos_supplier_info ADD COLUMN IF NOT EXISTS pid_company bigint;

/* ── 3. a missing branch, where the organisation makes it unambiguous ──
   Only where the organisation has exactly one branch. Where it has several there
   is no way to tell which pharmacy a row was meant for, and a wrong branch is
   worse than an absent one: an absent one is visible, a wrong one silently moves
   somebody's data into another pharmacy. Those are left for the report below. */
CREATE TEMP TABLE sole_branch ON COMMIT DROP AS
SELECT pid_org, MIN(pid_branch) AS pid_branch
  FROM pharma.all_branch
 GROUP BY pid_org
HAVING COUNT(*) = 1;

UPDATE pharma.pos_product_category     t SET pid_branch = s.pid_branch FROM sole_branch s WHERE s.pid_org = t.pid_org AND t.pid_branch IS NULL;
UPDATE pharma.pos_product_sub_category t SET pid_branch = s.pid_branch FROM sole_branch s WHERE s.pid_org = t.pid_org AND t.pid_branch IS NULL;
UPDATE pharma.pos_generic_info         t SET pid_branch = s.pid_branch FROM sole_branch s WHERE s.pid_org = t.pid_org AND t.pid_branch IS NULL;
UPDATE pharma.pos_company_info         t SET pid_branch = s.pid_branch FROM sole_branch s WHERE s.pid_org = t.pid_org AND t.pid_branch IS NULL;
UPDATE pharma.pos_leaf_info            t SET pid_branch = s.pid_branch FROM sole_branch s WHERE s.pid_org = t.pid_org AND t.pid_branch IS NULL;
UPDATE pharma.pos_box_info             t SET pid_branch = s.pid_branch FROM sole_branch s WHERE s.pid_org = t.pid_org AND t.pid_branch IS NULL;
UPDATE pharma.pos_product_unit         t SET pid_branch = s.pid_branch FROM sole_branch s WHERE s.pid_org = t.pid_org AND t.pid_branch IS NULL;
UPDATE pharma.all_departments          t SET pid_branch = s.pid_branch FROM sole_branch s WHERE s.pid_org = t.pid_org AND t.pid_branch IS NULL;
UPDATE pharma.hr_designation           t SET pid_branch = s.pid_branch FROM sole_branch s WHERE s.pid_org = t.pid_org AND t.pid_branch IS NULL;
UPDATE pharma.hr_employees             t SET pid_branch = s.pid_branch FROM sole_branch s WHERE s.pid_org = t.pid_org AND t.pid_branch IS NULL;
UPDATE pharma.pos_customer_info        t SET pid_branch = s.pid_branch FROM sole_branch s WHERE s.pid_org = t.pid_org AND t.pid_branch IS NULL;
UPDATE pharma.pos_supplier_info        t SET pid_branch = s.pid_branch FROM sole_branch s WHERE s.pid_org = t.pid_org AND t.pid_branch IS NULL;

/* ── 4. the company follows the branch ── */
UPDATE pharma.pos_product_category     t SET pid_company = b.pid_company FROM pharma.all_branch b WHERE b.pid_branch = t.pid_branch AND t.pid_company IS NULL;
UPDATE pharma.pos_product_sub_category t SET pid_company = b.pid_company FROM pharma.all_branch b WHERE b.pid_branch = t.pid_branch AND t.pid_company IS NULL;
UPDATE pharma.pos_generic_info         t SET pid_company = b.pid_company FROM pharma.all_branch b WHERE b.pid_branch = t.pid_branch AND t.pid_company IS NULL;
UPDATE pharma.pos_leaf_info            t SET pid_company = b.pid_company FROM pharma.all_branch b WHERE b.pid_branch = t.pid_branch AND t.pid_company IS NULL;
UPDATE pharma.pos_box_info             t SET pid_company = b.pid_company FROM pharma.all_branch b WHERE b.pid_branch = t.pid_branch AND t.pid_company IS NULL;
UPDATE pharma.pos_product_unit         t SET pid_company = b.pid_company FROM pharma.all_branch b WHERE b.pid_branch = t.pid_branch AND t.pid_company IS NULL;
UPDATE pharma.all_departments          t SET pid_company = b.pid_company FROM pharma.all_branch b WHERE b.pid_branch = t.pid_branch AND t.pid_company IS NULL;
UPDATE pharma.hr_designation           t SET pid_company = b.pid_company FROM pharma.all_branch b WHERE b.pid_branch = t.pid_branch AND t.pid_company IS NULL;
UPDATE pharma.hr_employees             t SET pid_company = b.pid_company FROM pharma.all_branch b WHERE b.pid_branch = t.pid_branch AND t.pid_company IS NULL;
UPDATE pharma.pos_customer_info        t SET pid_company = b.pid_company FROM pharma.all_branch b WHERE b.pid_branch = t.pid_branch AND t.pid_company IS NULL;
UPDATE pharma.pos_supplier_info        t SET pid_company = b.pid_company FROM pharma.all_branch b WHERE b.pid_branch = t.pid_branch AND t.pid_company IS NULL;

COMMIT;

/* ── 5. what is still unfilled, before anything is made NOT NULL ──
   Run this first. If it returns rows, the constraints below will fail, and the
   rows it names are the ones to decide about by hand. */
SELECT 'pos_product_category' AS tbl, count(*) FILTER (WHERE pid_branch IS NULL) AS no_branch, count(*) FILTER (WHERE pid_company IS NULL) AS no_company FROM pharma.pos_product_category
UNION ALL SELECT 'pos_product_sub_category', count(*) FILTER (WHERE pid_branch IS NULL), count(*) FILTER (WHERE pid_company IS NULL) FROM pharma.pos_product_sub_category
UNION ALL SELECT 'pos_generic_info',     count(*) FILTER (WHERE pid_branch IS NULL), count(*) FILTER (WHERE pid_company IS NULL) FROM pharma.pos_generic_info
UNION ALL SELECT 'pos_company_info',     count(*) FILTER (WHERE pid_branch IS NULL), 0 FROM pharma.pos_company_info
UNION ALL SELECT 'pos_leaf_info',        count(*) FILTER (WHERE pid_branch IS NULL), count(*) FILTER (WHERE pid_company IS NULL) FROM pharma.pos_leaf_info
UNION ALL SELECT 'pos_box_info',         count(*) FILTER (WHERE pid_branch IS NULL), count(*) FILTER (WHERE pid_company IS NULL) FROM pharma.pos_box_info
UNION ALL SELECT 'pos_product_unit',     count(*) FILTER (WHERE pid_branch IS NULL), count(*) FILTER (WHERE pid_company IS NULL) FROM pharma.pos_product_unit
UNION ALL SELECT 'all_departments',      count(*) FILTER (WHERE pid_branch IS NULL), count(*) FILTER (WHERE pid_company IS NULL) FROM pharma.all_departments
UNION ALL SELECT 'hr_designation',       count(*) FILTER (WHERE pid_branch IS NULL), count(*) FILTER (WHERE pid_company IS NULL) FROM pharma.hr_designation
UNION ALL SELECT 'hr_employees',         count(*) FILTER (WHERE pid_branch IS NULL), count(*) FILTER (WHERE pid_company IS NULL) FROM pharma.hr_employees
UNION ALL SELECT 'pos_customer_info',    count(*) FILTER (WHERE pid_branch IS NULL), count(*) FILTER (WHERE pid_company IS NULL) FROM pharma.pos_customer_info
UNION ALL SELECT 'pos_supplier_info',    count(*) FILTER (WHERE pid_branch IS NULL), count(*) FILTER (WHERE pid_company IS NULL) FROM pharma.pos_supplier_info;
