# Master Data Integration Notes
> Confirmed from: `MasterDataController.java`, `MasterDataService.java`, `MasterDataRequests.java`, `MasterDataResponses.java`, and all 16 entity files.
> Date: 2026-08-15

---

## Endpoint Table — All 16 Entities

| # | Entity (spec) | Table | Base Path | GET list | POST | PUT | DELETE | Notes |
|---|---|---|---|---|---|---|---|---|
| 1 | Organization | `all_organization` | `/api/organizations` | ✅ | ✅ | ✅ `/{id}` | ❌ | No DELETE in controller; `deleteOrganization` exists in service interface but not exposed |
| 2 | Company | `all_company` | `/api/companies` | ✅ `?orgId=` | ✅ | ✅ `/{id}` | ❌ | Scoped by `orgId` |
| 3 | Branch | `all_branch` | `/api/branches` | ✅ `?orgId=&regionId=` | ✅ | ✅ `/{id}` | ❌ | Scoped by `orgId`; optional `regionId` filter |
| 4 | Region | `all_region` | `/api/regions` | ✅ | ✅ | ✅ `/{id}` | ❌ | Body is `{name}` map, not a typed DTO |
| 5 | **Division** | `all_division` | — | ❌ **MISSING** | ❌ | ❌ | ❌ | Entity + repository exist; **no controller endpoint**. Gap recorded below. |
| 6 | **District** | `all_district` | — | ❌ **MISSING** | ❌ | ❌ | ❌ | Same — no controller endpoint. |
| 7 | **Thana** | `all_thana` | — | ❌ **MISSING** | ❌ | ❌ | ❌ | Same — no controller endpoint. |
| 8 | Area | `pos_area_info` | `/api/areas` | ✅ `?branchId=` | ❌ | ❌ | ❌ | **GET-only** — no POST/PUT/DELETE exposed. Gap recorded below. |
| 9 | Product Category | `pos_product_category` | `/api/categories` | ✅ `?orgId=` | ✅ | ✅ `/{id}` | ❌ | Scoped by `orgId` |
| 10 | Product Sub-Category | `pos_product_sub_category` | `/api/sub-categories` | ✅ `?categoryId=&orgId=` | ✅ | ✅ `/{id}` | ❌ | Scoped by `orgId` + `categoryId` |
| 11 | Generic | `pos_generic_info` | `/api/generics` | ✅ `?orgId=` | ✅ | ✅ `/{id}` | ❌ | Scoped by `orgId` |
| 12 | Leaf | `pos_leaf_info` | `/api/leafs` | ✅ `?orgId=` | ✅ | ❌ | ❌ | **No PUT** — update leaf not exposed |
| 13 | Box | `pos_box_info` | `/api/boxes` | ✅ `?orgId=&leafId=` | ✅ | ❌ | ❌ | **No PUT** — update box not exposed; `leafId` filter available |
| 14 | Employee | `hr_employees` | `/api/employees` | ✅ paginated `?branchId=&q=` | ✅ | ✅ `/{id}` | ❌ | `GET /delivery-men?branchId=` also available |
| 15 | Designation | `hr_designation` | `/api/designations` | ✅ | ✅ | ❌ | ❌ | **No PUT** — update designation not exposed |
| 16 | Department | `all_departments` | `/api/departments` | ✅ | ✅ | ❌ | ❌ | **No PUT** — update department not exposed |

---

## Primary Key & FK Field Names (from DTOs)

### Request DTO field names (what the frontend sends)

| Entity | Required Fields | Optional Fields | Parent FK |
|---|---|---|---|
| Organization | `name` | `shortName`, `email`, `phone`, `address`, `status` | — |
| Company | `orgId`, `name` | `shortName`, `email`, `phone`, `address`, `status` | `orgId` → Organization |
| Branch | `orgId`, `companyId`, `name` | `address`, `email`, `phone`, `status`, `regionId` | `orgId`, `companyId`, `regionId` |
| Region | `name` (raw Map) | — | — |
| Category | `name` | `status` | — (orgId injected from JWT) |
| Sub-Category | `name`, `categoryId` | `status` | `categoryId` → Category |
| Generic | `name` | `description`, `companyId`, `status` | `companyId` → ProductCompany |
| Leaf | `type`, `noOfPcs` | `status` | — |
| Box | `leafId`, `title`, `noOfLeaf` | `status` | `leafId` → Leaf |
| Department | `name` | `status` | — (orgId injected) |
| Designation | `name` | `description`, `status` | — (orgId injected) |
| Employee | `empId`, `empName`, `branchId` | `email`, `phone`, `departmentId`, `designationId`, `gender`, `address`, `vehicleNo`, `status` | `branchId`, `departmentId`, `designationId` |

