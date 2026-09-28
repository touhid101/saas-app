# Accounts database

Schema `pharma`, PostgreSQL. Flyway migrations **V90–V94** (the project was at
V89 before this module; nothing below V90 was touched). Every statement is
`CREATE … IF NOT EXISTS` / `ADD COLUMN IF NOT EXISTS` / `INSERT … WHERE NOT
EXISTS`; there is no DROP, no NOT NULL added to a populated table, no change
to any applied migration, and each file was dry-run in a `BEGIN … ROLLBACK`
transaction against a copy of the dev database before being applied. All
money is `NUMERIC(18,2)`; ids are `BIGINT` from a sequence per table; audit
columns (`status, inserted_by, inserted_date, updated_by, updated_date`)
follow the project's `BaseEntity`.

| Migration | Contents |
|---|---|
| **V90 accounting_core** | `sequence_tracker` rows SEQ_JOURNAL_NO/SEQ_CASH_VOUCHER_NO/SEQ_INVESTOR_TXN_NO/SEQ_PROFIT_DIST_NO; `acc_account_template` (53 rows) and per-organisation `acc_account` provisioning for every existing org; `acc_fiscal_year`, `acc_period`, `acc_cost_center`, `acc_tax_rate`, `acc_setting`, `acc_payment_mode` (11 default mappings), `acc_bank_account`, `acc_event`, `acc_rule` (32) + `acc_rule_line` (76), `acc_journal`, `acc_journal_line`, `acc_cash_voucher`, `acc_backfill_batch`; `hr_expense_type.pid_account`; the immutability triggers; views `acc_v_ledger`, `acc_v_account_balance` |
| **V91 branch_daily_cash** | `acc_denomination` (BDT 1000…1 per org), `acc_business_day`, `acc_cash_count`, `acc_business_day_audit` |
| **V92 investor_and_profit_distribution** | `acc_investor`, `acc_investor_txn`, `acc_profit_policy`, `acc_profit_policy_line`, `acc_profit_distribution`, `acc_profit_allocation` |
| **V93 accounts_modules_and_permissions** | menu group `GRP_ACCOUNTS`, modules MOD41–MOD52, role rights (ROLE01/02 all; ROLE04 all but delete), plan ceilings and templates — same pattern as V77/V87 for HR |
| **V94 stock_movement_ref_index** | `ix_stock_movement_ref_mst (ref_type, ref_mst, movement_type)` on `pos_stock_movement` — the COGS lookup per sale; made backfill 5.6× faster |
| **V95 supplier_advance_restated_rule** | rule `SUPPLIER_ADVANCE_RESTATED` (Dr AP / Cr SUPPLIER_ADVANCE by DELTA) for restating an invoice-time advance to the invoice discount |
| **V96 acc_ledger_org_indexes** | `ix_acc_jl_org_date (pid_org, journal_date)`, `ix_acc_jl_org_party (pid_org, party_type, party_id, journal_date) WHERE party_id IS NOT NULL` |
| **V97 acc_ledger_view_without_journal_join** | partial index `ix_acc_journal_unposted` on journals not POSTED/REVERSED; `acc_v_ledger` redefined with a LEFT JOIN to the journal, the line's own `journal_date`, and drafts excluded by anti-join — aggregates that read no journal column skip the journal table |
| **V98 acc_ledger_covering_indexes** | the two V96 indexes recreated with INCLUDE (branch, account, journal keys, debit, credit, party_name) so ledger scans can be index-only |
| **V99 accounts_menu_seed_for_every_environment** | the pair of V88 for Accounts: `GRP_ACCOUNTS` + MOD41–MOD52 (insert where missing, repair parent/route/icon where NULL), grants for ROLE01/ROLE02 (all rights) and ROLE04 (all but delete), the twelve lines on the BASIC/STANDARD/ENTERPRISE templates, and plan ceilings for narrowed branches from their own plan's template — a no-op where V93 already did the work, a complete seed where nothing exists. Verification query at the foot lists narrowed branches whose plan has no template. **Policy point:** all three plans carry Accounts; trim section 4 before the production run if a plan should not |

## Core tables

