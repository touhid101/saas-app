-- The two screens built in Phases 2 and 3 need a way in.
--
-- The sidebar is built from ACL_MODULE_INFO, so a page that is routed but has no
-- module row works only for somebody who types the route. Both screens existed
-- and neither was reachable by clicking.
--
-- Subscription goes to every role, because a pharmacy that cannot find its own
-- renewal page is a pharmacy that phones you instead. Pharmacies goes to the
-- Super Admin alone.
\set ON_ERROR_STOP on

BEGIN;

INSERT INTO pharma.acl_module_info
       (pid_module, module_code, module_name, module_desc, route_path, icon_key, sort_order, is_active, status, inserted_by)
VALUES
  (26, 'MOD22', 'Subscription', 'Your plan, renewal and payment history', '/subscription', 'card', 90, 1, 'Y', 'V36'),
  (27, 'MOD23', 'Pharmacies',   'Applications, subscriptions and access', '/tenants',      'shop', 95, 1, 'Y', 'V36')
ON CONFLICT (pid_module) DO NOTHING;

/* Subscription: visible to everyone, editable by nobody — renewing is a button
   on the page, not an edit right, and view is what puts it in the menu. */
INSERT INTO pharma.pos_permissions
       (pid_permission, pid_role, pid_module, can_view, can_create, can_edit, can_delete, can_approve, can_export, can_print, status, inserted_by)
SELECT nextval('pharma.sq_pos_perm'), g.pid_group, 26, 1, 0, 0, 0, 0, 0, 1, 'Y', 'V36'
  FROM pharma.acl_group g
 WHERE NOT EXISTS (SELECT 1 FROM pharma.pos_permissions p
                    WHERE p.pid_role = g.pid_group AND p.pid_module = 26);

/* Pharmacies: Super Admin only. */
INSERT INTO pharma.pos_permissions
       (pid_permission, pid_role, pid_module, can_view, can_create, can_edit, can_delete, can_approve, can_export, can_print, status, inserted_by)
SELECT nextval('pharma.sq_pos_perm'), g.pid_group, 27, 1, 1, 1, 1, 1, 1, 1, 'Y', 'V36'
  FROM pharma.acl_group g
 WHERE g.role_code = 'ROLE01'
   AND NOT EXISTS (SELECT 1 FROM pharma.pos_permissions p
                    WHERE p.pid_role = g.pid_group AND p.pid_module = 27);

/* Every existing template gains Subscription, for the same reason every role
   does. Pharmacies is deliberately absent: no tenant administers the platform. */
INSERT INTO pharma.acl_perm_template_line
       (pid_template_line, pid_template, pid_module, can_view, can_create, can_edit, can_delete, can_approve, can_export, can_print)
SELECT nextval('pharma.sq_template_line'), t.pid_template, 26, 1, 0, 0, 0, 0, 0, 1
  FROM pharma.acl_perm_template t
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_perm_template_line l
                    WHERE l.pid_template = t.pid_template AND l.pid_module = 26);

/* Branches already running were granted from a template before these rows
   existed, so they need the same addition or their menu loses the page. */
INSERT INTO pharma.acl_tenant_module
       (pid_tenant_module, pid_branch, pid_module, can_view, can_create, can_edit, can_delete, can_approve, can_export, can_print, status, inserted_by)
SELECT nextval('pharma.sq_tenant_module'), b.pid_branch, 26, 1, 0, 0, 0, 0, 0, 1, 'Y', 'V36'
  FROM (SELECT DISTINCT pid_branch FROM pharma.acl_tenant_module) b
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_tenant_module m
                    WHERE m.pid_branch = b.pid_branch AND m.pid_module = 26);

COMMIT;

SELECT m.module_code, m.module_name, m.route_path,
       count(p.pid_permission) AS roles_granted
  FROM pharma.acl_module_info m
  LEFT JOIN pharma.pos_permissions p ON p.pid_module = m.pid_module AND p.can_view = 1
 WHERE m.pid_module IN (26, 27)
 GROUP BY m.pid_module, m.module_code, m.module_name, m.route_path
 ORDER BY m.pid_module;
