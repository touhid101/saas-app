# Friends Pharma — React Service to REST API Mapping

## Authentication
| React Call | HTTP Method | Endpoint | Notes |
|-----------|-------------|----------|-------|
| login(username, password) | POST | /api/auth/login | Returns {accessToken, refreshToken, user} |
| refresh token | POST | /api/auth/refresh | Body: {refreshToken} |
| logout | POST | /api/auth/logout | Invalidates refresh token |
| getCurrentUser | GET | /api/auth/me | Returns UserResponse |
| uploadAvatar | POST | /api/auth/me/avatar | Multipart, max 2MB |

## Organization Hierarchy
| React Entity | Method | Endpoint |
|-------------|--------|----------|
| ORGANIZATIONS | GET/POST/PUT/DELETE | /api/organizations/{id?} |
| ORG_COMPANIES | GET/POST/PUT/DELETE | /api/companies/{id?} |
| BRANCHES | GET/POST/PUT/DELETE | /api/branches/{id?} |
| REGIONS | GET/POST/PUT/DELETE | /api/regions/{id?} |
| DIVISIONS | GET/POST/PUT/DELETE | /api/divisions/{id?} |
| DISTRICTS | GET/POST/PUT/DELETE | /api/districts/{id?} |
| THANAS | GET/POST/PUT/DELETE | /api/thanas/{id?} |
| AREAS | GET/POST/PUT/DELETE | /api/areas/{id?} |

## Master Data
| React Entity | Method | Endpoint |
|-------------|--------|----------|
| CATEGORIES | GET/POST/PUT/DELETE | /api/categories/{id?} |
| SUBCATEGORIES | GET/POST/PUT/DELETE | /api/sub-categories/{id?}?categoryId= |
| UNITS | GET/POST/PUT/DELETE | /api/units/{id?} |
| GENERICS | GET/POST/PUT/DELETE | /api/generics/{id?} |
| COMPANIES (product mfr) | GET/POST/PUT/DELETE | /api/product-companies/{id?} |
| LEAFS | GET/POST/PUT/DELETE | /api/leafs/{id?} |
| BOXES | GET/POST/PUT/DELETE | /api/boxes/{id?}?leafId= |
| PRODUCTS | GET/POST/PUT/DELETE | /api/products/{id?} |
| Product image upload | POST | /api/products/{id}/image |
| Product barcode lookup | GET | /api/products/barcode/{barcode} |
| CUSTOMERS | GET/POST/PUT/DELETE | /api/customers/{id?} |
| SUPPLIERS | GET/POST/PUT/DELETE | /api/suppliers/{id?} |
| DEPARTMENTS | GET/POST/PUT/DELETE | /api/departments/{id?} |
| DESIGNATIONS | GET/POST/PUT/DELETE | /api/designations/{id?} |
| EMPLOYEES | GET/POST/PUT/DELETE | /api/employees/{id?} |

## Purchase
| React Action | Method | Endpoint |
|-------------|--------|----------|
| List purchases | GET | /api/purchases?branchId=&dateFrom=&dateTo=&status=&supplierId= |
| Create purchase | POST | /api/purchases |
| Get purchase | GET | /api/purchases/{id} |
| Update purchase | PUT | /api/purchases/{id} |
| Complete purchase | POST | /api/purchases/{id}/complete |
| Cancel purchase | POST | /api/purchases/{id}/cancel |
| Purchase Return: create | POST | /api/purchase-returns |
| Purchase Return: list | GET | /api/purchase-returns?purchaseId= |

## Sales
| React Action | Method | Endpoint |
|-------------|--------|----------|
| List sales | GET | /api/sales?branchId=&dateFrom=&dateTo=&stage=&customerId= |
| Create sale | POST | /api/sales |
| Get sale | GET | /api/sales/{id} |
| FEFO preview | POST | /api/sales/fefo-preview | Body: {productId, qty, unitMode, branchId} |
| Finalize sale | POST | /api/sales/{id}/finalize |
| Sales Return: create | POST | /api/sales-returns |
| Sales Return: list | GET | /api/sales-returns?saleId= |

