/* ─────────────────────────────────────────────────────────────────────────────
   Accounts — the core: chart of accounts, periods, cost centres, tax,
   accounting events, accounting rules, journals and the ledger view.

   Nothing accounting-shaped existed before this migration (see
   docs/accounts/current-financial-transaction-analysis.md, section 1). Every
   object here is new; every statement is guarded, so the file is safe to run
   against a database that already carries some of it, and safe against the
   baseline-63 trap (no seed row from below V64 is assumed).

   The shape, in one breath:

     business transaction  →  acc_event (one per source transaction, unique)
                           →  acc_rule / acc_rule_line (which accounts, by role)
                           →  acc_journal + acc_journal_line (balanced, POSTED)
                           →  acc_v_ledger (the general ledger)

   Books are kept per organisation (the pharmacy business): each organisation
   gets its own copy of the chart of accounts, its own periods, rules may be
   overridden per organisation. Every journal line carries the branch, so a
   branch is a dimension of the ledger and never a second set of books.

   Amounts are NUMERIC(18,2) everywhere money is stored; the calculators round
   HALF_UP to 2 places before a line is written, so a stored figure is what
   the screen shows.
   ───────────────────────────────────────────────────────────────────────────── */

/* ═══ Sequences ═══════════════════════════════════════════════════════════ */

CREATE SEQUENCE IF NOT EXISTS pharma.seq_acc_account        START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_acc_fiscal_year    START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_acc_period         START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_acc_cost_center    START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_acc_tax_rate       START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_acc_event          START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_acc_rule           START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_acc_rule_line      START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_acc_journal        START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_acc_journal_line   START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_acc_setting        START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_acc_payment_mode   START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_acc_bank_account   START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_acc_cash_voucher   START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_acc_backfill_batch START WITH 1 INCREMENT BY 1;

/* ═══ Number series ═══════════════════════════════════════════════════════
   Journals, cash vouchers, investor transactions and profit distributions
   each get their own monthly counter in the tracker DocumentNumberService
   already locks — one numbering scheme, four more rows. */
INSERT INTO pharma.sequence_tracker (seq_name, last_year_month, last_value)
SELECT v.n, NULL, 0
  FROM (VALUES ('SEQ_JOURNAL_NO'), ('SEQ_CASH_VOUCHER_NO'), ('SEQ_INVESTOR_TXN_NO'), ('SEQ_PROFIT_DIST_NO')) AS v(n)
 WHERE NOT EXISTS (SELECT 1 FROM pharma.sequence_tracker t WHERE t.seq_name = v.n);

/* ═══ Chart of accounts template ══════════════════════════════════════════
   The default hierarchy every organisation starts from. Copied, not shared:
   a pharmacy renames and extends its own list without touching another.
   role_key is what the accounting rules post to; a role is unique within an
   organisation, so a rule never has to name a code. */

CREATE TABLE IF NOT EXISTS pharma.acc_account_template (
    account_code    VARCHAR(20)  NOT NULL,
    account_name    VARCHAR(150) NOT NULL,
    account_type    VARCHAR(10)  NOT NULL,   /* ASSET LIABILITY EQUITY REVENUE EXPENSE */
    parent_code     VARCHAR(20),
    is_group        SMALLINT     DEFAULT 0,
    normal_side     VARCHAR(1)   NOT NULL,   /* D or C */
    role_key        VARCHAR(40),
    allow_manual    SMALLINT     DEFAULT 1,
    sort_order      INT          DEFAULT 100,
    CONSTRAINT acc_account_template_pkey PRIMARY KEY (account_code),
    CONSTRAINT ck_acc_tpl_type CHECK (account_type IN ('ASSET','LIABILITY','EQUITY','REVENUE','EXPENSE')),
    CONSTRAINT ck_acc_tpl_side CHECK (normal_side IN ('D','C'))
);

INSERT INTO pharma.acc_account_template (account_code, account_name, account_type, parent_code, is_group, normal_side, role_key, allow_manual, sort_order)
SELECT v.code, v.name, v.type, v.parent, v.grp, v.side, v.role, v.manual, v.ord
  FROM (VALUES
    ('1000','Assets',                        'ASSET',    NULL,  1,'D',NULL,                    0, 1000),
    ('1100','Cash & Cash Equivalents',       'ASSET',    '1000',1,'D',NULL,                    0, 1100),
    ('1110','Cash Register',                 'ASSET',    '1100',0,'D','CASH_REGISTER',         1, 1110),
    ('1120','Petty Cash',                    'ASSET',    '1100',0,'D','PETTY_CASH',            1, 1120),
    ('1130','Bank',                          'ASSET',    '1100',1,'D',NULL,                    0, 1130),
    ('1131','Bank - Main Account',           'ASSET',    '1130',0,'D','BANK_DEFAULT',          1, 1131),
    ('1140','Mobile Wallets (bKash, Nagad)', 'ASSET',    '1100',0,'D','MOBILE_WALLET',         1, 1140),
    ('1150','Cash in Transit',               'ASSET',    '1100',0,'D','CASH_IN_TRANSIT',       1, 1150),
    ('1200','Accounts Receivable',           'ASSET',    '1000',0,'D','AR',                    0, 1200),
    ('1300','Inventory',                     'ASSET',    '1000',0,'D','INVENTORY',             0, 1300),
    ('1400','Prepayments & Advances',        'ASSET',    '1000',1,'D',NULL,                    0, 1400),
    ('1410','Supplier Advances',             'ASSET',    '1400',0,'D','SUPPLIER_ADVANCE',      0, 1410),
    ('1420','Employee Advances',             'ASSET',    '1400',0,'D','EMPLOYEE_ADVANCE',      0, 1420),
    ('1430','Employee Loans',                'ASSET',    '1400',0,'D','EMPLOYEE_LOAN',         0, 1430),
    ('1440','Other Prepayments',             'ASSET',    '1400',0,'D','PREPAYMENT',            1, 1440),
    ('1500','Fixed Assets',                  'ASSET',    '1000',1,'D',NULL,                    0, 1500),
    ('1510','Furniture & Equipment',         'ASSET',    '1500',0,'D','FIXED_ASSET',           1, 1510),
    ('2000','Liabilities',                   'LIABILITY',NULL,  1,'C',NULL,                    0, 2000),
    ('2100','Accounts Payable',              'LIABILITY','2000',0,'C','AP',                    0, 2100),
    ('2200','Payroll Payable',               'LIABILITY','2000',1,'C',NULL,                    0, 2200),
    ('2210','Salary Payable',                'LIABILITY','2200',0,'C','SALARY_PAYABLE',        0, 2210),
    ('2220','Provident Fund Payable',        'LIABILITY','2200',0,'C','PF_PAYABLE',            0, 2220),
    ('2230','Employee Reimbursements Payable','LIABILITY','2200',0,'C','EMPLOYEE_REIMB_PAYABLE',0, 2230),
    ('2240','Payroll Other Deductions',      'LIABILITY','2200',0,'C','PAYROLL_OTHER_DED',     0, 2240),
    ('2300','Tax Payable',                   'LIABILITY','2000',1,'C',NULL,                    0, 2300),
    ('2310','Salary Tax Payable',            'LIABILITY','2300',0,'C','TAX_PAYABLE',           0, 2310),
    ('2320','VAT Payable',                   'LIABILITY','2300',0,'C','VAT_PAYABLE',           1, 2320),
    ('2400','Investor Profit Payable',       'LIABILITY','2000',0,'C','INVESTOR_PROFIT_PAYABLE',0,2400),
    ('2500','Other Payables',                'LIABILITY','2000',0,'C','OTHER_PAYABLE',         1, 2500),
    ('3000','Equity',                        'EQUITY',   NULL,  1,'C',NULL,                    0, 3000),
    ('3100','Owner Capital',                 'EQUITY',   '3000',0,'C','OWNER_CAPITAL',         1, 3100),
    ('3200','Investor Capital',              'EQUITY',   '3000',0,'C','INVESTOR_CAPITAL',      0, 3200),
    ('3300','Retained Earnings',             'EQUITY',   '3000',0,'C','RETAINED_EARNINGS',     0, 3300),
    ('3400','Profit Distributed',            'EQUITY',   '3000',0,'D','PROFIT_DISTRIBUTION',   0, 3400),
    ('3900','Opening Balance Equity',        'EQUITY',   '3000',0,'C','OPENING_BALANCE_EQUITY',0, 3900),
    ('4000','Revenue',                       'REVENUE',  NULL,  1,'C',NULL,                    0, 4000),
    ('4100','Sales Revenue',                 'REVENUE',  '4000',0,'C','SALES_REVENUE',         0, 4100),
    ('4150','Sales Returns',                 'REVENUE',  '4000',0,'D','SALES_RETURNS',         0, 4150),
    ('4200','Delivery Income',               'REVENUE',  '4000',0,'C','DELIVERY_INCOME',       0, 4200),
    ('4300','Subscription Revenue',          'REVENUE',  '4000',0,'C','SUBSCRIPTION_REVENUE',  0, 4300),
    ('4900','Other Income',                  'REVENUE',  '4000',0,'C','OTHER_INCOME',          1, 4900),
    ('5000','Cost of Sales',                 'EXPENSE',  NULL,  1,'D',NULL,                    0, 5000),
    ('5100','Cost of Goods Sold',            'EXPENSE',  '5000',0,'D','COGS',                  0, 5100),
    ('6000','Operating Expenses',            'EXPENSE',  NULL,  1,'D',NULL,                    0, 6000),
    ('6100','Salaries & Wages',              'EXPENSE',  '6000',0,'D','SALARY_EXPENSE',        0, 6100),
    ('6110','Provident Fund Expense',        'EXPENSE',  '6000',0,'D','PF_EXPENSE',            0, 6110),
    ('6120','Gratuity Expense',              'EXPENSE',  '6000',0,'D','GRATUITY_EXPENSE',      0, 6120),
    ('6130','Staff Expenses & Conveyance',   'EXPENSE',  '6000',0,'D','STAFF_EXPENSE',         1, 6130),
    ('6200','Rent',                          'EXPENSE',  '6000',0,'D','RENT',                  1, 6200),
    ('6300','Utilities',                     'EXPENSE',  '6000',0,'D','UTILITIES',             1, 6300),
    ('6400','Bank Charges',                  'EXPENSE',  '6000',0,'D','BANK_CHARGES',          1, 6400),
    ('6500','Cash Short / Over',             'EXPENSE',  '6000',0,'D','CASH_SHORT_OVER',       0, 6500),
    ('6900','Other Expenses',                'EXPENSE',  '6000',0,'D','OTHER_EXPENSE',         1, 6900)
  ) AS v(code, name, type, parent, grp, side, role, manual, ord)
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acc_account_template t WHERE t.account_code = v.code);

