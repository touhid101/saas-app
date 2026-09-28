# 21 — Gap Analysis

> Friends Pharma Platform · Platform SRS v1.0 · 9 September 2026

**Status vocabulary** — EXISTS · PARTIALLY EXISTS · MISSING · NEEDS MODIFICATION · NEW PLATFORM CAPABILITY · PLANNED · RECOMMENDED · BUSINESS DECISION REQUIRED · BUSINESS / LEGAL CONFIRMATION REQUIRED. These are never mixed.

17 gaps between the current system and the target platform.

| ID | Severity | Area | Current behaviour | Impact | Recommendation |
|---|---|---|---|---|---|
| PG-01 | 🔴 Critical | Consumer identity | Customers are records with no credentials. | Nothing in the customer app can exist without it — no login, cart, order, address or review. | Build a consumer identity domain separate from ACL_USER, which is a staff model. |
| PG-02 | 🔴 Critical | Geolocation | Zero coordinate columns; the geographic hierarchy holds one row per level. | "Pharmacies near me", distance, ETA, delivery radius and zones are all impossible. | Add coordinates to branch and customer address; populate the geography; choose a distance strategy before designing discovery. |
| PG-03 | 🔴 Critical | Order object | No order exists. A sale is an invoice raised by staff. | The entire marketplace rests on an object that is not modelled. | Model the order as its own aggregate that produces a POS sale on fulfilment, rather than overloading POS_TRANSACTION_MST. |
| PG-04 | 🔴 Critical | Money movement | No commission, settlement, wallet or gateway. No outbound HTTP client at all. | The platform cannot take money, take a cut, or pay a pharmacy. | Design provider-agnostic payment intents and a settlement ledger. Decide the commission model first. |
| PG-05 | 🔴 Critical | Reaching people | In-app notification only. | A pharmacy is never told an order arrived; a customer is never told it is coming. | Push for both apps with an SMS fallback for order-critical events. |
| PG-06 | 🟠 High | Stock reservation | The batch ledger has no reservation state between acceptance and pickup. | Two customers can buy the same last box. | Add a reservation that holds pieces against an accepted order and releases on cancel or timeout. |
| PG-07 | 🟠 High | Delivery domain | A delivery man's name on a collection record. | No courier, assignment, pickup, ETA, proof or failure path. | New domain. Consider deferring to Phase 3 and launching pickup-only or pharmacy-delivered. |
| PG-08 | 🟠 High | Marketplace visibility | A branch product cannot be marked sellable online, and a branch cannot be published or unpublished. | A pharmacy cannot control what the public sees. | Add a published flag at both levels; default to off. |
| PG-09 | 🟠 High | Prescription workflow | The flag exists on 9 of 29 global products; nothing reads it. | A prescription-only medicine can be ordered like sweets. | Configurable enforcement, pending legal confirmation. |
| PG-10 | 🟠 High | Supplier as participant | Suppliers are per-pharmacy records tied to one manufacturer. | No cross-platform supplier discovery or wholesale ordering. | Phase 4. The manufacturer-scoping rule will need revisiting. |
| PG-11 | 🟡 Medium | Storefront profile | No hours, radius, fee, minimum order or preparation time. | Discovery cannot filter, and orders arrive when a pharmacy is shut. | Extend the branch profile. |
| PG-12 | 🟡 Medium | Ratings and reviews | None. | No trust signal and no ranking input. | Phase 2 or 3. |
| PG-13 | 🟡 Medium | Verification | Approval is an operator's judgement with no evidence trail. | A regulated trade with no record of why a pharmacy was admitted. | Document capture and a verification state. |
| PG-14 | 🟡 Medium | Cross-tenant reads | Isolation forbids reading another tenant's data — correctly, for staff. | Discovery and price comparison require exactly that. | Design one narrow, audited public-catalogue read path. Do not weaken the general rule. |
| PG-15 | 🟡 Medium | Platform financial reporting | Reports are branch-scoped. | No GMV, commission, take rate or platform revenue. | New reporting layer above the tenant. |
| PG-16 | 🟢 Low | Support and disputes | No role, case or ticket. | Complaints have nowhere to go. | Phase 3+. |
| PG-17 | 🟢 Low | Sales discount feature | The table exists but the feature has never worked — 0 rows, PID_BRANCH never set. | Promotions are unavailable to the marketplace. | Fix the existing defect (baseline GAP-08) before building promotions on it. |

## Reading the severities

The five critical gaps are not independent problems — they are the five things a marketplace **is**: an identity to order with, a way to find a shop, an order, money, and a way to tell someone. None can be deferred, and none is a modification of something existing.

The encouraging half is what is *not* on this list: costing, stock, batch traceability, tenancy, permissions, subscription and onboarding are all sound and reusable.
