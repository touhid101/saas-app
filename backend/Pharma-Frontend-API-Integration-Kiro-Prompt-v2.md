## Friends Pharma — Connect React Frontend to the Live Backend API (v2.0.0)

You are a senior full-stack engineer. The Spring Boot backend for Friends Pharma is fully implemented and documented in `API_DOCUMENTATION.md` (attach this file to the workspace). Base URL: `http://localhost:8080`. Backend DB is PostgreSQL (`minipos_db`) — no Oracle-specific handling needed anywhere in the frontend layer.

The React app (`pharma-app/`) currently runs on **mock data and mock services** under `src/services/*` and `src/lib/mockData.js`. Your job: **replace every mock service with a real API integration layer against these documented endpoints**, with zero change to page-level business logic, calculations, or UI — only the data layer changes.

Do not modify `src/lib/calc.js` calculations, page layouts, or component structure unless a field genuinely doesn't exist on the backend and must be reconciled (log any such change in `docs/frontend-integration-notes.md`).

---

# 1. SOURCE OF TRUTH FOR THE API CONTRACT

`API_DOCUMENTATION.md` is authoritative for endpoint paths, request/response shapes, and error codes. Before writing code:

1. Read `API_DOCUMENTATION.md` in full (all 16 endpoint groups).
2. Cross-check against live Swagger (`http://localhost:8080/swagger-ui.html`, JSON at `/v3/api-docs`) — **if the two disagree on a path or field, trust Swagger/live controller source over the markdown doc**, since docs can lag code. Flag any such mismatch in `docs/frontend-integration-notes.md`.
3. Read every existing `src/services/*.js` mock file to lock in the exact function signatures pages already depend on — preserve those signatures so pages don't need to change.

Produce `docs/frontend-integration-notes.md` first, listing: base URL config, confirmed endpoint paths (flagging any doc-vs-Swagger mismatch, especially whether a `/v1` prefix exists or not), and field-name mismatches found between mock data and real DTOs.

---

# 2. API CLIENT LAYER

Create `src/lib/apiClient.js`, used by every service:

* Base URL from `import.meta.env.VITE_API_BASE_URL`, defaulting to `http://localhost:8080`.
* Wraps `fetch` (or `axios` if already a project dependency — check `package.json` first).
* Attaches `Authorization: Bearer <accessToken>` to every request except `POST /api/auth/login` and `POST /api/auth/register`.
* Unwraps the common envelope `{ success, message, data, errorCode }`. On `success: false`, throw a typed `ApiError` carrying `message` and `errorCode` (see the error code table in Section 8) so calling code shows the backend's business message directly.
* Unwraps the paginated shape exactly as documented: `{ content, totalElements, totalPages, currentPage, pageSize, hasNext, hasPrevious }` — note this is **not** the same field set as a generic Spring `Page` (`currentPage`/`pageSize`, not `number`/`size`). Adapt any table/pagination component that assumed different field names.
* On 401: call `POST /api/auth/refresh` with the stored refresh token once, retry the original request; on repeated failure, clear auth state and redirect to `/login`.
* Centralized error logging without leaking tokens.

---

# 3. AUTHENTICATION INTEGRATION

Wire real calls per the documented shapes:

* `POST /api/auth/login` → `{ username, password }` → response `data` contains `accessToken`, `refreshToken`, `tokenType`, `expiresIn`, and `user: { pidUser, username, fullName, email, roles }`. Store tokens using whatever persistence pattern the app already uses; store the `user` object (including `roles`) in auth context.
* `POST /api/auth/refresh` → `{ refreshToken }` — used transparently by the API client's 401 handling and optionally on app bootstrap to restore session.
* `POST /api/auth/logout` — clear stored tokens/auth state, redirect to `/login`.
* `POST /api/auth/register` → `{ username, password, email, fullName, pidOrg, pidBranch }` — wire this if the app has a registration/user-creation flow (e.g. RBAC → Users → Add User); otherwise skip.
* RBAC/menu gating: this API doc does not list a dedicated `/menu` endpoint — derive dynamic menu/permission gating from `GET /api/rbac/roles/{roleId}/permissions` and `GET /api/rbac/users/{userId}/roles` for the logged-in user's role(s), combined with `GET /api/rbac/modules` for the full module list. If a more direct "my menu" endpoint exists in Swagger but isn't in the markdown doc, prefer it and note the discrepancy in `docs/frontend-integration-notes.md`.
* Keep the existing demo-user login shortcuts in the Login page, pointed at real seeded users.

