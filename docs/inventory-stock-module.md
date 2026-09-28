# Branch-wise batch stock — what was built

Stock is held in **PCS**, converted from BOX and STRIP, scoped **per branch**, and
tracked **per batch**. All four transaction types move it.

Supersedes the design in `inventory-reporting-plan.md`, which proposed a SQL view.
A physical table was chosen instead so lots can carry their own state (active,
exhausted, blocked) and reads are O(1) rather than re-aggregating history.

---

## 1. The model

```
POS_TRANSACTION_MST / DTL     the documents — what was bought and sold, at what price
          │                    unchanged; still the commercial record
          ▼  on approval
    StockPostingService        decides direction from transaction type
          │
          ▼
      StockService             the only writer
        ├──▶ POS_STOCK_BATCH      the shelf: on hand per branch per lot, in pcs
        └──▶ POS_STOCK_MOVEMENT   append-only audit; the balance is derived from this
```

`POS_PRODUCT_INFO.STOCK_QTY` is deprecated — one column, not a table. It is no
longer written or read for stock. Nothing else was obsoleted.

### The conversion

```
pcs = quantity × NO_OF_PCS × sign(transaction_type)
```

`NO_OF_PCS` is on the detail line, frozen at entry, and is the factor for the unit
that line was written in — 12 for a BOX line, 10 for a STRIP line, 1 for PCS. One
expression, correct for every type and every unit.

The legacy `VW_PRODUCT_STOCK` instead hardcoded `NO_OF_LEAF × NO_OF_PCS` and applied
it only to purchases, assuming purchases are always in boxes and sales always in
pieces. That was true until someone bought a strip.

Freezing the factor also protects history: read live from Box/Leaf, a later
pack-size edit would silently re-value every batch received under the old packing.

### Direction

| Type | | Batch |
|---|---|---|
| `PU` purchase | **IN** | the batch the line names |
| `PR` purchase return | **OUT** | the batch the line names — never FEFO |
| `SL` sale | **OUT** | batch-wise FEFO, unless the line already names one |
| `SR` sales return | **IN** | the batch the sale consumed |

A purchase return goes back out of the exact delivery it came from. Returning
"whatever expires soonest" would send the supplier goods they never sent, at a
cost they never charged.

---

## 2. Batch-wise FEFO

The allocation unit is the **batch**, not the expiry date. Two deliveries sharing
an expiry stay separate lots with separate costs; the legacy procedure grouped on
expiry and merged them, destroying the per-batch cost.

Order: earliest expiry, then earliest receipt, then batch number — total and
repeatable, so a preview and the sale that follows walk the same lots. Lots with
no expiry sort last; an unknown expiry is not an imminent one.

Verified live against product 3, which holds three lots:

```
request 200 PCS
  → B-UI-001  exp 2027-08-21   120 pcs @ 13.928083   = 1671.37
  → 000011    exp 2027-08-22    80 pcs @ 14.094688   = 1127.58
  → B-001     exp 2027-12-31   untouched
                                 totalCost 2798.945
```

The two lots carry **different costs**, and `totalCost` is their weighted sum.
That is real batch-level COGS — the thing actual-profit reporting needs and a
single `STOCK_QTY` could never produce.

`15 BOX` returns the same split converted through the pack size: 180 pcs → 120 + 60.

---

## 3. Guarantees

**Idempotency.** A partial unique index on `(REF_TYPE, REF_DTL, MOVEMENT_TYPE)`
plus a check in `receive`, `issue` and `issueFromBatch`. Approving a document twice
moves stock once. A replayed posting rebuilds its allocation from the movement rows
rather than recomputing against stock that has since moved.

**Concurrency.** `lockAllocatableBatches` takes `PESSIMISTIC_WRITE` in allocation
order. Without it two concurrent sales both read the same availability, both pass
the check and both deduct. `CK_STOCK_QTY_POSITIVE` is the database backstop.

**Drafts are not stock.** Only `status='A'` posts. At migration time 10 of 14
transactions were drafts or pending; the old Java sum counted all of them.

**Auditability.** Movements are never updated or deleted — a correction is an
opposing row. `GET /api/inventory/reconcile` sums each lot's own movements and
compares to its stored balance. Currently clean.

**Branch isolation.** Branch comes from the JWT. `ROLE01` may pass an explicit
`branchId`; anyone else gets **404, not 403** — a 403 would confirm the branch
exists. Verified: manager requesting `branchId=99` gets 404.

---

## 4. What was replaced

`InventoryServiceImpl.calculateStock` was wrong on five axes at once:

