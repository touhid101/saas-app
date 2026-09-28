# PHASE 0 — FINAL REPORT
## Global-customer, multi-branch B2B e-commerce on the existing Friends Pharma ERP

Status: **analysis complete, nothing implemented**. No code, migration, table or existing file
was modified. Repository `saas-app` inspected on 2026-09-22 (Flyway head V99; dev DB
`minipos_db`, 131 tables, read-only queries only).

Supporting documents: `00`–`22` in this folder. Every statement below was verified in source
(file/line references in the supporting docs); where the business must decide, it says
**BUSINESS / TAX POLICY CONFIRMATION REQUIRED**.

---

## A. Architecture

```text
Backend:    Spring Boot 3.3.2 / Java 17 target (JDK 21 locally), Spring Security + JWT (jjwt),
            Spring Data JPA (Hibernate, ddl-auto=update!), Flyway, springdoc; 46 controllers,
            ~120 entities, pure calculators in util/. Package com.friendspharma.app.
Frontend:   React 18 + Vite 5, plain CSS tokens (light/dark), page switch in App.jsx mirrored to
            paths by usePageRoute (react-router installed, unused), hand-drawn SVG icons +
            lucide-react, services/*.js over lib/apiClient.js (bearer + silent refresh).
Database:   PostgreSQL 18, schema pharma, 131 tables. Tenancy: ALL_ORGANIZATION > ALL_COMPANY >
            ALL_BRANCH; every business row carries PID_ORG (+ PID_BRANCH). Stock in pieces per
            (branch, product, batch). No PostGIS, no row-level security.
Flyway:     V1..V99 (98 files), V<n>__snake_case.sql, baseline-on-migrate, production baselined at
            63 (seed rows before that never ran there). Next free: V100.
Deployment: fat jar + static dist/ behind nginx (try_files → index.html); uploads on local disk
            under UPLOAD_ROOT served at /uploads/**; no Docker/CI in repo; secrets via env.
```

## B. Authentication

```text
ERP Authentication:      POST /api/auth/login → DaoAuthenticationProvider → ACL_USER (USER_NAME unique,
                         PID_ORG/COMPANY/BRANCH NOT NULL, PID_ROLE → ACL_GROUP ROLE01..ROLE09).
                         UserPrincipal{userId, orgId, companyId, branchId, employeeId, roleCode}.
Customer Authentication: DOES NOT EXIST. No customer principal, no customer login, no public API
                         beyond tenant registration.
JWT:                     HMAC (jjwt) with claims userId/orgId/companyId/branchId/employeeId/roleCode/
                         roles/type; access 8 h. Single secret in application.yml (env override).
Refresh Token:           POS_REFRESH_TOKENS (PID_USER → ACL_USER, TOKEN_VALUE unique, EXPIRES_AT,
                         REVOKED), 7 d, revoked on logout.
Password Hash:           DelegatingPasswordEncoder default {bcrypt} strength 12; NoOp fallback matches
                         legacy plaintext rows and upgrades them on login (8 staff rows still plaintext).
```

## C. Customer

```text
Existing Customer Table: PHARMA.POS_CUSTOMER_INFO (24 rows dev) — PID_ORG, PID_COMPANY, PID_BRANCH NOT NULL,
                         CUSTOMER_CODE, CUSTOMER_NAME, MOBILE_NO (unique per BRANCH, partial index
                         uq_customer_branch_mobile), EMAIL_ADDRESS, ADDRESS, CITY, OWNER_NAME,
                         OPENING_BAL, CREDIT_LIMIT, CUSTOMER_TYPE, STATUS.
Customer Entity:         entity/PosCustomerInfo.java (BaseEntity).
Customer Service:        MasterDataServiceImpl (create/update/toggle-status), CustomerPicker.jsx inline create.
Customer API:            GET/POST /api/customers, GET/PUT /api/customers/{id}, POST /{id}/toggle-status
                         (staff JWT; writes ROLE01/02; branch from session).
Customer Approval:       NONE — active on creation.
Customer Type:           CHECK (Retailer, Wholesaler, Hospital, Pharmacy, Clinic, Special).
                         Pricing tier = CustomerPricing.tierOf(): WHOLESALE* → WHOLESALER, SPECIAL* → SPECIAL,
                         everything else (incl. Hospital/Pharmacy/Clinic) → RETAILER. No audit of changes.
Branch Relationship:     OWNED BY ONE BRANCH of one tenant org; a sale's PID_SUP_CUST must point at a
                         customer of the sale's branch/org.
```

