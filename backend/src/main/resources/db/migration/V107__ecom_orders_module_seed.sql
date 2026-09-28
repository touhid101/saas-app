/* ─────────────────────────────────────────────────────────────────────────────
   V107 — The branch's E-Commerce Orders board.

   V101 registered the group and the customer console and said MOD53 was
   reserved for this screen; here it is, in the place that was left for it.

     GRP_ECOM   E-Commerce                                     (exists, V101)
       MOD53    E-Commerce Orders        /ecomorders           sort 1
       MOD55    E-Commerce Customers     /ecomcustomers        sort 3  (V101)

   Unlike MOD55, this is a BRANCH screen. An online order belongs to the
   pharmacy that will fill it, so the rights mirror MOD06 (Sales Activity),
   which is the board it sits beside:

     ROLE01 Super Admin              view, edit, approve, export, print
     ROLE02 Administrator (Branch)   view, edit, approve, export
     ROLE09 Sales Manager            view, edit
     ROLE04 Accounts                 view
     ROLE06 Delivery Staff           — nothing. A driver works from Delivery,
                                       which is already scoped to their round.

   Seeded wherever missing, idempotent, nothing overwritten: production was
   baselined at 63 and has never received a seed row from an earlier migration.
   ───────────────────────────────────────────────────────────────────────────── */

CREATE SEQUENCE IF NOT EXISTS pharma.sq_acl_module;
CREATE SEQUENCE IF NOT EXISTS pharma.sq_pos_perm;

SELECT setval('pharma.sq_acl_module',
    GREATEST((SELECT COALESCE(MAX(pid_module), 0) FROM pharma.acl_module_info),
             (SELECT last_value FROM pharma.sq_acl_module)), true);
SELECT setval('pharma.sq_pos_perm',
    GREATEST((SELECT COALESCE(MAX(pid_permission), 0) FROM pharma.pos_permissions),
             (SELECT last_value FROM pharma.sq_pos_perm)), true);

/* The group is V101's; created here only if that migration never ran. */
INSERT INTO pharma.acl_module_info
       (pid_module, module_code, module_name, module_desc, route_path, icon_key,
        pid_parent_module, sort_order, is_active, status, inserted_by)
SELECT nextval('pharma.sq_acl_module'), 'GRP_ECOM', 'E-Commerce',
       'Online customers, orders and promotions across every pharmacy',
       NULL, 'ecom', NULL, 12, 1, 'Y', 'V107'
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_module_info WHERE module_code = 'GRP_ECOM');

INSERT INTO pharma.acl_module_info
       (pid_module, module_code, module_name, module_desc, route_path, icon_key,
        pid_parent_module, sort_order, is_active, status, inserted_by)
SELECT nextval('pharma.sq_acl_module'), 'MOD53', 'E-Commerce Orders',
       'Online orders for this pharmacy: confirm, prepare, dispatch, deliver or cancel',
       '/ecomorders', 'ecomorders',
       (SELECT p.pid_module FROM pharma.acl_module_info p WHERE p.module_code = 'GRP_ECOM'),
       1, 1, 'Y', 'V107'
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_module_info WHERE module_code = 'MOD53');

/* Repair, never replace (as V99 and V101): a module present without its parent gets one. */
UPDATE pharma.acl_module_info m
   SET pid_parent_module = (SELECT p.pid_module FROM pharma.acl_module_info p WHERE p.module_code = 'GRP_ECOM'),
       updated_by = 'V107', updated_date = CURRENT_TIMESTAMP
 WHERE m.module_code = 'MOD53'
   AND m.pid_parent_module IS NULL;

/* Matrix rows, only where the role has none for this module. */
CREATE TEMP TABLE ecom_order_rights (
    role_code varchar(20),
    can_view int, can_create int, can_edit int, can_delete int,
    can_export int, can_approve int, can_print int
) ON COMMIT DROP;

INSERT INTO ecom_order_rights VALUES
    ('ROLE01', 1, 0, 1, 0, 1, 1, 1),
    ('ROLE02', 1, 0, 1, 0, 1, 1, 0),
    ('ROLE09', 1, 0, 1, 0, 0, 0, 0),
    ('ROLE04', 1, 0, 0, 0, 0, 0, 0);

INSERT INTO pharma.pos_permissions
       (pid_permission, pid_role, pid_module,
        can_view, can_create, can_edit, can_delete, can_export, can_approve, can_print,
        status, inserted_by)
SELECT nextval('pharma.sq_pos_perm'), g.pid_group, m.pid_module,
       r.can_view, r.can_create, r.can_edit, r.can_delete, r.can_export, r.can_approve, r.can_print,
       'Y', 'V107'
  FROM ecom_order_rights r
  JOIN pharma.acl_group g ON g.role_code = r.role_code
  JOIN pharma.acl_module_info m ON m.module_code = 'MOD53'
 WHERE NOT EXISTS (SELECT 1 FROM pharma.pos_permissions x
                    WHERE x.pid_role = g.pid_group AND x.pid_module = m.pid_module);

/* ═══ Verification ══════════════════════════════════════════════════════════ */
SELECT (SELECT count(*) FROM pharma.acl_module_info WHERE module_code = 'MOD53')                       AS module_present,
       (SELECT count(*) FROM pharma.acl_module_info WHERE module_code = 'MOD53' AND pid_parent_module IS NULL) AS without_parent,
       (SELECT count(*) FROM pharma.pos_permissions p
          JOIN pharma.acl_module_info m ON m.pid_module = p.pid_module AND m.module_code = 'MOD53')    AS matrix_rows;
