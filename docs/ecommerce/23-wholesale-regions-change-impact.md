# 23 — Region-based wholesale, pharmacy selection, Evaly-inspired storefront

**Change-impact analysis. Step 1 and Step 2 of the change request; no code written yet
beyond the new storefront project's scaffold.**

Everything below was checked against the running database and the source tree on
2026-09-27, not inferred from the earlier documents. Where the change request assumes
something must be built and it already exists, that is recorded as *reuse*; where the
data will not support the new rule on day one, that is recorded as a **blocker**.

---

## 1. What already exists (reuse — do not rebuild)

| Change request | What is already there | Verdict |
|---|---|---|
| §2 "use the existing region master if one exists" | `pharma.all_region` (`pid_region`, `region_name`), with `all_division` → `all_district` beneath it, and **`all_branch.pid_region` already on the branch** | **Reuse.** No new region master. §23's "do not create a duplicate region master" is satisfied by using these. |
| §11 UOM and price by customer type | `util/CustomerPricing.java` already encodes exactly the requested rule: WHOLESALER and SPECIAL get BOX/STRIP/PCS, RETAILER gets PCS only; price resolution is already tier-aware through `SalesPriceCalculator` + `POS_SALES_DISCOUNT` | **No change.** The rule in §11 is the rule already in force. |
| §1 three customer types | `ecom_customer.customer_type` already holds `RETAILER` / `WHOLESALER` / `SPECIAL`, with the approval workflow from Phase 1 | **Reuse.** |
| §13 one cart, one fulfilment branch | `ecom_cart.pid_branch` exists; Phase 5 already enforces one open cart per customer per pharmacy | **Reuse.** The "cart branch" requirement is met; what is missing is *validating* that branch against type and region. |
| §12 branch-scoped coupons and offers | `ECOM_COUPON.branch_scope` + `ECOM_COUPON_BRANCH` (V110), and the same shape for points rules (V111) | **Reuse.** Revalidation on pharmacy switch is the only gap. |
| §14 POS integration | `SalesServiceImpl.createSale(req, SaleContext)` writing `POS_TRANSACTION_MST/DTL` with `FROM_SOURCE=ECOM`, FEFO deduction and the accounting hooks (Phase 6) | **No change.** The selected pharmacy is already the invoice's branch. |
| §1A retail nearby discovery | `GET /api/ecom/public/pharmacies` — Haversine, radius, tenant-status gated (Phase 2) | **Reuse**, plus a branch-type filter. |
| §15 order operations | MOD53 *E-Commerce Orders*, branch-scoped (Phase 8) | **Extend** with region / customer type / selected pharmacy columns. |

## 2. What is genuinely missing

| # | Gap | Smallest safe change |
|---|---|---|
| 1 | **No wholesale identification on a branch.** `all_branch` has no type, category or wholesale flag. | One column on `all_branch` — `branch_trade_type VARCHAR(10)` with `RETAIL` / `WHOLESALE` / `BOTH`, default `RETAIL`, NOT NULL with a default so existing rows are safe. No second branch master. |
| 2 | **No approved region on the customer.** `ecom_customer` has `reg_address`, `reg_area`, `reg_city`, `reg_latitude`, `reg_longitude` — a typed address, but no link to `all_region`. | `ecom_customer.pid_approved_region BIGINT NULL` → `all_region`, plus who approved it and when. Nullable: a retailer never needs one. |
| 3 | **No eligibility service.** Nothing resolves "which pharmacies may this customer buy from". | `EcomEligibilityService` — one place that answers it from the authenticated customer, and one endpoint per §6. |
| 4 | **Catalogue scoping is accepted, not enforced.** `/api/ecom/public/catalogue?branchId=…` honours whatever branch the caller names. For a retailer that is correct. For a wholesaler §9 forbids it. | Gate the branch parameter through the eligibility service for authenticated wholesale customers; leave the public/guest path alone. |
| 5 | **No selected-pharmacy shopping context.** The cart carries a branch, but there is no context *before* a cart exists. | Derive it from the open cart where one exists, and hold the customer's choice in a small session row or the cart itself — never on `ecom_customer`. |
| 6 | **No revalidation on pharmacy switch** (§22). | A revalidation pass reusing the Phase 5 cart pricing and the Phase 12/13 quote services, reporting changes rather than applying them silently. |
| 7 | **The storefront** (§16–§21). | New project `friends-pharma-ecommerce-platform`. |

## 3. Blockers in the data — business decisions required

These are not coding problems. **BUSINESS CONFIRMATION REQUIRED** before the region
rule can be switched on.

### 3.1 The region master has one row

`all_region` contains exactly one region: **Dhaka**. Every branch that has a region at
all points at it — including **branch 902 "PERF Chattogram GEC", whose coordinates
(22.359, 91.8215) are in Chattogram**. Four of the nine branches have no region at all.

If region eligibility shipped against today's data, a Dhaka wholesaler's eligible list
would contain a Chattogram pharmacy on day one. The region master has to be populated
and the branches corrected before the rule means anything. That is a data exercise for
whoever owns the branch master, not something to infer from coordinates.

