/* ─────────────────────────────────────────────────────────────────────────────
   HR — working hours, shifts, weekends and holidays.

   ── This closes an open question rather than inventing an answer ──

   02-hr-module-srs.md records P-04: the application held no weekly-off pattern
   and no holiday calendar, so leave counted every calendar day in a range and
   payroll had no way to tell a Friday from a Tuesday. The note said the fix was
   a working-day calendar per branch, and this is it.

   Nothing here decides what a weekend IS. WEEKEND_DAYS is a per-branch set of
   day numbers with no default beyond "not set", and a policy that names none
   behaves exactly as the application does today. A Friday-Saturday weekend, a
   Sunday one, and a shop that opens seven days are all normal here, and a
   seeded value would have been read as the answer.

   ── Three tables, because they answer three different questions ──

       HR_SHIFT           when a working day starts and ends
       HR_WORKING_POLICY  which days are worked, and what counts as late
       HR_HOLIDAY         which specific dates are not worked

   A shift is reusable across branches; a policy belongs to one branch and names
   a default shift; a holiday is a date, optionally narrowed to one branch.

   ── Overnight shifts ──

   END_TIME may be earlier than START_TIME, and that is not an error: a shift
   running 22:00 to 06:00 ends on the following date. CROSSES_MIDNIGHT is stored
   rather than derived so the rule is stated once, in the row, instead of being
   re-inferred by every reader.
   ───────────────────────────────────────────────────────────────────────────── */

CREATE SEQUENCE IF NOT EXISTS pharma.seq_hr_shift          START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_hr_working_policy START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_hr_holiday        START WITH 1 INCREMENT BY 1;


/* ═══ Shift ═════════════════════════════════════════════════════════════════ */

