/* ─────────────────────────────────────────────────────────────────────────────
   HR, part 5 — the five screens, and who may open them.

   Follows V64 exactly, because V64 is the migration that got this right after
   one that did not: every sequence this file draws from is advanced with
   GREATEST before any row is inserted. On this dev database the sequences and
   the tables happen to be in step; on production rows were loaded with explicit
   ids, which does not move a sequence, and nextval returns a key that already
   exists. That failure took a whole migration down.

   ── Five modules, not one ──

   Employees, attendance, leave, advance-and-loan, payroll. They are separate
   because the permission matrix is the only thing that decides who may reach
   what, and a single HR module would mean the person who records attendance can
   also open the payroll. Splitting them is what lets a manager grant the shop
   floor attendance and keep salaries to themselves.

   ── Department and Designation are NOT here ──

   They already appear on the Master Data screen, under a group labelled "HR",
   governed by MOD02. Giving them a second home would mean two screens editing
   one list and two permissions deciding who may.

   ── Sort order 15 ──

   Last in the menu, because the ranks up to 14 are taken and renumbering the
   existing modules to make room would rewrite rows this work has no business
   touching. Moving it later is one UPDATE whenever somebody decides where it
   belongs.
   ───────────────────────────────────────────────────────────────────────────── */

SELECT setval('pharma.sq_acl_module',
    GREATEST((SELECT COALESCE(MAX(pid_module), 0) FROM pharma.acl_module_info),
             (SELECT last_value FROM pharma.sq_acl_module)),
    true);

SELECT setval('pharma.sq_pos_perm',
    GREATEST((SELECT COALESCE(MAX(pid_permission), 0) FROM pharma.pos_permissions),
             (SELECT last_value FROM pharma.sq_pos_perm)),
    true);

SELECT setval('pharma.sq_tenant_module',
    GREATEST((SELECT COALESCE(MAX(pid_tenant_module), 0) FROM pharma.acl_tenant_module),
             (SELECT last_value FROM pharma.sq_tenant_module)),
    true);

SELECT setval('pharma.sq_template_line',
    GREATEST((SELECT COALESCE(MAX(pid_template_line), 0) FROM pharma.acl_perm_template_line),
             (SELECT last_value FROM pharma.sq_template_line)),
    true);


/* ═══ The group heading ═════════════════════════════════════════════════════ */

INSERT INTO pharma.acl_module_info
       (pid_module, module_code, module_name, module_desc, route_path, icon_key,
        pid_parent_module, sort_order, is_active, status, inserted_by)
SELECT nextval('pharma.sq_acl_module'), 'GRP_HR', 'Human Resources',
       'Staff, attendance, leave and payroll', NULL, 'employees',
       NULL, 15, 1, 'Y', 'V77'
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_module_info x WHERE x.module_code = 'GRP_HR');


/* ═══ The five screens ══════════════════════════════════════════════════════ */

INSERT INTO pharma.acl_module_info
       (pid_module, module_code, module_name, module_desc, route_path, icon_key,
        pid_parent_module, sort_order, is_active, status, inserted_by)
SELECT nextval('pharma.sq_acl_module'), m.code, m.name, m.descr, m.route, m.icon,
       (SELECT pid_module FROM pharma.acl_module_info WHERE module_code = 'GRP_HR'),
       m.ord, 1, 'Y', 'V77'
  FROM (VALUES
        ('MOD32', 'Employees',      'The people employed at this pharmacy: their record, their pay basis and their departure',           '/hr',         'employees',  1),
        ('MOD33', 'Attendance',     'One row per person per day. What payroll counts a working month from',                               '/attendance', 'attendance', 2),
        ('MOD34', 'Leave',          'Requests, approvals and the balance each person has left',                                           '/leave',      'leave',      3),
        ('MOD35', 'Advance & Loan', 'Money lent to staff, and the plan by which payroll takes it back',                                   '/advances',   'advance',    4),
        ('MOD36', 'Payroll',        'The monthly run: calculate, review, approve, post and pay. A posted run cannot be changed',          '/payroll',    'payroll',    5)
       ) AS m(code, name, descr, route, icon, ord)
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_module_info x WHERE x.module_code = m.code);


/* ═══ Role grants ═══════════════════════════════════════════════════════════

   ROLE01 Super Admin and ROLE02 Administrator (Branch) get everything,
   approval included — a branch administrator is who signs off a payroll.

   ROLE04 Accounts gets the work but not the authority: view, create, edit,
   export and print, and no Delete and no Approve anywhere. That is the shape of
   the job. The person who prepares a payroll should not also be the person who
   approves it, and the matrix is where that separation is expressed.

   Everybody else gets nothing here. Not because no dispenser should ever record
   attendance, but because handing five new screens to seven roles on the day a
   module ships is a decision for the pharmacy, taken on the RBAC screen, not
   one this migration should make on their behalf. */

