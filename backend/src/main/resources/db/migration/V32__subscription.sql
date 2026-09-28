-- Subscriptions: what a pharmacy is paying for, and whether it still is.
--
-- Five tables and one column. The column is the one the application reads on
-- every request; the tables are what the Super Admin console manages.
--
-- Shape of the money side: an invoice is raised for a period, a payment settles
-- it, and settling it reactivates the branch. That indirection is deliberate even
-- though payment is recorded by hand today — a gateway webhook and the Super
-- Admin screen then post the same transition against the same row, so adding a
-- gateway later is a controller, not a redesign. PROVIDER_REF and
-- IDEMPOTENCY_KEY exist for that day; the manual path leaves them null.
\set ON_ERROR_STOP on
\timing on

BEGIN;

/* ── what can be bought ─────────────────────────────────────────────────── */
CREATE TABLE IF NOT EXISTS pharma.sub_plan (
    pid_plan        bigint       PRIMARY KEY,
    plan_code       varchar(30)  NOT NULL UNIQUE,
    plan_name       varchar(100) NOT NULL,
    description     varchar(300),
    -- Price for one billing term, not per month: a yearly plan priced monthly
    -- invites the two to disagree the first time somebody edits one of them.
    price           numeric(18,2) NOT NULL DEFAULT 0,
    billing_term    varchar(10)   NOT NULL DEFAULT 'MONTHLY',   -- MONTHLY | YEARLY
    max_users       integer,                                     -- null = no cap
    grace_days      integer       NOT NULL DEFAULT 10,
    is_active       integer       NOT NULL DEFAULT 1,
    sort_order      integer       NOT NULL DEFAULT 0,
    status          varchar(1)    DEFAULT 'Y',
    inserted_by     varchar(50),
    inserted_date   timestamp     DEFAULT CURRENT_TIMESTAMP,
    updated_by      varchar(50),
    updated_date    timestamp,
    CONSTRAINT ck_plan_term  CHECK (billing_term IN ('MONTHLY','YEARLY')),
    CONSTRAINT ck_plan_grace CHECK (grace_days >= 0)
);

/* ── what one pharmacy is on ────────────────────────────────────────────── */
CREATE TABLE IF NOT EXISTS pharma.sub_subscription (
    pid_subscription bigint      PRIMARY KEY,
    pid_branch       bigint      NOT NULL,
    pid_org          bigint      NOT NULL,
    pid_plan         bigint      NOT NULL REFERENCES pharma.sub_plan(pid_plan),
    starts_on        date        NOT NULL,
    expires_on       date        NOT NULL,
    -- Copied from the plan when the subscription is written, not read through
    -- it: editing a plan's grace period must not silently move the date on which
    -- somebody already trading gets locked out.
    grace_days       integer     NOT NULL DEFAULT 10,
    sub_status       varchar(20) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | EXPIRED | SUSPENDED | CANCELLED
    cancelled_reason varchar(300),
    status           varchar(1)  DEFAULT 'Y',
    inserted_by      varchar(50),
    inserted_date    timestamp   DEFAULT CURRENT_TIMESTAMP,
    updated_by       varchar(50),
    updated_date     timestamp,
    CONSTRAINT ck_sub_status CHECK (sub_status IN ('ACTIVE','EXPIRED','SUSPENDED','CANCELLED')),
    CONSTRAINT ck_sub_dates  CHECK (expires_on >= starts_on)
);

-- One live subscription per branch. Two would make "is this pharmacy paid up"
-- a question with two answers, and the filter would have to pick one.
CREATE UNIQUE INDEX IF NOT EXISTS uq_subscription_live
    ON pharma.sub_subscription (pid_branch)
 WHERE sub_status IN ('ACTIVE','SUSPENDED');

CREATE INDEX IF NOT EXISTS ix_subscription_expiry ON pharma.sub_subscription (expires_on, sub_status);

/* ── what is owed ───────────────────────────────────────────────────────── */
CREATE TABLE IF NOT EXISTS pharma.sub_invoice (
    pid_invoice      bigint       PRIMARY KEY,
    pid_subscription bigint       NOT NULL REFERENCES pharma.sub_subscription(pid_subscription),
    pid_branch       bigint       NOT NULL,
    invoice_no       varchar(30)  NOT NULL UNIQUE,
    period_from      date         NOT NULL,
    period_to        date         NOT NULL,
    amount           numeric(18,2) NOT NULL,
    issued_on        date         NOT NULL DEFAULT CURRENT_DATE,
    due_on           date         NOT NULL,
    pay_status       varchar(10)  NOT NULL DEFAULT 'PENDING',  -- PENDING | PAID | VOID
    status           varchar(1)   DEFAULT 'Y',
    inserted_by      varchar(50),
    inserted_date    timestamp    DEFAULT CURRENT_TIMESTAMP,
    updated_by       varchar(50),
    updated_date     timestamp,
    CONSTRAINT ck_invoice_status CHECK (pay_status IN ('PENDING','PAID','VOID'))
);

CREATE INDEX IF NOT EXISTS ix_subinvoice_branch ON pharma.sub_invoice (pid_branch, pay_status);

