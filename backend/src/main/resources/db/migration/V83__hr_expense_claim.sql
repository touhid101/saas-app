/* ─────────────────────────────────────────────────────────────────────────────
   HR — conveyance and expense claims.

   ══ A reimbursement is not pay, and must never reach the gross ══

   Somebody who takes a rickshaw to the wholesaler and is given the fare back has
   not earned anything. The money is theirs being returned.

   That matters more now than it would have a week ago. If a reimbursement were
   added to GROSS_SALARY it would sit inside the taxable base, and — since V82 —
   inside the provident fund base whenever a scheme is taken on gross. The
   pharmacy would deduct PF from a rickshaw fare and hand back less than was
   spent. So an approved claim is paid on its own, recorded with a date, a mode
   and a reference exactly as an advance disbursement is, and it never appears on
   a payslip.

   This is deliberately NOT the shape overtime took. Overtime is work, so it
   belongs on the payslip and the claim feeds the attendance register that
   payroll already reads. A reimbursement is not work, so it belongs beside the
   payslip and nothing about payroll changes.

   ══ Header and lines, because a claim is rarely one thing ══

   A week of conveyance is six journeys on six dates. Filing six claims to be
   approved six times is not how anybody works, and one row with a single amount
   and a paragraph of explanation cannot be checked against anything.

   Each line carries its own date, type, amount and — once approved — its own
   approved amount, because an approver who allows the fares and refuses the
   dinner has made two decisions and both should be visible.

   ══ What is policy, and therefore blank ══

   HR_EXPENSE_TYPE.MAX_PER_CLAIM and MAX_PER_DAY are NULLABLE with no default.
   A daily conveyance cap is a business rule this application cannot invent —
   the same answer P-02, P-07 and P-08 got. NULL means no cap has been decided,
   not a cap of nought, and nothing is refused for exceeding a limit nobody set.

   The type NAMES are seeded, because "Conveyance" and "Meal" are vocabulary
   rather than policy — exactly as V73 seeded leave type names while leaving
   every quota NULL. They can be renamed or retired.
   ───────────────────────────────────────────────────────────────────────────── */

CREATE SEQUENCE IF NOT EXISTS pharma.seq_hr_expense_type       START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_hr_expense_claim      START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_hr_expense_claim_line START WITH 1 INCREMENT BY 1;


/* ═══ What can be claimed for ═══════════════════════════════════════════════ */

