# HRIS — Employee Self-Service

*Friends Pharma ERP · HR module · September 2026*

The page a person opens to know where they stand: **my HR data, my
requests, my progress, my actions.** It is reached as `/hris` ("My HR
(HRIS)" in the sidebar) and shows one employee — the one the login is
linked to — and nobody else.

This document records what was built, what was reused, how ownership is
enforced, and what was tested.

---

## 1. Page architecture

One page, three sections, one on screen at a time (`HrTabBar` on a desktop,
the bottom bar on a phone — the same pattern as every HR screen):

| Section | What is on it, top to bottom |
|---|---|
| **Overview** | identity strip · *what is waiting on you* · quick actions · KPI tiles · **my request progress** (in-flight requests, each with its lifecycle rail) · today & this month · my leave · my expenses · my advance & loan · recent activity |
| **My requests** | the unified history: type chips, search, status filter, table (cards on a phone), paged |
| **Attendance** | today & this month · my calendar for a month, one square per day |

The order on the Overview is the order of urgency: who you are, what needs
you, what you can do, what is in flight, then the figures and the record.

Files:

```
src/pages/Hris.jsx                       the shell, the overview, the history
src/pages/hris/HrisForms.jsx             LeaveForm · ExpenseForm · AdvanceForm · LoanForm · CorrectionForm · ReceiptControl
src/pages/hris/HrisRequestDrawer.jsx     one request: rail, data, attachments, comments, approval history
src/pages/hris/HrisAttendance.jsx        TodayCard · the month calendar
src/services/hrisService.js              every call, all under /api/v1/hr/me
src/hris.css                             only what a portal has and an HR screen does not
```

## 2. Component architecture

**Reused from the HR kit** (`src/components/HrKit.jsx`) — nothing was
duplicated: `HrPage`, `HrTabBar`, `HrTabs`, `Kpis`/`Kpi`, `HrButton`,
`HrSearch`, `HrFilterBar`/`Filter`, `HrTable` (rows become cards on a phone),
`HrPagination`, `usePagedList`, `HrDrawer`, `HrTimeline`, `HrEmptyState`,
`HrErrorState`, `HrSkeleton`/`HrPageSkeleton`, `HrProgress`, `HrFormSection`,
`HrLines`, `Status`, `Facts`/`Fact`, `PolicyNote`, `EmployeeAvatar`, `Modal`
(with the `subtitle` added earlier), `useConfirm`.

**Added to the kit** (reusable, not portal-specific):

- `HrRequestTimeline` — the lifecycle rail. Stages carry `DONE / CURRENT /
  PENDING / REJECTED / CANCELLED`, who, when and a remark; glyph + word,
  never colour alone; horizontal where there is room, vertical in the drawer
  and on a phone; `compact` for the cards. The brief's `HrRequestProgress`.
- `fmtWhen` — "12 Sep, 03:40 PM".
- `Status` now tones `COMPLETED`/`APPLIED` (sage), `PROCESSING` (teal),
  `REQUESTED` (amber) — the brief's `HrRequestStatus` is the existing pill.

**Portal-specific** (CSS classes in `hris.css`): the identity strip
(`hris-id`), the attention banner (`hris-attn`), quick actions
(`hris-quick`), request cards (`hris-req`), filter chips (`hris-chip`),
stat cells (`hris-stat`), the receipt control (`hris-file`), the calendar
markers (`hr-cal-day.is-present|late|absent|leave|pending`).

Every component has default, hover, focus-visible, disabled, loading
(skeleton), empty and error states, and follows the four themes through the
tokens. `prefers-reduced-motion` turns the transitions off.

## 3. API integration

All under `/api/v1/hr/me` (`HrSelfServiceController`). There is **no
employee id in any URL** and none is read from any body.

| Method | Path | What |
|---|---|---|
| GET | `/me` | who I am (`linked=false` when the login has no employee) |
| GET | `/me/summary` | leave balances, request counts, attendance issues, money, expenses this month, today, the month, actions, active, activity, latest payslip |
| GET | `/me/requests?type&status&q&page&size` | every request of mine, one shape, newest first |
| GET | `/me/requests/{type}/{id}` | one request: item, lifecycle stages, the module's record, the chain, the history |
| POST | `/me/requests/{type}/{id}/cancel` | withdraw (draft or undecided; not corrections) |
| GET | `/me/attendance?month` | my rows, holidays, weekends, summary |
| GET | `/me/calendar?month` | holidays, my leave, my dates (reuses `HrCalendarViewService`) |
| POST | `/me/attendance/{id}/correction` | ask for one of my recorded days to be corrected |
| GET | `/me/leave/balances`, `/me/leave/types` | |
| POST | `/me/leave` | apply (filed and submitted) |
| GET | `/me/expenses/types` | |
| POST | `/me/expenses` | file (`submit=false` keeps a draft for receipts) |
| POST | `/me/expenses/{id}/submit` | submit a draft |
| POST/DELETE | `/me/expenses/lines/{lineId}/receipt` | attach / remove a receipt (multipart) |
| POST | `/me/advances`, `/me/loans` | request |

The four filing bodies are the modules' own request DTOs. They are validated
in the service **after** the employee has been filled in from the login (the
controller does not use `@Valid` on them, because `employeeId` is `@NotNull`
and the server supplies it).

Existing APIs reused unchanged for the approver's side of every journey:
`/hr/leave/{id}/approve`, `/hr/expenses/{id}/approve|pay`,
`/hr/advances/{id}/approve|pay`, `/hr/loans/{id}/approve|disburse`,
`/hr/attendance/{id}/correction/approve|reject`.

## 4. Authentication and user mapping

```
login → JWT → UserPrincipal.employeeId  (from ACL_USER.PID_EMPLOYEE)
      → SecurityUtils.getCurrentEmployeeId()
      → HrSelfServiceImpl.whoAmI(): the HrEmployee, only if it is of the session branch
      → own records only
```

`UserResponse` (login and `/api/auth/me`) now carries `employeeId`, so the
client knows whether to show the menu item. The link is set by an
administrator on the user record (`ACL_USER.PID_EMPLOYEE`, unique per
employee); nothing on the portal can change it.

## 5. Employee ownership security

- **Identity is never taken from the request.** `employeeId` in a filing
  body is overwritten with the caller's own before anything runs
  (verified: a body with `employeeId: 999999` files for the caller).
- **Anything addressed by id is checked to be mine** before it is read or
  written — a leave request, a claim, a claim line (through its claim), a
  loan, an advance, a day of attendance. What is not mine answers **404**,
  never 403, which would confirm it exists (verified for read, withdraw and
  correction).
- **Authority is scoped, not granted.** An employee holds no rights on the
  HR modules and gets none. `HrScope.asSelf(employeeId, work)` sets a
  thread-local for the duration of one call; while set, `HrScope.require`
  passes every right *except APPROVE*. The self-service layer calls only
  file, read, attach, submit and cancel on the modules' own services, and
  never approve, reject, pay or disburse — so a decision cannot be reached
  through it. The flag is cleared in a `finally`.
- **The module's own rules still apply**: overlap, balance reservation,
  future-date and half-day checks for leave; receipts-required, line caps
  and the draft-only receipt rule for claims; one pending correction per
  day, no correction on a day written by leave.
- The bell (`NotificationService`) adds "n requests of yours are waiting"
  only when the login is linked, using four one-row count queries.

## 6–10. The workflows

Every request goes through the module's own service — the same validation,
numbering, workflow and audit as one filed by HR — and appears on the portal
with the module's status translated into seven portal words: **Draft,
Pending, Approved, Processing, Completed, Rejected, Cancelled**.

| Journey | Module states → portal | Lifecycle rail |
|---|---|---|
| **Leave** | DRAFT→Draft · SUBMITTED→Pending · APPROVED→Approved (Completed once the last day has passed) · REJECTED · CANCELLED | Submitted → *signatures* → Approved |
| **Expense** | DRAFT · SUBMITTED→Pending · APPROVED→Processing ("awaiting payment") · PAID→Completed · REJECTED · CANCELLED | Submitted → *signatures* → Approved → Paid |
| **Advance** | SUBMITTED→Pending · APPROVED→Processing (awaiting pay-out) · PAID/RECOVERING→Processing · CLOSED→Completed | Submitted → *signatures* → Approved → Paid out → Recovered through payroll |
| **Loan** | as advance with DISBURSED | Submitted → *signatures* → Approved → Disbursed → Repaid through payroll |
| **Attendance correction** | REQUESTED→Pending · APPROVED→Completed ("attendance updated") · REJECTED | Requested → Review → Attendance updated |

**Signatures come from the chain the request actually took.** Where an
approval chain is configured (`HR_APPROVAL_FLOW`), each level is a stage
with its label, the signer, the time and the remark; the level waiting is
*current*. Where none applies — the default in this deployment — a request
takes one signature and the rail says so with a single **Review** step
rather than inventing three. Nothing is hard-coded from the brief's example
("Manager → HR → Finance"); the rail is the configured workflow.

**Numbering.** The existing series (`DocumentNumberService`, the one every
document shares — `PR2609 00041` for payroll) is now given to requests at
submission: `LV…` leave, `EX…` claims, `AD…` advances, `LN…` loans. The
columns existed; nothing had filled them. An attendance correction has no
number of its own and is shown by its date.

**Rejected** requests show the decision, who, when and the reason (the
approver's remark) in the drawer and on the rail. **Returned for
correction** is not a state the backend workflow has; see §20.

## 11. Request lifecycle & detail

`GET /me/requests/{type}/{id}` returns:

- `item` — the one-shape summary (number, title, subtitle, amount, days,
  status, stage in words, updated, decided by, remarks, `actionRequired`,
  `cancellable`, `submittable`);
- `stages` — the rail;
- `data` — the module's own record, untouched (lines and receipts on a
  claim, the repayment schedule on a loan, recorded vs proposed times on a
  correction);
- `chain` — the approval chain with every action;
- `history` — the human-readable audit line, oldest first: created,
  submitted, each signature, the decision, paid/disbursed, closed.

The drawer lays these out as: where it is · the request · attachments ·
comments · approval history. On a phone it is full-screen. A draft claim is
finished from the drawer (receipts on its lines, then Submit); anything still
undecided can be withdrawn there.

## 12. Notification integration

The existing bell is derived on read (`NotificationService.forCurrentUser`),
not stored, and gated per alert. A self-service alert was added to it
("My Requests — n requests of yours are waiting for a decision", linking to
`hris`), gated on the login being linked. Decisions reach the employee on
the portal itself: the attention banner (drafts to finish), *my request
progress*, *my recent activity* (everything that changed in 60 days, with
its status), and a toast with the request number on every submission.

## 13. Attachment handling

Receipts reuse the expense module's own route (`ImageStorageService`,
bucket `EXPENSE_RECEIPT`, `${UPLOAD_ROOT}/expense_receipt`), through
`/me/expenses/lines/{lineId}/receipt` after the line is checked to be mine.
`ReceiptControl` takes a drop on a desktop and the picker on a phone, shows
*attached / replace / remove*, and the file type is limited to images
(`IMAGE_ACCEPT`); the server keeps its own type and size checks. No
filesystem path is ever shown — only the public URL the module already
returns.

