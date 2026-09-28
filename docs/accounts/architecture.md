# Accounts module — architecture

*Friends Pharma SaaS · Accounting / Accounts module · September 2026*

This document describes how the accounting module is built: where the books
live, how a business transaction becomes a journal, what is authoritative, and
which decisions were made on purpose. It is written for the engineer who has to
change it and the accountant who has to trust it.

Companion documents: [current-financial-transaction-analysis.md](current-financial-transaction-analysis.md)
(what the application already did with money before this module),
[chart-of-accounts.md](chart-of-accounts.md), [accounting-rules.md](accounting-rules.md),
[daily-cash-control.md](daily-cash-control.md), [investor-accounting.md](investor-accounting.md),
[profit-distribution.md](profit-distribution.md), [api.md](api.md), [database.md](database.md),
[migration-reconciliation.md](migration-reconciliation.md), [testing.md](testing.md), [ui-ux.md](ui-ux.md).

---

## 1. The one-sentence model

> A business transaction raises a **financial event**; an **accounting rule**
> turns the event into a balanced **journal**; the journal is the only thing
> that changes the **general ledger**; every report is read from the ledger.

```
 Sales / Purchase / HR / Cash / Investor / Subscription screens
                │  (existing service methods — unchanged business logic)
                ▼
      AccountingHooks.<transaction>(entity)         ── builds a FinancialEvent
                │
                ▼
      AccountingEventService.post(event)            ── idempotent, transactional
        ├─ acc_event      (one row per event identity, status POSTED/SKIPPED/FAILED/REVERSED)
        ├─ acc_rule / acc_rule_line → resolves roles → acc_account
        ├─ acc_journal + acc_journal_line  (POSTED, immutable)
        └─ guards: balance, period open, business day not closed, group/retired accounts
                │
                ▼
      acc_v_ledger / acc_v_account_balance          ── the only source for
      AccLedgerService                                 ledger, TB, P&L, BS, ageing,
      AccReportServiceImpl (30 acc-* reports)          statements, cash books, dashboards
```

Nothing writes to `acc_journal_line` except the engine. Nothing reads business
tables to produce a financial figure except the reconciliation centre, whose
job is precisely to compare the two.

## 2. Tenancy and scope

| Level | Object | Accounting meaning |
|---|---|---|
| Platform | `pos_org` = 1 (Friends Pharma) | Owns the SaaS books: subscription revenue. Platform operator = `ROLE01` **and** platform org (see memory note "Platform operator = role AND org"). |
| Organisation (pharmacy) | `pos_org.pid_org` | **One set of books per organisation.** Chart of accounts, periods, settings, rules, investors and profit distributions all carry `pid_org`. |
| Branch | `pos_branch` | A **dimension** on every journal and journal line (`pid_branch`). Branch reports filter by it; the trial balance across branches still balances because every journal is single-branch. Daily cash control is per branch. |
| Cost centre | `acc_cost_center` | Optional dimension on journals and journal lines (`pid_cost_center`). Defined on Periods & Settings; **set** on a cash voucher (one per voucher, copied onto both its ledger lines) and per line on a manual journal; read back by `acc-daily-expense`. Branch is where a posting happened, a cost centre is who it was for — one shop can hold several. |

`AccScope` (`service/impl/accounting/AccScope.java`) is the single place scope
is decided: `orgId()` from the session, `branchId(req)` validates a requested
branch against the user (never trusted from the client), `branchFilter(req)`
returns `null` (= all branches) only for the platform operator, and
`require(module, right, action)` enforces the module rights in §8.

## 3. The posting engine

`AccountingEventService.post(FinancialEvent)`:

1. **Identity.** `(pid_org, source_module, source_type, source_id, event_key)`
   is unique in `acc_event`. A second post of the same identity returns the
   existing journal and does nothing (idempotent). A REVERSED identity refuses
   a re-post; the caller must take a new revision key (`nextRevisionKey` →
   `SALE_CREATED:R2`, `:R3`…), which is how an edited sale is re-posted.
2. **Posting switch.** If `posting_enabled = N` for the organisation the event
   is stored **SKIPPED** with the reason, and the business transaction proceeds.
   Skipped events can be replayed from the Reconciliation screen.
3. **Rule.** `acc_rule` for the event type — the organisation's own copy if it
   has one, otherwise the shared default (`pid_org IS NULL`). Rules are cached
   in memory per organisation/event type and evicted when a rule is saved.
