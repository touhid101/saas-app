-- Three more unique constraints that stop at the organisation, not the pharmacy.
--
-- V35 swept the constraints that were global and missed these, because the sweep
-- excluded anything already mentioning PID_ORG as "tenant-scoped". Under
-- branch-as-tenant that reasoning is wrong: an org holds a chain, and two outlets
-- of one chain both stocking Napa is the ordinary case, not a duplicate.
--
-- Found the way these things are found — importing the catalogue into a second
-- branch failed on UX_PRODUCT_BARCODE_ORG, because the barcode on a box of Napa
-- is the same barcode in every shop that sells it. That is what a barcode is for.
--
-- SKU and barcode are now unique per branch, and the discount tier per branch,
-- which is where each of them actually means something.
\set ON_ERROR_STOP on
\timing on

BEGIN;

/* ── SKU: a pharmacy's own numbering ── */
ALTER TABLE pharma.pos_product_info DROP CONSTRAINT IF EXISTS ux_product_sku_org;
DROP INDEX IF EXISTS pharma.ux_product_sku_org;
CREATE UNIQUE INDEX IF NOT EXISTS uq_product_branch_sku
    ON pharma.pos_product_info (COALESCE(pid_branch, 0), UPPER(sku))
 WHERE sku IS NOT NULL;

/* ── barcode: the manufacturer's, and the same in every shop ── */
ALTER TABLE pharma.pos_product_info DROP CONSTRAINT IF EXISTS ux_product_barcode_org;
DROP INDEX IF EXISTS pharma.ux_product_barcode_org;
CREATE UNIQUE INDEX IF NOT EXISTS uq_product_branch_barcode
    ON pharma.pos_product_info (COALESCE(pid_branch, 0), UPPER(barcode))
 WHERE barcode IS NOT NULL AND barcode <> '';

/* ── a discount is one pharmacy's commercial decision ── */
ALTER TABLE pharma.pos_sales_discount DROP CONSTRAINT IF EXISTS uq_sales_disc;
DROP INDEX IF EXISTS pharma.uq_sales_disc;
CREATE UNIQUE INDEX IF NOT EXISTS uq_sales_disc_branch
    ON pharma.pos_sales_discount (pid_branch, pid_company, pid_product, discount_tier);

COMMIT;

-- Nothing unique should now be narrower than the pharmacy, except the handful
-- that must be global: session tokens, role permissions, movement references and
-- sign-in names.
SELECT c.relname AS table_name, i.relname AS index_name
  FROM pg_index x
  JOIN pg_class c ON c.oid = x.indrelid
  JOIN pg_class i ON i.oid = x.indexrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'pharma' AND x.indisunique AND NOT x.indisprimary
   AND pg_get_indexdef(x.indexrelid) NOT LIKE '%pid_branch%'
   AND c.relname NOT LIKE 'acl_%' AND c.relname NOT LIKE 'sub_%'
   AND c.relname NOT IN ('pos_refresh_tokens', 'pos_invoice_unlock', 'pos_stock_movement',
                         'tenant_registration', 'global_product')
 ORDER BY c.relname;