### 3.2 122 approved wholesalers have no region

| Customer type | Approval | Count |
|---|---|---|
| WHOLESALER | APPROVED | **122** |
| SPECIAL | APPROVED | 4 |
| RETAILER | APPROVED | 3 |
| RETAILER | PENDING | 24 |

§8 says: *"Do not silently assign the customer to Dhaka or any default region."* Taken
literally, the day this ships **126 already-approved wholesale and special customers
lose wholesale access** until somebody approves a region for each of them.

Three honest options, none of which should be chosen without you:

1. **Hard cutover.** Ship the rule; the 126 see the "complete your business
   information" message from §8 and staff work through the queue. Correct, and it stops
   ordering for those accounts on day one.
2. **Backfill from the address they already gave.** `reg_city` / `reg_area` are
   populated for most; map those to regions in a one-off migration that writes an
   auditable `APPROVED_BY='MIGRATION'`. Not silent — it is recorded and reversible —
   but it is still the system deciding.
3. **Grace period.** A customer with no approved region keeps today's behaviour until a
   cut-off date, with the gap reported on the console. Staff clear the queue against a
   deadline rather than a switch.

My recommendation is (2) then (1): backfill what the customer themselves told you,
leave the rest refused, and let staff clear a short queue. But this is your money and
your customers, so it is your call.

### 3.3 Price comparison conflicts with §9

Phase 4 shipped `GET /api/ecom/public/compare/{productId}` — the same product priced
across pharmacies — and the customer app's Compare screen is built on it. §9 and §10
forbid a wholesaler seeing a catalogue that mixes branches after selection.

The two cannot both be true for a wholesaler. Options: restrict Compare to retailers;
or keep it for wholesalers but limited to the pharmacies they are *eligible* for, as a
pre-selection aid rather than a post-selection view. The second reading fits §21's
"distance can be an additional display attribute" better, but it is a call to make
rather than to assume. **Flagged, not resolved.**

## 4. Migration plan (no destructive steps)

`V112__wholesale_branch_and_customer_region.sql`:

```
ALTER TABLE pharma.all_branch    ADD COLUMN branch_trade_type VARCHAR(10) NOT NULL DEFAULT 'RETAIL'
                                 CHECK (branch_trade_type IN ('RETAIL','WHOLESALE','BOTH'));
ALTER TABLE pharma.ecom_customer ADD COLUMN pid_approved_region BIGINT NULL REFERENCES pharma.all_region,
                                 ADD COLUMN region_approved_by  VARCHAR(50),
                                 ADD COLUMN region_approved_at  TIMESTAMP;
```

Both are additive with safe defaults. No table is recreated, nothing is truncated, no
customer is assigned a branch, and `branch_id` is not added to the customer. Existing
rows keep working: every branch defaults to `RETAIL`, which is what they are today, and
every customer's approved region stays null until somebody approves one.

The region master is **not** seeded by the migration — see §3.1. Regions are the
organisation's own master data.

## 5. Evaly reference — what was actually inspected

Inspected live on 2026-09-27 in the built-in browser: `evaly.com.bd` (home) and
`evaly.com.bd/products`. What follows is the publicly observable layout, recorded as a
reference for *our own* original implementation. **No Evaly source, asset, logo or
product image is copied**, per §16.

**Home, top to bottom:** a dark announcement strip carrying a "Deliver to <area>"
control and a language toggle; a white sticky header with the wordmark, a search field
with a scope dropdown and a search button, and account / wishlist / cart icons; a
category bar with an "All Categories" button and a horizontally scrolling chip list (16
categories); a dark hero card with an eyebrow badge, a two-line headline, a subhead,
three trust badges (COD / verified sellers / 7-day returns) and two CTAs; two feature
cards; a **Flash Sale** block with an eyebrow, a live countdown and "View all"; product
rails; a "shop by brand" grid; an app-download and a sell-with-us block; a "why shop
here" block; an FAQ accordion; a four-column footer. On a narrow viewport a fixed
bottom tab bar appears: Home · Search · Cart · Orders · Account.

**Product card:** discount badge top-left, "Add to Cart" as an overlay control, a seller
line ("<store> · Verified"), the product name on two lines, the sale price large with
the original struck through and the discount percentage beside it, then small tags —
"New", "N sold", "Selling fast" with a sold/total ratio, and a delivery line.

**Listing page:** breadcrumb, title with a count and one explanatory sentence, a left
filter rail (price buckets *with result counts*, seller, rating, offers & delivery, a
"Clear all" and a "Show N results" button), and a toolbar with a Filters button, a
"Showing 24 of 48+" counter and a Sort dropdown (Relevance, Best Selling, Price ↑,
Price ↓, Top Rated, Biggest Discount). Four-column grid at desktop width.

**What maps to us, and what does not.** The counts-beside-filters, the sort list, the
sticky search header, the category chip rail, the card hierarchy and the mobile tab bar
all transfer directly. The seller/marketplace framing becomes the **pharmacy** framing —
"Mystery Store · Verified" becomes the selected pharmacy. What must **not** transfer:
scarcity theatre ("Selling fast", "7/20 sold", countdowns) invented by the front end.
§20 forbids fake stock and fake discounts, and our stock figures are real lot balances;
a countdown belongs to a real coupon window or it does not exist.

