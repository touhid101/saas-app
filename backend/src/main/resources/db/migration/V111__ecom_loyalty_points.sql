/* ─────────────────────────────────────────────────────────────────────────────
   V111 — Loyalty points.

   Nothing to reuse: the ERP has no loyalty, wallet or cashback logic at all
   (docs/ecommerce 16 §1). These two tables and one link table are the whole of
   it.

     ECOM_POINT_RULE          what a point is worth, and how one is earned
     ECOM_POINT_RULE_BRANCH   which pharmacies a branch-scoped rule covers
     ECOM_POINT_LEDGER        every point ever earned, spent, expired or adjusted

   THE LEDGER IS THE TRUTH. ECOM_CUSTOMER.POINTS_BALANCE (V100, never used until
   now) is a cache of SUM(points) and is written only by the service that writes
   the ledger row, in the same transaction — the same rule the ERP applies to
   stock and to accounts.

   POINTS ARE GLOBAL (RULE 44 of the brief): the balance belongs to the customer,
   not to a pharmacy. Earned at one, spendable at another — subject to whatever
   the rule that applies there says.

   EVERY NUMBER IS A BUSINESS DECISION, so there is no default rule and no
   seeded values. Until somebody writes a rule, nothing earns and nothing can be
   redeemed: a loyalty scheme that switched itself on with an invented exchange
   rate would be giving away money nobody agreed to.

   WHO BEARS THE COST of a redemption — the pharmacy whose invoice it reduces,
   or the platform — is the same open question as a coupon's (15 §3), and is NOT
   answered here. BUSINESS/ACCOUNTING CONFIRMATION REQUIRED.
   ───────────────────────────────────────────────────────────────────────────── */

CREATE SEQUENCE IF NOT EXISTS pharma.seq_ecom_point_rule;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_ecom_point_rule_branch;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_ecom_point_ledger;

CREATE TABLE IF NOT EXISTS pharma.ecom_point_rule (
    pid_point_rule         BIGINT       PRIMARY KEY DEFAULT nextval('pharma.seq_ecom_point_rule'),
    rule_name              VARCHAR(150) NOT NULL,
    description            VARCHAR(300),

    /* Earning. Expressed per 100 spent because that is how a pharmacy says it
       out loud — "a point for every 100" — rather than as a fraction nobody can
       check at a glance. */
    earn_points_per_100    NUMERIC(10, 2) NOT NULL,
    min_order_to_earn      NUMERIC(14, 2),

    /* Spending. POINT_VALUE is what one point takes off a bill; it is the
       exchange rate, and there is deliberately no default for it. */
    point_value            NUMERIC(10, 4) NOT NULL,
    min_points_to_redeem   INTEGER,
    /* A ceiling, so points cannot pay for a whole order unless somebody says so. */
    max_redeem_percent     NUMERIC(5, 2),

    /* Points that sit unused for this many months expire. Null = they do not. */
    expiry_months          INTEGER,

    branch_scope           VARCHAR(20)  NOT NULL,
    customer_type_scope    VARCHAR(20)  NOT NULL DEFAULT 'ALL',

    starts_at              TIMESTAMP,
    ends_at                TIMESTAMP,
    is_active              VARCHAR(1)   NOT NULL DEFAULT 'Y',

    pid_owner_branch       BIGINT,
    pid_org                BIGINT,

    status                 VARCHAR(1)   DEFAULT 'Y',
    inserted_by            VARCHAR(50),
    inserted_date          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_by             VARCHAR(50),
    updated_date           TIMESTAMP,

    CONSTRAINT ck_ecom_pr_scope   CHECK (branch_scope IN ('ALL_BRANCHES', 'SELECTED_BRANCHES')),
    CONSTRAINT ck_ecom_pr_tier    CHECK (customer_type_scope IN ('ALL', 'RETAILER', 'WHOLESALER', 'SPECIAL')),
    CONSTRAINT ck_ecom_pr_numbers CHECK (earn_points_per_100 >= 0
                                     AND point_value > 0
                                     AND COALESCE(min_order_to_earn, 0) >= 0
                                     AND COALESCE(min_points_to_redeem, 1) > 0
                                     AND COALESCE(max_redeem_percent, 1) > 0
                                     AND COALESCE(max_redeem_percent, 100) <= 100
                                     AND COALESCE(expiry_months, 1) > 0),
    CONSTRAINT ck_ecom_pr_window  CHECK (starts_at IS NULL OR ends_at IS NULL OR ends_at > starts_at)
);