```text
Is customer currently branch-owned?   YES — PID_BRANCH NOT NULL, mobile unique per branch, all
                                      queries branch-scoped, created only by branch staff.

If yes, how can global customer architecture be implemented safely?
  1. Create the global identity OUTSIDE tenant scope: ECOM_CUSTOMER (no PID_ORG, no PID_BRANCH),
     ECOM_CUSTOMER_AUTH (own credentials, same PasswordEncoder), ECOM_CUSTOMER_ADDRESS,
     ECOM_CUSTOMER_TYPE_HISTORY, ECOM_AUDIT_LOG. Mobile/e-mail unique GLOBALLY.
  2. Do not touch the meaning of POS_CUSTOMER_INFO. Keep every ERP screen/report unchanged.
  3. Bridge per fulfilment branch: on the first order at branch B, create (inside the checkout
     transaction) a "shadow" POS_CUSTOMER_INFO row in B (CUSTOMER_TYPE = the global tier label the
     ERP already understands, MOBILE_NO = global mobile, CUSTOMER_CODE EC-<id>) and record it in
     ECOM_CUSTOMER_BRANCH_LINK(PID_ECOM_CUSTOMER, PID_BRANCH, PID_CUSTOMER) — unique per
     (customer, branch) and per shadow row. The ERP sees a normal branch customer; the branch never
     owns the identity, and the customer may hold links to any number of branches.
  4. Customer type lives on ECOM_CUSTOMER; changes are permissioned, audited and propagated to all
     shadow rows in one transaction. Orders snapshot the tier, so history never changes.
  5. Shadow rows are marked (POS_CUSTOMER_INFO.ECOM_MANAGED, nullable) so the ERP customer form
     refuses type edits on them and points to the ECOM console.
  Rejected: relaxing PID_BRANCH / adding global columns to POS_CUSTOMER_INFO (breaks unique
  index, scope queries, customer lists); making PID_SUP_CUST polymorphic (touches every consumer).
```

## D. Global Customer Authentication — recommendation

```text
Existing authentication reuse:  NO for identity/credentials. ACL_USER is staff: org/branch NOT NULL,
                                role → menu permissions, forced-password-change and tenant-status
                                filters, employee link. A customer row there would be a fake employee
                                of one tenant. POS_REFRESH_TOKENS.PID_USER means ACL_USER.

                                YES for the mechanisms: the PasswordEncoder bean ({bcrypt}), the
                                JwtTokenProvider pattern (a second provider/secret with claim
                                type=CUSTOMER), the filter-chain pattern, the ApiResponse envelope
                                and GlobalExceptionHandler.

New ECOM customer authentication:
  Tables   ECOM_CUSTOMER_AUTH (login_identifier unique GLOBAL, password_hash, status,
           failed_login_count, locked_until, last_login_at, email/phone_verified, password_changed_at)
           ECOM_CUSTOMER_REFRESH_TOKEN (mirrors POS_REFRESH_TOKENS, keyed by customer)
  Security EcomCustomerPrincipal + EcomCustomerAuthenticationFilter bound to /api/ecom/**;
           staff tokens rejected there; customer tokens rejected everywhere else.
  API      POST /api/ecom/auth/register | login | refresh | logout | forgot | reset (OTP provider TBD)
           GET/PUT /api/ecom/me, /me/addresses, PATCH /me/password
  Rule     login uniqueness is global (phone E.164 or lower-cased e-mail), never per branch.
```

## E. Branch

```text
Branch Table:       PHARMA.ALL_BRANCH — PID_BRANCH, PID_ORG, PID_COMPANY, BRANCH_NAME, BRANCH_ADDRESS,
                    BRANCH_EMAIL, BRANCH_PHONE_NO, LOGO_URL, REPORT_HEADER, PID_REGION, TENANT_STATUS.
                    9 rows dev: org 1 (platform) has 3 branches; other pharmacies are separate orgs
                    with one branch each (TenantOnboardingService.activate creates org+company+branch
                    +admin+subscription atomically).
Branch API:         GET /api/branches (staff, own org); admin onboarding/subscription/console (platform);
                    branch settings (logo, header). NO public branch endpoint.
Branch Coordinates: NONE.
ECOM Eligibility:   NONE (no flag). Proposed additive columns: ECOM_ENABLED 'N' default, LATITUDE,
                    LONGITUDE, ECOM_DELIVERY_RADIUS_KM, ECOM_MIN_ORDER_AMOUNT, ECOM_OPENING_HOURS,
                    BRANCH_IMAGE_URL — all nullable/defaulted.
Tenant Relationship: branch ⊂ company ⊂ organisation; TenantStatusFilter blocks suspended branches
                    (402); ActingBranchFilter lets a platform operator act as one branch (audited).
                    An e-commerce "branch" is any ALL_BRANCH with ECOM_ENABLED='Y', regardless of org.
```

## F. UOM

```text
PCS:        the only stored unit (POS_STOCK_BATCH.QTY_PCS, POS_STOCK_MOVEMENT.QTY_PCS).
STRIP:      = POS_LEAF_INFO.NO_OF_PCS pieces (via POS_BOX_INFO.PID_LEAF).
BOX:        = POS_BOX_INFO.NO_OF_LEAF strips × NO_OF_PCS pieces (via POS_PRODUCT_INFO.PID_BOX).
Conversion: util/StockUnitConverter — factorFor/toPcs/fromPcs/breakdown/describe; missing packing =
            unknown (null), never 1; whole-unit availability counted per lot; DTL stores SALES_UNIT +
            NO_OF_PCS at time of sale. 66/108 dev products have packing; the rest are PCS-only.
```

## G. Pricing

