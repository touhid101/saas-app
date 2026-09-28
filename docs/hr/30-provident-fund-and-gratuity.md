# 30 — Provident Fund and Gratuity

Built and verified against the running application on 2026-09-10.
Migration **V82**, applied. 322 unit tests (9 new), and a live suite in
`scratchpad/verify-hr-pf-gratuity.mjs`.

---

## The one decision behind both

P-07 (PF contribution rates) and P-08 (gratuity formula and eligibility) are
marked *BUSINESS / TAX POLICY CONFIRMATION REQUIRED*. Employee share, employer
share, the wage a percentage applies to, a ceiling, days of pay per year of
service, the divisor that turns a monthly wage into a daily one, the qualifying
period — every one of those is policy, and in places statute.

**So this build makes them enterable and ships them empty.** The same answer
P-04 got for the weekly off. Every rate column is nullable with no default, and

> **a null rate does not mean nought per cent — it means nobody has decided.**

A branch with no scheme and no policy behaves exactly as this application
behaved yesterday: payroll deducts no PF, and no gratuity is computed for
anybody. Seeding 8% and 30 days because they are common would put a number on a
payslip and in a settlement that no accountant chose, and it would be believed.

The distinction is carried through to the API, not just the database:

| | Says | Not |
|---|---|---|
| `GET /pf/scheme` with none set | `null` | a scheme of zeros |
| A scheme with blank rates | `rateSet: false`, `deducting: false` | `employeePct: 0` |
| `GET /gratuity/estimate/{id}` with no policy | `configured: false` plus a sentence saying what is unanswered | `gratuityAmount: 0` |
| Creating an award with no policy | `409 HR_GRATUITY_NOT_CONFIGURED` | an award of nought |

---

## Provident fund

### The double deduction this had to avoid

A PF deduction **can already be modelled** as an ordinary DEDUCTION component on
a salary structure, and the dev structure does exactly that at 5% of basic. Drop
a scheme on top and every such payslip deducts PF twice.

`HR_SALARY_COMPONENT.IS_PF` marks a component **as** the PF deduction. When a
scheme is in force the calculator **skips** it and uses the scheme; with no
scheme it behaves exactly as before. Measured, not asserted:

```
before marking:  2 PF lines — PF=1050 (component 5%) + PF=1680 (scheme 8%)
after marking:   1 PF line  — PF=1680
```

**Nothing is inferred from a component's name.** The scheme screen *points at*
deduction components whose names look like PF and are not marked — an advisory
for a person to act on, never something the calculator acts on. An unmarked
component keeps being deducted alongside the scheme, and there is a passing test
that says so, because that is the honest behaviour: the application will not
silently decide which of somebody's deductions is really PF.

### Where PF sits in the payslip

```
7  deduction components (a marked PF one skipped when a scheme is in force)
8  provident fund      ← new
9  tax (still 0)
10 advance, then loan recovery — each capped at what is left
```

PF is taken **before** the recoveries. That ordering is a decision: a recovery is
capped at what the month has left, so PF first means a tight month short-pays the
loan rather than the fund. Money owed to a fund is not the same kind of debt as
money owed back to the employer. Tested at a deliberately absurd 50% rate:
10,000 gross → 5,000 to the fund → a loan asking 8,000 gets 5,000, net 0.

**The employer's share is not a deduction.** It is computed, stored on the
payslip line and reported, and it never enters `DEDUCTION_TOTAL` or
`NET_SALARY` — it is the company's money, and adding it to a deduction total
would understate what somebody was paid. Verified on a live payslip:
`31,700 − 2,730 = 28,970`, with 2,100 of employer contribution beside it.

A **ceiling caps the wage, not the contribution**. Capping the contribution
instead would give a different answer whenever the two shares differ, and it is
the wage that statutes tend to cap.

### The ledger moves at POSTED, not at calculation

A run can be recalculated any number of times before it is posted, and a fund
whose balance moved on every recalculation would not be a fund. POSTED is where
this application already draws its immutability line, so that is where a
contribution becomes a fact.

`uq_hr_pf_ledger_contribution` makes a second contribution for the same run
impossible rather than merely unlikely — verified by posting twice.

Rates are **copied onto every ledger row**, not joined to the scheme: a statement
printed next year must show what was actually contributed, not what the rates
happen to be by then.

Amounts are **positive magnitudes and the type carries the sign** — a ledger
with negatives in some rows and positives in others is one nobody can total by
eye. A withdrawal is refused when the fund does not hold it, and a contribution
**cannot be typed by hand**: money in the fund that no payslip accounts for is
money nobody can explain.

---

## Gratuity

### Frozen, not accrued

Service years, the wage, the daily rate, the days and the amount are copied onto
the award at approval and never recomputed — the rule `HrIncrement` already
follows. Verified: an award approved at 72,692.31 on a wage of 21,000 stayed at
exactly that after the salary was raised to 26,000, while a fresh estimate moved
to the new figure.

**No monthly accrual is posted anywhere.** Accruing gratuity into a liability
account every month *is* an accounting policy, this build has no Accounts module
to post it to, and inventing the entry would be inventing the policy.

### The estimate writes nothing

`GET /gratuity/estimate/{id}` shows the whole working — raw service, countable
service after the part-year rule, the wage and where it came from, the daily
rate, the days, the amount — so the figure can be argued with rather than just
believed. Asserted that it saves nothing: 0 awards on the table afterwards.

### What is configurable, and why each one had to be

