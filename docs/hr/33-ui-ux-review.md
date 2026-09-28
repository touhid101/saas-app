# HR — UI/UX review

*10 September 2026. Numbered 33 rather than 29 because `29-ui-ux-review.md` was
asked for and `29-overtime-and-increment.md` already holds that number; renaming
an existing document to free it would break every link into it.*

This records what the HR interface was before this pass, what changed, and what
is still open. It is a review, not a plan: everything described here is in the
working tree and was exercised against the running application.

---

## 1. The existing design system

**There was one, and it is good.** The instruction to inspect before building
mattered — a large part of the component-level specification was already
satisfied, and the honest answer to most of it was "reuse", not "create".

### Tokens — `src/styles.css`

One `:root` block, then three complete overrides. Every colour in the
application comes from these; not one HR rule written in this pass declares a
literal colour.

| | |
|---|---|
| Ink | `--ink`, `--ink-soft` |
| Ground | `--paper`, `--paper-deep`, `--card` |
| Line | `--line` |
| Accent | `--amber`, `--amber-deep`, `--amber-soft` |
| Status | `--teal`/`--teal-soft`, `--sage`/`--sage-soft`, `--clay`/`--clay-soft` |
| Shape | `--radius` (14px), `--radius-sm` (9px) |
| Depth | `--shadow`, `--shadow-lg` |

**Four themes**: default, `[data-theme="dark"]`, `[data-theme="ocean"]`,
`[data-theme="forest"]`. Because every HR rule goes through the tokens, all four
work on HR without a single HR-specific colour.

**Type**: Fraunces for headings, Inter for body, IBM Plex Mono for labels,
codes and figures. `font-variant-numeric: tabular-nums` on every money column.

**Spacing**: the existing scale is 4 / 8 / 12 / 16 / 20 / 24, and it was already
being kept to.

### Components already present

`Card`, `Modal`, `Field`, `Pill`, `Table`, `Tabs`, `Toast`, `QtyInput`
(`components/UI.jsx`); `ConfirmProvider` — a real confirmation dialog, not
`window.confirm`; `EmployeeAvatar` — a photograph that degrades to initials and
never shows a broken image; `Icons.jsx` — an inline icon set; `Sidebar` with
expanded / rail / drawer states; a skeleton and empty-state pattern in
`PasswordAdmin.jsx`; a complete report framework in `Reports.jsx` with filters,
export and print.

### And an HR kit — `components/HrKit.jsx`

The seven HR screens were already built on a shared kit: `HrPage`, `Kpi`,
`HrTable` (with its card-per-row behaviour below 768px), `Status`,
`PayrollSteps`, `HrTabs`, `Pane`, `Filters`, `Facts`, `Blocked`, `PolicyNote`.

**So this was an upgrade pass on a working system, not a rewrite.** Editing the
kit propagates to all seven screens at once, which is why so few page-level
changes were needed to move every screen together.

---

## 2. What was actually wrong

Six real defects, found by inspection and by driving the running application.

### 2.1 Four undeclared CSS custom properties — the HR calendar drew on nothing

`hr.css` referenced `--surface`, `--surface-sunk`, `--line-strong` and `--brass`.
**None is declared anywhere in the application, and none had a fallback.** So
every calendar day rendered with a transparent background and a transparent
left border — the colour-coding by event type did not exist.

Mapped to the real tokens: `--surface` → `--card`, `--surface-sunk` → `--paper`,
`--line-strong` → `--ink-soft`, `--brass` → `--amber`.

*(The audit also flagged `--mono`, `--accent`, `--muted` and `--sage-deep`
elsewhere in `styles.css`. Those are outside HR **and each has a fallback**
— `var(--mono, monospace)` — so they render correctly. Left alone.)*

### 2.2 The bottom tab bar broke at five tabs and six

`.hr-tabs-2`, `-3` and `-4` had column rules. Attendance has **six** panes and
Employees has **five** — both fell through to a single grid column, so the
fixed bottom bar rendered as a **tall vertical stack covering half the phone
screen**, over the content it was meant to sit under.

Replaced with `grid-auto-flow: column; grid-auto-columns: 1fr`, which holds for
any count, plus a label size reduction past four tabs so the targets stay 48px.

### 2.3 `flex-grow` meant for a row, growing a column

`.hr-filter-grow { flex: 1 1 200px }` is right in a row. Below 768px
`.hr-filters` becomes a column — and the same rule then grew the field
*downwards*, so two filters in the phone sheet sat at opposite ends of the
screen with a hole between them.

### 2.4 One filter, stretched across 1440px

A single status dropdown filled the width of its card. Capped at 340px, released
to full width inside the phone sheet.

### 2.5 The icon-only button was not a square on a phone

`.hr-head-actions .btn { flex: 1 1 auto }` applied to every button, so Refresh
became a wide empty box with a glyph adrift in the middle of it.

### 2.6 A double-press could post a payroll twice

The lifecycle actions — calculate, review, approve, post, pay — had **no busy
state at all**. On a slow network a second press was accepted. Posting is not
idempotent.

---

## 3. Components reused

Unchanged, used as they are: `Card`, `Modal`, `Field`, `Pill`, `Toast`,
`ConfirmProvider`, `EmployeeAvatar`, `Sidebar`, `Topbar`, the report framework,
every design token, the type scale, and all four themes.

## 4. Components extended

| Component | What it gained | Why |
|---|---|---|
| `HrPage` | `crumbs` | §7 — a breadcrumb, quiet, and trimmed on a phone |
| `HrTable` | `loading`, `error`, `onRetry`, `onRowClick`, `caption`, `sortable` columns, rich `empty` | §27/§29/§30/§31 — four states, not two |
| `Kpi` | `icon`, `onClick`, `tone` | §15/§16 — a tile that opens the rows behind the figure |
| `Status` | a glyph beside the word | §17 — never colour alone |
| `PayrollSteps` | an `exceptions` flag on the calculated step | §53 |
| `Filter` | a real `<label for>` binding | §65 |
| `Fact` | `num` for tabular figures | §58 |
| `Spinner` | now renders a skeleton page | §29/§74 |

`empty` accepts either a string or `{ icon, title, hint, action }`, so no
existing call site broke.

## 5. Components created

All in `components/HrKit.jsx`, all named to the existing convention.

| Component | Spec | What it does |
|---|---|---|
| `HrCrumb` | §7 | Breadcrumb. Only crumbs given an `onClick` become links — a link that goes nowhere is worse than plain text |
| `HrButton` | §8–§12, §71 | Variants, sizes, icon slot, **busy** state, `iconOnly` that warns in the console when it has no label |
| `HrRowActions` | §28 | One visible action, the rest behind a menu that closes on Escape, on click-away and on choosing |
| `HrSearch` | §25 | Icon, clear button, Escape to clear, debounced — types land locally at once, the value goes up when typing stops |
| `HrFilterBar` | §26/§57 | Fields on desktop; on a phone one button that counts the set filters and opens them in a bottom sheet with Reset and Show results |
| `HrSkeleton`, `HrTableSkeleton`, `HrPageSkeleton` | §29/§74 | The shape of the answer while it is being fetched |
| `HrEmptyState` | §30/§78 | Icon, title, hint, and a next action |
| `HrErrorState` | §31/§75 | A plain sentence and a Retry. Never a stack trace |
| `HrDrawer` | §33 | Right-hand panel; focus moved in, focus trapped, focus returned, Escape closes, full-width on a phone |
| `HrTimeline` | §40/§41 | Approval history, oldest first, with the level still waiting drawn as an entry of its own |
| `HrStepper` | §23 | Numbered stepper with done / current / error states |
| `HrFormSection` | §21/§22 | Icon, heading, description, grid |
| `humanError` | §75 | Turns "Failed to fetch" into a sentence; passes a real server message through unchanged |

## 6. Existing pages modified — no new pages

**Seven pages changed. None created.** Every HR business function already had a
page, so §85 resolved to "modify" every time.

