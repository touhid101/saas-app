/* ─────────────────────────────────────────────────────────────────────────────
   Let a gift line have no quantity.

   CK_TRAN_DTL_QUANTITY has always read `quantity > 0`, which was right for every
   line that existed before V69: a line was something you bought, and buying none
   of it is a typo. A gift is the first line that is not bought — its units are
   all bonus — so the old rule refused it at the database with a message about
   quantity that told the storekeeper nothing about what was actually wrong.

   The rule is not dropped, it is made specific to each kind:

       PURCHASE  quantity > 0        — buying none of something is still a typo
       GIFT      quantity >= 0 and bonus > 0
                                     — a gift receives units, just not bought ones

   So a gift with nothing in it is still refused, which is the case worth
   refusing: a row that occupies a line, receives no stock, and takes a share of
   the invoice cost away from the goods that did arrive.
   ───────────────────────────────────────────────────────────────────────────── */

ALTER TABLE pharma.pos_transaction_dtl
  DROP CONSTRAINT IF EXISTS ck_tran_dtl_quantity;

ALTER TABLE pharma.pos_transaction_dtl
  ADD CONSTRAINT ck_tran_dtl_quantity CHECK (
      CASE WHEN COALESCE(line_kind, 'PURCHASE') = 'GIFT'
           THEN quantity >= 0 AND COALESCE(bonus_qty, 0) > 0
           ELSE quantity > 0
      END);

SELECT COALESCE(line_kind, 'PURCHASE') AS kind,
       count(*)                        AS lines,
       min(quantity)                   AS smallest_qty
  FROM pharma.pos_transaction_dtl
 GROUP BY 1
 ORDER BY 1;
