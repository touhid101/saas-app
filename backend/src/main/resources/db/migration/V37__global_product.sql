-- The Super Admin's catalogue, and the link back from a pharmacy's copy of it.
--
-- Bangladesh sells the same few thousand medicines in every pharmacy. Making each
-- new customer type Napa, Seclo and Monas in by hand before they can sell
-- anything is the slowest part of opening an account, and it produces a thousand
-- spellings of the same drug.
--
-- So: one catalogue the Super Admin curates, and an import that copies a row into
-- a pharmacy. The copy is a real POS_PRODUCT_INFO row carrying that branch — its
-- price, its stock, its supplier, its reorder level are its own from the moment
-- it lands, and editing the catalogue afterwards does not reach into anybody's
-- shelf.
--
-- On the rows already in POS_PRODUCT_INFO with a null branch: those are from
-- before any of this, and the product queries treat them as visible to everyone.
-- They are left exactly as they are — four hundred thousand transaction lines
-- point at them — and the catalogue below is seeded from them so a new pharmacy
-- can import the same list. Null-branch is a legacy shim, not the design.
\set ON_ERROR_STOP on
\timing on

BEGIN;

CREATE TABLE IF NOT EXISTS pharma.global_product (
    pid_global_product bigint       PRIMARY KEY,
    product_name       varchar(200) NOT NULL,
    generic_name       varchar(200),
    company_name       varchar(150),
    category_name      varchar(100),
    strength           varchar(60),
    -- What a pharmacy starts from, not what it must charge. Every price is
    -- copied into the branch's own row on import and is theirs to change.
    suggested_mrp      numeric(18,4),
    suggested_tp       numeric(18,4),
    unit_name          varchar(50),
    pcs_per_leaf       integer,
    leaf_per_box       integer,
    prescription_flag  varchar(1)   DEFAULT 'N',
    barcode            varchar(60),
    product_code       varchar(60),
    file_name          varchar(200),
    file_mime_type     varchar(100),
    -- The filename on disk, matching POS_PRODUCT_INFO. A byte[] LOB maps to an
    -- oid in PostgreSQL rather than bytea, and a catalogue of photographs is
    -- tens of megabytes read every time somebody lists names.
    product_image      varchar(300),
    is_active          integer      NOT NULL DEFAULT 1,
    status             varchar(1)   DEFAULT 'Y',
    inserted_by        varchar(50),
    inserted_date      timestamp    DEFAULT CURRENT_TIMESTAMP,
    updated_by         varchar(50),
    updated_date       timestamp
);

CREATE INDEX IF NOT EXISTS ix_global_product_name    ON pharma.global_product (LOWER(product_name));
CREATE INDEX IF NOT EXISTS ix_global_product_company ON pharma.global_product (company_name);

/* ── the link back ── */
ALTER TABLE pharma.pos_product_info
    ADD COLUMN IF NOT EXISTS pid_global_product bigint;

-- What makes importing twice a no-op rather than a duplicate. The application
-- checks too, but two administrators importing the same catalogue at the same
-- moment would both pass that check and only one can win here.
CREATE UNIQUE INDEX IF NOT EXISTS uq_branch_global_product
    ON pharma.pos_product_info (pid_branch, pid_global_product)
 WHERE pid_global_product IS NOT NULL;

CREATE SEQUENCE IF NOT EXISTS pharma.sq_global_product START 100;

/* ── seed the catalogue from what is already known ──
   The existing products are the real medicines this business sells, so they are
   a better starting catalogue than anything invented here. Copied, not moved:
   the originals stay where they are and keep working. */
INSERT INTO pharma.global_product
       (pid_global_product, product_name, generic_name, company_name, category_name,
        suggested_mrp, suggested_tp, unit_name, prescription_flag, barcode, product_code,
        is_active, status, inserted_by)
SELECT nextval('pharma.sq_global_product'),
       p.product_name, g.generic_name, c.company_name, cat.category_name,
       -- POS_PRODUCT_INFO carries both: PID_UNIT_ID is the key and PID_UNIT is
       -- the name typed against it. Prefer the joined name, fall back to the text.
       p.mrp_price, p.tp_price, COALESCE(u.unit_name, p.pid_unit),
       COALESCE(p.prescription_flag, 'N'), p.barcode, p.product_code,
       1, 'Y', 'V37'
  FROM pharma.pos_product_info p
  LEFT JOIN pharma.pos_generic_info     g   ON g.pid_generic   = p.pid_generic
  LEFT JOIN pharma.pos_company_info     c   ON c.pid_company   = p.pid_company
  LEFT JOIN pharma.pos_product_category cat ON cat.pid_category = p.pid_category
  LEFT JOIN pharma.pos_product_unit     u   ON u.pid_unit      = p.pid_unit_id
 WHERE COALESCE(p.status, 'Y') = 'Y'
   AND NOT EXISTS (SELECT 1 FROM pharma.global_product gp
                    WHERE LOWER(gp.product_name) = LOWER(p.product_name));

COMMIT;

SELECT count(*) AS catalogue_products,
       count(*) FILTER (WHERE suggested_mrp IS NOT NULL) AS priced,
       count(DISTINCT company_name)                      AS companies
  FROM pharma.global_product;
