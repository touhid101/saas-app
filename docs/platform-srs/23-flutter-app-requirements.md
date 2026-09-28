# 23 — Flutter Application Requirements

> Friends Pharma Platform · Platform SRS v1.0 · 9 September 2026

**Status vocabulary** — EXISTS · PARTIALLY EXISTS · MISSING · NEEDS MODIFICATION · NEW PLATFORM CAPABILITY · PLANNED · RECOMMENDED · BUSINESS DECISION REQUIRED · BUSINESS / LEGAL CONFIRMATION REQUIRED. These are never mixed.

## Architecture

| Concern | Recommendation | Reason |
|---|---|---|
| State management | One approach across the app (Riverpod or Bloc) — chosen once | Mixed approaches in a marketplace app produce cart and session bugs that are very hard to reproduce |
| Navigation | Declarative router with deep links | Order notifications must open the order |
| Offline | Cache catalogue and last orders; never cache stock or price as truth | A stale price shown to a customer is a dispute |
| Auth | Token with silent refresh, mirroring the existing JWT model | The backend already does this for staff |
| Money | Integer minor units or Decimal — never double | The backend is BigDecimal scale 2 HALF_UP; the app must not disagree |
| Localisation | Bangla and English from the first screen | Retail pharmacy customers are not uniformly English-reading |
| Accessibility | Dynamic type, contrast, and dose text that never truncates | A clipped strength is a safety issue, not a layout issue |

## Screens

| Screen | Contents | Current foundation |
|---|---|---|
| Home | Location header, search, categories, nearby pharmacies, popular products, promotions, recent orders | Every element depends on geolocation or an order history — none exist |
| Search | Product name, generic, brand, category, pharmacy; recent and suggested | Generic and brand data exist in the catalogue; the search surface does not |
| Pharmacy discovery | Distance, rating, open now, delivery time, delivery fee, minimum order | Blocked entirely on PG-02 geolocation and PG-11 storefront profile |
| Pharmacy details | Profile, categories, in-store search, offers, delivery information | Catalogue exists; the public view does not |
| Product details | Name, generic, brand, strength, form, price, availability, pharmacy, description | Catalogue supplies most fields; description is missing |
| Cart | Lines, quantity, price, discount, delivery fee, total; one pharmacy per cart | New |
| Checkout | Address, pharmacy, delivery slot, payment method, prescription upload if required, summary | New |
| Order tracking | Live status timeline with the 11-state machine | New |
| Orders | History, reorder, invoice | New |
| Profile | Details, addresses, favourites, reviews, notification preferences | New |

## Non-negotiables for a pharmacy app

- Strength and form are shown wherever the product is shown — never name and price alone.
- Prescription-only status is visible in search results, not discovered at checkout.
- Substitution is never silent. If a pharmacy cannot supply the exact item, the customer decides.
- Expiry-dated goods: the customer sees what the pharmacy is committing to supply.
