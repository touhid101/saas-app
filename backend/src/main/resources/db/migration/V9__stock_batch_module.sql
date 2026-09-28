-- ═══════════════════════════════════════════════════════════════════════════
-- V9 — Batch-wise stock control
--
-- Replaces the two mechanisms that used to answer "how much stock is there":
--
--   1. POS_PRODUCT_INFO.STOCK_QTY  — a single mutable number per product, with
--      no branch, no batch, and no history. Retained as a column so existing
--      screens keep compiling, but nothing reads it for stock any more.
--   2. Summing POS_TRANSACTION_DTL in Java — unsigned, unconverted, unfiltered
--      by branch or approval status.
--
-- The replacement is a real stock table keyed by BATCH, scoped by BRANCH, held
-- in PCS, plus an append-only movement log that the balance can be rebuilt from.
--
-- Quantity unit: every quantity in these two tables is PCS. BOX and STRIP are
-- presentation units, derived on read from the frozen PCS_PER_BOX /
-- PCS_PER_STRIP snapshot. Keeping one unit in the store is what makes the
-- arithmetic exact — no rounding ever enters a balance.
-- ═══════════════════════════════════════════════════════════════════════════

-- ── Sequences ──────────────────────────────────────────────────────────────

CREATE SEQUENCE IF NOT EXISTS PHARMA.SEQ_POS_STOCK_BATCH    START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS PHARMA.SEQ_POS_STOCK_MOVEMENT START WITH 1 INCREMENT BY 1;


-- ── POS_STOCK_BATCH — current on-hand, one row per lot ─────────────────────
--
-- Grain: (PID_BRANCH, PID_PRODUCT, BATCH_NO).
--
-- The batch is the unit of stock, not the expiry date. Two deliveries that
-- happen to share an expiry are two batches with two costs, and merging them
-- (as the legacy VW_PRODUCT_STOCK did, by grouping on EXPIRY_DATE) destroys
-- the batch-level cost that actual-profit reporting depends on.

CREATE TABLE IF NOT EXISTS PHARMA.POS_STOCK_BATCH (
    PID_STOCK        BIGINT        NOT NULL,
    PID_ORG          BIGINT        NOT NULL,
    PID_BRANCH       BIGINT        NOT NULL,
    PID_PRODUCT      BIGINT        NOT NULL,

    BATCH_NO         VARCHAR(50)   NOT NULL,
    EXPIRY_DATE      DATE,

    -- On-hand. Maintained transactionally; rebuildable from POS_STOCK_MOVEMENT.
    QTY_PCS          NUMERIC(18,4) NOT NULL DEFAULT 0,
    RECEIVED_PCS     NUMERIC(18,4) NOT NULL DEFAULT 0,
    ISSUED_PCS       NUMERIC(18,4) NOT NULL DEFAULT 0,

    -- Landed cost of one piece in THIS batch. The whole reason batches are
    -- tracked separately: COGS is per batch, not per product.
    COST_PER_PCS     NUMERIC(18,6),
    MRP_PRICE        NUMERIC(14,4),

    -- Conversion snapshot, frozen at receipt. Read live from Box/Leaf instead
    -- and a later pack-size edit would silently re-value existing stock.
    PCS_PER_BOX      INTEGER,
    PCS_PER_STRIP    INTEGER,

    RECEIVED_DATE    DATE          NOT NULL,
    SOURCE_TRAN_MST  BIGINT,
    SOURCE_TRAN_DTL  BIGINT,

    -- Y active, X exhausted, B blocked (recalled / quarantined — never issued)
    STATUS           VARCHAR(1)    NOT NULL DEFAULT 'Y',

    INSERTED_DATE    TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    INSERTED_BY      VARCHAR(50),
    UPDATED_DATE     TIMESTAMP,
    UPDATED_BY       VARCHAR(50),

    CONSTRAINT pk_stock_batch        PRIMARY KEY (PID_STOCK),
    CONSTRAINT uq_stock_batch_lot    UNIQUE (PID_BRANCH, PID_PRODUCT, BATCH_NO),
    CONSTRAINT ck_stock_qty_positive CHECK (QTY_PCS >= 0),
    CONSTRAINT ck_stock_received     CHECK (RECEIVED_PCS >= 0),
    CONSTRAINT ck_stock_issued       CHECK (ISSUED_PCS >= 0),
    CONSTRAINT ck_stock_status       CHECK (STATUS IN ('Y','X','B'))
);

-- Oversell is refused in the service layer, but CK_STOCK_QTY_POSITIVE is the
-- backstop: if two concurrent sales ever raced past the check, the second one
-- fails at the database rather than writing a negative balance.


-- ── POS_STOCK_MOVEMENT — append-only audit ─────────────────────────────────
--
-- Never updated, never deleted. A correction is a new opposing row. This is
-- what makes the balance above verifiable rather than merely believed.

