# 18 — Testing (existing coverage + ECOM test plan skeleton)

## 1. Existing coverage

* **31 JUnit tests** in `backend/src/test` — pure calculators (`SalesPriceCalculatorTest`,
  `CustomerPricingTest`, `StockUnitConverterTest`, `ProductPricingCalculatorTest`,
  `PurchaseCalculatorTest`, `PayrollCalculatorTest`, `AmountInWordsTest`), scope rules
  (`SaleBranchScopeTest`, `ReportBranchScopeTest`, `DeliveryScopeTest`), permissions
  (`PermissionResolverTest`, `GrantedAuthorityTest`, `CapabilityModuleTest`,
  `ReportGridServiceImplRightsTest`, `AccLedgerServiceRolesTest`), storage
  (`AvatarStorageTest`), accounting, product validation, exception handling.
* **No integration test** of `createSale` / FEFO / ledger; no repository tests against Postgres.
* **Out-of-repo QA**: Playwright + node scripts (memory: `saas-verification-suites` — tenancy,
  subscriptions, onboarding; `hr-verify-suites`) driving a backend on 8586 and Vite on 5174 with
  a scripted login (`POST /api/auth/login`, tokens injected into localStorage). These are the
  regression harness that actually proves runtime behaviour (`vite build` proves nothing).
* Docs: `TESTING_GUIDE.md`, `TESTING_RBAC.md`.

## 2. ECOM test plan (to be written per phase; mapped to §105–116 of the brief)

| Brief § | Test | Type | Notes |
|---|---|---|---|
| 105 | customer-type price: same product, same branch, Retailer vs Wholesaler vs Special | unit (`CustomerPricingTest` extension) + API | reuse pure functions |
| 106 | UOM: retailer BOX refused (422 `UNIT_NOT_SELLABLE`), wholesaler BOX priced, qty→pcs snapshot | API | |
| 107 | coupon branch: coupon for A rejected on an order to B; ALL_BRANCHES accepted; usage limits under concurrency | service + API | |
| 108 | customer branch ownership: `ECOM_CUSTOMER` has no branch column (schema test); orders at 2 branches for one customer | schema + API | |
| 109 | order branch: order's `PID_BRANCH` = cart branch, POS MST `PID_BRANCH` identical, stock moved in that branch only | integration | |
| 110 | order history: customer sees only own orders across branches; another customer's order no → 404 | API | |
| 111 | branch staff order visibility: branch B staff cannot read branch A ECOM orders; ROLE01 `allBranches` only | API (`SaleBranchScopeTest` pattern) | |
| 112 | global auth: same mobile cannot register twice; login works from any branch context; staff token rejected on `/api/ecom/me`; customer token rejected on `/api/sales` | API | |
| 113 | type change: audited, propagated to shadow rows, historical order snapshot unchanged, next price quote changes | integration | |
| 114 | **POS regression**: golden comparison of MST/DTL/movement/ledger rows for a seeded staff sale before/after the `createSale` extension | integration | must exist before Phase 7 merges |
| 115 | inventory regression: FEFO order, whole-unit counts, expiry exclusion unchanged; concurrent last-box checkout → exactly one succeeds | integration (two threads) | |
| 116 | migration regression: `V100+` applied on a copy of prod-shaped DB (baseline 63, no seed rows) then app starts; JPQL/placeholder pitfalls from memory | script | |
| — | cancellation releases stock and reverses ledger (after ERP fix) | integration | |
| — | Sales Activity unchanged for `FROM_SOURCE='API'` (list, counts, summary) | API | |
| — | storefront E2E: register → approve → browse → compare → cart → checkout → board → deliver → history | Playwright | extends the existing QA harness |

## 3. Harness conventions to keep

Backend `--server.port=8586`, Vite `--port 5174` with `VITE_API_BASE_URL`, never the user's
8585/5173; scripts written with the Write tool (heredocs break); node `fetch` instead of `curl
-w`; test litter cleaned (cancel/withdraw what the run created); servers stopped after each run.

