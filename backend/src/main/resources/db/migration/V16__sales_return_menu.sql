-- ═══════════════════════════════════════════════════════════════════════════
-- V16 — Sales Return on the menu
--
-- The module and its permissions, so the page is reachable and RBAC governs it
-- like every other screen rather than being open to whoever knows the URL.
--
-- Placed after Sales Activity: a return is raised against an invoice, and that
-- is the screen someone is looking at when they need one.
-- ═══════════════════════════════════════════════════════════════════════════

INSERT INTO PHARMA.ACL_MODULE_INFO
       (PID_MODULE, MODULE_CODE, MODULE_NAME, MODULE_DESC, ROUTE_PATH, ICON_KEY, SORT_ORDER, IS_ACTIVE, STATUS)
SELECT COALESCE((SELECT MAX(PID_MODULE) FROM PHARMA.ACL_MODULE_INFO), 0) + 1,
       'MOD18', 'Sales Return',
       'Goods returned against a sales invoice, back into the batch they were sold from',
       '/salesreturn', 'undo', 7, 1, 'Y'
 WHERE NOT EXISTS (SELECT 1 FROM PHARMA.ACL_MODULE_INFO WHERE MODULE_CODE = 'MOD18');

-- Permissions mirror the controller: ROLE01, ROLE02 and ROLE03 may raise a
-- return — the pharmacist runs the counter, so refusing them here would mean
-- every return waited for a manager.
--
-- No delete: a return moved real stock and credited real money. Correcting one
-- is a fresh transaction, not an erasure.
INSERT INTO PHARMA.POS_PERMISSIONS
       (PID_ROLE, PID_MODULE, CAN_VIEW, CAN_CREATE, CAN_EDIT, CAN_DELETE, CAN_APPROVE, CAN_EXPORT, STATUS)
SELECT g.PID_GROUP, m.PID_MODULE,
       1,                                                   -- view
       CASE WHEN g.ROLE_CODE IN ('ROLE01','ROLE02','ROLE03') THEN 1 ELSE 0 END,  -- create
       0, 0, 0,
       CASE WHEN g.ROLE_CODE IN ('ROLE01','ROLE02','ROLE04') THEN 1 ELSE 0 END,  -- export
       'Y'
  FROM PHARMA.ACL_GROUP g
  CROSS JOIN PHARMA.ACL_MODULE_INFO m
 WHERE m.MODULE_CODE = 'MOD18'
   -- Accounts and Sales Manager can see returns without raising them; delivery
   -- staff have no business on this screen at all.
   AND g.ROLE_CODE IN ('ROLE01','ROLE02','ROLE03','ROLE04','ROLE09')
   AND NOT EXISTS (
       SELECT 1 FROM PHARMA.POS_PERMISSIONS x
        WHERE x.PID_ROLE = g.PID_GROUP AND x.PID_MODULE = m.PID_MODULE);
