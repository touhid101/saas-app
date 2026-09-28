/* ─────────────────────────────────────────────────────────────────────────────
   MOD30 renamed to "Branch Medicine Catalogue".

   It shared a name with MOD24, the operator's catalogue, which made the RBAC
   permission screens read as two identical rows called "Medicine Catalogue" —
   one of them grantable to a pharmacy and one of them not. Naming the branch
   copy for what it is removes the guess about which row is which.
   ───────────────────────────────────────────────────────────────────────────── */

UPDATE pharma.acl_module_info
   SET module_name  = 'Branch Medicine Catalogue',
       updated_by   = 'V59',
       updated_date = CURRENT_TIMESTAMP
 WHERE module_code = 'MOD30';

SELECT module_code, module_name,
       COALESCE(route_path, '(no route — off the menu)') AS route_path
  FROM pharma.acl_module_info
 WHERE module_code IN ('MOD24', 'MOD30')
 ORDER BY module_code;
