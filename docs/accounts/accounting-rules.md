# Accounting rules

A rule is the accounting of one business event: which posting role is debited
and credited, from which amount the event carries. The 32 default rules are
seeded by `V90` with `pid_org = NULL`; saving a rule on **Periods & Settings ›
Accounting rules** copies it for the organisation (`pid_org = <org>`), which
then takes precedence. The engine (`AccountingEventService`) reads rules as
data — no event's Dr/Cr is hard-coded in Java.

## How a rule line is read

| Column | Meaning |
|---|---|
| `side` | `D` debit or `C` credit. A negative amount swaps the side. |
| `account_role` | A posting role (`AR`, `SALES_REVENUE`…), **`@MODE`** (the cash account of the event's payment mode, falling back to `CASH_REGISTER` or the role after `\|`), or **`@PAYLOAD:<key>`** (an account the hook chose and put in the event's accounts map). |
| `amount_key` | Which amount of the event this line takes. Zero → line omitted. |
| `party_type` | If set, and the event carries a party of that type, the line stores `party_type / party_id / party_name` — that is what makes AR, AP, capital and payable accounts a **sub-ledger** per customer/supplier/employee/investor/tenant. |

Amounts with **splits** (payroll) produce one line per split, each with its
own party. Journals must balance or nothing posts.

## The default rules and where each amount comes from

Notation: `Dr` debit, `Cr` credit. "Party" is the party the line carries.

### Sales

**SALE_POSTED** — `SalesServiceImpl.createSale` (and re-posted on `updateSale` under a new revision key after the original is reversed). Skipped for merge invoices (`is_merge_invoice = Y`).

| Dr / Cr | Role | Amount key | Source |
|---|---|---|---|
| Dr | AR (party CUSTOMER) | RECEIVABLE | `amount_tobe_paid` — the invoice total after discount |
| Cr | SALES_REVENUE | REVENUE | `amount_tobe_paid − delivery_charge` |
| Cr | DELIVERY_INCOME | DELIVERY_CHARGE | `delivery_charge` |
| Dr | COGS | COGS | Σ `qty_pcs × cost_per_pcs` of the sale's stock movements (`ref_type = 'TRANSACTION'`, movement SALE) |
| Cr | INVENTORY | COGS | same |

**SALE_RECEIPT** — same call, when `given_amount > 0` (capped at the invoice total).

| Dr | @MODE (cash account of `payment_type`) | AMOUNT | `min(given_amount, amount_tobe_paid)` |
| Cr | AR (party CUSTOMER) | AMOUNT | |

So a fully paid cash sale nets to Dr Cash / Cr Revenue with the receivable
opened and closed inside the same second; a credit sale leaves the receivable
open on the customer's statement.

**SALE_COLLECTION** — `collect` / `cascadeCollection` (Sales Activity, Delivery). Key `COLLECTION`, `COLLECTION:R2`… per collection.

| Dr | @PAYLOAD:RECEIPT_ACCOUNT | AMOUNT | the amount collected. Account = **CASH_IN_TRANSIT** when the sale has a delivery man and `use_cash_in_transit = Y`; otherwise the cash account of the payment type (fallback till) |
| Cr | AR (party CUSTOMER) | AMOUNT | |

**DELIVERY_CASH_IN** — `CollectionServiceImpl.record` (the rider hands in). Amended → reversal + re-post; cancelled → reversal.

| Dr | @MODE | AMOUNT | `collected_amount` |
| Cr | CASH_IN_TRANSIT (party EMPLOYEE = delivery man) | AMOUNT | |

**SALES_RETURN_POSTED** — `SalesReturnServiceImpl`.

