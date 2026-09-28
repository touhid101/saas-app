# 11 — Payment and Settlement

> Friends Pharma Platform · Platform SRS v1.0 · 9 September 2026

**Status vocabulary** — EXISTS · PARTIALLY EXISTS · MISSING · NEEDS MODIFICATION · NEW PLATFORM CAPABILITY · PLANNED · RECOMMENDED · BUSINESS DECISION REQUIRED · BUSINESS / LEGAL CONFIRMATION REQUIRED. These are never mixed.

| Capability | Status | Detail |
|---|---|---|
| Cash at the counter | EXISTS | POS takes cash, card and credit as payment types on a sales invoice. |
| Cash on delivery | PARTIALLY EXISTS | Collection records cash against an invoice after delivery — the shape of COD without an order behind it. |
| Online payment capture | MISSING | No gateway. There is no outbound HTTP client anywhere in the backend. |
| Customer wallet | MISSING | — |
| Platform commission | MISSING | No table, rate or calculation. |
| Pharmacy settlement / payout | MISSING | No settlement run, no payout, no statement. |
| Refund | MISSING | Sales returns exist for over-the-counter trade; there is no refund of a platform-collected payment. |
| Subscription billing | EXISTS | Invoices and payments against a subscription, fully implemented. |
| Provider independence | RECOMMENDED | Model payment as an intent with a provider-agnostic status, so a gateway can be chosen later without reworking the order. |

## Provider-independent requirements

1. A payment is an **intent** with its own lifecycle, referenced by the order — never a column on the order.
2. Every state change is idempotent and carries the provider's reference, so a retried callback cannot double-credit.
3. Settlement is a **ledger**, not a report: gross, commission, adjustments, net, and a payout that references them.
4. Refunds reverse entries; they never delete them.
5. The money model must use the same precision as the backend — BigDecimal scale 2, HALF_UP.

> The commission model is undecided (see 03). Settlement cannot be designed until it is.