CREATE TABLE IF NOT EXISTS pharma.hr_shift (
    pid_shift        BIGINT       NOT NULL,
    pid_org          BIGINT       NOT NULL,
    pid_company      BIGINT,
    pid_branch       BIGINT       NOT NULL,

    shift_code       VARCHAR(20)  NOT NULL,
    shift_name       VARCHAR(100) NOT NULL,
    start_time       TIME         NOT NULL,
    end_time         TIME         NOT NULL,

    /* Set when END_TIME is earlier than START_TIME. Stored rather than derived:
       a night shift ending at 06:00 is a fact about the shift, and every reader
       working it out again is a chance for one of them to get it wrong. */
    crosses_midnight SMALLINT     NOT NULL DEFAULT 0,

    break_minutes    INT          NOT NULL DEFAULT 0,
    /* Minutes after START_TIME that are still not late. */
    grace_minutes    INT          NOT NULL DEFAULT 0,
    /* What a full day of this shift is worth, net of the break. Stored because
       a shift edited next year must not change what last year's attendance was
       measured against. */
    working_minutes  INT,

    status           VARCHAR(1)   DEFAULT 'Y',
    inserted_by      VARCHAR(50),
    inserted_date    TIMESTAMP,
    updated_by       VARCHAR(50),
    updated_date     TIMESTAMP,

    CONSTRAINT hr_shift_pkey PRIMARY KEY (pid_shift),
    CONSTRAINT ck_hr_shift_break CHECK (break_minutes >= 0 AND break_minutes < 24 * 60),
    CONSTRAINT ck_hr_shift_grace CHECK (grace_minutes >= 0 AND grace_minutes < 12 * 60),
    CONSTRAINT ck_hr_shift_overnight CHECK (crosses_midnight IN (0, 1)),
    CONSTRAINT ck_hr_shift_minutes CHECK (working_minutes IS NULL
                                          OR (working_minutes > 0 AND working_minutes <= 24 * 60))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_hr_shift_code
    ON pharma.hr_shift (pid_branch, upper(shift_code));

CREATE INDEX IF NOT EXISTS ix_hr_shift_branch
    ON pharma.hr_shift (pid_branch, status);


/* ═══ Working policy ════════════════════════════════════════════════════════

   One live policy per branch at a time, effective-dated. Editing a policy in
   place would rewrite what last month's attendance was judged against, so a
   change is a new row with a new EFFECTIVE_FROM and the old one closed off.

   WEEKEND_DAYS holds ISO day numbers, 1 = Monday through 7 = Sunday, comma
   separated. An array would be tidier, but this column is read by report SQL as
   often as by Java and a plain string is greppable in psql.

   NULL means the branch has not decided, and the application then treats every
   day as a working day — which is what it did before this table existed. That
   is deliberate: an empty policy changes nothing. */

CREATE TABLE IF NOT EXISTS pharma.hr_working_policy (
    pid_policy         BIGINT       NOT NULL,
    pid_org            BIGINT       NOT NULL,
    pid_company        BIGINT,
    pid_branch         BIGINT       NOT NULL,

    policy_name        VARCHAR(120) NOT NULL,
    pid_default_shift  BIGINT,

    /* ISO day numbers, 1=Mon .. 7=Sun. "5,6" is a Friday-Saturday weekend.
       NULL or empty means no weekly off has been decided. */
    weekend_days       VARCHAR(20),

    /* Minutes late before the day is marked LATE, on top of the shift's grace. */
    late_threshold_minutes  INT     NOT NULL DEFAULT 0,
    /* Minutes short of the shift end before the day is marked EARLY_LEAVE. */
    early_leave_threshold_minutes INT NOT NULL DEFAULT 0,
    /* Below this, a present day is only half a day. NULL disables the rule. */
    half_day_minutes   INT,
    /* Below this, a day is not counted as worked at all. NULL disables it. */
    min_working_minutes INT,

    /* Whether overtime may be claimed here at all, and the least that counts.
       What an overtime hour is WORTH stays on the salary structure — this says
       whether it may be claimed, not what it pays. */
    overtime_allowed   SMALLINT     NOT NULL DEFAULT 0,
    overtime_min_minutes INT        NOT NULL DEFAULT 0,

    effective_from     DATE         NOT NULL,
    effective_to       DATE,

    status             VARCHAR(1)   DEFAULT 'Y',
    inserted_by        VARCHAR(50),
    inserted_date      TIMESTAMP,
    updated_by         VARCHAR(50),
    updated_date       TIMESTAMP,

    CONSTRAINT hr_working_policy_pkey PRIMARY KEY (pid_policy),
    CONSTRAINT fk_hr_policy_shift FOREIGN KEY (pid_default_shift)
        REFERENCES pharma.hr_shift (pid_shift),
    CONSTRAINT ck_hr_policy_dates CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT ck_hr_policy_overtime CHECK (overtime_allowed IN (0, 1)),
    CONSTRAINT ck_hr_policy_thresholds CHECK (
            late_threshold_minutes >= 0
        AND early_leave_threshold_minutes >= 0
        AND overtime_min_minutes >= 0
        AND (half_day_minutes    IS NULL OR half_day_minutes    > 0)
        AND (min_working_minutes IS NULL OR min_working_minutes > 0)),
    /* Every entry must be a day number. A typo here would silently make a
       weekday a weekend for the whole branch. */
    CONSTRAINT ck_hr_policy_weekend CHECK (
        weekend_days IS NULL OR weekend_days = '' OR weekend_days ~ '^[1-7](,[1-7])*$')
);

/* One policy in force per branch at a time. */
CREATE UNIQUE INDEX IF NOT EXISTS uq_hr_policy_current
    ON pharma.hr_working_policy (pid_branch)
 WHERE effective_to IS NULL AND status = 'Y';

CREATE INDEX IF NOT EXISTS ix_hr_policy_branch
    ON pharma.hr_working_policy (pid_branch, effective_from DESC);


/* ═══ Holiday ═══════════════════════════════════════════════════════════════

   PID_BRANCH is nullable and that is the point: a national holiday belongs to
   the organisation and applies everywhere, while a branch closing for a local
   occasion applies to one shop. A single table with a nullable branch answers
   both without a second table that would eventually disagree.

   Government holidays are not seeded. They change every year, they are declared
   by gazette rather than by formula, and a list invented here would be wrong by
   next year and trusted anyway. */

CREATE TABLE IF NOT EXISTS pharma.hr_holiday (
    pid_holiday    BIGINT       NOT NULL,
    pid_org        BIGINT       NOT NULL,
    pid_company    BIGINT,
    /* NULL means the whole organisation. */
    pid_branch     BIGINT,

    holiday_date   DATE         NOT NULL,
    holiday_name   VARCHAR(150) NOT NULL,
    /* GOVERNMENT, PUBLIC, COMPANY, SPECIAL, RELIGIOUS. */
    holiday_type   VARCHAR(20)  NOT NULL DEFAULT 'COMPANY',
    holiday_year   INT          NOT NULL,
    /* An unpaid holiday is a closure the employer does not pay for; it is rare
       and it is not the same thing as a weekend. */
    is_paid        SMALLINT     NOT NULL DEFAULT 1,
    description    VARCHAR(500),

    status         VARCHAR(1)   DEFAULT 'Y',
    inserted_by    VARCHAR(50),
    inserted_date  TIMESTAMP,
    updated_by     VARCHAR(50),
    updated_date   TIMESTAMP,

    CONSTRAINT hr_holiday_pkey PRIMARY KEY (pid_holiday),
    CONSTRAINT ck_hr_holiday_type CHECK (holiday_type IN
        ('GOVERNMENT','PUBLIC','COMPANY','SPECIAL','RELIGIOUS')),
    CONSTRAINT ck_hr_holiday_paid CHECK (is_paid IN (0, 1)),
    /* The year has to agree with the date, or a year filter silently loses
       rows and nobody notices until a December holiday is missing. */
    CONSTRAINT ck_hr_holiday_year CHECK (holiday_year = EXTRACT(YEAR FROM holiday_date))
);

/* One holiday per date per scope. COALESCE, because a NULL branch is a real
   scope — the organisation — and NULLs do not compare equal in a plain index. */
CREATE UNIQUE INDEX IF NOT EXISTS uq_hr_holiday_date
    ON pharma.hr_holiday (pid_org, COALESCE(pid_branch, 0), holiday_date)
 WHERE status = 'Y';

CREATE INDEX IF NOT EXISTS ix_hr_holiday_year
    ON pharma.hr_holiday (pid_org, holiday_year, holiday_date);

/* What the attendance and payroll readers actually ask: is this date a holiday
   for this branch, counting organisation-wide ones. */
CREATE INDEX IF NOT EXISTS ix_hr_holiday_lookup
    ON pharma.hr_holiday (holiday_date, pid_org)
 WHERE status = 'Y';


/* ═══ Attendance gains its shift and policy ═════════════════════════════════

   Nullable, because 281 rows already exist and were recorded before shifts did.
   A row with no shift is judged exactly as it is today — by what was typed —
   and one with a shift is judged against it. */

ALTER TABLE pharma.hr_attendance
    ADD COLUMN IF NOT EXISTS pid_shift        BIGINT,
    ADD COLUMN IF NOT EXISTS scheduled_in     TIMESTAMP,
    ADD COLUMN IF NOT EXISTS scheduled_out    TIMESTAMP,
    /* Set when a correction has been asked for and not yet decided, so the
       register can show a day as disputed without changing what it says. */
    ADD COLUMN IF NOT EXISTS correction_status VARCHAR(20),
    ADD COLUMN IF NOT EXISTS corrected_by     VARCHAR(50),
    ADD COLUMN IF NOT EXISTS corrected_at     TIMESTAMP,
    ADD COLUMN IF NOT EXISTS correction_reason VARCHAR(500);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_hr_attendance_correction') THEN
        ALTER TABLE pharma.hr_attendance
            ADD CONSTRAINT ck_hr_attendance_correction
            CHECK (correction_status IS NULL OR correction_status IN
                   ('REQUESTED','APPROVED','REJECTED'))
            NOT VALID;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS ix_hr_attendance_correction
    ON pharma.hr_attendance (pid_branch, attendance_date)
 WHERE correction_status = 'REQUESTED' AND status = 'Y';


/* ═══ Verification ══════════════════════════════════════════════════════════ */

SELECT 'hr_shift'          AS table_name, count(*)::text AS rows FROM pharma.hr_shift
UNION ALL SELECT 'hr_working_policy', count(*)::text FROM pharma.hr_working_policy
UNION ALL SELECT 'hr_holiday',        count(*)::text FROM pharma.hr_holiday
UNION ALL SELECT 'attendance rows kept', count(*)::text FROM pharma.hr_attendance;
