# 00 — Current System Analysis

> Friends Pharma Platform · Platform SRS v1.0 · 9 September 2026

**Status vocabulary** — EXISTS · PARTIALLY EXISTS · MISSING · NEEDS MODIFICATION · NEW PLATFORM CAPABILITY · PLANNED · RECOMMENDED · BUSINESS DECISION REQUIRED · BUSINESS / LEGAL CONFIRMATION REQUIRED. These are never mixed.

Built on the verified baseline SRS v2.0 of the running system (docs/srs/). The current-system column of every table below was read from source, the live PostgreSQL schema or a probe of the running API. Nothing forward-looking is presented as existing, and no legal requirement is invented.

## What the running system is today

| Measure | Value |
|---|---|
| Modules | 14 |
| Frontend pages | 36 |
| Shared components | 16 |
| REST controllers | 34 (~380 operations) |
| Services | 49 |
| Repositories | 68 |
| JPA entities | 63 |
| Database tables | 64 |
| Database views | 8 |
| Flyway migrations | 72 (baselined at V63) |
| Automated tests | 297, all passing |

## Findings that decide the platform design

### Pharmacy onboarding already exists

**EXISTS** — Public sign-up → approval queue → provisioning of tenant, branch, roles and permissions in one transaction, then a subscription with expiry and grace. This is pharmacy onboarding. It needs extending — documents, verification, storefront profile — not building.

### Subscription billing already exists

**EXISTS** — Plans, terms, subscriptions, invoices, payments and a gate that refuses a lapsed tenant. Phase 1 of the roadmap is largely already delivered.

### A platform product catalogue already exists

**EXISTS** — GLOBAL_PRODUCT carries name, generic, company, category, strength, unit, pack sizes, barcode, image, suggested MRP/TP and a prescription flag — and branches import from it. The platform-catalogue / pharmacy-catalogue split the marketplace needs is already the model in use.

### Customers are records, not accounts

**MISSING** — POS_CUSTOMER_INFO has no password, no credential and no identity column of any kind. A customer today is something a pharmacy writes down. Consumer identity, and everything that depends on it, is entirely new.

### There is no geolocation anywhere

**MISSING** — Zero latitude/longitude columns in the whole schema, and no opening hours, delivery radius, delivery fee, minimum order or rating on a branch. "Pharmacies near me" — the first screen of the target app — has no data foundation at all.

### There is no order

**MISSING** — A sale is an invoice raised by staff at a till. There is no order table, no acceptance step, no state machine, no rejection, no partial fulfilment. A marketplace order is a different object from a POS sale and must be modelled as one.

### Delivery is internal, not a marketplace

**PARTIALLY EXISTS** — POS_DELIVERY_COLLECTION attaches a delivery man to an invoice and records cash collected. There is no courier as an actor, no assignment, no pickup, no ETA, no proof of delivery, no failed-delivery path.

### No money moves through the platform

**MISSING** — No commission, wallet or settlement table exists, and there is no payment gateway — no outbound HTTP client of any kind in the backend. Payments are recorded, never captured.

### Notification is in-app only

**PARTIALLY EXISTS** — POS_NOTIFICATION plus three scheduled alert jobs. No push, no SMS, no email. A marketplace cannot tell a pharmacy an order arrived.

### Prescription control has a hook

**PARTIALLY EXISTS** — GLOBAL_PRODUCT.PRESCRIPTION_FLAG exists and 9 of 29 global products carry it. The data is there; no workflow reads it.

## Strongest existing assets

- **Costing engine.** Supplier discount, cashback and free goods all reduce the cost per piece of what arrived; margin is fixed at the moment of sale from the issuing batch. 15 formulas, all unit-tested.
- **Batch and expiry ledger.** FEFO issue, cost snapshot per movement, full traceability — a genuine prerequisite for regulated retail.
- **Tenancy.** Organisation → branch, enforced from the JWT, with cross-tenant ids answered as 404.
- **Permission matrix.** Per-branch View/Create/Edit/Delete/Approve/Export/Print, changeable without a redeploy.
- **Subscription and onboarding.** Already a SaaS product with paying tenants.
- **Global product catalogue.** Rich enough to feed a consumer product page.