| Page | Changed |
|---|---|
| `HrEmployees.jsx` | Breadcrumb · skeleton · error+retry · `HrSearch` on the staff list · sortable columns · row-click opens the profile · rich empty states · the record form split into three `HrFormSection`s · photo upload with a real busy state · row menus |
| `HrAttendance.jsx` | Breadcrumb · skeleton (including a **month-shaped** skeleton for the calendar) · error+retry · filter bar with phone sheet · row menus on corrections and overtime · Save with a busy state |
| `HrLeave.jsx` | Breadcrumb · skeleton · error+retry · filter bar · Approve as the visible action with Reject in the menu · clickable KPI tiles |
| `HrPayroll.jsx` | Breadcrumb · skeleton · error+retry · **busy states on the whole lifecycle** · row actions reduced to next-step + menu · **exception panel** · Print on the payslip · print stylesheet |
| `HrAdvances.jsx` | Breadcrumb · skeleton · error+retry · filter bar · row menus on both advances and loans |
| `HrExpenses.jsx` | Breadcrumb · skeleton · error+retry · filter bar · **claim detail moved from a modal to a drawer** · receipt actions in a menu |
| `HrApprovals.jsx` | Breadcrumb · skeleton · error+retry · **review drawer with an approval timeline** |

### Two changes worth their own note

**The expense claim moved from a modal to a drawer.** A modal says "answer me";
a drawer says "here is the detail, the list is still behind you". Reading a
claim is the second thing. The *decision* still opens a modal, because that one
really is a question.

**The approvals inbox now shows the signatures.** `getApprovalChain` existed on
the server and in `hrService.js` and **had never been called from the
interface**. It is now the review drawer: every level's decision, who gave it,
when, and the remark — plus the level still waiting, drawn as an entry of its
own, because "approved by the manager" and "approved by the manager, now with
the administrator" are different states and only the second explains the delay
somebody is asking about.

## 7. DML and report UI

Every HR object has list, create, edit, detail, submit, approve, reject, history
and report. Nothing is backend-only.

Reports (§87) needed no work: `Reports.jsx` already carries a header, filters,
search, reset, summary, sorted table, CSV export, print, and loading / empty /
error states, and all 25 HR reports run through it.

**One report is now surfaced where it is needed rather than only where it
lives.** `hr-payroll-exception` is fetched for the run's own dates and shown in
the payroll run as a graded panel — severity, employee, issue, and what it
causes. It calls the same report an operator would run by hand, so a screen and
a report cannot come to disagree about whether a payroll is safe to post.

## 8. Responsive strategy

Unchanged breakpoints — the whole application already used them.

| Width | Behaviour |
|---|---|
| ≥ 1200 | Full tables, side-by-side panes, multi-column forms |
| 768–1199 | `advisory` columns dropped, forms to two columns, stepper hints dropped |
| < 768 | One card per row, one-column forms, filters in a sheet, fixed bottom bar |
| < 360 | KPI tiles one per row |

The KPI breakpoint moved from 400px to **360px**: at 390 — the width of most
phones in use — two tiles across still gives each about 180px, and one per row
pushed every row of data below the fold.

## 9. Mobile

Card-per-row tables (§62) were already the pattern and stay. Added: the filter
sheet, 44px minimum on every icon-only button and search field, row actions in
a menu instead of a strip of small targets, and the bottom-bar fix.

## 10. Icons

One inline set, one stroke width (1.9), one viewBox, colour always inherited —
so an icon in a danger button is red without anybody choosing red. Extended `P`
from 17 glyphs to 34, all semantic and all matching the specification's mapping
(Add→plus, Edit→pencil, Delete→trash, Search, Filter, Reset→rotate-ccw,
Download, Print, Approve→check, Reject→x, View→eye, More→horizontal dots,
Calendar, Employee, Attendance→clock, Overtime→timer, Salary→wallet,
Payroll→calculator, Expense→receipt, Reports→bar chart, Settings, Upload,
Close). **No emoji anywhere in the module** — checked.

## 11. Motion

150–200ms, on opacity and transform only. Menus and reveals 140–160ms, the
drawer and sheet 200ms on a single ease-out curve, the skeleton a 1.4s opacity
pulse. Nothing animates a table of data, and nothing delays a business action.

`prefers-reduced-motion: reduce` disables every animation named above and every
transition on the new components.

## 12. Accessibility

- Breadcrumb is a real `<nav>` + `<ol>` with `aria-current="page"`.
- Sortable headers are buttons; the `<th>` carries `aria-sort`. **Verified by
  keyboard alone.**
- The row menu is `role="menu"` / `menuitem` with `aria-haspopup` and
  `aria-expanded`; Escape closes it and returns focus.
- The drawer is `role="dialog" aria-modal="true"`, moves focus in, **traps Tab
  in both directions**, restores focus to the element that opened it, and locks
  the body scroll.
- Every icon-only button has both a `title` and an `aria-label`; `HrButton`
  warns in the console if one is built without a label.
- Status is a glyph **and** a word, never colour alone.
- Every filter `<label>` is bound to its control by a generated id.
- Loading regions carry `aria-busy` and an off-screen `role="status"`; skeletons
  are `aria-hidden`.
- Tables have an off-screen `<caption>` saying what they list.
- Every icon is `aria-hidden` with `focusable="false"`.

## 13. Employee image

`EmployeeAvatar` was already right — server-supplied initials behind the photo,
so a missing file, a deleted file and a failed request all land on initials
rather than the browser's broken-image glyph. The upload button now shows a real
busy state and Remove is a trash icon rather than a cross. The path is never
shown; `uploadUrl()` resolves it.

## 14. Approval UX

Inbox → **Review** opens the drawer → the request, the amount, the chain, and
the full signature timeline → either close, or go to the workflow's own screen
to decide. The decision stays on the workflow screen on purpose: that is where
the figures it changes are in front of you. The drawer says which case you are
in — "you may sign this", or "this is waiting on somebody else".

## 15. Payroll UX

The six-state rail (draft → calculated → reviewed → approved → posted → paid)
stays; it is the real lifecycle and matches what the server permits, which an
invented eight-step stepper would not. Added around it:

- **Busy states on every lifecycle action.** One key is in flight at a time; the
  pressed button spins and says what it is doing, the rest go flat.
- **One visible action** — the next step — with the rest, including Cancel run,
  in the menu. Post and Cancel run were previously the same size, side by side.
- **The exception panel**, graded, before approval.
- **Print on the payslip**, with a print stylesheet scoped by
  `:has(.hr-payslip)` so only the payslip prints as a document and every other
  dialog in the application prints exactly as it did.

## 16. Performance

No library added; the icon set stays inline. Search is debounced at 250ms and
0ms where the filter is local. Sorting is memoised. Avatars are lazy and
async-decoded. The exception report fails quietly — a panel that cannot load is
not a reason to refuse to show a payroll run. Bundle CSS grew 178.06 kB → 178.95 kB
(30.85 → 31.04 kB gzipped) — 0.9 kB, 0.19 kB gzipped, for the whole component
layer.

## 17. Verified, not assumed

Three scripts drove the running application at `localhost:5173` against the live
backend.

**`qa.mjs`** — 42 screenshots: 7 screens × 3 widths × 2 themes, each audited in
the page for elements wider than the document, custom properties used but never
declared, and buttons with no accessible name.
→ *No horizontal overflow in any HR element. No undeclared variable without a
fallback. No unnamed button.*

**`qa2.mjs`** — the parts a screenshot cannot reach.
→ *Drawer opens and Escape closes it · filter sheet opens and Escape closes it ·
row menu opens and Escape closes it · a column sorts by keyboard and sets
`aria-sort` · the skeleton is drawn while loading (request throttled 2.5s) · the
error state and its Retry appear when the request is refused.*

**`qa3.mjs`** — every screen, every pane, failing on any console error.
→ *No console errors on any HR screen.*

That third script exists because of a bug this pass created and it caught: six
files called `humanError` without importing it. **The bundler built cleanly** —
it is valid JavaScript until it runs. Only a browser walking the pages found it.

## 18. Remaining

**Deliberately not done**

- *The 8-step payroll stepper (§52).* The server permits six states. An
  eight-step display would show two steps nothing can reach.
- *An HR-specific dark palette (§66).* The tokens already carry four themes.
- *Bulk actions (§88).* "Only where safe" — approving twelve leave requests in
  one press is not, and nobody has asked.
- *Inline editing (§88).* Every HR figure is approved, frozen or posted. Inline
  editing is for values that are cheap to get wrong.

**Genuinely open**

- *An employee self-service view (§43 mobile dashboard).* No such surface
  exists; gap item 18.
- *An old-value/new-value audit trail (§40 audit tab).* `HrTimeline` is built
  and would render it; there is nothing yet to render. Gap item 21.
