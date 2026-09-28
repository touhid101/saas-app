# 15 — Search and Discovery

> Friends Pharma Platform · Platform SRS v1.0 · 9 September 2026

**Status vocabulary** — EXISTS · PARTIALLY EXISTS · MISSING · NEEDS MODIFICATION · NEW PLATFORM CAPABILITY · PLANNED · RECOMMENDED · BUSINESS DECISION REQUIRED · BUSINESS / LEGAL CONFIRMATION REQUIRED. These are never mixed.

| Capability | Status | Detail |
|---|---|---|
| Product search within a branch | EXISTS | By name, code, SKU or barcode, with a searchable picker |
| Global cross-module search | EXISTS | Ctrl+K, permission-gated |
| Search by generic name | PARTIALLY EXISTS | Generic is on the product and the global catalogue; no consumer-facing search reads it |
| Search by brand / manufacturer | PARTIALLY EXISTS | Company is on the product; same limitation |
| Pharmacy search | MISSING | No consumer-facing pharmacy directory |
| Location-based discovery | MISSING | No coordinates, no distance, no radius |
| Availability-aware results | PARTIALLY EXISTS | Stock is known per branch; nothing exposes it to a consumer |
| Price comparison across pharmacies | MISSING | Requires a cross-tenant read that the isolation model deliberately forbids today — a specific, designed exception |
| Ranking by rating or delivery time | MISSING | Neither exists |

## Ranking inputs

Distance, availability, price, rating, delivery time and preparation time. **None of the six is available today** — the first four are blocked on geolocation and marketplace visibility, and the last two do not exist as data. Ranking cannot be designed before those exist.
