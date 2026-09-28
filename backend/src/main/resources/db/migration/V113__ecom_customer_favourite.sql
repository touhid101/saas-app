-- ════════════════════════════════════════════════════════════════════════════
-- V113 — a customer's saved medicines (the storefront's "favourites")
--
-- Additive only. Nothing existing is altered, renamed or dropped.
--
-- WHY A PRODUCT *AND* A BRANCH
-- The same medicine is a different row at every pharmacy, with its own price
-- and its own stock. Saving "Napa 500mg" alone would save something that has
-- no price, so a favourite is a product AT a pharmacy — the thing the customer
-- was actually looking at when they saved it.
--
-- WHY NO FOREIGN KEY TO THE PRODUCT
-- A pharmacy may supersede or retire a product; POS_PRODUCT_INFO carries
-- SUPERSEDED_BY for exactly that. A hard FK would either block that or cascade
-- a customer's list away. The row is kept and the read resolves it, so a saved
-- medicine that is gone can be shown as gone rather than silently vanishing.
-- The customer and branch references are real, because neither is ever removed.
-- ════════════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS PHARMA.ECOM_CUSTOMER_FAVOURITE (
    PID_FAVOURITE       BIGINT       NOT NULL,
    PID_ECOM_CUSTOMER   BIGINT       NOT NULL,
    PID_BRANCH          BIGINT       NOT NULL,
    PID_PRODUCT         BIGINT       NOT NULL,

    -- BaseEntity's own columns, same shape as every other table here.
    INSERTED_BY         VARCHAR(100),
    INSERTED_DATE       TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    UPDATED_BY          VARCHAR(100),
    UPDATED_DATE        TIMESTAMP,
    STATUS              CHAR(1)      DEFAULT 'Y' NOT NULL,

    CONSTRAINT PK_ECOM_CUSTOMER_FAVOURITE PRIMARY KEY (PID_FAVOURITE),
    CONSTRAINT FK_ECOM_FAV_CUSTOMER FOREIGN KEY (PID_ECOM_CUSTOMER)
        REFERENCES PHARMA.ECOM_CUSTOMER (PID_ECOM_CUSTOMER),
    CONSTRAINT FK_ECOM_FAV_BRANCH FOREIGN KEY (PID_BRANCH)
        REFERENCES PHARMA.ALL_BRANCH (PID_BRANCH)
);

-- Saving the same medicine twice is not an error worth an exception; the index
-- makes the second save a no-op the service can detect and ignore.
CREATE UNIQUE INDEX IF NOT EXISTS UX_ECOM_FAV_ONE_PER_LISTING
    ON PHARMA.ECOM_CUSTOMER_FAVOURITE (PID_ECOM_CUSTOMER, PID_BRANCH, PID_PRODUCT);

-- The only read that matters: this customer's list, newest first.
CREATE INDEX IF NOT EXISTS IX_ECOM_FAV_CUSTOMER
    ON PHARMA.ECOM_CUSTOMER_FAVOURITE (PID_ECOM_CUSTOMER, INSERTED_DATE DESC);

CREATE SEQUENCE IF NOT EXISTS PHARMA.SEQ_ECOM_CUSTOMER_FAVOURITE
    START WITH 1 INCREMENT BY 1;
