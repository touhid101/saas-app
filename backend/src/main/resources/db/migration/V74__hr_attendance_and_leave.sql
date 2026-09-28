/* ─────────────────────────────────────────────────────────────────────────────
   HR, part 2 — attendance and leave.

   These two are one migration because they answer the same question from two
   directions: how many days of the month did this person work, and of the days
   they did not, which were authorised. Payroll reads both and cannot read
   either alone — an absence with an approved leave request behind it is not the
   same absence.

   ── The duplicate-attendance rule is an index, not a service check ──

   One row per employee per day, enforced by a unique index rather than by a
   SELECT-then-INSERT in the service. Two devices posting the same punch at the
   same moment both pass a service check and both insert; the index refuses the
   second whatever order they arrive in. The service still checks first, so the
   user gets a sentence rather than a constraint name — but the index is what
   makes the rule true.
   ───────────────────────────────────────────────────────────────────────────── */

CREATE SEQUENCE IF NOT EXISTS pharma.seq_hr_attendance     START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_hr_leave_request  START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_hr_leave_balance  START WITH 1 INCREMENT BY 1;


/* ═══ Attendance ════════════════════════════════════════════════════════════

   Minutes, not hours, for every duration. Hours as a decimal loses a minute to
   rounding on almost every row, and overtime paid on a rounded hour is a
   complaint waiting to happen. The screen shows hours; the table holds minutes.

   CHECK_IN and CHECK_OUT are timestamps rather than times of day, because a
   night shift ends on the following date and a TIME column cannot say so. */

CREATE TABLE IF NOT EXISTS pharma.hr_attendance (
    pid_attendance       BIGINT      NOT NULL,
    pid_org              BIGINT      NOT NULL,
    pid_company          BIGINT,
    pid_branch           BIGINT      NOT NULL,
    pid_employee         BIGINT      NOT NULL,

    attendance_date      DATE        NOT NULL,
    check_in             TIMESTAMP,
    check_out            TIMESTAMP,

    working_minutes      INT         DEFAULT 0,
    late_minutes         INT         DEFAULT 0,
    early_leave_minutes  INT         DEFAULT 0,
    overtime_minutes     INT         DEFAULT 0,

    /* PRESENT, ABSENT, LATE, EARLY_LEAVE, HALF_DAY, LEAVE, HOLIDAY, WEEKEND.
       LEAVE is written by the leave module when a request is approved, so a
       month has one row per day per employee whatever happened on it and
       payroll can count days without joining three tables. */
    attendance_status    VARCHAR(20) NOT NULL,

    /* Where the row came from. MANUAL is somebody typing it, and is the only
       one a user may edit afterwards — a punch from a device is evidence. */
    source               VARCHAR(20) DEFAULT 'MANUAL',
    pid_leave_request    BIGINT,
    remarks              VARCHAR(500),

    status               VARCHAR(1)  DEFAULT 'Y',
    inserted_by          VARCHAR(50),
    inserted_date        TIMESTAMP,
    updated_by           VARCHAR(50),
    updated_date         TIMESTAMP,

    CONSTRAINT hr_attendance_pkey PRIMARY KEY (pid_attendance),
    CONSTRAINT fk_hr_attendance_employee FOREIGN KEY (pid_employee)
        REFERENCES pharma.hr_employees (pid_employee),
    CONSTRAINT ck_hr_attendance_status CHECK (attendance_status IN
        ('PRESENT','ABSENT','LATE','EARLY_LEAVE','HALF_DAY','LEAVE','HOLIDAY','WEEKEND')),
    CONSTRAINT ck_hr_attendance_source CHECK (source IN ('MANUAL','DEVICE','IMPORT','SELF')),
    CONSTRAINT ck_hr_attendance_minutes CHECK (
            COALESCE(working_minutes,0)     >= 0
        AND COALESCE(late_minutes,0)        >= 0
        AND COALESCE(early_leave_minutes,0) >= 0
        AND COALESCE(overtime_minutes,0)    >= 0),
    /* Out before in is a data-entry slip that would otherwise produce a
       negative working day and a negative wage. */
    CONSTRAINT ck_hr_attendance_order CHECK (
        check_out IS NULL OR check_in IS NULL OR check_out >= check_in)
);

/* The rule. Cancelled rows are excluded so a day struck out by mistake can be
   entered again rather than being permanently unavailable. */
CREATE UNIQUE INDEX IF NOT EXISTS uq_hr_attendance_day
    ON pharma.hr_attendance (pid_employee, attendance_date)
 WHERE status = 'Y';

/* The two reads the module actually makes: one employee's month, and one
   branch's day. */
CREATE INDEX IF NOT EXISTS ix_hr_attendance_emp_date
    ON pharma.hr_attendance (pid_employee, attendance_date DESC);
CREATE INDEX IF NOT EXISTS ix_hr_attendance_branch_date
    ON pharma.hr_attendance (pid_branch, attendance_date DESC, status);


/* ═══ Leave request ═════════════════════════════════════════════════════════

   DRAFT → SUBMITTED → APPROVED | REJECTED, and CANCELLED from any of the first
   three. An approved request is never edited: the fields that record who
   approved it and when are the paper trail, and rewriting the request under
   them would leave the trail describing something that no longer exists.

   DAYS is stored rather than derived from the dates because a half day is
   half a day, and because a request spanning a weekend does not count the
   weekend. What the service computed is what payroll must read back. */

