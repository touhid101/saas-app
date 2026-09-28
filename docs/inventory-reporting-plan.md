# Branch-wise inventory — original plan (superseded)

> **Superseded by [inventory-stock-module.md](inventory-stock-module.md).** This
> document proposed a SQL view over the transaction tables. The build instead uses
> a physical stock table with an append-only movement log, and allocates batch-wise
> rather than by expiry-date bucket. Sections 1 and 2 (the conversion formula and
> the defects found) still hold; section 3 onward describes the view approach that
> was not taken.

Stock is kept in **PCS**, converted from BOX and STRIP, and controlled **per branch**.
Authority is the SRS stock calculation (`VW_PRODUCT_STOCK`), not `POS_PRODUCT_INFO.STOCK_QTY`.

---

## 1. The canonical stock formula

Taken from `VW_PRODUCT_STOCK` (`Docs/Pharma_Database.sql:3483`) and SRS §3.3.

### Pack hierarchy

```
POS_PRODUCT_INFO.PID_BOX → POS_BOX_INFO.NO_OF_LEAF   (strips per box)
POS_BOX_INFO.PID_LEAF    → POS_LEAF_INFO.NO_OF_PCS   (pcs per strip)

pcs_per_box   = NO_OF_LEAF × NO_OF_PCS
pcs_per_strip = NO_OF_PCS
pcs_per_pcs   = 1
```

### Signed movement in PCS

The legacy view hardcodes *"purchase is always in boxes"*:

```sql
PU →  + QUANTITY × NO_OF_LEAF × NO_OF_PCS
PR →  − QUANTITY × NO_OF_LEAF × NO_OF_PCS
SL →  − QUANTITY                            -- assumed already pcs
SR →  + QUANTITY
```

**We already have something better.** `POS_TRANSACTION_DTL.NO_OF_PCS` is written per line
by `PurchaseServiceImpl:469` as the conversion factor *for the unit actually transacted* —
verified in the live data:

| line | sales_unit | quantity | no_of_pcs | = pcs |
|---|---|---|---|---|
| 1 | BOX | 10 | 12 | 120 |
| 2 | STRIP | 4 | 10 | 40 |
| 16 | PCS | 1 | 1 | 1 |

So the rule collapses to one expression that is correct for **every** unit and every
transaction type:

```sql
qty_pcs = quantity × COALESCE(no_of_pcs, 1) × CASE transaction_type
                                                WHEN 'PU' THEN  1
                                                WHEN 'SR' THEN  1
                                                WHEN 'SL' THEN -1
                                                WHEN 'PR' THEN -1
                                              END
```

`NO_OF_PCS` is a **frozen snapshot**, stored beside the frozen prices. This matters: if
someone edits a product's pack size from 10×10 to 10×12, a factor read live from master
data would silently re-value every historical purchase. The snapshot cannot.

### Grain and derivation

```
grain:  pid_branch × pid_product × batch_no × expiry_date

stock_pcs   = SUM(qty_pcs)
stock_box   = stock_pcs / pcs_per_box      -- display only, 2 dp
stock_strip = stock_pcs / pcs_per_strip    -- display only, 2 dp
```

PCS is the only stored quantity. BOX and STRIP are presentation, computed on the way out.
That is what makes the arithmetic exact — no rounding ever enters the balance.

---

## 2. What is broken right now

Verified against the running system.

**`InventoryServiceImpl.calculateStock(productId, branchId)` (line 199) is wrong on four axes:**

```java
private BigDecimal calculateStock(Long productId, Long branchId) {
    List<PosTransactionDtl> dtls = dtlRepo.findActiveBatchesByProduct(productId);
    return dtls.stream()
            .filter(d -> d.getQuantity() != null)
            .map(PosTransactionDtl::getQuantity)          // raw qty
            .reduce(BigDecimal.ZERO, BigDecimal::add);    // all added
}
```

1. **`branchId` is accepted and never used.** There is no branch isolation at all.
2. **No sign by transaction type** — a sale *increases* stock.
3. **No unit conversion** — a purchase of 1 BOX counts as 1 PCS instead of 12.
4. **No status filter** — drafts count as stock. Right now that is
   `A=4, D=8, P=2`: **10 of 14 transactions are unapproved and are being counted.**
5. **N+1** — called once per product inside the loop at line 39.

**Branch comes from a query parameter, not the session.** `InventoryController:33` takes
`@RequestParam Long branchId`, so any user can read any branch. `SecurityUtils.getCurrentBranchId()`
(line 126) already exists and is never called here.

**`POS_TRANSACTION_DTL` has no `PID_BRANCH`** — branch lives on `_MST` only, so every
branch-scoped query must join, and that join is unindexed.

