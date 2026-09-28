# 16 — Flyway Production Migration Plan

**Migrations in this release: V73, V74, V75, V76, V77.** All additive. No
`DROP`, no `TRUNCATE`, no `DELETE`, no change to any file V1–V72.

---

## 1. The two traps this project has already fallen into

Both are recorded in the code, and both govern how these five files are written.

**Baseline 63.** `baseline-version: 63` means **no migration below V64 has ever
run on production.** The tables exist because they were built by hand; the seed
rows inside V1–V63 did not run there. Any migration that depends on a row a
pre-V64 file inserted works on dev and fails on production.

*How V73–V77 avoid it:* nothing in them reads a seeded row. V73 seeds leave
types from `all_branch`, which is real data. V77 grants permissions by selecting
from `acl_group`, `acl_tenant_module` and `acl_perm_template_line` as they
actually are, with `WHERE NOT EXISTS` on every insert.

**Sequences out of step.** V64's own header records the incident: a migration
advanced one sequence and called `nextval` on three others. On dev all four
happened to agree; on production rows had been loaded with explicit ids, which
does not move a sequence, so `nextval` returned a key that already existed and
the whole migration rolled back.

*How V77 avoids it:* every sequence it draws from is advanced with
`setval(..., GREATEST(max(pk), last_value), true)` **before any insert**.
`GREATEST` means this can never move a sequence backwards, so it is safe to
re-run.

## 2. The migrations

### V73 — `hr_employee_extension_and_salary_setup.sql`

| | |
|---|---|
| **Purpose** | Give `hr_employees` what payroll needs; create salary structures and leave types |
| **Existing objects reused** | `hr_employees` (6 rows on dev), `all_branch` |
| **Tables extended** | `hr_employees` — 13 new columns, **all nullable** |
| **New tables** | `hr_salary_structure`, `hr_salary_component`, `hr_leave_type` |
| **New sequences** | `seq_hr_salary_structure`, `seq_hr_salary_component`, `seq_hr_leave_type` |
| **Indexes** | `ix_hr_employee_code`, `ix_hr_employee_structure`, `ix_hr_structure_branch`, `ix_hr_component_structure`, `uq_hr_component_code`, `uq_hr_leave_type_code` |
| **Constraints** | 2 `NOT VALID` CHECKs on `hr_employees`; 5 CHECKs on the new tables; 1 FK |
| **Seed data** | 4 leave types per branch (`CASUAL`, `SICK`, `ANNUAL`, `UNPAID`), **`annual_quota` NULL** |
| **Backfill** | None. Every added column stays NULL |
| **Data impact** | Zero rows changed. 4 × *(number of branches)* rows inserted into a new table |
| **Rollback** | `ALTER TABLE ... DROP COLUMN` × 13, `DROP TABLE` × 3. Safe while the new application code is not deployed |

**Why every added column is nullable.** The table has rows. A `NOT NULL` column
on a populated table either fails or silently invents a value for real people.
The service requires what payroll needs at the point payroll needs it, which is
a better place to say so than a constraint firing during an upgrade.

**Why `NOT VALID`.** `ck_hr_employees_employment_type` and
`ck_hr_employees_salary_mode` govern what is written from now on and do not
re-read the existing rows — which all hold NULL, and NULL passes anyway. It
avoids a full table scan under an ACCESS EXCLUSIVE lock on a table the delivery
screen reads.

**One index is deliberately not unique.** `ix_hr_employee_code` covers
`(pid_branch, upper(emp_id))`. Making it `UNIQUE` is a separate decision, taken
only once the existing `emp_id` values are known to be clean — see §5.

### V74 — `hr_attendance_and_leave.sql`

| | |
|---|---|
| **New tables** | `hr_attendance`, `hr_leave_request`, `hr_leave_balance` |
| **New sequences** | `seq_hr_attendance`, `seq_hr_leave_request`, `seq_hr_leave_balance` |
| **Key index** | `uq_hr_attendance_day` — one live row per employee per date |
| **Constraints** | 8 CHECKs, 4 FKs to `hr_employees` / `hr_leave_type` |
| **Data impact** | None — three empty tables |
| **Rollback** | `DROP TABLE` × 3 |

### V75 — `hr_advance_and_loan.sql`