```text
Retailer:         PCS only, at the regular PCS price (wholesaler piece rate).
Wholesaler:       BOX, STRIP, PCS at regular prices (SALES_PER, SALES_PER_LEAF, SALES_PER_PCS).
Special Customer: BOX, STRIP, PCS at special prices (SALES_PER_SPC, SALES_PER_SPC_LEAF, SALES_PER_SPC_RET).
Branch:           implicit — POS_PRODUCT_INFO is per branch, so MRP and the six percentages are per
                  branch; the session branch (BranchScopeService) selects the product rows.
UOM:              unit MRP = box MRP apportioned by packing (strip = MRP×pcsPerStrip/pcsPerBox, pcs = MRP/pcsPerBox).
Price:            unitPrice = round2(unitMrp − unitMrp × pct/100)  (SalesPriceCalculator; on the sale path a
                  null pct reads as 0 %, a null/0 MRP makes the product unsellable).
Discount:         POS_SALES_DISCOUNT rule (product > company > org, per unit tier) applied per DTL row;
                  plus client-supplied line discountPct (if higher) and invoiceDiscPct — the ECOM wrapper
                  must not send either. No tax on sales (TOTAL_TAX never set) — TAX POLICY CONFIRMATION
                  REQUIRED before showing tax to customers.
Exposure:         GET /api/sales/pricing (per-unit allowed/price/mrp/maxQty/reason + live stock + FEFO);
                  POST /api/sales recomputes everything server-side.
```

## H. Inventory

```text
Live Stock:      Σ POS_STOCK_BATCH.QTY_PCS where STATUS='Y' AND QTY_PCS>0 AND (EXPIRY_DATE IS NULL OR ≥ today),
                 per (branch, product) — StockService.availablePcs.
Available Stock: same, expressed in whole units per lot (availableWholeUnits) — two lots of 11 pcs = 0 boxes of 12.
Reserved Stock:  DOES NOT EXIST. Stock leaves the lot when createSale commits.
Batch:           POS_STOCK_BATCH per (branch, product, batch no) with expiry, cost, received/issued pcs,
                 packing at receipt; STATUS Y active / X exhausted / B blocked.
Expiry:          expired lots excluded from availability; GET /api/inventory/expiry lists them.
FEFO:            earliest expiry (NULLS LAST) → earliest receipt → batch no; preview and issue walk the
                 same order; issue locks lots with PESSIMISTIC_WRITE; one DTL row per lot consumed;
                 POS_STOCK_MOVEMENT journal with REF_TYPE/REF_MST/REF_DTL.
Branch:          every read/write is branch-keyed; no cross-branch stock view exists.
```

## I. POS

```text
POS_TRANSACTION_MST: header (PID_ORG, PID_COMPANY, PID_BRANCH, PID_SUP_CUST → POS_CUSTOMER_INFO,
                     TRANSACTION_TYPE 'SL', INVOICE_NO 'SL'+yyMM+5 random digits (not guaranteed unique),
                     STAGE, PAYMENT_STATUS/TYPE, totals, delivery man/address/charge, FROM_SOURCE 'API',
                     merge fields, ADVANCE_AMOUNT). 140,410 SL rows in dev (seeded history).
POS_TRANSACTION_DTL: one row per batch consumed: BATCH_NO, EXPIRY_DATE, SALES_UNIT, NO_OF_PCS, QUANTITY (in unit),
                     MRP_PRICE, SALES_PRICE, DISCOUNT_PCT, FLAT_DISCOUNT, TOTAL_PRICE, return links.
Creation Service:    SalesServiceImpl.createSale(SalesRequest) — the ONLY live path (Sales + Quick Sales).
                     PosServiceImpl/PosController (/api/v1/pos, POS_CART) is dead: no frontend caller,
                     finalise throws UnsupportedOperationException, accepts client price override.
Transaction Lifecycle: Submitted → (Confirmed → Delivery Assigned → On The Way → Delivered →) Collected;
                     Collected at once when paid; Delivered when counterSale; editable only at Submitted;
                     corrections after that = Sales Return; cancel endpoint broken ('Cancelled' not in STAGES,
                     no stock reversal).
Stock Integration:   inside createSale: FEFO preview → DTL rows → StockPostingService.post → row-locked issue →
                     movements → totals → AccountingHooks.saleCreated (ledger) — all one @Transactional.
```

## J. Sales Activity

```text
Can Sales Activity safely support ECOM?   YES — WITH EXTENSION, and together with a dedicated ECOM board.

What needs extension (ERP side, additive):
  • FROM_SOURCE value 'ECOM' written by an additive createSale entry point that takes origin + branch
    context from the ECOM service instead of the staff session (11 §5); existing signature unchanged.
  • Sales Activity list/stage-counts: optional source filter + chip; order-number column; link to ECOM detail.
  • Cancellation: implement once in SalesServiceImpl (reverse posting + 'Cancelled' + ledger reversal);
    today's endpoint is broken for ERP users too.
  • NOT extended: the STAGES list (Preparing/Ready would change every ERP chip, summary and report).

Dedicated ECOM Order Operations:  REQUIRED — as an operational board over the same services, not a
  second sales engine. It shows ECOM-only state (global customer, address/map, ECOM status machine,
  coupon/points, payment ref, proof of delivery, status history) and calls SalesServiceImpl
  advance/assign/collect for anything touching stock or money. Branch-aware via BranchScopeService.
```

## K. Coupon

```text
Existing Coupon System:     NONE (0 source files).
Existing Promotion System:  NONE. What exists: per-product Sales % (the price list), POS_SALES_DISCOUNT
                            standing discount% per unit (org/company/product, branch-owned), manual invoice
                            discount %, dormant CASHBACK_AMOUNT column.
Branch Scope:               none of the above is a campaign; POS_SALES_DISCOUNT is per branch by ownership.
Customer Scope:             none (tier only via the price list).
Product Scope:              POS_SALES_DISCOUNT can be product-specific.
```

