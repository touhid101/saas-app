/* ─────────────────────────────────────────────────────────────────────────────
   V101 — E-commerce: the menu group and the customer console module.

   Companion to V100. Registers the staff screen that approves online
   customers and sets their tier, the way V99 registered Accounts: seeded
   wherever missing, idempotent, nothing overwritten.

     GRP_ECOM   E-Commerce                                     sort 12
       MOD55    E-Commerce Customers     /ecomcustomers        sort 3

   MOD53 (E-Commerce Orders) and MOD54 (E-Commerce Promotions) are reserved for
   later phases and are NOT created here; sort 3 leaves them their places.

   Rights: ROLE01 only - view, edit, approve, export. The customer is global,
   so deciding on one is a platform action, and the service additionally
   requires the caller to be a platform operator (ROLE01 in the platform
   organisation) whatever the matrix says. No plan template carries the
   module, exactly as MOD25 (Platform) is handled: a pharmacy narrowed to its
   plan never sees it, and one that is not narrowed sees it only with ROLE01.
   ───────────────────────────────────────────────────────────────────────────── */

CREATE SEQUENCE IF NOT EXISTS pharma.sq_acl_module;
CREATE SEQUENCE IF NOT EXISTS pharma.sq_pos_perm;

SELECT setval('pharma.sq_acl_module',
    GREATEST((SELECT COALESCE(MAX(pid_module), 0) FROM pharma.acl_module_info),
             (SELECT last_value FROM pharma.sq_acl_module)), true);
SELECT setval('pharma.sq_pos_perm',
    GREATEST((SELECT COALESCE(MAX(pid_permission), 0) FROM pharma.pos_permissions),
             (SELECT last_value FROM pharma.sq_pos_perm)), true);

CREATE TEMP TABLE ecom_seed_module (
    module_code varchar(20), module_name varchar(100), module_desc text,
    route_path varchar(100), icon_key varchar(50), parent_code varchar(20),
    sort_order integer
) ON COMMIT DROP;

INSERT INTO ecom_seed_module VALUES
    ('GRP_ECOM', 'E-Commerce',           'Online customers, orders and promotions across every pharmacy', '',                'ecom',          '',         12),
    ('MOD55',    'E-Commerce Customers', 'Online customer registrations: approve, reject, suspend, and set the pricing tier', '/ecomcustomers', 'ecomcustomers', 'GRP_ECOM', 3);

INSERT INTO pharma.acl_module_info
       (pid_module, module_code, module_name, module_desc, route_path, icon_key,
        pid_parent_module, sort_order, is_active, status, inserted_by)
SELECT nextval('pharma.sq_acl_module'), s.module_code, s.module_name, NULLIF(s.module_desc, ''),
       NULLIF(s.route_path, ''), NULLIF(s.icon_key, ''), NULL, s.sort_order, 1, 'Y', 'V101'
  FROM ecom_seed_module s
 WHERE s.parent_code = ''
   AND NOT EXISTS (SELECT 1 FROM pharma.acl_module_info x WHERE x.module_code = s.module_code);

INSERT INTO pharma.acl_module_info
       (pid_module, module_code, module_name, module_desc, route_path, icon_key,
        pid_parent_module, sort_order, is_active, status, inserted_by)
SELECT nextval('pharma.sq_acl_module'), s.module_code, s.module_name, NULLIF(s.module_desc, ''),
       NULLIF(s.route_path, ''), NULLIF(s.icon_key, ''),
       (SELECT p.pid_module FROM pharma.acl_module_info p WHERE p.module_code = s.parent_code),
       s.sort_order, 1, 'Y', 'V101'
  FROM ecom_seed_module s
 WHERE s.parent_code <> ''
   AND NOT EXISTS (SELECT 1 FROM pharma.acl_module_info x WHERE x.module_code = s.module_code);

/* Repair, never replace (as V99): a module present without its parent gets one. */
UPDATE pharma.acl_module_info m
   SET pid_parent_module = (SELECT p.pid_module FROM pharma.acl_module_info p WHERE p.module_code = s.parent_code),
       updated_by = 'V101', updated_date = CURRENT_TIMESTAMP
  FROM ecom_seed_module s
 WHERE s.module_code = m.module_code
   AND s.parent_code <> ''
   AND m.pid_parent_module IS NULL;

/* The matrix row for ROLE01, only where none exists. */
INSERT INTO pharma.pos_permissions
       (pid_permission, pid_role, pid_module,
        can_view, can_create, can_edit, can_delete, can_export, can_approve, can_print,
        status, inserted_by)
SELECT nextval('pharma.sq_pos_perm'), g.pid_group, m.pid_module,
       1, 0, 1, 0, 1, 1, 0, 'Y', 'V101'
  FROM pharma.acl_group g
  JOIN pharma.acl_module_info m ON m.module_code = 'MOD55'
 WHERE g.role_code = 'ROLE01'
   AND NOT EXISTS (SELECT 1 FROM pharma.pos_permissions x WHERE x.pid_role = g.pid_group AND x.pid_module = m.pid_module);

/* ═══ Verification ══════════════════════════════════════════════════════════ */
SELECT (SELECT count(*) FROM pharma.acl_module_info WHERE module_code IN ('GRP_ECOM', 'MOD55'))       AS modules_present,
       (SELECT count(*) FROM pharma.acl_module_info WHERE module_code = 'MOD55' AND pid_parent_module IS NULL) AS children_without_parent,
       (SELECT count(*) FROM pharma.pos_permissions p
          JOIN pharma.acl_group g ON g.pid_group = p.pid_role AND g.role_code = 'ROLE01'
          JOIN pharma.acl_module_info m ON m.pid_module = p.pid_module AND m.module_code = 'MOD55')  AS role01_rows;
