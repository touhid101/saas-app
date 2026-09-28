/* ─────────────────────────────────────────────────────────────────────────────
   Product type: PHARMA or GENERAL.

   A pharmacy does not only sell medicine. Soap, razors, baby food and phone
   credit go across the same counter, and until now every one of them had to be
   entered as though it were a drug — the form demanded a SKU and a generic.
   "Generic: none" is not a fact about a bar of soap, so people either invented a
   generic or kept the item out of the system.

   ── What changes ──

   PHARMA is medicine and behaves exactly as before: SKU and generic required.
   GENERAL is everything else, and for those the two fields are optional and
   hidden on the form.

   Nothing else differs. Pricing, stock, batches and expiry work the same either
   way — a General product still has an MRP and still needs a unit, because it is
   still something being sold.

   ── Why every existing row becomes PHARMA ──

   Because every one of them is medicine. Worth stating, because the obvious
   supporting argument is false: it is NOT true that the old form's mandatory
   fields guarantee it. At the time of writing this database holds 106 branch
   products, and 70 of them have no SKU at all — Fexo, Ketorol, Sergel and the
   rest arrived through the bulk importer and the catalogue copy, neither of
   which went through the form. 69 of those 70 do carry a generic, which is what
   identifies them as drugs.

   So the backfill is a judgement about what this data is, not a deduction from a
   constraint that was never actually enforced.

   ── Why there is no CHECK tying SKU and generic to PHARMA ──

   The tempting constraint is:

       CHECK (product_type <> 'PHARMA' OR (sku IS NOT NULL AND pid_generic IS NOT NULL))

   It cannot go in. Those 70 SKU-less medicines are PHARMA and always will be, so
   the constraint would be false for two thirds of the table. Adding it NOT VALID
   would hide that at migration time and then reject the next UPDATE to any of
   those rows — turning "edit this product's price" into a constraint violation
   on 70 real medicines, discovered by a user rather than by this file.

   The rule belongs where the missing values can actually be supplied: the form
   and ProductValidator, which apply it to what someone is entering now rather
   than to what was imported years ago. That is a deliberate trade — the database
   will not stop a script writing a PHARMA row with no SKU — and it is the right
   one, because the alternative breaks working data to guard against a case no
   screen can produce.

   ── Why the codes are uppercase ──

   The value is an enumeration, not a label. Storing 'Pharma' would make the
   first rename of the display text a data migration.
   ───────────────────────────────────────────────────────────────────────────── */

/* ── Branch products ──────────────────────────────────────────────────────── */

ALTER TABLE pharma.pos_product_info
  ADD COLUMN IF NOT EXISTS product_type varchar(10);

UPDATE pharma.pos_product_info SET product_type = 'PHARMA' WHERE product_type IS NULL;

ALTER TABLE pharma.pos_product_info
  ALTER COLUMN product_type SET DEFAULT 'PHARMA',
  ALTER COLUMN product_type SET NOT NULL;

/* This one is safe to enforce: it constrains the new column against itself, and
   every row was just written by the statement above. */
ALTER TABLE pharma.pos_product_info
  DROP CONSTRAINT IF EXISTS ck_product_type;
ALTER TABLE pharma.pos_product_info
  ADD CONSTRAINT ck_product_type CHECK (product_type IN ('PHARMA', 'GENERAL'));

/* ── The shared catalogue ─────────────────────────────────────────────────── */

ALTER TABLE pharma.global_product
  ADD COLUMN IF NOT EXISTS product_type varchar(10);

UPDATE pharma.global_product SET product_type = 'PHARMA' WHERE product_type IS NULL;

ALTER TABLE pharma.global_product
  ALTER COLUMN product_type SET DEFAULT 'PHARMA',
  ALTER COLUMN product_type SET NOT NULL;

ALTER TABLE pharma.global_product
  DROP CONSTRAINT IF EXISTS ck_global_product_type;
ALTER TABLE pharma.global_product
  ADD CONSTRAINT ck_global_product_type CHECK (product_type IN ('PHARMA', 'GENERAL'));

/* Filtering a few thousand rows by type is a list-screen action, not a report. */
CREATE INDEX IF NOT EXISTS ix_product_type        ON pharma.pos_product_info (product_type);
CREATE INDEX IF NOT EXISTS ix_global_product_type ON pharma.global_product   (product_type);
