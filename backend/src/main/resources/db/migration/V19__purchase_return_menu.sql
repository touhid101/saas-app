-- ═══════════════════════════════════════════════════════════════════════════
-- V19 — Purchase Return, under a Purchase menu group
--
-- Purchase was a single leaf. It now holds two pages, so it becomes a container
-- in the same shape as Sales and Delivery: no route of its own, no permission
-- row, visibility derived from whether the user can see anything inside it.
-- ═══════════════════════════════════════════════════════════════════════════

/* ── The container ───────────────────────────────────────────────────────── */

INSERT INTO PHARMA.ACL_MODULE_INFO
       (PID_MODULE, MODULE_CODE, MODULE_NAME, MODULE_DESC, ROUTE_PATH, ICON_KEY, SORT_ORDER, IS_ACTIVE, STATUS)
SELECT nextval('PHARMA.SQ_ACL_MODULE'),
       'GRP_PURCHASE', 'Purchase', 'Buying, and goods going back to suppliers',
       NULL, 'purchase', 3, 1, 'Y'
 WHERE NOT EXISTS (SELECT 1 FROM PHARMA.ACL_MODULE_INFO WHERE MODULE_CODE = 'GRP_PURCHASE');

/* ── The page itself ─────────────────────────────────────────────────────── */

INSERT INTO PHARMA.ACL_MODULE_INFO
       (PID_MODULE, MODULE_CODE, MODULE_NAME, MODULE_DESC, ROUTE_PATH, ICON_KEY, SORT_ORDER, IS_ACTIVE, STATUS)
SELECT nextval('PHARMA.SQ_ACL_MODULE'),
       'MOD20', 'Purchase Return',
       'Goods returned to the supplier, out of the batch they were received into',
       '/purchasereturn', 'purchasereturn', 2, 1, 'Y'
 WHERE NOT EXISTS (SELECT 1 FROM PHARMA.ACL_MODULE_INFO WHERE MODULE_CODE = 'MOD20');

/* ── Reparent the existing purchase page under the new container ─────────── */

UPDATE PHARMA.ACL_MODULE_INFO SET
       PID_PARENT_MODULE = (SELECT PID_MODULE FROM PHARMA.ACL_MODULE_INFO WHERE MODULE_CODE = 'GRP_PURCHASE'),
       SORT_ORDER  = 1,
       -- Renamed so it does not share its parent's name, the same way
       -- "Sales" became "Sales Invoice" under the Sales group.
       MODULE_NAME = 'Purchase Invoice'
 WHERE MODULE_CODE = 'MOD03';

UPDATE PHARMA.ACL_MODULE_INFO SET
       PID_PARENT_MODULE = (SELECT PID_MODULE FROM PHARMA.ACL_MODULE_INFO WHERE MODULE_CODE = 'GRP_PURCHASE')
 WHERE MODULE_CODE = 'MOD20';

/* ── Permissions ─────────────────────────────────────────────────────────── */

-- Mirrors the controller. Purchase Manager and Store Keeper raise these: the
-- storekeeper is who finds the damaged carton, and making them wait for a
-- manager is how goods sit in a corner until they are out of date.
--
-- No delete: a return has moved stock and credited a supplier. Correcting one
-- is a fresh transaction, not an erasure.
INSERT INTO PHARMA.POS_PERMISSIONS
       (PID_ROLE, PID_MODULE, CAN_VIEW, CAN_CREATE, CAN_EDIT, CAN_DELETE, CAN_APPROVE, CAN_EXPORT, STATUS)
SELECT g.PID_GROUP, m.PID_MODULE,
       1,
       CASE WHEN g.ROLE_CODE IN ('ROLE01','ROLE02','ROLE05','ROLE07') THEN 1 ELSE 0 END,
       0, 0, 0,
       CASE WHEN g.ROLE_CODE IN ('ROLE01','ROLE02','ROLE04','ROLE05') THEN 1 ELSE 0 END,
       'Y'
  FROM PHARMA.ACL_GROUP g
  CROSS JOIN PHARMA.ACL_MODULE_INFO m
 WHERE m.MODULE_CODE = 'MOD20'
   -- Accounts sees them for the credit side without raising them; delivery
   -- staff and the POS cashier have no business on this screen.
   AND g.ROLE_CODE IN ('ROLE01','ROLE02','ROLE04','ROLE05','ROLE07')
   AND NOT EXISTS (
       SELECT 1 FROM PHARMA.POS_PERMISSIONS x
        WHERE x.PID_ROLE = g.PID_GROUP AND x.PID_MODULE = m.PID_MODULE);
