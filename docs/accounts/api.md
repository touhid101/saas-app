# Accounts API

Base path **`/api/v1/accounts`**. Every response is the application's
`ApiResponse` envelope `{ success, message, data }`; paged lists return
`PageResponse` (`content, totalElements, totalPages, page, size`). Dates are
ISO `yyyy-MM-dd`. Authentication and tenancy are the application's own (JWT;
organisation and branch from the session — a `branchId` parameter is
validated server-side and, except for the platform operator, must be the
caller's own branch; the platform operator may pass none for "all branches").

Errors are `BusinessException`s with a code: `ACC_NOT_PERMITTED` (403),
`ACC_VALIDATION` (400/422), `ACC_UNBALANCED`, `ACC_DAY_CLOSED`,
`ACC_DAY_NOT_OPEN`, `ACC_DAY_STILL_OPEN`, `ACC_DAY_HAS_SUCCESSOR`,
`ACC_PERIOD_CLOSED`, `ACC_PERIOD_LOCKED`, `ACC_RULE_MISSING`,
`ACC_GROUP_ACCOUNT`, `ACC_ACCOUNT_RETIRED`, `ACC_EVENT_REVERSED`,
`ACC_IMMUTABLE`, `ACC_NOT_DRAFT`, `ACC_CANCELLED`, `ACC_DIST_DUPLICATE`,
`ACC_DIST_STATE`, `ACC_DIST_FROZEN`, `ACC_DIST_PAID`, `ACC_OVER_ALLOCATED`,
`ACC_NO_INVESTORS`, `ACC_NO_POLICY`, `ACC_POSTING_FAILED`,
`ACC_RETRY_UNSUPPORTED`, `ACC_JOURNAL_NOT_FOUND`, `ACC_EVENT_NOT_FOUND`
(mostly 409). Messages are written for the user; no SQL ever reaches them.

Rights: **M** = module, V/C/E/A/D = view/create/edit/approve/delete on it.
MOD41 Dashboard · MOD42 Journals · MOD43 Ledger · MOD44 Chart · MOD45
Parties · MOD46 Cash & Bank · MOD47 Daily Cash · MOD48 Investors · MOD49
Profit · MOD50 Settings · MOD51 Reports · MOD52 Reconciliation. "any V" = view
on any Accounts module.

## Dashboard and chart (`AccountsController`)

