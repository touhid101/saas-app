-- The platform console needs a way in.
--
-- Super Admin only, like the Pharmacies console and the catalogue. Export and
-- print are granted because the numbers on it are the ones somebody takes into a
-- board meeting; nothing on it can be created or edited, so those stay off.
\set ON_ERROR_STOP on

BEGIN;

INSERT INTO pharma.acl_module_info
       (pid_module, module_code, module_name, module_desc, route_path, icon_key, sort_order, is_active, status, inserted_by)
VALUES (29, 'MOD25', 'Platform', 'Revenue, renewals and who has stopped trading', '/platform', 'chart', 97, 1, 'Y', 'V39')
ON CONFLICT (pid_module) DO NOTHING;

INSERT INTO pharma.pos_permissions
       (pid_permission, pid_role, pid_module, can_view, can_create, can_edit, can_delete, can_approve, can_export, can_print, status, inserted_by)
SELECT nextval('pharma.sq_pos_perm'), g.pid_group, 29, 1, 0, 0, 0, 0, 1, 1, 'Y', 'V39'
  FROM pharma.acl_group g
 WHERE g.role_code = 'ROLE01'
   AND NOT EXISTS (SELECT 1 FROM pharma.pos_permissions p
                    WHERE p.pid_role = g.pid_group AND p.pid_module = 29);

COMMIT;

SELECT m.module_code, m.module_name, m.route_path, count(p.pid_permission) AS roles_granted
  FROM pharma.acl_module_info m
  LEFT JOIN pharma.pos_permissions p ON p.pid_module = m.pid_module AND p.can_view = 1
 WHERE m.pid_module IN (26, 27, 28, 29)
 GROUP BY m.pid_module, m.module_code, m.module_name, m.route_path
 ORDER BY m.pid_module;
