# 00 — Current System Audit

**Read date:** 2026-09-09
**Method:** direct inspection of the repository at `D:/Projects/Professional/pharma/Docs/Pharma_apps/saas-app`
and of the live development database (`minipos_db`, schema `pharma`, port 5489).
Every count and column list below was read out of that database or that source tree,
not inferred from the SRS.

---

## 1. Architecture

| | |
|---|---|
| Shape | Modular monolith. One Spring Boot application, one database, one React SPA. |
| Backend | Spring Boot 3.3.2, Java 21, Spring Data JPA (Hibernate), Spring Security + JWT, Lombok, springdoc-openapi |
| Frontend | React 18 + Vite 5. No router library — page state in `App.jsx`, URL kept in step by `usePageRoute.js` |
| Database | PostgreSQL. All application objects in schema `pharma` |
| Migrations | Flyway |
| Build | Maven (`backend/pom.xml`). No `mvnw` wrapper in the tree |
| Ports (dev) | app 8585, Postgres 5489, Vite 5173 |

There are no microservices, no message broker, and **no outbound HTTP client anywhere in
the backend** — the application currently has no external runtime dependency other than
its own database. Anything new must not change that without a deliberate decision.

## 2. Database — what is there today

**63 tables, 8 views, 57 sequences** in schema `pharma`.

Table families:

| Prefix | Count | Subject |
|---|---|---|
| `acl_*` | 7 | users, roles, modules, permission templates, tenant module grants, password-reset audit |
| `all_*` | 8 | organisation, company, branch, department, and the geography tables (region/division/district/thana) |
| `global_*` | 8 | the platform-owned shared medicine catalogue |
| `hr_*` | 2 | employees, designations |
| `pos_*` | 27 | products and their masters, customers, suppliers, sales, purchases, stock batches and movements, requisitions, collections, notifications |
| `sub_*` | 6 | subscription plans, terms, subscriptions, invoices, payments, audit |
| other | 5 | `tenant_registration`, `sequence_tracker`, `flyway_schema_history`, `pos_permissions`, `pos_refresh_tokens` |

Trading volume in dev: `pos_transaction_mst` holds **200,466 rows**. This is not an empty
schema, and migrations must be written for a populated table.

## 3. Flyway — the actual state

```
locations:          classpath:db/migration
schemas:            pharma
baseline-on-migrate:true
baseline-version:   63
out-of-order:       false
```