Design — **Coupon → Branch** (never Customer → Branch → Coupon):

```text
ECOM_COUPON(code unique ci, name, description, discount_type PERCENT|FIXED, discount_value, max_discount,
            minimum_order_amount, usage_limit, per_customer_limit, start_date, end_date, is_active,
            first_order_only, customer_type_scope, product_scope, category_scope,
            branch_scope ALL_BRANCHES|SELECTED_BRANCHES NOT NULL — no default)
ECOM_COUPON_BRANCH(pid_coupon, pid_branch)          ← the mapping
ECOM_COUPON_PRODUCT / ECOM_COUPON_CATEGORY          ← optional scope
ECOM_COUPON_USAGE(pid_coupon, pid_ecom_customer, pid_ecom_order, amount, used_at)
ECOM_OFFER (+ _BRANCH, _PRODUCT)                    ← automatic offers, same scope model

Validation always against the ORDER/CART branch: active ∧ in window ∧ (ALL_BRANCHES ∨ (coupon, orderBranch)
∈ ECOM_COUPON_BRANCH) ∧ type scope ∧ min amount on server-priced cart ∧ limits (row lock) ∧ first-order ∧
product/category scope. Re-validated at checkout. Stored on ECOM_ORDER (code + amount) and in USAGE.
How the amount reaches the POS document (invoice discount vs separate settlement): BUSINESS/ACCOUNTING
CONFIRMATION REQUIRED (15 §3).
```

## L. Delivery

```text
Delivery:     no entity — a sale with STAGE + DELIVERY_MAN + DELIVERY_ADDRESS(text 200) + DELIVERY_CHARGE;
              assign / bulk-assign / advance / collect endpoints on /api/sales-activity; merge documents.
Deliveryman:  HR_EMPLOYEE with EMP_TYPE delivery (isDeliveryMan), active, same org; logs in as ROLE06;
              may only move own orders to On The Way | Delivered; cash-in via POS_DELIVERY_COLLECTION.
Location:     none (no coordinates anywhere; region master only).
Tracking:     none (no GPS, no realtime channel, no customer-facing status).
```

## M. Database

```text
Existing tables to reuse:   POS_TRANSACTION_MST/DTL, POS_STOCK_BATCH, POS_STOCK_MOVEMENT, POS_PRODUCT_INFO,
                            POS_BOX_INFO, POS_LEAF_INFO, POS_PRODUCT_UNIT, POS_SALES_DISCOUNT, GLOBAL_PRODUCT (+ global
                            masters), ALL_BRANCH/ALL_COMPANY/ALL_ORGANIZATION, HR_EMPLOYEE, POS_DELIVERY_COLLECTION,
                            POS_NOTIFICATION, ACC_*, ACL_MODULE_INFO/POS_PERMISSIONS/ACL_GROUP (seed only), SUB_AUDIT,
                            POS_CUSTOMER_INFO (as the per-branch shadow, unchanged semantics).
Existing tables to alter:   ALL_BRANCH (+ LATITUDE, LONGITUDE, ECOM_ENABLED, ECOM_DELIVERY_RADIUS_KM,
                            ECOM_MIN_ORDER_AMOUNT, ECOM_OPENING_HOURS, BRANCH_IMAGE_URL);
                            POS_CUSTOMER_INFO (+ ECOM_MANAGED CHAR(1) NULL). Nothing else.
New tables actually required:
  Phase 1   ECOM_CUSTOMER, ECOM_CUSTOMER_AUTH, ECOM_CUSTOMER_REFRESH_TOKEN, ECOM_CUSTOMER_ADDRESS,
            ECOM_CUSTOMER_TYPE_HISTORY, ECOM_AUDIT_LOG, ECOM_CUSTOMER_BRANCH_LINK
  Phase 5–6 ECOM_CART, ECOM_CART_ITEM, ECOM_ORDER, ECOM_ORDER_ITEM, ECOM_ORDER_STATUS_HISTORY
            (+ ECOM_STOCK_RESERVATION only if "reserve then confirm" is chosen)
  Phase 10  ECOM_DELIVERY_TRACKING, ECOM_DELIVERY_PROOF
  Phase 11  ECOM_SLIDER, ECOM_SLIDER_SLIDE, ECOM_SUB_SLIDER_ITEM
  Phase 12  ECOM_COUPON, ECOM_COUPON_BRANCH, ECOM_COUPON_PRODUCT, ECOM_COUPON_CATEGORY, ECOM_COUPON_USAGE,
            ECOM_OFFER, ECOM_OFFER_BRANCH, ECOM_OFFER_PRODUCT
  Phase 13  ECOM_POINT_RULE, ECOM_POINT_LEDGER
  NOT created: ECOM_PRODUCT / ECOM_PRICE / ECOM_STOCK / ECOM_BRANCH / ECOM_DELIVERY_MAN / ECOM_INVOICE.
New columns required:       listed above; on POS_TRANSACTION_MST NONE (FROM_SOURCE exists; ECOM_ORDER holds the FK).
Indexes required:           ECOM_CUSTOMER(MOBILE_NO) uq, (EMAIL) uq partial, (APPROVAL_STATUS); ECOM_CUSTOMER_AUTH
                            (LOGIN_IDENTIFIER) uq; ECOM_CART(customer, branch) uq WHERE OPEN; ECOM_ORDER(ORDER_NO) uq,
                            (PID_TRAN_MST) uq partial, (PID_BRANCH, ECOM_STATUS, PLACED_AT), (PID_ECOM_CUSTOMER, PLACED_AT);
                            ECOM_COUPON(lower(CODE)) uq; ECOM_COUPON_USAGE(coupon, customer); ECOM_POINT_LEDGER
                            (customer, date); ECOM_DELIVERY_TRACKING(order, at); ALL_BRANCH partial on ECOM_ENABLED='Y';
                            optional POS_TRANSACTION_MST(FROM_SOURCE, PID_BRANCH, STAGE) after measurement.
Constraints required:       CHECKs on CUSTOMER_TYPE, APPROVAL_STATUS, ECOM_STATUS, discount_type, branch_scope
                            (NOT NULL, no default); FKs to ERP tables ON DELETE NO ACTION; every NOT NULL on an
                            existing table only after add-nullable → backfill → validate → constrain.
```

