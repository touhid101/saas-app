-- The catalogue's masters were loose text. Now they are lists.
--
-- GLOBAL_PRODUCT held its manufacturer, generic, category, sub-category and unit
-- as free-typed varchars. Nothing stopped "Square Pharmaceuticals", "Square
-- Pharma" and "square pharmaceuticals" being three manufacturers, and every
-- pharmacy that imported them would have inherited all three as separate rows.
-- Packaging was worse: two loose integers with no name, repeated on every entry.
--
-- ── what this does NOT change ──
-- Import still copies. A pharmacy keeps its own manufacturer, generic, category,
-- sub-category, unit, leaf and box rows, still stamped with its own branch and
-- company, still covered by the NOT NULL constraints from V43 and by the tenancy
-- gate. These tables describe the catalogue; they are not shared shelves.
--
-- ── how the two layers meet ──
-- GLOBAL_PRODUCT keeps its *_NAME columns. They stay the contract the import
-- reads, so the import path — which is proven and tenancy-critical — does not
-- change at all. What changes is where the names come from: the FK is the truth
-- and the name is written from it, so a catalogue entry can no longer name a
-- manufacturer that does not exist.
\set ON_ERROR_STOP on

BEGIN;

/* ── the lists ── */
CREATE TABLE IF NOT EXISTS pharma.global_company (
    pid_global_company bigserial PRIMARY KEY,
    company_name       varchar(150) NOT NULL,
    status             varchar(1)   NOT NULL DEFAULT 'Y',
    inserted_by        varchar(50),
    inserted_date      timestamp DEFAULT now(),
    updated_by         varchar(50),
    updated_date       timestamp,
    CONSTRAINT uq_global_company_name UNIQUE (company_name)
);

CREATE TABLE IF NOT EXISTS pharma.global_generic (
    pid_global_generic bigserial PRIMARY KEY,
    generic_name       varchar(150) NOT NULL,
    description        varchar(500),
    status             varchar(1)   NOT NULL DEFAULT 'Y',
    inserted_by        varchar(50),
    inserted_date      timestamp DEFAULT now(),
    updated_by         varchar(50),
    updated_date       timestamp,
    CONSTRAINT uq_global_generic_name UNIQUE (generic_name)
);

CREATE TABLE IF NOT EXISTS pharma.global_category (
    pid_global_category bigserial PRIMARY KEY,
    category_name       varchar(100) NOT NULL,
    status              varchar(1)   NOT NULL DEFAULT 'Y',
    inserted_by         varchar(50),
    inserted_date       timestamp DEFAULT now(),
    updated_by          varchar(50),
    updated_date        timestamp,
    CONSTRAINT uq_global_category_name UNIQUE (category_name)
);

/* Unique on (category, name): two categories may each have a "Tablet", and they
   are different things. Same rule the pharmacy-side table follows. */
CREATE TABLE IF NOT EXISTS pharma.global_sub_category (
    pid_global_sub_category bigserial PRIMARY KEY,
    pid_global_category     bigint       NOT NULL
        REFERENCES pharma.global_category (pid_global_category),
    sub_category_name       varchar(100) NOT NULL,
    status                  varchar(1)   NOT NULL DEFAULT 'Y',
    inserted_by             varchar(50),
    inserted_date           timestamp DEFAULT now(),
    updated_by              varchar(50),
    updated_date            timestamp,
    CONSTRAINT uq_global_sub_category UNIQUE (pid_global_category, sub_category_name)
);

CREATE TABLE IF NOT EXISTS pharma.global_unit (
    pid_global_unit bigserial PRIMARY KEY,
    unit_name       varchar(50) NOT NULL,
    status          varchar(1)  NOT NULL DEFAULT 'Y',
    inserted_by     varchar(50),
    inserted_date   timestamp DEFAULT now(),
    updated_by      varchar(50),
    updated_date    timestamp,
    CONSTRAINT uq_global_unit_name UNIQUE (unit_name)
);

/* Leaf and box together, because neither is usable alone: a box is a count of
   leaves and a leaf is a count of pieces. Storing the pair as one named row is
   what lets the catalogue offer "10 x 10" instead of two bare integers. */
CREATE TABLE IF NOT EXISTS pharma.global_packaging (
    pid_global_packaging bigserial PRIMARY KEY,
    packaging_title      varchar(60) NOT NULL,
    pcs_per_leaf         integer     NOT NULL,
    leaf_per_box         integer     NOT NULL,
    status               varchar(1)  NOT NULL DEFAULT 'Y',
    inserted_by          varchar(50),
    inserted_date        timestamp DEFAULT now(),
    updated_by           varchar(50),
    updated_date         timestamp,
    CONSTRAINT uq_global_packaging UNIQUE (pcs_per_leaf, leaf_per_box),
    CONSTRAINT ck_global_packaging_positive CHECK (pcs_per_leaf > 0 AND leaf_per_box > 0)
);

