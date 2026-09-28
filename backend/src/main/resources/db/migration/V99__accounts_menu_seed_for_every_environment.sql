/* ─────────────────────────────────────────────────────────────────────────────
   Accounts — the menu group, its twelve modules, the role grants, the plan
   templates and the plan ceilings, seeded wherever they are missing.

   The pair of V88. V93 registered Accounts the way V77 registered HR, and it
   runs on every environment (Flyway is baselined at 63; V93 is above it). This
   file makes the outcome independent of what V93 found when it ran: a role
   that was not there yet, a template that had no rows, a branch narrowed after
   the fact, a module row somebody deactivated by hand and re-enabled. It is
   a no-op where every row exists and a complete seed where none does, and it
   is what to read to know what "Accounts, installed" means.

     GRP_ACCOUNTS  Accounts                                   sort 10
       MOD41  Accounts Dashboard      /accounts
       MOD42  Journals                /accjournals
       MOD43  General Ledger          /accledger
       MOD44  Chart of Accounts       /acccoa
       MOD45  Receivables & Payables  /accparties
       MOD46  Cash & Bank             /acccash
       MOD47  Daily Cash              /accdailycash
       MOD48  Investors               /accinvestors
       MOD49  Profit Distribution     /accprofit
       MOD50  Periods & Settings      /accsettings
       MOD51  Accounts Reports        /accreports
       MOD52  Reconciliation          /accreconcile

   Rights, exactly as the development database holds them on 2026-09-19:
     ROLE01 Super Admin, ROLE02 Administrator (Branch)   everything
     ROLE04 Accounts                                      everything but delete
     every other role                                     nothing — the pharmacy decides
   Plan templates BASIC, STANDARD and ENTERPRISE all carry the twelve modules
   with every right (this is what V93 wrote and development still holds).
   BUSINESS POLICY CONFIRMATION REQUIRED: the ENTERPRISE template describes
   itself as "everything, including HR, accounts and the AI copilot", which
   reads as if Accounts were an Enterprise feature. If a plan is not meant to
   carry Accounts, delete its twelve lines from section 4 below BEFORE this
   file runs on production; afterwards the change is made on the Pharmacies
   screen, never by editing this file.

   Every id comes from nextval(). Nothing is deleted, renamed or overwritten:
   a permission changed on the RBAC screen, a ceiling set from the Pharmacies
   screen, a module renamed — all left exactly as found.
   ───────────────────────────────────────────────────────────────────────────── */

/* ═══ 0 · Sequences: present and ahead of every existing id ════════════════ */

CREATE SEQUENCE IF NOT EXISTS pharma.sq_acl_module;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_acl_group;
CREATE SEQUENCE IF NOT EXISTS pharma.sq_pos_perm;
CREATE SEQUENCE IF NOT EXISTS pharma.sq_tenant_module;
CREATE SEQUENCE IF NOT EXISTS pharma.sq_perm_template;
CREATE SEQUENCE IF NOT EXISTS pharma.sq_template_line;

SELECT setval('pharma.sq_acl_module',
    GREATEST((SELECT COALESCE(MAX(pid_module), 0) FROM pharma.acl_module_info),
             (SELECT last_value FROM pharma.sq_acl_module)), true);
SELECT setval('pharma.seq_acl_group',
    GREATEST((SELECT COALESCE(MAX(pid_group), 0) FROM pharma.acl_group),
             (SELECT last_value FROM pharma.seq_acl_group)), true);
SELECT setval('pharma.sq_pos_perm',
    GREATEST((SELECT COALESCE(MAX(pid_permission), 0) FROM pharma.pos_permissions),
             (SELECT last_value FROM pharma.sq_pos_perm)), true);
SELECT setval('pharma.sq_tenant_module',
    GREATEST((SELECT COALESCE(MAX(pid_tenant_module), 0) FROM pharma.acl_tenant_module),
             (SELECT last_value FROM pharma.sq_tenant_module)), true);
