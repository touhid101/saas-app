# 00 — Existing API inventory

Phase 0 · analysis only · nothing in this document has been changed in code.

Source of truth: `backend/src/main/java/com/friendspharma/app/controller/*.java`, inspected on
2026-09-22. Endpoint counts are the number of `@GetMapping/@PostMapping/…` annotations in each file.
Every endpoint except the ones listed under **Public** requires a staff JWT
(`SecurityConfig.filterChain`: `.anyRequest().authenticated()`).

## 1. Security boundary as it stands

| Path | Access | Notes |
|---|---|---|
| `POST /api/auth/login`, `POST /api/auth/refresh` | public | staff (`ACL_USER`) credentials only |
| `/api/public/**` | public | only `POST /api/public/register` (pharmacy tenant sign-up queue) exists |
| `/uploads/**` | public | static files: avatars, logos, `product_image/<branch>/…`, `employee_image`, `expense_receipt` |
| `/swagger-ui/**`, `/v3/api-docs/**`, `/actuator/health`, `/actuator/info` | public | |
| everything else | staff JWT | filters in order: `JwtAuthenticationFilter` → `ActingBranchFilter` → `TenantStatusFilter` → `ForcedPasswordChangeFilter` |

There is **no customer-facing public API of any kind**. Every product, price, stock and order
endpoint assumes a `UserPrincipal` with `orgId / companyId / branchId / roleCode`.

## 2. Controller inventory

Base path, endpoint count and the endpoints that matter to e-commerce. Guards are the literal
`@PreAuthorize` expressions found; "none" means the endpoint relies on the class-level JWT
requirement plus service-level `BranchScopeService` checks.

### Authentication & identity

| Controller | Base | # | Endpoints relevant to ECOM | Guard |
|---|---|---|---|---|
| `AuthController` | `/api/auth` | 7 | `POST login`, `POST refresh`, `POST logout`, `GET/PUT me`, `PATCH me/password`, `POST me/avatar` | login/refresh public; rest JWT |
| `PasswordAdminController` | `/api/admin/passwords` | 3 | staff password reset with audit (`ACL_PASSWORD_RESET_AUDIT`) | ROLE01 |
| `RbacController` | `/api/v1/rbac` | 26 | users, groups, modules, permission matrix (`ACL_*`, `POS_PERMISSIONS`) | ROLE01 / ROLE02 |
| `BranchRbacController` | `/api/v1/branch-rbac` | 3 | per-branch module purchase (`ACL_TENANT_MODULE`) | ROLE01 |

### Tenant, branch, platform

