/* ─────────────────────────────────────────────────────────────────────────────
   Global Search, as a grantable capability.

   The search box on the topbar was ungated — every role had it — and what it
   searched was mockData.js: the PRODUCTS, CUSTOMERS, SUPPLIERS, SALES,
   PURCHASES and REQUISITIONS seed arrays, filtered in the browser. It never
   called the server. Typing a real customer's name found nothing, and typing a
   seed name found a record that does not exist in this database.

   A /api/v1/search endpoint did exist, and could not have worked: its SQL names
   POS_TRANSACTION_MST.PID_TRANSACTION, TRAN_TYPE='SALE' and PID_CUSTOMER, none
   of which are columns here, and POS_SUPPLIER_INFO.SUPPLIER_CODE, which does not
   exist at all. Every query was wrapped in a catch that returned an empty list,
   so it reported "no results" rather than failing.

   ── The second module of its shape ──

   Like MOD28 Notifications, this has no ROUTE_PATH: search is a capability, not
   a page. ModuleShape keeps it out of the sidebar and grantable on both
   permission screens. See V54 for why that distinction had to be settled.

   ── Who gets it ──

   ROLE01 and ROLE02, as asked. Every other role loses a search box that never
   returned a true result anyway, and gets a working one back with a single tick.

   Each entity searched is separately gated by the View right on the module that
   owns it — products by Product Master, customers and suppliers by Organisation
   & Parties, invoices by Sales, purchases by Purchase, requisitions by
   Requisition. Granting search therefore widens nobody's reach: it is a faster
   way to reach records they could already open, not a way to reach new ones.
   ───────────────────────────────────────────────────────────────────────────── */

SELECT setval('pharma.sq_acl_module',
    GREATEST((SELECT COALESCE(MAX(pid_module), 0) FROM pharma.acl_module_info),
             (SELECT last_value FROM pharma.sq_acl_module)),
    true);

INSERT INTO pharma.acl_module_info
       (pid_module, module_code, module_name, module_desc, route_path, icon_key,
        pid_parent_module, sort_order, is_active, status, inserted_by)
SELECT nextval('pharma.sq_acl_module'), 'MOD29', 'Global Search',
       'The search box on the topbar. Each kind of record it returns is '
       || 'additionally gated by the View right on the module that owns it.',
       NULL, 'search', NULL, 13, 1, 'Y', 'V55'
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_module_info x WHERE x.module_code = 'MOD29');

/* View only: searching creates, changes and deletes nothing. */
INSERT INTO pharma.pos_permissions
       (pid_permission, pid_role, pid_module,
        can_view, can_create, can_edit, can_delete, can_export, can_approve, can_print,
        status, inserted_by)
SELECT nextval('pharma.sq_pos_perm'), g.pid_group, n.pid_module,
       1, 0, 0, 0, 0, 0, 0, 'Y', 'V55'
  FROM pharma.acl_group g
 CROSS JOIN (SELECT pid_module FROM pharma.acl_module_info WHERE module_code = 'MOD29') n
 WHERE g.role_code IN ('ROLE01', 'ROLE02')
   AND NOT EXISTS (SELECT 1 FROM pharma.pos_permissions x
                    WHERE x.pid_role = g.pid_group AND x.pid_module = n.pid_module);

/* Every pharmacy's plan carries it, or PermissionResolver drops the module
   whatever the roles allow — the plan is a ceiling, not a default. */
INSERT INTO pharma.acl_tenant_module
       (pid_tenant_module, pid_branch, pid_module,
        can_view, can_create, can_edit, can_delete, can_approve, can_export, can_print,
        status, inserted_by)
SELECT nextval('pharma.sq_tenant_module'), b.pid_branch, n.pid_module,
       1, 0, 0, 0, 0, 0, 0, 'Y', 'V55'
  FROM (SELECT DISTINCT pid_branch FROM pharma.acl_tenant_module) b
 CROSS JOIN (SELECT pid_module FROM pharma.acl_module_info WHERE module_code = 'MOD29') n
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_tenant_module x
                    WHERE x.pid_branch = b.pid_branch AND x.pid_module = n.pid_module);

INSERT INTO pharma.acl_perm_template_line
       (pid_template_line, pid_template, pid_module,
        can_view, can_create, can_edit, can_delete, can_approve, can_export, can_print)
SELECT nextval('pharma.sq_template_line'), t.pid_template, n.pid_module,
       1, 0, 0, 0, 0, 0, 0
  FROM (SELECT DISTINCT pid_template FROM pharma.acl_perm_template_line) t
 CROSS JOIN (SELECT pid_module FROM pharma.acl_module_info WHERE module_code = 'MOD29') n
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_perm_template_line x
                    WHERE x.pid_template = t.pid_template AND x.pid_module = n.pid_module);

/* Search leans on the same columns the LIKE filters hit. Without these each
   keystroke is a sequential scan of every product and invoice in the branch.
   text_pattern_ops because the predicate is UPPER(col) LIKE 'ABC%' — a plain
   btree cannot serve a prefix LIKE under the C collation rules. */
CREATE INDEX IF NOT EXISTS ix_product_name_search
    ON pharma.pos_product_info (pid_branch, UPPER(product_name) text_pattern_ops);
CREATE INDEX IF NOT EXISTS ix_customer_name_search
    ON pharma.pos_customer_info (pid_branch, UPPER(customer_name) text_pattern_ops);
CREATE INDEX IF NOT EXISTS ix_supplier_name_search
    ON pharma.pos_supplier_info (pid_branch, UPPER(supplier_name) text_pattern_ops);
CREATE INDEX IF NOT EXISTS ix_tran_invoice_search
    ON pharma.pos_transaction_mst (pid_branch, transaction_type, UPPER(invoice_no) text_pattern_ops);

SELECT g.role_code, g.group_name, COALESCE(p.can_view, 0) AS search
  FROM pharma.acl_group g
  LEFT JOIN pharma.acl_module_info m ON m.module_code = 'MOD29'
  LEFT JOIN pharma.pos_permissions p ON p.pid_role = g.pid_group AND p.pid_module = m.pid_module
 ORDER BY g.role_code;
