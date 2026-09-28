# 32 — Approval Workflow and the HR Calendar

Built and verified against the running application on 2026-09-10.
Migrations **V85** and **V86**, both applied. 43 checks in
`scratchpad/verify-hr-approvals.mjs`, all passing.

---

## Part one — multi-level approval

### Added in front of what already works, never in place of it

Every HR object already had a decision: leave, overtime, advance, loan,
increment, gratuity and expense each carry `SUBMITTED → APPROVED/REJECTED` with
`DECIDED_BY`, gated by the Approve right on their module. What was missing was
**levels** — "leave up to three days is the manager's, longer than that needs
the branch administrator too" could not be expressed anywhere.

So the chain sits **beside** those workflows. Every service kept every line of
its `approve()`; it asks one question first and returns early when the answer is
"not yet":

```java
if (approvals.decide(HrScope.WF_LEAVE, id, true, remarks)
        == HrApprovalService.Step.PENDING_NEXT) {
    return map(r, e, t);          // still SUBMITTED, balance untouched
}
// ... everything the method already did, unchanged ...
```

**With no chain configured, `decide` returns `NO_FLOW` and nothing is written.**
That is how every branch starts and how they stay until somebody configures one:
one Approve right, one decision, exactly as before. The same compatibility
contract the working policy, the PF scheme and the gratuity formula follow —
and the first thing the verification checks, before any chain exists:

```
PASS  ONE SIGNATURE STILL APPROVES IT — nothing changed  — APPROVED
PASS    ...and no chain row was written
```

### The hold is real, and measured

With a two-level chain on leave over three days:

```
PASS  THE FIRST SIGNATURE DOES NOT APPROVE IT  — SUBMITTED after HTTP 200
PASS    ...and the balance has not moved either  — 0.00 then 0.00
PASS    ...and no leave attendance was written  — 0 rows
PASS    ...the chain has moved to level 2  — Level 2 of 2
PASS    ...and the first signature is on the trail  — 1:APPROVED
```

That the **side effects** stay unwritten is the part that matters. An
intermediate signature that moved the leave balance, or wrote the loan schedule,
or froze an increment's figures, would be a half-approval the business has no
word for.

### A level narrows; it never widens

The module's Approve right is checked by the calling service *before* the chain
is consulted, so a chain can only take authority away:

```
PASS  A LEVEL NARROWS: signing out of turn is refused  — HR_APPROVAL_WRONG_LEVEL
PASS    ...and the message says whose turn it is
        — "Level 1 of 2 is for Branch administrator to sign."
PASS    ...and the request is untouched  — SUBMITTED
```

A level names a **role**, or the sentinel `MANAGER` meaning the employee's own
manager, read from `HR_EMPLOYEES.PID_MANAGER` — a column V78 added and nothing
had used. A role outlives a person; a named approver does not, and a chain that
breaks when somebody resigns is a chain nobody trusts.

Where an employee has **no manager on record**, a MANAGER level is *not* silently
skipped. It refuses and says so: quietly dropping a signature is exactly the
failure a chain exists to prevent.

### The chain is frozen when it starts

`TOTAL_LEVELS`, `FLOW_NAME` and `ROUTED_VALUE` are copied onto the request the
moment it begins. Editing a flow does not change a request already travelling
through it — the rule an approved increment and a posted payslip both follow.
Retiring a flow deactivates it rather than deleting it, so the trail can still
say which chain a request followed.

### Routing

`MIN_VALUE` is the floor a chain applies from, measured in **whatever the
workflow measures itself in** — days for leave, minutes for overtime, money for
an advance, a claim, an increment or a gratuity award. Only the calling service
knows which, so only the calling service supplies the figure. A null floor means
"anything", and is the fallback when no threshold matches.

Seven workflows can be chained, each with its own vocabulary rather than the
module code — increments and gratuity awards both sit under Employees, and a
pharmacy that wants two signatures on a pay rise does not thereby want two on a
leaving payment:

`LEAVE` · `OVERTIME` · `ADVANCE` · `LOAN` · `EXPENSE` · `INCREMENT` · `GRATUITY`

**Payroll is deliberately absent.** A run already has five signatures of its own
— calculate, review, approve, post, pay — and a second chain around them would
give it two answers to "who signs next".

### The inbox

`GET /api/v1/hr/approvals/inbox` answers *"what is waiting on me"* across every
HR workflow. It merges two sources:

* objects in a **chain** whose current level this reader can sign, and
* objects merely **submitted** with no chain at all — the state every module was
  in before flows existed, and still is wherever none is configured.

Merging them is the point. "What needs me" is one question, and answering it out
of six screens is why it went unanswered. A workflow whose module Approve right
the reader lacks is left out entirely, so an inbox never advertises a decision
somebody cannot make. Rows waiting on *somebody else* are shown separately and
marked `mine: false` — because "why has my leave not been approved" is a
question people ask, and the answer belongs on the screen.

