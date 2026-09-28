# 06 — Customer Mobile Application

> Friends Pharma Platform · Platform SRS v1.0 · 9 September 2026

**Status vocabulary** — EXISTS · PARTIALLY EXISTS · MISSING · NEEDS MODIFICATION · NEW PLATFORM CAPABILITY · PLANNED · RECOMMENDED · BUSINESS DECISION REQUIRED · BUSINESS / LEGAL CONFIRMATION REQUIRED. These are never mixed.

Treated as its own product. The experience target is a modern delivery marketplace, kept clearly pharmacy-focused: dosage and strength are shown wherever a food app would show a size, and prescription status is never hidden behind a tap.

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

## Order status shown to the customer

| State | Meaning | May move to |
|---|---|---|
| PLACED | Customer has confirmed and, for prepaid, payment is authorised | ACCEPTED, REJECTED, CANCELLED |
| ACCEPTED | Pharmacy has confirmed it can fulfil | PREPARING, CANCELLED |
| PREPARING | Being picked and packed; stock reserved | READY, PARTIALLY_FULFILLED, CANCELLED |
| READY | Awaiting pickup or handover | PICKED_UP, CANCELLED |
| PICKED_UP | Courier has the order | OUT_FOR_DELIVERY |
| OUT_FOR_DELIVERY | En route to the customer | DELIVERED, DELIVERY_FAILED |
| DELIVERED | Customer has it; settlement becomes due | — (terminal) |
| REJECTED | Pharmacy declined — out of stock, closed, cannot dispense | — (terminal, refund if prepaid) |
| CANCELLED | Customer or platform cancelled | — (terminal, refund if prepaid) |
| DELIVERY_FAILED | Could not be delivered | RETURNED, OUT_FOR_DELIVERY (retry) |
| RETURNED | Back at the pharmacy; stock restored | — (terminal, refund if prepaid) |

> These states are proposed. If the business runs a different lifecycle — for example, no separate PREPARING, or a pharmacy-delivered path with no courier — the machine must be re-cut before build.
