# 29 — Overtime Claims and Salary Increments

Built and verified against the running application on 2026-09-10.
Migration **V81**, applied. 55 checks in `scratchpad/verify-hr-ot-increment.mjs`,
all passing; 313 unit tests, 33 report checks and every HR suite still green.

---

## Overtime: the double count this design exists to avoid

`PayrollCalculator` already values overtime. It reads
`HR_ATTENDANCE.OVERTIME_MINUTES` and multiplies by the structure's rate. **A
claim table that also fed payroll would pay every approved hour twice.**

So the claim does not feed payroll. It feeds the register:

```
claim (SUBMITTED) → approve → HR_ATTENDANCE.OVERTIME_MINUTES += approved minutes
                                          ↓
                            payroll reads exactly what it always read
```

This is the same shape leave already uses: an approved leave request writes
LEAVE attendance rows and lets payroll count days from the register, rather than
telling payroll about itself.

Three consequences follow, and each is enforced:

| Rule | Why | Refusal |
|---|---|---|
| A claim can only be approved on a day that is **on the register** | there is nothing to write the minutes to, and creating a row would be inventing attendance | `HR_OT_NO_ATTENDANCE` |
| Approval **adds** to the day rather than replacing it | a register entry and a claim are two people saying something about the same day; the claim is not automatically the whole story | — |
| Cancelling subtracts **exactly what it added**, found through `PID_ATTENDANCE` | zeroing the day would discard overtime recorded for another reason | — |

Verified end to end: a day carrying 30 minutes entered directly, plus a claim of
120 approved at 90, reads **120** on the register; cancelling the claim takes it
back to **30**, not to zero.

### What the branch decides, and what the pay structure decides

`HR_WORKING_POLICY.OVERTIME_ALLOWED` says whether overtime may be claimed here.
`OVERTIME_MIN_MINUTES` says the least that counts. **What an hour is worth stays
on the salary structure** — so a claim can be approved at a branch whose
structures value overtime at nothing, and the payslip will say
`recorded only`. A branch says *whether*; a structure says *how much*.

A day is judged by **the policy that governed it**, not the one in force now:
turning overtime on today does not make last week claimable. Verified.

With **no policy at all** nothing is restricted — the same compatibility
guarantee the rest of HR follows. Nothing is defaulted on the pharmacy's behalf.

### Lifecycle

`DRAFT → SUBMITTED → APPROVED | REJECTED`, and `CANCELLED` from any live state.
`uq_hr_ot_live` allows one live claim per employee per date; a rejected or
cancelled one frees the day, so a refused claim can be filed again with a better
reason.

An approver may grant **fewer** minutes than were claimed and not more. Both
figures are kept — `CLAIMED_MINUTES` and `APPROVED_MINUTES` — because an
approver who cuts the hours has made a decision worth being able to see
afterwards. Approving zero is a rejection in disguise and is refused as one.

---

## Increments: the figure that must not move

**The three money columns are frozen when the increment is approved, and read
back unchanged when it is applied.**

A 10% increment signed off in March against 20,000 is 2,000. If the basic salary
is edited to 25,000 in April, applying that increment must still be the 2,000
somebody agreed to. Recomputing at apply time would quietly change a decision
after it was made.

And when the salary has moved underneath an approved increment, applying is
**refused** rather than guessed at:

```
HR_INCREMENT_SALARY_MOVED
  "Abdul Malek is now on 21777.00, not the 21000.00 this increment was approved
   against. The salary changed after the decision — cancel this increment and
   propose one against the figure that is actually in force."
```

Writing the frozen `NEW_SALARY` over a figure that changed for some other reason
would discard that change silently. Refusing puts the question back to a person
who can see both. Verified: the salary was moved by 777 while an increment sat
approved, apply was refused, and the frozen figures were still 21,000 → 23,100.

### These rows are the salary history

`HR_EMPLOYEES.BASIC_SALARY` holds only the current figure. `HR_INCREMENT` is the
record of how it got there, which is why an applied increment is never deleted —
`HR_INCREMENT_APPLIED` refuses the cancel, and the way to undo one is a further
increment that says so.

### What applying does not touch

It changes what the employee is paid **from now on**. It does not reach into a
payroll run already calculated, and certainly not a POSTED one: a payslip is
what was decided at the time it was decided. An increment dated inside an open
period takes effect the next time that run is calculated.

Applying is also refused before the effective date (`HR_INCREMENT_NOT_DUE`) — an
increment effective in October is not October's salary in September.

### Lifecycle

`DRAFT → SUBMITTED → APPROVED → APPLIED`, with `REJECTED` and `CANCELLED`.
`uq_hr_increment_live` allows one live increment per employee per effective date.

A **promotion** may carry a new designation and grade; applying moves them with
the salary. An annual increment usually moves neither, so both are optional.

---

## Where it lives on screen