CREATE TABLE IF NOT EXISTS pharma.hr_expense_type (
    pid_expense_type  BIGINT       NOT NULL,
    pid_org           BIGINT       NOT NULL,
    pid_company       BIGINT,
    pid_branch        BIGINT       NOT NULL,

    type_code         VARCHAR(20)  NOT NULL,
    type_name         VARCHAR(120) NOT NULL,
    description       VARCHAR(500),

    /* Both NULL: no cap has been decided. See the header — this is not a cap of
       nought, and a claim is not refused against a limit nobody set. */
    max_per_claim     NUMERIC(18,2),
    max_per_day       NUMERIC(18,2),

    /* Whether a photograph of the receipt is expected. Defaults to 0 so that
       nothing already in flight is suddenly incomplete; a pharmacy that wants
       receipts turns it on per type. */
    requires_receipt  SMALLINT     DEFAULT 0,

    sort_order        INT          DEFAULT 100,

    status            VARCHAR(1)   DEFAULT 'Y',
    inserted_by       VARCHAR(50),
    inserted_date     TIMESTAMP,
    updated_by        VARCHAR(50),
    updated_date      TIMESTAMP,

    CONSTRAINT hr_expense_type_pkey PRIMARY KEY (pid_expense_type),
    CONSTRAINT ck_hr_expense_type_receipt CHECK (COALESCE(requires_receipt,0) IN (0,1)),
    CONSTRAINT ck_hr_expense_type_max_claim CHECK (max_per_claim IS NULL OR max_per_claim > 0),
    CONSTRAINT ck_hr_expense_type_max_day   CHECK (max_per_day   IS NULL OR max_per_day   > 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_hr_expense_type_code
    ON pharma.hr_expense_type (pid_branch, UPPER(type_code))
 WHERE status = 'Y';


/* ═══ The claim ═════════════════════════════════════════════════════════════

   CLAIMED_AMOUNT and APPROVED_AMOUNT are both totals of the lines, stored on the
   header rather than summed on every read. The same reason a payroll run stores
   its totals: a figure somebody approved should still read the same after
   somebody edits a line by hand in the database, and a list of forty claims
   should not be forty sub-queries. */

CREATE TABLE IF NOT EXISTS pharma.hr_expense_claim (
    pid_expense_claim BIGINT       NOT NULL,
    pid_org           BIGINT       NOT NULL,
    pid_company       BIGINT,
    pid_branch        BIGINT       NOT NULL,
    pid_employee      BIGINT       NOT NULL,

    claim_no          VARCHAR(30),
    claim_date        DATE         NOT NULL,

    /* The span the lines cover, kept so a month's claims can be listed without
       reading every line. Maintained by the service from the lines themselves. */
    period_from       DATE,
    period_to         DATE,

    claimed_amount    NUMERIC(18,2) NOT NULL DEFAULT 0,
    approved_amount   NUMERIC(18,2),

    purpose           VARCHAR(1000),

    claim_status      VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',

    submitted_by      VARCHAR(50), submitted_at TIMESTAMP,
    decided_by        VARCHAR(50), decided_at   TIMESTAMP,
    decision_remarks  VARCHAR(1000),

    /* Paid on its own, never through a payslip. See the header. */
    paid_date         DATE,
    payment_mode      VARCHAR(20),
    payment_reference VARCHAR(60),
    paid_by           VARCHAR(50),

    status            VARCHAR(1)   DEFAULT 'Y',
    inserted_by       VARCHAR(50),
    inserted_date     TIMESTAMP,
    updated_by        VARCHAR(50),
    updated_date      TIMESTAMP,

    CONSTRAINT hr_expense_claim_pkey PRIMARY KEY (pid_expense_claim),
    CONSTRAINT fk_hr_expense_claim_employee FOREIGN KEY (pid_employee)
        REFERENCES pharma.hr_employees (pid_employee),
    CONSTRAINT ck_hr_expense_claim_status CHECK (claim_status IN
        ('DRAFT','SUBMITTED','APPROVED','REJECTED','PAID','CANCELLED')),
    CONSTRAINT ck_hr_expense_claim_amount CHECK (claimed_amount >= 0),
    CONSTRAINT ck_hr_expense_claim_approved CHECK (approved_amount IS NULL
        OR (approved_amount >= 0 AND approved_amount <= claimed_amount)),
    /* Paid means paid: a date and how, or it is not that status. */
    CONSTRAINT ck_hr_expense_claim_paid CHECK (claim_status <> 'PAID'
        OR (paid_date IS NOT NULL AND payment_mode IS NOT NULL
            AND approved_amount IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS ix_hr_expense_claim_branch
    ON pharma.hr_expense_claim (pid_branch, claim_status, claim_date DESC);

CREATE INDEX IF NOT EXISTS ix_hr_expense_claim_employee
    ON pharma.hr_expense_claim (pid_employee, claim_date DESC);

CREATE INDEX IF NOT EXISTS ix_hr_expense_claim_pending
    ON pharma.hr_expense_claim (pid_branch, claim_date)
 WHERE claim_status = 'SUBMITTED' AND status = 'Y';


/* ═══ One expense ═══════════════════════════════════════════════════════════ */

CREATE TABLE IF NOT EXISTS pharma.hr_expense_claim_line (
    pid_expense_line  BIGINT       NOT NULL,
    pid_expense_claim BIGINT       NOT NULL,
    pid_expense_type  BIGINT       NOT NULL,

    expense_date      DATE         NOT NULL,

    /* Where and where to, for a journey. Free text, because a pharmacy's
       delivery round is not a route table anybody is going to maintain. */
    from_place        VARCHAR(120),
    to_place          VARCHAR(120),

    description       VARCHAR(500),

    claimed_amount    NUMERIC(18,2) NOT NULL,
    /* What the approver allowed on THIS line. Null until decided; a line
       approved at less than it asked for is a decision worth seeing beside the
       line rather than buried in a total. */
    approved_amount   NUMERIC(18,2),

    /* Public URL of the receipt photograph, relative to the configured upload
       prefix. Never an absolute machine path — the root moves between machines
       and a path baked into a row does not. */
    receipt_path      VARCHAR(300),

    sort_order        INT          DEFAULT 100,

    status            VARCHAR(1)   DEFAULT 'Y',
    inserted_by       VARCHAR(50),
    inserted_date     TIMESTAMP,
    updated_by        VARCHAR(50),
    updated_date      TIMESTAMP,

    CONSTRAINT hr_expense_claim_line_pkey PRIMARY KEY (pid_expense_line),
    CONSTRAINT fk_hr_expense_line_claim FOREIGN KEY (pid_expense_claim)
        REFERENCES pharma.hr_expense_claim (pid_expense_claim),
    CONSTRAINT fk_hr_expense_line_type FOREIGN KEY (pid_expense_type)
        REFERENCES pharma.hr_expense_type (pid_expense_type),
    CONSTRAINT ck_hr_expense_line_amount CHECK (claimed_amount > 0),
    CONSTRAINT ck_hr_expense_line_approved CHECK (approved_amount IS NULL
        OR (approved_amount >= 0 AND approved_amount <= claimed_amount))
);

CREATE INDEX IF NOT EXISTS ix_hr_expense_line_claim
    ON pharma.hr_expense_claim_line (pid_expense_claim, sort_order, pid_expense_line);

CREATE INDEX IF NOT EXISTS ix_hr_expense_line_type
    ON pharma.hr_expense_claim_line (pid_expense_type, expense_date);


/* ═══ The vocabulary, with no limits on it ══════════════════════════════════

   Names only. Every cap stays NULL, which is the difference between "we have
   not decided what a day's conveyance is worth" and "a day's conveyance is
   worth nothing".

   Seeded for every branch that exists, the same way V73 seeded leave types, and
   guarded so re-running adds nothing. The sequence is moved past whatever the
   table already holds before a single row is inserted — rows loaded with
   explicit ids do not move a sequence, and the next insert after one would
   collide. */

SELECT setval('pharma.seq_hr_expense_type',
              GREATEST((SELECT COALESCE(MAX(pid_expense_type), 0) FROM pharma.hr_expense_type),
                       (SELECT last_value FROM pharma.seq_hr_expense_type)),
              true);

INSERT INTO pharma.hr_expense_type
    (pid_expense_type, pid_org, pid_company, pid_branch,
     type_code, type_name, description,
     max_per_claim, max_per_day, requires_receipt, sort_order,
     status, inserted_by, inserted_date)
SELECT nextval('pharma.seq_hr_expense_type'),
       b.pid_org, b.pid_company, b.pid_branch,
       v.code, v.name, v.note,
       NULL, NULL, 0, v.ord,
       'Y', 'SYSTEM', CURRENT_TIMESTAMP
  FROM pharma.all_branch b
 CROSS JOIN (VALUES
        ('CONVEY',  'Conveyance',      'Fares and travel on company business',      10),
        ('MEAL',    'Meal',            'Meals while working away or late',          20),
        ('MEDICAL', 'Medical',         'Medical costs the pharmacy reimburses',     30),
        ('PHONE',   'Phone & Internet','Calls and data used for work',              40),
        ('OTHER',   'Other',           'Anything the other types do not cover',     50)
    ) AS v(code, name, note, ord)
 WHERE b.status = 'Y'
   AND NOT EXISTS (SELECT 1 FROM pharma.hr_expense_type t
                    WHERE t.pid_branch = b.pid_branch
                      AND UPPER(t.type_code) = v.code);


/* ═══ Permissions ═══════════════════════════════════════════════════════════

   MOD37 under the existing HR group. Unlike PF and gratuity — which are payroll
   money and part of leaving, and so sit under modules that already exist — an
   expense claim is its own thing that ordinary staff file for themselves. It
   needs a right of its own so a pharmacy can let everybody claim without
   letting everybody see payroll.

   Registered exactly the way V77 registered the other five: the module, the
   role grants, the plan ceiling, and the onboarding templates. Miss the ceiling
   and the screen is invisible however the roles are set, which reads to a
   manager as the feature not having shipped. */

SELECT setval('pharma.sq_acl_module',
    GREATEST((SELECT COALESCE(MAX(pid_module), 0) FROM pharma.acl_module_info),
             (SELECT last_value FROM pharma.sq_acl_module)),
    true);

SELECT setval('pharma.sq_pos_perm',
    GREATEST((SELECT COALESCE(MAX(pid_permission), 0) FROM pharma.pos_permissions),
             (SELECT last_value FROM pharma.sq_pos_perm)),
    true);

SELECT setval('pharma.sq_tenant_module',
    GREATEST((SELECT COALESCE(MAX(pid_tenant_module), 0) FROM pharma.acl_tenant_module),
             (SELECT last_value FROM pharma.sq_tenant_module)),
    true);

SELECT setval('pharma.sq_template_line',
    GREATEST((SELECT COALESCE(MAX(pid_template_line), 0) FROM pharma.acl_perm_template_line),
             (SELECT last_value FROM pharma.sq_template_line)),
    true);

INSERT INTO pharma.acl_module_info
       (pid_module, module_code, module_name, module_desc, route_path, icon_key,
        pid_parent_module, sort_order, is_active, status, inserted_by)
SELECT nextval('pharma.sq_acl_module'), 'MOD37', 'Expense Claims',
       'What staff spent on the pharmacy''s behalf, and getting it back. Never paid through a payslip',
       '/expenses', 'advance',
       (SELECT pid_module FROM pharma.acl_module_info WHERE module_code = 'GRP_HR'),
       6, 1, 'Y', 'V83'
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_module_info x WHERE x.module_code = 'MOD37');

/* ROLE01 and ROLE02 get everything, approval included. */
INSERT INTO pharma.pos_permissions
       (pid_permission, pid_role, pid_module,
        can_view, can_create, can_edit, can_delete, can_export, can_approve, can_print,
        status, inserted_by)
SELECT nextval('pharma.sq_pos_perm'), g.pid_group, n.pid_module,
       1, 1, 1, 1, 1, 1, 1, 'Y', 'V83'
  FROM pharma.acl_group g
 CROSS JOIN (SELECT pid_module FROM pharma.acl_module_info WHERE module_code = 'MOD37') n
 WHERE g.role_code IN ('ROLE01', 'ROLE02')
   AND NOT EXISTS (SELECT 1 FROM pharma.pos_permissions x
                    WHERE x.pid_role = g.pid_group AND x.pid_module = n.pid_module);

/* ROLE04 files and cannot decide — the same separation V77 drew everywhere
   else in HR. Whether the rest of the staff may claim is a decision for the
   pharmacy on the RBAC screen, not one this migration makes for them. */
INSERT INTO pharma.pos_permissions
       (pid_permission, pid_role, pid_module,
        can_view, can_create, can_edit, can_delete, can_export, can_approve, can_print,
        status, inserted_by)
SELECT nextval('pharma.sq_pos_perm'), g.pid_group, n.pid_module,
       1, 1, 1, 0, 1, 0, 1, 'Y', 'V83'
  FROM pharma.acl_group g
 CROSS JOIN (SELECT pid_module FROM pharma.acl_module_info WHERE module_code = 'MOD37') n
 WHERE g.role_code = 'ROLE04'
   AND NOT EXISTS (SELECT 1 FROM pharma.pos_permissions x
                    WHERE x.pid_role = g.pid_group AND x.pid_module = n.pid_module);

INSERT INTO pharma.acl_tenant_module
       (pid_tenant_module, pid_branch, pid_module,
        can_view, can_create, can_edit, can_delete, can_approve, can_export, can_print,
        status, inserted_by)
SELECT nextval('pharma.sq_tenant_module'), b.pid_branch, n.pid_module,
       1, 1, 1, 1, 1, 1, 1, 'Y', 'V83'
  FROM (SELECT DISTINCT pid_branch FROM pharma.acl_tenant_module) b
 CROSS JOIN (SELECT pid_module FROM pharma.acl_module_info WHERE module_code = 'MOD37') n
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_tenant_module x
                    WHERE x.pid_branch = b.pid_branch AND x.pid_module = n.pid_module);

INSERT INTO pharma.acl_perm_template_line
       (pid_template_line, pid_template, pid_module,
        can_view, can_create, can_edit, can_delete, can_approve, can_export, can_print)
SELECT nextval('pharma.sq_template_line'), t.pid_template, n.pid_module,
       1, 1, 1, 1, 1, 1, 1
  FROM (SELECT DISTINCT pid_template FROM pharma.acl_perm_template_line) t
 CROSS JOIN (SELECT pid_module FROM pharma.acl_module_info WHERE module_code = 'MOD37') n
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_perm_template_line x
                    WHERE x.pid_template = t.pid_template AND x.pid_module = n.pid_module);


/* ═══ Verification ══════════════════════════════════════════════════════════ */

SELECT 'hr_expense_type'       AS table_name, count(*)::text AS rows FROM pharma.hr_expense_type
UNION ALL SELECT 'hr_expense_claim',      count(*)::text FROM pharma.hr_expense_claim
UNION ALL SELECT 'hr_expense_claim_line', count(*)::text FROM pharma.hr_expense_claim_line
UNION ALL SELECT 'types with a cap set',
       count(*)::text FROM pharma.hr_expense_type
        WHERE max_per_claim IS NOT NULL OR max_per_day IS NOT NULL
UNION ALL SELECT 'MOD37 role grants',
       count(*)::text FROM pharma.pos_permissions p
        JOIN pharma.acl_module_info m ON m.pid_module = p.pid_module
       WHERE m.module_code = 'MOD37'
UNION ALL SELECT 'MOD37 pharmacies',
       count(*)::text FROM pharma.acl_tenant_module t
        JOIN pharma.acl_module_info m ON m.pid_module = t.pid_module
       WHERE m.module_code = 'MOD37'
UNION ALL SELECT 'MOD37 templates',
       count(*)::text FROM pharma.acl_perm_template_line l
        JOIN pharma.acl_module_info m ON m.pid_module = l.pid_module
       WHERE m.module_code = 'MOD37';
