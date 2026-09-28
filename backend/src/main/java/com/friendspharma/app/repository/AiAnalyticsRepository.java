package com.friendspharma.app.repository;

import com.friendspharma.app.entity.PosProductInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * The numbers behind the AI Copilot.
 *
 * <p><b>Everything here is one set-based query.</b> The page it feeds covers
 * every product in the catalogue, and the obvious shape — fetch the products,
 * then ask the database about each one — is two round trips per product. The
 * version of this that was here before did exactly that and would have issued
 * fifty-seven queries to draw one screen. These aggregate once and hand back a
 * finished row.
 *
 * <p><b>Sales are read from POS_STOCK_MOVEMENT, not from the invoice lines.</b>
 * The movement ledger is append-only, already reconciled against batch
 * balances, carries the movement date and the batch cost, and nets returns off
 * naturally through its direction column. It also excludes merge wrappers
 * without being told to, because a merge posts no movement — invoice lines
 * would have to remember to exclude them, and one day would forget.
 */
@Repository
public interface AiAnalyticsRepository extends JpaRepository<PosProductInfo, Long> {

    /**
     * One row per stocked or recently sold product, with everything the reorder
     * maths needs.
     *
     * <p>The columns worth explaining:
     *
     * <ul>
     *   <li><b>sellable_pcs</b> — not the same as stock. A batch with 400 pieces
     *       and three weeks to run is not 400 pieces of cover if the product
     *       only moves five a day; it is 105 pieces and a write-off. Each batch
     *       is capped at what its own remaining shelf life allows to be sold,
     *       so the deficit that comes out of this is the one that will actually
     *       be felt.</li>
     *   <li><b>pcs_30 / pcs_prev_30</b> — the two windows the trend compares.
     *       Returns are subtracted, so a product sold and handed back has not
     *       moved.</li>
     *   <li><b>selling_days</b> — how many distinct days it sold on in the last
     *       thirty. The window is exclusive at the far end, so "thirty days"
     *       holds thirty of them and a product cannot report selling on
     *       thirty-one. Thirty pieces across twenty days is a rate; thirty pieces on
     *       one day was an event, and forecasting from it is how a pharmacy ends
     *       up with a year of stock.</li>
     * </ul>
     */
    @Query(value = """
           WITH mv AS (
             SELECT m.PID_PRODUCT,
                    SUM(CASE WHEN m.DIRECTION = 'O' THEN m.QTY_PCS ELSE -m.QTY_PCS END)
                        FILTER (WHERE m.MOVEMENT_DATE > CURRENT_DATE - 30)              AS pcs_30,
                    SUM(CASE WHEN m.DIRECTION = 'O' THEN m.QTY_PCS ELSE -m.QTY_PCS END)
                        FILTER (WHERE m.MOVEMENT_DATE > CURRENT_DATE - 60
                                  AND m.MOVEMENT_DATE <= CURRENT_DATE - 30)              AS pcs_prev_30,
                    SUM(CASE WHEN m.DIRECTION = 'O' THEN m.QTY_PCS ELSE -m.QTY_PCS END)
                        FILTER (WHERE m.MOVEMENT_DATE > CURRENT_DATE - 90)              AS pcs_90,
                    COUNT(DISTINCT m.MOVEMENT_DATE)
                        FILTER (WHERE m.MOVEMENT_DATE > CURRENT_DATE - 30
                                  AND m.DIRECTION = 'O')                                 AS selling_days,
                    MAX(m.MOVEMENT_DATE) FILTER (WHERE m.DIRECTION = 'O')                AS last_sold
               FROM PHARMA.POS_STOCK_MOVEMENT m
              WHERE m.MOVEMENT_TYPE IN ('SALE', 'SALES_RETURN')
                AND m.MOVEMENT_DATE > CURRENT_DATE - 90
                AND (CAST(:orgId AS bigint) IS NULL OR m.PID_ORG = :orgId)
                AND (CAST(:branchId AS bigint) IS NULL OR m.PID_BRANCH = :branchId)
              GROUP BY m.PID_PRODUCT
           ),
           rate AS (
             SELECT PID_PRODUCT,
                    GREATEST(COALESCE(pcs_30, 0), 0) / 30.0 AS per_day
               FROM mv
           ),
           bat AS (
             SELECT b.PID_PRODUCT,
                    SUM(b.QTY_PCS)                                              AS stock_pcs,
                    SUM(b.QTY_PCS * COALESCE(b.COST_PER_PCS, 0))                AS stock_value,
                    MIN(b.EXPIRY_DATE)                                          AS nearest_expiry,
                    SUM(b.QTY_PCS) FILTER (
                        WHERE b.EXPIRY_DATE < CURRENT_DATE + :expiryDays)       AS expiring_pcs,
                    SUM(b.QTY_PCS * COALESCE(b.COST_PER_PCS, 0)) FILTER (
                        WHERE b.EXPIRY_DATE < CURRENT_DATE + :expiryDays)       AS expiring_value,
                    -- What can still be sold before it goes out of date, batch
                    -- by batch: never more than the batch holds, never more
                    -- than its remaining days will carry at the current rate.
                    SUM(LEAST(
                        b.QTY_PCS,
                        GREATEST(b.EXPIRY_DATE - CURRENT_DATE, 0) * COALESCE(r.per_day, 0)
                    ))                                                          AS sellable_pcs
               FROM PHARMA.POS_STOCK_BATCH b
               LEFT JOIN rate r ON r.PID_PRODUCT = b.PID_PRODUCT
              WHERE b.QTY_PCS > 0
                AND COALESCE(b.STATUS, 'Y') = 'Y'
                AND (CAST(:orgId AS bigint) IS NULL OR b.PID_ORG = :orgId)
                AND (CAST(:branchId AS bigint) IS NULL OR b.PID_BRANCH = :branchId)
              GROUP BY b.PID_PRODUCT
           )
           SELECT p.PID_PRODUCT,
                  p.PRODUCT_NAME,
                  p.SKU,
                  co.PID_COMPANY,
                  co.COMPANY_NAME,
                  cat.CATEGORY_NAME,
                  COALESCE(bx.NO_OF_LEAF * lf.NO_OF_PCS, 1)          AS pcs_per_box,
                  p.TP_PRICE,
                  p.MRP_PRICE,
                  COALESCE(bat.stock_pcs, 0)                          AS stock_pcs,
                  COALESCE(bat.sellable_pcs, 0)                       AS sellable_pcs,
                  COALESCE(bat.stock_value, 0)                        AS stock_value,
                  bat.nearest_expiry,
                  COALESCE(bat.expiring_pcs, 0)                       AS expiring_pcs,
                  COALESCE(bat.expiring_value, 0)                     AS expiring_value,
                  COALESCE(mv.pcs_30, 0)                              AS pcs_30,
                  COALESCE(mv.pcs_prev_30, 0)                         AS pcs_prev_30,
                  COALESCE(mv.pcs_90, 0)                              AS pcs_90,
                  COALESCE(mv.selling_days, 0)                        AS selling_days,
                  mv.last_sold,
                  COALESCE(p.REORDER_LEVEL, 0)                        AS reorder_level,
                  COALESCE(p.ALERT_QTY, 0)                            AS alert_qty
             FROM PHARMA.POS_PRODUCT_INFO p
             LEFT JOIN mv  ON mv.PID_PRODUCT  = p.PID_PRODUCT
             LEFT JOIN bat ON bat.PID_PRODUCT = p.PID_PRODUCT
             LEFT JOIN PHARMA.POS_BOX_INFO      bx  ON bx.PID_BOX      = p.PID_BOX
             LEFT JOIN PHARMA.POS_LEAF_INFO     lf  ON lf.PID_LEAF     = bx.PID_LEAF
             LEFT JOIN PHARMA.POS_COMPANY_INFO  co  ON co.PID_COMPANY  = p.PID_COMPANY
             LEFT JOIN PHARMA.POS_PRODUCT_CATEGORY cat ON cat.PID_CATEGORY = p.PID_CATEGORY
            WHERE p.STATUS = 'Y'
              AND (CAST(:orgId AS bigint) IS NULL OR p.PID_ORG = :orgId)
              -- Narrowing to one manufacturer is how a buyer places an order
              -- with one supplier: that supplier sells products from one maker
              -- only, so a list spanning four of them cannot be ordered as it is.
              AND (CAST(:companyId AS bigint) IS NULL OR p.PID_COMPANY = :companyId)
              -- A product with neither stock nor recent movement has nothing to
              -- say. Listing it would bury the twenty rows that matter.
              AND (bat.stock_pcs IS NOT NULL OR mv.pcs_90 IS NOT NULL)
           """, nativeQuery = true)
    List<Object[]> productSignals(@Param("orgId") Long orgId,
                                  @Param("branchId") Long branchId,
                                  @Param("companyId") Long companyId,
                                  @Param("expiryDays") int expiryDays);

