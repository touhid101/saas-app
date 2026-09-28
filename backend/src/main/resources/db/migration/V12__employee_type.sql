-- ═══════════════════════════════════════════════════════════════════════════
-- V12 — Employee type, so a delivery man is something the system can identify
--
-- HR_EMPLOYEES held no marker for what an employee does. The consequence was
-- not cosmetic: HrEmployeeRepository.searchDeliveryMen filtered on org and
-- status only, so the "delivery man" list was every active employee in the
-- company — the accountant, the pharmacist, anyone. Invoices could be, and in
-- a live system would be, dispatched to people who do not deliver.
--
-- EMP_TYPE names the role the application actually branches on. Only
-- DELIVERY_MAN carries behaviour today; the rest exist so the column describes
-- the whole workforce rather than splitting it into "driver" and "not driver",
-- which would need widening the first time anything else needed a type.
-- ═══════════════════════════════════════════════════════════════════════════

ALTER TABLE PHARMA.HR_EMPLOYEES
    ADD COLUMN IF NOT EXISTS EMP_TYPE VARCHAR(20);

-- Backfill before constraining.
--
-- VEHICLE_NO was added to this table for delivery men and is meaningless for
-- anyone else — the entity comment says as much — so a row carrying one is a
-- driver. This is an inference, not a record: it is the only evidence the table
-- holds, and it is reversible from the UI. Anyone it gets wrong shows up as a
-- name in the delivery-man list that should not be there.
UPDATE PHARMA.HR_EMPLOYEES
   SET EMP_TYPE = 'DELIVERY_MAN'
 WHERE EMP_TYPE IS NULL
   AND VEHICLE_NO IS NOT NULL
   AND TRIM(VEHICLE_NO) <> '';

-- Everyone else is unclassified rather than guessed at. OTHER is inert: it
-- grants nothing and appears in no picker, so an unreviewed row cannot be
-- assigned a delivery by accident.
UPDATE PHARMA.HR_EMPLOYEES
   SET EMP_TYPE = 'OTHER'
 WHERE EMP_TYPE IS NULL;

ALTER TABLE PHARMA.HR_EMPLOYEES
    ALTER COLUMN EMP_TYPE SET DEFAULT 'OTHER';

ALTER TABLE PHARMA.HR_EMPLOYEES
    ALTER COLUMN EMP_TYPE SET NOT NULL;

-- Constrained rather than left as free text. This column decides who may be
-- handed goods to deliver, and a typo in it should fail loudly at write time,
-- not silently drop someone out of every delivery list afterwards.
ALTER TABLE PHARMA.HR_EMPLOYEES
    DROP CONSTRAINT IF EXISTS CK_HR_EMPLOYEES_EMP_TYPE;

ALTER TABLE PHARMA.HR_EMPLOYEES
    ADD CONSTRAINT CK_HR_EMPLOYEES_EMP_TYPE
        CHECK (EMP_TYPE IN ('DELIVERY_MAN', 'SALES_REP', 'CASHIER',
                            'PHARMACIST', 'MANAGER', 'ACCOUNTANT', 'OTHER'));

-- The delivery-man picker's query, and the check run on every assignment.
-- Partial: delivery men are a small slice of the table and the other types are
-- never looked up this way.
CREATE INDEX IF NOT EXISTS IX_HR_EMPLOYEES_DELIVERY
    ON PHARMA.HR_EMPLOYEES (PID_ORG, PID_BRANCH, EMP_NAME)
 WHERE EMP_TYPE = 'DELIVERY_MAN' AND STATUS = 'Y';

COMMENT ON COLUMN PHARMA.HR_EMPLOYEES.EMP_TYPE IS
    'What this employee does. DELIVERY_MAN is the only value the application '
    'branches on: it gates who may be assigned an invoice for delivery.';
