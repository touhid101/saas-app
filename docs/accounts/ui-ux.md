# Accounts — UI / UX

The twelve Accounts screens are built from the application's existing kit —
`HrKit.jsx` (page frame, KPI tiles, tables, filter bar, drawers, stepper,
timeline, status pills, pagination, skeletons, error states) and `UI.jsx`
(Modal) — with one small addition, `src/pages/accounts/AccKit.jsx`, for what a
ledger needs and the kit did not have. Typography, tokens and breakpoints are
the application's (Fraunces headings, Inter body, IBM Plex Mono for codes and
document numbers; the light / dark / blue themes from `styles.css`).
`src/accounts.css` is the only new stylesheet; everything in it is under
`.acc-*`, reads the theme tokens and names no colour of its own.

## Menu

Sidebar group **Accounts** (`GRP_ACCOUNTS`, V93), in the order a finance
person works: Accounts Dashboard · Journals · General Ledger · Chart of
Accounts · Receivables & Payables · Cash & Bank · Daily Cash · Investors ·
Profit Distribution · Periods & Settings · Accounts Reports · Reconciliation.
Each has its own 24-grid line icon in `Sidebar.jsx` (`ICON_PATHS`), drawn to
the weight of the HR set. Routes `/accounts`, `/accjournals`, `/accledger`,
`/acccoa`, `/accparties`, `/acccash`, `/accdailycash`, `/accinvestors`,
`/accprofit`, `/accsettings`, `/accreports`, `/accreconcile` (`App.jsx`
`PAGE_META` + render lines). A user sees only the modules their role holds.

## Screens

| Screen | What it is built from | Notes |
|---|---|---|
| **Accounts Dashboard** | `HrPage`, `Kpis/Kpi` ×2 rows, `hr-card` ×4 | Period figures (revenue, cost of sales, gross, expenses, net) and position as at the end date (12 tiles); today's daily-cash card with `Figure`s; the **control centre** list (failed/skipped events, drafts, unclosed days, cash short/over, pending distributions, current period, past periods still open) each with an Open button to the screen that fixes it; recent journals; largest receivables/payables. Quick range buttons Today / This month / This year. |
| **Journals** | `HrFilterBar`, `HrTable`, `HrPagination`, `HrDrawer`, `Modal` | Register with search/date/status/type/branch; drawer shows the lines table with Dr/Cr, party, source badge, the event and the reversal link; **New manual journal** modal with a lines grid and a live balance note (Dr − Cr) that blocks Save until zero; Post / Reverse (reason prompt) / Discard per rights. |
| **General Ledger** | `AccTabs` (Ledger · Trial balance), `Figure`s, `HrTable` | Account picker + range + branch + source + search; opening / debits / credits / closing figures; running balance column; CSV export. Trial balance with opening, movement and closing per account and a Dr = Cr foot. |
| **Chart of Accounts** | `HrTable` with depth-indented codes, `Modal` | Tree order, type, normal side, posting role as a mono chip, balance; add/edit account (parent, type, role, manual allowed, retire). |
| **Receivables & Payables** | `AccTabs` (Receivables · Payables · Staff · Tenants), `HrTable`, `HrDrawer` | Balances per party with an ageing toggle (0–30 / 31–60 / 61–90 / 90+); statement drawer with running balance and CSV. |
| **Cash & Bank** | `AccTabs` (Vouchers · Cash accounts · Bank accounts), `Kpis` positions, `HrTable`, `Modal` | Positions per cash/bank/wallet/petty/transit account; voucher register with kind/status filters; voucher form whose fields change with the kind (counter account for receipt/payment, to-account for transfer, reason mandatory for adjustments); approve / cancel. Bank accounts CRUD. |
| **Daily Cash** | `AccTabs` (Day sheet · Cash book), `Kpis` ×6, `HrStepper` (7 steps), `Figure`s, denomination grid, `HrTimeline` | The sheet reads top-to-bottom as the process: opening → movement (drill-down to journals) → count by denomination (amount per row, total) → expected vs counted → close (reason field appears when outside tolerance; submit/approve when review is on) → audit timeline. Reopen with a reason prompt. |
| **Investors** | `Kpis`, `HrTable`, `HrDrawer` with `AccTabs inline`, `Modal` | Capital, payable, invested/withdrawn/allocated/paid per investor from the ledger; drawer with capital ledger, transactions and profit share; investor form; transaction form (type-dependent fields, allocation picker for a profit payment). |
| **Profit Distribution** | `AccTabs` (Distributions · Policies), `HrTable`, `HrDrawer` with `HrStepper`, `Modal` | Start form (period, branch, policy, adjustment + reason); drawer shows the P&L build-up as `Figure`s, the allocation table with capital figures, the workflow stepper and the action for the next step (Review / Approve / Post / Cancel); policies tab with method-specific fields (percentage lines for investor-specific). |
| **Periods & Settings** | `AccTabs` ×6, `HrTable`, `PolicyNote`, settings list, `Modal` | Periods with Close / Reopen / Lock (lock asks for confirmation — it is final); policies as a labelled list with a one-line explanation each; payment-mode map; tax rates; cost centres (defining one makes the picker appear on the voucher form and on manual-journal lines — an empty list shows no control, because a picker with nothing in it is a control that does nothing); accounting rules with a per-line editor (side, role with datalist of the chart's roles, amount key, party, description). |
| **Accounts Reports** | the existing `Reports.jsx` with `ACC_REPORT_GROUPS` | Same index, viewer, filter bar, CSV and print as every other report screen; five groups, 30 reports; the filter bar shows only what a report honours. |
| **Reconciliation** | `AccTabs` (Event monitor · Reconciliation · Backfill history), `HrTable`, `Kpis`, integrity grid, `Modal` | Event monitor with status/module/branch/date filters, an event modal (payload, error, journal) and Retry on FAILED/SKIPPED; reconciliation checks table (documents vs events, business amount vs ledger amount, missing journals, difference, status pill) and the eight integrity counters; backfill runner (module, dates, batch size, dry run / post) with the batch history. |

## Polish pass (18/09/2026) — icons, interaction, alignment

* **Every page header carries its screen's icon** (`HrPage icon`, the same
  24-grid drawing as the sidebar), every tab strip and bottom bar carries an
  icon per section, and every KPI tile an icon in a tinted disc. The set lives
  in `AccKit.AI` so a header, a tab and a menu item say the same thing.
