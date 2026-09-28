/* ─────────────────────────────────────────────────────────────────────────────
   HR, part 1 — what an employee is paid, and the shapes a payslip can take.

   ── Why this extends rather than creates ──

   HR_EMPLOYEES already exists and is not empty: six staff records, referenced
   by POS_TRANSACTION_MST.DELIVERY_MAN (who drove the order out) and by
   ACL_USER.PID_EMPLOYEE (which login belongs to which person). ALL_DEPARTMENTS
   and HR_DESIGNATION exist too, are on the Master Data screen under a group
   already labelled "HR", and are already chosen from on the employee form.

   So a new HR_EMPLOYEE table would not be a fresh start — it would be a second
   list of the same people, with the delivery run still pointing at the first
   one. Payroll needs a handful of facts the table does not yet hold; this adds
   those columns and nothing else.

   ── Every column added here is nullable ──

   The table has rows. A NOT NULL column added to a populated table either fails
   or silently invents a value for six real people. Nullable now; the service
   requires what payroll actually needs at the point payroll needs it, which is
   a better place to say so than a constraint that fires during a migration.

   ── EMP_TYPE and EMPLOYMENT_TYPE are not the same question ──

   EMP_TYPE (from V12) is what the person does: DELIVERY_MAN, PHARMACIST,
   CASHIER. It decides who may be handed an invoice to deliver. EMPLOYMENT_TYPE
   is the basis they are engaged on: permanent, probation, contract. A cashier
   can be on probation and a pharmacist on contract, so one column cannot carry
   both. They are deliberately separate and neither replaces the other.
   ───────────────────────────────────────────────────────────────────────────── */


/* ═══ 1. HR_EMPLOYEES — the facts payroll needs ═════════════════════════════ */

ALTER TABLE pharma.hr_employees
    /* The one figure every structure is computed from. Nullable: an employee
       nobody has set a salary for is not in payroll, which is different from
       an employee who earns nothing. */
    ADD COLUMN IF NOT EXISTS basic_salary        NUMERIC(18,2),
    ADD COLUMN IF NOT EXISTS pid_salary_structure BIGINT,

    ADD COLUMN IF NOT EXISTS employment_type     VARCHAR(20),
    ADD COLUMN IF NOT EXISTS confirmation_date   DATE,
    ADD COLUMN IF NOT EXISTS date_of_birth       DATE,
    ADD COLUMN IF NOT EXISTS national_id         VARCHAR(30),
    ADD COLUMN IF NOT EXISTS blood_group         VARCHAR(5),
    ADD COLUMN IF NOT EXISTS emergency_contact   VARCHAR(20),

    /* How salary reaches them. Kept on the employee rather than asked again on
       every payment run, because it is a standing arrangement — but the payment
       row still records what was actually used, since the standing arrangement
       can be overridden for one month. */
    ADD COLUMN IF NOT EXISTS salary_payment_mode VARCHAR(20),
    ADD COLUMN IF NOT EXISTS bank_name           VARCHAR(100),
    ADD COLUMN IF NOT EXISTS bank_branch         VARCHAR(100),
    ADD COLUMN IF NOT EXISTS bank_account_no     VARCHAR(50),
    ADD COLUMN IF NOT EXISTS mobile_wallet_no    VARCHAR(20);

/* NOT VALID: the constraint governs what is written from now on and does not
   re-read six existing rows, all of which hold NULL here anyway. NULL passes —
   an employee whose basis nobody has recorded is not a violation. */
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conname = 'ck_hr_employees_employment_type') THEN
        ALTER TABLE pharma.hr_employees
            ADD CONSTRAINT ck_hr_employees_employment_type
            CHECK (employment_type IS NULL OR employment_type IN
                   ('PERMANENT','PROBATION','CONTRACT','PART_TIME','INTERN','CASUAL'))
            NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conname = 'ck_hr_employees_salary_mode') THEN
        ALTER TABLE pharma.hr_employees
            ADD CONSTRAINT ck_hr_employees_salary_mode
            CHECK (salary_payment_mode IS NULL OR salary_payment_mode IN
                   ('CASH','BANK','MOBILE','CHEQUE'))
            NOT VALID;
    END IF;
END $$;

/* An employee code should be unique inside a branch — but six rows already
   exist and one of them may not be. Partial and non-unique for now: the index
   makes the lookup fast and the service reports a clash with a message; making
   it UNIQUE is a separate decision once the existing data is known to be clean.
   See 16-flyway-production-migration-plan.md. */
CREATE INDEX IF NOT EXISTS ix_hr_employee_code
    ON pharma.hr_employees (pid_branch, upper(emp_id));

CREATE INDEX IF NOT EXISTS ix_hr_employee_structure
    ON pharma.hr_employees (pid_salary_structure)
 WHERE pid_salary_structure IS NOT NULL;