### acc_account (per organisation chart)
`pid_account, pid_org, account_code, account_name, account_type
(ASSET|LIABILITY|EQUITY|REVENUE|EXPENSE), pid_parent, is_group, normal_side
(D|C), role_key, allow_manual, is_system, is_active, currency, description,
sort_order`. Unique `(pid_org, account_code)`; **partial unique
`uq_acc_account_role (pid_org, role_key) WHERE role_key IS NOT NULL`** — one
account per posting role per organisation. `acc_account_template` holds the
same shape without `pid_org` and is what a new organisation is copied from.

### acc_event (the financial events)
`pid_event, pid_org, pid_branch, source_module, source_type, source_id,
event_key, event_type, event_date, reference, description, amount, payload
(json text), event_status (POSTED|SKIPPED|FAILED|REVERSED), pid_journal,
error_message, retry_count, processed_at, pid_backfill_batch`.
**Unique `(pid_org, source_module, source_type, source_id, event_key)`** — the
idempotency key. Indexes on `(pid_branch, event_date)`, `(pid_org,
event_status)`, `(source_module, source_type, source_id)`.

### acc_rule / acc_rule_line
`acc_rule (pid_rule, pid_org NULL = default, event_type, rule_name,
description, is_active)` unique on `(COALESCE(pid_org,0), event_type)`;
`acc_rule_line (pid_rule_line, pid_rule, line_no, side, account_role,
amount_key, party_type, description)`.

### acc_journal
`pid_journal, pid_org, pid_branch, journal_no, journal_date, pid_period,
journal_type (AUTO|MANUAL|ADJUSTMENT|OPENING|REVERSAL|CLOSING), source_module,
source_type, source_id, pid_event, reference, description, pid_cost_center,
currency, total_debit, total_credit, journal_status (DRAFT|POSTED|REVERSED),
reversal_of, reversed_by, reversal_reason, approved_by/at, posted_by/at,
reversed_by_user, reversed_at`. Unique `(pid_org, journal_no)`; **CHECK
`total_debit = total_credit` whenever status ≠ DRAFT**; indexes on
`(pid_branch, journal_date)`, `(pid_org, journal_date)`, source triple,
`(pid_period, journal_status)`.

### acc_journal_line
`pid_journal_line, pid_journal, line_no, pid_account, debit, credit,
description, pid_branch, pid_cost_center, party_type, party_id, party_name,
journal_date, pid_org` (date and org denormalised so the ledger view needs
no join to filter). CHECK debit ≥ 0, credit ≥ 0, not both > 0. Indexes on
`(pid_account, journal_date)`, `(pid_branch, journal_date)`, `(party_type,
party_id, journal_date)`, `(pid_journal, line_no)`.

### Immutability triggers
`acc_guard_posted_journal` (BEFORE UPDATE/DELETE on `acc_journal`) raises
"Posted journal JV… cannot be deleted; reverse it instead" on DELETE of a
POSTED/REVERSED journal and refuses any UPDATE that changes its organisation,
branch, number, date, period, totals, type or lines-bearing fields — only the
status flip to REVERSED, the reversal links and the reversed-by stamps pass.
`acc_guard_posted_line` (BEFORE UPDATE/DELETE on `acc_journal_line`) refuses
any change to a line whose journal is POSTED/REVERSED. The verification suite
proves both by attempting the UPDATE/DELETE directly in SQL.

### Views
`acc_v_ledger` — one row per journal line of a POSTED or REVERSED journal (V97: a LEFT JOIN plus an anti-join on the unposted partial index, so a query reading only line and account columns never joins the journal) with the
journal's number, date, type, status, source, reference, the account's code,
name, type, normal side, role and the line's branch, party and cost centre.
`acc_v_account_balance` — Σ debit, Σ credit and signed balance per
organisation, branch and account.

## Control tables