CREATE TABLE IF NOT EXISTS pharma.hr_leave_request (
    pid_leave_request  BIGINT       NOT NULL,
    pid_org            BIGINT       NOT NULL,
    pid_company        BIGINT,
    pid_branch         BIGINT       NOT NULL,
    pid_employee       BIGINT       NOT NULL,
    pid_leave_type     BIGINT       NOT NULL,

    request_no         VARCHAR(30),
    from_date          DATE         NOT NULL,
    to_date            DATE         NOT NULL,
    days               NUMERIC(5,2) NOT NULL,
    is_half_day        SMALLINT     DEFAULT 0,
    reason             VARCHAR(1000),

    request_status     VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    submitted_by       VARCHAR(50),
    submitted_at       TIMESTAMP,
    decided_by         VARCHAR(50),
    decided_at         TIMESTAMP,
    decision_remarks   VARCHAR(1000),
    cancelled_reason   VARCHAR(500),

    status             VARCHAR(1)   DEFAULT 'Y',
    inserted_by        VARCHAR(50),
    inserted_date      TIMESTAMP,
    updated_by         VARCHAR(50),
    updated_date       TIMESTAMP,

    CONSTRAINT hr_leave_request_pkey PRIMARY KEY (pid_leave_request),
    CONSTRAINT fk_hr_leave_req_employee FOREIGN KEY (pid_employee)
        REFERENCES pharma.hr_employees (pid_employee),
    CONSTRAINT fk_hr_leave_req_type FOREIGN KEY (pid_leave_type)
        REFERENCES pharma.hr_leave_type (pid_leave_type),
    CONSTRAINT ck_hr_leave_req_status CHECK (request_status IN
        ('DRAFT','SUBMITTED','APPROVED','REJECTED','CANCELLED')),
    CONSTRAINT ck_hr_leave_req_dates CHECK (to_date >= from_date),
    CONSTRAINT ck_hr_leave_req_days  CHECK (days > 0)
);

CREATE INDEX IF NOT EXISTS ix_hr_leave_req_employee
    ON pharma.hr_leave_request (pid_employee, from_date DESC);
CREATE INDEX IF NOT EXISTS ix_hr_leave_req_branch_status
    ON pharma.hr_leave_request (pid_branch, request_status, from_date DESC);
/* The approval queue is the screen this module is opened for. */
CREATE INDEX IF NOT EXISTS ix_hr_leave_req_pending
    ON pharma.hr_leave_request (pid_branch, from_date)
 WHERE request_status = 'SUBMITTED' AND status = 'Y';


/* ═══ Leave balance ═════════════════════════════════════════════════════════

   One row per employee, per type, per leave year.

   ENTITLED is copied from the type's quota when the balance is opened rather
   than read through a join, because changing next year's policy must not
   silently rewrite what somebody was entitled to last year.

   PENDING and TAKEN are held separately: a submitted request must reduce what
   the employee can still ask for, or two requests filed the same morning both
   pass the balance check. Available = ENTITLED + CARRIED − TAKEN − PENDING. */

CREATE TABLE IF NOT EXISTS pharma.hr_leave_balance (
    pid_balance     BIGINT       NOT NULL,
    pid_org         BIGINT       NOT NULL,
    pid_branch      BIGINT       NOT NULL,
    pid_employee    BIGINT       NOT NULL,
    pid_leave_type  BIGINT       NOT NULL,
    leave_year      INT          NOT NULL,

    entitled        NUMERIC(6,2) NOT NULL DEFAULT 0,
    carried_forward NUMERIC(6,2) NOT NULL DEFAULT 0,
    taken           NUMERIC(6,2) NOT NULL DEFAULT 0,
    pending         NUMERIC(6,2) NOT NULL DEFAULT 0,

    status          VARCHAR(1)   DEFAULT 'Y',
    inserted_by     VARCHAR(50),
    inserted_date   TIMESTAMP,
    updated_by      VARCHAR(50),
    updated_date    TIMESTAMP,

    CONSTRAINT hr_leave_balance_pkey PRIMARY KEY (pid_balance),
    CONSTRAINT fk_hr_leave_bal_employee FOREIGN KEY (pid_employee)
        REFERENCES pharma.hr_employees (pid_employee),
    CONSTRAINT fk_hr_leave_bal_type FOREIGN KEY (pid_leave_type)
        REFERENCES pharma.hr_leave_type (pid_leave_type),
    CONSTRAINT ck_hr_leave_bal_amounts CHECK (
        entitled >= 0 AND carried_forward >= 0 AND taken >= 0 AND pending >= 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_hr_leave_balance
    ON pharma.hr_leave_balance (pid_employee, pid_leave_type, leave_year);

CREATE INDEX IF NOT EXISTS ix_hr_leave_balance_branch
    ON pharma.hr_leave_balance (pid_branch, leave_year);


/* ═══ Verification ══════════════════════════════════════════════════════════ */

SELECT 'hr_attendance'     AS table_name, count(*)::text AS rows FROM pharma.hr_attendance
UNION ALL SELECT 'hr_leave_request', count(*)::text FROM pharma.hr_leave_request
UNION ALL SELECT 'hr_leave_balance', count(*)::text FROM pharma.hr_leave_balance;
