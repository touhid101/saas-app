-- ═══════════════════════════════════════════════════════════════════════════
-- V24 — The advance handed over with the purchase invoice
--
-- V23 gave the payment screen a way to pay a bill plus its discount, with the
-- discount booked as money the supplier owes back. The same thing happens at
-- the counter when the invoice is first keyed in: the cash in GIVEN_AMOUNT is
-- sometimes the gross, not the net.
--
-- There was nowhere to say so. GIVEN_AMOUNT is a single figure, and the ledger
-- mirror capped it at the payable amount — so an operator who handed over the
-- gross had the extra silently trimmed off, and the rebate owed back vanished
-- before it was ever recorded.
--
-- ADVANCE_AMOUNT is the slice of GIVEN_AMOUNT that settles no part of the bill.
-- The rest of the system already knows what to do with it: the approval mirror
-- copies it onto the POS_SUPPLIER_PAYMENT row, and from there V_PURCHASE_PAYABLE
-- excludes it from what the invoice has been paid while V_SUPPLIER_ACCOUNT
-- carries it as a receivable.
-- ═══════════════════════════════════════════════════════════════════════════

ALTER TABLE PHARMA.POS_TRANSACTION_MST
    ADD COLUMN IF NOT EXISTS ADVANCE_AMOUNT NUMERIC(14,4) NOT NULL DEFAULT 0;

DO $$
BEGIN
    -- An advance is carved out of the cash handed over, so it cannot exceed it.
    -- Written against COALESCE because GIVEN_AMOUNT is nullable on older rows.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_tran_advance_within_given') THEN
        ALTER TABLE PHARMA.POS_TRANSACTION_MST
            ADD CONSTRAINT CK_TRAN_ADVANCE_WITHIN_GIVEN
            CHECK (ADVANCE_AMOUNT >= 0 AND ADVANCE_AMOUNT <= COALESCE(GIVEN_AMOUNT, 0));
    END IF;
END $$;

COMMENT ON COLUMN PHARMA.POS_TRANSACTION_MST.ADVANCE_AMOUNT IS
    'Part of GIVEN_AMOUNT that settles no part of this bill — typically the '
    'invoice discount handed over on the understanding the supplier returns it. '
    'Mirrored onto the supplier payment ledger when the purchase is approved.';
