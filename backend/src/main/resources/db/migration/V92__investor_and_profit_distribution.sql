/* ─────────────────────────────────────────────────────────────────────────────
   Accounts — investors, their capital, and the sharing of profit.

   An investor is not a contact. Every movement of their money is a
   transaction that raises an accounting event, so the investor ledger is the
   general ledger read by party. Profit share is a workflow over a financial
   period, computed from posted results, snapshotted with the policy that
   produced it, and posted as a liability that a later payment settles.
   See docs/accounts/investor-accounting.md and profit-distribution.md.
   ───────────────────────────────────────────────────────────────────────────── */

CREATE SEQUENCE IF NOT EXISTS pharma.seq_acc_investor            START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_acc_investor_txn        START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_acc_profit_policy       START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_acc_profit_policy_line  START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_acc_profit_distribution START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_acc_profit_allocation   START WITH 1 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS pharma.acc_investor (
    pid_investor       BIGINT        NOT NULL,
    pid_org            BIGINT        NOT NULL,
    pid_branch         BIGINT,                      /* NULL = invests in the business as a whole */
    investor_code      VARCHAR(20)   NOT NULL,
    investor_name      VARCHAR(150)  NOT NULL,
    contact_phone      VARCHAR(30),
    contact_email      VARCHAR(120),
    address            VARCHAR(300),
    id_reference       VARCHAR(60),                 /* NID / passport / registration reference — a reference, not a copy */
    investment_type    VARCHAR(20)   DEFAULT 'EQUITY',   /* EQUITY PARTNER SILENT OTHER */
    start_date         DATE,
    end_date           DATE,
    share_pct          NUMERIC(9,4),                /* used by INVESTOR_SPECIFIC_PERCENTAGE policies */
    bank_name          VARCHAR(120),
    bank_account_no    VARCHAR(60),
    mobile_wallet      VARCHAR(30),
    remarks            VARCHAR(500),
    investor_status    VARCHAR(10)   DEFAULT 'ACTIVE',   /* ACTIVE INACTIVE EXITED */
    status             VARCHAR(1)    DEFAULT 'Y',
    inserted_by        VARCHAR(50),
    inserted_date      TIMESTAMP,
    updated_by         VARCHAR(50),
    updated_date       TIMESTAMP,
    CONSTRAINT acc_investor_pkey PRIMARY KEY (pid_investor),
    CONSTRAINT uq_acc_investor_code UNIQUE (pid_org, investor_code),
    CONSTRAINT ck_acc_investor_status CHECK (investor_status IN ('ACTIVE','INACTIVE','EXITED')),
    CONSTRAINT ck_acc_investor_share CHECK (share_pct IS NULL OR (share_pct >= 0 AND share_pct <= 100))
);
CREATE INDEX IF NOT EXISTS ix_acc_investor_org ON pharma.acc_investor (pid_org, investor_status);

CREATE TABLE IF NOT EXISTS pharma.acc_investor_txn (
    pid_investor_txn   BIGINT        NOT NULL,
    pid_org            BIGINT        NOT NULL,
    pid_branch         BIGINT        NOT NULL,
    pid_investor       BIGINT        NOT NULL,
    txn_no             VARCHAR(30)   NOT NULL,
    txn_date           DATE          NOT NULL,
    /* INITIAL_INVESTMENT ADDITIONAL_INVESTMENT CAPITAL_WITHDRAWAL CAPITAL_ADJUSTMENT
       PROFIT_DISTRIBUTION REFUND OTHER_APPROVED_ADJUSTMENT — PROFIT_ALLOCATION rows are
       written by the distribution workflow, never entered by hand. */
    txn_type           VARCHAR(30)   NOT NULL,
    amount             NUMERIC(18,2) NOT NULL,
    /* CAPITAL_ADJUSTMENT / OTHER: +1 raises capital, -1 lowers it. */
    direction          SMALLINT      DEFAULT 1,
    payment_mode       VARCHAR(30),
    reference          VARCHAR(60),
    description        VARCHAR(300),
    reason             VARCHAR(500),
    pid_distribution   BIGINT,
    pid_allocation     BIGINT,
    txn_status         VARCHAR(10)   DEFAULT 'DRAFT',    /* DRAFT APPROVED POSTED CANCELLED */
    approved_by        VARCHAR(50),
    approved_at        TIMESTAMP,
    posted_by          VARCHAR(50),
    posted_at          TIMESTAMP,
    cancelled_by       VARCHAR(50),
    cancelled_at       TIMESTAMP,
    cancel_reason      VARCHAR(500),
    pid_event          BIGINT,
    pid_journal        BIGINT,
    status             VARCHAR(1)    DEFAULT 'Y',
    inserted_by        VARCHAR(50),
    inserted_date      TIMESTAMP,
    updated_by         VARCHAR(50),
    updated_date       TIMESTAMP,
    CONSTRAINT acc_investor_txn_pkey PRIMARY KEY (pid_investor_txn),
    CONSTRAINT uq_acc_investor_txn_no UNIQUE (pid_org, txn_no),
    CONSTRAINT fk_acc_investor_txn_investor FOREIGN KEY (pid_investor) REFERENCES pharma.acc_investor (pid_investor),
    CONSTRAINT ck_acc_investor_txn_type CHECK (txn_type IN ('INITIAL_INVESTMENT','ADDITIONAL_INVESTMENT','CAPITAL_WITHDRAWAL','CAPITAL_ADJUSTMENT','PROFIT_ALLOCATION','PROFIT_DISTRIBUTION','LOSS_ALLOCATION','REFUND','OTHER_APPROVED_ADJUSTMENT')),
    CONSTRAINT ck_acc_investor_txn_status CHECK (txn_status IN ('DRAFT','APPROVED','POSTED','CANCELLED')),
    CONSTRAINT ck_acc_investor_txn_amount CHECK (amount > 0),
    CONSTRAINT ck_acc_investor_txn_direction CHECK (direction IN (1, -1))
);
CREATE INDEX IF NOT EXISTS ix_acc_investor_txn_investor ON pharma.acc_investor_txn (pid_investor, txn_date);

