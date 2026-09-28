# Migration and reconciliation — bringing history into the ledger

*Dev database `minipos_db` · organisation 1 (Friends Pharma) · run on 17/09/2026*

The books started empty on 17/09/2026. Everything that happened before was
brought in by the **controlled backfill** — the Reconciliation screen's
"Backfill history" tab, or `POST /api/v1/accounts/backfill` — which posts each
historical sale, sales return, approved purchase, purchase return and supplier
payment through the *same* hook methods live transactions use, dated when the
document happened, under a numbered batch, one document per database
transaction, never twice (the event identity refuses a duplicate). This
document records what was run, what the ledger now says, how it compares with
the business tables, and what remains to be decided.

## 1. How the backfill was run

| | |
|---|---|
| Command | `scratchpad/backfill-loop.mjs 1` → `POST /backfill {orgId: 1, limit: 5000, dryRun: false}` in a loop until a batch scans 0 |
| Scope | all modules, all dates, all branches of organisation 1 |
| Order | documents by date, then id — so receivables and payables age correctly |
| Batches | **50** (`acc_backfill_batch` 1–50), 5000 documents each; 15:35 → 20:12 local |
| Throughput | ≈ 45 documents/s after the V94 index and the account/rule/period caches (6/s before) |
| Interruption | batch 45 was cut when the application process was restarted mid-run; 3,565 of its documents were already POSTED (each is its own transaction), the batch row was closed as FAILED with that note, and batch 46 picked up the rest. Nothing was posted twice. |
| Result | **scanned 202,107 · posted 201,857 · skipped 250 · failed 0** |

Skipped = zero-value documents (19 approved purchases with `amount_tobe_paid = 0`) and events whose rule produced no lines; they are recorded SKIPPED with the reason and can be replayed from the event monitor if the documents are ever amended.

## 2. Documents versus events (organisation 1, all dates)

| Business documents | Count | Amount (documents) | Posted events | Ledger amount | Difference | Explanation |
|---|---:|---:|---:|---:|---:|---|
| Sales invoices (`SL`, status Y, not merged) | 140,391 | 14,500,405.30 | 140,391 | 14,500,384.05 (revenue) | 21.25 | per-document 2 dp rounding of 4 dp source amounts; one invoice reversed and re-posted after an edit (1.78) |
| Sales returns (`SR`) | 14,004 | 279,865.49 | 14,004 | 279,868.35 | −2.86 | rounding |
| Purchases (`PU`, approved) | 40,030 | 3,382,876.11 | 40,011 (+19 skipped) | 3,382,880.19 (AP) | −4.08 | rounding; 19 zero-value purchases skipped |
| Purchase returns (`PR`) | 6,002 | 98,576.88 | 6,002 | 98,577.62 | −0.74 | rounding |
| Supplier payments (`PAYMENT` entries) | 9 | 6,599.44 | 9 | 6,599.44 | 0.00 | the two advance-recovery entries (49.08) are a separate event type and are not payments |

Every document has its event; every posted event has its journal
(`postedEventsWithoutJournal = 0`).

## 3. Ledger integrity

| Check | Result |
|---|---|
| Σ debit = Σ credit over every posted line (975,902 lines) | **43,901,503.42 = 43,901,503.42, difference 0.00** |
| Journals whose header ≠ lines, unbalanced non-draft journals | 0 |
| Lines on group accounts, lines on another organisation's books | 0 |
| Journals posted after their period was locked | 0 |
| Failed events | 28 — all deliberate refusals by the verification suites (cash postings dated on a closed day, an adjustment without a reason, a claim paid on a closed day); none from the backfill |
| Trial balance (Ledger › Trial balance) | closing Dr = Cr |

Journals 2024-08-26 … 2026-09-17: 333,547 (organisation 1: 333,235 of them from the backfill).

## 4. Business balances versus the ledger (as at 17/09/2026)