### Response DTO field names (what the frontend receives)

| Entity | PK field | Name field | Parent FK shown |
|---|---|---|---|
| Organization | `id` | `name` | — |
| Company | `id` | `name` | `orgId` |
| Branch | `id` | `name` | `orgId`, `companyId`, `regionId`, `regionName` |
| Region | `id` | `name` | — |
| Category | `id` | `name` | `orgId` |
| Sub-Category | `id` | `name` | `categoryId`, `categoryName`, `orgId` |
| Generic | `id` | `name` | `companyId`, `companyName`, `orgId` |
| Leaf | `id` | `type` | `orgId` |
| Box | `id` | `title` | `leafId`, `leafType`, `orgId`, `noOfLeaf`, `pcsPerBox` |
| Department | `id` | `name` | `orgId` |
| Designation | `id` | `name` | `orgId` |
| Employee | `id` | `empName` | `orgId`, `branchId`, `departmentId`, `designationId` |
| Area | `id` | `name` | `branchId`, `branchName` |
| Customer | `id` | `name` | `orgId`, `branchId`, `areaId` |
| Supplier | `id` | `name` | `orgId`, `branchId`, `companyId` |

---

## Backend Gaps (missing or incomplete endpoints)

> **All 7 gaps have been implemented.** See below for the resolved endpoints.

### GAP-1: Division — ✅ RESOLVED
- Added: `GET /api/divisions?regionId=`, `GET /api/divisions/{id}`, `POST /api/divisions`, `PUT /api/divisions/{id}`
- Notes: AllDivision uses manual PK (no sequence) — ID generated as max+1 in service.

### GAP-2: District — ✅ RESOLVED
- Added: `GET /api/districts?divisionId=`, `GET /api/districts/{id}`, `POST /api/districts`, `PUT /api/districts/{id}`
- Notes: AllDistrict uses manual PK (no sequence) — ID generated as max+1 in service.

### GAP-3: Thana — ✅ RESOLVED
- Added: `GET /api/thanas?districtId=`, `GET /api/thanas/{id}`, `POST /api/thanas`, `PUT /api/thanas/{id}`
- Notes: AllThana uses SEQ_ALL_THANA sequence — JPA handles ID automatically.

### GAP-4: Area — ✅ RESOLVED
- Added: `POST /api/areas`, `PUT /api/areas/{id}`
- Body fields: `areaName` (required), `branchId` (required), `thanaId`, `employeeId`, `status`

### GAP-5: Leaf/Box — ✅ RESOLVED
- Added: `PUT /api/leafs/{id}`, `PUT /api/boxes/{id}`
- Leaf body: `type`, `noOfPcs`, `status` (all optional for partial update)
- Box body: `leafId`, `title`, `noOfLeaf`, `status` (all optional)

### GAP-6: Department/Designation — ✅ RESOLVED
- Added: `PUT /api/departments/{id}`, `PUT /api/designations/{id}`
- Department body: `name`, `status`
- Designation body: `name`, `description`, `status`

### GAP-7: No DELETE on any entity except Product — still by design
- Soft-delete only: use PUT with `status: "N"` for entities that have a PUT endpoint.
- Product is the only entity with a real DELETE (soft-deactivate via `DELETE /api/products/{id}`).

---

## Cascading Dropdown Resolution

| Dependent | Depends On | Cascade endpoint |
|---|---|---|
| Company | orgId | `GET /api/companies?orgId={orgId}` |
| Branch | orgId, companyId | `GET /api/branches?orgId={orgId}` |
| Sub-Category | categoryId | `GET /api/sub-categories?categoryId={id}` |
| Box | leafId | `GET /api/boxes?leafId={leafId}` |
| **Division** | regionId | `GET /api/divisions?regionId={regionId}` ✅ |
| **District** | divisionId | `GET /api/districts?divisionId={divisionId}` ✅ |
| **Thana** | districtId | `GET /api/thanas?districtId={districtId}` ✅ |
| Area | branchId | `GET /api/areas?branchId={branchId}` |

---

## Entities In-Scope but NOT in the 16 (handled separately)

These are in `MasterDataController` but were not listed in the integration spec:
- `Unit` (`/api/units`) — POST only (no PUT exposed), org-scoped
- `ProductCompany` (`/api/product-companies`) — full GET/POST/PUT — used as Generic's `companyId`
- `Customer` (`/api/customers`) — full CRUD + toggle-status
- `Supplier` (`/api/suppliers`) — full CRUD + toggle-status
- `Product` (`/api/products`) — full CRUD + image upload + DELETE (soft)

These are wired as part of the 16-entity service file for completeness (they feed dropdowns) but their separate screens are out of scope unless the spec is extended.
