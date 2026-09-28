-- ════════════════════════════════════════════════════════════════════════════
-- V114 — a pharmacy's verification, so the storefront's badge means something
--
-- Additive only. Nothing existing is altered, renamed or dropped.
--
-- WHY
-- The storefront card carried a "· Licensed" badge beside every pharmacy name.
-- ALL_BRANCH held no licence number and no verification flag, so the badge was
-- a trust mark with nothing behind it — it appeared for a pharmacy nobody had
-- ever checked, exactly as it did for one somebody had. It was removed, and
-- these columns are what puts it back honestly.
--
-- DEFAULT 'N' ON PURPOSE
-- Every existing pharmacy starts unverified. Back-filling 'Y' would assert
-- that a check happened for branches where none did, which is the same lie in
-- a different place. Staff tick the box on the branch screen once the papers
-- are actually seen, and only then does the badge appear.
-- ════════════════════════════════════════════════════════════════════════════

ALTER TABLE PHARMA.ALL_BRANCH
    ADD COLUMN IF NOT EXISTS BRANCH_VERIFIED CHAR(1) DEFAULT 'N' NOT NULL;

ALTER TABLE PHARMA.ALL_BRANCH
    ADD COLUMN IF NOT EXISTS DRUG_LICENSE_NO VARCHAR(60);

ALTER TABLE PHARMA.ALL_BRANCH
    ADD COLUMN IF NOT EXISTS VERIFIED_BY VARCHAR(100);

ALTER TABLE PHARMA.ALL_BRANCH
    ADD COLUMN IF NOT EXISTS VERIFIED_AT TIMESTAMP;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'CK_ALL_BRANCH_VERIFIED') THEN
        ALTER TABLE PHARMA.ALL_BRANCH
            ADD CONSTRAINT CK_ALL_BRANCH_VERIFIED CHECK (BRANCH_VERIFIED IN ('Y', 'N'));
    END IF;
END $$;

COMMENT ON COLUMN PHARMA.ALL_BRANCH.BRANCH_VERIFIED IS
    'Y once staff have seen this pharmacy''s drug licence. The storefront shows a Verified badge only for Y.';