## N. Security

```text
Customer isolation:      customer JWT (type=CUSTOMER, own secret) → every /api/ecom/me|orders|cart read filtered by
                         PID_ECOM_CUSTOMER; order lookup by number requires ownership; 404 not 403 for others' orders.
Tenant isolation:        ECOM services never run under a staff org; the order branch is resolved explicitly and
                         handed to ERP services via an additive, tested entry point; cross-org reads confined to a
                         read-only platform service emitting public fields only.
Branch isolation:        staff ECOM boards go through BranchScopeService exactly like Sales Activity; no
                         "all branches" for non-ROLE01 (memory rule); platform operators via ActingBranch (audited).
Staff RBAC:              new modules MOD53 E-Commerce Orders, MOD54 Promotions, MOD55 Customers; customer-type change
                         is a separate right; stock/money actions keep SalesActivityController role guards.
Customer authentication: bcrypt via existing encoder, lockout counter, refresh rotation, OTP-based reset
                         (provider: INFRA CONFIRMATION), global unique identifier, no NoOp fallback ever.
Price protection:        client sends {productId, unit, qty}; wrapper never forwards discountPct / invoiceDiscPct /
                         prices; coupon/points computed server-side; quotes re-derived at checkout.
Stock protection:        checkout inside createSale's row-locked transaction (or reservation + revalidation);
                         browse counts advisory; no batch/cost/expiry exposed.
Coupon protection:       validated on order branch at cart and checkout; usage under row lock; ci codes; limits.
Location privacy:        coordinates only in ECOM_* tables; staff see delivery addresses of their own orders only;
                         driver pings visible to the owner only while ON_THE_WAY; retention purge; no coordinates in URLs.
Existing risks noted:    client-controlled discounts in createSale; plaintext-legacy fallback; constraint messages
                         echoed by two handlers; no login lockout; default secrets in yml; dead PosController with
                         price override; random INVOICE_NO; ddl-auto=update.
```

## O. API Reuse Matrix

