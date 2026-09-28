
## Friends Pharma — React Pharmacy POS & Inventory Prototype

You are a senior product architect, React/TypeScript engineer, UX designer, business analyst and Oracle/APEX reverse-engineering specialist.

I need you to build a **complete, production-quality React prototype** for **Friends Pharma Pharmacy POS, Inventory, Sales, Purchase, Requisition, Delivery, Collection, Reporting, RBAC and AI Analytics**.

The prototype must be based on the three source files provided in this project:

1. `SRS.html`
2. `Pharma_Database.sql`
3. `f110.sql`

Do NOT build a generic pharmacy application.

You must first reverse-engineer the business structure, entities, workflows, calculations, statuses, reports, navigation and UI behavior from these three files and then implement the React prototype.

---

# 1. SOURCE-OF-TRUTH RULE

Treat the supplied files in this priority order:

### Priority 1 — Existing business/database structure

`Pharma_Database.sql`

Use it to understand:

* tables
* columns
* relationships
* organization hierarchy
* product structure
* transaction structure
* purchase/sales structure
* requisition structure
* stock structure
* customer/supplier structure
* payments
* delivery
* collection
* ACL/RBAC
* generic/medicine/product relationships
* leaf/box
* company/branch structure

Do not rename business concepts unnecessarily.

---

### Priority 2 — Existing production behavior

`f110.sql`

This is an Oracle APEX 22.2 export of the existing Pharma application.

Reverse-engineer:

* page list
* page purpose
* navigation
* forms
* reports
* dashboards
* AJAX processes
* PL/SQL processes
* calculations
* validations
* status transitions
* invoice workflow
* requisition workflow
* delivery workflow
* collection workflow
* stock ledger logic
* invoice merge logic
* payment logic
* return logic
* dashboard calculations
* report filters
* print/report layouts

Do not ignore existing production logic merely because React will replace APEX.

---

### Priority 3 — SRS

Use `SRS.html` for:

* overall product scope
* business terminology
* requirements
* calculations
* validations
* UX expectations
* pharmacy POS concepts

If the SRS references content that is not physically available in the repository, do not invent exact rules.

Where source files conflict:

1. Database structure
2. Existing APEX business logic
3. SRS
4. New requirements in this prompt

Use the new requirements to enhance the application, but preserve existing business behavior wherever possible.

---

# 2. IMPORTANT PROTOTYPE RULE

This is a **React prototype**, not the final production backend.

Therefore:

* React must run independently.
* Use realistic mock data.
* Use local state / mock services / localStorage where appropriate.
* Do NOT require Oracle DB to run the prototype.
* Do NOT require Oracle APEX.
* Do NOT create fake REST endpoints that cannot run.
* Create a clean service layer so the mock API can later be replaced with Spring Boot REST APIs.
* Business calculations must be implemented in reusable TypeScript functions.
* Do not put important business calculations directly inside JSX.
* Do not use hardcoded page-specific duplicated logic.

The application must feel like a real working ERP/POS application.

---

# 3. TECHNOLOGY STACK

Use:

* React
* TypeScript
* Vite
* React Router
* Tailwind CSS
* Lucide icons
* Recharts or equivalent charting library
* React Hook Form
* TanStack Query where useful
* date-fns



Use a clean modern architecture.

Suggested structure:

src/
app/
components/
layouts/
pages/
features/
modules/
services/
mock/
data/
hooks/
store/
types/
utils/
validations/
charts/
reports/
constants/

---

# 4. DESIGN DIRECTION

Create a beautiful modern pharmacy ERP UI.

The UI must NOT look like old Oracle APEX.

Design characteristics:

* professional enterprise SaaS
* clean
* fast
* modern
* spacious
* responsive
* pharmacy/healthcare feel
* desktop-first but responsive
* strong visual hierarchy
* excellent tables
* compact forms
* keyboard-friendly POS screens
* useful empty states
* meaningful toast notifications
* confirmation dialogs
* loading states
* skeletons
* badges
* status colors
* command/search interface
* global search
* notification center

Use a consistent design system.

---

# 5. APPLICATION LAYOUT

Create:

### Desktop

Left collapsible sidebar:

* Dashboard
* Master Data
* Purchase
* Sales
* POS
* Requisition
* Delivery
* Inventory
* Payments
* Reports
* Analytics
* AI Assistant
* Administration

Top header:

* global search
* branch selector
* organization/company selector
* notifications
* current user
* profile menu
* theme switch
* help

Main content:

* breadcrumb
* page title
* page actions
* content
* responsive cards/tables

---

# 6. MULTI-ORGANIZATION STRUCTURE