CREATE TABLE IF NOT EXISTS pharma.acc_profit_policy (
    pid_profit_policy  BIGINT        NOT NULL,
    pid_org            BIGINT        NOT NULL,
    pid_branch         BIGINT,
    policy_name        VARCHAR(120)  NOT NULL,
    /* FIXED_PERCENTAGE CAPITAL_RATIO INVESTOR_SPECIFIC_PERCENTAGE WEIGHTED_AVERAGE_CAPITAL CUSTOM_APPROVED_METHOD */
    share_method       VARCHAR(40)   NOT NULL,
    /* FIXED_PERCENTAGE: the share of distributable profit that goes to investors as a pool, split by capital ratio. */
    investor_pool_pct  NUMERIC(9,4)  DEFAULT 100,
    /* Kept back before anything is distributable. */
    retained_pct       NUMERIC(9,4)  DEFAULT 0,
    effective_from     DATE          NOT NULL,
    effective_to       DATE,
    is_active          SMALLINT      DEFAULT 1,
    notes              VARCHAR(500),
    status             VARCHAR(1)    DEFAULT 'Y',
    inserted_by        VARCHAR(50),
    inserted_date      TIMESTAMP,
    updated_by         VARCHAR(50),
    updated_date       TIMESTAMP,
    CONSTRAINT acc_profit_policy_pkey PRIMARY KEY (pid_profit_policy),
    CONSTRAINT ck_acc_profit_policy_method CHECK (share_method IN ('FIXED_PERCENTAGE','CAPITAL_RATIO','INVESTOR_SPECIFIC_PERCENTAGE','WEIGHTED_AVERAGE_CAPITAL','CUSTOM_APPROVED_METHOD')),
    CONSTRAINT ck_acc_profit_policy_pcts CHECK (investor_pool_pct BETWEEN 0 AND 100 AND retained_pct BETWEEN 0 AND 100)
);

CREATE TABLE IF NOT EXISTS pharma.acc_profit_policy_line (
    pid_policy_line    BIGINT        NOT NULL,
    pid_profit_policy  BIGINT        NOT NULL,
    pid_investor       BIGINT        NOT NULL,
    share_pct          NUMERIC(9,4)  NOT NULL,
    status             VARCHAR(1)    DEFAULT 'Y',
    CONSTRAINT acc_profit_policy_line_pkey PRIMARY KEY (pid_policy_line),
    CONSTRAINT fk_acc_policy_line_policy FOREIGN KEY (pid_profit_policy) REFERENCES pharma.acc_profit_policy (pid_profit_policy),
    CONSTRAINT fk_acc_policy_line_investor FOREIGN KEY (pid_investor) REFERENCES pharma.acc_investor (pid_investor),
    CONSTRAINT uq_acc_policy_line UNIQUE (pid_profit_policy, pid_investor),
    CONSTRAINT ck_acc_policy_line_pct CHECK (share_pct >= 0 AND share_pct <= 100)
);

