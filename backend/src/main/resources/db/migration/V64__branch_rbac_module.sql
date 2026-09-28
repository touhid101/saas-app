/* ─────────────────────────────────────────────────────────────────────────────
   Branch Access Control — RBAC for one pharmacy, run from inside it.

   MOD16 "RBAC / Admin" is the platform's screen: it maintains the modules and
   roles every pharmacy shares. This is the other half — the same work, bounded
   to a single branch, so a Branch Manager can add a dispenser and decide what
   they may do without a call to the platform.

   ── Why a separate module and not a right on MOD16 ──

   Because they are not one screen with different buttons. MOD16 edits the
   global catalogue: one manager renaming a role would rename it for every
   pharmacy on the estate, and creating a module would put a page on everyone's
   menu. Granting a pharmacy any view of MOD16 leaves that one permission
   mistake away. MOD31 can do none of it — roles and modules are read-only
   there — so it is its own module.

   ── What bounds it ──

   The branch is never in a URL. BranchRbacController resolves it from the
   session, so another pharmacy is unreachable rather than merely refused. The
   grant below is what makes the page appear; the isolation is in the server and
   does not depend on it.

   Rights are View and Edit. Create is not granted here: adding a person is
   Create on this module in the UI, but the write it performs is checked against
   the existing RBAC user endpoints, which are branch-scoped in
   PermissionServiceImpl. Delete is absent deliberately — removing an account is
   an act with a paper trail behind it, and deactivating is the reversible one.
   ───────────────────────────────────────────────────────────────────────────── */

/* ── every sequence this file draws from, put ahead of its table ──────────────

   The first version advanced only sq_acl_module and then called nextval on
   three other sequences. On dev all four happened to be in step and it passed;
   on production sq_pos_perm was behind — rows had been loaded with explicit
   ids, which does not move a sequence — so nextval returned 2, a key that
   already existed, and the whole migration rolled back:

       duplicate key value violates unique constraint "pos_permissions_pkey"
       Detail: Key (pid_permission)=(2) already exists.

   A migration cannot assume a sequence is correct. It is cheap to make sure,
   and GREATEST means this never moves one backwards. */
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

/* Under Administration, directly after the platform's own RBAC screen — the two
   are the same subject at two scopes, and a reader should meet them together. */
INSERT INTO pharma.acl_module_info
       (pid_module, module_code, module_name, module_desc, route_path, icon_key,
        pid_parent_module, sort_order, is_active, status, inserted_by)
SELECT nextval('pharma.sq_acl_module'), 'MOD31', 'Branch Access Control',
       'The people at this pharmacy and what each of them may do. Roles and modules '
       || 'are the platform''s and are read-only here; permissions cannot exceed the '
       || 'module grant the pharmacy''s plan carries.',
       '/branchrbac', 'shield',
       (SELECT pid_module FROM pharma.acl_module_info WHERE module_code = 'GRP_ADMINISTRATION'),
       12, 1, 'Y', 'V64'
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_module_info x WHERE x.module_code = 'MOD31');

/* ROLE02 is who this is for. ROLE01 too, so a platform administrator supporting
   a pharmacy sees the same screen the manager is describing to them. */
INSERT INTO pharma.pos_permissions
       (pid_permission, pid_role, pid_module,
        can_view, can_create, can_edit, can_delete, can_export, can_approve, can_print,
        status, inserted_by)
SELECT nextval('pharma.sq_pos_perm'), g.pid_group, n.pid_module,
       1, 1, 1, 0, 0, 0, 0, 'Y', 'V64'
  FROM pharma.acl_group g
 CROSS JOIN (SELECT pid_module FROM pharma.acl_module_info WHERE module_code = 'MOD31') n
 WHERE g.role_code IN ('ROLE01', 'ROLE02')
   AND NOT EXISTS (SELECT 1 FROM pharma.pos_permissions x
                    WHERE x.pid_role = g.pid_group AND x.pid_module = n.pid_module);

/* Every pharmacy that carries a plan needs it in the plan, or PermissionResolver
   drops the module whatever the roles allow — the plan is a ceiling, and a
   branch with a restricted plan would never see the page. */
INSERT INTO pharma.acl_tenant_module
       (pid_tenant_module, pid_branch, pid_module,
        can_view, can_create, can_edit, can_delete, can_approve, can_export, can_print,
        status, inserted_by)
SELECT nextval('pharma.sq_tenant_module'), b.pid_branch, n.pid_module,
       1, 1, 1, 0, 0, 0, 0, 'Y', 'V64'
  FROM (SELECT DISTINCT pid_branch FROM pharma.acl_tenant_module) b
 CROSS JOIN (SELECT pid_module FROM pharma.acl_module_info WHERE module_code = 'MOD31') n
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_tenant_module x
                    WHERE x.pid_branch = b.pid_branch AND x.pid_module = n.pid_module);

/* And in the templates, so a pharmacy onboarded tomorrow gets it too. */
INSERT INTO pharma.acl_perm_template_line
       (pid_template_line, pid_template, pid_module,
        can_view, can_create, can_edit, can_delete, can_approve, can_export, can_print)
SELECT nextval('pharma.sq_template_line'), t.pid_template, n.pid_module,
       1, 1, 1, 0, 0, 0, 0
  FROM (SELECT DISTINCT pid_template FROM pharma.acl_perm_template_line) t
 CROSS JOIN (SELECT pid_module FROM pharma.acl_module_info WHERE module_code = 'MOD31') n
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_perm_template_line x
                    WHERE x.pid_template = t.pid_template AND x.pid_module = n.pid_module);

SELECT m.module_code, m.module_name, m.route_path, p.module_name AS parent, m.sort_order,
       (SELECT count(*) FROM pharma.pos_permissions x WHERE x.pid_module = m.pid_module) AS roles,
       (SELECT count(*) FROM pharma.acl_tenant_module x WHERE x.pid_module = m.pid_module) AS pharmacies
  FROM pharma.acl_module_info m
  LEFT JOIN pharma.acl_module_info p ON p.pid_module = m.pid_parent_module
 WHERE m.module_code IN ('MOD16', 'MOD31');
