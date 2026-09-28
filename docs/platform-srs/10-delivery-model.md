# 10 — Delivery Model

> Friends Pharma Platform · Platform SRS v1.0 · 9 September 2026

**Status vocabulary** — EXISTS · PARTIALLY EXISTS · MISSING · NEEDS MODIFICATION · NEW PLATFORM CAPABILITY · PLANNED · RECOMMENDED · BUSINESS DECISION REQUIRED · BUSINESS / LEGAL CONFIRMATION REQUIRED. These are never mixed.

| Capability | Status | Detail |
|---|---|---|
| Delivery person on a record | PARTIALLY EXISTS | POS_DELIVERY_COLLECTION carries a delivery man's id and name against an invoice. |
| Cash collection on delivery | EXISTS | Collected amount, balance, payment mode, cheque details. |
| Delivery partner identity and login | MISSING | No courier account, no app, no availability. |
| Delivery zones | MISSING | No zone, no radius, no coordinates. |
| Distance and ETA | MISSING | Not computable — no geolocation. |
| Assignment (manual or automatic) | MISSING | — |
| Pickup confirmation | MISSING | — |
| Proof of delivery | MISSING | No signature, photo or OTP. |
| Failed delivery and retry | MISSING | — |
| Delivery fee calculation | MISSING | No fee field on branch or order. |
| Live tracking | MISSING | Requires courier location streaming — none of the transport exists. |

## Recommendation

Delivery is the most expensive domain here and the least necessary at launch. Phase 2 can ship with **pickup or pharmacy-delivered** only: the pharmacy marks the order delivered and collects cash, which the existing collection model already supports. A courier network is worth building once order volume justifies it.
