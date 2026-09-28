/* ─────────────────────────────────────────────────────────────────────────────
   A customer may have no phone number.

   Requiring one was a rule about the wrong thing. At the counter a walk-in
   buying paracetamol has no reason to give a number, and refusing to save the
   customer stopped the sale rather than improving the record.

   The evidence that it was not working is in the data: every pharmacy's
   Walk-in Customer carried a different placeholder — 01000000000, 01919888306,
   and one that is not even a valid number — because the duplicate check was
   org-wide, so the first pharmacy to save a placeholder owned it everywhere and
   the others had to invent their own.

   uq_customer_branch_mobile (V35) is a partial index on (pid_branch, mobile_no)
   WHERE mobile_no IS NOT NULL, so it already permits any number of rows with no
   number, and scopes the rest to one pharmacy. Nothing to change there.
   ───────────────────────────────────────────────────────────────────────────── */

ALTER TABLE pharma.pos_customer_info
    ALTER COLUMN mobile_no DROP NOT NULL;

/* Empty strings, if any exist, are the absence of a number and not a value:
   left as '' they would collide with each other under the unique index. */
UPDATE pharma.pos_customer_info
   SET mobile_no = NULL
 WHERE mobile_no IS NOT NULL AND btrim(mobile_no) = '';

/* A global UNIQUE on the column would contradict the per-branch index — two
   pharmacies may each know the same person. Dropped if an older schema, or
   Hibernate's ddl-auto, ever created one. */
DO $$
DECLARE c text;
BEGIN
    SELECT tc.constraint_name INTO c
      FROM information_schema.table_constraints tc
      JOIN information_schema.key_column_usage k
        ON k.constraint_name = tc.constraint_name
       AND k.table_schema    = tc.table_schema
     WHERE tc.table_schema   = 'pharma'
       AND tc.table_name     = 'pos_customer_info'
       AND tc.constraint_type = 'UNIQUE'
       AND k.column_name     = 'mobile_no'
     GROUP BY tc.constraint_name
    HAVING count(*) = 1
     LIMIT 1;

    IF c IS NOT NULL THEN
        EXECUTE format('ALTER TABLE pharma.pos_customer_info DROP CONSTRAINT %I', c);
        RAISE NOTICE 'Dropped global unique constraint % on mobile_no', c;
    END IF;
END $$;

COMMIT;

SELECT is_nullable AS mobile_no_nullable
  FROM information_schema.columns
 WHERE table_schema = 'pharma'
   AND table_name   = 'pos_customer_info'
   AND column_name  = 'mobile_no';
