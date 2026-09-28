/* ─────────────────────────────────────────────────────────────────────────────
   Catalogue — IGTA and buy price on a global product.

   POS_PRODUCT_INFO has carried both since the beginning: IGTA is the VAT
   amount on the trade price (TP x VAT / 100) and BUY_PRICE is the trade price
   with it (TP + IGTA), the landed cost a purchase line is valued at. The
   shared catalogue carried only the suggested MRP and TP, so every pharmacy
   that imported a medicine got a row with no IGTA and no buy price and had to
   fill both in by hand, product by product.

   Two columns, the same type and scale as the ones they mirror, both nullable:
   a catalogue entry with no known VAT is still a valid entry. The import
   (GlobalProductServiceImpl.importInto) now copies both onto the pharmacy's
   row beside the suggested prices, and the pharmacy owns them from then on
   exactly as it owns the prices.

   Backfill, narrowly: where every pharmacy that already holds a catalogue
   medicine agrees on its IGTA and buy price, the catalogue takes that value.
   Where they disagree, or nobody holds it, the columns stay NULL rather than
   guess. Only NULLs are filled; nothing already on the catalogue is replaced.
   ───────────────────────────────────────────────────────────────────────────── */

ALTER TABLE pharma.global_product ADD COLUMN IF NOT EXISTS igta      numeric(12, 4);
ALTER TABLE pharma.global_product ADD COLUMN IF NOT EXISTS buy_price numeric(14, 4);

COMMENT ON COLUMN pharma.global_product.igta      IS 'VAT amount on the suggested trade price, per piece - mirrors POS_PRODUCT_INFO.IGTA';
COMMENT ON COLUMN pharma.global_product.buy_price IS 'Suggested trade price with IGTA - mirrors POS_PRODUCT_INFO.BUY_PRICE';

UPDATE pharma.global_product g
   SET igta      = x.igta,
       buy_price = x.buy_price,
       updated_by = 'V89', updated_date = CURRENT_TIMESTAMP
  FROM (SELECT pid_global_product,
               MIN(igta)      AS igta,
               MIN(buy_price) AS buy_price
          FROM pharma.pos_product_info
         WHERE pid_global_product IS NOT NULL
           AND status = 'Y'
           AND igta IS NOT NULL AND buy_price IS NOT NULL
         GROUP BY pid_global_product
        HAVING COUNT(DISTINCT igta) = 1 AND COUNT(DISTINCT buy_price) = 1) x
 WHERE g.pid_global_product = x.pid_global_product
   AND g.igta IS NULL AND g.buy_price IS NULL;


/* ═══ Verification ══════════════════════════════════════════════════════════ */

SELECT (SELECT count(*) FROM pharma.global_product)                         AS catalogue_rows,
       (SELECT count(*) FROM pharma.global_product WHERE igta IS NOT NULL)   AS with_igta,
       (SELECT count(*) FROM pharma.global_product WHERE updated_by = 'V89') AS backfilled;
