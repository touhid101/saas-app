# 01 — API dependency map

Phase 0 · analysis only. Each chain below was traced by reading the controller, the service it
calls, the repositories it uses and the tables those map to. Nothing was inferred from names.

Legend: `→` calls · `⇒` writes · `(ro)` read-only transaction · `[lock]` row lock

## 1. Sale creation (the only live "create a sale" path)

```
POST /api/sales                         SalesController.create   @PreAuthorize ROLE01/02/03/09
 └→ SalesServiceImpl.createSale(SalesRequest)                    @Transactional
     ├→ PosCustomerInfoRepository.findById(customerId)           POS_CUSTOMER_INFO (status must be Y)
     ├→ BranchScopeService.resolve(req.branchId)                 ActingBranch header ▸ session branch ▸ ROLE01 may name another branch of the org
     ├→ CustomerPricing.tierOf(customer.customerType)            pure: WHOLESALER | RETAILER | SPECIAL
     ├→ validateLines()  per line
     │    ├→ PosProductInfoRepository.findById                   POS_PRODUCT_INFO (status Y)
     │    ├→ pcsPerUnit()  → PosBoxInfoRepository, PosLeafInfoRepository   POS_BOX_INFO.NO_OF_LEAF × POS_LEAF_INFO.NO_OF_PCS
     │    ├→ SalesPriceCalculator.unitPricesForSale(mrp, pcsPerBox, pcsPerStrip, 6 × SALES_PER_*)
     │    ├→ CustomerPricing.unavailableReason(tier, unit, prices)     refuses BOX/STRIP for a retailer, unpriced units, missing packing
     │    └→ StockService.assertAvailable(branch, product, qty, unit)  (ro) POS_STOCK_BATCH whole-unit count, FEFO order
     ├⇒ PosTransactionMstRepository.save                          POS_TRANSACTION_MST  stage=Submitted, status=Y, FROM_SOURCE='API', INVOICE_NO='SL'+yyMM+5 random digits
     ├→ writeLinesAndPost()  per line
     │    ├→ SalesDiscountService.resolveEffectiveDiscount(org, product, company, unit)   POS_SALES_DISCOUNT product ▸ company ▸ org
     │    │     (client line.discountPct wins if HIGHER)
     │    ├→ StockService.preview(branch, product, qty, unit)     (ro) FEFO walk, whole units per lot
     │    ├→ tierPrice() = CustomerPricing.priceFor(tier, unit, unitPricesFor(product))
     │    ├⇒ PosTransactionDtlRepository.save  one row PER BATCH consumed   POS_TRANSACTION_DTL (BATCH_NO, EXPIRY_DATE, SALES_UNIT, NO_OF_PCS, MRP_PRICE, SALES_PRICE, DISCOUNT_PCT, FLAT_DISCOUNT, TOTAL_PRICE)
     │    └⇒ StockPostingService.post(mstId)
     │          └→ StockService.issueFromBatch(...) per DTL row (the DTL already names its batch from the preview); FEFO issue() only when a line has no batch   [lock] PosStockBatchRepository.lockAllocatableBatches (PESSIMISTIC_WRITE)
     │                ⇒ POS_STOCK_BATCH.QTY_PCS −, ISSUED_PCS +
     │                ⇒ POS_STOCK_MOVEMENT (MOVEMENT_TYPE, DIRECTION='O' (PosStockMovement.OUT), QTY_PCS, BALANCE_AFTER, REF_TYPE/REF_MST/REF_DTL)
     ├→ applyInvoiceTotals()  TransactionCalculator.calculateInvoiceTotals(lineTotals, invoiceDiscPct, deliveryCharge)
     │    ⇒ MST.TOTAL_AMOUNT, INVOICE_DIS_AMT, AMOUNT_TOBE_PAID, PAYMENT_STATUS (Paid | Partial | Unpaid)
     │      stage: Submitted → Collected when Paid; Submitted → Delivered when counterSale
     └→ AccountingHooks.saleCreated(mst)                          ACC_* journal in the SAME transaction (ledger is authoritative)
```

Everything a storefront checkout needs — tier gating, unit pricing, FEFO, row-locked issue,
totals, ledger — already sits behind one `@Transactional` method. What it lacks is a caller that is
not a staff `UserPrincipal`.

## 2. Price lookup

```
GET /api/sales/pricing?customerId&productId[&qty&unit][&branchId]      (ro)
 └→ SalesServiceImpl.getPricing
     ├→ POS_CUSTOMER_INFO (tier)          ├→ POS_PRODUCT_INFO (MRP, 6 percentages, PID_BOX)
     ├→ POS_BOX_INFO / POS_LEAF_INFO      ├→ BranchScopeService.resolve(branchId)
     ├→ StockService.availablePcs / availableWholeUnits(branch, product, unit)   POS_STOCK_BATCH
     └→ StockService.preview (FEFO) when qty given
```

