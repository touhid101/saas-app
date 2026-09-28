# 16 — Ratings and Reviews

> Friends Pharma Platform · Platform SRS v1.0 · 9 September 2026

**Status vocabulary** — EXISTS · PARTIALLY EXISTS · MISSING · NEEDS MODIFICATION · NEW PLATFORM CAPABILITY · PLANNED · RECOMMENDED · BUSINESS DECISION REQUIRED · BUSINESS / LEGAL CONFIRMATION REQUIRED. These are never mixed.

| Capability | Today | Target |
|---|---|---|
| Pharmacy rating | MISSING | PLANNED |
| Product review | MISSING | PLANNED |
| Delivery rating | MISSING | PLANNED |
| Order feedback | MISSING | PLANNED |
| Complaint / dispute | MISSING | PLANNED |
| Moderation | MISSING | RECOMMENDED |

## Design notes

- A review should be attached to a **delivered order**, so it cannot be written by someone who never bought.
- Product reviews for medicines carry a risk food reviews do not: a customer describing a clinical effect. Moderation policy is a business and legal question before it is a feature.
- Pharmacy and delivery ratings should be separate — a pharmacy should not be punished for a courier.
