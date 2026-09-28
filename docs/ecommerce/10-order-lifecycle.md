# 10 — Order lifecycle

Phase 0 · the ERP lifecycle as coded, and the ECOM status mapping it implies.

## 1. ERP sale lifecycle (`SalesServiceImpl`)

```
                createSale
                    │  stage=Submitted, status=Y, FROM_SOURCE=API, stock ISSUED, ledger posted
                    ▼
   ┌──────── Submitted ────────┐   editable (updateSale / supervisor unlock) — reverse + re-issue
   │                           │
   │ paid in full at creation  │ counterSale && unpaid
   ▼                           ▼
Collected                  Delivered
                    │
advanceStage(any of) ▼
   Confirmed → Delivery Assigned → On The Way → Delivered → Collected
   (sequence advisory; server accepts any listed stage from any stage)
   ROLE06 (driver): only own orders, only On The Way | Delivered
   'Collected' ⇒ PAYMENT_STATUS = Paid ; collect(amount) ⇒ Collected when balance = 0 (+ ledger)
   merge: stage cascades to member orders; members are read-only while merged

cancel endpoint  → advanceStage("Cancelled") → ValidationException (not in STAGES); stock never reversed
after Submitted  → corrections only via Sales Return (new MST type, stock back to lot, ledger)
```

Payment: `PAYMENT_TYPE` (Cash / …), `PAYMENT_STATUS` Paid / Partial / Unpaid, `GIVEN_AMOUNT`,
`ADVANCE_AMOUNT`; delivery cash-in through `POS_DELIVERY_COLLECTION`.

Timestamps on MST: `RECEIVED_DATE`, `DELIVERED_DATE`, `PAID_DATE` (+ `_BY`) exist as columns;
`advanceStage` does **not** set them (only `BaseEntity.UPDATED_DATE` moves).

## 2. ECOM status model (design, Phase 6–8)

The ECOM order (`ECOM_ORDER.ECOM_STATUS`) is the customer-facing state machine; the POS stage is
the ERP's. They are linked, not merged.

| ECOM status | Meaning | POS document | POS stage written via `SalesServiceImpl` |
|---|---|---|---|
| NEW | placed, awaiting branch confirmation | design A: created at checkout (stock issued) · design B: none yet | A: `Submitted` · B: — |
| CONFIRMED | branch accepted | exists | `Confirmed` |
| PREPARING | being packed | exists | (no ERP equivalent — stays `Confirmed`) |
| READY | packed, awaiting dispatch / pickup | exists | (stays `Confirmed`; DM assignment ⇒ `Delivery Assigned`) |
| ON_THE_WAY | dispatched | exists | `On The Way` |
| DELIVERED | handed over; COD collected | exists | `Delivered` then `collect()` ⇒ `Collected` |
| CANCELLED | by customer (only while NEW/CONFIRMED) or by branch (any time before ON_THE_WAY) | A: exists → **needs the ERP cancel fix** (reverse posting + `Cancelled` stage + ledger reversal) · B: none | `Cancelled` (new, after fix) |
| RETURNED | after delivery | exists | Sales Return document |

Allowed transitions are enforced in the ECOM service (a real state machine, unlike the ERP's
advisory list) and every transition writes `ECOM_ORDER_STATUS_HISTORY` (actor kind, actor id,
reason, at).

## 3. Who may transition

| Actor | Transitions |
|---|---|
| Customer | NEW → CANCELLED (before CONFIRMED; **BUSINESS CONFIRMATION**: allow after CONFIRMED?) |
| Branch staff with ECOM order right (new module) | NEW → CONFIRMED / CANCELLED; CONFIRMED → PREPARING → READY; READY → ON_THE_WAY (assign DM = existing `assign-delivery-man`); any → CANCELLED before ON_THE_WAY |
| Delivery man (ROLE06) | ON_THE_WAY → DELIVERED (+ collect) — mirrors `DRIVER_STAGES` |
| System | auto-cancel of NEW older than the hold window (Phase 6, configurable) |

## 4. Invariants

* `ECOM_ORDER.PID_TRAN_MST` is set exactly once and never changed.
* ECOM totals are computed from the POS document (authoritative) plus ECOM-level adjustments
  (coupon / points) recorded on the order; the ledger is posted by the ERP hooks only.
* No status may be "advanced" on the POS side by the ECOM board except through
  `SalesServiceImpl.advanceStage` / `collect` / `assign` so the merge, driver and branch rules
  keep applying.
* Historical orders render from snapshots (`ECOM_ORDER*`, `POS_TRANSACTION_DTL`), never from the
  live product/customer rows.

## 5. As built — Phase 6 (checkout)

