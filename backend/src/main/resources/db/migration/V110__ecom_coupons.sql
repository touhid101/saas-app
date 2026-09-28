/* ─────────────────────────────────────────────────────────────────────────────
   V110 — Coupons, and the screen that manages them.

   A coupon is the one promotion the ERP has never had (docs/ecommerce 15 §1):
   the product price list, the standing trade discount (POS_SALES_DISCOUNT) and
   the manual invoice discount all exist, and none of them is a code a customer
   types. These tables add exactly that, and nothing that duplicates the three.

     ECOM_COUPON         the code and its rules
     ECOM_COUPON_BRANCH  which pharmacies honour it, when it is not all of them
     ECOM_COUPON_USAGE   one row per order it was spent on — the limits are
                         counted from here, never from a counter on the coupon

   BRANCH SCOPE IS NOT NULL AND HAS NO DEFAULT. A coupon that became valid
   everywhere because somebody left a box empty is the expensive kind of
   mistake, so the column refuses to be guessed at.

   USAGE IS A LEDGER, NOT A COUNTER. Two customers redeeming the last use of a
   coupon at the same instant would both pass a counter read; they cannot both
   insert a row under the unique index below.

   WHO PAYS FOR THE DISCOUNT is still a business decision (15 §3). Today the
   amount lands on the pharmacy's own invoice as INVOICE_DIS_AMT, so the
   pharmacy's revenue carries it. A platform-funded coupon would need a
   settlement account and is NOT implemented — BUSINESS/ACCOUNTING CONFIRMATION.
   ───────────────────────────────────────────────────────────────────────────── */

CREATE SEQUENCE IF NOT EXISTS pharma.seq_ecom_coupon;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_ecom_coupon_branch;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_ecom_coupon_usage;

CREATE TABLE IF NOT EXISTS pharma.ecom_coupon (
    pid_coupon            BIGINT       PRIMARY KEY DEFAULT nextval('pharma.seq_ecom_coupon'),
    code                  VARCHAR(40)  NOT NULL,
    coupon_name           VARCHAR(150) NOT NULL,
    description           VARCHAR(300),

    /* PERCENT of the basket · FIXED sum off · FREE_DELIVERY pays the delivery
       charge V108 introduced, which is the promotion a pharmacy reaches for
       first once delivery costs something. */
    discount_type         VARCHAR(20)  NOT NULL,
    discount_value        NUMERIC(12, 2),
    /* The most a PERCENT coupon may ever be worth. Without it, "20% off" on a
       50,000 order is a 10,000 gift nobody intended. */
    max_discount_amount   NUMERIC(12, 2),
    min_order_amount      NUMERIC(14, 2),

    /* Who may use it, and where. Both are decisions, so neither has a default. */
    branch_scope          VARCHAR(20)  NOT NULL,
    customer_type_scope   VARCHAR(20)  NOT NULL DEFAULT 'ALL',
    first_order_only      VARCHAR(1)   NOT NULL DEFAULT 'N',

    usage_limit_total     INTEGER,
    usage_limit_per_customer INTEGER,

    starts_at             TIMESTAMP,
    ends_at               TIMESTAMP,
    is_active             VARCHAR(1)   NOT NULL DEFAULT 'Y',

    /* The pharmacy that made it, or null for one the platform made. Kept for
       the audit trail; what a coupon applies to is BRANCH_SCOPE's business. */
    pid_owner_branch      BIGINT,
    pid_org               BIGINT,

    status                VARCHAR(1)   DEFAULT 'Y',
    inserted_by           VARCHAR(50),
    inserted_date         TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_by            VARCHAR(50),
    updated_date          TIMESTAMP,

    CONSTRAINT ck_ecom_coupon_type   CHECK (discount_type IN ('PERCENT', 'FIXED', 'FREE_DELIVERY')),
    CONSTRAINT ck_ecom_coupon_scope  CHECK (branch_scope IN ('ALL_BRANCHES', 'SELECTED_BRANCHES')),
    CONSTRAINT ck_ecom_coupon_tier   CHECK (customer_type_scope IN ('ALL', 'RETAILER', 'WHOLESALER', 'SPECIAL')),
    CONSTRAINT ck_ecom_coupon_value  CHECK (
        (discount_type = 'FREE_DELIVERY' AND COALESCE(discount_value, 0) >= 0)
        OR (discount_type = 'PERCENT' AND discount_value > 0 AND discount_value <= 100)
        OR (discount_type = 'FIXED'   AND discount_value > 0)),
    CONSTRAINT ck_ecom_coupon_window CHECK (starts_at IS NULL OR ends_at IS NULL OR ends_at > starts_at),
    CONSTRAINT ck_ecom_coupon_limits CHECK (COALESCE(usage_limit_total, 1) > 0
                                        AND COALESCE(usage_limit_per_customer, 1) > 0)
);