---

# 4. SERVICE-BY-SERVICE REPLACEMENT

Implement each with the **same exported function names/signatures** the pages already call. Map to these documented endpoints exactly:

**`authService.js`** → `POST /api/auth/login`, `POST /api/auth/refresh`, `POST /api/auth/logout`, `POST /api/auth/register`

**`posService.js`** (Quick Sales) →
`GET /api/pos/cart?orgId=&branchId=&userId=` (get/create cart),
`GET /api/pos/barcode/{barcode}?orgId=` (product lookup),
`POST /api/pos/cart/{cartId}/items` (add item),
`PUT /api/pos/cart/{cartId}/items/{itemId}` (update qty/discount),
`DELETE /api/pos/cart/{cartId}/items/{itemId}` (remove item),
`DELETE /api/pos/cart/{cartId}/clear`,
`POST /api/pos/cart/{cartId}/hold` (`{ holdNote }`),
`POST /api/pos/cart/recall` (`{ cartRef }`),
`GET /api/pos/cart/held?branchId=`,
`PUT /api/pos/cart/{cartId}/customer/{customerId}`,
`POST /api/pos/cart/finalize` (complete sale → returns invoice + lines),
`POST /api/pos/quick-sale` (single-call sale bypassing cart),
`GET /api/pos/receipt/{transactionId}`

**`salesService.js`** →
`POST /api/sales` (create),
`GET /api/sales/{id}`,
`GET /api/sales?page=&size=&orgId=&branchId=&startDate=&endDate=` (paginated list),
`PUT /api/sales/{id}`,
`DELETE /api/sales/{id}`,
`GET /api/sales/search?invoiceNo=&customerName=&startDate=&endDate=`

**`purchaseService.js`** →
`POST /api/purchase`, `GET /api/purchase/{id}`, `GET /api/purchase?page=&size=&orgId=&branchId=`, `PUT /api/purchase/{id}`, `DELETE /api/purchase/{id}`

**`inventoryService.js`** →
`GET /api/inventory/summary?orgId=&branchId=`,
`GET /api/inventory/stock/{productId}`,
`GET /api/inventory/low-stock?orgId=`,
`GET /api/inventory/out-of-stock`,
`GET /api/inventory/expiring?orgId=&days=90`,
`POST /api/inventory/adjust` (`{ pidProduct, pidOrg, pidBranch, adjustmentType, quantity, remarks }`)

**`masterDataService.js`** — one function group per sub-resource, all under `/api/master/*`:
`organizations`, `companies` (`?orgId=`), `branches` (`?orgId=&companyId=`), `categories`, `sub-categories` (`?categoryId=`), `units`, `generics`, `manufacturers`, `customers` (`?orgId=&page=&size=`), `suppliers`, `employees` — each with `GET` (list) and `POST` (create); confirm from Swagger whether `PUT`/`DELETE` exist per sub-resource (the markdown doc only documents list+create for most — check live controllers, since edit/delete are almost certainly needed by the existing Master Data UI).

**`productService.js`** →
`GET /api/products?orgId=&categoryId=&page=&size=`,
`GET /api/products/{id}`,
`GET /api/products/search?orgId=&query=`,
`POST /api/products`,
`PUT /api/products/{id}`,
`DELETE /api/products/{id}`
(product image upload endpoint is not in this doc — check Swagger; if it exists, wire it here, otherwise flag as a backend gap in `docs/frontend-integration-notes.md`)

**`collectionService.js`** →
`GET /api/collection?orgId=&page=&size=`, `POST /api/collection`, `GET /api/collection/{id}`, `GET /api/collection/customer/{customerId}`

**`reportService.js`** →
`GET /api/reports/sales?orgId=&branchId=&startDate=&endDate=&format=` (JSON|PDF|EXCEL),
`GET /api/reports/purchase?orgId=&startDate=&endDate=`,
`GET /api/reports/stock?orgId=&branchId=`,
`GET /api/reports/profit-loss?orgId=&startDate=&endDate=`,
`GET /api/reports/customer-ledger/{customerId}?startDate=&endDate=`,
`GET /api/reports/supplier-ledger/{supplierId}`,
`GET /api/reports/expiry?orgId=&days=90`
(the React Reports page has ~31 report tabs per earlier build notes — this doc only lists 7 core report endpoints; map every tab that has a matching endpoint here, and list any report tab with no backend counterpart yet in `docs/frontend-integration-notes.md` rather than inventing data for it)

