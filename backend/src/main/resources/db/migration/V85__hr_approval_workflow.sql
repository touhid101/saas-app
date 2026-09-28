/* ─────────────────────────────────────────────────────────────────────────────
   HR — a multi-level approval workflow, added in front of what already works.

   ══ What exists today, and what is missing ══

   Every HR object already has a decision: leave, advance, loan, overtime,
   increment, gratuity and expense each carry SUBMITTED -> APPROVED/REJECTED
   with DECIDED_BY and DECIDED_AT, gated by the Approve right on their module.

   What is missing is levels. "Leave up to three days is the manager's, longer
   than that needs the branch administrator too" cannot be expressed anywhere.

   ══ Added in front, never in place of ══

   These tables sit BESIDE the existing workflows. With no flow defined for a
   workflow — which is how every branch starts and how they all stay until
   somebody configures one — nothing changes at all: one Approve right, one
   decision, exactly as yesterday. That is the same compatibility contract the
   working policy, the PF scheme and the gratuity formula all follow.

   When a flow IS defined, the object stays SUBMITTED until the last level has
   signed. The existing approve() in each service keeps every line it had; it
   simply asks first whether this decision is the final one, and returns early
   when it is not. Nothing was rewritten to make room for this.

   ══ Who may sign a level ══

   A level names a role, or the sentinel MANAGER meaning "the employee's own
   manager", read from HR_EMPLOYEES.PID_MANAGER — a column V78 added and nothing
   has used until now. Naming a role rather than a person means a flow survives
   somebody leaving, which a named approver does not.

   Holding the module's Approve right is still required. A level narrows who may
   decide; it never widens it, so a flow cannot hand somebody an authority the
   RBAC screen did not give them.

   ══ Payroll is deliberately not routed through this ══

   A payroll run already has five signatures of its own — calculate, review,
   approve, post, pay — each with its own right and its own meaning. Wrapping a
   second chain around that would give it two answers to "who signs next", and
   the run's own chain is the one the business understands.
   ───────────────────────────────────────────────────────────────────────────── */

CREATE SEQUENCE IF NOT EXISTS pharma.seq_hr_approval_flow       START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_hr_approval_flow_level START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_hr_approval_request    START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_hr_approval_action     START WITH 1 INCREMENT BY 1;


/* ═══ The flow ══════════════════════════════════════════════════════════════

   One per branch per workflow per threshold. MIN_VALUE is what routes a request
   to this flow rather than a shorter one: the value is whatever the module
   measures itself in — days for leave, money for an advance or a claim — and
   the calling service decides which, because only it knows.

   NULL MIN_VALUE means "anything", and is the flow used when no threshold
   matches. A branch with one flow at NULL has a single chain for everything. */

CREATE TABLE IF NOT EXISTS pharma.hr_approval_flow (
    pid_flow          BIGINT       NOT NULL,
    pid_org           BIGINT       NOT NULL,
    pid_company       BIGINT,
    pid_branch        BIGINT       NOT NULL,

    /* LEAVE, ADVANCE, LOAN, OVERTIME, INCREMENT, GRATUITY, EXPENSE.

       Its own vocabulary rather than the module code, because two workflows can
       share a module: increments and gratuity awards both sit under Employees,
       and a pharmacy that wants two signatures on a pay rise does not thereby
       want two on a leaving payment.

       Authorisation is still the module's Approve right — this column only says
       which chain applies. */
    workflow_code     VARCHAR(20)  NOT NULL,
    flow_name         VARCHAR(120) NOT NULL,

    /* The floor this flow applies from. NULL means it applies to anything. */
    min_value         NUMERIC(18,2),

    description       VARCHAR(500),

    status            VARCHAR(1)   DEFAULT 'Y',
    inserted_by       VARCHAR(50),
    inserted_date     TIMESTAMP,
    updated_by        VARCHAR(50),
    updated_date      TIMESTAMP,

    CONSTRAINT hr_approval_flow_pkey PRIMARY KEY (pid_flow),
    CONSTRAINT ck_hr_flow_min CHECK (min_value IS NULL OR min_value >= 0)
);

