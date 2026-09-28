# 17 — Security (existing controls + ECOM requirements)

## 1. Existing controls (verified in code)

| Control | Implementation |
|---|---|
| Staff authentication | `POST /api/auth/login` → `DaoAuthenticationProvider` → `ACL_USER`; JWT HMAC (jjwt) access 8 h / refresh 7 d (`POS_REFRESH_TOKENS`, revocable on logout); `ForcedPasswordChangeFilter` |
| Password hashing | `DelegatingPasswordEncoder` default `{bcrypt}` strength 12; legacy plaintext matched by `NoOp` fallback and upgraded on login (8 accounts still plaintext per memory) |
| Authorisation | `@EnableMethodSecurity`; `@PreAuthorize("hasAnyRole(…)")` on writes; module rights via `PermissionResolver` (`POS_PERMISSIONS`, `ACL_USER_PERMISSION`, `ACL_TENANT_MODULE`); frontend `usePermission` only hides controls |
| Tenant isolation | every query filters by session `orgId`; `BranchScopeService.requireInOrg`; `TenantStatusFilter` (402 for suspended); `ActingBranchFilter` for platform operators (org-checked, audited) |
| Branch isolation | `BranchScopeService.resolve` — body `branchId` ignored for non-admins; `SaleBranchScopeTest`, `ReportBranchScopeTest`, `DeliveryScopeTest` |
| Price / stock protection | `createSale` recomputes price, unit eligibility, FEFO and totals server-side; `PESSIMISTIC_WRITE` on lots; **exception**: client `invoiceDiscPct` and a higher line `discountPct` are honoured |
| Error handling | `GlobalExceptionHandler` (12 handlers): `BusinessException{status, code}`, validation, 404, 403, upload size; generic `Exception` → "An unexpected server error occurred" (no stack trace); **but** `ConstraintViolationException`/`DataIntegrityViolationException` handlers echo `ex.getMessage()` / most-specific cause — can leak constraint names to staff |
| Uploads | 2 MB app limit (`app.upload.max-size-bytes`), 5 MB servlet limit; extension/MIME allow-list in storage services; path-escape defence in `ProductImageStorageService`; served publicly at `/uploads/**` |
| CSRF | disabled (stateless bearer API) |
| CORS | explicit origin list per environment |
| Audit | `BaseEntity` by/date; `SUB_AUDIT`; `ACL_PASSWORD_RESET_AUDIT`; accounting journals |
| Secrets | JWT secret and DB password have defaults in `application.yml` — **must** be env-overridden in production |
| Rate limiting / brute force | **none** (no lockout counter on `ACL_USER`, no bucket4j) |
| Row-level security in Postgres | none |

## 2. ECOM requirements mapped to the brief (§53–55, §95–99)

| Requirement | Design (no code yet) |
|---|---|
| **Customer isolation** | customer JWT carries `ecomCustomerId` and `type=CUSTOMER`; every `/api/ecom/**` read filters by that id (`ECOM_ORDER.PID_ECOM_CUSTOMER`, addresses, points). Order lookup by number without ownership check is forbidden. |
| **Separate principal** | new `EcomCustomerPrincipal` + `EcomCustomerAuthenticationFilter` on `/api/ecom/**`; the staff filter chain ignores customer tokens and vice versa (token `type` claim + separate signing key or key id). A customer token can never reach `/api/sales/**`; a staff token can never reach `/api/ecom/me/**`. |
| **Customer authentication** | `ECOM_CUSTOMER_AUTH`: same `PasswordEncoder` bean, `FAILED_LOGIN_COUNT` + `LOCKED_UNTIL` (lockout, unlike staff), refresh tokens in `ECOM_CUSTOMER_REFRESH_TOKEN`, password reset via OTP (provider TBD), login identifier normalised (E.164 phone / lower-case e-mail) and globally unique |
| **Tenant isolation** | ECOM services run **without** a staff org context; they resolve the *order branch* explicitly and pass it into ERP services through an additive, tested entry point (`11` §5). Cross-org reads (compare, nearby) are confined to a read-only platform service that emits only public fields. |
| **Branch isolation (staff side)** | ECOM boards use `BranchScopeService.resolve` exactly like Sales Activity; new modules MOD53/54/55 with rights; type-change right is separate from order rights |
| **Price protection** | storefront sends `{productId, unit, qty}` only; the ECOM wrapper never forwards `discountPct` / `invoiceDiscPct` / prices; coupon/points amounts computed server-side |
| **Stock protection** | checkout inside `createSale`'s transaction (lot row locks) or reservation + revalidation; browse numbers are advisory |
| **Coupon protection** | validation on the order branch at cart *and* checkout; usage counted under a row lock on the coupon; codes compared case-insensitively; never returned in lists to customers |
| **Location privacy** | coordinates stored only on `ECOM_*` tables; branch staff see the delivery address of *their* orders only; driver pings visible to the order owner only while `ON_THE_WAY`; retention policy; no coordinates in URLs/query strings (POST bodies) |
| **Public endpoints** | `GET /api/ecom/public/**` (branches, catalogue, compare) rate-limited and cache-friendly; no customer data; `ECOM_ENABLED='Y'` branches only |
| **Input validation** | Bean Validation on every ECOM DTO; quantities positive decimals with scale limits; unit ∈ {BOX, STRIP, PCS}; address lengths; upload MIME allow-list for prescriptions (if added) |
| **Error surface** | reuse `ApiResponse.error(message, code)` with customer-safe messages; tighten the two constraint handlers to generic text for `/api/ecom/**` |
| **Brute force** | login throttling per identifier + IP for `/api/ecom/auth/**` (in-memory or DB counter; no Redis in stack) — **INFRA CONFIRMATION** if a gateway/WAF will do it instead |
| **Secrets** | separate JWT secret for customer tokens; all secrets from environment in production |

## 3. Risks found in the existing system (to fix or fence, not silently)

| # | Risk | Where | Recommendation |
|---|---|---|---|
| S1 | Client-controlled discounts | `SalesServiceImpl` lines 333–335, `invoiceDiscPct` | ECOM wrapper omits them; consider a staff-right check in the ERP later |
| S2 | Plaintext-legacy password fallback | `SecurityConfig.passwordEncoder` | finish the migration (memory: 8 accounts) before customer launch; never enable NoOp for `ECOM_CUSTOMER_AUTH` |
| S3 | Constraint messages echoed | `GlobalExceptionHandler` lines 145, 173 | generic message for customer routes |
| S4 | No login lockout | staff auth | add for customers; staff optional |
| S5 | Public `/uploads/**` | images, receipts, avatars | fine for product/branch images; prescription uploads (if any) must be served through an authenticated endpoint, not `/uploads` |
| S6 | Default secrets in `application.yml` | config | env overrides + startup check |
| S7 | Dead `PosController` accepts `unitPriceOverride` | `/api/v1/pos` | out of ECOM scope; note for ERP housekeeping |
| S8 | `INVOICE_NO` random, not unique | `createSale` | ECOM uses its own sequence-based `ORDER_NO`; ERP collision risk noted |
