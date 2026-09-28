# Accounts — testing

Four layers, from the inside out. Every figure asserted anywhere is read back
from the ledger, never recomputed in the test from the same inputs.

## 1. Unit tests (JUnit 5 + Mockito, `backend/src/test/java/.../service/impl/accounting/`)

Run: `mvn -o -q test -Dtest='AccountingEventServiceTest,AccProfitAllocationTest'`
(or the whole suite, `mvn test`, which includes the 322 pre-existing tests).

**`AccountingEventServiceTest`** (18 tests) — the posting engine with every
collaborator mocked:

| Group | Proves |
|---|---|
| Posting | a rule becomes a balanced POSTED journal with the right accounts, sides, party on the AR line only, branch on every line, the event marked POSTED with the journal id; a zero amount line is left out and an all-zero document is SKIPPED; a negative amount swaps the side (shortage = credit to the till); a credit sale with real money still reaches the till; payroll splits make one line per employee |
| Refusals | an unbalanced rule posts nothing and records the failure; a missing rule is a configuration error; a group account is refused; an event without identity is refused before anything is read |
| Idempotency | an already-posted identity returns the existing journal without touching rules or saving; a REVERSED identity demands a new revision key; revision keys count only this organisation's earlier postings and ignore `:REV` trails; posting switched off records SKIPPED and reads no rule |
| Closed day | a cash posting into a CLOSED day is refused; a bank posting on the same day is fine; NEXT_DAY policy dates the posting on the first day not closed (skipping a LOCKED one); historical backfill ignores the guard; `require_day_open` refuses a day nobody opened |

**`AccProfitAllocationTest`** (9 tests) — the allocation maths through the
package-private `allocate()` with the ledger statements stubbed: capital-ratio
shares by closing capital with the rounding remainder on the last investor;
additions/withdrawals inside the period are recorded; weighted-average capital
counts money for the days it was in (1000 for 30 days vs 3000 for 16 days →
1000 : 1600); investor-specific percentages come from the policy lines and may
leave part of the pool; custom method uses the investor's own share; over 100%
is refused; no capital → zero allocations; no active investor is an error;
branch distributions include only that branch's and unscoped investors.

The pre-existing suite (`ProductServiceImplTest`, `SalesReturnMathTest`,
`LandedCostTest`, …) still passes — **349 tests, 0 failures** on 17/09/2026
(322 existing + 27 new). The hooks change no business calculation; the
existing tests exercise the maths helpers and `ProductServiceImpl`, none of
which take an accounting bean.

## 2. API verification suite (Node, `scratchpad/verify-accounts-api.mjs`)

Runs against the live backend (port 8585) as `admin`, using the dev database
and direct `psql` checks. It is re-runnable: it reopens the day it closed on
the previous run and takes the next free month for a distribution (a past
month, where today's test investors hold no capital, so the capital-ratio
assertion expects zero shares there). 77 assertions, all `ok` on the last run:

* chart (53 accounts), settings (11 keys, 32 rules, 12 payment modes), periods;
* manual journal: draft → unbalanced refused → posted → **`UPDATE` in SQL
  refused by the trigger** → reversal without reason refused → reversed, the
  original REVERSED with its lines intact;
* cash and daily cash: sheet, open, adjustment without reason refused,
  adjustment and payment vouchers posted as journals, cash in/out equal the
  ledger, expected = opening + in − out, drill-down lists the vouchers, count
  by denomination → SHORT, close without reason refused, close with reason,
  **a cash posting into the closed day refused (`ACC_DAY_CLOSED`)**, reopen
  with reason, close again, audit trail, cash book;
* investors: create, bank investment posted, **cash investment into the
  closed day refused**, wallet investment, capital credited per investor,
  withdrawal above capital refused, list reads capital from the ledger;
* profit: policy saved, income voucher, calculate — accounting profit equals
  the ledger P&L computed independently in SQL, distributable = profit −
  retained, allocations by capital ratio add up to the pool, duplicate period
  refused, post before approval refused, review → approve → post, paying
  more than allocated refused, allocation paid by bank;
* all 30 `acc-*` reports return a grid; trial balance closing Dr = Cr;
  dashboard; reconciliation integrity clean; backfill dry run; event monitor;
  **the whole ledger balances (Σ debit = Σ credit over every posted line)**.

`scratchpad/acc-smoke.mjs` is the end-to-end business proof: a sale through
the real Sales API → SALE_POSTED and SALE_RECEIPT journals; editing the sale →
reversal + re-post under `:R2`; collecting the balance → wallet debited and
the receivable cleared.

## 3. Screen QA (Playwright, `scratchpad/qa-acc.mjs`)

Drives all twelve Accounts pages at 1440 / 1024 / 768 / 375 (plus every tab,
the journal/investor/distribution drawers and the dark theme) in one
authenticated browser context, recording console errors, page errors, HTTP
≥ 500 and 400s from `/api/v1/accounts`, horizontal overflow and visible
error states, and saving a screenshot of each (`scratchpad/acc/*.jpg`).
Findings and fixes from the runs on 17/09/2026 are listed in
[ui-ux.md](ui-ux.md).

## 4. Regression of the existing modules

* `mvn test` — 349 green (322 pre-existing + 27 new) after the hooks were wired.
* Sales, sales-return, purchase, purchase-return, supplier-payment, payroll,
  expense, advance/loan, gratuity and subscription flows were exercised
  through their own APIs by the smoke and verification suites and by the
  backfill of 200k historical documents through the same hook methods; a
  hook failure rolls the business transaction back with a clear message, so
  a broken rule cannot leave a half-recorded sale.
* The SaaS verification suites (tenancy, subscriptions, onboarding — see the
  memory note "SaaS verification suites") are unaffected: onboarding now also
  provisions the chart, which the tenant suite exercises by creating an
  organisation.

## What is not automated (yet)

* Report **content** is asserted only as "returns a grid"; the figures are
  proven at the ledger level (trial balance, P&L, party balances) rather than
  per report.
* Reconciliation checks are asserted as clean on the verification run; the
  document-vs-ledger checks are exercised by the backfill reconciliation in
  [migration-reconciliation.md](migration-reconciliation.md).
* Frontend unit tests: none (the project has none for any screen); the
  Playwright drive is the frontend check.