/* ═══ 2. Salary structure — the shape, not the money ════════════════════════

   A structure says "basic, plus 40% house rent, plus a fixed transport
   allowance, less provident fund at 5%". It does not say how much anybody
   earns; that is BASIC_SALARY on the employee. One structure therefore serves
   every employee on the same terms, and a raise is one number on one person
   rather than a new structure.

   Scoped to a branch, like everything else in this product. There is no
   cross-branch screen, so an org-wide structure would be invisible to the
   screen that maintains it. */

CREATE SEQUENCE IF NOT EXISTS pharma.seq_hr_salary_structure START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_hr_salary_component START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_hr_leave_type       START WITH 1 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS pharma.hr_salary_structure (
    pid_structure    BIGINT       NOT NULL,
    pid_org          BIGINT       NOT NULL,
    pid_company      BIGINT,
    pid_branch       BIGINT       NOT NULL,
    structure_name   VARCHAR(120) NOT NULL,
    description      VARCHAR(500),
    effective_from   DATE,
    effective_to     DATE,

    /* ── Overtime and absence policy ──────────────────────────────────────

       Two numbers this file refuses to guess.

       OVERTIME_MULTIPLIER is null by default, and null means overtime is
       recorded but not paid — the payslip says so in as many words. A rate is
       a policy question and, in several jurisdictions, a statutory one; a
       plausible-looking 1.5 seeded here would be read as the answer, and the
       first anyone would learn otherwise is a wage claim. Set it and overtime
       is valued as:

           basic / working_days / standard_hours_per_day
                 × overtime_multiplier × overtime_hours

       ABSENCE_BASIS decides what an unpaid day costs: BASIC or GROSS. BASIC is
       the default because it is the narrower of the two and deducting on gross
       without being asked to takes more money than deducting on basic. Either
       way it is the pharmacy's choice, recorded rather than assumed. */
    standard_hours_per_day NUMERIC(5,2),
    overtime_multiplier    NUMERIC(9,4),
    absence_basis          VARCHAR(10)  DEFAULT 'BASIC',

    status           VARCHAR(1)   DEFAULT 'Y',
    inserted_by      VARCHAR(50),
    inserted_date    TIMESTAMP,
    updated_by       VARCHAR(50),
    updated_date     TIMESTAMP,

    CONSTRAINT hr_salary_structure_pkey PRIMARY KEY (pid_structure),
    CONSTRAINT ck_hr_structure_dates CHECK (effective_to IS NULL
                                            OR effective_from IS NULL
                                            OR effective_to >= effective_from),
    CONSTRAINT ck_hr_structure_absence CHECK (absence_basis IN ('BASIC','GROSS')),
    CONSTRAINT ck_hr_structure_ot CHECK (
        overtime_multiplier IS NULL OR overtime_multiplier >= 0),
    /* A multiplier with no day length to divide by cannot value an hour. */
    CONSTRAINT ck_hr_structure_ot_hours CHECK (
        overtime_multiplier IS NULL OR standard_hours_per_day > 0)
);

CREATE INDEX IF NOT EXISTS ix_hr_structure_branch
    ON pharma.hr_salary_structure (pid_branch, status);

/* One line of the payslip template.

   CALC_METHOD decides which of AMOUNT and PCT_VALUE is read:

       FIXED          AMOUNT, as entered
       PCT_OF_BASIC   PCT_VALUE % of the employee's BASIC_SALARY
       PCT_OF_GROSS   PCT_VALUE % of gross earnings computed so far

   PCT_OF_GROSS is evaluated after every earning line, which is why SORT_ORDER
   is part of the contract and not decoration: a deduction computed on gross
   must not run before the allowances that make up that gross. The service
   enforces the ordering; this column is where it reads it from. */
