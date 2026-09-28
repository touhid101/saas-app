-- The catalogue described no packaging, so no pharmacy could be given any.
--
-- Importing a catalogue entry builds the receiving pharmacy's own leaf/strip and
-- box rows from PCS_PER_LEAF and LEAF_PER_BOX. Both columns were null on all 28
-- catalogue rows, so the import had nothing to build from and every imported
-- product arrived with PID_BOX empty. The copy was working; there was simply
-- nothing to copy.
--
-- ── how the numbers are chosen ──
-- From UNIT_NAME, which is the only packaging fact the catalogue actually
-- records. Nothing here is inferred from a product's name or its generic: this
-- catalogue's clinical fields are demo data (several products name a generic that
-- is not theirs), and guessing a dosage form from a brand name in a pharmacy
-- system is not a guess worth making.
--
--   STRIP, BOX, PCS   → 10 pieces per strip, 10 strips per box
--                       The common presentation here, and the one a pharmacy can
--                       correct in two fields if their supplier differs.
--   BOTTLE, VIAL      → left null. A bottle has no strips, and half a packaging
--                       description is worse than none: the import deliberately
--                       creates no box unless both numbers are present.
--
-- A pharmacy that already imported these products is NOT changed. Import copies,
-- and a copy belongs to whoever holds it — the catalogue does not reach onto a
-- shelf it has already stocked. New pharmacies get the packaging from here on.
\set ON_ERROR_STOP on

BEGIN;

UPDATE pharma.global_product
   SET pcs_per_leaf = 10,
       leaf_per_box = 10
 WHERE UPPER(COALESCE(unit_name, '')) IN ('STRIP', 'BOX', 'PCS')
   AND pcs_per_leaf IS NULL
   AND leaf_per_box IS NULL;

COMMIT;

SELECT COALESCE(unit_name, '(none)') AS unit,
       count(*)                      AS rows,
       count(pcs_per_leaf)           AS now_have_packaging
  FROM pharma.global_product
 GROUP BY 1
 ORDER BY 1;
