-- ═══════════════════════════════════════════════════════════════════════════
-- V13 — A delivery man who can log in
--
-- Two populations existed with nothing joining them:
--
--   ACL_USER        who can log in, and under which role
--   HR_EMPLOYEES    who exists as staff — and who an invoice is dispatched to,
--                   via POS_TRANSACTION_MST.DELIVERY_MAN
--
-- So a delivery man signing in had no way to be recognised as the person those
-- invoices were assigned to. "Show me my deliveries" was unanswerable: the
-- session knew a user id, the invoice held an employee id, and nothing mapped
-- one to the other.
--
-- PID_EMPLOYEE closes that. It stays nullable — most users are not staff on the
-- delivery roster, and an office login has no employee record to point at.
-- ═══════════════════════════════════════════════════════════════════════════

ALTER TABLE PHARMA.ACL_USER
    ADD COLUMN IF NOT EXISTS PID_EMPLOYEE BIGINT;

-- Referential integrity, so a login cannot point at an employee who was later
-- removed. ON DELETE SET NULL rather than CASCADE: deleting an employee record
-- must not silently delete somebody's ability to log in.
ALTER TABLE PHARMA.ACL_USER
    DROP CONSTRAINT IF EXISTS FK_ACL_USER_EMPLOYEE;

ALTER TABLE PHARMA.ACL_USER
    ADD CONSTRAINT FK_ACL_USER_EMPLOYEE
        FOREIGN KEY (PID_EMPLOYEE) REFERENCES PHARMA.HR_EMPLOYEES (PID_EMPLOYEE)
        ON DELETE SET NULL;

-- One login per employee.
--
-- Partial, so the many users with no employee link do not collide with each
-- other on NULL. Without this, two accounts could both claim the same driver
-- and each would see the other's deliveries as their own.
CREATE UNIQUE INDEX IF NOT EXISTS UX_ACL_USER_EMPLOYEE
    ON PHARMA.ACL_USER (PID_EMPLOYEE)
 WHERE PID_EMPLOYEE IS NOT NULL;

COMMENT ON COLUMN PHARMA.ACL_USER.PID_EMPLOYEE IS
    'The staff record this login belongs to. Required for a delivery man: it is '
    'how a signed-in driver is matched to the invoices assigned to them.';

-- ── Link the existing delivery man, where the evidence is unambiguous ───────
--
-- Matched on full name against employees already typed DELIVERY_MAN, and only
-- where exactly one user and one employee share that name. A name is weak
-- evidence, so anything ambiguous is left alone for a human to set rather than
-- guessed at — a wrong link would show one person another's rounds and let them
-- collect against them.
WITH candidate AS (
    SELECT u.PID_USER,
           e.PID_EMPLOYEE
      FROM PHARMA.ACL_USER u
      JOIN PHARMA.HR_EMPLOYEES e
        ON  LOWER(TRIM(e.EMP_NAME)) = LOWER(TRIM(u.FULL_NAME))
       AND e.PID_ORG   = u.PID_ORG
       AND e.EMP_TYPE  = 'DELIVERY_MAN'
       AND e.STATUS    = 'Y'
     WHERE u.PID_EMPLOYEE IS NULL
       AND u.FULL_NAME IS NOT NULL
       AND TRIM(u.FULL_NAME) <> ''
), unambiguous AS (
    SELECT PID_USER, MIN(PID_EMPLOYEE) AS PID_EMPLOYEE
      FROM candidate
     GROUP BY PID_USER
    HAVING COUNT(*) = 1
), one_user_each AS (
    SELECT PID_USER, PID_EMPLOYEE
      FROM unambiguous
     WHERE PID_EMPLOYEE IN (
           SELECT PID_EMPLOYEE FROM unambiguous GROUP BY PID_EMPLOYEE HAVING COUNT(*) = 1)
)
UPDATE PHARMA.ACL_USER u
   SET PID_EMPLOYEE = m.PID_EMPLOYEE
  FROM one_user_each m
 WHERE u.PID_USER = m.PID_USER;
