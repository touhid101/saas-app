-- ═══════════════════════════════════════════════════════════════════════════
-- A load-test dataset: two branches, one user each, 100,000 transactions apiece.
--
-- Split the way a pharmacy's ledger actually splits — mostly sales, a fifth
-- purchases, a tail of returns both ways — because a table of 200,000 identical
-- rows measures the disk and nothing else. The point is to make the indexes,
-- the branch filters and the dashboard aggregates work as hard as they would on
-- a real two-year book.
--
--   SL  sales             70%      PU  purchases          20%
--   SR  sales returns      7%      PR  purchase returns    3%
--
-- ── How it stays consistent ──────────────────────────────────────────────────
-- Everything is generated set-based from generate_series; a row-by-row loop at
-- this volume takes hours. Ids come from reserved blocks so detail and movement
-- rows can be built in the same pass as their parents, and the identity
-- sequences are wound past the block at the end.
--
-- Stock is kept honest by construction rather than by hoping: one batch per
-- product per branch, an opening movement sized to whatever the generated
-- traffic consumed, and a final UPDATE that sets every batch to its own ledger
-- net. The invariant QTY_PCS = SUM(IN) - SUM(OUT) holds when it finishes.
--
-- ── Removing it again ────────────────────────────────────────────────────────
-- Every row is tagged FROM_SOURCE='PERF' or sits in branch 901/902, and batches
-- are numbered 'PF…'. perf_dataset_teardown.sql removes exactly this and
-- nothing else.
--
-- ── Before you read any dashboard afterwards ─────────────────────────────────
-- These branches will dominate every figure in the application. That is the
-- point, but it means a number you did not expect is probably this file.
-- ═══════════════════════════════════════════════════════════════════════════

\set ON_ERROR_STOP on
\timing on

BEGIN;

/* ── 1. the two branches and their users ─────────────────────────────────── */

INSERT INTO pharma.all_branch
  (pid_branch, branch_name, branch_address, pid_org, pid_company, pid_region,
   status, inserted_date, inserted_by)
VALUES (901, 'PERF Dhaka Uttara',  'Uttara, Dhaka',  1, 1, 1, 'Y', now(), 'perf'),
       (902, 'PERF Chattogram GEC','GEC, Chattogram', 1, 1, 1, 'Y', now(), 'perf')
ON CONFLICT (pid_branch) DO NOTHING;

-- Branch managers: role 2, so they exercise the branch-scoped path rather than
-- the administrator bypass. Passwords are plain because the encoder is NoOp.
INSERT INTO pharma.acl_user
  (pid_user, user_name, full_name, password_hash, pid_branch, pid_org, pid_company,
   pid_role, is_admin, status, inserted_date, inserted_by)
VALUES (901, 'perf901', 'PERF Uttara Manager',    'perf901pass', 901, 1, 1, 2, 'N', 'Y', now(), 'perf'),
       (902, 'perf902', 'PERF Chattogram Manager','perf902pass', 902, 1, 1, 2, 'N', 'Y', now(), 'perf')
ON CONFLICT (pid_user) DO NOTHING;

/* ── 2. one batch per product per branch ─────────────────────────────────── */

-- Opened empty; the quantity is set from the ledger once the traffic exists.
INSERT INTO pharma.pos_stock_batch
  (pid_stock, pid_org, pid_branch, pid_product, batch_no, expiry_date, qty_pcs,
   received_pcs, issued_pcs, cost_per_pcs, mrp_price, pcs_per_box, pcs_per_strip,
   received_date, status, inserted_date, inserted_by)
SELECT 900000 + (b.pid_branch * 1000) + p.pid_product,
       1, b.pid_branch, p.pid_product,
       'PF' || b.pid_branch || '-' || lpad(p.pid_product::text, 4, '0'),
       -- A spread of expiry dates so the near-expiry reports have shape.
       current_date + (20 + ((p.pid_product * 37) % 500))::int,
       0, 0, 0,
       ROUND(p.tp_price / NULLIF(bx.no_of_leaf * lf.no_of_pcs, 0), 4),
       p.mrp_price, (bx.no_of_leaf * lf.no_of_pcs), lf.no_of_pcs,
       current_date - 760, 'Y', now(), 'perf'
  FROM pharma.pos_product_info p
  JOIN pharma.pos_box_info  bx ON bx.pid_box  = p.pid_box
  JOIN pharma.pos_leaf_info lf ON lf.pid_leaf = bx.pid_leaf
 CROSS JOIN (VALUES (901), (902)) AS b(pid_branch)
 WHERE p.status = 'Y'