| Field | Why it is not a constant |
|---|---|
| `DAYS_PER_YEAR` | the whole formula |
| `DAYS_IN_MONTH` | **26 and 30 give materially different answers on the same salary** — a matter of contract or law, not arithmetic |
| `WAGE_BASIS` | basic or gross, and a gross-basis policy reads the last **posted** payslip rather than re-deriving one |
| `MIN_SERVICE_YEARS` | the qualifying period |
| `MAX_SERVICE_YEARS` | some schemes cap countable service |
| `PARTIAL_YEAR_RULE` | ignore / pro rata / round up — there is no defensible default |

The part-year rule is applied **before** the cap, deliberately: rounding up and
then capping is not the same as capping and then rounding, and the cap is a
ceiling on what is paid rather than on what was served.

### What it does not touch

`HR_SEPARATION.SETTLEMENT_AMOUNT` is untouched. **P-05 is still unanswered** —
what belongs in a final settlement is policy — so an approved award is shown
beside the separation as a fact and whoever settles it decides. Verified: paying
a gratuity award wrote no settlement anywhere.

A **paid** award cannot be cancelled: cancelling would not take the money back.

---

## Three bugs found on the way, all the same bug

Wiring this up ran into `DATA_INTEGRITY_VIOLATION` twice, and both turned out to
be the flush-order trap this codebase has now hit four times:

> **Hibernate orders a transaction's inserts before its deletes.** Clearing a
> collection and re-inserting rows that reuse a unique key inserts the new row
> while the old one is still there, and the index refuses it. The error reads as
> a duplicate and is really a flush-order problem.

| Where | Unique key | Symptom | Fixed by |
|---|---|---|---|
| Working policy (earlier) | `uq_hr_policy_current` | superseding a policy failed | `saveAndFlush` |
| PF scheme (this work) | `uq_hr_pf_scheme_current` | avoided by writing it the same way |
| **Salary structure components** | `uq_hr_component_code` | **editing a structure has never worked** when component codes are kept — which is every edit | `structureRepo.saveAndFlush(s)` after `clear()` |
| **Payroll recalculation** | `uq_hr_payroll_line` | **a second Calculate on the same run has never worked** | `payrollRepo.saveAndFlush(run)` after `clear()` |

The last two are pre-existing defects in code this work did not add, found
because PF made the test recalculate a run and re-save a structure. Both are
fixed, and both fixes carry the explanation in a comment at the site.

---

## Where it lives on screen

Neither got a page of its own — the brief's rule is to extend the page that
already performs the business function.

| | Screen | Why there |
|---|---|---|
| PF scheme, ledger, statements | **Payroll → Fund** tab | PF is deducted by a payroll run and its rates sit beside the structures that shape a payslip |
| **Is PF** tick | **Payroll → Structures**, on each deduction row | it is a property of the component, and it is where somebody editing the structure will see it |
| Gratuity policy, estimates, awards | **Employees → Gratuity** tab | gratuity is part of somebody leaving, and Leavers is already there |

The Fund tab shows a `Blocked` panel when no scheme exists, saying in words that
the rates are business policy and nothing here will choose them. The Gratuity tab
does the same. A scheme that exists but takes nothing says so rather than
pretending the fund is running, and the "possible double deduction" warning names
the components at risk.

---

## API

Branch comes from the session; no request carries one.

### `/api/v1/hr/pf` — gated on **Payroll (MOD36)**

| Method | Path | Right |
|---|---|---|
| GET | `/scheme`, `/scheme/history`, `/ledger`, `/statement/{id}` | View |
| POST | `/scheme` | Edit |
| POST | `/ledger` | **Approve** |

### `/api/v1/hr/gratuity` — gated on **Employees (MOD32)**

| Method | Path | Right |
|---|---|---|
| GET | `/policy`, `/policy/history`, `/estimate/{id}`, `/`, `/pending` | View |
| POST | `/policy` | Edit |
| POST | `/` · `/{id}/submit` | Create |
| POST | `/{id}/approve`, `/{id}/reject`, `/{id}/pay` | **Approve** |
| POST | `/{id}/cancel` | Delete |

Neither gets a module of its own. PF is payroll money administered by the people
who run payroll; gratuity is part of leaving, which is already on Employees. A
new module would mean a grant migration and a row on the RBAC screen nobody
would think to tick.

---

## Reports

Two added, taking the HR catalogue to **24** and the whole catalogue to **42**.
Both were listed in [28](28-report-completeness-matrix.md) as impossible because
the tables did not exist.

| Report | Id | Note |
|---|---|---|
| Provident Fund | `hr-pf` | movements, with each person's whole-fund balance |
| Gratuity Awards | `hr-gratuity` | the frozen working, read rather than recomputed |

Both were executed against live rows created and then removed by the
verification — a report checked only against an empty table proves the SQL
parses and nothing more.

---

## Schema — V82

Four tables, two columns on `HR_PAYROLL_LINE`, one on `HR_SALARY_COMPONENT`.
All additive: nothing dropped, no column made NOT NULL on populated data, no
applied migration edited. Every payslip already posted still reads as it did,
because `PF_EMPLOYEE_AMOUNT` and `PF_EMPLOYER_AMOUNT` default to zero and they
were all computed with no scheme.

| Table | Holds |
|---|---|
| `HR_PF_SCHEME` | the rates, effective-dated per branch, one open-ended row |
| `HR_PF_LEDGER` | every movement, with the rates that applied copied on |
| `HR_GRATUITY_POLICY` | the formula, effective-dated per branch |
| `HR_GRATUITY_AWARD` | the frozen working and the decision |

---

## Still open

**P-07 and P-08 are not closed by this work — they are made answerable.** The
machinery exists and refuses to run until somebody supplies the numbers. That is
the difference between "not implemented" and "not decided", and the screens now
say which one applies.

**P-05 (final settlement) is unchanged.** Gratuity is now a figure that can sit
beside a settlement; what belongs in the settlement itself is still a question
nobody here has answered.
