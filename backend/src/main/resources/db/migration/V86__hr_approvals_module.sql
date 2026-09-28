/* ─────────────────────────────────────────────────────────────────────────────
   HR — the Approvals screen as a module of its own.

   V85 built the chains and the inbox but registered no module, so the screen
   had no row on the RBAC matrix and no place in the sidebar — which the
   application builds from ACL_MODULE_INFO rather than from a hard-coded list.

   MOD38 is that row. Two rights carry different meanings on it:

     View  — may open the screen and see the inbox
     Edit  — may configure who signs what

   The inbox narrows itself again on top of that: an entry only appears where
   the reader holds the Approve right on the module the entry belongs to. So
   granting View here shows somebody their own queue, never everybody's.

   Registered the way V77 and V83 registered theirs: the module, the role
   grants, the plan ceiling, and the onboarding templates. Miss the ceiling and
   the screen is invisible however the roles are set.
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

INSERT INTO pharma.acl_module_info
       (pid_module, module_code, module_name, module_desc, route_path, icon_key,
        pid_parent_module, sort_order, is_active, status, inserted_by)
SELECT nextval('pharma.sq_acl_module'), 'MOD38', 'Approvals',
       'Everything waiting for a decision, and who signs it. Nothing is configured until somebody adds a chain',
       '/approvals', 'approvals',
       (SELECT pid_module FROM pharma.acl_module_info WHERE module_code = 'GRP_HR'),
       7, 1, 'Y', 'V86'
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_module_info x WHERE x.module_code = 'MOD38');

/* ROLE01 and ROLE02 configure chains as well as read the inbox. */
INSERT INTO pharma.pos_permissions
       (pid_permission, pid_role, pid_module,
        can_view, can_create, can_edit, can_delete, can_export, can_approve, can_print,
        status, inserted_by)
SELECT nextval('pharma.sq_pos_perm'), g.pid_group, n.pid_module,
       1, 1, 1, 1, 1, 1, 1, 'Y', 'V86'
  FROM pharma.acl_group g
 CROSS JOIN (SELECT pid_module FROM pharma.acl_module_info WHERE module_code = 'MOD38') n
 WHERE g.role_code IN ('ROLE01', 'ROLE02')
   AND NOT EXISTS (SELECT 1 FROM pharma.pos_permissions x
                    WHERE x.pid_role = g.pid_group AND x.pid_module = n.pid_module);

/* ROLE04 reads its own queue and does not decide who signs what. Who signs is
   pay policy, and the person who prepares payroll is not the person who sets
   the rules it runs under — the same separation V77 drew. */
INSERT INTO pharma.pos_permissions
       (pid_permission, pid_role, pid_module,
        can_view, can_create, can_edit, can_delete, can_export, can_approve, can_print,
        status, inserted_by)
SELECT nextval('pharma.sq_pos_perm'), g.pid_group, n.pid_module,
       1, 0, 0, 0, 1, 0, 1, 'Y', 'V86'
  FROM pharma.acl_group g
 CROSS JOIN (SELECT pid_module FROM pharma.acl_module_info WHERE module_code = 'MOD38') n
 WHERE g.role_code = 'ROLE04'
   AND NOT EXISTS (SELECT 1 FROM pharma.pos_permissions x
                    WHERE x.pid_role = g.pid_group AND x.pid_module = n.pid_module);

INSERT INTO pharma.acl_tenant_module
       (pid_tenant_module, pid_branch, pid_module,
        can_view, can_create, can_edit, can_delete, can_approve, can_export, can_print,
        status, inserted_by)
SELECT nextval('pharma.sq_tenant_module'), b.pid_branch, n.pid_module,
       1, 1, 1, 1, 1, 1, 1, 'Y', 'V86'
  FROM (SELECT DISTINCT pid_branch FROM pharma.acl_tenant_module) b
 CROSS JOIN (SELECT pid_module FROM pharma.acl_module_info WHERE module_code = 'MOD38') n
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_tenant_module x
                    WHERE x.pid_branch = b.pid_branch AND x.pid_module = n.pid_module);

INSERT INTO pharma.acl_perm_template_line
       (pid_template_line, pid_template, pid_module,
        can_view, can_create, can_edit, can_delete, can_approve, can_export, can_print)
SELECT nextval('pharma.sq_template_line'), t.pid_template, n.pid_module,
       1, 1, 1, 1, 1, 1, 1
  FROM (SELECT DISTINCT pid_template FROM pharma.acl_perm_template_line) t
 CROSS JOIN (SELECT pid_module FROM pharma.acl_module_info WHERE module_code = 'MOD38') n
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_perm_template_line x
                    WHERE x.pid_template = t.pid_template AND x.pid_module = n.pid_module);


/* ═══ Verification ══════════════════════════════════════════════════════════ */

SELECT m.module_code, m.module_name, m.route_path, m.icon_key,
       (SELECT count(*) FROM pharma.pos_permissions   x WHERE x.pid_module = m.pid_module) AS roles,
       (SELECT count(*) FROM pharma.acl_tenant_module x WHERE x.pid_module = m.pid_module) AS pharmacies,
       (SELECT count(*) FROM pharma.acl_perm_template_line x WHERE x.pid_module = m.pid_module) AS templates
  FROM pharma.acl_module_info m
 WHERE m.module_code IN ('MOD37', 'MOD38')
 ORDER BY m.module_code;
