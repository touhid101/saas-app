/* ─────────────────────────────────────────────────────────────────────────────
   SEQUENCE_TRACKER — the table behind every document number.

       ERROR: relation "pharma.sequence_tracker" does not exist

   Saving a purchase asks DocumentNumberService for an invoice number, and that
   reads this table. On production it is not there, so nothing that needs a
   number can be saved at all.

   ── why it is missing ──

   V5 creates it. V5 has never run: Flyway is baselined at 63, so every file at
   or below that number is recorded as history and skipped for ever.

   Normally that would not matter, because production's schema was built by
   hibernate.ddl-auto from the entities. But SEQUENCE_TRACKER is not an entity.
   It is read and written by native SQL in DocumentNumberService — deliberately,
   because the counter is advanced under SELECT ... FOR UPDATE and that is not
   something an entity mapping expresses. Hibernate creates tables it knows
   about, and it does not know about this one.

   prod_schema_sync.sql does not cover it either, and also deliberately: that
   file states it creates no tables, on the understanding that Hibernate does.
   This table falls in the gap between the two rules. So it is fixed here, in a
   migration, where it will also be right for the next environment.

   ── the part that matters more than the CREATE ──

   Seeding the counter. An empty tracker starts at zero, so the next invoice of
   the month would be issued as 00001 — a number production almost certainly
   used weeks ago, and ux_purchase_invoice_no would reject it. That turns a
   missing table into a duplicate key, which is not progress.

   The counter is therefore seeded from the highest number already issued this
   year-month, read out of the invoices themselves. Next month it resets to 1 on
   its own, which is what the month in the format is for.
   ───────────────────────────────────────────────────────────────────────────── */

CREATE TABLE IF NOT EXISTS pharma.sequence_tracker (
    seq_name        varchar(50) NOT NULL,
    last_year_month varchar(6),
    last_value      bigint      NOT NULL DEFAULT 0,
    updated_date    timestamp,
    CONSTRAINT pk_sequence_tracker PRIMARY KEY (seq_name)
);

COMMENT ON TABLE pharma.sequence_tracker IS
    'Per-sequence counters that reset on a year-month boundary. Advanced under a row lock by DocumentNumberService.';
COMMENT ON COLUMN pharma.sequence_tracker.last_year_month IS
    'YYMM the counter last issued for; a change resets LAST_VALUE to 0.';

/* The invoice counter, seeded to where this month has already reached.

   INVOICE_NO is <TYPE><YYMM><5 digits> — 'PU260900007' is purchase, September
   2026, number 7. One counter serves every type, so the highest of all of them
   is what the next number must clear. Rows that do not match the shape are
   ignored rather than guessed at: anything hand-written or imported from the
   old system has no place setting a counter.

   Only inserted if the row is absent, so this cannot disturb a database where
   the series is already running. */
INSERT INTO pharma.sequence_tracker (seq_name, last_year_month, last_value, updated_date)
SELECT 'SEQ_INVOICE_NO',
       to_char(CURRENT_DATE, 'YYMM'),
       COALESCE((SELECT MAX(CAST(substring(m.invoice_no FROM 7 FOR 5) AS bigint))
                   FROM pharma.pos_transaction_mst m
                  WHERE m.invoice_no ~ ('^[A-Za-z]{2}' || to_char(CURRENT_DATE, 'YYMM') || '[0-9]{5}$')),
                0),
       CURRENT_TIMESTAMP
 WHERE NOT EXISTS (SELECT 1 FROM pharma.sequence_tracker WHERE seq_name = 'SEQ_INVOICE_NO');

/* Batch numbers are the other half of V5 and the other thing no entity owns.
   A continuous series, not reset monthly. */
CREATE SEQUENCE IF NOT EXISTS pharma.seq_batch_no START WITH 1 INCREMENT BY 1 NO CYCLE;

COMMENT ON SEQUENCE pharma.seq_batch_no IS
    'Continuous counter behind POS_TRANSACTION_MST.BATCH_NO, rendered zero-padded to 6 digits.';

/* Past whatever the invoices already carry. A sequence created just now sits at
   1 and would hand out batch numbers that exist; GREATEST also means this can
   never wind an already-correct sequence backwards. */
SELECT setval('pharma.seq_batch_no',
              GREATEST((SELECT COALESCE(MAX(CAST(m.batch_no AS bigint)), 0)
                          FROM pharma.pos_transaction_mst m
                         WHERE m.batch_no ~ '^[0-9]+$'),
                       (SELECT last_value FROM pharma.seq_batch_no),
                       1),
              true);

SELECT t.seq_name,
       t.last_year_month,
       t.last_value,
       (SELECT last_value FROM pharma.seq_batch_no) AS batch_no_sequence
  FROM pharma.sequence_tracker t;