## Sales Activity / Delivery Workflow
| React Action | Method | Endpoint |
|-------------|--------|----------|
| List with filters | GET | /api/sales-activity?stage=&payStatus=&dmId=&dateFrom=&dateTo=&q= |
| Advance stage | POST | /api/sales-activity/{id}/advance |
| Assign delivery man | POST | /api/sales-activity/{id}/assign-delivery-man | Body: {deliveryManId} |
| Bulk assign DM | POST | /api/sales-activity/bulk-assign-delivery-man | Body: {invoiceIds:[], deliveryManId} |
| Start delivery | POST | /api/sales-activity/{id}/start-delivery |
| Mark delivered | POST | /api/sales-activity/{id}/mark-delivered |
| Cancel order | POST | /api/sales-activity/{id}/cancel |

## POS / Quick Sales
| React Action | Method | Endpoint |
|-------------|--------|----------|
| Product search | GET | /api/pos/products?q=&categoryId=&branchId= |
| Barcode lookup | GET | /api/pos/products/barcode/{code} |
| Create cart (hold) | POST | /api/pos/cart |
| Get cart | GET | /api/pos/cart/{sessionId} |
| Add to cart | POST | /api/pos/cart/{sessionId}/add |
| Update qty | PUT | /api/pos/cart/{sessionId}/lines/{lineId} |
| Remove from cart | DELETE | /api/pos/cart/{sessionId}/lines/{lineId} |
| Finalize POS sale | POST | /api/pos/cart/{sessionId}/finalize |
| Receipt data | GET | /api/pos/receipt/{saleId} |

## Collection
| React Action | Method | Endpoint |
|-------------|--------|----------|
| Record collection | POST | /api/collections |
| List collections | GET | /api/collections?deliveryManId=&dateFrom=&dateTo= |
| Collection by DM summary | GET | /api/collections/summary-by-dm |

## Sales Discount (Sales%)
| React Action | Method | Endpoint |
|-------------|--------|----------|
| Get all rules | GET | /api/sales-discounts?companyId=&productId= |
| Set product rule | PUT | /api/sales-discounts/{productId} | Body: {box,strip,pcs,special} |
| Bulk set for company | POST | /api/sales-discounts/bulk | Body: {companyId, box, strip, pcs, special} |
| Effective price calc | POST | /api/sales-discounts/effective-price | Body: {productId, unitMode, customerId} |
| Export rules CSV | GET | /api/sales-discounts/export |

## Inventory
| React Action | Method | Endpoint |
|-------------|--------|----------|
| Stock ledger | GET | /api/inventory/stock?productId=&categoryId=&branchId= |
| Batch stock | GET | /api/inventory/batches?productId=&branchId= |
| Expiry report | GET | /api/inventory/expiry?days=30&branchId= |
| Low stock alerts | GET | /api/inventory/alerts/low-stock?branchId= |
| Out of stock | GET | /api/inventory/alerts/out-of-stock?branchId= |
| Stock valuation | GET | /api/inventory/valuation?branchId=&categoryId= |

## Requisition
| React Action | Method | Endpoint |
|-------------|--------|----------|
| List requisitions | GET | /api/requisitions?status=&branchId=&source= |
| Create requisition | POST | /api/requisitions |
| Create from AI | POST | /api/requisitions/from-ai | Body: {recommendations:[{productId,suggestedBoxQty,supplierId,...}]} |
| Update (editable qty) | PUT | /api/requisitions/{id} |
| Submit for approval | POST | /api/requisitions/{id}/submit |
| Approve | POST | /api/requisitions/{id}/approve |
| Reject | POST | /api/requisitions/{id}/reject |
| Return for correction | POST | /api/requisitions/{id}/return |
| Convert to purchase | POST | /api/requisitions/{id}/convert |

## Invoice Merge
| React Action | Method | Endpoint |
|-------------|--------|----------|
| Get customer invoices | GET | /api/invoice-merge/eligible?customerId= |
| Merge invoices | POST | /api/invoice-merge | Body: {invoiceIds:[], customerId, reason} |
| Merge history | GET | /api/invoice-merge/history?customerId= |