1. accepted `branchId` and never used it — no branch isolation at all
2. no sign by transaction type — a sale *increased* stock
3. no unit conversion — 1 BOX counted as 1 PCS
4. no status filter — drafts counted as stock
5. N+1, called once per product inside a loop

Plus four `dtlRepo.findAll()` calls loading the whole detail table, two of them
back to back in one method. The dashboard's five counters are now one query.

`SalesServiceImpl` called `PROC_EXPIRY_WISE_SALES_QTY_DIS` through `SimpleJdbcCall`
— an Oracle procedure that does not exist in PostgreSQL, so sale creation had been
failing outright since the migration. That procedure also priced a BOX sale by
multiplying allocated **pieces** by the **box** price, inflating the line by the
whole pack size. Pricing is now per unit sold.

---

## 5. Files

**Migration** `V9__stock_batch_module.sql` — tables, sequences, indexes,
`NO_OF_PCS` backfill and `NOT NULL`, backfill from approved purchases.

**Entities** `PosStockBatch`, `PosStockMovement`
**Repositories** `PosStockBatchRepository`, `PosStockMovementRepository`
**Services** `StockService`/`Impl` (the only writer), `StockPostingService`/`Impl`
(documents → movements), `InventoryServiceImpl` (reads)
**Util** `StockUnitConverter` — 20 tests
**API** `InventoryController` — dashboard, stock, batches, expiry, alerts,
movements, fefo-preview, reconcile

141 tests pass.

---

## 6. Verified live

| Check | Result |
|---|---|
| Backfill from 4 approved invoices | 7 lots, every conversion exact |
| Draft purchase | moves no stock |
| Approve 5 BOX of product 7 | new lot `000024`, 60 pcs, shown as "5 box" |
| Movement row | `PURCHASE / IN / 60 / balance 60 / cost 2.640833` |
| Re-approve | refused |
| Reconciliation | clean |
| FEFO 200 pcs across 3 lots | 120 + 80, correct order, per-batch cost |
| Unconfigured BOX packing | `PACKING_NOT_CONFIGURED`, not a silent factor of 1 |
| Unknown unit | `UNKNOWN_STOCK_UNIT` |
| Manager → other branch | 404 |

---

## 7. Open items

**`STOCK_QTY` now diverges, by design.** Product 7 reads 120 there against 180 in
real batch stock. Any screen still showing that column is showing a stale number —
Product Master needs pointing at the new source.

**No daily ledger.** Opening balance is *derivable* — sum the movements before a
date, which works today — but it is not stored. That sum re-reads all history, so
monthly reports degrade as trading continues. `POS_STOCK_DAY_SNAPSHOT` (per branch
× product × batch × day: opening, in, out, closing, COGS) is the fix, and is best
built after sales are producing real movements. `MOVEMENT_DATE` is the business
date and `COST_PER_PCS` is on every movement precisely so that job can be added
without schema change.

**Purchase cancel** still refuses approved invoices and directs the user to a
purchase return — correct accounting behaviour, left as is.
`StockPostingService.reverse()` exists for when a cancel-approved flow is wanted.

**Purchase Return and Sales Return modules are not built.** The posting path
handles `PR` and `SR`, but nothing writes those documents yet.

**`branchName` is null** in batch stock responses — cosmetic, not joined.

---

## 8. Inventory reports

Seven tabs under Inventory, all reading `/api/inventory`. Branch comes from the
JWT; only `ROLE01` may look at another branch.

| Tab | Shows |
|---|---|
| Dashboard | value, pieces, products in stock, low, out, near-expiry, expired |
| Stock Ledger | per product — stock, batches, value, alert level, all six sale prices |
| Batch Stock | per lot — expiry, cost/pc, received, issued, all six sale prices |
| Stock Movement | every piece in and out, with the document behind it |
| Movement Summary | opening → purchased, pur. return, sold, sales return, adjusted → closing |
| Expiry | lots at or past expiry, windowed 0/30/60/90/180 days |
| Alerts | low stock and out of stock, side by side |

### All four document types

`GET /api/inventory/movement-summary?fromDate=&toDate=` returns, per product:
opening, purchased, purchaseReturned, sold, salesReturned, adjusted, closing,
purchaseValue and **salesCostValue** — the cost of the batches actually
consumed, which is the COGS half of actual profit.

`closingPcs` is read from the movements independently rather than inferred, and
each row is flagged `balanced` when the two agree. A mismatch surfaces as a
banner and a tinted row instead of being averaged away. Currently zero.

Sales and sales-return columns read zero until those modules write documents.
The report is built to receive them; no rewrite is needed when they arrive.

### Sale prices on every report

