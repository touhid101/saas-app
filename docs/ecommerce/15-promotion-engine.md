# 15 — Promotions: coupons & offers (existing state + Phase 12 skeleton)

## 1. Existing promotion-like features

| Feature | Where | Is it a coupon? |
|---|---|---|
| Sales % (six percentages per product) | `POS_PRODUCT_INFO.SALES_PER*`, MOD08 "Sales Discount%" | no — it **is the price list** |
| Discount % rules | `POS_SALES_DISCOUNT` (org / company / product × unit tier), `SalesDiscountService` | no — a standing trade discount; branch-owned (`PID_BRANCH NOT NULL` since V30), no code, no dates, no usage limits, no customer scope |
| Invoice discount % | `SalesRequest.invoiceDiscPct` → `MST.INVOICE_DIS_PCT/AMT` | no — manual, per invoice, typed by staff |
| Line discount override | `SalesRequest.lines[].discountPct` (client wins if higher) | no |
| `CASHBACK_AMOUNT` on MST | column exists, never written by `createSale` | dormant |
| Coupon / voucher / promo code / campaign | — | **does not exist** (0 source files) |

**Conclusion:** no coupon or offer system exists. Coupons/offers are new, ECOM-only tables.

## 2. Coupon → Branch model (design, per §22–27, §60–62 of the brief)

```
ECOM_COUPON            code (unique, case-insensitive), name, description,
                       discount_type PERCENT|FIXED, discount_value, max_discount,
                       minimum_order_amount, usage_limit, per_customer_limit,
                       start_date, end_date, is_active, first_order_only,
                       customer_type_scope ALL|RETAILER|WHOLESALER|SPECIAL,
                       branch_scope ALL_BRANCHES|SELECTED_BRANCHES   (NOT NULL, no default — RULE: never global by accident)
ECOM_COUPON_BRANCH     (pid_coupon, pid_branch)          ← Coupon → Branch, never Customer → Branch
ECOM_COUPON_PRODUCT / ECOM_COUPON_CATEGORY   optional scope
ECOM_COUPON_USAGE      (pid_coupon, pid_ecom_customer, pid_ecom_order, amount, used_at)  unique per order
ECOM_OFFER (+ _BRANCH, _PRODUCT)  automatic promotions with the same branch-scope rule
```

