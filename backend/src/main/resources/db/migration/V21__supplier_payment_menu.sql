-- ═══════════════════════════════════════════════════════════════════════════
-- V21 — Supplier Payment, under Purchase
--
-- Purchase already became a container in V19. This is its third child: the
-- money side of buying, next to the invoice and the return.
-- ═══════════════════════════════════════════════════════════════════════════

INSERT INTO PHARMA.ACL_MODULE_INFO
       (PID_MODULE, MODULE_CODE, MODULE_NAME, MODULE_DESC, ROUTE_PATH, ICON_KEY,
        PID_PARENT_MODULE, SORT_ORDER, IS_ACTIVE, STATUS)
SELECT nextval('PHARMA.SQ_ACL_MODULE'),
       'MOD21', 'Supplier Payment',
       'Payments to suppliers, outstanding balances, ageing and performance',
       '/supplierpayment', 'supplierpayment',
       (SELECT PID_MODULE FROM PHARMA.ACL_MODULE_INFO WHERE MODULE_CODE = 'GRP_PURCHASE'),
       3, 1, 'Y'
 WHERE NOT EXISTS (SELECT 1 FROM PHARMA.ACL_MODULE_INFO WHERE MODULE_CODE = 'MOD21');

/* ── Permissions ─────────────────────────────────────────────────────────── */

-- Mirrors the controller. Paying a supplier is an Accounts job first, so ROLE04
-- creates here as well as views; the Purchase Manager pays too, because the
-- person who agreed the bill is often the one who settles it.
--
-- The Store Keeper (ROLE07) can raise a purchase return but is deliberately not
-- given payment rights: receiving goods and releasing money are the two halves
-- of the same control, and one person holding both is how an unnoticed payment
-- happens.
--
-- No delete: a payment is reversed with a reason, never erased.
INSERT INTO PHARMA.POS_PERMISSIONS
       (PID_ROLE, PID_MODULE, CAN_VIEW, CAN_CREATE, CAN_EDIT, CAN_DELETE, CAN_APPROVE, CAN_EXPORT, STATUS)
SELECT g.PID_GROUP, m.PID_MODULE,
       1,
       CASE WHEN g.ROLE_CODE IN ('ROLE01','ROLE02','ROLE04','ROLE05') THEN 1 ELSE 0 END,
       -- Cancelling a payment is the edit right, and it stops at Accounts and
       -- above: reversing money already sent is not a counter-level correction.
       CASE WHEN g.ROLE_CODE IN ('ROLE01','ROLE02','ROLE04') THEN 1 ELSE 0 END,
       0, 0,
       CASE WHEN g.ROLE_CODE IN ('ROLE01','ROLE02','ROLE04','ROLE05') THEN 1 ELSE 0 END,
       'Y'
  FROM PHARMA.ACL_GROUP g
  CROSS JOIN PHARMA.ACL_MODULE_INFO m
 WHERE m.MODULE_CODE = 'MOD21'
   AND g.ROLE_CODE IN ('ROLE01','ROLE02','ROLE04','ROLE05','ROLE07')
   AND NOT EXISTS (
       SELECT 1 FROM PHARMA.POS_PERMISSIONS x
        WHERE x.PID_ROLE = g.PID_GROUP AND x.PID_MODULE = m.PID_MODULE);
