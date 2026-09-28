/* ─────────────────────────────────────────────────────────────────────────────
   V108 — What a pharmacy charges to deliver.

   Online orders have been delivered free since checkout was built, because the
   ERP had nowhere to say otherwise: V103 gave a branch its radius and its
   minimum order, but not a price for the journey. This adds the policy, per
   pharmacy, and leaves every existing branch exactly where it is — no fee type
   means no fee, which is what they have been doing.

     ECOM_DELIVERY_FEE_TYPE    NONE (or null)  free, as today
                               FLAT            ECOM_DELIVERY_FLAT_FEE, whatever the distance
                               DISTANCE        ECOM_DELIVERY_FLAT_FEE as a base
                                               + ECOM_DELIVERY_FEE_PER_KM × km
     ECOM_DELIVERY_FREE_ABOVE  order subtotal at or above which delivery is free anyway

   DISTANCE needs two points. The pharmacy always has coordinates (it cannot be
   listed online without them); the customer's address may not, and a fee
   invented from a guessed distance would be a fee charged for a number nobody
   measured. Where the distance is unknown the base fee applies and the order
   says so — see EcomDeliveryFeeService.

   Nullable, no defaults, no backfill.

   NOT decided here: whether a delivery charge carries VAT, and whether the
   pharmacy or the platform keeps it. The column holds a number; who it belongs
   to is a BUSINESS CONFIRMATION item (docs/ecommerce 13 §4).
   ───────────────────────────────────────────────────────────────────────────── */

ALTER TABLE pharma.all_branch
    ADD COLUMN IF NOT EXISTS ecom_delivery_fee_type   VARCHAR(10),
    ADD COLUMN IF NOT EXISTS ecom_delivery_flat_fee   NUMERIC(12, 2),
    ADD COLUMN IF NOT EXISTS ecom_delivery_fee_per_km NUMERIC(12, 2),
    ADD COLUMN IF NOT EXISTS ecom_delivery_free_above NUMERIC(14, 2);

COMMENT ON COLUMN pharma.all_branch.ecom_delivery_fee_type IS
  'NONE/null = free delivery · FLAT = the flat fee · DISTANCE = flat fee as a base plus per-km.';
COMMENT ON COLUMN pharma.all_branch.ecom_delivery_flat_fee IS
  'The fee for FLAT, and the base for DISTANCE (also the fallback when the address has no coordinates).';
COMMENT ON COLUMN pharma.all_branch.ecom_delivery_fee_per_km IS
  'Added per kilometre of straight-line distance from the pharmacy, for DISTANCE only.';
COMMENT ON COLUMN pharma.all_branch.ecom_delivery_free_above IS
  'Subtotal at or above which the order is delivered free, whatever the fee type says.';

/* A fee type the application does not understand would be charged as nothing,
   silently. Only the three words mean anything. */
ALTER TABLE pharma.all_branch
    DROP CONSTRAINT IF EXISTS ck_branch_ecom_fee_type;
ALTER TABLE pharma.all_branch
    ADD CONSTRAINT ck_branch_ecom_fee_type
    CHECK (ecom_delivery_fee_type IS NULL
           OR ecom_delivery_fee_type IN ('NONE', 'FLAT', 'DISTANCE'));

/* A negative delivery charge is a refund wearing a delivery charge's clothes. */
ALTER TABLE pharma.all_branch
    DROP CONSTRAINT IF EXISTS ck_branch_ecom_fee_amounts;
ALTER TABLE pharma.all_branch
    ADD CONSTRAINT ck_branch_ecom_fee_amounts
    CHECK (COALESCE(ecom_delivery_flat_fee, 0)   >= 0
       AND COALESCE(ecom_delivery_fee_per_km, 0) >= 0
       AND COALESCE(ecom_delivery_free_above, 0) >= 0);

SELECT count(*) FILTER (WHERE ecom_delivery_fee_type IS NOT NULL AND ecom_delivery_fee_type <> 'NONE') AS branches_charging,
       count(*) FILTER (WHERE ecom_enabled = 'Y')                                                      AS branches_online
  FROM pharma.all_branch;
