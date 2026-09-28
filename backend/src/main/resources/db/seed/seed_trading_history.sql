-- ═══════════════════════════════════════════════════════════════════════════
-- Trading history for the AI Copilot to read.
--
-- The database held three days of sales across 28 products, 21 of them with no
-- stock at all. Nothing velocity-based can be said about that, so this lays
-- down 120 days of movement with a deliberate shape: two lines about to run
-- out, a few tightening, most comfortable, three dead, and four carrying a
-- short-dated batch. That spread is what makes a reorder list worth reading —
-- if every product looked alike the ranking would carry no information.
--
-- The sales are planned before any stock is opened, so each product can be
-- given exactly the opening quantity that lands it on its intended days of
-- cover. Sizing stock first and hoping is what makes seeded data look uniform.
--
-- Everything is posted the way the application posts it — an invoice header,
-- one detail row per batch touched, and a movement against that batch — so the
-- ledger still reconciles (QTY_PCS = SUM(IN) - SUM(OUT)) when it finishes.
--
-- Every row is marked FROM_SOURCE='SEED' and every batch numbered 'SD...', so
-- unseed.sql removes precisely this and nothing else.
-- ═══════════════════════════════════════════════════════════════════════════

\set ON_ERROR_STOP on
BEGIN;

DO $seed$
DECLARE
  c_days   int    := 120;                  -- history depth, ending yesterday
  c_org    bigint := 1;
  c_branch bigint := 1;
  c_comp   bigint := 1;
  d0       date   := current_date - c_days;

  r         record;
  b         record;
  bt        record;
  v_day     date;
  v_i       int;
  v_qty     numeric;
  v_trend   numeric;
  v_dow     numeric;
  v_noise   numeric;
  v_mst     bigint;
  v_dtl     bigint;
  v_left    numeric;
  v_take    numeric;
  v_price   numeric;
  v_total   numeric;
  v_cust    bigint;
  v_invoice text;
BEGIN

/* -- 1. the products, and the story each one is to tell -------------------- */
CREATE TEMP TABLE seed_prod ON COMMIT DROP AS
SELECT p.pid_product,
       p.product_name,
       (bx.no_of_leaf * lf.no_of_pcs)::numeric               AS pcs_per_box,
       lf.no_of_pcs::numeric                                 AS pcs_per_strip,
       p.mrp_price / NULLIF(bx.no_of_leaf * lf.no_of_pcs, 0) AS mrp_pcs,
       p.tp_price  / NULLIF(bx.no_of_leaf * lf.no_of_pcs, 0) AS cost_pcs,
       v.base_daily, v.trend, v.end_days, v.short_dated, v.dead_after,
       COALESCE(st.pcs, 0)                                    AS existing_pcs
  FROM pharma.pos_product_info p
  JOIN pharma.pos_box_info  bx ON bx.pid_box  = p.pid_box
  JOIN pharma.pos_leaf_info lf ON lf.pid_leaf = bx.pid_leaf
  LEFT JOIN (SELECT pid_product, SUM(qty_pcs) AS pcs
               FROM pharma.pos_stock_batch GROUP BY 1) st ON st.pid_product = p.pid_product
  JOIN (VALUES
      --   id, pcs/day, 120-day trend, days of cover to finish on, short-dated, silent for N days
        ( 3::bigint, 34::numeric,  0.45::numeric,   2::numeric, false, 0),   -- critical
        (13,         28,           0.30,            4,          false, 0),   -- critical
        (23,         22,           0.25,            7,          false, 0),   -- high
        (20,         19,           0.10,            9,          false, 0),   -- high
        ( 4,         15,           0.00,           13,          false, 0),   -- high
        (10,         13,           0.05,           18,          false, 0),   -- medium
        (17,         11,          -0.05,           24,          false, 0),   -- medium
        (16,          9,           0.00,           30,          false, 0),   -- medium
        (22,          8,           0.15,           38,          false, 0),
        ( 7,          7,          -0.40,           55,          false, 0),   -- declining
        (12,          6,          -0.35,           70,          false, 0),   -- declining
        ( 8,          4,           0.00,           60,          false, 0),
        ( 9,          4,           0.20,           45,          true,  0),   -- short-dated
        (14,          3,           0.00,           85,          true,  0),   -- short-dated
        (15,          3,          -0.10,           70,          true,  0),   -- short-dated
        (19,          2,           0.05,           95,          false, 0),
        (21,          2,           0.00,           80,          false, 0),
        (47,          2,           0.30,           16,          false, 0),   -- medium, high value
      -- dead stock: sold for a while, then nothing for months
        (24,          5,           0.00,            0,          false, 75),
        (26,          4,           0.00,            0,          true,  90),  -- dead AND expiring
        (27,          3,           0.00,            0,          false, 70)
    ) AS v(pid, base_daily, trend, end_days, short_dated, dead_after)
    ON v.pid = p.pid_product
 WHERE p.status = 'Y';

