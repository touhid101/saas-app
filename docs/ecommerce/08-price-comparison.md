# 08 — Price comparison across pharmacies

Phase 0 · existing state + the constraints any design must respect.

## 1. What exists

**Nothing customer-facing.** There is no endpoint that answers "who sells medicine X and at what
price". What does exist and can be composed:

| Building block | Where | Limitation |
|---|---|---|
| Cross-branch product identity | `POS_PRODUCT_INFO.PID_GLOBAL_PRODUCT` → `GLOBAL_PRODUCT` (`uq_branch_global_product`: one branch row per catalogue entry) | nullable — 76 of 108 dev products are linked; pharmacy-typed products have no global id |
| Per-branch price | `SalesServiceImpl.getPricing` (tier × unit) | takes an ERP `customerId` and resolves branch from the staff session |
| Per-branch stock | `StockService.availableWholeUnits` | branch-scoped |
| Branch list | `ALL_BRANCH` | no coordinates, no ECOM flag, no public read |
| Generic / strength / company | `POS_PRODUCT_INFO.PID_GENERIC`, `PRODUCT_NAME`, `PID_COMPANY`; `GLOBAL_PRODUCT.STRENGTH` | usable for a fuzzy "similar products" fallback, not for exact comparison |

## 2. Constraints from the ERP that shape the design

1. **Comparison key = `PID_GLOBAL_PRODUCT`.** Only catalogue-linked branch products can be
   compared exactly. Encouraging pharmacies to import from the catalogue (existing
   `BranchCatalogueController`) is the operational lever; a "link existing product to catalogue"
   staff action is a Phase 3/4 candidate (ERP-side, additive).
2. **Price is per (branch product, tier, unit).** A comparison table is therefore per unit and
   per customer tier; a retailer compares PCS prices only.
3. **Stock is per branch**; "cheapest" must be qualified by "in stock in the unit you want".
4. **Distance** needs `ALL_BRANCH.LATITUDE/LONGITUDE` (new, Phase 2) and the customer's chosen
   delivery address / device location.
5. **Tenant isolation**: the comparison read crosses organisations (each pharmacy is its own
   org). It must be served by a **platform-level read service** that only ever emits
   `{branchId, branchName, distance, unit, price, mrp, inStock, maxQty}` — never org ids,
   costs, batches, percentages or discount rules.

## 3. Implemented (2026-09-22)

`GET /api/ecom/public/compare/{productId}?unit&lat&lng&radiusKm` → `EcomCompareService`. Matches on
`PID_GLOBAL_PRODUCT` when the pharmacies imported the same catalogue entry, else on the exact
product name (which carries the strength here); the answer states which, as `matchedBy`. Rows are
built by `EcomCatalogueService.item`, so a price here is the number the catalogue, the product page
and the till all produce. Ranked in-stock first, then price for one unit, then distance; carries
price per piece, difference against the cheapest, and the pharmacy's delivery radius and minimum
order. The unit compared is the cheapest the caller's tier may buy, or the one asked for.

## 4. Design direction (as built)

```
GET /api/ecom/compare?globalProductId=…&unit=PCS&lat=…&lng=…&radiusKm=…   (customer JWT or guest)
 └→ EcomCompareService (new, read-only)
     ├→ eligible branches: ALL_BRANCH where ECOM_ENABLED='Y' and TENANT_STATUS active, within bounding box → Haversine
     ├→ branch products: POS_PRODUCT_INFO where PID_GLOBAL_PRODUCT = :gp and PID_BRANCH in (…) and status Y
     ├→ for each: SalesPriceCalculator.unitPricesForSale(...) + CustomerPricing.priceFor(tier, unit, …)   ← same pure functions
     └→ for each: StockService.availableWholeUnits(branch, product, unit)                                 ← same service
     ranked: in-stock first, then price, then distance
```

Performance: N branches × (1 product read + 1 stock aggregate). With a short-TTL cache keyed
`(branch, product, tier, unit)` invalidated on stock movement / percentage change this is cheap;
without a cache it is still bounded by the radius. No new tables.

## 4. UI reference

`friends-pharma-aura.html` product page: ranked pharmacy "choice" cards (price, distance, stock,
unit selector, add-to-cart per pharmacy). To be rebuilt in React inside the existing app shell
(see `20-ui-ux.md`).
