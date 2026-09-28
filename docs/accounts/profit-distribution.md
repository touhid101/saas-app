# Profit distribution

Screen: **Accounts › Profit Distribution** (MOD49, `/accprofit`). Service:
`AccProfitDistributionService`. Tables: `acc_profit_policy`,
`acc_profit_policy_line`, `acc_profit_distribution`, `acc_profit_allocation`
(V92). Unit tests: `AccProfitAllocationTest`.

A distribution takes a period's accounting profit from the ledger, applies a
policy, allocates the investors' pool between them, and — only once approved —
posts the liability. Nothing is estimated and nothing is entered by hand
except an explicit, reasoned adjustment.

## Policies (`acc_profit_policy`)

| Field | Meaning |
|---|---|
| `share_method` | one of the five methods below |
| `investor_pool_pct` | the share of distributable profit that goes to investors (default 100; the rest stays with the owner) |
| `retained_pct` | the share of profit retained by the business before anything is distributed (default 0) |
| `effective_from / to`, `is_active`, optional branch | the policy in force is the active one whose dates cover the period end (branch-specific first) |
| lines (`pid_investor`, `share_pct`) | only for INVESTOR_SPECIFIC_PERCENTAGE; must add up to ≤ 100 |

Percentages are validated 0–100. A policy is never deleted; it is retired.

## Methods

| Method | Share of investor *i* |
|---|---|
| **FIXED_PERCENTAGE** | same maths as CAPITAL_RATIO (share by closing capital) — the fixed split is expressed through the policy's pool/retained percentages and the investors' capital |
| **CAPITAL_RATIO** | `eligible_i / Σ eligible`, where eligible = capital balance at period end (max 0) |
| **INVESTOR_SPECIFIC_PERCENTAGE** | `share_pct_i` from the policy lines; an investor without a line gets 0; the pool need not be fully allocated |
| **WEIGHTED_AVERAGE_CAPITAL** | `avg_i / Σ avg`, where avg = time-weighted average capital over the period (each balance × the days it was held ÷ days in period) |
| **CUSTOM_APPROVED_METHOD** | `share_pct` on the investor master, exactly as approved by management |

For the ratio methods (FIXED_PERCENTAGE, CAPITAL_RATIO, WEIGHTED_AVERAGE_CAPITAL) percentages are held to 4 decimals and the **last investor takes the rounding remainder** so the allocations add up to the pool exactly. For the percentage methods the allocations are rounded independently and may leave part of the pool unallocated; allocations above the pool are refused (`ACC_OVER_ALLOCATED`).

Eligibility: investor ACTIVE; and, for a branch distribution, either
unscoped or scoped to that branch. No eligible investor → `ACC_NO_INVESTORS`.

## The calculation (`calculate`)

Inputs: period `from`–`to` (not in the future), optional branch, optional
policy (else the one in force), optional `adjustment` with a mandatory reason,
notes. A period is distributed **once**: an overlapping distribution that is
not CANCELLED refuses (`ACC_DIST_DUPLICATE`). A DRAFT/CALCULATED distribution
can be recalculated; anything further is frozen (`ACC_DIST_FROZEN`).

From the ledger (`AccLedgerService.totalsByType` and `profitAndLoss`) for the
period and branch:

```
revenue          = Σ REVENUE accounts except OTHER_INCOME
other_income     = OTHER_INCOME
cost_of_sales    = COGS
operating_exp    = every EXPENSE except COGS, OTHER_EXPENSE, CASH_SHORT_OVER, BANK_CHARGES
other_expense    = OTHER_EXPENSE + CASH_SHORT_OVER + BANK_CHARGES
accounting_profit = Σ REVENUE − Σ EXPENSE            (2 dp)

retained         = profit > 0 ? profit × retained_pct / 100 : 0
distributable    = max(0, profit − retained + adjustment)
pool             = distributable × investor_pool_pct / 100
allocation_i     = pool × share_i / 100                (2 dp; remainder rule above)
```

Everything is stored on the distribution row (revenue, cost of sales,
operating expenses, other income/expense, accounting profit, retained %, and
amount, adjustment + reason, distributable, pool %, allocated total) together
with a JSON **snapshot** of the per-investor figures (opening capital,
additions, withdrawals, closing, average, eligible, share %, allocated) so the
approved numbers can be shown later exactly as they were, even if the ledger
moves. A loss period yields a distributable of 0 and posting is refused
("Nothing to post").

## Workflow

```
DRAFT → CALCULATED → REVIEWED → APPROVED → POSTED → PAID
                                   └──────── CANCELLED (reason) ────────┘
```

| Step | Right | What happens |
|---|---|---|
| calculate | Create | number `PD` + yyMM + 5 digits; figures and allocations (DRAFT rows) written; status CALCULATED |
| review | Edit | reviewer stamped |
| approve | Approve | approver stamped; figures frozen |
| post | Approve | one `PROFIT_ALLOCATED` journal: Dr 3400 Profit Distributed (total) / Cr 2400 Investor Profit Payable, one line per investor; one PROFIT_ALLOCATION investor transaction per investor (POSTED, linked to the allocation); allocations → POSTED; distribution → POSTED |
| pay | Investors screen | a PROFIT_DISTRIBUTION transaction against an allocation: Dr 2400 (party) / Cr cash; allocation → PARTIAL/PAID; distribution → PAID when all are |
| cancel | Approve, reason | before posting: status only. After posting: **refused if anything has been paid**; otherwise the journal is reversed, the allocation transactions cancelled, allocations back to DRAFT, status CANCELLED. The period is then free to be distributed again. |

## What the screen shows

The distribution drawer walks the stepper, shows the P&L build-up
(revenue → cost of sales → gross → operating → other → profit → retained →
adjustment → distributable → pool) and the allocation table with the capital
figures per investor, the who/when of each step, and the journal once posted.
Policies have their own tab with the method, percentages, dates and lines.

Reports: `acc-profit-distribution` (one row per allocation with the
distribution's figures), `acc-investor-profit`.