Each row carries a `prices` block: box, strip and piece, for regular and special
customers, plus the percentages they came from.

**MRP is apportioned to the unit before the percentage applies.** A strip of a
12-strip box starts at MRP÷12, not at MRP. Verified: product 3 at MRP 250/box →
strip 83.33, piece 20.83, then 14% and 9% off those respectively.

`SalesPriceCalculator.calculateAll` applies every percentage to the raw MRP,
which is right only for BOX. `unitPrices(...)` was added alongside it and is what
the reports and the sales path use. **The Sales Discount screen still calls
`calculateAll`**, so the strip and piece prices it previews are high — worth
fixing, and deliberately left alone here rather than changed underneath that
screen without asking.

The legacy view is no guide: it divides MRP down to a per-piece figure and hands
that back as the *box* price, which the FEFO procedure then multiplies by a piece
count. Two errors that partly cancel.

An unpriced unit renders as a dash, never as ৳ 0.00 — not priced and priced at
zero are different states.

### Verified in the browser

All seven tabs against the live API, no console errors. Dashboard ৳10,772 /
2,066 pcs matches the endpoint exactly. Batch stock shows "10 box", "4 strip",
"8 box" from piece counts. Stock ledger paginates 28 rows over 2 pages. Movement
ledger shows the test purchase `PU260800024` as `Purchase / IN / +60 / balance 60`
alongside the V9 backfill openings.

### Replaced

`src/services/inventoryService.js` called six endpoints that never existed
(`/summary`, `/stock/{id}`, `/low-stock`, `/out-of-stock`, `/expiring`,
`/adjust`) and was imported by nothing.

`src/pages/Inventory.jsx` ran entirely on `mockData.js` — it looked like a
working report and showed fabricated numbers.

---

## 9. Sales — FEFO invoicing priced by customer type

`CUSTOMER_TYPE` decides two separate things: **which units are sellable** and
**which price list applies**.

| Type | Units | Price list |
|---|---|---|
| Wholesaler | BOX, STRIP, PCS | regular |
| Special | BOX, STRIP, PCS | special |
| Retailer | **PCS only** | **regular** — the wholesaler's piece rate |

The retailer row is the one that looks wrong until you know the rule: the
restriction is on the unit, not the price list. A retailer's piece price equals
the wholesaler's by design.

An unrecognised or missing type resolves to **Retailer** — the most restrictive
tier. Failing open would hand wholesale box pricing to a customer whose terms
nobody recorded.

### `GET /api/sales/pricing?customerId=&productId=&qty=&unit=`

Returns **every** unit, including ones the customer cannot have, each with
`allowed` and a `reason` — so the UI can explain a missing option rather than
silently dropping it. Also returns branch stock, the max whole units available,
and the batches FEFO would consume.

Verified live for product 3 (MRP 250/box, 12 pcs/box, 4 pcs/strip):

| Tier | BOX | STRIP | PCS |
|---|---|---|---|
| Wholesaler | 222.50 | 71.66 | 18.96 |
| Special | 190.00 | 67.50 | 18.12 |
| Retailer | *blocked* | *blocked* | **18.96** |

### Enforcement is server-side

Hiding a tile is convenience, not control. `POST /api/sales` re-validates the
tier and refuses the line however the request was constructed:

```
Retailer + BOX → 422 UNIT_NOT_SELLABLE
"'Napa 500mg Tablet' cannot be sold to Rahim Medical Store in BOX.
 Retailer customers can only buy in PCS."
```

A line whose tier has no configured price is refused too, rather than falling
back to MRP — quietly charging list price because a percentage was missing is
worse than refusing the sale.

### One invoice line per batch

A sale of 11 BOX where the earliest lot holds 10 produces two lines:

```
B-UI-001  10 BOX  @ 222.50 = 2225.00    cost/pc 13.928083
000011     1 BOX  @ 222.50 =  222.50    cost/pc 14.094688
                              2447.50   COGS    1840.51
```

FEFO drained the earliest-expiry lot to zero (status → `X` exhausted), spilled
the remainder into the next, and left the latest-expiry lot untouched. Each line
carries the batch it dispensed — which pharmacy invoicing requires, and which the
sales return path needs to put stock back in the right lot at the right cost.

### Bugs found by driving the UI

Three that unit tests could not have caught:

- **`useToast()` returns a tuple `[fire, node]`, not an object.** Destructured as
  an object, `showToast` was `undefined` — and because the catch block calls it
  too, a *successful* save threw twice and silently skipped the reset. The
  invoice persisted; the screen just never cleared.
