/* ─────────────────────────────────────────────────────────────────────────────
   V102 — Mark the pharmacy customer rows that belong to an online customer.

   The shadow row a global e-commerce customer gets inside a pharmacy (V100,
   ECOM_CUSTOMER_BRANCH_LINK) looks like any other POS_CUSTOMER_INFO row, and
   that is the point: invoices, returns, collections and reports treat it as
   one. But its CUSTOMER_TYPE is a copy of the global tier, and if branch staff
   edited it on the Customers screen the copy would drift from the identity it
   mirrors. This flag lets the customer form refuse that one edit and point at
   the E-Commerce Customers console instead.

   Nullable, no default, no backfill: every existing row was made by staff and
   stays exactly as it is. Only the bridge service ever writes 'Y'.
   ───────────────────────────────────────────────────────────────────────────── */

ALTER TABLE pharma.pos_customer_info
    ADD COLUMN IF NOT EXISTS ecom_managed VARCHAR(1);

COMMENT ON COLUMN pharma.pos_customer_info.ecom_managed IS
  'Y when this row is the shadow of a global e-commerce customer (see ECOM_CUSTOMER_BRANCH_LINK). Its type is then managed from the E-Commerce Customers console, not here.';

SELECT count(*) AS rows_marked
  FROM pharma.pos_customer_info
 WHERE ecom_managed = 'Y';
