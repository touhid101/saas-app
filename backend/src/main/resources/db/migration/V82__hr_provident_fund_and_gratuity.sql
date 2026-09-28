/* ─────────────────────────────────────────────────────────────────────────────
   HR — provident fund, and gratuity.

   ══ Nothing here decides a rate ══

   P-07 (PF contribution rates) and P-08 (gratuity formula and eligibility) are
   both marked BUSINESS / TAX POLICY CONFIRMATION REQUIRED. Employee share,
   employer share, the wage a percentage applies to, a ceiling, days of pay per
   year of service, the divisor that turns a monthly wage into a daily one,
   the minimum qualifying service — every one of those is policy, and in places
   statute, and this application does not know them.

   So the answer is the same one P-04 got for the weekly off: MAKE THEM
   ENTERABLE AND SHIP THEM EMPTY. Every rate column below is NULLABLE with no
   default. A NULL rate does not mean nought per cent — it means nobody has
   decided, and the calculation refuses rather than inventing a figure. A branch
   with no scheme behaves exactly as this application behaved before today:
   payroll deducts no PF, and no gratuity is computed for anybody.

   The opposite — seeding 8% and 30 days because they are common — would put a
   number on a payslip and in a settlement that no accountant chose, and it
   would be believed.

   ══ PF, and the double-count that had to be avoided ══

   A PF deduction can already be modelled today as an ordinary DEDUCTION
   component on a salary structure, and at least one dev structure does exactly
   that. If a scheme were introduced on top, every payslip carrying both would
   deduct PF twice.

   HR_SALARY_COMPONENT.IS_PF marks a component AS the PF deduction. When a
   scheme is in force at the branch, the calculator SKIPS components marked
   IS_PF and takes the figure from the scheme instead: one PF line on the
   payslip, from one source. With no scheme in force the component behaves
   exactly as it does today. Nothing is guessed from a component's name.

   ══ Where the fund's balance lives ══

   HR_PF_LEDGER is written when a payroll run is POSTED, not when it is
   calculated. Before posting a run can be recalculated any number of times, and
   a fund whose balance moved on every recalculation would not be a fund. Posted
   is where this application already draws the immutability line, so that is
   where the contribution becomes a fact.

   The payslip still shows the deduction at calculation time — it is on
   HR_PAYROLL_LINE and HR_PAYSLIP_COMPONENT like every other figure. The ledger
   is the fund's copy, not a second calculation of it.

   ══ Gratuity is frozen, not accrued ══

   HR_GRATUITY_AWARD holds the figures as they stood when somebody approved
   them — service years, the wage, the daily rate, the days awarded and the
   amount. Nothing recomputes them afterwards, for the same reason an approved
   increment is not recomputed: an award recalculated later is a decision
   changed after it was made.

   No monthly accrual is posted anywhere. Accruing gratuity into a liability
   account every month IS an accounting policy, this build has no Accounts
   module to post it to, and inventing the entry would be inventing the policy.
   The estimate endpoint answers "what would this person be owed" without
   writing anything.

   HR_SEPARATION.SETTLEMENT_AMOUNT is untouched and still entered by hand
   (P-05). An approved award is shown beside it as a fact; what belongs in a
   final settlement remains a question nobody here has answered.
   ───────────────────────────────────────────────────────────────────────────── */

CREATE SEQUENCE IF NOT EXISTS pharma.seq_hr_pf_scheme       START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_hr_pf_ledger       START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_hr_gratuity_policy START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_hr_gratuity_award  START WITH 1 INCREMENT BY 1;


/* ═══ The PF scheme ═════════════════════════════════════════════════════════

   Effective-dated per branch, exactly like HR_WORKING_POLICY: last month's
   payroll was computed against last month's rates, and superseding a scheme
   closes it rather than rewriting it. */