| API | Current use | ECOM use | Reuse / Extend / New | Risk |
|---|---|---|---|---|
| `POST /api/sales` → `SalesServiceImpl.createSale` | staff sale | create the POS document at checkout | **REUSE WITH EXTENSION** (internal overload: origin `ECOM`, explicit branch/org/company context; no client discounts) | medium — must be covered by golden regression before merge |
| `GET /api/sales/pricing` | till pricing per customer | per-pharmacy price/units/stock for a global customer | **REUSE WITH EXTENSION** (ECOM read wrapper resolving tier from `ECOM_CUSTOMER` and branch from the request, calling the same pure functions + `StockService`) | low |
| `SalesPriceCalculator`, `CustomerPricing`, `StockUnitConverter` | pure engine | same | **EXACT REUSE** | none |
| `StockService.availablePcs / availableWholeUnits / preview / assertAvailable` | till + inventory | live stock, compare, checkout | **EXACT REUSE** (via service, branch passed explicitly) | low |
| `StockPostingService.post / reverse` | sale write / edit | via createSale; cancel (after fix) | **EXACT REUSE** | — |
| `SalesDiscountService.resolveEffectiveDiscount` | line discount rule | same, via createSale | **EXACT REUSE** | none |
| `/api/sales-activity/**` (list, counts, summary, advance, assign, bulk-assign, collect) | order ops | same actions from the ECOM board; source filter | **REUSE WITH EXTENSION** (source filter/chip; order no column) | low |
| `/api/sales-activity/{id}/cancel` | broken | ECOM cancellation | **REUSE WITH EXTENSION** (fix: reverse + stage + ledger) | medium — ERP behaviour change, business confirmation on reporting |
| `/api/sales-returns` | returns | RETURNED status | **EXACT REUSE** | none |
| `/api/v1/collections`, `AccountingHooks.*` | COD cash-in, ledger | same | **EXACT REUSE** | none |
| `GET /api/employees/delivery-men`, `DeliveryManService` | assignment | same | **EXACT REUSE** | none |
| `/api/products` (list/get), `ProductImage`, `/uploads/**` | staff catalogue | public catalogue read model per ECOM branch | **REUSE WITH EXTENSION** (public read endpoint under `/api/ecom/public`, whitelisted fields, `ECOM_ENABLED` branches only) | low |
| `/api/catalogue` (branch import), `GLOBAL_PRODUCT` | catalogue link | comparison key | **EXACT REUSE** (+ optional "link existing product" staff action) | low |
| `/api/branches`, `ALL_BRANCH` | staff | public pharmacy list / nearby | **REUSE WITH EXTENSION** (new columns; public read endpoint) | low |
| `/api/customers` (`POS_CUSTOMER_INFO`) | branch customers | shadow rows created by the bridge service | **REUSE WITH EXTENSION** (`ECOM_MANAGED` flag; form refuses type edits on shadows) | medium — the only ERP master touched |
| `/api/auth/**`, `JwtTokenProvider`, `PasswordEncoder` | staff auth | pattern + encoder bean | **DO NOT REUSE** for identity; **EXACT REUSE** of the encoder bean and provider pattern | — |
| `POS_REFRESH_TOKENS` | staff refresh | — | **DO NOT REUSE** (PID_USER = ACL_USER) → `ECOM_CUSTOMER_REFRESH_TOKEN` | — |
| `/api/v1/pos/**`, `POS_CART*`, `PosServiceImpl` | dead | — | **DO NOT REUSE** | — |
| `/api/notifications`, `NotificationService` | staff alerts | "new ECOM order" alert to branch staff | **REUSE WITH EXTENSION** (new NOTIF_TYPE) | none |
| `BranchScopeService`, `ActingBranch`, `PermissionResolver` | scoping/RBAC | staff ECOM boards | **EXACT REUSE** | none |
| `/api/v1/reports`, `ReportGrid` | reports | branch ECOM sales inside existing sales reports; separable by `FROM_SOURCE` | **REUSE WITH EXTENSION** (source filter on 2–3 reports; no duplicate reports) | low |
| `TENANT_REGISTRATION` / `AdminOnboarding` | pharmacy sign-up | pattern for customer approval (not the table) | **DO NOT REUSE** the table; copy the pattern | — |
| `GlobalExceptionHandler`, `ApiResponse` | envelope | same | **EXACT REUSE** (+ generic messages on constraint handlers for customer routes) | low |
| `/api/ecom/auth/**`, `/api/ecom/me/**`, `/api/ecom/public/{branches,catalogue,compare,slider}`, `/api/ecom/cart/**`, `/api/ecom/checkout`, `/api/ecom/orders/**`, `/api/ecom/coupons/validate`, `/api/ecom/points/**`, `/api/ecom/delivery/**`; staff `/api/ecom/admin/{customers,orders,coupons,offers,sliders,branch-settings}` | — | storefront + boards | **NEW API REQUIRED** | designed to be thin over the rows above |

## P. Recommended Architecture

```text
                          ┌───────────────────────── customer (browser / phone) ─────────────────────────┐
                          │  React storefront /shop/** in the SAME Vite bundle, outside the staff shell   │
                          │  customer JWT (type=CUSTOMER) · server-driven cart/prices/stock              │
                          └───────────────────────────────────┬──────────────────────────────────────────┘
                                                              │ /api/ecom/**
┌──────────────────────────────── Spring Boot (same jar, same DB, same transactions) ────────────────────────────────┐
│  ECOM layer (new, thin)                                     │  ERP core (existing, unchanged behaviour)            │
│  EcomCustomerAuth (own principal, filter, refresh)          │  SalesServiceImpl.createSale  ← +origin/branch ctx   │
│  EcomCustomerService (register, approve, type, addresses)   │  StockService (FEFO, locks)  SalesPriceCalculator    │
│  EcomCatalogueReadService (public, ECOM_ENABLED branches)   │  CustomerPricing  SalesDiscountService  Accounting   │
│  EcomCompareService (PID_GLOBAL_PRODUCT × branches)         │  SalesActivity (advance/assign/collect/cancel-fixed) │
│  EcomCartService (cart per customer×branch, priced live)    │  DeliveryManService  CollectionService  Reports      │
│  EcomCheckoutService ── shadow customer bridge ──▶ createSale(ECOM) ─▶ ECOM_ORDER ⇄ POS_TRANSACTION_MST         │
│  EcomOrderService (status machine, history, board)          │  BranchScopeService  PermissionResolver  RBAC        │
│  EcomPromotion (coupon→branch, offers)  EcomLoyalty (ledger)│  NotificationService  SUB_AUDIT                      │
│  EcomTracking (pings, proof)  EcomSlider                    │                                                      │
└───────────────────────────────────────────┬─────────────────┴──────────────────────────────────────────────────────┘
                                            │ single Postgres schema pharma
        ECOM_* tables (global customer, cart, order snapshot, coupon, points, tracking, slider)
        ERP tables untouched except additive columns on ALL_BRANCH and POS_CUSTOMER_INFO.ECOM_MANAGED
```

Principles: one ERP, one stock engine, one pricing engine, one POS document; customer global,
branch on the cart/order; every ECOM write that touches stock or money goes through the existing
service and its transaction; ECOM tables hold only ECOM state and snapshots.

## Q. Recommended Phase 1 — Global customer foundation

Implement first, in this order (still subject to approval of this report):

