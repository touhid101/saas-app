# 01 — Existing Object Inventory and Reuse Decisions

Every row below was checked against the live schema and the source tree before the action
was chosen. **REUSE** = use as-is. **EXTEND** = `ALTER TABLE ... ADD COLUMN`, nullable, no
data rewritten. **CREATE** = genuinely does not exist. **INTEGRATE** = exists and stays
where it is; the new module reads it rather than copying it.

---

## A. HR

| SRS object | Existing DB object | Existing backend object | Existing frontend | Action |
|---|---|---|---|---|
| Employee master | `pharma.hr_employees` (6 rows) | `HrEmployee`, `HrEmployeeRepository`, `MasterDataServiceImpl`, `DeliveryManService` | `MasterData.jsx` → group "HR" → entity `employee` | **EXTEND** — add employment type, confirmation/probation, salary structure link, separation fields, bank/payment fields |
| Department | `pharma.all_departments` (3 rows) | `AllDepartments`, `AllDepartmentsRepository` | `MasterData.jsx` entity `department` | **REUSE** — no new column needed for HR; it becomes a cost-centre source in Accounts |
| Designation | `pharma.hr_designation` (3 rows) | `HrDesignation`, `HrDesignationRepository` | `MasterData.jsx` entity `designation` | **REUSE** |
| Employee type / job function | `hr_employees.emp_type` + CHECK constraint from V12 | `EmployeeType` (closed set in Java) | employee form select | **REUSE** — do not add a second classification column |
| Employee photo | `hr_employees.emp_image / file_name / file_mime_type` | `ImageStorageService` pattern | employee form | **REUSE** |
| Branch / company / org on an employee | `hr_employees.pid_branch, pid_company, pid_org` (all NOT NULL) | `SecurityUtils` scope resolution | — | **REUSE** |
| Attendance | — | — | — | **CREATE** `hr_attendance` |
| Leave type | — | — | — | **CREATE** `hr_leave_type` |
| Leave balance | — | — | — | **CREATE** `hr_leave_balance` |
| Leave request | — | — | — | **CREATE** `hr_leave_request` |
| Salary structure | — | — | — | **CREATE** `hr_salary_structure` + `hr_salary_component` |
| Employee advance | — | — | — | **CREATE** `hr_advance` |
| Employee loan | — | — | — | **CREATE** `hr_loan` + `hr_loan_schedule` |
| Payroll period | — | — | — | **CREATE** `hr_payroll_period` |
| Payroll run / payslip | — | — | — | **CREATE** `hr_payroll` + `hr_payroll_line` |
| Salary payment | — | — | — | **CREATE** `hr_salary_payment` |
| Separation | — | — | — | **CREATE** `hr_separation` |
| Document numbering (payroll no.) | `sequence_tracker` | `DocumentNumberService` | — | **REUSE** |
| Approval / audit stamps | `BaseEntity` (`status, inserted_by, inserted_date, updated_by, updated_date`) | `AuditingEntityListener` | — | **REUSE** on every new table |

### HR objects deliberately NOT created

| Tempting | Why not |
|---|---|
| `hr_department` | `all_departments` already exists, is populated and is already on the HR screen. A second department table would split the workforce across two lists. |
| `hr_employee` (new) | `hr_employees` exists with 6 live rows referenced by delivery assignment and invoices. |
| A second employee-type enum | `EmployeeType` + the V12 CHECK constraint already close the set. |
| An HR-specific user table | Employees and login accounts are already related through `AclUser.pidEmployee`. |

---

## B. Accounts

