# 05 — Pricing engine (existing behaviour)

Phase 0 · documents what the ERP does today. Nothing here is proposed; the proposal is one line:
**reuse this engine exactly, through `SalesServiceImpl.getPricing` / `createSale`, and never
re-implement any of it in the storefront.**

## 1. Inputs

| Input | Where | Notes |
|---|---|---|
| MRP | `POS_PRODUCT_INFO.MRP_PRICE` | **the price of one BOX** (`PosProductInfo.getMrpBox()`); per branch product |
| Packing | `PID_BOX → POS_BOX_INFO.NO_OF_LEAF`, `POS_BOX_INFO.PID_LEAF → POS_LEAF_INFO.NO_OF_PCS` | `pcsPerStrip = NO_OF_PCS`, `pcsPerBox = NO_OF_LEAF × NO_OF_PCS`; missing ⇒ that unit is **unpriced**, never assumed 1 |
| Six percentages | `SALES_PER` (box), `SALES_PER_LEAF` (strip), `SALES_PER_PCS` (pcs), `SALES_PER_SPC` (special box), `SALES_PER_SPC_LEAF`, `SALES_PER_SPC_RET` (special pcs) | maintained on the *Sales Discount %* screen (`/api/sales-percentages`, MOD08); a null means "unpriced" on that screen |
| Customer tier | `POS_CUSTOMER_INFO.CUSTOMER_TYPE` → `CustomerPricing.tierOf()` | prefix match: `WHOLESALE*` → WHOLESALER, `SPECIAL*` → SPECIAL, `RETAIL*`/null/anything else → RETAILER |
| Discount% rule | `POS_SALES_DISCOUNT` (org, company, product, `DISCOUNT_TIER` = BOX / STRIP / PCS / SPECIAL, `DISCOUNT_PCT`, `IS_ACTIVE`) | resolved product ▸ company ▸ org for the **unit** being sold |
| Branch | session branch (`BranchScopeService`) | the product row is already branch-specific, so "branch-aware price" is implicit: two branches selling the same medicine have two product rows, two MRPs, two sets of percentages |

## 2. Formula (`SalesPriceCalculator`, pure, unit-tested)

```
boxMrp   = MRP_PRICE
stripMrp = boxMrp × pcsPerStrip ÷ pcsPerBox        (null when packing missing)
pcsMrp   = boxMrp × 1           ÷ pcsPerBox        (null when packing missing)

salePrice(unitMrp, pct) = round2( unitMrp − unitMrp × pct ÷ 100 )
```

Two readings of a **null percentage**:

* `unitPrices(...)` (maintenance screens): null% ⇒ unit **unpriced** (price = null).
* `unitPricesForSale(...)` (the till and therefore the storefront): null% ⇒ **0 %**, i.e. sell at
  the unit's share of MRP — *unless* MRP itself is null/≤ 0, in which case every unit is unpriced
  ("nothing to discount from").

## 3. Tier rule (`CustomerPricing`)

```
WHOLESALER  BOX, STRIP, PCS   regular prices  (boxPrice / stripPrice / pcsPrice)
SPECIAL     BOX, STRIP, PCS   special prices  (specialBox / specialStrip / specialPcs)
RETAILER    PCS only          regular PCS price (the wholesaler piece rate)
```

The tier decides **which units are sellable** and **which price column applies** — separately.
`CustomerPricing.priceFor(tier, unit, prices)` returns null when the unit is not allowed or not
priced, and `unavailableReason()` names the missing input (tier rule / packing / MRP / percentage)
so the UI can say why.

## 4. Discount on top (`POS_SALES_DISCOUNT`)

`SalesDiscountService.resolveEffectiveDiscount(org, product, company, unit)` → first match by
priority product > company > org for `DISCOUNT_TIER = unit`; else 0. Applied per DTL row:
`FLAT_DISCOUNT = TOTAL_PRICE × pct ÷ 100`, `TOTAL_PRICE −= FLAT_DISCOUNT`, `DISCOUNT_PCT` stored.
Note: the `SPECIAL` discount tier exists in the table but the sale path looks up by **unit**
(`line.getSalesUnit()`), so `SPECIAL` rows are never matched by `createSale` — a dormant feature.

Also on the request: `line.discountPct` (used when *higher* than the rule) and
`invoiceDiscPct` (invoice-level) — both client-supplied. Storefront must not pass them.

## 5. Invoice totals (`TransactionCalculator`)

```
subtotal        = Σ DTL.TOTAL_PRICE (after line discounts)
invoiceDiscAmt  = subtotal × invoiceDiscPct ÷ 100
amountTobePaid  = subtotal − invoiceDiscAmt + deliveryCharge
due             = amountTobePaid − givenAmount
paymentStatus   = Paid | Partial | Unpaid
```

**No tax** is applied on a sale (`TOTAL_TAX` column exists, is never set by `createSale`).
VAT is part of *purchase* cost (`ProductPricingCalculator`: TP/IGTA/BUY from MRP, conversion
rate 25.037 %, VAT 17.40 %). **TAX POLICY CONFIRMATION REQUIRED** before showing any tax line to
customers.

