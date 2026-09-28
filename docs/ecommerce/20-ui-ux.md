# 20 — UI / UX (existing frontend + storefront direction)

## 1. Existing frontend facts

* React 18 + Vite 5, plain CSS files (`styles.css`, `responsive.css`, `topbar.css`, per-area
  css), CSS tokens on `:root` (`--ink`, `--teal`, `--paper…`) with a dark theme block; `Inter`.
* Navigation: `App.jsx` switches on a `page` string; `usePageRoute` maps it to a path
  (`/sales`, `/salesactivity`, …). `react-router-dom` is installed but unused. `quicksales`
  already renders **outside the app shell** — the precedent for a storefront layout inside the
  same bundle.
* Components: `UI.jsx` (Card, Pill, Field, Modal, tables), `HrKit`, `ReceiptKit`, `Sidebar`,
  `Topbar`, `CustomerPicker`, `ProductImage` (fallback art), `ProductDetailSheet`, `SearchSelect`.
* Icons: hand-drawn SVG set in `components/Icons.jsx` (primary) + `lucide-react` on four pages.
  **No emoji icons** — the storefront uses these two sources only.
* Permissions: `usePermission` / `AppContext.can(moduleCode, right)` gate controls; the server is
  the authority.
* Uploads rendered via `lib/uploadUrl.js` (prefixes `VITE_API_BASE_URL`).

## 2. Visual reference

`friends-pharma-aura.html` (prototype folder `friends-pharma-marketplace-prototype/`,
`css/aura.css`, `js/aura.js`): light/dark app shell, left rail, command-bar search, offer slider
with sub-slider thumbnails, pharmacy cards with distance/open state, product cards with unit
selector and per-pharmacy price, ranked "choice" cards for comparison, side cart panel that does
not navigate away, three-step checkout, order tracking timeline, account pages, ops board.

It is a **visual** reference only: its pricing/stock/cart logic is mock and must not be ported.

## 3. The customer app is a SEPARATE project (decided 2026-09-22)

* `friends-pharma-ecommerce/` (sibling of `saas-app`): Vite + React, the Aura stylesheet
  verbatim, its own session keys (`fp_access_token`, never `pharma_*`), its own port (5180).
  It calls only `/api/ecom/**`. Nothing customer-facing lives inside the ERP frontend; the ERP
  keeps the staff consoles (E-Commerce Customers, later Orders and Promotions).
* State: server-driven — cart, prices and stock are fetched, never computed client-side; local
  state only for UI (scheme, panel collapsed, last branch).
* Pages: Home (slider, nearby pharmacies, categories) · Pharmacy list/map · Pharmacy detail ·
  Product search/list · Product detail + comparison · Cart (panel + page) · Checkout · Order
  confirmation · Orders & tracking · Profile (type, addresses, points) · Register / Login /
  Pending-approval.
* Staff additions inside the shell: Sales Activity source filter; **E-Commerce Orders** board;
  **E-Commerce Customers** (approve/type); **E-Commerce Promotions**; branch settings (ECOM
  toggle, location, radius, image); slider management (platform).
* Responsiveness: phone-first for the storefront (16 px gutters, sticky cart bar), the staff
  pages follow the existing `responsive.css` breakpoints.
* Accessibility: visible focus, labelled controls, keyboard-operable slider (arrows/dots already
  in the prototype), `prefers-reduced-motion` respected, contrast checked in both schemes.
* Copy: prices always with unit ("৳1.10 / pcs"), stock as "In stock" / "Only 3 box left", tier
  visible in the header ("Wholesaler pricing"), pending-approval state explained.

## Favourites — saved medicines (V113)

The first genuinely new thing on the storefront: nothing in the ERP held a wishlist, a favourite,
or a saved list of any kind, so unlike coupons and discounts this could not be surfaced from
something that already existed.

### The table

`PHARMA.ECOM_CUSTOMER_FAVOURITE` (V113, additive; nothing altered or dropped). Four columns that
matter: the customer, the branch, the product, and `BaseEntity`'s own audit columns.

**A favourite is a product AT a pharmacy.** The same medicine is a separate listing at every
branch with its own price and its own stock, so saving the medicine alone would save something
that has no price to show. The unique index is therefore
`(PID_ECOM_CUSTOMER, PID_BRANCH, PID_PRODUCT)`.

**No foreign key to the product.** A pharmacy may supersede or retire one — `POS_PRODUCT_INFO`
carries `SUPERSEDED_BY` for exactly that — and a hard FK would either block the retirement or
cascade a customer's list away. The customer and branch references are real, because neither is
ever removed.