Support:

Organization
→ Company
→ Branch

All transactional screens must understand the selected:

* Organization
* Company
* Branch

Provide branch context in the application header.

Add:

### Region

Introduce a Region concept at the UI/analytics level.

Example:

Region
→ Division
→ District
→ Thana
→ Area

Use the existing location structures from the database where applicable.

Region must be available in:

* dashboards
* sales analytics
* purchase analytics
* inventory analytics
* customer analytics
* delivery analytics
* reports

---

# 7. DASHBOARD

Create an executive dashboard with at least **10 analytics widgets**.

Minimum:

1. Today's Sales
2. Today's Purchase
3. Gross Profit
4. Net Profit
5. Total Orders
6. Outstanding Customer Due
7. Supplier Payable
8. Low Stock Products
9. Near Expiry Products
10. Out of Stock Products
11. Sales Return
12. Purchase Return
13. Collection
14. Delivery Pending
15. Top Products
16. Top Customers

Dashboard filters:

* Date range
* Organization
* Company
* Branch
* Division
* District
* Thana
* Area

Charts:

* Sales trend
* Purchase trend
* Sales vs Purchase
* Profit trend
* Region-wise sales
* Branch-wise sales
* Category-wise sales
* Top products
* Top customers
* Payment type distribution
* Delivery status
* Inventory health

Provide drill-down.

Clicking a dashboard card should navigate to the relevant report.

---

# 8. MASTER DATA

Create full CRUD prototype screens for:

### Organization

Fields:

* Organization ID
* Organization Name
* Status

### Company

* Company
* Organization
* Status

### Branch

* Organization
* Company
* Branch
* Address
* Region
* Division
* Area
* Status

### Supplier

* Supplier Code
* Supplier Name
* Company
* Branch
* Address
* Mobile
* Email
* Credit Limit
* Opening Balance
* Status

### Customer

* Customer Code
* Customer Name
* Branch
* Area
* Address
* Mobile
* Email
* Credit Limit
* Opening Balance
* Status

### Product Company

Use the business concept represented by the database.

### Generic

* Generic
* Company
* Description
* Status

### Leaf

* Leaf
* Leaf Type
* Status

### Box

* Box
* Leaf
* Box Size
* Status

### Product

Create a comprehensive product master:

* Product Code
* Product Name
* Generic
* Company
* Category
* Sub Category
* Unit
* Leaf
* Box
* MRP
* Purchase Price
* Sales Price
* TP %
* VAT
* Reorder Level
* Safety Stock
* Minimum Stock
* Maximum Stock
* Status
* Image
* Barcode
* Batch/expiry tracking
* Prescription flag where applicable


Example:
PID_PRODUCT - 274
PID_ORG - 60 
PRODUCT_NAME - Linarol Tab. 5 mg 
BARCODE - 274 
PID_UNIT - 46 
MRP_PRICE - 756 
BUY_PRICE - 651.15
OPENING_QTY - 0 
VAT_PCT - 17.4 
FILE_MIME_TYPE - image/png 
FILE_NAME - 274.PNG 
FILE_CHARACTER_SET - 
STATUS - Y 
INSERTED_DATE
 - SYSDATE 
INSERTED_BY - 101160 
UPDATED_DATE 
UPDATED_BY  
PID_BRANCH - 80 
PID_CATEGORY - 60 
PID_GENERIC - 414 
SKU - 5
PID_BOX - 16
IGTA - 96.51
NO_OF_PCS - 42
DISCOUNT_PCT  
CONVERSION_RATE - 26.635
SALES_PER - 12 
PID_COMPANY - 27 
TP_PRICE - 554.64
ALERT_QTY - 2
SALES_PER_LEAF - 12
SALES_PER_PCS - 10  
SALES_PER_SPC - 13 
SALES_PER_SPC_LEAF - 12 
SALES_PER_SPC_RET - 10


### Category

### Sub Category

### Unit

### Area

### Department

### Designation

### Employee

### Medicine

Preserve the distinction between Product / Medicine / Generic found in the source application.

---

# 9. PURCHASE MODULE

Create:

## Purchase Entry

Page must contain:

Header:

* Supplier
* Organization / Company / Branch Auto From User session
* Purchase Date
* Invoice No
* Challan No
* Purchase Type
* Payment Type
* Remarks

Lines:

* Product
* Generic
* Batch No
* Expiry Date
* Quantity
* Unit
* MRP
* Purchase Price (BOX / STRIP / PCS)
* Sales Price (BOX / STRIP / PCS) FROM Sales Percentage
* Discount %
* Discount Amount
* Tax
* TP %
* Total

Calculations:

