/* ─────────────────────────────────────────────────────────────────────────────
   Subscription terms.

   Activating a pharmacy takes three decisions: which plan, how long, and which
   permission template. Two of those were data — SUB_PLAN and ACL_PERM_TEMPLATE,
   both editable by a Super Admin. The third was a literal in the browser:

       {[1, 3, 6, 12].map((m) => <option key={m} value={m}>...</option>)}

   So the lengths a pharmacy could be sold were fixed at build time. Offering a
   24-month deal meant editing JSX, rebuilding the frontend and redeploying, and
   the salesperson who needed it had no way to say so. This table is where the
   list lives now.

   ── Why a term is not a billing cycle ──

   SUB_PLAN.BILLING_TERM already says MONTHLY or YEARLY. That is what the price
   covers. This says how much is being bought in one go. A plan priced monthly
   can still be sold twelve months at a time, and keeping the two apart is what
   lets that happen without either number lying about the other.

   ── Why nothing references this table ──

   Activation reads a term once, works out an expiry date from it, and stores the
   date on SUB_SUBSCRIPTION. It never looks here again. That is deliberate: a
   subscription's expiry is a fact about a deal that was struck, and it must not
   move because somebody later edited the list of lengths on offer.

   The consequence is that a term can be deleted outright, where a plan can only
   be taken off sale. Deleting a plan would leave a live subscription pointing at
   no price and no grace; deleting a term orphans nothing, because no row points
   at one.
   ───────────────────────────────────────────────────────────────────────────── */

CREATE TABLE IF NOT EXISTS pharma.sub_term (
    pid_term        bigint       PRIMARY KEY,
    -- The length itself. This is the only column activation actually reads.
    term_months     integer      NOT NULL UNIQUE,
    -- What the operator picks from. Two rows offering twelve months are
    -- indistinguishable in a dropdown, which is why the length is unique.
    term_name       varchar(60)  NOT NULL,
    description     varchar(300),
    -- A discount for committing to this length, in percent. Recorded so a
    -- "two months free on an annual" arrangement is written down in one place
    -- rather than applied by hand on each invoice and remembered by nobody.
    discount_pct    numeric(5,2),
    is_active       integer      NOT NULL DEFAULT 1,
    sort_order      integer      NOT NULL DEFAULT 0,

    status          varchar(1)   DEFAULT 'Y',
    inserted_by     varchar(50),
    inserted_date   timestamp,
    updated_by      varchar(50),
    updated_date    timestamp
);

CREATE SEQUENCE IF NOT EXISTS pharma.sq_sub_term START 100;

/* The four lengths the hard-coded list offered, so nothing on the activation
   screen changes on the day this ships. Keyed on TERM_MONTHS rather than on an
   id, so re-running this cannot produce a second "12 months". */
INSERT INTO pharma.sub_term
       (pid_term, term_months, term_name, description, discount_pct, is_active, sort_order,
        status, inserted_by, inserted_date)
SELECT v.pid, v.months, v.name, v.descr, v.disc, 1, v.ord, 'Y', 'V60', NOW()
  FROM (VALUES
          (1, 1,  '1 month',   'Rolling monthly.',          NULL::numeric, 1),
          (2, 3,  '3 months',  'One quarter.',              NULL::numeric, 2),
          (3, 6,  '6 months',  'Half a year.',              NULL::numeric, 3),
          (4, 12, '12 months', 'A full year, billed once.', NULL::numeric, 4)
       ) AS v(pid, months, name, descr, disc, ord)
 WHERE NOT EXISTS (SELECT 1 FROM pharma.sub_term t WHERE t.term_months = v.months);

/* Keep the sequence ahead of the seeded ids, or the first term added through the
   screen collides with one of the four above. */
SELECT setval('pharma.sq_sub_term',
              GREATEST(100, (SELECT COALESCE(MAX(pid_term), 0) + 1 FROM pharma.sub_term)),
              false);
