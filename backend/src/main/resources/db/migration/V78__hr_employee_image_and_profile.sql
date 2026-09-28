/* ─────────────────────────────────────────────────────────────────────────────
   HR — the employee photograph, and the rest of the personal record.

   ── Why a path and not the existing BLOB ──

   HR_EMPLOYEES already has EMP_IMAGE (an oid, so a large object), FILE_NAME and
   FILE_MIME_TYPE. Nothing in the application writes them: every other image in
   this product — avatars, branch logos, product images — goes to disk through
   ImageStorageService and the row keeps a URL. A second mechanism, used by one
   table, would need its own upload path, its own validation, its own serving
   endpoint and its own backup story.

   So this adds IMAGE_PATH beside the old columns and leaves them alone. They
   are not dropped: this migration must be safe on a production database, and a
   large object nobody has looked at might still hold the only copy of somebody's
   photograph. Dropping it is a separate decision for a later release, once the
   three existing rows are known to be empty.

   ── What goes in the column ──

   A public URL relative to the application, e.g.

       /uploads/employee_image/employee_42_9f3c1b8e4d2a4f7c9b1e5a6d8c0f2b34.jpg

   Never an absolute filesystem path. Where that folder actually sits is
   configuration — the UPLOAD_ROOT variable, plus /employee_image — and a
   machine-specific path in the database would break the moment the application
   moved.

   Note the variable is named here without the dollar-brace syntax on purpose.
   Flyway substitutes placeholders across the whole file, comments included, and
   an unresolvable one stops the application from starting rather than being
   ignored as prose. This is the same trap as an apostrophe inside a JPQL
   comment: a comment is not inert to the parser reading it.

   ── The rest of the columns ──

   Personal and employment facts the HR brief asks for that V73 did not add:
   a preferred name, marital status, father and mother, a reporting manager, a
   grade, a probation period and a notice period. All nullable, for the same
   reason V73's were: the table has rows, and a NOT NULL column on a populated
   table either fails or invents a value for real people.
   ───────────────────────────────────────────────────────────────────────────── */

ALTER TABLE pharma.hr_employees
    /* The public URL of the photograph. 300 characters is generous: the prefix
       is configurable and a UUID filename is 32 hex plus an extension. */
    ADD COLUMN IF NOT EXISTS image_path        VARCHAR(300),

    /* What the person is actually called, when that is not their legal name. */
    ADD COLUMN IF NOT EXISTS preferred_name    VARCHAR(50),
    ADD COLUMN IF NOT EXISTS marital_status    VARCHAR(20),
    ADD COLUMN IF NOT EXISTS father_name       VARCHAR(100),
    ADD COLUMN IF NOT EXISTS mother_name       VARCHAR(100),
    ADD COLUMN IF NOT EXISTS present_address   VARCHAR(500),

    /* Who this person reports to. A self-reference rather than a separate
       hierarchy table: a manager is an employee, and a second table would let
       the two disagree about who exists. */
    ADD COLUMN IF NOT EXISTS pid_manager       BIGINT,

    ADD COLUMN IF NOT EXISTS grade             VARCHAR(30),
    ADD COLUMN IF NOT EXISTS probation_months  INT,
    ADD COLUMN IF NOT EXISTS notice_period_days INT,

    /* Which shift this person normally works. Nullable, and the branch policy
       supplies a default when it is not set — see V79. */
    ADD COLUMN IF NOT EXISTS pid_shift         BIGINT,

    /* Whether the two long-service schemes apply to this person. Both default
       to off: switching somebody into a scheme is a decision, and defaulting to
       on would silently enrol everybody the day the column appeared. */
    ADD COLUMN IF NOT EXISTS pf_applicable       SMALLINT DEFAULT 0,
    ADD COLUMN IF NOT EXISTS gratuity_applicable SMALLINT DEFAULT 0,
    ADD COLUMN IF NOT EXISTS tax_applicable      SMALLINT DEFAULT 0;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_hr_employees_marital') THEN
        ALTER TABLE pharma.hr_employees
            ADD CONSTRAINT ck_hr_employees_marital
            CHECK (marital_status IS NULL OR marital_status IN
                   ('SINGLE','MARRIED','DIVORCED','WIDOWED','OTHER'))
            NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_hr_employees_flags') THEN
        ALTER TABLE pharma.hr_employees
            ADD CONSTRAINT ck_hr_employees_flags
            CHECK (COALESCE(pf_applicable,0)       IN (0,1)
               AND COALESCE(gratuity_applicable,0) IN (0,1)
               AND COALESCE(tax_applicable,0)      IN (0,1))
            NOT VALID;
    END IF;
    /* A person cannot report to themselves. Cycles further up are a service
       concern — a constraint cannot see them — but the one-step case is the one
       that happens by accident on a form. */
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_hr_employees_manager') THEN
        ALTER TABLE pharma.hr_employees
            ADD CONSTRAINT ck_hr_employees_manager
            CHECK (pid_manager IS NULL OR pid_manager <> pid_employee)
            NOT VALID;
    END IF;
END $$;

/* The team view: everybody who reports to one person. */
CREATE INDEX IF NOT EXISTS ix_hr_employee_manager
    ON pharma.hr_employees (pid_manager)
 WHERE pid_manager IS NOT NULL;


/* ═══ Verification ══════════════════════════════════════════════════════════ */

SELECT 'employees'                AS object, count(*)::text AS value FROM pharma.hr_employees
UNION ALL
SELECT 'with a photograph',       count(*)::text FROM pharma.hr_employees WHERE image_path IS NOT NULL
UNION ALL
SELECT 'legacy BLOB images kept', count(*)::text FROM pharma.hr_employees WHERE emp_image IS NOT NULL
UNION ALL
SELECT 'reporting to somebody',   count(*)::text FROM pharma.hr_employees WHERE pid_manager IS NOT NULL;
