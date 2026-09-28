/* ═══════════════════════════════════════════════════════════════════════════
   V97 — the ledger view without the journal join on its hot path.

   acc_v_ledger joined every line to its journal to read the status, and every
   aggregate over the ledger paid for that join — a hash of every journal the
   organisation has, per query, before a single figure was summed.

   The status only excludes lines of journals that are not POSTED or REVERSED
   (a draft, in practice). That is now an anti-join against a partial index
   holding just those journals — a handful of rows, so the check costs nothing
   — and the journal join is a LEFT JOIN on the journal's key. Postgres drops a
   left join on a unique key when the query reads none of its columns, which
   is every balance, trend and party query; a listing that shows the journal
   number still gets the join, and pays for it only over the page it shows.

   The line's own journal_date replaces the journal's: the two are written
   together and a draft's lines are rewritten with the journal on edit, and it
   is the column the line indexes carry.
   ═══════════════════════════════════════════════════════════════════════════ */
CREATE INDEX IF NOT EXISTS ix_acc_journal_unposted
    ON pharma.acc_journal (pid_journal)
    WHERE journal_status NOT IN ('POSTED','REVERSED');

CREATE OR REPLACE VIEW pharma.acc_v_ledger AS
SELECT l.pid_journal_line, l.pid_journal, j.journal_no, l.journal_date, j.journal_type, j.journal_status,
       j.source_module, j.source_type, j.source_id, j.reference, j.description AS journal_description,
       l.line_no, l.pid_account, a.account_code, a.account_name, a.account_type, a.normal_side, a.role_key,
       l.debit, l.credit, l.description, l.pid_branch, l.pid_cost_center,
       l.party_type, l.party_id, l.party_name, l.pid_org, j.pid_period, j.posted_at, j.posted_by
  FROM pharma.acc_journal_line l
  LEFT JOIN pharma.acc_journal j ON j.pid_journal = l.pid_journal
  JOIN pharma.acc_account a ON a.pid_account = l.pid_account
 WHERE NOT EXISTS (SELECT 1 FROM pharma.acc_journal x
                    WHERE x.pid_journal = l.pid_journal AND x.journal_status NOT IN ('POSTED','REVERSED'));
