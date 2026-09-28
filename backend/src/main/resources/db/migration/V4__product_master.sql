-- ============================================================================
-- V4 — PRODUCT MASTER
-- Target: PostgreSQL (the live datasource per application.yml).
-- An Oracle-dialect equivalent ships as db/oracle/V4__product_master_oracle.sql
--
-- Adds only the columns Product Master needs that POS_PRODUCT_INFO lacks.
-- Every statement is idempotent so it can be re-run safely.
--
-- Columns REUSED (not touched): PRODUCT_NAME, PID_GENERIC, PID_COMPANY,
--   PID_CATEGORY, PID_SUB_CATEGORY, SKU, PID_BOX, MRP_PRICE, CONVERSION_RATE,
--   TP_PRICE, VAT_PCT, BUY_PRICE, BARCODE, PRESCRIPTION_FLAG, STATUS,
--   ALERT_QTY, STOCK_QTY, IMAGE/FILE_NAME/FILE_MIME_TYPE,
--   INSERTED_BY/INSERTED_DATE/UPDATED_BY/UPDATED_DATE (audit — BaseEntity).
-- ============================================================================

-- ── 1. PRODUCT_CODE ─────────────────────────────────────────────────────────
-- New. Previously PosProductInfo.getProductCode() aliased SKU, which conflated
-- two different business identifiers. Product Code is system-generated and
-- immutable; SKU is user-entered.
ALTER TABLE PHARMA.POS_PRODUCT_INFO
    ADD COLUMN IF NOT EXISTS PRODUCT_CODE VARCHAR(30);

COMMENT ON COLUMN PHARMA.POS_PRODUCT_INFO.PRODUCT_CODE IS
    'System-generated immutable product code (PRD######), derived from PID_PRODUCT. Never user-editable.';

-- ── 2. IGTA — type correction ───────────────────────────────────────────────
-- IGTA is a calculated monetary amount (TP_PRICE * VAT_PCT / 100) but was
-- declared VARCHAR2(50) during the Oracle import. The column is referenced
-- nowhere in application code, so the conversion is safe.
DO $migration$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'pharma' AND table_name = 'pos_product_info'
          AND column_name = 'igta' AND data_type IN ('character varying', 'character')
    ) THEN
        ALTER TABLE PHARMA.POS_PRODUCT_INFO
            ALTER COLUMN IGTA TYPE NUMERIC(14,4)
            USING NULLIF(REGEXP_REPLACE(IGTA, '[^0-9.-]', '', 'g'), '')::NUMERIC;
    END IF;
END
$migration$;

COMMENT ON COLUMN PHARMA.POS_PRODUCT_INFO.IGTA IS
    'Calculated: ROUND(TP_PRICE * VAT_PCT / 100, 2). Server-calculated only.';

-- ── 3. Stock control levels ─────────────────────────────────────────────────
-- REORDER_LEVEL is new. ALERT_QTY is retained and kept in sync by the service
-- layer because InventoryServiceImpl and
-- PosProductInfoRepository.findLowStockProducts() still read ALERT_QTY.
ALTER TABLE PHARMA.POS_PRODUCT_INFO ADD COLUMN IF NOT EXISTS REORDER_LEVEL NUMERIC(14,4) DEFAULT 0;
ALTER TABLE PHARMA.POS_PRODUCT_INFO ADD COLUMN IF NOT EXISTS SAFETY_STOCK  NUMERIC(14,4) DEFAULT 0;
ALTER TABLE PHARMA.POS_PRODUCT_INFO ADD COLUMN IF NOT EXISTS MINIMUM_STOCK NUMERIC(14,4) DEFAULT 0;
ALTER TABLE PHARMA.POS_PRODUCT_INFO ADD COLUMN IF NOT EXISTS MAXIMUM_STOCK NUMERIC(14,4) DEFAULT 0;

COMMENT ON COLUMN PHARMA.POS_PRODUCT_INFO.REORDER_LEVEL IS 'Stock level that triggers reorder. Mirrored into ALERT_QTY for the legacy Inventory module.';
COMMENT ON COLUMN PHARMA.POS_PRODUCT_INFO.SAFETY_STOCK  IS 'Reserve buffer stock.';
COMMENT ON COLUMN PHARMA.POS_PRODUCT_INFO.MINIMUM_STOCK IS 'Absolute operational minimum.';
COMMENT ON COLUMN PHARMA.POS_PRODUCT_INFO.MAXIMUM_STOCK IS 'Maximum desired stock. 0 = no ceiling configured.';