1. **Migrations** `V100` (customer, auth, refresh, address, type history, audit, branch link) and
   `V101` (MOD55 module + rights), idempotent seeds, dry-run on a prod-shaped copy. Entities only
   after the migration (ddl-auto trap).
2. **Customer principal & filter chain segment** for `/api/ecom/**` (own secret, `type=CUSTOMER`),
   `PasswordEncoder` reuse, lockout, refresh rotation, logout.
3. **Registration → pending → approval** APIs (`/api/ecom/auth/register`, `/api/ecom/me`,
   `/api/ecom/admin/customers` approve/reject/suspend/set-type with audit and
   `ECOM_CUSTOMER_TYPE_HISTORY`), permission checks through `PermissionResolver`.
4. **Address book & registration location** (no branch link; coordinates optional).
5. **Shadow-customer bridge service** (create/link `POS_CUSTOMER_INFO` per branch on demand,
   `ECOM_MANAGED` flag, type propagation) — built and tested now, used by checkout later.
6. **Staff UI**: "E-Commerce Customers" page in the existing shell (approve / reject / type change
   / suspend, history). **Customer UI**: register, login, pending page, profile, addresses — in
   the same React app under `/shop/**`, Aura as visual reference, existing icon set.
7. **Tests**: §112, §113, §108 from the brief + migration regression + Playwright register→approve.
8. **Docs** updated (`09`, `17`, `19`), feature flag `app.ecom.enabled=false` by default.

Explicitly **not** in Phase 1: any change to `createSale`, pricing, stock, Sales Activity, or the
ERP customer form beyond the `ECOM_MANAGED` guard.

---

## Answers to the 28 questions

1. **How can the existing customer model support a GLOBAL customer?** It cannot be made global
   in place — `POS_CUSTOMER_INFO` is org- and branch-scoped by design and by index. The global
   identity is a new platform-level table (`ECOM_CUSTOMER` + auth/address/type-history); the
   existing table is used unchanged as a per-branch *shadow* created on first order and linked
   through `ECOM_CUSTOMER_BRANCH_LINK` (C, D above; `04` §5).
2. **Does existing customer data already contain branch ownership?** Yes: `PID_BRANCH NOT
   NULL`, `PID_ORG`, `PID_COMPANY`, `uq_customer_branch_mobile(pid_branch, mobile_no)`,
   `ix_customer_branch`; all 24 dev rows belong to a branch.
3. **Can existing ERP customer authentication be reused?** There is no customer authentication
   to reuse; staff authentication (`ACL_USER`) must not be reused for customers (tenant-bound,
   role/menu semantics, forced-password and tenant-status filters). The encoder bean, JWT
   provider pattern and filter pattern are reused.
4. **What global customer authentication table/API is required?** `ECOM_CUSTOMER_AUTH`
   (global-unique login identifier, bcrypt hash, lockout fields, verification flags) +
   `ECOM_CUSTOMER_REFRESH_TOKEN`; APIs `/api/ecom/auth/{register,login,refresh,logout,forgot,
   reset}`, `/api/ecom/me/**` (D above).
5. **How is customer approval currently implemented?** It is not; customers are active on
   creation by staff. The only approval workflow in the system is tenant registration
   (`TENANT_REGISTRATION` PENDING/APPROVED/REJECTED), which is the pattern to copy (`09`).
6. **How is customer type currently stored?** `POS_CUSTOMER_INFO.CUSTOMER_TYPE` VARCHAR with
   CHECK (Retailer, Wholesaler, Hospital, Pharmacy, Clinic, Special), set by staff, un-audited;
   mapped to the pricing tier by prefix (`CustomerPricing.tierOf`), with Hospital/Pharmacy/Clinic
   falling to RETAILER — **BUSINESS CONFIRMATION REQUIRED**.
7. **How does existing pricing determine Retailer / Wholesaler / Special?** Tier from the
   customer type; unit MRP apportioned from box MRP by packing; six per-product percentages (three
   regular, three special); Retailer = PCS only at the regular PCS price; Wholesaler = all units
   regular; Special = all units special; then `POS_SALES_DISCOUNT` per unit; all in
   `SalesPriceCalculator` / `CustomerPricing` / `SalesServiceImpl` (G, `05`).
8. **How does UOM conversion work?** `PID_BOX → POS_BOX_INFO.NO_OF_LEAF → POS_LEAF_INFO.NO_OF_PCS`;
   stock in pieces only; `StockUnitConverter` converts; missing packing = unknown (refuse), never 1;
   whole-unit availability per lot (F, `06`).
9. **How is live branch stock calculated?** Sum of `POS_STOCK_BATCH.QTY_PCS` for the branch and
   product where status `Y`, qty > 0 and not expired; whole units counted per lot; FEFO order;
   no reservation (H, `07`).
10. **How does POS create MST/DTL?** `SalesServiceImpl.createSale`: validate customer/branch/tier/
    lines/stock → insert MST (Submitted, FROM_SOURCE API) → per line: discount rule, FEFO preview,
    tier price, one DTL per lot → `StockPostingService.post` (row-locked issue + movements) →
    totals/payment status → `AccountingHooks.saleCreated`; one transaction (I, `01` §1, `11`).