- **`/api/customers` returns `type`, not `customerType`.** Every customer
  rendered as "Retailer", so nobody could sell a box.
- **`listSales` passed `null` for the customer name**, and the list read
  `totalAmount`/`amountToBePaid` where the API sends `subtotal`/`amountTobePaid`
  — so every row showed a blank customer and ৳ 0.00.

### Files

`CustomerPricing` (+17 tests), `SalesPricingResponse`, `SalesServiceImpl`
(`getPricing`, tier validation, `listSales` names), `SalesController`
(`/api/sales/pricing`), `V10__customer_type_pricing.sql` (constraint, index, one
customer per tier), `src/services/salesService.js`, `src/pages/Sales.jsx`.

`SalesRequest.branchId` is no longer `@NotNull` — branch comes from the JWT, and
requiring it asked the client to state which branch's stock to consume.

158 tests pass. Reconciliation clean after three sales across three tiers.

---

## 10. Invoice editing, box/strip everywhere, actual profit

### Editing an unconfirmed invoice

`PUT /api/sales/{id}` — allowed only at stage **Submitted**. Past that the goods
are on their way and the stock movement is a fact; the correction is a sales
return, and the API says so:

```
"Invoice SL260803995 is at stage 'Confirmed' and can no longer be edited.
 Raise a sales return to correct it."
```

Lines are replaced wholesale, not diffed. The previous allocation is **reversed
first** — every consumed piece goes back to the lot it came from — before the
new lines are allocated. Reversing after validating would make the stock this
very invoice is holding look unavailable, refusing an edit that only shuffles
quantities.

Verified: 5 BOX → 2 BOX returned lot `000011` to 55 and `B-001` to 120, then
took 24 pieces. Reconciliation clean.

The Invoices list carries an Edit button, disabled with the reason on anything
past Submitted.

### Box and strip on every stock report

Stock ledger, batch stock, movement ledger, movement summary, expiry and the
low-stock alert all now show pieces with the box and strip equivalents beneath,
derived from the packing frozen on each lot. A dash means the product has no
packing for that unit — not zero.

A low-stock alert reading "10 pcs" when the shelf holds most of a box is
technically right and operationally useless; the person restocking counts boxes.

### Actual profit

`GET /api/inventory/profit?fromDate=&toDate=` — revenue as invoiced against the
cost of the batches actually consumed, paired line by line through the movement
log. Sales returns net off both sides. Margin is against **revenue, not cost**:
100 cost sold at 150 is a 33% margin, not 50%.

Rows carry `soldBelowCost`, and the summary carries `belowCostCount`, so a
loss-making product is visible rather than buried inside a positive total. It
immediately found one: **Ceevit 250mg sells at ৳30.60/box against a landed cost
of ৳31.69** — a real pricing fault in master data, not a reporting artefact.

### Three bugs found by testing

**Fractional boxes.** Allocating 5 boxes from a lot of 55 pieces took all 55,
producing a line of "4.58 BOX" and stranding `0.0004` pieces, because
`round(55/12, 4) × 12` is 54.9996. The allocator now works in **whole units**: a
lot contributes only as many complete boxes as it physically holds — four boxes
here, with the fifth coming from the next lot and seven loose pieces left
behind. Availability is counted the same way, lot by lot: two lots of 11 pieces
hold 22 pieces and *zero* boxes of twelve.

**Phantom losses after an edit.** Editing deletes the old detail lines but the
original `SALE` movements remain, pointing at rows that no longer exist. An
outer join counted their cost against no revenue and invented a ৳843 loss —
Napa reported as sold below cost while actually running a 23% margin. The join
is now inner: a sale movement with no surviving line belongs to no invoice and
contributes neither side.

**Lines vanishing on edit.** A "skip the first run" guard on the
customer-changed effect was consumed by the mount pass, so the effect fired
again when the loaded invoice set its customer and wiped the lines that had just
arrived. Now it compares against the previous value instead of counting renders.

Also: a `--` comment containing an apostrophe inside a native query. Spring Data
tokenises the string without stripping SQL comments, reads the apostrophe as an
unterminated quote, and fails the whole repository at startup. No `--` comments
in `@Query` strings.

158 tests pass.

---

## 11. Actual buy price on every inventory report

Every stock report now carries the landed cost per **box, strip and piece**.

### What the number is

The **landed** cost of the batch: total invoice value ÷ pieces received, so it
already carries trade price, IGTA and any line discount. Per box and per strip
are that piece cost scaled by the packing frozen on the lot.

It is deliberately **not** `POS_PRODUCT_INFO.BUY_PRICE`, which is the product's
current list cost and says nothing about what a particular delivery was billed
at. Two lots of the same product routinely differ — which is the whole reason
COGS is computed per batch.