## 14. Responsive design

Checked at 1440, 1280, 1024, 768, 480 and 375px with no sideways scroll:
five quick actions in a row → three → two; KPI tiles reflow through the kit;
the two-column widget grid becomes one; request cards drop the amount and
date under the title; the rail turns vertical; the calendar stacks the days
below 768px and is a weekday-aligned seven-column grid above it; the drawer
fills the screen on a phone.

## 15. Accessibility

Semantic sections with labels; the rail is an ordered list with
`aria-current="step"` and a visually-hidden state word per step; cards and
calendar days are keyboard buttons (Enter/Space); every icon-only control
has a title and `aria-label`; the drawer traps focus and returns it; status
is never colour alone (glyph + word); focus is visible on chips, cards,
quick actions and days; `prefers-reduced-motion` respected.

## 16. Existing components reused / 17. Pages modified / 18. Created

**Reused:** everything in §2, `EmployeeAvatar`, `Modal`, `Card`, `Field`,
the HR calendar's grid classes, the expense receipt route, the HR calendar
view, every module service.

**Modified:**

- `HrScope` — self-service thread-local (`asSelf`, `selfEmployeeId`) and the
  one line in `require`.
- `HrLeaveServiceImpl`, `HrExpenseServiceImpl`, `HrAdvanceLoanServiceImpl` —
  request numbers assigned at submission from the existing series.