| Table | Key columns |
|---|---|
| `acc_fiscal_year` | `pid_org, year_name, start_date, end_date, year_status` |
| `acc_period` | `pid_fiscal_year, pid_org, period_no, period_name (yyyy-MM), start_date, end_date, period_status (OPEN|CLOSED|LOCKED), closed_by/at, locked_by/at` |
| `acc_cost_center` | `pid_org, cc_code, cc_name, pid_branch, description, is_active` |
| `acc_tax_rate` | `pid_org, tax_code, tax_name, tax_type, rate_pct NUMERIC(7,4), effective_from/to, is_inclusive, pid_account, is_active` |
| `acc_setting` | `pid_org, setting_key, setting_value` unique per org/key |
| `acc_payment_mode` | `pid_org NULL = default, mode_key, mode_label, account_role, pid_bank_account, is_active` unique on `(COALESCE(pid_org,0), mode_key)` |
| `acc_bank_account` | `pid_org, pid_branch, bank_name, account_no, account_name, branch_name, pid_account, is_default, is_active` |
| `acc_cash_voucher` | `voucher_no (CV…), voucher_date, voucher_kind, pid_cash_account, pid_counter_account, amount, party, reference, description, reason, voucher_status (DRAFT|POSTED|CANCELLED), approved/posted/cancelled stamps, pid_event, pid_journal` |
| `acc_backfill_batch` | `pid_org, pid_branch, scope_module, date_from, date_to, batch_status (RUNNING|DONE|FAILED), scanned/posted/skipped/failed_count, summary, started_by/at, finished_at` |
| `acc_denomination` | `pid_org, denom_value, denom_kind (NOTE|COIN), sort_order, is_active` |
| `acc_business_day` | `pid_org, pid_branch, business_date, pid_cash_account, day_status, pid_previous_day, opening_cash, cash_in, cash_out, expected_closing, physical_cash, difference, difference_kind, difference_reason, notes, variance_posted, pid_variance_journal, opened/counted/submitted/reviewed/closed/reopened by+at, reopen_reason, reopen_count` — **unique `(pid_branch, business_date)`** |
| `acc_cash_count` | `pid_business_day, denom_value, quantity, amount` |
| `acc_business_day_audit` | `pid_business_day, action, note, acted_by, acted_at` |
| `acc_investor` | `pid_org, pid_branch, investor_code (unique per org), investor_name, contact_*, id_reference, investment_type, start/end_date, share_pct, investor_status, notes` |
| `acc_investor_txn` | `txn_no (IV…), pid_investor, txn_type, txn_date, amount, direction, payment_mode, description, reason, txn_status, pid_distribution, pid_allocation, approved/posted/cancelled stamps, pid_event, pid_journal` |
| `acc_profit_policy` (+`_line`) | `policy_name, share_method, investor_pool_pct, retained_pct, effective_from/to, pid_branch, is_active, notes`; lines `pid_investor, share_pct` |
| `acc_profit_distribution` | `distribution_no (PD…), period_from/to, pid_period_from/to, pid_profit_policy, share_method, revenue, cost_of_sales, operating_expenses, other_income, other_expense, accounting_profit, retained_pct/amount, adjustment_amount/reason, distributable_profit, investor_pool_pct, allocated_total, paid_total, dist_status, snapshot (json), reviewed/approved/posted/cancelled stamps, pid_journal, notes` — **partial unique `uq_acc_distribution_period` on `(pid_org, COALESCE(pid_branch,0), period_from, period_to) WHERE dist_status <> 'CANCELLED'`** |
| `acc_profit_allocation` | `pid_distribution, pid_investor, opening_capital, additions, withdrawals, eligible_capital, share_pct, allocated_amount, paid_amount, alloc_status (DRAFT|POSTED|PARTIAL|PAID), pid_investor_txn` |

## Operational notes

* **Hibernate `ddl-auto: update`** widens the SMALLINT flag columns
  (`is_group`, `is_active`, `is_system`, `allow_manual`, `is_default`,
  `is_inclusive`, `variance_posted`) to `integer` at first boot because the
  entities map them as `Integer`. Observed on dev; harmless; expected on
  production's first start after V90–V92.
* Sizes on the dev database after backfill of organisation 1: see
  [migration-reconciliation.md](migration-reconciliation.md). Roughly two
  journal lines per business document plus the receipt journal for paid
  sales; `acc_journal_line` is the largest table and is indexed for the
  ledger's access paths (account+date, branch+date, party+date).
* Retention: nothing is deleted. Periods are locked, not archived.
* Backups: the accounting tables are ordinary tables in the same schema and
  are covered by the existing `pg_dump` routine; no separate backup is
  needed. Every foreign key is inside the `acc_*` set (organisation, branch
  and party ids are plain columns, as elsewhere in this schema), so the
  tables restore in any order alongside the rest.