-- Backfill REORDER_LEVEL from the legacy ALERT_QTY so existing rows stay correct.
UPDATE PHARMA.POS_PRODUCT_INFO
   SET REORDER_LEVEL = ALERT_QTY
 WHERE ALERT_QTY IS NOT NULL
   AND (REORDER_LEVEL IS NULL OR REORDER_LEVEL = 0);

-- ── 4. Tracking / compliance flags ──────────────────────────────────────────
-- Y/N CHAR(1) to match the existing PRESCRIPTION_FLAG convention.
ALTER TABLE PHARMA.POS_PRODUCT_INFO ADD COLUMN IF NOT EXISTS BATCH_TRACKING  CHAR(1) DEFAULT 'N';
ALTER TABLE PHARMA.POS_PRODUCT_INFO ADD COLUMN IF NOT EXISTS EXPIRY_TRACKING CHAR(1) DEFAULT 'N';

UPDATE PHARMA.POS_PRODUCT_INFO SET BATCH_TRACKING    = 'N' WHERE BATCH_TRACKING    IS NULL;
UPDATE PHARMA.POS_PRODUCT_INFO SET EXPIRY_TRACKING   = 'N' WHERE EXPIRY_TRACKING   IS NULL;
UPDATE PHARMA.POS_PRODUCT_INFO SET PRESCRIPTION_FLAG = 'N' WHERE PRESCRIPTION_FLAG IS NULL;

COMMENT ON COLUMN PHARMA.POS_PRODUCT_INFO.BATCH_TRACKING  IS 'Y = batch number mandatory in purchase/inventory transactions.';
COMMENT ON COLUMN PHARMA.POS_PRODUCT_INFO.EXPIRY_TRACKING IS 'Y = expiry date mandatory in purchase/inventory transactions.';

-- ── 5. Product image path ───────────────────────────────────────────────────
-- Images move from the IMAGE BLOB to disk storage under a configurable
-- directory (app.upload.product-image-dir). IMAGE / FILE_MIME_TYPE / FILE_NAME
-- are retained so legacy rows keep rendering until they are re-uploaded.
ALTER TABLE PHARMA.POS_PRODUCT_INFO ADD COLUMN IF NOT EXISTS PRODUCT_IMAGE VARCHAR(300);

COMMENT ON COLUMN PHARMA.POS_PRODUCT_INFO.PRODUCT_IMAGE IS
    'Stored filename only (never a client-supplied path). Public URL = app.upload.product-image-url-prefix || PRODUCT_IMAGE.';

-- ── 6. Unit foreign key ─────────────────────────────────────────────────────
-- PID_UNIT is VARCHAR(20) holding the unit NAME, not a key. The POS and
-- transaction modules read that string, so it stays; PID_UNIT_ID adds the real
-- FK and the service layer writes both.
ALTER TABLE PHARMA.POS_PRODUCT_INFO ADD COLUMN IF NOT EXISTS PID_UNIT_ID BIGINT;

COMMENT ON COLUMN PHARMA.POS_PRODUCT_INFO.PID_UNIT_ID IS
    'FK to POS_PRODUCT_UNIT.PID_UNIT. PID_UNIT (varchar) retains the denormalised unit name for the POS/transaction modules.';

-- ── 7. Drop NO_OF_PCS ───────────────────────────────────────────────────────
-- Business rule: pieces-per-box is derived (POS_BOX_INFO.NO_OF_LEAF ×
-- POS_LEAF_INFO.NO_OF_PCS) and must not be duplicated on the product row.
-- The column was never read or written by application code.
ALTER TABLE PHARMA.POS_PRODUCT_INFO DROP COLUMN IF EXISTS NO_OF_PCS;

-- ── 8. Widen PRODUCT_NAME ───────────────────────────────────────────────────
-- 50 chars truncates real pharmaceutical names, e.g.
-- "Amoxicillin + Clavulanic Acid 875mg/125mg Film-Coated Tablet".
ALTER TABLE PHARMA.POS_PRODUCT_INFO ALTER COLUMN PRODUCT_NAME TYPE VARCHAR(150);

-- ── 9. Backfill PRODUCT_CODE for pre-existing rows ──────────────────────────
UPDATE PHARMA.POS_PRODUCT_INFO
   SET PRODUCT_CODE = 'PRD' || LPAD(PID_PRODUCT::TEXT, 6, '0')
 WHERE PRODUCT_CODE IS NULL;

-- ── 10. Constraints ─────────────────────────────────────────────────────────
-- Uniqueness is scoped per organisation: two tenants may legitimately reuse a
-- SKU or barcode. PRODUCT_CODE is globally unique (derived from the PK).
-- Partial indexes exclude NULLs so optional fields stay optional.

