# Chart of accounts

Every organisation gets its own copy of the template below when it is created
(`AccChartService.ensureProvisioned`, called from tenant onboarding and lazily
from the engine). Codes, names and hierarchy are editable per organisation on
**Accounts › Chart of Accounts**; new accounts can be added under any group.
What the engine relies on is not the code but the **posting role**
(`acc_account.role_key`, unique per organisation): rules say "credit
SALES_REVENUE", and the chart says which account that is today.

Legend — **Type** ASSET / LIABILITY / EQUITY / REVENUE / EXPENSE · **N** normal
side (D debit, C credit) · **Group** rows take no postings.

## Assets (1000)

| Code | Account | N | Role | Used by |
|---|---|---|---|---|
| 1100 | Cash & Cash Equivalents (group) | D | | |
| 1110 | Cash Register | D | `CASH_REGISTER` | till: cash sales, cash receipts/payments, daily cash control |
| 1120 | Petty Cash | D | `PETTY_CASH` | petty cash book, transfers |
| 1130 | Bank (group) | D | | |
| 1131 | Bank – Main Account | D | `BANK_DEFAULT` | CARD/BANK/CHEQUE modes; more bank accounts are added under 1130 and linked from Cash & Bank |
| 1140 | Mobile Wallets (bKash, Nagad) | D | `MOBILE_WALLET` | BKASH/NAGAD/ROCKET modes |
| 1150 | Cash in Transit | D | `CASH_IN_TRANSIT` | delivery man's collections until handed in |
| 1200 | Accounts Receivable | D | `AR` | customer credit sales, subscription invoices (party lines) |
| 1300 | Inventory | D | `INVENTORY` | purchases at landed cost, COGS, returns |
| 1400 | Prepayments & Advances (group) | D | | |
| 1410 | Supplier Advances | D | `SUPPLIER_ADVANCE` | advance part of a supplier payment; adjustment/replacement return credits |
| 1420 | Employee Advances | D | `EMPLOYEE_ADVANCE` | HR advance paid, recovered through payroll |
| 1430 | Employee Loans | D | `EMPLOYEE_LOAN` | HR loan disbursed, recovered through payroll |
| 1440 | Other Prepayments | D | `PREPAYMENT` | manual |
| 1500 | Fixed Assets (group) | D | | |
| 1510 | Furniture & Equipment | D | `FIXED_ASSET` | manual |

## Liabilities (2000)

| Code | Account | N | Role | Used by |
|---|---|---|---|---|
| 2100 | Accounts Payable | C | `AP` | approved purchases, supplier payments (party lines) |
| 2200 | Payroll Payable (group) | C | | |
| 2210 | Salary Payable | C | `SALARY_PAYABLE` | net pay at payroll post, cleared at salary paid (per employee) |
| 2220 | Provident Fund Payable | C | `PF_PAYABLE` | employee + employer PF at payroll post |
| 2230 | Employee Reimbursements Payable | C | `EMPLOYEE_REIMB_PAYABLE` | approved expense claims until paid |
| 2240 | Payroll Other Deductions | C | `PAYROLL_OTHER_DED` | residual deductions at payroll post |
| 2300 | Tax Payable (group) | C | | |
| 2310 | Salary Tax Payable | C | `TAX_PAYABLE` | tax deducted at payroll post |
| 2320 | VAT Payable | C | `VAT_PAYABLE` | default account of new tax rates (posting awaits policy confirmation) |
| 2400 | Investor Profit Payable | C | `INVESTOR_PROFIT_PAYABLE` | profit allocated, cleared when paid (per investor) |
| 2500 | Other Payables | C | `OTHER_PAYABLE` | manual |

## Equity (3000)

| Code | Account | N | Role | Used by |
|---|---|---|---|---|
| 3100 | Owner Capital | C | `OWNER_CAPITAL` | manual |
| 3200 | Investor Capital | C | `INVESTOR_CAPITAL` | investments, withdrawals, adjustments (per investor) — the investor capital ledger |
| 3300 | Retained Earnings | C | `RETAINED_EARNINGS` | manual year-end close (not automated) |
| 3400 | Profit Distributed | D | `PROFIT_DISTRIBUTION` | debit side of a posted profit distribution |
| 3900 | Opening Balance Equity | C | `OPENING_BALANCE_EQUITY` | opening cash vouchers, investor capital adjustments |