/* -- 2. plan every sale first, so stock can be sized to it exactly --------- */
CREATE TEMP TABLE seed_line (
  sale_date date, bucket int, pid_product bigint, qty numeric) ON COMMIT DROP;

FOR v_i IN 0 .. c_days - 1 LOOP
  v_day := d0 + v_i;
  FOR r IN SELECT * FROM seed_prod LOOP
    CONTINUE WHEN r.dead_after > 0 AND v_i > c_days - r.dead_after;

    -- A trend across the window, the Friday dip a Bangladeshi pharmacy sees,
    -- and a deterministic wobble. No random(): a re-run must reproduce this.
    v_trend := 1 + r.trend * (v_i::numeric / c_days);
    v_dow   := CASE EXTRACT(dow FROM v_day) WHEN 5 THEN 0.45 WHEN 6 THEN 0.8 ELSE 1.0 END;
    v_noise := 1 + (((r.pid_product * 31 + v_i * 17) % 11) - 5) / 18.0;

    v_qty := round(r.base_daily * v_trend * v_dow * v_noise);
    CONTINUE WHEN v_qty <= 0;
    INSERT INTO seed_line VALUES (v_day, (r.pid_product % 3) + 1, r.pid_product, v_qty);
  END LOOP;
END LOOP;

/* -- 3. opening stock: what will be sold, plus the cover it should end on -- */
CREATE TEMP TABLE seed_open ON COMMIT DROP AS
SELECT sp.*,
       COALESCE(pl.planned, 0) AS planned,
       -- The rate it is running at by the end, not the rate it started at:
       -- days of cover is judged against recent sales, so the target must be.
       COALESCE(pl.recent, 0) / 30.0 AS end_rate,
       GREATEST(
         COALESCE(pl.planned, 0)
         + CEIL(COALESCE(pl.recent, 0) / 30.0 * sp.end_days)
         - sp.existing_pcs,
         CASE WHEN sp.dead_after > 0 THEN 120 ELSE 20 END) AS to_open
  FROM seed_prod sp
  LEFT JOIN (SELECT pid_product,
                    SUM(qty)                                               AS planned,
                    SUM(qty) FILTER (WHERE sale_date >= current_date - 30)  AS recent
               FROM seed_line GROUP BY 1) pl ON pl.pid_product = sp.pid_product;

FOR r IN SELECT * FROM seed_open LOOP
  DECLARE
    v_short numeric := 0;
  BEGIN
    INSERT INTO pharma.pos_stock_batch
      (pid_stock, pid_org, pid_branch, pid_product, batch_no, expiry_date, qty_pcs,
       received_pcs, issued_pcs, cost_per_pcs, mrp_price, pcs_per_box, pcs_per_strip,
       received_date, status, inserted_date, inserted_by)
    VALUES (nextval('pharma.seq_pos_stock_batch'), c_org, c_branch, r.pid_product,
            'SD' || lpad(r.pid_product::text, 4, '0') || 'M',
            current_date + 240 + (r.pid_product % 180)::int,
            r.to_open, r.to_open, 0,
            round(r.cost_pcs, 4), round(r.mrp_pcs * r.pcs_per_box, 4),
            r.pcs_per_box, r.pcs_per_strip, d0 - 5, 'Y', now(), 'seed');

    INSERT INTO pharma.pos_stock_movement
      (pid_movement, pid_stock, pid_org, pid_branch, pid_product, batch_no, movement_type,
       direction, qty_pcs, balance_after, cost_per_pcs, ref_type, movement_date,
       remarks, inserted_date, inserted_by)
    VALUES (nextval('pharma.seq_pos_stock_movement'), currval('pharma.seq_pos_stock_batch'),
            c_org, c_branch, r.pid_product, 'SD' || lpad(r.pid_product::text, 4, '0') || 'M',
            'OPENING', 'I', r.to_open, r.to_open, round(r.cost_pcs, 4), 'MIGRATION', d0 - 5,
            'Seeded opening stock', now(), 'seed');
  END;
