# Friends Pharma — Source Analysis

## 1. Database Structure (Pharma_Database.sql)

### Key Tables & Relationships
| Table | Purpose | Key Columns |
|-------|---------|-------------|
| POS_ORGANIZATION | Top-level org | PID_ORG, ORG_NAME, STATUS |
| POS_COMPANY | Company under org | PID_COMPANY, COMPANY_NAME, PID_ORG |
| POS_BRANCH | Branch under company | PID_BRANCH, BRANCH_NAME, PID_COMPANY, ADDRESS |
| POS_PRODUCT | Product master | PID_PRODUCT, PRODUCT_NAME, PID_GENERIC, PID_CATEGORY, MRP_PRICE, BUY_PRICE, TP_PRICE, VAT_PCT, ALERT_QTY |
| POS_GENERIC | Generic drug | PID_GENERIC, GENERIC_NAME |
| POS_CATEGORY | Product category | PID_CATEGORY, CAT_NAME |
| POS_LEAF | Strip/leaf packaging | PID_LEAF, LEAF_TYPE, PCS_PER_LEAF |
| POS_BOX | Box packaging | PID_BOX, PID_LEAF, LEAVES_PER_BOX |
| POS_CUSTOMER | Customer master | PID_CUSTOMER, CUSTOMER_NAME, PID_AREA, CREDIT_LIMIT |
| POS_SUPPLIER | Supplier master | PID_SUPPLIER, SUPPLIER_NAME, PID_COMPANY |
| POS_PURCHASE | Purchase header | PID_PURCHASE, PID_SUPPLIER, PURCHASE_DATE, TOTAL_AMOUNT |
| POS_PURCHASE_DETAIL | Purchase lines | PID_DETAIL, PID_PURCHASE, PID_PRODUCT, BATCH_NO, EXPIRY_DATE, QTY, TP_PRICE |
| POS_SALES | Sales header | PID_SALES, PID_CUSTOMER, SALES_DATE, GRAND_TOTAL |
| POS_SALES_DETAIL | Sales lines | PID_DETAIL, PID_SALES, PID_PRODUCT, BATCH_NO, QTY, PRICE |
| POS_STOCK | Stock ledger | PID_PRODUCT, PID_BATCH, QTY_PCS, BRANCH_ID |
| POS_REQUISITION | Requisition header | PID_REQ, PID_SUPPLIER, REQ_DATE, STATUS |
| POS_DELIVERY | Delivery tracking | PID_DELIVERY, PID_SALES, DELIVERY_MAN_ID, STATUS |
| POS_COLLECTION | Collection records | PID_COLLECTION, PID_DELIVERY, AMOUNT, COLLECTION_DATE |
| ACL_USERS | User accounts | USER_ID, USERNAME, PID_ROLE, BRANCH_ID |
| ACL_ROLES | Roles | ROLE_ID, ROLE_NAME |
| ACL_PERMISSIONS | Menu permissions | ROLE_ID, MENU_ID, VIEW_YN, CREATE_YN, EDIT_YN, DELETE_YN |

### Transaction Model
- Purchase → increases PCS stock by batch
- Sales → decreases PCS stock (FEFO order)
- Purchase Return → decreases stock, adjusts supplier payable
- Sales Return → increases stock, adjusts customer receivable
- Requisition → triggers purchase workflow

### Key Calculations
- Line Total = PCS × Base Price × (1 - Discount%)
- Invoice Total = SUM(Line Totals) × (1 - Invoice Discount%)
- Due = Total - Paid
- PCS per Box = PCS per Leaf × Leaves per Box
- Safety Stock = Daily Velocity × Lead Time × Safety Factor

## 2. APEX Application (f110.sql)

### Key Pages Identified
| Page | Name | Purpose |
|------|------|---------|
| P1 | Dashboard | KPI cards, charts, alerts |
| P10 | Product List | Product CRUD |
| P20 | Purchase Entry | Batch-wise purchase |
| P30 | Sales Entry | FEFO sales |
| P40 | Quick Sales | POS terminal |
| P50 | Sales Activity | Kanban order tracking |
| P60 | Delivery | Delivery management |
| P70 | Collection | Collection from delivery men |
| P80 | Requisition | Out-of-stock requisition |
| P90 | Invoice Merge | Consolidate invoices |
| P100 | Inventory | Stock, batch, expiry |
| P110 | Reports | All operational reports |
| P120 | AI Agent | Purchase recommendations |
| P130 | RBAC | Access control |