11. **How does Sales Activity work?** `/api/sales-activity` over `POS_TRANSACTION_MST` type SL:
    branch-scoped list with SQL filters, stage counts, summary, advance (any listed stage; driver
    limits; merge cascade), assign/bulk-assign DM (validated employee), collect (ledger), broken
    cancel; UI `SalesActivity.jsx` with chips and next-action buttons (`02` §1).
12. **Can Sales Activity support ECOM orders?** Yes, with additive extension (source marker and
    filter, working cancel) and without changing the stage list — plus a dedicated ECOM board for
    ECOM-only state (J, `02`).
13. **Minimum safe dedicated E-Commerce Order Operations dashboard?** A branch-scoped page
    (MOD53) over `ECOM_ORDER` joined to its POS transaction: status chips (NEW…RETURNED), filters,
    detail with items/address/history/coupon, and actions that call the existing
    `SalesServiceImpl` methods; no direct MST/DTL writes (`12`).
14. **How are branches represented?** `ALL_BRANCH` under `ALL_COMPANY` under `ALL_ORGANIZATION`;
    each signed-up pharmacy is its own org with one branch; the platform org has three; tenant
    status per branch (E).
15. **Do branches have latitude/longitude?** No. Additive nullable columns are required (E, `14`).
16. **How are product images served?** Multipart upload to `product_image/<branchId>/<productId>.<ext>`
    under `UPLOAD_ROOT`, filename stored in `POS_PRODUCT_INFO.PRODUCT_IMAGE`, public URL
    `/uploads/product_image/…?v=<mtime>` (permitAll); `GLOBAL_PRODUCT.PRODUCT_IMAGE` for catalogue
    rows; frontend `ProductImage.jsx` with fallback; 16/108 dev products have an image (`03` §5).
17. **Does a coupon/promotion system already exist?** No. Sales % is the price list and
    `POS_SALES_DISCOUNT` is a standing trade discount, not a campaign (K, `15`).
18. **Can coupons be made branch-wise using the existing model?** No existing model to extend;
    new `ECOM_COUPON` + `ECOM_COUPON_BRANCH` with explicit `branch_scope`, validated on the order
    branch (K).
19. **Does loyalty/points already exist?** No (`CASHBACK_AMOUNT` column is dormant). New
    `ECOM_POINT_RULE` + append-only `ECOM_POINT_LEDGER`, global balance (`16`).
20. **How does delivery currently work?** The sale carries the delivery man (HR employee of type
    delivery), address text and stage; managers assign, drivers move to On The Way/Delivered,
    cash is collected into `POS_DELIVERY_COLLECTION` and the ledger (L, `13`).
21. **Is delivery tracking already implemented?** No — stages only; no GPS, no customer view,
    no realtime channel (`14`).
22. **What database migrations are actually required?** `V100` customer foundation, `V101`
    modules/permissions, `V102` branch columns, `V103` `ECOM_MANAGED` flag, `V104` cart/order,
    `V105` indexes, `V106` tracking, `V107` slider, `V108` coupon/offer, `V109` loyalty, later
    constraint promotions; all additive (M, `19`).
23. **What APIs can be reused exactly?** Pricing/UOM calculators, `StockService`,
    `StockPostingService`, `SalesDiscountService`, sales returns, collections, accounting hooks,
    delivery-man lookup, catalogue import, `BranchScopeService`/RBAC, notifications, exception
    envelope (O).
24. **What APIs require extension?** `createSale` (origin + branch context, no client
    discounts), `getPricing` (ECOM wrapper), Sales Activity (source filter, cancel fix),
    products/branches (public read models), customers (`ECOM_MANAGED`), reports (source filter)
    (O).
25. **What APIs must be newly created?** Everything under `/api/ecom/**`: auth, me/addresses,
    public branches/catalogue/compare/slider, cart, checkout, orders/tracking, coupon validate,
    points; staff `/api/ecom/admin/**` for customers, orders board, promotions, sliders, branch
    ECOM settings (O).
26. **What security risks exist?** Client-controlled discounts in `createSale`; legacy plaintext
    fallback; constraint messages echoed; no login lockout; default secrets in yml; dead
    `PosController` with price override; random invoice numbers; `ddl-auto=update`; public
    `/uploads` (fine for images, not for prescriptions); no rate limiting (N, `17` §3).
27. **What performance risks exist?** Price comparison = N branch reads per product (needs a
    short-TTL read model, cache keyed by branch/product/tier/unit); Sales Activity list filtered
    by `FROM_SOURCE` without an index on 140k+ rows (measure, then index); nearby search without
    PostGIS (bounding box + Haversine is fine at hundreds of branches); in-process schedulers on
    a single instance; public catalogue endpoints without caching/rate limiting; uploads on local
    disk for multi-instance deployments (`21`).
28. **What is the safest production architecture?** One backend, one database, one transaction
    boundary: a thin ECOM layer (own customer identity, global tables, snapshots, status machine,
    promotions, points, tracking) that calls the unchanged ERP engines (pricing, UOM, stock, POS
    document, ledger, delivery, RBAC) through additive entry points, with the branch on the
    cart/order, feature-flagged off by default, rolled out per phase behind regression tests (P).

---

**STOP.** Phase 1 will not begin until this report is reviewed and approved. Items marked
BUSINESS / TAX POLICY / ACCOUNTING / INFRA CONFIRMATION REQUIRED need an answer before the phase
that depends on them.