| | |
|---|---|
| **New tables** | `hr_advance`, `hr_loan`, `hr_loan_schedule` |
| **Constraints** | 11 CHECKs, 2 FKs. `uq_hr_loan_schedule` on `(loan, installment_no)` |
| **Data impact** | None |
| **Rollback** | `DROP TABLE` × 3 (schedule first) |

### V76 — `hr_payroll_and_separation.sql`

| | |
|---|---|
| **New tables** | `hr_payroll_period`, `hr_payroll`, `hr_payroll_line`, `hr_payslip_component`, `hr_salary_payment`, `hr_separation` |
| **Key indexes** | `uq_hr_payroll_live_run` (one live run per branch per period), `uq_hr_payroll_no`, `uq_hr_payroll_line`, `uq_hr_separation_live` |
| **Constraints** | 13 CHECKs, 6 FKs |
| **Data impact** | None |
| **Rollback** | `DROP TABLE` in reverse FK order |

### V77 — `hr_modules_and_permissions.sql`

| | |
|---|---|
| **Purpose** | Register the five screens and grant them |
| **Existing objects written** | `acl_module_info` (+6 rows), `pos_permissions` (+15), `acl_tenant_module` (+5 per branch), `acl_perm_template_line` (+5 per template) |
| **New tables** | None |
| **Sequences advanced** | `sq_acl_module`, `sq_pos_perm`, `sq_tenant_module`, `sq_template_line` — all with `GREATEST` |
| **Data impact** | **Inserts only.** No existing permission row is modified. A pharmacy that has already customised its grants keeps them |
| **Rollback** | `DELETE` from the four tables where `module_code IN ('GRP_HR','MOD32'..'MOD36')`. Removing a permission row removes access; it destroys no business data |

On dev this produced: 6 modules, 15 role grants, 30 tenant-module rows
(6 branches × 5), 15 template lines (3 templates × 5).

## 3. Expand → deploy → backfill → validate → activate

| Step | Action | Reversible? |
|---|---|---|
| 1 Expand | Apply V73–V77. Adds structure only | Yes — nothing reads the new columns yet |
| 2 Deploy | Ship the application. Existing screens are untouched | Yes — the previous build still runs against this schema, because every added column is nullable |
| 3 Backfill | **None required.** HR starts empty by design: no employee has a salary until somebody sets one | — |
| 4 Validate | §6 below | — |
| 5 Activate | Grant MOD32–MOD36 to the roles each pharmacy wants, on the RBAC screen | Yes |

**There is no backfill, and that is deliberate.** Inventing a basic salary for
six existing employees would put a figure nobody chose into a payroll run. HR
opens with every employee marked *not payroll ready*, and the screen says
exactly what is missing.

## 4. Lock profile

The only migration that touches a populated table is V73.

| Statement | Lock | Duration |
|---|---|---|
| `ALTER TABLE hr_employees ADD COLUMN` × 13 | ACCESS EXCLUSIVE | Milliseconds — PostgreSQL 11+ adds a nullable column without rewriting the table |
| `ADD CONSTRAINT ... NOT VALID` × 2 | ACCESS EXCLUSIVE | Milliseconds — no scan |
| `CREATE INDEX ix_hr_employee_code` | SHARE | Proportional to 6 rows |

**On a larger `hr_employees` than dev's six rows**, consider
`CREATE INDEX CONCURRENTLY` for `ix_hr_employee_code` — which cannot run inside
a transaction, so it would need its own migration with
`-- flyway:executeInTransaction=false`. At any realistic pharmacy headcount this
is unnecessary.

Nothing touches `pos_transaction_mst` (200,466 rows) or any other trading table.

## 5. Deferred decisions

| # | Decision | Why deferred |
|---|---|---|
| D-01 | Make `ix_hr_employee_code` UNIQUE | Existing `emp_id` values have not been proved unique per branch. A UNIQUE index that fails takes the whole migration down. **Check first:** the query in §6 |
| D-02 | Validate the two `NOT VALID` constraints | `ALTER TABLE ... VALIDATE CONSTRAINT` takes a SHARE UPDATE EXCLUSIVE lock and scans. Worth doing once the columns hold real values |
| D-03 | A working-day calendar per branch | Would answer P-04 in `02-hr-module-srs.md` |