Placing an order is one transaction: `EcomCheckoutService.placeOrder(cartId, addressId, note)`
re-prices the cart, resolves the shadow customer, calls `SalesServiceImpl.createSale(req,
SaleContext(branch, org, company, "ECOM"))`, then writes `ECOM_ORDER` + `ECOM_ORDER_ITEM` +
`ECOM_ORDER_STATUS_HISTORY` and closes the cart. Anything that refuses — an unapproved account,
a price that moved, a lot that emptied — rolls back the whole thing, so a refused checkout leaves
no invoice, no stock movement, no ledger line and an intact cart.

Implemented of the table in §2: **NEW** (on placing) and **CANCELLED** (by the customer, only
while NEW). The rest of the state machine belongs to the branch board (Phase 8) — the column is
already the one that will carry them, and `ECOM_ORDER_STATUS_HISTORY` already records actor kind,
actor id, reason and time for every change.

**Order numbers.** `ECOM-YYMM-NNNNNN` from `seq_ecom_order_no`, drawn in a `REQUIRES_NEW`
transaction. Not `INVOICE_NO`, which is five random digits and can collide (§11 "2"); the invoice
number is still shown to the customer, because that is what the counter and the phone call use.

**Cancelling does not touch the invoice.** Stock left the shelf when the order was placed, and
putting it back is a posting with a ledger consequence — the ERP's own cancel path, which is
still unfinished (`02 §3.4`). Until that lands the ECOM order goes to CANCELLED, the history
records who and why, and the branch reverses the invoice the way it reverses any other: with a
sales return. The refusal message says so rather than implying the goods are released.

### Verified (`scratchpad/ecom-p6-api.mjs`, `ecom-p6-race.mjs`, `platform-p6-ui.mjs`)

* order → `ECOM_ORDER` + `POS_TRANSACTION_MST` with `FROM_SOURCE='ECOM'` at the cart's branch,
  the shadow customer (`ECOM_MANAGED='Y'`), one DTL row, one stock movement, four journal lines;
  the branch sees it in its own **Sales Activity** and gets an `ECOM_ORDER` notification
* the cart is closed by the same transaction: a second POST for it is a 404
* another customer's order number is a 404; a guest's is a 403
* cancel while NEW → 200 and history `NEW → CANCELLED`; cancelling again → 409
* an unapproved account → 403 `ECOM_CUSTOMER_NOT_APPROVED`; a cart under the pharmacy's minimum
  → 409 `ECOM_CART_NOT_READY`, and in both cases the ECOM invoice count is unchanged
* two customers placing the last units of one lot at the same instant → exactly one order, one
  invoice, one debit; the loser keeps their cart and sees "Out of stock at this pharmacy"

## 6. Phase 7 — cancelling really cancels

§5 said the pharmacy's invoice was *not* touched when a customer called an order off, because the
ERP had no cancellation to call. It has one now (`02 §5`), and `EcomOrderService.cancel` uses it:
the ECOM order goes to CANCELLED **and** `SalesServiceImpl.cancelSale` runs in the same
transaction, so the pieces go back to the lots they came from and the journals are reversed. Either
both happen or neither does.

What the customer may still do is unchanged — only while the order is `NEW`. After the pharmacy
confirms it, cancelling is the branch's call, made from Sales Activity, under the same rules: not
once the goods are out, not once money has been taken.

Verified (`scratchpad/ecom-p7-cancel.mjs`), for an order placed and then called off by the
customer:

```
ERP invoice now: Cancelled|N|CUST:39|Ordered by mistake
shelf 4986 → 4992 (started at 4992) · movements SALE|O|6.0000 , ADJUST|I|6.0000 · journals 2, net 0.00
PASS stock back: true · PASS books square: true · PASS invoice void: true
still on the branch board: false
```

and through the browser, from the customer's own screen: order `ECOM-2609-001014` cancelled →
`SL260909040 | Cancelled | N | CUST:45 | Ordered the wrong strength`.

## 7. Phase 8 — the rest of the machine

§5 implemented NEW and CANCELLED and said the rest belonged to the branch board. It exists
(`12 §5`): CONFIRMED, PREPARING, READY, ON_THE_WAY and DELIVERED are written by
`EcomOrderBoardService`, which allows only the transitions in its own table and maps each one to
the ERP stage the ERP already has — or, for PREPARING and READY, to nothing at all.

RETURNED remains unimplemented: a return is a sales return in the ERP, and wiring the customer's
side of one is its own phase.

**Answered by the code:** the customer may still cancel only while NEW (§3). After that the
pharmacy decides, from its board, under the ERP's rules — not once the goods are out, not once
money has been taken.
