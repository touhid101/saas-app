-- The catalogue could not describe a medicine fully enough to copy it.
--
-- Importing a catalogue entry into a pharmacy already created that pharmacy's own
-- manufacturer, category, generic and unit. Three things were missing:
--
--   · sub-category — the catalogue had no column for one at all, so an imported
--     medicine landed with PID_SUB_CATEGORY null even where the pharmacy had a
--     perfectly good sub-category to file it under;
--   · packaging — PCS_PER_LEAF and LEAF_PER_BOX existed but nothing read them, so
--     no leaf/strip or box row was ever created and PID_BOX stayed null;
--   · the image — the catalogue could not hold one, so an imported product had no
--     picture and the shelf looked empty next to a hand-typed product.
--
-- This adds what the catalogue was missing. The import that reads it is a code
-- change; the columns are here so it has something to read.
\set ON_ERROR_STOP on

BEGIN;

/* Named, not an id: the catalogue is global and a pharmacy's sub-category ids are
   its own. Every other master on this table works the same way — the name is the
   contract, and the receiving pharmacy resolves it to its own row or creates one. */
ALTER TABLE pharma.global_product
  ADD COLUMN IF NOT EXISTS sub_category_name varchar(100);

COMMENT ON COLUMN pharma.global_product.sub_category_name IS
  'Resolved to the receiving pharmacy''s own sub-category on import, under its category';
COMMENT ON COLUMN pharma.global_product.pcs_per_leaf IS
  'Pieces per leaf/strip. Creates the pharmacy''s own leaf row on import';
COMMENT ON COLUMN pharma.global_product.leaf_per_box IS
  'Leaves per box. Creates the pharmacy''s own box row on import';
COMMENT ON COLUMN pharma.global_product.product_image IS
  'Path to the catalogue''s copy. The file is duplicated per pharmacy on import, never shared';

COMMIT;

SELECT column_name, data_type
  FROM information_schema.columns
 WHERE table_schema = 'pharma' AND table_name = 'global_product'
   AND column_name IN ('sub_category_name', 'pcs_per_leaf', 'leaf_per_box',
                       'product_image', 'file_name', 'file_mime_type')
 ORDER BY column_name;
