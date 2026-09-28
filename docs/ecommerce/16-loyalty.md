# 16 — Loyalty points

## 1. Existing state

**No loyalty, points, wallet or cashback logic exists** (0 source files match
`loyalty|reward_point|loyaltyPoint`). `POS_TRANSACTION_MST.CASHBACK_AMOUNT` exists as a column
defaulted to 0 and is never written. Nothing to reuse.

## 2. Design principles fixed by the brief and the ERP

* **Points are global** (RULE 44): balance belongs to `ECOM_CUSTOMER`, not to any branch.
  Earned at branch A, redeemable at branch B — subject to the redemption rule below.
* **Ledger, not a counter**: `ECOM_POINT_LEDGER` is append-only (EARN / REDEEM / EXPIRE /
  ADJUST, `BALANCE_AFTER`); `ECOM_CUSTOMER.POINTS_BALANCE` is a denormalised cache recomputed
  from the ledger and never edited directly. Mirrors how the ERP treats stock and accounts
  ("ledger is authoritative").
* **Earn** on `DELIVERED` (not on placement) so cancelled orders never earn; **reverse** on
  `RETURNED`.
* **Redeem** at checkout: points value is an order-level amount subject to the same ERP-document
  question as coupons (`15` §3) — the same decision applies to both.
* Rules (`ECOM_POINT_RULE`): earn rate per currency unit by customer type and branch scope
  (`ALL_BRANCHES | SELECTED_BRANCHES`), min redemption, max % of order payable by points, expiry
  window. **BUSINESS CONFIRMATION REQUIRED** for every number, and for whether the cost of
  redeemed points is borne by the platform or the fulfilling branch (accounting).

## 3. Not in scope in Phase 13

Tiers/levels, referral bonuses, birthday points, partner earning.

## 4. As built — Phase 13 (loyalty points)

### 4.1 What was built

**V111** — three new tables, nothing reused, because §1 found nothing to reuse:

| Table | What it holds |
|---|---|
| `ECOM_POINT_RULE` | what a point is worth and how one is earned |
| `ECOM_POINT_RULE_BRANCH` | which pharmacies a branch-scoped rule covers |
| `ECOM_POINT_LEDGER` | every point ever earned, spent, expired or adjusted |

`ECOM_CUSTOMER.POINTS_BALANCE` — a column added in V100 and never written until now — became the
cache of `SUM(points)`, written only by the service that writes the ledger row, in the same
transaction. The same rule the ERP applies to stock and to accounts.

**There is no default rule and nothing is seeded.** Until somebody writes a scheme on the
Promotions screen, nothing earns and nothing can be redeemed:

> Say what one point is worth. There is no default — it is your money.

A loyalty scheme that switched itself on with an invented exchange rate would be giving away money
nobody agreed to. Every number on the form is a business decision, and the server refuses a rule
that leaves the one that matters blank.

**Not built:** tiers and levels, referral and birthday points, partner earning (§3 puts them
out of scope), and expiry *sweeping*. `EXPIRY_MONTHS` is stored and shown — "unused points
expire after 12 months" — but no job runs to write the `EXPIRE` rows yet; the ledger and the entry
type are in place for one. **Say so rather than imply it**: a pharmacy that sets 12 months today
gets the promise recorded, not enforced.

### 4.2 Earning happens on arrival

Points are credited when the order reaches **DELIVERED**, never when it is placed. An order that
is cancelled, refused at the door or never turns up earns nothing, and no reversal is needed
because nothing was paid.

Crediting rides the Phase 10 event (`SaleStageChanged` → `EcomOrderStageSync`), so an invoice
advanced to Delivered from **Sales Activity** earns exactly as the board does. Delivering twice —
a stage corrected, an event replayed — cannot pay twice:

```sql
CREATE UNIQUE INDEX uq_ecom_pl_earn ON ecom_point_ledger (pid_ecom_order)
 WHERE entry_type = 'EARN' AND pid_ecom_order IS NOT NULL;
```

### 4.3 Spending happens at checkout

The cart says what the points would do *at that pharmacy* — the balance is global, what it is
worth is not:

```
usable 8 · worth 8.00 · "8 points — 8 off"
```

`redeemPoints` on the order request is **a request, not an instruction**. The server quotes again
inside the transaction that writes the invoice and caps at whichever binds first: the balance, the
rule's ceiling, or the order. Asking for 9999 points against a balance of 500 on an 701.52 order
with a 25% ceiling spends 175, and says so.

There is deliberately **no box to type a smaller number in** at checkout. Every number below the
offer is one the scheme might refuse (the minimum-to-redeem), and a refusal at that point costs
the customer their order.

### 4.4 How the redemption reaches the invoice

The same answer Phase 12 gave for coupons, and the same field: `SalesRequest.invoiceDiscAmt`, an
**exact amount**, never a derived percentage. A coupon and points on one order add together into
that one figure; the order row keeps them apart so the customer can see which was which.

```
order   (goods|points|value|total)   : 811.95 | 8.00 | 8.00 | 803.95
invoice (no|goods|discount|payable)  : SL260970618 | 811.9500 | 8.0000 | 803.9500
```

**Who bears the cost of a redemption** — the pharmacy whose invoice it reduces, or the platform —
is the same open question as a coupon's (`15` §3) and is **still not answered**. There is no
promotional-expense account, so the discount simply reduces revenue at the fulfilling branch.
**BUSINESS / ACCOUNTING CONFIRMATION REQUIRED.**

### 4.5 A cancelled order gives the points back

Cancelling an order that spent points writes an `ADJUST` row returning them — an addition, not a
deletion, because "spent and then given back" is what happened and the ledger is append-only. All
three cancellation paths do it: the customer's, the board's, and an invoice cancelled from Sales
Activity.

```
REDEEM -7.00 → 0.00   Spent on order ECOM-2609-001075
ADJUST  +7.00 → 7.00  Returned when order ECOM-2609-001075 was cancelled
```

### 4.6 Refusals, in words

| Situation | What is said |
|---|---|
| no scheme at this pharmacy | "This pharmacy does not take points yet." |
| nothing to spend | "You have no points to spend." |
| rule written with no exchange rate | "Say what one point is worth. There is no default — it is your money." |
| ceiling outside 1–100% | "Points may pay for between 1% and 100% of an order." |
| adjustment with no reason | "An adjustment needs a reason." |
| adjustment that would overdraw | "That would leave the balance below zero — it is 357 now." |

### 4.7 Where it is, on screen

**Staff — E-Commerce Promotions (MOD54)** gained a second tab, *Loyalty points*, beside *Coupons*:
one screen for the two things a pharmacy can give a customer. The tab lists the schemes with their
earning and spending rules, and the drawer writes one.

**Staff — E-Commerce Customers (MOD55)** gained a *Points* pane on the customer drawer: the
balance, the whole ledger, and the box that moves a balance by hand. The reason is required and
goes onto the ledger row, because somebody will have to explain it later.

**Customer — Account → Points** shows the same statement the pharmacy sees. A loyalty balance
shown as a bare number is one the customer has to take on trust.

**Customer — cart** says what the order will earn; **checkout** offers the points and shows the
total drop; **the order** shows what was spent, and what it earned once it arrives.

### 4.8 Authorisation

Reading a customer's balance needs **MOD54 VIEW**; moving one by hand needs **MOD54 APPROVE** —
the right the ERP already reserves for actions that cost money. The checks sit at the controller,
not inside `EcomPointsService`: checkout calls that service on behalf of a customer, who holds no
ERP permission at all.

A customer's own token is refused on both (403), and so is no token.
