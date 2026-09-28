-- ============================================================================
-- V8 — SALES PERCENTAGE (Sales%)
-- Target: PostgreSQL.
--
-- The six SALES_PER* columns on POS_PRODUCT_INFO hold the discount off MRP used
-- to derive each unit's sale price, per the SRS:
--
--     Unit Sales Price = MRP - (MRP x Sales Percentage / 100)
--
--   SALES_PER            BOX   %  regular  (wholesale / retail)
--   SALES_PER_LEAF       STRIP %  regular
--   SALES_PER_PCS        PCS   %  regular
--   SALES_PER_SPC        BOX   %  special customer
--   SALES_PER_SPC_LEAF   STRIP %  special customer
--   SALES_PER_SPC_RET    PCS   %  special customer
--
-- All six were INTEGER, so a rate like 12.5% could not be stored — it silently
-- truncated to 12. They are widened to NUMERIC(8,4) here. Every one of them is
-- currently NULL across all products, so nothing is lost in the conversion.
--
-- Distinct from POS_SALES_DISCOUNT, which is a separate per-line discount rules
-- engine. These columns are pricing; that table is discounting. Both stay.
--
-- Idempotent; safe to re-run.
-- ============================================================================

-- ── 1. Widen to a real percentage ───────────────────────────────────────────
DO $migration$
DECLARE
    col TEXT;
BEGIN
    FOREACH col IN ARRAY ARRAY[
        'sales_per', 'sales_per_leaf', 'sales_per_pcs',
        'sales_per_spc', 'sales_per_spc_leaf', 'sales_per_spc_ret'
    ] LOOP
        IF EXISTS (
            SELECT 1 FROM information_schema.columns
             WHERE table_schema = 'pharma' AND table_name = 'pos_product_info'
               AND column_name = col AND data_type <> 'numeric'
        ) THEN
            EXECUTE format(
                'ALTER TABLE PHARMA.POS_PRODUCT_INFO ALTER COLUMN %I TYPE NUMERIC(8,4) USING %I::NUMERIC',
                col, col);
        END IF;
    END LOOP;
END
$migration$;

COMMENT ON COLUMN PHARMA.POS_PRODUCT_INFO.SALES_PER          IS 'Sales % off MRP for a BOX, regular customer. Sale price = MRP - (MRP x pct / 100).';
COMMENT ON COLUMN PHARMA.POS_PRODUCT_INFO.SALES_PER_LEAF     IS 'Sales % off MRP for a STRIP/LEAF, regular customer.';
COMMENT ON COLUMN PHARMA.POS_PRODUCT_INFO.SALES_PER_PCS      IS 'Sales % off MRP for a PCS, regular customer.';
COMMENT ON COLUMN PHARMA.POS_PRODUCT_INFO.SALES_PER_SPC      IS 'Sales % off MRP for a BOX, special customer.';
COMMENT ON COLUMN PHARMA.POS_PRODUCT_INFO.SALES_PER_SPC_LEAF IS 'Sales % off MRP for a STRIP/LEAF, special customer.';
COMMENT ON COLUMN PHARMA.POS_PRODUCT_INFO.SALES_PER_SPC_RET  IS 'Sales % off MRP for a PCS, special customer.';

-- ── 2. Keep them inside 0–100 ───────────────────────────────────────────────
-- A percentage above 100 would produce a negative sale price; below zero would
-- price above MRP. Null stays allowed and means "not configured for this unit",
-- which the SRS treats as not sellable in that unit.
DO $migration$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_product_sales_pct_range') THEN
        ALTER TABLE PHARMA.POS_PRODUCT_INFO ADD CONSTRAINT ck_product_sales_pct_range CHECK (
            (SALES_PER          IS NULL OR SALES_PER          BETWEEN 0 AND 100) AND
            (SALES_PER_LEAF     IS NULL OR SALES_PER_LEAF     BETWEEN 0 AND 100) AND
            (SALES_PER_PCS      IS NULL OR SALES_PER_PCS      BETWEEN 0 AND 100) AND
            (SALES_PER_SPC      IS NULL OR SALES_PER_SPC      BETWEEN 0 AND 100) AND
            (SALES_PER_SPC_LEAF IS NULL OR SALES_PER_SPC_LEAF BETWEEN 0 AND 100) AND
            (SALES_PER_SPC_RET  IS NULL OR SALES_PER_SPC_RET  BETWEEN 0 AND 100)
        );
    END IF;
END
$migration$;

-- ── 3. Index for "which products still need pricing?" ───────────────────────
-- The Sales% screen's most-used filter is the unconfigured list, so it is worth
-- a partial index rather than a scan of the whole catalogue.
CREATE INDEX IF NOT EXISTS IX_PRODUCT_SALES_PCT_UNSET
    ON PHARMA.POS_PRODUCT_INFO (PID_ORG)
    WHERE SALES_PER IS NULL AND SALES_PER_LEAF IS NULL AND SALES_PER_PCS IS NULL;
