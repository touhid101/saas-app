-- Packaging becomes Leaf/Strip and Box, the same two things a pharmacy has.
--
-- V47 modelled packaging as one row holding both numbers. That was tidy but it
-- did not match anything: a pharmacy has a Leaf/Strip master (a named strip and
-- its piece count) and a Box master (a named box, the strip it is built from, and
-- how many of them fit). The catalogue should describe the same two things, or
-- the person curating it has to think in a shape nothing else uses.
--
-- ── what a catalogue entry points at now ──
-- Its box. The box names its leaf, and the leaf carries the piece count, so one
-- reference still yields both numbers — GLOBAL_PRODUCT.PCS_PER_LEAF and
-- LEAF_PER_BOX are still written from it, and the import still reads those. That
-- path does not change.
--
-- An entry may name a leaf without a box: a medicine sold in strips that never
-- come boxed is a real thing, and forcing a box on it would invent one.
\set ON_ERROR_STOP on

BEGIN;

CREATE TABLE IF NOT EXISTS pharma.global_leaf (
    pid_global_leaf bigserial PRIMARY KEY,
    leaf_type       varchar(60) NOT NULL,
    no_of_pcs       integer     NOT NULL,
    status          varchar(1)  NOT NULL DEFAULT 'Y',
    inserted_by     varchar(50),
    inserted_date   timestamp DEFAULT now(),
    updated_by      varchar(50),
    updated_date    timestamp,
    CONSTRAINT uq_global_leaf_type UNIQUE (leaf_type),
    CONSTRAINT ck_global_leaf_pcs  CHECK (no_of_pcs > 0)
);

CREATE TABLE IF NOT EXISTS pharma.global_box (
    pid_global_box  bigserial PRIMARY KEY,
    box_size_title  varchar(60) NOT NULL,
    pid_global_leaf bigint      NOT NULL REFERENCES pharma.global_leaf (pid_global_leaf),
    no_of_leaf      integer     NOT NULL,
    status          varchar(1)  NOT NULL DEFAULT 'Y',
    inserted_by     varchar(50),
    inserted_date   timestamp DEFAULT now(),
    updated_by      varchar(50),
    updated_date    timestamp,
    CONSTRAINT uq_global_box_title UNIQUE (box_size_title),
    CONSTRAINT ck_global_box_leaves CHECK (no_of_leaf > 0)
);

ALTER TABLE pharma.global_product
  ADD COLUMN IF NOT EXISTS pid_global_leaf bigint REFERENCES pharma.global_leaf (pid_global_leaf),
  ADD COLUMN IF NOT EXISTS pid_global_box  bigint REFERENCES pharma.global_box  (pid_global_box);

/* ── carry the old pairings across ──
   Named the way the pharmacy side names them: a strip is "Strip-10", a box is
   "10 x 10". Nothing is invented — every row here comes from a pairing that was
   already in use. */
INSERT INTO pharma.global_leaf (leaf_type, no_of_pcs, inserted_by)
SELECT DISTINCT 'Strip-' || p.pcs_per_leaf, p.pcs_per_leaf, 'V49'
  FROM pharma.global_packaging p
ON CONFLICT (leaf_type) DO NOTHING;

INSERT INTO pharma.global_box (box_size_title, pid_global_leaf, no_of_leaf, inserted_by)
SELECT DISTINCT p.leaf_per_box || ' x ' || p.pcs_per_leaf, l.pid_global_leaf, p.leaf_per_box, 'V49'
  FROM pharma.global_packaging p
  JOIN pharma.global_leaf l ON l.no_of_pcs = p.pcs_per_leaf
ON CONFLICT (box_size_title) DO NOTHING;

UPDATE pharma.global_product g
   SET pid_global_box  = b.pid_global_box,
       pid_global_leaf = b.pid_global_leaf
  FROM pharma.global_packaging p
  JOIN pharma.global_box b ON b.box_size_title = p.leaf_per_box || ' x ' || p.pcs_per_leaf
 WHERE g.pid_global_packaging = p.pid_global_packaging;

/* An entry with the numbers but no packaging row still gets its leaf, so nothing
   that already described its strips loses that. */
UPDATE pharma.global_product g
   SET pid_global_leaf = l.pid_global_leaf
  FROM pharma.global_leaf l
 WHERE g.pid_global_leaf IS NULL
   AND g.pcs_per_leaf IS NOT NULL
   AND l.no_of_pcs = g.pcs_per_leaf;

/* ── the old shape goes ──
   Dropped rather than left behind: two ways to say the same thing is how they
   drift apart, and everything it held has been carried across above. */
ALTER TABLE pharma.global_product DROP COLUMN IF EXISTS pid_global_packaging;
DROP TABLE IF EXISTS pharma.global_packaging;

COMMIT;

SELECT 'global_leaf' AS list, count(*) FROM pharma.global_leaf
UNION ALL SELECT 'global_box', count(*) FROM pharma.global_box;

SELECT count(*) AS catalogue_rows,
       count(pid_global_leaf) AS linked_leaf,
       count(pid_global_box)  AS linked_box
  FROM pharma.global_product;