## 4. Suites that exist (scratchpad, run against 8586 / 5180)

| Script | Covers |
|---|---|
| `ecom-p1-api.mjs`, `ecom-p1-bridge.mjs` | registration, approval, tiers, the shadow-customer bridge (CREATED and adopted) |
| `ecom-p2-api.mjs` | pharmacy discovery: nearest-first, radius, tenant-status gating |
| `ecom-p3-api.mjs` | catalogue, live stock and tier prices vs the ERP's own `/api/sales/pricing` quote |
| `ecom-p4-api.mjs` | price comparison across pharmacies for one global product |
| `ecom-p5-api.mjs` | carts: one per pharmacy, re-priced on read, short and unsellable lines |
| `ecom-p6-api.mjs` | **checkout**: order → invoice → movement → ledger → Sales Activity → notification; cart closed; refusals (not approved, under minimum, another customer's cart, cancel twice) |
| `ecom-p6-race.mjs` | **two customers, the last units, fired together**: one order, one invoice, one debit; the loser's cart survives |
| `ecom-p12-coupons.mjs` | **coupons**: writing them and the refusals when writing; the eight refusals when spending; the percentage cap; the discount exact on the invoice; per-customer and total limits; the use released when an order is cancelled |
| `p12-ui.mjs` | a pharmacy writes a coupon on the Promotions screen, a customer types it in the cart, and the figure shown at checkout is the figure charged |
| `ecom-p13-points.mjs` | **loyalty points**: nothing works before a rule exists; the refusals when writing one (no exchange rate, a 150% ceiling); earning on arrival and never twice; the balance capping a redemption and the rule's ceiling capping an over-ask; the discount exact on the invoice; the points returned when an order is cancelled; the statement both sides read; an adjustment by hand, its required reason and its overdraw refusal; a stopped scheme leaving balances alone — and it stops any scheme an earlier run left running |
| `p13-ui.mjs` | both browsers: the Promotions screen's second tab writing a scheme (and refusing one with no exchange rate), the cart saying what an order will earn, the checkout offer moving the total, the figure shown equalling the figure charged, the customer's own statement, and staff reading the same ledger on the customer's record before moving it by hand |
| `ecom-p10-tracking.mjs` | **tracking and proof**: positions only from the assigned driver and only while out, a too-vague reading refused, the customer given one point and never a trail, proof by photo/name/note with the photograph actually served |
| `ecom-p10-sync.mjs` | an invoice moved from **Sales Activity** carries the customer's order with it — including the cancellation reason — and never drags it backwards |
| `p10-ui.mjs` | both browsers: the driver's Share-location toggle, the customer watching the distance close, the handover dialog, and the photograph on the customer's order |
| `ecom-p9-delivery.mjs` | **the delivery charge**: none/flat/distance/free-above, an unpinned address, the fee on the invoice and split out in the ledger, and the refusals for a bad policy — the branch's own settings are restored at the end |
| `p9-ui.mjs` | both browsers: the cart's charge, the checkout re-quoting when the address changes, the figure shown equalling the figure charged, and the driver's map pin and tap-to-call |
| `ecom-p8-board.mjs` | **the branch board**: the whole state machine, each move against the ERP document, refusals (wrong move, no driver, a non-driver, cancel after dispatch), branch scoping and who may call it |
| `erp-p8-ui.mjs` | the board in Chrome: chips, filters, drawer, dispatch dialog, cancel dialog, phone width |
| `platform-p8-ui.mjs` | the customer's order screen following the pharmacy, status by status |
| `erp-p7-cancel.mjs` | **cancelling a sale**: stock back to the lot, journals reversed to nil, revenue unchanged; refusals for paid, part-paid, dispatched and already-cancelled documents; the Cancelled chip and the Source filter |
| `ecom-p7-cancel.mjs` | a customer calls their own order off → the pharmacy's invoice is cancelled with it, in one transaction |
| `erp-p7-ui.mjs` | the Sales Activity board in Chrome: chips, Source filter, ONLINE badge, the cancel dialog, and the row afterwards |
| `erp-sale-golden.mjs` | **POS regression** (row 114): `--capture` on the old build, `--compare` on the new; MST header, DTL rows, movements, and the ledger's shape |
| `platform-p1..p6-ui.mjs` | the customer app in Chrome: sign-in, discovery, catalogue, compare, cart, checkout, order, cancel, phone width, console errors |

Row 115's "concurrent last-box checkout" is `ecom-p6-race.mjs`; it failed on first run and the
ERP defect it found is written up in `11 §6.2`.

**Test litter.** The suites create real customers, carts, orders and invoices in the dev
database. They are tagged in the data (`P6 …` names, `remarks='golden'`) and are confined to the
seeded PERF branches and branch 1; nothing is deleted automatically, because deleting posted
sales is exactly what the ERP must never do.

**Two things a suite must do, learned the hard way.**

*Suspend what you switch on.* A promotion left running decides the next run's numbers, so
`ecom-p13-points.mjs` and `p13-ui.mjs` stop every scheme they find before writing their own, and
stop their own at the end. Stopped, never deleted — points earned under a rule point at it.

*Keep a restore snapshot in a file, not in memory.* `ecom-p9-delivery.mjs` switches branch 1's
delivery policy and restores what it read at its own start. A run that died half way left the
branch on the test's last setting, and the next run faithfully "restored" that — which is how
branch 1 spent an afternoon on NONE when its staff had set FLAT ৳50, free above ৳100. The
snapshot now goes to `p9-branch-before.json`, is written only if absent, and is deleted only once
the restore has actually happened, so a crash is recoverable by running the suite again.

**The suites eat stock, and eventually finish it.** Each run buys real goods,
and after enough of them branch 1 was down to 474 pieces across 27 products —
a shelf worth about 95 taka, against suites that build 700- and 1,500-taka
baskets. Tests then fail for want of inventory rather than for want of
correctness, which is the least useful kind of red.

`pick-branch.mjs` answers "which pharmacy still has stock" from the database and
the stock-hungry suites (`ecom-p12`, `ecom-p13`) call it instead of hard-coding
branch 1. It prints what it picked, because a suite that quietly moves branch is
one whose output you cannot compare with yesterday's. Nothing is restocked and no
master data is touched: if every online pharmacy is empty, it says so and stops.

Two things that surfaced the moment the suites left branch 1, both of which are
the system working rather than bugs:

* **The order board is branch-scoped.** Acting as branch 1 on an order at branch
  902 gets `Order not found` — correct, and the suites now send
  `X-Acting-Branch` on staff calls.
* **Stock and delivery staff are not in the same place.** Branch 902 has 90,000
  pieces and no delivery men; branch 1 has three drivers and nothing to sell. The
  points suite now advances the invoice from **Sales Activity** where there is no
  driver — not a workaround, but the path Phase 10 made equivalent on purpose,
  and the one a pharmacy uses when a customer collects.

## 5. One thing the golden test had to stop comparing

It compared the *sum of the debits* on the sale's journals, and after Phase 8 that sum moved:
258.95 → 278.26. Nothing had regressed. Receivable and revenue were 148.00 exactly as before; the
difference was **cost of goods sold**, because the test consumes stock on every run, and by then
FEFO had emptied lot `SD0023M` (9.2458/pc) and reached `000086` (10.855/pc).

A figure that cannot be stable is worth nothing as an assertion, so the test now asserts what a
regression would actually move — four journal lines, debits equal to credits, and
`Accounts Receivable = Sales Revenue = AMOUNT_TOBE_PAID` — and *reports* COGS beside the cost of
the lots the sale actually took, which is the invariant that matters for it:

```
booked: Accounts Receivable=148.00/0.00 · Sales Revenue=0.00/148.00 · COGS 130.26 = lot cost 130.26
```