| | Business tables | Ledger | Difference | Explanation |
|---|---:|---:|---:|---|
| **Customer dues** — Σ (payable − given) on sales invoices vs **AR** | 1,243,394.97 | 1,199,983.17 | 43,411.80 | (a) 2,000 sales returns with payment type CREDIT (44,353.42) credit the customer's account in the ledger but do not change the invoice's `given_amount`; (b) 962.29 of `given_amount` above the invoice total is capped by the receipt rule (an overpayment is not a receivable movement); (c) 21 of rounding. 44,353.42 − 962.29 − 21 ≈ 43,370 → residual ≈ 40, rounding over 140k documents. |
| **Supplier outstanding** — `v_supplier_account_branch` vs **AP − supplier advances** | 3,375,717.72 | 3,339,105.11 | 36,612.61 | 2,000 purchase returns carry payment type **CREDIT** (36,577.98): the ledger books them as supplier credit (Dr AP), the business view nets only `ADJUSTMENT` returns against the payable and ignores CREDIT ones. Residual ≈ 35 = the advance part of payments (106.13, in Supplier Advances) and rounding. **BUSINESS POLICY CONFIRMATION REQUIRED (BR-028):** what a CREDIT-type purchase return means — if it is a credit note against the payable the ledger is right and the view is short; if it is nothing until applied, the rule line should point CREDIT at Supplier Advances instead. |
| **Inventory** — Σ batch qty × cost vs **Inventory account** | 735,126.30 | −8,435,054.62 | 9,170,180.92 | The ledger values stock only from documents: purchases in (3.61M incl. returns) and cost of sales out (11.95M). The stock sold came largely from batches that entered the system without a purchase document (opening stock / seeded batches), so the ledger's inventory runs negative. **This is expected on go-live and is corrected by one OPENING journal** — Dr Inventory / Cr Opening Balance Equity for the counted stock value at the cut-over date (see §6). Not posted automatically: the figure must be a counted, approved stock value. |
| **Cost of goods sold** — Σ SALE stock movements at cost vs **COGS from sale journals** | 11,949,949.76 | 11,949,953.85 | −4.09 | rounding. (Movements of type ADJUST that reference a sale — 6,374.32 — are stock corrections, not cost of sale, and are correctly not in COGS.) |
| **Cash** — till / bank / wallets | n/a | 7,319,122.63 / 2,770,663.89 / 3,131,864.67 | | Historical receipts were never banked, spent or counted in any table, so the ledger's cash is Σ receipts − Σ payments since 2024-08-26. The branch daily-cash control fixes the real drawer from the first day it is closed (opening cash = counted cash); the bank and wallet balances need an OPENING voucher each at cut-over. |

## 5. What the reconciliation centre shows

`GET /api/v1/accounts/reconciliation` (Reconciliation › Reconciliation) runs
the checks in §2 and §4 live for any range and branch, plus the eight
integrity counters. Two defects in its own SQL were found while writing this
document and fixed the same day: the sales check summed a non-existent role
(`REVENUE` instead of `SALES_REVENUE`, showing ledger 0) and the purchase-return
check read the debit side of Inventory where the return sits on the credit
side; the supplier-payment check also counted advance recoveries on the
ledger side only. Document checks now treat a difference within 0.005 ×
documents (per-document HALF_UP rounding of 4 dp source amounts) as MATCHED
and report the tolerance. The figures above were computed directly in SQL
(`scratchpad/recon.sql`, `recon2.sql`) and agree with the corrected screen.

## 6. Go-live procedure for a production organisation

1. Apply V90–V94 (they create nothing that already exists and touch no existing row except the new `hr_expense_type.pid_account` column). Start the application once with Hibernate `ddl-auto: update` and accept the SMALLINT → integer widenings it logs for the new flag columns.
2. Keep `posting_enabled = Y` (default). From that moment every new sale, purchase, payment, payroll and claim posts live; nothing historical is in the books yet.
3. On the Reconciliation screen run a **dry run** for each module and date range to see counts; then run real batches (≤ 5000 documents each) module by module, oldest first: SALES, PURCHASE, SUPPLIER_PAYMENT. Watch `failed_count`; a failure names the document and the reason in the batch notes and the event monitor, and never blocks the rest of the batch.
4. Run the reconciliation checks. Expect rounding-level differences on the document checks and the three balance differences explained in §4.
5. Post the **opening balances** as OPENING journals / vouchers, all dated the cut-over date and all against 3900 Opening Balance Equity: counted stock value (Inventory), bank statement balances (each bank account), mobile wallet balances, petty cash, fixed assets, prepayments, investor capital already invested (via investor capital adjustments with a reason), owner capital. Then transfer the net of 3900 to 3100/3300 by a manual journal once the accountant is satisfied.
6. Open the first business day on Daily Cash for each branch and count the drawer: that count becomes the till's authoritative opening.
7. Lock the periods before the cut-over date once the opening balances are approved.

## 7. Rollback

There is nothing destructive to roll back. If the backfill must be undone for
an organisation before go-live, reverse the batches' journals through the
engine (`reverseSource` per event, or a batch-level reversal endpoint if
added) — the originals stay, marked REVERSED, as accounting history requires.
Dropping `acc_*` tables is never the answer and no migration does it.
