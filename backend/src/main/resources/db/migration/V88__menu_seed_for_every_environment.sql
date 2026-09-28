/* ─────────────────────────────────────────────────────────────────────────────
   The menu, the roles, the permission matrix and the plan templates — seeded
   wherever they are missing.

   Flyway is baselined at 63. Every migration at or below it is recorded as
   applied and never runs, on every environment, for ever. The tables those
   files created exist wherever the schema was built by hand, but the rows they
   inserted do not: V33, V36, V38, V39, V48, V52, V54, V55, V57 and V59 seeded
   MOD01-MOD30 and the menu groups, and on production none of that ran. What
   ran was V64 onward - MOD31 and the HR modules - each hanging its rows off a
   parent group that, there, may not exist.

   This file is the whole menu as the code expects it, written so that it is
   a no-op where a row already exists and a complete seed where nothing does:

     1  the groups (parents first), then every module, keyed by MODULE_CODE
     2  a repair for rows that exist but hang from nothing - parent, route,
        icon and sort are filled in only where they are NULL, never replaced
     3  the nine roles, keyed by ROLE_CODE
     4  the role x module permission matrix, exactly as development holds it,
        one row per pair where none exists
     5  the three plan templates and their lines
     6  plan ceilings for a branch that already has plan rows, taken from
        the template of its own live plan and only for modules this file
        inserted - a narrowed branch stays exactly as narrow as its plan;
        a branch with no plan rows is unrestricted (V33) and is left so

   Every id comes from nextval(), never a literal - the trap that caught V64 on
   production was seeding with explicit ids and leaving the sequence behind.
   The six sequences are created if absent and realigned first, as V86 and V87 do.

   Nothing is deleted, renamed or overwritten. A module a pharmacy has renamed,
   a role it has re-coloured, a permission it has changed: all untouched.
   Generated from the development database on 2026-09-12.
   ───────────────────────────────────────────────────────────────────────────── */

CREATE SEQUENCE IF NOT EXISTS pharma.sq_acl_module;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_acl_group;
CREATE SEQUENCE IF NOT EXISTS pharma.sq_pos_perm;
CREATE SEQUENCE IF NOT EXISTS pharma.sq_tenant_module;
CREATE SEQUENCE IF NOT EXISTS pharma.sq_perm_template;
CREATE SEQUENCE IF NOT EXISTS pharma.sq_template_line;

SELECT setval('pharma.sq_acl_module',
    GREATEST((SELECT COALESCE(MAX(pid_module), 0) FROM pharma.acl_module_info),
             (SELECT last_value FROM pharma.sq_acl_module)), true);
SELECT setval('pharma.seq_acl_group',
    GREATEST((SELECT COALESCE(MAX(pid_group), 0) FROM pharma.acl_group),
             (SELECT last_value FROM pharma.seq_acl_group)), true);
SELECT setval('pharma.sq_pos_perm',
    GREATEST((SELECT COALESCE(MAX(pid_permission), 0) FROM pharma.pos_permissions),
             (SELECT last_value FROM pharma.sq_pos_perm)), true);
SELECT setval('pharma.sq_tenant_module',
    GREATEST((SELECT COALESCE(MAX(pid_tenant_module), 0) FROM pharma.acl_tenant_module),
             (SELECT last_value FROM pharma.sq_tenant_module)), true);
SELECT setval('pharma.sq_perm_template',
    GREATEST((SELECT COALESCE(MAX(pid_template), 0) FROM pharma.acl_perm_template),
             (SELECT last_value FROM pharma.sq_perm_template)), true);
SELECT setval('pharma.sq_template_line',
    GREATEST((SELECT COALESCE(MAX(pid_template_line), 0) FROM pharma.acl_perm_template_line),
             (SELECT last_value FROM pharma.sq_template_line)), true);

/* ═══ 1 · The menu ══════════════════════════════════════════════════════════
   One temporary table holds the seed for the rest of the file. TEMP, so it
   vanishes with the session and leaves nothing behind. */

CREATE TEMP TABLE seed_module (
    module_code varchar(20), module_name varchar(100), module_desc text,
    route_path varchar(100), icon_key varchar(50), parent_code varchar(20),
    sort_order integer, is_active integer
) ON COMMIT DROP;

