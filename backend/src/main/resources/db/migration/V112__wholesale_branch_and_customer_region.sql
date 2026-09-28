/* ─────────────────────────────────────────────────────────────────────────────
   V112 — Which pharmacies sell wholesale, and which region a customer may buy
   wholesale in.

   Two columns, both additive, both safe on a populated table. Nothing here
   decides anything on anybody's behalf.

   WHAT ALREADY EXISTED, AND IS NOT DUPLICATED HERE
   ------------------------------------------------
   The region master is real and is reused as it stands:

     ALL_REGION            the region
     ALL_DIVISION          under it
     ALL_DISTRICT          under that
     ALL_BRANCH.PID_REGION the branch's region, already on the branch

   So there is no new region table, no branch-region link table, and no second
   branch master (change request 23). A branch says where it is the way it
   always has.

   1. ALL_BRANCH.BRANCH_TRADE_TYPE
   -------------------------------
   Nothing in the ERP said whether a branch trades retail, wholesale or both.
   Every existing branch defaults to RETAIL, because that is what they are
   doing today: a branch becomes a wholesale pharmacy when somebody says so on
   the branch form, never because a migration decided it.

   A warehouse is not a wholesale pharmacy. This column is about who a branch
   may sell to, and it is set by hand.

   2. ECOM_CUSTOMER.PID_APPROVED_REGION
   ------------------------------------
   Nullable, and deliberately left null for every existing customer.

   NO BACKFILL. 122 approved wholesalers and 4 special customers have no region
   today, and the brief is explicit: do not silently assign anybody to Dhaka or
   to any default. Until somebody approves a region, wholesale shopping is
   refused with the message that asks them to complete their business details
   (docs/ecommerce 23 3.2).

   That is a business decision with three honest answers - hard cutover, an
   auditable backfill from the address the customer themselves gave, or a grace
   period - and it is NOT taken here. When the answer arrives it is one more
   migration, which is exactly where a decision of that size belongs.
   BUSINESS CONFIRMATION REQUIRED.
   ───────────────────────────────────────────────────────────────────────────── */

ALTER TABLE pharma.all_branch
    ADD COLUMN IF NOT EXISTS branch_trade_type VARCHAR(10) DEFAULT 'RETAIL';

UPDATE pharma.all_branch SET branch_trade_type = 'RETAIL' WHERE branch_trade_type IS NULL;

ALTER TABLE pharma.all_branch
    ALTER COLUMN branch_trade_type SET NOT NULL;

/* A value the application does not understand would fall through every
   eligibility test and quietly sell to nobody, or to everybody. Three words. */
ALTER TABLE pharma.all_branch
    DROP CONSTRAINT IF EXISTS ck_all_branch_trade_type;
ALTER TABLE pharma.all_branch
    ADD CONSTRAINT ck_all_branch_trade_type
    CHECK (branch_trade_type IN ('RETAIL', 'WHOLESALE', 'BOTH'));

COMMENT ON COLUMN pharma.all_branch.branch_trade_type IS
  'RETAIL = nearby retail shopping only · WHOLESALE = wholesale customers only · BOTH = either. Set by hand on the branch form; never inferred.';

CREATE INDEX IF NOT EXISTS ix_all_branch_trade
    ON pharma.all_branch (branch_trade_type, ecom_enabled, pid_region);

ALTER TABLE pharma.ecom_customer
    ADD COLUMN IF NOT EXISTS pid_approved_region BIGINT,
    ADD COLUMN IF NOT EXISTS region_approved_by  VARCHAR(50),
    ADD COLUMN IF NOT EXISTS region_approved_at  TIMESTAMP;

ALTER TABLE pharma.ecom_customer
    DROP CONSTRAINT IF EXISTS fk_ecom_customer_region;
ALTER TABLE pharma.ecom_customer
    ADD CONSTRAINT fk_ecom_customer_region
    FOREIGN KEY (pid_approved_region) REFERENCES pharma.all_region (pid_region);

COMMENT ON COLUMN pharma.ecom_customer.pid_approved_region IS
  'The region this customer may buy wholesale in. A global attribute of the customer, NOT branch ownership: it narrows which pharmacies they may choose from, and nothing else.';
COMMENT ON COLUMN pharma.ecom_customer.region_approved_by IS
  'Who approved the region. An adjustment nobody can point at afterwards is one nobody can defend.';

CREATE INDEX IF NOT EXISTS ix_ecom_customer_region
    ON pharma.ecom_customer (pid_approved_region, customer_type);

/* What this migration leaves behind, printed so the state is not a surprise:
   every branch retail, and every customer still waiting for a region. */
SELECT (SELECT count(*) FROM pharma.all_branch WHERE branch_trade_type = 'WHOLESALE')                    AS wholesale_branches,
       (SELECT count(*) FROM pharma.all_branch WHERE branch_trade_type = 'BOTH')                         AS both_branches,
       (SELECT count(*) FROM pharma.all_region)                                                          AS regions_defined,
       (SELECT count(*) FROM pharma.ecom_customer
         WHERE customer_type IN ('WHOLESALER', 'SPECIAL') AND approval_status = 'APPROVED'
           AND pid_approved_region IS NULL)                                                              AS awaiting_region;