```
batch 000011   ৳14.0947/pc  ×4 = ৳56.38/strip   ×12 = ৳169.14/box
batch B-002    ৳66.5330/pc  ×10 = ৳665.33/strip ×100 = ৳6,653.30/box
```

### Per-lot versus averaged

| Report | Basis |
|---|---|
| Batch stock, expiry, movement ledger | one lot's own cost |
| Stock ledger, low-stock alerts | weighted average across lots on hand |
| Movement summary | average of what was purchased in the period |
| Profit | the rate actually realised on what was sold |

An averaged figure is flagged `averaged: true` and labelled "avg" in the UI. An
average across two lots is a different number from either lot's cost, and
presenting it as a fact is how people end up quoting a price no delivery was
ever billed at.

A unit with no packing configured returns **null**, rendered as a dash — never
৳ 0.00, which would read as "this was free" rather than "nobody recorded it".

### Why it earns its column

On the Profit tab the buy price sits directly beside revenue and margin, so the
comparison is on one line:

```
Ceevit 250mg   Buy ৳31.69/box   sells at ৳30.60/box   margin −0.66%
```

That is the product priced below cost, now visible as a cause rather than just a
red total.

158 tests pass.

---

## 12. Company / batch / SKU search on every report

One filter bar above the tabs, applying to all seven reports.

### The controls

| Control | Matches |
|---|---|
| Search box | product **name**, **SKU**, **and batch number** |
| Company | manufacturer, from `POS_PRODUCT_INFO.PID_COMPANY` |
| Batch | exact or partial batch reference |

The search box spanning all three is deliberate: someone holding a strip with a
number printed on it can type that number without first working out whether it
is a SKU or a batch. Company and Batch narrow (AND); the search term widens
(name OR sku OR batch).

### Filters persist across tabs

Having narrowed to one manufacturer on Batch Stock, you almost always want the
same narrowing on Movement or Profit to see why. State lives above the tabs, so
switching keeps it. Verified: `search=Napa` + Beximco →

```
Stock Ledger 2   Batch Stock 3   Movement 16
Summary 2        Profit 1        Expiry 0 (empty state)   Alerts 0
```

### How it is wired

Company, SKU, name and category are **product** attributes; batch belongs to the
**stock**. So the product-side filters resolve once into an id set and every
report narrows on that set, rather than seven queries each re-joining to the
product table in their own slightly different way.

Two details that matter:

**An empty id set is not the same as no filter.** A nullable collection cannot
express that difference, and Hibernate will not bind a null `IN` list, so a
`filterProducts` boolean carries it. Without it, "matched nothing" would render
as "matched everything".

**Short-circuiting on a product miss alone breaks batch search.** No product is
named "B-002", so the scope comes back empty — and an early return meant the
query that *would* have matched the batch never ran. `nothingToShow()` skips the
query only when the product set is empty *and* there is no free text that could
still match a lot.

### Lookups

`GET /api/inventory/lookups` returns only companies and batch references that
actually hold stock in the branch. Offering a manufacturer whose every lot is
exhausted produces an empty report and a puzzled user.

### A bug worth recording

The debounced search fired `onChange({ ...filter, search: text })`, spreading
the `filter` captured **when the timer was set**. Picking a company within those
300ms reverted it — the company silently un-picked itself a moment after being
chosen. Reproduced by typing and selecting in the same tick. Now a functional
update (`prev => ({ ...prev, search: text })`), so the debounce only ever
touches its own field.

158 tests pass.

---

## 13. Purchase invoice: company name and landed cost — and a costing bug

### Company name

Was already in the API response and simply never rendered. Now shown under the
product code on each line.

### Buy price per box / strip / pcs

New `cost` block on each purchase line, same shape the inventory reports use, so
a lot costed on the invoice reads identically on Batch Stock.

Packing comes from the product's Box/Leaf, **not** the line's `NO_OF_PCS` — that
field is the factor for the unit *transacted in*, so on a STRIP purchase it
would produce a strip cost labelled as a box.

### The bug this surfaced

Asked whether the 2% invoice discount was included, it was not — anywhere.

`COST_PER_PCS` was the **line** total over pieces. A line total carries trade
price, IGTA and the *line* discount; an invoice-level discount is applied to the
header. So every lot bought on a discounted invoice was valued above what was
paid, and every margin measured against it understated by that percentage.

```
PU260800026 at 2%
  stored    58.24 / 24 pcs = 2.426667
  correct  (58.24 − 1.16) / 24 = 2.378333
```

