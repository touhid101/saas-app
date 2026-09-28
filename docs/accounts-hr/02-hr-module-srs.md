# 02 — HR Module SRS (as built)

**Status:** implemented and verified against the running application on 2026-09-09.
Migrations V73–V77 applied. 313 backend tests pass. 79 end-to-end checks pass.

This document describes what the code does, not what was intended. Where the
implementation deliberately refuses to decide something, it says so and marks it
**BUSINESS POLICY CONFIRMATION REQUIRED**.

---

## 1. Scope

| Req | Requirement | Status |
|---|---|---|
| HR-001 | Employee master | **Extended** — `hr_employees` gains 13 columns; CRUD stays on `/api/employees` |
| HR-002 | Department | **Reused** — `all_departments`, already on Master Data |
| HR-003 | Designation | **Reused** — `hr_designation`, already on Master Data |
| HR-004 | Attendance | **Implemented** — `hr_attendance` |
| HR-005 | Leave | **Implemented** — type, balance, request, approval |
| HR-006 | Payroll | **Implemented** — period, run, payslip, component-level arithmetic |
| HR-007 | Employee advance | **Implemented** |
| HR-008 | Employee loan | **Implemented**, with a written repayment schedule |
| HR-009 | Salary payment | **Implemented**, separate from the run |
| HR-010 | Employee separation | **Implemented**, non-destructive |
| HR-011 | Salary structure | **Implemented** — `hr_salary_structure` + components |

## 2. Modules and rights

Registered by V77 under a new `GRP_HR` heading.

| Code | Screen | Route | ROLE01 | ROLE02 | ROLE04 Accounts |
|---|---|---|---|---|---|
| MOD32 | Employees | `/hr` | all | all | V C E X P |
| MOD33 | Attendance | `/attendance` | all | all | V C E X P |
| MOD34 | Leave | `/leave` | all | all | V C E X P |
| MOD35 | Advance & Loan | `/advances` | all | all | V C E X P |
| MOD36 | Payroll | `/payroll` | all | all | V C E X P |

**ROLE04 holds no Delete and no Approve anywhere.** That is the shape of the
job: the person who prepares a payroll should not be the person who approves it,
and the permission matrix is where that separation is expressed. No other role
is granted anything — that is a decision for each pharmacy, taken on the RBAC
screen, not one a migration should make on their behalf.

Every check is `PermissionGuard.has(module, right)` in the service, via
`HrScope.require`. **No `@PreAuthorize` role name appears in any HR class** —
the defect G-01 pattern is not repeated.

## 3. Data model

```
hr_employees  (extended)
   ├─ pid_salary_structure ──► hr_salary_structure ──► hr_salary_component
   ├─ hr_attendance            (unique: employee + date, live rows only)
   ├─ hr_leave_request ──────► hr_leave_type
   │      └─ writes hr_attendance rows tagged with the request
   ├─ hr_leave_balance         (unique: employee + type + year)
   ├─ hr_advance
   ├─ hr_loan ───────────────► hr_loan_schedule ──► pid_payroll
   ├─ hr_payroll_period ─────► hr_payroll ──► hr_payroll_line ──► hr_payslip_component
   │                                              └─ hr_salary_payment
   └─ hr_separation            (unique: one live per employee)
```

15 tables: 1 extended, 14 new. Every new table carries the five `BaseEntity`
audit columns.

## 4. The payroll calculation — the actual formula

Implemented in `util/PayrollCalculator.java`, a pure class with no Spring and no
I/O, so it is unit-tested independently of a database. 16 tests.

```
 1  basic          = HR_EMPLOYEES.BASIC_SALARY
 2  earnings         walk EARNING components in SORT_ORDER, accumulating:
                        FIXED         AMOUNT
                        PCT_OF_BASIC  basic × PCT_VALUE / 100
                        PCT_OF_GROSS  runningGross × PCT_VALUE / 100
                     runningGross starts at basic
    allowances     = runningGross − basic
 3  overtime       = 0 when the structure names no multiplier, otherwise
                     basic / workingDays / hoursPerDay × multiplier × otHours
 4  bonus            as entered for this run, per employee
 5  gross          = basic + allowances + overtime + bonus
 6  absence        = (absenceBasis = GROSS ? gross : basic) / workingDays
                     × (absentDays + unpaidLeaveDays)
 7  deductions       walk DEDUCTION components the same way, against gross
 8  tax            = 0                                    ← see §7
 9  advanceRecovery = min(advance due, what is left)
    loanRecovery    = min(loan due,    what is left after the advance)
10  net            = gross − everything above, floored at zero
```

