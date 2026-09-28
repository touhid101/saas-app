# Accounts — Phase 1: current financial transaction analysis

> Friends Pharma ERP v2.0 · 17 September 2026 · read from the running code and the live schema, not from the SRS summaries.

## 1. What exists today

Searched the tree and the schema (`pharma`, 95 tables) for `journal`, `ledger`, `chart_of_account`, `coa`, `voucher`, `debit`, `credit`, `fiscal`, `period`, `investor`, `petty`, `bank_account`, `cost_center`, `tax_rate`.

| Looked for | Found |
|---|---|
| Chart of accounts, journal, general ledger, fiscal year / period, cost centre, tax configuration, accounting event, investor, petty cash, bank account | **Nothing.** No table, entity, service or screen. Accounts is a creation. |
| "Ledger" | The **stock** movement ledger (`pos_stock_movement`), the **supplier** ledger (`pos_supplier_payment` + views), the **PF** ledger (`hr_pf_ledger`). All operational registers, none double-entry. |
| "Voucher" | `pos_supplier_payment.voucher_no`, `hr_salary_payment.voucher_no` — reference strings issued by `DocumentNumberService`. |
| Idempotency precedent | `sub_payment.idempotency_key` — the pattern the accounting event table follows. |
| Numbering | `DocumentNumberService.nextInvoiceNo(prefix)` → `<PREFIX><yyMM><00000>` under a row lock in `REQUIRES_NEW`. Reused for `JV` (journal), `CV` (cash voucher), `IV` (investor transaction), `PD` (profit distribution). |
| Authorisation | `PermissionGuard.Right {VIEW, CREATE, EDIT, DELETE, APPROVE, EXPORT, PRINT}` on a module code; `HrScope.require(module, right, verb)` is the HR-module wrapper. Accounts gets the same shape (`AccScope`). |
| Tenancy | `all_organization` (the pharmacy business) → `all_branch` (the tenant that subscribes; stock, invoices and reports are per branch). `BranchScopeService` resolves the session branch; `isPlatformOperator()` = ROLE01 in the platform organisation. |
| Audit convention | `BaseEntity`: `inserted_by/date`, `updated_by/date`, `status` 'Y'. Workflow tables add `*_by / *_at` per step (`hr_payroll`: calculated / reviewed / approved / posted / cancelled). |
| Menu registration | `acl_module_info` row + `pos_permissions` per role + `acl_tenant_module` plan ceiling + `acl_perm_template_line` — the V87 pattern, all `WHERE NOT EXISTS`. |
| Report engine | `ReportGridServiceImpl.CATALOGUE` + `dispatch`, with `HrReportServiceImpl` plugged in by id prefix. Frontend `Reports.jsx` reused by `HrReports.jsx` with its own groups and permission. |
| Frontend kit | `HrKit.jsx` (HrPage, HrTabBar, Kpis, HrTable, HrDrawer, HrStepper, HrTimeline, Status, usePagedList…) over `UI.jsx` (Modal, Toast) with tokens in `styles.css`. Fonts actually loaded: Fraunces, Inter, IBM Plex Mono. |
| Tests | Unit tests of calculators and scope logic (322, green) + script-driven verification against the running app. No `@SpringBootTest`. |
| Flyway | Latest `V89`. Baseline 63 — nothing below V64 ever ran on production; every seed is `WHERE NOT EXISTS`, every sequence `setval(GREATEST(...))`. Hibernate `ddl-auto: update` is on, so entity column types must match the migration exactly. |

**Marketplace, B2B, Payment gateway, Settlement:** documented in `docs/platform-srs` as roadmap; no table, service or screen exists. Nothing to integrate; the event contract is designed so they plug in later with their own `source_module`.

## 2. Volumes (development database — seeded trading history)

| Table | Rows | Value |
|---|---|---|
| `pos_transaction_mst` SL (sales, status Y) | 140,401 | ৳14,501,843.95 |
| SR (sales returns) | 14,005 | ৳279,866.62 |
| PU approved (A) | 40,041 | ৳3,388,967.57 |
| PU draft / pending | 12 / 2 | not liabilities |
| PR (purchase returns) | 6,003 | ৳98,976.88 |
| `pos_supplier_payment` | 28 (5 cancelled) | ৳19,141 |
| `pos_delivery_collection` | 0 | — |
| `hr_payroll` | 1 (CALCULATED — never posted) | — |
| `sub_invoice` / `sub_payment` | 1 / 1 | — |
| Organisations / branches | 7 orgs, 9 branches | — |

Any backfill must therefore be batched (≈200k documents), reproducible, and reconciled — not a migration script.