END LOOP;

/* -- 4. post the sales, earliest expiry first ------------------------------ */
FOR r IN SELECT sale_date, bucket FROM seed_line GROUP BY 1, 2 ORDER BY 1, 2 LOOP

  v_cust := (ARRAY[1::bigint, 2, 4, 9001, 9002])
              [(r.bucket + EXTRACT(day FROM r.sale_date)::int) % 5 + 1];
  v_invoice := 'SLH' || to_char(r.sale_date, 'YYMMDD') || lpad(r.bucket::text, 2, '0');

  INSERT INTO pharma.pos_transaction_mst
    (pid_org, pid_branch, pid_company, pid_sup_cust, transaction_type, invoice_no,
     transaction_date, payment_type, stage, status, payment_status, from_source,
     total_amount, amount_tobe_paid, given_amount, advance_amount, remarks,
     inserted_date, inserted_by)
  VALUES (c_org, c_branch, c_comp, v_cust, 'SL', v_invoice, r.sale_date,
          CASE WHEN r.bucket = 1 THEN 'CASH' ELSE 'CREDIT' END,
          'Collected', 'Y', 'Paid', 'SEED', 0, 0, 0, 0,
          'Seeded trading history', now(), 'seed')
  RETURNING pid_tran_mst INTO v_mst;

  v_total := 0;

  FOR b IN SELECT sl.pid_product, sl.qty, sp.mrp_pcs, sp.pcs_per_box
             FROM seed_line sl JOIN seed_prod sp ON sp.pid_product = sl.pid_product
            WHERE sl.sale_date = r.sale_date AND sl.bucket = r.bucket LOOP

    v_left  := b.qty;
    v_price := round(b.mrp_pcs * 0.91, 4);           -- the wholesaler piece rate

    -- One detail row per batch touched. POS_STOCK_MOVEMENT is unique on
    -- (REF_TYPE, REF_DTL, MOVEMENT_TYPE), so a quantity drawn from two batches
    -- cannot hang off one line: it is two lines, which is also how the invoice
    -- must print if the batch numbers are to mean anything.
    FOR bt IN SELECT * FROM pharma.pos_stock_batch
               WHERE pid_product = b.pid_product AND qty_pcs > 0
               ORDER BY expiry_date, pid_stock LOOP
      EXIT WHEN v_left <= 0;
      v_take := LEAST(v_left, bt.qty_pcs);
      v_dtl  := nextval('pharma.seq_pos_transaction_dtl');

      UPDATE pharma.pos_stock_batch
         SET qty_pcs    = qty_pcs - v_take,
             issued_pcs = COALESCE(issued_pcs, 0) + v_take
       WHERE pid_stock = bt.pid_stock;

      INSERT INTO pharma.pos_transaction_dtl
        (pid_tran_dtl, pid_tran_mst, pid_product, quantity, sales_price, total_price,
         mrp_price, sales_unit, sales_type, no_of_pcs, batch_no, expiry_date, inserted_date)
      VALUES (v_dtl, v_mst, b.pid_product, v_take, v_price, round(v_take * v_price, 4),
              round(b.mrp_pcs * b.pcs_per_box, 4), 'PCS', 'PCS', 1,
              bt.batch_no, bt.expiry_date, now());

      INSERT INTO pharma.pos_stock_movement
        (pid_movement, pid_stock, pid_org, pid_branch, pid_product, batch_no, movement_type,
         direction, qty_pcs, balance_after, cost_per_pcs, ref_type, ref_mst, ref_dtl,
         movement_date, remarks, inserted_date, inserted_by)
      VALUES (nextval('pharma.seq_pos_stock_movement'), bt.pid_stock, c_org, c_branch,
              b.pid_product, bt.batch_no, 'SALE', 'O', v_take, bt.qty_pcs - v_take,
              bt.cost_per_pcs, 'TRANSACTION', v_mst, v_dtl, r.sale_date, v_invoice,
              now(), 'seed');

      v_total := v_total + round(v_take * v_price, 4);
      v_left  := v_left - v_take;
    END LOOP;
  END LOOP;

  UPDATE pharma.pos_transaction_mst
     SET total_amount = v_total, amount_tobe_paid = v_total, given_amount = v_total
   WHERE pid_tran_mst = v_mst;
