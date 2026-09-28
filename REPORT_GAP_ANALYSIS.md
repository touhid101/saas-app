# Friends Pharma — Report Inventory & Gap Analysis

*Audited 21 Sep 2026 against the repository and the dev database (every catalogued report was run
through `GET /api/reports/{id}` with a 1 Jul – 30 Sep 2026 window; row counts below are from that run).*

## 0. How reporting is built today

| Layer | Where | Notes |
| --- | --- | --- |
| One grid engine | `ReportGridController` → `ReportGridServiceImpl.run(id, Filters)` | `GET /api/reports/catalogue`, `GET /api/reports/{id}`. Each report declares its own columns and the filters it honours; the screen renders any of them. |
| Three engines behind it | `ReportGridServiceImpl` (base), `HrReportServiceImpl` (`hr-*`), `AccReportServiceImpl` (`acc-*`) | dispatch by id prefix |
| Inventory & delivery reports | `/api/inventory/*`, `/api/sales-activity/delivery-performance`, `/api/sales-returns` | adapted to the same grid shape in `reportService.js` (`source: inventory / delivery / salesReturn`) |
| Three doors, one viewer | `Reports.jsx` (base, permission `reports`/MOD14), `HrReports.jsx` (`hrreports`/MOD39), `AccReports.jsx` (`accreports`/MOD51) | registries `REPORT_GROUPS`, `HR_REPORT_GROUPS`, `ACC_REPORT_GROUPS` in `reportService.js` |
| Export / print | client CSV (`downloadCSV`), browser print | no server-side Excel/PDF; reports are capped at 5,000 rows server-side |
| RBAC | HR and Accounts engines check `VIEW` on their module server-side | **the base engine only refused delivery staff — no `MOD14 VIEW` check** (fixed in this work, see §6) |

## 1. Existing report inventory

### 1a. Base / operational (`Reports` page, MOD14)

| Report | Id | Source | Filters | Purpose | Duplicate risk |
| --- | --- | --- | --- | --- | --- |
| Daily Profit & Loss | `daily-pnl` | sales lines + batch cost | date, branch | revenue, COGS, profit per day | vs `acc-profit-loss` — different basis (invoices vs posted ledger); both kept, documented |
| Monthly Profit & Loss | `monthly-pnl` | same | date, branch | same per month | as above |
| Product-wise Profit | `product-pnl` | `/api/inventory/profit` | date, branch, company, search | profit per product at batch cost | — |
| Sales Register | `sales-register` | invoices | date, branch, customer, payment status, search | every sale with paid/due | — |
| Sales by Product / Customer / Company / Category | `sales-by-*` | invoices | date, branch, … | grouped sales | — |
| Customer Due | `customer-due` | invoices | date, branch, search | who owes what now | **not** an ageing report (see §3) |
| Sales Return Register | `sales-return` | `/api/sales-returns` | date, branch | returns | — |
| Purchase Register / Return Register | `purchase-register`, `purchase-return` | purchase invoices | date, branch, supplier, search | — | — |
| Purchase by Product / Supplier | `purchase-by-*` | purchase lines | date, branch, … | — | — |
| Supplier Outstanding | `supplier-due` | purchase invoices + payments | branch, search | what is owed per supplier, incl. advances and on-account | — |
| Supplier Benefit | `supplier-benefit` | purchase lines | date, branch | bonus / gift / cashback per supplier | — |
| Supplier Ageing | `supplier-ageing` | purchase invoices | branch, search | **backend only — not in any menu** | duplicate of `acc-ap-ageing` (§3) |
| Supplier Payment Register | `supplier-payments` | `pos_supplier_payment` | date, branch, supplier, search | **backend only — not in any menu** | — |
| Supplier Advance Register | `supplier-advance` | `pos_supplier_payment` | date, branch, supplier, search | **backend only — not in any menu** | — |
| Current Stock, Batch Stock, Stock Valuation, Low/Alert, Out of Stock, Near Expiry 30/90, Expired, Stock Movement Ledger | `/api/inventory/*` | stock batches & movements | branch, company, category, batch, search | — | — |
| Collection Summary | `collection-summary` | invoices | date, branch | billed / collected / outstanding / rate per day | — |
| Delivery Performance | `delivery-performance` | sales activity | date, branch | per delivery man | — |