/* ── the catalogue points at them ── */
ALTER TABLE pharma.global_product
  ADD COLUMN IF NOT EXISTS pid_global_company      bigint REFERENCES pharma.global_company (pid_global_company),
  ADD COLUMN IF NOT EXISTS pid_global_generic      bigint REFERENCES pharma.global_generic (pid_global_generic),
  ADD COLUMN IF NOT EXISTS pid_global_category     bigint REFERENCES pharma.global_category (pid_global_category),
  ADD COLUMN IF NOT EXISTS pid_global_sub_category bigint REFERENCES pharma.global_sub_category (pid_global_sub_category),
  ADD COLUMN IF NOT EXISTS pid_global_unit         bigint REFERENCES pharma.global_unit (pid_global_unit),
  ADD COLUMN IF NOT EXISTS pid_global_packaging    bigint REFERENCES pharma.global_packaging (pid_global_packaging);

/* ── seed the lists from what the catalogue already says ──
   Taken from live data rather than invented, so nothing that is in use goes
   missing and no name that nobody uses is introduced. */
INSERT INTO pharma.global_company (company_name, inserted_by)
SELECT DISTINCT TRIM(company_name), 'V47'
  FROM pharma.global_product
 WHERE company_name IS NOT NULL AND TRIM(company_name) <> ''
ON CONFLICT (company_name) DO NOTHING;

INSERT INTO pharma.global_generic (generic_name, inserted_by)
SELECT DISTINCT TRIM(generic_name), 'V47'
  FROM pharma.global_product
 WHERE generic_name IS NOT NULL AND TRIM(generic_name) <> ''
ON CONFLICT (generic_name) DO NOTHING;

INSERT INTO pharma.global_category (category_name, inserted_by)
SELECT DISTINCT TRIM(category_name), 'V47'
  FROM pharma.global_product
 WHERE category_name IS NOT NULL AND TRIM(category_name) <> ''
ON CONFLICT (category_name) DO NOTHING;

INSERT INTO pharma.global_sub_category (pid_global_category, sub_category_name, inserted_by)
SELECT DISTINCT c.pid_global_category, TRIM(g.sub_category_name), 'V47'
  FROM pharma.global_product g
  JOIN pharma.global_category c ON c.category_name = TRIM(g.category_name)
 WHERE g.sub_category_name IS NOT NULL AND TRIM(g.sub_category_name) <> ''
ON CONFLICT (pid_global_category, sub_category_name) DO NOTHING;

INSERT INTO pharma.global_unit (unit_name, inserted_by)
SELECT DISTINCT TRIM(unit_name), 'V47'
  FROM pharma.global_product
 WHERE unit_name IS NOT NULL AND TRIM(unit_name) <> ''
ON CONFLICT (unit_name) DO NOTHING;

INSERT INTO pharma.global_packaging (packaging_title, pcs_per_leaf, leaf_per_box, inserted_by)
SELECT DISTINCT leaf_per_box || ' x ' || pcs_per_leaf, pcs_per_leaf, leaf_per_box, 'V47'
  FROM pharma.global_product
 WHERE pcs_per_leaf IS NOT NULL AND leaf_per_box IS NOT NULL
   AND pcs_per_leaf > 0 AND leaf_per_box > 0
ON CONFLICT (pcs_per_leaf, leaf_per_box) DO NOTHING;

/* ── point every catalogue row at its list entry ── */
UPDATE pharma.global_product g SET pid_global_company = c.pid_global_company
  FROM pharma.global_company c WHERE c.company_name = TRIM(g.company_name);

UPDATE pharma.global_product g SET pid_global_generic = x.pid_global_generic
  FROM pharma.global_generic x WHERE x.generic_name = TRIM(g.generic_name);

UPDATE pharma.global_product g SET pid_global_category = c.pid_global_category
  FROM pharma.global_category c WHERE c.category_name = TRIM(g.category_name);

UPDATE pharma.global_product g SET pid_global_sub_category = s.pid_global_sub_category
  FROM pharma.global_sub_category s
 WHERE s.sub_category_name = TRIM(g.sub_category_name)
   AND s.pid_global_category = g.pid_global_category;

UPDATE pharma.global_product g SET pid_global_unit = u.pid_global_unit
  FROM pharma.global_unit u WHERE u.unit_name = TRIM(g.unit_name);

UPDATE pharma.global_product g SET pid_global_packaging = p.pid_global_packaging
  FROM pharma.global_packaging p
 WHERE p.pcs_per_leaf = g.pcs_per_leaf AND p.leaf_per_box = g.leaf_per_box;

COMMIT;

SELECT 'global_company' AS list, count(*) FROM pharma.global_company
UNION ALL SELECT 'global_generic',      count(*) FROM pharma.global_generic
UNION ALL SELECT 'global_category',     count(*) FROM pharma.global_category
UNION ALL SELECT 'global_sub_category', count(*) FROM pharma.global_sub_category
UNION ALL SELECT 'global_unit',         count(*) FROM pharma.global_unit
UNION ALL SELECT 'global_packaging',    count(*) FROM pharma.global_packaging;

SELECT count(*) AS catalogue_rows,
       count(pid_global_company)  AS linked_company,
       count(pid_global_generic)  AS linked_generic,
       count(pid_global_category) AS linked_category,
       count(pid_global_unit)     AS linked_unit,
       count(pid_global_packaging) AS linked_packaging
  FROM pharma.global_product;
