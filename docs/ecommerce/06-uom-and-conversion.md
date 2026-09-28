# 06 — UOM and conversion (existing behaviour)

Phase 0 · documents the ERP as it is.

## 1. The three units

| Unit | Constant | Meaning | Stored? |
|---|---|---|---|
| PCS | `StockUnitConverter.PCS` | one tablet / capsule / bottle / sachet | **yes — the only stored unit** (`POS_STOCK_BATCH.QTY_PCS`, `POS_STOCK_MOVEMENT.QTY_PCS`) |
| STRIP (a.k.a. LEAF) | `StockUnitConverter.STRIP` | one blister strip | no — derived |
| BOX | `StockUnitConverter.BOX` | one retail box of strips | no — derived |

`normalise(unit)`: trims, upper-cases; blank/null reads as PCS. `isKnownUnit` accepts only the
three. The DTL columns `SALES_UNIT` and `SALES_TYPE` both carry the unit name; `NO_OF_PCS` carries
the factor used at the time of sale.

## 2. Packing hierarchy (per branch product)

```
POS_PRODUCT_INFO.PID_BOX  ──▶ POS_BOX_INFO.NO_OF_LEAF      strips per box
POS_BOX_INFO.PID_LEAF     ──▶ POS_LEAF_INFO.NO_OF_PCS      pieces per strip

pcsPerStrip = NO_OF_PCS
pcsPerBox   = NO_OF_LEAF × NO_OF_PCS
```

`PosProductInfo` exposes transient `pcsPerBox / pcsPerLeaf / leavesPerBox`; services recompute
from the repositories (`SalesServiceImpl.pcsPerUnit`, `StockServiceImpl.packingFor`). Global
catalogue rows carry `PCS_PER_LEAF` / `LEAF_PER_BOX` directly and are copied into branch
box/leaf masters on import.

In dev: 66 of 108 active products have `PID_BOX` set; the remaining 42 can only be sold in PCS
(their BOX/STRIP prices are null — `unavailableReason` says "no … packing set").

## 3. Conversion rules (`StockUnitConverter`)

```
factorFor(unit, pcsPerBox, pcsPerStrip)   BOX → pcsPerBox | STRIP → pcsPerStrip | PCS → 1 | unknown → null
toPcs(qty, unit, …)                        qty × factor; throws IllegalArgumentException when factor is null
fromPcs(pcs, unit, …)                      pcs ÷ factor, 2 dp, display only
breakdown(pcs, …)                          whole boxes, whole strips, loose pcs   e.g. 1234 pcs @ 10×10 → 12 box 3 strip 4 pcs
describe(pcs, …)                           "12 box 3 strip 4 pcs"
```

A missing pack size is **unknown, not 1** — the code refuses to transact rather than turn a
100-piece box into one tablet.

## 4. Whole-unit availability

`StockServiceImpl.availableWholeUnits(branch, product, unit)` counts **per lot**:
Σ over allocatable batches of `floor(QTY_PCS ÷ unitSize)`. Two lots of 11 pieces hold 22 pieces
and **zero** boxes of 12. `assertAvailable` and `preview` use the same whole-unit walk, so a
customer asking for 2 BOX is refused unless two whole boxes exist in FEFO order across lots.

## 5. Tier × unit gate

`CustomerPricing.allowedUnits(tier)`: RETAILER → `[PCS]`; WHOLESALER / SPECIAL → `[BOX, STRIP,
PCS]`. Enforced in `validateLines` (HTTP 422 `UNIT_NOT_SELLABLE`) — not merely hidden in the UI.

## 6. How a sale records it

For a line `{qty=3, unit=BOX}` on a 10×10 product, FEFO may consume 2 boxes from lot A and 1 from
lot B → **two** `POS_TRANSACTION_DTL` rows, each with `SALES_UNIT='BOX'`, `NO_OF_PCS=100`,
`QUANTITY` in boxes (4 dp), `BATCH_NO`, `EXPIRY_DATE`. Stock movements are in pieces.

## 7. Consequences for e-commerce

1. The storefront never converts: it asks the server for `{allowed units, price per unit, maxQty
   per unit}` (`SalesPricingResponse.units[]`) and sends back `{productId, unit, qty}` only.
2. The unit selector per product card shows exactly `allowedUnits` for the logged-in tier;
   guests see PCS.
3. `ECOM_ORDER_ITEM` snapshots `PCS_PER_UNIT_SNAPSHOT` and `QTY_PCS` so a later packing change
   cannot re-describe an old order.
4. Products without packing are sellable in PCS only; the card must say so rather than offering
   an empty BOX option.