- `UserResponse` / `AuthServiceImpl` — `employeeId` on the login payload.
- `NotificationService` — the self-service alert.
- `hrService.toneFor` — three more tones.
- `HrKit.jsx` — `HrRequestTimeline`, `fmtWhen`.
- `App.jsx` — the `hris` page; `Sidebar.jsx` — the menu item, shown when the
  login is linked (it is not an RBAC module: the server refuses every
  `/hr/me` call for an unlinked login regardless).

**Created:** `HrSelfServiceController`, `HrSelfServiceImpl`,
`HrSelfServiceResponses`, `hrisService.js`, `Hris.jsx`, `hris/HrisForms.jsx`,
`hris/HrisRequestDrawer.jsx`, `hris/HrisAttendance.jsx`, `hris.css`. No new
tables, no migration.

## 19. Testing performed

`verify-hris.mjs` (scratchpad), **74 checks, all passing**, against the
running application. The admin login is linked to employee 30 for the run —
the only write to existing data, put back to NULL at the end — and every
row the run files is removed, with its chain rows, lines, receipt, the
attendance written for it and the balance movement; every count is asserted
back to the baseline.

- **Who I am**: the login payload carries `employeeId`; `/me` names the
  employee and branch; the summary has the balances and the latest payslip.
- **Leave**: filed with `employeeId: 999999` in the body → filed for me;
  rail *Submitted done → Review current → Approved next*; HR approves → all
  done, history has the approval by `admin`, the balance fell by one day; a
  future approved leave is still withdrawable.