INSERT INTO pharma.pos_permissions
       (pid_permission, pid_role, pid_module,
        can_view, can_create, can_edit, can_delete, can_export, can_approve, can_print,
        status, inserted_by)
SELECT nextval('pharma.sq_pos_perm'), g.pid_group, n.pid_module,
       1, 1, 1, 1, 1, 1, 1, 'Y', 'V77'
  FROM pharma.acl_group g
 CROSS JOIN (SELECT pid_module FROM pharma.acl_module_info
              WHERE module_code IN ('MOD32','MOD33','MOD34','MOD35','MOD36')) n
 WHERE g.role_code IN ('ROLE01', 'ROLE02')
   AND NOT EXISTS (SELECT 1 FROM pharma.pos_permissions x
                    WHERE x.pid_role = g.pid_group AND x.pid_module = n.pid_module);

INSERT INTO pharma.pos_permissions
       (pid_permission, pid_role, pid_module,
        can_view, can_create, can_edit, can_delete, can_export, can_approve, can_print,
        status, inserted_by)
SELECT nextval('pharma.sq_pos_perm'), g.pid_group, n.pid_module,
       1, 1, 1, 0, 1, 0, 1, 'Y', 'V77'
  FROM pharma.acl_group g
 CROSS JOIN (SELECT pid_module FROM pharma.acl_module_info
              WHERE module_code IN ('MOD32','MOD33','MOD34','MOD35','MOD36')) n
 WHERE g.role_code = 'ROLE04'
   AND NOT EXISTS (SELECT 1 FROM pharma.pos_permissions x
                    WHERE x.pid_role = g.pid_group AND x.pid_module = n.pid_module);


/* ═══ The plan ceiling ══════════════════════════════════════════════════════

   PermissionResolver narrows every role grant by what the pharmacy's plan
   carries. Without a row here the modules are invisible however the roles are
   set, which reads to a manager as the feature not having shipped.

   Full rights at this level: the plan says the pharmacy may have HR, and the
   roles decide who inside it does what. Narrowing twice would mean a branch
   administrator could not be granted approval no matter what the RBAC screen
   showed — which is the exact defect this ceiling caused for purchase approval. */

INSERT INTO pharma.acl_tenant_module
       (pid_tenant_module, pid_branch, pid_module,
        can_view, can_create, can_edit, can_delete, can_approve, can_export, can_print,
        status, inserted_by)
SELECT nextval('pharma.sq_tenant_module'), b.pid_branch, n.pid_module,
       1, 1, 1, 1, 1, 1, 1, 'Y', 'V77'
  FROM (SELECT DISTINCT pid_branch FROM pharma.acl_tenant_module) b
 CROSS JOIN (SELECT pid_module FROM pharma.acl_module_info
              WHERE module_code IN ('MOD32','MOD33','MOD34','MOD35','MOD36')) n
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_tenant_module x
                    WHERE x.pid_branch = b.pid_branch AND x.pid_module = n.pid_module);


/* ═══ And into the templates, so a pharmacy onboarded tomorrow gets it ══════ */

INSERT INTO pharma.acl_perm_template_line
       (pid_template_line, pid_template, pid_module,
        can_view, can_create, can_edit, can_delete, can_approve, can_export, can_print)
SELECT nextval('pharma.sq_template_line'), t.pid_template, n.pid_module,
       1, 1, 1, 1, 1, 1, 1
  FROM (SELECT DISTINCT pid_template FROM pharma.acl_perm_template_line) t
 CROSS JOIN (SELECT pid_module FROM pharma.acl_module_info
              WHERE module_code IN ('MOD32','MOD33','MOD34','MOD35','MOD36')) n
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_perm_template_line x
                    WHERE x.pid_template = t.pid_template AND x.pid_module = n.pid_module);


/* ═══ Verification ══════════════════════════════════════════════════════════ */

SELECT m.module_code, m.module_name, m.route_path,
       p.module_name AS parent, m.sort_order,
       (SELECT count(*) FROM pharma.pos_permissions   x WHERE x.pid_module = m.pid_module) AS roles,
       (SELECT count(*) FROM pharma.acl_tenant_module x WHERE x.pid_module = m.pid_module) AS pharmacies,
       (SELECT count(*) FROM pharma.acl_perm_template_line x WHERE x.pid_module = m.pid_module) AS templates
  FROM pharma.acl_module_info m
  LEFT JOIN pharma.acl_module_info p ON p.pid_module = m.pid_parent_module
 WHERE m.module_code IN ('GRP_HR','MOD32','MOD33','MOD34','MOD35','MOD36')
 ORDER BY m.sort_order, m.module_code;
