-- ═══════════════════════════════════════════════════════════════════════════
-- V18 — Put SQ_ACL_MODULE back ahead of the table
--
-- V16 and V17 inserted modules with COALESCE(MAX(PID_MODULE), 0) + 1 and never
-- advanced the sequence the entity actually allocates from. The table reached
-- PID_MODULE 21 while the sequence stayed at 20, so the next module created
-- through the UI asked for 21, collided with the row already there, and came
-- back as a bare 409 "a record with the same unique identifier already exists".
--
-- Nothing was wrong with the data — the counter behind it had simply been
-- stepped over. Hand-picking ids in a migration is the mistake; setval here
-- repairs it, and the guard below keeps it repaired if it happens again.
-- ═══════════════════════════════════════════════════════════════════════════

SELECT setval(
    'PHARMA.SQ_ACL_MODULE',
    -- GREATEST, so re-running this can only ever move the sequence forward.
    -- Rewinding it would hand out ids that already exist.
    GREATEST(
        (SELECT COALESCE(MAX(PID_MODULE), 0) FROM PHARMA.ACL_MODULE_INFO),
        (SELECT last_value FROM PHARMA.SQ_ACL_MODULE)
    ),
    true   -- already used, so the next nextval() returns max + 1
);