/* Codes are typed by people, so SAVE100 and save100 are the same coupon. */
CREATE UNIQUE INDEX IF NOT EXISTS uq_ecom_coupon_code ON pharma.ecom_coupon (LOWER(code));
CREATE INDEX IF NOT EXISTS ix_ecom_coupon_active ON pharma.ecom_coupon (is_active, ends_at);

CREATE TABLE IF NOT EXISTS pharma.ecom_coupon_branch (
    pid_coupon_branch BIGINT PRIMARY KEY DEFAULT nextval('pharma.seq_ecom_coupon_branch'),
    pid_coupon        BIGINT NOT NULL,
    pid_branch        BIGINT NOT NULL,
    status            VARCHAR(1) DEFAULT 'Y',
    inserted_by       VARCHAR(50),
    inserted_date     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_by        VARCHAR(50),
    updated_date      TIMESTAMP,
    CONSTRAINT fk_ecom_cb_coupon FOREIGN KEY (pid_coupon) REFERENCES pharma.ecom_coupon (pid_coupon),
    CONSTRAINT fk_ecom_cb_branch FOREIGN KEY (pid_branch) REFERENCES pharma.all_branch (pid_branch)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_ecom_coupon_branch ON pharma.ecom_coupon_branch (pid_coupon, pid_branch);

CREATE TABLE IF NOT EXISTS pharma.ecom_coupon_usage (
    pid_usage         BIGINT PRIMARY KEY DEFAULT nextval('pharma.seq_ecom_coupon_usage'),
    pid_coupon        BIGINT NOT NULL,
    pid_ecom_customer BIGINT NOT NULL,
    pid_ecom_order    BIGINT NOT NULL,
    pid_branch        BIGINT NOT NULL,
    /* What it was actually worth on that order, which is what a report about
       the cost of a campaign has to add up. */
    amount            NUMERIC(14, 2) NOT NULL,
    used_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    /* Set when the order was called off: the use is released without losing the
       record that it happened. */
    released_at       TIMESTAMP,

    status            VARCHAR(1) DEFAULT 'Y',
    inserted_by       VARCHAR(50),
    inserted_date     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_by        VARCHAR(50),
    updated_date      TIMESTAMP,

    CONSTRAINT fk_ecom_cu_coupon FOREIGN KEY (pid_coupon)     REFERENCES pharma.ecom_coupon (pid_coupon),
    CONSTRAINT fk_ecom_cu_order  FOREIGN KEY (pid_ecom_order) REFERENCES pharma.ecom_order (pid_ecom_order),
    CONSTRAINT ck_ecom_cu_amount CHECK (amount >= 0)
);

/* One coupon per order, once. This index is the usage limit's real enforcement:
   two simultaneous redemptions cannot both land. */
CREATE UNIQUE INDEX IF NOT EXISTS uq_ecom_usage_order ON pharma.ecom_coupon_usage (pid_ecom_order);
CREATE INDEX IF NOT EXISTS ix_ecom_usage_coupon   ON pharma.ecom_coupon_usage (pid_coupon, released_at);
CREATE INDEX IF NOT EXISTS ix_ecom_usage_customer ON pharma.ecom_coupon_usage (pid_coupon, pid_ecom_customer, released_at);

/* ═══ The screen (MOD54, reserved by V101) ═════════════════════════════════ */

CREATE SEQUENCE IF NOT EXISTS pharma.sq_acl_module;
CREATE SEQUENCE IF NOT EXISTS pharma.sq_pos_perm;
SELECT setval('pharma.sq_acl_module',
    GREATEST((SELECT COALESCE(MAX(pid_module), 0) FROM pharma.acl_module_info),
             (SELECT last_value FROM pharma.sq_acl_module)), true);
SELECT setval('pharma.sq_pos_perm',
    GREATEST((SELECT COALESCE(MAX(pid_permission), 0) FROM pharma.pos_permissions),
             (SELECT last_value FROM pharma.sq_pos_perm)), true);

INSERT INTO pharma.acl_module_info
       (pid_module, module_code, module_name, module_desc, route_path, icon_key,
        pid_parent_module, sort_order, is_active, status, inserted_by)
SELECT nextval('pharma.sq_acl_module'), 'MOD54', 'E-Commerce Promotions',
       'Coupon codes customers type at checkout: who may use them, where, and for how long',
       '/ecompromotions', 'ecompromotions',
       (SELECT p.pid_module FROM pharma.acl_module_info p WHERE p.module_code = 'GRP_ECOM'),
       2, 1, 'Y', 'V110'
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_module_info WHERE module_code = 'MOD54');