INSERT INTO seed_module VALUES
    ('GRP_ADMINISTRATION', 'Administration', '', '', 'activity', '', 11, 1),
    ('GRP_DELIVERY', 'Delivery', 'Dispatch, tracking and collection', '', 'delivery', '', 5, 1),
    ('GRP_HR', 'Human Resources', 'Staff, attendance, leave and payroll', '', 'employees', '', 15, 1),
    ('GRP_MASTER', 'Master Data', 'Organisation, parties and product setup', '', 'master', '', 2, 1),
    ('GRP_PURCHASE', 'Purchase', 'Buying, and goods going back to suppliers', '', 'purchase', '', 3, 1),
    ('GRP_SALES', 'Sales', 'Selling, returns and sales analysis', '', 'sales', '', 4, 1),
    ('MOD01', 'Dashboard', '', '/dashboard', 'dashboard', '', 1, 1),
    ('MOD02', 'Organisation & Parties', '', '/master', 'database', 'GRP_MASTER', 2, 1),
    ('MOD03', 'Purchase Invoice', '', '/purchase', 'purchase', 'GRP_PURCHASE', 1, 1),
    ('MOD04', 'Sales Invoice', '', '/sales', 'sales', 'GRP_SALES', 1, 1),
    ('MOD05', 'Quick Sales / POS', '', '/quicksales', 'quicksales', 'GRP_SALES', 2, 1),
    ('MOD06', 'Sales Activity', '', '/activity', 'activity', 'GRP_SALES', 3, 1),
    ('MOD07', 'Sales Performance', '', '/salesperformance', 'trending', 'GRP_SALES', 6, 1),
    ('MOD08', 'Sales Discount%', '', '/salesdiscount', 'percent', 'GRP_SALES', 5, 1),
    ('MOD09', 'Delivery Tracking', '', '/delivery', 'delivery', 'GRP_DELIVERY', 1, 1),
    ('MOD10', 'Collection', '', '/collection', 'wallet', 'GRP_DELIVERY', 2, 1),
    ('MOD11', 'Requisition', '', '/requisition', 'requisition', '', 6, 1),
    ('MOD12', 'Invoice Merge', '', '/merge', 'sales', '', 7, 1),
    ('MOD13', 'Inventory', '', '/inventory', 'inventory', '', 8, 1),
    ('MOD14', 'Reports', '', '/reports', 'reports', '', 9, 1),
    ('MOD15', 'AI Copilot', '', '/ai', 'ai', '', 10, 1),
    ('MOD16', 'RBAC / Admin', '', '/rbac', 'rbac', 'GRP_ADMINISTRATION', 11, 1),
    ('MOD17', 'Product Master', 'Pharmaceutical product catalogue', '/productmaster', 'productmaster', 'GRP_MASTER', 3, 1),
    ('MOD18', 'Sales Return', 'Goods returned against a sales invoice, back into the batch they were sold from', '/salesreturn', 'undo', 'GRP_SALES', 4, 1),
    ('MOD20', 'Purchase Return', 'Goods returned to the supplier, out of the batch they were received into', '/purchasereturn', 'purchasereturn', 'GRP_PURCHASE', 2, 1),
    ('MOD21', 'Supplier Payment', 'Payments to suppliers, outstanding balances, ageing and performance', '/supplierpayment', 'supplierpayment', 'GRP_PURCHASE', 3, 1),
    ('MOD22', 'Subscription', 'Your plan, renewal and payment history', '/subscription', 'activity', 'GRP_ADMINISTRATION', 90, 1),
    ('MOD23', 'Pharmacies', 'Applications, subscriptions and access', '/tenants', 'heart', 'GRP_ADMINISTRATION', 95, 1),
    ('MOD24', 'Medicine Catalogue', 'The shared list pharmacies import from', '/catalogue', 'pill', 'GRP_ADMINISTRATION', 96, 1),
    ('MOD25', 'Platform', 'Revenue, renewals and who has stopped trading', '/platform', 'chart', 'GRP_ADMINISTRATION', 97, 1),
    ('MOD26', 'Catalogue Lists', 'Manufacturers, generics, categories, units and packaging the catalogue is built from', '/cataloguelists', 'settings', 'GRP_ADMINISTRATION', 96, 1),
    ('MOD27', 'Org Structure', '', '/orgstructure', 'orgstructure', 'GRP_MASTER', 1, 1),
    ('MOD28', 'Notifications', 'The alert bell on the topbar. Each alert inside it is additionally gated by the View right on the module it links to.', '', 'bell', '', 12, 1),
    ('MOD29', 'Global Search', 'The search box on the topbar. Each kind of record it returns is additionally gated by the View right on the module that owns it.', '', 'search', '', 13, 1),
    ('MOD30', 'Branch Medicine Catalogue', 'Browse the shared medicine catalogue and copy entries into this pharmacy''s own product list. Reached from Product Master rather than the menu.', '', 'catalogue', '', 14, 1),
    ('MOD31', 'Branch Access Control', 'The people at this pharmacy and what each of them may do. Roles and modules are the platform''s and are read-only here; permissions cannot exceed the module grant the pharmacy''s plan carries.', '/branchrbac', 'shield', 'GRP_ADMINISTRATION', 12, 1),
    ('MOD32', 'Employees', 'The people employed at this pharmacy: their record, their pay basis and their departure', '/hr', 'employees', 'GRP_HR', 1, 1),
    ('MOD33', 'Attendance', 'One row per person per day. What payroll counts a working month from', '/attendance', 'attendance', 'GRP_HR', 2, 1),
    ('MOD34', 'Leave', 'Requests, approvals and the balance each person has left', '/leave', 'leave', 'GRP_HR', 3, 1),
    ('MOD35', 'Advance & Loan', 'Money lent to staff, and the plan by which payroll takes it back', '/advances', 'advance', 'GRP_HR', 4, 1),
    ('MOD36', 'Payroll', 'The monthly run: calculate, review, approve, post and pay. A posted run cannot be changed', '/payroll', 'payroll', 'GRP_HR', 5, 1),
    ('MOD37', 'Expense Claims', 'What staff spent on the pharmacy''s behalf, and getting it back. Never paid through a payslip', '/expenses', 'expenses', 'GRP_HR', 6, 1),
    ('MOD38', 'Approvals', 'Everything waiting for a decision, and who signs it. Nothing is configured until somebody adds a chain', '/approvals', 'approvals', 'GRP_HR', 7, 1),
    ('MOD39', 'HR Reports', 'People, attendance and leave, pay - every HR report, read from the live tables', '/hrreports', 'reports', 'GRP_HR', 8, 1),
    ('MOD40', 'HR Master', 'Departments and designations - the lists every staff record points at', '/hrmaster', 'database', 'GRP_HR', 0, 1);

/* Groups first: a child looks its parent up by code. */
INSERT INTO pharma.acl_module_info
       (pid_module, module_code, module_name, module_desc, route_path, icon_key,
        pid_parent_module, sort_order, is_active, status, inserted_by)