Per the brief's rule — *if an existing page already performs the same business
function, extend it* — neither got a page of its own.

| | Screen | Why there |
|---|---|---|
| Overtime | **Attendance → Overtime** tab | overtime is a fact about a day in the register, and approving a claim writes onto that day. A separate page would put the claim somewhere other than the thing it changes |
| Increments | **Employees → Increments** tab | an increment changes a person's record, and the figure it moves is set two tabs away on the same page |

The Employees tab also carries an **Approved and due to be applied** card:
applying is a deliberate step, not something that happens on a date passing.

The increment form previews what the proposal is worth today, from the
employee's HR profile rather than the staff list — the list is the shared
`/api/employees` endpoint, and putting pay on it would put pay on a response
every screen in the application reads.

---

## API

Branch comes from the session; no request carries one.

### `/api/v1/hr/overtime` — gated on **Attendance (MOD33)**

| Method | Path | Right |
|---|---|---|
| GET | `/` | View |
| GET | `/pending` | View |
| POST | `/` | Create |
| POST | `/{id}/submit` | Create |
| POST | `/{id}/approve` | **Approve** |
| POST | `/{id}/reject` | **Approve** |
| POST | `/{id}/cancel` | Delete |

### `/api/v1/hr/increments` — gated on **Employees (MOD32)**

| Method | Path | Right |
|---|---|---|
| GET | `/`, `/pending`, `/due`, `/employee/{id}` | View |
| POST | `/` · PUT `/{id}` | Create · Edit |
| POST | `/{id}/submit` | Create |
| POST | `/{id}/approve`, `/{id}/reject`, `/{id}/apply` | **Approve** |
| POST | `/{id}/cancel` | Delete |

Claiming needs only Create, so somebody can claim their own overtime or have an
increment proposed for them without being able to grant it. Under V77 that means
ROLE04 can file and cannot decide, with no code change.

---

## Reports

Two added, taking the HR catalogue to **22** and the whole catalogue to **40**.

| Report | Id | What it reads |
|---|---|---|
| Overtime Claims | `hr-overtime-claims` | the claim workflow — who asked, what was granted |
| Increment Register | `hr-increment` | the salary history, frozen figures as approved |

`hr-overtime-claims` is deliberately **separate** from the existing `hr-overtime`,
which reports the register. The two differ wherever overtime was entered directly
on a day rather than claimed for it, and showing that difference is the point;
reconciling it away would hide exactly what somebody opens the report to find.

Both were executed against live rows, not merely registered — native SQL is not
checked at compile time. `From + Rise = To` was asserted on every increment row.

Both declare a **status** filter, and it works over the API — `2 submitted of 4`
was asserted, not assumed. The Reports *screen* has no status control yet, so
today it is reachable only by query parameter. That is not new: several existing
HR reports declare `status` the same way. Adding the control is one input on
`Reports.jsx` and would serve all of them at once; it was left alone here
because it belongs to that screen rather than to this module.

---

## Schema — V81

`HR_OVERTIME_REQUEST`

| Column | Note |
|---|---|
| `CLAIMED_MINUTES` / `APPROVED_MINUTES` | minutes, never hours — the unit the register uses, so the approved figure lands without a conversion |
| `PID_ATTENDANCE` | the row the minutes went to, so cancellation takes back exactly what it added |
| `APPLIED_AT` | when the register was written |
| `uq_hr_ot_live` | one live claim per employee per date |

`HR_INCREMENT`

| Column | Note |
|---|---|
| `PCT_VALUE` / `FIXED_AMOUNT` | the proposal |
| `PREVIOUS_SALARY` / `INCREMENT_AMOUNT` / `NEW_SALARY` | the decision, frozen at approval |
| `PID_NEW_DESIGNATION` / `NEW_GRADE` | a promotion may move these |
| `uq_hr_increment_live` | one live increment per employee per effective date |

Both tables are additive. Nothing was dropped, no column was made NOT NULL on
populated data, and no applied migration was edited.

---

## Still open

**P-02 overtime rate** remains a *BUSINESS POLICY CONFIRMATION REQUIRED*. This
work decides *whether* overtime is claimable and *how it is approved*; the
multiplier that turns minutes into money is still a number nobody has supplied,
and a structure with none records the hours and pays nothing rather than
inventing a rate.

Nothing here decides an increment policy either — no automatic annual cycle, no
default percentage, no eligibility rule. Every increment is a decision somebody
makes and signs.

## A note on dev-database litter

Two earlier verification suites leave rows behind: `verify-hr-policy.mjs` leaves
its working policy, holiday and leave types, and `verify-hr.mjs` leaves an
approved leave request with its three LEAVE attendance rows plus three marked
days for one employee (287 rows against a 281-row seed). Both suites pass; the
leftovers caused two false failures during this work before they were traced.
Neither is in this module's scope to fix, but the next reader should know the
register is not the pure seed.