**Worked example**, verified end-to-end by `verify-hr.mjs`:

| | |
|---|---|
| Basic | 20,000.00 |
| House rent, 40% of 20,000 | 8,000.00 |
| Medical, fixed | 1,500.00 |
| Overtime, 10h at 2× (20000/26/8 = 96.1538/h) | 1,923.08 |
| **Gross** | **31,423.08** |
| Provident fund, 5% of basic | (1,000.00) |
| 2 absent days, 2/26 of basic | (1,538.46) |
| Advance recovery | (3,000.00) |
| Loan installment 1 of 3 | (3,333.33) |
| Tax | (0.00) |
| **Net** | **22,551.29** |

Rounding is 2 dp HALF_UP at each step, matching `PurchaseCalculator` and
`SalesPriceCalculator`.

### Why recoveries are capped, and why advances go first

`ck_hr_line_net` refuses a negative payslip, and it is right to: a negative net
means the recovery plan took more than the month contained. So a recovery takes
what is there and no more, and the balance stays outstanding for next month. An
advance is recovered before a loan because an advance is this month's salary
already handed over, while a loan was always going to take several months.

## 5. Business rules the implementation enforces

| # | Rule | Where it lives |
|---|---|---|
| R-01 | One attendance row per employee per day | `uq_hr_attendance_day` **and** a service check for the message |
| R-02 | A device punch cannot be edited, only overridden | `HrConstants.AttendanceSource.isEditable` |
| R-03 | Worked minutes come from the two timestamps when both are present | `HrAttendanceServiceImpl.workedMinutes` |
| R-04 | Attendance cannot be recorded for a future day | service |
| R-05 | LEAVE cannot be typed onto the register — no balance would move | service |
| R-06 | A submitted leave request reserves its days as PENDING | `reserve()`, under a row lock |
| R-07 | Approving moves PENDING to TAKEN and writes tagged attendance | `approve()` |
| R-08 | Cancelling an approved request removes exactly the rows it created | found by `pid_leave_request`, not by date |
| R-09 | Overlapping leave for one employee is refused | `findOverlapping` |
| R-10 | A balance cannot be opened for a type with no quota | service |
| R-11 | An entitlement cannot be set below what is already used | service |
| R-12 | An approved amount cannot exceed what was requested | service + `ck_hr_advance_approved` |
| R-13 | Recovery is always computed from the **approved** amount | entity |
| R-14 | An advance or loan cannot be cancelled once partly recovered | service |
| R-15 | A loan schedule sums to the principal exactly (last installment carries the rounding) | `buildSchedule` |
| R-16 | One live payroll run per branch per period | `uq_hr_payroll_live_run` |
| R-17 | Two payroll periods cannot contain the same day | service |
| R-18 | Working days are required on a period, never defaulted | request validation |
| R-19 | Recalculating releases everything the run previously claimed | `releasePreviousClaims` |
| R-20 | A POSTED run cannot be recalculated, edited, deleted or cancelled | service + `HrConstants.PayrollStatus.isFrozen` |
| R-21 | Only a POSTED run can be paid | service |
| R-22 | Cancelling a payment takes the run back out of PAID | `refreshPaidTotal`, summed from live rows |
| R-23 | A structure naming BASIC is refused — it would be paid twice | service |
| R-24 | A component must be computable (fixed needs an amount, % needs a %) | service + `ck_hr_component_value` |
| R-25 | An overtime multiplier without a standard day is refused | service |
| R-26 | A structure cannot be retired while anybody is on it | service |
| R-27 | A separation cannot be approved while money is owed | service |
| R-28 | Approving a separation deactivates, never deletes | service |
| R-29 | Bank payment needs an account number; mobile needs a wallet number | service |
| R-30 | An employee from another branch is *not found*, not refused | `HrScope.employee` |

## 6. Validation layers