| Controller | Base | # | Endpoints relevant to ECOM | Guard |
|---|---|---|---|---|
| `OnboardingController` | `/api/public/register` | 1 | pharmacy applies for a tenant (queue row in `TENANT_REGISTRATION`, password hashed on arrival) | public |
| `AdminOnboardingController` | `/api/admin/onboarding` | 17 | approve/activate registration → creates `ALL_ORGANIZATION` + `ALL_COMPANY` + `ALL_BRANCH` + admin user + subscription in **one transaction** | platform operator |
| `AdminSubscriptionController` | `/api/admin/subscriptions` | 18 | plans, subscriptions, invoices, suspend/restore (`SUB_*`, `SUB_AUDIT`) | platform operator |
| `SubscriptionController` | `/api/subscription` | 3 | a branch reads its own subscription | JWT |
| `PlatformConsoleController` | `/api/admin/console` | 1 | platform KPIs; acting-as-branch is a request header (`ActingBranch.HEADER`) handled by `ActingBranchFilter`, audited to `SUB_AUDIT` | platform operator |
| `MasterDataController` | mixed (`/api/branches`, `/api/customers`, `/api/companies`, `/api/employees`, …) | 75 | **`/api/customers`** CRUD (branch-owned customer), `/api/branches` (org's branches), `/api/employees/delivery-men` | mostly JWT + `BranchScopeService`; writes ROLE01/02 |

### Catalogue & product

| Controller | Base | # | Endpoints relevant to ECOM | Guard |
|---|---|---|---|---|
| `ProductController` | `/api/products` | 11 | `GET` list (paged, search, category/company filters, branch-scoped), `GET {id}`, `GET barcode/{barcode}`, `GET lookups`, `POST {id}/image` (multipart, 2 MB), `DELETE {id}/image`, versions, bulk import | reads JWT; writes ROLE01/02/05 |
| `SalesPercentController` | `/api/sales-percentages` | 5 | per-product six percentages (BOX/STRIP/PCS × regular/special) — the **price list** | reads JWT; writes ROLE01/02/05 |
| `SalesDiscountController` | `/api/v1/sales-discount` | 8 | org → company → product discount% per tier (`POS_SALES_DISCOUNT`), `GET effective` | JWT |
| `GlobalProductController` | `/api/admin/global-products` | 6 | platform medicine catalogue (`GLOBAL_PRODUCT`) | ROLE01 platform |
| `BranchCatalogueController` | `/api/catalogue` | 3 | a pharmacy imports catalogue rows into its own `POS_PRODUCT_INFO` (sets `PID_GLOBAL_PRODUCT`) | JWT, branch from session only |
| `GlobalMasterController` | `/api/admin/global-masters` | 16 | global unit / leaf / box / generic / company masters | platform |
| `ProductImageBackfillController` | `/api/admin/product-images` | 3 | move legacy blob images to files | ROLE01 |
| `GlobalSearchController` | `/api/v1/search` | 1 | staff omnisearch across products/customers/invoices | JWT |

### Stock

| Controller | Base | # | Endpoints relevant to ECOM | Guard |
|---|---|---|---|---|
| `InventoryController` | `/api/inventory` | 13 | `GET stock` (product ledger rows: on-hand pcs, box/strip breakdown), `GET batches`, `GET expiry`, `GET fefo-preview`, alerts (`low-stock`, `out-of-stock`, `slow-moving`), `movements`, `profit`, `reconcile` | JWT + branch scope; reconcile ROLE01 |
| `PurchaseController` / `PurchaseReturnController` / `RequisitionController` / `SupplierPaymentController` | various | 8/2/14/9 | inbound stock; not used by ECOM | — |

### Sales, orders, delivery, collection

| Controller | Base | # | Endpoints relevant to ECOM | Guard |
|---|---|---|---|---|
| `SalesController` | `/api/sales` | 16 | `GET` list, `GET {id}`, **`GET pricing?customerId&productId&qty&unit`** (tier, allowed units, unit prices, live stock, FEFO preview), **`POST`** create sale (FEFO issue + accounting), `PUT {id}` (only at *Submitted*), `POST {id}/unlock`, `POST {id}/finalize`, `GET {id}/document` | create: ROLE01/02/03/09 |
| `SalesActivityController` (same file) | `/api/sales-activity` | 9 | `GET` list (stage/payment/branch/DM/date/q), `GET stage-counts`, `GET summary`, `GET delivery-performance`, `POST {id}/collect`, `POST {id}/advance`, `POST {id}/assign-delivery-man`, `POST bulk-assign-delivery-man`, `POST {id}/cancel` | advance/collect ROLE01/02/03/06; assign ROLE01/02; cancel ROLE01/02 |
| `SalesMergeController` | `/api/sales-merges` | 3 | group several orders into one delivery/collection document | ROLE01/02/03/09 |
| `SalesReturnController` | `/api/sales-returns` | 2 | return against a sale (stock back to batch, accounting) | ROLE01/02/03 |
| `CollectionController` | `/api/v1/collections` | 7 | delivery-man cash-in against an invoice (`POS_DELIVERY_COLLECTION`) | JWT + scope |
| `SalesPerformanceController` | `/api/sales-performance` | 1 | targets vs actual | JWT |
| `PosController` | `/api/v1/pos` | 13 | barcode lookup, server-side cart (`POS_CART`, `POS_CART_ITEM`), hold/recall, `finalise`, `quick-sale`, receipt | JWT |

> **`PosController` is dead code.** The frontend never calls `/api/v1/pos` (`src/services/posService.js`
> targets `/api/pos/...`, which does not exist, and is imported by no page). Its `finalise` path ends in
> `InventoryServiceImpl.createTransactionFromCart` → `throw new UnsupportedOperationException("not yet
> implemented")`. It also accepts `unitPriceOverride` from the client. The live sale path is
> `POST /api/sales` → `SalesServiceImpl.createSale`. See `11-pos-integration.md`.

### Reporting, dashboard, notifications, HR, accounts

| Controller | Base | # | Notes |
|---|---|---|---|
| `DashboardController` | `/api/dashboard` | 1 | branch KPIs |
| `ReportController` | `/api/v1/reports` | 31 | branch-scoped sales/stock/purchase/collection reports (`ReportBranchScopeTest` proves scope) |
| `ReportGridController` | `/api/reports` | 2 | generic grid runner with per-report rights |
| `NotificationController` | `/api/notifications` | 1 | `GET` list only; pushes come from services (`POS_NOTIFICATION`: low stock, expiry, pending approval); mark-read/delete exist in the service but have no endpoint |
| `AiCopilotController` | `/api/ai` | 3 | staff copilot |
| `Hr*Controller` (12 files) | `/api/v1/hr/**` | 163 | HR; `HrEmployee.isDeliveryMan()` is what makes someone assignable to a delivery |
| `Acc*` (3 files in `controller/accounting`) | `/api/v1/accounts` | — | ledger; `AccountingHooks.saleCreated/saleUpdated/saleCollected/deliveryCashIn` are called **inside** the sale transaction |

## 3. DTO shapes the storefront would have to speak

### `SalesRequest` (`POST /api/sales`)

```
customerId          Long      required, must be status Y
branchId            Long      IGNORED unless caller is ROLE01 — BranchScopeService.resolve()
transactionDate     LocalDate
paymentType         String
deliveryAddress     String(200)
deliveryCharge      BigDecimal
invoiceDiscPct      BigDecimal    ← trusted from client
givenAmount         BigDecimal
counterSale         Boolean       ← Submitted→Delivered shortcut for a till sale
deliveryManId       Long
remarks             String(100)
lines[]:
  productId         Long
  quantity          BigDecimal (in salesUnit)
  salesUnit         BOX | STRIP | PCS
  discountPct       BigDecimal    ← trusted from client when HIGHER than the rule (SalesServiceImpl:333)
```

Server decides: tier (from `POS_CUSTOMER_INFO.CUSTOMER_TYPE`), unit eligibility, unit price
(`CustomerPricing.priceFor`), FEFO allocation, stock sufficiency, totals, accounting.
Client-supplied `invoiceDiscPct` and a *higher* line `discountPct` are honoured — acceptable for a
till operated by staff, **not acceptable for a public storefront** (RULE: never trust frontend
discount).

### `SalesPricingResponse` (`GET /api/sales/pricing`)

```
customerId, customerName, customerType, tier, tierLabel
productId, productName, sku
allowedUnits[]                    e.g. ["PCS"] for a retailer
availablePcs, availableDisplay    "8 box 2 strip 3 pcs"
pcsPerBox, pcsPerStrip
units[]: { unit, allowed, price, mrp, discountPct, pcsPerUnit, maxQty, reason }
allocation (optional FEFO preview for qty/unit)
```

This is already, almost exactly, the "price for this customer, this product, this branch, this
unit" answer a storefront needs — but it takes an ERP `customerId` and a staff session branch.

### `TransactionResponse` (sale / activity rows)

`pidTranMst, invoiceNo, transactionDate, customerId, customerName, branchId, stage, paymentStatus,
paymentType, totalAmount, invoiceDisAmt, amountTobePaid, givenAmount, deliveryAddress,
deliveryMan, deliveryManName, deliveryCharge, remarks, fromSource, isMergeInvoice,
lines[] { productId, productName, batchNo, expiryDate, salesUnit, quantity, noOfPcs, mrpPrice,
salesPrice, discountPct, flatDiscount, totalPrice }`

## 4. Frontend service modules that wrap these APIs

`src/services/`: `authService`, `salesService` (`/api/sales`), `salesActivityService`
(`/api/sales-activity`), `inventoryService` (`/api/inventory`), `productMasterService`
(`/api/products`), `masterDataService` (`/api/customers`, `/api/branches`, …),
`salesPercentService`, `branchCatalogueService`, `notificationService`, `reportService`,
`invoiceMergeService`, `salesReturnService`, `posService` (**orphaned**).
All go through `src/lib/apiClient.js` (bearer `pharma_access_token` from localStorage, silent
refresh on 401, `ApiResponse{success,message,data}` unwrapping).

## 5. What does not exist (confirmed by search, not assumption)

| Capability | Search | Result |
|---|---|---|
| Customer login / customer JWT | `grep -ri customer security/ AuthServiceImpl` | staff only; `CustomUserDetailsService` loads `ACL_USER` by `user_name` |
| Coupon / voucher / promo code | `grep -rli coupon backend/src` | **0 files** |
| Loyalty / points | `grep -rli 'loyalty\|reward_point\|loyaltyPoint'` | **0 files** |
| Branch or customer coordinates | `grep -rli latitude` | **0 files** (`ALL_BRANCH` has `BRANCH_ADDRESS`, `PID_REGION` only) |
| OTP / SMS / e-mail verification | `grep -rli 'otp\b'` | **0 files**; no mail starter in `pom.xml` |
| Realtime (WebSocket / SSE) | `grep -rli 'websocket\|SseEmitter'` | **0 files** |
| Cache / Redis / rate limiting | `grep -rli 'redis\|bucket4j\|@Cacheable'` | **0 files** |
| Stock reservation | `PosStockBatch`, `StockServiceImpl` | none; stock is only decremented at sale creation (row-locked FEFO issue) |
| Order "source" enum | `POS_TRANSACTION_MST.FROM_SOURCE` | exists (VARCHAR 15), values seen: `API` |
| External order reference column | `POS_TRANSACTION_MST` | none (`INVOICE_NO`, `CHALLAN_NO`, `REQUISITION_SOURCE` only) |
