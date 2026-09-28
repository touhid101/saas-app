/* ═══════════════════════════════════════════════════════════════════════════
   V96 — two indexes the Accounts dashboard and party views read through.

   Every dashboard figure filters journal lines by organisation first; the
   existing indexes lead with branch or account, so an organisation-wide
   window ("this month, all branches") read every line the organisation has
   ever posted. The same for a party's balance and ageing: the party index
   (party_type, party_id, date) does not carry the organisation, so a wrong
   organisation's rows were read and discarded.

   Plain CREATE INDEX (not CONCURRENTLY: Flyway runs inside a transaction);
   on a million lines it holds the table for a few seconds at deploy time.
   ═══════════════════════════════════════════════════════════════════════════ */

CREATE INDEX IF NOT EXISTS ix_acc_jl_org_date
    ON pharma.acc_journal_line (pid_org, journal_date);

CREATE INDEX IF NOT EXISTS ix_acc_jl_org_party
    ON pharma.acc_journal_line (pid_org, party_type, party_id, journal_date)
    WHERE party_id IS NOT NULL;