The legacy `VW_PRODUCT_STOCK` had it right all along:
`(TOTAL_PRICE − TOTAL_PRICE × INVOICE_DIS_PCT/100) / QUANTITY`.

**Fixed at the source** — `StockPostingServiceImpl.netLineTotal` apportions the
header discount before deriving the unit cost, so newly posted stock is right,
and the purchase view uses the same function rather than a parallel calculation
that could drift.

**`V11__landed_cost_net_of_invoice_discount.sql`** restates what was already
stored: 5 batches, 4 purchase movements, 7 opening rows, 2 outbound movements.
Outbound rows move with their lot — a sale costed against a price never paid
reports a margin that was never earned. Quantities untouched; only valuation was
ever wrong.

Effect on the period: gross profit ৳808 → **৳985**, margin 19.49% → **23.21%**.

### The check that proves the apportionment

Per-line shares must sum back to the header figure, or value leaks into
rounding:

```
gross  58.24 + 665.33 = 723.57   invoice total
net    57.08 + 652.02 = 709.10   amount to be paid
diff                     14.47   stated invoice discount
```

Pinned in `LandedCostTest` along with the zero, null and 100% cases.

164 tests pass. Reconciliation clean.

---

## 14. Sales product picker — focus loss, unit-aware costs, company filter

### The caret jumping out of the quantity field

The picker held one `loading` flag, and the render read:

```jsx
{productId && loading && <div>Loading…</div>}
{productId && !loading && ... && <>…quantity input…</>}
```

Every keystroke set `loading = true`, which **unmounted the branch containing
the quantity input** and remounted it when the reply landed. React destroyed and
recreated the element, so the browser dropped focus — the caret left the field
mid-number, and backspace did the same thing.

Fixed by splitting the flag in two:

- `firstLoad` — blocks the pane, only when opening a product for the first time
- `refreshing` — a quiet line of text; the form stays mounted throughout

Also debounced at 250ms, so a three-digit quantity asks the server once rather
than three times with replies racing each other.

Verified: typing `1 → 12 → 123`, then backspacing, leaves
`document.activeElement` on the field — including across the async response
landing, which is when it used to fail.

### Costs shown in the unit being sold

The allocation table read `Cost/pc` regardless of the selected unit. It now
follows the selection — `Cost/box` at ৳28.54 with `৳2.38/pc` beside it, and the
quantity column likewise. A box price next to a piece count invites multiplying
the two, which is exactly the mistake the legacy FEFO procedure made.

### Unit selection

The active tile carries a solid underline as well as the fill. A fill alone
reads as "hovered" more than "chosen", and the underline survives a
colour-blind reading.

### Company

Shown under each product in the list — the manufacturer is what distinguishes
two products sharing a generic name, which in a pharmacy catalogue is most of
them. A company dropdown filters the list (28 → 7 for Renata), and the search
box matches company as well as name and SKU.

164 tests pass.

---

## 15. Multi-product add, and the wheel stepping quantities

### Mouse wheel silently changing quantities

A focused `<input type="number">` treats wheel events as increment/decrement.
Scrolling the page with the pointer over a quantity changed it — and on this
form that means shipping a different amount of stock than anyone chose. Worse,
the field is usually still focused from typing, which is exactly when a scroll
is most likely.

Fixed with a `NumberInput` wrapper applied to **every** number field on the page
— quantity, line discount, invoice discount, delivery charge, given amount. All
five had the same hazard; only quantity was reported.

The listener is registered manually with `{ passive: false }`, because React
attaches `onWheel` passively at the root where `preventDefault()` is ignored.
Preventing rather than blurring keeps the caret in place, which the picker
depends on after the focus fix in §14.

Verified: three wheel events on a focused quantity leave the value unchanged,
and the same holds for all three rail fields.

### Adding several products in one pass

The picker closed after each item, so a counter sale of five products meant five
rounds of reopen, re-filter, re-find. It now stays open: the line goes onto the
invoice immediately, the product selection clears, and the search and company
filter are kept — the next item is usually from the same shelf.

A running list inside the modal shows what the session has added, because the
invoice table is behind the overlay and unreadable. The close button reads
**Done** rather than Cancel once anything has been added — the lines are already
on the invoice and the button does not undo them, so calling it Cancel would be
a lie.

Verified: Ceevit, Napa and Ibucap added in one pass; modal reported "3 items
added", and all three landed on the invoice with a correct ৳11.29 subtotal.

The parent's `addLine` had to stop calling `setPicker(false)` — it was closing
the modal from outside regardless of what the picker wanted.

164 tests pass.

---

## 16. One quantity control across the application