| Method & path | Right | Parameters / body | Returns |
|---|---|---|---|
| GET `/dashboard` | M41 V | `branchId?`, `from?`, `to?` (default this month) | `period{revenue, costOfSales, grossProfit, expenses, netProfit}`, `position{receivable, payable, cashInTill, bank, mobileWallet, pettyCash, inventory, payrollPayable, investorCapital, investorProfitPayable, taxPayable, cashInTransit}`, `dailyCash` (today's sheet summary), `control{failedEvents, skippedEvents, draftJournals, unclosedDays, cashShortages, cashOverages, pendingDistributions, currentPeriod, openPeriods}`, `recentJournals[]`, `receivablesTop[]`, `payablesTop[]` |
| GET `/chart` | any V | | accounts in tree order with `depth`, `balance`, `hasLines` |
| POST `/chart` | M44 C | `AccAccount` json (`accountCode, accountName, accountType, pidParent?, isGroup?, normalSide?, roleKey?, allowManual?, description?`) | the account |
| PUT `/chart/{id}` | M44 E | same (name, parent, role, allowManual, isActive, description) | the account |
| GET `/periods` | M50 V | `yearId?` | `{years[], periods[]}` |
| POST `/periods/{id}/status` | M50 A | `{status: OPEN\|CLOSED\|LOCKED}` | the period (closing refuses drafts inside; locking is final) |
| POST `/periods/years` | M50 C | `{date}` | the fiscal year containing `date`, with 12 periods |
| GET `/settings` | M50 V | | `{settings{}, defaults{}, paymentModes[], bankAccounts[], taxRates[], costCenters[], rules[{rule, lines[]}]}` |
| PUT `/settings` | M50 E | `{key: value, …}` | settings |
| POST `/settings/payment-modes` | M50 E | `{modeKey, modeLabel?, accountRole?, pidBankAccount?, isActive?}` | the mapping (per org) |
| POST `/settings/bank-accounts` | M46 C | `{bankName, accountNo, accountName?, branchName?, pidAccount?, isDefault?}` | bank account (a ledger account under 1130 is created if none given) |
| PUT `/settings/bank-accounts/{id}` | M46 E | same | |
| POST `/settings/tax-rates` | M50 E | `{pidTaxRate?, taxCode, taxName, taxType, ratePct, effectiveFrom, effectiveTo?, isInclusive?, pidAccount?, isActive?}` | |
| GET `/cost-centers` | M46 V | | active cost centres for the org, code order — the short list the entry screens offer (`/settings` also returns them, with everything else) |
| POST `/settings/cost-centers` | M50 E | `{pidCostCenter?, ccCode, ccName, description?, pidBranch?, isActive?}` | |
| POST `/settings/rules/{eventType}` | M50 A | `{lines: [{side, accountRole, amountKey, partyType?, description?}], isActive?}` | the organisation's copy of the rule |

## Journals, ledger, parties, cash, daily cash (`AccLedgerController`)

| Method & path | Right | Parameters / body | Notes |
|---|---|---|---|
| GET `/journals` | M42 V | `branchId?, status?, type?, from?, to?, q?, page, size` | paged |
| GET `/journals/{id}` | M42 V | | `{journal, lines[], event?, reversal?}` |
| POST `/journals` | M42 C | `{journalDate, branchId?, reference?, description, costCenterId?, journalType: MANUAL\|ADJUSTMENT\|OPENING, lines: [{accountId, debit, credit, description?, partyType?, partyId?, partyName?, costCenterId?}]}` | saved DRAFT (posted on save if `manual_journal_auto_post = Y` and caller holds Approve) |
| PUT `/journals/{id}` | M42 E | same | drafts only |
| DELETE `/journals/{id}` | M42 D | | drafts only |
| POST `/journals/{id}/post` | M42 A | | validates and posts |
| POST `/journals/{id}/reverse` | M42 A | `{reason, date?}` | REVERSAL journal |
| GET `/ledger` | M43 V | `accountId, branchId?, from?, to?, costCenterId?, source?, q?, page, size` | opening balance, rows with running balance, closing |
| GET `/trial-balance` | M43 V | `branchId?, from?, to?` | per account: opening, debit, credit, closing |
| GET `/parties/{CUSTOMER\|SUPPLIER\|EMPLOYEE\|INVESTOR\|TENANT}` | M45 V | `branchId?, asOf?, openOnly=true, ageing=false` | balances per party; with `ageing` the 0–30/31–60/61–90/90+ buckets |
| GET `/parties/{type}/{partyId}/statement` | M45 V | `branchId?, role?, from?, to?` | opening, rows, closing on the party's control account(s). `role` takes one role or a comma list; the default for SUPPLIER is `AP,SUPPLIER_ADVANCE`, so a payment above the bill shows as a debit on Supplier Advances and the balance nets (positive owed, negative advance held). Each row carries `account`. Reports → *Supplier Statement* uses the same pair |
| GET `/cash/positions` | M46 V | `branchId?` | balance per cash/bank/wallet/petty/transit account |
| GET `/cash/accounts` | any V | | the cash-like accounts |
| GET `/cash/vouchers` | M46 V | `branchId?, status?, kind?, from?, to?, page, size` | |
| POST `/cash/vouchers` | M46 C | `{voucherDate, voucherKind: RECEIPT\|PAYMENT\|TRANSFER\|OPENING\|ADJUSTMENT_IN\|ADJUSTMENT_OUT, pidCashAccount, pidCounterAccount?, amount, partyType?, partyId?, partyName?, reference?, description?, reason?, pidCostCenter?}` | DRAFT; number `CV`+yyMM+5 |
| POST `/cash/vouchers/{id}/approve` | M46 A | | approve + post |
| POST `/cash/vouchers/{id}/cancel` | M46 A | `{reason}` | reverses if posted |
| GET `/daily-cash/current` | M47 V | `branchId?, date?` | the day sheet (see daily-cash-control.md) |
| GET `/daily-cash/book` | M47 V | `branchId?, from?, to?` | one row per day |
| POST `/daily-cash/open` | M47 C | `{branchId?, date?}` | |
| POST `/daily-cash/{dayId}/count` | M47 E | `{counts: [{denomValue, quantity}], physicalTotal?}` | |
| POST `/daily-cash/{dayId}/close` | M47 E (A when SUBMITTED) | `{differenceReason?, notes?}` | |
| POST `/daily-cash/{dayId}/reopen` | M47 A | `{reason}` | |

## Investors, profit, events, reconciliation (`AccInvestorController`)

| Method & path | Right | Parameters / body | Notes |
|---|---|---|---|
| GET `/investors` | M48 V | | with ledger capital and payable |
| POST `/investors` · PUT `/investors/{id}` | M48 C / E | `AccInvestor` json | |
| GET `/investors/{id}` | M48 V | `from?, to?` | investor + balances + capital and payable statements |
| GET `/investors/{id}/ledger` | M48 V | `from?, to?` | capital statement only |
| GET `/investor-transactions` | M48 V | `investorId?, status?, page, size` | |
| POST `/investor-transactions` | M48 C | `{pidInvestor, txnType, txnDate, amount, paymentMode?, direction? (adjustments: 1/-1), reason?, description?, pidAllocation? (profit payment)}` | DRAFT, or POSTED at once for a caller with Approve |
| POST `/investor-transactions/{id}/approve` | M48 A | | approve + post |
| POST `/investor-transactions/{id}/cancel` | M48 A | `{reason}` | |
| GET `/profit/policies` | M49 V | | `[{policy, lines[]}]` |
| POST `/profit/policies` | M49 E | `{policy: {...}, lines: [{pidInvestor, sharePct}]}` | |
| GET `/profit/distributions` | M49 V | | |
| GET `/profit/distributions/{id}` | M49 V | | `{distribution, allocations[], snapshot, journal?}` |
| POST `/profit/distributions/calculate` | M49 C | `{existingId?, branchId?, from, to, policyId?, adjustment?, adjustmentReason?, notes?}` | CALCULATED |
| POST `/profit/distributions/{id}/review` | M49 E | | |
| POST `/profit/distributions/{id}/approve` · `/post` | M49 A | | |
| POST `/profit/distributions/{id}/cancel` | M49 A | `{reason}` | |
| GET `/events` | M52 V | `branchId?, status?, module?, from?, to? (default last 30 days), page, size` | the event monitor |
| POST `/events/{id}/retry` | M52 E | | re-runs a FAILED/SKIPPED sales/purchase/supplier-payment event from its document |
| GET `/reconciliation` | M52 V | `branchId?, from?, to?` (default: this month) | `{checks[], integrity{window, …}, cash[], batches[]}` — document checks **and** the integrity counts are read over the window; widen it for a full audit (since 2000 takes ~10 s on a million lines) |
| GET `/backfill/batches` | M52 V | | the backfill batches alone, newest first — what the Backfill tab lists |
| POST `/backfill` | M52 A | `{orgId? (platform only), branchId?, module?: SALES\|PURCHASE\|SUPPLIER_PAYMENT, from?, to?, dryRun, limit ≤ 5000}` | the batch row with counts and notes |

## Maintenance (`SupplierPaymentController`)

| Endpoint | Role | Body | Returns |
|---|---|---|---|
| POST `/api/supplier-payments/advances/restate` | ROLE01 | — | `[{purchaseId, invoiceNo, supplierName, advanceBefore, advanceAfter, movedToBill, paymentStatusBefore, paymentStatusAfter, ledgerAdjusted}]` — purchases in the caller's organisation whose invoice-time advance was carved from the whole discount, now restated to the invoice discount (mirror payment and a SUPPLIER_ADVANCE_RESTATED journal follow). Empty when there is nothing left to restate; safe to repeat. Purchases with no invoice discount are left alone and need a business decision. |

## Reports

Accounting reports go through the existing report grid:
`GET /api/reports/{id}?from&to&branchId&customerId&supplierId&q` with the
`acc-*` ids listed in [architecture.md](architecture.md) §5 / `ACC_REPORT_GROUPS`
(`src/services/reportService.js`). View on MOD51 is required for any `acc-*`
id; base ids need View on MOD14 (Reports) server-side as well.

Added 21 Sep 2026 — see `REPORT_GAP_ANALYSIS.md` at the repository root for the full audit:

| Id | What | Rule |
| --- | --- | --- |
| `acc-ar-movement`, `acc-ap-movement`, `acc-inventory-movement` | control-account roll-forward: opening, one row per posting source (debit, credit, net, running balance), closing; AR/AP add the open-invoice sub-ledger and the difference when the window ends today | POSTED journals only (`acc_v_ledger`); branch-scoped; balances read from the account's normal side |
| `acc-general-ledger?q=<code or name>` | when the search matches exactly one account: opening row, running balance, closing in the note | as above |
| `acc-expense-analysis`, `acc-revenue-analysis` | now carry previous period (same length, immediately before), variance and variance % | as above |

Added 27 Sep 2026 — the cost-centre dimension:

| Id | What | Rule |
| --- | --- | --- |
| `acc-daily-expense` | every expense posting in the window, grouped by **day → cost centre → head**, with a subtotal per day and one for the window; honours `costCenterId` as well as `from`/`to`/`branchId` | POSTED journals only (`acc_v_ledger`), `account_type = 'EXPENSE'`; a posting with no cost centre shows as **(not set)** rather than being folded into a bucket that would read like a real centre |

Not a duplicate of the two reports either side of it: `acc-daily-summary` gives
one expense figure a day with no breakdown, `acc-expense-analysis` breaks a
window down by head with no days in it. Neither can answer "what did the front
shop spend on tea last week".

`GET /api/reports/{id}` gained `costCenterId`; the screen shows the picker only
for reports whose `supportedFilters` include `costCenter`, and only when the
organisation actually keeps cost centres.

## Frontend client

`src/services/accountsService.js` wraps every endpoint above (one function
each, e.g. `getDaySheet`, `closeDay`, `calculateProfit`, `runBackfill`) and
exposes the constant lists `VOUCHER_KINDS`, `INVESTOR_TXN_TYPES`,
`SHARE_METHODS`, `PAYMENT_MODES`, `ACCOUNT_TYPES`.
