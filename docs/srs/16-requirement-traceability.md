# Phase 16 — Requirement Traceability

> Friends Pharma ERP · SRS v2.0 · 9 September 2026

| Requirement | Screen | API | Service | Database | Status |
|---|---|---|---|---|---|
| FR-001 Record a supplier purchase | Purchase.jsx | POST /api/purchases | PurchaseServiceImpl | POS_TRANSACTION_MST/DTL | IMPLEMENTED |
| FR-002 Receive stock at a correct cost per piece | StockPostingServiceImpl (netLineTotal, CostAllocation, unitCost) | POS_STOCK_BATCH | POS_STOCK_MOVEMENT | POS_STOCK_MOVEMENT | IMPLEMENTED |
| FR-003 Sell stock and fix its margin | QuickSales.jsx | /api/v1/pos | PosServiceImpl | StockPostingServiceImpl | IMPLEMENTED |
| FR-004 Keep product history when a product changes | ProductMaster.jsx | /api/products | ProductServiceImpl | PID_ROOT_PRODUCT / VERSION_NO / SUPERSEDED_BY | PARTIALLY IMPLEMENTED |
| FR-005 Administer a forgotten password safely | PasswordAdmin.jsx | /api/admin/passwords | PasswordAdminService | ACL_PASSWORD_RESET_AUDIT | IMPLEMENTED |
| FR-006 Report what suppliers gave beyond the price | Reports.jsx | GET /api/reports/supplier-benefit | ReportGridServiceImpl | SalesPurchaseReportRepository | IMPLEMENTED |
| FR-007 Restrict a pharmacy whose subscription has lapsed | TenantStatusFilter | TenantStatusService | SUB_SUBSCRIPTION | SUB_SUBSCRIPTION | PARTIALLY IMPLEMENTED |

## Calculations

| ID | Calculation | Source class |
|---|---|---|
| C-01 | Product derived pricing | ProductPricingCalculator |
| C-02 | Purchase line total — MNP basis | PurchaseCalculator.calculateLineTotal |
| C-03 | Purchase line total — MRP basis | PurchaseCalculator.calculateLineTotal |
| C-04 | Line gross (for TOTAL_DISCOUNT) | PurchaseCalculator.calculateLineGross |
| C-05 | Purchase invoice roll-up | PurchaseCalculator.calculateInvoice |
| C-06 | Net line value after header allocations | StockPostingServiceImpl.netLineTotal |
| C-07 | Gift cost allocation | StockPostingServiceImpl.CostAllocation |
| C-08 | Received pieces and cost per piece | StockPostingServiceImpl.receivedPcs / unitCost |
| C-09 | Cost guard on receipt | StockPostingServiceImpl.requireCostBelowMrp |
| C-10 | Sales price from MRP | SalesPriceCalculator |
| C-11 | COGS and gross profit | DashboardAnalyticsRepository / AiAnalyticsRepository |
| C-12 | Stock valuation | DashboardAnalyticsRepository.stockValue |
| C-13 | Supplier payable and ageing | V_PURCHASE_PAYABLE |
| C-14 | Supplier benefit reporting | SalesPurchaseReportRepository.supplierBenefit / ReportGridServiceImpl |
| C-15 | Temporary password strength | TemporaryPassword |