- **Expense**: draft → flagged "Attach receipts and submit" → receipt
  uploaded → submitted → HR allows 100 of 120 → *Approved — awaiting
  payment*, Paid is current → HR pays → Completed, every step done, receipt
  still on the line.
- **Advance**: submitted → approved → paid out → *Recovered through payroll*
  current; outstanding and per-payslip recovery on the summary moved by
  3,000 and 1,000.
- **Loan**: submitted, five-step rail, withdrawn → Cancelled, no longer
  cancellable.
- **Attendance**: HR records a day 09:40–18:00 → I ask for 09:05 → a second
  request while one waits is 409 → detail shows recorded and asked → HR
  approves → the day says 09:05, rail complete.
- **The list**: seven requests of five kinds in one list; type+status
  filter; search; the summary's activity and in-flight lists.
- **Ownership**: another employee's leave, claim, withdraw and correction →
  404; the list never contains another employee's request.
- **The screen**: identity strip, sidebar item, 5 quick actions, 7 tiles,
  in-flight cards each with a rail; the drawer with rail and history; the
  leave form (Submit disabled until a reason, balance box, submit → toast →
  drawer opens on the new request → progress list refreshed); the history
  tab and its chips; the calendar with present days marked; six widths with
  no sideways scroll; the phone drawer full-screen; no console errors.

Plus the standing sweeps: `qa3` (console errors across every HR tab),
`qa-layout` (now including `/hris`, 29 pages × 4 widths), the field audit,
unused imports, the build.

## 20. Known limitations

- **Returned for correction** does not exist in the backend workflow (a
  request is approved, rejected or cancelled). Rejected requests show the
  reason and can be filed again; there is no edit-and-resubmit.
- **A correction needs a recorded day.** The attendance module attaches a
  proposal to an existing row; a day nobody has recorded has nothing to
  correct, and the form says so rather than inventing a row.
- **Distance × rate** on expenses: the expense module has no rate table, so
  a claim line is an amount. Adding rates is a policy decision (BUSINESS
  POLICY CONFIRMATION REQUIRED).
- **Advance type / loan type**: the modules have one kind each.
- **Comments** are the approvers' remarks; there is no threaded discussion.
- **Payslip**: the tile shows the latest run's net and payment status; the
  A4 payslip print stays on the Payroll screen (HR rights), because the
  print document endpoint is under `/hr/payroll/{id}`. A self-service
  payslip document is a small follow-up.
- The unified list is assembled in memory from the five modules' own paged
  searches (up to 500 each, twelve months of attendance). At one person's
  volume that is tens of rows; it is not meant for a branch-wide view.
- The bell shows only "n waiting"; decisions are surfaced on the portal.
- The user manual has no HRIS section yet.
