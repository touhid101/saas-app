# 13 — Delivery (existing behaviour + Phase 9 skeleton)

## 1. What delivery is in the ERP today

* **There is no delivery entity.** A delivery is a sale (`POS_TRANSACTION_MST`) carrying
  `DELIVERY_MAN` (→ `HR_EMPLOYEE.PID_EMPLOYEE`), `DELIVERY_MAN_NAME`, `DELIVERY_ADDRESS` (free text,
  200 chars), `DELIVERY_CHARGE`, `AREA_NAME` and a `STAGE`.
* **Delivery man** = `HR_EMPLOYEE` with `EMP_TYPE` delivery (`HrEmployee.isDeliveryMan()`),
  active, same org (`DeliveryManService.requireDeliveryMan`). Listed by
  `GET /api/employees/delivery-men`. A driver logs in as a staff user with `ROLE06` linked to that
  employee (`ACL_USER.PID_EMPLOYEE`).
* **Assignment**: `POST /api/sales-activity/{id}/assign-delivery-man`, `/bulk-assign-delivery-man`
  (ROLE01/02), or `advance` with `deliveryManId` (managers only). Stage `Delivery Assigned`.
* **Driver actions**: `advance` to `On The Way` / `Delivered` on own orders only
  (`requireOwnDelivery`, `DRIVER_STAGES`); cannot confirm, assign or mark collected.
* **Collection**: `POST /api/sales-activity/{id}/collect` (amount, payment type) or
  `POST /api/v1/collections` (`POS_DELIVERY_COLLECTION`: invoice, DM, customer, amount, balance,
  payment mode, cheque/bank/ref) → ledger via `AccountingHooks.saleCollected` /
  `deliveryCashIn`.
* **Performance**: `GET /api/sales-activity/delivery-performance` (per driver; a driver sees
  only their own row), `GET /summary`.
* **Frontend**: `Delivery.jsx` (MOD09), `Collection.jsx` (MOD10), `SalesActivity.jsx` (MOD06).

## 2. What does not exist

Delivery zones / fees by distance · time slots · route / batching (merge invoices is the closest:
`SalesMergeController`) · customer notification on dispatch · proof of delivery (photo/OTP/
signature) · GPS / live location · delivery address as structured data with coordinates ·
third-party courier integration.

## 3. Phase 9 design direction

* Reuse assignment, stages, driver rules and collection **unchanged**.
* `ECOM_ORDER` carries the structured address snapshot + lat/long; `DELIVERY_ADDRESS` on MST
  gets the one-line rendering (≤ 200 chars) so existing prints still work.
* Delivery charge policy per branch (`ALL_BRANCH.ECOM_DELIVERY_RADIUS_KM`, flat fee / by distance)
  — **BUSINESS CONFIRMATION REQUIRED**; computed server-side and passed as `deliveryCharge` to
  `createSale`.
* Proof of delivery and customer-facing status come from `ECOM_ORDER_STATUS_HISTORY` +
  optional `ECOM_DELIVERY_PROOF`.
* Driver app = the existing staff app's Delivery page, extended with the ECOM detail (address
  map link, phone tap-to-call, proof capture). No second app.

## 4. As built — Phase 9

Assignment, stages, driver rules and collection are untouched, as §3 said they would be. What
Phase 9 adds is the price of the journey, and enough of the customer's address for a driver to
find the door.

### 4.1 The delivery charge policy (V108)

Four nullable columns on `ALL_BRANCH`, set from **Master Data › Branch**:

| Setting | Meaning |
|---|---|
| `ECOM_DELIVERY_FEE_TYPE` | `NONE`/null → free, which is what every branch did before this existed · `FLAT` · `DISTANCE` |
| `ECOM_DELIVERY_FLAT_FEE` | the fee for FLAT, the base for DISTANCE, and the fallback when an address has no pin |
| `ECOM_DELIVERY_FEE_PER_KM` | added per km of straight-line distance from the pharmacy (DISTANCE only) |
| `ECOM_DELIVERY_FREE_ABOVE` | a subtotal at or above which the order ships free, whatever the fee type says |

Refused with a sentence rather than a constraint error: a fee type that is not one of the three, a
negative amount, and DISTANCE on a branch with no coordinates to measure from. A `CHECK` constraint
stands behind all three, because the form is not the only way into the column.

**Nothing is charged that cannot be justified.** DISTANCE needs two points. The pharmacy always has
coordinates — it cannot be listed online without them — but an address may have no pin, and then
the base fee applies and the quote says so: *"40 delivery — add a location to your address for the
exact fee"*. A per-kilometre fee with no kilometres would be a number somebody made up.

### 4.2 One place decides it

`EcomDeliveryFeeService.quote(branch, subtotal, lat, lng)` answers for the cart, for the checkout
screen and for the invoice, so all three agree. Nothing about the fee is ever taken from the
client.

**The quote follows the door.** The cart quotes against the customer's default address (where an
order usually goes); the checkout screen re-quotes for the address they actually select, and the
order is charged for that one. Picking the far warehouse instead of the near shop changes the
figure on the screen *before* the button is pressed — verified: screen ৳796.72, charged ৳796.72.

### 4.3 What the ERP does with it

The charge is passed to `SalesServiceImpl.createSale` as `deliveryCharge`, which the ERP has always
supported (`total = subtotal − invoice discount + deliveryCharge`) — so the invoice carries it, and
the ledger books it to **Delivery Income** rather than to revenue, by the ERP's own posting rule:

```
Accounts Receivable = 607.12 / 0.00
Sales Revenue       = 0.00 / 417.12
Delivery Income     = 0.00 / 190.00
Cost of Goods Sold  = 360.76 / 0.00
Inventory           = 0.00 / 360.76
```

`ECOM_ORDER` keeps the same split (`SUBTOTAL`, `DELIVERY_CHARGE`, `GRAND_TOTAL`), so the customer's
order and the pharmacy's invoice tell the same story, and a cancellation reverses all of it
through the Phase 7 path.

### 4.4 The driver's end

`SaleSourceReferences` now returns what the marketplace knows and the ERP has no column for — the
order number, the pin the customer dropped, the number to ring and who is expecting it — for a
whole page of invoices in one query. **Delivery › Tracking** shows them on the existing row:

```
SL260900007 · ECOM-2609-001037 · P9 UI · for Mr Alam · 01744444444
Far door, Savar, Dhaka · Open the map pin · Karim Rider · ৳796.72 · ৳190.00 · On The Way
```

No second app, no new screen: the driver's page gained a map link, a tap-to-call number and the
customer's own order number.

### 4.5 Still a business decision

* **VAT on a delivery charge.** The fee is booked as Delivery Income with no tax component, which
  matches the rest of the system (no VAT is computed anywhere today). If delivery is taxable here,
  that is a change to the posting rule, not to this service. **BUSINESS CONFIRMATION REQUIRED.**
* **Who keeps it.** The charge belongs to the pharmacy's invoice. A platform commission on
  delivery would be a settlement question between the platform and the pharmacy, and nothing here
  assumes one. **BUSINESS CONFIRMATION REQUIRED.**
* Time slots, zones drawn on a map, courier hand-off and proof of delivery remain out (Phase 10+).
