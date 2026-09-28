/* ─────────────────────────────────────────────────────────────────────────────
   HR — what a correction is asking for.

   V79 added CORRECTION_STATUS, CORRECTED_BY, CORRECTED_AT and
   CORRECTION_REASON: enough to record that a day was disputed and who decided,
   but not what was actually being asked for. Without that the only way to build
   the workflow is to apply the change immediately and treat approval as a
   decision about whether to keep it — which means the register shows the
   corrected figure before anybody has agreed to it, and a rejection has to
   reconstruct the original from nothing.

   So the proposal is held beside the current values, and the row keeps saying
   what it said until somebody with the Approve right decides. That is the whole
   difference between a correction workflow and an edit.

   ── Why this is not folded into V79 ──

   V79 is applied. Editing it would change a file the schema history has already
   hashed, and Flyway would refuse to start on any database that ran the old one.
   Forward-only, always.
   ───────────────────────────────────────────────────────────────────────────── */

ALTER TABLE pharma.hr_attendance
    /* What the request wants the day to become. All nullable: a correction may
       ask to change only the times, or only the status. */
    ADD COLUMN IF NOT EXISTS proposed_status           VARCHAR(20),
    ADD COLUMN IF NOT EXISTS proposed_check_in         TIMESTAMP,
    ADD COLUMN IF NOT EXISTS proposed_check_out        TIMESTAMP,
    ADD COLUMN IF NOT EXISTS proposed_overtime_minutes INT,

    /* Who asked, and when. CORRECTED_BY and CORRECTED_AT from V79 are the
       decision; these are the request, and a workflow needs both ends. */
    ADD COLUMN IF NOT EXISTS correction_requested_by   VARCHAR(50),
    ADD COLUMN IF NOT EXISTS correction_requested_at   TIMESTAMP;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conname = 'ck_hr_attendance_proposed_status') THEN
        ALTER TABLE pharma.hr_attendance
            ADD CONSTRAINT ck_hr_attendance_proposed_status
            CHECK (proposed_status IS NULL OR proposed_status IN
                   ('PRESENT','ABSENT','LATE','EARLY_LEAVE','HALF_DAY','LEAVE','HOLIDAY','WEEKEND'))
            NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conname = 'ck_hr_attendance_proposed_ot') THEN
        ALTER TABLE pharma.hr_attendance
            ADD CONSTRAINT ck_hr_attendance_proposed_ot
            CHECK (proposed_overtime_minutes IS NULL OR proposed_overtime_minutes >= 0)
            NOT VALID;
    END IF;
    /* Out before in, on the proposal this time. The same guard the live columns
       already carry — a correction should not be able to introduce a state the
       original row could not hold. */
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conname = 'ck_hr_attendance_proposed_order') THEN
        ALTER TABLE pharma.hr_attendance
            ADD CONSTRAINT ck_hr_attendance_proposed_order
            CHECK (proposed_check_out IS NULL OR proposed_check_in IS NULL
                   OR proposed_check_out >= proposed_check_in)
            NOT VALID;
    END IF;
END $$;


/* ═══ Verification ══════════════════════════════════════════════════════════ */

SELECT 'attendance rows'          AS object, count(*)::text AS value FROM pharma.hr_attendance
UNION ALL
SELECT 'corrections outstanding', count(*)::text FROM pharma.hr_attendance
 WHERE correction_status = 'REQUESTED';