### 1b. HR (`HR Reports` page, MOD39) — 25 reports, all verified running

People: Employee Register, Headcount, Employee Separation, Increment Register · Attendance & Leave: Attendance Register, Monthly Attendance Summary, Late Arrivals, Absences, Overtime, Overtime Claims, Leave Register, Leave Balance, Leave Utilisation, Leave Awaiting Approval · Payroll: Salary Register, Payroll Register, Payroll Summary, Payroll Cost by Department, Payroll Deductions, Payroll Exceptions · Money & Benefits: Advance Register, Loan Register, Expense & Conveyance, Provident Fund, Gratuity Awards.

### 1c. Accounting (`Accounts Reports` page, MOD51) — 30 reports, all verified running

Financial statements: Trial Balance, Profit & Loss, Balance Sheet, Cash Flow, Branch-wise P&L, Revenue Analysis, Expense Analysis · Ledgers & journals: General Ledger, Journal Register, Daily Transaction Summary, Accounting Event Register, Tax / VAT Report · Receivables & payables: Receivables Ageing, Payables Ageing, Customer Statement, Supplier Statement · Cash & bank: Cash Book, Bank Book, Petty Cash Book, Daily Cash Report, Branch Cash Book, Cash Closing, Cash Short/Over, Cash Reconciliation · Investors & payroll: Investor Statement, Investor Capital, Investor Profit, Profit Distribution, Payroll Summary (ledger), Salary Expense.

Interactive equivalents that are *not* reports but answer the same questions live: Accounts › Ledger (account ledger with running balance), Parties (statements), Daily Cash, Reconciliation; Inventory page; Sales Activity; Supplier Payment (payments and advances tabs with CSV).

**Totals: 73 grid reports (18 base · 25 HR · 30 accounting) + 10 inventory/delivery/return reports = 83 reports reachable, 3 more backend-only.**

## 2. Requested categories checked against the inventory

| Requested | Exists as | Verdict |
| --- | --- | --- |
| Customer Aging | `acc-ar-ageing` (0–30 / 31–60 / 61–90 / 90+, per customer, branch) | **exists** |
| Supplier Aging | `acc-ap-ageing`; also `supplier-ageing` (invoice-based, backend only) | **exists**, one duplicate to retire from the menu |
| Cash Book / Bank Book / Petty Cash | `acc-cash-book`, `acc-bank-book`, `acc-petty-cash-book` (opening, in, out, running balance, source) | **exists** |
| Cash Position / Daily Cash Closing | `acc-daily-cash`, `acc-cash-closing`, `acc-cash-reconciliation`, Accounts › Cash | **exists** |
| General Ledger | `acc-general-ledger` (all accounts, one account by search) | exists — **no opening / running balance in the report** (page has it) → enhance |
| Journal Register | `acc-journal-register` (type, status, Dr, Cr, source, posted by) | **exists** |
| Chart of Accounts, Opening/Closing balance | Accounts › Chart; Trial Balance (opening, movement, closing) | **exists** |
| Expense Analysis | `acc-expense-analysis` (by account, share) | exists — **no previous period / variance** → enhance |
| Customer Profitability | none (Sales by Customer has billed/paid/due only; profit exists per product) | **CREATE** |
| Product Profitability | `product-pnl` | exists — not duplicated |
| Branch Profitability | `acc-branch-pnl` | **exists** |
| Receivable Collection Performance (roll-forward) | `collection-summary` is per-day billed/collected; no opening→closing roll-forward | **CREATE** as control-account movement |
| Supplier Payment Performance (roll-forward) | none | **CREATE** (same report, AP) |
| Inventory Financial Movement | `movement-summary` is pieces; Stock Valuation is a snapshot | **CREATE** (same report, Inventory) |
| Payroll Cost Analysis | `hr-payroll-cost`, `acc-salary-expense`, `acc-payroll-summary` | **exists** — not created |
| Tax / VAT | `acc-tax` exists; returns 0 rows because no VAT postings/rates are configured | not extended — no supporting data (BUSINESS / TAX POLICY CONFIRMATION REQUIRED) |
| Investor reports | 4 exist | **exists** |
| Sales Discount / Payment Mode / Salesperson | Sales Performance page (live); `sales-register` shows payment type per invoice | payment-mode mix: low priority, not created |
| Dead / slow-moving stock | none | **CREATE** (operational control: expiry & capital tied up) |
| Stock Adjustment Register | Stock Movement Ledger filtered by type `ADJUST` | **exists** (filter) |
| Expiry Loss Analysis | Near Expiry / Expired carry *Value at Risk* | **exists** |
| Purchase Price Variance | none | deferred — medium value, needs a price-history baseline decision |
| Customer Credit Utilisation | credit limits set on 2 of 24 customers | not created — data too thin; noted for later |
| Delivery pending / time | Delivery page + `delivery-performance` | exists |
| HR headcount by branch/designation, new joiners | Headcount (branch filter, by department & designation); Employee Register sorts by *Joined* | covered — no change (see §4.4) |
| Audit / approval history | `acc-events`, Journal Register (status, reversals), Cash Short/Over with reasons, HR registers with decided-by | exists |