- *The four fallback-guarded undeclared variables outside HR.* Harmless, but
  they should be declared or the fallbacks made the value.
- *`GET /api/customers` returns 500.* Found by the sweep in §20, nothing to do
  with HR, and it breaks the Sales page's customer list. Reported, not touched.

---

## 19. Sidebar badges and the dashboard's People tab

*Added after the review above, closing the two items §18 had left open.*

### 19.1 The badge mechanism was dead code

`NavLink` read `item.badgeKey` and looked it up in a `badges` map. **Nothing,
anywhere, ever set `badgeKey` on any item** — `toNav()` does not produce the
field — so the lookup was always `undefined` and no badge had ever rendered.
The requisitions count the mechanism was written for had never appeared either.

Now keyed on `item.id`, the route, which is what the menu actually carries and
what `badges` was already keyed by.

### 19.2 One count, read twice

The sidebar and the dashboard want the same figures and are both drawn on every
page. Counting them in two places would mean two requests and, worse, two
answers: a badge saying 3 beside a tile saying 4 is worse than neither being
there. So `AppContext` fetches the HR summary once and both read from it.

**Gated on the menu, not attempted and caught.** `summary()` requires the
employee module's View right, so a cashier fetching it would earn a 403 on every
page load to be told a number they may not see. If the server put no HR page in
this user's menu, nothing is asked for and no badge is drawn.

The service is imported dynamically, so the HR service is not in the bundle a
cashier downloads.

### 19.3 Two counts the summary could not produce

`HrSummaryDto` predates expense claims (V83) and attendance corrections, so the
two things most likely to be waiting on somebody were the two it could not
count. Added `pendingExpenseClaims` and `pendingAttendanceCorrections`, both
using repository methods that already existed. **No migration** — these are
counts, not columns.

### 19.4 What earns a badge

Only things **waiting on a person**. Not "how many exist", not "how much money":
a badge is a call to action, and one that never reaches zero is furniture the
eye stops seeing within a week.

| Page | Counts |
|---|---|
| Employees | separations awaiting a decision |
| Attendance | corrections requested |
| Leave | requests submitted |
| Advance & Loan | advances **+** loans submitted — one page, so one number |
| Expense Claims | claims submitted |

Two figures were deliberately left off. **Attendance not yet recorded today** is
a full house every morning before anybody marks the register — a red 9 at 9am
that means nothing has happened yet. **Payroll** is a five-step sequence, not a
queue of items; its state belongs on the rail, which it has.

Approvals gets no badge of its own: everything in its inbox is already counted
on the page that owns it, and badging both would count the same leave request
twice.

### 19.5 Three widths, three shapes

| | |
|---|---|
| **Sidebar** | the number, beside the page name |
| **Group closed** | the total of what is inside it, on the heading — otherwise collapsing "Human Resources" hides five badges behind a chevron and the whole point of them is lost |
| **Rail (768–1023)** | a 6px dot on the icon, with the figure in the tooltip. 56px leaves no room for a numeral beside a centred icon, and one small enough to fit is unreadable rather than compact |

A `.sr-only` line carries the count to a screen reader in every shape.

**A bug this uncovered:** the rail hides labels with
`.nav-item span:not(.badge-count)`. The dot and the screen-reader text are spans
that are not labels, so both were `display: none` — the dot was in the DOM, and
the test that counted five of them passed, while the screen showed none. Only
looking at the pixels found it. The selector now names its exceptions.

### 19.6 The People tab

A fourth tab on the dashboard, drawn only for somebody with an HR page, in the
order the questions are asked: **is anybody waiting on me**, **who is in
today**, **where is this month's payroll**.

- Five tiles: active staff, in today, register not marked, waiting on a
  decision, owed by staff.
- Somebody payroll will skip is said **in words**, not as a tile — it is the one
  thing on the tab that is wrong rather than merely pending.
- Five queue rows, each a way in. A figure telling you eleven things need a
  decision with no way to reach them is a complaint, not a dashboard. Only the
  non-empty ones are drawn: a row of zeroes is a list of things you do not have
  to do.
- The payroll card says what the run is **waiting for**, not only what state it
  is in — a status word says where it is, not what comes next.

It costs no request: the counts are the ones the sidebar already fetched.

### 19.7 Kept honest

A count fetched once at login goes stale the moment somebody approves
something. It is re-counted **on every page change** — the moment a stale badge
would be seen — skipping the first render, since the context has already
counted when the menu arrived. Before that guard, every login asked the server
the same question twice.

---

## 20. Verified, again

The files this touched — `AppContext`, `Sidebar`, `App`, `Dashboard`,
`styles.css` — are on **every** page, not just HR, so the checks had to be too.

**`qa5.mjs`** — the branch's queues are genuinely all at zero, which proves the
empty states and nothing else. So it runs twice: once against the real summary,
once with `/api/v1/hr/summary` stubbed to a populated one. Nothing is written;
the stub lives in the browser. The stub also carries the two new fields, so the
interface was checked before the backend was restarted.
→ *Zero: no badges, People tab present, "every queue is clear". Populated:
badges 1 · 2 · 3 · 3 · 4, roll-up 13 on the closed heading, five queue rows,
five rail dots with counts in their tooltips.*

**`qa6.mjs`** — six pages (dashboard, inventory, sales, purchase, reports, hr) ×
four viewport/theme combinations, failing on any console error or horizontal
overflow, and asserting the rail still hides its labels.
→ *No console errors, no overflow, rail labels still hidden.*

**`qa7.mjs`** — proves the badge does not go stale: the stub drops the leave
count to zero on the third request, and the badge must disappear.
→ *One request on load, refresh fires on navigation, badge cleared.*

**`qa3.mjs`** re-run → *no console errors on any HR screen.*

The only failure any of this surfaced is `GET /api/customers` returning 500,
which predates this work and belongs to Sales.

---

## 21. The two new counts, against the restarted server

*Everything in §20 read the two new fields from a stub, because the backend was
still running the old build. It was restarted on 10 September 2026 and they were
checked for real.*

**The restart.** `mvn -o spring-boot:run`, default profile — the same one the
previous instance was on, pointing at port 5489. Flyway **validated 86
migrations and applied none**, which is the point: two counts are not a schema
change. Started in 34s, no errors in the log that this verification did not
itself provoke.

### 21.1 A zero is not a proof

Every queue on this branch is genuinely empty, and a field hard-coded to `0`
passes that test. So `verify-hr-counts.mjs` makes one of each thing, watches the
count rise, undoes it, and watches it fall back:

| | |
|---|---|
| Fields served | `pendingExpenseClaims`, `pendingAttendanceCorrections` present |
| Agree with SQL | api 0 = sql 0, for both |
| A **draft** claim | does **not** count — only a submitted one is waiting on anybody |
| Submitting one | count 0 → 1 |
| Requesting a correction | count 0 → 1 |
| Undoing both | count 1 → 0, for both |

### 21.2 And then the interface, with no stub anywhere

`verify-live-badges.mjs` puts one real submitted claim and one real requested
correction into the branch, and reads what the interface draws from the live
server: **sidebar Attendance = 1, Expense Claims = 1; dashboard "Waiting on a
decision 2, across five queues" with both rows present.** Then it takes them
out again.

### 21.3 The branch is exactly as it was

Counts were taken before anything was touched and again afterwards:

| | before | after |
|---|---|---|
| expense claims | 1 | 1 |
| claim lines | 1 | 1 |
| attendance rows | 283 | 283 |
| rows carrying a correction | 2 | 2 |
| employees | 10 | 10 |
| leave requests | 3 | 3 |

No `ZZ-` row survives. The claim was **created and deleted** by the script. The
attendance row was **not** created by it — it is the pharmacy's — so only the
four correction columns were read first and written back to the NULLs they held.
Nothing else on that row was touched.

### 21.4 Three things the server was right to refuse

All three were my script's fault, and each is the application behaving well:

- **`amount` on a claim line** — refused as a calculated field a client may not
  supply. The field is `claimedAmount`; the total is the server's to work out.
- **A correction on a day that came from approved leave** — refused with
  *"Cancel the leave instead — correcting the attendance alone would leave the
  leave still counted."* The script now picks a genuinely marked day.