/* ═══ Chart of accounts (per organisation) ═══════════════════════════════ */

CREATE TABLE IF NOT EXISTS pharma.acc_account (
    pid_account     BIGINT       NOT NULL,
    pid_org         BIGINT       NOT NULL,
    account_code    VARCHAR(20)  NOT NULL,
    account_name    VARCHAR(150) NOT NULL,
    account_type    VARCHAR(10)  NOT NULL,
    pid_parent      BIGINT,
    is_group        SMALLINT     DEFAULT 0,
    normal_side     VARCHAR(1)   NOT NULL,
    /* The posting role the rules address. NULL for accounts the rules never
       post to directly (groups, manual-only accounts). Unique per org. */
    role_key        VARCHAR(40),
    allow_manual    SMALLINT     DEFAULT 1,
    /* 1 = created from the template; renaming is allowed, deleting is not. */
    is_system       SMALLINT     DEFAULT 0,
    is_active       SMALLINT     DEFAULT 1,
    currency        VARCHAR(3)   DEFAULT 'BDT',
    description     VARCHAR(500),
    sort_order      INT          DEFAULT 100,
    status          VARCHAR(1)   DEFAULT 'Y',
    inserted_by     VARCHAR(50),
    inserted_date   TIMESTAMP,
    updated_by      VARCHAR(50),
    updated_date    TIMESTAMP,
    CONSTRAINT acc_account_pkey PRIMARY KEY (pid_account),
    CONSTRAINT uq_acc_account_code UNIQUE (pid_org, account_code),
    CONSTRAINT ck_acc_account_type CHECK (account_type IN ('ASSET','LIABILITY','EQUITY','REVENUE','EXPENSE')),
    CONSTRAINT ck_acc_account_side CHECK (normal_side IN ('D','C'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_acc_account_role ON pharma.acc_account (pid_org, role_key) WHERE role_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_acc_account_org ON pharma.acc_account (pid_org, account_type, sort_order);

/* Provision every organisation that exists now. New organisations are
   provisioned by AccChartService on first use and at onboarding. */
INSERT INTO pharma.acc_account
       (pid_account, pid_org, account_code, account_name, account_type, pid_parent, is_group,
        normal_side, role_key, allow_manual, is_system, is_active, sort_order, status, inserted_by, inserted_date)
SELECT nextval('pharma.seq_acc_account'), o.pid_org, t.account_code, t.account_name, t.account_type, NULL,
       t.is_group, t.normal_side, t.role_key, t.allow_manual, 1, 1, t.sort_order, 'Y', 'V90', now()
  FROM pharma.all_organization o
 CROSS JOIN pharma.acc_account_template t
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acc_account a WHERE a.pid_org = o.pid_org AND a.account_code = t.account_code);

/* Parents, resolved by code within the same organisation. */
UPDATE pharma.acc_account a
   SET pid_parent = p.pid_account
  FROM pharma.acc_account_template t
  JOIN pharma.acc_account p ON p.account_code = t.parent_code
 WHERE a.account_code = t.account_code
   AND p.pid_org = a.pid_org
   AND a.pid_parent IS NULL
   AND t.parent_code IS NOT NULL;

/* ═══ Fiscal years and periods ════════════════════════════════════════════ */

CREATE TABLE IF NOT EXISTS pharma.acc_fiscal_year (
    pid_fiscal_year BIGINT      NOT NULL,
    pid_org         BIGINT      NOT NULL,
    year_code       VARCHAR(20) NOT NULL,      /* e.g. FY2026 */
    start_date      DATE        NOT NULL,
    end_date        DATE        NOT NULL,
    year_status     VARCHAR(10) DEFAULT 'OPEN', /* OPEN CLOSED */
    closed_by       VARCHAR(50),
    closed_at       TIMESTAMP,
    pid_closing_journal BIGINT,
    status          VARCHAR(1)  DEFAULT 'Y',
    inserted_by     VARCHAR(50),
    inserted_date   TIMESTAMP,
    updated_by      VARCHAR(50),
    updated_date    TIMESTAMP,
    CONSTRAINT acc_fiscal_year_pkey PRIMARY KEY (pid_fiscal_year),
    CONSTRAINT uq_acc_fiscal_year UNIQUE (pid_org, year_code),
    CONSTRAINT ck_acc_fiscal_year_dates CHECK (end_date > start_date),
    CONSTRAINT ck_acc_fiscal_year_status CHECK (year_status IN ('OPEN','CLOSED'))
);

CREATE TABLE IF NOT EXISTS pharma.acc_period (
    pid_period      BIGINT      NOT NULL,
    pid_fiscal_year BIGINT      NOT NULL,
    pid_org         BIGINT      NOT NULL,
    period_no       INT         NOT NULL,
    period_name     VARCHAR(30) NOT NULL,      /* e.g. 2026-09 */
    start_date      DATE        NOT NULL,
    end_date        DATE        NOT NULL,
    /* OPEN: postings accepted. CLOSED: no ordinary posting; adjustments only.
       LOCKED: nothing, not even adjustments. */
    period_status   VARCHAR(10) DEFAULT 'OPEN',
    closed_by       VARCHAR(50),
    closed_at       TIMESTAMP,
    locked_by       VARCHAR(50),
    locked_at       TIMESTAMP,
    status          VARCHAR(1)  DEFAULT 'Y',
    inserted_by     VARCHAR(50),
    inserted_date   TIMESTAMP,
    updated_by      VARCHAR(50),
    updated_date    TIMESTAMP,
    CONSTRAINT acc_period_pkey PRIMARY KEY (pid_period),
    CONSTRAINT uq_acc_period UNIQUE (pid_org, period_name),
    CONSTRAINT fk_acc_period_year FOREIGN KEY (pid_fiscal_year) REFERENCES pharma.acc_fiscal_year (pid_fiscal_year),
    CONSTRAINT ck_acc_period_status CHECK (period_status IN ('OPEN','CLOSED','LOCKED')),
    CONSTRAINT ck_acc_period_dates CHECK (end_date >= start_date)
);
CREATE INDEX IF NOT EXISTS ix_acc_period_org_dates ON pharma.acc_period (pid_org, start_date, end_date);

/* ═══ Cost centres ════════════════════════════════════════════════════════ */

CREATE TABLE IF NOT EXISTS pharma.acc_cost_center (
    pid_cost_center BIGINT       NOT NULL,
    pid_org         BIGINT       NOT NULL,
    pid_branch      BIGINT,
    cc_code         VARCHAR(20)  NOT NULL,
    cc_name         VARCHAR(120) NOT NULL,
    description     VARCHAR(500),
    is_active       SMALLINT     DEFAULT 1,
    status          VARCHAR(1)   DEFAULT 'Y',
    inserted_by     VARCHAR(50),
    inserted_date   TIMESTAMP,
    updated_by      VARCHAR(50),
    updated_date    TIMESTAMP,
    CONSTRAINT acc_cost_center_pkey PRIMARY KEY (pid_cost_center),
    CONSTRAINT uq_acc_cost_center UNIQUE (pid_org, cc_code)
);

/* ═══ Tax configuration ═══════════════════════════════════════════════════
   Rates are data, never code. The product master keeps its own VAT % for
   pricing (V-earlier); these rows are the accounting side: which account a
   collected or paid tax lands on, from when, inclusive or exclusive. */

CREATE TABLE IF NOT EXISTS pharma.acc_tax_rate (
    pid_tax_rate    BIGINT        NOT NULL,
    pid_org         BIGINT        NOT NULL,
    tax_code        VARCHAR(20)   NOT NULL,
    tax_name        VARCHAR(120)  NOT NULL,
    tax_type        VARCHAR(20)   NOT NULL,    /* VAT SALES_TAX WITHHOLDING OTHER */
    rate_pct        NUMERIC(9,4)  NOT NULL,
    effective_from  DATE          NOT NULL,
    effective_to    DATE,
    pid_account     BIGINT,
    is_inclusive    SMALLINT      DEFAULT 0,
    is_active       SMALLINT      DEFAULT 1,
    status          VARCHAR(1)    DEFAULT 'Y',
    inserted_by     VARCHAR(50),
    inserted_date   TIMESTAMP,
    updated_by      VARCHAR(50),
    updated_date    TIMESTAMP,
    CONSTRAINT acc_tax_rate_pkey PRIMARY KEY (pid_tax_rate),
    CONSTRAINT ck_acc_tax_rate_pct CHECK (rate_pct >= 0 AND rate_pct <= 100)
);
CREATE INDEX IF NOT EXISTS ix_acc_tax_rate_org ON pharma.acc_tax_rate (pid_org, tax_code, effective_from);

/* ═══ Settings ════════════════════════════════════════════════════════════
   One row per key per organisation. A missing row means the default in
   AccSettings, so nothing has to be seeded for an organisation to work. */

CREATE TABLE IF NOT EXISTS pharma.acc_setting (
    pid_setting     BIGINT       NOT NULL,
    pid_org         BIGINT       NOT NULL,
    setting_key     VARCHAR(60)  NOT NULL,
    setting_value   VARCHAR(200),
    status          VARCHAR(1)   DEFAULT 'Y',
    inserted_by     VARCHAR(50),
    inserted_date   TIMESTAMP,
    updated_by      VARCHAR(50),
    updated_date    TIMESTAMP,
    CONSTRAINT acc_setting_pkey PRIMARY KEY (pid_setting),
    CONSTRAINT uq_acc_setting UNIQUE (pid_org, setting_key)
);

/* ═══ Payment mode → cash account role ════════════════════════════════════
   The existing modules write free-text payment types (CASH, bKash, Bank
   Transfer, CARD ...). This table says which cash account each one is, per
   organisation, so the rule engine never guesses. NULL pid_org rows are the
   defaults every organisation inherits. */

CREATE TABLE IF NOT EXISTS pharma.acc_payment_mode (
    pid_payment_mode BIGINT      NOT NULL,
    pid_org          BIGINT,
    mode_key         VARCHAR(30) NOT NULL,     /* upper-cased, spaces and underscores removed */
    mode_label       VARCHAR(60),
    account_role     VARCHAR(40),              /* NULL = not cash (CREDIT) */
    pid_bank_account BIGINT,                   /* optional: a specific bank account */
    is_active        SMALLINT    DEFAULT 1,
    status           VARCHAR(1)  DEFAULT 'Y',
    inserted_by      VARCHAR(50),
    inserted_date    TIMESTAMP,
    updated_by       VARCHAR(50),
    updated_date     TIMESTAMP,
    CONSTRAINT acc_payment_mode_pkey PRIMARY KEY (pid_payment_mode)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_acc_payment_mode ON pharma.acc_payment_mode (COALESCE(pid_org, 0), mode_key);

INSERT INTO pharma.acc_payment_mode (pid_payment_mode, pid_org, mode_key, mode_label, account_role, is_active, status, inserted_by, inserted_date)
SELECT nextval('pharma.seq_acc_payment_mode'), NULL, v.k, v.l, v.r, 1, 'Y', 'V90', now()
  FROM (VALUES
    ('CASH',         'Cash',           'CASH_REGISTER'),
    ('CARD',         'Card',           'BANK_DEFAULT'),
    ('BANK',         'Bank',           'BANK_DEFAULT'),
    ('BANKTRANSFER', 'Bank transfer',  'BANK_DEFAULT'),
    ('CHEQUE',       'Cheque',         'BANK_DEFAULT'),
    ('BKASH',        'bKash',          'MOBILE_WALLET'),
    ('NAGAD',        'Nagad',          'MOBILE_WALLET'),
    ('ROCKET',       'Rocket',         'MOBILE_WALLET'),
    ('MOBILE',       'Mobile wallet',  'MOBILE_WALLET'),
    ('CREDIT',       'Credit',         NULL),
    ('ADJUSTMENT',   'Adjustment',     'SUPPLIER_ADVANCE'),
    ('REPLACEMENT',  'Replacement',    'SUPPLIER_ADVANCE')
  ) AS v(k, l, r)
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acc_payment_mode m WHERE m.pid_org IS NULL AND m.mode_key = v.k);

/* ═══ Bank accounts ═══════════════════════════════════════════════════════
   A bank account is a ledger account under 1130 plus the details of the
   real account. Creating one creates its ledger account. */

CREATE TABLE IF NOT EXISTS pharma.acc_bank_account (
    pid_bank_account BIGINT       NOT NULL,
    pid_org          BIGINT       NOT NULL,
    pid_branch       BIGINT,
    pid_account      BIGINT       NOT NULL,
    bank_name        VARCHAR(120) NOT NULL,
    account_title    VARCHAR(150),
    account_no       VARCHAR(60),
    branch_name      VARCHAR(120),
    currency         VARCHAR(3)   DEFAULT 'BDT',
    is_default       SMALLINT     DEFAULT 0,
    is_active        SMALLINT     DEFAULT 1,
    status           VARCHAR(1)   DEFAULT 'Y',
    inserted_by      VARCHAR(50),
    inserted_date    TIMESTAMP,
    updated_by       VARCHAR(50),
    updated_date     TIMESTAMP,
    CONSTRAINT acc_bank_account_pkey PRIMARY KEY (pid_bank_account),
    CONSTRAINT fk_acc_bank_account_account FOREIGN KEY (pid_account) REFERENCES pharma.acc_account (pid_account)
);

/* ═══ Accounting events ═══════════════════════════════════════════════════
   One row per financial event of a source transaction. The unique index is
   the idempotency guarantee: the same source can never produce two journals
   for the same event key. A re-post of a changed source uses a new event key
   (a revision) and reverses the previous one. */

CREATE TABLE IF NOT EXISTS pharma.acc_event (
    pid_event        BIGINT        NOT NULL,
    pid_org          BIGINT        NOT NULL,
    pid_branch       BIGINT        NOT NULL,
    source_module    VARCHAR(30)   NOT NULL,   /* SALES PURCHASE SUPPLIER_PAYMENT COLLECTION HR SUBSCRIPTION INVESTOR CASH DAILY_CASH MANUAL BACKFILL */
    source_type      VARCHAR(40)   NOT NULL,   /* SALE SALES_RETURN PURCHASE ... */
    source_id        BIGINT        NOT NULL,
    event_key        VARCHAR(40)   NOT NULL,   /* POSTED RECEIPT COLLECTION:12 REV:2 ... */
    event_type       VARCHAR(40)   NOT NULL,   /* the rule name: SALE_POSTED, SUPPLIER_PAYMENT ... */
    event_date       DATE          NOT NULL,
    reference        VARCHAR(60),
    description      VARCHAR(300),
    amount           NUMERIC(18,2),
    payload          TEXT,                     /* JSON: the amount keys and dimensions the rule read */
    event_status     VARCHAR(12)   DEFAULT 'PENDING', /* PENDING POSTED FAILED SKIPPED REVERSED */
    pid_journal      BIGINT,
    error_message    VARCHAR(1000),
    retry_count      INT           DEFAULT 0,
    processed_at     TIMESTAMP,
    pid_backfill_batch BIGINT,
    status           VARCHAR(1)    DEFAULT 'Y',
    inserted_by      VARCHAR(50),
    inserted_date    TIMESTAMP,
    updated_by       VARCHAR(50),
    updated_date     TIMESTAMP,
    CONSTRAINT acc_event_pkey PRIMARY KEY (pid_event),
    CONSTRAINT uq_acc_event UNIQUE (pid_org, source_module, source_type, source_id, event_key),
    CONSTRAINT ck_acc_event_status CHECK (event_status IN ('PENDING','POSTED','FAILED','SKIPPED','REVERSED'))
);
CREATE INDEX IF NOT EXISTS ix_acc_event_branch_date ON pharma.acc_event (pid_branch, event_date);
CREATE INDEX IF NOT EXISTS ix_acc_event_status ON pharma.acc_event (pid_org, event_status);
CREATE INDEX IF NOT EXISTS ix_acc_event_source ON pharma.acc_event (source_module, source_type, source_id);

/* ═══ Accounting rules ════════════════════════════════════════════════════
   Which accounts an event posts to, as data. A rule line names an account by
   ROLE (resolved in the organisation's chart), or by @MODE (resolved through
   acc_payment_mode from the event's payment mode), and the amount by KEY
   (read from the event payload). A line whose amount is zero is skipped. A
   rule with pid_org set overrides the default (NULL) rule of the same type
   for that organisation. */

CREATE TABLE IF NOT EXISTS pharma.acc_rule (
    pid_rule        BIGINT       NOT NULL,
    pid_org         BIGINT,
    event_type      VARCHAR(40)  NOT NULL,
    rule_name       VARCHAR(120) NOT NULL,
    description     VARCHAR(500),
    is_active       SMALLINT     DEFAULT 1,
    status          VARCHAR(1)   DEFAULT 'Y',
    inserted_by     VARCHAR(50),
    inserted_date   TIMESTAMP,
    updated_by      VARCHAR(50),
    updated_date    TIMESTAMP,
    CONSTRAINT acc_rule_pkey PRIMARY KEY (pid_rule)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_acc_rule ON pharma.acc_rule (COALESCE(pid_org, 0), event_type);

CREATE TABLE IF NOT EXISTS pharma.acc_rule_line (
    pid_rule_line   BIGINT       NOT NULL,
    pid_rule        BIGINT       NOT NULL,
    line_no         INT          NOT NULL,
    side            VARCHAR(1)   NOT NULL,     /* D or C */
    account_role    VARCHAR(40)  NOT NULL,     /* a role key, or @MODE, or @PAYLOAD:<key> for an account chosen by the event */
    amount_key      VARCHAR(40)  NOT NULL,
    party_type      VARCHAR(20),               /* CUSTOMER SUPPLIER EMPLOYEE INVESTOR TENANT — copied to the line for sub-ledgers */
    description     VARCHAR(200),
    status          VARCHAR(1)   DEFAULT 'Y',
    CONSTRAINT acc_rule_line_pkey PRIMARY KEY (pid_rule_line),
    CONSTRAINT fk_acc_rule_line_rule FOREIGN KEY (pid_rule) REFERENCES pharma.acc_rule (pid_rule),
    CONSTRAINT ck_acc_rule_line_side CHECK (side IN ('D','C'))
);
CREATE INDEX IF NOT EXISTS ix_acc_rule_line_rule ON pharma.acc_rule_line (pid_rule, line_no);

/* The default rules. See docs/accounts/accounting-rules.md for the prose. */
INSERT INTO pharma.acc_rule (pid_rule, pid_org, event_type, rule_name, description, is_active, status, inserted_by, inserted_date)
SELECT nextval('pharma.seq_acc_rule'), NULL, v.t, v.n, v.d, 1, 'Y', 'V90', now()
  FROM (VALUES
    ('SALE_POSTED',                 'Sale invoiced',                  'Revenue, delivery income, receivable and cost of goods sold for a sales invoice'),
    ('SALE_RECEIPT',                'Sale paid at the counter',       'Cash handed over with the invoice'),
    ('SALE_COLLECTION',             'Sale collected',                 'Money received against an invoice later; via a delivery man it is cash in transit'),
    ('DELIVERY_CASH_IN',            'Delivery man cash handed in',    'Rider cash reaches the till'),
    ('SALES_RETURN_POSTED',         'Sales return',                   'Refund and stock back at cost'),
    ('PURCHASE_APPROVED',           'Purchase approved',              'Stock received at landed cost; supplier owed; advance carved out'),
    ('PURCHASE_RETURN_POSTED',      'Purchase return',                'Stock out of the batch; supplier credits us'),
    ('SUPPLIER_PAYMENT',            'Supplier paid',                  'Settles the bill; the advance part is recoverable'),
    ('SUPPLIER_ADVANCE_RECOVERY',   'Supplier advance recovered',     'Money back, or credit against a bill'),
    ('PAYROLL_POSTED',              'Payroll posted',                 'Salary cost, net payable, deductions'),
    ('SALARY_PAID',                 'Salary paid',                    ''),
    ('EXPENSE_CLAIM_APPROVED',      'Expense claim approved',         'Expense accrued; employee owed'),
    ('EXPENSE_CLAIM_PAID',          'Expense claim paid',             ''),
    ('ADVANCE_PAID',                'Employee advance paid',          ''),
    ('LOAN_DISBURSED',              'Employee loan disbursed',        ''),
    ('GRATUITY_PAID',               'Gratuity paid',                  ''),
    ('SUBSCRIPTION_INVOICED',       'Subscription invoiced',          'Platform books'),
    ('SUBSCRIPTION_PAID',           'Subscription paid',              'Platform books'),
    ('INVESTOR_INVESTMENT',         'Investor capital in',            ''),
    ('INVESTOR_WITHDRAWAL',         'Investor capital out',           ''),
    ('INVESTOR_ADJUSTMENT_UP',      'Investor capital adjusted up',   'Approved adjustment against opening balance equity'),
    ('INVESTOR_ADJUSTMENT_DOWN',    'Investor capital adjusted down', ''),
    ('PROFIT_ALLOCATED',            'Profit allocated to investors',  ''),
    ('PROFIT_PAID',                 'Investor profit paid',           ''),
    ('CASH_RECEIPT',                'Other cash receipt',             'Cash voucher: money in, counter account chosen on the voucher'),
    ('CASH_PAYMENT',                'Other cash payment',             'Cash voucher: money out, counter account chosen on the voucher'),
    ('CASH_TRANSFER',               'Cash / bank / petty transfer',   'Cash voucher: between two cash accounts'),
    ('CASH_OPENING',                'Opening cash balance',           'Controlled first-day balance against opening balance equity'),
    ('CASH_ADJUSTMENT_IN',          'Approved cash adjustment in',    ''),
    ('CASH_ADJUSTMENT_OUT',         'Approved cash adjustment out',   ''),
    ('CASH_VARIANCE_SHORT',         'Cash short at closing',          'Posted only when policy allows'),
    ('CASH_VARIANCE_OVER',          'Cash over at closing',           'Posted only when policy allows')
  ) AS v(t, n, d)
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acc_rule r WHERE r.pid_org IS NULL AND r.event_type = v.t);

INSERT INTO pharma.acc_rule_line (pid_rule_line, pid_rule, line_no, side, account_role, amount_key, party_type, description, status)
SELECT nextval('pharma.seq_acc_rule_line'), r.pid_rule, v.n, v.s, v.a, v.k, v.p, v.d, 'Y'
  FROM (VALUES
    /* SALE_POSTED: receivable = payable; revenue = payable - delivery; delivery income; COGS at movement cost */
    ('SALE_POSTED', 1,'D','AR',              'RECEIVABLE',      'CUSTOMER','Customer owes the invoice'),
    ('SALE_POSTED', 2,'C','SALES_REVENUE',   'REVENUE',         NULL,      'Net of invoice discount'),
    ('SALE_POSTED', 3,'C','DELIVERY_INCOME', 'DELIVERY_CHARGE', NULL,      NULL),
    ('SALE_POSTED', 4,'D','COGS',            'COGS',            NULL,      'Batch cost at the moment of sale'),
    ('SALE_POSTED', 5,'C','INVENTORY',       'COGS',            NULL,      NULL),
    ('SALE_RECEIPT',1,'D','@MODE',           'AMOUNT',          NULL,      'Till, bank or wallet by payment mode'),
    ('SALE_RECEIPT',2,'C','AR',              'AMOUNT',          'CUSTOMER',NULL),
    ('SALE_COLLECTION',1,'D','@PAYLOAD:RECEIPT_ACCOUNT','AMOUNT',NULL,     'Cash in transit when a delivery man carries it, else the account of the payment mode'),
    ('SALE_COLLECTION',2,'C','AR',           'AMOUNT',          'CUSTOMER',NULL),
    ('DELIVERY_CASH_IN',1,'D','@MODE',       'AMOUNT',          NULL,      NULL),
    ('DELIVERY_CASH_IN',2,'C','CASH_IN_TRANSIT','AMOUNT',       'EMPLOYEE',NULL),
    ('SALES_RETURN_POSTED',1,'D','SALES_RETURNS','REFUND',      NULL,      'Contra revenue'),
    ('SALES_RETURN_POSTED',2,'C','@PAYLOAD:REFUND_ACCOUNT','REFUND','CUSTOMER','Refund by cash / bank, or (CREDIT) the customer account'),
    ('SALES_RETURN_POSTED',3,'D','INVENTORY','COGS',            NULL,      'Back at the cost it left at'),
    ('SALES_RETURN_POSTED',4,'C','COGS',     'COGS',            NULL,      NULL),
    ('PURCHASE_APPROVED',1,'D','INVENTORY',  'LANDED_COST',     NULL,      'Amount to be paid, as the batches were costed'),
    ('PURCHASE_APPROVED',2,'C','AP',         'PAYABLE',         'SUPPLIER','What the supplier is owed; any cash handed over is booked by the mirrored supplier payment'),
    ('PURCHASE_RETURN_POSTED',1,'D','@PAYLOAD:CREDIT_ACCOUNT','CREDIT','SUPPLIER','ADJUSTMENT reduces the payable; CASH/BANK is money back; REPLACEMENT is an advance'),
    ('PURCHASE_RETURN_POSTED',2,'C','INVENTORY','CREDIT',       NULL,      NULL),
    ('SUPPLIER_PAYMENT',1,'D','AP',          'SETTLING',        'SUPPLIER','Amount less advance'),
    ('SUPPLIER_PAYMENT',2,'D','SUPPLIER_ADVANCE','ADVANCE',     'SUPPLIER','Recoverable from the supplier'),
    ('SUPPLIER_PAYMENT',3,'C','@MODE',       'AMOUNT',          NULL,      'ADJUSTMENT mode consumes a credit instead of cash'),
    ('SUPPLIER_ADVANCE_RECOVERY',1,'D','@PAYLOAD:RECOVERY_ACCOUNT','AMOUNT','SUPPLIER','Cash back by mode, or the payable when credited against a bill'),
    ('SUPPLIER_ADVANCE_RECOVERY',2,'C','SUPPLIER_ADVANCE','AMOUNT','SUPPLIER',NULL),
    ('PAYROLL_POSTED',1,'D','SALARY_EXPENSE','SALARY_COST',     NULL,      'Gross less absence deduction'),
    ('PAYROLL_POSTED',2,'D','PF_EXPENSE',    'PF_EMPLOYER',     NULL,      NULL),
    ('PAYROLL_POSTED',3,'C','SALARY_PAYABLE','NET',             'EMPLOYEE',NULL),
    ('PAYROLL_POSTED',4,'C','EMPLOYEE_ADVANCE','ADVANCE_RECOVERY','EMPLOYEE',NULL),
    ('PAYROLL_POSTED',5,'C','EMPLOYEE_LOAN', 'LOAN_RECOVERY',   'EMPLOYEE',NULL),
    ('PAYROLL_POSTED',6,'C','TAX_PAYABLE',   'TAX',             NULL,      NULL),
    ('PAYROLL_POSTED',7,'C','PF_PAYABLE',    'PF_TOTAL',        NULL,      'Employee plus employer contribution'),
    ('PAYROLL_POSTED',8,'C','PAYROLL_OTHER_DED','OTHER_DEDUCTION',NULL,    'Residual, so the run always balances and the residual is visible'),
    ('SALARY_PAID',1,'D','SALARY_PAYABLE',   'AMOUNT',          'EMPLOYEE',NULL),
    ('SALARY_PAID',2,'C','@MODE',            'AMOUNT',          NULL,      NULL),
    ('EXPENSE_CLAIM_APPROVED',1,'D','@PAYLOAD:EXPENSE_ACCOUNT','AMOUNT',NULL,'Account from the expense type, else Staff Expenses'),
    ('EXPENSE_CLAIM_APPROVED',2,'C','EMPLOYEE_REIMB_PAYABLE','AMOUNT','EMPLOYEE',NULL),
    ('EXPENSE_CLAIM_PAID',1,'D','EMPLOYEE_REIMB_PAYABLE','AMOUNT','EMPLOYEE',NULL),
    ('EXPENSE_CLAIM_PAID',2,'C','@MODE',     'AMOUNT',          NULL,      NULL),
    ('ADVANCE_PAID',1,'D','EMPLOYEE_ADVANCE','AMOUNT',          'EMPLOYEE',NULL),
    ('ADVANCE_PAID',2,'C','@MODE',           'AMOUNT',          NULL,      NULL),
    ('LOAN_DISBURSED',1,'D','EMPLOYEE_LOAN', 'AMOUNT',          'EMPLOYEE',NULL),
    ('LOAN_DISBURSED',2,'C','@MODE',         'AMOUNT',          NULL,      NULL),
    ('GRATUITY_PAID',1,'D','GRATUITY_EXPENSE','AMOUNT',         'EMPLOYEE',NULL),
    ('GRATUITY_PAID',2,'C','@MODE',          'AMOUNT',          NULL,      NULL),
    ('SUBSCRIPTION_INVOICED',1,'D','AR',     'AMOUNT',          'TENANT',  NULL),
    ('SUBSCRIPTION_INVOICED',2,'C','SUBSCRIPTION_REVENUE','AMOUNT',NULL,   NULL),
    ('SUBSCRIPTION_PAID',1,'D','@MODE',      'AMOUNT',          NULL,      NULL),
    ('SUBSCRIPTION_PAID',2,'C','AR',         'AMOUNT',          'TENANT',  NULL),
    ('INVESTOR_INVESTMENT',1,'D','@MODE',    'AMOUNT',          NULL,      NULL),
    ('INVESTOR_INVESTMENT',2,'C','INVESTOR_CAPITAL','AMOUNT',   'INVESTOR',NULL),
    ('INVESTOR_WITHDRAWAL',1,'D','INVESTOR_CAPITAL','AMOUNT',   'INVESTOR',NULL),
    ('INVESTOR_WITHDRAWAL',2,'C','@MODE',    'AMOUNT',          NULL,      NULL),
    ('INVESTOR_ADJUSTMENT_UP',1,'D','OPENING_BALANCE_EQUITY','AMOUNT',NULL,NULL),
    ('INVESTOR_ADJUSTMENT_UP',2,'C','INVESTOR_CAPITAL','AMOUNT','INVESTOR',NULL),
    ('INVESTOR_ADJUSTMENT_DOWN',1,'D','INVESTOR_CAPITAL','AMOUNT','INVESTOR',NULL),
    ('INVESTOR_ADJUSTMENT_DOWN',2,'C','OPENING_BALANCE_EQUITY','AMOUNT',NULL,NULL),
    ('PROFIT_ALLOCATED',1,'D','PROFIT_DISTRIBUTION','AMOUNT',   NULL,      'Equity set aside for investors'),
    ('PROFIT_ALLOCATED',2,'C','INVESTOR_PROFIT_PAYABLE','AMOUNT','INVESTOR',NULL),
    ('PROFIT_PAID',1,'D','INVESTOR_PROFIT_PAYABLE','AMOUNT',    'INVESTOR',NULL),
    ('PROFIT_PAID',2,'C','@MODE',            'AMOUNT',          NULL,      NULL),
    ('CASH_RECEIPT',1,'D','@PAYLOAD:CASH_ACCOUNT','AMOUNT',     NULL,      'The cash / bank / petty account on the voucher'),
    ('CASH_RECEIPT',2,'C','@PAYLOAD:COUNTER_ACCOUNT','AMOUNT',  NULL,      'Income or other account on the voucher'),
    ('CASH_PAYMENT',1,'D','@PAYLOAD:COUNTER_ACCOUNT','AMOUNT',  NULL,      'Expense or other account on the voucher'),
    ('CASH_PAYMENT',2,'C','@PAYLOAD:CASH_ACCOUNT','AMOUNT',     NULL,      NULL),
    ('CASH_TRANSFER',1,'D','@PAYLOAD:TO_ACCOUNT','AMOUNT',      NULL,      NULL),
    ('CASH_TRANSFER',2,'C','@PAYLOAD:FROM_ACCOUNT','AMOUNT',    NULL,      NULL),
    ('CASH_OPENING',1,'D','@PAYLOAD:CASH_ACCOUNT','AMOUNT',     NULL,      NULL),
    ('CASH_OPENING',2,'C','OPENING_BALANCE_EQUITY','AMOUNT',    NULL,      NULL),
    ('CASH_ADJUSTMENT_IN',1,'D','@PAYLOAD:CASH_ACCOUNT','AMOUNT',NULL,     NULL),
    ('CASH_ADJUSTMENT_IN',2,'C','CASH_SHORT_OVER','AMOUNT',     NULL,      NULL),
    ('CASH_ADJUSTMENT_OUT',1,'D','CASH_SHORT_OVER','AMOUNT',    NULL,      NULL),
    ('CASH_ADJUSTMENT_OUT',2,'C','@PAYLOAD:CASH_ACCOUNT','AMOUNT',NULL,    NULL),
    ('CASH_VARIANCE_SHORT',1,'D','CASH_SHORT_OVER','AMOUNT',    NULL,      NULL),
    ('CASH_VARIANCE_SHORT',2,'C','CASH_REGISTER','AMOUNT',      NULL,      NULL),
    ('CASH_VARIANCE_OVER',1,'D','CASH_REGISTER','AMOUNT',       NULL,      NULL),
    ('CASH_VARIANCE_OVER',2,'C','CASH_SHORT_OVER','AMOUNT',     NULL,      NULL)
  ) AS v(t, n, s, a, k, p, d)
  JOIN pharma.acc_rule r ON r.pid_org IS NULL AND r.event_type = v.t
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acc_rule_line x WHERE x.pid_rule = r.pid_rule AND x.line_no = v.n);

/* ═══ Journals ════════════════════════════════════════════════════════════ */

CREATE TABLE IF NOT EXISTS pharma.acc_journal (
    pid_journal      BIGINT        NOT NULL,
    pid_org          BIGINT        NOT NULL,
    pid_branch       BIGINT        NOT NULL,
    journal_no       VARCHAR(30)   NOT NULL,
    journal_date     DATE          NOT NULL,
    pid_period       BIGINT        NOT NULL,
    journal_type     VARCHAR(12)   NOT NULL,   /* AUTO MANUAL REVERSAL ADJUSTMENT OPENING CLOSING */
    source_module    VARCHAR(30),
    source_type      VARCHAR(40),
    source_id        BIGINT,
    pid_event        BIGINT,
    reference        VARCHAR(60),
    description      VARCHAR(300),
    pid_cost_center  BIGINT,
    currency         VARCHAR(3)    DEFAULT 'BDT',
    total_debit      NUMERIC(18,2) NOT NULL DEFAULT 0,
    total_credit     NUMERIC(18,2) NOT NULL DEFAULT 0,
    journal_status   VARCHAR(10)   NOT NULL DEFAULT 'DRAFT', /* DRAFT POSTED REVERSED */
    reversal_of      BIGINT,
    reversed_by      BIGINT,
    reversal_reason  VARCHAR(500),
    approved_by      VARCHAR(50),
    approved_at      TIMESTAMP,
    posted_by        VARCHAR(50),
    posted_at        TIMESTAMP,
    reversed_by_user VARCHAR(50),
    reversed_at      TIMESTAMP,
    status           VARCHAR(1)    DEFAULT 'Y',
    inserted_by      VARCHAR(50),
    inserted_date    TIMESTAMP,
    updated_by       VARCHAR(50),
    updated_date     TIMESTAMP,
    CONSTRAINT acc_journal_pkey PRIMARY KEY (pid_journal),
    CONSTRAINT uq_acc_journal_no UNIQUE (pid_org, journal_no),
    CONSTRAINT fk_acc_journal_period FOREIGN KEY (pid_period) REFERENCES pharma.acc_period (pid_period),
    CONSTRAINT ck_acc_journal_type CHECK (journal_type IN ('AUTO','MANUAL','REVERSAL','ADJUSTMENT','OPENING','CLOSING')),
    CONSTRAINT ck_acc_journal_status CHECK (journal_status IN ('DRAFT','POSTED','REVERSED')),
    /* The equation, enforced by the database as well as the service. */
    CONSTRAINT ck_acc_journal_balanced CHECK (journal_status = 'DRAFT' OR total_debit = total_credit)
);
CREATE INDEX IF NOT EXISTS ix_acc_journal_branch_date ON pharma.acc_journal (pid_branch, journal_date);
CREATE INDEX IF NOT EXISTS ix_acc_journal_org_date ON pharma.acc_journal (pid_org, journal_date);
CREATE INDEX IF NOT EXISTS ix_acc_journal_source ON pharma.acc_journal (source_module, source_type, source_id);
CREATE INDEX IF NOT EXISTS ix_acc_journal_period ON pharma.acc_journal (pid_period, journal_status);

CREATE TABLE IF NOT EXISTS pharma.acc_journal_line (
    pid_journal_line BIGINT        NOT NULL,
    pid_journal      BIGINT        NOT NULL,
    line_no          INT           NOT NULL,
    pid_account      BIGINT        NOT NULL,
    debit            NUMERIC(18,2) NOT NULL DEFAULT 0,
    credit           NUMERIC(18,2) NOT NULL DEFAULT 0,
    description      VARCHAR(300),
    pid_branch       BIGINT        NOT NULL,
    pid_cost_center  BIGINT,
    party_type       VARCHAR(20),
    party_id         BIGINT,
    party_name       VARCHAR(150),
    /* Denormalised for the ledger views: never edited after posting. */
    journal_date     DATE          NOT NULL,
    pid_org          BIGINT        NOT NULL,
    status           VARCHAR(1)    DEFAULT 'Y',
    CONSTRAINT acc_journal_line_pkey PRIMARY KEY (pid_journal_line),
    CONSTRAINT fk_acc_journal_line_journal FOREIGN KEY (pid_journal) REFERENCES pharma.acc_journal (pid_journal),
    CONSTRAINT fk_acc_journal_line_account FOREIGN KEY (pid_account) REFERENCES pharma.acc_account (pid_account),
    CONSTRAINT ck_acc_journal_line_amounts CHECK (debit >= 0 AND credit >= 0 AND NOT (debit > 0 AND credit > 0))
);
CREATE INDEX IF NOT EXISTS ix_acc_jl_account_date ON pharma.acc_journal_line (pid_account, journal_date);
CREATE INDEX IF NOT EXISTS ix_acc_jl_branch_date ON pharma.acc_journal_line (pid_branch, journal_date);
CREATE INDEX IF NOT EXISTS ix_acc_jl_party ON pharma.acc_journal_line (party_type, party_id, journal_date);
CREATE INDEX IF NOT EXISTS ix_acc_jl_journal ON pharma.acc_journal_line (pid_journal, line_no);

/* Posted journals are immutable. The service refuses first; the database
   refuses second, so no code path — including a future one — can edit or
   delete a posted journal or its lines. Reversal writes a new journal. */
CREATE OR REPLACE FUNCTION pharma.acc_guard_posted_journal() RETURNS trigger AS $body$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.journal_status IN ('POSTED','REVERSED') THEN
            RAISE EXCEPTION 'Posted journal % cannot be deleted; reverse it instead', OLD.journal_no;
        END IF;
        RETURN OLD;
    END IF;
    IF OLD.journal_status IN ('POSTED','REVERSED') THEN
        /* The only permitted changes after posting: being marked reversed, and audit stamps. */
        IF NEW.pid_org <> OLD.pid_org OR NEW.pid_branch <> OLD.pid_branch OR NEW.journal_no <> OLD.journal_no
           OR NEW.journal_date <> OLD.journal_date OR NEW.pid_period <> OLD.pid_period
           OR NEW.total_debit <> OLD.total_debit OR NEW.total_credit <> OLD.total_credit
           OR NEW.journal_type <> OLD.journal_type
           OR COALESCE(NEW.source_id, -1) <> COALESCE(OLD.source_id, -1)
           OR (OLD.journal_status = 'REVERSED' AND NEW.journal_status <> 'REVERSED')
           OR (OLD.journal_status = 'POSTED' AND NEW.journal_status = 'DRAFT') THEN
            RAISE EXCEPTION 'Posted journal % is immutable', OLD.journal_no;
        END IF;
    END IF;
    RETURN NEW;
END;
$body$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_acc_guard_posted_journal ON pharma.acc_journal;
CREATE TRIGGER trg_acc_guard_posted_journal
    BEFORE UPDATE OR DELETE ON pharma.acc_journal
    FOR EACH ROW EXECUTE FUNCTION pharma.acc_guard_posted_journal();

CREATE OR REPLACE FUNCTION pharma.acc_guard_posted_line() RETURNS trigger AS $body$
DECLARE v_status VARCHAR(10);
BEGIN
    SELECT journal_status INTO v_status FROM pharma.acc_journal
     WHERE pid_journal = COALESCE(OLD.pid_journal, NEW.pid_journal);
    IF v_status IN ('POSTED','REVERSED') THEN
        RAISE EXCEPTION 'Lines of a posted journal are immutable';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$body$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_acc_guard_posted_line ON pharma.acc_journal_line;
CREATE TRIGGER trg_acc_guard_posted_line
    BEFORE UPDATE OR DELETE ON pharma.acc_journal_line
    FOR EACH ROW EXECUTE FUNCTION pharma.acc_guard_posted_line();

/* ═══ Cash vouchers ═══════════════════════════════════════════════════════
   Cash, bank and petty cash movements that no other module records: other
   receipts and payments, transfers between cash accounts, petty cash float
   and spend, the controlled first-day opening balance, approved adjustments.
   A voucher is the business document; posting it raises a CASH_* event. */

CREATE TABLE IF NOT EXISTS pharma.acc_cash_voucher (
    pid_cash_voucher BIGINT        NOT NULL,
    pid_org          BIGINT        NOT NULL,
    pid_branch       BIGINT        NOT NULL,
    voucher_no       VARCHAR(30)   NOT NULL,
    voucher_date     DATE          NOT NULL,
    voucher_kind     VARCHAR(20)   NOT NULL,   /* RECEIPT PAYMENT TRANSFER OPENING ADJUSTMENT_IN ADJUSTMENT_OUT */
    pid_cash_account BIGINT        NOT NULL,   /* the cash / bank / petty account moved */
    pid_counter_account BIGINT,                /* RECEIPT: income/other; PAYMENT: expense/other; TRANSFER: the other cash account */
    amount           NUMERIC(18,2) NOT NULL,
    party_type       VARCHAR(20),
    party_id         BIGINT,
    party_name       VARCHAR(150),
    reference        VARCHAR(60),
    description      VARCHAR(300),
    reason           VARCHAR(500),             /* required for OPENING and ADJUSTMENT_* */
    pid_cost_center  BIGINT,
    voucher_status   VARCHAR(10)   DEFAULT 'DRAFT', /* DRAFT APPROVED POSTED CANCELLED */
    approved_by      VARCHAR(50),
    approved_at      TIMESTAMP,
    posted_by        VARCHAR(50),
    posted_at        TIMESTAMP,
    cancelled_by     VARCHAR(50),
    cancelled_at     TIMESTAMP,
    cancel_reason    VARCHAR(500),
    pid_event        BIGINT,
    pid_journal      BIGINT,
    status           VARCHAR(1)    DEFAULT 'Y',
    inserted_by      VARCHAR(50),
    inserted_date    TIMESTAMP,
    updated_by       VARCHAR(50),
    updated_date     TIMESTAMP,
    CONSTRAINT acc_cash_voucher_pkey PRIMARY KEY (pid_cash_voucher),
    CONSTRAINT uq_acc_cash_voucher_no UNIQUE (pid_org, voucher_no),
    CONSTRAINT ck_acc_cash_voucher_kind CHECK (voucher_kind IN ('RECEIPT','PAYMENT','TRANSFER','OPENING','ADJUSTMENT_IN','ADJUSTMENT_OUT')),
    CONSTRAINT ck_acc_cash_voucher_status CHECK (voucher_status IN ('DRAFT','APPROVED','POSTED','CANCELLED')),
    CONSTRAINT ck_acc_cash_voucher_amount CHECK (amount > 0)
);
CREATE INDEX IF NOT EXISTS ix_acc_cash_voucher_branch_date ON pharma.acc_cash_voucher (pid_branch, voucher_date);

/* ═══ Backfill batches ════════════════════════════════════════════════════
   Historical transactions are brought into the ledger by a batched,
   reproducible job (never by a migration). Every batch is recorded with its
   scope and its reconciliation figures. */

CREATE TABLE IF NOT EXISTS pharma.acc_backfill_batch (
    pid_backfill_batch BIGINT      NOT NULL,
    pid_org            BIGINT      NOT NULL,
    pid_branch         BIGINT,
    scope_module       VARCHAR(30),           /* NULL = everything */
    date_from          DATE,
    date_to            DATE,
    dry_run            SMALLINT    DEFAULT 0,
    batch_status       VARCHAR(12) DEFAULT 'RUNNING', /* RUNNING DONE FAILED */
    scanned_count      INT         DEFAULT 0,
    posted_count       INT         DEFAULT 0,
    skipped_count      INT         DEFAULT 0,
    failed_count       INT         DEFAULT 0,
    summary            TEXT,
    started_by         VARCHAR(50),
    started_at         TIMESTAMP,
    finished_at        TIMESTAMP,
    status             VARCHAR(1)  DEFAULT 'Y',
    inserted_by        VARCHAR(50),
    inserted_date      TIMESTAMP,
    updated_by         VARCHAR(50),
    updated_date       TIMESTAMP,
    CONSTRAINT acc_backfill_batch_pkey PRIMARY KEY (pid_backfill_batch)
);

/* ═══ Existing tables — the one extension ═════════════════════════════════
   An expense type may name the account its claims are charged to. Nullable:
   an unset type falls back to Staff Expenses. */
ALTER TABLE pharma.hr_expense_type ADD COLUMN IF NOT EXISTS pid_account BIGINT;

/* ═══ The ledger ══════════════════════════════════════════════════════════
   Posted lines with their account, in one view every report reads. Reversed
   journals stay in the ledger (their reversal cancels them) so the trail is
   whole. */

CREATE OR REPLACE VIEW pharma.acc_v_ledger AS
SELECT l.pid_journal_line, l.pid_journal, j.journal_no, j.journal_date, j.journal_type, j.journal_status,
       j.source_module, j.source_type, j.source_id, j.reference, j.description AS journal_description,
       l.line_no, l.pid_account, a.account_code, a.account_name, a.account_type, a.normal_side, a.role_key,
       l.debit, l.credit, l.description, l.pid_branch, l.pid_cost_center,
       l.party_type, l.party_id, l.party_name, l.pid_org, j.pid_period, j.posted_at, j.posted_by
  FROM pharma.acc_journal_line l
  JOIN pharma.acc_journal j ON j.pid_journal = l.pid_journal
  JOIN pharma.acc_account a ON a.pid_account = l.pid_account
 WHERE j.journal_status IN ('POSTED','REVERSED');

/* Balance per account per branch, signed by the account's normal side. */
CREATE OR REPLACE VIEW pharma.acc_v_account_balance AS
SELECT l.pid_org, l.pid_branch, l.pid_account, a.account_code, a.account_name, a.account_type, a.normal_side, a.role_key,
       SUM(l.debit) AS total_debit, SUM(l.credit) AS total_credit,
       CASE WHEN a.normal_side = 'D' THEN SUM(l.debit) - SUM(l.credit) ELSE SUM(l.credit) - SUM(l.debit) END AS balance
  FROM pharma.acc_journal_line l
  JOIN pharma.acc_journal j ON j.pid_journal = l.pid_journal AND j.journal_status IN ('POSTED','REVERSED')
  JOIN pharma.acc_account a ON a.pid_account = l.pid_account
 GROUP BY l.pid_org, l.pid_branch, l.pid_account, a.account_code, a.account_name, a.account_type, a.normal_side, a.role_key;

/* ═══ Verification ════════════════════════════════════════════════════════ */
SELECT (SELECT count(*) FROM pharma.acc_account_template)                      AS template_accounts,
       (SELECT count(DISTINCT pid_org) FROM pharma.acc_account)                AS organisations_provisioned,
       (SELECT count(*) FROM pharma.acc_account WHERE pid_parent IS NULL AND account_code NOT IN ('1000','2000','3000','4000','5000','6000')) AS orphan_accounts,
       (SELECT count(*) FROM pharma.acc_rule WHERE pid_org IS NULL)            AS default_rules,
       (SELECT count(*) FROM pharma.acc_rule_line)                             AS rule_lines,
       (SELECT count(*) FROM pharma.acc_payment_mode WHERE pid_org IS NULL)    AS payment_modes;