Validation (server, at cart *and* again at checkout, using the **cart/order branch**, never the
customer's registration branch):

1. code exists, active, within dates
2. `branch_scope = ALL_BRANCHES` **or** `(coupon, orderBranch) ∈ ECOM_COUPON_BRANCH`
3. customer type scope matches the **global** tier snapshot
4. min order amount on the priced cart (server prices)
5. usage limit and per-customer limit (count `ECOM_COUPON_USAGE`, row-locked on the coupon)
6. first-order-only: customer has no non-cancelled `ECOM_ORDER`
7. product/category scope if set

Priority (§27): tier price → standing discount% (ERP) → **automatic offer** → **coupon**
(one coupon per order) → points redemption; `max_discount` caps; never below zero.

## 3. How the coupon amount reaches the ERP document — decision required

| Option | Mechanism | Pros | Cons |
|---|---|---|---|
| A. Invoice discount | pass `invoiceDiscPct` (derived so that `INVOICE_DIS_AMT` = coupon amount) to `createSale` | zero ERP change; ledger sees a normal discount | percentage rounding; the ERP invoice shows "discount %" not "coupon SAVE100"; reports cannot separate coupons from staff discounts |
| B. Separate settlement | POS document at full price; `ECOM_ORDER.COUPON_AMOUNT` reduces what the customer pays; recorded as a promotional expense on collection | exact amounts, separable in reports | needs an accounting decision (which account absorbs the coupon) and a `collect()` path that accepts a promo-covered amount |

**BUSINESS / ACCOUNTING CONFIRMATION REQUIRED.** Option A is the minimum-change default for
Phase 12 if no answer is given; the order snapshot keeps the exact coupon amount either way.

## 4. Staff UI

Platform console (global coupons) and branch settings (branch-scoped coupons and offers) —
new module `MOD54 "E-Commerce Promotions"`. A branch may only attach a coupon to **its own**
branch(es) (scope resolved by `BranchScopeService`); `ALL_BRANCHES` scope is platform-only.

## 5. As built — Phase 12 (coupons)

### 5.1 What was built, and what deliberately was not

**Coupons are new** (V110: `ECOM_COUPON`, `ECOM_COUPON_BRANCH`, `ECOM_COUPON_USAGE`, plus the
**MOD54 · E-Commerce Promotions** screen). They are the one promotion §1 found missing: a code a
customer is given and chooses to spend.

**Automatic product offers were not built, on purpose.** §1 of this document already records that
the ERP has a standing trade-discount engine — `POS_SALES_DISCOUNT` (org / company / product ×
unit), managed on **Sales Discount%** — and the marketplace has honoured it since Phase 3: it is
what the cart's "You save" line already shows. An `ECOM_OFFER` table applying percentages to
products would be a second engine answering the same question, and the two would disagree the
first time somebody used both. If time-boxed campaigns or tier-scoped automatic discounts are
wanted, the honest shape is to give `POS_SALES_DISCOUNT` dates and a scope rather than to
duplicate it — a decision for whoever owns pricing, not one to take by writing a table.

`ECOM_COUPON_PRODUCT` / `ECOM_COUPON_CATEGORY` from §2 are also not built: the discount lands as
an invoice-level amount, and restricting it to some lines would mean apportioning it across them —
which the ERP's invoice discount does not model, and which changes what a sales return of one line
is worth. Order-level rules (minimum, tier, branch, first order, limits, dates) cover what a
pharmacy asks for first.

### 5.2 The rules, and where they are enforced

`EcomCouponService.quote(...)` answers "what is this code worth on this basket" — **twice**: once
while the customer shops, and again inside the transaction that writes the invoice, with the
coupon row locked. The cart's figure is a quote; the checkout's is the answer. Between them a code
can expire, run out, or stop applying because the basket shrank, and the customer is told which:

| Refusal | What the customer reads |
|---|---|
| unknown code | "No coupon with that code." |
| stopped / expired / not open yet | "That coupon expired on 2026-09-01." |
| wrong pharmacy | "That coupon is not valid at this pharmacy." |
| wrong tier | "That coupon is for retailer accounts, and yours is wholesaler." |
| under the minimum | "That coupon needs an order of 300 or more. Add 262.08 more." |
| already used | "You have already used that coupon." |
| all gone | "That coupon has been fully taken up." |
| free delivery, nothing to pay | "That coupon pays for delivery, and this order is already delivered free." |

**Limits are counted from `ECOM_COUPON_USAGE`, never from a counter.** Two customers spending the
last use at the same instant would both pass a counter read; they cannot both insert under
`uq_ecom_usage_order`, and the coupon row is locked while the count is taken.

**A cancelled order gives the use back** — released, not deleted, because "used and then
cancelled" is part of what a campaign's numbers mean. Released from all three cancellation paths:
the customer's, the board's, and an invoice cancelled from Sales Activity.

### 5.3 How the discount reaches the invoice — §3 answered

Option A, as §3 said it would be if no business answer arrived: the coupon becomes the invoice's
own discount. But not as a derived percentage. `SalesRequest` gained `invoiceDiscAmt`, and
`TransactionCalculator` an overload that takes the discount as **an exact amount**:

> A coupon is worth ৳100, not 7.3529411%. Deriving a percentage and multiplying it back loses a
> paisa often enough to make the customer's order and the pharmacy's invoice disagree, which is
> the one thing neither may do.

Nothing else in the ERP sets that field, so every staff sale takes the percentage path it always
took — the golden regression still prints IDENTICAL.

```
order   (goods|code|coupon|total)      : 910.08 | UI0182162 | 150.00 | 760.08
invoice (no|goods|discount|payable)    : SL260958789 | 910.0800 | 150.0000 | 760.0800
```

**A free-delivery coupon** pays the Phase 9 delivery charge instead: the charge goes to zero and
the cart says "Free delivery with SHIPFREE…".

**Still a business decision (§3 B):** the discount reduces the pharmacy's own revenue — the ledger
books `Sales Revenue` net, with no separate promotional-expense account, so a platform-funded
coupon has nowhere to settle. `ECOM_COUPON_USAGE.AMOUNT` is where a campaign's cost is read from,
and it is exact. **BUSINESS/ACCOUNTING CONFIRMATION REQUIRED** before coupons are funded by anyone
other than the pharmacy honouring them.

### 5.4 Who may write one

A pharmacy may write coupons **for its own branch**; only a platform operator may write one scoped
`ALL_BRANCHES`. Enforced in `EcomCouponAdminService`, not on the screen. `BRANCH_SCOPE` is NOT
NULL with no default, so a coupon cannot become valid everywhere because a box was left empty.
Rights: create/edit on MOD54, and **Approve** to start or stop one — a coupon costs money, so
switching it on is an approval-grade act. Coupons are never deleted: orders paid with one point at
it.

### 5.5 Verified

`scratchpad/ecom-p12-coupons.mjs` (writing, the eight refusals, the cap, exactness on the invoice,
per-customer and total limits, the release on cancellation) and `p12-ui.mjs` (a pharmacy writes a
coupon on the new screen; a customer types it in the cart, sees the saving at checkout, and the
figure shown is the figure charged; the promotions list then shows one use and ৳150 given away).

## 6. As built — the storefront's offer surface

Until now a coupon could only be *typed*, never *found*: the cart took a code and the admin screen
wrote one, and nothing in between told a customer that a code existed. The storefront's flash-sale
rail and voucher wallet close that gap, and this section records what feeds them, because an offer
strip is the easiest place in a system to start making things up.

### 6.1 The endpoint

`GET /api/ecom/public/offers?branchId=&lat=&lng=&size=` — open, like the catalogue: a guest is
priced at retail, a signed-in customer's token prices the same medicines at their own tier.
Served by `EcomPublicOffersController` → `EcomOffersService`.

It returns four things:

| Field | What it is |
| --- | --- |
| `campaign` | the live coupon whose `ENDS_AT` is nearest, with `secondsLeft` — **or null** |
| `flash` | the twelve deepest genuine discounts at the scope |
| `deals` | everything else actually reduced |
| `vouchers` | live coupons this caller could spend, each with the cart's own verdict |

### 6.2 Three rules it does not bend

1. **A countdown needs a real deadline.** `POS_SALES_DISCOUNT` carries no dates — a product
   discount here is on until someone switches it off — so it can never justify a timer. A coupon
   has `ENDS_AT`, which is a moment a person typed. The clock therefore belongs to the coupon, and
   when no live coupon has an end date `campaign` is null, the rail keeps its deals and simply has
   no timer. It never invents a deadline to manufacture urgency.

2. **A saving is MRP less what this caller pays.** Never a percentage picked because it reads well,
   never a "was" price nobody was ever charged. `savingPct` is computed from the same `UnitOffer`
   the cart will price.

3. **Urgency is the pharmacy's own stock.** The bar under a flash card is pieces in hand against
   that branch's `REORDER_LEVEL` (surfaced as `Item.reorderPcs`, added additively to the catalogue
   DTO). A pharmacy that has not set a reorder level gets no bar at all. There is deliberately no
   "43 sold of 80" — this system does not count that, so the card does not claim it.