**Four `dtlRepo.findAll()` calls** — lines 57, 97, 129, 133; 129 and 133 load the whole
table twice inside one method.

**Two indexes on `POS_TRANSACTION_DTL`** — the PK and the master FK. Nothing on
`pid_product`, `expiry_date`, or `batch_no`.

**Current data:** 14 transactions, all `PU`, all `pid_branch = 1`. Every branch bug is
invisible today because there is only one branch and only one transaction type.

**Schema note:** these tables live in schema `pharma`, not `public`. `all_branch`,
`pos_box_info` and `pos_leaf_info` all resolve there — set the search path or qualify
the view DDL accordingly.

### Two legacy defects not to port

- `PROC_EXPIRY_WISE_SALES_QTY_DIS` allocates `STOCK_QTY` (**pcs**) but prices it at
  `SALES_PRICE` (**box price**) when the unit is BOX — inflating the line by `pcs_per_box`×.
- Every stock check in `POS_MANAGE_ORDERS` filters `WHERE PID_PRODUCT = p_product` with
  **no branch predicate**, then sums across all branches. That is precisely the control
  you are asking for, and the legacy system never had it.

---

## 3. Design

One SQL projection, branch-scoped, in the database. No JVM-side aggregation anywhere.

```
POS_TRANSACTION_MST (pid_branch, transaction_type, transaction_date, status)
          │  join
POS_TRANSACTION_DTL (pid_product, batch_no, expiry_date, quantity, no_of_pcs)
          │
          ▼
   qty_pcs = quantity × no_of_pcs × sign(transaction_type)
          │
          ▼
   VW_PRODUCT_STOCK   grain: branch × product × batch × expiry
          │
    ┌─────┴──────┬─────────────┬──────────────┐
    ▼            ▼             ▼              ▼
 batch stock  stock alert  expiry alert   FEFO allocation
```

### `VW_PRODUCT_STOCK` — the projection

```sql
CREATE OR REPLACE VIEW vw_product_stock AS
SELECT  m.pid_branch,
        b.branch_name,
        p.pid_company,
        p.pid_product,
        p.product_name,
        p.sku,
        d.batch_no,
        d.expiry_date,
        p.alert_qty,
        p.mrp_price,
        COALESCE(bx.no_of_leaf, 0) * COALESCE(lf.no_of_pcs, 0)  AS pcs_per_box,
        COALESCE(lf.no_of_pcs, 0)                               AS pcs_per_strip,

        SUM(d.quantity * COALESCE(d.no_of_pcs, 1) *
            CASE m.transaction_type
                WHEN 'PU' THEN  1 WHEN 'SR' THEN  1
                WHEN 'SL' THEN -1 WHEN 'PR' THEN -1
                ELSE 0 END)                                     AS stock_pcs,

        SUM(CASE WHEN m.transaction_type = 'PU'
                 THEN d.quantity * COALESCE(d.no_of_pcs, 1) ELSE 0 END) AS purchase_pcs,
        SUM(CASE WHEN m.transaction_type = 'SL'
                 THEN d.quantity * COALESCE(d.no_of_pcs, 1) ELSE 0 END) AS sales_pcs,

        -- weighted average landed cost per pcs, for actual profit
        CASE WHEN SUM(CASE WHEN m.transaction_type = 'PU'
                           THEN d.quantity * COALESCE(d.no_of_pcs, 1) ELSE 0 END) > 0
             THEN ROUND(SUM(CASE WHEN m.transaction_type = 'PU'
                                 THEN d.total_price ELSE 0 END)
                      / SUM(CASE WHEN m.transaction_type = 'PU'
                                 THEN d.quantity * COALESCE(d.no_of_pcs, 1) ELSE 0 END), 4)
        END                                                     AS cost_per_pcs
FROM        pos_transaction_dtl d
JOIN        pos_transaction_mst m  ON m.pid_tran_mst = d.pid_tran_mst
JOIN        pos_product_info    p  ON p.pid_product  = d.pid_product
LEFT JOIN   pos_box_info        bx ON bx.pid_box     = p.pid_box
LEFT JOIN   pos_leaf_info       lf ON lf.pid_leaf    = bx.pid_leaf
LEFT JOIN   all_branch          b  ON b.pid_branch   = m.pid_branch
WHERE       m.status = 'A'                    -- approved only; D (draft) and P are not stock
GROUP BY    m.pid_branch, b.branch_name, p.pid_company, p.pid_product,
            p.product_name, p.sku, d.batch_no, d.expiry_date,
            p.alert_qty, p.mrp_price, bx.no_of_leaf, lf.no_of_pcs;
```

Three deliberate differences from the Oracle original:

