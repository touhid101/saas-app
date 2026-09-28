/* ─────────────────────────────────────────────────────────────────────────────
   HR, part 3 — money lent to an employee, and how it comes back.

   ── Advance and loan are not the same thing, and are not one table ──

   An advance is salary paid early: it is recovered out of the next payslip, or
   the next two, and there is no schedule worth keeping. A loan is a sum lent
   against a plan: twelve installments, a due month for each, and a record of
   which payroll run took which one. Folding them together would either give the
   advance a schedule nobody wants to maintain or give the loan none, and the
   difference is exactly what a manager is deciding between when they choose.

   ── HR does not write journals ──

   Paying an advance moves money and belongs in the general ledger. Nothing in
   this migration records that. The advance carries a status the accounting side
   reads, and Accounts raises the journal from its own financial event — the
   division set out in 51 of the brief and in 10-payroll-accounting.md. A
   PID_EVENT column is not added here either: the reference points from Accounts
   to HR, so HR can be built and used before Accounts exists.

   ── Interest is not implemented ──

   INTEREST_RATE is present and nullable. Nothing computes with it. Whether this
   pharmacy charges interest on a staff loan, at what rate, and whether that is
   permissible, is a policy and possibly a legal question — recorded in
   02-hr-module-srs.md as BUSINESS POLICY CONFIRMATION REQUIRED. A zero or a
   guessed default would look like an answer.
   ───────────────────────────────────────────────────────────────────────────── */

CREATE SEQUENCE IF NOT EXISTS pharma.seq_hr_advance       START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_hr_loan          START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_hr_loan_schedule START WITH 1 INCREMENT BY 1;


/* ═══ Advance ═══════════════════════════════════════════════════════════════

   AMOUNT is what was asked for; APPROVED_AMOUNT is what was granted, and they
   are different columns because an approver who cuts the figure has made a
   decision worth being able to see afterwards. Recovery is always computed from
   APPROVED_AMOUNT.

   OUTSTANDING is not stored. It is APPROVED_AMOUNT − RECOVERED_AMOUNT, and a
   third column holding the same fact is a column that will one day disagree
   with the other two. */

CREATE TABLE IF NOT EXISTS pharma.hr_advance (
    pid_advance       BIGINT       NOT NULL,
    pid_org           BIGINT       NOT NULL,
    pid_company       BIGINT,
    pid_branch        BIGINT       NOT NULL,
    pid_employee      BIGINT       NOT NULL,

    advance_no        VARCHAR(30),
    request_date      DATE         NOT NULL,
    amount            NUMERIC(18,2) NOT NULL,
    approved_amount   NUMERIC(18,2),
    recovered_amount  NUMERIC(18,2) NOT NULL DEFAULT 0,

    /* Spread over this many payslips, starting at RECOVER_FROM. One means the
       whole thing comes off the next run. */
    installments      INT          DEFAULT 1,
    recover_from      DATE,

    reason            VARCHAR(1000),
    advance_status    VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    decided_by        VARCHAR(50),
    decided_at        TIMESTAMP,
    decision_remarks  VARCHAR(1000),

    /* Set when the money actually left, by whichever route. The accounting
       entry is Accounts' business; this is the operational fact HR needs to
       know the advance is live and recoverable. */
    paid_date         DATE,
    payment_mode      VARCHAR(20),
    payment_reference VARCHAR(60),

    status            VARCHAR(1)   DEFAULT 'Y',
    inserted_by       VARCHAR(50),
    inserted_date     TIMESTAMP,
    updated_by        VARCHAR(50),
    updated_date      TIMESTAMP,

    CONSTRAINT hr_advance_pkey PRIMARY KEY (pid_advance),
    CONSTRAINT fk_hr_advance_employee FOREIGN KEY (pid_employee)
        REFERENCES pharma.hr_employees (pid_employee),
    CONSTRAINT ck_hr_advance_status CHECK (advance_status IN
        ('DRAFT','SUBMITTED','APPROVED','REJECTED','PAID','RECOVERING','CLOSED','CANCELLED')),
    CONSTRAINT ck_hr_advance_amount CHECK (amount > 0),
    CONSTRAINT ck_hr_advance_approved CHECK (
        approved_amount IS NULL OR (approved_amount > 0 AND approved_amount <= amount)),
    /* More cannot come back than went out. */
    CONSTRAINT ck_hr_advance_recovered CHECK (
        recovered_amount >= 0
        AND (approved_amount IS NULL OR recovered_amount <= approved_amount)),
    CONSTRAINT ck_hr_advance_installments CHECK (installments IS NULL OR installments >= 1)
);

CREATE INDEX IF NOT EXISTS ix_hr_advance_employee
    ON pharma.hr_advance (pid_employee, request_date DESC);
CREATE INDEX IF NOT EXISTS ix_hr_advance_branch_status
    ON pharma.hr_advance (pid_branch, advance_status, request_date DESC);
/* What payroll asks for: the advances still owing at this branch. */
CREATE INDEX IF NOT EXISTS ix_hr_advance_open
    ON pharma.hr_advance (pid_branch, pid_employee)
 WHERE advance_status IN ('PAID','RECOVERING') AND status = 'Y';


/* ═══ Loan ══════════════════════════════════════════════════════════════════ */