CREATE UNIQUE INDEX IF NOT EXISTS UX_PRODUCT_CODE
    ON PHARMA.POS_PRODUCT_INFO (PRODUCT_CODE)
    WHERE PRODUCT_CODE IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS UX_PRODUCT_SKU_ORG
    ON PHARMA.POS_PRODUCT_INFO (PID_ORG, UPPER(SKU))
    WHERE SKU IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS UX_PRODUCT_BARCODE_ORG
    ON PHARMA.POS_PRODUCT_INFO (PID_ORG, UPPER(BARCODE))
    WHERE BARCODE IS NOT NULL AND BARCODE <> '';

-- Search / filter indexes for the Product List grid.
CREATE INDEX IF NOT EXISTS IX_PRODUCT_ORG_STATUS ON PHARMA.POS_PRODUCT_INFO (PID_ORG, STATUS);
CREATE INDEX IF NOT EXISTS IX_PRODUCT_NAME_LOWER ON PHARMA.POS_PRODUCT_INFO (LOWER(PRODUCT_NAME));
CREATE INDEX IF NOT EXISTS IX_PRODUCT_CATEGORY   ON PHARMA.POS_PRODUCT_INFO (PID_CATEGORY);
CREATE INDEX IF NOT EXISTS IX_PRODUCT_COMPANY    ON PHARMA.POS_PRODUCT_INFO (PID_COMPANY);
CREATE INDEX IF NOT EXISTS IX_PRODUCT_GENERIC    ON PHARMA.POS_PRODUCT_INFO (PID_GENERIC);

-- Check constraints — the database is the last line of defence for the
-- calculated/price fields the API refuses to trust from the client.
DO $migration$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_product_prices_nonneg') THEN
        ALTER TABLE PHARMA.POS_PRODUCT_INFO ADD CONSTRAINT ck_product_prices_nonneg CHECK (
            COALESCE(MRP_PRICE,0)       >= 0 AND
            COALESCE(TP_PRICE,0)        >= 0 AND
            COALESCE(BUY_PRICE,0)       >= 0 AND
            COALESCE(IGTA,0)            >= 0 AND
            COALESCE(CONVERSION_RATE,0) >= 0 AND
            COALESCE(VAT_PCT,0)         >= 0
        );
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_product_pct_range') THEN
        ALTER TABLE PHARMA.POS_PRODUCT_INFO ADD CONSTRAINT ck_product_pct_range CHECK (
            COALESCE(VAT_PCT,0)         <= 100 AND
            COALESCE(CONVERSION_RATE,0) <= 100
        );
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_product_stock_nonneg') THEN
        ALTER TABLE PHARMA.POS_PRODUCT_INFO ADD CONSTRAINT ck_product_stock_nonneg CHECK (
            COALESCE(REORDER_LEVEL,0) >= 0 AND
            COALESCE(SAFETY_STOCK,0)  >= 0 AND
            COALESCE(MINIMUM_STOCK,0) >= 0 AND
            COALESCE(MAXIMUM_STOCK,0) >= 0
        );
    END IF;

    -- MINIMUM <= SAFETY <= REORDER <= MAXIMUM.
    -- MAXIMUM_STOCK = 0 means "no ceiling configured" and is exempt, otherwise
    -- every existing row with unset levels would fail the constraint.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_product_stock_ladder') THEN
        ALTER TABLE PHARMA.POS_PRODUCT_INFO ADD CONSTRAINT ck_product_stock_ladder CHECK (
            COALESCE(MINIMUM_STOCK,0) <= COALESCE(SAFETY_STOCK,0)
        AND COALESCE(SAFETY_STOCK,0)  <= COALESCE(REORDER_LEVEL,0)
        AND (COALESCE(MAXIMUM_STOCK,0) = 0
             OR COALESCE(REORDER_LEVEL,0) <= COALESCE(MAXIMUM_STOCK,0))
        );
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_product_flags_yn') THEN
        ALTER TABLE PHARMA.POS_PRODUCT_INFO ADD CONSTRAINT ck_product_flags_yn CHECK (
            COALESCE(BATCH_TRACKING,'N')    IN ('Y','N') AND
            COALESCE(EXPIRY_TRACKING,'N')   IN ('Y','N') AND
            COALESCE(PRESCRIPTION_FLAG,'N') IN ('Y','N') AND
            COALESCE(STATUS,'Y')            IN ('Y','N')
        );
    END IF;
END
$migration$;

