/* ─────────────────────────────────────────────────────────────────────────────
   HR — its reports and its master data, as modules of the HR group.

   Until now the twenty-five HR reports sat on the general Reports screen
   (MOD14) beside sales and purchase, and Department, Designation and the
   Employee list sat on Master Data (MOD02) beside products and packaging.
   Somebody who runs HR opened two other modules to do HR work, and somebody
   who ran sales reports saw payroll figures they had no business seeing.

   Two rows put both where they belong:

     MOD40  HR Master    /hrmaster    Department and Designation
     MOD39  HR Reports   /hrreports   every hr- report

   The employee list leaves Master Data altogether: the HR Employees screen
   (MOD32) now enters and edits a record in full, photograph included, and
   two forms over one row is how the two quietly disagree.

   Rights follow the pattern V77, V86 set: administrators (ROLE01, ROLE02)
   hold everything on both; the payroll role (ROLE04) reads, exports and
   prints reports and touches no master data. The general Reports right no
   longer opens an HR report — the server checks View on MOD39 for any hr-
   id — so a role that could see payroll only because it could see sales
   reports cannot any more. Grant MOD39 to restore it deliberately.

   Registered like every module before it: the row, the role grants, the plan
   ceiling, the onboarding templates. Nothing existing is dropped or renamed.
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

/* ── The modules ──────────────────────────────────────────────────────────── */

INSERT INTO pharma.acl_module_info
       (pid_module, module_code, module_name, module_desc, route_path, icon_key,
        pid_parent_module, sort_order, is_active, status, inserted_by)
SELECT nextval('pharma.sq_acl_module'), 'MOD40', 'HR Master',
       'Departments and designations - the lists every staff record points at',
       '/hrmaster', 'database',
       (SELECT pid_module FROM pharma.acl_module_info WHERE module_code = 'GRP_HR'),
       0, 1, 'Y', 'V87'
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_module_info x WHERE x.module_code = 'MOD40');

INSERT INTO pharma.acl_module_info
       (pid_module, module_code, module_name, module_desc, route_path, icon_key,
        pid_parent_module, sort_order, is_active, status, inserted_by)
SELECT nextval('pharma.sq_acl_module'), 'MOD39', 'HR Reports',
       'People, attendance and leave, pay - every HR report, read from the live tables',
       '/hrreports', 'reports',
       (SELECT pid_module FROM pharma.acl_module_info WHERE module_code = 'GRP_HR'),
       8, 1, 'Y', 'V87'
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_module_info x WHERE x.module_code = 'MOD39');

/* ── Roles ────────────────────────────────────────────────────────────────── */

/* ROLE01 and ROLE02: everything on both. */
INSERT INTO pharma.pos_permissions
       (pid_permission, pid_role, pid_module,
        can_view, can_create, can_edit, can_delete, can_export, can_approve, can_print,
        status, inserted_by)
SELECT nextval('pharma.sq_pos_perm'), g.pid_group, n.pid_module,
       1, 1, 1, 1, 1, 1, 1, 'Y', 'V87'
  FROM pharma.acl_group g
 CROSS JOIN (SELECT pid_module FROM pharma.acl_module_info WHERE module_code IN ('MOD39', 'MOD40')) n
 WHERE g.role_code IN ('ROLE01', 'ROLE02')
   AND NOT EXISTS (SELECT 1 FROM pharma.pos_permissions x
                    WHERE x.pid_role = g.pid_group AND x.pid_module = n.pid_module);

/* ROLE04 reads, exports and prints the reports; master data is not its work. */
INSERT INTO pharma.pos_permissions
       (pid_permission, pid_role, pid_module,
        can_view, can_create, can_edit, can_delete, can_export, can_approve, can_print,
        status, inserted_by)
SELECT nextval('pharma.sq_pos_perm'), g.pid_group, n.pid_module,
       1, 0, 0, 0, 1, 0, 1, 'Y', 'V87'
  FROM pharma.acl_group g
 CROSS JOIN (SELECT pid_module FROM pharma.acl_module_info WHERE module_code = 'MOD39') n
 WHERE g.role_code = 'ROLE04'
   AND NOT EXISTS (SELECT 1 FROM pharma.pos_permissions x
                    WHERE x.pid_role = g.pid_group AND x.pid_module = n.pid_module);

/* ── Plan ceiling and onboarding templates ────────────────────────────────── */

INSERT INTO pharma.acl_tenant_module
       (pid_tenant_module, pid_branch, pid_module,
        can_view, can_create, can_edit, can_delete, can_approve, can_export, can_print,
        status, inserted_by)
SELECT nextval('pharma.sq_tenant_module'), b.pid_branch, n.pid_module,
       1, 1, 1, 1, 1, 1, 1, 'Y', 'V87'
  FROM (SELECT DISTINCT pid_branch FROM pharma.acl_tenant_module) b
 CROSS JOIN (SELECT pid_module FROM pharma.acl_module_info WHERE module_code IN ('MOD39', 'MOD40')) n
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_tenant_module x
                    WHERE x.pid_branch = b.pid_branch AND x.pid_module = n.pid_module);

INSERT INTO pharma.acl_perm_template_line
       (pid_template_line, pid_template, pid_module,
        can_view, can_create, can_edit, can_delete, can_approve, can_export, can_print)
SELECT nextval('pharma.sq_template_line'), t.pid_template, n.pid_module,
       1, 1, 1, 1, 1, 1, 1
  FROM (SELECT DISTINCT pid_template FROM pharma.acl_perm_template_line) t
 CROSS JOIN (SELECT pid_module FROM pharma.acl_module_info WHERE module_code IN ('MOD39', 'MOD40')) n
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_perm_template_line x
                    WHERE x.pid_template = t.pid_template AND x.pid_module = n.pid_module);


/* ═══ Verification ══════════════════════════════════════════════════════════ */

SELECT m.module_code, m.module_name, m.route_path, m.sort_order,
       (SELECT count(*) FROM pharma.pos_permissions   x WHERE x.pid_module = m.pid_module) AS roles,
       (SELECT count(*) FROM pharma.acl_tenant_module x WHERE x.pid_module = m.pid_module) AS pharmacies,
       (SELECT count(*) FROM pharma.acl_perm_template_line x WHERE x.pid_module = m.pid_module) AS templates
  FROM pharma.acl_module_info m
 WHERE m.module_code IN ('MOD39', 'MOD40')
 ORDER BY m.sort_order;
