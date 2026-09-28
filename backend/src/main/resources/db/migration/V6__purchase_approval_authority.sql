-- ============================================================================
-- V6 — PURCHASE APPROVAL AUTHORITY
-- Target: PostgreSQL.
--
-- Approving a purchase is what receives its stock, so it is the
-- segregation-of-duties boundary in this module: Branch Managers and Purchase
-- Officers raise and edit invoices, an administrator approves them.
--
-- Branch Manager (ROLE02) currently carries CAN_APPROVE = 1 on Purchase, which
-- let a manager both create an invoice and sign off the stock receipt for it.
-- This revokes that. Create, edit and delete are untouched — a manager keeps
-- full control of an invoice right up until it is approved.
--
-- Idempotent; safe to re-run.
-- ============================================================================

UPDATE PHARMA.POS_PERMISSIONS p
   SET CAN_APPROVE  = 0,
       UPDATED_DATE = CURRENT_TIMESTAMP,
       UPDATED_BY   = 'SYSTEM'
  FROM PHARMA.ACL_GROUP g,
       PHARMA.ACL_MODULE_INFO m
 WHERE p.PID_ROLE   = g.PID_GROUP
   AND p.PID_MODULE = m.PID_MODULE
   AND m.ROUTE_PATH = '/purchase'
   AND g.ROLE_CODE <> 'ROLE01'
   AND p.CAN_APPROVE <> 0;

-- The administrator must be able to approve, or nothing can ever be received.
UPDATE PHARMA.POS_PERMISSIONS p
   SET CAN_APPROVE  = 1,
       UPDATED_DATE = CURRENT_TIMESTAMP,
       UPDATED_BY   = 'SYSTEM'
  FROM PHARMA.ACL_GROUP g,
       PHARMA.ACL_MODULE_INFO m
 WHERE p.PID_ROLE   = g.PID_GROUP
   AND p.PID_MODULE = m.PID_MODULE
   AND m.ROUTE_PATH = '/purchase'
   AND g.ROLE_CODE  = 'ROLE01'
   AND p.CAN_APPROVE <> 1;

-- The line-level BATCH_NO is no longer typed in during purchase entry; the
-- service fills it from the invoice's own generated batch reference. The column
-- stays because Inventory, Sales and POS all read it for batch stock and expiry
-- alerts — blanking it would empty those screens.
COMMENT ON COLUMN PHARMA.POS_TRANSACTION_DTL.BATCH_NO IS
    'Batch reference for the line. Purchase entry fills this from POS_TRANSACTION_MST.BATCH_NO rather than asking the user. Read by Inventory, Sales and POS.';

COMMENT ON COLUMN PHARMA.POS_TRANSACTION_DTL.EXPIRY_DATE IS
    'Auto-generated as the purchase date + 365 days. Not user-entered.';