## 3. Duplicate candidates — NOT created / retired from menus

| Candidate | Existing equivalent | Decision |
| --- | --- | --- |
| Customer Aging (new) | `acc-ar-ageing` | not created |
| Supplier Aging (new) | `acc-ap-ageing` | not created; `supplier-ageing` (invoice-based) stays API-only and out of the menu — two ageings would be two numbers to reconcile |
| Customer Ledger / Supplier Ledger | `acc-customer-statement`, `acc-supplier-statement` (+ Parties page) | not created |
| Accounts Receivable / Payable balance reports | `acc-ar-ageing` "outstanding" column, `customer-due`, `supplier-due` | not created |
| Collection Register | Cash Book by source, `collection-summary`, Sales Activity collections | not created |
| Bank Reconciliation | no bank statement import exists — nothing to reconcile against | not created |
| Cash Flow Statement | `acc-cash-flow` | exists |
| Payroll cost (accounting view) | `acc-salary-expense`, `acc-payroll-summary` | not created |
| Headcount by branch / designation | `hr-headcount` with branch filter | not created |
| Leave Approval Pending | `hr-leave-pending` | exists |
| Product Profitability | `product-pnl` | not created |

`daily-pnl` / `monthly-pnl` (invoice basis, batch cost) and `acc-profit-loss` (posted ledger) look alike but answer different questions — "what did the shop earn on what it sold" versus "what does the ledger say for the period, including expenses, payroll and adjustments". Both stay; their descriptions say which basis they use.

## 4. Missing reports — to implement

### 4.1 Control-account movement (Receivable · Payable · Inventory) — Priority 1
```
Report:        Receivable Movement / Payable Movement / Inventory Financial Movement
Ids:           acc-ar-movement, acc-ap-movement, acc-inventory-movement
Module:        Accounting › Financial Statements
Purpose:       Opening + movement by source = closing for a control account, so a manager can
               see why receivables/payables/stock value changed and auditors can tie the
               ledger to the sub-ledger.
Why not sufficient: ageing and statements show balances at a date; nothing shows the period
               roll-forward (new credit sales, collections, returns, adjustments) in one place.
Data:          acc_v_ledger on the AR / AP / INVENTORY role account, grouped by source module
               and type; opening = balance the day before `from`; closing = balance at `to`.
Filters:       date range, branch
KPIs:          opening, total in, total out, closing (first rows of the grid + footer)
Columns:       line, source, type, journals, debit, credit, net
Drill-down:    each source row names the module/type; General Ledger / Journal Register carry the lines
Export:        CSV / print (existing viewer)
Permission:    MOD51 VIEW (existing accounts-reports right)
Rule:          POSTED journals only (acc_v_ledger); branch-scoped by AccScope.
```

