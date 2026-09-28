-- The catalogue screen needs a way in.
--
-- Super Admin only, like the Pharmacies console beside it: no tenant curates the
-- platform's medicine list. Deliberately absent from every permission template
-- for the same reason — a template describes what a pharmacy has, and this is
-- not something a pharmacy can be given.
\set ON_ERROR_STOP on

BEGIN;

INSERT INTO pharma.acl_module_info
       (pid_module, module_code, module_name, module_desc, route_path, icon_key, sort_order, is_active, status, inserted_by)
VALUES (28, 'MOD24', 'Medicine Catalogue', 'The shared list pharmacies import from', '/catalogue', 'pill', 96, 1, 'Y', 'V38')
ON CONFLICT (pid_module) DO NOTHING;

INSERT INTO pharma.pos_permissions
       (pid_permission, pid_role, pid_module, can_view, can_create, can_edit, can_delete, can_approve, can_export, can_print, status, inserted_by)
SELECT nextval('pharma.sq_pos_perm'), g.pid_group, 28, 1, 1, 1, 1, 1, 1, 1, 'Y', 'V38'
  FROM pharma.acl_group g
 WHERE g.role_code = 'ROLE01'
   AND NOT EXISTS (SELECT 1 FROM pharma.pos_permissions p
                    WHERE p.pid_role = g.pid_group AND p.pid_module = 28);

COMMIT;

SELECT m.module_code, m.module_name, m.route_path, count(p.pid_permission) AS roles_granted
  FROM pharma.acl_module_info m
  LEFT JOIN pharma.pos_permissions p ON p.pid_module = m.pid_module AND p.can_view = 1
 WHERE m.pid_module = 28
 GROUP BY m.pid_module, m.module_code, m.module_name, m.route_path;