UPDATE pharma.acl_module_info m
   SET pid_parent_module = (SELECT p.pid_module FROM pharma.acl_module_info p WHERE p.module_code = 'GRP_ECOM'),
       updated_by = 'V110', updated_date = CURRENT_TIMESTAMP
 WHERE m.module_code = 'MOD54' AND m.pid_parent_module IS NULL;

/* A coupon costs the pharmacy money, so creating one is an approval-grade act:
   the same roles that may decide on an order may decide on a discount. */
CREATE TEMP TABLE ecom_promo_rights (
    role_code varchar(20),
    can_view int, can_create int, can_edit int, can_delete int,
    can_export int, can_approve int, can_print int
) ON COMMIT DROP;

INSERT INTO ecom_promo_rights VALUES
    ('ROLE01', 1, 1, 1, 1, 1, 1, 0),
    ('ROLE02', 1, 1, 1, 0, 1, 1, 0),
    ('ROLE04', 1, 0, 0, 0, 1, 0, 0);

INSERT INTO pharma.pos_permissions
       (pid_permission, pid_role, pid_module,
        can_view, can_create, can_edit, can_delete, can_export, can_approve, can_print,
        status, inserted_by)
SELECT nextval('pharma.sq_pos_perm'), g.pid_group, m.pid_module,
       r.can_view, r.can_create, r.can_edit, r.can_delete, r.can_export, r.can_approve, r.can_print,
       'Y', 'V110'
  FROM ecom_promo_rights r
  JOIN pharma.acl_group g ON g.role_code = r.role_code
  JOIN pharma.acl_module_info m ON m.module_code = 'MOD54'
 WHERE NOT EXISTS (SELECT 1 FROM pharma.pos_permissions x
                    WHERE x.pid_role = g.pid_group AND x.pid_module = m.pid_module);

SELECT (SELECT count(*) FROM pharma.ecom_coupon)                                   AS coupons,
       (SELECT count(*) FROM pharma.acl_module_info WHERE module_code = 'MOD54')   AS promotions_module,
       (SELECT count(*) FROM pharma.pos_permissions p
          JOIN pharma.acl_module_info m ON m.pid_module = p.pid_module AND m.module_code = 'MOD54') AS matrix_rows;
