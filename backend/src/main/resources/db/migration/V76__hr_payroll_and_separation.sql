/* ─────────────────────────────────────────────────────────────────────────────
   HR, part 4 — payroll, salary payment, separation.

   ── Three levels, because a payslip has to be explainable ──

       HR_PAYROLL            one run: this branch, this month
         HR_PAYROLL_LINE     one payslip
           HR_PAYSLIP_COMPONENT   one line of that payslip

   The third level is the one that is tempting to leave out, and the one that
   makes the difference between "net 24,300" and an answer to "why". Without it
   a recalculated payroll silently replaces a figure nobody can reconstruct, and
   an employee's question has to be answered by re-running the calculation and
   hoping the inputs have not changed. With it, the payslip carries its own
   arithmetic.

   ── POSTED is the line the data stops moving at ──

   DRAFT → CALCULATED → REVIEWED → APPROVED → POSTED → PAID, and CANCELLED from
   anywhere before POSTED.

   Up to APPROVED a run can be recalculated as often as anyone likes; that is
   what the review step is for. At POSTED it has been handed to Accounts and a
   journal exists against it. From there the row is read-only: no update, no
   delete, no recalculation. A correction is a reversal and a fresh run, which
   is the same rule the general ledger lives by and for the same reason.

   The database enforces the part it can — POSTED_AT is set and stays set, and
   the unique index refuses a second live run for a period. The service enforces
   the rest, because "you may not edit this" is a sentence, not a constraint
   violation.

   ── One live run per branch per period ──

   Enforced by a partial unique index excluding CANCELLED. A cancelled run stays
   in the table: it is evidence that somebody started a month and abandoned it,
   and deleting it would remove the only trace.
   ───────────────────────────────────────────────────────────────────────────── */

CREATE SEQUENCE IF NOT EXISTS pharma.seq_hr_payroll_period   START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_hr_payroll          START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_hr_payroll_line     START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_hr_payslip_component START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_hr_salary_payment   START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_hr_separation       START WITH 1 INCREMENT BY 1;


/* ═══ Payroll period ════════════════════════════════════════════════════════

   A calendar month, named by year and month, with explicit from and to dates
   because a pharmacy whose pay month runs 26th to 25th is not unusual and the
   dates are what attendance is counted between.

   This is HR's period and is not the accounting period. The two are related —
   posting a payroll into a closed accounting period is refused — but they are
   opened and closed by different people for different reasons, and one table
   serving both would tie a manager's payroll to the accountant's year end. */