SELECT setval('pharma.sq_perm_template',
    GREATEST((SELECT COALESCE(MAX(pid_template), 0) FROM pharma.acl_perm_template),
             (SELECT last_value FROM pharma.sq_perm_template)), true);
SELECT setval('pharma.sq_template_line',
    GREATEST((SELECT COALESCE(MAX(pid_template_line), 0) FROM pharma.acl_perm_template_line),
             (SELECT last_value FROM pharma.sq_template_line)), true);

/* ═══ 1 · The menu ══════════════════════════════════════════════════════════
   One temporary table holds the seed for the rest of the file. TEMP, so it
   vanishes with the session and leaves nothing behind. */

CREATE TEMP TABLE acc_seed_module (
    module_code varchar(20), module_name varchar(100), module_desc text,
    route_path varchar(100), icon_key varchar(50), parent_code varchar(20),
    sort_order integer
) ON COMMIT DROP;

INSERT INTO acc_seed_module VALUES
    ('GRP_ACCOUNTS', 'Accounts',               'Double-entry books: journals, ledger, cash, investors, profit share and financial reports', '',              'accounts',     '',             10),
    ('MOD41',        'Accounts Dashboard',     'KPIs, cash position, receivables and payables, investor summary, pending closings',        '/accounts',     'accdashboard', 'GRP_ACCOUNTS',  1),
    ('MOD42',        'Journals',               'Journal register, manual journals, reversals',                                              '/accjournals',  'journal',      'GRP_ACCOUNTS',  2),
    ('MOD43',        'General Ledger',         'Account-wise ledger and trial balance',                                                     '/accledger',    'ledger',       'GRP_ACCOUNTS',  3),
    ('MOD44',        'Chart of Accounts',      'The account hierarchy and posting roles',                                                   '/acccoa',       'coa',          'GRP_ACCOUNTS',  4),
    ('MOD45',        'Receivables & Payables', 'Customer and supplier balances, ageing and statements, from the ledger',                    '/accparties',   'parties',      'GRP_ACCOUNTS',  5),
    ('MOD46',        'Cash & Bank',            'Cash vouchers, bank accounts, petty cash, transfers',                                       '/acccash',      'cashbank',     'GRP_ACCOUNTS',  6),
    ('MOD47',        'Daily Cash',             'Branch business day: opening, cash count, closing, variance',                               '/accdailycash', 'dailycash',    'GRP_ACCOUNTS',  7),
    ('MOD48',        'Investors',              'Investor master, capital transactions and ledger',                                          '/accinvestors', 'investors',    'GRP_ACCOUNTS',  8),
    ('MOD49',        'Profit Distribution',    'Period profit, distributable profit, investor allocation, posting and payment',             '/accprofit',    'profit',       'GRP_ACCOUNTS',  9),
    ('MOD50',        'Periods & Settings',     'Fiscal years, periods, tax rates, cost centres, accounting rules and policies',             '/accsettings',  'accsettings',  'GRP_ACCOUNTS', 10),
    ('MOD51',        'Accounts Reports',       'Trial balance, profit and loss, balance sheet, cash books, ageing, investor and payroll reports', '/accreports', 'accreports', 'GRP_ACCOUNTS', 11),
    ('MOD52',        'Reconciliation',         'Accounting event monitor, business-vs-ledger reconciliation and historical backfill',       '/accreconcile', 'reconcile',    'GRP_ACCOUNTS', 12);

/* The group first: a child looks its parent up by code. */
INSERT INTO pharma.acl_module_info
       (pid_module, module_code, module_name, module_desc, route_path, icon_key,
        pid_parent_module, sort_order, is_active, status, inserted_by)
SELECT nextval('pharma.sq_acl_module'), s.module_code, s.module_name, NULLIF(s.module_desc, ''),
       NULLIF(s.route_path, ''), NULLIF(s.icon_key, ''), NULL, s.sort_order, 1, 'Y', 'V99'
  FROM acc_seed_module s
 WHERE s.parent_code = ''
   AND NOT EXISTS (SELECT 1 FROM pharma.acl_module_info x WHERE x.module_code = s.module_code);

