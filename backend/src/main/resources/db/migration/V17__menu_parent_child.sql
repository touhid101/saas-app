-- ═══════════════════════════════════════════════════════════════════════════
-- V17 — A real parent/child navigation menu
--
-- ACL_MODULE_INFO has carried PID_PARENT_MODULE since the baseline and nothing
-- ever used it: every module was a root, and the sidebar invented its own
-- headings by hard-coding module codes into four buckets. Adding a page meant
-- editing that list, and a page nobody remembered to add landed under
-- "OPERATIONS" by default.
--
-- The grouping now lives here, where the modules do.
--
-- Parents are pure containers: no ROUTE_PATH, because clicking a heading that
-- both navigates and expands is ambiguous, and there is no page behind them.
-- They also get no permission rows — a container's visibility follows from
-- whether the user can see anything inside it, which PermissionServiceImpl
-- derives. Granting parents separately would mean every new child needed its
-- parent granted too, and forgetting that hides a page the user is entitled to.
-- ═══════════════════════════════════════════════════════════════════════════

/* ── The three containers ────────────────────────────────────────────────── */

INSERT INTO PHARMA.ACL_MODULE_INFO
       (PID_MODULE, MODULE_CODE, MODULE_NAME, MODULE_DESC, ROUTE_PATH, ICON_KEY, SORT_ORDER, IS_ACTIVE, STATUS)
SELECT COALESCE((SELECT MAX(PID_MODULE) FROM PHARMA.ACL_MODULE_INFO), 0) + 1,
       'GRP_MASTER', 'Master Data', 'Organisation, parties and product setup',
       NULL, 'master', 2, 1, 'Y'
 WHERE NOT EXISTS (SELECT 1 FROM PHARMA.ACL_MODULE_INFO WHERE MODULE_CODE = 'GRP_MASTER');

INSERT INTO PHARMA.ACL_MODULE_INFO
       (PID_MODULE, MODULE_CODE, MODULE_NAME, MODULE_DESC, ROUTE_PATH, ICON_KEY, SORT_ORDER, IS_ACTIVE, STATUS)
SELECT COALESCE((SELECT MAX(PID_MODULE) FROM PHARMA.ACL_MODULE_INFO), 0) + 1,
       'GRP_SALES', 'Sales', 'Selling, returns and sales analysis',
       NULL, 'sales', 4, 1, 'Y'
 WHERE NOT EXISTS (SELECT 1 FROM PHARMA.ACL_MODULE_INFO WHERE MODULE_CODE = 'GRP_SALES');

INSERT INTO PHARMA.ACL_MODULE_INFO
       (PID_MODULE, MODULE_CODE, MODULE_NAME, MODULE_DESC, ROUTE_PATH, ICON_KEY, SORT_ORDER, IS_ACTIVE, STATUS)
SELECT COALESCE((SELECT MAX(PID_MODULE) FROM PHARMA.ACL_MODULE_INFO), 0) + 1,
       'GRP_DELIVERY', 'Delivery', 'Dispatch, tracking and collection',
       NULL, 'delivery', 5, 1, 'Y'
 WHERE NOT EXISTS (SELECT 1 FROM PHARMA.ACL_MODULE_INFO WHERE MODULE_CODE = 'GRP_DELIVERY');

/* ── Children keep their routes and permissions; only their parent changes ── */

-- Master Data
UPDATE PHARMA.ACL_MODULE_INFO SET
       PID_PARENT_MODULE = (SELECT PID_MODULE FROM PHARMA.ACL_MODULE_INFO WHERE MODULE_CODE = 'GRP_MASTER'),
       SORT_ORDER = 1,
       -- Renamed: a child called "Master Data" inside a parent called "Master
       -- Data" reads as a mistake. This page is companies, branches, customers,
       -- suppliers, areas and staff — the parties, not the products.
       MODULE_NAME = 'Organisation & Parties'
 WHERE MODULE_CODE = 'MOD02';

UPDATE PHARMA.ACL_MODULE_INFO SET
       PID_PARENT_MODULE = (SELECT PID_MODULE FROM PHARMA.ACL_MODULE_INFO WHERE MODULE_CODE = 'GRP_MASTER'),
       SORT_ORDER = 2
 WHERE MODULE_CODE = 'MOD17';

-- Sales — the group this was asked for, in the order the work actually happens:
-- raise an invoice, sell over the counter, track it, take it back, then price
-- and measure.
UPDATE PHARMA.ACL_MODULE_INFO SET
       PID_PARENT_MODULE = (SELECT PID_MODULE FROM PHARMA.ACL_MODULE_INFO WHERE MODULE_CODE = 'GRP_SALES'),
       SORT_ORDER = 1,
       MODULE_NAME = 'Sales Invoice'
 WHERE MODULE_CODE = 'MOD04';