**`dashboardService.js`** →
`GET /api/dashboard/summary?orgId=&branchId=`,
`GET /api/dashboard/sales-trend?orgId=&days=30`,
`GET /api/dashboard/top-products?orgId=&limit=10&startDate=&endDate=`
(similarly, the dashboard has ~16 KPI widgets per earlier build notes but only 3 endpoints are documented here — `summary` likely bundles several KPIs into one payload; map each widget to a field in `summary` where possible, and list any KPI with no data source in the notes file)

**`notificationService.js`** →
`GET /api/notifications?orgId=&userId=&page=&size=`,
`GET /api/notifications/unread`,
`GET /api/notifications/count`,
`PUT /api/notifications/{id}/read`,
`PUT /api/notifications/mark-all-read?orgId=&userId=`

**`rbacService.js`** →
`GET /api/rbac/roles?orgId=`,
`POST /api/rbac/roles` (`{ pidOrg, groupName, groupDescription, roleColor, groupCode }`),
`GET /api/rbac/roles/{roleId}/permissions`,
`POST /api/rbac/roles/{roleId}/permissions` (`{ permissions: [{ pidModule, canView, canCreate, canEdit, canDelete }] }`),
`GET /api/rbac/users/{userId}/roles`,
`POST /api/rbac/users/{userId}/roles/{roleId}`,
`GET /api/rbac/modules`
(module CRUD with icons and user CRUD with avatar upload aren't in this doc — check Swagger; if genuinely absent, flag as a backend gap rather than faking it client-side)

**`searchService.js`** →
`GET /api/search/global?orgId=&query=&type=` (type optional: PRODUCT|CUSTOMER|SUPPLIER|INVOICE) → response has `products`, `customers`, `suppliers`, `invoices` arrays

**`aiService.js`** →
`POST /api/ai/chat` (`{ message, context: { orgId, userId } }`) → `{ response, suggestions }`,
`GET /api/ai/recommendations?orgId=&type=` (REORDER|PRICING|DISCOUNT)
(note: this is a conversational chat endpoint plus a typed recommendations endpoint — different shape from the earlier "AI purchase recommendation with full explanation payload" assumed in prior planning. Map the AI Agent page's UI to whichever of these two endpoints actually matches what each part of that page needs; if the page expects a stockout/lead-time explanation payload that `recommendations` doesn't return, flag it in the notes file rather than fabricating fields.)

**`invoiceMergeService.js`** →
`POST /api/invoice-merge` (`{ pidOrg, pidBranch, pidCustomer, invoiceIds[], mergedInvoiceNo, remarks }`), `GET /api/invoice-merge/{id}`

**`requisitionService.js`** →
`POST /api/requisition`, `GET /api/requisition?orgId=&status=&page=&size=`, `PUT /api/requisition/{id}/approve`, `PUT /api/requisition/{id}/reject` (`{ remarks }`)
(no explicit "edit line qty before approval" endpoint is documented — check Swagger for a PUT/PATCH on `/api/requisition/{id}`; if it exists use it, otherwise flag it as a gap since the frontend's approval flow depends on editable quantities)

For every service, delete the mock implementation body but keep exports; each function becomes a thin call into `apiClient`. After conversion, run `grep -R "mockData" src/` to confirm no stray mock imports remain in production code paths.

---

# 5. FIELD MAPPING / DTO RECONCILIATION

Diff every mock object against the real DTOs shown in `API_DOCUMENTATION.md`'s example payloads. Notable ones to get right:

* Product: backend uses `mrpPrice`, `buyPrice`, `tpPrice`, `vatPct`, `discountPct`, `alertQty`, `prescriptionFlag` (`"Y"`/`"N"` string, not boolean) — confirm the frontend's product form/table maps these exactly, not booleans/renamed equivalents from mock data.
* POS barcode lookup returns box/strip/pcs MRP and conversion (`mrpBox`, `mrpStrip`, `mrpPcs`, `pcsPerStrip`, `stripPerBox`) — confirm this matches the POS cart UI's per-unit pricing logic.
* Sales line items use `salesUnit` + `quantity` + `salesPrice` + `discountPct`; POS cart items use `salesUnit` + `qty` + `unitPriceOverride` — these are two different shapes for two different flows (Sales module vs POS module); do not conflate them.
* Notification `isRead` is `0`/`1` (integer), not boolean — handle accordingly in read/unread UI logic.
* `prescriptionFlag`, and any other Y/N string flags found in DTOs, should be normalized to booleans at the service layer boundary (not spread as strings through component logic) unless components already expect string flags.

Log every mismatch and its resolution in `docs/frontend-integration-notes.md`.

---

# 6. ERROR HANDLING

Surface backend error codes/messages using the documented table:

| Code | Meaning |
|---|---|
| `AUTH_001` | Invalid credentials |
| `AUTH_002` | Token expired |
| `AUTH_003` | Unauthorized access |
| `VAL_001` | Validation error |
| `RES_001` | Resource not found |
| `BUS_001` | Business logic error |
| `DB_001` | Database error |
| `SYS_001` | System error |

`ApiError.message` should always be shown to the user in the existing toast/alert/inline-error UI. Use `errorCode` only for programmatic branching (e.g. `AUTH_002` triggers the refresh-then-retry flow; `AUTH_003` triggers a redirect to login without retry). Never replace a backend business message with generic text.

---

# 7. FILE UPLOADS

The documented API has **no product-image or user-avatar upload endpoint**. Before wiring the existing image-upload UI (Product form, User profile), check Swagger for an undocumented multipart endpoint. If none exists at all, do not silently drop the upload UI — keep the UI functional against a local preview only, and record this as a clear backend gap in `docs/frontend-integration-notes.md` (product/user image upload endpoint missing) rather than inventing one.

---

# 8. ENVIRONMENT CONFIGURATION

* Add/update `pharma-app/.env` and `.env.example`:
  ```
  VITE_API_BASE_URL=http://localhost:8080
  ```
* Update `pharma-app/README.md` with instructions for pointing at a different backend host by changing only this variable.
* Confirm CORS is permissive for the Vite dev origin on the backend; if not, note it as a backend follow-up rather than working around it client-side.

---

# 9. VERIFICATION CHECKLIST (page by page, in order)

1. Login (real auth; role/permission state loaded from RBAC endpoints)
2. Dashboard (map every KPI widget to `summary`/`sales-trend`/`top-products`, flag any with no data source)
3. Master Data (every sub-resource: list, create, and edit/delete if confirmed present in Swagger)
4. Products (list, search, create, edit, delete)
5. Inventory (summary, low-stock, out-of-stock, expiring, adjust)
6. Purchase (create, list, update, delete)
7. Sales (create, list, update, delete, search)
8. Quick Sales / POS (cart lifecycle: create → barcode lookup → add/update/remove item → hold/recall → assign customer → finalize; and standalone quick-sale; receipt)
9. Collection (create, list, by customer)
10. Requisition (create, list/filter by status, approve, reject — flag missing line-qty-edit endpoint if absent)
11. Invoice Merge (merge, get merged invoice)
12. Reports (map each of the 7 documented endpoints to its UI tab; flag any UI tab without a backend match)
13. Notifications (list, unread, count, mark read, mark all read)
14. RBAC (roles, role permissions, user roles, modules; flag missing module/user CRUD and avatar upload if absent)
15. Global Search (Ctrl+K)
16. AI Agent (chat + recommendations; flag any UI expecting the fuller explanation payload not present in `recommendations`)

For each page: confirm no leftover `mockData` import, confirm loading/error states work, and trigger at least one real error case (e.g. an invalid login, or exceeding available stock) to confirm the backend's `message`/`errorCode` renders correctly in the UI.

---

# 10. DELIVERABLES

1. `docs/frontend-integration-notes.md` — endpoint confirmations, doc-vs-Swagger mismatches, field mismatches, and a clear list of backend gaps found (missing endpoints for image upload, module/user CRUD, requisition line-qty edit, any unmapped report/KPI/AI fields).
2. `src/lib/apiClient.js`.
3. Every `src/services/*.js` file converted from mock to real, same exported signatures.
4. `.env` / `.env.example` updates.
5. Updated `pharma-app/README.md`.
6. Confirmation that `grep -R "mockData" src/` returns no remaining production usages.

---

# 11. FIRST TASK

1. Read `API_DOCUMENTATION.md` fully, then cross-check against live Swagger/OpenAPI.
2. Read every existing `src/services/*.js` mock file to lock in function signatures.
3. Produce `docs/frontend-integration-notes.md` first, including the list of backend gaps.
4. Build `src/lib/apiClient.js` and the auth integration (Section 3).
5. Convert services module by module in the order listed in Section 9, verifying each against the running backend before moving to the next.

Where the documented API is incomplete relative to what the existing React UI needs (missing endpoints, missing fields), do not fabricate data or silently stub it — implement everything that has a real endpoint, and clearly list every gap in `docs/frontend-integration-notes.md` instead of guessing.