ON CONFLICT (pid_stock) DO NOTHING;

/* ── 3. the transactions ─────────────────────────────────────────────────── */

-- Id blocks: branch 901 from 2,000,001; branch 902 from 2,200,001. Reserved so
-- the detail and movement rows can be derived arithmetically instead of read
-- back one at a time.
CREATE TEMP TABLE perf_mst ON COMMIT DROP AS
SELECT base + g                                          AS pid_tran_mst,
       br                                                AS pid_branch,
       g,
       CASE WHEN g % 100 <  70 THEN 'SL'
            WHEN g % 100 <  90 THEN 'PU'
            WHEN g % 100 <  97 THEN 'SR'
            ELSE 'PR' END                                AS ttype,
       (current_date - ((g * 7919) % 730)::int)               AS tdate,
       1 + (g % 3)                                       AS lines
  FROM (VALUES (901, 2000000), (902, 2200000)) AS b(br, base),
       generate_series(1, 100000) g;

CREATE INDEX ON perf_mst (pid_tran_mst);

INSERT INTO pharma.pos_transaction_mst
  (pid_tran_mst, pid_org, pid_branch, pid_company, pid_sup_cust, transaction_type,
   invoice_no, transaction_date, payment_type, stage, status, payment_status,
   from_source, total_amount, amount_tobe_paid, given_amount, advance_amount,
   remarks, inserted_date, inserted_by)
OVERRIDING SYSTEM VALUE
SELECT m.pid_tran_mst, 1, m.pid_branch, 1,
       CASE WHEN m.ttype IN ('SL','SR')
            THEN (ARRAY[1,2,4,9001,9002])[(m.g % 5) + 1]
            ELSE (ARRAY[1,901,902,903])[(m.g % 4) + 1] END,
       m.ttype,
       'PF' || m.pid_branch || '-' || m.ttype || '-' || lpad(m.g::text, 6, '0'),
       m.tdate,
       (ARRAY['CASH','CREDIT','BKASH','CARD'])[(m.g % 4) + 1],
       CASE WHEN m.ttype = 'SL' THEN
              (ARRAY['Collected','Delivered','On The Way','Delivery Assigned','Confirmed'])
                [LEAST((m.g % 20) + 1, 5)]
            ELSE 'Collected' END,
       CASE WHEN m.ttype IN ('PU','PR') THEN 'A' ELSE 'Y' END,
       CASE WHEN m.g % 9 = 0 THEN 'Partial'
            WHEN m.g % 17 = 0 THEN 'Unpaid' ELSE 'Paid' END,
       'PERF', 0, 0, 0, 0,
       'Load test row', now(), 'perf'
  FROM perf_mst m;

/* ── 4. the lines, one to three per transaction ──────────────────────────── */

-- Detail ids are derived: master id x 4 + line number. Unique by construction
-- and computable without a round trip, which is what makes this set-based.
CREATE TEMP TABLE perf_dtl ON COMMIT DROP AS
SELECT (m.pid_tran_mst * 4) + ln                            AS pid_tran_dtl,
       m.pid_tran_mst,
       m.pid_branch,
       m.ttype,
       m.tdate,
       p.pid_product,
       p.pcs_per_box,
       p.cost_pcs,
       p.mrp_pcs,
       -- Between 1 and 24 pieces; returns are small, as they are in life.
       (CASE WHEN m.ttype IN ('SR','PR') THEN 1 + ((m.g + ln) % 4)
             ELSE 1 + ((m.g + ln) % 24) END)::numeric       AS qty_pcs
  FROM perf_mst m
  CROSS JOIN LATERAL generate_series(1, m.lines) ln
  CROSS JOIN LATERAL (
        SELECT pi.pid_product,
               (bx.no_of_leaf * lf.no_of_pcs)                       AS pcs_per_box,
               pi.tp_price  / NULLIF(bx.no_of_leaf * lf.no_of_pcs, 0) AS cost_pcs,
               pi.mrp_price / NULLIF(bx.no_of_leaf * lf.no_of_pcs, 0) AS mrp_pcs,
               row_number() OVER (ORDER BY pi.pid_product)          AS rn
          FROM pharma.pos_product_info pi
          JOIN pharma.pos_box_info  bx ON bx.pid_box  = pi.pid_box
          JOIN pharma.pos_leaf_info lf ON lf.pid_leaf = bx.pid_leaf
         WHERE pi.status = 'Y') p
 WHERE p.rn = ((m.g * 3 + ln) % 21) + 1;

