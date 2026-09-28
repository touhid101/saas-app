# Branch daily cash control

Screen: **Accounts › Daily Cash** (module MOD47, route `/accdailycash`).
Service: `AccDailyCashService`. Tables: `acc_business_day`, `acc_cash_count`,
`acc_business_day_audit`, `acc_denomination` (V91).

The purpose is simple: at the end of every day each branch proves that the
cash in the drawer equals what the ledger says should be there, and explains
any difference before the day is signed off. Nothing here changes the ledger
except, by policy, the variance itself.

## The seven steps on the screen

```
 1 Open the day  →  2 Cash movement (from the ledger)  →  3 Count physical cash
 →  4 Expected vs counted  →  5 Close (reason if needed)  →  6 Review (optional)  →  7 Locked / Reopen
```

## Definitions

| Figure | Definition | Where it comes from |
|---|---|---|
| **Opening cash** | The previous **closed** day's *physical* (counted) cash for this branch. Before any day has ever been closed: the ledger balance of the till (`CASH_REGISTER`) at the end of the previous date. | `open()`: `previousClosed(branch, date).physical_cash`, else `ledger.accountBalance(till, date − 1)` |
| **Cash in / Cash out** | Σ debits / Σ credits on the till account dated on the business date, from posted journals — cash sales, counter receipts, delivery cash handed in, cash payments, transfers. | `ledger.dayMovement` over `acc_v_ledger` |
| **Expected closing** | `opening + cash in − cash out` | computed, refreshed from the ledger at count and close |
| **Physical cash** | What was counted: Σ `denomination × quantity`, or a total typed in. | `count()` → `acc_cash_count` rows + `physical_cash` |
| **Difference** | `physical − expected` | `setDifference()` |
| **Kind** | `BALANCED` (0), `SHORT` (< 0), `OVER` (> 0) | `difference_kind` |
| **Tolerance** | setting `cash_tolerance` (default 0). A difference within tolerance can close without a reason. | `acc_setting` |

Denominations (BDT 1000, 500, 200, 100, 50, 20, 10, 5, 2, 1) are per
organisation in `acc_denomination`; the count grid shows the active ones.

## Rules

1. **One open day per branch at a time.** Opening a second day while one is
   OPEN/REOPENED/SUBMITTED is refused (`ACC_DAY_STILL_OPEN`): "Close 12/09
   before opening 13/09". The dashboard and the reconciliation centre list
   days not closed.
2. **No future days.** A day cannot be opened after today.
3. **A day exists once.** Opening a date that already has a sheet is refused.
4. **Counting** is allowed while OPEN or REOPENED (Edit right). A new count
   replaces the previous one; quantities cannot be negative; a count is
   required before closing.
5. **Closing** (Edit right) refreshes cash in/out from the ledger, computes the
   difference and:
   * if `|difference| > tolerance` and no reason was given → refused: "The
     count is short by 5.78. Say why before closing.";
   * if `closing_requires_review = Y` and the user lacks Approve → status
     **SUBMITTED**; someone with Approve closes it (step 6);
   * otherwise → **CLOSED**. If `post_cash_variance = Y` and the difference is
     not zero, a `CASH_VARIANCE_SHORT` / `CASH_VARIANCE_OVER` journal is posted
     (Dr/Cr Cash Short/Over 6500 against the till) dated on the day so the
     ledger's till equals the drawer; with `N` the ledger keeps the expected
     figure and the difference stays on the sheet only.
6. **After closing, the till is frozen for that date.** Any posting whose
   lines touch `CASH_REGISTER` dated on a CLOSED/SUBMITTED/LOCKED day is
   refused by the engine (`ACC_DAY_CLOSED`) — a cash sale, a receipt voucher,
   an investor's cash investment — or, when `late_posting_policy = NEXT_DAY`,
   is dated on the next day that is not closed. Bank and wallet postings are
   unaffected. `require_day_open = Y` additionally refuses cash postings on a
   date nobody has opened.
7. **Reopening** (Approve right) needs a reason, is only possible for a CLOSED
   day, and is refused when a later day exists for the branch
   (`ACC_DAY_HAS_SUCCESSOR`) because that later day took its opening from this
   closing. Reopening reverses a posted variance journal, clears the closing
   stamps, increments `reopen_count` and sets status REOPENED; the day is then
   counted and closed again (the new variance event key carries the reopen
   count so it is never confused with the first).
8. **Locking.** A day is LOCKED when its fiscal period is locked; nothing
   about it changes afterwards.
9. **Audit.** Every action writes `acc_business_day_audit` (OPENED, COUNTED,
   SUBMITTED, REVIEWED, VARIANCE_POSTED, CLOSED, REOPENED) with user, time and
   note; the screen shows it as a timeline.

## Reading the sheet (API `GET /api/v1/accounts/daily-cash/current`)

`status` NOT_STARTED / OPEN / SUBMITTED / CLOSED / REOPENED / LOCKED;
`previousClosing`, `previousDate`, `ledgerOpening`, `openingCash`,
`openingSource` (previous closing or ledger); `cashIn`, `cashOut`,
`expectedClosing`, `physicalCash`, `difference`, `differenceKind`;
`tolerance`, `requiresReview`, `postsVariance`; `movements` (every till line of
the day with source and journal); `counts`, `denominations`; `audit`;
`unclosedEarlier` (dates still open before this one); and the four
capabilities `canOpen / canCount / canClose / canReopen` computed from status
and the caller's rights, which the screen uses to enable its buttons.

`GET /daily-cash/book?from&to` is the cash book: one row per day with opening,
in, out, expected, counted, difference, status — the same figures the
`acc-daily-cash`, `acc-cash-closing` and `acc-cash-difference` reports print.

## Worked example (dev database, branch 1, 17/09/2026)

Opening 0.00 (no earlier closing; till ledger balance 0) → cash in 101.78
(a 100 opening voucher, a 2.67 cash sale receipt… as the movements list
shows) → cash out 40.00 (a cash payment voucher) → expected 61.78 → counted
56.00 → **SHORT 5.78** → tolerance 0 → reason required → closed. The
dashboard's "Cash shortages (period)" tile is Σ |SHORT| over the closed days
in the range, read from the day sheets.

## Settings that shape this screen

| Setting | Default | Effect |
|---|---|---|
| `cash_tolerance` | 0 | difference within it closes without a reason |
| `post_cash_variance` | N | Y posts the variance to 6500 so the ledger equals the drawer |
| `closing_requires_review` | N | Y makes closing a two-step submit/approve |
| `require_day_open` | N | Y refuses cash postings on a date not opened |
| `late_posting_policy` | REFUSE | NEXT_DAY moves a late cash posting forward instead of refusing |