END LOOP;

/* -- 5. short-dated stock, received last week -------------------------------
   Opened after the sales are posted and dated recently, because FEFO takes the
   earliest expiry first: a short-dated batch that existed for the whole window
   would have been sold out months ago and left nothing to report. Stock that
   arrived days ago with weeks left on it is the exposure a pharmacy actually
   carries, and the one worth acting on.                                     */
FOR r IN SELECT * FROM seed_open WHERE short_dated LOOP
  DECLARE
    v_short numeric := GREATEST(CEIL(r.end_rate * 45), 60);
  BEGIN
    INSERT INTO pharma.pos_stock_batch
      (pid_stock, pid_org, pid_branch, pid_product, batch_no, expiry_date, qty_pcs,
       received_pcs, issued_pcs, cost_per_pcs, mrp_price, pcs_per_box, pcs_per_strip,
       received_date, status, inserted_date, inserted_by)
    VALUES (nextval('pharma.seq_pos_stock_batch'), c_org, c_branch, r.pid_product,
            'SD' || lpad(r.pid_product::text, 4, '0') || 'E',
            current_date + 18 + (r.pid_product % 40)::int, v_short, v_short, 0,
            round(r.cost_pcs, 4), round(r.mrp_pcs * r.pcs_per_box, 4),
            r.pcs_per_box, r.pcs_per_strip, current_date - 6, 'Y', now(), 'seed');

    INSERT INTO pharma.pos_stock_movement
      (pid_movement, pid_stock, pid_org, pid_branch, pid_product, batch_no, movement_type,
       direction, qty_pcs, balance_after, cost_per_pcs, ref_type, movement_date,
       remarks, inserted_date, inserted_by)
    VALUES (nextval('pharma.seq_pos_stock_movement'), currval('pharma.seq_pos_stock_batch'),
            c_org, c_branch, r.pid_product, 'SD' || lpad(r.pid_product::text, 4, '0') || 'E',
            'OPENING', 'I', v_short, v_short, round(r.cost_pcs, 4), 'MIGRATION',
            current_date - 6, 'Seeded short-dated stock', now(), 'seed');
  END;
END LOOP;

END
$seed$;

COMMIT;

\echo '-- what the seed produced --'
SELECT (SELECT count(*) FROM pharma.pos_transaction_mst WHERE from_source='SEED')  AS invoices,
       (SELECT count(*) FROM pharma.pos_transaction_dtl d
          JOIN pharma.pos_transaction_mst t ON t.pid_tran_mst=d.pid_tran_mst
         WHERE t.from_source='SEED')                                               AS lines,
       (SELECT count(*) FROM pharma.pos_stock_movement WHERE inserted_by='seed')   AS movements,
       (SELECT round(sum(total_amount)) FROM pharma.pos_transaction_mst WHERE from_source='SEED') AS revenue;

\echo '-- the ledger still reconciles --'
SELECT count(*) AS unreconciled FROM (
  SELECT b.pid_stock FROM pharma.pos_stock_batch b
   LEFT JOIN pharma.pos_stock_movement m ON m.pid_stock = b.pid_stock
   GROUP BY b.pid_stock, b.qty_pcs
  HAVING b.qty_pcs <> COALESCE(SUM(CASE WHEN m.direction='I' THEN m.qty_pcs ELSE 0 END),0)
                    - COALESCE(SUM(CASE WHEN m.direction='O' THEN m.qty_pcs ELSE 0 END),0)) z;
