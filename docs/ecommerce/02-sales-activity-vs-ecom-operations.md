# 02 — Sales Activity vs E-Commerce Order Operations

Phase 0 · analysis only.

## 1. What Sales Activity is today

| Layer | Artefact | Facts |
|---|---|---|
| Controller | `SalesActivityController` (package-private class at the bottom of `SalesController.java`), base `/api/sales-activity` | 9 endpoints: list, stage-counts, summary, delivery-performance, collect, advance, assign-delivery-man, bulk-assign-delivery-man, cancel |
| Service | `SalesServiceImpl` (same bean as sale creation) | `listActivity`, `stageCounts`, `deliverySummary`, `deliveryPerformance`, `collect`, `advanceStage`, `bulkAssignDeliveryMan`, merge cascade |
| Repository | `PosTransactionMstRepository` | `listActivity(org, stage, payStatus, branch, customer, dm, unassigned, from, to, q, pageable)`, `countByStage`, `deliveryPerformance` — all filtered in SQL |
| Entity | `PosTransactionMst` (+ `PosTransactionDtl`) | no order entity of its own; **an order is a sale** (`TRANSACTION_TYPE='SL'`) |
| DTO | `TransactionResponse`, `DeliverySummaryResponse`, `DeliveryPerformanceResponse` | |
| Frontend | `src/pages/SalesActivity.jsx` (MOD06), `src/services/salesActivityService.js` | stage chips, filters, per-row Next-action button (`NEXT_LABEL`), assign / bulk-assign modal, collect modal, CSV export. `Delivery.jsx` (MOD09) and `Collection.jsx` (MOD10) sit on the same rows |

### Status lifecycle (as coded)

```
STAGES = Submitted → Confirmed → Delivery Assigned → On The Way → Delivered → Collected
```

* Set at creation: `Submitted`; `Collected` immediately when fully paid; `Delivered` when
  `counterSale=true` and money is still owed.
* `advanceStage` accepts **any** stage in the list from **any** current stage — the sequence is
  advisory (`NEXT_LABEL` in the UI), not enforced server-side.
* `Collected` sets `PAYMENT_STATUS='Paid'`; `collect()` moves to `Collected` when the balance
  reaches zero.
* `Cancelled` is **not** in `STAGES`; `POST /{id}/cancel` therefore throws
  `ValidationException` ("'Cancelled' is not an order stage"). No cancellation path releases stock.
* Editing is only possible at `Submitted` (`updateSale`, supervisor unlock). After that the
  correction is a sales return (`SalesReturnController`).

### Branch filtering

`BranchScopeService.resolve(branchId, allBranches)`: acting-branch header (platform operator) ▸
session branch ▸ `ROLE01` may name another branch of the same org ▸ `allBranches=true` only for
`ROLE01`. Non-admins asking for another branch get 404. Counts and lists are consistent
(stage-counts is counted across the branch, not the page).

### Delivery assignment

`DeliveryManService.requireDeliveryMan(employeeId)`: `HR_EMPLOYEE` row, same org, `EMP_TYPE`
delivery, active. Assignment writes `DELIVERY_MAN` + `DELIVERY_MAN_NAME` on the MST.
Drivers (`ROLE06`) may only move their own orders and only to `On The Way` / `Delivered`.

### Order actions

confirm (advance) · assign / bulk-assign DM · dispatch (advance) · delivered (advance) · collect
(amount + paymentType, posts `AccountingHooks.saleCollected`) · cancel (broken, see above) · merge
/ split (`SalesMergeController`) · return (`SalesReturnController`).

### POS integration

Sales Activity *is* the POS document. There is no second order table: everything it lists is
`POS_TRANSACTION_MST` with `TRANSACTION_TYPE='SL'`, and the lines it shows are the batch-level
`POS_TRANSACTION_DTL` rows that moved stock.

## 2. Can Sales Activity carry E-Commerce orders? — checklist from §32 of the brief

