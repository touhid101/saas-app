# 03 — Existing system analysis

Phase 0 · analysis only · repository inspected 2026-09-22 (`saas-app`, Flyway head **V99**, 98
migration files, dev DB `minipos_db` schema `pharma`, 131 tables).

## 1. Architecture

```
saas-app/
├── backend/            Spring Boot 3.3.2, Java 17 (built with JDK 21), Maven
│   └── com.friendspharma.app
│       ├── config/     SecurityConfig, WebMvcConfig (static /uploads/**), CorsConfig, Jackson, OpenAPI, AuditorAware
│       ├── security/   JwtTokenProvider, JwtAuthenticationFilter, UserPrincipal, SecurityUtils, ActingBranch(Filter), TenantStatusFilter, ForcedPasswordChangeFilter
│       ├── controller/ 46 REST controllers (+3 accounting), ApiResponse{success,message,data} envelope
│       ├── service/interfaces + service/impl   (business rules; @Transactional boundaries)
│       ├── repository/ Spring Data JPA (+ native queries for reports)
│       ├── entity/     ~120 JPA entities, BaseEntity(status Y/N, inserted_/updated_ by/date)
│       ├── util/       pure calculators: StockUnitConverter, SalesPriceCalculator, CustomerPricing, ProductPricingCalculator, TransactionCalculator
│       ├── scheduler/  CartCleanupScheduler (orphan POS_CART), others
│       └── resources/db/migration/V1..V99  Flyway, schema pharma, baseline-on-migrate
├── src/                React 18 + Vite 5, plain CSS, hand-drawn SVG icon set (components/Icons.jsx) + lucide-react on 4 pages
│   ├── App.jsx         single page switch on `page` state; usePageRoute mirrors it to the path (/sales, /salesactivity …); react-router-dom installed but unused
│   ├── lib/            apiClient (bearer + silent refresh), AppContext (currentUser, can(), branch), usePermission, permissionRules
│   ├── components/     UI.jsx (Card, Pill, Field, Modal, Table…), HrKit, ReceiptKit, Sidebar, Topbar, CustomerPicker, ProductImage, ProductDetailSheet
│   ├── pages/          ~45 staff screens incl. Sales, QuickSales (renders outside the shell), SalesActivity, Delivery, Collection, Inventory, ProductMaster, MasterData
│   └── services/       one module per backend area
└── docs/               SRS, RBAC_IMPLEMENTATION, TESTING_GUIDE, inventory, accounts, hr, platform-srs
```

Deployment: no Dockerfile / compose in the repo; `usePageRoute` documents the nginx
`try_files … /index.html` rule; uploads live on local disk under `UPLOAD_ROOT`
(`app.upload.*` in `application.yml`); JWT secret and DB credentials are in `application.yml` with
env overrides; CORS origins are configured per environment.

## 2. Tenancy

* `ALL_ORGANIZATION` (tenant) 1─< `ALL_COMPANY` 1─< `ALL_BRANCH`. Every pharmacy that signs up
  becomes **its own organisation** with one company and one branch
  (`TenantOnboardingService.activate`, single transaction). The platform's own org (id 1) has
  three branches. So "multi-branch" means both *branches of one org* and *many single-branch
  orgs* — an e-commerce marketplace spans **organisations**, not just branches.
* Tenant status lives on `ALL_BRANCH.TENANT_STATUS` and is enforced per request by
  `TenantStatusFilter` (402 when suspended). Subscriptions (`SUB_*`) and purchased modules
  (`ACL_TENANT_MODULE`) are per branch.
* Every business table carries `PID_ORG` (+ usually `PID_BRANCH`); queries filter by the
  session's org and `BranchScopeService`-resolved branch. There is no row-level security in
  Postgres; isolation is entirely in the service layer.
* Platform operators (`ROLE01` **in org 1**) may act as one branch via the `ActingBranch`
  header; every such request is audited to `SUB_AUDIT`.

