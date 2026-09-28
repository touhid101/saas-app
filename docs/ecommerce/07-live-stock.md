# 07 — Live stock (existing behaviour)

Phase 0 · documents the ERP as it is.

## 1. Source of truth

`PHARMA.POS_STOCK_BATCH` — one row per **(branch, product, batch number)**
(`uq_stock_batch_lot`): `QTY_PCS` (on hand), `RECEIVED_PCS`, `ISSUED_PCS`, `EXPIRY_DATE`,
`COST_PER_PCS`, `MRP_PRICE` (as received), `PCS_PER_BOX`, `PCS_PER_STRIP` (packing at receipt),
`RECEIVED_DATE`, `SOURCE_TRAN_MST/DTL`, `STATUS` ∈ `Y` active · `X` exhausted · `B` blocked
(recalled/quarantined: present, counted, never issued).

Every change is journaled in `POS_STOCK_MOVEMENT` (`MOVEMENT_TYPE`, `DIRECTION` I/O, `QTY_PCS`,
`BALANCE_AFTER`, `REF_TYPE/REF_MST/REF_DTL`, `MOVEMENT_DATE`). `POS_PRODUCT_INFO.STOCK_QTY` is
legacy; `GET /api/inventory/reconcile` (ROLE01) compares the two.

## 2. Definitions as coded (`StockServiceImpl`, `PosStockBatchRepository`)

| Term | Definition |
|---|---|
| **Live / available stock (pcs)** | `availablePcs(branch, product)` = Σ `QTY_PCS` where `STATUS='Y'` **and** `QTY_PCS > 0` **and** (`EXPIRY_DATE IS NULL` or `EXPIRY_DATE ≥ today`) |
| **Available whole units** | per-lot `floor(QTY_PCS ÷ unitSize)` summed over allocatable lots (see `06`) |
| **Allocatable lots (FEFO order)** | same filter, ordered `expiry NULLS LAST, expiry ASC, receivedDate ASC, batchNo ASC` — total and repeatable, so preview and issue walk the same sequence |
| **Reserved stock** | **does not exist**. There is no reservation, hold or soft-allocation; stock leaves the batch the moment `createSale` commits |
| **Expired stock** | present, excluded from availability; `GET /api/inventory/expiry` lists it |
| **Blocked stock** | `STATUS='B'`, excluded |
| **Branch** | every read and write takes `branchId`; `requireBranch` refuses null |

## 3. Write path and concurrency

* `StockService.issue(IssueCommand)` → `lockAllocatableBatches` (`@Lock(PESSIMISTIC_WRITE)`,
  `SELECT … FOR UPDATE` in FEFO order) → decrement lot by lot → movement rows. Two concurrent sales
  of the last box serialise on the row lock; the second sees the reduced balance and fails with
  `InsufficientStockException` inside its own transaction → nothing partially written.
* `issueFromBatch` (used by `StockPostingService.post` for DTL rows that already name a batch)
  locks the named lot.
* `reverseByReference(refType, refMst, …)` puts pieces back — used by `updateSale` (edit at
  `Submitted`) and by returns. **Not** used by cancel (which is broken anyway).
* `receive(ReceiveCommand)` on purchase approval creates/merges lots.

## 4. Read APIs

| Endpoint | What it returns |
|---|---|
| `GET /api/inventory/stock` | per product: on-hand pcs + box/strip breakdown, value, alerts; branch from session |
| `GET /api/inventory/batches` | per lot |
| `GET /api/inventory/fefo-preview?productId&qty&unit` | which lots a request would consume |
| `GET /api/sales/pricing` | `availablePcs`, `availableDisplay`, `units[].maxQty` — the storefront-shaped answer |
| `GET /api/inventory/alerts/{low-stock,out-of-stock,slow-moving}` | thresholds from `POS_PRODUCT_INFO.ALERT_STOCK` etc. |

All are staff-authenticated and branch-scoped; there is no public or cross-branch stock view.

## 5. Consequences for e-commerce

1. **Browse stock is informational.** The product card shows `availableWholeUnits` per unit at
   render time; the number may be stale a second later. Text must say "in stock" / "only N left",
   not promise.
2. **Checkout must re-read live stock inside the same transaction that takes it.** Two safe
   designs exist; the choice is a Phase 6 decision:
   * **A — issue at checkout (recommended for COD, simplest, no new mechanism):** checkout calls
     `SalesServiceImpl.createSale` → stock leaves the shelf immediately under the existing row
     lock; a later cancellation runs the (to-be-fixed) reverse path. Oversell is impossible by
     construction. Downside: unconfirmed online orders hold physical stock until cancelled;
     `CartCleanupScheduler`-style auto-cancel after N hours would be required (**BUSINESS
     CONFIRMATION REQUIRED**: hold window).
   * **B — reserve, then issue on confirmation:** new `ECOM_STOCK_RESERVATION` rows; every
     availability read subtracts active reservations; branch staff "Confirm" creates the POS sale.
     Needs changes to `availablePcs`/`preview`/`assertAvailable` **or** a wrapper that
     subtracts reservations — the former touches ERP behaviour, the latter risks the two paths
     disagreeing. Higher complexity; only justified if the business wants online orders reviewed
     before stock moves.
3. **Cross-branch availability** (price comparison) is N calls to the same branch-scoped read,
   one per eligible pharmacy that carries the global product — a read model / cache per
   `(branch, product)` with short TTL is a Phase 4 performance item; the ERP tables stay the
   only truth.
4. Never expose batch numbers, costs or expiry dates to customers; expose only availability by
   unit. (Expiry-aware messaging such as "short-dated" is a **BUSINESS CONFIRMATION** item.)

## 5. Resolved in Phase 6 — design A, and the locking it exposed

**Design A is what was built:** checkout creates the POS sale immediately, so stock leaves the
shelf when the customer presses *Place order* and an online order is a real invoice from the
first second. No `ECOM_STOCK_RESERVATION` table exists and no ERP availability read was altered.
The hold-window auto-cancel of design A is not needed while NEW orders already hold real stock;
what an unconfirmed order should do after N hours remains a **BUSINESS CONFIRMATION** item, now
about cancellation policy rather than about reservations.

The first concurrency test of that design found that the ERP could oversell a lot: the deduction
was a read, a check and a write of an absolute value, so two sales that read 60 pcs both wrote 0.
See `11 §6.2` — the arithmetic now happens in one conditional `UPDATE`, and the second caller is
refused rather than served from stock that is not there. Two customers placing the last units at
the same instant now produce exactly one order, one invoice and one debit.

Pessimistic row locks remain in `lockAllocatableBatches` and still do useful work (they order the
walk under contention); they are no longer what stands between the shelf and a double sale.