| Requirement | Sales Activity today | Verdict |
|---|---|---|
| ECOM order source | `FROM_SOURCE VARCHAR(15)` exists on MST (`'API'` written by `createSale`). No filter, no chip, no index. | extendable |
| Branch | MST.PID_BRANCH from session; list scoped by `BranchScopeService` | fits as-is (order branch = fulfilment branch) |
| Customer | MST.PID_SUP_CUST → `POS_CUSTOMER_INFO` (branch-owned, `PID_BRANCH NOT NULL`) | **conflict** — a global customer has no row in `POS_CUSTOMER_INFO`; the MST FK target is the branch customer |
| Customer type | read live from the customer row at creation; **not snapshotted** on MST | gap — historical orders would reprice if the type changes (RULE: historical orders never change) |
| Order lines | one DTL row per batch; no "what the customer asked for" line; no UOM conversion snapshot beyond `NO_OF_PCS` | gap — a 3-BOX request can become two DTL rows; the storefront line (qty, unit, quoted price, coupon share) has nowhere to live |
| UOM | `SALES_UNIT`, `NO_OF_PCS` on DTL | fits |
| POS transaction | it *is* the POS transaction | fits |
| Delivery | DM assignment, stages, collection | fits for staff; no customer-visible tracking, no address geo, no proof of delivery |
| ECOM statuses | `New / Confirmed / Preparing / Ready / On The Way / Delivered / Cancelled / Returned` needed; ERP has `Submitted / Confirmed / Delivery Assigned / On The Way / Delivered / Collected` | **partial** — `Preparing`, `Ready`, `Cancelled` (working), `Returned` are missing; adding them to `STAGES` changes the meaning of every ERP stage filter, chip and report (`deliverySummary` iterates `STAGES`) |
| Payment | `PAYMENT_TYPE`, `PAYMENT_STATUS`, `GIVEN_AMOUNT`; COD/cash model | fits for COD; no online-payment reference |
| Coupon / offer / points | nothing on MST/DTL (`INVOICE_DIS_PCT/AMT`, `TOTAL_DISCOUNT` only) | gap — coupon code, coupon amount, points earned/redeemed need a home |
| External reference | none | gap — `ECOM-…` order number vs `SL2609xxxxx` invoice number |
| Cancellation with stock release | broken | gap for both ERP and ECOM |

## 3. Decision

**Sales Activity can safely support ECOM orders — with extension, not duplication —
provided the ECOM-specific state lives in ECOM tables that *point at* the POS transaction, and the
POS transaction is created only by `SalesServiceImpl.createSale` (or a thin wrapper around it).**

Concretely (design only, Phase 1+):

1. **Extend, do not fork, the page.** `SalesActivity.jsx` gains a *Source* filter/chip
   (`FROM_SOURCE = 'ECOM'`), an order-number column and a link to the ECOM order detail. Stage
   semantics for ERP rows are untouched.
2. **Keep the ERP stage list as the ERP stage list.** ECOM statuses (`NEW, CONFIRMED, PREPARING,
   READY, ON_THE_WAY, DELIVERED, CANCELLED, RETURNED`) are a column on the ECOM order record and are
   mapped *to* ERP stages at defined points (see `10-order-lifecycle.md`). Adding `Preparing`/`Ready`
   into `SalesServiceImpl.STAGES` is the one thing that would silently change ERP behaviour
   (`deliverySummary`, `stageCounts`, `DeliverySummaryResponse`, the Delivery page and reports all
   iterate that list) — so it is not done.
3. **Dedicated "E-Commerce Orders" board is REQUIRED as well** — not as a second sales engine but
   as the *operational* view of ECOM-only state the ERP rows cannot show: customer (global), address
   and map pin, ECOM status, coupon/points, payment reference, customer messages, proof of delivery,
   partial fulfilment / cancellation reasons. It reads ECOM tables + the linked POS transaction and
   calls the **same** `SalesServiceImpl` actions (advance / assign / collect) for anything that
   touches stock or money. Branch-aware by construction: `BranchScopeService.resolve` is reused.