## 3. Authentication & authorisation (staff)

* `ACL_USER` (`USER_NAME` unique, `PASSWORD_HASH`, `PID_ORG/COMPANY/BRANCH`, `PID_ROLE` →
  `ACL_GROUP`, `PID_EMPLOYEE` → `HR_EMPLOYEE`, `MUST_CHANGE_PASSWORD`, `TEMP_PASSWORD_EXPIRES`,
  `LAST_LOGIN`).
* `PasswordEncoder`: `DelegatingPasswordEncoder` default `bcrypt` (strength 12); legacy plaintext
  rows still match through a `NoOp` fallback and are re-hashed on successful login (memory:
  8 accounts were still plaintext at last count).
* JWT (jjwt, HS512 secret from config): access 8 h, refresh 7 d stored in `POS_REFRESH_TOKENS`
  (revocable). Claims: `userId, orgId, companyId, branchId, employeeId, roleCode, roles, type`.
* Roles are fixed codes in `ACL_GROUP`: ROLE01 Super Admin, ROLE02 Branch Administrator, ROLE03
  Pharmacist, ROLE04 Accounts, ROLE05 Purchase Manager, ROLE06 Delivery Staff, ROLE07 Store
  Keeper, ROLE08 Viewer, ROLE09 Sales Manager. `@PreAuthorize("hasAnyRole('ROLE0x',…)")` on
  write endpoints; fine-grained rights (`canView/Create/Edit/Delete/Approve/Export/Print`) per
  module (`MOD01…MOD52`) via `POS_PERMISSIONS` / `ACL_USER_PERMISSION` resolved by
  `PermissionResolver`, mirrored in the frontend `usePermission`.
* Module registry: `ACL_MODULE_INFO` — highest code in use **MOD52** (Reconciliation). Menu is
  seeded by migration (`V88`, `V99`).

## 4. Customer (as it exists)

`POS_CUSTOMER_INFO` — **branch-owned master data**: `PID_ORG, PID_COMPANY, PID_BRANCH NOT NULL`,
`CUSTOMER_CODE`, `CUSTOMER_NAME`, `MOBILE_NO` (unique per **branch**: `uq_customer_branch_mobile`),
`EMAIL_ADDRESS`, `ADDRESS`, `CITY`, `OWNER_NAME`, `OPENING_BAL`, `CREDIT_LIMIT`,
`CUSTOMER_TYPE` CHECK IN (`Retailer, Wholesaler, Hospital, Pharmacy, Clinic, Special`).
Created by staff only (`/api/customers`, ROLE01/02). No password, no login, no approval, no
address book, no coordinates. 24 rows in dev (17 Retailer, 6 Wholesaler, 1 Special).

`CustomerPricing.tierOf()` maps the type by prefix: `WHOLESALE*` → WHOLESALER, `SPECIAL*` →
SPECIAL, everything else (including **Hospital, Pharmacy, Clinic**) → RETAILER (PCS only). This
is a latent ERP inconsistency: the V45 check constraint admits types the pricing tier does not
know. **BUSINESS CONFIRMATION REQUIRED** before the storefront exposes those types.

## 5. Product & catalogue

* `POS_PRODUCT_INFO` is **per branch** (`PID_BRANCH`, `uq_product_branch_code`,
  `uq_branch_global_product`), versioned (`PID_ROOT_PRODUCT`, `VERSION_NO`, `SUPERSEDED_*`).
  108 active products in dev; 76 linked to a `GLOBAL_PRODUCT` (platform catalogue), 66 with
  box/strip packing, 16 with an image.
* Cross-branch identity of "the same medicine" = `PID_GLOBAL_PRODUCT` (nullable). Products a
  pharmacy typed in itself have no global id — price comparison across pharmacies is only
  possible for catalogue-linked rows (or by generic + strength + company matching, which is
  fuzzy).
