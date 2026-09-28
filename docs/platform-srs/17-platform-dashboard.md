# 17 — Platform Dashboard

> Friends Pharma Platform · Platform SRS v1.0 · 9 September 2026

**Status vocabulary** — EXISTS · PARTIALLY EXISTS · MISSING · NEEDS MODIFICATION · NEW PLATFORM CAPABILITY · PLANNED · RECOMMENDED · BUSINESS DECISION REQUIRED · BUSINESS / LEGAL CONFIRMATION REQUIRED. These are never mixed.

A platform console exists today with tenant counts, subscription revenue, expiring renewals and activity. Every marketplace figure below is new.

| Metric | Status | Source |
|---|---|---|
| Total / active pharmacies | EXISTS | Platform console |
| Subscription revenue (MRR) | EXISTS | Platform console |
| Pharmacies at risk / expiring | EXISTS | Platform console |
| Pending applications | EXISTS | Approval queue |
| Registered / active customers | MISSING | Needs consumer identity |
| Orders placed / completed / cancelled | MISSING | Needs the order object |
| GMV | MISSING | Needs orders |
| Commission and take rate | MISSING | Needs a commission model |
| Acceptance and fulfilment rate | MISSING | Needs the state machine |
| Average delivery time | MISSING | Needs the delivery domain |
| Top pharmacies / products | PARTIALLY EXISTS | Per-tenant reporting exists; no platform aggregate |
