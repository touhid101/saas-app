# 11 — POS integration (existing behaviour)

Phase 0 · how the POS document is created today and the only safe way for ECOM to create one.

## 1. Two "POS" code paths — one is live, one is dead

| Path | Controller | Service | Status |
|---|---|---|---|
| **Sales / Quick Sales** | `POST /api/sales` (`SalesController`) | `SalesServiceImpl.createSale` | **live** — used by `Sales.jsx` and `QuickSales.jsx` (`salesService.createSale`) |
| Cart POS | `/api/v1/pos/**` (`PosController`) | `PosServiceImpl` (+ `POS_CART`, `POS_CART_ITEM`) | **dead** — no frontend caller (`posService.js` targets a non-existent `/api/pos` and is imported nowhere); `finalise` → `InventoryServiceImpl.createTransactionFromCart` throws `UnsupportedOperationException`; prices come from `getMrpBox/Strip/Pcs` ignoring tier and percentages; accepts `unitPriceOverride` from the client |

**Decision for ECOM: reuse `SalesServiceImpl`; do not touch `PosServiceImpl` / `POS_CART`.**
Retiring the dead path is an ERP housekeeping item outside this project's scope.

## 2. `POS_TRANSACTION_MST` — what `createSale` writes

| Column | Value |
|---|---|
| `PID_ORG`, `PID_COMPANY` | from the staff session (`SecurityUtils`) |
| `PID_BRANCH` | `BranchScopeService.resolve(req.branchId)` |
| `PID_SUP_CUST` | `req.customerId` (validated active `POS_CUSTOMER_INFO`) |
| `TRANSACTION_TYPE` | `SL` |
| `TRANSACTION_DATE` | `req.transactionDate` |
| `INVOICE_NO` | `SL` + `yyMM` + 5 random digits (**no uniqueness guarantee** — a collision is possible, ~1 in 10⁵ per month per org; ECOM should not rely on it as its order number) |
| `PAYMENT_TYPE`, `GIVEN_AMOUNT`, `DELIVERY_ADDRESS`(200), `DELIVERY_CHARGE`, `INVOICE_DIS_PCT`, `REMARKS`(100) | from request |
| `DELIVERY_MAN`, `DELIVERY_MAN_NAME` | when `deliveryManId` given (validated) |
| `STAGE` | `Submitted` → `Collected` if paid → `Delivered` if `counterSale` |
| `STATUS` | `Y` |
| `FROM_SOURCE` | `API` (VARCHAR 15, free text) — **the natural place for `ECOM`** |
| `TOTAL_AMOUNT`, `INVOICE_DIS_AMT`, `AMOUNT_TOBE_PAID`, `PAYMENT_STATUS` | computed by `applyInvoiceTotals` |
| `ADVANCE_AMOUNT` | 0 (NOT NULL default) |
| not set | `CHALLAN_NO`, `TOTAL_TAX`, `SERVICE_CHARGE`, `CASHBACK_AMOUNT`, `AREA_NAME`, `RECEIVED/DELIVERED/PAID_*`, `MERGE_*`, `PID_EMPLOYEE` |

## 3. `POS_TRANSACTION_DTL` — one row per batch consumed

| Column | Value |
|---|---|
| `PID_TRAN_MST`, `PID_PRODUCT` | |
| `BATCH_NO`, `EXPIRY_DATE` | from the FEFO allocation line |
| `SALES_UNIT`, `SALES_TYPE` | the requested unit (BOX/STRIP/PCS) |
| `NO_OF_PCS` | pieces per unit at time of sale |
| `QUANTITY` | allocated pcs ÷ `NO_OF_PCS` (4 dp) |
| `MRP_PRICE` | product MRP (box) |
| `SALES_PRICE` | tier unit price |
| `TOTAL_PRICE` | `SALES_PRICE × QUANTITY` minus `FLAT_DISCOUNT` |
| `DISCOUNT_PCT`, `FLAT_DISCOUNT` | from the discount rule (or higher client pct) |
| `LINE_KIND` | default `PURCHASE` (column shared with purchase docs), `UNIT_MODE`, `BONUS_QTY`, `RETURN_QTY`, `PID_TRAN_DTL_RETURN` for returns |

## 4. Transaction lifecycle & stock integration

`createSale` is one `@Transactional` unit: validate → MST insert → per line (discount, FEFO
preview, tier price, DTL rows) → `StockPostingService.post(mstId)` (locks lots, decrements, writes
`POS_STOCK_MOVEMENT` with `REF_TYPE/REF_MST/REF_DTL`) → totals → `AccountingHooks.saleCreated`.
Any failure rolls back everything, including the ledger. See `01-api-dependency-map.md` §1.

## 5. How ECOM must create the POS document (design)

