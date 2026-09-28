-- ============================================================================
-- V5 — PURCHASE MODULE
-- Target: PostgreSQL (the live datasource per application.yml).
--
-- Adds the columns, sequences and number-generation support the Purchase entry
-- screen needs on top of POS_TRANSACTION_MST / POS_TRANSACTION_DTL.
-- Idempotent; safe to re-run.
--
-- Columns REUSED (not touched): PID_ORG, PID_COMPANY, PID_BRANCH, PID_SUP_CUST,
--   TRANSACTION_DATE, TRANSACTION_TYPE, INVOICE_NO, CHALLAN_NO, TOTAL_AMOUNT,
--   INVOICE_DIS_PCT, INVOICE_DIS_AMT, TOTAL_DISCOUNT, TOTAL_TAX, SERVICE_CHARGE,
--   AMOUNT_TOBE_PAID, GIVEN_AMOUNT, PAYMENT_TYPE, REMARKS, PURCHASE_TYPE,
--   FROM_SOURCE, STATUS, and on the detail table QUANTITY, MNP_PRICE, MRP_PRICE,
--   DISCOUNT_PCT, FLAT_DISCOUNT, TP_PCT, TOTAL_PRICE, EXPIRY_DATE, SALES_TYPE,
--   BATCH_NO.
-- ============================================================================

-- ── 1. Master: invoice-level batch reference ────────────────────────────────
-- Spec puts BATCH_NO on the master as a 6-digit sequence value. The detail
-- table already has its own BATCH_NO for the manufacturer's batch on each line;
-- the two are different things and both are kept.
ALTER TABLE PHARMA.POS_TRANSACTION_MST
    ADD COLUMN IF NOT EXISTS BATCH_NO VARCHAR(20);

COMMENT ON COLUMN PHARMA.POS_TRANSACTION_MST.BATCH_NO IS
    'System-generated 6-digit purchase batch reference from SEQ_BATCH_NO. Not the manufacturer batch — that lives on POS_TRANSACTION_DTL.BATCH_NO.';

-- ── 2. Master: PHARMA / NON-PHARMA classification ───────────────────────────
ALTER TABLE PHARMA.POS_TRANSACTION_MST
    ADD COLUMN IF NOT EXISTS PRODUCT_TYPE VARCHAR(20);

COMMENT ON COLUMN PHARMA.POS_TRANSACTION_MST.PRODUCT_TYPE IS
    'PHARMA or NON-PHARMA. Classifies the whole purchase invoice.';

-- ── 3. Detail: IGTA snapshot ────────────────────────────────────────────────
-- The MNP total-price formula is
--   QTY * (TP_PRICE + IGTA) - (QTY * TP_PRICE * TP_PCT / 100)
-- so the line needs IGTA. It is snapshotted from the product at entry time
-- rather than joined at read time: product pricing changes, and a historical
-- purchase line must keep reproducing the total it was saved with.
ALTER TABLE PHARMA.POS_TRANSACTION_DTL
    ADD COLUMN IF NOT EXISTS IGTA NUMERIC(14,4) DEFAULT 0;

COMMENT ON COLUMN PHARMA.POS_TRANSACTION_DTL.IGTA IS
    'Snapshot of POS_PRODUCT_INFO.IGTA at entry. Input to the MNP total-price formula.';

-- ── 4. Detail: pieces per unit ──────────────────────────────────────────────
ALTER TABLE PHARMA.POS_TRANSACTION_DTL
    ADD COLUMN IF NOT EXISTS NO_OF_PCS INTEGER;

COMMENT ON COLUMN PHARMA.POS_TRANSACTION_DTL.NO_OF_PCS IS
    'Pieces represented by one SALES_TYPE unit, derived from the product Box/Leaf at entry. Display + stock conversion.';

-- MNP_PRICE holds the trade price the MNP formula calls TP_PRICE.
COMMENT ON COLUMN PHARMA.POS_TRANSACTION_DTL.MNP_PRICE IS
    'Snapshot of POS_PRODUCT_INFO.TP_PRICE at entry (the "TP_PRICE" of the MNP formula).';

-- ── 5. Number generation ────────────────────────────────────────────────────
-- SEQUENCE_TRACKER mirrors the Oracle FN_INVOICE_NO_GEN design: one row per
-- logical sequence holding the year-month it last issued for.
--
-- The Oracle function resets by running ALTER SEQUENCE inside an autonomous
-- transaction. That is not reproducible safely here — runtime DDL is not
-- transactional, and two concurrent callers can both attempt the reset. The
-- counter therefore lives in this table and is advanced under a row lock
-- (SELECT ... FOR UPDATE) by DocumentNumberService, which gives the same
-- month-reset behaviour with proper concurrency guarantees and no DDL.
CREATE TABLE IF NOT EXISTS PHARMA.SEQUENCE_TRACKER (
    SEQ_NAME        VARCHAR(50)  NOT NULL,
    LAST_YEAR_MONTH VARCHAR(6),
    LAST_VALUE      BIGINT       NOT NULL DEFAULT 0,
    UPDATED_DATE    TIMESTAMP,
    CONSTRAINT PK_SEQUENCE_TRACKER PRIMARY KEY (SEQ_NAME)
);

COMMENT ON TABLE PHARMA.SEQUENCE_TRACKER IS
    'Per-sequence counters that reset on a year-month boundary. Advanced under a row lock by DocumentNumberService.';
COMMENT ON COLUMN PHARMA.SEQUENCE_TRACKER.LAST_YEAR_MONTH IS 'YYMM the counter last issued for; a change resets LAST_VALUE to 0.';

