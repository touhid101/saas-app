# 28 — Report Completeness Matrix

**25 HR reports, all built and all verified against live data.**
The catalogue now serves 43 reports; the screen lists 52 including the inventory
and delivery ones it sources elsewhere.

Twenty were built on 2026-09-09. Five followed on 2026-09-10 with the modules
behind them — Overtime Claims and the Increment Register, see
[29](29-overtime-and-increment.md); Provident Fund and Gratuity Awards, see
[30](30-provident-fund-and-gratuity.md); Expense & Conveyance, see
[31](31-expense-and-conveyance.md).

Every report was executed, not merely registered. Native SQL is not checked at
compile time — a wrong column name only appears when the query runs.

---

## Built

| Report | Id | Filters | Rows on dev | Verified |
|---|---|---|---|---|
| Employee Register | `hr-employees` | branch, search | 9 | ✅ |
| Headcount | `hr-headcount` | branch | 7 | ✅ |
| Salary Register | `hr-salary-register` | branch, search | 9 | ✅ |
| Increment Register | `hr-increment` | date, branch, status, search | 2 under test | ✅ |
| Provident Fund | `hr-pf` | date, branch, employee, search | 2 under test | ✅ |
| Gratuity Awards | `hr-gratuity` | date, branch, status, search | 1 under test | ✅ |
| Expense & Conveyance | `hr-expense` | date, branch, employee, status, search | 6 under test | ✅ |
| Employee Separation | `hr-separation` | date, branch, search | 0 | ✅ |
| Attendance Register | `hr-attendance` | date, branch, employee, search | 281 | ✅ |
| Monthly Attendance Summary | `hr-attendance-summary` | date, branch, employee, search | 9 | ✅ |
| Late Arrivals | `hr-late` | date, branch, employee, search | 21 | ✅ |
| Absences | `hr-absent` | date, branch, employee, search | 9 | ✅ |
| Overtime | `hr-overtime` | date, branch, search | 8 | ✅ |
| Overtime Claims | `hr-overtime-claims` | date, branch, status, search | 4 under test | ✅ |
| Leave Register | `hr-leave-register` | date, branch, employee, search | 2 | ✅ |
| Leave Balance | `hr-leave-balance` | date, branch, employee, search | 36 | ✅ |
| Leave Utilisation | `hr-leave-utilisation` | date, branch | 1 | ✅ |
| Leave Awaiting Approval | `hr-leave-pending` | date, branch, employee, search | 1 | ✅ |
| Advance Register | `hr-advance` | date, branch, employee, search | 2 | ✅ |
| Loan Register | `hr-loan` | date, branch, employee, search | 1 | ✅ |
| Payroll Register | `hr-payroll-register` | date, branch, employee, search | 9 | ✅ |
| Payroll Summary | `hr-payroll-summary` | date, branch | 2 | ✅ |
| Payroll Cost by Department | `hr-payroll-cost` | date, branch | 6 | ✅ |
| Payroll Deductions | `hr-payroll-deduction` | date, branch, search | 9 | ✅ |
| Payroll Exceptions | `hr-payroll-exception` | date, branch | 1 | ✅ |

## Not built, and why

| Report the brief lists | Why not |
|---|---|
| HR Audit Report | there is no old-value/new-value audit table. `BaseEntity` records who and when, not what changed from what |
| Payslip | not a grid — it is a document, and it exists as a screen on `/payroll` with its full arithmetic |

**Registering an empty report would be worse than omitting it.** A name in the
catalogue that always returns nothing reads as a broken report rather than as a
module nobody has built.

The five reports added on 2026-09-10 hold no rows on the dev database, because
nothing has been claimed, awarded or contributed there yet. They were verified
against rows created and then removed by `verify-hr-ot-increment.mjs`,
`verify-hr-pf-gratuity.mjs` and `verify-hr-expense.mjs` — a report checked only
against an empty table proves the SQL parses and nothing more.

**Provident Fund and Gratuity Awards are empty for a reason worth stating.** No
branch has a PF scheme or a gratuity policy, because the rates and the formula
are business policy (P-07, P-08) and this application will not choose them. The
reports are empty because nothing has been decided, not because the answer is
nought — see [30](30-provident-fund-and-gratuity.md).

## What the verification actually checked

`scratchpad/verify-hr-reports.mjs`, 29 checks, all passing.

Beyond "does it return 200":

* **Row shape** — every row carries exactly one value per declared column. A
  mismatch means a query returning a different number of columns than the report
  declares, which the positional `row()` helper would otherwise hide.
* **Totals** — a total appears only for a column that asked for one, and equals
  the sum of the rows. A footer that sums the page rather than the report looks
  like an answer and is not one.
* **Cross-report reconciliation** — the Payroll Register and the Payroll Summary
  agree on net (**307,887.04** both ways), and the Register and Cost-by-Department
  agree on gross (**337,122.22** both ways). Three reports reading the same
  payslips must not disagree.
* **Filters narrow** — Late Arrivals is a strict subset of the Attendance
  Register (21 of 281) and every row in it is actually late.
* **Branch isolation** — an unreachable branch id does not silently widen the
  report to every branch.
* **Overtime distinguishes unpaid from none** — 8 employees with overtime, 3 of
  them on the Delivery Staff structure which names no multiplier. Their value
  column reads *recorded only* rather than showing `0.00`, which would read as
  "no overtime" instead of "overtime is not paid here".

## Design decisions worth knowing

**One report engine, not two.** `HrReportServiceImpl` is a separate class because
nineteen more methods would have made `ReportGridServiceImpl` unreadable — but
the catalogue merges, `/api/reports/{id}` dispatches by prefix, and `Reports.jsx`
renders HR reports through the same grid with no change to the table component.
The column helpers were widened from private to package-private and reused
rather than copied.

**Payroll figures are read, never recomputed.** Gross, deductions and net come
out of `HR_PAYROLL_LINE` exactly as `PayrollCalculator` wrote them. The one
exception is the Overtime report's indicative value, which runs over a date
window rather than a payroll period and so has no payslip to read — the note on
that report says as much.

**Minutes become hours in one place.** Attendance stores minutes; the reports
show hours. Two reports rounding the same figure differently is a support call.

**The report count in the page subtitle is now computed**, not written down. It
said 28 while the screen showed 47, because adding a report and updating a
caption are two acts and only one of them is obvious.

## Regression

All **18 pre-existing reports still return 200** after the `Filters` record
gained two fields. 313 unit tests pass.