SELECT nextval('pharma.sq_acl_module'), s.module_code, s.module_name, NULLIF(s.module_desc, ''),
       NULLIF(s.route_path, ''), NULLIF(s.icon_key, ''), NULL, s.sort_order, s.is_active, 'Y', 'V88'
  FROM seed_module s
 WHERE s.parent_code = ''
   AND NOT EXISTS (SELECT 1 FROM pharma.acl_module_info x WHERE x.module_code = s.module_code);

INSERT INTO pharma.acl_module_info
       (pid_module, module_code, module_name, module_desc, route_path, icon_key,
        pid_parent_module, sort_order, is_active, status, inserted_by)
SELECT nextval('pharma.sq_acl_module'), s.module_code, s.module_name, NULLIF(s.module_desc, ''),
       NULLIF(s.route_path, ''), NULLIF(s.icon_key, ''),
       (SELECT p.pid_module FROM pharma.acl_module_info p WHERE p.module_code = s.parent_code),
       s.sort_order, s.is_active, 'Y', 'V88'
  FROM seed_module s
 WHERE s.parent_code <> ''
   AND NOT EXISTS (SELECT 1 FROM pharma.acl_module_info x WHERE x.module_code = s.module_code);

/* ═══ 2 · Repair, never replace ══════════════════════════════════════════════
   A module that exists but hangs from nothing - the HR rows V77 inserted where
   GRP_HR was missing - is given its parent. A blank route or icon is filled.
   A value already there is left, whatever it says. */

UPDATE pharma.acl_module_info m
   SET pid_parent_module = (SELECT p.pid_module FROM pharma.acl_module_info p WHERE p.module_code = s.parent_code),
       updated_by = 'V88', updated_date = CURRENT_TIMESTAMP
  FROM seed_module s
 WHERE s.module_code = m.module_code
   AND s.parent_code <> ''
   AND m.pid_parent_module IS NULL;

UPDATE pharma.acl_module_info m
   SET route_path = COALESCE(m.route_path, NULLIF(s.route_path, '')),
       icon_key   = COALESCE(m.icon_key,   NULLIF(s.icon_key, '')),
       updated_by = 'V88', updated_date = CURRENT_TIMESTAMP
  FROM seed_module s
 WHERE s.module_code = m.module_code
   AND ((m.route_path IS NULL AND s.route_path <> '') OR (m.icon_key IS NULL AND s.icon_key <> ''));

/* ═══ 3 · The roles ════════════════════════════════════════════════════════
   Keyed by ROLE_CODE, which is what every permission check reads. A role
   already there keeps its name and colour. The organisation is the platform's
   first, as the development seed had it. */

CREATE TEMP TABLE seed_role (role_code varchar(20), group_name varchar(100), group_description text, role_color varchar(20)) ON COMMIT DROP;
INSERT INTO seed_role VALUES
    ('ROLE01', 'Super Admin', 'Full system access', '#21a810'),
    ('ROLE02', 'Administrator (Branch)', 'Branch operations management', '#2f396a'),
    ('ROLE03', 'Pharmacist', 'POS and dispensing operations', '#B8752B'),
    ('ROLE04', 'Accounts', 'Financial and collection management', '#b4d5c1'),
    ('ROLE05', 'Purchase Manager', 'Purchasing and inventory', '#A8452E'),
    ('ROLE06', 'Delivery Staff', 'Delivery and logistics', '#658a2e'),
    ('ROLE07', 'Store Keeper', 'Inventory and stock management', '#5A9B3C'),
    ('ROLE08', 'Viewer', 'Read-only access to reports', '#9FB0A5'),
    ('ROLE09', 'Sales Manager', 'Sales performance and targets', '#D4925A');

INSERT INTO pharma.acl_group (pid_group, role_code, group_name, group_description, role_color, is_active, pid_org, status, inserted_by)
SELECT nextval('pharma.seq_acl_group'), s.role_code, s.group_name, NULLIF(s.group_description, ''), NULLIF(s.role_color, ''), 1,
       (SELECT MIN(pid_org) FROM pharma.all_organization), 'Y', 'V88'
  FROM seed_role s
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_group x WHERE x.role_code = s.role_code);

/* ═══ 4 · The permission matrix ═════════════════════════════════════════════
   Every role x module pair development holds, with the seven rights as they
   are there. A pair that already has a row - however it was set - is skipped:
   a permission somebody changed on the RBAC screen is theirs. */

CREATE TEMP TABLE seed_perm (role_code varchar(20), module_code varchar(20),
    can_view integer, can_create integer, can_edit integer, can_delete integer, can_approve integer, can_export integer, can_print integer) ON COMMIT DROP;
