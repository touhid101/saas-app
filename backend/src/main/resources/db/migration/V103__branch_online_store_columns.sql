/* ─────────────────────────────────────────────────────────────────────────────
   V103 — E-commerce Phase 2: what a pharmacy needs to be found online.

   ALL_BRANCH gains the columns the marketplace reads to list a pharmacy:
   whether it sells online at all, where it is, how far it delivers, the
   least it will deliver for, when it is open, and a line to introduce it.

   Every column is nullable or defaulted, and ECOM_ENABLED defaults to N:
   no pharmacy appears online until its administrator turns it on. Nothing
   existing changes meaning; the ERP reads none of these.
   ───────────────────────────────────────────────────────────────────────────── */

ALTER TABLE pharma.all_branch
    ADD COLUMN IF NOT EXISTS ecom_enabled            VARCHAR(1)     NOT NULL DEFAULT 'N',
    ADD COLUMN IF NOT EXISTS latitude                NUMERIC(9,6),
    ADD COLUMN IF NOT EXISTS longitude               NUMERIC(9,6),
    ADD COLUMN IF NOT EXISTS ecom_delivery_radius_km NUMERIC(6,2),
    ADD COLUMN IF NOT EXISTS ecom_min_order_amount   NUMERIC(14,2),
    ADD COLUMN IF NOT EXISTS ecom_opening_hours      VARCHAR(200),
    ADD COLUMN IF NOT EXISTS ecom_description        VARCHAR(500);

ALTER TABLE pharma.all_branch DROP CONSTRAINT IF EXISTS ck_branch_ecom_enabled;
ALTER TABLE pharma.all_branch ADD CONSTRAINT ck_branch_ecom_enabled CHECK (ecom_enabled IN ('Y', 'N'));
ALTER TABLE pharma.all_branch DROP CONSTRAINT IF EXISTS ck_branch_latitude;
ALTER TABLE pharma.all_branch ADD CONSTRAINT ck_branch_latitude  CHECK (latitude  IS NULL OR (latitude  BETWEEN -90  AND 90));
ALTER TABLE pharma.all_branch DROP CONSTRAINT IF EXISTS ck_branch_longitude;
ALTER TABLE pharma.all_branch ADD CONSTRAINT ck_branch_longitude CHECK (longitude IS NULL OR (longitude BETWEEN -180 AND 180));
ALTER TABLE pharma.all_branch DROP CONSTRAINT IF EXISTS ck_branch_ecom_radius;
ALTER TABLE pharma.all_branch ADD CONSTRAINT ck_branch_ecom_radius CHECK (ecom_delivery_radius_km IS NULL OR ecom_delivery_radius_km > 0);
ALTER TABLE pharma.all_branch DROP CONSTRAINT IF EXISTS ck_branch_ecom_min_order;
ALTER TABLE pharma.all_branch ADD CONSTRAINT ck_branch_ecom_min_order CHECK (ecom_min_order_amount IS NULL OR ecom_min_order_amount >= 0);

/* The marketplace reads only the pharmacies that are switched on. Small
   table; the partial index keeps that read a scan of a handful of rows. */
CREATE INDEX IF NOT EXISTS ix_branch_ecom_enabled
    ON pharma.all_branch (ecom_enabled) WHERE ecom_enabled = 'Y';

COMMENT ON COLUMN pharma.all_branch.ecom_enabled IS
  'Y when this pharmacy sells on the e-commerce platform. Off by default; set from Master Data > Branch.';
COMMENT ON COLUMN pharma.all_branch.ecom_delivery_radius_km IS
  'How far this pharmacy delivers, in km from its coordinates. NULL = not stated.';

SELECT count(*) FILTER (WHERE ecom_enabled = 'Y') AS online_pharmacies, count(*) AS branches
  FROM pharma.all_branch;
