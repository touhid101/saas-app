# Phase 2 — Module Inventory

> Friends Pharma ERP · SRS v2.0 · 9 September 2026

14 business modules were identified by correlating frontend pages, API endpoints, backend services and database entities.

| ID | Module | Purpose | Screens | APIs | Backend | Database | Status |
|---|---|---|---|---|---|---|---|
| M-01 | Authentication & Session | Sign in, token refresh, own profile and password, forced change | Login, Register, ForcePasswordChange | /api/auth (7) | AuthServiceImpl | ACL_USER, POS_REFRESH_TOKENS | IMPLEMENTED |
| M-02 | Access Control | Roles, per-branch module rights, password administration | RBAC, BranchRBAC, UserPermissions, ModuleRoles, PasswordAdmin | /api/v1/rbac (26), /api/v1/branch-rbac (3), /api/admin/passwords (3) | PermissionServiceImpl, PasswordAdminService | ACL_GROUP, ACL_USER_PERMISSION, POS_PERMISSIONS, ACL_PASSWORD_RESET_AUDIT | IMPLEMENTED |
| M-03 | Product & Catalogue | Branch product master, global catalogue, versioning, bulk import | ProductMaster, Catalogue, CatalogueForm, CatalogueMasters, BranchCatalogue | /api/products (13), /api/catalogue (4), /api/admin/global-products (8) | ProductServiceImpl, ProductBulkServiceImpl | POS_PRODUCT_INFO, GLOBAL_PRODUCT | PARTIALLY IMPLEMENTED |
| M-04 | Purchasing | Supplier invoice, approval, bonus, gift, cashback, supplier scoping | Purchase | /api/purchases (10) | PurchaseServiceImpl, StockPostingServiceImpl | POS_TRANSACTION_MST/DTL, POS_STOCK_BATCH | IMPLEMENTED |
| M-05 | Purchase Return & Credit | Return goods to a supplier and raise credit against payable | PurchaseReturn | /api/purchase-returns (4) | PurchaseReturnServiceImpl | POS_TRANSACTION_MST (type PR) | PARTIALLY IMPLEMENTED |
| M-06 | Supplier Settlement | Payment against invoices, advances, allocation, ageing | SupplierPayment | /api/supplier-payments (10) | SupplierPaymentServiceImpl | POS_SUPPLIER_PAYMENT, V_SUPPLIER_ACCOUNT_BRANCH | IMPLEMENTED |
| M-07 | Inventory | Batches, FEFO issue, movement ledger, valuation, expiry | Inventory | /api/inventory (12) | StockPostingServiceImpl, InventoryServiceImpl | POS_STOCK_BATCH, POS_STOCK_MOVEMENT | IMPLEMENTED |
| M-08 | Sales & POS | Full sales screen, fast till, returns, invoice merge, discounts | Sales, QuickSales, SalesReturn, InvoiceMerge, SalesDiscount, SalesActivity, SalesPerformance | /api/sales (18), /api/v1/pos (13), /api/sales-returns (4), /api/sales-merges (5), /api/v1/sales-discount (9) | SalesServiceImpl, PosServiceImpl | POS_TRANSACTION_MST (SL/SR), POS_CART | PARTIALLY IMPLEMENTED |
| M-09 | Fulfilment | Delivery assignment and cash collection | Delivery, Collection | /api/v1/collections (8) | CollectionServiceImpl | POS_DELIVERY_COLLECTION | IMPLEMENTED |
| M-10 | Requisition | Inter-branch stock request, approval, conversion | Requisition, NewRequisition | /api/v1/requisitions (16) | RequisitionServiceImpl | POS_REQUISITION_MST/DTL | IMPLEMENTED |
| M-11 | Reporting & Analytics | 18 grid reports, dashboard, internal analytics assistant | Reports, Dashboard, AIAgent | /api/reports (2), /api/dashboard (1), /api/ai (3) | ReportGridServiceImpl, DashboardServiceImpl, AiCopilotServiceImpl | 8 views + native queries | PARTIALLY IMPLEMENTED |
| M-12 | Tenancy & Subscription | Public sign-up, approval queue, plans, terms, invoices, the access gate | Register, Tenants, TenantDetail, Subscription, PlatformConsole | /api/public/register (2), /api/admin/onboarding (17), /api/admin/subscriptions (19), /api/subscription (3) | TenantOnboardingService, SubscriptionServiceImpl, TenantStatusService | TENANT_REGISTRATION, SUB_* | IMPLEMENTED |
| M-13 | Reference Data | Suppliers, customers, categories, units, areas, companies, generics | MasterData | MasterDataController (75) | MasterDataServiceImpl | POS_SUPPLIER_INFO, POS_CUSTOMER_INFO, POS_GENERIC_INFO … | IMPLEMENTED |
| M-14 | Notifications | In-app notifications and scheduled operational alerts | (topbar bell) | /api/notifications (1) | NotificationServiceImpl, CartCleanupScheduler | POS_NOTIFICATION | PARTIALLY IMPLEMENTED |
