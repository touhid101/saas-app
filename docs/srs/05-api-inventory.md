# Phase 5 — API Inventory

> Friends Pharma ERP · SRS v2.0 · 9 September 2026

34 REST controllers, ~380 operations. The Roles column is the class-level `@PreAuthorize`; several controllers apply finer method-level rules, and services apply the permission matrix beyond that.

| Controller | Base path | Roles | Ops | Purpose |
|---|---|---|---|---|
| AdminOnboardingController | /api/admin/onboarding | ROLE01 | 17 | Registration queue, approve or reject, provision a tenant |
| AdminSubscriptionController | /api/admin/subscriptions | ROLE01 | 19 | Plans, terms, subscriptions, invoices, payments |
| AiCopilotController | /api/ai | authenticated | 3 | Analytics answered from the ledger — no external service |
| AuthController | /api/auth | — | 7 | Login, refresh, logout, me, profile, own password, avatar |
| BranchCatalogueController | /api/catalogue | ROLE01 | 4 | Global catalogue import into a branch |
| BranchRbacController | /api/v1/branch-rbac | ROLE01, ROLE02 | 3 | Per-branch permission matrix |
| CollectionController | /api/v1/collections | matrix (MOD10) | 8 | Cash collection against sales invoices |
| DashboardController | /api/dashboard | authenticated | 1 | Operations and finance dashboard |
| GlobalMasterController | /api/admin/global-masters | ROLE01 | 17 | Platform-wide reference data |
| GlobalProductController | /api/admin/global-products | ROLE01 | 8 | Platform product catalogue |
| GlobalSearchController | /api/v1/search | — | 1 | Cross-module search (Ctrl+K) |
| InventoryController | /api/inventory | ROLE01 | 12 | Stock, batches, valuation, expiry, movements |
| MasterDataController | (various) | ROLE01, ROLE02 | 75 | Suppliers, customers, categories, units, areas, companies |
| NotificationController | /api/notifications | — | 1 | In-app notifications |
| OnboardingController | /api/public/register | — | 2 | Public pharmacy sign-up |
| PasswordAdminController | /api/admin/passwords | ROLE01 + platform operator | 3 | Account list, reset, audit trail |
| PlatformConsoleController | /api/admin/console | ROLE01 | 1 | Platform KPIs |
| PosController | /api/v1/pos | — | 13 | Cart, hold, checkout, barcode lookup |
| ProductController | /api/products | ROLE01/02/05 | 13 | Product CRUD, versioning, bulk import |
| ProductImageBackfillController | /api/admin/product-images | ROLE01 | 3 | Image backfill utility |
| PurchaseController | /api/purchases | ROLE01/02/05 | 10 | Purchase CRUD, approve, cancel |
| PurchaseReturnController | /api/purchase-returns | ROLE01/02/05/07 | 4 | Returns to supplier and credit |
| RbacController | /api/v1/rbac | ROLE01, ROLE02 | 26 | Users, roles, modules, permissions |
| ReportController | /api/v1/reports | — | 31 | Legacy surface — every endpoint fails (GAP-02) |
| ReportGridController | /api/reports | — | 2 | Catalogue and run for all 18 grid reports |
| RequisitionController | /api/v1/requisitions | matrix (MOD11) | 16 | Inter-branch requisition |
| SalesController | /api/sales | ROLE01/02/03/09 | 18 | Sales invoicing, approval, cancel |
| SalesDiscountController | /api/v1/sales-discount | matrix (MOD08) | 9 | Company and product discount tiers |
| SalesMergeController | /api/sales-merges | ROLE01/02/03/09 | 5 | Merge invoices for one delivery |
| SalesPercentController | /api/sales-percentages | ROLE01/02/05 | 6 | Sales percentage master |
| SalesPerformanceController | /api/sales-performance | — | 2 | Target against achievement |
| SalesReturnController | /api/sales-returns | ROLE01/02/03 | 4 | Customer returns |
| SubscriptionController | /api/subscription | authenticated | 3 | Own subscription status |
| SupplierPaymentController | /api/supplier-payments | ROLE01/02/04/05 | 10 | Payment, advance, allocation |