/* ── what was paid ──────────────────────────────────────────────────────── */
CREATE TABLE IF NOT EXISTS pharma.sub_payment (
    pid_payment      bigint       PRIMARY KEY,
    pid_invoice      bigint       NOT NULL REFERENCES pharma.sub_invoice(pid_invoice),
    pid_branch       bigint       NOT NULL,
    amount           numeric(18,2) NOT NULL,
    paid_on          date         NOT NULL DEFAULT CURRENT_DATE,
    method           varchar(20)  NOT NULL DEFAULT 'MANUAL',   -- MANUAL | BKASH | SSLCOMMERZ | BANK
    -- For the gateway that does not exist yet. A webhook that fires twice must
    -- settle an invoice once, and the key is what makes the second call a no-op.
    provider_ref     varchar(100),
    idempotency_key  varchar(100),
    note             varchar(300),
    status           varchar(1)   DEFAULT 'Y',
    inserted_by      varchar(50),
    inserted_date    timestamp    DEFAULT CURRENT_TIMESTAMP,
    updated_by       varchar(50),
    updated_date     timestamp
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_idempotency
    ON pharma.sub_payment (idempotency_key) WHERE idempotency_key IS NOT NULL;

/* ── who changed what ───────────────────────────────────────────────────── */
CREATE TABLE IF NOT EXISTS pharma.sub_audit (
    pid_audit     bigint       PRIMARY KEY,
    pid_branch    bigint       NOT NULL,
    event_type    varchar(40)  NOT NULL,   -- ACTIVATED | RENEWED | SUSPENDED | EXPIRED | PAYMENT | PLAN_CHANGED
    detail        varchar(500),
    old_value     varchar(100),
    new_value     varchar(100),
    acted_by      varchar(50),
    acted_at      timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS ix_subaudit_branch ON pharma.sub_audit (pid_branch, acted_at DESC);

/* ── the column the application actually reads ──────────────────────────── */
-- ALL_BRANCH.STATUS is a one-character audit flag and cannot carry a lifecycle,
-- so the lifecycle gets its own column rather than overloading that one.
ALTER TABLE pharma.all_branch ADD COLUMN IF NOT EXISTS tenant_status varchar(20);
UPDATE pharma.all_branch SET tenant_status = 'ACTIVE' WHERE tenant_status IS NULL;
ALTER TABLE pharma.all_branch ALTER COLUMN tenant_status SET NOT NULL;
ALTER TABLE pharma.all_branch ALTER COLUMN tenant_status SET DEFAULT 'PENDING';

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_branch_tenant_status') THEN
    ALTER TABLE pharma.all_branch ADD CONSTRAINT ck_branch_tenant_status
      CHECK (tenant_status IN ('PENDING','ACTIVE','SUSPENDED','EXPIRED'));
  END IF;
END $$;

CREATE SEQUENCE IF NOT EXISTS pharma.sq_sub_plan         START 100;
CREATE SEQUENCE IF NOT EXISTS pharma.sq_sub_subscription START 100;
CREATE SEQUENCE IF NOT EXISTS pharma.sq_sub_invoice      START 100;
CREATE SEQUENCE IF NOT EXISTS pharma.sq_sub_payment      START 100;
CREATE SEQUENCE IF NOT EXISTS pharma.sq_sub_audit        START 100;

/* ── the three plans the brief names ────────────────────────────────────── */
INSERT INTO pharma.sub_plan (pid_plan, plan_code, plan_name, description, price, billing_term, max_users, grace_days, sort_order, inserted_by)
VALUES
  (1, 'BASIC',      'Basic Pharmacy',      'Sales, inventory and the essential reports.',        2000.00, 'MONTHLY',  5, 10, 1, 'V32'),
  (2, 'STANDARD',   'Standard Pharmacy',   'Adds purchasing, suppliers and the full reporting.', 4500.00, 'MONTHLY', 15, 10, 2, 'V32'),
  (3, 'ENTERPRISE', 'Enterprise Pharmacy', 'Everything, including HR, accounts and the copilot.', 9000.00, 'MONTHLY', NULL, 10, 3, 'V32')
ON CONFLICT (pid_plan) DO NOTHING;

/* The branches that already trade keep trading. Backdated a year so the first
   renewal is a deliberate act rather than a lockout on the morning after. */
INSERT INTO pharma.sub_subscription (pid_subscription, pid_branch, pid_org, pid_plan,
                                     starts_on, expires_on, grace_days, sub_status, inserted_by)
SELECT nextval('pharma.sq_sub_subscription'), b.pid_branch, b.pid_org, 3,
       CURRENT_DATE - 30, CURRENT_DATE + 335, 10, 'ACTIVE', 'V32'
  FROM pharma.all_branch b
 WHERE NOT EXISTS (SELECT 1 FROM pharma.sub_subscription s
                    WHERE s.pid_branch = b.pid_branch
                      AND s.sub_status IN ('ACTIVE','SUSPENDED'));

COMMIT;

SELECT b.pid_branch, b.branch_name, b.tenant_status, p.plan_name, s.expires_on, s.grace_days
  FROM pharma.all_branch b
  LEFT JOIN pharma.sub_subscription s ON s.pid_branch = b.pid_branch AND s.sub_status = 'ACTIVE'
  LEFT JOIN pharma.sub_plan p ON p.pid_plan = s.pid_plan
 ORDER BY b.pid_branch;
