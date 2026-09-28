# 31 — Conveyance and Expense Claims

Built and verified against the running application on 2026-09-10.
Migrations **V83** and **V84**, both applied. A live suite in
`scratchpad/verify-hr-expense.mjs`.

---

## The decision this module turns on

**A reimbursement is not pay.** Somebody who takes a rickshaw to the wholesaler
and is given the fare back has not earned anything — the money is theirs being
returned.

So an approved claim is **paid on its own**: a date, a mode, a reference,
exactly as an advance is disbursed. It never appears on a payslip, and there is
no hook from this module into `HrPayrollServiceImpl` anywhere.

That is not squeamishness about categories. If a reimbursement were added to
`GROSS_SALARY` it would sit inside the taxable base and — since V82 — inside the
**provident fund base** whenever a scheme is taken on gross. The pharmacy would
deduct PF from a rickshaw fare and hand back less than was spent.

Measured, not asserted. The suite runs payroll, pays a claim, and runs payroll
again:

```
before the reimbursement:  gross 31,700   net 30,650
after 350 reimbursed:      gross 31,700   net 30,650
payslip lines:             BASIC, HRA, MEDICAL, TRANSPORT, PF   (no expense line)
hr_payslip_component rows mentioning expense/conveyance/reimburse:  0
```

### Deliberately not the shape overtime took

| | Overtime (V81) | Expense claim (V83) |
|---|---|---|
| Is it work? | yes | no |
| Reaches the payslip? | **yes** — approval writes minutes onto the attendance row payroll already reads | **no** — paid on its own |
| Why | paying overtime twice was the risk | taxing a fare was the risk |

Two claims modules, two opposite answers, each because of what the money *is*.

---

## No limit is invented

`MAX_PER_CLAIM` and `MAX_PER_DAY` are nullable with no default. What a day's
conveyance is worth at this pharmacy is a business rule, and a figure shipped in
the box would be a number nobody chose being enforced against people's own
money — the same answer P-02, P-07 and P-08 got.

**A null cap is not a cap of nought.** Verified: with nothing set, a claim of
99,999 for one rickshaw ride is accepted without complaint.

Once a cap *is* set it is enforced, and the daily one holds **across claims**:

| Test | Result |
|---|---|
| one expense of 250 against a 200 per-expense cap | `HR_EXPENSE_OVER_CAP` |
| two expenses of 180 on one day, 300 daily cap | `HR_EXPENSE_OVER_DAILY_CAP` |
| 200 on one claim, then 150 on a **second** claim for the same day | `HR_EXPENSE_OVER_DAILY_CAP` |

That last one is what makes a daily cap mean anything — without it the limit is
defeated by filing twice.

Caps are checked **when the claim is filed**, not at approval, so the person who
spent the money hears about it while they can still explain it. And a cap can be
**cleared** again: sending null removes it, because a limit set by mistake that
could never be undone is worse than no limit at all.

The five seeded type names — Conveyance, Meal, Medical, Phone, Other — are
vocabulary rather than policy, seeded the way V73 seeded leave type names while
leaving every quota null. They can be renamed or retired.

---

## Header and lines

A week of deliveries is six journeys on six dates. Filing six claims to be
approved six times is not how anybody works, and one row with a single amount
and a paragraph of explanation cannot be checked against anything.

**Every total is derived from the lines.** Nothing on the screen or in the API
lets somebody type a total — a total that disagrees with its own detail is worse
than no total. The claim's `periodFrom`/`periodTo` come from the line dates too.

**Approval is per line.** An approver who allows the fares and trims the dinner
has made two decisions, and both are visible where they belong:

```
claimed:  120 + 80 + 250  = 450
allowed:  120 + 80 + 150  = 350     ← the header total is the sum of what was allowed
```

More cannot be allowed than was claimed (`HR_EXPENSE_OVER_CLAIM`), and allowing
nothing at all is refused as a rejection in disguise (`HR_EXPENSE_ZERO_APPROVAL`).

---

## Receipts

Reuses `ImageStorageService` with a new `EXPENSE_RECEIPT` bucket rather than
storing files itself. The extension whitelist, the MIME-and-extension agreement,
the magic-byte check and the path re-check against the configured root all apply
to a till slip exactly as they do to a staff photograph, and a second
implementation would be a second place for one of them to be forgotten.