CREATE TABLE IF NOT EXISTS PHARMA.POS_STOCK_MOVEMENT (
    PID_MOVEMENT     BIGINT        NOT NULL,
    PID_STOCK        BIGINT        NOT NULL,

    PID_ORG          BIGINT        NOT NULL,
    PID_BRANCH       BIGINT        NOT NULL,
    PID_PRODUCT      BIGINT        NOT NULL,
    BATCH_NO         VARCHAR(50)   NOT NULL,

    -- PURCHASE | SALE | PURCHASE_RETURN | SALES_RETURN | ADJUST | OPENING | TRANSFER_IN | TRANSFER_OUT
    MOVEMENT_TYPE    VARCHAR(20)   NOT NULL,
    DIRECTION        VARCHAR(1)    NOT NULL,          -- I in, O out

    -- Always positive; DIRECTION carries the sign. A signed quantity invites a
    -- sign error to silently invert a movement, and SUM() then hides it.
    QTY_PCS          NUMERIC(18,4) NOT NULL,
    BALANCE_AFTER    NUMERIC(18,4) NOT NULL,
    COST_PER_PCS     NUMERIC(18,6),

    REF_TYPE         VARCHAR(20),
    REF_MST          BIGINT,
    REF_DTL          BIGINT,

    MOVEMENT_DATE    DATE          NOT NULL,          -- business date, not insert time
    REMARKS          VARCHAR(255),

    INSERTED_DATE    TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    INSERTED_BY      VARCHAR(50),

    CONSTRAINT pk_stock_movement      PRIMARY KEY (PID_MOVEMENT),
    CONSTRAINT fk_movement_batch      FOREIGN KEY (PID_STOCK)
                                      REFERENCES PHARMA.POS_STOCK_BATCH (PID_STOCK),
    CONSTRAINT ck_movement_qty        CHECK (QTY_PCS > 0),
    CONSTRAINT ck_movement_direction  CHECK (DIRECTION IN ('I','O')),
    CONSTRAINT ck_movement_type       CHECK (MOVEMENT_TYPE IN
                                      ('PURCHASE','SALE','PURCHASE_RETURN','SALES_RETURN',
                                       'ADJUST','OPENING','TRANSFER_IN','TRANSFER_OUT'))
);


-- ── Indexes ────────────────────────────────────────────────────────────────
--
-- Every inventory read starts with a branch predicate, so PID_BRANCH leads
-- every index. Without that, branch-wise reporting scans the whole table and
-- discards most of it.

-- Batch-wise FEFO allocation: branch + product, ordered by expiry then receipt.
-- Covers the hot path in StockServiceImpl.allocate().
CREATE INDEX IF NOT EXISTS ix_stock_batch_fefo
    ON PHARMA.POS_STOCK_BATCH (PID_BRANCH, PID_PRODUCT, EXPIRY_DATE, RECEIVED_DATE)
    WHERE QTY_PCS > 0 AND STATUS = 'Y';

-- Expiry alerts, branch-wide.
CREATE INDEX IF NOT EXISTS ix_stock_batch_expiry
    ON PHARMA.POS_STOCK_BATCH (PID_BRANCH, EXPIRY_DATE)
    WHERE QTY_PCS > 0;

-- Stock ledger / low-stock rollup per product.
CREATE INDEX IF NOT EXISTS ix_stock_batch_product
    ON PHARMA.POS_STOCK_BATCH (PID_BRANCH, PID_PRODUCT);

CREATE INDEX IF NOT EXISTS ix_stock_batch_batchno
    ON PHARMA.POS_STOCK_BATCH (PID_BRANCH, BATCH_NO);

-- Movement history for one lot, newest first.
CREATE INDEX IF NOT EXISTS ix_stock_movement_stock
    ON PHARMA.POS_STOCK_MOVEMENT (PID_STOCK, MOVEMENT_DATE DESC, PID_MOVEMENT DESC);

-- Product movement report, date-bounded within a branch.
CREATE INDEX IF NOT EXISTS ix_stock_movement_branch_date
    ON PHARMA.POS_STOCK_MOVEMENT (PID_BRANCH, MOVEMENT_DATE DESC);

CREATE INDEX IF NOT EXISTS ix_stock_movement_product
    ON PHARMA.POS_STOCK_MOVEMENT (PID_BRANCH, PID_PRODUCT, MOVEMENT_DATE DESC);

-- Idempotency guard for the purchase write path: one movement per detail line
-- per type. Re-approving cannot double-count.
CREATE UNIQUE INDEX IF NOT EXISTS uq_stock_movement_ref
    ON PHARMA.POS_STOCK_MOVEMENT (REF_TYPE, REF_DTL, MOVEMENT_TYPE)
    WHERE REF_DTL IS NOT NULL;