UPDATE PHARMA.ACL_MODULE_INFO SET
       PID_PARENT_MODULE = (SELECT PID_MODULE FROM PHARMA.ACL_MODULE_INFO WHERE MODULE_CODE = 'GRP_SALES'),
       SORT_ORDER = 2
 WHERE MODULE_CODE = 'MOD05';

UPDATE PHARMA.ACL_MODULE_INFO SET
       PID_PARENT_MODULE = (SELECT PID_MODULE FROM PHARMA.ACL_MODULE_INFO WHERE MODULE_CODE = 'GRP_SALES'),
       SORT_ORDER = 3
 WHERE MODULE_CODE = 'MOD06';

UPDATE PHARMA.ACL_MODULE_INFO SET
       PID_PARENT_MODULE = (SELECT PID_MODULE FROM PHARMA.ACL_MODULE_INFO WHERE MODULE_CODE = 'GRP_SALES'),
       SORT_ORDER = 4
 WHERE MODULE_CODE = 'MOD18';

UPDATE PHARMA.ACL_MODULE_INFO SET
       PID_PARENT_MODULE = (SELECT PID_MODULE FROM PHARMA.ACL_MODULE_INFO WHERE MODULE_CODE = 'GRP_SALES'),
       SORT_ORDER = 5
 WHERE MODULE_CODE = 'MOD08';

UPDATE PHARMA.ACL_MODULE_INFO SET
       PID_PARENT_MODULE = (SELECT PID_MODULE FROM PHARMA.ACL_MODULE_INFO WHERE MODULE_CODE = 'GRP_SALES'),
       SORT_ORDER = 6
 WHERE MODULE_CODE = 'MOD07';

-- Delivery
UPDATE PHARMA.ACL_MODULE_INFO SET
       PID_PARENT_MODULE = (SELECT PID_MODULE FROM PHARMA.ACL_MODULE_INFO WHERE MODULE_CODE = 'GRP_DELIVERY'),
       SORT_ORDER = 1,
       MODULE_NAME = 'Delivery Tracking'
 WHERE MODULE_CODE = 'MOD09';

UPDATE PHARMA.ACL_MODULE_INFO SET
       PID_PARENT_MODULE = (SELECT PID_MODULE FROM PHARMA.ACL_MODULE_INFO WHERE MODULE_CODE = 'GRP_DELIVERY'),
       SORT_ORDER = 2
 WHERE MODULE_CODE = 'MOD10';

/* ── The remaining roots, renumbered around the new containers ───────────── */

UPDATE PHARMA.ACL_MODULE_INFO SET SORT_ORDER = 1,  PID_PARENT_MODULE = NULL WHERE MODULE_CODE = 'MOD01'; -- Dashboard
UPDATE PHARMA.ACL_MODULE_INFO SET SORT_ORDER = 3,  PID_PARENT_MODULE = NULL WHERE MODULE_CODE = 'MOD03'; -- Purchase
UPDATE PHARMA.ACL_MODULE_INFO SET SORT_ORDER = 6,  PID_PARENT_MODULE = NULL WHERE MODULE_CODE = 'MOD11'; -- Requisition
UPDATE PHARMA.ACL_MODULE_INFO SET SORT_ORDER = 7,  PID_PARENT_MODULE = NULL WHERE MODULE_CODE = 'MOD12'; -- Invoice Merge
UPDATE PHARMA.ACL_MODULE_INFO SET SORT_ORDER = 8,  PID_PARENT_MODULE = NULL WHERE MODULE_CODE = 'MOD13'; -- Inventory
UPDATE PHARMA.ACL_MODULE_INFO SET SORT_ORDER = 9,  PID_PARENT_MODULE = NULL WHERE MODULE_CODE = 'MOD14'; -- Reports
UPDATE PHARMA.ACL_MODULE_INFO SET SORT_ORDER = 10, PID_PARENT_MODULE = NULL WHERE MODULE_CODE = 'MOD15'; -- AI Copilot
UPDATE PHARMA.ACL_MODULE_INFO SET SORT_ORDER = 11, PID_PARENT_MODULE = NULL WHERE MODULE_CODE = 'MOD16'; -- RBAC

-- The menu is read on every sign-in, once per level.
CREATE INDEX IF NOT EXISTS IX_MODULE_PARENT_SORT
    ON PHARMA.ACL_MODULE_INFO (PID_PARENT_MODULE, SORT_ORDER)
 WHERE IS_ACTIVE = 1;

COMMENT ON COLUMN PHARMA.ACL_MODULE_INFO.PID_PARENT_MODULE IS
    'The menu group this module sits under. NULL for a top-level entry. A row '
    'with children and no ROUTE_PATH is a container: it has no page, and its '
    'visibility follows from whether the user can see anything inside it.';
