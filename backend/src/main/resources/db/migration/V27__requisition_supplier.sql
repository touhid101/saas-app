-- ═══════════════════════════════════════════════════════════════════════════
-- A requisition is raised on a supplier, and a supplier represents a
-- manufacturer.
--
-- Both halves of that sentence were already half-true in the schema and
-- half-missing:
--
--   * POS_SUPPLIER_INFO.POS_PID_COMPANY already named the manufacturer a
--     supplier distributes for, but it was nullable and unconstrained — so
--     nothing stopped a supplier existing with no manufacturer, or pointing at
--     a company id that had been deleted.
--
--   * POS_REQUISITION_MST had no supplier column at all. The requisition form
--     asks for a supplier, validates that one was chosen, and then drops it:
--     there was nowhere to put it. Every requisition ever raised has been
--     anonymous as to who it was going to.
--
-- With the supplier recorded, a requisition line can be checked against what
-- that supplier actually sells, which is the point of asking.
-- ═══════════════════════════════════════════════════════════════════════════

-- ── 1. a supplier must name the manufacturer it distributes for ─────────────

-- Safe as it stands: every supplier on file already has one.
ALTER TABLE PHARMA.POS_SUPPLIER_INFO
    ALTER COLUMN POS_PID_COMPANY SET NOT NULL;

ALTER TABLE PHARMA.POS_SUPPLIER_INFO
    ADD CONSTRAINT FK_SUPPLIER_COMPANY
    FOREIGN KEY (POS_PID_COMPANY) REFERENCES PHARMA.POS_COMPANY_INFO (PID_COMPANY);

CREATE INDEX IF NOT EXISTS IX_SUPPLIER_COMPANY
    ON PHARMA.POS_SUPPLIER_INFO (POS_PID_COMPANY);

-- ── 2. a requisition remembers who it is for ────────────────────────────────

ALTER TABLE PHARMA.POS_REQUISITION_MST
    ADD COLUMN IF NOT EXISTS PID_SUPPLIER BIGINT;

ALTER TABLE PHARMA.POS_REQUISITION_MST
    ADD CONSTRAINT FK_REQUISITION_SUPPLIER
    FOREIGN KEY (PID_SUPPLIER) REFERENCES PHARMA.POS_SUPPLIER_INFO (PID_SUPPLIER);

CREATE INDEX IF NOT EXISTS IX_REQUISITION_SUPPLIER
    ON PHARMA.POS_REQUISITION_MST (PID_SUPPLIER, REQ_DATE DESC);

COMMENT ON COLUMN PHARMA.POS_REQUISITION_MST.PID_SUPPLIER IS
    'Who the requisition is going to. Nullable on purpose: a buyer working from '
    'the reorder list may raise one covering several manufacturers and split it '
    'later. When it is set, every line must be a product of that supplier''s '
    'manufacturer — the service enforces it.';

-- ── 3. what a supplier can actually be asked for ────────────────────────────

-- Joins the two facts the purchase screens keep re-deriving by hand: which
-- manufacturer a supplier represents, and which products that manufacturer
-- makes. Filtering a product list by supplier is a select against this rather
-- than a lookup followed by a second query.
CREATE OR REPLACE VIEW PHARMA.V_SUPPLIER_PRODUCT AS
SELECT s.PID_SUPPLIER,
       s.SUPPLIER_NAME,
       s.PID_ORG,
       s.PID_BRANCH,
       c.PID_COMPANY,
       c.COMPANY_NAME,
       p.PID_PRODUCT,
       p.PRODUCT_NAME,
       p.SKU,
       p.TP_PRICE,
       p.MRP_PRICE,
       p.REORDER_LEVEL,
       p.ALERT_QTY,
       p.STATUS AS PRODUCT_STATUS
  FROM PHARMA.POS_SUPPLIER_INFO s
  JOIN PHARMA.POS_COMPANY_INFO  c ON c.PID_COMPANY = s.POS_PID_COMPANY
  JOIN PHARMA.POS_PRODUCT_INFO  p ON p.PID_COMPANY = c.PID_COMPANY
 WHERE s.STATUS = 'Y';

COMMENT ON VIEW PHARMA.V_SUPPLIER_PRODUCT IS
    'Products each supplier can be asked for, by way of the manufacturer they '
    'distribute. One manufacturer per supplier, which is what the column allows; '
    'a distributor carrying several brands would need a link table instead.';