Stored under `${UPLOAD_ROOT:uploads}/expense_receipt`, configurable by
`UPLOAD_EXPENSE_RECEIPT_DIR`. **The row holds the public URL, never an absolute
machine path** — the root moves between machines and a path baked into a row
does not.

A receipt can only be attached **while the claim is a draft**. One swapped after
approval would change the evidence behind a decision already made.

`REQUIRES_RECEIPT` is per type and defaults to 0. Where it is on, submitting
without one is refused — but the claim can still be **saved as a draft**, so the
refusal lands where somebody can still do something about it.

---

## Its own module, unlike PF and gratuity

| Module | Sits under | Why |
|---|---|---|
| Provident fund | Payroll (MOD36) | payroll money, administered by the people who run payroll |
| Gratuity | Employees (MOD32) | part of somebody leaving, where separations already are |
| **Expense claims** | **MOD37, its own** | ordinary staff file these for themselves |

A pharmacy has to be able to let everybody claim their fares without letting
everybody see payroll. That needs a right of its own, so V83 registers MOD37
the full way V77 registered the other five: the module, the role grants, the
**plan ceiling**, and the onboarding templates. Miss the ceiling and the screen
is invisible however the roles are set, which reads to a manager as the feature
not having shipped.

ROLE01 and ROLE02 get everything. ROLE04 files and cannot decide — no Delete, no
Approve, the same separation V77 drew across HR. Whether the rest of the staff
may claim is a decision for the pharmacy on the RBAC screen, not one a migration
makes for them.

---

## API — `/api/v1/hr/expenses`, gated on MOD37

| Method | Path | Right |
|---|---|---|
| GET | `/types` | View |
| POST · PUT · DELETE | `/types`, `/types/{id}` | Create · Edit · Delete |
| GET | `/`, `/pending`, `/{id}` | View |
| POST · PUT | `/` · `/{id}` | Create · Edit |
| POST | `/{id}/submit` | Create |
| POST | `/{id}/approve`, `/{id}/reject`, `/{id}/pay` | **Approve** |
| POST | `/{id}/cancel` | Delete |
| POST · DELETE | `/lines/{lineId}/receipt` | Edit |

Filing needs only Create, so somebody can claim their own fares without being
able to allow them.

---

## Where it lives on screen

A **new page** at `/expenses`, not a tab on an existing one — nothing else in
the application does expense claims, and it has its own module and its own
route. Two panes: **Claims** and **Kinds**.

V84 exists only to give MOD37 its own sidebar icon: V83 registered it with
`advance`, the glyph Advance & Loan already uses, and two adjacent items in the
same group drawn identically is a menu people misread. A separate migration
rather than an edit, because V83 had been applied.

---

## Report

One added, taking the HR catalogue to **25** and the whole catalogue to **43**.
Doc [28](28-report-completeness-matrix.md) listed it as impossible because the
tables did not exist.

| Report | Id |
|---|---|
| Expense & Conveyance | `hr-expense` |

**One row per expense, not per claim** — the question it is opened to answer is
what the pharmacy spends on conveyance, and a claim total mixes the fares with
the dinner. The approved column is **blank until a claim is decided** rather
than repeating the claimed figure: a claim awaiting a decision has not been
allowed anything, and showing the two columns equal would say it had.

---

## Schema — V83

| Table | Holds |
|---|---|
| `HR_EXPENSE_TYPE` | what can be claimed for, with every limit null |
| `HR_EXPENSE_CLAIM` | the header, its totals derived from the lines, and the payment |
| `HR_EXPENSE_CLAIM_LINE` | one expense: date, type, places, amount, allowed amount, receipt |

Entirely additive. No existing table was touched, nothing was dropped, and no
applied migration was edited.

---

## Still open

Nothing here is blocked on a policy answer — the caps are optional by design and
absent by default. What this module does **not** attempt:

* **No accounting entry.** A reimbursement is cash going out, and where that
  lands in a ledger is an Accounts question this build has no module to answer.
  The payment is recorded on the claim and nowhere else, exactly as an advance
  disbursement is.
* **No per-person or per-month budget.** Only per-expense and per-day caps by
  type. A monthly allowance per employee is a different policy and nobody has
  asked for one.
* **No mileage rate.** A distance-times-rate conveyance calculation would need a
  rate, and that is P-02-shaped: policy this application does not get to choose.
  Amounts are entered.