Also: `GET /api/sales-percentages/{productId}` → `SalesPercentServiceImpl` → same
`SalesPriceCalculator.unitPrices` (maintenance view: null% = unpriced, unlike the sale path).

## 3. Live stock

```
GET /api/inventory/stock       InventoryServiceImpl.stockLedger   POS_STOCK_BATCH grouped by product, joined POS_PRODUCT_INFO; branch from BranchScopeService
GET /api/inventory/batches     per-lot rows (BATCH_NO, EXPIRY_DATE, QTY_PCS, COST_PER_PCS)
GET /api/inventory/fefo-preview  StockService.preview
StockService.availablePcs(branch, product) = Σ QTY_PCS of POS_STOCK_BATCH where status='Y' AND QTY_PCS>0 AND (EXPIRY_DATE IS NULL OR ≥ today)
```

`POS_PRODUCT_INFO.STOCK_QTY` is a legacy column; the batch table is the source of truth
(`InventoryController.reconcile` exists precisely to compare the two).

## 4. Order operations (Sales Activity)

```
GET  /api/sales-activity                 SalesServiceImpl.listActivity   POS_TRANSACTION_MST (type SL) + customer name; filters stage, paymentStatus, branch, deliveryManId, unassigned, from/to, q
GET  /api/sales-activity/stage-counts    mstRepo count by STAGE for branch
GET  /api/sales-activity/summary         by-stage totals (DeliverySummaryResponse)
POST /api/sales-activity/{id}/advance    advanceStage(newStage, deliveryManId)
        ├ STAGES = [Submitted, Confirmed, Delivery Assigned, On The Way, Delivered, Collected]  (no ordering enforced; any listed stage may be set)
        ├ requireOwnDelivery (ROLE06 only their own), requireDriverStageAllowed (ROLE06: On The Way | Delivered), requireNotMerged
        ├ deliveryManId → DeliveryManService.requireDeliveryMan (HR_EMPLOYEE.EMP_TYPE = delivery, active, same org)
        ├ 'Collected' ⇒ PAYMENT_STATUS='Paid'
        └ cascadeStage() to merged source invoices
POST /api/sales-activity/{id}/collect    collect(amount, paymentType) ⇒ GIVEN_AMOUNT +, PAYMENT_STATUS, stage Collected when settled; AccountingHooks.saleCollected
POST /api/sales-activity/{id}/assign-delivery-man, /bulk-assign-delivery-man   ⇒ MST.DELIVERY_MAN, DELIVERY_MAN_NAME
POST /api/sales-activity/{id}/cancel     advanceStage(id, "Cancelled", null)
        ✗ "Cancelled" is NOT in STAGES → ValidationException "'Cancelled' is not an order stage"
        ✗ even if it were, no stock reversal is called (StockPostingService.reverse exists and is used only by updateSale / returns)
```

The two `Cancelled` rows in the dev database were seeded, not produced by this endpoint.

## 5. Delivery & collection

```
GET  /api/employees/delivery-men          MasterDataController → HrEmployeeRepository (EMP_TYPE delivery, active, org)
POST /api/v1/collections                  CollectionServiceImpl.record ⇒ POS_DELIVERY_COLLECTION (+ MST given/paid, AccountingHooks.deliveryCashIn)
GET  /api/sales-activity/delivery-performance   mstRepo.deliveryPerformance(org, branch, from, to) — driver sees only own row
Frontend: Delivery.jsx (MOD09) and Collection.jsx (MOD10) — "a delivery IS a sale carrying a stage and an employee"; there is no delivery entity, no route, no GPS.
```

## 6. Authentication

```
POST /api/auth/login   AuthServiceImpl.login
 ├→ AuthenticationManager (DaoAuthenticationProvider) → CustomUserDetailsService.loadUserByUsername → ACL_USER by USER_NAME (unique)
 ├ PasswordEncoder = DelegatingPasswordEncoder{bcrypt(12)} with NoOp fallback for legacy plaintext (lazy upgrade on login)
 ├→ JwtTokenProvider.generateAccessToken  claims: userId, orgId, companyId, branchId, employeeId, roleCode, roles, type=ACCESS  (8 h)
 ├⇒ POS_REFRESH_TOKENS (TOKEN_VALUE unique, EXPIRES_AT, REVOKED)  (7 d)
 └ UserPrincipal{userId, username, orgId, companyId, branchId, employeeId, roleCode, superAdmin, status}
Filters: JwtAuthenticationFilter (bearer → SecurityContext) → ActingBranchFilter (platform ROLE01 in platform org may act as one branch; audited) → TenantStatusFilter (suspended branch ⇒ 402 Payment Required) → ForcedPasswordChangeFilter
```

