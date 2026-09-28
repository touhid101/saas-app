/* ─────────────────────────────────────────────────────────────────────────────
   Medicine Catalogue (MOD30), off the menu.

   V57 gave it a route and put it under Master Data. It is not wanted in the
   sidebar, so the route is cleared.

   ── Why clearing the route is the whole change ──

   A module with no route and no children is a capability, not a page: grantable
   on both permission screens, and skipped by PermissionServiceImpl when the menu
   is built. That distinction already exists — Notifications and Global Search
   are the same shape — so nothing new is needed to hide this one, and the RBAC
   tick that controls it keeps working exactly as before.

   Deleting the module would have been the wrong move: the permission rows would
   go with it, and the endpoints behind the screen check MOD30 before they answer.
   The screen stays reachable at #branchcatalogue and stays permission-controlled;
   it simply is not advertised in the navigation.
   ───────────────────────────────────────────────────────────────────────────── */

UPDATE pharma.acl_module_info
   SET route_path = NULL,
       pid_parent_module = NULL,
       sort_order = 14,
       module_desc = 'Browse the shared medicine catalogue and copy entries into this pharmacy''s '
                  || 'own product list. Reached from Product Master rather than the menu.',
       updated_by = 'V58',
       updated_date = CURRENT_TIMESTAMP
 WHERE module_code = 'MOD30';

/* Read back: MOD30 beside the other two capabilities, all three route-less. */
SELECT m.module_code, m.module_name,
       COALESCE(m.route_path, '(no route — off the menu)') AS route_path,
       (SELECT count(*) FROM pharma.acl_module_info c WHERE c.pid_parent_module = m.pid_module) AS children,
       (SELECT count(*) FROM pharma.pos_permissions p
         WHERE p.pid_module = m.pid_module AND p.can_view = 1) AS roles_granted
  FROM pharma.acl_module_info m
 WHERE m.module_code IN ('MOD28', 'MOD29', 'MOD30')
 ORDER BY m.module_code;
