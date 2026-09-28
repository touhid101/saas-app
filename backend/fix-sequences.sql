-- =============================================================================
-- FIX: Advance PostgreSQL sequences past seeded data IDs
-- Run this ONCE on minipos_db to permanently resolve PK conflict 409 errors.
--
-- Run via psql:
--   psql -U pharma -d minipos_db -f fix-sequences.sql
--
-- Or paste directly into pgAdmin / IntelliJ Database console.
-- =============================================================================

-- ── RBAC Tables ──────────────────────────────────────────────────────────────

-- ACL_GROUP (pharma schema): seeded rows 1–9
SELECT setval('seq_acl_group',
    (SELECT COALESCE(MAX(pid_group), 9) + 1 FROM pharma.acl_group), false);

-- ACL_MODULE_INFO: seeded rows 1–16
SELECT setval('sq_acl_module',
    (SELECT COALESCE(MAX(pid_module), 16) + 1 FROM acl_module_info), false);

-- POS_PERMISSIONS: seeded ~91 rows
SELECT setval('sq_pos_perm',
    (SELECT COALESCE(MAX(pid_permission), 100) + 1 FROM pos_permissions), false);

-- ── Organization / Branch Tables ─────────────────────────────────────────────

-- ALL_ORGANIZATION: seeded row 1
SELECT setval('seq_all_organization',
    (SELECT COALESCE(MAX(pid_org), 1) + 1 FROM all_organization), false);

-- ALL_COMPANY: seeded row 1
SELECT setval('seq_all_company',
    (SELECT COALESCE(MAX(pid_company), 1) + 1 FROM all_company), false);

-- ALL_BRANCH: seeded rows 1–2
SELECT setval('seq_all_branch',
    (SELECT COALESCE(MAX(pid_branch), 2) + 1 FROM all_branch), false);

-- ALL_REGION: seeded rows 1–2
SELECT setval('seq_all_region',
    (SELECT COALESCE(MAX(pid_region), 2) + 1 FROM all_region), false);

-- ── Verify Results ────────────────────────────────────────────────────────────
SELECT 'seq_acl_group'    AS sequence_name, last_value AS next_id FROM seq_acl_group
UNION ALL
SELECT 'sq_acl_module'    AS sequence_name, last_value AS next_id FROM sq_acl_module
UNION ALL
SELECT 'sq_pos_perm'      AS sequence_name, last_value AS next_id FROM sq_pos_perm
UNION ALL
SELECT 'seq_all_organization', last_value FROM seq_all_organization
UNION ALL
SELECT 'seq_all_company',      last_value FROM seq_all_company
UNION ALL
SELECT 'seq_all_branch',       last_value FROM seq_all_branch
UNION ALL
SELECT 'seq_all_region',       last_value FROM seq_all_region;

-- Expected output:
--  sequence_name         | next_id
--  ----------------------+--------
--  seq_acl_group         |  10
--  sq_acl_module         |  17
--  sq_pos_perm           |  92+
--  seq_all_organization  |   2
--  seq_all_company       |   2
--  seq_all_branch        |   3
--  seq_all_region        |   3