`QtyInput` in `components/UI.jsx` — **− / + buttons and mouse-wheel stepping** —
replaces every ad-hoc quantity field. Counter staff adjust quantities
constantly, and reaching for the keyboard to turn a 2 into a 3 is the slow path.

### Where it is used

| Page | Field |
|---|---|
| Sales | picker quantity (capped at what the branch can supply) |
| Purchase | line quantity |
| Requisition | box quantity — list row and approval modal |
| AI Copilot | order quantity on a selected recommendation |

### The wheel steps only while the field is focused

This is the one judgement call worth recording. A number input steps on wheel
natively whether or not it is focused, so scrolling a long invoice with the
pointer passing over a quantity silently rewrites it — and someone ships the
wrong amount of stock without ever touching that field.

Requiring focus makes it deliberate: you clicked into the field, then scrolled
it. Verified both halves — unfocused wheel is ignored, focused wheel steps ±1.

The handler is attached with `{ passive: false }` because React registers
`onWheel` passively at the root, where `preventDefault()` is ignored;
preventing also stops the page scrolling underneath at the same time.

### Money and percentages deliberately do not step

Discounts, delivery charge and given amount keep the wheel suppressed. A
delivery charge has no natural "one more" — nudging one by scrolling past it is
an accident, not an adjustment. Only quantities got the stepper.

### Details

- Native spinners are hidden; two sets of controls side by side look broken.
- The buttons are `tabIndex={-1}`, so Tab still moves between fields rather
  than stopping at every button on the way through a line.
- `−` disables at `min`, `+` at `max`. On the sales picker `max` is the branch's
  real availability, so `+` stops at 26 rather than running on into a shortfall
  banner.
- Step precision is rounded to the step's own decimals, so a 0.1 step never
  surfaces `0.30000000000000004` in a field someone has to read.

Verified live on Sales (1→2→3, wheel→4, −→3, capped at 26), Purchase lines, and
the AI panel with its dark-card styling.

164 tests pass.

---

## 17. The modal's stray horizontal scrollbar

### Cause

`.modal` set only `overflow-y: auto`. Per CSS, when one axis is non-`visible`
the other computes from `visible` to **`auto`** — so `overflow-x` silently
became `auto` too. Once the dialog was tall enough for a vertical scrollbar,
that bar took ~15px off the content box, and anything sized to the full width
then overflowed horizontally.

It only shows where scrollbars **reserve space**. Windows Chrome does; the
headless browser used for testing uses overlay bars and reserves none, which is
why the first few measurements came back clean and the fault had to be
reproduced by forcing classic scrollbars.

### Fixes

`overflow-x: hidden` stated explicitly on `.modal`, plus `scrollbar-gutter:
stable` so the layout does not jump sideways when the vertical bar appears
mid-interaction.

Two contributors found while measuring:

- **`.picker-head` did not wrap.** The tier badge was pushed past the right
  edge on a narrow dialog, dragging the scrollbar with it — 40px of overflow at
  400px wide. Now wraps, with `min-width: 0` so the product name shrinks rather
  than holding the row open.
- **`.table-wrap` had the same single-axis mistake** (`overflow-x` only), which
  makes `overflow-y` compute to `auto` and can put a stray vertical bar inside a
  table that needs none.

The same audit found `.picker-items` and `.picker-added-list` setting only
`overflow-y`; both now state both axes.

### Why hiding is safe here

Genuinely wide content scrolls in its own container. Verified at a 360px
modal: the allocation table is 255px inside a 31px window and scrolls there,
staying within the dialog. Nothing is clipped that cannot be reached.

Swept 819 → 360px: no horizontal overflow above 400px, where before it began at
the first vertical scrollbar.

---

## 18. Sales picker lists only stocked products, with quantities

The picker listed the whole active catalogue, so choosing a product meant
waiting for a pricing round-trip only to be told there was none of it. 28
products offered, 5 sellable.

It now reads the **stock ledger** rather than the product catalogue:
branch-scoped, already carrying the quantity, and filtered to what is actually
on the shelf.

```
Ibucap 400mg                  Square Pharmaceuticals   SKU010     30 box
Ceevit 250mg                  Renata Limited           SKU004     2 box 2 pcs
UI Test Tablet 250mg          Incepta Pharmaceuticals  UITEST250  10 pcs
Napa 500mg Tablet (Updated)   Beximco Pharmaceuticals  NAPA500    10 box 1 strip 3 pcs
Napa                          Beximco Pharmaceuticals  500        1 box 4 strip
```

Stock reads in shelf terms — "2 box 2 pcs", not "26" — from the packing frozen
on each lot.