Line Total =
Quantity × Purchase Price(BOX / STRIP / PCS)

Discount Amount =
Line Total × Discount %

Net Line Amount =
Line Total - Discount

Invoice Total =
SUM(Net Line Amount)

Tax =
appropriate taxable amount

Amount To Be Paid =
Invoice Total + Tax + Service Charge - Invoice Discount

Implement calculations in reusable functions.

---

# 10. BATCH-WISE PURCHASE

Purchase must support:

* batch number
* expiry date
* purchase price (BOX / STRIP / PCS)
* MRP
* sales price (BOX / STRIP / PCS)
* quantity

A product can have multiple batches.

Show batch inventory:

Product
→ Batch
→ Expiry
→ Purchase Price
→ MRP
→ Sales Price
→ Available Quantity

Add:

### Batch History

* purchase date
* supplier
* invoice
* batch
* expiry
* quantity
* price
* current balance

---

# 11. PURCHASE VALIDATIONS

Implement meaningful validation.

Examples:

* Supplier required
* Branch required
* Purchase date required
* Invoice number required where applicable
* Product required
* Quantity > 0
* Purchase price >= 0
* Sales price >= 0
* Expiry date valid
* Batch number required for batch-controlled product
* No duplicate batch line where prohibited
* Discount cannot exceed allowed amount
* Invoice totals must reconcile
* Cannot finalize empty invoice
* Cannot save invalid product
* Cannot use inactive product
* Cannot use inactive supplier

Display errors inline.

Also display success/error toast messages.

---

# 12. PURCHASE RETURN

Create Purchase Return.

Support:

* original purchase invoice
* supplier
* product
* batch
* expiry
* quantity
* return price
* reason
* total

Validation:

Return quantity cannot exceed available purchased quantity.

Stock must decrease.

Supplier payable must be adjusted.

---

# 13. SALES MODULE

Create:

## Sales Entry

Header:

* Customer
* Branch
* Sales Date
* Invoice No
* Delivery Address
* Area
* Delivery Charge
* Payment Type
* Remarks

Lines:

* Product
* Batch
* Expiry
* Quantity
* Sales Unit
* Sales Type (BOX / STRIP / PCS)
* MRP_PRICE  (BOX / STRIP / PCS)
* Sales Price (BOX / STRIP / PCS)
* Discount
* Tax
* Total

Calculations:

Gross Amount
Discount
Discount_AMT
Tax
Delivery Charge
Net Amount
Paid
Due

---

# 14. BATCH-WISE SALES

Sales must allocate stock batch-wise.

Implement FEFO:

First Batch
First Out

For a product with multiple available batches:

1. select earliest valid batch
2. consume available quantity
3. continue to next batch if required
4. never sell expired stock
5. never sell unavailable stock

Show allocation preview:

Product | Batch | Expiry | Available | Required | Allocated

The user must be able to understand which batches will be consumed.

---

# 15. SALES VALIDATIONS

Implement:

* Customer validation
* Product validation
* Quantity > 0
* Stock availability
* Expiry validation
* Batch validation
* Sales price validation
* Discount validation
* Payment validation
* Paid amount cannot create invalid negative due
* Cannot finalize empty sale
* Cannot sell inactive product
* Cannot sell expired batch
* Cannot sell more than available quantity

Show clear business messages.

Example:

"Insufficient stock for Napa 500mg. Available: 8 PCS, Requested: 12 PCS."

---

# 16. SALES RETURN

Create:

Sales Return List
Sales Return Entry

Support:

* original invoice
* customer
* product
* batch
* expiry
* quantity
* return reason
* return amount

Validation:

Return quantity cannot exceed eligible sold quantity.

Update inventory correctly.

Update customer balance correctly.

---

# 17. QUICK SALES / POS BILLING

Create a dedicated **Quick Sales / POS Terminal**.

This is one of the most important screens.

Design it like a professional pharmacy POS.

Left:

Product search / barcode search

Center:

Cart

Right:

Payment summary

Features:

* barcode input
* product search
* keyboard navigation
* quantity +/- buttons
* batch selection
* FEFO allocation
* discount
* customer selection
* walk-in customer
* payment type
* cash
* card
* mobile banking
* paid amount
* due
* change
* hold bill
* recall bill
* save
* finalize
* print receipt

Shortcut keys:

F2 Search
F4 Customer
F6 Payment
F8 Hold
F9 Recall
F12 Complete Sale

Show a professional receipt preview.

---

# 18. SALES ACTIVITY / ORDER WORKFLOW

Create Sales Activity screen.

Workflow:

Draft
→ Submitted
→ Confirmed
→ Delivery Man Assigned
→ On The Way
→ Delivered
→ Collection/Paid

Provide Kanban view.

Columns:

Submitted
Confirmed
Assigned
On The Way
Delivered
Paid

Each order card:

* Invoice
* Customer
* Area
* Amount
* Delivery man
* Payment
* Due
* Date

Actions:

* Submit
* Confirm
* Assign Delivery Man
* Start Delivery
* Mark Delivered
* Record Collection
* Mark Paid
* Cancel
* View Invoice

---

# 19. DELIVERY MANAGEMENT

Create:

### Delivery Dashboard

KPIs:

* Pending
* Assigned
* On the way
* Delivered
* Failed
* Returned
* Collected

Delivery screen:

* Invoice
* Customer
* Address
* Area
* Delivery Man
* Amount
* Delivery Charge
* Paid
* Due
* Return
* Sales

The existing application has delivery reporting around these concepts, so preserve them.

---

# 20. COLLECTION MANAGEMENT

Create:

### Collection From Delivery Man

Show:

* delivery man
* invoice
* customer
* invoice amount
* paid amount
* collection amount
* due
* collection date
* voucher

Create collection entry.

Support:

* full collection
* partial collection
* outstanding amount
* collection history

---

# 21. REQUISITION MODULE

Create:

## Out-of-Stock Requisition

Automatically identify:

* out of stock products
* below reorder level
* below safety stock

Group products by:

* supplier
* company
* product company
* branch

Create requisition.

Workflow:

Out of Stock
→ Requisition Draft
→ Submitted
→ Approval Pending
→ Approved
→ Supplier Selection
→ Purchase Invoice
→ Inventory Updated

---

# 22. REQUISITION ENTRY

Header:

* Branch
* Company
* Supplier
* Requisition Date
* Required Date
* Remarks

Lines:

* Product
* Current Stock
* Reorder Level
* Safety Stock (POS_PRODUCT_INFO -> ALERT_QTY)
* Required Quantity
* Suggested Quantity
* Supplier
* Purchase Price
* Estimated Amount

Provide "Generate from Out of Stock".

---

# 23. REQUISITION APPROVAL

Create approval screen.

Tabs:

* Pending
* Approved
* Rejected
* Converted

Approval actions:

Approve
Reject
Return for Correction

Approval modal must show:

* requested quantity
* current stock
* safety stock
* supplier
* estimated purchase value

---

# 24. REQUISITION → PURCHASE

After approval:

"Create Purchase Invoice"

Automatically populate:

* supplier
* product
* requested quantity
* purchase price
* branch
* company

User can edit before finalizing.

After purchase finalization:

Inventory increases.

Requisition status becomes:

CONVERTED

---

# 25. INVOICE MERGE

Implement the existing production concept:

### Invoice Merge Process

User selects multiple sales invoices.

System creates a new consolidated invoice.

The original invoices become inactive/merged.

Store:

* original invoice
* merged invoice
* merge date
* user
* reason
* status

Show relationship:

Invoice A
Invoice B
Invoice C
↓
Merged Invoice M-10001

Original invoices must show:

"Merged into M-10001"

Do not physically delete them.

---

# 26. INVENTORY

Create Inventory module:

### Inventory Dashboard

* Total Stock Value
* Total Units
* Low Stock
* Out of Stock
* Near Expiry
* Expired
* Overstock
* Fast Moving
* Slow Moving

### Product Stock

Columns:

* Product
* Category
* Branch
* Batch
* Expiry
* Purchase Price
* MRP
* Sales Price
* Available Qty
* Stock Value

---

# 27. STOCK LEDGER

Implement Product Stock Ledger.

The existing application calculates:

Previous Stock
Current Purchase
Purchase Return
Current In
Current Sales
Sales Return
Current Out
Stock Balance

Preserve this conceptual model.

Filters:

* Product
* Category
* Supplier
* Branch
* Batch
* Date From
* Date To

Show transaction history.

---

# 28. STOCK ALERT

Create:

* Out of Stock
* Low Stock
* Safety Stock Warning
* Near Expiry
* Expired
* Overstock

Allow configurable thresholds.

---

# 29. AI AGENT

Create an **AI Inventory & Purchase Agent**.

Name:

### Pharma AI Copilot

Purpose:

Analyze inventory and recommend what should be purchased.

The prototype must work without an external AI API.

Use a deterministic mock AI analysis engine initially.

Analyze:

* sales velocity
* last 7 days sales
* last 30 days sales
* last 90 days sales
* current stock
* safety stock
* reorder level
* supplier lead time
* expiry risk
* seasonality
* purchase frequency
* stock coverage days