### 4.2 Customer Profitability — Priority 2
```
Report:        Customer Profitability
Id:            customer-profit  (Base › Finance)
Purpose:       Which customers make money: revenue after invoice discount, discount given,
               COGS at the batch cost actually consumed, gross profit and margin %.
Why not sufficient: Sales by Customer has billed/paid/due; Product-wise Profit has cost but
               per product. Nothing shows margin per customer.
Data:          the same pairing as profitByProduct (sale line ↔ stock movement cost), grouped
               by the invoice's customer; returns netted.
Filters:       date range, branch, search (customer)
Columns:       customer, type, invoices, pcs, gross sales, discount, net revenue, COGS, profit, margin %
Permission:    MOD14 VIEW
```

### 4.3 Slow-moving / Dead Stock — Priority 3
```
Report:        Slow-moving Stock
Id:            slow-moving  (Base › Inventory, source inventory)
Endpoint:      GET /api/inventory/slow-moving?days=90
Purpose:       Batches with stock on hand and no sale in N days: capital tied up and the
               next expiry write-off. Prevents stock loss.
Why not sufficient: Low/Out of Stock look at shortage; Near Expiry looks at dates; neither
               shows stock that simply is not selling.
Data:          pos_stock_batch (qty > 0) left-joined to the last SALE movement per product/branch.
Filters:       branch, company, category, search, idle days (30/60/90/180)
Columns:       product, batch, expiry, pieces, cost value, last sold, days idle
Permission:    the inventory permission the other inventory reports use
```

### 4.4 Enhancements instead of new reports — Priority 5
* **General Ledger** (`acc-general-ledger`): when the search names exactly one account, show an
  opening row, a running balance and a closing footer (the page already does; the report
  should print the same).
* **Expense Analysis** (`acc-expense-analysis`): previous period of the same length, variance
  and variance %, so a manager sees trend without a second report.
* ~~Employee Register: honour the date range on hire date~~ — not done: the grid engine treats a
  date-capable report as windowed by default (last 90 days), which would silently hide most staff;
  the *Joined* column sorts, which answers "who joined recently" without a rule change.
* **Purchase group**: list the already-built `supplier-payments` and `supplier-advance`
  registers (backend-only today).
* **Base report RBAC**: server-side `MOD14 VIEW` check (the page hid the menu; the API did not refuse).

## 5. Not implemented, and why

| Report | Reason |
| --- | --- |
| Customer Aging, Supplier Aging | exist (`acc-ar-ageing`, `acc-ap-ageing`) |
| Customer / Supplier Ledger | exist as statements |
| Bank Reconciliation | no bank statement data in the system |
| VAT / tax registers beyond `acc-tax` | no tax rules or postings configured — policy confirmation required |
| Customer Credit Utilisation | limits set on 2 of 24 customers; revisit when limits are maintained |
| Purchase Price Variance | needs a baseline rule (last price vs average vs list) to be decided first |
| Payment-mode analysis, salesperson analysis | Sales Performance page and Cash Book by source cover the need |
| Headcount by branch / designation, attendance & leave trends | covered by existing HR reports with the branch filter and date ranges |
| Requisition reports | the Requisition screens list and filter requisitions; no reporting gap surfaced |

## 6. Observations outside the reporting scope (not changed)
* `Collection.jsx` still reads `mockData.js`; collections are recorded through Sales Activity. Worth retiring.
* The old `/api/v1/reports` surface returns 500 on all 28 endpoints (documented in `ReportGridController`); nothing in the UI uses it.

## 7. Implemented (21 Sep 2026)

### 7.1 New reports

