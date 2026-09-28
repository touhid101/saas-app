/* ─────────────────────────────────────────────────────────────────────────────
   V100 — E-commerce: the global customer.

   Phase 1 of docs/ecommerce (see 04-database-gap-analysis.md and the Phase 0
   report, sections C and D). Everything here is additive: seven new tables and
   nothing changed on any existing one.

   Why a new table and not POS_CUSTOMER_INFO. That table is a pharmacy's own
   customer list: PID_BRANCH is NOT NULL, a phone number is unique per branch,
   every read is scoped to the branch and the row is written by branch staff.
   An online customer is none of those things - one identity, one phone, able to
   buy from any pharmacy on the platform. So the identity lives here, outside
   every organisation, and the pharmacy's table keeps its meaning. When such a
   customer first orders from a pharmacy the application creates an ordinary
   POS_CUSTOMER_INFO row there (the "shadow") and records the pairing in
   ECOM_CUSTOMER_BRANCH_LINK, so invoices, returns, collections, ledger and
   reports see the customer they have always seen.

   Tables
     ecom_customer               who they are, what tier they buy at, and whether
                                 the platform has approved them
     ecom_customer_auth          how they sign in - separate from ACL_USER, which is
                                 staff and belongs to a tenant
     ecom_customer_refresh_token their sessions
     ecom_customer_address       where they want things delivered (many)
     ecom_customer_type_history  every change of tier, by whom and why
     ecom_customer_branch_link   the shadow rows, one per pharmacy ordered from
     ecom_audit_log              what happened to the account, by customer or staff

   Naming follows the estate: pharma schema, pid_* keys from a sequence,
   BaseEntity columns (status, inserted_by/date, updated_by/date).

   Not created here, deliberately: any org or branch column on the customer,
   any copy of product, price or stock, any order table (Phase 5-6).
   ───────────────────────────────────────────────────────────────────────────── */

CREATE SEQUENCE IF NOT EXISTS pharma.seq_ecom_customer               START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_ecom_customer_auth          START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_ecom_customer_refresh_token START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_ecom_customer_address       START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_ecom_customer_type_history  START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_ecom_customer_branch_link   START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_ecom_audit_log              START WITH 1 INCREMENT BY 1;

/* ═══ 1 · The customer ══════════════════════════════════════════════════════ */