### Status Flows
- Sales: Submitted → Confirmed → Delivery Assigned → On The Way → Delivered → Collected
- Requisition: Draft → Submitted → Pending Approval → Approved → Converted
- Purchase: Pending → Completed / Cancelled
- Delivery: Assigned → On The Way → Delivered → Collected

## 3. Business Rules

### Stock Rules
- BR-001: Stock is tracked at PCS (piece) level per batch per branch
- BR-002: FEFO (First Expiry, First Out) allocation for all sales
- BR-003: Expired batches cannot be sold
- BR-004: Alert Qty triggers low-stock notification
- BR-005: Safety Stock = Alert Qty × 2
- BR-006: Stock increases only on Completed purchase
- BR-007: Stock decreases only on confirmed sale

### Purchase Rules
- BR-010: Supplier, date, product, batch, expiry mandatory
- BR-011: MNP (trade price) or MRP (market price) costing
- BR-012: Invoice discount applied after line discounts
- BR-013: Return qty cannot exceed purchased qty per batch

### Sales Rules
- BR-020: Customer required for credit sales
- BR-021: Stock validation before saving
- BR-022: FEFO batch allocation is automatic
- BR-023: Return qty cannot exceed sold qty per invoice line

### Requisition Rules
- BR-030: Auto-generate from products below Alert Qty
- BR-031: Approval required before conversion
- BR-032: Approved requisition converts to Purchase Invoice

### Invoice Merge Rules
- BR-040: Minimum 2 invoices required for merge
- BR-041: Only unmerged invoices can be merged
- BR-042: Original invoices are flagged Merged, not deleted
- BR-043: Same customer invoices only

## 4. Calculations

| ID | Name | Formula |
|----|------|---------|
| CAL-01 | PCS per Box | PCS/Leaf × Leaves/Box |
| CAL-02 | Line Total (MNP) | PCS × TP × (1 - Disc%) |
| CAL-03 | Line Total (MRP) | PCS × MRP × (1 - Disc%) |
| CAL-04 | Invoice Total | SUM(Line Totals) × (1 - Invoice Disc%) |
| CAL-05 | Due | Total - Paid |
| CAL-06 | Change | Paid - Total (if positive) |
| CAL-07 | Safety Stock | Alert Qty × 2 |
| CAL-08 | Stock Coverage | Current Stock ÷ Daily Velocity |
| CAL-09 | AI Recommend Qty | MAX(0, Safety Stock - Current Stock) |
| CAL-10 | Gross Profit | Sales Revenue × ~18% |

## 5. Gap Analysis

| Feature | APEX | React Prototype | Status |
|---------|------|-----------------|--------|
| Dashboard KPIs | ✓ | ✓ 16 KPIs | Enhanced |
| Master Data CRUD | ✓ | ✓ 19 entities | Enhanced |
| Purchase Entry | ✓ | ✓ Full MNP/MRP | Existing |
| Sales FEFO | ✓ | ✓ Full FEFO | Existing |
| POS Terminal | ✓ | ✓ Keyboard shortcuts | Enhanced |
| Sales Activity Kanban | ✓ | ✓ 6 stages | Enhanced |
| Delivery Management | ✓ | ✓ New page | New |
| Collection Management | ✓ | ✓ New page | New |
| Requisition Workflow | ✓ | ✓ Full approval | Enhanced |
| Invoice Merge | ✓ | ✓ With history | Enhanced |
| Inventory Module | ✓ | ✓ Dashboard + Ledger | Enhanced |
| Reports (20+) | ✓ | ✓ 28 reports | Enhanced |
| AI Recommendations | ✗ | ✓ New | New |
| AI Chat | ✗ | ✓ New | New |
| RBAC | ✓ | ✓ 9 roles, 9 perms | Enhanced |
| Global Search | ✗ | ✓ Ctrl+K | New |
| Notifications | ✓ | ✓ Bell icon | Enhanced |
| Region Analytics | ✓ | ✓ Dashboard | Enhanced |