```
EcomCheckoutService.placeOrder(customerJwt, cartId, addressId, couponCode?)      @Transactional
  1. load global customer (APPROVED), cart (OPEN, branch B), address
  2. ensure shadow POS_CUSTOMER_INFO row for (customer, B)  → pidCustomer         (04 §5)
  3. build SalesRequest{ customerId=pidCustomer, branchId=B, lines=[{productId, qty, unit}], deliveryAddress, deliveryCharge, paymentType=COD, remarks="ECOM <orderNo>" }
     — NO discountPct, NO invoiceDiscPct, NO counterSale, NO deliveryManId, NO givenAmount
  4. run under a *service-level* branch context for B (not a staff session):
       either an internal overload createSale(req, SaleOrigin.ECOM, branchId, orgId, companyId)
       or a scoped ActingBranch-style context set by the ECOM service — must be designed so that
       BranchScopeService.resolve() returns B without a staff principal, and FROM_SOURCE = 'ECOM'
  5. insert ECOM_ORDER (+ items, snapshots, PID_TRAN_MST = new mst id, ECOM_STATUS = NEW), status history, audit
  6. coupon usage / points earn (Phase 12–13) in the same transaction
  7. notify branch staff (POS_NOTIFICATION, existing service)
```

Step 4 is the **only ERP code change** this integration requires: `createSale` currently reads
org/company from `SecurityUtils` and branch from `BranchScopeService`, and hard-codes
`FROM_SOURCE='API'`. The extension is additive (a new overload / parameter object; the existing
signature keeps behaving identically for staff callers) and must be covered by
`SaleBranchScopeTest`-style tests so a storefront call can never write into another branch.

## 6. Regression surface to protect (Phase 7 tests)

* `POST /api/sales` from Sales / Quick Sales: identical MST/DTL/movement/ledger output before and
  after (golden-file comparison on a seeded product).
* Sales Activity list, stage counts, delivery summary unchanged for `FROM_SOURCE='API'` rows.
* Sales Return against an ECOM-originated sale works unchanged (it only needs MST/DTL).
* Reports (`ReportBranchScopeTest`) unchanged; ECOM rows appear in branch sales totals exactly like
  counter sales — **BUSINESS CONFIRMATION REQUIRED** if they should be separable (they can be,
  via `FROM_SOURCE`).

## 6. As built — Phase 6

### 6.1 The one extension to `createSale`

```java
public record SaleContext(Long branchId, Long orgId, Long companyId, String source) { … }

@Transactional public TransactionResponse createSale(SalesRequest req)                  // unchanged callers
@Transactional public TransactionResponse createSale(SalesRequest req, SaleContext ctx)  // ECOM
```

The old signature now resolves the branch, org and company exactly as before and delegates with
`SOURCE_API`. Nothing else in the method changed: the ECOM path differs only in *where the four
context values come from*, because an online order has no staff session to read them from. The
request the marketplace builds carries product, quantity and unit only — no prices, no discounts,
no batch — so the ERP's own pricing and FEFO decide everything that ends up on the invoice.

**Regression proof (`scratchpad/erp-sale-golden.mjs`).** A staff sale was captured on a build
*without* the change and compared against the same sale on the build *with* it — the MST columns
the till sets, the DTL rows, the stock movements read from `POS_STOCK_MOVEMENT.REF_MST`, and the
`ACC_JOURNAL` lines:

```
IDENTICAL — the staff sale writes exactly what it wrote before the change.
 header: {"transactionType":"SL","stage":"Submitted","paymentStatus":"Unpaid","paymentType":"Cash",
          "branchId":1,"customerId":1,"amountTobePaid":148,…,"invoiceNoPrefix":"SL","invoiceNoLength":11}
 product 23 | lines 1 | movements ['SALE|O|12.0000|TRANSACTION'] | ledger ['4|258.95|258.95']
```

It was re-run after the stock fix in §6.2 and still prints IDENTICAL.

### 6.2 An ERP defect this channel forced into the open: lost updates on a lot

Taking stock out of a lot was read-check-write:

```java
if (lot.getQtyPcs() < qty) throw …;      // read
lot.setQtyPcs(lot.getQtyPcs() - qty);    // write an absolute value
```

Two sales that both read a lot of 60 pcs both passed the check and both wrote 0. **120 pieces left
a lot that held 60**, and nothing said so — the first concurrency test of the checkout reproduced
it on the first attempt (two orders, two invoices, one shelf). It was never an e-commerce bug:
two counters selling the same batch in the same second would do the same. The marketplace only
makes that ordinary rather than freak.

A row lock is not enough on its own, either. The sale checks availability before it posts, so the
lot is already in the persistence context at its old quantity, and Hibernate returns that cached
instance from the locked query — the stale figure is what gets written back.

**Fix** (`PosStockBatchRepository.deductIfAvailable`, used by `StockServiceImpl.issueFromBatch`
and the FEFO `issue`): the check and the subtraction are one conditional `UPDATE`. PostgreSQL
serialises the two statements on the row and re-evaluates `qty_pcs >= :qty` against the committed
value, so the second matches nothing, returns 0, and the caller refuses with
`INSUFFICIENT_BATCH_STOCK` / `INSUFFICIENT_STOCK` instead of inventing stock. The entity is
refreshed afterwards, so the refusal quotes the balance that is actually on the shelf.

