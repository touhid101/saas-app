# Investor accounting

Screen: **Accounts › Investors** (MOD48, `/accinvestors`). Service:
`AccInvestorService`. Tables: `acc_investor`, `acc_investor_txn` (V92).

An investor is a party. Their capital is not a table of balances but the
**party sub-ledger of 3200 Investor Capital**: every investment, withdrawal
and adjustment is a journal line carrying `party_type = INVESTOR` and the
investor's id, so the capital balance, the capital statement and the
distribution calculations all read the same ledger everyone else reads.
Profit owed is the same on 2400 Investor Profit Payable.

## Investor master (`acc_investor`)

Code (generated `INV-nnn` if blank, unique per organisation), name, contact,
address, id reference, investment type (EQUITY / LOAN / …), start/end dates,
`share_pct` (used only by the CUSTOM_APPROVED_METHOD policy), optional branch
(a branch-scoped investor takes part only in that branch's distributions),
status ACTIVE / INACTIVE / EXITED, notes. Nothing is deleted; an investor with
history is set INACTIVE or EXITED and refuses new transactions.

The list shows, per investor and from the ledger: capital (3200 balance),
profit payable (2400 balance), total invested, withdrawn, profit allocated and
paid (from the transactions).

## Transactions (`acc_investor_txn`)

Number `IV` + yyMM + 5 digits (`SEQ_INVESTOR_TXN_NO`). Types a user can record:

| Type | Direction | Event when posted | Journal |
|---|---|---|---|
| INITIAL_INVESTMENT, ADDITIONAL_INVESTMENT | in | INVESTOR_INVESTMENT | Dr cash (by payment mode) / Cr 3200 (party) |
| CAPITAL_WITHDRAWAL, REFUND | out | INVESTOR_WITHDRAWAL | Dr 3200 (party) / Cr cash — **refused if amount > the investor's capital balance** |
| CAPITAL_ADJUSTMENT, OTHER_APPROVED_ADJUSTMENT | ± (direction chosen) | INVESTOR_ADJUSTMENT_UP / DOWN | against 3900 Opening Balance Equity — **a reason is mandatory** |
| PROFIT_DISTRIBUTION | out | PROFIT_PAID | Dr 2400 (party) / Cr cash — **must name a POSTED/PARTIAL allocation and cannot exceed what is still unpaid on it** |
| PROFIT_ALLOCATION | — | (created by a posted distribution, not by hand) | see [profit-distribution.md](profit-distribution.md) |

Workflow: **DRAFT → POSTED** (Approve; approve-and-post is one step for a
user holding Approve; a user with Create only leaves it DRAFT), or
**CANCELLED** with a reason. Cancelling a POSTED transaction reverses its
journal; a PROFIT_ALLOCATION is cancelled only through its distribution.
Amount must be > 0; the investor must be ACTIVE; a cash-mode transaction is
subject to the branch day guard like any other cash posting (an investment
dated on a closed day is refused).

Paying a profit allocation updates the allocation's `paid_amount` and status
(POSTED → PARTIAL → PAID) and, when every allocation of the distribution is
paid, the distribution becomes PAID.

## Statement and reports

`GET /investors/{id}?from&to` returns the investor, capital and payable
balances, and the two statements (3200 and 2400 party ledgers with running
balances). Reports: `acc-investor-statement`, `acc-investor-capital`
(opening, in, out, closing per investor), `acc-investor-profit` (allocated,
paid, owed), `acc-profit-distribution`.

## Controls

* Rights: MOD48 view/create/edit/approve. Only Approve posts or cancels.
* Every posted transaction is a journal; the journal is immutable; the
  transaction row keeps `pid_journal` and `pid_event`.
* Capital cannot go negative through a withdrawal; adjustments can take it
  anywhere but need a reason and Approve, and are visible as such.
* Branch-scoped investors see only their branch's distributions.
