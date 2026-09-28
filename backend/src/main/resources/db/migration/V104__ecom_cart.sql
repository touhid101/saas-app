/* ─────────────────────────────────────────────────────────────────────────────
   V104 — E-commerce Phase 5: the customer's cart.

   One open cart per (customer, pharmacy): a cart belongs to the pharmacy that
   will pack it, because stock and price are that pharmacy's. A customer
   shopping at three pharmacies has three carts and pays each of them.

   What the rows hold is deliberately thin — product, unit, quantity. No price
   and no stock: both are the pharmacy's to state, they move, and a price kept
   in a cart row is a price that can go stale between the shelf and the till.
   Every read prices the cart again through the same engine the till uses.

   POS_CART is NOT reused (docs/ecommerce 04 §3): it is staff-keyed
   (PID_USER NOT NULL), branch-owned, and feeds a finaliser that throws.
   ───────────────────────────────────────────────────────────────────────────── */

CREATE SEQUENCE IF NOT EXISTS pharma.seq_ecom_cart      START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_ecom_cart_item START WITH 1 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS pharma.ecom_cart (
    pid_cart            BIGINT       NOT NULL,
    pid_ecom_customer   BIGINT       NOT NULL,
    /* The pharmacy that will pack and deliver it. */
    pid_branch          BIGINT       NOT NULL,

    cart_status         VARCHAR(20)  NOT NULL DEFAULT 'OPEN',

    /* Applied at checkout, validated against this cart's branch (Phase 12). */
    coupon_code         VARCHAR(40),
    /* What the customer wants the pharmacy to know. */
    note                VARCHAR(300),

    checked_out_at      TIMESTAMP,
    /* The order this cart became, once it has become one (Phase 6). */
    pid_ecom_order      BIGINT,

    status              VARCHAR(1)   NOT NULL DEFAULT 'Y',
    inserted_by         VARCHAR(50),
    inserted_date       TIMESTAMP,
    updated_by          VARCHAR(50),
    updated_date        TIMESTAMP,

    CONSTRAINT ecom_cart_pkey PRIMARY KEY (pid_cart),
    CONSTRAINT fk_ecom_cart_customer FOREIGN KEY (pid_ecom_customer)
        REFERENCES pharma.ecom_customer (pid_ecom_customer),
    CONSTRAINT fk_ecom_cart_branch FOREIGN KEY (pid_branch)
        REFERENCES pharma.all_branch (pid_branch),
    CONSTRAINT ck_ecom_cart_status CHECK (cart_status IN ('OPEN', 'CHECKED_OUT', 'ABANDONED'))
);

/* One open cart per customer per pharmacy — the rule the service relies on
   rather than re-checking. A checked-out or abandoned cart is history and may
   repeat. */
CREATE UNIQUE INDEX IF NOT EXISTS uq_ecom_cart_open
    ON pharma.ecom_cart (pid_ecom_customer, pid_branch) WHERE cart_status = 'OPEN';
CREATE INDEX IF NOT EXISTS ix_ecom_cart_customer
    ON pharma.ecom_cart (pid_ecom_customer, cart_status);

CREATE TABLE IF NOT EXISTS pharma.ecom_cart_item (
    pid_cart_item       BIGINT        NOT NULL,
    pid_cart            BIGINT        NOT NULL,
    /* The pharmacy's own product row — POS_PRODUCT_INFO, at this cart's branch. */
    pid_product         BIGINT        NOT NULL,
    sales_unit          VARCHAR(10)   NOT NULL,
    qty                 NUMERIC(12,2) NOT NULL,

    status              VARCHAR(1)    NOT NULL DEFAULT 'Y',
    inserted_by         VARCHAR(50),
    inserted_date       TIMESTAMP,
    updated_by          VARCHAR(50),
    updated_date        TIMESTAMP,

    CONSTRAINT ecom_cart_item_pkey PRIMARY KEY (pid_cart_item),
    CONSTRAINT fk_ecom_cart_item_cart FOREIGN KEY (pid_cart)
        REFERENCES pharma.ecom_cart (pid_cart),
    CONSTRAINT ck_ecom_cart_item_unit CHECK (sales_unit IN ('BOX', 'STRIP', 'PCS')),
    CONSTRAINT ck_ecom_cart_item_qty CHECK (qty > 0),
    /* One line per (product, unit): asking for two boxes twice is four boxes on
       one line, not two lines a customer has to reconcile. */
    CONSTRAINT uq_ecom_cart_item UNIQUE (pid_cart, pid_product, sales_unit)
);

CREATE INDEX IF NOT EXISTS ix_ecom_cart_item_cart
    ON pharma.ecom_cart_item (pid_cart);

COMMENT ON TABLE pharma.ecom_cart IS
  'An e-commerce customer''s cart at one pharmacy. Prices and stock are never stored here; every read re-prices through the ERP engine.';
COMMENT ON COLUMN pharma.ecom_cart_item.qty IS
  'Quantity in SALES_UNIT (boxes, strips or pieces), not in pieces.';

SELECT (SELECT count(*) FROM pharma.ecom_cart) AS carts,
       (SELECT count(*) FROM pharma.ecom_cart_item) AS cart_items;