| Report | Module | Route / card | API | Data | Filters | Export | Permission |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Receivable Movement | Accounting › Financial Statements | Accounts Reports → card | `GET /api/reports/acc-ar-movement` | `acc_v_ledger` AR account, grouped by posting source; sub-ledger from open sales invoices | date, branch | CSV, print | MOD51 View |
| Payable Movement | same | same | `acc-ap-movement` | AP account; sub-ledger from open purchase invoices less unapplied payments | date, branch | CSV, print | MOD51 View |
| Inventory Financial Movement | same | same | `acc-inventory-movement` | INVENTORY account by source (purchases, COGS, returns, adjustments) | date, branch | CSV, print | MOD51 View |
| Customer Profitability | Base › Finance | Reports → card | `GET /api/reports/customer-profit` | sale lines ↔ batch-cost movements, grouped by the invoice's customer, returns netted | date, branch, search | CSV, print | MOD14 View |
| Slow-moving Stock | Base › Inventory | Reports → card | `GET /api/inventory/alerts/slow-moving?days=90` | lots with stock whose product has had no SALE in the branch for N days (never-sold lots count from receipt) | branch, company, search | CSV, print | inventory right, as the other inventory reports |

### 7.2 Enhanced instead of duplicated

| Report | Change |
| --- | --- |
| General Ledger (`acc-general-ledger`) | a search matching exactly one account → opening row, running balance, closing identity in the note |
| Expense Analysis / Revenue Analysis | previous period (same length, immediately before the window), variance, variance % |
| Supplier Statement | AP + Supplier Advances in one net statement (done earlier the same day) |
| Purchase group (menu) | Supplier Payment Register and Supplier Advance Register — built already, previously unreachable |
| Base report engine | server-side `MOD14 View` check → `403 REPORT_NOT_PERMITTED`; HR and Accounts engines already checked theirs |

### 7.3 Not created

See §3 (duplicates) and §5 (no supporting data / deferred).

### 7.4 Test matrix (dev database, 1 Jul – 21 Sep 2026 unless noted)

| Check | Result |
| --- | --- |
| Every catalogued report returns 200 with default filters (73 existing + 4 new = 77) | pass |
| Receivable Movement: opening + Σ net movement = closing; closing = Trial Balance 1200 closing | pass (0 + 3,191.23 = 3,191.23) |
| Payable Movement: same identity; closing = Trial Balance 2100; sub-ledger difference | pass; difference 0.00 — reconciled |
| Inventory Financial Movement: identity; closing = Trial Balance 1300 | pass — and it exposes a −79,258.52 credit balance in dev (seeded sales posted COGS with no matching purchase postings), which is exactly what the report exists to surface |
| Receivable tie-out | difference 811.72 flagged "investigate": receivables also carry tenant-subscription postings and test invoices in dev |
| General Ledger 1110: opening + Dr − Cr = last running balance | pass (75,948.90 + 75,352.97 − 11,507.09 = 139,794.78) |
| Expense Analysis: variance = amount − previous on every row | pass |
| Customer Profitability: profit = revenue − COGS per row; Σ COGS equals Daily P&L Σ COGS | pass (63,401.58 both); revenue differs from Daily P&L by the invoice discount, by design |
| Slow-moving: every row has stock and is idle ≥ N days; 30-day set ⊇ 90-day set | pass |
| RBAC: base report without MOD14 View → 403 `REPORT_NOT_PERMITTED`; HR / Accounts unaffected | pass — `ReportGridServiceImplRightsTest` (4 tests) |
| UI: cards open, columns render, date and search filters honoured, CSV export downloads (`customer-profit.csv`), Print present, no horizontal overflow at 390 px, 0 console errors | pass (Playwright / Chrome) |
| `mvn compile`, `vite build` | pass |
| Performance at dev volume (333k journals, 1.2M ledger lines) | movement reports 60–390 ms; GL one account 100 ms; GL all accounts 585 ms (1,597 rows, cap 5,000) |
