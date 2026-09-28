# 01 — HR Gap Analysis

**As at 2026-09-09.** Status is what the code does, verified against the running
application — not what was intended.

Legend: ✅ built and verified · 🟡 partly built · ❌ not started

---

## Phase status

| Phase | Requirement | Status | Where |
|---|---|---|---|
| 0 | Existing system analysis | ✅ | this folder + `docs/accounts-hr/00` |
| 1 | Organisation / branch / department / designation | ✅ **reused unchanged** | `all_organization`, `all_branch`, `all_departments`, `hr_designation` |
| 2 | Working hour / shift / weekend / holiday policy | ✅ **built and verified** | V79 + entities, calendar service, 12 endpoints, Policy pane. **Closes P-04** |
| 3 | Employee master | ✅ **complete** | V73 +13, V78 +14. All 29 columns now have a form: Personal / Employment / Pay on the profile, photo in its header |
| 4 | Employee image upload | ✅ **verified, 22 checks** | `docs/hr/18-…` |
| 5 | Attendance | ✅ **complete** | register, marking, history, and the **correction workflow** — request, queue, approve, reject |
| 6 | Leave | ✅ | types, balances, requests, approval. **Day counting now excludes weekends and holidays** |
| 7 | Overtime **request/approval** | ✅ **built and verified** | V81. Approving writes minutes onto the attendance row payroll already reads, so an hour is never paid twice — `29-overtime-and-increment.md` |
| 8 | Salary structure | ✅ | structures, components, effective dating. V82 adds `IS_PF` so a scheme can replace a PF component rather than double it |
| 9 | PF | ✅ **built and verified** | V82. Scheme, ledger, statements. Contributions are written at POSTED. Rates ship empty — P-07 — `30-provident-fund-and-gratuity.md` |
| 10 | Gratuity | ✅ **built and verified** | V82. Policy, estimate, frozen award. Formula ships empty — P-08 |
| 11 | Increment | ✅ **built and verified** | V81. Money frozen at approval; applying refuses when the salary moved underneath |
| 12 | Employee advance | ✅ | request → approve → pay → recover |
| 13 | Employee loan | ✅ | + written repayment schedule |
| 14 | Conveyance / expense claim | ✅ **built and verified** | V83/V84. Header and lines, receipts, per-day caps. **Reimbursement never reaches a payslip** — `31-expense-and-conveyance.md` |
| 15 | Approval workflow | ✅ **built and verified** | V85/V86. Multi-level chains in front of seven workflows, plus one inbox across all of them. Nothing configured until somebody adds a chain — `32-approval-workflow-and-calendar.md` |
| 16 | HR calendar | ✅ **built and verified** | A view over rows that already exist — no migration. Holidays, leave, birthdays, anniversaries, probation, increments |
| 17 | Payroll | ✅ | DRAFT→CALCULATED→REVIEWED→APPROVED→POSTED→PAID, immutable after posting. Deliberately **not** routed through the approval chain — it has five signatures of its own |
| 18 | Employee self-service | ❌ | the image endpoint already permits self-edit; no portal |
| 19 | HR reports | ✅ **25 built and verified** | see `28-report-completeness-matrix.md` |
| 20 | Dashboard / KPI | ✅ | **closed 10 Sep 2026** — a People tab on `/dashboard` and pending-count badges in the sidebar, both from one summary read. See `33-ui-ux-review.md` §19 |
| 21 | Security / audit | 🟡 | permissions ✅, branch isolation ✅, `BaseEntity` stamps ✅. **No old-value/new-value audit table** |
| 22 | Testing | 🟡 | 313 unit + 85 HR e2e + 22 image + 31 report + 31 policy + 32 record/correction + 15 regression |
| 23 | Production migration validation | ✅ | `docs/accounts-hr/16-…`, extended by V78/V79 |

## What is genuinely missing, in order of cost

**1. Audit (Phase 21).** `BaseEntity` records who and when on every row, and
every decision carries `decided_by`/`decided_at`. What is missing is the
old-value/new-value trail the brief asks for — a salary changed from 20,000 to
24,000 currently leaves only the new figure. The approval chain's
`HR_APPROVAL_ACTION` is the nearest thing to it and covers decisions only, not
edits.

**2. Employee self-service (Phase 18).** No portal. The image endpoint already
permits self-edit, and the approvals inbox already narrows to what a reader may
act on, so the groundwork is there.

*(Phase 20, dashboard KPIs, was closed on 10 September 2026: `/dashboard` now
carries a People tab, and the sidebar badges what is waiting. Both read the one
HR summary, so a badge and a tile cannot disagree.)*

**3. Accounts — phases 8 to 17 of the original brief.** Not started. No
accounting table, entity, service or endpoint exists. Every module built so far
records its own money and stops short of a journal, and each says so where the
hook would go.

## Business / HR policy confirmation required

Unchanged from `docs/accounts-hr/02-hr-module-srs.md` §7, plus two the new phases add:

| # | Question | What ships |
|---|---|---|
| P-01 | Income tax on salary | always 0; taxable base tracked |
| P-02 | Overtime rate | null multiplier ⇒ recorded, shown in hours, not valued |
| P-03 | Leave entitlement | types seeded with no quota |
| P-04 | Weekly off and holidays | ✅ **answered by configuration.** A branch sets its own weekly off and holidays on the Attendance screen; with none set, leave counts every calendar day exactly as before. Still no government holidays seeded — they change yearly and are declared by gazette |
| P-05 | Final settlement | entered, never computed |
| P-06 | Loan interest | recorded, never applied |
| P-07 | **PF contribution rates** | ✅ **answerable by configuration** (V82). Employee %, employer %, ceiling and the wage are columns on HR_PF_SCHEME, all null until somebody decides. A null rate deducts nothing — it is not nought per cent |
| P-08 | **Gratuity formula and eligibility** | ✅ **answerable by configuration** (V82). Days per year, the days-in-a-month divisor, the wage basis, the qualifying period and the part-year rule are all columns, all empty. With none set the estimate answers 'not configured' rather than nought |

## Migrations

| V | Purpose | Applied |
|---|---|---|
| V73–V77 | employee extension, salary, leave, attendance, advance, loan, payroll, separation, modules | ✅ |
| V78 | employee photograph + personal/employment/financial columns | ✅ |
| V79 | shift, working policy, holiday, attendance-correction columns | ✅ |
| V80 | what an attendance correction is proposing | ✅ |

All additive. No `DROP`, no `TRUNCATE`, no change to any applied file.

**One defect was introduced and fixed here:** `${UPLOAD_ROOT}` written as prose
in a V78 comment was read by Flyway as an unresolvable placeholder and stopped
the application from starting. Flyway substitutes across the whole file,
comments included — and a psql dry-run does not catch it, because psql has no
placeholder layer.