| SRS object | Existing DB object | Existing backend object | Existing frontend | Action |
|---|---|---|---|---|
| Chart of accounts | — | — | — | **CREATE** `acc_account` |
| Fiscal year / period | — | — | — | **CREATE** `acc_fiscal_year`, `acc_period` |
| Cost centre | — | — | — | **CREATE** `acc_cost_centre`, sourced from branch and department |
| Financial event | — | — | — | **CREATE** `acc_event` (carries the idempotency key) |
| Accounting rule | — | — | — | **CREATE** `acc_rule` + `acc_rule_line` |
| Journal header / lines | — | — | — | **CREATE** `acc_journal`, `acc_journal_line` |
| General ledger | — | — | — | **CREATE** — as a query over posted `acc_journal_line`, plus `acc_balance` for period totals |
| Tax configuration | — | — | — | **CREATE** `acc_tax` |
| Bank master / account | — | `pos_supplier_payment.bank_name` is free text only | — | **CREATE** `acc_bank_account` |
| Cash account / drawer | — | — | — | **CREATE** — as rows in `acc_account` under Cash, not a separate table |
| Petty cash | — | — | — | **CREATE** `acc_petty_cash` + `acc_petty_expense` |
| Investor | — | — | — | **CREATE** `acc_investor`, `acc_investor_txn` |
| Customer (AR party) | `pos_customer_info` — **already has `credit_limit` and `opening_bal`** | `PosCustomerInfo` | `MasterData.jsx`, `CustomerPicker.jsx` | **REUSE** — AR is a ledger view of this party, not a new customer table |
| Supplier (AP party) | `pos_supplier_info` — **already has `credit_limit` and `opening_bal`** | `PosSupplierInfo` | `MasterData.jsx` | **REUSE** |
| Customer invoice | `pos_transaction_mst` / `_dtl` (200,466 rows) | `SalesServiceImpl` | `Sales.jsx`, `QuickSales.jsx` | **INTEGRATE** — the sale raises a financial event; the invoice is not re-recorded |
| Supplier invoice | `pos_transaction_mst` with purchase type | `PurchaseServiceImpl` | `Purchase.jsx` | **INTEGRATE** |
| Sales return | `pos_transaction_mst.pid_tran_mst_return` | `SalesReturnServiceImpl` | `SalesReturn.jsx` | **INTEGRATE** |
| Purchase return | same | `PurchaseReturnServiceImpl` | `PurchaseReturn.jsx` | **INTEGRATE** |
| Supplier payment | `pos_supplier_payment` (28 rows) — has `voucher_no`, `payment_mode`, cheque fields, `entry_type` | `SupplierPaymentServiceImpl` | `SupplierPayment.jsx` | **INTEGRATE** — payment stays where it is and raises an event |
| Customer receipt / collection | `pos_delivery_collection` (0 rows) and the payment fields on `pos_transaction_mst` (`given_amount, paid_by, paid_date, payment_status, payment_type, advance_amount`) | `CollectionServiceImpl` | `Collection.jsx` | **INTEGRATE** |
| Subscription revenue | `sub_invoice`, `sub_payment` — **`sub_payment.idempotency_key` already exists** | `SubscriptionServiceImpl` | `Subscription.jsx` | **INTEGRATE** — platform books |
| Inventory value / COGS | `pos_stock_batch.cost_per_pcs`, `pos_stock_movement.cost_per_pcs` | `StockPostingServiceImpl`, `LandedCostTest` | `Inventory.jsx` | **INTEGRATE** — COGS is read from the movement, never recalculated in Accounts |
| Branch (cost centre / book boundary) | `all_branch` | — | — | **REUSE** |
| Organisation (tenant / book owner) | `all_organization` | — | — | **REUSE** |
| Document numbering (journal no.) | `sequence_tracker` | `DocumentNumberService` | — | **REUSE** |
| Audit trail | `BaseEntity` + `acl_password_reset_audit` pattern | `PasswordResetAuditRecorder` (`REQUIRES_NEW`) | — | **REUSE the pattern**, create `acc_audit` for financial actions |
| Permissions | `acl_module_info`, `pos_permissions`, `acl_tenant_module`, `acl_perm_template_line` | `PermissionGuard`, `PermissionResolver` | `usePermission.js` | **REUSE** — new modules MOD32+ registered the V64 way |
| Role for accounts staff | `acl_group` **ROLE04 "Accounts"** already exists | — | `RBAC.jsx` | **REUSE** — it is the grantee, no new role |

### Accounts objects deliberately NOT created

| Tempting | Why not |
|---|---|
| `acc_customer` / `acc_supplier` | `pos_customer_info` and `pos_supplier_info` already carry `credit_limit` and `opening_bal`. AR/AP is a *view of the ledger for that party*, keyed on the existing id. |
| `acc_invoice` | Sales and purchase invoices are `pos_transaction_mst`. Copying 200,466 rows into an accounting table would create two versions of the truth. |
| `acc_payment` | `pos_supplier_payment` exists and already has a voucher number and mode. |
| `acc_inventory_value` | Value is `pos_stock_batch.cost_per_pcs × quantity`. A second store would drift from the batch. |
| A separate cash table | A cash location is an account in the chart of accounts. A table would duplicate what the COA is for. |

---

## C. What the two modules own, and what they must not touch

| Concern | Owner | Rule |
|---|---|---|
| Employee, attendance, leave, salary calculation, payroll | **HR** | Accounts never recalculates a payslip |
| Financial event, journal, general ledger, period, payable | **Accounts** | HR never writes a journal |
| Stock quantity and cost per piece | **Inventory** (existing) | Accounts reads `cost_per_pcs`; it does not re-derive landed cost |
| Invoice totals, discount, cashback, gift allocation | **Sales / Purchase** (existing) | Accounts reads the posted totals |
| Who may do what | **the permission matrix** (existing) | neither module gets its own role check |

---

## D. Migration versions this inventory implies

| Version | Purpose |
|---|---|
| V73 | extend `hr_employees`; create the HR reference tables (leave type, salary structure) |
| V74 | create attendance and leave |
| V75 | create advance, loan and the loan schedule |
| V76 | create payroll period, payroll, payroll line, salary payment, separation |
| V77 | register HR modules (MOD32–MOD35) and grant them |
| V78+ | Accounts — see `16-flyway-production-migration-plan.md` |

All additive. No `DROP`, no `TRUNCATE`, no `NOT NULL` added to a populated table without a
default, no change to any file V1–V72.