## 6. Validation

Run before and after, and compare.

```sql
-- Baseline counts that must not change
SELECT 'employees'     AS o, count(*) FROM pharma.hr_employees
UNION ALL SELECT 'departments',  count(*) FROM pharma.all_departments
UNION ALL SELECT 'designations', count(*) FROM pharma.hr_designation
UNION ALL SELECT 'invoices',     count(*) FROM pharma.pos_transaction_mst
UNION ALL SELECT 'roles',        count(*) FROM pharma.acl_group
UNION ALL SELECT 'users',        count(*) FROM pharma.acl_user;

-- Migration history
SELECT version, description, success, installed_on
  FROM pharma.flyway_schema_history
 WHERE version::numeric >= 73 ORDER BY installed_rank;

-- The 15 HR tables exist
SELECT count(*) FROM information_schema.tables
 WHERE table_schema = 'pharma'
   AND (table_name LIKE 'hr\_%' ESCAPE '\');   -- expect 17 incl. the two pre-existing

-- The modules are registered and granted
SELECT m.module_code, m.route_path,
       (SELECT count(*) FROM pharma.pos_permissions   x WHERE x.pid_module = m.pid_module) AS roles,
       (SELECT count(*) FROM pharma.acl_tenant_module x WHERE x.pid_module = m.pid_module) AS pharmacies
  FROM pharma.acl_module_info m
 WHERE m.module_code IN ('GRP_HR','MOD32','MOD33','MOD34','MOD35','MOD36')
 ORDER BY m.module_code;

-- No existing permission row was modified by V77
SELECT count(*) FROM pharma.pos_permissions WHERE updated_by = 'V77';   -- expect 0

-- D-01: is emp_id already unique per branch?
SELECT pid_branch, upper(emp_id), count(*)
  FROM pharma.hr_employees
 GROUP BY 1, 2 HAVING count(*) > 1;   -- empty means the UNIQUE index is safe
```

## 7. Rollback

Application first, then schema — the reverse of deployment.

```sql
BEGIN;
-- V77: remove access. Destroys no business data.
DELETE FROM pharma.acl_perm_template_line WHERE pid_module IN
       (SELECT pid_module FROM pharma.acl_module_info
         WHERE module_code IN ('MOD32','MOD33','MOD34','MOD35','MOD36'));
DELETE FROM pharma.acl_tenant_module WHERE pid_module IN
       (SELECT pid_module FROM pharma.acl_module_info
         WHERE module_code IN ('MOD32','MOD33','MOD34','MOD35','MOD36'));
DELETE FROM pharma.pos_permissions   WHERE pid_module IN
       (SELECT pid_module FROM pharma.acl_module_info
         WHERE module_code IN ('MOD32','MOD33','MOD34','MOD35','MOD36'));
DELETE FROM pharma.acl_module_info
       WHERE module_code IN ('GRP_HR','MOD32','MOD33','MOD34','MOD35','MOD36');
COMMIT;
```

**Stop there unless the schema itself must go.** Removing the modules removes
the screens; the tables can stay empty indefinitely at no cost. Dropping them
after anybody has run a payroll destroys payslips, and a payslip is a record
somebody was paid against.

If the tables must go, drop in reverse FK order —
`hr_salary_payment`, `hr_payslip_component`, `hr_payroll_line`, `hr_payroll`,
`hr_payroll_period`, `hr_loan_schedule`, `hr_loan`, `hr_advance`,
`hr_separation`, `hr_leave_balance`, `hr_leave_request`, `hr_attendance`,
`hr_leave_type`, `hr_salary_component`, `hr_salary_structure` — then drop the 13
columns from `hr_employees`, then delete the five history rows.

## 8. Deployment order

1. Back up the database (policy-defined; this plan does not execute it).
2. `flyway info` — confirm the last applied version is 72.
3. `flyway validate`.
4. Apply V73–V77. On startup the application does this itself.
5. Run §6, compare against the pre-migration baseline.
6. Verify the existing application: login, POS sale, purchase, inventory,
   subscription, tenant isolation.
7. Verify HR: open `/hr`, confirm the summary reads and staff list.
8. Grant MOD32–MOD36 to the roles each pharmacy wants.