Re-run: `A: 201 ECOM-2609-001005 · C: 422 INSUFFICIENT_BATCH_STOCK · orders +1 · invoices +1 ·
stock debited once`, and the staff-sale golden comparison unchanged.

### 6.3 What the ECOM order writes beside the POS document

`ECOM_ORDER` (V105) keeps the customer's side: order number, ECOM status, the delivery address
**as a snapshot** (label, recipient, phone, lines, area, city, postcode — an address edited later
does not rewrite history), payment method (`COD`), subtotal / discount / delivery charge / grand
total, the customer note, and `PID_TRAN_MST` under a partial unique index so one invoice can
never carry two orders. `ECOM_ORDER_ITEM` snapshots name, generic, company, unit, pcs per unit,
unit price, line total and the prescription flag, so an order renders years later without the
live product rows. Money is still the ledger's: nothing here is posted.

## 7. As built — Phase 7 (POS integration polish)

### 7.1 Cancellation, once, in the sales service

`cancelSale(id, reason)` for staff, `cancelSale(id, reason, actor)` for the marketplace — the
second argument is the only difference, because an online cancellation has a customer behind it
rather than a session. Order of work matters and is deliberate: **stock first**, then the ledger,
then the document. A lot that will not take its pieces back (`STOCK_ALREADY_CONSUMED`) aborts the
transaction before anything is marked, so there is no state where the invoice says cancelled and
the shelf disagrees.

V106 adds `CANCELLED_BY`, `CANCELLED_DATE`, `CANCEL_REASON` to `POS_TRANSACTION_MST` — the same
shape as the `RECEIVED_/DELIVERED_/PAID_` pairs already there. `REMARKS` is left alone: it holds
what the counter typed when the sale was made, and the merge-split path had already shown what
happens when a cancellation writes into a field that is full (see `SalesMergeServiceImpl`).

### 7.2 What a cancelled sale stops counting towards

A reversal in the ledger and a row still counted as revenue would contradict each other. Every
sales aggregate that reads `POS_TRANSACTION_MST` now excludes `STATUS = 'N'`:

| Where | Query |
|---|---|
| `PosTransactionMstRepository` | `countSalesByStage`, `deliveryPerformance`, `customerOutstanding`, `sumAmountByOrgTypeDate`, and the activity list (unless the Cancelled filter asks) |
| `SalesPurchaseReportRepository` | `salesByProduct`, `salesByCustomer`, `salesByCompany`, `salesByCategory`, `collectionSummary`, `customerDueTotals` |
| `SalesPerformanceRepository` | `actualsByPerson`, `monthlyTotals` |
| `DashboardAnalyticsRepository` | already filtered `COALESCE(STATUS,'Y') <> 'N'` — untouched |

`TransactionResponse.due` is zero on a void document for the same reason: the amount it was once
worth is history, not debt, and a screen printing it as due invites somebody to chase an invoice
nobody has to pay.

### 7.3 The board knows where an order came from

`GET /api/sales-activity?source=ECOM|API`, plus `source`, `ecomOrderNo`, `cancelledBy`,
`cancelledDate` and `cancelReason` on every row. The order number is resolved for a whole page in
one query via `SaleSourceReferences` (ERP-side interface, e-commerce-side implementation
`EcomSaleReferences`), so `SalesServiceImpl` still imports nothing from the marketplace.

On the screen: a **Cancelled** chip beside the six stages, a **Source** filter, an `ONLINE` badge
carrying the `ECOM-…` number under the invoice number, a **Cancel** action that opens a dialog for
the reason, and a cancelled row that shows that reason, owes nothing, and offers no further action.

### 7.4 Verified

`scratchpad/erp-p7-cancel.mjs` (API), `ecom-p7-cancel.mjs` (the customer's own cancellation),
`erp-p7-ui.mjs` (the board in Chrome), and the golden staff-sale comparison re-run afterwards:

```
stock back: 436 → 448 (was 448 before the sale) · movements: SALE|O|12.0000 , ADJUST|I|12.0000
ledger now: 8 lines, dr 517.90, cr 517.90 · net movement on this invoice: 0.00
invoice lines kept: 1 · billed total at the branch unchanged
cancel again → 409 SALE_ALREADY_CANCELLED
part-paid (Submitted, 10 paid) → 409 SALE_ALREADY_PAID, nothing reversed
dispatched (On The Way) → 409 SALE_TOO_LATE_TO_CANCEL, its stock stayed out
IDENTICAL — the staff sale writes exactly what it wrote before the change.
```

### 7.5 Still outstanding in this area

* `receive()` still reads-modifies-writes a lot when a second delivery lands in an existing batch
  (`11 §6.2` fixed the outbound paths only). Two purchase approvals for the same batch at the same
  instant would lose one of the two quantities. Rare, and on the purchase path rather than this
  project's, so it is recorded here rather than changed under an e-commerce phase.
* A `CHECK` constraint on `FROM_SOURCE` is still a candidate, after a backfill validation (`02 §4`).
