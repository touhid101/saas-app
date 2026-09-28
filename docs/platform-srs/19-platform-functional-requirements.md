# 19 — Platform Functional Requirements

> Friends Pharma Platform · Platform SRS v1.0 · 9 September 2026

**Status vocabulary** — EXISTS · PARTIALLY EXISTS · MISSING · NEEDS MODIFICATION · NEW PLATFORM CAPABILITY · PLANNED · RECOMMENDED · BUSINESS DECISION REQUIRED · BUSINESS / LEGAL CONFIRMATION REQUIRED. These are never mixed.

| ID | Requirement | Domain | Status | Description | Evidence / note |
|---|---|---|---|---|---|
| FR-PLT-001 | Pharmacy registration | Platform | EXISTS | Public sign-up creating a queued application | OnboardingController · TENANT_REGISTRATION |
| FR-PLT-002 | Pharmacy verification | Platform | MISSING | Document capture, review, verified state and evidence trail | New |
| FR-PLT-003 | Pharmacy approval and provisioning | Platform | EXISTS | Approve with plan, term and template; provision in one transaction | AdminOnboardingController · TenantOnboardingService |
| FR-PLT-004 | Pharmacy subscription | Platform | EXISTS | Plans, terms, invoices, payments, access gate | AdminSubscriptionController · SUB_* |
| FR-PLT-005 | Storefront profile | Platform | MISSING | Hours, coordinates, radius, fee, minimum order, published flag | New |
| FR-PLT-006 | Commission configuration | Platform | BUSINESS DECISION REQUIRED | Rate, base and accrual — model undecided | New |
| FR-CUS-001 | Customer registration and login | Consumer | MISSING | Self-service identity with credentials and verification | New |
| FR-CUS-002 | Pharmacy discovery by location | Consumer | MISSING | Nearby, open, delivering to this address | New — blocked on geolocation |
| FR-CUS-003 | Product search | Consumer | PARTIALLY EXISTS | Name, generic, brand, category across published catalogues | Search exists inside a branch only |
| FR-CUS-004 | Customer addresses | Consumer | MISSING | Multiple saved addresses with coordinates | New |
| FR-CUS-005 | Cart | Consumer | PARTIALLY EXISTS | POS_CART is a till-side parked sale, not a customer's cart | Shape is a precedent, not a reuse |
| FR-ORD-001 | Place a marketplace order | Marketplace | MISSING | Order object distinct from a POS sale | New |
| FR-ORD-002 | Pharmacy accepts or rejects | Marketplace | MISSING | With a timeout | New |
| FR-ORD-003 | Order state machine | Marketplace | MISSING | 11 states with defined transitions | New |
| FR-ORD-004 | Stock reservation on acceptance | Marketplace | MISSING | Prevents selling the same last box twice | New — extends the batch ledger |
| FR-ORD-005 | Partial fulfilment | Marketplace | MISSING | Ordered vs fulfilled quantity; money follows fulfilled | New |
| FR-ORD-006 | Order tracking | Consumer | MISSING | Status timeline and notifications | New |
| FR-B2B-001 | Supplier as a platform participant | B2B | MISSING | Identity, catalogue, order queue | New |
| FR-B2B-002 | Wholesale purchase order | B2B | PARTIALLY EXISTS | Receiving is strong; the order that precedes it does not exist | Extends purchasing |
| FR-B2B-003 | Supplier settlement | B2B | EXISTS | Payments, advances, ageing per branch | SupplierPaymentController |
| FR-DLV-001 | Delivery partner identity | Delivery | MISSING | Account, availability, app | New |
| FR-DLV-002 | Delivery assignment | Delivery | MISSING | Manual or automatic | New |
| FR-DLV-003 | Proof of delivery | Delivery | MISSING | OTP, signature or photo | New |
| FR-PAY-001 | Online payment | Payment | MISSING | Provider-agnostic intent and status | New |
| FR-PAY-002 | Pharmacy settlement | Payment | MISSING | Gross, commission, net, payout, statement | New |
| FR-PAY-003 | Refund | Payment | MISSING | Against a platform-collected payment | New |
| FR-CMP-001 | Prescription enforcement | Compliance | PARTIALLY EXISTS | The flag exists; nothing acts on it | Configurable control recommended |
| FR-NOT-001 | Order notification to pharmacy | Notification | MISSING | Push plus fallback; the platform does not work without it | New |
| FR-RAT-001 | Pharmacy rating | Reviews | MISSING | Post-delivery, one per order | New |