CREATE TABLE IF NOT EXISTS pharma.hr_loan (
    pid_loan           BIGINT       NOT NULL,
    pid_org            BIGINT       NOT NULL,
    pid_company        BIGINT,
    pid_branch         BIGINT       NOT NULL,
    pid_employee       BIGINT       NOT NULL,

    loan_no            VARCHAR(30),
    request_date       DATE         NOT NULL,
    principal          NUMERIC(18,2) NOT NULL,
    approved_principal NUMERIC(18,2),
    installments       INT          NOT NULL DEFAULT 1,
    installment_amount NUMERIC(18,2),
    /* Present, unused, undecided — see the header. */
    interest_rate      NUMERIC(9,4),

    recovered_amount   NUMERIC(18,2) NOT NULL DEFAULT 0,
    start_date         DATE,

    purpose            VARCHAR(1000),
    loan_status        VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    decided_by         VARCHAR(50),
    decided_at         TIMESTAMP,
    decision_remarks   VARCHAR(1000),

    disbursed_date     DATE,
    payment_mode       VARCHAR(20),
    payment_reference  VARCHAR(60),

    status             VARCHAR(1)   DEFAULT 'Y',
    inserted_by        VARCHAR(50),
    inserted_date      TIMESTAMP,
    updated_by         VARCHAR(50),
    updated_date       TIMESTAMP,

    CONSTRAINT hr_loan_pkey PRIMARY KEY (pid_loan),
    CONSTRAINT fk_hr_loan_employee FOREIGN KEY (pid_employee)
        REFERENCES pharma.hr_employees (pid_employee),
    CONSTRAINT ck_hr_loan_status CHECK (loan_status IN
        ('DRAFT','SUBMITTED','APPROVED','REJECTED','DISBURSED','RECOVERING','CLOSED','CANCELLED')),
    CONSTRAINT ck_hr_loan_principal CHECK (principal > 0),
    CONSTRAINT ck_hr_loan_approved CHECK (
        approved_principal IS NULL OR (approved_principal > 0 AND approved_principal <= principal)),
    CONSTRAINT ck_hr_loan_installments CHECK (installments >= 1),
    CONSTRAINT ck_hr_loan_recovered CHECK (
        recovered_amount >= 0
        AND (approved_principal IS NULL OR recovered_amount <= approved_principal)),
    CONSTRAINT ck_hr_loan_interest CHECK (interest_rate IS NULL OR interest_rate >= 0)
);

CREATE INDEX IF NOT EXISTS ix_hr_loan_employee
    ON pharma.hr_loan (pid_employee, request_date DESC);
CREATE INDEX IF NOT EXISTS ix_hr_loan_branch_status
    ON pharma.hr_loan (pid_branch, loan_status, request_date DESC);
CREATE INDEX IF NOT EXISTS ix_hr_loan_open
    ON pharma.hr_loan (pid_branch, pid_employee)
 WHERE loan_status IN ('DISBURSED','RECOVERING') AND status = 'Y';


/* ═══ Loan schedule ═════════════════════════════════════════════════════════

   One row per installment, written when the loan is approved so the employee
   can be shown the whole plan before they accept it — not built lazily as
   payroll runs, which would mean the plan does not exist until it is half over.

   PID_PAYROLL records which run took the installment. That is the audit answer
   to "why is my salary short this month", and it is also what makes recovery
   idempotent: a payroll recalculated twice finds the installment already
   marked against it and does not take it again. */

CREATE TABLE IF NOT EXISTS pharma.hr_loan_schedule (
    pid_schedule     BIGINT        NOT NULL,
    pid_loan         BIGINT        NOT NULL,
    installment_no   INT           NOT NULL,
    due_date         DATE          NOT NULL,
    due_amount       NUMERIC(18,2) NOT NULL,
    recovered_amount NUMERIC(18,2) NOT NULL DEFAULT 0,
    schedule_status  VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    pid_payroll      BIGINT,
    recovered_on     DATE,
    remarks          VARCHAR(500),

    status           VARCHAR(1)    DEFAULT 'Y',
    inserted_by      VARCHAR(50),
    inserted_date    TIMESTAMP,
    updated_by       VARCHAR(50),
    updated_date     TIMESTAMP,

    CONSTRAINT hr_loan_schedule_pkey PRIMARY KEY (pid_schedule),
    CONSTRAINT fk_hr_schedule_loan FOREIGN KEY (pid_loan)
        REFERENCES pharma.hr_loan (pid_loan),
    CONSTRAINT ck_hr_schedule_status CHECK (schedule_status IN
        ('PENDING','PARTIAL','RECOVERED','WAIVED','CANCELLED')),
    CONSTRAINT ck_hr_schedule_amounts CHECK (
        due_amount > 0 AND recovered_amount >= 0 AND recovered_amount <= due_amount),
    CONSTRAINT ck_hr_schedule_no CHECK (installment_no >= 1)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_hr_loan_schedule
    ON pharma.hr_loan_schedule (pid_loan, installment_no);

CREATE INDEX IF NOT EXISTS ix_hr_schedule_due
    ON pharma.hr_loan_schedule (due_date)
 WHERE schedule_status IN ('PENDING','PARTIAL') AND status = 'Y';


/* ═══ Verification ══════════════════════════════════════════════════════════ */

SELECT 'hr_advance'       AS table_name, count(*)::text AS rows FROM pharma.hr_advance
UNION ALL SELECT 'hr_loan',          count(*)::text FROM pharma.hr_loan
UNION ALL SELECT 'hr_loan_schedule', count(*)::text FROM pharma.hr_loan_schedule;