| | Legacy | Here | Why |
|---|---|---|---|
| Conversion | `NO_OF_LEAF × NO_OF_PCS` from master, PU only | `d.no_of_pcs` snapshot, every type | survives a pack-size edit; handles STRIP/PCS purchases |
| Draft filter | none | `m.status = 'A'` | an unapproved draft must not move stock |
| Grain | expiry only | batch **and** expiry | batch stock is a stated requirement |

> **Constraint the batch grain imposes:** the sales write path **must stamp `batch_no`** on
> the line it consumes. The legacy FEFO procedure sets `EXPIRY_DATE` but leaves `BATCH_NO`
> null — with a batch in the grain, those sales would never net against their purchase and
> stock would read high forever. Sales is not built yet, so this is a requirement to honour,
> not a migration. **Until it is honoured, group by `expiry_date` alone.**

### Branch scoping — the actual control

Three rules, no exceptions:

1. **Branch is resolved from the JWT**, exactly as company/tenant already is:
   `SecurityUtils.getCurrentBranchId()`. Same posture we settled on for Purchase.
2. **`branchId` is removed as a request parameter** from every inventory endpoint.
3. **Only `ROLE01` may pass an explicit `branchId`** (for a head-office consolidated view).
   Anyone else asking for another branch gets **404, not 403** — consistent with the
   cross-tenant rule already in Product Master. 403 confirms the branch exists.

Every read is `WHERE pid_branch = :branchId`, pushed into SQL. Never filtered in Java.

### Where work happens

| Report | Freshness | Mechanism |
|---|---|---|
| Batch stock, current stock, FEFO | exact, live | view, indexed, branch-filtered |
| Low / out-of-stock / expiry alerts | ≤ 5 min | scheduled refresh into `pos_stock_alert` |
| Daily / monthly / actual profit | previous day | nightly `pos_stock_day_snapshot` |

At your volume the live view is fast enough for tiers 1 and 2 for a long time. The alert
table exists so the **dashboard** does not re-scan on every page load — that is the one
read that fires constantly and cares least about being seconds-fresh.

Materialise the view (`MATERIALIZED VIEW` + `REFRESH ... CONCURRENTLY`) only when measured
p95 exceeds 150 ms. Not before — a matview trades freshness for speed, and paying that
before you need it means FEFO allocates against stale stock.

---

## 4. Steps

### Step 1 — Indexes *(0.5 day, no code)*

`V9__inventory_indexes.sql`:

```sql
CREATE INDEX CONCURRENTLY ix_tran_dtl_product_expiry ON pos_transaction_dtl (pid_product, expiry_date);
CREATE INDEX CONCURRENTLY ix_tran_dtl_batch          ON pos_transaction_dtl (batch_no);
CREATE INDEX CONCURRENTLY ix_tran_mst_branch_type    ON pos_transaction_mst (pid_branch, transaction_type, status);
CREATE INDEX CONCURRENTLY ix_tran_mst_branch_date    ON pos_transaction_mst (pid_branch, transaction_date DESC);
```

The MST index carries the join and the branch predicate together — that is the one that
makes branch-scoped stock cheap.

### Step 2 — Backfill `no_of_pcs` *(0.5 day)*

The whole formula depends on this column. **Checked: zero nulls today** — so this step is
cheap now and expensive later. A null here does not read as missing, it reads as `1`
through the `COALESCE`, silently undercounting a BOX line by `pcs_per_box`×. The backfill
below is a guard; the `NOT NULL` constraint after it is the real deliverable.

```sql
UPDATE pos_transaction_dtl d
   SET no_of_pcs = CASE UPPER(COALESCE(d.sales_unit, d.sales_type, 'PCS'))
                     WHEN 'BOX'   THEN COALESCE(bx.no_of_leaf,1) * COALESCE(lf.no_of_pcs,1)
                     WHEN 'STRIP' THEN COALESCE(lf.no_of_pcs,1)
                     ELSE 1 END
  FROM pos_product_info p
  LEFT JOIN pos_box_info  bx ON bx.pid_box  = p.pid_box
  LEFT JOIN pos_leaf_info lf ON lf.pid_leaf = bx.pid_leaf
 WHERE p.pid_product = d.pid_product
   AND d.no_of_pcs IS NULL;
```

Then `ALTER TABLE pos_transaction_dtl ALTER COLUMN no_of_pcs SET NOT NULL` — after
verifying zero nulls remain. A null here is a wrong number, not a missing one, and
wrong numbers should fail loudly.

### Step 3 — The view *(1 day)*

`V10__vw_product_stock.sql` — the DDL in §3. Validate against hand-computed expectations
for the 14 existing rows before anything reads it.

### Step 4 — Rewrite `InventoryServiceImpl` *(2–3 days)*