4. **Lines.** Each rule line names a side, an *account role*, an *amount key*
   and optionally a party type. The amount comes from the event's amounts map;
   zero amounts are skipped; a negative amount swaps the side (a shortage is a
   credit to the till, never a negative debit). A line whose amount key has
   *splits* (payroll: one per employee) becomes one journal line per split,
   each carrying its own party.
5. **Accounts.** A role resolves through the chart cache (`AccChartService`).
   `@MODE` resolves the event's payment mode through `acc_payment_mode`
   (unmapped/credit → the till, or the role after `|`). `@PAYLOAD:<key>` takes
   an account the hook chose (refund account, expense type's account…). Group
   and retired accounts are refused.

   *The per-organisation caches (chart, payment modes, periods) are filled on
   first use — which, for an organisation's first posting, is inside the
   posting's own transaction, right after `ensureProvisioned` / `createYear`
   inserted the rows. A fill that happens inside a transaction registers an
   eviction that runs unless that transaction commits (`UncommittedGuard`), so
   a rolled-back first posting cannot leave the cache holding ids the database
   never got — the symptom was every later posting failing with
   `fk_acc_journal_period … Key (pid_period)=(9) is not present` until restart.*
6. **Balance.** Σ debit must equal Σ credit or nothing is posted
   (`ACC_UNBALANCED`); a zero-value document posts nothing and is SKIPPED.
7. **Date and period.** The journal is dated on the event date. A journal
   whose lines touch the branch till on a CLOSED/SUBMITTED/LOCKED business
   day is refused (`ACC_DAY_CLOSED`) or moved to the next open date when
   `late_posting_policy = NEXT_DAY`. Historical (backfill) postings skip this
   guard. The fiscal period is auto-created if missing and must be OPEN
   (CLOSED accepts only ADJUSTMENT/REVERSAL; LOCKED accepts nothing).
8. **Write.** `acc_journal` (status POSTED, number `JV` + yyMM + 5 digits from
   `sequence_tracker` SEQ_JOURNAL_NO) and its lines; `acc_event` → POSTED with
   the journal id.

**Synchronous by design.** Posting runs inside the business transaction. If
the posting fails the business write rolls back with a clear message — a sale
that cannot be accounted for is not a sale. The failure itself is recorded in
`acc_event` as FAILED by `AccEventFailureRecorder` in a `REQUIRES_NEW`
transaction so it survives the rollback (see memory note "Audit rows roll
back with refusals"). No queue, no eventual consistency, no "posting later".

**Reversal, never edit.** Posted journals are immutable: database triggers
`acc_guard_posted_journal` / `acc_guard_posted_line` refuse UPDATE/DELETE on a
POSTED/REVERSED journal (except the status flip to REVERSED and the reversal
link). A correction is a REVERSAL journal with the lines swapped, the original
marked REVERSED, and a trail event `<key>:REV`. Editing a sale = reverse the
original postings + post again under a new revision key.

## 4. Where the hooks are

Each existing service method that completes a money-moving action calls one
`AccountingHooks` method after its own writes, in the same transaction. The
business logic of those methods is unchanged.

| Module | Service (method) | Hook | Event(s) |
|---|---|---|---|
| Sales | `SalesServiceImpl.createSale / updateSale / collect / cascadeCollection` | `saleCreated / saleUpdated / saleCollected` | SALE_POSTED, SALE_RECEIPT, SALE_COLLECTION |
| Delivery collection | `CollectionServiceImpl.record / update / cancel` | `deliveryCashIn / deliveryCashInAmended / deliveryCashInCancelled` | DELIVERY_CASH_IN (+ reversals) |
| Sales return | `SalesReturnServiceImpl` | `salesReturnCreated` | SALES_RETURN_POSTED |
| Purchase | `PurchaseServiceImpl.approve / receive` | `purchaseApproved` | PURCHASE_APPROVED |
| Purchase return | `PurchaseReturnServiceImpl` | `purchaseReturnCreated` | PURCHASE_RETURN_POSTED |
| Supplier payment | `SupplierPaymentServiceImpl.record / mirrorInvoicePayment / recoverAdvance / cancel` | `supplierPaymentRecorded / supplierPaymentCancelled` | SUPPLIER_PAYMENT, SUPPLIER_ADVANCE_RECOVERY |
| Payroll | `HrPayrollServiceImpl.post / pay / cancelPayment` | `payrollPosted / salaryPaid / salaryPaymentCancelled` | PAYROLL_POSTED, SALARY_PAID |
| Expense claims | `HrExpenseServiceImpl.approve / pay` | `expenseClaimApproved / expenseClaimPaid` | EXPENSE_CLAIM_APPROVED, EXPENSE_CLAIM_PAID |
| Advance / loan | `HrAdvanceLoanServiceImpl.payAdvance / disburseLoan` | `advancePaid / loanDisbursed` | ADVANCE_PAID, LOAN_DISBURSED |
| Gratuity | `HrGratuityServiceImpl.pay` | `gratuityPaid` | GRATUITY_PAID |
| Subscriptions (platform books) | `SubscriptionServiceImpl.raiseRenewal / recordPayment / voidInvoice` | `subscriptionInvoiced / subscriptionPaid / subscriptionVoided` | SUBSCRIPTION_INVOICED, SUBSCRIPTION_PAID |
| Onboarding | `TenantOnboardingService` | `AccChartService.ensureProvisioned` | chart provisioned for a new organisation |
| Accounts screens | `AccCashService`, `AccInvestorService`, `AccProfitDistributionService`, `AccDailyCashService`, `AccJournalService` | direct `engine.post` | CASH_*, INVESTOR_*, PROFIT_*, CASH_VARIANCE_*, manual journals |

Cost of goods sold is Σ `qty_pcs × cost_per_pcs` of the stock movements the
sale/return created (`ref_type = 'TRANSACTION'`), so the ledger's COGS is the
same figure the inventory module already tracks. Purchases are booked at landed
cost. The full Dr/Cr per event is in [accounting-rules.md](accounting-rules.md).

## 5. Ledger and reports

`acc_v_ledger` joins lines to journals and accounts; `acc_v_account_balance`
sums it. `AccLedgerService` runs native SQL over these views for: account
ledger with running balance, trial balance, balances by role, totals by type,
profit & loss, balance sheet, party balances and statements, ageing (30/60/90+),
cash by source, day movement, cash flow, daily cash book. Every dashboard tile
and every one of the 30 `acc-*` reports (`AccReportServiceImpl`, plugged into
the existing `ReportGridServiceImpl` catalogue) calls this service. There is no
second computation of any financial number.

### Dashboard cost

The dashboard reads the whole ledger — every line as at the end date, plus the
window and the one before it — and on a million lines that once took seven
seconds. Four things keep it near a second cold and ~100ms warm:

1. **Two passes, not fourteen.** `sumsByBranchAndAccount` (as at) and
   `windowSumsByBranchAndAccount` (this window and the previous one, flagged)
   return a few hundred rows; every tile, branch row, previous-period delta and
   expense line is a sum over them in Java. `ageing` reads a party type once,
   grouped by party and day: the balances, last activity and the bucket walk
   all come from those daily rows (a day is the finest grain a bucket needs).
2. **The view without the journal join** (V97). `acc_v_ledger` left-joins the
   journal and excludes drafts through an anti-join on a partial index of
   unposted journals; a query that reads no journal column never touches the
   journal table. The line's own `journal_date` is the one exposed.
3. **Covering indexes** (V96/V98) on `(pid_org, journal_date)` and
   `(pid_org, party_type, party_id, journal_date)` carrying the amounts and
   keys, so the scans can be answered from the index and stay cached.
4. **Custom plans and memory** — `AccLedgerService.analyticSession()` sets
   `plan_cache_mode = force_custom_plan` and `work_mem = 64MB` for the
   transaction: the generic plan Postgres settles on after five executions
   dropped the parallel scan and hash anti-join for a nested loop over every
   line, and the sorts spilled to disk at 4MB.

The five reads run side by side on a four-thread pool and the result is kept
per organisation/branch/window against `AccLedgerVersion` — a counter bumped
after every journal commit (engine post, reversal, manual post) — with a
five-minute ceiling. The page polls every minute; while nobody posts, the poll
costs the database nothing. Today's cash sheet and the control counters are
read live each time.

Retained earnings are **not** posted by a year-end routine; the balance sheet
shows the cumulative result as "Profit to date (revenue less expenses)" under equity,
and profit distribution debits *Profit Distributed* (3400) rather than
retained earnings so the P&L stays intact.

## 6. Branch daily cash control

`acc_business_day` per branch and date: OPEN → (SUBMITTED) → CLOSED, or
REOPENED with a reason and Approve. Opening cash = the previous closed day's
counted cash (before any close: the ledger's till balance); expected closing =
opening + ledger cash in − ledger cash out for that date; physical cash is the
denomination count; difference = physical − expected → SHORT / OVER / BALANCED.
Details in [daily-cash-control.md](daily-cash-control.md).

## 7. Investors and profit distribution

Investor master, capital ledger (party lines on 3200 Investor Capital),
transactions with approve/post/cancel, and a distribution workflow
DRAFT → CALCULATED → REVIEWED → APPROVED → POSTED → PAID driven by a
configurable policy (five methods). Details in
[investor-accounting.md](investor-accounting.md) and
[profit-distribution.md](profit-distribution.md).

## 8. Security

Twelve modules in menu group `GRP_ACCOUNTS` (`V93`): MOD41 Dashboard … MOD52
Reconciliation. Rights per role follow the existing RBAC (`ROLE01`/`ROLE02`:
all; `ROLE04` (accounts/finance): all but delete; others none until granted).
Each endpoint declares the module and right it needs via `AccScope.require`;
the UI hides what the user cannot do using `usePermission(routeKey)`, but the
server is the authority. Branch ids in requests are validated server-side.
Errors reach the UI as business messages with codes (`ACC_*`); SQL and stack
traces never do.

## 9. Configuration, not code

Everything an accountant might want to change is data:

* chart of accounts and posting roles (`acc_account.role_key`, unique per org)
* accounting rules (`acc_rule`, `acc_rule_line`, per-org override of a default)
* payment-mode → cash-account map (`acc_payment_mode`)
* settings (`acc_setting`, per org, defaults in `AccSettings.DEFAULTS`):
  `fiscal_year_start_month`, `currency`, `cash_tolerance`, `post_cash_variance`,
  `closing_requires_review`, `require_day_open`, `late_posting_policy`,
  `expense_claim_basis`, `posting_enabled`, `manual_journal_auto_post`,
  `use_cash_in_transit`
* tax rates (`acc_tax_rate`), cost centres, bank accounts, denominations
* fiscal years and periods (`acc_fiscal_year`, `acc_period`)

## 10. Deliberate decisions and open policy points

* **Synchronous posting** (above). The alternative — an outbox with retries —
  was rejected because a pharmacy till cannot show a sale the books do not.
* **Books per organisation, branch as a dimension** rather than books per
  branch: one trial balance per legal entity; branch P&L is a filter.
* **Journal immutability is enforced in the database**, not only in Java.
* **Hibernate `ddl-auto: update`** is on in this project. At boot it widens
  the new SMALLINT flag columns (`is_group`, `is_active`, …) to `integer`
  because the entities map them as `Integer`. This is harmless, was observed
  on the dev database, and is noted here so nobody is surprised by the ALTERs
  in the startup log.
* `org.hibernate.SQL: DEBUG` is set in `application.yml`; during backfill the
  backend was started with `--logging.level.org.hibernate.SQL=INFO` to keep
  the log readable. Consider lowering it permanently.
* **BUSINESS POLICY CONFIRMATION REQUIRED**
  * *Cash in transit*: with `use_cash_in_transit = Y` (default) a delivery
    man's collection is booked to 1150 until the Collection register hands it
    in. Confirm this matches how riders actually settle.
  * *Purchase-return credit types* (BR-028): CASH/BANK returns credit the cash
    account, ADJUSTMENT/REPLACEMENT credit Supplier Advances. Confirm.
  * *Expense claims* default to ACCRUAL (expense at approval). Confirm or set
    `expense_claim_basis = CASH`.
  * *VAT*: sales are booked gross to revenue; no output-VAT split is posted
    because product VAT % is a pricing input today and the tax treatment
    (inclusive/exclusive, registration status) is not recorded. Tax rates are
    configurable and the `acc-tax` report is ready; the split is switched on
    by adding a `VAT` amount key to the SALE_POSTED rule once policy is
    confirmed. **Do not invent a rate.**
  * *Retained earnings close* at year end is not automated (see §5).