INSERT INTO PHARMA.SEQUENCE_TRACKER (SEQ_NAME, LAST_YEAR_MONTH, LAST_VALUE, UPDATED_DATE)
SELECT 'SEQ_INVOICE_NO', TO_CHAR(CURRENT_DATE, 'YYMM'), 0, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM PHARMA.SEQUENCE_TRACKER WHERE SEQ_NAME = 'SEQ_INVOICE_NO');

-- Batch numbers do not reset monthly — they are a continuous 6-digit series.
CREATE SEQUENCE IF NOT EXISTS PHARMA.SEQ_BATCH_NO START WITH 1 INCREMENT BY 1 NO CYCLE;

COMMENT ON SEQUENCE PHARMA.SEQ_BATCH_NO IS
    'Continuous counter behind POS_TRANSACTION_MST.BATCH_NO, rendered zero-padded to 6 digits.';

-- ── 6. Constraints ──────────────────────────────────────────────────────────
DO $migration$
BEGIN
    -- Purchase invoice numbers must be unique. Partial so the many historical
    -- rows without one, and non-purchase transactions, stay unaffected.
    IF NOT EXISTS (SELECT 1 FROM pg_class WHERE relname = 'ux_purchase_invoice_no') THEN
        CREATE UNIQUE INDEX ux_purchase_invoice_no
            ON PHARMA.POS_TRANSACTION_MST (INVOICE_NO)
            WHERE INVOICE_NO IS NOT NULL AND TRANSACTION_TYPE = 'PU';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_tran_product_type') THEN
        ALTER TABLE PHARMA.POS_TRANSACTION_MST ADD CONSTRAINT ck_tran_product_type
            CHECK (PRODUCT_TYPE IS NULL OR PRODUCT_TYPE IN ('PHARMA', 'NON-PHARMA'));
    END IF;

    -- Purchases use P/A/D. Other transaction types keep using Y/N, so the
    -- constraint has to accept both rather than replacing one with the other.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_tran_status') THEN
        ALTER TABLE PHARMA.POS_TRANSACTION_MST ADD CONSTRAINT ck_tran_status
            CHECK (STATUS IS NULL OR STATUS IN ('P', 'A', 'D', 'Y', 'N', 'C'));
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_tran_dtl_quantity') THEN
        ALTER TABLE PHARMA.POS_TRANSACTION_DTL ADD CONSTRAINT ck_tran_dtl_quantity
            CHECK (QUANTITY > 0);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_tran_dtl_pct_range') THEN
        ALTER TABLE PHARMA.POS_TRANSACTION_DTL ADD CONSTRAINT ck_tran_dtl_pct_range
            CHECK (COALESCE(DISCOUNT_PCT,0) BETWEEN 0 AND 100
               AND COALESCE(TP_PCT,0)       BETWEEN 0 AND 100);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_tran_dtl_sales_type') THEN
        ALTER TABLE PHARMA.POS_TRANSACTION_DTL ADD CONSTRAINT ck_tran_dtl_sales_type
            CHECK (SALES_TYPE IS NULL OR SALES_TYPE IN ('BOX', 'STRIP', 'PCS'));
    END IF;
END
$migration$;

-- ── 6b. Detail rows must belong to a real invoice ───────────────────────────
-- There was no foreign key here, and the consequence is not theoretical: a
-- detail row could be written for a master that did not exist, sit as an
-- orphan, and then be silently ADOPTED by a later invoice that happened to be
-- allocated the same PID_TRAN_MST — appearing on that invoice as if it had
-- always been one of its lines.
--
-- Orphans are removed first, because the constraint cannot be added while any
-- remain. ON DELETE CASCADE also means cancelling an invoice takes its lines
-- with it instead of leaving new orphans behind.
DELETE FROM PHARMA.POS_TRANSACTION_DTL d
 WHERE NOT EXISTS (
     SELECT 1 FROM PHARMA.POS_TRANSACTION_MST m WHERE m.PID_TRAN_MST = d.PID_TRAN_MST);

DO $migration$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_tran_dtl_mst') THEN
        ALTER TABLE PHARMA.POS_TRANSACTION_DTL
            ADD CONSTRAINT fk_tran_dtl_mst FOREIGN KEY (PID_TRAN_MST)
            REFERENCES PHARMA.POS_TRANSACTION_MST (PID_TRAN_MST) ON DELETE CASCADE;
    END IF;
END
$migration$;

-- ── 7. Indexes for the purchase list ────────────────────────────────────────
CREATE INDEX IF NOT EXISTS IX_TRAN_MST_TYPE_DATE
    ON PHARMA.POS_TRANSACTION_MST (TRANSACTION_TYPE, TRANSACTION_DATE DESC);
CREATE INDEX IF NOT EXISTS IX_TRAN_MST_ORG_TYPE
    ON PHARMA.POS_TRANSACTION_MST (PID_ORG, TRANSACTION_TYPE);
CREATE INDEX IF NOT EXISTS IX_TRAN_MST_SUPPLIER
    ON PHARMA.POS_TRANSACTION_MST (PID_SUP_CUST);
CREATE INDEX IF NOT EXISTS IX_TRAN_MST_STATUS
    ON PHARMA.POS_TRANSACTION_MST (STATUS);
CREATE INDEX IF NOT EXISTS IX_TRAN_DTL_MST
    ON PHARMA.POS_TRANSACTION_DTL (PID_TRAN_MST);

-- ── 8. Menu integration — Purchase already exists as MOD03 ──────────────────
-- Nothing to add; V3 seeded the Purchase module and the sidebar reads it.
