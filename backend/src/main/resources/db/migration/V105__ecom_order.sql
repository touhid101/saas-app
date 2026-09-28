/* ─────────────────────────────────────────────────────────────────────────────
   V105 — E-commerce Phase 6: the order.

   An online order is TWO records, deliberately:

     POS_TRANSACTION_MST   the pharmacy's invoice. Stock left the shelf for it,
                           the ledger was posted for it, and every ERP screen —
                           Sales Activity, Delivery, Collection, reports — reads
                           it exactly as it reads a counter sale.
     ECOM_ORDER            what the customer ordered, and everything the ERP has
                           no column for: the global customer, the delivery
                           address as typed, the customer-facing status, and the
                           prices AS QUOTED at the moment they pressed the button.

   The link is ECOM_ORDER.PID_TRAN_MST — one invoice per order, never shared.
   The snapshot columns exist because a price is a promise made at a moment:
   the product's price may move tomorrow, and the customer's tier may change,
   and neither may rewrite what they were charged (RULE: historical orders
   never change).

   No table here duplicates a POS table. The batch-level detail of what was
   actually dispensed stays in POS_TRANSACTION_DTL, where the till writes it.
   ───────────────────────────────────────────────────────────────────────────── */

CREATE SEQUENCE IF NOT EXISTS pharma.seq_ecom_order         START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_ecom_order_item    START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_ecom_order_history START WITH 1 INCREMENT BY 1;
/* The customer-facing number. A sequence, not random digits: an order number a
   customer reads out on the phone must be unique, and POS_TRANSACTION_MST's
   INVOICE_NO is five random digits (see docs/ecommerce 11 §2). */
CREATE SEQUENCE IF NOT EXISTS pharma.seq_ecom_order_no      START WITH 1000 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS pharma.ecom_order (
    pid_ecom_order          BIGINT        NOT NULL,
    order_no                VARCHAR(30)   NOT NULL,

    pid_ecom_customer       BIGINT        NOT NULL,
    /* The pharmacy that packs and delivers it, and its organisation — carried so
       a branch's order list is one index lookup rather than a join to ALL_BRANCH. */
    pid_branch              BIGINT        NOT NULL,
    pid_org                 BIGINT,
    /* The pharmacy's invoice. Set inside the same transaction that creates it. */
    pid_tran_mst            BIGINT,
    /* The pharmacy-side customer row the invoice points at (the shadow, V100). */
    pid_customer            BIGINT,

    ecom_status             VARCHAR(20)   NOT NULL DEFAULT 'NEW',

    /* ── who they were when they ordered ── */
    customer_name_snapshot  VARCHAR(150),
    phone_snapshot          VARCHAR(20),
    customer_type_snapshot  VARCHAR(20),

    /* ── where it goes, as typed, not as a pointer to an address that may change ── */
    delivery_label          VARCHAR(40),
    delivery_recipient      VARCHAR(150),
    delivery_phone          VARCHAR(20),
    delivery_line1          VARCHAR(200),
    delivery_line2          VARCHAR(200),
    delivery_area           VARCHAR(100),
    delivery_city           VARCHAR(100),
    delivery_postcode       VARCHAR(20),
    delivery_latitude       NUMERIC(9,6),
    delivery_longitude      NUMERIC(9,6),

    payment_method          VARCHAR(20)   NOT NULL DEFAULT 'COD',
    payment_ref             VARCHAR(100),

    /* ── the money, as quoted ── */
    subtotal                NUMERIC(14,2) NOT NULL DEFAULT 0,
    discount_amount         NUMERIC(14,2) NOT NULL DEFAULT 0,
    coupon_code             VARCHAR(40),
    coupon_amount           NUMERIC(14,2) NOT NULL DEFAULT 0,
    offer_amount            NUMERIC(14,2) NOT NULL DEFAULT 0,
    points_redeemed         NUMERIC(14,2) NOT NULL DEFAULT 0,
    points_value            NUMERIC(14,2) NOT NULL DEFAULT 0,
    delivery_charge         NUMERIC(14,2) NOT NULL DEFAULT 0,
    /* No VAT is charged on a sale in this ERP (docs/ecommerce 05 §5). The column
       exists so a later tax decision has somewhere to land; it stays zero until
       that decision is made. */
    tax_amount              NUMERIC(14,2) NOT NULL DEFAULT 0,
    grand_total             NUMERIC(14,2) NOT NULL DEFAULT 0,

    customer_note           VARCHAR(300),

    placed_at               TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_at            TIMESTAMP,
    delivered_at            TIMESTAMP,
    cancelled_at            TIMESTAMP,
    cancel_reason           VARCHAR(300),
    cancelled_by_kind       VARCHAR(10),

    status                  VARCHAR(1)    NOT NULL DEFAULT 'Y',
    inserted_by             VARCHAR(50),
    inserted_date           TIMESTAMP,
    updated_by              VARCHAR(50),
    updated_date            TIMESTAMP,

    CONSTRAINT ecom_order_pkey PRIMARY KEY (pid_ecom_order),
    CONSTRAINT fk_ecom_order_customer FOREIGN KEY (pid_ecom_customer)
        REFERENCES pharma.ecom_customer (pid_ecom_customer),
    CONSTRAINT fk_ecom_order_branch FOREIGN KEY (pid_branch)
        REFERENCES pharma.all_branch (pid_branch),
    CONSTRAINT fk_ecom_order_tran FOREIGN KEY (pid_tran_mst)
        REFERENCES pharma.pos_transaction_mst (pid_tran_mst),
    CONSTRAINT uq_ecom_order_no UNIQUE (order_no),
    CONSTRAINT ck_ecom_order_status CHECK (ecom_status IN
        ('NEW', 'CONFIRMED', 'PREPARING', 'READY', 'ON_THE_WAY', 'DELIVERED', 'CANCELLED', 'RETURNED')),
    CONSTRAINT ck_ecom_order_cancelled_by CHECK (cancelled_by_kind IS NULL OR cancelled_by_kind IN ('CUSTOMER', 'STAFF', 'SYSTEM'))
);