CREATE TABLE IF NOT EXISTS pharma.acc_profit_distribution (
    pid_distribution   BIGINT        NOT NULL,
    pid_org            BIGINT        NOT NULL,
    pid_branch         BIGINT,                       /* NULL = whole business */
    distribution_no    VARCHAR(30)   NOT NULL,
    period_from        DATE          NOT NULL,
    period_to          DATE          NOT NULL,
    pid_period_from    BIGINT,
    pid_period_to      BIGINT,
    pid_profit_policy  BIGINT,
    share_method       VARCHAR(40)   NOT NULL,      /* snapshot of the policy at calculation */
    revenue            NUMERIC(18,2) DEFAULT 0,
    cost_of_sales      NUMERIC(18,2) DEFAULT 0,
    operating_expenses NUMERIC(18,2) DEFAULT 0,
    other_income       NUMERIC(18,2) DEFAULT 0,
    other_expense      NUMERIC(18,2) DEFAULT 0,
    accounting_profit  NUMERIC(18,2) DEFAULT 0,
    retained_pct       NUMERIC(9,4)  DEFAULT 0,
    retained_amount    NUMERIC(18,2) DEFAULT 0,
    adjustment_amount  NUMERIC(18,2) DEFAULT 0,     /* signed, approved, with a reason */
    adjustment_reason  VARCHAR(500),
    distributable_profit NUMERIC(18,2) DEFAULT 0,
    investor_pool_pct  NUMERIC(9,4)  DEFAULT 100,
    allocated_total    NUMERIC(18,2) DEFAULT 0,
    paid_total         NUMERIC(18,2) DEFAULT 0,
    /* DRAFT CALCULATED REVIEWED APPROVED POSTED PAID CANCELLED */
    dist_status        VARCHAR(12)   DEFAULT 'DRAFT',
    snapshot           TEXT,                        /* JSON: the figures and inputs the calculation read, for reproducibility */
    calculated_by      VARCHAR(50),
    calculated_at      TIMESTAMP,
    reviewed_by        VARCHAR(50),
    reviewed_at        TIMESTAMP,
    approved_by        VARCHAR(50),
    approved_at        TIMESTAMP,
    posted_by          VARCHAR(50),
    posted_at          TIMESTAMP,
    cancelled_by       VARCHAR(50),
    cancelled_at       TIMESTAMP,
    cancel_reason      VARCHAR(500),
    pid_event          BIGINT,
    pid_journal        BIGINT,
    notes              VARCHAR(1000),
    status             VARCHAR(1)    DEFAULT 'Y',
    inserted_by        VARCHAR(50),
    inserted_date      TIMESTAMP,
    updated_by         VARCHAR(50),
    updated_date       TIMESTAMP,
    CONSTRAINT acc_profit_distribution_pkey PRIMARY KEY (pid_distribution),
    CONSTRAINT uq_acc_distribution_no UNIQUE (pid_org, distribution_no),
    CONSTRAINT ck_acc_distribution_status CHECK (dist_status IN ('DRAFT','CALCULATED','REVIEWED','APPROVED','POSTED','PAID','CANCELLED')),
    CONSTRAINT ck_acc_distribution_dates CHECK (period_to >= period_from)
);
/* One live distribution per organisation, branch scope and period — a second
   one for the same span must be a cancellation and a fresh calculation. */
CREATE UNIQUE INDEX IF NOT EXISTS uq_acc_distribution_period
    ON pharma.acc_profit_distribution (pid_org, COALESCE(pid_branch, 0), period_from, period_to)
    WHERE dist_status <> 'CANCELLED';

CREATE TABLE IF NOT EXISTS pharma.acc_profit_allocation (
    pid_allocation     BIGINT        NOT NULL,
    pid_distribution   BIGINT        NOT NULL,
    pid_investor       BIGINT        NOT NULL,
    opening_capital    NUMERIC(18,2) DEFAULT 0,
    additions          NUMERIC(18,2) DEFAULT 0,
    withdrawals        NUMERIC(18,2) DEFAULT 0,
    eligible_capital   NUMERIC(18,2) DEFAULT 0,
    share_pct          NUMERIC(9,4)  DEFAULT 0,
    allocated_amount   NUMERIC(18,2) DEFAULT 0,
    paid_amount        NUMERIC(18,2) DEFAULT 0,
    alloc_status       VARCHAR(10)   DEFAULT 'DRAFT',   /* DRAFT POSTED PARTIAL PAID */
    pid_investor_txn   BIGINT,                          /* the PROFIT_ALLOCATION row written at posting */
    status             VARCHAR(1)    DEFAULT 'Y',
    inserted_by        VARCHAR(50),
    inserted_date      TIMESTAMP,
    updated_by         VARCHAR(50),
    updated_date       TIMESTAMP,
    CONSTRAINT acc_profit_allocation_pkey PRIMARY KEY (pid_allocation),
    CONSTRAINT fk_acc_allocation_distribution FOREIGN KEY (pid_distribution) REFERENCES pharma.acc_profit_distribution (pid_distribution),
    CONSTRAINT fk_acc_allocation_investor FOREIGN KEY (pid_investor) REFERENCES pharma.acc_investor (pid_investor),
    CONSTRAINT uq_acc_allocation UNIQUE (pid_distribution, pid_investor),
    CONSTRAINT ck_acc_allocation_status CHECK (alloc_status IN ('DRAFT','POSTED','PARTIAL','PAID'))
);