CREATE TABLE IF NOT EXISTS pharma.hr_pf_scheme (
    pid_pf_scheme       BIGINT       NOT NULL,
    pid_org             BIGINT       NOT NULL,
    pid_company         BIGINT,
    pid_branch          BIGINT       NOT NULL,

    scheme_name         VARCHAR(120) NOT NULL,

    /* NULL means nobody has decided, and no PF is deducted. It does NOT mean
       nought per cent — see the header. Both shares are separately nullable
       because a scheme where only the employer contributes is a real thing. */
    employee_pct        NUMERIC(9,4),
    employer_pct        NUMERIC(9,4),

    /* BASIC or GROSS — which wage the percentages apply to. Also policy: some
       schemes are on basic alone, some on basic plus dearness allowance, some
       on gross. */
    contribution_base   VARCHAR(10)  NOT NULL DEFAULT 'BASIC',

    /* The most wage a contribution is taken on. NULL means no ceiling, which is
       a decision rather than an omission — the scheme screen says so. */
    wage_ceiling        NUMERIC(18,2),

    /* How long somebody must have been employed before contributing. NULL or 0
       means from the first payslip. */
    min_service_months  INT,

    effective_from      DATE         NOT NULL,
    effective_to        DATE,

    remarks             VARCHAR(1000),

    status              VARCHAR(1)   DEFAULT 'Y',
    inserted_by         VARCHAR(50),
    inserted_date       TIMESTAMP,
    updated_by          VARCHAR(50),
    updated_date        TIMESTAMP,

    CONSTRAINT hr_pf_scheme_pkey PRIMARY KEY (pid_pf_scheme),
    CONSTRAINT ck_hr_pf_base CHECK (contribution_base IN ('BASIC','GROSS')),
    CONSTRAINT ck_hr_pf_employee_pct CHECK (employee_pct IS NULL
        OR (employee_pct >= 0 AND employee_pct <= 100)),
    CONSTRAINT ck_hr_pf_employer_pct CHECK (employer_pct IS NULL
        OR (employer_pct >= 0 AND employer_pct <= 100)),
    CONSTRAINT ck_hr_pf_ceiling CHECK (wage_ceiling IS NULL OR wage_ceiling > 0),
    CONSTRAINT ck_hr_pf_service CHECK (min_service_months IS NULL OR min_service_months >= 0),
    CONSTRAINT ck_hr_pf_window CHECK (effective_to IS NULL OR effective_to >= effective_from)
);

/* One open-ended scheme per branch. The partial index is what makes
   "the scheme in force" a question with one answer. */
CREATE UNIQUE INDEX IF NOT EXISTS uq_hr_pf_scheme_current
    ON pharma.hr_pf_scheme (pid_branch)
 WHERE effective_to IS NULL AND status = 'Y';

CREATE INDEX IF NOT EXISTS ix_hr_pf_scheme_branch
    ON pharma.hr_pf_scheme (pid_branch, effective_from DESC);


/* ═══ The PF ledger ═════════════════════════════════════════════════════════

   The fund's own record. One row per movement: a contribution written when a
   payroll run is posted, or an entry somebody makes by hand.

   EMPLOYEE_PCT and EMPLOYER_PCT are copied onto every row rather than joined to
   the scheme, for the same reason a payslip copies its component rates: a
   statement printed next year must show what was actually contributed, not what
   the rates happen to be by then. */