INSERT INTO pharma.acl_module_info
       (pid_module, module_code, module_name, module_desc, route_path, icon_key,
        pid_parent_module, sort_order, is_active, status, inserted_by)
SELECT nextval('pharma.sq_acl_module'), s.module_code, s.module_name, NULLIF(s.module_desc, ''),
       NULLIF(s.route_path, ''), NULLIF(s.icon_key, ''),
       (SELECT p.pid_module FROM pharma.acl_module_info p WHERE p.module_code = s.parent_code),
       s.sort_order, 1, 'Y', 'V99'
  FROM acc_seed_module s
 WHERE s.parent_code <> ''
   AND NOT EXISTS (SELECT 1 FROM pharma.acl_module_info x WHERE x.module_code = s.module_code);

/* ═══ 2 · Repair, never replace ══════════════════════════════════════════════
   A module that exists but hangs from nothing is given its parent; a blank
   route or icon is filled. A value already there is left, whatever it says. */

UPDATE pharma.acl_module_info m
   SET pid_parent_module = (SELECT p.pid_module FROM pharma.acl_module_info p WHERE p.module_code = s.parent_code),
       updated_by = 'V99', updated_date = CURRENT_TIMESTAMP
  FROM acc_seed_module s
 WHERE s.module_code = m.module_code
   AND s.parent_code <> ''
   AND m.pid_parent_module IS NULL;

UPDATE pharma.acl_module_info m
   SET route_path = COALESCE(m.route_path, NULLIF(s.route_path, '')),
       icon_key   = COALESCE(m.icon_key,   NULLIF(s.icon_key, '')),
       updated_by = 'V99', updated_date = CURRENT_TIMESTAMP
  FROM acc_seed_module s
 WHERE s.module_code = m.module_code
   AND ((m.route_path IS NULL AND s.route_path <> '') OR (m.icon_key IS NULL AND s.icon_key <> ''));

/* ═══ 3 · The roles and the permission matrix ═══════════════════════════════
   The three roles this file grants to, present by ROLE_CODE (V88 seeds all
   nine; this guards the three in case it did not run first). Then one row per
   role x module where none exists — a pair already there, however it was set,
   is somebody's decision and is skipped. */

INSERT INTO pharma.acl_group (pid_group, role_code, group_name, group_description, role_color, is_active, pid_org, status, inserted_by)
SELECT nextval('pharma.seq_acl_group'), s.role_code, s.group_name, s.group_description, s.role_color, 1,
       (SELECT MIN(pid_org) FROM pharma.all_organization), 'Y', 'V99'
  FROM (VALUES
    ('ROLE01', 'Super Admin',            'Full system access',                  '#21a810'),
    ('ROLE02', 'Administrator (Branch)', 'Branch operations management',        '#2f396a'),
    ('ROLE04', 'Accounts',               'Financial and collection management', '#b4d5c1')
  ) AS s(role_code, group_name, group_description, role_color)
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_group x WHERE x.role_code = s.role_code);

CREATE TEMP TABLE acc_seed_perm (role_code varchar(20), module_code varchar(20),
    can_view integer, can_create integer, can_edit integer, can_delete integer, can_approve integer, can_export integer, can_print integer) ON COMMIT DROP;

INSERT INTO acc_seed_perm
SELECT r.role_code, m.module_code,
       1, 1, 1, CASE WHEN r.role_code = 'ROLE04' THEN 0 ELSE 1 END, 1, 1, 1
  FROM (VALUES ('ROLE01'), ('ROLE02'), ('ROLE04')) AS r(role_code)
 CROSS JOIN acc_seed_module m
 WHERE m.parent_code <> '';

INSERT INTO pharma.pos_permissions
       (pid_permission, pid_role, pid_module,
        can_view, can_create, can_edit, can_delete, can_export, can_approve, can_print,
        status, inserted_by)
