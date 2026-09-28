-- Two layers under the role, and the templates that fill them in.
--
-- POS_PERMISSIONS already answers "what may this role do", globally. Two things
-- are missing for a SaaS: what this *pharmacy* bought, and what this *user* may
-- do inside that.
--
--   role   → the default for the job (Pharmacist, Accounts, ...)   [exists]
--   tenant → what the branch's plan includes                       [new]
--   user   → one person's exception                                [new]
--
-- The tenant layer can only ever NARROW the role's rights, never widen them. A
-- Basic subscriber must not be able to reach an Enterprise module because
-- somebody ticked a box on the wrong screen, so widening is not expressible.
--
-- A branch with NO tenant rows is unrestricted. That is what keeps every branch
-- trading the moment this lands: an empty grant means "as the role allows",
-- not "nothing".
\set ON_ERROR_STOP on
\timing on

BEGIN;

/* ── the seventh right the brief asks for ───────────────────────────────── */
ALTER TABLE pharma.pos_permissions ADD COLUMN IF NOT EXISTS can_print integer NOT NULL DEFAULT 0;
-- Anyone who can already export can print: the two are the same act on paper,
-- and starting everybody at zero would silently take a right away.
UPDATE pharma.pos_permissions SET can_print = can_export WHERE can_print = 0;

/* ── what the pharmacy's plan includes ──────────────────────────────────── */
CREATE TABLE IF NOT EXISTS pharma.acl_tenant_module (
    pid_tenant_module bigint  PRIMARY KEY,
    pid_branch        bigint  NOT NULL,
    pid_module        bigint  NOT NULL REFERENCES pharma.acl_module_info(pid_module),
    can_view    integer NOT NULL DEFAULT 1,
    can_create  integer NOT NULL DEFAULT 1,
    can_edit    integer NOT NULL DEFAULT 1,
    can_delete  integer NOT NULL DEFAULT 1,
    can_approve integer NOT NULL DEFAULT 1,
    can_export  integer NOT NULL DEFAULT 1,
    can_print   integer NOT NULL DEFAULT 1,
    status        varchar(1) DEFAULT 'Y',
    inserted_by   varchar(50),
    inserted_date timestamp DEFAULT CURRENT_TIMESTAMP,
    updated_by    varchar(50),
    updated_date  timestamp,
    CONSTRAINT uq_tenant_module UNIQUE (pid_branch, pid_module)
);

CREATE INDEX IF NOT EXISTS ix_tenant_module_branch ON pharma.acl_tenant_module (pid_branch);

/* ── one person's exception ─────────────────────────────────────────────── */
CREATE TABLE IF NOT EXISTS pharma.acl_user_permission (
    pid_user_permission bigint PRIMARY KEY,
    pid_user   bigint NOT NULL REFERENCES pharma.acl_user(pid_user),
    pid_module bigint NOT NULL REFERENCES pharma.acl_module_info(pid_module),
    -- Nullable on purpose: null means "whatever the role and tenant say".
    -- A row that had to state all seven rights would turn every small exception
    -- into a full copy of the role, and the copy would drift.
    can_view    integer,
    can_create  integer,
    can_edit    integer,
    can_delete  integer,
    can_approve integer,
    can_export  integer,
    can_print   integer,
    note          varchar(200),
    status        varchar(1) DEFAULT 'Y',
    inserted_by   varchar(50),
    inserted_date timestamp DEFAULT CURRENT_TIMESTAMP,
    updated_by    varchar(50),
    updated_date  timestamp,
    CONSTRAINT uq_user_permission UNIQUE (pid_user, pid_module)
);

CREATE INDEX IF NOT EXISTS ix_user_permission_user ON pharma.acl_user_permission (pid_user);

/* ── templates, so activation is one click ──────────────────────────────── */
CREATE TABLE IF NOT EXISTS pharma.acl_perm_template (
    pid_template  bigint      PRIMARY KEY,
    template_code varchar(30) NOT NULL UNIQUE,
    template_name varchar(100) NOT NULL,
    description   varchar(300),
    -- The plan this template is the natural fit for. Advisory: the Super Admin
    -- may pick any template for any plan, and sometimes should.
    plan_code     varchar(30),
    is_active     integer     NOT NULL DEFAULT 1,
    sort_order    integer     NOT NULL DEFAULT 0,
    status        varchar(1)  DEFAULT 'Y',
    inserted_by   varchar(50),
    inserted_date timestamp   DEFAULT CURRENT_TIMESTAMP,
    updated_by    varchar(50),
    updated_date  timestamp
);

CREATE TABLE IF NOT EXISTS pharma.acl_perm_template_line (
    pid_template_line bigint PRIMARY KEY,
    pid_template bigint NOT NULL REFERENCES pharma.acl_perm_template(pid_template),
    pid_module   bigint NOT NULL REFERENCES pharma.acl_module_info(pid_module),
    can_view    integer NOT NULL DEFAULT 1,
    can_create  integer NOT NULL DEFAULT 1,
    can_edit    integer NOT NULL DEFAULT 1,
    can_delete  integer NOT NULL DEFAULT 0,
    can_approve integer NOT NULL DEFAULT 0,
    can_export  integer NOT NULL DEFAULT 1,
    can_print   integer NOT NULL DEFAULT 1,
    CONSTRAINT uq_template_module UNIQUE (pid_template, pid_module)
);