4. **Cancellation** must be implemented once, in `SalesServiceImpl`, as "reverse posting + stage
   `Cancelled` + accounting reversal" (the reverse primitive already exists:
   `StockPostingService.reverse(mstId, reason)` used by `updateSale`). That is an ERP fix that the
   ECOM flow depends on; it is flagged for Phase 7/8, with **BUSINESS CONFIRMATION REQUIRED** on
   whether a `Cancelled` sale should also appear in ERP stage counts/reports.

## 4. What must NOT be done

* No new table that duplicates `POS_TRANSACTION_MST/DTL` for ECOM orders' financial lines.
* No direct `INSERT` into MST/DTL from an ECOM service (bypasses FEFO lock, DTL fan-out, ledger).
* No change to the meaning of existing stages; no renaming `Submitted`.
* No `FROM_SOURCE` values other than a new constant (`ECOM`) — the column is 15 chars, free text
  today; a CHECK constraint is a Phase-7 candidate only after backfill validation.
* No cross-branch "all ECOM orders" screen for branch staff (memory rule: every screen is the
  session branch; `allBranches` stays ROLE01-only).

## 5. Built in Phase 7

Points 1, 2 and 4 of §3 are done; point 3 (the dedicated E-Commerce Orders board) is Phase 8.

**Cancellation (§3.4) exists now.** `SalesServiceImpl.cancelSale(id, reason[, actor])`: reverse the
stock posting, reverse the journals (`AccountingHooks.saleCancelled` → `engine.reverseSource`),
then mark the document — `STAGE = 'Cancelled'`, `STATUS = 'N'`, and V106's `CANCELLED_BY`,
`CANCELLED_DATE`, `CANCEL_REASON`. One transaction: a lot that cannot take its pieces back rolls
the whole thing over, and nothing is marked cancelled that is not.

It refuses in two places, and says which:

| Situation | Code | Why |
|---|---|---|
| stage beyond `Delivery Assigned` | `SALE_TOO_LATE_TO_CANCEL` | the goods have left the branch; what comes back is a sales return |
| anything paid (`GIVEN_AMOUNT` or a delivery collection) | `SALE_ALREADY_PAID` | the money has to go back, and a refund is somebody's deliberate act |
| already cancelled | `SALE_ALREADY_CANCELLED` | |
| a merge wrapper, or an order inside one | `MERGE_NOT_CANCELLABLE` / `ORDER_MERGED` | split the merge first, or the two halves describe different deliveries |

**`Cancelled` is still not in `STAGES`** (§3.2 holds). A cancelled document leaves the stage
machine rather than joining it: `STATUS = 'N'` is what splitting a merge has always written to the
wrapper it voids, and what the dashboards already read as void. So nothing about the existing six
stages changed.

**The business question in §3.4 is answered by the code, not by a guess:** a cancelled sale is
excluded from stage counts, from the delivery summary's money, and from every sales aggregate that
reads `POS_TRANSACTION_MST` — its journals were reversed, so counting it would bill for goods that
went back on the shelf. It stays visible: the board's **Cancelled** chip lists it with its reason,
and the invoice list shows it as before, because a cancelled invoice still has a number somebody
may be searching for. **BUSINESS CONFIRMATION** remains open only on whether a *cancelled* sale
should appear in any printed statutory report; today it does not.

**Source is on the board (§3.1).** `FROM_SOURCE` is filterable (`?source=ECOM|API`), the row
carries an `ONLINE` badge with the `ECOM-…` number beside the invoice number, and the number is
resolved for a whole page in one query through `SaleSourceReferences` — an interface on the ERP
side that the e-commerce module implements, so the sale path still knows nothing about ECOM tables.