/* One order per invoice: an invoice that two orders claimed would be money
   counted twice. */
CREATE UNIQUE INDEX IF NOT EXISTS uq_ecom_order_tran
    ON pharma.ecom_order (pid_tran_mst) WHERE pid_tran_mst IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_ecom_order_branch
    ON pharma.ecom_order (pid_branch, ecom_status, placed_at DESC);
CREATE INDEX IF NOT EXISTS ix_ecom_order_customer
    ON pharma.ecom_order (pid_ecom_customer, placed_at DESC);

CREATE TABLE IF NOT EXISTS pharma.ecom_order_item (
    pid_order_item          BIGINT        NOT NULL,
    pid_ecom_order          BIGINT        NOT NULL,

    pid_product             BIGINT        NOT NULL,
    pid_global_product      BIGINT,

    /* As it was when they ordered — the name on the pack, the pack size, the
       price. The product row may be edited or superseded afterwards. */
    product_name_snapshot   VARCHAR(200),
    generic_snapshot        VARCHAR(200),
    company_snapshot        VARCHAR(150),
    sales_unit              VARCHAR(10)   NOT NULL,
    qty                     NUMERIC(12,2) NOT NULL,
    pcs_per_unit_snapshot   INTEGER,
    qty_pcs                 NUMERIC(14,2),
    mrp_snapshot            NUMERIC(14,4),
    unit_price_snapshot     NUMERIC(14,4) NOT NULL,
    discount_pct_snapshot   NUMERIC(8,4),
    line_total              NUMERIC(14,2) NOT NULL,
    prescription_flag_snapshot VARCHAR(1),

    status                  VARCHAR(1)    NOT NULL DEFAULT 'Y',
    inserted_by             VARCHAR(50),
    inserted_date           TIMESTAMP,
    updated_by              VARCHAR(50),
    updated_date            TIMESTAMP,

    CONSTRAINT ecom_order_item_pkey PRIMARY KEY (pid_order_item),
    CONSTRAINT fk_ecom_order_item_order FOREIGN KEY (pid_ecom_order)
        REFERENCES pharma.ecom_order (pid_ecom_order),
    CONSTRAINT ck_ecom_order_item_unit CHECK (sales_unit IN ('BOX', 'STRIP', 'PCS')),
    CONSTRAINT ck_ecom_order_item_qty CHECK (qty > 0)
);

CREATE INDEX IF NOT EXISTS ix_ecom_order_item_order
    ON pharma.ecom_order_item (pid_ecom_order);

/* Every move, who made it and why. Append-only: the trail of an order is not
   something anybody edits. */
CREATE TABLE IF NOT EXISTS pharma.ecom_order_status_history (
    pid_history             BIGINT       NOT NULL,
    pid_ecom_order          BIGINT       NOT NULL,
    from_status             VARCHAR(20),
    to_status               VARCHAR(20)  NOT NULL,
    actor_kind              VARCHAR(10)  NOT NULL,
    actor_id                VARCHAR(80),
    reason                  VARCHAR(300),
    changed_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ecom_order_status_history_pkey PRIMARY KEY (pid_history),
    CONSTRAINT fk_ecom_order_history_order FOREIGN KEY (pid_ecom_order)
        REFERENCES pharma.ecom_order (pid_ecom_order),
    CONSTRAINT ck_ecom_order_history_actor CHECK (actor_kind IN ('CUSTOMER', 'STAFF', 'SYSTEM'))
);

CREATE INDEX IF NOT EXISTS ix_ecom_order_history_order
    ON pharma.ecom_order_status_history (pid_ecom_order, changed_at);

COMMENT ON TABLE pharma.ecom_order IS
  'A marketplace order: the customer-facing half. The pharmacy invoice it created is POS_TRANSACTION_MST, linked by PID_TRAN_MST.';
COMMENT ON COLUMN pharma.ecom_order_item.unit_price_snapshot IS
  'What one unit cost at the moment the order was placed. Never recomputed.';

SELECT (SELECT count(*) FROM pharma.ecom_order) AS orders,
       (SELECT count(*) FROM pharma.ecom_order_item) AS order_items;