- **A cleanup delete with `NaN` for an id** — the first run crashed before its
  cleanup because the claim had never been created, and the cleanup then ran
  with an undefined id. Nothing was harmed, because there was nothing to delete
  and Postgres rejected the predicate outright. The script now **stops before
  any cleanup** when there is nothing to undo: a delete with a broken predicate
  is how a verification script becomes the thing that damages the data.

---

## 22. Two dialogs that sent a field the server refuses

*Reported from the Attendance page: "'empName' is not an accepted field for this
request." Both bugs found by it are **pre-existing** — neither `saveDetail` nor
the leave-type body appears in any edit made during the UI work, and the copy of
`HrAttendance.jsx` taken before that work already carries the spread. The repo
has no commits, so this is evidence rather than proof.*

### 22.1 What the server is doing, and why it is right

`JacksonConfig` runs with **`FAIL_ON_UNKNOWN_PROPERTIES` on**, deliberately: a
client posting a server-owned field is refused loudly instead of having it
silently dropped. That is the correct choice — `tpPrice` on a product, `amount`
on an expense line, `totalNet` on a payroll run are all the server's to compute,
and a client that thinks otherwise should be told.

The consequence is that **`{ ...form }` into a request body is a standing
hazard.** A form is a screen's working state; it holds whatever the dialog
needed, including things held purely for display.

### 22.2 The two

**Attendance → Times → Save.** `detail` carries `empName` so the dialog can put a
name in its heading, and the whole object was spread into the record request.
Every save was refused. Now built field by field.

The same fix cured a second fault in the same call: an untouched
`datetime-local` reads as `""`, which was being sent where the server parses a
`LocalDateTime`. Blank now goes as `null` — an empty box means *not given*, not
*given as nothing*.

**Leave → Types → Edit → Save.** `typeForm` carries `id` so the screen knows it
is editing rather than adding. The id belongs in the URL; the DTO has no such
field, so the body was refused. **Adding a type worked and editing one never
did.** Now built field by field, with `status` included only when the form has
one.

### 22.3 The rest of the module, checked rather than assumed

`field-audit.mjs` reads the request DTOs straight out of `HrRequests.java` and
checks every key each screen can put in a body — including keys that only exist
when editing an existing row — against the DTO that must receive it.

Seven bodies are spread or near-spread; five were already clean. `saveClaim`,
`saveStructure`, `saveDetails` and `savePayBasis` all build their bodies field by
field already, which is why they never broke. The audit now encodes the fixed
shape, so going back to a spread turns it red.

### 22.4 Proved through the interface, not the API

The bug was in what the *screen* puts in the body, so `verify-unknown-field-fix.mjs`
clicks Save in both dialogs and reads what comes back.

| | |
|---|---|
| Times dialog opens, saves | no unknown-field refusal |
| the day is recorded | 0 → 1 rows for today, overtime stored as typed |
| an untouched check-in | stored as `NULL`, not `""` |
| leave type edit saves | no refusal, dialog closes (it only closes on success) |
| the leave type itself | byte for byte as it was |

Today's register had no rows, so recording one **created** a row rather than
modifying an existing one, and it was deleted afterwards — attendance back to
283. The leave type was saved back with the values it already had: a real round
trip through the endpoint that changes nothing, verified by comparing the row
before and after.

---

## 23. The page scrolled past its own content, and the menu went with it

*Reported as "sometimes css break and app unlimited scroll down rather than menu
showing upper". Two separate faults, both in rules that apply to **every** page
in the application, and a third found on the way.*

### 23.1 `height: 100%` on html, body and #root

```
styles.css:96   html, body, #root { height: 100%; }
```

That pins all three boxes to the viewport, so a page taller than the screen
overflows them instead of growing them. Measured on Attendance at 1920×1030:

| | |
|---|---|
| content (`.app-shell`) | **3787px** |
| `body` | **1030px** — the viewport |
| document would scroll to | **1820px** |

So roughly **two thousand pixels of the page could not be reached at all** — on
Attendance, everything from Shifts downwards. Changed to `min-height: 100%`.
Nothing wanted these boxes to *be* the viewport; what needs viewport height asks
for it directly (`.app-shell` has `min-height: 100vh`, `.sidebar` and `.pos`
have `height: 100vh`).

### 23.2 `overflow-x: hidden` on `<body>`

```
responsive.css:38  html, body { max-width: 100%; overflow-x: hidden; }
styles.css:2854    (the same rule again)
```

A safety net against one over-wide child sliding the document sideways — good
intent, wrong element. Setting overflow on one axis forces the other to compute
to `auto`, so **`<body>` became a scroll container**. Every `position: sticky`
inside then sticks to body's scrollport, which never scrolls because the
viewport does — and the sidebar slid up and off the page, which is what the
report described.

Overflow set on `<html>` propagates to the viewport and leaves `<html>` itself
visible, so the sideways clip is kept and sticky works. Both copies changed.

*(The same trap is already documented in `topbar.css`, on the bar's own
overflow. It caught the sidebar too.)*

### 23.3 The rail spilled its menu down the document

Found by the sweep, at 768–1023px. The rail lists every page flat — there are no
headings to collapse — so its menu is about **1280px** on a **1000px** screen.
`brand.css` had set `overflow: visible` on it so the hover label could paint
outside its 56px, which also meant the menu could not scroll: it simply spilled
down the document, and the page scrolled to reveal it while the sticky sidebar
ran out of containing block.

The rail now scrolls its own menu. A scroll container clips its contents on both
axes — `overflow-clip-margin` does not rescue a pseudo-element from that, which
was checked and does not work — so the label moved out of the rail entirely:
`RailTip` is a real element, a **sibling of the sidebar**, positioned from the
hovered item's own box. Nothing clips it, it follows the menu as it scrolls, and
it is one element rather than thirty pseudo-elements. `title` stays on each item
as the screen-reader name and the fallback.

### 23.4 Verified across the whole application

These are global rules, so the check is global: **28 pages × 4 widths**, each
scrolled to its end and measured.

> no sideways scroll, no unreachable content, the sidebar stays put — on every
> page, at every width

Also re-run clean: no console errors on any HR screen, no console errors or
overflow on the wider page sweep, the rail still hides its labels, and the
badges and People tab still read correctly.

### 23.5 The topbar, made sticky on request

`styles.css` declared it `position: sticky`; `topbar.css`, loading later,
overrode it to `position: relative` for a stacking context, and the two had
disagreed for as long as both existed. It is sticky now, at `top: 0`, with the
same `z-index: 20` — sticky is positioned, so the stacking the override was
there for still holds.

**Its height is a token**, `--topbar-h`, 64px and 56px on a phone, because two
other things depend on it:

- anything else that sticks to the top of the *window* has to start below the
  bar, or it slides under it. Two do: the Sales rail and the Purchase totals
  panel, both now `top: calc(var(--topbar-h) + 16px)`, and the rail's height cap
  shortened by the same amount so its own bottom stays on screen. Every other
  sticky `top` in the application — modal heads, the permissions grid, the
  module-rights table, the requisition list header — lives inside its own
  scroll container and is unaffected;
- an anchor jump or `scrollIntoView` would land its target under the bar, so
  `html { scroll-padding-top: var(--topbar-h) }`.

**Verified:** bar at `top: 0` while scrolled 2500px into Attendance; nothing
the page draws paints over it; the bell's dropdown opens on top of the page
while scrolled; the two offset panels compute to `top: 80px`; on a phone the bar
is 56px and the token follows. Then the whole-application sweep again — 28
pages × 4 widths — now **asserting** the topbar as well as the sidebar: all
hold.

One honest note on the two panels: on this branch's data they are exactly as
tall as their column, so they have no room to stick and simply flow — the
offset is applied and correct, but only a longer page would show it working.

**A mistake in the check itself.** The sweep's topbar assertion read
`topTop > 1`, which only catches the bar being pushed *down*; a bar scrolled up
out of view has a large negative top, so it could never fail and said nothing on
every run. Now `|top| <= 1`, which fails in both directions — an assertion that
cannot fail is worse than none, because it reads like coverage.


---

## 24. The register, rebuilt for 2,500 people

*Asked for as "more interactive and better UI & UX for attendance page … I
have 2500 employee". At that size the page's shape was the problem, not its
styling, so this is a rebuild with a small backend change under it.*

### 24.1 Why a restyle would have been dishonest

Three things in the old page do not survive 2,500 people:

- `GET /attendance/day` returned **every employee, every time** — a 2,500-row
  response on each date change, drawn as one table;