INSERT INTO seed_perm VALUES
    ('ROLE01', 'MOD01', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE01', 'MOD02', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE01', 'MOD03', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE01', 'MOD04', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE01', 'MOD05', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE01', 'MOD06', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE01', 'MOD07', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE01', 'MOD08', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE01', 'MOD09', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE01', 'MOD10', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE01', 'MOD11', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE01', 'MOD12', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE01', 'MOD13', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE01', 'MOD14', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE01', 'MOD15', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE01', 'MOD16', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE01', 'MOD17', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE01', 'MOD18', 1, 1, 0, 0, 0, 1, 1),
    ('ROLE01', 'MOD20', 1, 1, 0, 0, 0, 1, 1),
    ('ROLE01', 'MOD21', 1, 1, 1, 0, 0, 1, 1),
    ('ROLE01', 'MOD22', 1, 0, 0, 0, 0, 0, 1),
    ('ROLE01', 'MOD23', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE01', 'MOD24', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE01', 'MOD25', 1, 0, 0, 0, 0, 1, 1),
    ('ROLE01', 'MOD26', 1, 1, 1, 1, 0, 0, 0),
    ('ROLE01', 'MOD27', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE01', 'MOD28', 1, 0, 0, 0, 0, 0, 0),
    ('ROLE01', 'MOD29', 1, 0, 0, 0, 0, 0, 0),
    ('ROLE01', 'MOD30', 1, 0, 0, 0, 0, 0, 0),
    ('ROLE01', 'MOD31', 1, 1, 1, 0, 0, 0, 0),
    ('ROLE01', 'MOD32', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE01', 'MOD33', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE01', 'MOD34', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE01', 'MOD35', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE01', 'MOD36', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE01', 'MOD37', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE01', 'MOD38', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE01', 'MOD39', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE01', 'MOD40', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE02', 'MOD01', 1, 1, 1, 1, 1, 1, 0),
    ('ROLE02', 'MOD02', 1, 1, 1, 1, 1, 1, 0),
    ('ROLE02', 'MOD03', 1, 1, 1, 1, 1, 1, 0),
    ('ROLE02', 'MOD04', 1, 1, 1, 1, 1, 1, 0),
    ('ROLE02', 'MOD05', 1, 1, 1, 1, 1, 1, 0),
    ('ROLE02', 'MOD06', 1, 1, 1, 1, 1, 1, 0),
    ('ROLE02', 'MOD07', 1, 1, 1, 1, 1, 1, 0),
    ('ROLE02', 'MOD08', 1, 1, 1, 1, 1, 1, 0),
    ('ROLE02', 'MOD09', 1, 1, 1, 1, 1, 1, 0),
    ('ROLE02', 'MOD10', 1, 1, 1, 1, 1, 1, 0),
    ('ROLE02', 'MOD11', 1, 1, 1, 1, 1, 1, 0),
    ('ROLE02', 'MOD12', 1, 1, 1, 1, 1, 1, 0),
    ('ROLE02', 'MOD13', 1, 1, 1, 1, 1, 1, 0),
    ('ROLE02', 'MOD14', 1, 1, 1, 1, 1, 1, 0),
    ('ROLE02', 'MOD15', 1, 1, 1, 1, 1, 1, 0),
    ('ROLE02', 'MOD16', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE02', 'MOD17', 1, 1, 1, 1, 1, 1, 0),
    ('ROLE02', 'MOD18', 1, 1, 1, 1, 1, 1, 0),
    ('ROLE02', 'MOD20', 1, 1, 1, 1, 1, 1, 0),
    ('ROLE02', 'MOD21', 1, 1, 1, 1, 1, 1, 0),
    ('ROLE02', 'MOD22', 1, 0, 0, 0, 0, 0, 0),
    ('ROLE02', 'MOD23', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE02', 'MOD24', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE02', 'MOD25', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE02', 'MOD26', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE02', 'MOD27', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE02', 'MOD28', 1, 0, 0, 0, 0, 0, 0),
    ('ROLE02', 'MOD29', 1, 0, 0, 0, 0, 0, 0),
    ('ROLE02', 'MOD30', 1, 0, 0, 0, 0, 0, 0),
    ('ROLE02', 'MOD31', 1, 1, 1, 0, 0, 0, 0),
    ('ROLE02', 'MOD32', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE02', 'MOD33', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE02', 'MOD34', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE02', 'MOD35', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE02', 'MOD36', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE02', 'MOD37', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE02', 'MOD38', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE02', 'MOD39', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE02', 'MOD40', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE03', 'MOD01', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE03', 'MOD02', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE03', 'MOD04', 1, 0, 0, 0, 0, 0, 0),
    ('ROLE03', 'MOD05', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE03', 'MOD06', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE03', 'MOD07', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE03', 'MOD08', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE03', 'MOD09', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE03', 'MOD10', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE03', 'MOD11', 1, 1, 1, 1, 1, 1, 0),
    ('ROLE03', 'MOD12', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE03', 'MOD13', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE03', 'MOD14', 1, 1, 1, 1, 1, 0, 0),
    ('ROLE03', 'MOD15', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE03', 'MOD16', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE03', 'MOD17', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE03', 'MOD18', 1, 1, 0, 0, 0, 0, 0),
    ('ROLE03', 'MOD20', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE03', 'MOD21', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE03', 'MOD22', 1, 0, 0, 0, 0, 0, 0),
    ('ROLE03', 'MOD23', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE03', 'MOD24', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE03', 'MOD25', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE03', 'MOD26', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE03', 'MOD27', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE04', 'MOD01', 1, 0, 0, 0, 0, 0, 0),
    ('ROLE04', 'MOD04', 1, 0, 0, 0, 0, 0, 0),
    ('ROLE04', 'MOD06', 1, 0, 0, 0, 0, 0, 0),
    ('ROLE04', 'MOD10', 1, 1, 1, 0, 0, 0, 0),
    ('ROLE04', 'MOD12', 1, 1, 1, 0, 0, 0, 0),
    ('ROLE04', 'MOD14', 1, 0, 0, 0, 0, 1, 1),
    ('ROLE04', 'MOD18', 1, 0, 0, 0, 0, 1, 1),
    ('ROLE04', 'MOD20', 1, 0, 0, 0, 0, 1, 1),
    ('ROLE04', 'MOD21', 1, 1, 1, 0, 0, 1, 1),
    ('ROLE04', 'MOD22', 1, 0, 0, 0, 0, 0, 1),
    ('ROLE04', 'MOD32', 1, 1, 1, 0, 0, 1, 1),
    ('ROLE04', 'MOD33', 1, 1, 1, 0, 0, 1, 1),
    ('ROLE04', 'MOD34', 1, 1, 1, 0, 0, 1, 1),
    ('ROLE04', 'MOD35', 1, 1, 1, 0, 0, 1, 1),
    ('ROLE04', 'MOD36', 1, 1, 1, 0, 0, 1, 1),
    ('ROLE04', 'MOD37', 1, 1, 1, 0, 0, 1, 1),
    ('ROLE04', 'MOD38', 1, 0, 0, 0, 0, 1, 1),
    ('ROLE04', 'MOD39', 1, 0, 0, 0, 0, 1, 1),
    ('ROLE05', 'MOD01', 1, 0, 0, 0, 0, 0, 0),
    ('ROLE05', 'MOD02', 1, 0, 0, 0, 0, 0, 0),
    ('ROLE05', 'MOD03', 1, 1, 1, 0, 0, 0, 0),
    ('ROLE05', 'MOD04', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE05', 'MOD05', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE05', 'MOD06', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE05', 'MOD07', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE05', 'MOD08', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE05', 'MOD09', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE05', 'MOD10', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE05', 'MOD11', 1, 1, 1, 0, 1, 0, 0),
    ('ROLE05', 'MOD12', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE05', 'MOD13', 1, 1, 1, 0, 0, 0, 0),
    ('ROLE05', 'MOD14', 1, 0, 0, 0, 0, 1, 1),
    ('ROLE05', 'MOD15', 1, 0, 0, 0, 0, 0, 0),
    ('ROLE05', 'MOD16', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE05', 'MOD17', 1, 1, 1, 1, 1, 1, 1),
    ('ROLE05', 'MOD20', 1, 1, 0, 0, 0, 1, 1),
    ('ROLE05', 'MOD21', 1, 1, 0, 0, 0, 1, 1),
    ('ROLE05', 'MOD22', 1, 0, 0, 0, 0, 0, 1),
    ('ROLE05', 'MOD27', 1, 0, 0, 0, 0, 0, 0),
    ('ROLE06', 'MOD01', 1, 0, 0, 0, 0, 0, 0),
    ('ROLE06', 'MOD06', 1, 0, 1, 0, 0, 0, 0),
    ('ROLE06', 'MOD09', 1, 1, 1, 0, 0, 0, 0),
    ('ROLE06', 'MOD10', 1, 1, 0, 0, 0, 0, 0),
    ('ROLE06', 'MOD22', 1, 0, 0, 0, 0, 0, 1),
    ('ROLE07', 'MOD01', 1, 0, 0, 0, 0, 0, 0),
    ('ROLE07', 'MOD02', 1, 0, 0, 0, 0, 0, 0),
    ('ROLE07', 'MOD03', 1, 0, 0, 0, 0, 0, 0),
    ('ROLE07', 'MOD04', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE07', 'MOD05', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE07', 'MOD06', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE07', 'MOD07', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE07', 'MOD08', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE07', 'MOD09', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE07', 'MOD10', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE07', 'MOD11', 1, 1, 0, 0, 0, 0, 0),
    ('ROLE07', 'MOD12', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE07', 'MOD13', 1, 1, 1, 0, 0, 0, 0),
    ('ROLE07', 'MOD14', 1, 0, 0, 0, 0, 1, 1),
    ('ROLE07', 'MOD15', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE07', 'MOD16', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE07', 'MOD17', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE07', 'MOD20', 1, 1, 0, 0, 0, 0, 0),
    ('ROLE07', 'MOD21', 1, 0, 0, 0, 0, 0, 0),
    ('ROLE07', 'MOD22', 1, 0, 0, 0, 0, 0, 1),
    ('ROLE07', 'MOD27', 1, 0, 0, 0, 0, 0, 0),
    ('ROLE08', 'MOD01', 1, 0, 0, 0, 0, 0, 0),
    ('ROLE08', 'MOD02', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE08', 'MOD04', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE08', 'MOD05', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE08', 'MOD06', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE08', 'MOD07', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE08', 'MOD08', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE08', 'MOD09', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE08', 'MOD10', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE08', 'MOD11', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE08', 'MOD12', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE08', 'MOD13', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE08', 'MOD14', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE08', 'MOD15', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE08', 'MOD16', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE08', 'MOD17', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE08', 'MOD18', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE08', 'MOD20', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE08', 'MOD21', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE08', 'MOD22', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE08', 'MOD23', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE08', 'MOD24', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE08', 'MOD25', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE08', 'MOD26', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE08', 'MOD27', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE08', 'MOD28', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE08', 'MOD29', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE08', 'MOD30', 0, 0, 0, 0, 0, 0, 0),
    ('ROLE09', 'MOD01', 1, 0, 0, 0, 0, 0, 0),
    ('ROLE09', 'MOD02', 1, 0, 0, 0, 0, 0, 0),
    ('ROLE09', 'MOD04', 1, 1, 1, 1, 0, 0, 0),
    ('ROLE09', 'MOD05', 1, 1, 1, 0, 0, 0, 0),
    ('ROLE09', 'MOD06', 1, 1, 1, 0, 0, 0, 0),
    ('ROLE09', 'MOD07', 1, 1, 1, 0, 0, 1, 1),
    ('ROLE09', 'MOD08', 1, 1, 1, 0, 0, 0, 0),
    ('ROLE09', 'MOD14', 1, 0, 0, 0, 0, 1, 1),
    ('ROLE09', 'MOD18', 1, 0, 0, 0, 0, 0, 0),
    ('ROLE09', 'MOD22', 1, 0, 0, 0, 0, 0, 1),
    ('ROLE09', 'MOD27', 1, 0, 0, 0, 0, 0, 0);

INSERT INTO pharma.pos_permissions
       (pid_permission, pid_role, pid_module,
        can_view, can_create, can_edit, can_delete, can_approve, can_export, can_print, status, inserted_by)
SELECT nextval('pharma.sq_pos_perm'), g.pid_group, m.pid_module,
       s.can_view, s.can_create, s.can_edit, s.can_delete, s.can_approve, s.can_export, s.can_print, 'Y', 'V88'
  FROM seed_perm s
  JOIN pharma.acl_group g       ON g.role_code = s.role_code
  JOIN pharma.acl_module_info m ON m.module_code = s.module_code
 WHERE NOT EXISTS (SELECT 1 FROM pharma.pos_permissions x WHERE x.pid_role = g.pid_group AND x.pid_module = m.pid_module);

/* ═══ 5 · The plan templates ════════════════════════════════════════════════ */

CREATE TEMP TABLE seed_template (template_code varchar(30), template_name varchar(100), description text, plan_code varchar(30), sort_order integer) ON COMMIT DROP;
INSERT INTO seed_template VALUES
    ('BASIC', 'Basic Pharmacy', 'Selling, stock and the day-to-day reports.', 'BASIC', 1),
    ('STANDARD', 'Standard Pharmacy', 'Adds buying, suppliers and the full reporting suite.', 'STANDARD', 2),
    ('ENTERPRISE', 'Enterprise Pharmacy', 'Everything, including HR, accounts and the AI copilot.', 'ENTERPRISE', 3);

INSERT INTO pharma.acl_perm_template (pid_template, template_code, template_name, description, plan_code, is_active, sort_order, status, inserted_by)
SELECT nextval('pharma.sq_perm_template'), s.template_code, s.template_name, NULLIF(s.description, ''), s.plan_code, 1, s.sort_order, 'Y', 'V88'
  FROM seed_template s
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_perm_template x WHERE x.template_code = s.template_code);

CREATE TEMP TABLE seed_tline (template_code varchar(30), module_code varchar(20),
    can_view integer, can_create integer, can_edit integer, can_delete integer, can_approve integer, can_export integer, can_print integer) ON COMMIT DROP;
INSERT INTO seed_tline VALUES
    ('BASIC', 'GRP_MASTER', 1, 1, 1, 0, 0, 1, 1),
    ('BASIC', 'GRP_SALES', 1, 1, 1, 0, 0, 1, 1),
    ('BASIC', 'MOD01', 1, 1, 1, 0, 0, 1, 1),
    ('BASIC', 'MOD02', 1, 1, 1, 0, 0, 1, 1),
    ('BASIC', 'MOD04', 1, 1, 1, 0, 0, 1, 1),
    ('BASIC', 'MOD05', 1, 1, 1, 0, 0, 1, 1),
    ('BASIC', 'MOD06', 1, 1, 1, 0, 0, 1, 1),
    ('BASIC', 'MOD13', 1, 1, 1, 0, 0, 1, 1),
    ('BASIC', 'MOD14', 1, 1, 1, 0, 0, 1, 1),
    ('BASIC', 'MOD17', 1, 1, 1, 0, 0, 1, 1),
    ('BASIC', 'MOD18', 1, 1, 1, 0, 0, 1, 1),
    ('BASIC', 'MOD22', 1, 0, 0, 0, 0, 0, 1),
    ('BASIC', 'MOD27', 1, 1, 1, 0, 0, 1, 1),
    ('BASIC', 'MOD28', 1, 0, 0, 0, 0, 0, 0),
    ('BASIC', 'MOD29', 1, 0, 0, 0, 0, 0, 0),
    ('BASIC', 'MOD30', 1, 0, 0, 0, 0, 0, 0),
    ('BASIC', 'MOD31', 1, 1, 1, 0, 0, 0, 0),
    ('BASIC', 'MOD32', 1, 1, 1, 1, 1, 1, 1),
    ('BASIC', 'MOD33', 1, 1, 1, 1, 1, 1, 1),
    ('BASIC', 'MOD34', 1, 1, 1, 1, 1, 1, 1),
    ('BASIC', 'MOD35', 1, 1, 1, 1, 1, 1, 1),
    ('BASIC', 'MOD36', 1, 1, 1, 1, 1, 1, 1),
    ('BASIC', 'MOD37', 1, 1, 1, 1, 1, 1, 1),
    ('BASIC', 'MOD38', 1, 1, 1, 1, 1, 1, 1),
    ('BASIC', 'MOD39', 1, 1, 1, 1, 1, 1, 1),
    ('BASIC', 'MOD40', 1, 1, 1, 1, 1, 1, 1),
    ('ENTERPRISE', 'GRP_DELIVERY', 1, 1, 1, 1, 1, 1, 1),
    ('ENTERPRISE', 'GRP_MASTER', 1, 1, 1, 1, 1, 1, 1),
    ('ENTERPRISE', 'GRP_PURCHASE', 1, 1, 1, 1, 1, 1, 1),
    ('ENTERPRISE', 'GRP_SALES', 1, 1, 1, 1, 1, 1, 1),
    ('ENTERPRISE', 'MOD01', 1, 1, 1, 1, 1, 1, 1),
    ('ENTERPRISE', 'MOD02', 1, 1, 1, 1, 1, 1, 1),
    ('ENTERPRISE', 'MOD03', 1, 1, 1, 1, 1, 1, 1),
    ('ENTERPRISE', 'MOD04', 1, 1, 1, 1, 1, 1, 1),
    ('ENTERPRISE', 'MOD05', 1, 1, 1, 1, 1, 1, 1),
    ('ENTERPRISE', 'MOD06', 1, 1, 1, 1, 1, 1, 1),
    ('ENTERPRISE', 'MOD07', 1, 1, 1, 1, 1, 1, 1),
    ('ENTERPRISE', 'MOD08', 1, 1, 1, 1, 1, 1, 1),
    ('ENTERPRISE', 'MOD09', 1, 1, 1, 1, 1, 1, 1),
    ('ENTERPRISE', 'MOD10', 1, 1, 1, 1, 1, 1, 1),
    ('ENTERPRISE', 'MOD11', 1, 1, 1, 1, 1, 1, 1),
    ('ENTERPRISE', 'MOD12', 1, 1, 1, 1, 1, 1, 1),
    ('ENTERPRISE', 'MOD13', 1, 1, 1, 1, 1, 1, 1),
    ('ENTERPRISE', 'MOD14', 1, 1, 1, 1, 1, 1, 1),
    ('ENTERPRISE', 'MOD15', 1, 1, 1, 1, 1, 1, 1),
    ('ENTERPRISE', 'MOD16', 1, 1, 1, 1, 1, 1, 1),
    ('ENTERPRISE', 'MOD17', 1, 1, 1, 1, 1, 1, 1),
    ('ENTERPRISE', 'MOD18', 1, 1, 1, 1, 1, 1, 1),
    ('ENTERPRISE', 'MOD20', 1, 1, 1, 1, 1, 1, 1),
    ('ENTERPRISE', 'MOD21', 1, 1, 1, 1, 1, 1, 1),
    ('ENTERPRISE', 'MOD22', 1, 0, 0, 0, 0, 0, 1),
    ('ENTERPRISE', 'MOD27', 1, 1, 1, 1, 1, 1, 1),
    ('ENTERPRISE', 'MOD28', 1, 0, 0, 0, 0, 0, 0),
    ('ENTERPRISE', 'MOD29', 1, 0, 0, 0, 0, 0, 0),
    ('ENTERPRISE', 'MOD30', 1, 0, 0, 0, 0, 0, 0),
    ('ENTERPRISE', 'MOD31', 1, 1, 1, 0, 0, 0, 0),
    ('ENTERPRISE', 'MOD32', 1, 1, 1, 1, 1, 1, 1),
    ('ENTERPRISE', 'MOD33', 1, 1, 1, 1, 1, 1, 1),
    ('ENTERPRISE', 'MOD34', 1, 1, 1, 1, 1, 1, 1),
    ('ENTERPRISE', 'MOD35', 1, 1, 1, 1, 1, 1, 1),
    ('ENTERPRISE', 'MOD36', 1, 1, 1, 1, 1, 1, 1),
    ('ENTERPRISE', 'MOD37', 1, 1, 1, 1, 1, 1, 1),
    ('ENTERPRISE', 'MOD38', 1, 1, 1, 1, 1, 1, 1),
    ('ENTERPRISE', 'MOD39', 1, 1, 1, 1, 1, 1, 1),
    ('ENTERPRISE', 'MOD40', 1, 1, 1, 1, 1, 1, 1),
    ('STANDARD', 'GRP_DELIVERY', 1, 1, 1, 0, 1, 1, 1),
    ('STANDARD', 'GRP_MASTER', 1, 1, 1, 0, 1, 1, 1),
    ('STANDARD', 'GRP_PURCHASE', 1, 1, 1, 0, 1, 1, 1),
    ('STANDARD', 'GRP_SALES', 1, 1, 1, 0, 1, 1, 1),
    ('STANDARD', 'MOD01', 1, 1, 1, 0, 1, 1, 1),
    ('STANDARD', 'MOD02', 1, 1, 1, 0, 1, 1, 1),
    ('STANDARD', 'MOD03', 1, 1, 1, 0, 1, 1, 1),
    ('STANDARD', 'MOD04', 1, 1, 1, 0, 1, 1, 1),
    ('STANDARD', 'MOD05', 1, 1, 1, 0, 1, 1, 1),
    ('STANDARD', 'MOD06', 1, 1, 1, 0, 1, 1, 1),
    ('STANDARD', 'MOD07', 1, 1, 1, 0, 1, 1, 1),
    ('STANDARD', 'MOD08', 1, 1, 1, 0, 1, 1, 1),
    ('STANDARD', 'MOD09', 1, 1, 1, 0, 1, 1, 1),
    ('STANDARD', 'MOD10', 1, 1, 1, 0, 1, 1, 1),
    ('STANDARD', 'MOD11', 1, 1, 1, 0, 1, 1, 1),
    ('STANDARD', 'MOD12', 1, 1, 1, 0, 1, 1, 1),
    ('STANDARD', 'MOD13', 1, 1, 1, 0, 1, 1, 1),
    ('STANDARD', 'MOD14', 1, 1, 1, 0, 1, 1, 1),
    ('STANDARD', 'MOD17', 1, 1, 1, 0, 1, 1, 1),
    ('STANDARD', 'MOD18', 1, 1, 1, 0, 1, 1, 1),
    ('STANDARD', 'MOD20', 1, 1, 1, 0, 1, 1, 1),
    ('STANDARD', 'MOD21', 1, 1, 1, 0, 1, 1, 1),
    ('STANDARD', 'MOD22', 1, 0, 0, 0, 0, 0, 1),
    ('STANDARD', 'MOD27', 1, 1, 1, 0, 1, 1, 1),
    ('STANDARD', 'MOD28', 1, 0, 0, 0, 0, 0, 0),
    ('STANDARD', 'MOD29', 1, 0, 0, 0, 0, 0, 0),
    ('STANDARD', 'MOD30', 1, 0, 0, 0, 0, 0, 0),
    ('STANDARD', 'MOD31', 1, 1, 1, 0, 0, 0, 0),
    ('STANDARD', 'MOD32', 1, 1, 1, 1, 1, 1, 1),
    ('STANDARD', 'MOD33', 1, 1, 1, 1, 1, 1, 1),
    ('STANDARD', 'MOD34', 1, 1, 1, 1, 1, 1, 1),
    ('STANDARD', 'MOD35', 1, 1, 1, 1, 1, 1, 1),
    ('STANDARD', 'MOD36', 1, 1, 1, 1, 1, 1, 1),
    ('STANDARD', 'MOD37', 1, 1, 1, 1, 1, 1, 1),
    ('STANDARD', 'MOD38', 1, 1, 1, 1, 1, 1, 1),
    ('STANDARD', 'MOD39', 1, 1, 1, 1, 1, 1, 1),
    ('STANDARD', 'MOD40', 1, 1, 1, 1, 1, 1, 1);

INSERT INTO pharma.acl_perm_template_line
       (pid_template_line, pid_template, pid_module, can_view, can_create, can_edit, can_delete, can_approve, can_export, can_print)
SELECT nextval('pharma.sq_template_line'), t.pid_template, m.pid_module,
       s.can_view, s.can_create, s.can_edit, s.can_delete, s.can_approve, s.can_export, s.can_print
  FROM seed_tline s
  JOIN pharma.acl_perm_template t ON t.template_code = s.template_code
  JOIN pharma.acl_module_info m   ON m.module_code = s.module_code
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_perm_template_line x WHERE x.pid_template = t.pid_template AND x.pid_module = m.pid_module);

/* ═══ 6 · Plan ceilings, from each branch's own plan ═══════════════════════
   A branch with plan rows is narrowed to the modules they name, so a module
   seeded above with no row would be invisible there - and for a branch on
   production that is most of the application. What each such branch gets is
   what onboarding would have given it: its live subscription names a plan,
   the plan names a template, and the template's line for the module carries
   the rights. Only modules THIS file inserted are considered (inserted_by =
   'V88'), so a module a plan deliberately leaves out - purchase on a Basic
   pharmacy, the platform console anywhere - stays out. A branch with no plan
   rows is unrestricted (V33) and gets none; a branch whose plan has no
   template gets none either, and is named in the verification below. */

INSERT INTO pharma.acl_tenant_module
       (pid_tenant_module, pid_branch, pid_module,
        can_view, can_create, can_edit, can_delete, can_approve, can_export, can_print, status, inserted_by)
SELECT nextval('pharma.sq_tenant_module'), b.pid_branch, l.pid_module,
       l.can_view, l.can_create, l.can_edit, l.can_delete, l.can_approve, l.can_export, l.can_print, 'Y', 'V88'
  FROM (SELECT DISTINCT pid_branch FROM pharma.acl_tenant_module) b
  JOIN pharma.sub_subscription sub ON sub.pid_branch = b.pid_branch AND sub.status = 'Y' AND sub.sub_status = 'ACTIVE'
  JOIN pharma.sub_plan pl          ON pl.pid_plan = sub.pid_plan
  JOIN pharma.acl_perm_template t  ON t.plan_code = pl.plan_code AND t.status = 'Y'
  JOIN pharma.acl_perm_template_line l ON l.pid_template = t.pid_template
  JOIN pharma.acl_module_info m    ON m.pid_module = l.pid_module AND m.inserted_by = 'V88'
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acl_tenant_module x WHERE x.pid_branch = b.pid_branch AND x.pid_module = l.pid_module);


/* ═══ Verification ══════════════════════════════════════════════════════════
   Every seeded code present, every child with a parent, every role present. */

SELECT (SELECT count(*) FROM seed_module)                                                       AS modules_expected,
       (SELECT count(*) FROM pharma.acl_module_info m JOIN seed_module s ON s.module_code = m.module_code) AS modules_present,
       (SELECT count(*) FROM pharma.acl_module_info m JOIN seed_module s ON s.module_code = m.module_code
         WHERE s.parent_code <> '' AND m.pid_parent_module IS NULL)                              AS children_without_parent,
       (SELECT count(*) FROM seed_role)                                                         AS roles_expected,
       (SELECT count(*) FROM pharma.acl_group g JOIN seed_role s ON s.role_code = g.role_code)  AS roles_present,
       (SELECT count(*) FROM seed_perm)                                                         AS matrix_rows_expected,
       (SELECT count(*) FROM pharma.pos_permissions p JOIN pharma.acl_group g ON g.pid_group = p.pid_role
          JOIN pharma.acl_module_info m ON m.pid_module = p.pid_module
          JOIN seed_perm s ON s.role_code = g.role_code AND s.module_code = m.module_code)        AS matrix_rows_present,
       (SELECT count(*) FROM pharma.acl_module_info WHERE inserted_by = 'V88')                   AS modules_seeded_here,
       (SELECT count(*) FROM pharma.acl_tenant_module WHERE inserted_by = 'V88')                 AS ceilings_seeded_here;

/* Narrowed branches whose plan has no template: they gain nothing here and
   are listed so somebody applies a template from the Pharmacies screen. */
SELECT b.pid_branch, pl.plan_code
  FROM (SELECT DISTINCT pid_branch FROM pharma.acl_tenant_module) b
  LEFT JOIN pharma.sub_subscription sub ON sub.pid_branch = b.pid_branch AND sub.status = 'Y' AND sub.sub_status = 'ACTIVE'
  LEFT JOIN pharma.sub_plan pl          ON pl.pid_plan = sub.pid_plan
  LEFT JOIN pharma.acl_perm_template t  ON t.plan_code = pl.plan_code AND t.status = 'Y'
 WHERE t.pid_template IS NULL
 ORDER BY 1;