    /**
     * Revenue and margin per product over a window, for the movers board.
     *
     * <p>Margin comes off the movement's own COST_PER_PCS — the cost of the
     * batch that actually went out — rather than today's purchase price. Using
     * the current price would restate the profit on every past sale each time a
     * supplier changed their rate.
     *
     * <p>Quantity is taken from the ledger too, not from QUANTITY x NO_OF_PCS on
     * the line. Those two disagree on older rows where a box was sold with the
     * piece count left unset, and a margin computed from one basis against a
     * cost computed from the other is not a margin, it is a subtraction of two
     * unrelated numbers.
     */
    @Query(value = """
           SELECT d.PID_PRODUCT,
                  SUM(d.TOTAL_PRICE)                                   AS revenue,
                  SUM(COALESCE(mv.QTY_PCS, d.QUANTITY * COALESCE(d.NO_OF_PCS, 1))) AS pcs,
                  SUM(mv.QTY_PCS * COALESCE(mv.COST_PER_PCS, 0))                            AS cost,
                  COUNT(DISTINCT d.PID_TRAN_MST)                       AS invoices
             FROM PHARMA.POS_TRANSACTION_DTL d
             JOIN PHARMA.POS_TRANSACTION_MST t ON t.PID_TRAN_MST = d.PID_TRAN_MST
             -- One movement per line, not an aggregate over the whole table:
             -- POS_STOCK_MOVEMENT is unique on (REF_TYPE, REF_DTL,
             -- MOVEMENT_TYPE), so this joins through that index instead of
             -- grouping four hundred thousand rows to read a few hundred.
             LEFT JOIN PHARMA.POS_STOCK_MOVEMENT mv
                    ON mv.REF_TYPE = 'TRANSACTION'
                   AND mv.REF_DTL = d.PID_TRAN_DTL
                   AND mv.MOVEMENT_TYPE = 'SALE'
            WHERE t.TRANSACTION_TYPE = 'SL'
              AND t.STAGE <> 'Submitted'
              AND COALESCE(t.STATUS, 'Y') <> 'N'
              -- A merge is a wrapper over orders already counted below it.
              AND COALESCE(t.IS_MERGE_INVOICE, 'N') <> 'Y'
              AND t.TRANSACTION_DATE > CURRENT_DATE - :days
              AND (CAST(:orgId AS bigint) IS NULL OR t.PID_ORG = :orgId)
              AND (CAST(:branchId AS bigint) IS NULL OR t.PID_BRANCH = :branchId)
            GROUP BY d.PID_PRODUCT
           """, nativeQuery = true)
    List<Object[]> productRevenue(@Param("orgId") Long orgId,
                                  @Param("branchId") Long branchId,
                                  @Param("days") int days);

    /**
     * Daily sales for the trend line, already summed by the database so the
     * browser is handed ninety numbers instead of two thousand rows.
     */
    @Query(value = """
           SELECT m.MOVEMENT_DATE,
                  SUM(CASE WHEN m.DIRECTION = 'O' THEN m.QTY_PCS ELSE -m.QTY_PCS END)  AS pcs,
                  SUM(CASE WHEN m.DIRECTION = 'O' THEN 1 ELSE 0 END)                    AS lines
             FROM PHARMA.POS_STOCK_MOVEMENT m
            WHERE m.MOVEMENT_TYPE IN ('SALE', 'SALES_RETURN')
              AND m.MOVEMENT_DATE > CURRENT_DATE - :days
              AND (CAST(:orgId AS bigint) IS NULL OR m.PID_ORG = :orgId)
              AND (CAST(:branchId AS bigint) IS NULL OR m.PID_BRANCH = :branchId)
            GROUP BY m.MOVEMENT_DATE
            ORDER BY m.MOVEMENT_DATE
           """, nativeQuery = true)
    List<Object[]> dailySales(@Param("orgId") Long orgId,
                              @Param("branchId") Long branchId,
                              @Param("days") int days);
}
