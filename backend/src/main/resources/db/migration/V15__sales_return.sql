-- ═══════════════════════════════════════════════════════════════════════════
-- V15 — Sales return: link the return line to the line it reverses
--
-- SRS FR-11: "System shall support sales returns linked to original
-- transactions." The columns for that link already existed —
-- POS_TRANSACTION_DTL.PID_TRAN_DTL_RETURN and RETURN_QTY — but nothing
-- constrained or indexed them, and no code wrote to them.
--
-- The link is what makes a return correct rather than merely plausible. A
-- return line must put the goods back in the batch the sale took them from, at
-- the price they were sold at, and no more than what actually went out. All
-- three facts live on the original detail row, so the pointer to it is the
-- whole mechanism.
-- ═══════════════════════════════════════════════════════════════════════════

-- Returned quantity defaults to zero rather than NULL.
--
-- The remaining-returnable sum is QUANTITY - RETURN_QTY, and a NULL there makes
-- the whole expression NULL — which compares false against every threshold, so
-- a line that had never been returned would silently refuse to be returned at
-- all. COALESCE at each call site would work; one default is harder to forget.
UPDATE PHARMA.POS_TRANSACTION_DTL SET RETURN_QTY = 0 WHERE RETURN_QTY IS NULL;

ALTER TABLE PHARMA.POS_TRANSACTION_DTL
    ALTER COLUMN RETURN_QTY SET DEFAULT 0;

-- A line cannot have more returned than was sold, and cannot go negative.
-- Enforced here as well as in the service: the service explains why, this
-- guarantees it even if some future path forgets to ask.
ALTER TABLE PHARMA.POS_TRANSACTION_DTL
    DROP CONSTRAINT IF EXISTS CK_TRAN_DTL_RETURN_QTY;

ALTER TABLE PHARMA.POS_TRANSACTION_DTL
    ADD CONSTRAINT CK_TRAN_DTL_RETURN_QTY
        CHECK (COALESCE(RETURN_QTY, 0) >= 0
           AND COALESCE(RETURN_QTY, 0) <= QUANTITY);

-- The return line points at the sale line it reverses.
--
-- ON DELETE SET NULL rather than CASCADE: deleting a sale line must not delete
-- the return that reversed it. The return moved real stock and took real money
-- off an invoice — it is a fact in its own right, and orphaning it is honest
-- where erasing it is not.
ALTER TABLE PHARMA.POS_TRANSACTION_DTL
    DROP CONSTRAINT IF EXISTS FK_TRAN_DTL_RETURN_SOURCE;

ALTER TABLE PHARMA.POS_TRANSACTION_DTL
    ADD CONSTRAINT FK_TRAN_DTL_RETURN_SOURCE
        FOREIGN KEY (PID_TRAN_DTL_RETURN) REFERENCES PHARMA.POS_TRANSACTION_DTL (PID_TRAN_DTL)
        ON DELETE SET NULL;

-- "What has already been returned against this sale line?" — asked once per
-- line every time a return screen opens, and again on every save.
CREATE INDEX IF NOT EXISTS IX_TRAN_DTL_RETURN_SOURCE
    ON PHARMA.POS_TRANSACTION_DTL (PID_TRAN_DTL_RETURN)
 WHERE PID_TRAN_DTL_RETURN IS NOT NULL;

-- The return master points at the sale it reverses, so an invoice can show its
-- returns and a return can show its origin. Reuses PID_TRAN_MST_REF if the
-- column exists; otherwise adds it.
ALTER TABLE PHARMA.POS_TRANSACTION_MST
    ADD COLUMN IF NOT EXISTS PID_TRAN_MST_RETURN BIGINT;

ALTER TABLE PHARMA.POS_TRANSACTION_MST
    DROP CONSTRAINT IF EXISTS FK_TRAN_MST_RETURN_SOURCE;

ALTER TABLE PHARMA.POS_TRANSACTION_MST
    ADD CONSTRAINT FK_TRAN_MST_RETURN_SOURCE
        FOREIGN KEY (PID_TRAN_MST_RETURN) REFERENCES PHARMA.POS_TRANSACTION_MST (PID_TRAN_MST)
        ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS IX_TRAN_MST_RETURN_SOURCE
    ON PHARMA.POS_TRANSACTION_MST (PID_TRAN_MST_RETURN)
 WHERE PID_TRAN_MST_RETURN IS NOT NULL;

COMMENT ON COLUMN PHARMA.POS_TRANSACTION_DTL.PID_TRAN_DTL_RETURN IS
    'On a sales-return line: the sale line being reversed. Carries the batch, '
    'expiry and price the goods must go back at.';

COMMENT ON COLUMN PHARMA.POS_TRANSACTION_DTL.RETURN_QTY IS
    'On a sale line: how much of it has been returned so far, in the line''s own '
    'sales unit. Capped at QUANTITY by CK_TRAN_DTL_RETURN_QTY.';

COMMENT ON COLUMN PHARMA.POS_TRANSACTION_MST.PID_TRAN_MST_RETURN IS
    'On a sales-return invoice: the sale being returned against.';