Generate recommendations:

### Buy Now

Product
Recommended Quantity
Reason
Supplier
Expected Stockout Date
Safety Stock
Confidence

Example:

"Paracetamol 500mg — purchase 420 PCS. Current stock covers approximately 5 days while target safety coverage is 20 days."

---

# 30. AI HOT PRODUCT ANALYSIS

Create dashboard:

### Hot Products

Rank products using:

* sales quantity
* sales frequency
* growth
* margin
* stock coverage

Categories:

🔥 Hot
🟢 Healthy
🟡 Watch
🔴 Critical

Provide:

"Why?"

button.

Clicking it opens AI explanation.

---

# 31. AI PURCHASE RECOMMENDATION

Create:

### Purchase Recommendation

Columns:

* Product
* Current Stock
* Safety Stock
* Avg Daily Sales
* Stock Coverage
* Lead Time
* Recommended Qty
* Supplier
* Expected Cost
* Priority

Actions:

* Add to Requisition
* Ignore
* Snooze
* View History

"Create Requisition" must create a requisition draft from selected AI recommendations.

---

# 32. REPORTING MODULE

Create a comprehensive Reports section.

Minimum reports:

## Sales

1. Sales Summary
2. Daily Sales
3. Monthly Sales
4. Sales by Product
5. Sales by Category
6. Sales by Sub Category
7. Sales by Generic
8. Sales by Company
9. Sales by Supplier
10. Sales by Customer
11. Sales by Branch
12. Sales by Region
13. Sales by Area
14. Sales by Salesman
15. Sales by Delivery Man
16. Sales Return
17. Sales Actual Price Greater Than Sales Price
18. Sales Performance
19. Customer Status
20. Customer Ledger

## Purchase

21. Purchase Summary
22. Purchase by Product
23. Purchase by Supplier
24. Purchase by Company
25. Purchase by Branch
26. Purchase Return
27. Supplier Ledger
28. Supplier Outstanding
29. Purchase Invoice

## Inventory

30. Current Stock
31. Product Stock
32. Stock Ledger
33. Product-wise Stock Ledger
34. Batch Stock
35. Expiry Report
36. Near Expiry
37. Expired Products
38. Low Stock
39. Out of Stock
40. Available Product Stock
41. Stock Valuation
42. Fast Moving Products
43. Slow Moving Products
44. Dead Stock

## Customer

45. Customer Ledger
46. Customer Due
47. Top Customers
48. Low Value Customers
49. Customer Status
50. Area-wise Top Customer

## Delivery

51. Delivery Tracking
52. Delivery Pending
53. Delivery Performance
54. Delivery Man Performance
55. Collection from Delivery Man

## Payment

56. Customer Payment
57. Supplier Payment
58. Collection Report
59. Outstanding Report
60. Payment Type Summary

## Requisition

61. Requisition List
62. Pending Requisition
63. Approved Requisition
64. Supplier-wise Requisition
65. Out-of-stock Requisition
66. Requisition Conversion

## Invoice

67. Invoice List
68. Invoice Merge
69. Merged Invoice History
70. Invoice Print

Every report must support:

* date filter
* branch
* company
* organization
* region
* search
* sorting
* pagination
* column visibility
* export CSV
* print
* PDF preview mock

---

# 33. FINANCIAL REPORTS

Include:

* Customer Ledger
* Supplier Ledger
* Trial Balance
* Profit & Loss
* Balance Sheet
* Chart of Accounts
* Ledger
* Ledger Print Report

These concepts exist in the existing APEX application and should be represented in the prototype.

---

# 34. RBAC

Create complete RBAC module.

Structure:

Module
→ Menu
→ Role
→ User

Screens:

1. Module List
2. Module Entry
3. Menu List
4. Menu Entry
5. Role/Group List
6. Role/Group Entry
7. Role Permission
8. User List
9. User Entry
10. User Role Assignment

Permission types:

* View
* Create
* Edit
* Delete
* Approve
* Print
* Export
* Finalize
* Cancel

Support:

Organization scope
Company scope
Branch scope

---

# 35. DYNAMIC MENU

Menu must be generated from RBAC.

Do not hardcode all sidebar visibility.

Example:

Role:
Branch Manager

Permissions:

Dashboard ✓
Sales ✓
Purchase ✓
Requisition Approve ✓
Reports ✓
Administration ✕

Sidebar automatically changes based on permission.

---

# 36. USER MANAGEMENT

User fields:

* User ID
* Username
* Employee
* Email
* Mobile
* Organization
* Company
* Branch
* Role
* Status

Add:

* active/inactive
* reset password mock
* last login
* session status