### 6.3 Not duplicated

- No new promotions table, entity or admin screen. Coupons are still written on the existing
  Promotions screen, and this only *shows* them.
- No second opinion on whether a coupon applies: every candidate goes through
  `EcomCouponService.quote(...)`, the same method the cart calls, so a voucher on the storefront is
  one the cart will honour, and an unusable one carries the cart's own sentence ("needs an order of
  ৳500 or more"). A refusal the basket cannot fix — wrong tier, wrong pharmacy, already spent —
  hides the card instead of teasing it.
- The clock lives in `FlashSale.jsx` and counts down from the **server's** `secondsLeft`, not from
  `endsAt` against the device clock, so a customer whose phone is an hour fast does not see an offer
  that has already closed or has not opened.

### 6.4 A consequence worth knowing

Making vouchers discoverable means every live coupon is now advertised. The first run surfaced
thirteen `P12…` coupons left behind by `ecom-p12-coupons.mjs` — "Another pharmacy", "Wholesalers
only" — which had been harmless only because nothing displayed them. They were stopped through the
admin API (`POST /api/ecom/admin/coupons/{id}/active?on=false`; never deleted, because orders point
at them). The lesson generalises: **a test suite that writes a coupon must stop it again**, exactly
as `branch-state.mjs` suspends and restores branch configuration.

### 6.5 Verified

Driven in the browser against a live backend, not inferred from a green build:

- With no live coupon carrying an end date: `campaign: null`, the rail titled "Today's best prices ·
  up to 11% off", **no timer drawn**.
- With one (`FLASHDEMO`, 10% off, ends in 26h): eyebrow "Limited time · 10% off with FLASHDEMO",
  the coupon's own name as the heading, and a clock ticking 19:59:46 → 19:59:19 across two reads.
- Stock bars matched the data: `16 in stock` against reorder 15 drew 53%; `10 left` against reorder
  19 drew 26% and flipped to "Running low"; a 5,000-piece shelf drew a full bar.
- Sign-in accepts `01712345678`, `1712345678`, `+8801712345678` and `880 1712 345678`, all
  resolving to the stored form, with the resolved number shown back before submitting.

## 7. The flash card, rebuilt to the reference layout

The card was rebuilt to match the marketplace reference exactly — discount pill, heart, seller with
a verified mark, two-line name, price with a struck MRP and a red percentage pill, a New tag, a
sold count, and a "Selling fast" progress bar. The question that mattered was not how to draw it
but **what could honestly fill each slot**, because three of those slots are precisely the ones a
shop window uses to invent pressure.

