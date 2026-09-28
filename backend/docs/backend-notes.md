# Friends Pharma Backend — Implementation Notes

## 1. Oracle Procedures & Packages Decision Log

### PROC_EXPIRY_WISE_SALES_QTY_DIS
**Decision: REUSE — call via SimpleJdbcCall**
- Parameters: pPID_PRODUCT NUMBER, pQTY NUMBER, pPID_TRAN_MST NUMBER, pSALES_UNIT VARCHAR2
- Returns: nothing (side-effecting INSERT into POS_TRANSACTION_DTL)
- Logic: Reads VW_PRODUCT_STOCK ordered by EXPIRY_DATE ASC, distributes requested qty
  across available batches (FEFO), inserts one row per batch consumed into POS_TRANSACTION_DTL.
- Spring usage: Call via `SimpleJdbcCall` after creating POS_TRANSACTION_MST.
  The procedure selects SALES_PRICE based on pSALES_UNIT (BOX/STRIP/PCS) from VW_PRODUCT_STOCK.
- Required: VW_PRODUCT_STOCK view must exist in the Oracle schema with columns:
  PID_PRODUCT, EXPIRY_DATE, STOCK_QTY, ACTUAL_PURCHASE (→ MRP_PRICE),
  SALES_PRICE (box), STRIP_SALES_PRICE, PCS_SALES_PRICE, STOCK_QTY_LEAF, STOCK_QTY_BOX.

### ACL Package (CUSTOM_AUTH, GET_CURRENT_USER_ORG_PID, GET_IS_SUPER_ADMIN)
**Decision: REPLACE — do not call from Spring Boot**
- CUSTOM_AUTH compared plaintext passwords from ACL_USER.PIN_NO — insecure, not BCrypt.
- Spring Security handles authentication with BCrypt hashed passwords in ACL_USER.PASSWORD_HASH.
- GET_CURRENT_USER_ORG_PID used APEX V('USER') — not available in Spring.
- GET_IS_SUPER_ADMIN checked IS_ADMIN='Y' — replaced by role-based check in Spring Security.

### PKG_USER (INS/UPD/DEL ACL_USER and APP_USERS)
**Decision: REPLACE — Spring Data JPA handles CRUD**
- These are simple CRUD wrappers. Spring Data JPA repositories are the correct approach.
- No business logic in these procedures beyond INSERT/UPDATE/DELETE.

### POS_MANAGE_ORDERS Package (pos_add_product, pos_update_product, pos_remove_product)
**Decision: REPLACE — Spring Boot service layer replaces this**
- Heavily dependent on APEX's apex_json package (not available outside APEX).
- Logic extracted and reimplemented in PosService.java and SalesService.java.
- pos_add_product/pos_update_product call PROC_EXPIRY_WISE_SALES_QTY_DIS internally;
  Spring Boot service calls the procedure directly.

### COM Package (GET_PROPERTY_HEADER, SPELL_OUT, GET_VALUEBYQUERY)
**Decision: REPLACE in Java**
- GET_PROPERTY_HEADER: Java service reads ALL_BRANCH.REPORT_HEADER via JPA.
- SPELL_OUT / FN_NUMBER_TO_WORD / FN_NUMBER_TO_WORD: Implemented as AmountToWordConverter.java utility.
- GET_VALUEBYQUERY: Dynamic SQL execution — not needed in REST backend.

### FN_INVOICE_NO_GEN
**Decision: REPLACE — Spring Boot generates invoice numbers**
- Used APEX SEQUENCE_TRACKER pattern (not standard).
- Spring Boot generates formatted invoice IDs: {TYPE}{YYMM}{5-digit sequence} using database sequence.

### PROC_AUTO_CLOSE_NOT_SUBIMITTED
**Decision: REPLACE as scheduled job (@Scheduled in Spring)**
- Deletes unsubmitted POS transactions older than 24 hours.
- Reimplemented as Spring @Scheduled task in CartCleanupScheduler.java.

### FN_MENU_DISPLAY / FN_MENU_DISPLAY_NORMAL / GET_BREADCRUMB / GET_BREADCRUMB_PAGE
**Decision: OBSOLETE — APEX-specific, not used in React/Spring**
- These generate APEX URLs (f?p=...) — completely irrelevant for REST API.

