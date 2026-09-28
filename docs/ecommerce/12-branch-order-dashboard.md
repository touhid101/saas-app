# 12 — Branch-wise E-Commerce Order Operations dashboard

Phase 0 skeleton · existing state documented; design fixed only where the ERP already decides it.
Detailed design belongs to Phase 8.

## 1. Existing state

* `SalesActivity.jsx` (MOD06) is the branch's order board for **all** sales; chips per stage
  (`GET /api/sales-activity/stage-counts`), filters (stage, payment status, delivery man,
  unassigned, date range, text), per-row next action, bulk assign, collect, CSV export.
* `Delivery.jsx` (MOD09) — driver-scoped view of the same rows; `Collection.jsx` (MOD10) — cash-in.
* Branch scoping is server-side (`BranchScopeService`); `allBranches` is ROLE01-only.
* No ECOM rows exist yet; `FROM_SOURCE` is not filterable.

## 2. Decision (from `02-sales-activity-vs-ecom-operations.md`)

* **Extend** Sales Activity with a *Source* filter and chip (`ECOM`), an order-number column and
  a link to the ECOM order detail. No new stages in the ERP list.
* **Add** a dedicated page **Sales → E-Commerce Orders** (new module code, next free `MOD53`)
  showing ECOM-only state and KPIs. It is an operational board over existing services, not a
  sales engine.

## 3. Board contents (to be designed in Phase 8)

```
Branch A                                   (branch from session; ROLE01 may pick)
New 15 · Confirmed 6 · Preparing 8 · Ready 4 · On The Way 3 · Delivered 22 · Cancelled 2 · Returned 0
Filters: status, date, customer (name/phone), order no, payment method, delivery man, coupon used, prescription required
Row: order no · placed at · customer (global) · type · items · total · payment · ECOM status · POS invoice no · DM · next action
Detail: items (as ordered) + POS batch lines · address + map pin · status history · coupon/points · notes · proof of delivery · actions
Actions: Confirm · Reject/Cancel (reason) · Preparing · Ready · Assign DM (existing endpoint) · Dispatch · Delivered+Collect (existing) · Print invoice (existing document endpoint)
```

## 4. Security

* Reads: `ECOM_ORDER` where `PID_BRANCH` = resolved branch. Never by `PID_ORG` alone (a
  multi-branch org must still see one branch at a time — memory rule).
* Writes: new rights on MOD53 (`canView`, `canEdit` = status transitions, `canApprove` =
  confirm/cancel, `canPrint`), resolved by the existing `PermissionResolver`; stock/money actions
  additionally keep their `SalesActivityController` `@PreAuthorize` roles because they are called
  through the same service methods.

## 5. As built — Phase 8

**Sales → E-Commerce Orders (MOD53), `/ecomorders`.** Seeded by V107 in the place V101 left for
it, under the existing E-Commerce group. Rights mirror MOD06 (Sales Activity), because this is the
board beside it rather than a platform console:

| Role | Rights on MOD53 |
|---|---|
| ROLE01 Super Admin | view, edit, approve, export, print |
| ROLE02 Administrator (Branch) | view, edit, approve, export |
| ROLE09 Sales Manager | view, edit |
| ROLE04 Accounts | view |
| ROLE06 Delivery Staff | none — a driver works from Delivery, already scoped to their round |

### 5.1 API

`/api/ecom/admin/orders` — under the **admin** prefix deliberately: every other path below
`/api/ecom/` is a customer route where the filter clears a staff session, so a staff board could
not live there.

| Call | What it does |
|---|---|
| `GET /` | the branch's orders; `status` takes one ECOM status or `OPEN` (everything still needing work), plus `q` (order no, name, mobile), `from`, `to` |
| `GET /status-counts` | per status, plus `OPEN` and `ALL` |
| `GET /{orderNo}` | items **with the batches the invoice actually took**, address and pin, totals, the ERP document's stage / payment status / what is still owed, and the full history |
| `POST /{orderNo}/action` | `CONFIRM · PREPARE · READY · DISPATCH · DELIVER · CANCEL` |

Branch is the scope on every call (`BranchScopeService.resolve`), and another pharmacy's order
number is a **404**, not a 403: a branch should not learn that another branch's order exists.

### 5.2 The two state machines, and where they meet

| Board action | ECOM status | ERP document | Right |
|---|---|---|---|
| Confirm | NEW → CONFIRMED | `advanceStage("Confirmed")` | APPROVE |
| Start packing | CONFIRMED → PREPARING | *nothing* | EDIT |
| Mark ready | PREPARING → READY | *nothing* | EDIT |
| Dispatch | → ON_THE_WAY | `advanceStage("Delivery Assigned", dm)` then `"On The Way"` | EDIT |
| Delivered | ON_THE_WAY → DELIVERED | `advanceStage("Delivered")` | EDIT |
| Cancel | any pre-dispatch → CANCELLED | `cancelSale(...)` — stock back, journals reversed | APPROVE |

PREPARING and READY deliberately move nothing in the ERP. They describe the shop floor, and
adding stages for them would change what every stage filter, chip and delivery report means
(`02 §3.2`). Collecting COD money is **not** on this board: it stays on Sales Activity and
Delivery, where collections have always lived, because that is the screen the cash reconciles on.

Every transition writes `ECOM_ORDER_STATUS_HISTORY` (who, what, why, when) and an
`ECOM_AUDIT_LOG` row. The ERP call comes **first**: if it refuses — a driver who is not a driver,
goods already out, money already taken — the whole transaction rolls back and the customer is not
told a story the branch cannot deliver.

### 5.3 The screen

Chips (Needs work, then each status) · filters (status, date range, search) · a row carrying both
numbers, the customer and their phone, where it goes, the total with its payment state, the driver
and the status · a drawer with the items **and the lots to pick from, with expiry**, the delivery
address with a Maps pin, the customer's note, the totals against what the invoice still shows as
owed, and the history. Actions are offered only when the server would accept them *and* the matrix
allows them, so a Sales Manager sees Start packing and not Confirm.

### 5.4 Verified

`scratchpad/ecom-p8-board.mjs` (API), `erp-p8-ui.mjs` (the board in Chrome),
`platform-p8-ui.mjs` (the customer's screen following it):

```
CONFIRM → CONFIRMED · ERP Confirmed      DISPATCH → ON_THE_WAY · ERP On The Way | Karim Rider
PREPARE → PREPARING · ERP Confirmed      DELIVER  → DELIVERED  · ERP Delivered
READY   → READY     · ERP Confirmed
deliver before confirm      → 409 ECOM_TRANSITION_NOT_ALLOWED ("What you can do: Confirm, Cancel")
dispatch with no driver     → 400 DELIVERY_MAN_REQUIRED
dispatch to a non-driver    → 400, and the order stayed at CONFIRMED
cancel after dispatch       → 409, nothing reversed
cancel before it            → invoice Cancelled/N, pieces back on the shelf
another branch's order      → 404 · a customer token → 403 · nobody → 403
customer's screen: Placed → Confirmed → Packing → Ready → On the way → Delivered
```