CREATE TABLE IF NOT EXISTS pharma.hr_payroll_period (
    pid_period     BIGINT      NOT NULL,
    pid_org        BIGINT      NOT NULL,
    pid_company    BIGINT,
    pid_branch     BIGINT      NOT NULL,

    period_year    INT         NOT NULL,
    period_month   INT         NOT NULL,
    period_from    DATE        NOT NULL,
    period_to      DATE        NOT NULL,
    period_status  VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    /* Working days in the period, entered once. Payroll divides by it to value
       an absence, and storing it means a run reproduced next year uses the
       figure that was used then rather than one recomputed from a calendar
       whose holidays have since been edited. */
    working_days   NUMERIC(5,2),
    remarks        VARCHAR(500),

    status         VARCHAR(1)  DEFAULT 'Y',
    inserted_by    VARCHAR(50),
    inserted_date  TIMESTAMP,
    updated_by     VARCHAR(50),
    updated_date   TIMESTAMP,

    CONSTRAINT hr_payroll_period_pkey PRIMARY KEY (pid_period),
    CONSTRAINT ck_hr_period_status CHECK (period_status IN ('OPEN','CLOSED')),
    CONSTRAINT ck_hr_period_month  CHECK (period_month BETWEEN 1 AND 12),
    CONSTRAINT ck_hr_period_year   CHECK (period_year BETWEEN 2000 AND 2999),
    CONSTRAINT ck_hr_period_dates  CHECK (period_to >= period_from),
    CONSTRAINT ck_hr_period_days   CHECK (working_days IS NULL OR working_days > 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_hr_payroll_period
    ON pharma.hr_payroll_period (pid_branch, period_year, period_month)
 WHERE status = 'Y';


/* ═══ Payroll run ═══════════════════════════════════════════════════════════ */

CREATE TABLE IF NOT EXISTS pharma.hr_payroll (
    pid_payroll     BIGINT        NOT NULL,
    pid_org         BIGINT        NOT NULL,
    pid_company     BIGINT,
    pid_branch      BIGINT        NOT NULL,
    pid_period      BIGINT        NOT NULL,

    payroll_no      VARCHAR(30)   NOT NULL,
    payroll_status  VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',

    employee_count  INT           NOT NULL DEFAULT 0,
    total_gross     NUMERIC(18,2) NOT NULL DEFAULT 0,
    total_deduction NUMERIC(18,2) NOT NULL DEFAULT 0,
    total_tax       NUMERIC(18,2) NOT NULL DEFAULT 0,
    total_net       NUMERIC(18,2) NOT NULL DEFAULT 0,
    total_paid      NUMERIC(18,2) NOT NULL DEFAULT 0,

    calculated_by   VARCHAR(50), calculated_at TIMESTAMP,
    reviewed_by     VARCHAR(50), reviewed_at   TIMESTAMP,
    approved_by     VARCHAR(50), approved_at   TIMESTAMP,
    posted_by       VARCHAR(50), posted_at     TIMESTAMP,
    cancelled_by    VARCHAR(50), cancelled_at  TIMESTAMP,
    cancel_reason   VARCHAR(500),
    remarks         VARCHAR(1000),

    status          VARCHAR(1)    DEFAULT 'Y',
    inserted_by     VARCHAR(50),
    inserted_date   TIMESTAMP,
    updated_by      VARCHAR(50),
    updated_date    TIMESTAMP,

    CONSTRAINT hr_payroll_pkey PRIMARY KEY (pid_payroll),
    CONSTRAINT fk_hr_payroll_period FOREIGN KEY (pid_period)
        REFERENCES pharma.hr_payroll_period (pid_period),
    CONSTRAINT ck_hr_payroll_status CHECK (payroll_status IN
        ('DRAFT','CALCULATED','REVIEWED','APPROVED','POSTED','PAID','CANCELLED')),
    /* A run that says it is posted must say when. The states after POSTED
       inherit the stamp, so this covers PAID as well. */
    CONSTRAINT ck_hr_payroll_posted CHECK (
        payroll_status NOT IN ('POSTED','PAID') OR posted_at IS NOT NULL),
    CONSTRAINT ck_hr_payroll_totals CHECK (
        total_gross >= 0 AND total_deduction >= 0 AND total_net >= 0 AND total_paid >= 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_hr_payroll_no
    ON pharma.hr_payroll (payroll_no);

/* One live run per period. CANCELLED excluded so a month can be started again. */
CREATE UNIQUE INDEX IF NOT EXISTS uq_hr_payroll_live_run
    ON pharma.hr_payroll (pid_branch, pid_period)
 WHERE payroll_status <> 'CANCELLED' AND status = 'Y';

CREATE INDEX IF NOT EXISTS ix_hr_payroll_branch_status
    ON pharma.hr_payroll (pid_branch, payroll_status);


/* ═══ Payslip ═══════════════════════════════════════════════════════════════

   The columns here are totals by role — gross, deductions, the three named
   recoveries, net — because those are what a payroll register prints and what
   Accounts posts. The itemised arithmetic behind them is one level down.

   NET_SALARY is stored, not derived on read. It is the number that was approved
   and the number that will be paid; recomputing it later against a structure
   somebody has since edited would produce a different figure for a month that
   is closed. */

CREATE TABLE IF NOT EXISTS pharma.hr_payroll_line (
    pid_payroll_line  BIGINT        NOT NULL,
    pid_payroll       BIGINT        NOT NULL,
    pid_employee      BIGINT        NOT NULL,

    /* Copied at calculation time. An employee who transfers department in March
       must still appear under the department they were in when February was
       paid, or every historical report moves under them. */
    pid_department    BIGINT,
    pid_designation   BIGINT,
    emp_id            VARCHAR(20),
    emp_name          VARCHAR(50),

    basic_salary      NUMERIC(18,2) NOT NULL DEFAULT 0,
    allowance_total   NUMERIC(18,2) NOT NULL DEFAULT 0,
    overtime_amount   NUMERIC(18,2) NOT NULL DEFAULT 0,
    bonus_amount      NUMERIC(18,2) NOT NULL DEFAULT 0,
    gross_salary      NUMERIC(18,2) NOT NULL DEFAULT 0,

    deduction_total   NUMERIC(18,2) NOT NULL DEFAULT 0,
    absence_deduction NUMERIC(18,2) NOT NULL DEFAULT 0,
    advance_recovery  NUMERIC(18,2) NOT NULL DEFAULT 0,
    loan_recovery     NUMERIC(18,2) NOT NULL DEFAULT 0,
    tax_amount        NUMERIC(18,2) NOT NULL DEFAULT 0,
    net_salary        NUMERIC(18,2) NOT NULL DEFAULT 0,

    /* The attendance the figures were computed from, frozen with them. */
    present_days      NUMERIC(5,2)  DEFAULT 0,
    absent_days       NUMERIC(5,2)  DEFAULT 0,
    leave_days        NUMERIC(5,2)  DEFAULT 0,
    unpaid_leave_days NUMERIC(5,2)  DEFAULT 0,
    overtime_minutes  INT           DEFAULT 0,

    payment_status    VARCHAR(20)   NOT NULL DEFAULT 'UNPAID',
    paid_amount       NUMERIC(18,2) NOT NULL DEFAULT 0,
    remarks           VARCHAR(500),

    status            VARCHAR(1)    DEFAULT 'Y',
    inserted_by       VARCHAR(50),
    inserted_date     TIMESTAMP,
    updated_by        VARCHAR(50),
    updated_date      TIMESTAMP,

    CONSTRAINT hr_payroll_line_pkey PRIMARY KEY (pid_payroll_line),
    CONSTRAINT fk_hr_line_payroll FOREIGN KEY (pid_payroll)
        REFERENCES pharma.hr_payroll (pid_payroll),
    CONSTRAINT fk_hr_line_employee FOREIGN KEY (pid_employee)
        REFERENCES pharma.hr_employees (pid_employee),
    CONSTRAINT ck_hr_line_pay_status CHECK (payment_status IN ('UNPAID','PARTIAL','PAID')),
    /* Net may be zero — an employee whose recoveries consume the whole month —
       but it may not be negative. A negative payslip means the recovery plan
       took more than there was, which is a calculation fault, not a payment. */
    CONSTRAINT ck_hr_line_net CHECK (net_salary >= 0),
    CONSTRAINT ck_hr_line_paid CHECK (paid_amount >= 0 AND paid_amount <= net_salary)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_hr_payroll_line
    ON pharma.hr_payroll_line (pid_payroll, pid_employee);

CREATE INDEX IF NOT EXISTS ix_hr_line_employee
    ON pharma.hr_payroll_line (pid_employee);


/* ═══ Payslip component — the arithmetic ════════════════════════════════════

   BASE_AMOUNT and PCT_VALUE are stored beside the result, so the row reads as a
   sentence: "House Rent, 40% of 20,000, = 8,000". A reader can check it without
   the structure in front of them, and it stays checkable after the structure is
   edited. */

CREATE TABLE IF NOT EXISTS pharma.hr_payslip_component (
    pid_payslip_component BIGINT        NOT NULL,
    pid_payroll_line      BIGINT        NOT NULL,
    component_code        VARCHAR(30)   NOT NULL,
    component_name        VARCHAR(120)  NOT NULL,
    component_type        VARCHAR(10)   NOT NULL,
    calc_method           VARCHAR(20),
    base_amount           NUMERIC(18,2),
    pct_value             NUMERIC(9,4),
    amount                NUMERIC(18,2) NOT NULL,
    sort_order            INT           DEFAULT 100,

    status                VARCHAR(1)    DEFAULT 'Y',
    inserted_by           VARCHAR(50),
    inserted_date         TIMESTAMP,
    updated_by            VARCHAR(50),
    updated_date          TIMESTAMP,

    CONSTRAINT hr_payslip_component_pkey PRIMARY KEY (pid_payslip_component),
    CONSTRAINT fk_hr_payslip_comp_line FOREIGN KEY (pid_payroll_line)
        REFERENCES pharma.hr_payroll_line (pid_payroll_line),
    CONSTRAINT ck_hr_payslip_comp_type CHECK (component_type IN ('EARNING','DEDUCTION'))
);

CREATE INDEX IF NOT EXISTS ix_hr_payslip_comp_line
    ON pharma.hr_payslip_component (pid_payroll_line, sort_order);


/* ═══ Salary payment ════════════════════════════════════════════════════════

   Separate from the payroll run, because calculating what is owed and handing
   it over are different acts on different days, often by different people, and
   a month can be paid in two goes.

   PID_PAYROLL_LINE is nullable: a payment row may cover one employee or the
   whole run. Both are real — a bank transfer file settles everybody at once,
   while the one person who is away gets paid in cash a week later. */

CREATE TABLE IF NOT EXISTS pharma.hr_salary_payment (
    pid_salary_payment BIGINT        NOT NULL,
    pid_org            BIGINT        NOT NULL,
    pid_company        BIGINT,
    pid_branch         BIGINT        NOT NULL,
    pid_payroll        BIGINT        NOT NULL,
    pid_payroll_line   BIGINT,
    pid_employee       BIGINT,

    voucher_no         VARCHAR(30),
    payment_date       DATE          NOT NULL,
    amount             NUMERIC(18,2) NOT NULL,
    payment_mode       VARCHAR(20)   NOT NULL,
    bank_name          VARCHAR(100),
    cheque_no          VARCHAR(40),
    cheque_date        DATE,
    reference_no       VARCHAR(60),
    remarks            VARCHAR(500),
    payment_status     VARCHAR(20)   NOT NULL DEFAULT 'PAID',
    cancel_reason      VARCHAR(500),

    status             VARCHAR(1)    DEFAULT 'Y',
    inserted_by        VARCHAR(50),
    inserted_date      TIMESTAMP,
    updated_by         VARCHAR(50),
    updated_date       TIMESTAMP,

    CONSTRAINT hr_salary_payment_pkey PRIMARY KEY (pid_salary_payment),
    CONSTRAINT fk_hr_salpay_payroll FOREIGN KEY (pid_payroll)
        REFERENCES pharma.hr_payroll (pid_payroll),
    CONSTRAINT fk_hr_salpay_line FOREIGN KEY (pid_payroll_line)
        REFERENCES pharma.hr_payroll_line (pid_payroll_line),
    CONSTRAINT ck_hr_salpay_amount CHECK (amount > 0),
    CONSTRAINT ck_hr_salpay_mode CHECK (payment_mode IN ('CASH','BANK','CHEQUE','MOBILE')),
    CONSTRAINT ck_hr_salpay_status CHECK (payment_status IN ('PAID','CANCELLED'))
);

CREATE INDEX IF NOT EXISTS ix_hr_salpay_payroll
    ON pharma.hr_salary_payment (pid_payroll, payment_status);
CREATE INDEX IF NOT EXISTS ix_hr_salpay_branch_date
    ON pharma.hr_salary_payment (pid_branch, payment_date DESC);


/* ═══ Separation ════════════════════════════════════════════════════════════

   The employee row is never deleted. Six of them are already referenced by
   200,466 invoices and by the delivery history; removing one would leave those
   rows pointing at nothing, and a report of who sold what last year would
   quietly lose a person.

   Separation is therefore a record beside the employee, and what it changes on
   the employee is STATUS — the same flag every other master in this schema uses
   to mean "not in the pickers any more". */

CREATE TABLE IF NOT EXISTS pharma.hr_separation (
    pid_separation    BIGINT        NOT NULL,
    pid_org           BIGINT        NOT NULL,
    pid_company       BIGINT,
    pid_branch        BIGINT        NOT NULL,
    pid_employee      BIGINT        NOT NULL,

    separation_type   VARCHAR(20)   NOT NULL,
    notice_date       DATE,
    last_working_date DATE          NOT NULL,
    notice_period_days INT,
    reason            VARCHAR(1000),

    separation_status VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
    decided_by        VARCHAR(50),
    decided_at        TIMESTAMP,
    decision_remarks  VARCHAR(1000),

    /* What is still owed in both directions on the last day: unpaid salary and
       unrecovered advances and loans. The figure is entered by whoever settles
       it; HR does not compute a final settlement, because what belongs in one
       is a policy question this implementation does not answer. */
    settlement_status VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    settlement_amount NUMERIC(18,2),
    settlement_date   DATE,
    remarks           VARCHAR(1000),

    status            VARCHAR(1)    DEFAULT 'Y',
    inserted_by       VARCHAR(50),
    inserted_date     TIMESTAMP,
    updated_by        VARCHAR(50),
    updated_date      TIMESTAMP,

    CONSTRAINT hr_separation_pkey PRIMARY KEY (pid_separation),
    CONSTRAINT fk_hr_separation_employee FOREIGN KEY (pid_employee)
        REFERENCES pharma.hr_employees (pid_employee),
    CONSTRAINT ck_hr_separation_type CHECK (separation_type IN
        ('RESIGNATION','TERMINATION','RETIREMENT','CONTRACT_END','DEATH','OTHER')),
    CONSTRAINT ck_hr_separation_status CHECK (separation_status IN
        ('DRAFT','SUBMITTED','APPROVED','REJECTED','CANCELLED')),
    CONSTRAINT ck_hr_settlement_status CHECK (settlement_status IN
        ('PENDING','SETTLED','WAIVED')),
    CONSTRAINT ck_hr_separation_notice CHECK (
        notice_date IS NULL OR last_working_date >= notice_date)
);

/* One live separation per employee. Rejected and cancelled ones stay, so a
   resignation that was withdrawn is still on the record. */
CREATE UNIQUE INDEX IF NOT EXISTS uq_hr_separation_live
    ON pharma.hr_separation (pid_employee)
 WHERE separation_status IN ('DRAFT','SUBMITTED','APPROVED') AND status = 'Y';

CREATE INDEX IF NOT EXISTS ix_hr_separation_branch
    ON pharma.hr_separation (pid_branch, last_working_date DESC);


/* ═══ Verification ══════════════════════════════════════════════════════════ */

SELECT 'hr_payroll_period'     AS table_name, count(*)::text AS rows FROM pharma.hr_payroll_period
UNION ALL SELECT 'hr_payroll',            count(*)::text FROM pharma.hr_payroll
UNION ALL SELECT 'hr_payroll_line',       count(*)::text FROM pharma.hr_payroll_line
UNION ALL SELECT 'hr_payslip_component',  count(*)::text FROM pharma.hr_payslip_component
UNION ALL SELECT 'hr_salary_payment',     count(*)::text FROM pharma.hr_salary_payment
UNION ALL SELECT 'hr_separation',         count(*)::text FROM pharma.hr_separation;