- all six sections rendered **stacked down one page** on any screen wider than
  a phone — a register of 2,500 followed by the history, the corrections, the
  overtime, the calendar and the policy;
- three `<select>` pickers of people — history, overtime, calendar — would each
  hold **2,500 options**.

None of that is fixed by colour and spacing.

### 24.2 The backend, changed as little as possible

**`GET /attendance/day`** now takes `q`, `departmentId`, `shiftId`, `filter`,
`page`, `size`. It pages over *people* and left-joins the day's row, so the
filter can say `NOT_RECORDED` — the question the register exists to answer —
as well as any status. The counts above the page (`total`, `recorded`,
`notRecorded`, `byStatus`) come from a `GROUP BY` over the whole branch,
never from the page. Rows gained `imagePath`, `initials`, `departmentName`
and `shiftName`, looked up once per page, because at 2,500 those are what
tell two Rahims apart.

**`POST /attendance/day/fill`** marks everyone the filter matches who has **no
row yet**. It is how a large register gets finished: mark the exceptions,
then "everyone else was present" in one request. A mark already made, a
device punch, a day written by approved leave — all left alone. LEAVE and
future dates are refused.

**No migration.** Two queries and one insert; the schema did not change.

### 24.3 The fill was 111 seconds, then 210 milliseconds

The first version of the fill put each person through the entity path —
`upsert()`, the same code a single mark uses. Measured at 2,500:

> filled 2500 rows in **111,505ms**

Four round trips a person — a lookup, two scope calls, a sequence fetch, an
insert. A proxy gives up on a request after sixty seconds; this one would
never have finished in production.

Rewritten as **one `INSERT … SELECT`** with a `NOT EXISTS` guard, writing
exactly what `apply()` writes for a status-only mark — MANUAL source, zero
minutes, the audit stamps set by hand because JPA auditing only sees entity
saves:

> filled 2500 rows in **210ms**

Five hundred times faster, verified to have written `inserted_by = admin`
and a timestamp on every row.

### 24.4 The page

**One section at a time.** `HrTabBar` along the top on a desktop, the same
six tabs as the bottom bar on a phone, both bound to one state. The page
renders only the chosen pane. Each pane is its own file under
`pages/attendance/`; `HrAttendance.jsx` is the shell.

**The register** (`RegisterPane.jsx`):

- date with previous/next arrows, Today, and the weekday — a register is
  filled for yesterday as often as for today, and a date picker is three
  clicks to move one day;
- a **progress bar** — "2,377 of 2,500 · 95%" — in words as well as a bar;
- five tiles that are **filters**: click Not recorded and the page shows only
  them, click again to clear;
- **server-side** search, department, shift and status filters; any
  narrowing goes back to page one;
- **pagination** with "1–50 of 2,500", first/previous/next/last, page size;
- **marks that survive paging** — held by employee id, compared against every
  row the session has seen, so the save bar counts marks on pages that are
  no longer on screen: "12 unsaved marks · 3 on this page";
- a **sticky save bar** that follows the page — Save and Discard, discard
  asking first past three marks, saving all or nothing;
- **the keyboard**: ↑↓ or J/K walk the rows, P/A/L/H mark the one under the
  cursor and move on, Enter opens Times, Escape clears, S saves. A register of
  2,500 is filled by somebody who never lifts their hands from the keys, and
  the legend is on screen rather than in a tooltip so nobody has to discover
  it;
- **Mark page present** (client, unsaved) and **Everyone else present**
  (server, one request, asks first, says how many);
- every row: photograph or initials, name, code, department, shift; a mark
  group colour-coded green/red/amber/teal; an amber stripe while a mark is
  unsaved, a teal stripe under the cursor; Fix and Times.

A day written by approved leave is locked and says so; a device punch is
locked and says so — both are changed through a correction, never overwritten.

**Everywhere a person is chosen**, `HrEmployeePicker` types against the
server and shows the twenty that match, each with photograph, code and
department. `<select>` is gone from the module's people-pickers.

**History** gained This month / Last month / Last 30 days, in/out times, and
summary chips — days recorded, present, of them late, absent, on leave,
worked, overtime — because "how many days was she late" is the question and
counting rows is not an answer.

### 24.5 Two layout bugs this uncovered

**The page wrapper in `App.jsx` was `overflow: auto`.** It never scrolled —
`.main-col` grows with the page — but `overflow: auto` still made it a scroll
container, so every `position: sticky` inside any page measured itself
against that box instead of the window. The sticky save bar therefore sat at
the end of the content, never in view. (An old comment on `.sales-rail` had
noticed exactly this and compensated with a height cap.) Now `overflow:
visible`.

Removing it exposed what it had been absorbing: Collection's 833px table in
an 844px tablet column. The bare-table scroller in `responsive.css` only
applied below 768px; it now applies at every width.

**A `flex: 1 1 320px` growing a column** — the progress bar's wrapper, right
in a row, opened a 280px hole on a phone once the head stacked. The third
time this class of bug has appeared in this module; the fix is the same each
time.

### 24.6 Verified

**`verify-register.mjs`** — 33 checks through the interface on real data:
tiles and pager, marks and the save bar, toggling a mark off, keyboard walk
(`P-LA-P---` read back down the page), discard asking first, save landing two
rows, tile filters and server search, the fill writing 7 and leaving the 2
hand-marked rows alone, "Register complete", the phone. Runs on today, which
had no rows, and deletes only rows dated today.

**`verify-scale.mjs`** — **2,491 tagged people inserted**, everything timed,
everything removed, every count asserted back to baseline:

| at 2,500 | |
|---|---|
| first page of 50 | 44–230ms |
| last page (49) | 56–104ms |
| search "test 124" → 11 | 47–84ms |
| filter NOT_RECORDED → 2,500 | 59–94ms |
| **fill 2,500 in one request** | **210ms** (was 111,505) |
| screen: rows in the DOM | **50**, not 2,500 |
| first rows painted | ~2s |
| jump to last page | ~1s |
| typed search → 1 of 2,500 | ~0.5s |
| marks across two pages | saved: ABSENT 1, LATE 1, PRESENT 2,498 |

Inserted rows are `ZZ-00001…`, named "ZZ Scale Test n", removed by tag with
their attendance first; the baseline — 10 employees, 284 attendance rows, 0
today — is captured before and asserted after. Sequences move on; nothing
else does.

Then the usual: no console errors on any HR screen, no console errors or
overflow on the wider sweep, 28 pages × 4 widths with nothing sideways and
both bars stuck, the field audit clean with the three register bodies added.

### 24.7 Left as it is

- The user manual's Attendance section shows the old page. It needs its
  screenshots retaken; not done here.
- `unrecordedOn`, a query written for the fill's first version, was removed
  when the fill became one statement.

---

## 25. The other six pages, in the register's shape

*Asked for as "recreate or modify hr, leave, advances, payroll, expenses,
approvals page as like attendance page". The register's shape — one section
at a time, everything paged on the server, people chosen by typing — is now
the shape of the whole module.*

### 25.1 What every page had in common before

- **`getEmployees({ size: 200 })`** on Leave, Advances, Payroll, Expenses and
  Employees: the first two hundred people, fetched to fill `<select>`
  pickers. At 2,500 the pickers were wrong (2,300 people missing) and the
  request was wasted.
- **Every section stacked** down one page on a desktop: five cards of tables
  on Employees, four on Payroll, before the reader reached the one they
  came for.
- **Lists capped where the page happened to cap them** — `searchPayrolls({
  size: 30 })`, `searchSeparations({ size: 50 })`, `getPfLedger({ size: 50
  })`, `searchIncrements({ size: 50 })`, `searchGratuity({ size: 50 })` —
  with no way to the fifty-first row.
- **Tiles reduced over the loaded page.** Advances summed `outstanding` over
  the rows on screen and called it the branch's figure.
- **The staff list** on Employees was the 200-row fetch filtered in the
  browser by name, code and phone.

No backend change was needed: every list endpoint already took `employeeId`,
`status`, `page` and `size`, and `/api/employees` already took `q`. The
pages had not been using them.

### 25.2 The shape, applied

Each page is now: `HrTabBar` along the top on a desktop, the same tabs in
the bottom bar on a phone, both bound to one `pane` state, and **only the
chosen pane rendered**. Under it, per page:

| page | panes | paged on the server | paged in the browser | pickers |
|---|---|---|---|---|
| **Employees** | Overview · Staff · Increments · Gratuity · Leavers | staff (`q`), increments, gratuity, separations | the two increment queues (due, waiting) | separation, increment, award, gratuity estimate, *Reports to* |
| **Leave** | Waiting · All · Balances · Types | requests (status, person) | the waiting queue, searched | filter, balances, file-a-request |
| **Advances** | Advances · Loans | both (status, person) | — | filter, advance, loan |
| **Payroll** | Runs · Periods · Structures · Fund | runs (status), PF ledger (person, kind) | periods (by year); **the open run's payslips**, searched and filtered to *still owing*; its payments, searched | ledger filter, fund movement |
| **Expenses** | Waiting · All claims · Kinds | claims (status, person) | the waiting queue, searched | filter, file-a-claim |
| **Approvals** | Waiting on me · On somebody else · Chains | — (the inbox is a union of six queues, none of which pages) | both inboxes, searched and narrowed by kind | — |

**Every employee `<select>` is gone.** Eighteen of them became
`HrEmployeePicker`, which types against `/api/employees?q=` and shows twenty.
It gained one prop, `valueLabel`, for the case where the caller already
knows the name behind a preset id — *Reports to* on the employee record,
where the manager's name arrives with the profile — so the box never has to
say `#123` while it looks the person up.

**Tiles are the branch's figures.** Advances reads `advanceOutstanding`,
`loanOutstanding` and the two pending counts from the HR summary. Expenses
reads `pendingExpenseClaims` from it and asks the claims endpoint for
`status=APPROVED&size=1` to count what is approved and unpaid — a count from
the server, not from a page. Employees' Gratuity badge does the same with
`status=SUBMITTED`. Payroll's tiles describe the latest run, fetched as
`page=0&size=1` on its own rather than as the first of thirty.

**Each list says how many it has** — "Staff · 2,500", "Claims · 1" — and the
pager says where the reader is. Filter empties offer Reset; true empties
say what the list is for.

### 25.3 The payslips of a run

`GET /payroll/{id}` returns the run with every line; that is what the
lifecycle needs and it is not changed. The modal used to draw all of them.
It now searches by name or code, filters a posted run to *still owing* or
*paid in full*, and pages — 2,500 payslips are fifty rows on screen and
"Payslips · 12 of 2,500" in the heading when a filter is on. The payments
list under it is searched by name, voucher or reference, and paged the same
way. Both reset when a different run is opened.

### 25.4 One bug found on the way

`useLocalPage` resets to page one whenever the array it is handed changes
identity — the right behaviour when a search changes the rows. Approvals
narrowed its inbox with a `filter()` in the render body, which hands the
hook a new array every render, so no page past the first could be reached.
Memoised; the comment beside it says why.

### 25.5 Request bodies

The separation form used to send `{ ...sepForm, submit: true }` — the whole
form object. It sends `employeeId`, `separationType`, `noticeDate`,
`lastWorkingDate`, `noticePeriodDays`, `reason`, `submit` by name now, like
every other body in the module, because a field added to the form later
would otherwise reach a server that refuses fields it does not know. The
field audit covers it.

### 25.6 Verified

**`verify-pages-scale.mjs`** — 2,491 tagged people inserted, 32 checks,
everything removed, every count asserted back to the baseline (10
employees, 293 attendance rows, 4 leave requests, 3 advances):

| at 2,500 | |
|---|---|
| `/api/employees` page 0 / page 49 | 24ms / 51ms |
| `q=ZZ Scale Test 124` → 11 · `q=ZZ-02490` → 1 | 25ms / 29ms |
| Staff tab painted "1–50 of 2,500" | 2.8s |
| rows in the DOM | **50** |
| last page "2,451–2,500 of 2,500" | 425ms |
| typed search → "1–11 of 11", and it went out as `q=` | 392ms |
| Advance dialog picker "scale test 77" → 11 people, picked one | 1.4s |
| Leave filter picker: 111 match, 20 shown, "Everyone" on top | — |
| every tab on Expenses, Payroll, Approvals painted, pagers where lists page | — |
| phone: Staff pane, no sideways scroll, no Overview tiles on it | — |

Then: no console errors on any HR screen with every desktop tab clicked
(`qa3.mjs`, now walking `.hr-tabbar-tab`), 28 pages × 4 widths with nothing
sideways and both bars stuck, the field audit clean, no unused imports.

### 25.7 Left as it is

- The user manual's screenshots of all six pages are out of date, as the
  Attendance ones already were.
- Leave balances are still opened one person at a time; a "open the year
  for everybody" is a separate ask.
- The approval inbox is not paged on the server. It is a union of six
  queues; paging it would mean a query that spans them, and at 2,500
  people the *waiting* lists are still tens, not thousands.

---

## 26. The printed payslip, and the line editors

*Two asks: "PaySlip print option as like sales invoice format", and "modify
the expenses modal & approvals modal more professional and Standard UI &
UX" — the two screenshots showed raw, unlabelled inputs in a wrapped row.*

### 26.1 The payslip printed a blank page

Measured before anything was changed: `window.print()` from the payslip
dialog produced a 1.2KB PDF with nothing on it. The print rule hid
`.app-shell` to get rid of the page behind the dialog — and the dialog lives
inside `.app-shell`, so it hid the payslip too. Nobody had printed one.

### 26.2 The sheet, in the invoice's shape

**`GET /api/v1/hr/payroll/{id}/lines/{lineId}/document`** — a new
`PayslipDocumentService`, built like `InvoiceDocumentService`: the branch's
own letterhead (trading name, address, phone, logo), the tenancy wall (a run
is found through the session branch; a line from another run is *not found*,
never *forbidden*), and every figure computed on the server:

- the lines the run wrote, earnings and deductions in the run's own order,
  each with its basis ("40% of 21,000.00") — nothing re-read from today's
  structure;
- the attendance the run counted, and the employer's PF share, shown and
  never deducted;
- every payment against the payslip, oldest first;
- the **net in words**, from the same `AmountInWords` the invoice uses;
- bank account and wallet **masked to the last four digits** — the whole
  number does not belong on paper;
- a `printedAt` stamp so two printouts can be told apart.

**`PayslipPrint.jsx`** draws it on the invoice's own `.pd-*` classes: the
masthead with PAYSLIP and a Paid / Part Paid / Unpaid stamp read from the
figures; *Paid to* and the facts (period, run, status, approved by, paid
by); earnings on the left and deductions on the right of one table with the
two subtotals on one line; attendance beside the totals block — basic,
gross, less deductions, **net**, paid, balance due; the words; the payments;
three signature slots (Prepared / Received / Authorised); the footer with
the number and the print stamp. The overlay has the invoice's toolbar
(Close, Print) and is **rendered on `<body>`** through a portal, so the
print stylesheet can hide the shell and leave the sheet.

Two ways in: a print button on every payslip row of the run, and the
Print button in the payslip dialog — both open the same sheet.

### 26.3 `HrLines` — the editable grid

The expenses on a claim, the signatures on a chain and the components of a
structure were each a `.hr-component` row: five native inputs in a
flex-wrap, no headings, the delete button wrapping onto the next line, the
inputs unstyled because they sat outside `.hr-form`.

They are now one kit component, `HrLines`: a real table — headings once,
cells lined up under them, the row number on the left, the remove control
on the right, the same input shape as every other field in the module, an
*Add* button under it and an optional footed total ("Claimed 150.00"). On a
phone each row becomes a card and every cell wears its heading. The
per-line approval of a claim uses the same grid with static cells for what
cannot be edited and an input for what can.

Then a second pass, asked for again, on the two dialogs themselves:

- **A subtitle under the title** (`Modal` gained the prop): "Money somebody
  spent on the pharmacy's behalf, to be paid back — never through a
  payslip"; "Who has to sign a request of this kind, and in what order".
- **Sections** through `HrFormSection` — *Who is claiming* / *Expenses · n*;
  *The chain* / *Signatures, in order · n* — each with an icon and a
  one-line hint, ruled off from the next.
- **A summary panel** in the shape of the invoice's totals block — expenses,
  days covered, **claimed** — instead of two facts under the grid; and the
  footer's left edge says what the buttons are about to submit ("2 expenses
  · ৳150.00"; "2 signatures · every request").
