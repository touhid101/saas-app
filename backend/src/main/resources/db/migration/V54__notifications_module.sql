/* ─────────────────────────────────────────────────────────────────────────────
   Notifications, as a grantable capability.

   The bell on the topbar was ungated: every role saw it, and what it showed was
   eight hard-coded strings in mockData.js — "8 products below alert quantity",
   "3 products have zero stock" — identical for every user, every branch and
   every day, describing a pharmacy that does not exist. Nothing behind it was
   ever read from the database.

   This makes it a real capability that RBAC can grant, so the bell can be given
   to a role or taken away without touching code.

   ── A module with no route ──

   MOD28 has no ROUTE_PATH because notifications are not a page. That is what
   distinguishes it from every other module here, and the two definitions of
   "not a page" already in the codebase disagreed about it:

     · The role matrix (RBAC.jsx) calls a row a container when it has children
       AND no route — so a route-less leaf like this one is grantable there.
     · The per-user screen skipped anything without a route at all, so the same
       row would have been invisible.

   The first definition is the correct one and the second is aligned to it in
   TenantOnboardingService. A route-less module WITH children is a heading; a
   route-less module WITHOUT children is a capability. PermissionServiceImpl
   keeps capabilities out of the sidebar, where they have nowhere to navigate to.

   ── Who gets it ──

   ROLE01 and ROLE02 only, as asked. Every other role loses a bell it had, and
   gets it back with one tick on the Permissions screen — which is the point of
   moving this out of the code. Each alert inside the bell is separately gated by
   the View right on the module it links to, so granting the bell never reveals
   a figure the role could not already have looked up.
   ───────────────────────────────────────────────────────────────────────────── */

/* V38, V39, V48 and V52 each inserted a module and left the sequence behind the
   table. GREATEST so re-running can only move it forward. */
SELECT setval('pharma.sq_acl_module',
    GREATEST((SELECT COALESCE(MAX(pid_module), 0) FROM pharma.acl_module_info),
             (SELECT last_value FROM pharma.sq_acl_module)),
    true);

INSERT INTO pharma.acl_module_info
       (pid_module, module_code, module_name, module_desc, route_path, icon_key,
        pid_parent_module, sort_order, is_active, status, inserted_by)
SELECT nextval('pharma.sq_acl_module'), 'MOD28', 'Notifications',
       'The alert bell on the topbar. Each alert inside it is additionally gated '
       || 'by the View right on the module it links to.',
       NULL, 'bell', NULL, 12, 1, 'Y', 'V54'
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_module_info x WHERE x.module_code = 'MOD28');

/* View only. There is nothing to create, edit or delete on an alert — it is a
   count of things that already exist elsewhere. */
INSERT INTO pharma.pos_permissions
       (pid_permission, pid_role, pid_module,
        can_view, can_create, can_edit, can_delete, can_export, can_approve, can_print,
        status, inserted_by)
SELECT nextval('pharma.sq_pos_perm'), g.pid_group, n.pid_module,
       1, 0, 0, 0, 0, 0, 0, 'Y', 'V54'
  FROM pharma.acl_group g
 CROSS JOIN (SELECT pid_module FROM pharma.acl_module_info WHERE module_code = 'MOD28') n
 WHERE g.role_code IN ('ROLE01', 'ROLE02')
   AND NOT EXISTS (SELECT 1 FROM pharma.pos_permissions x
                    WHERE x.pid_role = g.pid_group AND x.pid_module = n.pid_module);

/* Every pharmacy's plan carries it. Without a row here PermissionResolver drops
   the module outright, whatever the roles allow — the plan is a ceiling. */
INSERT INTO pharma.acl_tenant_module
       (pid_tenant_module, pid_branch, pid_module,
        can_view, can_create, can_edit, can_delete, can_approve, can_export, can_print,
        status, inserted_by)
SELECT nextval('pharma.sq_tenant_module'), b.pid_branch, n.pid_module,
       1, 0, 0, 0, 0, 0, 0, 'Y', 'V54'
  FROM (SELECT DISTINCT pid_branch FROM pharma.acl_tenant_module) b
 CROSS JOIN (SELECT pid_module FROM pharma.acl_module_info WHERE module_code = 'MOD28') n
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_tenant_module x
                    WHERE x.pid_branch = b.pid_branch AND x.pid_module = n.pid_module);

/* And every template, so a pharmacy activated tomorrow gets the same. */
INSERT INTO pharma.acl_perm_template_line
       (pid_template_line, pid_template, pid_module,
        can_view, can_create, can_edit, can_delete, can_approve, can_export, can_print)
SELECT nextval('pharma.sq_template_line'), t.pid_template, n.pid_module,
       1, 0, 0, 0, 0, 0, 0
  FROM (SELECT DISTINCT pid_template FROM pharma.acl_perm_template_line) t
 CROSS JOIN (SELECT pid_module FROM pharma.acl_module_info WHERE module_code = 'MOD28') n
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_perm_template_line x
                    WHERE x.pid_template = t.pid_template AND x.pid_module = n.pid_module);

COMMIT;

SELECT g.role_code, g.group_name, COALESCE(p.can_view, 0) AS bell
  FROM pharma.acl_group g
  LEFT JOIN pharma.acl_module_info m ON m.module_code = 'MOD28'
  LEFT JOIN pharma.pos_permissions p ON p.pid_role = g.pid_group AND p.pid_module = m.pid_module
 ORDER BY g.role_code;