* Images: files under `product_image/<branchId>/<productId>.<ext>` served publicly at
  `/uploads/…`; `GLOBAL_PRODUCT` has its own `PRODUCT_IMAGE`. Frontend `ProductImage.jsx`
  handles fallback.

## 6. UOM, pricing, stock, sales, delivery

Documented in detail in `05`–`07`, `10`, `11`, `13`. Summary:

* UOM chain `POS_PRODUCT_INFO.PID_BOX → POS_BOX_INFO(NO_OF_LEAF, PID_LEAF) → POS_LEAF_INFO(NO_OF_PCS)`;
  stock stored in **pieces only**; `StockUnitConverter` converts; a missing pack size is
  *unknown*, never 1.
* Price = MRP (box) apportioned to the unit, minus the unit's percentage (`SALES_PER*`), chosen
  by tier; plus an optional org/company/product **discount%** per unit (`POS_SALES_DISCOUNT`).
  All server-side, all pure functions with unit tests.
* Stock = `POS_STOCK_BATCH` per branch/product/batch; live = unexpired, unblocked, `QTY_PCS>0`;
  FEFO with `PESSIMISTIC_WRITE` on the write path; no reservation concept.
* Sale = `POS_TRANSACTION_MST/DTL`, created by `SalesServiceImpl.createSale` only; ledger posted
  in-transaction; stages Submitted…Collected; cancel endpoint broken.
* Delivery = the sale + an `HR_EMPLOYEE` delivery man + stages; cash-in via
  `POS_DELIVERY_COLLECTION`; no geo, no tracking, no customer notification.

## 7. Notifications, audit, reports

* `POS_NOTIFICATION` in-app only (low stock, expiry, pending approval); no e-mail/SMS/push
  channel exists in the codebase.
* Audit: `BaseEntity` columns everywhere; `SUB_AUDIT` for platform actions; password reset audit;
  accounting journals. No generic "who changed customer type" trail.
* Reports: 31 branch-scoped endpoints; `ReportBranchScopeTest` and `SaleBranchScopeTest` pin
  the scoping.

## 8. Testing & quality signals

31 JUnit tests (pure calculators, scope rules, permission resolver, storage). No integration test
of `createSale`; verification of end-to-end flows is done with the Playwright/node QA scripts kept
outside the repo (memory: `saas-verification-suites`, `hr-verify-suites`). Vite build passing is
not evidence of runtime health (memory: `vite-build-hides-runtime-breaks`).

## 9. Findings that constrain the e-commerce design

| # | Finding | Impact |
|---|---|---|
| F1 | Customer is branch-owned and org-scoped; mobile unique per branch | a global customer cannot be a `POS_CUSTOMER_INFO` row — but every sale FK points there |
| F2 | Only one identity type (`ACL_USER`, staff, org-bound) | customer login needs its own principal and filter chain segment |
| F3 | Branches have no coordinates, no ECOM flag, no public endpoint | discovery/nearby needs additive columns + a public read model |
| F4 | Product identity per branch; global link optional | price comparison limited to `PID_GLOBAL_PRODUCT`-linked rows |
| F5 | Pricing engine complete and pure; retailer PCS-only already enforced | **reuse exactly**; do not re-implement in the storefront |
| F6 | No reservation; stock decremented at sale creation under row lock | checkout must create the sale inside one transaction (or add a reservation table); browse stock is informational |
| F7 | `createSale` trusts client `invoiceDiscPct` and a *higher* line `discountPct` | the ECOM wrapper must pass neither |
| F8 | `FROM_SOURCE` exists but is free text, unindexed | safe place to mark `ECOM` |
| F9 | Cancel endpoint is broken and never releases stock | must be fixed once in the ERP before ECOM cancellation |
| F10 | No coupon, loyalty, geo, OTP, mail, realtime, cache | all new, additive |
| F11 | Customer type not snapshotted on the sale | ECOM order snapshot must carry it |
| F12 | Hospital/Pharmacy/Clinic types price as Retailer | business confirmation |