## 6. Files this will touch

**Backend — modify:** `AllBranch` entity + branch DTOs/controller (trade type),
`EcomCustomer` + `EcomCustomerResponses` + `EcomCustomerAdminService` (approved region
and its approval), `EcomPharmacyService` (branch-type filter), `EcomCatalogueService`
(gate the branch for wholesale customers), `EcomCartService` (validate the branch on
every read), `EcomCheckoutService` (revalidate at the point of writing the invoice),
`EcomCouponService` / `EcomPointsService` (already branch-aware; revalidation entry
points), `EcomOrderBoardService` (region and type columns).

**Backend — new:** `EcomEligibilityService`, `EcomWholesaleController`, `V112`.

**ERP frontend — modify:** Master Data › Branch form (trade type, region),
`EcomCustomers.jsx` (approve a region), `EcomOrders.jsx` (new columns).

**New project:** `friends-pharma-ecommerce-platform`.

## 7. Order of work

1. `V112` + branch trade type + the ERP branch form. *Nothing customer-facing changes.*
2. Region approval on the customer console, and the decision from §3.2 applied.
3. `EcomEligibilityService` + the eligible-pharmacies endpoint, with the security tests
   from §25 Test B (a Rangpur wholesaler forging a Dhaka branch id must be refused).
4. Gate the catalogue, cart and checkout through it.
5. Pharmacy selection and switching, with revalidation.
6. The storefront.

Steps 1–4 are the business rule and can ship without any new UI. Step 6 is the biggest
single piece and depends on none of the others being finished to start.

---

## 8. As built — steps 1 to 4

### 8.1 V112, and nothing it decided for anybody

`all_branch.branch_trade_type` (RETAIL / WHOLESALE / BOTH, **every existing branch
defaults to RETAIL**, which is what they are doing today) and
`ecom_customer.pid_approved_region` with who approved it and when, **left null for
every existing customer**. No backfill: §3.2 is still yours to decide, and when it is
decided it is one more migration.

The region master is reused as it stands. No new region table, no branch-region link
table, no second branch master.

### 8.2 `EcomEligibilityService` — the one place that decides

| Customer | May shop at |
|---|---|
| RETAILER | any pharmacy whose trade type is RETAIL or BOTH |
| WHOLESALER / SPECIAL | WHOLESALE or BOTH pharmacies **in their own approved region** |
| no approved region | nothing, with the sentence that says what to complete |

Called from `openCart` — where a branch id first arrives from outside and starts
costing money — and again in `placeOrder`, because a region can be corrected between
filling a cart and paying for it.

**Nothing is read from the request.** `GET /api/ecom/wholesale/eligible-pharmacies`
takes no parameters at all: the region is a fact about the authenticated customer, not
something a caller narrows.

### 8.3 The switch, and why it is off

`app.ecom.wholesale-regions-enforced` (`ECOM_WHOLESALE_REGIONS_ENFORCED`), **default
false**.

While it is false, eligibility behaves exactly as the platform did before V112 — any
pharmacy open to the marketplace — so nothing that works today stops working. Arming it
on a database whose region master holds one row and whose 122 approved wholesalers have
no region would stop all of them ordering the moment it deployed. Shipping the code and
switching the rule on are two decisions, and only the first one is mine.

Turn it on when the regions are populated, the branches are marked, and the customers
are approved. Reversible by config, not by code.

### 8.4 One thing the gate broke, and the fix

The first version refused a **pending** customer at the cart. That was wrong: a customer
waiting for approval has always been able to browse and fill a basket, and is refused at
checkout with a sentence explaining why. Taking that away turns a clear refusal at the
end into a baffling one at the beginning. `browsingScopeOf` now asks about the branch
without demanding approval; approval is still checked where it belongs.

### 8.5 Tests

`ecom-p14-regions.mjs` — §25 Tests A, B, C and D, run with the rule armed. It builds
its own fixture (a second region, two branches switched, five customers), snapshots the
branch rows to a file first so a crash is recoverable, and puts everything back:

```
B · Rangpur wholesaler
  forging a Dhaka branch id into the cart: 403 ECOM_BRANCH_WRONG_REGION
    "Friends Pharma 60 Feet is not in P14 Rangpur, which is where your account
     is approved to buy wholesale."
  nothing was written: true
D · retailer at a WHOLESALE-only pharmacy: 403 ECOM_BRANCH_WHOLESALE_ONLY
  a wholesaler with no approved region:    403 ECOM_NO_APPROVED_REGION
  nobody was quietly put in Dhaka:         true
```

Regressions on the shipping default (switch off): golden, P6, P6 race, P12, P13 — all
unchanged.

### 8.6 Still to do

The ERP branch form does not yet expose `branch_trade_type` or the region, and the
customer console does not yet approve a region — both are set by hand in SQL today, so
**step 1 is code-complete but not yet operable by staff**. The storefront's wholesale
selection screens are step 6.