## Reports
| React Report | Method | Endpoint |
|-------------|--------|----------|
| Sales Summary | GET | /api/reports/sales-summary?dateFrom=&dateTo=&branchId= |
| Daily Sales | GET | /api/reports/daily-sales?dateFrom=&dateTo=&branchId= |
| Sales by Product | GET | /api/reports/sales-by-product?dateFrom=&dateTo= |
| Sales by Customer | GET | /api/reports/sales-by-customer?dateFrom=&dateTo= |
| Sales by Category | GET | /api/reports/sales-by-category?dateFrom=&dateTo= |
| Sales by Region | GET | /api/reports/sales-by-region?dateFrom=&dateTo= |
| Sales Return Register | GET | /api/reports/sales-return?dateFrom=&dateTo= |
| Customer Ledger | GET | /api/reports/customer-ledger?customerId= |
| Customer Due | GET | /api/reports/customer-due |
| Top Customers | GET | /api/reports/top-customers?limit=10&dateFrom=&dateTo= |
| Purchase Summary | GET | /api/reports/purchase-summary?dateFrom=&dateTo= |
| Purchase by Product | GET | /api/reports/purchase-by-product |
| Purchase by Supplier | GET | /api/reports/purchase-by-supplier |
| Purchase Return | GET | /api/reports/purchase-return |
| Supplier Ledger | GET | /api/reports/supplier-ledger?supplierId= |
| Supplier Due | GET | /api/reports/supplier-due |
| Current Stock | GET | /api/reports/current-stock?branchId=&categoryId= |
| Stock Alert | GET | /api/reports/stock-alert |
| Batch Stock | GET | /api/reports/batch-stock |
| Near Expiry 30d | GET | /api/reports/expiry?days=30 |
| Near Expiry 90d | GET | /api/reports/expiry?days=90 |
| Expired | GET | /api/reports/expired |
| Stock Valuation | GET | /api/reports/stock-valuation |
| **Daily P&L** | GET | /api/reports/daily-pnl?dateFrom=&dateTo= |
| **Product P&L** | GET | /api/reports/product-pnl?dateFrom=&dateTo= |
| **Monthly P&L** | GET | /api/reports/monthly-pnl?year= |
| Delivery Tracking | GET | /api/reports/delivery-tracking |
| Collection Summary | GET | /api/reports/collection-summary?dateFrom=&dateTo= |

## Dashboard
| React KPI | Method | Endpoint |
|----------|--------|----------|
| All 16 KPIs | GET | /api/dashboard/kpis?branchId=&dateFrom=&dateTo= |
| Sales trend (14d) | GET | /api/dashboard/sales-trend?days=14&branchId= |
| Region breakdown | GET | /api/dashboard/region-breakdown?dateFrom=&dateTo= |
| Category split | GET | /api/dashboard/category-split?dateFrom=&dateTo= |
| Payment split | GET | /api/dashboard/payment-split |
| Top products | GET | /api/dashboard/top-products?limit=10 |
| Top customers | GET | /api/dashboard/top-customers?limit=10 |
| Delivery status | GET | /api/dashboard/delivery-status |

## AI Agent
| React Action | Method | Endpoint |
|-------------|--------|----------|
| Get recommendations | GET | /api/ai/recommendations?leadTimeDays=7&safetyFactor=1.4&windowDays=14 |
| Get hot products | GET | /api/ai/hot-products?limit=12 |

## RBAC
| React Action | Method | Endpoint |
|-------------|--------|----------|
| Get permission matrix | GET | /api/rbac/permissions/{roleId} |
| Save permissions | PUT | /api/rbac/permissions/{roleId} |
| List roles | GET | /api/rbac/roles |
| Create role | POST | /api/rbac/roles |
| Update role | PUT | /api/rbac/roles/{id} |
| List modules/menus | GET | /api/rbac/modules |
| Create module | POST | /api/rbac/modules |
| Update module | PUT | /api/rbac/modules/{id} |
| List users | GET | /api/rbac/users?q= |
| Create user | POST | /api/rbac/users |
| Update user | PUT | /api/rbac/users/{id} |
| Upload user avatar | POST | /api/rbac/users/{id}/avatar |
| Activate/deactivate | POST | /api/rbac/users/{id}/toggle-status |

## Global Search & Notifications
| React Action | Method | Endpoint |
|-------------|--------|----------|
| Global search (Ctrl+K) | GET | /api/search?q=&types=product,customer,supplier,sale,purchase,requisition |
| List notifications | GET | /api/notifications?unreadOnly= |
| Mark read | POST | /api/notifications/{id}/read |
| Mark all read | POST | /api/notifications/mark-all-read |
| Unread count | GET | /api/notifications/unread-count |