SELECT nextval('pharma.sq_pos_perm'), g.pid_group, m.pid_module,
       s.can_view, s.can_create, s.can_edit, s.can_delete, s.can_export, s.can_approve, s.can_print, 'Y', 'V99'
  FROM acc_seed_perm s
  JOIN pharma.acl_group g       ON g.role_code = s.role_code
  JOIN pharma.acl_module_info m ON m.module_code = s.module_code
 WHERE NOT EXISTS (SELECT 1 FROM pharma.pos_permissions x WHERE x.pid_role = g.pid_group AND x.pid_module = m.pid_module);

/* ═══ 4 · The plan templates ════════════════════════════════════════════════
   The three templates present by code (guarded, as in section 3), and a line
   per template x module where none exists. Every right on every plan — the
   policy question at the top of this file. */

INSERT INTO pharma.acl_perm_template (pid_template, template_code, template_name, description, plan_code, is_active, sort_order, status, inserted_by)
SELECT nextval('pharma.sq_perm_template'), s.template_code, s.template_name, s.description, s.plan_code, 1, s.sort_order, 'Y', 'V99'
  FROM (VALUES
    ('BASIC',      'Basic Pharmacy',      'Selling, stock and the day-to-day reports.',                'BASIC',      1),
    ('STANDARD',   'Standard Pharmacy',   'Adds buying, suppliers and the full reporting suite.',      'STANDARD',   2),
    ('ENTERPRISE', 'Enterprise Pharmacy', 'Everything, including HR, accounts and the AI copilot.',    'ENTERPRISE', 3)
  ) AS s(template_code, template_name, description, plan_code, sort_order)
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_perm_template x WHERE x.template_code = s.template_code);

CREATE TEMP TABLE acc_seed_tline (template_code varchar(30), module_code varchar(20),
    can_view integer, can_create integer, can_edit integer, can_delete integer, can_approve integer, can_export integer, can_print integer) ON COMMIT DROP;

INSERT INTO acc_seed_tline
SELECT t.template_code, m.module_code, 1, 1, 1, 1, 1, 1, 1
  FROM (VALUES ('BASIC'), ('STANDARD'), ('ENTERPRISE')) AS t(template_code)
 CROSS JOIN acc_seed_module m
 WHERE m.parent_code <> '';

INSERT INTO pharma.acl_perm_template_line
       (pid_template_line, pid_template, pid_module, can_view, can_create, can_edit, can_delete, can_approve, can_export, can_print)
SELECT nextval('pharma.sq_template_line'), t.pid_template, m.pid_module,
       s.can_view, s.can_create, s.can_edit, s.can_delete, s.can_approve, s.can_export, s.can_print
  FROM acc_seed_tline s
  JOIN pharma.acl_perm_template t ON t.template_code = s.template_code
  JOIN pharma.acl_module_info m   ON m.module_code = s.module_code
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_perm_template_line x WHERE x.pid_template = t.pid_template AND x.pid_module = m.pid_module);

/* ═══ 5 · Plan ceilings, from each branch's own plan ═══════════════════════
   A branch with plan rows is narrowed to the modules they name; an Accounts
   module with no row would be invisible there. Each such branch gets what
   onboarding would have given it: its live subscription names a plan, the
   plan a template, and the template's line carries the rights. Only the
   Accounts modules are considered, and only where no row exists — a ceiling
   set on the Pharmacies screen, or by V93, stays. A branch with no plan rows
   is unrestricted (V33) and gets none; a branch whose plan has no template
   gets none either, and is named in the verification below. */

INSERT INTO pharma.acl_tenant_module
       (pid_tenant_module, pid_branch, pid_module,
        can_view, can_create, can_edit, can_delete, can_approve, can_export, can_print, status, inserted_by)
