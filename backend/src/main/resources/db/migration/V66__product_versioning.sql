/* ─────────────────────────────────────────────────────────────────────────────
   Product versioning — an edit writes a new product and retires the old one.

   Until now, editing a product overwrote it. The row a sale made in March
   points at is the row somebody re-priced in September, so the invoice reprints
   at today's price under today's name and the March figures no longer add up.

   From here an edit supersedes: the new values go into a NEW row, the old row
   is marked inactive, and every transaction, stock movement and printed
   document that already named the old row goes on naming it. History becomes
   immutable because nothing rewrites it, not because anything guards it.

   ── the chain ──

   PID_ROOT_PRODUCT is the first version's id and never changes, so every
   version of one product shares it — one indexed column answers "show me this
   product's history" without walking a linked list. SUPERSEDED_BY points the
   other way, one step at a time, which is what tells you whether the row in
   your hand is the current one.

   ── the part that actually blocked this ──

   Four unique indexes. Product code, SKU, barcode and the catalogue link are
   each unique per branch, and none of them looked at status — so writing a
   second version carrying the same barcode failed on the index before any of
   the above could matter. They are rebuilt below as partial indexes over
   active rows only.

   That is the right rule regardless of versioning: a retired product has no
   business reserving a barcode that the shelf is still using.
   ───────────────────────────────────────────────────────────────────────────── */

/* ── the chain ─────────────────────────────────────────────────────────────── */
ALTER TABLE pharma.pos_product_info
  ADD COLUMN IF NOT EXISTS pid_root_product bigint,
  ADD COLUMN IF NOT EXISTS version_no       integer,
  ADD COLUMN IF NOT EXISTS superseded_by    bigint,
  ADD COLUMN IF NOT EXISTS superseded_date  timestamp,
  ADD COLUMN IF NOT EXISTS superseded_by_user varchar(50);

COMMENT ON COLUMN pharma.pos_product_info.pid_root_product IS
    'First version''s PID_PRODUCT. Equal to PID_PRODUCT on a product that has never been edited.';
COMMENT ON COLUMN pharma.pos_product_info.version_no IS
    '1 for the original, incrementing by one for each edit.';
COMMENT ON COLUMN pharma.pos_product_info.superseded_by IS
    'The version that replaced this row. NULL on the current version.';

/* Every row that exists today is version 1 of itself. Written rather than
   defaulted, because a default cannot say "the value of another column in this
   same row". */
UPDATE pharma.pos_product_info
   SET pid_root_product = pid_product
 WHERE pid_root_product IS NULL;

UPDATE pharma.pos_product_info
   SET version_no = 1
 WHERE version_no IS NULL;

/* VERSION_NO is NOT NULL because it has a default to fall back on.
   PID_ROOT_PRODUCT deliberately is not, and the reason is worth writing down: a
   brand-new product is its own root, so the value is its own primary key — which
   does not exist until the INSERT has already happened. NOT NULL would make it
   impossible to insert a product at all, which is precisely what it did the
   first time this migration was written.

   PRODUCT_CODE has the same shape and is handled the same way: derived from the
   generated id, so assigned by the UPDATE that follows the INSERT. Readers are
   spared the null: it is set within the same transaction, the mapper falls back
   to the row's own id, and the version query wraps it in COALESCE. */
ALTER TABLE pharma.pos_product_info
  ALTER COLUMN version_no SET NOT NULL,
  ALTER COLUMN version_no SET DEFAULT 1;

/* The history query: every version of one product, newest first. */
CREATE INDEX IF NOT EXISTS ix_product_root
    ON pharma.pos_product_info (pid_root_product, version_no DESC);

/* ── identifiers belong to the version that is live ────────────────────────── */

DROP INDEX IF EXISTS pharma.uq_product_branch_code;
CREATE UNIQUE INDEX uq_product_branch_code
    ON pharma.pos_product_info (COALESCE(pid_branch, 0::bigint), product_code)
 WHERE product_code IS NOT NULL AND COALESCE(status, 'Y') = 'Y';

DROP INDEX IF EXISTS pharma.uq_product_branch_sku;
CREATE UNIQUE INDEX uq_product_branch_sku
    ON pharma.pos_product_info (COALESCE(pid_branch, 0::bigint), upper(sku::text))
 WHERE sku IS NOT NULL AND COALESCE(status, 'Y') = 'Y';

DROP INDEX IF EXISTS pharma.uq_product_branch_barcode;
CREATE UNIQUE INDEX uq_product_branch_barcode
    ON pharma.pos_product_info (COALESCE(pid_branch, 0::bigint), upper(barcode::text))
 WHERE barcode IS NOT NULL AND barcode::text <> '' AND COALESCE(status, 'Y') = 'Y';

/* The catalogue link is the one that would have bitten hardest: a pharmacy that
   imported a medicine and then edited it would have been unable to save, and
   the message would have named an index rather than anything a person could
   act on. */
DROP INDEX IF EXISTS pharma.uq_branch_global_product;
CREATE UNIQUE INDEX uq_branch_global_product
    ON pharma.pos_product_info (pid_branch, pid_global_product)
 WHERE pid_global_product IS NOT NULL AND COALESCE(status, 'Y') = 'Y';

/* ── what a version chain must never look like ─────────────────────────────── */

/* A row cannot supersede itself, and a superseded row must be inactive. The
   pair is what makes "one active version per chain" hold: the writer sets both
   in one statement or neither, and this refuses the halfway state. */
ALTER TABLE pharma.pos_product_info
  DROP CONSTRAINT IF EXISTS ck_product_superseded;
ALTER TABLE pharma.pos_product_info
  ADD CONSTRAINT ck_product_superseded CHECK (
      superseded_by IS NULL
      OR (superseded_by <> pid_product AND COALESCE(status, 'Y') = 'N'));

SELECT count(*)                                   AS products,
       count(*) FILTER (WHERE version_no = 1)     AS version_1,
       count(*) FILTER (WHERE superseded_by IS NOT NULL) AS superseded,
       count(DISTINCT pid_root_product)           AS distinct_products
  FROM pharma.pos_product_info;
