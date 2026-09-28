# 09 — B2B Commerce

> Friends Pharma Platform · Platform SRS v1.0 · 9 September 2026

**Status vocabulary** — EXISTS · PARTIALLY EXISTS · MISSING · NEEDS MODIFICATION · NEW PLATFORM CAPABILITY · PLANNED · RECOMMENDED · BUSINESS DECISION REQUIRED · BUSINESS / LEGAL CONFIRMATION REQUIRED. These are never mixed.

The receiving half of this workflow is the strongest part of the existing system. What is missing is everything before the goods arrive.

| Capability | Today | Target | Detail |
|---|---|---|---|
| Supplier as a platform participant with its own login | MISSING | PLANNED | Suppliers today are records inside one pharmacy's data, not accounts. |
| Wholesale catalogue | MISSING | PLANNED | No supplier-owned product list or wholesale price exists. |
| Minimum order quantity (MOQ) | MISSING | PLANNED | No MOQ field anywhere. |
| Purchase order to a supplier | PARTIALLY EXISTS | NEEDS MODIFICATION | A purchase invoice is recorded after goods arrive. There is no order placed before, and no supplier confirmation step. |
| Supplier confirmation / acknowledgement | MISSING | PLANNED | — |
| Shipment and receiving against an order | PARTIALLY EXISTS | NEEDS MODIFICATION | Receiving exists and is strong — batch, expiry, cost per piece, FEFO. It just is not driven by an order. |
| Stock update on receipt | EXISTS | EXISTS | Fully implemented, including bonus, gift and cashback costing. |
| Supplier invoice | EXISTS | EXISTS | The purchase invoice is the supplier invoice. |
| Payment and outstanding balance | EXISTS | EXISTS | Payments, advances, allocation, ageing and a per-branch account view. |
| Credit terms | PARTIALLY EXISTS | NEEDS MODIFICATION | Payment type (cash/credit) exists; there are no terms, no due date, no credit limit on a supplier. |
| Supplier discovery across the platform | MISSING | PLANNED | A pharmacy can only see its own suppliers. |

```mermaid
flowchart LR
  A[Pharmacy] --> B[Search supplier]
  B --> C[Wholesale catalogue]
  C --> D[B2B cart]
  D --> E[Purchase order]
  E --> F[Supplier confirms]
  F --> G[Shipment]
  G --> H[Receiving]
  H --> I[Stock + cost per piece]
  I --> J[Payable]
```

Boxes H, I and J are built today and are fully tested. A through G are new.

> One existing rule will need revisiting: a supplier today represents exactly one manufacturer, and purchases are refused for any other maker's products. A platform distributor carrying many manufacturers breaks that assumption deliberately.
