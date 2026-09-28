# 14 — Notification Model

> Friends Pharma Platform · Platform SRS v1.0 · 9 September 2026

**Status vocabulary** — EXISTS · PARTIALLY EXISTS · MISSING · NEEDS MODIFICATION · NEW PLATFORM CAPABILITY · PLANNED · RECOMMENDED · BUSINESS DECISION REQUIRED · BUSINESS / LEGAL CONFIRMATION REQUIRED. These are never mixed.

| Channel / event | Status | Detail |
|---|---|---|
| In-app notification | EXISTS | POS_NOTIFICATION with a bell in the top bar |
| Low-stock alert | EXISTS | Scheduled every 6 hours |
| Expiry alert | EXISTS | Scheduled daily at 08:00 |
| Push notification (mobile) | MISSING | No device token, no push service |
| SMS | MISSING | No provider, no outbound client |
| Email | MISSING | No mail sender configured |
| Order received (to pharmacy) | MISSING | The single most important notification in the platform. Without it a marketplace order sits unseen. |
| Order status (to customer) | MISSING | — |
| Pharmacy approval / rejection | MISSING | Decided in-app; the applicant is not told |
| Subscription expiry warning | PARTIALLY EXISTS | The screen warns when signed in; nothing reaches the pharmacy otherwise |

## Proposed event matrix

| Event | Customer | Pharmacy | Courier | Platform |
|---|---|---|---|---|
| Customer registered | Email/SMS verify | — | — | — |
| Pharmacy application received | — | Email | — | In-app |
| Pharmacy approved / rejected | — | Email + SMS | — | — |
| Order placed | Push | **Push + SMS fallback** | — | — |
| Order accepted / rejected | Push | — | — | — |
| Order ready | Push | — | Push | — |
| Out for delivery | Push | — | — | — |
| Delivered | Push | Push | — | — |
| Payment succeeded / failed | Push + email | Push | — | — |
| Subscription expiring | — | Email + in-app | — | — |

> Order-placed to the pharmacy is the one event the marketplace cannot function without, and the one that must not rely on a single channel.