---

# 37. NOTIFICATION CENTER

Create notifications for:

* Low stock
* Out of stock
* Near expiry
* Requisition pending approval
* Purchase pending
* Sales pending confirmation
* Delivery pending
* Customer due
* Supplier due
* AI purchase recommendation

---

# 38. GLOBAL SEARCH

Create global search.

Search:

* invoice
* product
* customer
* supplier
* requisition
* batch
* employee

Search results grouped by type.

Keyboard shortcut:

Ctrl + K

---

# 39. PAGE ROUTING

Create routes for every major business page.

Example:

/dashboard

/master/organizations
/master/companies
/master/branches
/master/categories
/master/sub-categories
/master/units
/master/products
/master/generics
/master/leaf
/master/boxes
/master/customers
/master/suppliers
/master/employees

/purchase
/purchase/new
/purchase/returns
/purchase/batches

/sales
/sales/new
/sales/returns
/sales/activity

/pos
/pos/quick-sale
/pos/held-bills

/requisition
/requisition/new
/requisition/pending
/requisition/approval
/requisition/approved
/requisition/convert

/delivery
/delivery/tracking
/delivery/collection

/inventory
/inventory/dashboard
/inventory/stock
/inventory/ledger
/inventory/expiry
/inventory/alerts

/reports/...

/analytics/...

/ai
/ai/hot-products
/ai/purchase-recommendation

/admin/modules
/admin/menus
/admin/roles
/admin/permissions
/admin/users

---

# 40. PAGE REQUIREMENT

Do not create empty placeholder pages.

Every route must contain meaningful UI.

For each page provide:

* title
* breadcrumb
* filters
* data
* table/card
* actions
* dialogs
* validation
* toast
* pagination where applicable
* realistic mock records

---

# 41. TABLE UX

All ERP tables should support:

* search
* column filters
* sorting
* pagination
* page size
* column visibility
* density
* row selection
* export
* print
* sticky header
* responsive horizontal scrolling

Use colored status badges.

---

# 42. FORM UX

Forms must support:

* keyboard navigation
* required indicator
* inline validation
* calculated fields
* dependent dropdowns
* autocomplete
* product search
* supplier search
* customer search
* confirmation before destructive action
* unsaved-change warning

---

# 43. TRANSACTION STATUS

Use consistent status badges.

Purchase:

Draft
Pending
Confirmed
Completed
Cancelled

Sales:

Draft
Submitted
Confirmed
Assigned
On The Way
Delivered
Paid
Cancelled

Requisition:

Draft
Submitted
Pending Approval
Approved
Rejected
Converted
Cancelled

Invoice:

Active
Merged
Cancelled

---

# 44. MOCK DATA

Create enough realistic data to make the prototype feel alive.

Minimum:

* 2 organizations
* 3 companies
* 8 branches
* 5 regions
* 8 areas
* 20 suppliers
* 50 customers
* 100 products
* 20 generics
* 10 categories
* 30 subcategories
* 100+ batches
* 200 purchase invoices
* 500 sales invoices
* 50 requisitions
* 30 delivery orders

Use Bangladeshi context:

Currency:
BDT / ৳

Date:
DD/MM/YYYY

Examples of payment methods:

* Cash
* Card
* bKash
* Nagad
* Bank

---

# 45. BANGLADESH PHARMACY UX

Use:

* BDT
* ৳ symbol
* Bangladeshi address format
* Mobile number format
* realistic pharmacy products
* realistic suppliers/customers
* Bangladesh-oriented locations

But do not claim regulatory compliance unless it is explicitly supported by the source documents.

---

# 46. PRINT DESIGN

Create printable templates for:

* POS receipt
* Sales invoice
* Purchase invoice
* Purchase return
* Sales return
* Requisition
* Delivery invoice
* Payment receipt
* Collection receipt
* Stock ledger
* Customer ledger
* Supplier ledger
* Reports

Provide Print Preview dialogs.

---

# 47. BUSINESS LOGIC ARCHITECTURE

Create reusable services:

transactionService.ts
inventoryService.ts
purchaseService.ts
salesService.ts
requisitionService.ts
deliveryService.ts
paymentService.ts
reportService.ts
aiService.ts
rbacService.ts

Create reusable calculation utilities:

calculateLineTotal()
calculateDiscount()
calculateTax()
calculateInvoiceTotal()
calculateDue()
calculateChange()
calculateStockBalance()
calculateRecommendedPurchaseQty()
calculateStockCoverage()
allocateFEFO()
mergeInvoices()

Do not duplicate calculations across pages.

---

# 48. DATA MODEL

