-- Users and branches that were never given a company.
--
-- Two gaps in one chain, neither of which broke anything loudly:
--
--   · createUser set the organisation and the branch and never touched
--     PID_COMPANY, so every account created through the API had a null one —
--     including in the original pharmacy, where a company did exist all along.
--
--   · Tenant activation created an organisation and a branch and stopped, so
--     every pharmacy onboarded since Phase 3 had ALL_BRANCH.PID_COMPANY null
--     too, and its users had nothing to inherit even once the code was fixed.
--
-- Both are fixed going forward. This is for the rows already written.
--
-- A branch takes the company of its organisation where that organisation has
-- exactly one — which is true of every organisation here, because activation
-- now creates one company per pharmacy. Where it is ambiguous the column is left
-- null rather than guessed: a wrong company is worse than an absent one, because
-- an absent one is visible.
\set ON_ERROR_STOP on

BEGIN;

/* ── an organisation with no company at all gets one ──
   Pharmacies onboarded between Phase 3 and this fix have an organisation and a
   branch and nothing else, so there is no company to copy from. Named after the
   pharmacy, which is what activation does now. */
INSERT INTO pharma.all_company (pid_company, pid_org, company_name, company_short_name,
                                email, phone, company_address, status, inserted_by)
SELECT COALESCE((SELECT MAX(pid_company) FROM pharma.all_company), 0)
         + ROW_NUMBER() OVER (ORDER BY b.pid_branch),
       b.pid_org, b.branch_name, LEFT(b.branch_name, 20),
       b.branch_email, b.branch_phone_no, b.branch_address, 'Y', 'V41'
  FROM pharma.all_branch b
 WHERE b.pid_company IS NULL
   AND NOT EXISTS (SELECT 1 FROM pharma.all_company c WHERE c.pid_org = b.pid_org);

/* ── branches first: users inherit from them ── */
UPDATE pharma.all_branch b
   SET pid_company = c.pid_company
  FROM (SELECT pid_org, MIN(pid_company) AS pid_company, COUNT(*) AS n
          FROM pharma.all_company
         WHERE COALESCE(status, 'Y') = 'Y'
         GROUP BY pid_org) c
 WHERE c.pid_org = b.pid_org
   AND c.n = 1
   AND b.pid_company IS NULL;

/* ── then the people who work at them ── */
UPDATE pharma.acl_user u
   SET pid_company = b.pid_company
  FROM pharma.all_branch b
 WHERE b.pid_branch = u.pid_branch
   AND u.pid_company IS NULL
   AND b.pid_company IS NOT NULL;

COMMIT;

SELECT 'branches without a company' AS what, count(*) AS rows
  FROM pharma.all_branch WHERE pid_company IS NULL
UNION ALL
SELECT 'users without a company', count(*)
  FROM pharma.acl_user WHERE pid_company IS NULL
UNION ALL
SELECT 'users whose company disagrees with their branch', count(*)
  FROM pharma.acl_user u JOIN pharma.all_branch b ON b.pid_branch = u.pid_branch
 WHERE u.pid_company IS DISTINCT FROM b.pid_company;