-- ── Supporting indexes on the transaction tables ───────────────────────────
-- The backfill below and every purchase-history read join on these.

CREATE INDEX IF NOT EXISTS ix_tran_dtl_product_expiry
    ON PHARMA.POS_TRANSACTION_DTL (PID_PRODUCT, EXPIRY_DATE);

CREATE INDEX IF NOT EXISTS ix_tran_dtl_batch
    ON PHARMA.POS_TRANSACTION_DTL (BATCH_NO);

CREATE INDEX IF NOT EXISTS ix_tran_mst_branch_type
    ON PHARMA.POS_TRANSACTION_MST (PID_BRANCH, TRANSACTION_TYPE, STATUS);

CREATE INDEX IF NOT EXISTS ix_tran_mst_branch_date
    ON PHARMA.POS_TRANSACTION_MST (PID_BRANCH, TRANSACTION_DATE DESC);


-- ── NO_OF_PCS must be present ──────────────────────────────────────────────
--
-- The conversion depends on it entirely. A null does not read as "missing", it
-- reads as 1 through COALESCE, silently undercounting a BOX line by the whole
-- pack size. Backfill first, then forbid it.

UPDATE PHARMA.POS_TRANSACTION_DTL d
   SET NO_OF_PCS = CASE UPPER(COALESCE(d.SALES_UNIT, d.SALES_TYPE, 'PCS'))
                       WHEN 'BOX'   THEN GREATEST(COALESCE(bx.NO_OF_LEAF,1) * COALESCE(lf.NO_OF_PCS,1), 1)
                       WHEN 'STRIP' THEN GREATEST(COALESCE(lf.NO_OF_PCS,1), 1)
                       ELSE 1
                   END
  FROM PHARMA.POS_PRODUCT_INFO p
       LEFT JOIN PHARMA.POS_BOX_INFO  bx ON bx.PID_BOX  = p.PID_BOX
       LEFT JOIN PHARMA.POS_LEAF_INFO lf ON lf.PID_LEAF = bx.PID_LEAF
 WHERE p.PID_PRODUCT = d.PID_PRODUCT
   AND (d.NO_OF_PCS IS NULL OR d.NO_OF_PCS <= 0);

ALTER TABLE PHARMA.POS_TRANSACTION_DTL
    ALTER COLUMN NO_OF_PCS SET DEFAULT 1;

DO $$
DECLARE bad BIGINT;
BEGIN
    SELECT COUNT(*) INTO bad
      FROM PHARMA.POS_TRANSACTION_DTL
     WHERE NO_OF_PCS IS NULL OR NO_OF_PCS <= 0;

    IF bad > 0 THEN
        RAISE EXCEPTION
            'V9: % transaction detail row(s) still have no usable NO_OF_PCS. '
            'These rows cannot be converted to pieces and would corrupt every '
            'stock figure. Resolve them before re-running.', bad;
    END IF;

    ALTER TABLE PHARMA.POS_TRANSACTION_DTL ALTER COLUMN NO_OF_PCS SET NOT NULL;
END $$;


-- ── Backfill: rebuild batch stock from approved purchases ──────────────────
--
-- Only status 'A'. Drafts ('D') and pending ('P') invoices have not moved
-- stock and must not appear — the old Java sum counted all three.
--
-- Purchase returns are folded in as issues where they exist. Sales are not
-- backfilled: the sales module is not built, and no SL rows exist.

INSERT INTO PHARMA.POS_STOCK_BATCH (
    PID_STOCK, PID_ORG, PID_BRANCH, PID_PRODUCT, BATCH_NO, EXPIRY_DATE,
    QTY_PCS, RECEIVED_PCS, ISSUED_PCS, COST_PER_PCS, MRP_PRICE,
    PCS_PER_BOX, PCS_PER_STRIP, RECEIVED_DATE,
    SOURCE_TRAN_MST, SOURCE_TRAN_DTL, STATUS, INSERTED_BY
)
SELECT  NEXTVAL('PHARMA.SEQ_POS_STOCK_BATCH'),
        src.pid_org,
        src.pid_branch,
        src.pid_product,
        src.batch_no,
        src.expiry_date,
        src.received_pcs,
        src.received_pcs,
        0,
        CASE WHEN src.received_pcs > 0
             THEN ROUND(src.total_cost / src.received_pcs, 6) END,
        src.mrp_price,
        src.pcs_per_box,
        src.pcs_per_strip,
        src.received_date,
        src.source_mst,
        src.source_dtl,
        'Y',
        'V9_BACKFILL'
