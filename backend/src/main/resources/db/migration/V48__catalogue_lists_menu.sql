-- The catalogue's master lists had no way in.
--
-- V47 turned the catalogue's manufacturers, generics, categories, sub-categories,
-- units and packaging into real lists, and the catalogue form started choosing
-- from them instead of typing free text. But nothing on the menu managed the
-- lists themselves, so a manufacturer that was not already in use could only be
-- added by calling the API directly.
--
-- Super Admin only, beside the catalogue it belongs to. Create, edit and delete
-- are granted because curating these lists is the entire purpose of the screen;
-- delete retires rather than removes, and the service refuses while catalogue
-- entries still point at the entry.
\set ON_ERROR_STOP on

BEGIN;

INSERT INTO pharma.acl_module_info
       (pid_module, module_code, module_name, module_desc, route_path, icon_key,
        sort_order, is_active, status, inserted_by)
VALUES (30, 'MOD26', 'Catalogue Lists',
        'Manufacturers, generics, categories, units and packaging the catalogue is built from',
        '/cataloguelists', 'list', 96, 1, 'Y', 'V48')
ON CONFLICT (pid_module) DO NOTHING;

INSERT INTO pharma.pos_permissions
       (pid_permission, pid_role, pid_module, can_view, can_create, can_edit,
        can_delete, can_approve, can_export, can_print, status, inserted_by)
SELECT nextval('pharma.sq_pos_perm'), g.pid_group, 30, 1, 1, 1, 1, 0, 0, 0, 'Y', 'V48'
  FROM pharma.acl_group g
 WHERE g.role_code = 'ROLE01'
   AND NOT EXISTS (SELECT 1 FROM pharma.pos_permissions p
                    WHERE p.pid_role = g.pid_group AND p.pid_module = 30);

COMMIT;

SELECT m.pid_module, m.module_code, m.module_name, m.route_path,
       count(p.pid_permission) AS roles_granted
  FROM pharma.acl_module_info m
  LEFT JOIN pharma.pos_permissions p ON p.pid_module = m.pid_module AND p.can_view = 1
 WHERE m.pid_module IN (26, 27, 28, 29, 30)
 GROUP BY m.pid_module, m.module_code, m.module_name, m.route_path
 ORDER BY m.pid_module;