* **Dashboard is interactive.** A hero figure (net profit / loss for the
  window) with a delta against the window just before; four period tiles with
  deltas; two day-by-day charts (revenue vs expenses, cash in vs out) drawn in
  SVG with a crosshair tooltip, keyboard ← →, a legend and a "Show as table"
  view; "Where the cash is" and "Where the money went" bar lists; every tile,
  bar and control-centre row is a link that opens the right screen already
  filtered (`sendIntent` / `takeIntent` in AccKit — the ledger on that
  account and range, Receivables on that customer's statement, the event
  monitor on FAILED, a report with the range applied). Quick ranges: Today ·
  7 days · This month · Last month · This year. Refetch keeps the frame at
  reduced opacity instead of flashing a skeleton. The trend fills every day
  of the window with zero where nothing posted, so a quiet week reads quiet.
* **Chart colours are validated** with the dataviz six checks on both
  surfaces: light `#12907C` / `#B8752B`, dark `#2FA893` / `#E06B55`
  (tokens `--acc-chart-1/2`). Marks wear the colour; text never does.
* **Registers sort.** Journals, ledger, trial balance, receivables/payables
  and investors sort on their key columns (`sortable` + `sortValue`);
  aged buckets over 60 days are highlighted; a row hint ("Statement ›")
  appears on hover; a trial-balance row opens that account's ledger.
* **Empty states guide.** A register with nothing to show says why and offers
  the next action (New manual journal, New investor) instead of a bare line.
* **Cash & Bank** has a quick-create strip — one iconed button per voucher
  kind with a word of meaning ("money in", "between cash accounts").
* **Investors** gained a search and status filter.
* **Periods** show a fiscal-year picker, the current period highlighted, four
  KPIs (current period, past periods still open, locked, years) and Lock as a
  quiet, red-on-hover action rather than a primary button on every row.
* **Reports index** cards carry the group's icon and a one-line description
  of what the report answers (all report groups, not only Accounts); report
  summaries format amounts with thousands separators; the dashboard opens a
  report with its own range applied (`Reports initialReportId/initialFilters`).
* **Kit fix:** inside a filter bar `.hr-filter input` outranked
  `.hr-search-input`, so the search icon sat on the placeholder on every
  HR and Accounts screen — restored at the same specificity.
* **Recent journals** card packs date, source and description under the
  journal number so the amount and status never clip in a half-width card.

## Dashboard, second pass (18/09/2026)

* **Hero** now carries the margin (net ÷ revenue), a sparkline of net by day
  and a "where each ৳100 of revenue went" share bar (cost of sales / other
  expenses / net profit; each segment a link). Period tiles carry sparklines.