| Slot on the card | What fills it here |
| --- | --- |
| `−51%` pill | MRP less the caller's own price. Already real. |
| Heart | `ECOM_CUSTOMER_FAVOURITE` (V113). Real. |
| Seller name | The branch. Real. |
| **Verified** | **`ALL_BRANCH.BRANCH_VERIFIED` (V114)** — see below. |
| Two-line name | The product. Real. |
| Price + struck MRP | The caller's tier price and the pharmacy's MRP. Real. |
| **New** | `Item.addedAt` within 14 days — when the pharmacy put it on its books. Real. |
| **"38 sold"** | **Pieces sold online at that pharmacy in the last 7 days**, cancellations and returns excluded. Real. |
| **The bar, "38/80"** | **sold ÷ (sold + still in stock)**. Both halves counted. Real. |

### The two new figures

`EcomOrderItemRepository.piecesSoldSince(...)` groups `ECOM_ORDER_ITEM.QTY_PCS` by branch and
product over a seven-day window, excluding `CANCELLED` and `RETURNED` — a sale that came back is
not a sale, and counting it would put a figure on the storefront that the pharmacy's own books
disagree with. One query per page, not one per card.

The bar is the honest version of the pattern it imitates. A marketplace bar usually reads "38 of 80
sold", where 80 is a campaign allocation somebody chose. There is no such number here, so the
denominator is the one thing that is真 countable: **what went plus what is still there**. The
proportion is therefore the real split between sold and remaining, and the label under it says
`38/80` meaning exactly that. Nothing sold, no bar — rather than a bar sitting at zero implying a
target.

"Selling fast" appears only above 60% of that split, and reads "In stock" below it.

### V114 — the Verified badge, made real

The card previously carried "· Licensed" beside every pharmacy. `ALL_BRANCH` held no licence number
and no verification flag, so the badge appeared identically for a pharmacy nobody had ever checked
and one somebody had. It was removed in the previous pass. V114 is what puts it back honestly:

- `BRANCH_VERIFIED CHAR(1) DEFAULT 'N' NOT NULL` with a `Y`/`N` check
- `DRUG_LICENSE_NO`, `VERIFIED_BY`, `VERIFIED_AT`

**Every existing pharmacy starts at N.** Back-filling `Y` would assert a check that never happened —
the same lie relocated. Staff set it on the branch screen ("Licence seen"), and `applyVerification`
refuses `Y` without a licence number: *"Record the drug licence number before marking this pharmacy
verified: the badge tells customers somebody checked, so there has to be something checked."*
Switching to Y stamps who and when; switching back to N clears the stamp rather than leaving a
signature on a withdrawn claim.

`Item.branchVerified` and `Pharmacy.verified` carry it to the storefront, which shows the badge for
Y and the pharmacy's name alone for N.

## 8. Recently ordered

Built after being declined once on privacy grounds, and built with the three things that made it
declinable removed in the **query**, not the component:

- **No buyer.** `recentLines(...)` never selects a customer id, name or address. On a pharmacy,
  "someone just bought this" is otherwise a list of what people nearby are being treated for.
- **No prescription medicines.** `COALESCE(i.prescriptionFlagSnapshot, 'N') <> 'Y'` in the query.
  What somebody was prescribed is the most revealing row in the table.
- **No exact times.** "8 hrs ago", never a timestamp — a precise moment plus a small pharmacy
  narrows a purchase to a person.

The same medicine appears once however many people bought it, so the rail is a list of what is
moving rather than a count of who bought what. Three-day window, twelve rows.

It auto-scrolls at 3.5s and stops on hover, touch, keyboard focus, a hidden tab, and under
`prefers-reduced-motion` — the same `Rail` the flash strip uses, which gained `autoplayMs` and a
`controlsRef` so a section header's arrows drive the rail rather than the header growing its own
copy of the paging logic.

## 9. Verified

Live, against real data:

- Sold counts came back genuine and varied — `Ibucap 400mg` 668 sold with 10 left (bar 99%),
  `Emistat 8mg` 88 sold with 16 left (85%), and two listings with `sold=0` correctly drew **no bar
  at all** rather than an empty one.
- The recently-ordered payload carried exactly
  `productId, branchId, branchName, name, imageUrl, price, mrp, savingPct, whenText, minutesAgo,
  inStock` — no customer field of any kind — with coarse times ("5 hrs ago", "15 hrs ago").
- `branchVerified` was `false` on every card until a branch was actually verified, which is the
  default doing its job.