### The endpoints

| Call | What it does |
| --- | --- |
| `GET /api/ecom/me/favourites` | the list, each row resolved against the catalogue as it stands now |
| `GET /api/ecom/me/favourites/keys` | just `branchId:productId`, to light hearts on a grid already loaded |
| `POST /api/ecom/me/favourites/toggle` | save or unsave; the response says which state it ended in |

Signed-in only, and scoped to the token's own customer — the caller never names whose list it is,
so there is no id to tamper with.

### Two decisions worth recording

**The list stores a reference, never a copy.** No price, no stock, no name is written to the
favourites table. Everything the screen shows is read back from the catalogue at the moment of
asking, priced for whoever is asking. A medicine saved at ৳4.00 that has since gone up shows what
it costs now; one saved as a retailer, opened after the account became wholesale, is priced at the
wholesale rate. A wishlist that quietly remembered the old price would be the single most
misleading screen on the storefront.

**A retired listing is shown as gone, not dropped.** It comes back with `available: false` and the
page says so, because somebody who saved something is owed the difference between "this is no
longer sold" and "this was never here".

### On the storefront

One React context (`lib/favourites.jsx`) — the only one in the app, and deliberately small. The
heart appears on the grid card, the flash card, and the favourites page itself, and tapping any of
them lights all the others plus the header count. It flips optimistically and flips back if the
server refuses: a save is one row, so making the customer watch a spinner on a bookmark would cost
every tap to insure against few.

Favourites are deliberately **not** kept in `localStorage`. A list that lived in this browser would
be a different list on their phone, which is exactly when somebody looks up the thing they saved.

### Verified

`scripts/ecom-p17-favourites.mjs` — 18 checks, all passing against a live backend: the list is
refused without a token; a new customer starts empty; the same medicine at two pharmacies saves as
two rows with keys `1:2649` and `180:2529`; toggling the same listing four more times leaves one
copy rather than four; another customer's list is empty; saving an unlisted product is refused with
"That medicine is not listed online."; and the resolved row carries a live catalogue item with the
caller's own tier while storing no price of its own.

## 10. One card, everywhere — and the branch bug it exposed

The flash rail had its own card; the grids had another. Two layouts meant two sets of rules, and
that is exactly how a "· Licensed" badge survived on one of them long after it had stopped meaning
anything. There is now **one** `ProductCard`, rendered by the flash rail, the recently-ordered rail,
the home rails, the listing grid, a pharmacy's shelf and the favourites page. A rail gives it a
fixed width; a grid lets the column decide. One card, two containers — not two cards.

The extras that only some callers have are passed in rather than guessed:

```
<ProductCard product={item} unit={…} deal={…} stockPcs={…} badge="6 hrs ago" />
```

`deal` carries `soldPcs` and `soldBarPct` from the offers endpoint. A card rendered without them
does not draw them. It never fills the gap with an estimate.

### Stock is now a number, not a band

