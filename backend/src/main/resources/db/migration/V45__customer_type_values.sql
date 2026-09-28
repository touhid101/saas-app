-- The Customer form offered six types; the database accepted three.
--
-- CK_CUSTOMER_TYPE allowed only Wholesaler, Retailer and Special. The dropdown
-- offered Hospital, Pharmacy, Clinic, Walk-in, Wholesaler and Retail — so five of
-- its six choices were rejected on save, and the only one that worked was
-- Wholesaler. "Retail" was not even a spelling of "Retailer" as far as the check
-- was concerned.
--
-- The types the form offers are real: a pharmacy sells to hospitals, clinics and
-- other pharmacies, and those are worth reporting on separately. So the constraint
-- widens to admit them rather than the form narrowing to hide them.
--
-- "Retail" is dropped rather than added: six existing rows already say "Retailer",
-- and carrying two spellings of one thing means every later report has to know
-- about both. The form is corrected to send "Retailer" in the same change.
\set ON_ERROR_STOP on

BEGIN;

ALTER TABLE pharma.pos_customer_info DROP CONSTRAINT IF EXISTS ck_customer_type;

ALTER TABLE pharma.pos_customer_info
  ADD CONSTRAINT ck_customer_type CHECK (customer_type IN (
      'Retailer',    -- an over-the-counter buyer; what a walk-in sale is
      'Wholesaler',
      'Hospital',
      'Pharmacy',    -- another pharmacy buying stock
      'Clinic',
      'Special'      -- kept: one existing row uses it
  ));

COMMENT ON COLUMN pharma.pos_customer_info.customer_type IS
  'Retailer, Wholesaler, Hospital, Pharmacy, Clinic or Special — see CK_CUSTOMER_TYPE';

COMMIT;

SELECT pg_get_constraintdef(oid) AS now_allows
  FROM pg_constraint WHERE conname = 'ck_customer_type';

SELECT COALESCE(customer_type, '(null)') AS type, count(*)
  FROM pharma.pos_customer_info GROUP BY 1 ORDER BY 2 DESC;
