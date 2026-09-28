/* ─────────────────────────────────────────────────────────────────────────────
   HR — overtime claims, and salary increments.

   ══ Overtime, and the double-count that had to be avoided ══

   Payroll already values overtime: PayrollCalculator reads
   HR_ATTENDANCE.OVERTIME_MINUTES and multiplies it by the structure's rate. So
   a claim table that ALSO fed payroll would pay every approved hour twice.

   The claim therefore does not feed payroll. It feeds the attendance row:
   approving a claim writes OVERTIME_MINUTES onto the day, and payroll goes on
   reading exactly what it read before. One source of truth, and the workflow
   sits in front of it.

   This is the same shape as leave, which does not feed payroll either — it
   writes LEAVE attendance rows and lets payroll count days from the register.

   PID_ATTENDANCE records which row a claim was applied to, so cancelling one
   takes back exactly the minutes it added rather than zeroing a day that had
   overtime for another reason.

   ══ Increment, and the figure that must not move underneath it ══

   PREVIOUS_SALARY, INCREMENT_AMOUNT and NEW_SALARY are frozen when the
   increment is APPROVED, not computed again when it is applied. A percentage
   approved in March against 20,000 is worth 2,000; if somebody edits the basic
   salary to 25,000 in April, applying that increment must still be the 2,000
   that was signed off, not 2,500 nobody approved.

   The service refuses to apply an increment whose PREVIOUS_SALARY no longer
   matches what the employee is actually on — the underlying figure moved, and
   silently overwriting it would discard whatever change did that.
   ───────────────────────────────────────────────────────────────────────────── */

CREATE SEQUENCE IF NOT EXISTS pharma.seq_hr_overtime_request START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_hr_increment        START WITH 1 INCREMENT BY 1;


/* ═══ Overtime claim ════════════════════════════════════════════════════════

   One day per claim. A range would have to fan out to one attendance row per
   date on approval anyway, and a partial approval across a range has no obvious
   meaning — approving four of seven days is four decisions, not one. */

CREATE TABLE IF NOT EXISTS pharma.hr_overtime_request (
    pid_ot_request    BIGINT       NOT NULL,
    pid_org           BIGINT       NOT NULL,
    pid_company       BIGINT,
    pid_branch        BIGINT       NOT NULL,
    pid_employee      BIGINT       NOT NULL,

    request_no        VARCHAR(30),
    ot_date           DATE         NOT NULL,

    /* Minutes, like everything else about attendance. Hours as a decimal loses
       a minute to rounding on almost every row, and overtime paid on a rounded
       hour is a complaint waiting to happen. */
    claimed_minutes   INT          NOT NULL,
    /* What was actually granted. Separate from the claim, because an approver
       who cuts it has made a decision worth being able to see afterwards. */
    approved_minutes  INT,

    reason            VARCHAR(1000),
    request_status    VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',

    submitted_by      VARCHAR(50), submitted_at TIMESTAMP,
    decided_by        VARCHAR(50), decided_at   TIMESTAMP,
    decision_remarks  VARCHAR(1000),

    /* The attendance row the approved minutes were written to. Kept so
       cancelling takes back exactly what this claim added. */
    pid_attendance    BIGINT,
    applied_at        TIMESTAMP,

    status            VARCHAR(1)   DEFAULT 'Y',
    inserted_by       VARCHAR(50),
    inserted_date     TIMESTAMP,
    updated_by        VARCHAR(50),
    updated_date      TIMESTAMP,

    CONSTRAINT hr_overtime_request_pkey PRIMARY KEY (pid_ot_request),
    CONSTRAINT fk_hr_ot_employee FOREIGN KEY (pid_employee)
        REFERENCES pharma.hr_employees (pid_employee),
    CONSTRAINT fk_hr_ot_attendance FOREIGN KEY (pid_attendance)
        REFERENCES pharma.hr_attendance (pid_attendance),
    CONSTRAINT ck_hr_ot_status CHECK (request_status IN
        ('DRAFT','SUBMITTED','APPROVED','REJECTED','CANCELLED')),
    CONSTRAINT ck_hr_ot_claimed  CHECK (claimed_minutes > 0 AND claimed_minutes <= 24 * 60),
    CONSTRAINT ck_hr_ot_approved CHECK (approved_minutes IS NULL
        OR (approved_minutes > 0 AND approved_minutes <= claimed_minutes))
);

/* One live claim per person per day. Rejected and cancelled ones are excluded
   so a refused claim can be filed again with a better reason. */
CREATE UNIQUE INDEX IF NOT EXISTS uq_hr_ot_live
    ON pharma.hr_overtime_request (pid_employee, ot_date)
 WHERE request_status IN ('DRAFT','SUBMITTED','APPROVED') AND status = 'Y';

CREATE INDEX IF NOT EXISTS ix_hr_ot_branch_status
    ON pharma.hr_overtime_request (pid_branch, request_status, ot_date DESC);

