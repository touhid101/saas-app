# Phase 10 — Reports and KPIs

> Friends Pharma ERP · SRS v2.0 · 9 September 2026

## Reports (18, one grid engine)

Each report returns its own column definitions, rows, totals, applied window, row count and the filters it honours — so one table component renders all of them and a filter that would do nothing is never offered.

| Report | Business question | Source | Filters |
|---|---|---|---|
| Sales Register | Every sale in a period | Approved SL documents | date, branch, search |
| Sales by Product / Customer / Company / Category | Where revenue comes from | Sale lines joined to masters | date, branch, the dimension |
| Customer Due | Who owes money | Receivable position | branch, search |
| Sales Return Register | What came back | Approved SR documents | date, branch |
| Purchase Register | Every purchase in a period | Approved PU documents | date, branch, search |
| Purchase by Product / Supplier | Where spend goes | Purchase lines | date, branch, the dimension |
| Purchase Return Register | What went back to suppliers | Approved PR documents | date, branch |
| Supplier Outstanding | What is owed, with ageing | V_SUPPLIER_ACCOUNT_BRANCH | branch, search — never date |
| Supplier Benefit | Bonus, gift and cashback per supplier | V_PURCHASE_PAYABLE + lines | date, branch, search |
| Current Stock / Batch Stock / Stock Valuation | What is held and what it is worth | Stock ledger | branch, category, expiry |
| Delivery / Collection Register | Fulfilment and cash in | Delivery and collection records | date, branch |

## Dashboard KPIs

| KPI | Business meaning | Calculation |
|---|---|---|
| Revenue today / this month | Takings | Sum of approved sale value in the window |
| Margin this month | Revenue less cost of goods sold | Revenue − Σ(movement qty × movement cost) |
| Purchases this month | Spend | Σ AMOUNT_TOBE_PAID on approved purchases |
| Supplier benefit this month | What suppliers gave back | Cashback + gift value, against gross purchases |
| Owed to us / We owe | Working capital position | Receivable and payable, with overdue split |
| Stock at cost | Inventory value | Σ(batch qty × batch cost); expiring within 90 days shown beside it |

## Rules that keep figures honest

- Totals cover the whole result set, never the page on screen.
- A truncated result says so, and states how many rows matched.
- A defaulted date window is declared, with the way to change it.
- Outstanding balances are never date-filtered — a position as at today is not an activity total.
- Approved documents only; drafts never reach a financial report.