### Backend

`inStockOnly` on `StockFilter` and `/api/inventory/stock`. It defaults to
**false**, because the ledger is also a report, and a report that hides the
zeroes cannot tell you what has run out. Only the picker asks for it.

The filter reuses the rollup already loaded for the page — that map is keyed by
product and excludes empty lots, so it is the authority and no second query is
needed.

`ProductStockRow.companyName` was declared from the start and never populated,
so every consumer showing a company got a blank. Now filled from a per-instance
name cache: a page of 25 products is 25 lookups otherwise, and a pharmacy
catalogue has far more products than manufacturers.

### The bug in the middle

Passing `inStockOnly: true` did nothing at first — `filterParams()` copies a
fixed list of five keys and silently dropped it. Booleans need their own line
there, since the loop skips anything falsy. Caught by the list still showing 28
rows with "0 pcs" against most of them.

164 tests pass.

---

## 19. Invoice discount on the selling side too

Asked whether the invoice-level discount reached the **sale** price the way §13
made it reach the buy price. It did not — the same fault, pointing the other
way.

`revenue` came from each sale line's own `TOTAL_PRICE`, which carries the unit
price and the *line* discount but not the header one. So revenue was overstated
by the invoice percentage, and margin with it.

No sale in the data had ever carried a header discount, so the fault was latent.
Reproduced by raising one: 2 pcs at ৳2.94 with 10% off the invoice, charged
৳5.29.

```
Ceevit revenue   reported  ৳504.33     (lines before the header discount)
                 charged   ৳503.74     (what the customer paid)
```

### Fixed

The profit query joins the sale's own master and nets each line's share of
`INVOICE_DIS_PCT`, exactly as the cost side does. Revenue now reads ৳503.74.

Both sides of the margin are now netted the same way. Netting only one moves the
margin on its own: selling for 100 what cost 80 with 10% off reads 11.11% when
netted and 20% when not — nearly double, on a sale that never happened at that
price. `LandedCostTest` pins that case.

### Scope

Only the profit report computed revenue, so that was the only place to fix.
Movement summary reports cost, not revenue, and was unaffected. The purchase
side was already corrected in §13 and `V11`.

No migration needed: revenue is derived at read time from the invoice, unlike
`COST_PER_PCS`, which is stored on the lot and had to be restated.

166 tests pass.

---

## 20. Sales% : buy price, below-cost alert — and a 100× pricing error

### The blocking find

The page priced **every unit off the raw MRP**. Ace 500mg, a box of 100 at
৳400, showed its PCS price as **৳364.00**. A single tablet should be ৳3.64.

This had been flagged twice before as a discrepancy against the sales path but
never fixed, because nothing depended on it. Adding a buy-price comparison made
it blocking: a ৳364 "piece price" would never flag against a ৳4 piece cost, so
the requested alert could not work until the arithmetic was right.

`unitPrices()` — already used by the sales and inventory paths — now backs this
page too. MRP is apportioned to the unit before the percentage:

```
Ace 500mg, box of 100, MRP ৳400
  BOX    ৳400.00 less 11%  = ৳356.00      unchanged, MRP is the box price
  PCS    ৳4.00   less 9%   = ৳3.64        was ৳364.00
```

Sales Discount, the sales picker and the inventory reports now all derive prices
the same way, so the three agree.

### Buy price

A column beside MRP showing landed cost per box, strip and piece — the weighted
average across the lots on hand in the branch, resolved for the whole page in
one query. Blank where the product holds no stock; a zero would read as "free"
rather than "nothing bought yet".

### Below-cost alert

A row tints **clay** when any of its six configured prices sits at or below what
that unit cost, with the offending price marked `▼` so it is clear which of the
six is the problem. Both carry a tooltip naming the cost.

Two deliberate choices:

- **Warning, never a block.** A pharmacy discounts below cost on purpose — to
  clear stock that is about to expire. The row is flagged so the decision is
  visible, not prevented.
- **Unsaved edits keep their amber.** That state is about to change and is the
  more urgent thing to see; below-cost is a standing condition underneath it.
  The `▼` still shows on the cell, so the warning is never lost.

The cell warning recomputes locally while typing, so it tracks the number being
entered rather than the last one the server saw. Verified: typing 35% on a
product costing ৳28.54/box shows ৳23.40 ▼ immediately, before saving.

### Verified

Ceevit at 30% → row tinted, box price ৳25.20 ▼ against a ৳28.54 cost, row flag
`belowCost: true`. Restored to 10% afterwards; at that rate it sells at ৳32.40
and correctly does not flag.

166 tests pass.