-- ── 11. Foreign keys ────────────────────────────────────────────────────────
-- NOT VALID: enforced for new/updated rows without failing the migration on
-- pre-existing orphan references. Run VALIDATE CONSTRAINT after data cleanup.
DO $migration$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_product_unit') THEN
        ALTER TABLE PHARMA.POS_PRODUCT_INFO
            ADD CONSTRAINT fk_product_unit FOREIGN KEY (PID_UNIT_ID)
            REFERENCES PHARMA.POS_PRODUCT_UNIT (PID_UNIT) NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_product_box') THEN
        ALTER TABLE PHARMA.POS_PRODUCT_INFO
            ADD CONSTRAINT fk_product_box FOREIGN KEY (PID_BOX)
            REFERENCES PHARMA.POS_BOX_INFO (PID_BOX) NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_product_generic') THEN
        ALTER TABLE PHARMA.POS_PRODUCT_INFO
            ADD CONSTRAINT fk_product_generic FOREIGN KEY (PID_GENERIC)
            REFERENCES PHARMA.POS_GENERIC_INFO (PID_GENERIC) NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_product_category') THEN
        ALTER TABLE PHARMA.POS_PRODUCT_INFO
            ADD CONSTRAINT fk_product_category FOREIGN KEY (PID_CATEGORY)
            REFERENCES PHARMA.POS_PRODUCT_CATEGORY (PID_CATEGORY) NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_product_company') THEN
        ALTER TABLE PHARMA.POS_PRODUCT_INFO
            ADD CONSTRAINT fk_product_company FOREIGN KEY (PID_COMPANY)
            REFERENCES PHARMA.POS_COMPANY_INFO (PID_COMPANY) NOT VALID;
    END IF;
END
$migration$;

-- ── 12. Menu integration — Master Data → Product ────────────────────────────
-- Registers the module in ACL_MODULE_INFO so the RBAC-driven Sidebar picks it
-- up, then grants every existing role full rights on it.
--
-- Column names here follow the AclModuleInfo / PosPermissions ENTITIES
-- (MODULE_CODE, MODULE_NAME, ROUTE_PATH, IS_ACTIVE; permissions keyed on
-- PID_ROLE with integer 0/1 flags). Note this differs from V3__seed_rbac.sql,
-- which was written against the original Oracle schema (MODULE_TITLE,
-- ROUTE_KEY, PID_GROUP, 'Y'/'N') that the entities no longer match. The
-- entity-driven shape is the one the running database actually has.
-- Existing module rows were seeded without drawing from SQ_ACL_MODULE / SQ_POS_PERM,
-- so the sequences lag behind MAX(id) and NEXTVAL would collide. Fast-forward
-- them before inserting.
SELECT SETVAL('PHARMA.SQ_ACL_MODULE',
              GREATEST((SELECT COALESCE(MAX(PID_MODULE), 0) FROM PHARMA.ACL_MODULE_INFO), 1));
SELECT SETVAL('PHARMA.SQ_POS_PERM',
              GREATEST((SELECT COALESCE(MAX(PID_PERMISSION), 0) FROM PHARMA.POS_PERMISSIONS), 1));

INSERT INTO PHARMA.ACL_MODULE_INFO
    (PID_MODULE, MODULE_CODE, MODULE_NAME, MODULE_DESC, ICON_KEY, ROUTE_PATH,
     SORT_ORDER, IS_ACTIVE, STATUS, INSERTED_DATE, INSERTED_BY)
SELECT NEXTVAL('PHARMA.SQ_ACL_MODULE'), 'MOD17', 'Product Master',
       'Pharmaceutical product catalogue', 'productmaster', '/productmaster',
       3, 1, 'Y', CURRENT_TIMESTAMP, 'SYSTEM'
WHERE NOT EXISTS (
    SELECT 1 FROM PHARMA.ACL_MODULE_INFO WHERE ROUTE_PATH = '/productmaster');

INSERT INTO PHARMA.POS_PERMISSIONS
    (PID_PERMISSION, PID_ROLE, PID_MODULE,
     CAN_VIEW, CAN_CREATE, CAN_EDIT, CAN_DELETE, CAN_APPROVE, CAN_EXPORT,
     STATUS, INSERTED_DATE, INSERTED_BY)
SELECT NEXTVAL('PHARMA.SQ_POS_PERM'), g.PID_GROUP, m.PID_MODULE,
       1, 1, 1, 1, 1, 1, 'Y', CURRENT_TIMESTAMP, 'SYSTEM'
  FROM PHARMA.ACL_MODULE_INFO m
 CROSS JOIN PHARMA.ACL_GROUP g
 WHERE m.ROUTE_PATH = '/productmaster'
   AND g.ROLE_CODE IN ('ROLE01','ROLE02','ROLE05')
   AND NOT EXISTS (
       SELECT 1 FROM PHARMA.POS_PERMISSIONS p
        WHERE p.PID_ROLE = g.PID_GROUP AND p.PID_MODULE = m.PID_MODULE);
