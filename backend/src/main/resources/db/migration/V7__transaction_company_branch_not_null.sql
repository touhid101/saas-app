-- ============================================================================
-- V7 — COMPANY / BRANCH ON TRANSACTIONS
-- Target: PostgreSQL.
--
-- POS_TRANSACTION_MST.PID_COMPANY and PID_BRANCH were arriving null on every
-- purchase. The service reads them from the session, and the session was empty
-- because ACL_USER itself had no company and (for most users) no branch — so
-- there was nothing to carry.
--
-- This fixes the cause and then enforces it:
--   1. give every user a company and branch
--   2. backfill the transactions already written without them
--   3. make both columns NOT NULL so a null cannot come back
--
-- Idempotent; safe to re-run.
-- ============================================================================

-- ── 1. Give every user a company and branch ─────────────────────────────────
-- Each user takes their own organisation's company, and that company's branch.
-- Where an organisation has more than one, the lowest id is used as its
-- default: this is a backfill for accounts that were never assigned, not an
-- attempt to guess where someone actually works. Review afterwards with
--   SELECT user_name, pid_company, pid_branch FROM PHARMA.ACL_USER;
UPDATE PHARMA.ACL_USER u
   SET PID_COMPANY = (
        SELECT MIN(c.PID_COMPANY) FROM PHARMA.ALL_COMPANY c
         WHERE c.PID_ORG = u.PID_ORG AND COALESCE(c.STATUS,'Y') = 'Y')
 WHERE u.PID_COMPANY IS NULL
   AND u.PID_ORG IS NOT NULL;

UPDATE PHARMA.ACL_USER u
   SET PID_BRANCH = (
        SELECT MIN(b.PID_BRANCH) FROM PHARMA.ALL_BRANCH b
         WHERE b.PID_ORG = u.PID_ORG
           AND (b.PID_COMPANY = u.PID_COMPANY OR u.PID_COMPANY IS NULL)
           AND COALESCE(b.STATUS,'Y') = 'Y')
 WHERE u.PID_BRANCH IS NULL
   AND u.PID_ORG IS NOT NULL;

-- ── 2. Backfill transactions written before the fix ─────────────────────────
-- Prefer the branch's own company so the two stay consistent with each other.
UPDATE PHARMA.POS_TRANSACTION_MST t
   SET PID_COMPANY = COALESCE(
        (SELECT b.PID_COMPANY FROM PHARMA.ALL_BRANCH b WHERE b.PID_BRANCH = t.PID_BRANCH),
        (SELECT MIN(c.PID_COMPANY) FROM PHARMA.ALL_COMPANY c WHERE c.PID_ORG = t.PID_ORG))
 WHERE t.PID_COMPANY IS NULL;

UPDATE PHARMA.POS_TRANSACTION_MST t
   SET PID_BRANCH = COALESCE(
        (SELECT MIN(b.PID_BRANCH) FROM PHARMA.ALL_BRANCH b
          WHERE b.PID_ORG = t.PID_ORG AND b.PID_COMPANY = t.PID_COMPANY),
        (SELECT MIN(b.PID_BRANCH) FROM PHARMA.ALL_BRANCH b WHERE b.PID_ORG = t.PID_ORG))
 WHERE t.PID_BRANCH IS NULL;

-- A transaction with no organisation cannot be resolved to a company or branch
-- and would block the NOT NULL below. There should be none; fail loudly rather
-- than silently leaving the constraint off if there are.
DO $migration$
DECLARE
    orphan_count BIGINT;
BEGIN
    SELECT COUNT(*) INTO orphan_count
      FROM PHARMA.POS_TRANSACTION_MST
     WHERE PID_COMPANY IS NULL OR PID_BRANCH IS NULL;

    IF orphan_count > 0 THEN
        RAISE EXCEPTION
            'V7: % transaction row(s) still have a null company or branch and cannot be resolved. '
            'Check that ALL_COMPANY and ALL_BRANCH contain rows for every PID_ORG used in '
            'POS_TRANSACTION_MST, then re-run.', orphan_count;
    END IF;
END
$migration$;

-- ── 3. Enforce it ───────────────────────────────────────────────────────────
ALTER TABLE PHARMA.POS_TRANSACTION_MST ALTER COLUMN PID_COMPANY SET NOT NULL;
ALTER TABLE PHARMA.POS_TRANSACTION_MST ALTER COLUMN PID_BRANCH  SET NOT NULL;

COMMENT ON COLUMN PHARMA.POS_TRANSACTION_MST.PID_COMPANY IS
    'Company the transaction belongs to. Taken from the user session (ACL_USER.PID_COMPANY); never accepted from the client.';
COMMENT ON COLUMN PHARMA.POS_TRANSACTION_MST.PID_BRANCH IS
    'Branch the transaction belongs to. Taken from the user session, or from an explicit branch choice for multi-branch users.';

-- ── 4. Referential integrity for the two columns now that they are required ──
DO $migration$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_tran_mst_company') THEN
        ALTER TABLE PHARMA.POS_TRANSACTION_MST
            ADD CONSTRAINT fk_tran_mst_company FOREIGN KEY (PID_COMPANY)
            REFERENCES PHARMA.ALL_COMPANY (PID_COMPANY) NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_tran_mst_branch') THEN
        ALTER TABLE PHARMA.POS_TRANSACTION_MST
            ADD CONSTRAINT fk_tran_mst_branch FOREIGN KEY (PID_BRANCH)
            REFERENCES PHARMA.ALL_BRANCH (PID_BRANCH) NOT VALID;
    END IF;
END
$migration$;