- **The server's refusals, said first.** Submit is disabled with the reason
  in its tooltip until there is a person, at least one line, and every line
  has a kind, a date not in the future and an amount above nought; an
  amount typed as 0 or a future date turns the input red with one line of
  text under the grid. A fresh line is not called a mistake — the message
  appears only once something typed is wrong. The chain flags a signature
  that appears twice on both rows and in the preview, and disables Save.
- **The chain as it will run**: Request › ① The employee's own manager › ②
  Branch administrator › Approved — numbered chips joined by arrows, drawn
  from the rows as they are edited, so the order is seen rather than
  inferred. The label placeholder is the role's own name.
- The per-line approval dialog says what is being allowed of what in its
  footer, refuses a line allowed more than it claimed and a claim allowed
  nothing (that is a rejection) before the button is pressed, and takes
  remarks on one line.
- The long dashed policy notes became one-line notes with an icon — the
  dashed box is for pages, where there is room to read.

`.hr-component` is gone from the stylesheet.

### 26.4 Verified

`verify-payslip-print.mjs`, 40 checks, reads only: the document's earnings
sum to the gross, its deductions to the total, gross − deductions = net =
the run line, the words are present, a foreign line id is 404; the preview
opens on `<body>`, the app shell is `display:none` in print media and the
sheet is not, the PDF is 619KB with the whole payslip on it (was 1.2KB,
blank), Escape closes it, the dialog's Print reaches the same sheet; the
claim grid has headed columns, adds, foots and removes a row, its inputs
carry the form's 8px radius, an untouched line is not flagged, a 0 is,
Submit stays disabled until somebody is chosen; the chain grid has headed
columns and labelled fields, the preview shows one step, a duplicate
signature is flagged and blocks Save, changing it clears both; no sideways scroll on a phone; no console errors. Then
`qa3` across every HR tab, the field audit, unused imports, the build.

### 26.5 Noticed, not changed

`PrintDocument.jsx` (the sales invoice) renders inside `.app-shell`, not on
`<body>`; its print rules make the backdrop static but do not hide the page
behind it. Whether the page prints after the sheet was not measured here —
the Sales screen's Print buttons are on a tab this pass did not reach. The
same portal fix applies if it does.

---

## 27. The employee entry form

*Asked for as "interactive and standard employee entry form on hr page with
professional UI & UX and information with photo upload option".*

**One front for two halves that already existed.** A staff record lives on
`/api/employees` (Master Data: code, name, role, department, designation,
phone, address) and on `/api/v1/hr/employees/{id}` (HR: personal detail,
employment terms, pay basis, photograph). `EmployeeEntryForm`
(`src/pages/hr/EmployeeEntryForm.jsx`) writes through both — the same
endpoints the Master Data screen and the HR profile use, extended only by
`empType` on the Master Data service call — so nothing is stored twice and
no second employee API exists. The HR page's old "Edit record" dialog
(personal + employment only) was retired in its favour; "Pay basis" stays
as the quick edit it was.

**Four steps and a review**, on the kit's `HrStepper`: *Identity & role*
(photograph, code, name, role, gender, department, designation, phone,
email, vehicle when the role is delivery, status when editing, address) ·
*Personal* (preferred name, born, marital status, blood group, national ID,
emergency contact, parents, present address) · *Employment* (joined,
confirmed, type, grade, probation, notice, reports-to through the employee
picker, shift, PF/gratuity/tax applicability) · *Pay & bank* (basic,
structure, paid by, bank or wallet — offered only with Edit on Employees) ·
*Review*, the record read back section by section, each with its own Edit,
and the first thing still to fix named with a jump to it.

**Validation where it helps.** Inline errors on blur (a phone that does not
look like one, an email that does not, a code outside 2–20 characters, a
vehicle number required for a delivery man, a birth date after the joining
date, confirmation before joining, bank fields when paid by bank, a wallet
when paid by mobile). Next stays put on a step with a problem and says
which; the stepper flags the steps still wrong from the review.

**The photograph.** A drop zone with a live preview (a local object URL,
never a broken image), Replace and Remove, JPEG/PNG/WebP under 5 MB checked
on the client as a courtesy — the server keeps its own type, magic-byte and
size checks. For a new employee the file is held and uploaded after the
record exists; when editing it uploads at once through the existing
`/employees/{id}/image` route.

**The save is one sequence** — identity, then details, then pay, then the
photograph — and a part refused mid-way is said by name, the form stays open
on that step, and a record already created is kept and named so it can be
finished from the staff list rather than typed again. Unsaved changes ask
before closing. The modal's footer is now sticky, so Back/Next/Save stay in
view on a long step (a global change to `.modal-foot`).

**Verified** — `verify-employee-form.mjs`, 19 checks, all passing: an
employee added through the four steps with a photograph; the identity half,
the HR detail, the pay basis and the image path all landed in
`HR_EMPLOYEES`; the profile opened on the new record; editing showed the
photograph on record, jumped straight to Review, and saved the changed
name; the row and its image removed, counts back to baseline; no console
errors. Then the console sweep across every HR tab and the build.

---

## 28. HR reports and HR master data, into the HR group

*Asked for as "separate HR module all reports from Reports to move on Human
Resources module & separate HR master data like Department, Designation &
omit Employee page to Human Resources master menu".*

**Two modules, one migration** (`V87__hr_reports_and_master_modules.sql`):

| | route | under | who |
|---|---|---|---|
| **MOD40 HR Master** | `/hrmaster` | Human Resources, first | ROLE01, ROLE02 |
| **MOD39 HR Reports** | `/hrreports` | Human Resources, last | ROLE01, ROLE02 full; ROLE04 view, export, print |

Registered the way V77, V86 registered theirs — the row, the role grants,
the plan ceiling, the onboarding templates. Nothing dropped or renamed.

**HR Reports** is the general Reports screen with a different registry and
heading: the same index, viewer, filter bar and CSV export (`Reports.jsx`
took `groups / permission / title / subtitle` props; `HrReports.jsx` is
eight lines). All **25** reports the server catalogues are listed — the
general screen had shown 20 — in four groups: *People*, *Attendance &
Leave*, *Payroll*, *Money & Benefits*. They are gone from the general
Reports screen. **The server now checks View on HR Reports for any `hr-`
id** (`HrReportServiceImpl.run`), so a role that could see payroll only
because it could see sales reports cannot any more; the Payroll screen's
exceptions panel still runs for the payroll role, which holds the right.

**HR Master** is the Master Data engine (`MasterData.jsx`) opened on a third
door, `page="hrmaster"`, offering Department and Designation — the same
config entries, the same `/api/departments` and `/api/designations`, moved
from `page: "master"`. The page's heading now comes from a per-door copy
table, and a page with one group draws no group tabs. Master Data keeps
Branches, Products, Packaging and Parties and no longer has an HR tab.

**The Employee list left Master Data.** The HR Employees screen enters and
edits a record in full (§27), photograph included; two forms over one row
is how the two quietly disagree. `/api/employees` is unchanged — the
delivery list, the invoice salesperson and every picker still read it.

**Verified** — `verify-hr-split.mjs`, 19 checks, reads only: V87 applied
and both modules registered with their grants; the menu shows HR Master
first and HR Reports last under Human Resources; `hr-employees` and the
payroll exceptions report run; HR Reports lists 25 in four groups and opens
one in the viewer; the general Reports screen has no HR group; HR Master
offers Department and Designation and lists them; Master Data has no HR tab
and no Employee list; no console errors. Then the console sweep across
every HR tab and the layout sweep, now 31 pages × 4 widths.

---

## 29. The menu seed that runs on production

*Asked for as "menu seed migration to production — create flyway for all
menu".*

**The gap.** Flyway is baselined at 63, so V1–V63 never run anywhere; the
files that seeded MOD01–MOD30 and the menu groups (V33, V36, V38, V39,
V48, V52, V54, V55, V57, V59) did nothing on production, while V64 onward —
Branch Access Control and every HR module — inserted rows that hang from a
`GRP_HR` that may not exist there.

**`V88__menu_seed_for_every_environment.sql`**, generated from the
development database (`gen-menu-seed.mjs`), is the whole menu as the code
expects it, written to be a no-op where a row exists and a seed where none
does — 45 modules keyed by code (groups first), a parent/route/icon repair
only where NULL, the 9 roles keyed by code, the 207-row role × module
matrix exactly as development holds it, the 3 plan templates and their 102
lines, and plan ceilings for a narrowed branch taken from **its own plan's
template** and **only for modules this file inserted** — so a Basic
pharmacy does not gain purchase or the platform console. Ids come from
`nextval()`; six sequences are created if absent and realigned first.
Nothing is deleted, renamed or overwritten; a branch whose plan has no
template is listed by the verification query rather than guessed at.

