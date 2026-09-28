-- Uniqueness that stops at the pharmacy boundary.
--
-- Six unique constraints were global. With one pharmacy that is invisible; with
-- two it is a bug that surfaces as an unexplainable constraint error in the
-- middle of somebody's working day:
--
--   · Two pharmacies cannot both have a customer on the same phone number —
--     and a family that shops at two chemists has one phone.
--   · Document numbers are generated per branch, so the second pharmacy to
--     raise REQ-0001 today is refused.
--   · A supplier invoice number belongs to the supplier, not to us. Two
--     pharmacies buying from different suppliers can hold the same one.
--
-- Found while activating a second tenant: the seeded walk-in customer collided
-- on its phone number with the first pharmacy's.
--
-- Deliberately left global, because they are:
--   pos_refresh_tokens.token_value, pos_invoice_unlock.token_hash — secrets,
--     which must not collide with anything anywhere
--   pos_permissions (role, module) — roles are global
--   pos_stock_movement (ref_type, ref_dtl, movement_type) — ref_dtl is a
--     primary key, already unique across the database
--   tenant_registration.admin_username — one person, one sign-in name
\set ON_ERROR_STOP on
\timing on

BEGIN;

/* ── customers: a phone number identifies a person to one pharmacy ──────── */
ALTER TABLE pharma.pos_customer_info DROP CONSTRAINT IF EXISTS ukj98kk2hopcuofgrbbi29syv1a;
DROP INDEX IF EXISTS pharma.ukj98kk2hopcuofgrbbi29syv1a;
CREATE UNIQUE INDEX IF NOT EXISTS uq_customer_branch_mobile
    ON pharma.pos_customer_info (pid_branch, mobile_no)
 WHERE mobile_no IS NOT NULL;

/* ── product code: a pharmacy's own numbering, or the shared catalogue's ──
   COALESCE, not a plain two-column index: a null branch means the shared
   catalogue, and Postgres treats every null as distinct — which would let the
   catalogue hold the same code twice, the one place it must not. */
ALTER TABLE pharma.pos_product_info DROP CONSTRAINT IF EXISTS ux_product_code;
DROP INDEX IF EXISTS pharma.ux_product_code;
CREATE UNIQUE INDEX IF NOT EXISTS uq_product_branch_code
    ON pharma.pos_product_info (COALESCE(pid_branch, 0), product_code)
 WHERE product_code IS NOT NULL;

/* ── documents each pharmacy numbers for itself ─────────────────────────── */
ALTER TABLE pharma.pos_requisition_mst DROP CONSTRAINT IF EXISTS ukii2hycpswhic98al6jgckg617;
DROP INDEX IF EXISTS pharma.ukii2hycpswhic98al6jgckg617;
CREATE UNIQUE INDEX IF NOT EXISTS uq_requisition_branch_no
    ON pharma.pos_requisition_mst (pid_branch, requisition_no);

ALTER TABLE pharma.pos_invoice_merge DROP CONSTRAINT IF EXISTS ukfcun742n2c2wsqvwuk5fmmoek;
DROP INDEX IF EXISTS pharma.ukfcun742n2c2wsqvwuk5fmmoek;
CREATE UNIQUE INDEX IF NOT EXISTS uq_merge_branch_no
    ON pharma.pos_invoice_merge (pid_branch, merge_no);

ALTER TABLE pharma.pos_supplier_payment DROP CONSTRAINT IF EXISTS ux_supplier_payment_voucher;
DROP INDEX IF EXISTS pharma.ux_supplier_payment_voucher;
CREATE UNIQUE INDEX IF NOT EXISTS uq_supplier_payment_branch_voucher
    ON pharma.pos_supplier_payment (pid_branch, voucher_no);

ALTER TABLE pharma.pos_cart DROP CONSTRAINT IF EXISTS ukooh7ef6orv0gsm7tje2ulx83x;
DROP INDEX IF EXISTS pharma.ukooh7ef6orv0gsm7tje2ulx83x;
CREATE UNIQUE INDEX IF NOT EXISTS uq_cart_branch_ref
    ON pharma.pos_cart (pid_branch, cart_ref);

/* ── a supplier's invoice number belongs to the supplier ────────────────── */
ALTER TABLE pharma.pos_transaction_mst DROP CONSTRAINT IF EXISTS ux_purchase_invoice_no;
DROP INDEX IF EXISTS pharma.ux_purchase_invoice_no;
CREATE UNIQUE INDEX IF NOT EXISTS uq_purchase_branch_invoice_no
    ON pharma.pos_transaction_mst (pid_branch, invoice_no)
 WHERE invoice_no IS NOT NULL AND transaction_type = 'PU';

COMMIT;

SELECT c.relname AS table_name, i.relname AS index_name
  FROM pg_index x
  JOIN pg_class c ON c.oid = x.indrelid
  JOIN pg_class i ON i.oid = x.indexrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'pharma' AND x.indisunique AND NOT x.indisprimary
   AND pg_get_indexdef(x.indexrelid) NOT LIKE '%pid_branch%'
   AND pg_get_indexdef(x.indexrelid) NOT LIKE '%pid_org%'
   AND c.relname NOT LIKE 'sub_%' AND c.relname NOT LIKE 'acl_%'
 ORDER BY c.relname;