## Revenue (4000)

| Code | Account | N | Role | Used by |
|---|---|---|---|---|
| 4100 | Sales Revenue | C | `SALES_REVENUE` | sale invoiced (net of discount, excluding delivery charge) |
| 4150 | Sales Returns | D | `SALES_RETURNS` | contra-revenue on a sales return |
| 4200 | Delivery Income | C | `DELIVERY_INCOME` | delivery charge on a sale |
| 4300 | Subscription Revenue | C | `SUBSCRIPTION_REVENUE` | platform books only |
| 4900 | Other Income | C | `OTHER_INCOME` | cash receipt vouchers |

## Expenses (5000 cost of sales, 6000 operating)

| Code | Account | N | Role | Used by |
|---|---|---|---|---|
| 5100 | Cost of Goods Sold | D | `COGS` | Σ qty × cost of the sale's stock movements; reversed on return |
| 6100 | Salaries & Wages | D | `SALARY_EXPENSE` | gross salary cost at payroll post |
| 6110 | Provident Fund Expense | D | `PF_EXPENSE` | employer PF |
| 6120 | Gratuity Expense | D | `GRATUITY_EXPENSE` | gratuity paid |
| 6130 | Staff Expenses & Conveyance | D | `STAFF_EXPENSE` | expense claims whose type has no account of its own |
| 6200 | Rent | D | `RENT` | manual / payment vouchers |
| 6300 | Utilities | D | `UTILITIES` | manual / payment vouchers |
| 6400 | Bank Charges | D | `BANK_CHARGES` | manual / payment vouchers |
| 6500 | Cash Short / Over | D | `CASH_SHORT_OVER` | closing variance (when policy posts it), cash adjustment vouchers |
| 6900 | Other Expenses | D | `OTHER_EXPENSE` | payment vouchers |

Groups 1000 Assets, 2000 Liabilities, 3000 Equity, 4000 Revenue, 5000 Cost of
Sales and 6000 Operating Expenses sit above these (roles `ASSETS` … not
postable).

## Rules of the chart

* **Roles are unique per organisation.** Moving a role to another account
  (edit → posting role) re-points every rule at once; nothing already posted
  changes.
* **Group accounts never take a posting** (`ACC_GROUP_ACCOUNT`), from the
  engine or from a manual journal.
* **Retired accounts** (`is_active = 0`) refuse new postings but keep their
  history and appear in reports for the periods they were used.
* **Nothing in the chart is ever deleted** — there is no delete endpoint;
  an account is retired. An account carrying a posting role cannot be retired
  until the role is moved. A child must share its parent's type.
* **Manual journals** may use any postable account with `allow_manual = 1`.
  Control accounts that only the engine should touch can have it switched off.
* **Expense types** (`hr_expense_type.pid_account`, added by V90) map a claim
  type to an expense account; without one the claim lands on 6130.
* **Bank accounts** (`acc_bank_account`) each point at a ledger account under
  1130; the default bank is what `BANK_DEFAULT` resolves to.
* **Payment modes** (`acc_payment_mode`) decide which cash account a mode
  reaches: CASH → CASH_REGISTER; CARD, BANK, BANKTRANSFER, CHEQUE →
  BANK_DEFAULT; BKASH, NAGAD, ROCKET, MOBILE → MOBILE_WALLET; CREDIT → none;
  ADJUSTMENT, REPLACEMENT → SUPPLIER_ADVANCE. Unknown modes fall back to the
  till and are logged; map them on Periods & Settings › Payment modes.

## Opening balances

The books start empty. Two ways in, both leaving a trail:

1. **Backfill** (Reconciliation › Backfill history) posts every historical
   sale, return, purchase and supplier payment as dated journals, so
   receivables, payables, inventory and revenue history come from the
   documents themselves. See [migration-reconciliation.md](migration-reconciliation.md).
2. **Opening vouchers** for what documents do not carry: an OPENING cash
   voucher for the till/bank/petty balance (Dr cash, Cr 3900), investor
   capital adjustments for capital already invested (Cr 3200, Dr 3900), and
   manual journals for fixed assets, owner capital and prepayments against
   3900. When every balance is in, 3900 nets to the true opening equity and
   can be transferred to 3100/3300 by a manual journal.