Every card, the product page and each cart line print the pharmacy's own count — `355 pcs in
stock`, `16 PCS in stock`, `5,000 pcs in stock` — instead of "Only a few left". Somebody deciding
how many to order is owed the figure. The quantity steppers on the product page and in the cart now
stop at it, with the reason in the tooltip.

### The branch bug

Comparing the storefront against the database turned up a real defect that had nothing to do with
sold counts.

`GET /api/ecom/public/catalogue/{productId}` took **no branch**. A medicine held org-wide — one with
no `PID_BRANCH` — is listed at every online branch of its organisation with each counter's own
stock. Product 9 is **16 pieces** at Friends Pharma 60 Feet and **5,000** at PERF Chattogram GEC.
Asked without a branch, the service picked "the first online branch of the org".

So a customer who tapped the card reading *5,000 pcs in stock* landed on a product page reading
*16*, for what they believed was the same listing — and the cart would have gone to the wrong
pharmacy.

Fixed: the endpoint takes `branchId`, and the same ownership rule `quoteAt` already used decides
whether that pharmacy may sell the row. `Product.jsx` passes the `?b=` it already had in the URL,
and `EcomFavouriteService` resolves each saved listing at the branch it was saved from. Omitting the
parameter keeps the old behaviour, because a link without a branch still has to answer something.

Verified: `/catalogue/9?branchId=1` → 16 pcs at Friends Pharma 60 Feet; `?branchId=902` → 5,000 at
PERF Chattogram GEC. The product page now agrees with the card that led to it.

## 11. Verified against the database itself

`scripts/ecom-p18-sold-vs-db.mjs` asks the API, then puts the same question to Postgres through
`psql` and compares row by row — **14 checks, all passing**:

- Every card's sold figure equals `SUM(qty_pcs)` over seven days for that branch and product.
  22 cards compared, all equal.
- Nine deals with no sales drew **no bar at all**, not a bar at zero.
- Every bar equals sold ÷ (sold + in stock), to within a point.
- Cancelled and returned orders are excluded — proved by re-running the sum without the exclusion:
  26 pairs would differ.
- The card's stock equals the catalogue's own piece balance, per branch.
- Every recently-ordered row is a real line in `ECOM_ORDER` within three days, and `minutesAgo`
  matches `PLACED_AT` to within two minutes of what psql computes.
- The words agree with the number they were built from (`363m → "6 hrs ago"`, `966m → "16 hrs ago"`).
- 39 prescription products exist in the master and **7 were ordered in the window**; none of them
  appear in the feed, so the filter is doing work rather than having nothing to exclude.
- No row carries a field matching customer, buyer, mobile, phone, email or address.

Two of those checks failed on the first run. One was the suite's own bug — it compared against
`MAX(elapsed)`, the *oldest* line, while the feed shows the newest. The other was the branch defect
above, which was a real fault in the application.

## 12. The home gutter

`--gutter` is a token now. The page default is 16px (20px above 1100px); the home page carries
`.page.is-shop` and halves it to 10px/12px, because a shop front wants the width for cards more
than the margin wants the whitespace. It stays wide enough that a card's hover shadow is not
clipped.

## 13. The All-categories mega menu

Three panes: a scrolling rail of categories on the left, what is inside the highlighted one in the
middle, and a dark panel about it on the right. The rail highlights on hover **and on keyboard
focus**, so the menu is operable with Tab alone — verified with a real key press, not a synthetic
one (`element.focus()` from an automation context sets `activeElement` without dispatching a focus
event, so it proves nothing).

### What fills the middle pane

The marketplace this follows fills it with sub-categories — Smartphones, Tablets, Screen
Protectors. This product master *has* sub-categories (`POS_PRODUCT_SUB_CATEGORY`, already linked to
a category) but keeps them optional, and almost nothing carries one: "Analgesic" holds fifteen
medicines and exactly one is filed under a type. A sub-category-only pane would have been a single
link standing in for fifteen products.

So the pane shows **both** groups, each only when the catalogue returns it:

- **Browse by type** — sub-categories, where the pharmacy set any
- **Browse by manufacturer** — which every product has

Both arrive as facets carrying their own counts, computed over the matched rows before paging, so
every link leads to medicines that exist. A category with neither says so rather than opening an
empty column.

Supporting this needed two additive backend changes: `PosProductInfo.subCategoryInfo` mapped the
way `categoryInfo` already was, and a `subCategories` facet plus a `?subCategory=` filter on the
catalogue, alongside the existing category, company and pharmacy facets.

Verified live: hovering **Antacid** switched the pane and the panel together; following
*Browse by type → Tablet* landed on `/medicines?cat=Analgesic&sub=Tablet` titled "Tablet" showing
**2 of 2**, matching the facet's own count of 2. The menu closes on Escape, on a click outside, and
on following a link.

### The right panel

Real counts, not copy: *"15 medicines in stock from 1 manufacturer."* It is drawn from the same
fetch as the middle pane, so it cannot disagree with it. Below 1000px it is dropped and the two
useful panes keep the room; below 720px the whole menu becomes one column and fits a 390px phone
with no page overflow.

## 14. The gutter, and interactive affordances

`--gutter` is `clamp(12px, 5%, 72px)` — a share of the viewport rather than a fixed number, so the
margin grows with the screen instead of the content drifting into a band in the middle. The clamp
keeps it honest at both ends: never under 12px, where a card would touch the edge of a phone, and
never over 72px, where an ultrawide would open a canyon. Measured 4.9% at 1440px and 19.5px at
390px.

"See all" is a real control now rather than coloured text: a pill that fills on hover, an arrow that
travels, a visible focus ring, and a press state. The flash band's own View-all keeps its light
livery but gains the same arrow behaviour, so the two read as one control in two colours. The
All-categories trigger inverts and rotates its chevron while its menu is open, so the page says
which surface is showing.

All of it is inert under `prefers-reduced-motion`.