## 3. Transaction mapping

Every write path that changes money or stock value, as it is actually implemented. "Trigger" is the method where the transaction becomes real; the accounting event is raised inside that same transaction.

| # | Module | Transaction | Trigger (code) | Financial impact | Debit | Credit | Branch | Party | Event type |
|---|---|---|---|---|---|---|---|---|---|
| 1 | Sales | Invoice created (counter, wholesale, POS — POS charges through `POST /api/sales`) | `SalesServiceImpl.createSale` | Revenue earned; stock leaves at batch cost; customer owes or pays | AR (payable) · COGS (Σ pcs × movement cost) | Sales Revenue (subtotal − invoice discount) · Delivery Income (delivery charge) · Inventory (cost) | `pid_branch` | Customer `pid_sup_cust` | `SALE_POSTED` |
| 2 | Sales | Cash given at the counter (`given_amount` > 0 at creation) | same transaction | Cash/bank/mobile received against the invoice | Cash Register / Bank / Mobile Wallet (by `payment_type`) | AR | branch | customer | `SALE_RECEIPT` |
| 3 | Sales | Invoice edited while Submitted (lines rewritten) | `SalesServiceImpl.updateSale` | Original figures no longer true | reversal of 1–2, then fresh 1–2 with a new revision key | | | | `SALE_POSTED` rev n |
| 4 | Sales / Activity / Delivery | Collect against a delivered invoice | `SalesServiceImpl.collect` | Money received; if a delivery man carries it, it is cash in transit until handed in | Cash in Transit (delivery man set) or Cash Register / Bank (none) | AR | branch | customer | `SALE_COLLECTION` |
| 5 | Collection | Delivery man hands cash to the branch (`pos_delivery_collection`) | `CollectionServiceImpl.record` | Rider cash reaches the till | Cash Register | Cash in Transit | branch | delivery man | `DELIVERY_CASH_IN` |
| 6 | Collection | Collection cancelled | `CollectionServiceImpl.cancel` | | reversal of 5 | | | | `DELIVERY_CASH_IN_CANCELLED` |
| 7 | Sales Return | Return raised (goods back into the sold batch, refund net of invoice discount) | `SalesReturnServiceImpl.createReturn` | Revenue given back; stock returns at the cost it left at | Sales Returns (contra revenue) · Inventory (cost) | Cash / Bank (refund by) or AR (credit) · COGS | branch | customer | `SALES_RETURN_POSTED` |
| 8 | Purchase | Invoice approved (draft → A, or Save & Approve) | `PurchaseServiceImpl.approve` / `receive` | Stock received at landed cost; supplier is owed; any cash handed over is mirrored into the supplier ledger (row 10) | Inventory (amount to be paid) · Supplier Advance (advance carved out, if any) | Accounts Payable (amount to be paid + advance) | branch | supplier | `PURCHASE_APPROVED` |
| 9 | Purchase Return | Debit note raised | `PurchaseReturnServiceImpl.createReturn` | Stock leaves the named batch; supplier credits us | AP (`ADJUSTMENT`) · Cash / Bank (`CASH`/`BANK`) · Supplier Advance (`REPLACEMENT`) | Inventory | branch | supplier | `PURCHASE_RETURN_POSTED` |
| 10 | Supplier Payment | Payment recorded (manual, or mirrored from the invoice's Given Amount) | `SupplierPaymentServiceImpl.record` / `mirrorInvoicePayment` | Cash leaves; AP falls by the settling part; the advance part is a receivable from the supplier | AP (amount − advance) · Supplier Advance (advance) | Cash Register / Bank / Mobile (mode) — `ADJUSTMENT` mode credits Supplier Advance instead | branch | supplier | `SUPPLIER_PAYMENT` |
| 11 | Supplier Payment | Advance recovered (money back, or credit against a later bill) | `recoverAdvance` | | Cash / Bank (money) or AP (credit against bill) | Supplier Advance | branch | supplier | `SUPPLIER_ADVANCE_RECOVERY` |
| 12 | Supplier Payment | Payment / recovery cancelled with reason | `cancel` | | reversal of 10 / 11 | | | | `SUPPLIER_PAYMENT_CANCELLED` |
| 13 | HR Payroll | Payroll posted (status → POSTED) | `HrPayrollServiceImpl.post` | Salary cost incurred; staff owed net; deductions become liabilities / recoveries | Salary Expense (gross − absence deduction) · PF Expense (employer PF) | Salary Payable (net) · Employee Advance (advance recovery) · Employee Loan (loan recovery) · Tax Payable (tax) · PF Payable (employee + employer PF) · Payroll Other Deductions (residual) | branch | employee (per line) | `PAYROLL_POSTED` |
| 14 | HR Payroll | Salary paid (`hr_salary_payment`) | `pay` | Net salary settled | Salary Payable | Cash / Bank (mode) | branch | employee | `SALARY_PAID` |
| 15 | HR Payroll | Salary payment cancelled | `cancelPayment` | | reversal of 14 | | | | `SALARY_PAID_CANCELLED` |
| 16 | HR Expense | Claim approved | `HrExpenseServiceImpl.approve` | Expense incurred; employee is owed | Expense (by expense type → configurable expense account) | Employee Reimbursement Payable | branch | employee | `EXPENSE_CLAIM_APPROVED` |
| 17 | HR Expense | Claim paid | `pay` | | Employee Reimbursement Payable | Cash / Bank | branch | employee | `EXPENSE_CLAIM_PAID` |
| 18 | HR Advance | Advance paid out | `HrAdvanceLoanServiceImpl.payAdvance` | Money lent to staff | Employee Advance (asset) | Cash / Bank | branch | employee | `ADVANCE_PAID` |
| 19 | HR Loan | Loan disbursed | `disburseLoan` | | Employee Loan (asset) | Cash / Bank | branch | employee | `LOAN_DISBURSED` |
| 20 | HR Gratuity | Award paid | `HrGratuityServiceImpl.pay` | | Gratuity Expense | Cash / Bank | branch | employee | `GRATUITY_PAID` |
| 21 | HR PF | Contributions recorded (`hr_pf_ledger`, from payroll) | inside 13 | Already part of 13 — no second event | | | | | — |
| 22 | Subscription (platform books) | Renewal invoice raised (`sub_invoice`) | `SubscriptionServiceImpl.raiseRenewal` | Platform revenue earned; tenant owes | AR (tenant branch) | Subscription Revenue | platform branch | tenant branch | `SUBSCRIPTION_INVOICED` |
| 23 | Subscription | Payment recorded (`sub_payment`, idempotency key) | `recordPayment` | | Bank / Mobile (method) | AR | platform branch | tenant | `SUBSCRIPTION_PAID` |
| 24 | Subscription | Invoice voided | `voidInvoice` | | reversal of 22 | | | | `SUBSCRIPTION_VOIDED` |
| 25 | Investor (new) | Initial / additional investment | `AccInvestorService.post` | Capital in | Cash / Bank | Investor Capital (per investor) | branch or org | investor | `INVESTOR_INVESTMENT` |
| 26 | Investor | Capital withdrawal / refund | | Capital out | Investor Capital | Cash / Bank | | investor | `INVESTOR_WITHDRAWAL` |
| 27 | Investor | Capital adjustment (approved) | | | Investor Capital ↔ Opening Balance Equity / Retained Earnings (signed) | | | investor | `INVESTOR_ADJUSTMENT` |
| 28 | Profit distribution | Allocation posted for a period | `AccProfitDistributionService.post` | Profit set aside for investors | Retained Earnings / Profit Distribution | Investor Profit Payable (per investor) | | investor | `PROFIT_ALLOCATED` |
| 29 | Profit distribution | Allocation paid | investor txn `PROFIT_DISTRIBUTION` | | Investor Profit Payable | Cash / Bank | | investor | `PROFIT_PAID` |
| 30 | Cash & Bank (new) | Cash voucher: other receipt, other payment, transfer (cash ↔ bank, cash → petty cash), petty expense, opening balance, approved cash adjustment | `AccCashService.post` | | per voucher kind | per voucher kind | branch | optional | `CASH_VOUCHER` |
| 31 | Daily cash (new) | Day closed with a difference and policy `post_cash_variance = Y` | `AccDailyCashService.close` | Ledger cash made equal to counted cash | Cash Short/Over (short) or Cash Register (over) | Cash Register (short) or Cash Short/Over (over) | branch | — | `CASH_VARIANCE` |
| 32 | Inventory | Stock only moves through 1, 7, 8, 9 — there is no free-hand adjustment | — | Inventory value is fully explained by those four events | | | | | — |
| 33 | Sales Merge | Merged invoice | `SalesMergeServiceImpl` | **None** — a grouping document, moves no stock, counts in no revenue | | | | | none, by design |
| 34 | Purchase draft / pending, requisition, cart, hold | | | **None** — not yet a liability or a sale | | | | | none |

### Amount sources, exactly

* **Sale revenue** = `pos_transaction_mst.amount_tobe_paid − delivery_charge` (subtotal less invoice discount). Line discounts are already inside line totals. `total_amount` is the subtotal.
* **Sale COGS** = Σ over `pos_stock_movement` rows with `ref_type = 'TRAN'`, `ref_mst = invoice`, `movement_type = 'SALE'`: `qty_pcs × cost_per_pcs`. This is the snapshot cost the profit reports already use — the ledger and the reports will agree.
* **Sales return refund** = `pos_transaction_mst.amount_tobe_paid` on the SR row; cost = Σ SALES_RETURN movements `qty_pcs × cost_per_pcs`; refund by = `payment_type`.
* **Purchase** = `amount_tobe_paid` (after invoice discount, tax, service charge, cashback — the landed figure the batches were costed at); `advance_amount` is the part of the cash that settles nothing.
* **Purchase return credit** = `amount_tobe_paid` on the PR row; credit type = `payment_type` (ADJUSTMENT / CASH / BANK / REPLACEMENT).
* **Supplier payment** = `amount` (settling = amount − advance_amount), `payment_mode`, `entry_type` (PAYMENT / ADVANCE_RECOVERY), `status` (Y / C).
* **Payroll** = `hr_payroll_line` sums: gross_salary, absence_deduction, advance_recovery, loan_recovery, tax_amount, pf_employee_amount, pf_employer_amount, net_salary. Balance check: `gross − absence = net + advance + loan + tax + pf_employee + other`; any residual goes to *Payroll Other Deductions* so the journal always balances and the residual is visible.
* **Salary payment / advance / loan / gratuity / expense** = `amount` / `approved_amount` / `approved_principal` / `gratuity_amount` with `payment_mode`.
* **Subscription** = `sub_invoice.amount`, `sub_payment.amount` + `method`.

### Cash account by payment mode (configurable, `acc_payment_mode`)

| Mode value seen in data | Account role |
|---|---|
| CASH, Cash, blank | CASH_REGISTER (the branch till) |
| CARD, BANK, Bank Transfer, CHEQUE, BANK_TRANSFER | BANK_DEFAULT |
| BKASH, NAGAD, ROCKET, bKash, Nagad | MOBILE_WALLET |
| CREDIT | no cash — AR stays open |
| ADJUSTMENT (supplier) | SUPPLIER_ADVANCE (consumes a credit) |

## 4. Where money can be lost today (the gaps Accounts closes)

1. Supplier balances are computed by views over operational tables (`v_supplier_account_branch`); after Accounts, AP in the ledger must equal them (reconciliation report).
2. Customer dues are `amount_tobe_paid − given_amount` per invoice; AR in the ledger must equal Σ of that.
3. The Collection register and the invoice's Collect action are two separate registers of the same cash (manual §18). The ledger reconciles them through *Cash in Transit*.
4. Payroll has never been posted on this database; when it is, salary cost hits the ledger for the first time.
5. Cash is not counted anywhere. The branch daily cash control is new.

## 5. Decisions taken (and where confirmation is still needed)

| Decision | Basis | Status |
|---|---|---|
| Accounting posts **synchronously inside the business transaction**; a failed posting rolls the business transaction back | brief §86; the codebase has no message bus, every service is `@Transactional` | Implemented |
| Books are per **organisation** (chart of accounts, periods, policies); every journal line carries the **branch** dimension | branch is the tenant, org is the pharmacy business; investors and profit are org-level with an optional branch dimension | Implemented |
| A branch's chart of accounts is provisioned from the default template on first use and at onboarding | no org may reach a posting without accounts to post to | Implemented |
| Cash in transit for delivery-man collections | two registers of the same cash | **Business policy confirmation required** — alternative: post the till directly on Collect |
| Purchase-return credit types other than ADJUSTMENT | SRS BR-028 unresolved | **Business policy confirmation required** — modelled as cash (CASH/BANK) and advance (REPLACEMENT) meanwhile |
| Subscription revenue is the platform organisation's; a pharmacy's subscription cost is not posted in its books | the pharmacy pays outside the system | **Business policy confirmation required** |
| Expense claims accrue at approval, settle at payment | accrual basis | Implemented; switchable to cash basis by rule config |
| Cash variance is **not** posted by default; the setting `post_cash_variance` turns it on | brief §19 | Implemented |
| Late postings into a CLOSED business day are refused for cash accounts; non-cash postings need only an OPEN period | brief §24 | Implemented; `late_posting_policy` setting also offers NEXT_DAY |
| Historical backfill runs as a batched, reproducible job with a reconciliation report — never as a Flyway migration | brief §81 | Implemented |