CREATE INDEX ON perf_dtl (pid_tran_dtl);

INSERT INTO pharma.pos_transaction_dtl
  (pid_tran_dtl, pid_tran_mst, pid_product, quantity, sales_price, mnp_price,
   total_price, mrp_price, sales_unit, sales_type, no_of_pcs, batch_no,
   expiry_date, inserted_date)
SELECT d.pid_tran_dtl, d.pid_tran_mst, d.pid_product, d.qty_pcs,
       CASE WHEN d.ttype IN ('SL','SR') THEN ROUND(d.mrp_pcs * 0.91, 4) END,
       CASE WHEN d.ttype IN ('PU','PR') THEN ROUND(d.cost_pcs, 4) END,
       ROUND(d.qty_pcs * CASE WHEN d.ttype IN ('SL','SR')
                              THEN d.mrp_pcs * 0.91 ELSE d.cost_pcs END, 4),
       ROUND(d.mrp_pcs * d.pcs_per_box, 4),
       'PCS', 'PCS', 1,
       'PF' || d.pid_branch || '-' || lpad(d.pid_product::text, 4, '0'),
       current_date + (20 + ((d.pid_product * 37) % 500))::int,
       now()
  FROM perf_dtl d;

-- Header totals from the lines they were built from.
UPDATE pharma.pos_transaction_mst t
   SET total_amount     = x.total,
       amount_tobe_paid = x.total,
       given_amount     = CASE WHEN t.payment_status = 'Paid'    THEN x.total
                               WHEN t.payment_status = 'Partial' THEN ROUND(x.total * 0.5, 4)
                               ELSE 0 END
  FROM (SELECT pid_tran_mst, SUM(ROUND(qty_pcs * CASE WHEN ttype IN ('SL','SR')
                                                      THEN mrp_pcs * 0.91 ELSE cost_pcs END, 4)) AS total
          FROM perf_dtl GROUP BY pid_tran_mst) x
 WHERE t.pid_tran_mst = x.pid_tran_mst;

/* ── 5. the stock ledger ─────────────────────────────────────────────────── */

-- One movement per line. Sales and purchase returns take stock out; purchases
-- and sales returns put it back.
INSERT INTO pharma.pos_stock_movement
  (pid_movement, pid_stock, pid_org, pid_branch, pid_product, batch_no,
   movement_type, direction, qty_pcs, balance_after, cost_per_pcs,
   ref_type, ref_mst, ref_dtl, movement_date, remarks, inserted_date, inserted_by)
SELECT 20000000 + row_number() OVER (ORDER BY d.pid_tran_dtl),
       900000 + (d.pid_branch * 1000) + d.pid_product,
       1, d.pid_branch, d.pid_product,
       'PF' || d.pid_branch || '-' || lpad(d.pid_product::text, 4, '0'),
       CASE d.ttype WHEN 'SL' THEN 'SALE' WHEN 'PU' THEN 'PURCHASE'
                    WHEN 'SR' THEN 'SALES_RETURN' ELSE 'PURCHASE_RETURN' END,
       CASE WHEN d.ttype IN ('SL','PR') THEN 'O' ELSE 'I' END,
       d.qty_pcs,
       -- Not maintained per row: a running balance would force this into a
       -- loop, and nothing reads it except a human looking at one movement.
       0,
       ROUND(d.cost_pcs, 4),
       'TRANSACTION', d.pid_tran_mst, d.pid_tran_dtl, d.tdate,
       'Load test', now(), 'perf'
  FROM perf_dtl d;

-- Opening stock, sized to whatever the traffic consumed plus a working buffer,
-- so no batch is driven negative by its own generated history.
INSERT INTO pharma.pos_stock_movement
  (pid_movement, pid_stock, pid_org, pid_branch, pid_product, batch_no,
   movement_type, direction, qty_pcs, balance_after, cost_per_pcs,
   ref_type, movement_date, remarks, inserted_date, inserted_by)