| Dr | SALES_RETURNS | REFUND | `amount_tobe_paid` of the return |
| Cr | @PAYLOAD:REFUND_ACCOUNT (party CUSTOMER) | REFUND | **AR** when payment type is CREDIT (customer's account credited), else the cash account of the payment type |
| Dr | INVENTORY | COGS | Σ qty × cost of the return's movements |
| Cr | COGS | COGS | |

### Purchases

**PURCHASE_APPROVED** — `PurchaseServiceImpl.approve` (and `receive`).

| Dr | INVENTORY | LANDED_COST | `amount_tobe_paid` — the landed cost the inventory module values the batch at |
| Cr | AP (party SUPPLIER) | PAYABLE | same |

Cash paid with the purchase is booked by the mirrored supplier payment (below), never twice.

**PURCHASE_RETURN_POSTED** — `PurchaseReturnServiceImpl`. **BUSINESS POLICY CONFIRMATION REQUIRED** (BR-028 credit types).

| Dr | @PAYLOAD:CREDIT_ACCOUNT (party SUPPLIER) | CREDIT | `amount_tobe_paid`. Account: CASH/BANK/CARD/CHEQUE/BKASH/NAGAD/ROCKET → that cash account (money back); REPLACEMENT → SUPPLIER_ADVANCE (goods to come); anything else (ADJUSTMENT) → AP (payable reduced) |
| Cr | INVENTORY | CREDIT | |

**SUPPLIER_PAYMENT** — `SupplierPaymentServiceImpl.record` / `mirrorInvoicePayment`. Cancel → reversal.

| Dr | AP (party SUPPLIER) | SETTLING | `amount − advance_amount` |
| Dr | SUPPLIER_ADVANCE (party SUPPLIER) | ADVANCE | `advance_amount` (capped at amount) |
| Cr | @MODE | AMOUNT | `amount` |

**SUPPLIER_ADVANCE_RECOVERY** — `recoverAdvance`.

| Dr | @PAYLOAD:RECOVERY_ACCOUNT (party SUPPLIER) | AMOUNT | AP when the recovery is against a bill, else the cash account of the payment mode |
| Cr | SUPPLIER_ADVANCE (party SUPPLIER) | AMOUNT | |

**SUPPLIER_ADVANCE_RESTATED** (V95) — `SupplierPaymentServiceImpl.restateInvoiceAdvances`, `POST /api/supplier-payments/advances/restate` (ROLE01). The advance on a purchase is the **invoice discount only**; TP% and line discounts are the supplier's price and were never handed across. Purchases saved when the tick carved the whole discount out of the cash are corrected to the invoice discount, their mirror payment follows, and the difference is booked here — the original journal is never edited. Dated the day it runs; no cash line.

| Dr | AP (party SUPPLIER) | DELTA | `old advance − invoice_dis_amt`: settled the bill after all |
| Cr | SUPPLIER_ADVANCE (party SUPPLIER) | DELTA | no longer held by the supplier |

### Payroll and HR

**PAYROLL_POSTED** — `HrPayrollServiceImpl.post`, one event per run, split per employee.

| Dr | SALARY_EXPENSE | SALARY_COST | Σ (`gross_salary − absence_deduction`) |
| Dr | PF_EXPENSE | PF_EMPLOYER | Σ `pf_employer_amount` |
| Cr | SALARY_PAYABLE (split per EMPLOYEE) | NET | `net_salary` per line |
| Cr | EMPLOYEE_ADVANCE (split per EMPLOYEE) | ADVANCE_RECOVERY | `advance_recovery` per line |
| Cr | EMPLOYEE_LOAN (split per EMPLOYEE) | LOAN_RECOVERY | `loan_recovery` per line |
| Cr | TAX_PAYABLE | TAX | Σ `tax_amount` |
| Cr | PF_PAYABLE | PF_TOTAL | Σ (`pf_employee_amount + pf_employer_amount`) |
| Cr | PAYROLL_OTHER_DED | OTHER_DEDUCTION | the residual `cost + pf_employer − (net + advance + loan + tax + pf_total)` — shown, never hidden |

**SALARY_PAID** — `pay`: Dr SALARY_PAYABLE (party EMPLOYEE) / Cr @MODE, `amount`. Cancel → reversal.

**EXPENSE_CLAIM_APPROVED** (basis ACCRUAL, default) — `HrExpenseServiceImpl.approve`: Dr @PAYLOAD:EXPENSE_ACCOUNT (the first claim line's expense type account, else STAFF_EXPENSE) / Cr EMPLOYEE_REIMB_PAYABLE (party EMPLOYEE), `approved_amount`.
**EXPENSE_CLAIM_PAID** — `pay`: Dr EMPLOYEE_REIMB_PAYABLE (party EMPLOYEE) / Cr @MODE. With `expense_claim_basis = CASH` the accrual is posted at payment instead.

**ADVANCE_PAID** — Dr EMPLOYEE_ADVANCE (party) / Cr @MODE, `approved_amount`.
**LOAN_DISBURSED** — Dr EMPLOYEE_LOAN (party) / Cr @MODE, `approved_principal`.
**GRATUITY_PAID** — Dr GRATUITY_EXPENSE (party) / Cr @MODE, `gratuity_amount`.

### Subscriptions (platform organisation's books)

**SUBSCRIPTION_INVOICED** — Dr AR (party TENANT = branch) / Cr SUBSCRIPTION_REVENUE, invoice amount. Void → reversal.
**SUBSCRIPTION_PAID** — Dr @MODE / Cr AR (party TENANT), payment amount.

### Investors and profit

**INVESTOR_INVESTMENT** — Dr @MODE / Cr INVESTOR_CAPITAL (party INVESTOR).
**INVESTOR_WITHDRAWAL** — Dr INVESTOR_CAPITAL (party) / Cr @MODE.
**INVESTOR_ADJUSTMENT_UP** — Dr OPENING_BALANCE_EQUITY / Cr INVESTOR_CAPITAL (party).
**INVESTOR_ADJUSTMENT_DOWN** — Dr INVESTOR_CAPITAL (party) / Cr OPENING_BALANCE_EQUITY.
**PROFIT_ALLOCATED** — Dr PROFIT_DISTRIBUTION / Cr INVESTOR_PROFIT_PAYABLE (split per INVESTOR), the allocations.
**PROFIT_PAID** — Dr INVESTOR_PROFIT_PAYABLE (party) / Cr @MODE.

### Cash vouchers and daily cash

**CASH_RECEIPT** — Dr @PAYLOAD:CASH_ACCOUNT / Cr @PAYLOAD:COUNTER_ACCOUNT.
**CASH_PAYMENT** — Dr @PAYLOAD:COUNTER_ACCOUNT / Cr @PAYLOAD:CASH_ACCOUNT.
**CASH_TRANSFER** — Dr @PAYLOAD:TO_ACCOUNT / Cr @PAYLOAD:FROM_ACCOUNT.
**CASH_OPENING** — Dr @PAYLOAD:CASH_ACCOUNT / Cr OPENING_BALANCE_EQUITY.
**CASH_ADJUSTMENT_IN** — Dr @PAYLOAD:CASH_ACCOUNT / Cr CASH_SHORT_OVER.
**CASH_ADJUSTMENT_OUT** — Dr CASH_SHORT_OVER / Cr @PAYLOAD:CASH_ACCOUNT.
**CASH_VARIANCE_SHORT** — Dr CASH_SHORT_OVER / Cr CASH_REGISTER (only when `post_cash_variance = Y`).
**CASH_VARIANCE_OVER** — Dr CASH_REGISTER / Cr CASH_SHORT_OVER.

### Manual journals

Not rule-driven: the user names the accounts. Saved as DRAFT, validated
(≥ 2 lines, each a debit *or* a credit, balanced, no group/retired/system-only
accounts, the till line not on a closed day), and POSTED by someone holding
Approve — or on save when `manual_journal_auto_post = Y`. Types MANUAL,
ADJUSTMENT, OPENING.

## Reversal

`AccountingEventService.reverseSource(org, module, type, id, date, reason)`
reverses every POSTED journal of a source document: a new journal of type
REVERSAL with debits and credits swapped, dated `date` (today by default),
the original marked REVERSED and linked (`pid_reversal_of`), the event marked
REVERSED and a trail event `<key>:REV` POSTED. A reversal into a CLOSED period
is allowed (that is what CLOSED means); into a LOCKED period it is not.

Used by: sale edit, delivery cash-in amend/cancel, supplier payment cancel,
salary payment cancel, subscription void, manual journal reverse, day reopen
(variance), distribution cancel after posting.

## Editing a rule

On Periods & Settings › Accounting rules, a user with Approve on that module
can change the lines of a rule for their organisation. Keep the amount keys —
they are what the hook fills. Changing a role (e.g. sending delivery income to
SALES_REVENUE) takes effect for the next event; nothing posted is restated. A
rule saved with lines that cannot balance for the amounts the event carries
will refuse every such event with `ACC_UNBALANCED` and roll the business
transaction back — test on a dry run day, or with posting temporarily off.