## 6. Where it is exposed

* `GET /api/sales/pricing?customerId&productId[&qty&unit][&branchId]` → `SalesPricingResponse`
  with per-unit `{allowed, price, mrp, discountPct, pcsPerUnit, maxQty, reason}` + live stock +
  FEFO preview. Consumed by `Sales.jsx` / `QuickSales.jsx` via `salesService.getPricing`.
* `POST /api/sales` recomputes everything server-side; the client's quoted price is never read.
* `SalesPercentServiceImpl` list/search with "unpriced" filters for maintenance.

## 7. Worked example (from `SalesPriceCalculatorTest` style)

MRP 120 (box), 10 strips × 10 pcs. Percentages: box 12, strip 10, pcs 8, special 15/12/10.

| Unit | unit MRP | Wholesaler | Special | Retailer |
|---|---|---|---|---|
| BOX | 120.00 | 105.60 | 102.00 | — (not allowed) |
| STRIP | 12.00 | 10.80 | 10.56 | — |
| PCS | 1.20 | 1.10 | 1.08 | **1.10** |

## 8. Consequences for e-commerce (design constraints, not changes)

1. Price is a function of `(branch product, tier, unit)`; the storefront must show prices **per
   pharmacy**, never one price per medicine.
2. A customer's tier comes from the **global** customer (`ECOM_CUSTOMER.CUSTOMER_TYPE`), copied
   onto the branch shadow row so `createSale` reads the same tier the storefront quoted.
3. Guests (not logged in) can only be quoted as RETAILER (most restrictive) — or no price at all,
   per §50 of the brief (**BUSINESS CONFIRMATION REQUIRED**: show retail price to guests?).
4. Snapshot on `ECOM_ORDER_ITEM`: `MRP_SNAPSHOT`, `UNIT_PRICE_SNAPSHOT`, `DISCOUNT_PCT_SNAPSHOT`,
   `PCS_PER_UNIT_SNAPSHOT`; the DTL rows already freeze `MRP_PRICE`, `SALES_PRICE`, `DISCOUNT_PCT`.
5. Coupon/offer/points are **order-level ECOM amounts** layered on top; they must not be fed back
   into `invoiceDiscPct` blindly — see `15-promotion-engine.md` for the two options (invoice
   discount vs. separate settlement line) that need a business decision.

## Ordering, manufacturers, and what the storefront draws from them

Added alongside the existing search rather than beside it — same endpoint, same response, two more
things in it.

### The `sort` parameter

`GET /api/ecom/public/catalogue?sort=` takes `new`, `price-asc`, `price-desc` or `discount`.
Omitted, the order is unchanged: in stock, then nearest pharmacy, then name — the order a customer
scans in.

Two properties hold for all of them:

- **Every order leads with stock.** A cheapest-first list headed by things nobody can buy is a list
  of prices, not of offers, so `inStock` is the first key in every comparator and the chosen order
  is the second.
- **The order is applied across everything that matched, before paging.** This is the point of
  moving it server-side: the listing page and the home rails used to reorder the two dozen rows
  they had already loaded, so "biggest savings" meant the biggest saving *on that page* while the
  real one sat on page two. `Listing.jsx` and the home rails now ask the server and render what
  comes back.

`new` sorts on `Item.addedAt` (`BaseEntity.getCreatedAt()`, i.e. `INSERTED_DATE`) — when the
pharmacy put the product on its books. Unknown dates sort last rather than first, so a row with no
timestamp never poses as the newest thing on the shelf.

### The `company` filter and the manufacturer facet

`?company=` narrows to one manufacturer, and every response now carries a `companies` facet counted
the same way as `categories` and `pharmacies` — from the rows that matched, before paging.

The filter is applied in Java over the already-capped match set rather than in the repository query,
so the facet counts what the customer can actually reach, and `searchForMarketplace` — a query that
several screens depend on — was left alone.

One consequence worth stating, because it shapes the UI: when `company` is set, the `companies`
facet naturally collapses to that one manufacturer. The listing sidebar therefore loads its
manufacturer list from a **separate one-row request without the filter**, so choosing a maker
narrows the results without collapsing the list you chose from, and arriving on a maker's own link
(`/medicines?maker=…`) still shows the others with a way back.

### Verified

`scratchpad/ecom-p16-offers.mjs` — 17 checks, all passing against a live backend:

- `price-asc` is genuinely ascending and `price-desc` descending, and the two disagree about the
  first row, so the ordering demonstrably reached the server (৳0.39 vs ৳220.00).
- `new` is newest-first on real timestamps; `discount` is deepest-first.
- No order places an out-of-stock row above an in-stock one, across all four.
- The manufacturer facet's count equals what its filter returns (facet said 17 for Beximco
  Pharmaceuticals, the filter returned 17), and filtering yields that maker and no other.

Regression: `ecom-p3-api`, `ecom-p4-api`, `ecom-p5-api`, `ecom-p6-api` and `ecom-p12-coupons` all
behave as before — the four-argument `search(...)` shape was kept as an overload delegating to the
new one, so no existing caller changed.