SELECT nextval('pharma.sq_tenant_module'), b.pid_branch, l.pid_module,
       l.can_view, l.can_create, l.can_edit, l.can_delete, l.can_approve, l.can_export, l.can_print, 'Y', 'V99'
  FROM (SELECT DISTINCT pid_branch FROM pharma.acl_tenant_module) b
  JOIN pharma.sub_subscription sub ON sub.pid_branch = b.pid_branch AND sub.status = 'Y' AND sub.sub_status = 'ACTIVE'
  JOIN pharma.sub_plan pl          ON pl.pid_plan = sub.pid_plan
  JOIN pharma.acl_perm_template t  ON t.plan_code = pl.plan_code AND t.status = 'Y'
  JOIN pharma.acl_perm_template_line l ON l.pid_template = t.pid_template
  JOIN pharma.acl_module_info m    ON m.pid_module = l.pid_module
  JOIN acc_seed_module s           ON s.module_code = m.module_code AND s.parent_code <> ''
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_tenant_module x WHERE x.pid_branch = b.pid_branch AND x.pid_module = l.pid_module);

/* ═══ Verification ══════════════════════════════════════════════════════════
   Thirteen modules present, every child under the group, 36 matrix rows,
   36 template lines; and the narrowed branches that gained nothing. */

SELECT (SELECT count(*) FROM acc_seed_module)                                                        AS modules_expected,
       (SELECT count(*) FROM pharma.acl_module_info m JOIN acc_seed_module s ON s.module_code = m.module_code) AS modules_present,
       (SELECT count(*) FROM pharma.acl_module_info m JOIN acc_seed_module s ON s.module_code = m.module_code
         WHERE s.parent_code <> '' AND m.pid_parent_module IS NULL)                                   AS children_without_parent,
       (SELECT count(*) FROM pharma.acl_module_info m JOIN acc_seed_module s ON s.module_code = m.module_code
         WHERE m.is_active <> 1 OR m.status <> 'Y')                                                   AS modules_inactive,
       (SELECT count(*) FROM acc_seed_perm)                                                          AS matrix_rows_expected,
       (SELECT count(*) FROM pharma.pos_permissions p JOIN pharma.acl_group g ON g.pid_group = p.pid_role
          JOIN pharma.acl_module_info m ON m.pid_module = p.pid_module
          JOIN acc_seed_perm s ON s.role_code = g.role_code AND s.module_code = m.module_code)         AS matrix_rows_present,
       (SELECT count(*) FROM acc_seed_tline)                                                         AS template_lines_expected,
       (SELECT count(*) FROM pharma.acl_perm_template_line l JOIN pharma.acl_perm_template t ON t.pid_template = l.pid_template
          JOIN pharma.acl_module_info m ON m.pid_module = l.pid_module
          JOIN acc_seed_tline s ON s.template_code = t.template_code AND s.module_code = m.module_code) AS template_lines_present,
       (SELECT count(*) FROM pharma.acl_module_info WHERE inserted_by = 'V99')                        AS modules_seeded_here,
       (SELECT count(*) FROM pharma.pos_permissions WHERE inserted_by = 'V99')                        AS matrix_rows_seeded_here,
       (SELECT count(*) FROM pharma.acl_tenant_module WHERE inserted_by = 'V99')                      AS ceilings_seeded_here;

/* Narrowed branches with no Accounts ceiling at all after this file: their
   plan has no template. They see no Accounts menu until a template is applied
   from the Pharmacies screen. */
SELECT b.pid_branch, pl.plan_code
  FROM (SELECT DISTINCT pid_branch FROM pharma.acl_tenant_module) b
  LEFT JOIN pharma.sub_subscription sub ON sub.pid_branch = b.pid_branch AND sub.status = 'Y' AND sub.sub_status = 'ACTIVE'
  LEFT JOIN pharma.sub_plan pl          ON pl.pid_plan = sub.pid_plan
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_tenant_module x JOIN pharma.acl_module_info m ON m.pid_module = x.pid_module
                    WHERE x.pid_branch = b.pid_branch AND m.module_code = 'MOD41')
 ORDER BY 1;