CREATE TABLE IF NOT EXISTS pharma.hr_pf_ledger (
    pid_pf_ledger       BIGINT       NOT NULL,
    pid_org             BIGINT       NOT NULL,
    pid_company         BIGINT,
    pid_branch          BIGINT       NOT NULL,
    pid_employee        BIGINT       NOT NULL,

    /* CONTRIBUTION rows carry these. A hand-made entry does not. */
    pid_payroll         BIGINT,
    pid_payroll_line    BIGINT,
    pid_pf_scheme       BIGINT,

    entry_date          DATE         NOT NULL,
    period_from         DATE,
    period_to           DATE,

    /* CONTRIBUTION | WITHDRAWAL | ADJUSTMENT | SETTLEMENT.
       A withdrawal and a settlement both take money out; the difference is that
       a settlement closes the account, and the fund should be able to say which
       happened without reading a remark. */
    entry_type          VARCHAR(20)  NOT NULL,

    /* The wage the percentages were applied to, after any ceiling. Kept so a
       row can be checked without re-deriving the payslip it came from. */
    base_amount         NUMERIC(18,2),
    employee_pct        NUMERIC(9,4),
    employer_pct        NUMERIC(9,4),

    /* Signed by entry_type, not by these columns: both are held as positive
       magnitudes and a WITHDRAWAL subtracts. A ledger with negative amounts in
       some rows and positive in others is one nobody can total by eye. */
    employee_amount     NUMERIC(18,2) NOT NULL DEFAULT 0,
    employer_amount     NUMERIC(18,2) NOT NULL DEFAULT 0,

    reference_no        VARCHAR(50),
    remarks             VARCHAR(1000),

    recorded_by         VARCHAR(50),
    recorded_at         TIMESTAMP,

    status              VARCHAR(1)   DEFAULT 'Y',
    inserted_by         VARCHAR(50),
    inserted_date       TIMESTAMP,
    updated_by          VARCHAR(50),
    updated_date        TIMESTAMP,

    CONSTRAINT hr_pf_ledger_pkey PRIMARY KEY (pid_pf_ledger),
    CONSTRAINT fk_hr_pf_ledger_employee FOREIGN KEY (pid_employee)
        REFERENCES pharma.hr_employees (pid_employee),
    CONSTRAINT fk_hr_pf_ledger_payroll FOREIGN KEY (pid_payroll)
        REFERENCES pharma.hr_payroll (pid_payroll),
    CONSTRAINT fk_hr_pf_ledger_scheme FOREIGN KEY (pid_pf_scheme)
        REFERENCES pharma.hr_pf_scheme (pid_pf_scheme),
    CONSTRAINT ck_hr_pf_entry_type CHECK (entry_type IN
        ('CONTRIBUTION','WITHDRAWAL','ADJUSTMENT','SETTLEMENT')),
    CONSTRAINT ck_hr_pf_amounts CHECK (employee_amount >= 0 AND employer_amount >= 0),
    /* A movement of nothing is not a movement. */
    CONSTRAINT ck_hr_pf_nonzero CHECK (employee_amount > 0 OR employer_amount > 0),
    /* A contribution belongs to a payroll run; that is what makes it checkable. */
    CONSTRAINT ck_hr_pf_contribution_source CHECK (
        entry_type <> 'CONTRIBUTION' OR pid_payroll IS NOT NULL)
);

/* One contribution per employee per run. Recalculating a run before it is
   posted writes nothing here; posting it writes exactly one row each, and this
   index is what makes a second post impossible rather than merely unlikely. */
CREATE UNIQUE INDEX IF NOT EXISTS uq_hr_pf_ledger_contribution
    ON pharma.hr_pf_ledger (pid_payroll, pid_employee)
 WHERE entry_type = 'CONTRIBUTION' AND status = 'Y';

/* The statement read: one person, in date order. */
CREATE INDEX IF NOT EXISTS ix_hr_pf_ledger_employee
    ON pharma.hr_pf_ledger (pid_employee, entry_date, pid_pf_ledger);

CREATE INDEX IF NOT EXISTS ix_hr_pf_ledger_branch
    ON pharma.hr_pf_ledger (pid_branch, entry_date DESC);


/* ═══ The gratuity policy ═══════════════════════════════════════════════════

   Every figure below is a question this application cannot answer. They are
   columns so that somebody who can answer them has somewhere to put the
   answer. */

