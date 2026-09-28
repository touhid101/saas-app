# 03 — Business Model

> Friends Pharma Platform · Platform SRS v1.0 · 9 September 2026

**Status vocabulary** — EXISTS · PARTIALLY EXISTS · MISSING · NEEDS MODIFICATION · NEW PLATFORM CAPABILITY · PLANNED · RECOMMENDED · BUSINESS DECISION REQUIRED · BUSINESS / LEGAL CONFIRMATION REQUIRED. These are never mixed.

Only one revenue stream exists today. Every other model below is unconfirmed and is marked accordingly — none should be treated as approved.

| Model | Status | Evidence / current state | What must be decided |
|---|---|---|---|
| Subscription | EXISTS | Plans, terms, invoices, payments and the access gate are built and working. Pharmacies already pay to use the system. | None — this is live. |
| Marketplace commission | BUSINESS DECISION REQUIRED | No commission table, rate, or calculation exists anywhere. | Rate, base (gross or net of delivery), when it accrues, and who bears refunds are all unanswered. |
| Transaction fee | BUSINESS DECISION REQUIRED | Not present. | Whether a per-order fee applies in addition to, or instead of, commission. |
| Supplier / distributor revenue | BUSINESS DECISION REQUIRED | No supplier-side commercial relationship exists. | Whether suppliers pay listing, commission or subscription. |
| Premium services | BUSINESS DECISION REQUIRED | Featured placement, promotion, premium analytics — none exist. | Whether the platform sells visibility, and how that interacts with clinical neutrality for medicines. |
| Delivery fee | BUSINESS DECISION REQUIRED | No delivery fee field on a branch or an order. | Who sets it — platform or pharmacy — and who keeps it. |

## Questions the business must answer before design

1. Is the platform's revenue **subscription, commission, or both**? The answer changes the settlement design fundamentally.
2. If commission: what rate, on what base (before or after delivery fee and discount), and does it accrue at delivery or at settlement?
3. Who sets the delivery fee, and who keeps it?
4. Who bears the cost of a refund — platform, pharmacy, or shared?
5. Is paid placement acceptable for medicines? This is as much an ethical question as a commercial one.