/* One flow per branch, workflow and threshold. Two flows at the same floor would
   make "which chain applies" a question with two answers. */
CREATE UNIQUE INDEX IF NOT EXISTS uq_hr_flow_threshold
    ON pharma.hr_approval_flow (pid_branch, workflow_code, COALESCE(min_value, -1))
 WHERE status = 'Y';

CREATE INDEX IF NOT EXISTS ix_hr_flow_branch
    ON pharma.hr_approval_flow (pid_branch, workflow_code, min_value);


/* ═══ Its levels ════════════════════════════════════════════════════════════ */

CREATE TABLE IF NOT EXISTS pharma.hr_approval_flow_level (
    pid_flow_level    BIGINT       NOT NULL,
    pid_flow          BIGINT       NOT NULL,

    level_no          INT          NOT NULL,

    /* A role code from ACL_GROUP, or the sentinel MANAGER meaning the
       employee's own manager. A role outlives a person; a named approver does
       not, and a flow that breaks when somebody resigns is a flow nobody
       trusts. */
    approver_role     VARCHAR(20)  NOT NULL,

    /* What the screen calls this step: "Manager", "Branch administrator". */
    level_label       VARCHAR(120),

    status            VARCHAR(1)   DEFAULT 'Y',
    inserted_by       VARCHAR(50),
    inserted_date     TIMESTAMP,
    updated_by        VARCHAR(50),
    updated_date      TIMESTAMP,

    CONSTRAINT hr_approval_flow_level_pkey PRIMARY KEY (pid_flow_level),
    CONSTRAINT fk_hr_flow_level_flow FOREIGN KEY (pid_flow)
        REFERENCES pharma.hr_approval_flow (pid_flow),
    CONSTRAINT ck_hr_flow_level_no CHECK (level_no >= 1 AND level_no <= 10)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_hr_flow_level_no
    ON pharma.hr_approval_flow_level (pid_flow, level_no)
 WHERE status = 'Y';


/* ═══ One request travelling through a flow ═════════════════════════════════

   WORKFLOW_CODE and REF_ID point at the object being decided — a leave
   request, an advance, a claim. Not a foreign key, because it is a different table each
   time; the service that created the chain is the one that reads it back, and
   it knows which table it meant.

   TOTAL_LEVELS and the level rows are copied at the moment the chain starts, so
   editing a flow does not change a request already travelling through it. The
   same rule an approved increment and a posted payslip both follow. */

CREATE TABLE IF NOT EXISTS pharma.hr_approval_request (
    pid_approval      BIGINT       NOT NULL,
    pid_org           BIGINT       NOT NULL,
    pid_company       BIGINT,
    pid_branch        BIGINT       NOT NULL,

    workflow_code     VARCHAR(20)  NOT NULL,
    ref_id            BIGINT       NOT NULL,
    /* Whose request it is — read to find their manager, and to show a person
       their own chain. */
    pid_employee      BIGINT       NOT NULL,

    pid_flow          BIGINT,
    flow_name         VARCHAR(120),
    /* What it was routed on, frozen. A threshold changed next year should not
       make last month's chain look wrong. */
    routed_value      NUMERIC(18,2),

    current_level     INT          NOT NULL DEFAULT 1,
    total_levels      INT          NOT NULL,

    request_status    VARCHAR(20)  NOT NULL DEFAULT 'PENDING',

    started_by        VARCHAR(50), started_at  TIMESTAMP,
    closed_by         VARCHAR(50), closed_at   TIMESTAMP,

    status            VARCHAR(1)   DEFAULT 'Y',
    inserted_by       VARCHAR(50),
    inserted_date     TIMESTAMP,
    updated_by        VARCHAR(50),
    updated_date      TIMESTAMP,

    CONSTRAINT hr_approval_request_pkey PRIMARY KEY (pid_approval),
    CONSTRAINT fk_hr_approval_employee FOREIGN KEY (pid_employee)
        REFERENCES pharma.hr_employees (pid_employee),
    CONSTRAINT fk_hr_approval_flow FOREIGN KEY (pid_flow)
        REFERENCES pharma.hr_approval_flow (pid_flow),
    CONSTRAINT ck_hr_approval_status CHECK (request_status IN
        ('PENDING','APPROVED','REJECTED','CANCELLED')),
    CONSTRAINT ck_hr_approval_levels CHECK (total_levels >= 1
        AND current_level >= 1 AND current_level <= total_levels + 1)
);

/* One live chain per object. A rejected or cancelled one frees the object to be
   filed again, which is what every other workflow here already allows. */
CREATE UNIQUE INDEX IF NOT EXISTS uq_hr_approval_live
    ON pharma.hr_approval_request (workflow_code, ref_id)
 WHERE request_status = 'PENDING' AND status = 'Y';

CREATE INDEX IF NOT EXISTS ix_hr_approval_pending
    ON pharma.hr_approval_request (pid_branch, request_status, current_level)
 WHERE status = 'Y';

CREATE INDEX IF NOT EXISTS ix_hr_approval_ref
    ON pharma.hr_approval_request (workflow_code, ref_id);


/* ═══ Who signed what, and when ═════════════════════════════════════════════

   Every level's decision is kept, including the ones that came before a
   rejection. "Approved by the manager and refused by the administrator" is a
   different story from "refused", and only a trail can tell them apart. */

CREATE TABLE IF NOT EXISTS pharma.hr_approval_action (
    pid_action        BIGINT       NOT NULL,
    pid_approval      BIGINT       NOT NULL,

    level_no          INT          NOT NULL,
    level_label       VARCHAR(120),
    approver_role     VARCHAR(20),

    decision          VARCHAR(20)  NOT NULL,
    decided_by        VARCHAR(50),
    decided_at        TIMESTAMP,
    remarks           VARCHAR(1000),

    status            VARCHAR(1)   DEFAULT 'Y',
    inserted_by       VARCHAR(50),
    inserted_date     TIMESTAMP,
    updated_by        VARCHAR(50),
    updated_date      TIMESTAMP,

    CONSTRAINT hr_approval_action_pkey PRIMARY KEY (pid_action),
    CONSTRAINT fk_hr_approval_action FOREIGN KEY (pid_approval)
        REFERENCES pharma.hr_approval_request (pid_approval),
    CONSTRAINT ck_hr_action_decision CHECK (decision IN ('APPROVED','REJECTED'))
);

CREATE INDEX IF NOT EXISTS ix_hr_approval_action_request
    ON pharma.hr_approval_action (pid_approval, level_no);


/* ═══ Nothing is seeded ═════════════════════════════════════════════════════

   No flow is created for any branch. A pharmacy that wants two signatures on
   long leave says so on the Approvals screen; until then every module decides
   the way it decided yesterday, with one signature.

   Seeding a plausible chain would be this migration deciding who signs what,
   which is the pharmacy's decision and nobody else's. */


/* ═══ Verification ══════════════════════════════════════════════════════════ */

SELECT 'hr_approval_flow'       AS table_name, count(*)::text AS rows FROM pharma.hr_approval_flow
UNION ALL SELECT 'hr_approval_flow_level', count(*)::text FROM pharma.hr_approval_flow_level
UNION ALL SELECT 'hr_approval_request',    count(*)::text FROM pharma.hr_approval_request
UNION ALL SELECT 'hr_approval_action',     count(*)::text FROM pharma.hr_approval_action
UNION ALL SELECT 'employees with a manager on record',
       count(*)::text FROM pharma.hr_employees WHERE pid_manager IS NOT NULL;