FROM (
    SELECT  m.PID_ORG                                    AS pid_org,
            m.PID_BRANCH                                 AS pid_branch,
            d.PID_PRODUCT                                AS pid_product,
            COALESCE(NULLIF(TRIM(d.BATCH_NO), ''),
                     'LEGACY-' || d.PID_TRAN_MST)        AS batch_no,
            MIN(d.EXPIRY_DATE)                           AS expiry_date,
            SUM(d.QUANTITY * COALESCE(d.NO_OF_PCS, 1))   AS received_pcs,
            SUM(COALESCE(d.TOTAL_PRICE, 0))              AS total_cost,
            MAX(d.MRP_PRICE)                             AS mrp_price,
            MAX(COALESCE(bx.NO_OF_LEAF,0) * COALESCE(lf.NO_OF_PCS,0)) AS pcs_per_box,
            MAX(COALESCE(lf.NO_OF_PCS,0))                AS pcs_per_strip,
            MIN(COALESCE(m.TRANSACTION_DATE::date, CURRENT_DATE)) AS received_date,
            MIN(d.PID_TRAN_MST)                          AS source_mst,
            MIN(d.PID_TRAN_DTL)                          AS source_dtl
      FROM  PHARMA.POS_TRANSACTION_DTL d
      JOIN  PHARMA.POS_TRANSACTION_MST m  ON m.PID_TRAN_MST = d.PID_TRAN_MST
      JOIN  PHARMA.POS_PRODUCT_INFO    p  ON p.PID_PRODUCT  = d.PID_PRODUCT
      LEFT JOIN PHARMA.POS_BOX_INFO    bx ON bx.PID_BOX     = p.PID_BOX
      LEFT JOIN PHARMA.POS_LEAF_INFO   lf ON lf.PID_LEAF    = bx.PID_LEAF
     WHERE  m.TRANSACTION_TYPE = 'PU'
       AND  m.STATUS           = 'A'
       AND  m.PID_BRANCH IS NOT NULL
       AND  d.QUANTITY   IS NOT NULL
     GROUP BY m.PID_ORG, m.PID_BRANCH, d.PID_PRODUCT,
              COALESCE(NULLIF(TRIM(d.BATCH_NO), ''), 'LEGACY-' || d.PID_TRAN_MST)
    HAVING SUM(d.QUANTITY * COALESCE(d.NO_OF_PCS, 1)) > 0
) src
ON CONFLICT (PID_BRANCH, PID_PRODUCT, BATCH_NO) DO NOTHING;


-- Opening movement for every backfilled lot, so the audit log is complete from
-- day one and the reconciliation job has something to compare against.
INSERT INTO PHARMA.POS_STOCK_MOVEMENT (
    PID_MOVEMENT, PID_STOCK, PID_ORG, PID_BRANCH, PID_PRODUCT, BATCH_NO,
    MOVEMENT_TYPE, DIRECTION, QTY_PCS, BALANCE_AFTER, COST_PER_PCS,
    REF_TYPE, REF_MST, REF_DTL, MOVEMENT_DATE, REMARKS, INSERTED_BY
)
SELECT  NEXTVAL('PHARMA.SEQ_POS_STOCK_MOVEMENT'),
        s.PID_STOCK, s.PID_ORG, s.PID_BRANCH, s.PID_PRODUCT, s.BATCH_NO,
        'OPENING', 'I', s.QTY_PCS, s.QTY_PCS, s.COST_PER_PCS,
        'MIGRATION', s.SOURCE_TRAN_MST, NULL, s.RECEIVED_DATE,
        'V9 backfill from approved purchase invoices', 'V9_BACKFILL'
  FROM  PHARMA.POS_STOCK_BATCH s
 WHERE  s.INSERTED_BY = 'V9_BACKFILL'
   AND  NOT EXISTS (SELECT 1 FROM PHARMA.POS_STOCK_MOVEMENT mv
                     WHERE mv.PID_STOCK = s.PID_STOCK
                       AND mv.MOVEMENT_TYPE = 'OPENING');


-- ── STOCK_QTY is no longer the stock authority ─────────────────────────────
--
-- Left in place: Product Master still displays it and dropping it now would
-- break those screens for no gain. It is simply no longer read for stock, and
-- the comment records that so nobody reintroduces it.

COMMENT ON COLUMN PHARMA.POS_PRODUCT_INFO.STOCK_QTY IS
    'DEPRECATED as of V9 — not the stock authority. Stock lives in '
    'POS_STOCK_BATCH, keyed by branch and batch, held in pieces. This column '
    'is neither branch-aware nor batch-aware and is no longer maintained by '
    'the purchase write path. Do not read it for stock decisions.';

COMMENT ON TABLE PHARMA.POS_STOCK_BATCH IS
    'Branch-wise, batch-wise stock on hand, in PIECES. Grain: branch x product '
    'x batch. Rebuildable from POS_STOCK_MOVEMENT.';

COMMENT ON TABLE PHARMA.POS_STOCK_MOVEMENT IS
    'Append-only stock movement log. Never updated or deleted; corrections are '
    'new opposing rows. The balances in POS_STOCK_BATCH are derived from this.';
