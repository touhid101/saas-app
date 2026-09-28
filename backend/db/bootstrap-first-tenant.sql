-- ============================================================================
-- Bootstrap a blank database: organization, company, branch, super admin.
--
-- The application can create every tenant after this one by itself — a pharmacy
-- signs up, a Super Admin approves, and TenantOnboardingService builds the org,
-- company, branch, subscription and branch administrator in one transaction.
-- What it cannot do is create the FIRST Super Admin, because approving requires
-- being one. That is the gap this script fills, and the only reason it exists.
--
-- Run it once, on an empty database, after the tables exist.
--
--   psql -h localhost -p 5489 -U pharma -d minipos_db \
--        -v pw=Admin@123 -1 -f db/bootstrap-first-tenant.sql
--
-- Pass values WITHOUT surrounding single quotes. The script uses :'var', which
-- adds the SQL quoting itself — quoting again on the command line stores the
-- quote marks as part of the name, and you end up with a pharmacy literally
-- called 'My Pharmacy'. Quote for the shell only when a value contains spaces:
--        -v org_name="My Pharmacy Ltd"
--
-- -1 runs it as a single transaction: it either all lands or none of it does.
-- Re-running is safe — every step checks for its own row first and reports
-- "exists, left alone" rather than duplicating or failing.
--
-- ── Passwords ──
--
-- The hash is generated here by pgcrypto, in the same BCrypt format and at the
-- same cost factor (12) that SecurityConfig writes, with the {bcrypt} prefix
-- Spring's DelegatingPasswordEncoder uses to pick its algorithm. The plaintext
-- is never stored. Do not paste a password into this file — pass it with -v so
-- it stays out of the repository, and remember it reaches the server's log if
-- log_statement is on.
-- ============================================================================

\set ON_ERROR_STOP on

-- Defaults, so a run with no -v still works. Override any of them on the
-- command line: -v pw=Secret -v org_name="My Pharmacy Ltd"
\if :{?pw}         \else \set pw         'ChangeMe@123'          \endif
\if :{?admin_user} \else \set admin_user 'admin'                 \endif
\if :{?org_name}   \else \set org_name   'Friends Pharma Ltd'    \endif
\if :{?company_name} \else \set company_name 'Friends Pharma'    \endif
\if :{?branch_name}  \else \set branch_name  'Head Office'       \endif
\if :{?admin_email}  \else \set admin_email  'admin@example.com' \endif

SET search_path TO pharma, public;

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- psql substitutes :variables everywhere EXCEPT inside a dollar-quoted body,
-- so they are handed to the block below as session settings and read back with
-- current_setting(). Interpolating them directly would land the literal text
-- ":'org_name'" in the SQL and fail to parse.
SELECT set_config('boot.pw',           :'pw',           true),
       set_config('boot.admin_user',   :'admin_user',   true),
       set_config('boot.org_name',     :'org_name',     true),
       set_config('boot.company_name', :'company_name', true),
       set_config('boot.branch_name',  :'branch_name',  true),
       set_config('boot.admin_email',  :'admin_email',  true)
-- \gset captures the result into throwaway variables instead of printing it.
-- A plain ";" here would echo the password to the terminal and into any log
-- the operator happens to be capturing.
\gset boot_

DO $bootstrap$
DECLARE
    v_org      bigint;
    v_company  bigint;
    v_branch   bigint;
    v_user     bigint;
    v_plan     bigint;
    v_role     bigint;
    v_created  text := '';
