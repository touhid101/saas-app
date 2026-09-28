# 01 — Baseline Comparison

> Friends Pharma Platform · Platform SRS v1.0 · 9 September 2026

**Status vocabulary** — EXISTS · PARTIALLY EXISTS · MISSING · NEEDS MODIFICATION · NEW PLATFORM CAPABILITY · PLANNED · RECOMMENDED · BUSINESS DECISION REQUIRED · BUSINESS / LEGAL CONFIRMATION REQUIRED. These are never mixed.

Baseline is the verified SRS v2.0 in `docs/srs/`. Each capability is classified against the platform vision.

| Capability | Baseline (current) | Platform vision | Classification |
|---|---|---|---|
| Pharmacy POS | Full till, quick sales, returns, merge | Retained unchanged | EXISTS |
| Inventory | Batch, FEFO, valuation, expiry | Retained; add reservation | NEEDS MODIFICATION |
| Purchasing | Invoice, approval, bonus/gift/cashback | Retained; add supplier orders | NEEDS MODIFICATION |
| Branch management | Organisation → branch, scoped | Becomes pharmacy → outlet | EXISTS |
| Subscription | Plans, terms, invoices, gate | Retained as revenue stream 1 | EXISTS |
| Pharmacy onboarding | Public sign-up → approval → provision | Extend with documents and verification | NEEDS MODIFICATION |
| Global catalogue | GLOBAL_PRODUCT with import to branch | Becomes the marketplace catalogue | NEEDS MODIFICATION |
| Staff and permissions | 9 roles, per-branch matrix | Retained; add marketplace modules | EXISTS |
| Customer records | Name, contact, credit, area | Replaced for consumers by real accounts | NEEDS MODIFICATION |
| Consumer app | None | Flutter marketplace app | NEW PLATFORM CAPABILITY |
| Pharmacy discovery | None | Location, hours, rating, delivery | NEW PLATFORM CAPABILITY |
| Marketplace order | None | Own aggregate with a state machine | NEW PLATFORM CAPABILITY |
| Delivery | Delivery man on a collection record | Delivery partner domain | NEW PLATFORM CAPABILITY |
| Online payment / settlement | None | Provider-agnostic; commission and payout | NEW PLATFORM CAPABILITY |
| Ratings and reviews | None | Trust and ranking | NEW PLATFORM CAPABILITY |
| B2B supplier marketplace | Per-pharmacy supplier records | Cross-platform participant | NEW PLATFORM CAPABILITY |
| Push / SMS / email | In-app only | Order-critical channels | NEW PLATFORM CAPABILITY |
| Platform financial reporting | Branch-scoped only | GMV, commission, take rate | NEW PLATFORM CAPABILITY |
