/* ═══════════════════════════════════════════════════════════════════════════
   V98 — the two ledger indexes of V96 made covering.

   The dashboard's scans — every line of the organisation as at a date, and
   every line of a party type — found their rows through the V96 indexes and
   then fetched each row from the table. The table is larger than the buffer
   cache, so every scan re-read most of it from disk: a second and more per
   query, waiting on IO, with the figures themselves a few hundred rows.

   Carrying the summed columns in the index lets Postgres answer from the
   index alone: a third of the table's size, and it stays cached. The account
   and journal keys ride along so the joins the ledger view makes (the
   account's role and side, the draft-journal check) need no table row either.
   Same key columns as V96, so nothing that used them changes plan for the
   worse; INCLUDE columns are payload, not part of the key.
   ═══════════════════════════════════════════════════════════════════════════ */

DROP INDEX IF EXISTS pharma.ix_acc_jl_org_date;
CREATE INDEX ix_acc_jl_org_date
    ON pharma.acc_journal_line (pid_org, journal_date)
    INCLUDE (pid_branch, pid_account, pid_journal, debit, credit);

DROP INDEX IF EXISTS pharma.ix_acc_jl_org_party;
CREATE INDEX ix_acc_jl_org_party
    ON pharma.acc_journal_line (pid_org, party_type, party_id, journal_date)
    INCLUDE (pid_account, pid_journal, pid_branch, debit, credit, party_name)
    WHERE party_id IS NOT NULL;
