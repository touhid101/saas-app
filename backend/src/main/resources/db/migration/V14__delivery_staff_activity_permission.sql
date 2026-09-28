-- ═══════════════════════════════════════════════════════════════════════════
-- V14 — Delivery staff can work the Sales Activity queue
--
-- ROLE06 held /activity at view-only. That was right when delivery staff had
-- their own screen and Sales Activity was a manager's overview — but it is now
-- the screen a driver actually works from: mark a parcel on its way, mark it
-- delivered, take the money.
--
-- With CAN_EDIT at 0 every action on that page was hidden, so a driver saw
-- their round and could do nothing with it. The API already allows exactly
-- these actions and refuses everything else; this makes the UI agree.
--
-- CAN_CREATE stays 0. A driver does not raise invoices.
-- ═══════════════════════════════════════════════════════════════════════════

UPDATE PHARMA.POS_PERMISSIONS p
   SET CAN_EDIT = 1
  FROM PHARMA.ACL_MODULE_INFO m
 WHERE m.PID_MODULE = p.PID_MODULE
   AND p.PID_ROLE   = (SELECT PID_GROUP FROM PHARMA.ACL_GROUP WHERE ROLE_CODE = 'ROLE06')
   AND m.ROUTE_PATH = '/activity';

-- Insert the row if this deployment never had one, so the grant does not depend
-- on the seed having run first.
INSERT INTO PHARMA.POS_PERMISSIONS (PID_ROLE, PID_MODULE, CAN_VIEW, CAN_CREATE, CAN_EDIT, CAN_DELETE, STATUS)
SELECT g.PID_GROUP, m.PID_MODULE, 1, 0, 1, 0, 'Y'
  FROM PHARMA.ACL_GROUP g
  CROSS JOIN PHARMA.ACL_MODULE_INFO m
 WHERE g.ROLE_CODE  = 'ROLE06'
   AND m.ROUTE_PATH = '/activity'
   AND NOT EXISTS (
       SELECT 1 FROM PHARMA.POS_PERMISSIONS x
        WHERE x.PID_ROLE = g.PID_GROUP AND x.PID_MODULE = m.PID_MODULE);