BEGIN
    /* ── Sequences ──────────────────────────────────────────────────────────
       These three tables carry no column default: the id comes from a sequence
       the application asks for by name. A sequence sitting behind the highest
       id already in the table hands out one that is taken, and the insert dies
       on the primary key. Cheap to prevent, tedious to diagnose. */
    PERFORM setval('seq_all_organization',
                   GREATEST((SELECT COALESCE(MAX(pid_org), 0) FROM all_organization), 1));
    PERFORM setval('seq_all_company',
                   GREATEST((SELECT COALESCE(MAX(pid_company), 0) FROM all_company), 1));
    PERFORM setval('seq_all_branch',
                   GREATEST((SELECT COALESCE(MAX(pid_branch), 0) FROM all_branch), 1));
    PERFORM setval('sq_sub_subscription',
                   GREATEST((SELECT COALESCE(MAX(pid_subscription), 0) FROM sub_subscription), 1));

    /* ── 1. Organization ── */
    SELECT pid_org INTO v_org FROM all_organization WHERE org_name = current_setting('boot.org_name');
    IF v_org IS NULL THEN
        v_org := nextval('seq_all_organization');
        INSERT INTO all_organization (pid_org, org_name, org_shortname, email, status, inserted_date)
        VALUES (v_org, current_setting('boot.org_name'), left(current_setting('boot.org_name'), 20), current_setting('boot.admin_email'), 'Y', NOW());
        v_created := v_created || format('  organization  %s  "%s"%s', v_org, current_setting('boot.org_name'), chr(10));
    ELSE
        RAISE NOTICE 'organization "%" already exists (id %), left alone', current_setting('boot.org_name'), v_org;
    END IF;

    /* ── 2. Company ── */
    SELECT pid_company INTO v_company FROM all_company
     WHERE pid_org = v_org AND company_name = current_setting('boot.company_name');
    IF v_company IS NULL THEN
        v_company := nextval('seq_all_company');
        INSERT INTO all_company (pid_company, pid_org, company_name, company_short_name, email, status, inserted_date)
        VALUES (v_company, v_org, current_setting('boot.company_name'), left(current_setting('boot.company_name'), 20), current_setting('boot.admin_email'), 'Y', NOW());
        v_created := v_created || format('  company       %s  "%s"%s', v_company, current_setting('boot.company_name'), chr(10));
    ELSE
        RAISE NOTICE 'company "%" already exists (id %), left alone', current_setting('boot.company_name'), v_company;
    END IF;

    /* ── 3. Branch ──────────────────────────────────────────────────────────
       tenant_status must be spelled out. The column defaults to PENDING, and
       TenantStatusService refuses every request from a PENDING branch with
       "registration has not been approved yet" — a branch that exists, looks
       right in the table, and cannot be used. */
    SELECT pid_branch INTO v_branch FROM all_branch
     WHERE pid_company = v_company AND branch_name = current_setting('boot.branch_name');
    IF v_branch IS NULL THEN
        v_branch := nextval('seq_all_branch');
        INSERT INTO all_branch (pid_branch, pid_org, pid_company, branch_name,
                                branch_email, tenant_status, status, inserted_date)
        VALUES (v_branch, v_org, v_company, current_setting('boot.branch_name'),
                current_setting('boot.admin_email'), 'ACTIVE', 'Y', NOW());
        v_created := v_created || format('  branch        %s  "%s"  ACTIVE%s', v_branch, current_setting('boot.branch_name'), chr(10));
    ELSE
        RAISE NOTICE 'branch "%" already exists (id %), left alone', current_setting('boot.branch_name'), v_branch;
    END IF;

    /* ── 4. Subscription ────────────────────────────────────────────────────
       An ACTIVE branch with no live subscription is still locked out: the
       status check passes and the very next line asks for a subscription row,
       finds none, and returns SUBSCRIPTION_EXPIRED on every call. The branch
       needs both to work. */
    IF NOT EXISTS (SELECT 1 FROM sub_subscription
                    WHERE pid_branch = v_branch AND sub_status IN ('ACTIVE','SUSPENDED')) THEN
        SELECT pid_plan INTO v_plan FROM sub_plan ORDER BY pid_plan LIMIT 1;
        IF v_plan IS NULL THEN
            RAISE EXCEPTION 'No rows in sub_plan. Load the seed data (V32__subscription.sql) '
                            'before bootstrapping, or the branch cannot be subscribed.';
        END IF;
        INSERT INTO sub_subscription (pid_subscription, pid_branch, pid_org, pid_plan, starts_on, expires_on,
                                      grace_days, sub_status, status, inserted_date)
        SELECT nextval('sq_sub_subscription'), v_branch, v_org, v_plan, CURRENT_DATE, CURRENT_DATE + INTERVAL '12 months',
               COALESCE(p.grace_days, 10), 'ACTIVE', 'Y', NOW()
          FROM sub_plan p WHERE p.pid_plan = v_plan;
        v_created := v_created || format('  subscription  plan %s, 12 months%s', v_plan, chr(10));
    ELSE
        RAISE NOTICE 'branch % already has a live subscription, left alone', v_branch;
    END IF;

    /* ── 5. Super Admin ─────────────────────────────────────────────────────
       is_admin = 'Y' is what makes them one: CustomUserDetailsService reads that
       single column and hands out every authority. The role row is secondary,
       but pointing at ROLE01 keeps the console honest about who they are. */
    SELECT pid_user INTO v_user FROM acl_user WHERE lower(user_name) = lower(current_setting('boot.admin_user'));
    IF v_user IS NULL THEN
        SELECT pid_group INTO v_role FROM acl_group WHERE role_code = 'ROLE01' LIMIT 1;

        INSERT INTO acl_user (user_name, password_hash, full_name, email,
                              pid_org, pid_company, pid_branch, pid_role,
                              is_admin, status, inserted_date)
        VALUES (current_setting('boot.admin_user'),
                '{bcrypt}' || crypt(current_setting('boot.pw'), gen_salt('bf', 12)),
                'Super Admin', current_setting('boot.admin_email'),
                v_org, v_company, v_branch, v_role,
                'Y', 'Y', NOW())
        RETURNING pid_user INTO v_user;
        v_created := v_created || format('  super admin   %s  "%s"  (bcrypt)%s', v_user, current_setting('boot.admin_user'), chr(10));
    ELSE
        RAISE NOTICE 'user "%" already exists (id %), password NOT changed', current_setting('boot.admin_user'), v_user;
    END IF;

    IF v_created = '' THEN
        RAISE NOTICE 'Nothing to do — everything this script creates was already present.';
    ELSE
        RAISE NOTICE E'\n Created:\n%', v_created;
    END IF;
END
$bootstrap$;

-- ── What now exists ──
SELECT u.pid_user,
       u.user_name,
       u.is_admin                                   AS super_admin,
       CASE WHEN u.password_hash LIKE '{bcrypt}%' THEN 'bcrypt'
            WHEN u.password_hash IS NULL           THEN 'none'
            ELSE 'PLAINTEXT — fix this'
       END                                          AS password_form,
       o.org_name,
       c.company_name,
       b.branch_name,
       b.tenant_status,
       s.expires_on                                 AS subscribed_until
  FROM acl_user u
  LEFT JOIN all_organization o ON o.pid_org     = u.pid_org
  LEFT JOIN all_company     c ON c.pid_company  = u.pid_company
  LEFT JOIN all_branch      b ON b.pid_branch   = u.pid_branch
  LEFT JOIN sub_subscription s ON s.pid_branch  = b.pid_branch
                              AND s.sub_status IN ('ACTIVE','SUSPENDED')
 WHERE u.is_admin = 'Y'
 ORDER BY u.pid_user;
