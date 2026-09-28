# 17 — HR Completion Report

**Date:** 2026-09-09
**Verified against:** the running application (backend 8585, `minipos_db` on 5489),
not against fixtures.

---

## 1. The acceptance list

From §78 of the brief. Each row names how it was proved.

| | Item | Proof |
|---|---|---|
| ✅ | Employee Master works | Extended in place. `verify-hr.mjs`: an employee is created through the existing `/api/employees`, and the HR profile reads it back with the pay basis |
| ✅ | Department works | Reused unchanged; `/api/departments` returns 200 in the regression run |
| ✅ | Designation works | Reused unchanged; `/api/designations` returns 200 |
| ✅ | Attendance works | Register, one-tap marking, whole-day save, per-employee history |
| ✅ | Leave works | Type → quota → balance → request → submit |
| ✅ | Leave approval works | Approve moves pending to taken **and writes three LEAVE attendance rows tagged with the request** |
| ✅ | Salary Structure works | Created with three components; the payslip reproduces each one |
| ✅ | Advance works | Requested 5,000, approved 3,000, paid, recovered in full by payroll |
| ✅ | Loan works | 10,000 over 3; **the schedule sums to 10,000.00 exactly**, no lost penny |
| ✅ | Payroll works | Calculated for a real month against real attendance |
| ✅ | Payroll calculation works | Ten separate assertions on the arithmetic — see §3 |
| ✅ | Payroll review works | `CALCULATED → REVIEWED`, and a calculated run **cannot skip review** |
| ✅ | Payroll approval works | `REVIEWED → APPROVED`, gated by the Approve right |
| ✅ | Payroll posting works | `APPROVED → POSTED`, `frozen=true` |
| ✅ | Payroll immutability works | A posted run refuses recalculation **and** cancellation |
| ✅ | Salary payment works | Paid, run moved to PAID; cancelling a payment **took it back out of PAID** |
| ✅ | Employee separation works | Filed; approval **refused while an advance was outstanding**; employee stayed active |
| ✅ | Permissions work | Every service call goes through `PermissionGuard`; no `@PreAuthorize` role name in any HR class |
| ✅ | Tenant isolation works | Scope from the session only. No HR request accepts a branch or org |
| ✅ | Branch isolation works | An employee outside the branch returns **404, not 403** |
| ✅ | Responsive UI works | Measured at 375 / 768 / desktop — see §5 |
| ✅ | Backend tests pass | 313, up from 297. 16 new on the payroll calculator |
| ✅ | Integration tests pass | 79 end-to-end checks against the running app |
| ✅ | Flyway validate passes | V73–V77 applied cleanly; the app started, which validates every JPQL query |
| ✅ | Existing regression tests pass | 313 unchanged + 15 live endpoint checks |

## 2. What was measured, not assumed

**Migrations were dry-run first.** All five were concatenated inside a
`BEGIN … ROLLBACK` against the real database before being applied, so a syntax
or constraint error could not leave the schema half-changed.

**Hibernate mapping and JPQL were proved by boot.** The application started in
24 seconds with 16 new entities and 12 new repositories. Spring Data parses
every `@Query` at startup; a bad one refuses to start. It started.

**Regression was proved by calling the old endpoints**, not by assuming they
were untouched: menu, dashboard, products, employees, departments, designations,
suppliers, customers, inventory, purchases, subscription and reports all return
200 after the migration.

**Nothing about payroll was proved from a fixture.** Attendance was recorded
through the API, leave was approved through the API, an advance was paid and a
loan disbursed through the API, and the payslip was checked against arithmetic
worked out by hand.

## 3. The payslip that was verified

One employee, March 2026, 26 working days, a structure of house rent at 40% of
basic, a fixed medical allowance and provident fund at 5%.

| | Expected | Got |
|---|---|---|
| Basic | 20,000.00 | ✅ |
| Allowances (40% + 1,500) | 9,500.00 | ✅ |
| Overtime (10h at 2×, 20000/26/8 = 96.1538/h) | 1,923.08 | ✅ |
| **Gross** | **31,423.08** | ✅ |
| 2 absent days, 2/26 of basic | 1,538.46 | ✅ |
| Advance recovery | 3,000.00 | ✅ |
| Loan installment 1 of 3 | 3,333.33 | ✅ |
| Tax | 0.00 | ✅ |
| **Net** | **22,551.29** | ✅ |
| Gross − deductions = net | reconciles | ✅ |

Three paid-leave days changed the day counts and **not** the money, which is
what paid leave means.

### The one that mattered most

Calculating the same run twice produced **the same net, not a smaller one** —
22,551.29 both times, with the loan installment and the advance recovery
unchanged, and exactly one installment marked as claimed afterwards.