1. **Bean validation** on the request DTOs — presence, size, range.
2. **Service rules** — the 30 above, each raising a `BusinessException` with an
   error code and a sentence a user can act on.
3. **Database constraints** — 20 CHECK constraints and 7 unique indexes, which
   hold under concurrency where a service check alone does not.
4. **Permission matrix** — before any of the above.

## 7. BUSINESS POLICY CONFIRMATION REQUIRED

These five are deliberately unanswered. Each is configurable, and each is
**visible in the UI as a choice rather than as a blank field**.

| # | Question | What ships | Why not decided here |
|---|---|---|---|
| P-01 | **Income tax** on salary | `taxAmount` is always 0. The taxable base is tracked so a rate has something to apply to | No rate, slab or exemption exists anywhere in this application. A plausible figure on a payslip is a number no accountant chose |
| P-02 | **Overtime rate** | `overtime_multiplier` is null → overtime is recorded, shown in hours, and not valued | A rate is policy and in places statutory. A seeded 1.5 would be read as the answer, and the first anyone would learn otherwise is a wage claim |
| P-03 | **Leave entitlement** | The four leave types are seeded with `annual_quota = NULL`; balances cannot open until it is set | How many days each kind is worth is the pharmacy's policy, and in places its statutory obligation |
| P-04 | **Weekly off and holidays** | Every calendar day in a leave range counts | The application holds no weekly-off pattern and no holiday calendar. A Friday–Saturday weekend, a Sunday one, and a shop open seven days are all normal here. *Recommended:* a working-day calendar per branch |
| P-05 | **Final settlement** | `settlement_amount` is entered, never computed | What belongs in one — notice pay, leave encashment, gratuity — is policy and in places statute |

One further item is recorded but never applied:

| P-06 | **Interest on a staff loan** | `interest_rate` is stored; nothing computes with it | Whether interest is charged, at what rate, and whether that is permissible |

## 8. API surface

38 endpoints across 5 controllers, all under `/api/v1/hr`. No endpoint accepts a
branch or organisation — both are resolved from the session.

| Controller | Base | Endpoints |
|---|---|---|
| `HrController` | `/api/v1/hr` | summary, profile, pay-basis, separations (5), vocabulary |
| `HrAttendanceController` | `/api/v1/hr/attendance` | day, search, per-employee, record, record-day, update, withdraw |
| `HrLeaveController` | `/api/v1/hr/leave` | types (4), balances (2), requests (7) |
| `HrAdvanceLoanController` | `/api/v1/hr` | advances (6), loans (7) |
| `HrPayrollController` | `/api/v1/hr/payroll` | structures (5), periods (3), runs (7), payment (3) |

## 9. Frontend

5 screens, one stylesheet, one service module, one shared kit.

| Screen | Route | Panes on mobile |
|---|---|---|
| `HrEmployees.jsx` | `/hr` | Overview · Staff · Leavers |
| `HrAttendance.jsx` | `/attendance` | Register · History |
| `HrLeave.jsx` | `/leave` | Queue · All · Balances · Types |
| `HrAdvances.jsx` | `/advances` | Advances · Loans |
| `HrPayroll.jsx` | `/payroll` | Runs · Periods · Structures |

**Responsive behaviour is the QuickSales pattern, not a new one:** breakpoints
767.98 and 1199.98; a fixed bottom bar with 48px targets, a teal active state
with a 2.5px top bar so it survives a colour-blind reading, and
`env(safe-area-inset-bottom)`; tables become one card per row below 768.
Verified by measurement at 375, 768 and desktop: no horizontal overflow at any
width, the bar sits exactly at the viewport foot, and the page carries 76px of
bottom padding so nothing hides behind it.

**The screen holds no copy of the lifecycle.** Every payroll response carries
`allowedActions`, and the buttons are rendered from it — so the UI cannot offer
a step the server will refuse.

## 10. What HR does not do

* **It writes no journal.** Paying an advance, disbursing a loan and paying a
  salary are recorded as operational facts. `post()` freezes the run and stamps
  it; that is where Accounts will raise its financial event.
* **It computes no tax.**
* **It does not delete an employee.** Ever.
* **It has no cross-branch view.** Every screen is the session branch, matching
  the rest of the product.