CREATE TABLE IF NOT EXISTS pharma.hr_salary_component (
    pid_component    BIGINT       NOT NULL,
    pid_structure    BIGINT       NOT NULL,
    component_code   VARCHAR(30)  NOT NULL,
    component_name   VARCHAR(120) NOT NULL,
    component_type   VARCHAR(10)  NOT NULL,
    calc_method      VARCHAR(20)  NOT NULL,
    amount           NUMERIC(18,2),
    pct_value        NUMERIC(9,4),
    /* Whether this line is inside the base that tax is computed on. No rate is
       stored anywhere in HR — see ACC tax configuration, and
       11-financial-period-tax.md, which records that the rate itself is a
       BUSINESS / TAX POLICY CONFIRMATION REQUIRED item. */
    is_taxable       SMALLINT     DEFAULT 1,
    sort_order       INT          DEFAULT 100,

    status           VARCHAR(1)   DEFAULT 'Y',
    inserted_by      VARCHAR(50),
    inserted_date    TIMESTAMP,
    updated_by       VARCHAR(50),
    updated_date     TIMESTAMP,

    CONSTRAINT hr_salary_component_pkey PRIMARY KEY (pid_component),
    CONSTRAINT fk_hr_component_structure FOREIGN KEY (pid_structure)
        REFERENCES pharma.hr_salary_structure (pid_structure),
    CONSTRAINT ck_hr_component_type   CHECK (component_type IN ('EARNING','DEDUCTION')),
    CONSTRAINT ck_hr_component_method CHECK (calc_method IN ('FIXED','PCT_OF_BASIC','PCT_OF_GROSS')),
    /* A component must be computable: fixed needs an amount, a percentage needs
       a percentage. Without this a line can exist that silently contributes
       nothing and is very hard to see on a payslip. */
    CONSTRAINT ck_hr_component_value  CHECK (
        (calc_method = 'FIXED' AND amount IS NOT NULL)
     OR (calc_method IN ('PCT_OF_BASIC','PCT_OF_GROSS') AND pct_value IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS ix_hr_component_structure
    ON pharma.hr_salary_component (pid_structure, sort_order);

CREATE UNIQUE INDEX IF NOT EXISTS uq_hr_component_code
    ON pharma.hr_salary_component (pid_structure, upper(component_code));


/* ═══ 3. Leave types ════════════════════════════════════════════════════════

   ANNUAL_QUOTA is deliberately left NULL by this migration.

   How many days of each kind of leave a pharmacy grants is that pharmacy's
   policy, and in some cases its statutory obligation. Seeding a number here
   would put a figure nobody chose in front of a manager as though it were
   settled, and the first anyone would learn of it is when a balance came out
   wrong. The names are seeded because they are near-universal and save typing;
   the entitlement is not, and the Leave screen refuses to open balances for a
   type whose quota has not been set.

   Recorded in 02-hr-module-srs.md as BUSINESS POLICY CONFIRMATION REQUIRED. */

CREATE TABLE IF NOT EXISTS pharma.hr_leave_type (
    pid_leave_type    BIGINT       NOT NULL,
    pid_org           BIGINT       NOT NULL,
    pid_company       BIGINT,
    pid_branch        BIGINT       NOT NULL,
    type_code         VARCHAR(20)  NOT NULL,
    type_name         VARCHAR(100) NOT NULL,
    description       VARCHAR(500),
    annual_quota      NUMERIC(6,2),
    is_paid           SMALLINT     DEFAULT 1,
    carry_forward     SMALLINT     DEFAULT 0,
    max_carry_forward NUMERIC(6,2),
    requires_approval SMALLINT     DEFAULT 1,

    status            VARCHAR(1)   DEFAULT 'Y',
    inserted_by       VARCHAR(50),
    inserted_date     TIMESTAMP,
    updated_by        VARCHAR(50),
    updated_date      TIMESTAMP,

    CONSTRAINT hr_leave_type_pkey PRIMARY KEY (pid_leave_type),
    CONSTRAINT ck_hr_leave_quota CHECK (annual_quota IS NULL OR annual_quota >= 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_hr_leave_type_code
    ON pharma.hr_leave_type (pid_branch, upper(type_code));

/* Seeded per branch, because leave is administered by the branch that employs
   the person. WHERE NOT EXISTS on the code, so re-running changes nothing and
   a branch that has already renamed or removed a type keeps its decision. */
INSERT INTO pharma.hr_leave_type
       (pid_leave_type, pid_org, pid_company, pid_branch,
        type_code, type_name, description, is_paid, carry_forward,
        requires_approval, status, inserted_by)
SELECT nextval('pharma.seq_hr_leave_type'), b.pid_org, b.pid_company, b.pid_branch,
       t.code, t.name, t.note, t.paid, 0, 1, 'Y', 'V73'
  FROM pharma.all_branch b
 CROSS JOIN (VALUES
       ('CASUAL', 'Casual Leave', 'Short notice, personal. Set the annual quota before opening balances.', 1),
       ('SICK',   'Sick Leave',   'Illness. Set the annual quota before opening balances.',                 1),
       ('ANNUAL', 'Annual Leave', 'Planned. Set the annual quota before opening balances.',                 1),
       ('UNPAID', 'Unpaid Leave', 'Taken without pay. Days are deducted in payroll.',                       0)
   ) AS t(code, name, note, paid)
 WHERE NOT EXISTS (SELECT 1 FROM pharma.hr_leave_type x
                    WHERE x.pid_branch = b.pid_branch
                      AND upper(x.type_code) = t.code);


/* ═══ Verification ══════════════════════════════════════════════════════════ */

SELECT 'employees'      AS object, count(*)::text AS value FROM pharma.hr_employees
UNION ALL
SELECT 'employees with a salary', count(*)::text FROM pharma.hr_employees WHERE basic_salary IS NOT NULL
UNION ALL
SELECT 'salary structures',       count(*)::text FROM pharma.hr_salary_structure
UNION ALL
SELECT 'leave types seeded',      count(*)::text FROM pharma.hr_leave_type
UNION ALL
SELECT 'branches',                count(*)::text FROM pharma.all_branch;
