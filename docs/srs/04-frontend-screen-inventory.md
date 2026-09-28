# Phase 4 — Frontend Screen Inventory

> Friends Pharma ERP · SRS v2.0 · 9 September 2026

36 page components. The shell renders one at a time from a `page` state key; there is no router library. Representative screens:

| ID | Module | Screen | Route key | Actor | Main function | API | Status |
|---|---|---|---|---|---|---|---|
| SC-01 | M-01 | Login | shell (not logged in) | Any | Username and password against a JWT | /api/auth/login | IMPLEMENTED |
| SC-02 | M-01 | ForcePasswordChange | stands in place of the app | Any mid-reset | Set own password before anything else is reachable | /api/auth/me/password | IMPLEMENTED |
| SC-03 | M-11 | Dashboard | dashboard | All | Operations and finance tiles; money gated on the Approve right | /api/dashboard | IMPLEMENTED |
| SC-04 | M-03 | ProductMaster | productmaster | ROLE01/02/05 | Product CRUD with versioning; searchable pickers; name unique per branch | /api/products | IMPLEMENTED |
| SC-05 | M-04 | Purchase | purchase | ROLE01/02/05 | Keyboard-first entry; bonus, gift and cashback; central TP%; supplier scoping | /api/purchases | IMPLEMENTED |
| SC-06 | M-05 | PurchaseReturn | purchasereturn | ROLE01/02/05/07 | Return to supplier; Returns-raised list | /api/purchase-returns | PARTIALLY IMPLEMENTED |
| SC-07 | M-06 | SupplierPayment | supplierpayment | ROLE01/02/04/05 | Pay invoices, hold advances, see ageing | /api/supplier-payments | IMPLEMENTED |
| SC-08 | M-08 | QuickSales | quicksales | ROLE01/02/03/09 | Fast till; three panes; bottom tab bar on a phone | /api/v1/pos | IMPLEMENTED |
| SC-09 | M-08 | Sales | sales | ROLE01/02/03/09 | Full sales invoice with customer and delivery | /api/sales | IMPLEMENTED |
| SC-10 | M-07 | Inventory | inventory | ROLE01 | Stock on hand, batches, valuation, expiry, movements | /api/inventory | IMPLEMENTED |
| SC-11 | M-11 | Reports | reports | Per matrix | 18 grid reports; each declares its own columns and filters | /api/reports | IMPLEMENTED |
| SC-12 | M-02 | PasswordAdmin | passwords | Platform operator | Credential state per account; issue a temporary password; reset trail | /api/admin/passwords | IMPLEMENTED |
| SC-13 | M-12 | Tenants / TenantDetail | tenants | Platform operator | Approval queue and tenant administration | /api/admin/onboarding | IMPLEMENTED |
| SC-14 | M-10 | Requisition | requisition | Per matrix | Inter-branch request, approve, convert | /api/v1/requisitions | IMPLEMENTED |
| SC-15 | M-13 | MasterData | master / orgstructure | ROLE01/02 | Suppliers, customers, categories, units, generics, companies | MasterDataController | IMPLEMENTED |
