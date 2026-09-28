/* ─────────────────────────────────────────────────────────────────────────────
   Accounts — the menu group and its twelve modules, registered the way V77,
   V86 and V87 registered HR: the row, the role grants, the plan ceiling, the
   onboarding templates. Every insert is guarded; nothing existing is touched.

     GRP_ACCOUNTS  Accounts
       MOD41  Accounts Dashboard   /accounts
       MOD42  Journals             /accjournals     manual journals, register, reversal
       MOD43  General Ledger       /accledger       ledger, trial balance
       MOD44  Chart of Accounts    /acccoa
       MOD45  Receivables & Payables /accparties    AR / AP ageing, statements
       MOD46  Cash & Bank          /acccash         vouchers, bank accounts, petty cash
       MOD47  Daily Cash           /accdailycash    branch day open / count / close
       MOD48  Investors            /accinvestors
       MOD49  Profit Distribution  /accprofit
       MOD50  Periods & Settings   /accsettings     fiscal years, periods, tax, cost centres, rules, settings
       MOD51  Accounts Reports     /accreports
       MOD52  Reconciliation       /accreconcile    event monitor, reconciliation centre, backfill

   Rights: administrators (ROLE01, ROLE02) hold everything. The Accounts role
   (ROLE04) holds view, create, edit, approve, export and print on every module
   and delete on none — a posted journal cannot be deleted by anyone, and the
   right that would let somebody remove a draft is an administrator's. Nobody
   else is granted anything by default; the pharmacy decides.
   ───────────────────────────────────────────────────────────────────────────── */

SELECT setval('pharma.sq_acl_module',
    GREATEST((SELECT COALESCE(MAX(pid_module), 0) FROM pharma.acl_module_info),
             (SELECT last_value FROM pharma.sq_acl_module)), true);
SELECT setval('pharma.sq_pos_perm',
    GREATEST((SELECT COALESCE(MAX(pid_permission), 0) FROM pharma.pos_permissions),
             (SELECT last_value FROM pharma.sq_pos_perm)), true);
SELECT setval('pharma.sq_tenant_module',
    GREATEST((SELECT COALESCE(MAX(pid_tenant_module), 0) FROM pharma.acl_tenant_module),
             (SELECT last_value FROM pharma.sq_tenant_module)), true);
SELECT setval('pharma.sq_template_line',
    GREATEST((SELECT COALESCE(MAX(pid_template_line), 0) FROM pharma.acl_perm_template_line),
             (SELECT last_value FROM pharma.sq_template_line)), true);

/* ── The group ────────────────────────────────────────────────────────────── */
INSERT INTO pharma.acl_module_info
       (pid_module, module_code, module_name, module_desc, route_path, icon_key,
        pid_parent_module, sort_order, is_active, status, inserted_by)
SELECT nextval('pharma.sq_acl_module'), 'GRP_ACCOUNTS', 'Accounts',
       'Double-entry books: journals, ledger, cash, investors, profit share and financial reports',
       NULL, 'accounts', NULL, 10, 1, 'Y', 'V93'
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_module_info x WHERE x.module_code = 'GRP_ACCOUNTS');

/* ── The modules ──────────────────────────────────────────────────────────── */
INSERT INTO pharma.acl_module_info
       (pid_module, module_code, module_name, module_desc, route_path, icon_key,
        pid_parent_module, sort_order, is_active, status, inserted_by)
SELECT nextval('pharma.sq_acl_module'), v.code, v.name, v.descr, v.route, v.icon,
       (SELECT pid_module FROM pharma.acl_module_info WHERE module_code = 'GRP_ACCOUNTS'),
       v.ord, 1, 'Y', 'V93'
  FROM (VALUES
    ('MOD41','Accounts Dashboard',     'KPIs, cash position, receivables and payables, investor summary, pending closings', '/accounts',     'accdashboard', 1),
    ('MOD42','Journals',               'Journal register, manual journals, reversals',                                       '/accjournals',  'journal',      2),
    ('MOD43','General Ledger',         'Account-wise ledger and trial balance',                                              '/accledger',    'ledger',       3),
    ('MOD44','Chart of Accounts',      'The account hierarchy and posting roles',                                            '/acccoa',       'coa',          4),
    ('MOD45','Receivables & Payables', 'Customer and supplier balances, ageing and statements, from the ledger',             '/accparties',   'parties',      5),
    ('MOD46','Cash & Bank',            'Cash vouchers, bank accounts, petty cash, transfers',                                '/acccash',      'cashbank',     6),
    ('MOD47','Daily Cash',             'Branch business day: opening, cash count, closing, variance',                        '/accdailycash', 'dailycash',    7),
    ('MOD48','Investors',              'Investor master, capital transactions and ledger',                                   '/accinvestors', 'investors',    8),
    ('MOD49','Profit Distribution',    'Period profit, distributable profit, investor allocation, posting and payment',      '/accprofit',    'profit',       9),
    ('MOD50','Periods & Settings',     'Fiscal years, periods, tax rates, cost centres, accounting rules and policies',      '/accsettings',  'accsettings', 10),
    ('MOD51','Accounts Reports',       'Trial balance, profit and loss, balance sheet, cash books, ageing, investor and payroll reports', '/accreports', 'accreports', 11),
    ('MOD52','Reconciliation',         'Accounting event monitor, business-vs-ledger reconciliation and historical backfill', '/accreconcile', 'reconcile', 12)
  ) AS v(code, name, descr, route, icon, ord)
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_module_info x WHERE x.module_code = v.code);

