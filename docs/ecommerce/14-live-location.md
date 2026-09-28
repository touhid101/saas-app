# 14 — Location & live tracking (Phase 2 / Phase 10 skeleton)

## 1. Existing state

| Item | Status |
|---|---|
| Branch coordinates | **V103 (Phase 2, done 2026-09-22)**: `ALL_BRANCH.LATITUDE/LONGITUDE`, `ECOM_ENABLED` (default N), `ECOM_DELIVERY_RADIUS_KM`, `ECOM_MIN_ORDER_AMOUNT`, `ECOM_OPENING_HOURS`, `ECOM_DESCRIPTION`; set on Master Data › Branch; switching on requires coordinates |
| Customer coordinates | **none** (`POS_CUSTOMER_INFO.ADDRESS`, `CITY` text) |
| Geo libraries / PostGIS | **none** in `pom.xml` / migrations |
| Realtime channel (WebSocket / SSE / push) | **none** |
| Map component in frontend | **none** (the Aura prototype draws an inline SVG map; not a real map) |
| Delivery man position | **none** |

## 2. Location model (Phase 2)

* **Registration location** (`ECOM_CUSTOMER.REG_LATITUDE/LONGITUDE/ADDRESS`) — captured once,
  used as the default "near me"; **not** an ownership link to any branch (§102 of the brief).
* **Delivery addresses** (`ECOM_CUSTOMER_ADDRESS`, many, each with optional coordinates).
* **Branch location** (`ALL_BRANCH.LATITUDE/LONGITUDE`, additive nullable; `ECOM_ENABLED`,
  `ECOM_DELIVERY_RADIUS_KM`). Set by the pharmacy admin (existing branch settings screen extended)
  or the platform console.
* **Nearby search**: SQL bounding box on `(LATITUDE, LONGITUDE)` + Haversine ordering; radius
  default from the brief's prototype (10 km) — **BUSINESS CONFIRMATION** for the default and cap.
* Map provider for the storefront (tiles, geocoding, reverse-geocoding on address entry):
  **INFRA CONFIRMATION REQUIRED** (OpenStreetMap/Leaflet vs. Google Maps; key handling).

## 3. Live tracking (Phase 10)

* Driver pings: `POST /api/ecom/delivery/{orderId}/location` from the staff app (ROLE06, own
  order only, order in `ON_THE_WAY`) → append `ECOM_DELIVERY_TRACKING`. Interval and battery
  policy to be decided.
* Customer read: `GET /api/ecom/orders/{orderNo}/tracking` (owner only) → latest ping + ETA
  (straight-line, no routing service). Polling every 10–15 s is sufficient for v1; SSE is an
  optional later upgrade.
* Privacy: tracking rows visible only while the order is `ON_THE_WAY`; retention (e.g. purge after
  30 days) and driver consent are **BUSINESS/LEGAL CONFIRMATION** items.

## 4. As built — Phase 10

### 4.1 Two tables (V109)

`ECOM_DELIVERY_TRACKING` — one row per position a driver's device reports.
`ECOM_DELIVERY_PROOF` — one row per order: who took it, a photograph if one was taken, where.

### 4.2 The privacy rules, and where they live

All of them are in `EcomDeliveryTrackingService`, not in a client:

* A position is accepted **only from the driver the order is assigned to** — the same rule the ERP
  applies to advancing a stage — and **only while the order is ON_THE_WAY**. Nothing follows anyone
  between deliveries; nothing is written before dispatch or after arrival.
* The customer is given the **latest point and nothing else**. A trail is a record of somebody's
  working day; a customer needs to know how far away their medicine is.
* When the order is delivered or called off, the tracking answer closes: `live: false`, no
  coordinates, and the proof takes its place. The rows stay for the branch.
* Another branch's order number is a **404** on the driver's endpoint, and another customer's is a
  404 on the customer's.

**Retention is still nobody's decision.** Nothing purges. `ix_ecom_track_age` exists for the job
that will do it once somebody says how long positions are kept — **BUSINESS/LEGAL CONFIRMATION
REQUIRED**, along with driver consent.

### 4.3 What is deliberately not claimed

* **No ETA.** There is no routing service here, so the honest answer is a straight-line distance
  and how long ago it was reported: *"About 1.0 km away, in a straight line."* A minute-count
  conjured from a crow-flies kilometre is a promise the pharmacy cannot keep.
* **A vague reading is refused, not shown.** Worse than 2 km of claimed accuracy comes back as
  `POSITION_TOO_VAGUE`: a pin that could be anywhere in the district would tell the customer
  something false with the full authority of a map.
* **No OTP proof.** It needs an SMS provider nobody has chosen; the `CHECK` allows `PHOTO`, `NAME`
  and `NOTE` only, so the system cannot offer evidence it has no way to collect.

### 4.4 The two ends

**The driver** works from Delivery › Tracking, as before. On an online order the row now offers
*Share location* — the browser's position every 20 seconds, off by default, one order at a time,
stopping when the toggle goes off, the page closes, or the delivery is marked done — and
*Delivered →*, which opens the handover dialog: a name, a photograph (the phone's camera), or a
note. One of the three is required, because "delivered" with nothing behind it is exactly the
claim that gets disputed later. The position is attached quietly if the device knows it.

**The customer** sees, while it is out: how far away it is, who is bringing it, when the position
was last updated, a map link and the pharmacy's number. Afterwards: *Handed over*, who received
it, the note and the photograph.

### 4.5 A gap this phase closed

The Phase 8 board moved both halves of an order together, but it was never the only way an invoice
moves: a driver marks a delivery done from **Delivery**, a manager cancels from **Sales Activity**,
and neither screen knows the sale began online. The customer's screen would have gone on saying
"on the way" about a parcel that arrived an hour ago.

`SalesServiceImpl` now publishes `SaleStageChanged` — a plain record, no marketplace types — and
`EcomOrderStageSync` follows it **after the commit, in its own transaction**: the sale is the thing
that must not fail, and a follow-up that keeps a second table tidy has no business rolling one
back. Because the board sets its own status before that point, the listener finds nothing to do
and writes no second history row.

```
Sales Activity → Confirmed          order is now CONFIRMED   · "Followed the invoice (Confirmed)"
Sales Activity → Delivery Assigned  order is now CONFIRMED   · (no ERP equivalent; nothing moved)
Sales Activity → On The Way         order is now ON_THE_WAY
Sales Activity → Delivered          order is now DELIVERED
board CONFIRM                       history 1 → 2 rows (one move, one row)
Sales Activity cancel               order CANCELLED, and the customer reads the reason that was
                                    typed on the invoice — "Shelf count was wrong"
a delivered order pushed back       order stays DELIVERED · a cancelled one stays CANCELLED
```

### 4.6 Verified

`scratchpad/ecom-p10-tracking.mjs` (positions, refusals, proof, the photograph served),
`ecom-p10-sync.mjs` (an ERP-driven run carrying the order), `p10-ui.mjs` (both browsers: the
driver sharing, the customer watching, the handover written at the door and shown back).