* **Charts**: legend items toggle a series; Daily / Cumulative; **Compare**
  overlays the previous window as dashed lines (tooltip shows "prev"); a
  draw-in animation on first paint (off under reduced motion). Backend adds
  `previousTrend`.
* **Today so far** strip whenever today is in the window (revenue, cash in,
  cash out, till status), zeros when nothing has posted yet.
* **Ageing card**: receivables and payables as single-hue light→dark bars
  (backend `ageing`), segments open the lists.
* **Branch by branch** (all-branches view only; backend `branches`): net
  profit bars + a mini table; clicking a branch focuses the whole dashboard.
* **Books health** badge on the control centre; **auto-refresh** (60 s) with an
  "Updated" stamp; **foldable cards** remembered in localStorage; count-up on
  the hero figure; tile hover lift.
* Palette extended and validated: light `#12907C/#B8752B/#6B5FB5/#A8452E`,
  dark `#2FA893/#E06B55/#8C7FE0/#B87A3E`; ageing ramp `--acc-age-1..4`.

## Numbers

`src/utils/financial.js` is the one formatter: `amount()` (৳ with two
decimals, `—` for null, parentheses on request), `compact()` for tiles,
`drcr()` (Dr/Cr suffix), `pct()`, `num()`, `variance()` (SHORT/OVER with the
amount), `fmtDate()`, `fmtDateTime()`. Every money cell is `.acc-money` or the
kit's `.hr-num`: tabular numerals, right-aligned, never wrapped. Status
pills use the kit's `Status` with the accounting codes added to
`hrService.toneFor` (BALANCED/ACTIVE/MATCHED/DONE sage; OPEN/RUNNING teal;
REOPENED/OVER/SKIPPED/AMOUNT_DIFFERS amber; LOCKED/REVERSED/SHORT/FAILED/
MISSING_JOURNALS/EXITED/INACTIVE clay).

## Responsive behaviour

Breakpoints are the kit's: ≥ 1200 full layout; 1024 (`≤ 1199.98`) the
two-column bands (`.acc-two`, `.acc-two-lists`) become one column and the
settings rows narrow; 768 keeps tables as tables with horizontal scroll inside
their card; < 768 (phone) the kit stacks each table row into label/value
pairs, forms go single-column, figures go two-up (the big one full width),
the control-centre buttons drop under their label, the top tab strip is
replaced by the kit's fixed bottom bar (`AccTabs` renders both; inside a
drawer it renders a select instead so the bar does not cover the drawer),
and the page keeps the kit's bottom padding so the last row clears the bar.
Denomination inputs shrink to 72px. No page scrolls sideways at 375.

Motion follows `prefers-reduced-motion` (the kit's transitions and the two
added here switch off). Print hides actions and quick buttons and draws
figures with a plain border.

## Accessibility

Every control is a real `button`/`input`/`select` with a label (`aria-label`
on denomination counts, `sr-only` label on the phone section select); the
tab strips are `role="tablist"`; tables are the kit's with `scope="col"`
headers; keyboard focus is
visible (kit outlines on rows, buttons and the control-centre buttons);
status is conveyed by text as well as colour (pill text, SHORT/OVER words,
Dr/Cr suffixes); errors are sentences from the server, shown in the kit's
toast and error state, never codes alone.

## QA findings and fixes (Playwright drive, 17/09/2026)

| Found | Fix |
|---|---|
| Half-width journal tables wrapped dates and document numbers at 1440 | `.acc-page` table rules: first column, lead cells, numbers and status pills never wrap; descriptions give way |
| Dashboard two-column band overflowed at 1024 | two-column bands collapse at < 1200 instead of < 1024; list cells get `min-width: 0` |
| Tab strips vanished on phones (the kit hides `.hr-tabbar` there) and the investor drawer's tabs vanished on desktop (it used the phone bar) | `AccTabs` renders the strip and the bottom bar together; `inline` variant with a select for drawers |
| Journal-line descriptions showed the rule's generic note ("Till, bank or wallet by payment mode") instead of the document | engine now prefers the event's description; the rule text is the fallback |
| Recounting a day failed with a unique-key error (Hibernate flushes inserts before deletes) | `flush()` between delete and re-insert in `AccDailyCashService.count` |
| Event monitor crashed on load (`usePagedList` exposes `rows`, the page read `items`) | prop corrected; the page QA now covers every tab of every page |
| Chart provisioning for a **new** organisation failed (`normal_side` VARCHAR(1) comes back as `Character`) — found by the onboarding regression suite | `String.valueOf` on that column |

Screenshots of every page at every width are in the session scratchpad
(`acc/*.jpg`); the 26 pages of the base-module manual show the same kit for
comparison.