/* ── Roles ────────────────────────────────────────────────────────────────── */
INSERT INTO pharma.pos_permissions
       (pid_permission, pid_role, pid_module,
        can_view, can_create, can_edit, can_delete, can_export, can_approve, can_print,
        status, inserted_by)
SELECT nextval('pharma.sq_pos_perm'), g.pid_group, n.pid_module,
       1, 1, 1, 1, 1, 1, 1, 'Y', 'V93'
  FROM pharma.acl_group g
 CROSS JOIN (SELECT pid_module FROM pharma.acl_module_info
              WHERE module_code IN ('MOD41','MOD42','MOD43','MOD44','MOD45','MOD46','MOD47','MOD48','MOD49','MOD50','MOD51','MOD52')) n
 WHERE g.role_code IN ('ROLE01', 'ROLE02')
   AND NOT EXISTS (SELECT 1 FROM pharma.pos_permissions x
                    WHERE x.pid_role = g.pid_group AND x.pid_module = n.pid_module);

INSERT INTO pharma.pos_permissions
       (pid_permission, pid_role, pid_module,
        can_view, can_create, can_edit, can_delete, can_export, can_approve, can_print,
        status, inserted_by)
SELECT nextval('pharma.sq_pos_perm'), g.pid_group, n.pid_module,
       1, 1, 1, 0, 1, 1, 1, 'Y', 'V93'
  FROM pharma.acl_group g
 CROSS JOIN (SELECT pid_module FROM pharma.acl_module_info
              WHERE module_code IN ('MOD41','MOD42','MOD43','MOD44','MOD45','MOD46','MOD47','MOD48','MOD49','MOD50','MOD51','MOD52')) n
 WHERE g.role_code = 'ROLE04'
   AND NOT EXISTS (SELECT 1 FROM pharma.pos_permissions x
                    WHERE x.pid_role = g.pid_group AND x.pid_module = n.pid_module);

/* ── Plan ceiling and onboarding templates ────────────────────────────────── */
INSERT INTO pharma.acl_tenant_module
       (pid_tenant_module, pid_branch, pid_module,
        can_view, can_create, can_edit, can_delete, can_approve, can_export, can_print,
        status, inserted_by)
SELECT nextval('pharma.sq_tenant_module'), b.pid_branch, n.pid_module,
       1, 1, 1, 1, 1, 1, 1, 'Y', 'V93'
  FROM (SELECT DISTINCT pid_branch FROM pharma.acl_tenant_module) b
 CROSS JOIN (SELECT pid_module FROM pharma.acl_module_info
              WHERE module_code IN ('MOD41','MOD42','MOD43','MOD44','MOD45','MOD46','MOD47','MOD48','MOD49','MOD50','MOD51','MOD52')) n
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_tenant_module x
                    WHERE x.pid_branch = b.pid_branch AND x.pid_module = n.pid_module);

INSERT INTO pharma.acl_perm_template_line
       (pid_template_line, pid_template, pid_module,
        can_view, can_create, can_edit, can_delete, can_approve, can_export, can_print)
SELECT nextval('pharma.sq_template_line'), t.pid_template, n.pid_module,
       1, 1, 1, 1, 1, 1, 1
  FROM (SELECT DISTINCT pid_template FROM pharma.acl_perm_template_line) t
 CROSS JOIN (SELECT pid_module FROM pharma.acl_module_info
              WHERE module_code IN ('MOD41','MOD42','MOD43','MOD44','MOD45','MOD46','MOD47','MOD48','MOD49','MOD50','MOD51','MOD52')) n
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_perm_template_line x
                    WHERE x.pid_template = t.pid_template AND x.pid_module = n.pid_module);

/* ═══ Verification ══════════════════════════════════════════════════════════ */
SELECT m.module_code, m.module_name, m.route_path, m.sort_order,
       (SELECT count(*) FROM pharma.pos_permissions   x WHERE x.pid_module = m.pid_module) AS roles,
       (SELECT count(*) FROM pharma.acl_tenant_module x WHERE x.pid_module = m.pid_module) AS pharmacies
  FROM pharma.acl_module_info m
 WHERE m.module_code LIKE 'MOD4%' OR m.module_code LIKE 'MOD5%' OR m.module_code = 'GRP_ACCOUNTS'
 ORDER BY m.sort_order;