* **Highest applied version: `V72__sku_not_unique.sql`.** The next available version is **V73**.
* **Naming convention:** `V<n>__snake_case_description.sql`. No repeatable (`R__`) migrations exist.
* **Baseline 63 means no migration below V64 has ever run on production.** The tables exist
  because they were built by hand; the seed rows inside V1–V63 did not run there. Any new
  migration that depends on a row a pre-V64 migration inserted will work on dev and fail on
  production. This has already caused one incident (recorded in V64's own header).
* **V64 is the reference migration** for adding a module to the permission system. It
  demonstrates the pattern this work must follow: `setval` every sequence it draws from with
  `GREATEST(...)` first, then insert with `WHERE NOT EXISTS`, then grant into
  `pos_permissions`, `acl_tenant_module` and `acl_perm_template_line`, then a verification
  `SELECT` at the end.

## 4. Authentication

* JWT bearer tokens issued by `AuthController` / `AuthServiceImpl`.
* `AclUser` is the account; `UserPrincipal` is the Spring Security principal and carries
  `userId, username, orgId, companyId, branchId, roleCode, employeeId, superAdmin`.
* Refresh tokens are rows in `pos_refresh_tokens`.
* Passwords: BCrypt, converted lazily on first successful login. 8 accounts are still
  plaintext at the time of writing.
* `V71` added forced password change (`ForcePasswordChange.jsx` stands in place of the whole
  application while the flag is set) and `acl_password_reset_audit`.

**HR and Accounts reuse this untouched.** No new login, no second principal.

## 5. Authorisation — the permission matrix

Three tables resolve to one answer, in `PermissionResolver`:

```
role grant            pos_permissions      (pid_role  × pid_module)
  ∩ plan ceiling      acl_tenant_module    (pid_branch × pid_module)
  ∩ user override     acl_user_permission  (pid_user  × pid_module)
```

Rights are seven booleans: **VIEW, CREATE, EDIT, DELETE, APPROVE, EXPORT, PRINT.**

`PermissionGuard.has("MODxx", Right.X)` is the house call. Its own Javadoc states the rule
this work must obey:

> There is no super-user shortcut. Super Admin passes these checks because the matrix grants
> it the right, not because the code names the role.

**Consequence for this implementation:** authorisation goes in the service through
`PermissionGuard`, not in a `@PreAuthorize` role annotation. Role annotations were removed
from Collection and Sales Discount for exactly this reason (defect G-01).

### Modules registered today

MOD01–MOD18, MOD20–MOD31 (**30 modules**), plus five group headers:
`GRP_MASTER` (19), `GRP_SALES` (20), `GRP_DELIVERY` (21), `GRP_PURCHASE` (23),
`GRP_ADMINISTRATION` (38).

`MOD19` is unused. **The next free module codes are MOD32 onwards.**

### Roles

| pid | code | name |
|---|---|---|
| 1 | ROLE01 | Super Admin |
| 2 | ROLE02 | Administrator (Branch) |
| 3 | ROLE03 | Pharmacist |
| 4 | ROLE04 | **Accounts** |
| 5 | ROLE05 | Purchase Manager |
| 6 | ROLE06 | Delivery Staff |
| 7 | ROLE07 | Store Keeper |
| 8 | ROLE08 | Viewer |
| 9 | ROLE09 | Sales Manager |

**ROLE04 "Accounts" already exists and today holds no module that is about accounts.**
It is the natural grantee for the new modules.

## 6. Tenant and branch model

```
ALL_ORGANIZATION   the pharmacy (tenant)
   └─ ALL_COMPANY  a legal/trading entity within it
        └─ ALL_BRANCH   the outlet — the working scope of every screen
```

Resolution is from the session, never from the request body:
`SecurityUtils.getCurrentOrgId() / getCurrentBranchId() / getCurrentCompanyId()`.
`ActingBranch` lets a platform operator act inside a pharmacy; `getRealOrgId()` exists so
the check that decides *whether* someone may act does not read the acted-as organisation.

**Every screen is the session branch.** There is no cross-branch view anywhere in the
product, and this work must not introduce one.

`TenantStatusFilter` blocks a pharmacy whose subscription has lapsed with HTTP 402; the SPA
listens for that centrally and shows only the Subscription screen.

## 7. Existing HR functionality — more than expected

Three tables already exist and are populated:

| Table | Rows | Sequence |
|---|---|---|
| `hr_employees` | 6 | `seq_hr_employees` |
| `hr_designation` | 3 | `seq_hr_designation` |
| `all_departments` | 3 | `seq_all_departments` |

`HR_EMPLOYEES` columns today: `pid_employee, pid_org, pid_company, pid_branch,
pid_department, pid_designation, emp_id, emp_name, email, phone, hire_date, address,
gender, emp_image, file_name, file_mime_type, vehicle_no, emp_type` + the five `BaseEntity`
audit columns (`status, inserted_by, inserted_date, updated_by, updated_date`).

`EmployeeType` is a closed set held in Java and mirrored by a CHECK constraint from V12:
`DELIVERY_MAN, SALES_REP, CASHIER, PHARMACIST, MANAGER, ACCOUNTANT, OTHER`.

**Exposed today** through `MasterDataController` — `/api/employees` (paged, searchable,
filterable by type), `/api/employees/types`, `/api/employees/{id}`, `/api/departments`,
`/api/designations` — and on the frontend inside `MasterData.jsx` under a group literally
labelled `"HR"`.

**Consumed today** by `DeliveryManService` (who may be assigned a delivery),
`SalesServiceImpl` and `InvoiceDocumentService` (the salesperson on an invoice), and
`PermissionServiceImpl` (linking a user account to a staff record).

**What does not exist:** attendance, leave, salary structure, payroll, advance, loan,
salary payment, separation. Nothing anywhere in the tree.

## 8. Existing Accounts functionality — none

Searched the whole tree and the live schema for `journal`, `ledger`, `chart_of_account`,
`coa_`, `voucher`, `debit`, `credit`, `fiscal`, `period`.

* **No accounting table exists.** No chart of accounts, no journal, no general ledger, no
  fiscal period, no cost centre, no tax configuration, no financial event.
* The only hit for "voucher" is `pos_supplier_payment.voucher_no` — a payment reference
  string, not an accounting voucher.
* Every hit for "ledger" is a comment describing the **stock** movement ledger.

### Financial-adjacent objects that already exist and must be integrated, not duplicated

| Object | Rows (dev) | What it is |
|---|---|---|
| `pos_transaction_mst` / `_dtl` | 200,466 | sales and purchase invoices — the transactional spine |
| `pos_stock_batch` | — | batch-level cost per piece: the source of COGS |
| `pos_stock_movement` | — | every stock in/out with its own `cost_per_pcs` |
| `pos_supplier_payment` | 28 | money out to suppliers, with `voucher_no`, mode, cheque fields, `entry_type` |
| `pos_delivery_collection` | 0 | cash collected from delivery staff |
| `sub_invoice` / `sub_payment` | 1 / 1 | subscription billing. **`sub_payment` already carries an `idempotency_key`** — a precedent worth following |
| `v_supplier_account`, `v_supplier_account_branch`, `v_purchase_payable` | — | supplier balance views computed from operational tables |

That last row matters: **supplier balances today are computed from operational tables, not
from a ledger.** Once a general ledger exists, those two must agree or one of them must
stop being consulted. This is recorded as a decision in `03-accounts-module-srs.md`.

## 9. Reusable services

| Service | Why it matters here |
|---|---|
| `DocumentNumberService` | issues `<TYPE><yyMM><00000>` numbers under a `SELECT ... FOR UPDATE` row lock, in a `REQUIRES_NEW` transaction. Reuse for payroll numbers and journal numbers — do not write a second numbering scheme |
| `PermissionGuard` | the authorisation call |
| `BranchScopeService` | branch resolution for queries |
| `PasswordResetAuditRecorder` | the `REQUIRES_NEW` audit pattern — audit rows must not roll back with the refusal they record |
| `NotificationService` | in-app alerts (`pos_notification`) |
| `ImageStorageService` / `ProductImageStorageService` | file handling, for employee photos and expense attachments |

## 10. Frontend design system

* `src/components/UI.jsx` exports `KpiCard, Card, Pill, statusTone, Table, QtyInput, Field,
  Modal, Tabs, Toast, useToast`. `Card` accepts a `className`.
* `src/components/Icons.jsx` — 97 lines of inline SVG icon components.
* CSS: `styles.css` (tokens + shared), `brand.css`, `responsive.css`, `topbar.css`,
  `quicksales-responsive.css`, `purchase-responsive.css`. Design tokens are CSS variables
  (`--ink`, `--paper`, `--card`, `--line`, `--amber`, `--clay`, `--radius-sm`, `--shadow-lg`, …)
  with a `[data-theme="dark"]` override.
* **The proven responsive pattern** is QuickSales, replicated by Purchase: breakpoints
  **767.98px** and **1199.98px**; a fixed bottom tab bar (`.pos-tabs` / `.pu-tabs`) with
  48px targets and `env(safe-area-inset-bottom)`; tables become one card per row below 768.
  New HR and Accounts screens follow this, they do not invent a third pattern.
* Permissions in the UI come from `usePermission(routeKey)`, reading the menu the server
  already filtered.
* Services live in `src/services/*.js` and go through `src/lib/apiClient.js`.

## 11. Tests

24 test classes under `backend/src/test`, 297 tests, all passing. They are unit tests of
calculation and scope logic (`LandedCostTest`, `PurchaseCalculatorTest`,
`SaleBranchScopeTest`, `PermissionResolverTest`, …), not Spring integration tests. There is
no `@SpringBootTest` in the tree and no test database configuration.

**Consequence:** new tests follow the same shape — pure unit tests of the calculation and
rule logic — plus script-driven verification against the running application, which is how
every recent change in this codebase has been proved.

## 12. What this audit changes about the plan

1. **HR is an extension, not a creation.** Three of the ten HR requirements already have
   tables. `V73` extends them; it does not create `hr_department` beside `all_departments`.
2. **Accounts is a creation.** Nothing to reuse, everything to build — but it must consume
   the existing sales, purchase, payment and collection objects rather than re-recording them.
3. **The baseline-63 trap governs every migration.** Guard every insert with
   `WHERE NOT EXISTS`, `setval` every sequence with `GREATEST`, and never assume a seed row
   from a pre-V64 migration is present.
4. **Authorisation is matrix-only.** No `@PreAuthorize` role names in new code.
5. **`ROLE04 Accounts` is the grantee** the product has been waiting for.
