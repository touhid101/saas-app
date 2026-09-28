# Friends Pharma — B2B Pharmacy Platform SRS

**Friends Pharma Platform** · From single-tenant pharmacy POS to a multi-sided B2B pharmacy marketplace

Version 1.0 · 9 September 2026

> Built on the verified baseline SRS v2.0 of the running system (docs/srs/). The current-system column of every table below was read from source, the live PostgreSQL schema or a probe of the running API. Nothing forward-looking is presented as existing, and no legal requirement is invented.

**Status vocabulary** — EXISTS · PARTIALLY EXISTS · MISSING · NEEDS MODIFICATION · NEW PLATFORM CAPABILITY · PLANNED · RECOMMENDED · BUSINESS DECISION REQUIRED · BUSINESS / LEGAL CONFIRMATION REQUIRED. These are never mixed.

## 1. Executive summary

Friends Pharma today is a working, paid, multi-tenant pharmacy management system: point of sale, batch inventory, purchasing, supplier settlement, staff permissions, tenant onboarding and subscription billing, with 297 passing tests. The proposal is to keep all of it and add a consumer marketplace on top.

The analysis found more reusable foundation than expected — pharmacy onboarding, subscription billing, a rich platform product catalogue and the platform-to-pharmacy catalogue split all already exist. It also found that the marketplace itself rests on five things that do not exist at all: a customer who can log in, a location, an order, money movement, and a way to reach people.

## 2–7. Vision, model, actors

See `02-actors-and-roles.md`, `03-business-model.md`. One revenue stream (subscription) is live; every other model is unconfirmed and marked BUSINESS DECISION REQUIRED.

## 8–9. Current and target system

See `00-current-system-analysis.md` and `01-srs-baseline-comparison.md`.

## 10. Architecture

See `22-target-platform-architecture.md`. Recommendation: a modular monolith, not microservices.

## 11–16. Onboarding, catalogue, POS, purchasing

See `04`, `05`, `09`. Largely existing; extensions named.

## 17–21. Consumer app, marketplace, B2B, delivery

See `06`, `08`, `09`, `10`, `23`.

## 22–26. Payment, subscription, notification, search, reviews

See `11`, `14`, `15`, `16`.

## 27–29. Security, tenancy, reporting

See `12-multi-tenant-architecture.md`, `17`, `18`. The existing tenancy model already matches the target hierarchy.

## 30–34. Requirements, rules, data, APIs

See `19-platform-functional-requirements.md` and `20-platform-traceability.md`. Existing business rules are catalogued in the baseline at `docs/srs/07-business-rules.md`.

## 35–37. Gaps, roadmap, traceability

See `21-platform-gap-analysis.md`, `ROADMAP.md`, `20-platform-traceability.md`.