/* ── the queue a new pharmacy waits in ──────────────────────────────────── */
CREATE TABLE IF NOT EXISTS pharma.tenant_registration (
    pid_registration bigint      PRIMARY KEY,
    pharmacy_name    varchar(150) NOT NULL,
    contact_name     varchar(100) NOT NULL,
    contact_email    varchar(120),
    contact_phone    varchar(30)  NOT NULL,
    address          varchar(300),
    -- The username the branch admin will sign in with, held until approval.
    -- The password is hashed on arrival, not stored in the clear while waiting.
    admin_username   varchar(50)  NOT NULL,
    admin_password   varchar(500) NOT NULL,
    reg_status       varchar(20)  NOT NULL DEFAULT 'PENDING',  -- PENDING | APPROVED | REJECTED
    reject_reason    varchar(300),
    -- Filled in on approval, so the queue row points at what it became.
    pid_branch       bigint,
    pid_user         bigint,
    reviewed_by      varchar(50),
    reviewed_at      timestamp,
    status           varchar(1)  DEFAULT 'Y',
    inserted_by      varchar(50),
    inserted_date    timestamp   DEFAULT CURRENT_TIMESTAMP,
    updated_by       varchar(50),
    updated_date     timestamp,
    CONSTRAINT ck_reg_status CHECK (reg_status IN ('PENDING','APPROVED','REJECTED'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_registration_username
    ON pharma.tenant_registration (LOWER(admin_username)) WHERE reg_status = 'PENDING';
CREATE INDEX IF NOT EXISTS ix_registration_status ON pharma.tenant_registration (reg_status, inserted_date DESC);

CREATE SEQUENCE IF NOT EXISTS pharma.sq_tenant_module    START 100;
CREATE SEQUENCE IF NOT EXISTS pharma.sq_user_permission  START 100;
CREATE SEQUENCE IF NOT EXISTS pharma.sq_perm_template    START 100;
CREATE SEQUENCE IF NOT EXISTS pharma.sq_template_line    START 100;
CREATE SEQUENCE IF NOT EXISTS pharma.sq_registration     START 100;

/* ── the three templates the brief names ────────────────────────────────── */
INSERT INTO pharma.acl_perm_template (pid_template, template_code, template_name, description, plan_code, sort_order, inserted_by)
VALUES
  (1, 'BASIC',      'Basic Pharmacy',      'Selling, stock and the day-to-day reports.',              'BASIC',      1, 'V33'),
  (2, 'STANDARD',   'Standard Pharmacy',   'Adds buying, suppliers and the full reporting suite.',    'STANDARD',   2, 'V33'),
  (3, 'ENTERPRISE', 'Enterprise Pharmacy', 'Everything, including HR, accounts and the AI copilot.',  'ENTERPRISE', 3, 'V33')
ON CONFLICT (pid_template) DO NOTHING;

/* Basic: sell, hold stock, look at what happened. No buying, no HR, no admin. */
INSERT INTO pharma.acl_perm_template_line (pid_template_line, pid_template, pid_module,
       can_view, can_create, can_edit, can_delete, can_approve, can_export, can_print)
SELECT nextval('pharma.sq_template_line'), 1, m.pid_module, 1, 1, 1, 0, 0, 1, 1
  FROM pharma.acl_module_info m
 WHERE m.module_code IN ('MOD01','GRP_SALES','MOD04','MOD05','MOD06','MOD18','MOD13','MOD14','MOD17','GRP_MASTER','MOD02')
   AND NOT EXISTS (SELECT 1 FROM pharma.acl_perm_template_line l
                    WHERE l.pid_template = 1 AND l.pid_module = m.pid_module);

/* Standard: Basic plus purchasing, delivery and supplier money. */
INSERT INTO pharma.acl_perm_template_line (pid_template_line, pid_template, pid_module,
       can_view, can_create, can_edit, can_delete, can_approve, can_export, can_print)
SELECT nextval('pharma.sq_template_line'), 2, m.pid_module, 1, 1, 1, 0, 1, 1, 1
  FROM pharma.acl_module_info m
 WHERE m.module_code NOT IN ('MOD16','MOD15')
   AND NOT EXISTS (SELECT 1 FROM pharma.acl_perm_template_line l
                    WHERE l.pid_template = 2 AND l.pid_module = m.pid_module);

/* Enterprise: every module, every right. */
INSERT INTO pharma.acl_perm_template_line (pid_template_line, pid_template, pid_module,
       can_view, can_create, can_edit, can_delete, can_approve, can_export, can_print)
SELECT nextval('pharma.sq_template_line'), 3, m.pid_module, 1, 1, 1, 1, 1, 1, 1
  FROM pharma.acl_module_info m
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_perm_template_line l
                    WHERE l.pid_template = 3 AND l.pid_module = m.pid_module);

COMMIT;

SELECT t.template_name, count(l.pid_template_line) AS modules
  FROM pharma.acl_perm_template t
  LEFT JOIN pharma.acl_perm_template_line l ON l.pid_template = t.pid_template
 GROUP BY t.pid_template, t.template_name ORDER BY t.pid_template;
