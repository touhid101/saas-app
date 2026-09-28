/* ─────────────────────────────────────────────────────────────────────────────
   Medicine Catalogue, for the pharmacy rather than the platform.

   MOD24 "Medicine Catalogue" is the operator's screen: it maintains the shared
   catalogue and pushes entries out to chosen pharmacies. It sits behind
   ROLE01 and a pharmacy cannot open it.

   This is the other half of that arrangement — the same catalogue, read-only,
   with the pharmacy pulling what it wants instead of waiting to be sent it.

   ── Why a separate module and not a right on MOD24 ──

   Because the two screens are not the same screen with different buttons. The
   operator's version can add, edit and retire entries, and choose which
   pharmacies receive them; granting a pharmacy any view of MOD24 would put the
   whole catalogue's editing surface one permission mistake away from a customer.
   A pharmacy's version can do exactly one thing — copy an entry into its own
   product list — so it is its own module with its own single right.

   ── What the import can and cannot do ──

   Importing writes rows into the pharmacy's Product Master. That is a create,
   and it is gated as one: the screen needs View here, and the import itself
   additionally requires Create on Product Master (MOD17), checked in the
   service. Somebody who may not add a single product by hand does not get to
   add three hundred at once through a different door.

   The target branch is never sent by the browser. It is resolved from the
   session, so this screen cannot import into anybody else's pharmacy however
   the request is shaped.
   ───────────────────────────────────────────────────────────────────────────── */

SELECT setval('pharma.sq_acl_module',
    GREATEST((SELECT COALESCE(MAX(pid_module), 0) FROM pharma.acl_module_info),
             (SELECT last_value FROM pharma.sq_acl_module)),
    true);

/* Under Master Data, after Product Master — it is where products come from. */
INSERT INTO pharma.acl_module_info
       (pid_module, module_code, module_name, module_desc, route_path, icon_key,
        pid_parent_module, sort_order, is_active, status, inserted_by)
SELECT nextval('pharma.sq_acl_module'), 'MOD30', 'Medicine Catalogue',
       'Browse the shared medicine catalogue and copy entries into this pharmacy''s '
       || 'own product list. Read-only: the catalogue itself is maintained centrally.',
       '/branchcatalogue', 'catalogue',
       (SELECT pid_module FROM pharma.acl_module_info WHERE module_code = 'GRP_MASTER'),
       4, 1, 'Y', 'V57'
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_module_info x WHERE x.module_code = 'MOD30');

/* View for the two roles that run a pharmacy. Create is deliberately not granted
   here — the import checks Create on Product Master instead, so the two stay in
   step and a pharmacy cannot gain product-creation through this screen alone. */
INSERT INTO pharma.pos_permissions
       (pid_permission, pid_role, pid_module,
        can_view, can_create, can_edit, can_delete, can_export, can_approve, can_print,
        status, inserted_by)
SELECT nextval('pharma.sq_pos_perm'), g.pid_group, n.pid_module,
       1, 0, 0, 0, 0, 0, 0, 'Y', 'V57'
  FROM pharma.acl_group g
 CROSS JOIN (SELECT pid_module FROM pharma.acl_module_info WHERE module_code = 'MOD30') n
 WHERE g.role_code IN ('ROLE01', 'ROLE02')
   AND NOT EXISTS (SELECT 1 FROM pharma.pos_permissions x
                    WHERE x.pid_role = g.pid_group AND x.pid_module = n.pid_module);

/* Every pharmacy's plan carries it, or PermissionResolver drops the module
   whatever the roles allow — the plan is a ceiling. */
INSERT INTO pharma.acl_tenant_module
       (pid_tenant_module, pid_branch, pid_module,
        can_view, can_create, can_edit, can_delete, can_approve, can_export, can_print,
        status, inserted_by)
SELECT nextval('pharma.sq_tenant_module'), b.pid_branch, n.pid_module,
       1, 0, 0, 0, 0, 0, 0, 'Y', 'V57'
  FROM (SELECT DISTINCT pid_branch FROM pharma.acl_tenant_module) b
 CROSS JOIN (SELECT pid_module FROM pharma.acl_module_info WHERE module_code = 'MOD30') n
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_tenant_module x
                    WHERE x.pid_branch = b.pid_branch AND x.pid_module = n.pid_module);

INSERT INTO pharma.acl_perm_template_line
       (pid_template_line, pid_template, pid_module,
        can_view, can_create, can_edit, can_delete, can_approve, can_export, can_print)
SELECT nextval('pharma.sq_template_line'), t.pid_template, n.pid_module,
       1, 0, 0, 0, 0, 0, 0
  FROM (SELECT DISTINCT pid_template FROM pharma.acl_perm_template_line) t
 CROSS JOIN (SELECT pid_module FROM pharma.acl_module_info WHERE module_code = 'MOD30') n
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_perm_template_line x
                    WHERE x.pid_template = t.pid_template AND x.pid_module = n.pid_module);

SELECT m.module_code, m.module_name, m.route_path, p.module_name AS parent, m.sort_order,
       (SELECT count(*) FROM pharma.pos_permissions x WHERE x.pid_module = m.pid_module) AS roles,
       (SELECT count(*) FROM pharma.acl_tenant_module x WHERE x.pid_module = m.pid_module) AS pharmacies
  FROM pharma.acl_module_info m
  LEFT JOIN pharma.acl_module_info p ON p.pid_module = m.pid_parent_module
 WHERE m.module_code IN ('MOD24', 'MOD30');