---

## 2. Schema Additions (not in Pharma_Database.sql)

| Table | Column Added | Type | Reason (React page/field) |
|-------|-------------|------|--------------------------|
| POS_PRODUCT_INFO | SUB_CATEGORY_ID | NUMBER | MasterData.jsx: subCategoryId field in product form |
| POS_PRODUCT_INFO | IMG_COLOR | VARCHAR2(10) | QuickSales.jsx: imgColor hex for product card swatch |
| POS_PRODUCT_INFO | PRESCRIPTION_FLAG | VARCHAR2(1) | MasterData.jsx: prescriptionFlag Y/N |
| POS_PRODUCT_INFO | IMAGE_BLOB_FIELD | BLOB already exists (IMAGE col) | ProductImage.jsx: product image upload |
| POS_TRANSACTION_MST | STAGE | VARCHAR2(30) | SalesActivity.jsx: order stages (Submitted/Confirmed/etc.) |
| POS_TRANSACTION_MST | DELIVERY_MAN_ID | NUMBER | SalesActivity.jsx: deliveryManId assigned to invoice |
| POS_TRANSACTION_MST | DELIVERY_MAN_NAME | VARCHAR2(100) | SalesActivity.jsx: deliveryManName display |
| POS_TRANSACTION_MST | REQUISITION_SOURCE | VARCHAR2(10) | AIAgent.jsx: source flag MANUAL/AI for requisitions |
| POS_TRANSACTION_MST | MERGED_STATUS | VARCHAR2(10) DEFAULT 'N' | InvoiceMerge.jsx: merged flag |
| ACL_USER | ROLE_ID | NUMBER | RBAC.jsx: role assignment (FK to ACL_GROUP) |
| ACL_USER | AVATAR_URL | VARCHAR2(500) | Topbar.jsx: user avatar image URL |
| ACL_USER | PASSWORD_HASH | VARCHAR2(500) | Login.jsx: BCrypt password hash |
| ACL_USER | LAST_LOGIN | TIMESTAMP(6) | UserMenu in Topbar: lastLogin display |
| ACL_USER | EMAIL | VARCHAR2(100) | RBAC.jsx: user email field |
| ACL_USER | MOBILE_NO | VARCHAR2(20) | RBAC.jsx: user mobile field |
| ACL_USER | FULL_NAME | VARCHAR2(100) | User profile display name |
| ACL_GROUP | ROLE_COLOR | VARCHAR2(20) | RBAC.jsx: role color for UI badges |
| ACL_GROUP | ROLE_CODE | VARCHAR2(10) | Role identifier (ROLE01..ROLE09) |
| ACL_MODULE_INFO | ICON_KEY | VARCHAR2(50) | RBAC.jsx: module icon identifier |
| ACL_MODULE_INFO | ROUTE_KEY | VARCHAR2(100) | Sidebar.jsx: frontend route key |
| ACL_MODULE_INFO | GROUP_NAME | VARCHAR2(50) | Sidebar.jsx: nav group (OVERVIEW/OPERATIONS/etc.) |
| ACL_MODULE_INFO | SORT_ORDER | NUMBER | Module display order |
| **NEW: POS_PERMISSIONS** | Full table | — | RBAC.jsx Permission Matrix (view/create/edit/delete per role per module) |
| **NEW: POS_SALES_DISCOUNT** | Full table | — | SalesDiscount.jsx: company+product-wise Box/Strip/PCS/Special discount |
| **NEW: POS_REFRESH_TOKENS** | Full table | — | JWT refresh token storage |
| **NEW: POS_INVOICE_MERGE** | Full table | — | InvoiceMerge.jsx: merge master record |
| **NEW: POS_INVOICE_MERGE_DTL** | Full table | — | Source invoices linked to merged invoice |
| ALL_BRANCH | REGION_ID | NUMBER | Dashboard.jsx: regionId for region breakdown |

---

## 3. ASSUMPTION — BACKEND ONLY Items

