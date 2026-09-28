/* ─────────────────────────────────────────────────────────────────────────────
   Accounts — branch daily cash control.

   One row per branch per business date. Opening cash is the previous CLOSED
   day's counted cash, never typed. Expected closing is derived from the
   ledger (every posted line on the branch till account dated that day);
   physical cash is counted, denomination by denomination; the difference is
   recorded, explained, and — only where policy says so — posted.

   Nothing here is a second cash balance. The day sheet reads the ledger, and
   the ledger is what a report reads. See docs/accounts/daily-cash-control.md.
   ───────────────────────────────────────────────────────────────────────────── */

CREATE SEQUENCE IF NOT EXISTS pharma.seq_acc_business_day  START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_acc_cash_count    START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_acc_denomination  START WITH 1 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS pharma.acc_denomination (
    pid_denomination BIGINT        NOT NULL,
    currency         VARCHAR(3)    NOT NULL,
    denom_value      NUMERIC(18,2) NOT NULL,
    denom_kind       VARCHAR(10)   DEFAULT 'NOTE',   /* NOTE COIN */
    sort_order       INT           DEFAULT 100,
    is_active        SMALLINT      DEFAULT 1,
    status           VARCHAR(1)    DEFAULT 'Y',
    inserted_by      VARCHAR(50),
    inserted_date    TIMESTAMP,
    updated_by       VARCHAR(50),
    updated_date     TIMESTAMP,
    CONSTRAINT acc_denomination_pkey PRIMARY KEY (pid_denomination),
    CONSTRAINT uq_acc_denomination UNIQUE (currency, denom_value)
);

/* Bangladeshi Taka, as a default the settings may replace per currency. */
INSERT INTO pharma.acc_denomination (pid_denomination, currency, denom_value, denom_kind, sort_order, is_active, status, inserted_by, inserted_date)
SELECT nextval('pharma.seq_acc_denomination'), 'BDT', v.val, v.kind, v.ord, 1, 'Y', 'V91', now()
  FROM (VALUES (1000,'NOTE',1),(500,'NOTE',2),(200,'NOTE',3),(100,'NOTE',4),(50,'NOTE',5),(20,'NOTE',6),(10,'NOTE',7),
               (5,'COIN',8),(2,'COIN',9),(1,'COIN',10)) AS v(val, kind, ord)
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acc_denomination d WHERE d.currency = 'BDT' AND d.denom_value = v.val);

CREATE TABLE IF NOT EXISTS pharma.acc_business_day (
    pid_business_day  BIGINT        NOT NULL,
    pid_org           BIGINT        NOT NULL,
    pid_branch        BIGINT        NOT NULL,
    business_date     DATE          NOT NULL,
    pid_cash_account  BIGINT        NOT NULL,     /* the branch till (role CASH_REGISTER) */
    /* OPEN → (SUBMITTED →) CLOSED; a CLOSED day may be REOPENED with a reason and
       then closed again; LOCKED once its period is locked. */
    day_status        VARCHAR(12)   NOT NULL DEFAULT 'OPEN',
    pid_previous_day  BIGINT,                    /* the closed day the opening came from */
    opening_cash      NUMERIC(18,2) NOT NULL DEFAULT 0,
    /* Snapshots taken at submit/close; while OPEN they are recomputed from the ledger on read. */
    cash_in           NUMERIC(18,2) DEFAULT 0,
    cash_out          NUMERIC(18,2) DEFAULT 0,
    expected_closing  NUMERIC(18,2),
    physical_cash     NUMERIC(18,2),
    difference        NUMERIC(18,2),
    difference_kind   VARCHAR(10),               /* BALANCED SHORT OVER */
    difference_reason VARCHAR(500),
    variance_posted   SMALLINT      DEFAULT 0,
    pid_variance_journal BIGINT,
    opened_by         VARCHAR(50),
    opened_at         TIMESTAMP,
    submitted_by      VARCHAR(50),
    submitted_at      TIMESTAMP,
    reviewed_by       VARCHAR(50),
    reviewed_at       TIMESTAMP,
    closed_by         VARCHAR(50),
    closed_at         TIMESTAMP,
    reopened_by       VARCHAR(50),
    reopened_at       TIMESTAMP,
    reopen_reason     VARCHAR(500),
    reopen_count      INT           DEFAULT 0,
    notes             VARCHAR(1000),
    status            VARCHAR(1)    DEFAULT 'Y',
    inserted_by       VARCHAR(50),
    inserted_date     TIMESTAMP,
    updated_by        VARCHAR(50),
    updated_date      TIMESTAMP,
    CONSTRAINT acc_business_day_pkey PRIMARY KEY (pid_business_day),
    /* At most one day sheet per branch per date — the rule the brief names first. */
    CONSTRAINT uq_acc_business_day UNIQUE (pid_branch, business_date),
    CONSTRAINT ck_acc_business_day_status CHECK (day_status IN ('OPEN','SUBMITTED','CLOSED','REOPENED','LOCKED')),
    CONSTRAINT ck_acc_business_day_diff CHECK (difference_kind IS NULL OR difference_kind IN ('BALANCED','SHORT','OVER'))
);
CREATE INDEX IF NOT EXISTS ix_acc_business_day_branch ON pharma.acc_business_day (pid_branch, business_date DESC);

CREATE TABLE IF NOT EXISTS pharma.acc_cash_count (
    pid_cash_count    BIGINT        NOT NULL,
    pid_business_day  BIGINT        NOT NULL,
    denom_value       NUMERIC(18,2) NOT NULL,
    quantity          INT           NOT NULL DEFAULT 0,
    amount            NUMERIC(18,2) NOT NULL DEFAULT 0,
    status            VARCHAR(1)    DEFAULT 'Y',
    inserted_by       VARCHAR(50),
    inserted_date     TIMESTAMP,
    updated_by        VARCHAR(50),
    updated_date      TIMESTAMP,
    CONSTRAINT acc_cash_count_pkey PRIMARY KEY (pid_cash_count),
    CONSTRAINT fk_acc_cash_count_day FOREIGN KEY (pid_business_day) REFERENCES pharma.acc_business_day (pid_business_day),
    CONSTRAINT uq_acc_cash_count UNIQUE (pid_business_day, denom_value),
    CONSTRAINT ck_acc_cash_count_qty CHECK (quantity >= 0)
);

/* Every audited change of a day sheet, in order. */
CREATE SEQUENCE IF NOT EXISTS pharma.seq_acc_day_audit START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS pharma.acc_business_day_audit (
    pid_day_audit     BIGINT       NOT NULL,
    pid_business_day  BIGINT       NOT NULL,
    action            VARCHAR(20)  NOT NULL,   /* OPENED COUNTED SUBMITTED REVIEWED CLOSED REOPENED VARIANCE_POSTED NOTE */
    detail            VARCHAR(1000),
    acted_by          VARCHAR(50),
    acted_at          TIMESTAMP,
    CONSTRAINT acc_business_day_audit_pkey PRIMARY KEY (pid_day_audit),
    CONSTRAINT fk_acc_day_audit_day FOREIGN KEY (pid_business_day) REFERENCES pharma.acc_business_day (pid_business_day)
);
CREATE INDEX IF NOT EXISTS ix_acc_day_audit_day ON pharma.acc_business_day_audit (pid_business_day, acted_at);