**Rehearsed** in a rolled-back transaction on dev three ways — production-
shaped (10 orphaned V64+ modules → 45 with parents, 207, 3, 102, sequence
aligned), production-shaped with narrowed Basic branches (each gained
exactly the Basic template's share of the 35 new modules; the MAX-plan
branch, which has no template, gained nothing and was listed), and dev as-
is (no change at all) — then applied on dev by Flyway: `88 | success |
246ms`, every count unchanged, no row touched.

**Regenerate before it ships** if the development matrix changes: the file
is a snapshot, and the generator rewrites it in one command.

---

## 30. IGTA and buy price on the catalogue

*Asked for as "On Global product need IGTA and Buy Price column from
POS_PRODUCT_INFO … when user add product from branch catalogue page need to
insert product with two new column value".*

**`V89__global_product_igta_buy_price.sql`** adds `IGTA` and `BUY_PRICE`
to `GLOBAL_PRODUCT`, nullable, mirroring the two columns
`POS_PRODUCT_INFO` has always carried (IGTA = VAT on the trade price per
piece; buy price = TP + IGTA, what a purchase line is valued at). A narrow
backfill: where every pharmacy holding a catalogue medicine agrees on both
figures the catalogue takes them; otherwise NULL, never a guess. Only NULLs
are filled. (On dev Hibernate created the columns a moment before Flyway
did, at its default scale; the `ADD COLUMN IF NOT EXISTS` then did
nothing. Either way the columns exist and behave the same.)

**Backend**: `GlobalProduct` gained the two fields; both catalogue row
mappers (`/api/admin/global-products`, `/api/catalogue`) return `igta` and
`buyPrice`; **`importInto` copies both onto the pharmacy's new
`POS_PRODUCT_INFO` row** beside the suggested prices, so an imported
medicine can be bought without somebody typing its VAT first. The pharmacy
owns its copy from then on, as it owns the prices.

**Screens**: Medicine Catalogue and Branch Catalogue tables show IGTA and
Buy Price after Suggested TP; the catalogue form has both fields, and Buy
Price follows TP + IGTA — the same arithmetic Product Master does on the
server — until the operator types a figure of their own.

**Verified** — `verify-igta.mjs`, 16 checks, one ZZ entry created,
imported and removed: columns present, V89 applied (4 entries backfilled
from unanimous pharmacies), create/edit round-trip both fields, the branch
catalogue endpoint returns them, the import writes `tp 10 · mrp 12 · igta 2
· buy 12` onto the pharmacy's row and Product Master reads them, both
tables show the columns, the form sums and then stops summing, no console
errors; then the layout sweep.

---

## 31. IGTA and buy price on the Product Master bulk template

*Asked for as "now do the same on Product Master bulk import template".*

The template gained **IGTA** and **Buy Price** after *VAT %*, both optional:
a blank cell is calculated as before (IGTA = TP × VAT / 100, Buy Price =
TP + IGTA); a typed value is stored as typed; a Buy Price left blank
beside a typed IGTA is TP + that IGTA, so the row stays coherent. TP Price
and Product Code stay server-owned and are still ignored if typed. The
Example sheet's second row types both; the Instructions sheet states the
rule; the import screen's note says the same; the product export gained an
IGTA column beside Buy Price.

**Where the override lives.** `ProductMasterRequest` — the single-product
form's payload — still has no `igta` / `buyPrice`, on purpose: a screen
cannot send them, and the API refuses a body that tries (400). The bulk row
(`ProductBulkRequest.Row`) carries them, and the bulk importer hands them
to `ProductService.create / update(request, PriceOverride)` — a small
record on the interface that the two methods apply after the calculation.
The single-product path calls the same methods with `PriceOverride.NONE`.

**One thing to know:** a product imported with typed figures and later
*edited on the single-product form* is recalculated from MRP, conversion
rate and VAT, because that form still has no fields for them — the same as
TP has always been. Re-import with *Update existing* to reassert typed
figures.

**Verified** — `verify-bulk-igta.mjs`, 10 checks: the downloaded template
has the two headers in place and an example row using them with every
column still aligned; the Instructions and the screen state the rule; three
rows imported — typed both → `7 / 97`, typed IGTA only → `3 / 93`
(TP 90 + 3), neither → `4.5 / 94.5` (calculated); the single-product API
still refuses the fields; the three ZZ products removed.

## 32. Product pictures in a folder per branch

*Asked for as "product_image for Medicine Catalogue: create new branch ID
wise directory and save image on that directory like product_image/branchID;
productmaster page insert & update image on new directory; product add from
branchcatalogue page save on that directory; when new branch create
automatically create directory on product_image/BranchId".*

Every pharmacy now keeps its product pictures in its own folder under the
upload root, named after the branch id — `product_image/7/201.png`, stored
in `POS_PRODUCT_INFO.PRODUCT_IMAGE` as `7/201.png`. The Medicine
Catalogue's own pictures stay at the root (`215.png`): they belong to no
pharmacy. Nothing moved on disk; a name written before the folders existed
is still a bare filename, still resolves, and is replaced by a file in the
branch folder on the pharmacy's next upload.

**What changed, and where.**

- `ProductImageStorageService` — `store(file, productId, branchId)`,
  `copyOf(storedName, branchId)`, `copyAs(storedName, productId, branchId)`
  each place the file in `<root>/<branchId>/`, creating the folder before
  the write; the old signatures remain and mean "at the root".
  `ensureBranchDirectory(branchId)` makes the folder (best-effort, never
  fails a branch). `isSuspicious` now admits exactly two shapes — a bare
  filename, or one all-digit folder over a bare filename — and every reader
  (`delete`, `copyOf`, `copyAs`, `toPublicUrl`) resolves through it, so a
  hand-edited column with `..`, a backslash, a second folder or a folder
  that is not a branch id is refused rather than followed. At startup the
  service makes the folder for every branch that already exists
  (`AllBranchRepository.findAllBranchIds`), so the layout reads the same for
  old and new pharmacies.
- `ProductServiceImpl.uploadImage` passes `product.getPidBranch()` — the
  product's own branch, never a client value — so Product Master's insert
  and update land in the folder. `deleteImage` and product `delete` follow
  the stored name, wherever it points.
- `GlobalProductServiceImpl.importInto` copies the catalogue picture with
  `copyOf(name, branch.getPidBranch())`, so a product added from Branch
  Catalogue gets `<branch>/<uuid>.png`; the catalogue's file is untouched.
- `MasterDataServiceImpl.createBranch` and `TenantOnboardingService`
  (the branch made when a subscription is approved) call
  `ensureBranchDirectory` the moment the row is saved.
- `ProductImageBackfillService` places its copies in the branch folder too.
- The static handler needed nothing: its pattern is
  `/uploads/product_image/**`, so `/uploads/product_image/7/201.png` is
  served — cached an hour, stamped `?v=mtime` as before.

**The screens needed nothing.** Product Master and Branch Catalogue never
built a path themselves — they render the URL the server returns
(`toPublicUrl`) — so the folder appears in the URL and both pages keep
working unchanged.

**Verified** — `verify-image-dirs.mjs`, 30 checks: the startup pass made
`product_image/1`; a ZZ product's upload stored `1/<id>.png`, on disk in
the folder and not at the root, served through the folder (200 image/png);
a replacement with another extension landed beside it and the old file
went; delete removed the file and cleared the column; a ZZ catalogue
entry's picture stayed at the root, its import copied to
`1/<uuid>.png` while the catalogue's file stayed; `POST /api/branches`
created a branch and `product_image/<newId>` existed at once; a `..` typed
into the column was not resolved (no stamp) and the real name resolved
again; Product Master rendered the picture from the branch folder and the
browser loaded it; counts, the root's entry count and the branch folder
back to baseline. Onboarding's branch runs through the same
`ensureBranchDirectory` call, checked by compilation only — no ZZ tenant
was registered.

**Not changed:** the magic-byte check still asks "is this an image", not
"is this the declared type" — a PNG uploaded as `.jpg` is accepted and
stored as `.jpg`, as before this work.