Create TypeScript interfaces based on the database.

Examples:

Organization
Company
Branch
Region
Area
Supplier
Customer
Category
SubCategory
Unit
Generic
Product
Leaf
Box
Batch
Purchase
PurchaseLine
PurchaseReturn
Sales
SalesLine
SalesReturn
Requisition
RequisitionLine
Delivery
Collection
Payment
User
Role
Module
Menu
Permission
Notification

Use IDs corresponding conceptually to the Oracle schema.

---

# 49. PROTOTYPE STATE

Use localStorage so that:

* adding a product persists
* editing customer persists
* creating purchase persists
* creating sales persists
* requisition changes persist
* invoice merge persists
* RBAC changes persist

Provide a:

"Reset Demo Data"

option in Administration.

---

# 50. DEMO USERS

Create demo users:

Admin
Branch Manager
Sales Manager
Purchase Officer
Store Officer
Delivery Manager
Delivery Man
Accounts Officer
Viewer

Demonstrate RBAC by allowing login/user switching.

---

# 51. DEMO SCENARIO

The application must support this complete demo:

1. Product goes out of stock.
2. Dashboard shows out-of-stock warning.
3. AI recommends purchase.
4. User opens AI recommendation.
5. User creates requisition.
6. Manager approves requisition.
7. User converts requisition into purchase invoice.
8. Purchase is completed.
9. Batch stock increases.
10. Customer makes sales order.
11. Sales is confirmed.
12. FEFO chooses batch.
13. Delivery man is assigned.
14. Order becomes On The Way.
15. Order becomes Delivered.
16. Collection is recorded.
17. Invoice becomes Paid.
18. Dashboard updates.
19. Stock ledger updates.
20. Customer ledger updates.

This scenario must work entirely inside the prototype.

---

# 52. DASHBOARD DRILLDOWN

Every major KPI must be clickable.

Example:

Low Stock
→ /inventory/alerts?type=LOW_STOCK

Out of Stock
→ /inventory/alerts?type=OUT_OF_STOCK

Today's Sales
→ /reports/sales/daily

Customer Due
→ /reports/customer-due

Supplier Due
→ /reports/supplier-due

Near Expiry
→ /reports/expiry

---

# 53. ERROR HANDLING

Use professional messages.

Never show:

"Something went wrong."

Instead show useful messages.

Examples:

"Cannot complete sale: 12 PCS requested but only 8 PCS are available."

"Cannot merge invoice: one selected invoice has already been merged."

"Cannot approve requisition: supplier is inactive."

---

# 54. AUDIT TRAIL

For important transactions show:

* Inserted By
* Inserted Date
* Updated By
* Updated Date
* Status History

Especially for:

* purchase
* sales
* requisition
* invoice merge
* delivery
* collection
* approval

---

# 55. RESPONSIVE DESIGN

Desktop:

Full ERP layout.

Tablet:

Collapsible sidebar.

Mobile:

Bottom actions for POS.

POS should remain usable on a tablet.

---

# 56. ACCESSIBILITY

Implement:

* keyboard navigation
* visible focus
* aria labels
* semantic buttons
* accessible dialogs
* sufficient contrast

---

# 57. PERFORMANCE

Avoid:

* unnecessary re-renders
* huge static components
* duplicated data
* deeply nested state

Use:

* memoization where useful
* pagination
* virtualized lists if required
* lazy-loaded routes
* reusable components

---

# 58. DO NOT DO THESE THINGS

Do NOT:

* create only a dashboard
* create empty pages
* create fake buttons that do nothing
* use random generic pharmacy logic
* remove existing production concepts
* ignore database structure
* ignore APEX workflows
* use APEX UI styling
* hardcode all menus
* hardcode calculations in JSX
* require backend to demonstrate the prototype
* create only screenshots
* create a static HTML mockup

This must be a functioning React prototype.

---

# 59. REVERSE-ENGINEERING DELIVERABLE

Before implementation, inspect the source files and create:

`docs/source-analysis.md`

Include:

### Database Analysis

* tables
* key columns
* relationships
* transaction model
* ACL model
* inventory model

### APEX Analysis

* page number
* page name
* purpose
* related process
* related business logic
* report
* workflow

### Business Rules

List all discovered rules.

### Calculations

List all discovered calculations.

### Validations

List all discovered validations.

### Statuses

List all discovered statuses.

### Reports

Map existing APEX reports to React routes.

### Gap Analysis

Compare:

Existing APEX
vs
Requested new React requirements

Mark:

* Existing
* Enhanced
* New

Do not hide missing information.

---

# 60. PAGE INVENTORY

Use the existing APEX application as a source for the page inventory.