/* The approval queue — the screen this module is opened for. */
CREATE INDEX IF NOT EXISTS ix_hr_ot_pending
    ON pharma.hr_overtime_request (pid_branch, ot_date)
 WHERE request_status = 'SUBMITTED' AND status = 'Y';


/* ═══ Increment ═════════════════════════════════════════════════════════════ */

CREATE TABLE IF NOT EXISTS pharma.hr_increment (
    pid_increment       BIGINT        NOT NULL,
    pid_org             BIGINT        NOT NULL,
    pid_company         BIGINT,
    pid_branch          BIGINT        NOT NULL,
    pid_employee        BIGINT        NOT NULL,

    increment_no        VARCHAR(30),
    /* ANNUAL, PROMOTION, PERFORMANCE, ADJUSTMENT. */
    increment_type      VARCHAR(20)   NOT NULL DEFAULT 'ANNUAL',
    /* PERCENT or FIXED — which of the two figures below is read. */
    increment_basis     VARCHAR(10)   NOT NULL,
    pct_value           NUMERIC(9,4),
    fixed_amount        NUMERIC(18,2),

    /* All three frozen at approval. See the header: a percentage signed off in
       March is worth what it was worth in March. */
    previous_salary     NUMERIC(18,2),
    increment_amount    NUMERIC(18,2),
    new_salary          NUMERIC(18,2),

    effective_date      DATE          NOT NULL,

    /* A promotion may also move the designation and the grade. Both optional:
       an annual increment usually moves neither. */
    pid_new_designation BIGINT,
    new_grade           VARCHAR(30),

    reason              VARCHAR(1000),
    increment_status    VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',

    submitted_by        VARCHAR(50), submitted_at TIMESTAMP,
    decided_by          VARCHAR(50), decided_at   TIMESTAMP,
    decision_remarks    VARCHAR(1000),
    applied_by          VARCHAR(50), applied_at   TIMESTAMP,

    status              VARCHAR(1)    DEFAULT 'Y',
    inserted_by         VARCHAR(50),
    inserted_date       TIMESTAMP,
    updated_by          VARCHAR(50),
    updated_date        TIMESTAMP,

    CONSTRAINT hr_increment_pkey PRIMARY KEY (pid_increment),
    CONSTRAINT fk_hr_increment_employee FOREIGN KEY (pid_employee)
        REFERENCES pharma.hr_employees (pid_employee),
    CONSTRAINT ck_hr_increment_type CHECK (increment_type IN
        ('ANNUAL','PROMOTION','PERFORMANCE','ADJUSTMENT')),
    CONSTRAINT ck_hr_increment_status CHECK (increment_status IN
        ('DRAFT','SUBMITTED','APPROVED','REJECTED','APPLIED','CANCELLED')),
    CONSTRAINT ck_hr_increment_basis CHECK (increment_basis IN ('PERCENT','FIXED')),
    /* An increment must be computable: a percentage needs a percentage, a fixed
       amount needs an amount. The same rule the salary components carry. */
    CONSTRAINT ck_hr_increment_value CHECK (
        (increment_basis = 'PERCENT' AND pct_value IS NOT NULL AND pct_value > 0)
     OR (increment_basis = 'FIXED'   AND fixed_amount IS NOT NULL AND fixed_amount <> 0)),
    /* A decrease is allowed — an adjustment can go down — but the result has to
       be a salary somebody could be paid. */
    CONSTRAINT ck_hr_increment_new CHECK (new_salary IS NULL OR new_salary > 0),
    /* An applied increment must say what it moved from and to, or the history
       it exists to provide is missing. */
    CONSTRAINT ck_hr_increment_applied CHECK (
        increment_status <> 'APPLIED'
        OR (previous_salary IS NOT NULL AND new_salary IS NOT NULL AND applied_at IS NOT NULL))
);

/* One increment per employee per effective date, among the live ones. */
CREATE UNIQUE INDEX IF NOT EXISTS uq_hr_increment_live
    ON pharma.hr_increment (pid_employee, effective_date)
 WHERE increment_status IN ('DRAFT','SUBMITTED','APPROVED') AND status = 'Y';

CREATE INDEX IF NOT EXISTS ix_hr_increment_branch
    ON pharma.hr_increment (pid_branch, increment_status, effective_date DESC);

/* One person's salary history, which is the read the profile makes. */
CREATE INDEX IF NOT EXISTS ix_hr_increment_employee
    ON pharma.hr_increment (pid_employee, effective_date DESC);

CREATE INDEX IF NOT EXISTS ix_hr_increment_pending
    ON pharma.hr_increment (pid_branch, effective_date)
 WHERE increment_status = 'SUBMITTED' AND status = 'Y';


/* ═══ Verification ══════════════════════════════════════════════════════════ */

SELECT 'hr_overtime_request' AS table_name, count(*)::text AS rows FROM pharma.hr_overtime_request
UNION ALL SELECT 'hr_increment', count(*)::text FROM pharma.hr_increment
UNION ALL SELECT 'attendance rows kept', count(*)::text FROM pharma.hr_attendance;