CREATE INDEX IF NOT EXISTS ix_ecom_pr_active ON pharma.ecom_point_rule (is_active, branch_scope);

COMMENT ON TABLE pharma.ecom_point_rule IS
  'What a loyalty point is worth and how one is earned. No rule means no scheme: nothing earns and nothing redeems.';

CREATE TABLE IF NOT EXISTS pharma.ecom_point_rule_branch (
    pid_rule_branch BIGINT PRIMARY KEY DEFAULT nextval('pharma.seq_ecom_point_rule_branch'),
    pid_point_rule  BIGINT NOT NULL,
    pid_branch      BIGINT NOT NULL,
    status          VARCHAR(1) DEFAULT 'Y',
    inserted_by     VARCHAR(50),
    inserted_date   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_by      VARCHAR(50),
    updated_date    TIMESTAMP,
    CONSTRAINT fk_ecom_prb_rule   FOREIGN KEY (pid_point_rule) REFERENCES pharma.ecom_point_rule (pid_point_rule),
    CONSTRAINT fk_ecom_prb_branch FOREIGN KEY (pid_branch)     REFERENCES pharma.all_branch (pid_branch)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_ecom_prb ON pharma.ecom_point_rule_branch (pid_point_rule, pid_branch);

CREATE TABLE IF NOT EXISTS pharma.ecom_point_ledger (
    pid_point_entry   BIGINT       PRIMARY KEY DEFAULT nextval('pharma.seq_ecom_point_ledger'),
    pid_ecom_customer BIGINT       NOT NULL,
    /* EARN (+) · REDEEM (−) · EXPIRE (−) · ADJUST (±, by a person, with a reason) */
    entry_type        VARCHAR(10)  NOT NULL,
    points            NUMERIC(12, 2) NOT NULL,
    /* What the balance became. Kept so a statement reads like a statement, and
       so a disagreement with the cached balance is visible rather than inferred. */
    balance_after     NUMERIC(12, 2) NOT NULL,
    /* What the points were worth in money, on a redemption. */
    value_amount      NUMERIC(14, 2),

    pid_ecom_order    BIGINT,
    pid_branch        BIGINT,
    pid_point_rule    BIGINT,
    note              VARCHAR(300),

    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by        VARCHAR(50),

    status            VARCHAR(1)   DEFAULT 'Y',
    inserted_by       VARCHAR(50),
    inserted_date     TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_by        VARCHAR(50),
    updated_date      TIMESTAMP,

    CONSTRAINT ck_ecom_pl_type  CHECK (entry_type IN ('EARN', 'REDEEM', 'EXPIRE', 'ADJUST')),
    /* Direction is part of the type, so a REDEEM that adds points is refused
       here rather than discovered in a balance six weeks later. */
    CONSTRAINT ck_ecom_pl_sign  CHECK (
        (entry_type = 'EARN'   AND points > 0)
     OR (entry_type = 'REDEEM' AND points < 0)
     OR (entry_type = 'EXPIRE' AND points < 0)
     OR (entry_type = 'ADJUST'))
);

CREATE INDEX IF NOT EXISTS ix_ecom_pl_customer ON pharma.ecom_point_ledger (pid_ecom_customer, created_at DESC);

/* An order earns once. Delivering it twice — a stage corrected, an event
   replayed — must not pay twice. */
CREATE UNIQUE INDEX IF NOT EXISTS uq_ecom_pl_earn
    ON pharma.ecom_point_ledger (pid_ecom_order)
 WHERE entry_type = 'EARN' AND pid_ecom_order IS NOT NULL;

/* And it is redeemed against once. */
CREATE UNIQUE INDEX IF NOT EXISTS uq_ecom_pl_redeem
    ON pharma.ecom_point_ledger (pid_ecom_order)
 WHERE entry_type = 'REDEEM' AND pid_ecom_order IS NOT NULL;

COMMENT ON TABLE pharma.ecom_point_ledger IS
  'Every point earned, spent, expired or adjusted. Append-only; ECOM_CUSTOMER.POINTS_BALANCE is a cache of its sum.';

SELECT (SELECT count(*) FROM pharma.ecom_point_rule)   AS rules,
       (SELECT count(*) FROM pharma.ecom_point_ledger) AS entries,
       (SELECT count(*) FROM pharma.ecom_customer WHERE COALESCE(points_balance, 0) <> 0) AS customers_with_points;