The identity is a **staff** identity: `ACL_USER.PID_ORG/PID_BRANCH NOT NULL`, role → `ACL_GROUP`,
permissions → `ACL_USER_PERMISSION` / `POS_PERMISSIONS` / `ACL_TENANT_MODULE` resolved by
`PermissionResolver`. There is no principal type for a customer.

## 7. Customer master

```
/api/customers (MasterDataController) → MasterDataServiceImpl → POS_CUSTOMER_INFO
  PID_ORG, PID_COMPANY, PID_BRANCH NOT NULL; unique (PID_BRANCH, MOBILE_NO) where MOBILE_NO not null
  CUSTOMER_TYPE CHECK IN (Retailer, Wholesaler, Hospital, Pharmacy, Clinic, Special)
  create/update by staff (ROLE01/02) only; no self-registration, no approval, no password, no e-mail login
Frontend: CustomerPicker.jsx used by Sales.jsx / QuickSales.jsx; MasterData.jsx tab "Customers"
```

## 8. Product & images

```
GET /api/products                ProductServiceImpl.list  POS_PRODUCT_INFO where PID_ORG=session org AND (PID_BRANCH = session branch) AND status
                                 filters q, categoryId, companyId, genericId, productType, priced; paged
POST /api/products/{id}/image    ProductImageStorageService.store(file, productId, branchId) ⇒ <UPLOAD_ROOT>/product_image/<branchId>/<productId>.<ext>
                                 ⇒ POS_PRODUCT_INFO.PRODUCT_IMAGE = "<branchId>/<productId>.<ext>"; toPublicUrl → /uploads/product_image/<branchId>/<productId>.<ext>?v=<mtime>
Frontend: components/ProductImage.jsx + lib/uploadUrl.js (prefixes VITE_API_BASE_URL)
Identity across branches: POS_PRODUCT_INFO.PID_GLOBAL_PRODUCT (nullable, uq_branch_global_product) → GLOBAL_PRODUCT (platform catalogue, own PRODUCT_IMAGE)
```

## 9. Tenant / branch

```
ALL_ORGANIZATION 1 ─< ALL_COMPANY 1 ─< ALL_BRANCH (PID_ORG, PID_COMPANY, TENANT_STATUS, BRANCH_NAME, BRANCH_ADDRESS, BRANCH_EMAIL, BRANCH_PHONE_NO, LOGO_URL, REPORT_HEADER, PID_REGION)
Dev data: org 1 (platform, "Friends Pharma") has 3 branches; every other pharmacy is its own org with one branch (TenantOnboardingService.activate creates org + company + branch + admin + subscription atomically)
Branch APIs: GET /api/branches (org's branches, staff), admin onboarding/subscription (platform). No public branch list, no coordinates, no opening hours, no ECOM flag.
```

## 10. Notifications, audit, reports

```
POS_NOTIFICATION   NotificationServiceImpl.push*(org, branch, …) — per-user or per-branch in-app; GET /api/notifications only
SUB_AUDIT          platform-level events (suspension, payment, acting-as-branch) — PID_BRANCH NOT NULL, EVENT_TYPE, DETAIL, OLD/NEW_VALUE, ACTED_BY
ACL_PASSWORD_RESET_AUDIT   staff password resets
BaseEntity         INSERTED_BY/DATE, UPDATED_BY/DATE via AuditorAwareImpl (username from SecurityContext)
ACC_*              posted journals are the financial audit; "never delete posted history" already enforced by AccountingHooks design
Reports            ReportController → ReportServiceImpl → native queries over POS_TRANSACTION_MST/DTL, POS_STOCK_*, POS_DELIVERY_COLLECTION; branch resolved via BranchScopeService (ReportBranchScopeTest)
```

## 11. Cross-cutting invariants the ECOM layer must not break

| Invariant | Where enforced | Consequence for ECOM |
|---|---|---|
| Stock is pieces; BOX/STRIP only at the edges | `StockUnitConverter`, `POS_STOCK_BATCH.QTY_PCS` | never store a box count; convert with the product's packing at the moment of the order |
| One `POS_TRANSACTION_DTL` row per batch consumed | `writeLinesAndPost` | an ECOM order line may fan out into several DTL rows; the ECOM "line" must be a separate record that references them |
| Sale editable only at `Submitted`; afterwards correct with a return | `updateSale`, `unlockForEdit` | ECOM cancellation after confirmation = sales return, not deletion |
| Branch comes from the session, never the body | `BranchScopeService.resolve` | ECOM must pass the *order's* branch through a trusted path (service call), not the request body of a staff endpoint |
| Ledger posted in the sale transaction | `AccountingHooks` | any new "create order" path must call the same service, not write MST/DTL directly |
| Tenant = org; branch ⊂ org | `requireInOrg`, `ActingBranchFilter` | a global customer is outside every org; the order it places is inside the branch's org |