CREATE TABLE IF NOT EXISTS pharma.hr_gratuity_policy (
    pid_gratuity_policy BIGINT       NOT NULL,
    pid_org             BIGINT       NOT NULL,
    pid_company         BIGINT,
    pid_branch          BIGINT       NOT NULL,

    policy_name         VARCHAR(120) NOT NULL,

    /* Days of pay awarded per completed year of service. NULL means undecided,
       and no gratuity is computed for anybody at this branch. */
    days_per_year       NUMERIC(6,2),

    /* The divisor that turns a monthly wage into a daily one. This is policy in
       its own right and a common source of argument — 26 working days and 30
       calendar days give materially different answers on the same salary — so
       it is entered, never assumed. */
    days_in_month       NUMERIC(5,2),

    /* BASIC or GROSS. Which wage the daily rate comes from. */
    wage_basis          VARCHAR(10)  NOT NULL DEFAULT 'BASIC',

    /* Below this, nothing is payable. NULL means no qualifying period. */
    min_service_years   NUMERIC(5,2),

    /* Service beyond this is not counted. NULL means no cap. */
    max_service_years   NUMERIC(5,2),

    /* What a part-year is worth:
         IGNORE    only whole completed years count
         PRO_RATA  a part year counts as its fraction
         ROUND_UP  a part year counts as a whole one
       There is no defensible default, so this one has no default either. */
    partial_year_rule   VARCHAR(20)  NOT NULL DEFAULT 'IGNORE',

    effective_from      DATE         NOT NULL,
    effective_to        DATE,

    remarks             VARCHAR(1000),

    status              VARCHAR(1)   DEFAULT 'Y',
    inserted_by         VARCHAR(50),
    inserted_date       TIMESTAMP,
    updated_by          VARCHAR(50),
    updated_date        TIMESTAMP,

    CONSTRAINT hr_gratuity_policy_pkey PRIMARY KEY (pid_gratuity_policy),
    CONSTRAINT ck_hr_grat_basis CHECK (wage_basis IN ('BASIC','GROSS')),
    CONSTRAINT ck_hr_grat_partial CHECK (partial_year_rule IN
        ('IGNORE','PRO_RATA','ROUND_UP')),
    CONSTRAINT ck_hr_grat_days CHECK (days_per_year IS NULL OR days_per_year > 0),
    CONSTRAINT ck_hr_grat_divisor CHECK (days_in_month IS NULL
        OR (days_in_month > 0 AND days_in_month <= 31)),
    CONSTRAINT ck_hr_grat_min CHECK (min_service_years IS NULL OR min_service_years >= 0),
    CONSTRAINT ck_hr_grat_max CHECK (max_service_years IS NULL OR max_service_years > 0),
    CONSTRAINT ck_hr_grat_window CHECK (effective_to IS NULL OR effective_to >= effective_from)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_hr_gratuity_policy_current
    ON pharma.hr_gratuity_policy (pid_branch)
 WHERE effective_to IS NULL AND status = 'Y';

CREATE INDEX IF NOT EXISTS ix_hr_gratuity_policy_branch
    ON pharma.hr_gratuity_policy (pid_branch, effective_from DESC);


/* ═══ The gratuity award ════════════════════════════════════════════════════

   Every figure is frozen at approval. SERVICE_YEARS, MONTHLY_WAGE, DAILY_WAGE,
   DAYS_PER_YEAR and DAYS_AWARDED are all copied onto the row, so an award can
   be read back and checked years later without the policy or the salary that
   produced it still being reachable. */

CREATE TABLE IF NOT EXISTS pharma.hr_gratuity_award (
    pid_gratuity_award  BIGINT       NOT NULL,
    pid_org             BIGINT       NOT NULL,
    pid_company         BIGINT,
    pid_branch          BIGINT       NOT NULL,
    pid_employee        BIGINT       NOT NULL,

    /* The separation this settles, when there is one. An award can also be made
       to somebody still employed, which is why this is nullable. */
    pid_separation      BIGINT,
    pid_gratuity_policy BIGINT,

    award_no            VARCHAR(30),

    service_from        DATE         NOT NULL,
    service_to          DATE         NOT NULL,
    /* Frozen. Recomputing service from the dates later would silently answer a
       different question once the partial-year rule changed. */
    service_years       NUMERIC(7,4) NOT NULL,

    wage_basis          VARCHAR(10)  NOT NULL,
    monthly_wage        NUMERIC(18,2) NOT NULL,
    days_in_month       NUMERIC(5,2)  NOT NULL,
    daily_wage          NUMERIC(18,4) NOT NULL,
    days_per_year       NUMERIC(6,2)  NOT NULL,
    days_awarded        NUMERIC(9,2)  NOT NULL,

    gratuity_amount     NUMERIC(18,2) NOT NULL,

    award_status        VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',

    submitted_by        VARCHAR(50), submitted_at TIMESTAMP,
    decided_by          VARCHAR(50), decided_at   TIMESTAMP,
    decision_remarks    VARCHAR(1000),

    paid_date           DATE,
    payment_mode        VARCHAR(20),
    payment_reference   VARCHAR(50),
    paid_by             VARCHAR(50),

    reason              VARCHAR(1000),

    status              VARCHAR(1)   DEFAULT 'Y',
    inserted_by         VARCHAR(50),
    inserted_date       TIMESTAMP,
    updated_by          VARCHAR(50),
    updated_date        TIMESTAMP,

    CONSTRAINT hr_gratuity_award_pkey PRIMARY KEY (pid_gratuity_award),
    CONSTRAINT fk_hr_grat_award_employee FOREIGN KEY (pid_employee)
        REFERENCES pharma.hr_employees (pid_employee),
    CONSTRAINT fk_hr_grat_award_separation FOREIGN KEY (pid_separation)
        REFERENCES pharma.hr_separation (pid_separation),
    CONSTRAINT fk_hr_grat_award_policy FOREIGN KEY (pid_gratuity_policy)
        REFERENCES pharma.hr_gratuity_policy (pid_gratuity_policy),
    CONSTRAINT ck_hr_grat_award_status CHECK (award_status IN
        ('DRAFT','SUBMITTED','APPROVED','REJECTED','PAID','CANCELLED')),
    CONSTRAINT ck_hr_grat_award_basis CHECK (wage_basis IN ('BASIC','GROSS')),
    CONSTRAINT ck_hr_grat_award_service CHECK (service_to >= service_from),
    CONSTRAINT ck_hr_grat_award_amount CHECK (gratuity_amount >= 0),
    /* Paid means paid: a date and how, or it is not that status. */
    CONSTRAINT ck_hr_grat_award_paid CHECK (award_status <> 'PAID'
        OR (paid_date IS NOT NULL AND payment_mode IS NOT NULL))
);

/* One live award per employee per separation. Somebody leaving is settled once;
   a rejected or cancelled award frees the slot for a corrected one. */
CREATE UNIQUE INDEX IF NOT EXISTS uq_hr_gratuity_award_live
    ON pharma.hr_gratuity_award (pid_employee, service_to)
 WHERE award_status IN ('DRAFT','SUBMITTED','APPROVED','PAID') AND status = 'Y';

CREATE INDEX IF NOT EXISTS ix_hr_gratuity_award_branch
    ON pharma.hr_gratuity_award (pid_branch, award_status, service_to DESC);

CREATE INDEX IF NOT EXISTS ix_hr_gratuity_award_employee
    ON pharma.hr_gratuity_award (pid_employee, service_to DESC);


/* ═══ What the payslip carries ══════════════════════════════════════════════

   Additive and defaulted to zero, so every payslip already posted reads exactly
   as it did before: they were computed with no PF and they still say no PF. */

ALTER TABLE pharma.hr_payroll_line
    ADD COLUMN IF NOT EXISTS pf_employee_amount NUMERIC(18,2) DEFAULT 0,
    ADD COLUMN IF NOT EXISTS pf_employer_amount NUMERIC(18,2) DEFAULT 0;

/* The employer's share is NOT part of DEDUCTION_TOTAL and never reaches
   NET_SALARY — it is the company's money, not the employee's, and adding it to
   a deduction total would understate what everybody was paid. It sits on the
   line so the cost of employing somebody can be reported without a second
   calculation. */


/* ═══ Which component IS the PF deduction ═══════════════════════════════════

   Default 0: no existing component is assumed to be PF, so no existing payroll
   changes. Ticking one only matters once a scheme is in force — see the
   header. */

ALTER TABLE pharma.hr_salary_component
    ADD COLUMN IF NOT EXISTS is_pf SMALLINT DEFAULT 0;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_hr_component_is_pf') THEN
        ALTER TABLE pharma.hr_salary_component
            ADD CONSTRAINT ck_hr_component_is_pf CHECK (COALESCE(is_pf,0) IN (0,1));
    END IF;
END $$;


/* ═══ Verification ══════════════════════════════════════════════════════════ */

SELECT 'hr_pf_scheme'       AS table_name, count(*)::text AS rows FROM pharma.hr_pf_scheme
UNION ALL SELECT 'hr_pf_ledger',       count(*)::text FROM pharma.hr_pf_ledger
UNION ALL SELECT 'hr_gratuity_policy', count(*)::text FROM pharma.hr_gratuity_policy
UNION ALL SELECT 'hr_gratuity_award',  count(*)::text FROM pharma.hr_gratuity_award
UNION ALL SELECT 'payroll lines kept', count(*)::text FROM pharma.hr_payroll_line
UNION ALL SELECT 'components kept',    count(*)::text FROM pharma.hr_salary_component
UNION ALL SELECT 'components marked PF',
       count(*)::text FROM pharma.hr_salary_component WHERE COALESCE(is_pf,0) = 1;
