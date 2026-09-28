-- ═══════════════════════════════════════════════════════════════════════════
-- V10 — Customer type drives sellable units and price list
--
--   Wholesaler   BOX, STRIP, PCS   regular prices
--   Special      BOX, STRIP, PCS   special prices
--   Retailer     PCS only          regular (wholesaler) piece price
--
-- CUSTOMER_TYPE already existed and held free text — only 'Wholesaler' was ever
-- written. It now decides what a sale may contain and what it costs, so the
-- allowed values are constrained rather than left open. A typo in this column
-- would otherwise silently reprice an invoice.
-- ═══════════════════════════════════════════════════════════════════════════

-- Normalise what is already there before constraining it. The Java side reads
-- these case-insensitively by prefix, but storing one spelling keeps the data
-- honest and makes the constraint below meaningful.
UPDATE PHARMA.POS_CUSTOMER_INFO
   SET CUSTOMER_TYPE = 'Wholesaler'
 WHERE CUSTOMER_TYPE IS NULL
    OR UPPER(TRIM(CUSTOMER_TYPE)) LIKE 'WHOLESALE%';

UPDATE PHARMA.POS_CUSTOMER_INFO
   SET CUSTOMER_TYPE = 'Retailer'
 WHERE UPPER(TRIM(CUSTOMER_TYPE)) LIKE 'RETAIL%';

UPDATE PHARMA.POS_CUSTOMER_INFO
   SET CUSTOMER_TYPE = 'Special'
 WHERE UPPER(TRIM(CUSTOMER_TYPE)) LIKE 'SPECIAL%';

-- Anything still unrecognised becomes Retailer — the most restrictive tier.
-- A customer whose terms nobody recorded must not default into wholesale box
-- pricing, which is the expensive direction to be wrong in.
UPDATE PHARMA.POS_CUSTOMER_INFO
   SET CUSTOMER_TYPE = 'Retailer'
 WHERE CUSTOMER_TYPE NOT IN ('Wholesaler', 'Retailer', 'Special');

ALTER TABLE PHARMA.POS_CUSTOMER_INFO
    ALTER COLUMN CUSTOMER_TYPE SET DEFAULT 'Retailer';

ALTER TABLE PHARMA.POS_CUSTOMER_INFO
    ALTER COLUMN CUSTOMER_TYPE SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_customer_type') THEN
        ALTER TABLE PHARMA.POS_CUSTOMER_INFO
            ADD CONSTRAINT ck_customer_type
            CHECK (CUSTOMER_TYPE IN ('Wholesaler', 'Retailer', 'Special'));
    END IF;
END $$;

-- Sales lists and the pricing lookup both filter on it.
CREATE INDEX IF NOT EXISTS ix_customer_type
    ON PHARMA.POS_CUSTOMER_INFO (PID_ORG, CUSTOMER_TYPE, STATUS);

COMMENT ON COLUMN PHARMA.POS_CUSTOMER_INFO.CUSTOMER_TYPE IS
    'Pricing tier. Wholesaler: BOX/STRIP/PCS at regular prices. Special: '
    'BOX/STRIP/PCS at special prices. Retailer: PCS only, at the wholesaler '
    'piece rate. Read by CustomerPricing.tierOf().';


-- ── One customer of each tier, so the rules can actually be exercised ──────
-- Only 'Wholesaler' existed, which meant the retailer and special paths had no
-- way to be tested through the UI.

INSERT INTO PHARMA.POS_CUSTOMER_INFO
       (PID_CUSTOMER, PID_ORG, PID_BRANCH, CUSTOMER_CODE, CUSTOMER_NAME,
        MOBILE_NO, ADDRESS, CITY, CUSTOMER_TYPE, OPENING_BAL, CREDIT_LIMIT,
        STATUS, INSERTED_BY, INSERTED_DATE)
SELECT v.pid, 1, 1, v.code, v.name, v.mobile, v.addr, 'Dhaka', v.ctype,
       0, v.credit, 'Y', 'V10_SEED', CURRENT_TIMESTAMP
  FROM (VALUES
          (9001, 'CUS-RET-01', 'Rahim Medical Store',   '01711000901', '12 Green Road',   'Retailer',   5000),
          (9002, 'CUS-SPC-01', 'City Hospital Pharmacy', '01711000902', '44 Panthapath',   'Special',   50000)
       ) AS v(pid, code, name, mobile, addr, ctype, credit)
 WHERE NOT EXISTS (
       SELECT 1 FROM PHARMA.POS_CUSTOMER_INFO c WHERE c.PID_CUSTOMER = v.pid);

-- The table's id is assigned by the application, not a sequence default, so
-- nothing needs resetting here — but if a sequence is ever added, it must start
-- above 9002.