### Nothing is seeded

V85 creates no flow for any branch. Seeding a plausible chain would be a
migration deciding who signs what, which is the pharmacy's decision.

---

## Part two — the HR calendar

### A view, not a table

There is **no migration** behind the calendar and there should not be. It is
holidays, weekends, approved leave, birthdays, service anniversaries, probation
ending and increments taking effect — every one of which is already a row
somewhere. Copying them into a calendar table would create a second copy to keep
in step, and the first time somebody cancelled leave the two would disagree.

Verified: the calendar wrote nothing.

### Built on the working calendar, not beside it

Whether a day is worked stays `HrCalendarService`'s question — the one the
payroll run asks. `HrCalendarViewService` asks it and hangs events off the
answer, so the calendar and payroll cannot disagree about what a working day is.

`GET /api/v1/hr/attendance/hr-calendar?from=&to=&employeeId=`

| Event | Source | Shown when |
|---|---|---|
| `HOLIDAY` | `HR_HOLIDAY` | always |
| `LEAVE` | approved `HR_LEAVE_REQUEST`, expanded per day | one row per person per day away |
| `BIRTHDAY` | `DATE_OF_BIRTH` | only where one was entered |
| `ANNIVERSARY` | `HIRE_DATE` | from the first completed year |
| `CONFIRMATION` | `HIRE_DATE + PROBATION_MONTHS` | only where **both** are set and the person is not yet confirmed |
| `INCREMENT` | approved `HR_INCREMENT` | on its effective date |

**Nothing fills a blank with a plausible default.** Probation is not assumed to
be three months or six; no probation length on record means no event. A leap-day
birthday lands on the 28th in a common year rather than vanishing.

Leave is expanded per **calendar** day, weekends included: somebody on leave
across a weekend is still away on the Saturday, and skipping it would read as
their having come back.

A request covers at most 400 days, so one call cannot walk a decade.

---

## Where it lives on screen

| | Screen | Why there |
|---|---|---|
| Inbox and chains | **Approvals**, a new page at `/approvals` | nothing existing does this, and it spans every module |
| HR calendar | **Attendance → Calendar** tab | it is the same working calendar the register is judged by; a second screen would be a second answer to "is this a working day" |

The calendar is a month grid — seven columns from 768px up, stacked below, since
a seven-column grid on a phone gives each day about forty pixels and nothing
legible fits in it. One accent colour per kind of event, so a month reads at a
glance without a legend.

---

## API and permissions

**MOD38 Approvals** (V86) is the module: **View** opens the screen and the
inbox, **Edit** configures who signs what. Who signs is pay policy, and the
person who prepares payroll is not the person who sets the rules it runs under —
so ROLE04 gets View and no Edit, the separation V77 drew across HR.

The inbox narrows again on top of that: an entry appears only where the reader
holds the Approve right on *that* module. View here shows somebody their own
queue, never everybody's.

| Method | Path | Right |
|---|---|---|
| GET | `/workflows`, `/flows`, `/inbox`, `/chain/{workflow}/{refId}` | View |
| POST · PUT · DELETE | `/flows`, `/flows/{id}` | **Edit** |

The calendar is on Attendance (MOD33) View, where the working calendar already
was.

---

## Schema — V85

| Table | Holds |
|---|---|
| `HR_APPROVAL_FLOW` | a chain, per branch, per workflow, per threshold |
| `HR_APPROVAL_FLOW_LEVEL` | one signature: a role, or MANAGER |
| `HR_APPROVAL_REQUEST` | one object travelling through a chain |
| `HR_APPROVAL_ACTION` | every level's decision, kept whatever followed |

Entirely additive. No existing table was touched, nothing dropped, no applied
migration edited. The target of a chain is a workflow code and an id rather than
a foreign key — it is a different table each time, and a polymorphic foreign key
is a constraint the database could not check anyway.

Every level's decision is kept, including approvals given **before** a rejection:
"approved by the manager and refused by the administrator" is a different story
from "refused", and only a trail can tell them apart.

---

## Two things worth knowing

**The suites now tolerate real data.** Partway through this work the dev branch
acquired a PF scheme and an expense claim created through the UI at 08:53 and
09:08. Two verification suites failed because they asserted a virgin branch.
They were wrong, not the data: `verify-hr-pf-gratuity.mjs` now captures the live
scheme, supersedes it for the length of the test and reopens it at the end, and
`verify-hr-expense.mjs` scopes its cleanup by purpose and restores every expense
type's caps to exactly what it found. **Neither user row was deleted.**

**Still not built:** the old-value/new-value audit trail (gap analysis item 21),
the employee self-service portal (18), and HR KPI tiles on the main dashboard
(20). Accounts — phases 8 to 17 of the original brief — has not been started;
no accounting table, entity, service or endpoint exists.