This is the defect that would otherwise be invisible until somebody recalculated
a payroll in production: the first pass marks the installments it took, and a
naive second pass finds them taken, skips them, and pays the employee their own
recovery back. The run releases everything it previously claimed before
calculating again. Without that release the second payslip would have been
6,333.33 higher.

## 4. Reuse — the mandatory report (§77)

| Required concept | Existing object | Action |
|---|---|---|
| Employee | `pharma.hr_employees` (6 rows, referenced by invoices and delivery) | **EXTEND** — 13 nullable columns |
| Department | `pharma.all_departments` | **REUSE** — untouched |
| Designation | `pharma.hr_designation` | **REUSE** — untouched |
| Employee type | `hr_employees.emp_type` + V12 CHECK | **REUSE** — `employment_type` is a different question, not a duplicate |
| Employee photo | `emp_image` / `file_name` / `file_mime_type` | **REUSE** |
| Branch | `all_branch` | **REUSE** |
| Pharmacy / tenant | `all_organization` | **REUSE** |
| Login ↔ staff link | `acl_user.pid_employee` | **REUSE** |
| Permissions | `acl_module_info`, `pos_permissions`, `acl_tenant_module`, `acl_perm_template_line` | **REUSE** — MOD32–36 registered the V64 way |
| Accounts role | `acl_group` ROLE04 | **REUSE** — it was already there and held nothing |
| Document numbering | `sequence_tracker` + `DocumentNumberService` | **REUSE** — payroll numbers `PR…`, vouchers `SP…` |
| Audit stamps | `BaseEntity` + `AuditingEntityListener` | **REUSE** on all 14 new tables |
| Money arithmetic convention | `PurchaseCalculator` / `SalesPriceCalculator` | **REUSE** — `PayrollCalculator` is the same shape, 2 dp HALF_UP |
| Responsive pattern | QuickSales breakpoints and bottom bar | **REUSE** — replicated, not redesigned |
| Attendance, leave, payroll, advance, loan, separation, salary structure | — nothing existed | **CREATE** |

**Not created, deliberately:** `hr_department` (would split the workforce across
two lists), a second employee table, a second permission system, a second
employee CRUD path.

## 5. Responsive verification

Measured in the browser, not judged from a screenshot.

| Width | Bottom bar | Panes | Table | Horizontal overflow |
|---|---|---|---|---|
| 375 (mobile) | `position: fixed`, bottom edge at exactly 812 of an 812 viewport, 76px page padding clearing a 49.8px bar | one shown, others `display: none` | `display: block` cards; lead cell's `::before` suppressed; actions a full-width row | **−5px** (none) |
| 768 (tablet) | `display: none` | all shown as sections | `table-row`; advisory columns `display: none` | **0** |
| desktop | none | all shown | full table | 0 |

## 6. Test artefacts

Every row the verification created was removed and the removal verified: 0
employees named ZZHR, 0 structures, 0 payroll runs, 0 payslips, 0 attendance
rows, 0 leave requests, 0 advances, 0 loans. **The 6 real employees are intact.**

The two seeded leave-type quotas the script set (Casual 10, Unpaid 30) were
reverted to NULL, because the entitlement is the pharmacy's decision and a
number left behind by a test would look like one somebody made.

## 7. Known limitations, stated rather than hidden

1. **Five policy questions are deliberately unanswered** — tax, overtime rate,
   leave entitlement, weekly-off/holiday calendar, final settlement. Each is
   configurable and each is *visible in the UI as a choice*, not as a blank
   field. See `02-hr-module-srs.md` §7.
2. **Interest on a staff loan is recorded and never applied.**
3. **No journal is written.** Advance payment, loan disbursement and salary
   payment are operational records. `post()` is where Accounts will attach.
4. **No payslip PDF.** The payslip is on screen with its full arithmetic;
   printing would reuse `InvoiceDocumentService`, and has not been built.
5. **No attendance device integration.** The `DEVICE` source exists and device
   rows are protected from editing, but nothing imports them.
6. **`ix_hr_employee_code` is not UNIQUE** pending a check that existing
   `emp_id` values are unique per branch — see `16-…` §5, D-01.

## 8. Gate

The §32 completion gate is met: migrations apply, backend works, frontend works,
payroll works, permissions work, tenant and branch isolation hold, tests pass,
Flyway validates, and the existing application is verified unchanged.

**Accounts (Phases 8–17) has not been started.** Its design documents are the
next deliverable; no accounting table, entity, service or endpoint exists yet.
That is stated here rather than implied, because the gate exists to stop the
second module beginning before the first is genuinely finished — and it also
exists to stop the first being reported as finished when it is not.