CREATE TABLE IF NOT EXISTS pharma.ecom_customer (
    pid_ecom_customer   BIGINT        NOT NULL,

    /* EC000123 - assigned from the id after insert, the way PRODUCT_CODE is. */
    customer_code       VARCHAR(30),

    full_name           VARCHAR(150)  NOT NULL,

    /* Digits only, as normalised by the application (leading + removed, spaces
       and dashes stripped). Unique across the whole platform - this is the
       identity, not a branch's note of it. */
    mobile_no           VARCHAR(20)   NOT NULL,
    email               VARCHAR(150),

    /* The pricing tier - the thing the ERP's CustomerPricing.tierOf() reads.
       Only the three tiers the sale path knows. Defaults to the most
       restrictive; the platform raises it on approval. */
    customer_type       VARCHAR(20)   NOT NULL DEFAULT 'RETAILER',

    /* What the customer asked for when registering, so the approver sees it.
       Never used for pricing. */
    requested_type      VARCHAR(20),

    approval_status     VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    approved_by         VARCHAR(50),
    approved_at         TIMESTAMP,
    reject_reason       VARCHAR(500),
    suspend_reason      VARCHAR(500),

    /* Trade identity for wholesale and special tiers. Free text: which documents
       justify which tier is a business decision recorded in 09-customer-approval. */
    business_name       VARCHAR(200),
    trade_license_no    VARCHAR(60),
    drug_license_no     VARCHAR(60),

    /* Where they registered from. A default for "near me"; it links the
       customer to no pharmacy. */
    reg_address         VARCHAR(300),
    reg_area            VARCHAR(100),
    reg_city            VARCHAR(100),
    reg_latitude        NUMERIC(9,6),
    reg_longitude       NUMERIC(9,6),

    avatar_url          VARCHAR(500),

    /* A cache of the loyalty ledger (Phase 13). Never edited directly. */
    points_balance      NUMERIC(14,2) NOT NULL DEFAULT 0,

    status              VARCHAR(1)    NOT NULL DEFAULT 'Y',
    inserted_by         VARCHAR(50),
    inserted_date       TIMESTAMP,
    updated_by          VARCHAR(50),
    updated_date        TIMESTAMP,

    CONSTRAINT ecom_customer_pkey PRIMARY KEY (pid_ecom_customer),
    CONSTRAINT ck_ecom_customer_type CHECK (customer_type IN ('RETAILER', 'WHOLESALER', 'SPECIAL')),
    CONSTRAINT ck_ecom_customer_requested_type CHECK (requested_type IS NULL OR requested_type IN ('RETAILER', 'WHOLESALER', 'SPECIAL')),
    CONSTRAINT ck_ecom_customer_approval CHECK (approval_status IN ('PENDING', 'APPROVED', 'REJECTED', 'SUSPENDED')),
    CONSTRAINT ck_ecom_customer_latitude  CHECK (reg_latitude  IS NULL OR (reg_latitude  BETWEEN -90  AND 90)),
    CONSTRAINT ck_ecom_customer_longitude CHECK (reg_longitude IS NULL OR (reg_longitude BETWEEN -180 AND 180))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_ecom_customer_mobile
    ON pharma.ecom_customer (mobile_no);
CREATE UNIQUE INDEX IF NOT EXISTS uq_ecom_customer_email
    ON pharma.ecom_customer (lower(email)) WHERE email IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_ecom_customer_code
    ON pharma.ecom_customer (customer_code) WHERE customer_code IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_ecom_customer_approval
    ON pharma.ecom_customer (approval_status, inserted_date);
CREATE INDEX IF NOT EXISTS ix_ecom_customer_name
    ON pharma.ecom_customer (lower(full_name));

COMMENT ON TABLE  pharma.ecom_customer IS
  'E-commerce customer: one global identity per phone number, not owned by any org or branch. Buys from any ECOM-enabled pharmacy.';
COMMENT ON COLUMN pharma.ecom_customer.customer_type IS
  'Pricing tier read by the sale path: RETAILER (pcs only), WHOLESALER, SPECIAL. Copied to every shadow POS_CUSTOMER_INFO row on change.';
COMMENT ON COLUMN pharma.ecom_customer.approval_status IS
  'PENDING until the platform approves; APPROVED may order; REJECTED and SUSPENDED may sign in but not order.';

/* ═══ 2 · Credentials ══════════════════════════════════════════════════════ */

CREATE TABLE IF NOT EXISTS pharma.ecom_customer_auth (
    pid_auth            BIGINT        NOT NULL,
    pid_ecom_customer   BIGINT        NOT NULL,

    /* The normalised mobile number today; an e-mail is admitted by the same
       column should the sign-in policy widen. Unique on the platform. */
    login_identifier    VARCHAR(150)  NOT NULL,

    /* Written by the shared PasswordEncoder: always {bcrypt}..., never plain. */
    password_hash       VARCHAR(255)  NOT NULL,

    auth_status         VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    failed_login_count  INTEGER       NOT NULL DEFAULT 0,
    locked_until        TIMESTAMP,
    last_login_at       TIMESTAMP,
    password_changed_at TIMESTAMP,
    email_verified      VARCHAR(1)    NOT NULL DEFAULT 'N',
    phone_verified      VARCHAR(1)    NOT NULL DEFAULT 'N',

    status              VARCHAR(1)    NOT NULL DEFAULT 'Y',
    inserted_by         VARCHAR(50),
    inserted_date       TIMESTAMP,
    updated_by          VARCHAR(50),
    updated_date        TIMESTAMP,

    CONSTRAINT ecom_customer_auth_pkey PRIMARY KEY (pid_auth),
    CONSTRAINT fk_ecom_auth_customer FOREIGN KEY (pid_ecom_customer)
        REFERENCES pharma.ecom_customer (pid_ecom_customer),
    CONSTRAINT uq_ecom_auth_customer UNIQUE (pid_ecom_customer),
    CONSTRAINT uq_ecom_auth_login UNIQUE (login_identifier),
    CONSTRAINT ck_ecom_auth_status CHECK (auth_status IN ('ACTIVE', 'LOCKED', 'DISABLED')),
    CONSTRAINT ck_ecom_auth_failed CHECK (failed_login_count >= 0)
);

COMMENT ON TABLE pharma.ecom_customer_auth IS
  'Sign-in credentials of an e-commerce customer. Separate from ACL_USER (staff, tenant-bound). Hash from the shared PasswordEncoder.';

/* ═══ 3 · Sessions ═════════════════════════════════════════════════════════ */

CREATE TABLE IF NOT EXISTS pharma.ecom_customer_refresh_token (
    pid_token           BIGINT        NOT NULL,
    pid_ecom_customer   BIGINT        NOT NULL,
    token_value         VARCHAR(512)  NOT NULL,
    expires_at          TIMESTAMP     NOT NULL,
    revoked             VARCHAR(1)    NOT NULL DEFAULT 'N',
    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ecom_customer_refresh_token_pkey PRIMARY KEY (pid_token),
    CONSTRAINT fk_ecom_refresh_customer FOREIGN KEY (pid_ecom_customer)
        REFERENCES pharma.ecom_customer (pid_ecom_customer),
    CONSTRAINT uq_ecom_refresh_token UNIQUE (token_value)
);

CREATE INDEX IF NOT EXISTS ix_ecom_refresh_customer
    ON pharma.ecom_customer_refresh_token (pid_ecom_customer, revoked);

/* ═══ 4 · Addresses ════════════════════════════════════════════════════════ */

CREATE TABLE IF NOT EXISTS pharma.ecom_customer_address (
    pid_address         BIGINT        NOT NULL,
    pid_ecom_customer   BIGINT        NOT NULL,

    label               VARCHAR(40),
    recipient_name      VARCHAR(150),
    phone               VARCHAR(20),
    line1               VARCHAR(200)  NOT NULL,
    line2               VARCHAR(200),
    area                VARCHAR(100),
    city                VARCHAR(100),
    postcode            VARCHAR(20),
    latitude            NUMERIC(9,6),
    longitude           NUMERIC(9,6),
    is_default          VARCHAR(1)    NOT NULL DEFAULT 'N',

    status              VARCHAR(1)    NOT NULL DEFAULT 'Y',
    inserted_by         VARCHAR(50),
    inserted_date       TIMESTAMP,
    updated_by          VARCHAR(50),
    updated_date        TIMESTAMP,

    CONSTRAINT ecom_customer_address_pkey PRIMARY KEY (pid_address),
    CONSTRAINT fk_ecom_address_customer FOREIGN KEY (pid_ecom_customer)
        REFERENCES pharma.ecom_customer (pid_ecom_customer),
    CONSTRAINT ck_ecom_address_default CHECK (is_default IN ('Y', 'N')),
    CONSTRAINT ck_ecom_address_latitude  CHECK (latitude  IS NULL OR (latitude  BETWEEN -90  AND 90)),
    CONSTRAINT ck_ecom_address_longitude CHECK (longitude IS NULL OR (longitude BETWEEN -180 AND 180))
);

CREATE INDEX IF NOT EXISTS ix_ecom_address_customer
    ON pharma.ecom_customer_address (pid_ecom_customer, status);
/* One default per customer among the live addresses. */
CREATE UNIQUE INDEX IF NOT EXISTS uq_ecom_address_default
    ON pharma.ecom_customer_address (pid_ecom_customer)
 WHERE is_default = 'Y' AND status = 'Y';

/* ═══ 5 · Tier changes ═════════════════════════════════════════════════════ */

CREATE TABLE IF NOT EXISTS pharma.ecom_customer_type_history (
    pid_history         BIGINT        NOT NULL,
    pid_ecom_customer   BIGINT        NOT NULL,
    old_type            VARCHAR(20),
    new_type            VARCHAR(20)   NOT NULL,
    changed_by          VARCHAR(50)   NOT NULL,
    reason              VARCHAR(300),
    changed_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ecom_customer_type_history_pkey PRIMARY KEY (pid_history),
    CONSTRAINT fk_ecom_type_history_customer FOREIGN KEY (pid_ecom_customer)
        REFERENCES pharma.ecom_customer (pid_ecom_customer)
);

CREATE INDEX IF NOT EXISTS ix_ecom_type_history_customer
    ON pharma.ecom_customer_type_history (pid_ecom_customer, changed_at);

/* ═══ 6 · The shadow rows ══════════════════════════════════════════════════
   One pairing per (customer, pharmacy). The POS row is unique across links:
   two online identities can never share one pharmacy customer. */

CREATE TABLE IF NOT EXISTS pharma.ecom_customer_branch_link (
    pid_link            BIGINT        NOT NULL,
    pid_ecom_customer   BIGINT        NOT NULL,
    pid_branch          BIGINT        NOT NULL,
    pid_customer        BIGINT        NOT NULL,

    /* CREATED - the application made the POS row; LINKED - a row branch staff
       had already made for the same phone was adopted. */
    link_mode           VARCHAR(10)   NOT NULL DEFAULT 'CREATED',
    linked_at           TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    linked_by           VARCHAR(50),

    CONSTRAINT ecom_customer_branch_link_pkey PRIMARY KEY (pid_link),
    CONSTRAINT fk_ecom_link_customer FOREIGN KEY (pid_ecom_customer)
        REFERENCES pharma.ecom_customer (pid_ecom_customer),
    CONSTRAINT fk_ecom_link_branch FOREIGN KEY (pid_branch)
        REFERENCES pharma.all_branch (pid_branch),
    CONSTRAINT fk_ecom_link_pos_customer FOREIGN KEY (pid_customer)
        REFERENCES pharma.pos_customer_info (pid_customer),
    CONSTRAINT uq_ecom_link_customer_branch UNIQUE (pid_ecom_customer, pid_branch),
    CONSTRAINT uq_ecom_link_pos_customer UNIQUE (pid_customer),
    CONSTRAINT ck_ecom_link_mode CHECK (link_mode IN ('CREATED', 'LINKED'))
);

COMMENT ON TABLE pharma.ecom_customer_branch_link IS
  'The POS_CUSTOMER_INFO row that represents a global e-commerce customer inside one pharmacy. Created on first order there; the branch never owns the identity.';

/* ═══ 7 · Audit ════════════════════════════════════════════════════════════
   Written by its own transaction so a refused action still leaves its trace. */

CREATE TABLE IF NOT EXISTS pharma.ecom_audit_log (
    pid_audit           BIGINT        NOT NULL,
    event_type          VARCHAR(40)   NOT NULL,
    actor_kind          VARCHAR(10)   NOT NULL,
    actor_id            VARCHAR(80),
    pid_ecom_customer   BIGINT,
    pid_branch          BIGINT,
    ref_type            VARCHAR(30),
    ref_id              BIGINT,
    detail              VARCHAR(1000),
    ip_address          VARCHAR(64),
    acted_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ecom_audit_log_pkey PRIMARY KEY (pid_audit),
    CONSTRAINT ck_ecom_audit_actor CHECK (actor_kind IN ('CUSTOMER', 'STAFF', 'SYSTEM'))
);

CREATE INDEX IF NOT EXISTS ix_ecom_audit_customer
    ON pharma.ecom_audit_log (pid_ecom_customer, acted_at);
CREATE INDEX IF NOT EXISTS ix_ecom_audit_event
    ON pharma.ecom_audit_log (event_type, acted_at);

/* ═══ Verification ══════════════════════════════════════════════════════════
   Seven tables, all empty on arrival. */
SELECT count(*) AS ecom_tables
  FROM information_schema.tables
 WHERE table_schema = 'pharma'
   AND table_name IN ('ecom_customer', 'ecom_customer_auth', 'ecom_customer_refresh_token',
                      'ecom_customer_address', 'ecom_customer_type_history',
                      'ecom_customer_branch_link', 'ecom_audit_log');
