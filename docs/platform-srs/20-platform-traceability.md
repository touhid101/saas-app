# 20 — Traceability

> Friends Pharma Platform · Platform SRS v1.0 · 9 September 2026

**Status vocabulary** — EXISTS · PARTIALLY EXISTS · MISSING · NEEDS MODIFICATION · NEW PLATFORM CAPABILITY · PLANNED · RECOMMENDED · BUSINESS DECISION REQUIRED · BUSINESS / LEGAL CONFIRMATION REQUIRED. These are never mixed.

## Existing functionality — baseline to implementation

| Capability | Screen | API | Service | Database |
|---|---|---|---|---|
| Pharmacy onboarding | Register, Tenants | /api/public/register, /api/admin/onboarding | TenantOnboardingService | TENANT_REGISTRATION, ALL_ORGANIZATION, ALL_BRANCH |
| Subscription | Subscription, TenantDetail | /api/admin/subscriptions | SubscriptionServiceImpl | SUB_PLAN, SUB_TERM, SUB_SUBSCRIPTION, SUB_INVOICE |
| Global catalogue | Catalogue, BranchCatalogue | /api/admin/global-products, /api/catalogue | GlobalProductServiceImpl | GLOBAL_PRODUCT |
| Pharmacy catalogue | ProductMaster | /api/products | ProductServiceImpl | POS_PRODUCT_INFO |
| Inventory | Inventory | /api/inventory | StockPostingServiceImpl | POS_STOCK_BATCH, POS_STOCK_MOVEMENT |
| POS | QuickSales, Sales | /api/v1/pos, /api/sales | PosServiceImpl, SalesServiceImpl | POS_TRANSACTION_MST/DTL, POS_CART |
| Purchasing | Purchase | /api/purchases | PurchaseServiceImpl | POS_TRANSACTION_MST (PU) |
| Supplier settlement | SupplierPayment | /api/supplier-payments | SupplierPaymentServiceImpl | POS_SUPPLIER_PAYMENT, V_SUPPLIER_ACCOUNT_BRANCH |
| Delivery / collection | Delivery, Collection | /api/v1/collections | CollectionServiceImpl | POS_DELIVERY_COLLECTION |

## New functionality — requirement to proposed design

| Requirement | Proposed capability | Proposed app | Proposed API | Proposed data |
|---|---|---|---|---|
| FR-CUS-001 | Consumer identity | Flutter | /api/consumer/auth | CONSUMER, CONSUMER_ADDRESS |
| FR-CUS-002 | Pharmacy discovery | Flutter | /api/discovery/pharmacies | BRANCH_STOREFRONT (coordinates, hours, radius, fee) |
| FR-CUS-003 | Catalogue search | Flutter | /api/discovery/products | Published-catalogue read path |
| FR-ORD-001..006 | Marketplace order | Flutter + POS | /api/orders | ORDER, ORDER_LINE, ORDER_EVENT, STOCK_RESERVATION |
| FR-PAY-001..003 | Payment and settlement | Flutter + admin | /api/payments, /api/settlements | PAYMENT_INTENT, SETTLEMENT_LEDGER, PAYOUT |
| FR-DLV-001..003 | Delivery | Courier app | /api/delivery | COURIER, DELIVERY_ASSIGNMENT, POD |
| FR-B2B-001..002 | B2B ordering | Web | /api/b2b | SUPPLIER_ACCOUNT, WHOLESALE_PRICE, PURCHASE_ORDER |
| FR-RAT-001 | Ratings | Flutter | /api/reviews | REVIEW |
| FR-NOT-001 | Push and SMS | All | /api/notifications | DEVICE_TOKEN, NOTIFICATION_LOG |
