/* ─────────────────────────────────────────────────────────────────────────────
   Org Structure, split out of Organisation & Parties.

   MOD02 carried six unrelated things behind one menu entry — the organisation
   hierarchy, geography, product lists, packaging, staff and trading partners —
   so a role that needed to edit a customer also had to be given the company
   table. Splitting it is what makes those separately grantable.

   What moves: Organisation, Company, and the whole Geography set.
   What stays on MOD02: Branch, Products, Packaging, HR, Customers, Suppliers.

   Branch deliberately stays. It is the pharmacy itself — it carries the logo,
   the address on the invoice, the stock — and it belongs with the things a
   pharmacy is administered by, not with the corporate hierarchy above it.

   ── Permissions ──
   Every role keeps exactly what it had. The new module copies MOD02's grants
   rather than being seeded with a guess, because the alternative is either
   locking people out of a screen they used yesterday or handing it to people
   who never had it. Same for the per-pharmacy plan rows.
   ───────────────────────────────────────────────────────────────────────────── */

/* The sequence first. V38, V39 and V48 each inserted a module with a
   hand-picked id and left SQ_ACL_MODULE behind the table — the same fault V18
   was written to repair, recurring for the same reason. GREATEST so re-running
   can only move it forward; rewinding would hand out ids that already exist. */
SELECT setval('pharma.sq_acl_module',
    GREATEST((SELECT COALESCE(MAX(pid_module), 0) FROM pharma.acl_module_info),
             (SELECT last_value FROM pharma.sq_acl_module)),
    true);

/* The module, directly after Organisation & Parties under Master Data. */
INSERT INTO pharma.acl_module_info
       (pid_module, module_code, module_name, route_path, icon_key,
        pid_parent_module, sort_order, is_active, status, inserted_by)
SELECT nextval('pharma.sq_acl_module'), 'MOD27', 'Org Structure', '/orgstructure', 'orgstructure',
       m.pid_parent_module, 1, 1, 'Y', 'V52'
  FROM pharma.acl_module_info m
 WHERE m.module_code = 'MOD02'
   AND NOT EXISTS (SELECT 1 FROM pharma.acl_module_info x WHERE x.module_code = 'MOD27');

/* Organisation & Parties moves down one, so the pair reads in order. */
UPDATE pharma.acl_module_info SET sort_order = 2 WHERE module_code = 'MOD02';
UPDATE pharma.acl_module_info SET sort_order = 3 WHERE module_code = 'MOD17';

/* Role permissions: whatever a role had on MOD02, it now has on MOD27 too. */
INSERT INTO pharma.pos_permissions
       (pid_permission, pid_role, pid_module,
        can_view, can_create, can_edit, can_delete, can_export, can_approve, can_print,
        status, inserted_by)
SELECT nextval('pharma.sq_pos_perm'), p.pid_role, n.pid_module,
       p.can_view, p.can_create, p.can_edit, p.can_delete, p.can_export, p.can_approve,
       COALESCE(p.can_print, 0), 'Y', 'V52'
  FROM pharma.pos_permissions p
  JOIN pharma.acl_module_info o ON o.pid_module = p.pid_module AND o.module_code = 'MOD02'
  CROSS JOIN (SELECT pid_module FROM pharma.acl_module_info WHERE module_code = 'MOD27') n
 WHERE NOT EXISTS (SELECT 1 FROM pharma.pos_permissions x
                    WHERE x.pid_role = p.pid_role AND x.pid_module = n.pid_module);

/* Per-pharmacy plan rows: a pharmacy that had the old screen has the new one,
   with the same rights. Without this the module is absent from their plan and
   PermissionResolver drops it outright, whatever their roles allow. */
INSERT INTO pharma.acl_tenant_module
       (pid_tenant_module, pid_branch, pid_module,
        can_view, can_create, can_edit, can_delete, can_approve, can_export, can_print,
        status, inserted_by)
SELECT nextval('pharma.sq_tenant_module'), t.pid_branch, n.pid_module,
       t.can_view, t.can_create, t.can_edit, t.can_delete, t.can_approve, t.can_export, t.can_print,
       'Y', 'V52'
  FROM pharma.acl_tenant_module t
  JOIN pharma.acl_module_info o ON o.pid_module = t.pid_module AND o.module_code = 'MOD02'
  CROSS JOIN (SELECT pid_module FROM pharma.acl_module_info WHERE module_code = 'MOD27') n
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_tenant_module x
                    WHERE x.pid_branch = t.pid_branch AND x.pid_module = n.pid_module);

/* Templates too, so a pharmacy activated tomorrow gets the same pair. */
INSERT INTO pharma.acl_perm_template_line
       (pid_template_line, pid_template, pid_module,
        can_view, can_create, can_edit, can_delete, can_approve, can_export, can_print)
SELECT nextval('pharma.sq_template_line'), l.pid_template, n.pid_module,
       l.can_view, l.can_create, l.can_edit, l.can_delete, l.can_approve, l.can_export, l.can_print
  FROM pharma.acl_perm_template_line l
  JOIN pharma.acl_module_info o ON o.pid_module = l.pid_module AND o.module_code = 'MOD02'
  CROSS JOIN (SELECT pid_module FROM pharma.acl_module_info WHERE module_code = 'MOD27') n
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_perm_template_line x
                    WHERE x.pid_template = l.pid_template AND x.pid_module = n.pid_module);

COMMIT;

SELECT m.module_code, m.module_name, m.route_path, m.sort_order,
       (SELECT count(*) FROM pharma.pos_permissions p WHERE p.pid_module = m.pid_module) AS roles,
       (SELECT count(*) FROM pharma.acl_tenant_module t WHERE t.pid_module = m.pid_module) AS pharmacies
  FROM pharma.acl_module_info m
 WHERE m.module_code IN ('MOD02', 'MOD27', 'MOD17')
 ORDER BY m.sort_order;