| ID | Assumption | Where |
|----|-----------|-------|
| A01 | VW_PRODUCT_STOCK view exists in Oracle DB and has columns: PID_PRODUCT, EXPIRY_DATE, STOCK_QTY, ACTUAL_PURCHASE, SALES_PRICE, STRIP_SALES_PRICE, PCS_SALES_PRICE, STOCK_QTY_LEAF, STOCK_QTY_BOX, PRODUCT_NAME | InventoryService / FEFO |
| A02 | SEQUENCE_TRACKER table exists with SEQ_NAME + LAST_YEAR_MONTH columns used by FN_INVOICE_NO_GEN | Invoice numbering (replaced by Spring logic) |
| A03 | Gross profit margin for dashboard KPI = sales * 0.18 (18%) — not derived from actual cost | DashboardService |
| A04 | Net profit = gross profit * 0.65 (35% operating expense) — not from actual P&L accounts | DashboardService |
| A05 | AI salesGrowth is random (no historical comparison table exists) — returns 0 from backend | AiService |
| A06 | ALL_ORGANIZATION has a separate POS_REGION table or REGION_ID column added to ALL_BRANCH | RegionService |
| A07 | Delivery man is an HR_EMPLOYEES record with a specific designation or is identified by PID_EMPLOYEE | DeliveryService |
| A08 | POS_TRANSACTION_MST.STATUS values: NULL=Draft, 'Y'=Completed/Active, 'C'=Cancelled, 'M'=Merged | TransactionService |
| A09 | The PERMISSION_KEYS in React (view/create/edit/delete/approve/print/export/finalize/cancel) map to 9 boolean columns in POS_PERMISSIONS | PermissionService |

---

## 4. Package/Table Reference Map

```
React Page          → Oracle Tables Used
───────────────────────────────────────────────────────────
Dashboard           → POS_TRANSACTION_MST, POS_TRANSACTION_DTL, POS_PRODUCT_INFO, BATCHES (VW_PRODUCT_STOCK)
MasterData          → ALL_ORGANIZATION, ALL_COMPANY, ALL_BRANCH, POS_CUSTOMER_INFO, POS_SUPPLIER_INFO,
                      POS_PRODUCT_INFO, POS_GENERIC_INFO, POS_COMPANY_INFO, POS_PRODUCT_CATEGORY,
                      POS_PRODUCT_SUB_CATEGORY, POS_PRODUCT_UNIT, POS_LEAF_INFO, POS_BOX_INFO,
                      POS_AREA_INFO, ALL_DEPARTMENTS, HR_DESIGNATION, HR_EMPLOYEES, ALL_DIVISION, ALL_DISTRICT, ALL_THANA
Purchase            → POS_TRANSACTION_MST (type=PU), POS_TRANSACTION_DTL
Sales               → POS_TRANSACTION_MST (type=SL), POS_TRANSACTION_DTL, PROC_EXPIRY_WISE_SALES_QTY_DIS
QuickSales/POS      → POS_TRANSACTION_MST (type=SL), POS_TRANSACTION_DTL, POS_MANAGE_ORDERS (replaced)
SalesActivity       → POS_TRANSACTION_MST (stage field), POS_TRANSACTION_DTL, HR_EMPLOYEES (delivery men)
SalesDiscount       → POS_SALES_DISCOUNT (new)
Delivery            → POS_TRANSACTION_MST (DELIVERY_MAN_ID, STAGE columns)
Collection          → POS_DELIVERY_COLLECTION, POS_TRANSACTION_MST
Requisition         → POS_REQUISITION_MST, POS_REQUISITION_DTL
InvoiceMerge        → POS_INVOICE_MERGE, POS_INVOICE_MERGE_DTL, POS_TRANSACTION_MST
Inventory           → VW_PRODUCT_STOCK, POS_TRANSACTION_DTL, POS_PRODUCT_INFO
Reports             → Multiple tables — per-report SQL in ReportService
RBAC                → ACL_USER, ACL_GROUP, ACL_MODULE_INFO, ACL_MENU_LIST, POS_PERMISSIONS
AI Agent            → Computed from POS_TRANSACTION_DTL, VW_PRODUCT_STOCK
Notifications       → POS_NOTIFICATIONS
```