This is the core of the work.

- New `StockRepository` with native queries against `vw_product_stock`, every one taking
  `:branchId` as its first predicate.
- Delete `calculateStock()`. Delete all four `dtlRepo.findAll()` calls.
- `getStockLedger` becomes one grouped query, not a per-product loop.
- The two expiry counts at lines 129/133 become **one** query:
  ```sql
  SELECT COUNT(*) FILTER (WHERE expiry_date <  CURRENT_DATE)                  AS expired,
         COUNT(*) FILTER (WHERE expiry_date >= CURRENT_DATE
                            AND expiry_date <  CURRENT_DATE + 30)             AS near_expiry
    FROM vw_product_stock WHERE pid_branch = :branchId AND stock_pcs > 0;
  ```
- Responses carry `stockPcs`, `stockBox`, `stockStrip`, `pcsPerBox`, `pcsPerStrip` so the
  UI can show "12 box 4 strip 3 pcs" without recomputing.
- Every endpoint paginated.

### Step 5 — Branch scoping *(1 day)*

- Drop `branchId` from `InventoryController` signatures; resolve from `SecurityUtils`.
- `requireBranchId()` — same helper shape as `PurchaseServiceImpl` already uses.
- `ROLE01` may override; everyone else gets 404 on a foreign branch.
- Tests: a user of branch 1 cannot see branch 2's stock through any of the six endpoints.

### Step 6 — Unit-aware stock check *(1 day)*

Selling 1 BOX of a product with 5 pcs in stock must fail. The check runs in pcs:

```java
BigDecimal requiredPcs = qty.multiply(factorFor(unit, product));
if (availablePcs.compareTo(requiredPcs) < 0) throw new InsufficientStockException(...);
```

Report the shortfall in the unit the user typed, not in pcs. "You have 5 pcs" is not a
useful answer to someone who asked for 1 box.

### Step 7 — Alerts *(2 days)*

`pos_stock_alert` (pid_branch, pid_product, alert_type, severity, computed_at), refreshed
every 5 minutes per branch. Dashboard reads counts from it. The alert compares
`stock_pcs` against `alert_qty` — **decide and document which unit `alert_qty` is in.**
Today `getStockLedger:42` compares it against an unconverted quantity, so it is currently
meaningless either way.

### Step 8 — FEFO, branch-scoped *(2 days)*

Replace the Oracle procedure with Java over the view:
`WHERE pid_branch = :branchId AND stock_pcs > 0 ORDER BY expiry_date`.
Allocate in pcs; price at the **unit** rate (`qty_in_unit × unit_price`), not
`pcs × box_price`. Stamp `batch_no` on every allocated line — §3's constraint.

### Step 9 — Day snapshots *(3–4 days, when sales exist)*

`pos_stock_day_snapshot` per branch × product × day: opening, in, out, closing, COGS
(from `cost_per_pcs`), sales value. Nightly, idempotent. Daily/monthly/actual-profit read
this and never touch transactions for closed periods.

### Step 10 — One read model *(2 days)*

`InventoryReadService` as the single entry point for Sales, POS, Reports and Dashboard.
Today each computes stock differently, which is how they drift apart.

---

## 5. Performance and downtime

**Downtime: none required.** Steps 1 and 3 are `CREATE INDEX CONCURRENTLY` and
`CREATE VIEW` — neither takes a write lock. Step 2 is a one-time `UPDATE` of 19 rows;
at scale, batch it. Steps 4–10 are application code behind a normal deploy.

**Response-time targets** (p95, per branch):

| Endpoint | Target |
|---|---|
| Batch stock (one product) | < 100 ms |
| Stock ledger (paginated) | < 200 ms |
| Alerts | < 150 ms |
| Dashboard | < 300 ms |
| FEFO preview | < 100 ms |

**Load balance.** Branch scoping is itself the biggest win: every query touches one
branch's slice instead of the whole table, and that stays true as branches are added.
Reads route to a replica when one exists — the view is read-only, so no code change is
needed to make that work.

**Capture p95 before Step 1**, so the improvement is measured rather than assumed.

Separately: `spring.flyway.enabled` is `false` with `ddl-auto: update`. Hibernate is
inventing the schema at startup while these migrations are applied by hand. Turning
Flyway on with `ddl-auto: validate` should be scheduled regardless — it is a live
downtime risk independent of this plan.

---

## 6. Order of work

Steps 1–3 (2 days) establish the correct number. Steps 4–6 (4–5 days) make the API serve
it, branch-scoped and unit-aware. Everything after is additive.

**Start with Steps 1–4.** Until `calculateStock` is replaced, every stock number the
system reports is wrong in three separate ways, and every report built on top inherits all
three.