SELECT 21000000 + row_number() OVER (ORDER BY b.pid_stock),
       b.pid_stock, 1, b.pid_branch, b.pid_product, b.batch_no,
       'OPENING', 'I',
       GREATEST(COALESCE(net.out_pcs, 0) - COALESCE(net.in_pcs, 0), 0) + 5000,
       0, b.cost_per_pcs, 'MIGRATION', current_date - 760,
       'Load test opening', now(), 'perf'
  FROM pharma.pos_stock_batch b
  LEFT JOIN (
        SELECT pid_stock,
               SUM(qty_pcs) FILTER (WHERE direction = 'O') AS out_pcs,
               SUM(qty_pcs) FILTER (WHERE direction = 'I') AS in_pcs
          FROM pharma.pos_stock_movement
         WHERE inserted_by = 'perf'
         GROUP BY pid_stock) net ON net.pid_stock = b.pid_stock
 WHERE b.batch_no LIKE 'PF%';

-- The invariant, established rather than assumed.
UPDATE pharma.pos_stock_batch b
   SET qty_pcs     = led.net,
       received_pcs = led.in_pcs,
       issued_pcs   = led.out_pcs
  FROM (SELECT m.pid_stock,
               COALESCE(SUM(m.qty_pcs) FILTER (WHERE m.direction = 'I'), 0) AS in_pcs,
               COALESCE(SUM(m.qty_pcs) FILTER (WHERE m.direction = 'O'), 0) AS out_pcs,
               COALESCE(SUM(m.qty_pcs) FILTER (WHERE m.direction = 'I'), 0)
             - COALESCE(SUM(m.qty_pcs) FILTER (WHERE m.direction = 'O'), 0) AS net
          FROM pharma.pos_stock_movement m
         GROUP BY m.pid_stock) led
 WHERE led.pid_stock = b.pid_stock
   AND b.batch_no LIKE 'PF%';

/* ── 6. wind the sequences past the reserved blocks ──────────────────────── */

SELECT setval('pharma.pos_transaction_mst_pid_tran_mst_seq',
              GREATEST((SELECT max(pid_tran_mst) FROM pharma.pos_transaction_mst), 1));
SELECT setval('pharma.seq_pos_transaction_dtl',
              GREATEST((SELECT max(pid_tran_dtl) FROM pharma.pos_transaction_dtl), 1));
SELECT setval('pharma.seq_pos_stock_movement',
              GREATEST((SELECT max(pid_movement) FROM pharma.pos_stock_movement), 1));
SELECT setval('pharma.seq_pos_stock_batch',
              GREATEST((SELECT max(pid_stock) FROM pharma.pos_stock_batch), 1));

COMMIT;

ANALYZE pharma.pos_transaction_mst;
ANALYZE pharma.pos_transaction_dtl;
ANALYZE pharma.pos_stock_movement;
ANALYZE pharma.pos_stock_batch;

\echo '── what was generated ──'
SELECT pid_branch,
       count(*) FILTER (WHERE transaction_type = 'SL') AS sales,
       count(*) FILTER (WHERE transaction_type = 'PU') AS purchases,
       count(*) FILTER (WHERE transaction_type = 'SR') AS sales_returns,
       count(*) FILTER (WHERE transaction_type = 'PR') AS purchase_returns,
       count(*)                                        AS total,
       min(transaction_date) AS oldest, max(transaction_date) AS newest
  FROM pharma.pos_transaction_mst
 WHERE from_source = 'PERF'
 GROUP BY pid_branch ORDER BY pid_branch;

\echo '── table sizes ──'
SELECT 'pos_transaction_mst' AS table, count(*) FROM pharma.pos_transaction_mst
UNION ALL SELECT 'pos_transaction_dtl', count(*) FROM pharma.pos_transaction_dtl
UNION ALL SELECT 'pos_stock_movement',  count(*) FROM pharma.pos_stock_movement
UNION ALL SELECT 'pos_stock_batch',     count(*) FROM pharma.pos_stock_batch;

\echo '── the ledger still reconciles ──'
SELECT count(*) AS unreconciled FROM (
  SELECT b.pid_stock FROM pharma.pos_stock_batch b
   LEFT JOIN pharma.pos_stock_movement m ON m.pid_stock = b.pid_stock
   GROUP BY b.pid_stock, b.qty_pcs
  HAVING b.qty_pcs <> COALESCE(SUM(CASE WHEN m.direction='I' THEN m.qty_pcs ELSE 0 END),0)
                    - COALESCE(SUM(CASE WHEN m.direction='O' THEN m.qty_pcs ELSE 0 END),0)) z;