Important existing areas include:

* Organization
* Company
* Branch
* Category
* Sub Category
* Unit
* Product
* Customer
* Supplier
* Purchase
* Sales
* Stock
* Quotation
* Challan
* Customer Payment
* Supplier Payment
* Department
* Designation
* Employee
* Leaf
* Area
* Box
* Generic
* Medicine
* Sales Return
* Delivery Tracking
* Stock Alert
* Notification
* Customer Ledger
* Supplier Ledger
* Collection
* Accounting
* Dashboard
* Requisition
* Invoice Merge
* Sales Performance

Do not blindly reproduce every APEX technical page. Convert them into logical React modules.

---

# 61. REPORT DESIGN

Every report should have a reusable report shell:

Header:
Report Name

Filter Bar:
Date | Branch | Company | Region | Search

Toolbar:
Refresh | Export | Print | Columns

Content:
Table/chart

Footer:
Record count / totals

---

# 62. CHART DESIGN

Use:

* Area charts
* Line charts
* Bar charts
* Pie/Donut
* Stacked bar
* KPI cards
* Heatmap where useful

Avoid excessive charts.

Charts must answer business questions.

---

# 63. AI UI

Create a right-side AI assistant panel.

Example questions:

"What products should I purchase today?"

"Which products are likely to stock out?"

"What are my hottest products?"

"Which branch has the highest sales?"

"Which products are near expiry?"

"Which suppliers should I purchase from?"

Use mock responses based on application data.

---

# 64. AI EXPLANATION

Do not show only:

"Recommended: 500."

Show:

"Recommended 500 PCS because:

* Average daily sales: 25 PCS
* Current stock: 80 PCS
* Safety stock: 200 PCS
* Supplier lead time: 10 days
* Expected stockout: 3 days
* 30-day sales growth: 18%

Priority: HIGH"

---

# 65. FINAL QUALITY REQUIREMENT

After implementation:

Run:

npm install
npm run dev
npm run build

Fix all TypeScript/build errors.

Do not leave broken imports.

Do not leave console errors.

Check all routes.

Check all forms.

Check all dialogs.

Check all major workflows.

---

# 66. FINAL DOCUMENTATION

Create:

README.md
docs/source-analysis.md
docs/business-rules.md
docs/page-map.md
docs/data-model.md
docs/workflows.md
docs/ai-recommendation.md

README must explain:

* installation
* running
* project structure
* demo users
* demo workflow
* mock data
* how to replace mock API with Spring Boot API

---

# 67. IMPLEMENTATION ORDER

Work in this order:

PHASE 1
Source analysis

PHASE 2
Application architecture

PHASE 3
Design system

PHASE 4
Layout/navigation/RBAC

PHASE 5
Master data

PHASE 6
Inventory

PHASE 7
Purchase

PHASE 8
Sales

PHASE 9
Quick POS

PHASE 10
Delivery/collection

PHASE 11
Requisition

PHASE 12
Invoice merge

PHASE 13
Reports

PHASE 14
Dashboards

PHASE 15
AI agent

PHASE 16
Testing/polishing

---

# 68. IMPORTANT: ITERATIVE IMPLEMENTATION

Do not generate an enormous monolithic file.

Build modularly.

After each phase:

1. run TypeScript
2. run build
3. fix errors
4. continue

Do not replace working modules unnecessarily.

---

# 69. SUCCESS CRITERIA

The final application should feel like:

"Modern enterprise pharmacy ERP + pharmacy POS + inventory management + delivery management + analytics + AI purchase assistant."

It should NOT feel like:

"Demo CRUD application."

The user should be able to navigate through the entire business lifecycle:

MASTER DATA
→ PURCHASE
→ INVENTORY
→ REQUISITION
→ SALES
→ POS
→ DELIVERY
→ COLLECTION
→ PAYMENT
→ REPORTING
→ ANALYTICS
→ AI PURCHASE RECOMMENDATION

while retaining the important concepts from the existing Friends Pharma Oracle APEX application.

---

# 70. FIRST TASK

Before writing React code:

1. Inspect `SRS.html`.
2. Inspect `Pharma_Database.sql`.
3. Inspect `f110.sql`.
4. Reverse-engineer the existing application.
5. Create `docs/source-analysis.md`.
6. Create the complete React page/module map.
7. Create the TypeScript data model.
8. Then begin implementation.

Do not ask me to explain the business logic that is already available in the supplied files.

If a rule is not available in the sources, mark it as:

`ASSUMPTION — PROTOTYPE ONLY`

Do not silently invent production business rules.

Start by analyzing the three source files and then build the application.
