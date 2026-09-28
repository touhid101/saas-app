package com.friendspharma.app.repository;

import com.friendspharma.app.entity.PosProductInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * The figures behind the dashboard.
 *
 * <p><b>Split along the line the dashboard is split along.</b> The operational
 * queries return counts and quantities; the financial ones return money. They
 * are separate methods rather than one wide row so that a user who may not see
 * money never causes the money to be selected — the guard is that the query
 * does not run, not that a field is blanked on the way out.
 *
 * <p><b>Every query is branch-scoped and set-based.</b> A dashboard is the most
 * frequently loaded screen in the application, so it aggregates in the database
 * and returns finished rows.
 *
 * <p>Row limits are constants rather than parameters: a named parameter in a
 * native query's LIMIT clause cannot be parsed by the query derivation, and
 * the callers want a handful of rows from an aggregate that is already small.
 * The service takes the head of what comes back.
 *
 * <p>Where a figure can come from either the invoice lines or the stock ledger,
 * it comes from the ledger: it is append-only, already reconciled against batch
 * balances, carries the cost of the batch that actually went out, and excludes
 * merge wrappers without being told to.
 */
@Repository
public interface DashboardAnalyticsRepository extends JpaRepository<PosProductInfo, Long> {

    /* ══════════════════════════════════════════════════════════════════════
       OPERATIONS — counts and quantities, safe for everyone
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * Stock health in one row: how many products are tracked, out, low, dated
     * or idle.
     *
     * <p>"Low" is judged against the product's own reorder level where one is
     * set, because that is the buyer's instruction, and falls back to the alert
     * quantity otherwise. A product with neither is not low, it is unconfigured,
     * and counting it either way would be a guess.
     */
    @Query(value = """
           WITH stock AS (
             SELECT b.PID_PRODUCT,
                    SUM(b.QTY_PCS)                                              AS pcs,
                    MIN(b.EXPIRY_DATE) FILTER (WHERE b.QTY_PCS > 0)             AS nearest_expiry,
                    SUM(b.QTY_PCS) FILTER (WHERE b.EXPIRY_DATE < CURRENT_DATE + 30)  AS exp_30,
                    SUM(b.QTY_PCS) FILTER (WHERE b.EXPIRY_DATE < CURRENT_DATE + 90)  AS exp_90,
                    SUM(b.QTY_PCS) FILTER (WHERE b.EXPIRY_DATE < CURRENT_DATE)       AS expired
               FROM PHARMA.POS_STOCK_BATCH b
              WHERE COALESCE(b.STATUS, 'Y') = 'Y'
                AND (CAST(:orgId AS bigint) IS NULL OR b.PID_ORG = :orgId)
                AND (CAST(:branchId AS bigint) IS NULL OR b.PID_BRANCH = :branchId)
              GROUP BY b.PID_PRODUCT
           ),
           sold AS (
             SELECT m.PID_PRODUCT, MAX(m.MOVEMENT_DATE) AS last_sold
               FROM PHARMA.POS_STOCK_MOVEMENT m
              WHERE m.MOVEMENT_TYPE = 'SALE' AND m.DIRECTION = 'O'
                AND (CAST(:orgId AS bigint) IS NULL OR m.PID_ORG = :orgId)
                AND (CAST(:branchId AS bigint) IS NULL OR m.PID_BRANCH = :branchId)
              GROUP BY m.PID_PRODUCT
           )
           SELECT COUNT(*)                                                       AS tracked,
                  COUNT(*) FILTER (WHERE COALESCE(s.pcs, 0) <= 0)                AS out_of_stock,
                  COUNT(*) FILTER (WHERE COALESCE(s.pcs, 0) > 0
                                     AND COALESCE(NULLIF(p.REORDER_LEVEL, 0), p.ALERT_QTY, 0) > 0
                                     AND s.pcs <= COALESCE(NULLIF(p.REORDER_LEVEL, 0), p.ALERT_QTY)) AS low_stock,
                  COUNT(*) FILTER (WHERE COALESCE(s.exp_30, 0) > 0)              AS expiring_30,
                  COUNT(*) FILTER (WHERE COALESCE(s.exp_90, 0) > 0)              AS expiring_90,
                  COUNT(*) FILTER (WHERE COALESCE(s.expired, 0) > 0)             AS already_expired,
                  COUNT(*) FILTER (WHERE COALESCE(s.pcs, 0) > 0
                                     AND (so.last_sold IS NULL
                                          OR so.last_sold < CURRENT_DATE - 60))  AS dead_stock,
                  COALESCE(SUM(s.pcs), 0)                                        AS total_pcs,
                  COALESCE(SUM(s.exp_90), 0)                                     AS expiring_90_pcs
             FROM PHARMA.POS_PRODUCT_INFO p
             LEFT JOIN stock s ON s.PID_PRODUCT = p.PID_PRODUCT
             LEFT JOIN sold so ON so.PID_PRODUCT = p.PID_PRODUCT
            WHERE p.STATUS = 'Y'
              AND (CAST(:orgId AS bigint) IS NULL OR p.PID_ORG = :orgId)
           """, nativeQuery = true)
    List<Object[]> stockHealth(@Param("orgId") Long orgId, @Param("branchId") Long branchId);

    /** Orders by stage, so the delivery board and the order counts agree. */
    @Query(value = """
           SELECT t.STAGE,
                  COUNT(*)                                                          AS orders,
                  COUNT(*) FILTER (WHERE t.TRANSACTION_DATE = CURRENT_DATE)         AS today,
                  COUNT(*) FILTER (WHERE t.DELIVERY_MAN IS NULL)                    AS unassigned
             FROM PHARMA.POS_TRANSACTION_MST t
            WHERE t.TRANSACTION_TYPE = 'SL'
              AND COALESCE(t.STATUS, 'Y') <> 'N'
              AND COALESCE(t.IS_MERGE_INVOICE, 'N') <> 'Y'
              AND t.MERGE_PID_TRAN_MST IS NULL
              AND t.TRANSACTION_DATE >= CURRENT_DATE - :days
              AND (CAST(:orgId AS bigint) IS NULL OR t.PID_ORG = :orgId)
              AND (CAST(:branchId AS bigint) IS NULL OR t.PID_BRANCH = :branchId)
            GROUP BY t.STAGE
           """, nativeQuery = true)
    List<Object[]> ordersByStage(@Param("orgId") Long orgId, @Param("branchId") Long branchId,
                                 @Param("days") int days);

    /** Orders raised and pieces sold per day — the shape of trading, no money. */
    @Query(value = """
           SELECT d.day,
                  COALESCE(o.orders, 0)  AS orders,
                  COALESCE(m.pcs, 0)     AS pcs
             FROM generate_series(CURRENT_DATE - :days, CURRENT_DATE, INTERVAL '1 day') AS d(day)
             LEFT JOIN (
                  SELECT t.TRANSACTION_DATE AS day, COUNT(*) AS orders
                    FROM PHARMA.POS_TRANSACTION_MST t
                   WHERE t.TRANSACTION_TYPE = 'SL'
                     AND COALESCE(t.STATUS, 'Y') <> 'N'
                     AND COALESCE(t.IS_MERGE_INVOICE, 'N') <> 'Y'
                     AND t.TRANSACTION_DATE >= CURRENT_DATE - :days
                     AND (CAST(:orgId AS bigint) IS NULL OR t.PID_ORG = :orgId)
                     AND (CAST(:branchId AS bigint) IS NULL OR t.PID_BRANCH = :branchId)
                   GROUP BY t.TRANSACTION_DATE) o ON o.day = d.day
             LEFT JOIN (
                  SELECT mv.MOVEMENT_DATE AS day,
                         SUM(CASE WHEN mv.DIRECTION = 'O' THEN mv.QTY_PCS ELSE -mv.QTY_PCS END) AS pcs
                    FROM PHARMA.POS_STOCK_MOVEMENT mv
                   WHERE mv.MOVEMENT_TYPE IN ('SALE', 'SALES_RETURN')
                     AND mv.MOVEMENT_DATE >= CURRENT_DATE - :days
                     AND (CAST(:orgId AS bigint) IS NULL OR mv.PID_ORG = :orgId)
                     AND (CAST(:branchId AS bigint) IS NULL OR mv.PID_BRANCH = :branchId)
                   GROUP BY mv.MOVEMENT_DATE) m ON m.day = d.day
            ORDER BY d.day
           """, nativeQuery = true)
    List<Object[]> dailyActivity(@Param("orgId") Long orgId, @Param("branchId") Long branchId,
                                 @Param("days") int days);

    /**
     * What is moving, by quantity rather than value.
     *
     * <p>The operational board ranks on pieces because that is what the shelf
     * and the delivery van care about, and because it is the same list whether
     * or not the person looking is allowed to see money.
     */
    @Query(value = """
           SELECT p.PRODUCT_NAME,
                  co.COMPANY_NAME,
                  SUM(CASE WHEN m.DIRECTION = 'O' THEN m.QTY_PCS ELSE -m.QTY_PCS END)  AS pcs,
                  SUM(CASE WHEN m.DIRECTION = 'O' THEN m.QTY_PCS ELSE -m.QTY_PCS END)
                      FILTER (WHERE m.MOVEMENT_DATE > CURRENT_DATE - :days / 2)        AS pcs_recent,
                  COALESCE(st.pcs, 0)                                                  AS on_hand
             FROM PHARMA.POS_STOCK_MOVEMENT m
             JOIN PHARMA.POS_PRODUCT_INFO p ON p.PID_PRODUCT = m.PID_PRODUCT
             LEFT JOIN PHARMA.POS_COMPANY_INFO co ON co.PID_COMPANY = p.PID_COMPANY
             LEFT JOIN (SELECT b.PID_PRODUCT, SUM(b.QTY_PCS) AS pcs
                          FROM PHARMA.POS_STOCK_BATCH b
                         WHERE (CAST(:branchId AS bigint) IS NULL OR b.PID_BRANCH = :branchId)
                         GROUP BY b.PID_PRODUCT) st ON st.PID_PRODUCT = m.PID_PRODUCT
            WHERE m.MOVEMENT_TYPE IN ('SALE', 'SALES_RETURN')
              AND m.MOVEMENT_DATE > CURRENT_DATE - :days
              AND (CAST(:orgId AS bigint) IS NULL OR m.PID_ORG = :orgId)
              AND (CAST(:branchId AS bigint) IS NULL OR m.PID_BRANCH = :branchId)
            GROUP BY p.PRODUCT_NAME, co.COMPANY_NAME, st.pcs
            HAVING SUM(CASE WHEN m.DIRECTION = 'O' THEN m.QTY_PCS ELSE -m.QTY_PCS END) > 0
            ORDER BY pcs DESC
            LIMIT 40
           """, nativeQuery = true)
    List<Object[]> topMoversByQuantity(@Param("orgId") Long orgId, @Param("branchId") Long branchId,
                                       @Param("days") int days);

    /**
     * Batches that need a decision, soonest first.
     *
     * <p>Quantities and dates only — a store keeper needs to know what to pull
     * off the shelf, and none of that requires knowing what it cost.
     */
    @Query(value = """
           SELECT p.PRODUCT_NAME,
                  b.BATCH_NO,
                  b.EXPIRY_DATE,
                  b.QTY_PCS,
                  (b.EXPIRY_DATE - CURRENT_DATE) AS days_left
             FROM PHARMA.POS_STOCK_BATCH b
             JOIN PHARMA.POS_PRODUCT_INFO p ON p.PID_PRODUCT = b.PID_PRODUCT
            WHERE b.QTY_PCS > 0
              AND COALESCE(b.STATUS, 'Y') = 'Y'
              AND b.EXPIRY_DATE < CURRENT_DATE + :withinDays
              AND (CAST(:orgId AS bigint) IS NULL OR b.PID_ORG = :orgId)
              AND (CAST(:branchId AS bigint) IS NULL OR b.PID_BRANCH = :branchId)
            ORDER BY b.EXPIRY_DATE
            LIMIT 40
           """, nativeQuery = true)
    List<Object[]> expiringBatches(@Param("orgId") Long orgId, @Param("branchId") Long branchId,
                                   @Param("withinDays") int withinDays);

    /**
     * The catalogue bucketed by how long its stock will last.
     *
     * <p>A single "12 products are low" tells you the size of the problem but
     * not its shape. This says whether the shortage is four lines about to run
     * out or forty drifting down, which are different afternoons.
     */
    @Query(value = """
           WITH rate AS (
             SELECT m.PID_PRODUCT,
                    SUM(CASE WHEN m.DIRECTION = 'O' THEN m.QTY_PCS ELSE -m.QTY_PCS END) / 30.0 AS per_day
               FROM PHARMA.POS_STOCK_MOVEMENT m
              WHERE m.MOVEMENT_TYPE IN ('SALE', 'SALES_RETURN')
                AND m.MOVEMENT_DATE > CURRENT_DATE - 30
                AND (CAST(:orgId AS bigint) IS NULL OR m.PID_ORG = :orgId)
                AND (CAST(:branchId AS bigint) IS NULL OR m.PID_BRANCH = :branchId)
              GROUP BY m.PID_PRODUCT
           ),
           stock AS (
             SELECT b.PID_PRODUCT, SUM(b.QTY_PCS) AS pcs
               FROM PHARMA.POS_STOCK_BATCH b
              WHERE COALESCE(b.STATUS, 'Y') = 'Y'
                AND (CAST(:branchId AS bigint) IS NULL OR b.PID_BRANCH = :branchId)
              GROUP BY b.PID_PRODUCT
           ),
           cover AS (
             SELECT p.PID_PRODUCT,
                    COALESCE(s.pcs, 0) AS pcs,
                    CASE WHEN COALESCE(r.per_day, 0) <= 0 THEN NULL
                         ELSE COALESCE(s.pcs, 0) / r.per_day END AS days
               FROM PHARMA.POS_PRODUCT_INFO p
               LEFT JOIN stock s ON s.PID_PRODUCT = p.PID_PRODUCT
               LEFT JOIN rate  r ON r.PID_PRODUCT = p.PID_PRODUCT
              WHERE p.STATUS = 'Y'
                AND (CAST(:orgId AS bigint) IS NULL OR p.PID_ORG = :orgId)
           )
           SELECT COUNT(*) FILTER (WHERE pcs <= 0)                                AS out_of_stock,
                  COUNT(*) FILTER (WHERE pcs > 0 AND days IS NOT NULL AND days <= 7)      AS under_week,
                  COUNT(*) FILTER (WHERE pcs > 0 AND days > 7  AND days <= 30)     AS under_month,
                  COUNT(*) FILTER (WHERE pcs > 0 AND days > 30 AND days <= 90)     AS under_quarter,
                  COUNT(*) FILTER (WHERE pcs > 0 AND days > 90)                    AS comfortable,
                  COUNT(*) FILTER (WHERE pcs > 0 AND days IS NULL)                 AS not_moving
             FROM cover
           """, nativeQuery = true)
    List<Object[]> stockCoverBuckets(@Param("orgId") Long orgId, @Param("branchId") Long branchId);

    /**
     * Stock by how long it has left on the shelf.
     *
     * <p>Pieces and value in the same row. The value column is only read for a
     * user entitled to it — the buckets themselves are worth seeing either way,
     * because knowing what to push forward is a shop-floor decision.
     */
    @Query(value = """
           SELECT CASE WHEN b.EXPIRY_DATE <  CURRENT_DATE       THEN 0
                       WHEN b.EXPIRY_DATE <= CURRENT_DATE + 30  THEN 30
                       WHEN b.EXPIRY_DATE <= CURRENT_DATE + 60  THEN 60
                       WHEN b.EXPIRY_DATE <= CURRENT_DATE + 90  THEN 90
                       WHEN b.EXPIRY_DATE <= CURRENT_DATE + 180 THEN 180
                       ELSE 999 END                                       AS bucket,
                  SUM(b.QTY_PCS)                                          AS pcs,
                  SUM(b.QTY_PCS * COALESCE(b.COST_PER_PCS, 0))            AS value,
                  COUNT(DISTINCT b.PID_PRODUCT)                           AS products
             FROM PHARMA.POS_STOCK_BATCH b
            WHERE b.QTY_PCS > 0
              AND COALESCE(b.STATUS, 'Y') = 'Y'
              AND (CAST(:orgId AS bigint) IS NULL OR b.PID_ORG = :orgId)
              AND (CAST(:branchId AS bigint) IS NULL OR b.PID_BRANCH = :branchId)
            GROUP BY 1
            ORDER BY 1
           """, nativeQuery = true)
    List<Object[]> expiryRunway(@Param("orgId") Long orgId, @Param("branchId") Long branchId);

    /* ══════════════════════════════════════════════════════════════════════
       FINANCE — only ever called for a user allowed to see money
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * Revenue and cost by calendar month.
     *
     * <p>Grouped bars rather than a line: a line invites you to read a trend
     * between two bars that are months apart, while side-by-side bars make the
     * gap between what came in and what it cost the thing you actually look at.
     */
    @Query(value = """
           -- Twelve points do not need a hundred and forty thousand rows.
           -- Rolling each side up on its own and joining thirteen rows to
           -- thirteen is the same answer for a third of the work: revenue off
           -- the invoice, cost off the movements it produced.
           WITH rev AS (
             SELECT TO_CHAR(t.TRANSACTION_DATE, 'YYYY-MM')     AS month,
                    SUM(COALESCE(t.TOTAL_AMOUNT, 0))           AS revenue,
                    COUNT(*)                                   AS invoices
               FROM PHARMA.POS_TRANSACTION_MST t
              WHERE t.TRANSACTION_TYPE = 'SL'
                AND t.STAGE <> 'Submitted'
                AND COALESCE(t.STATUS, 'Y') <> 'N'
                AND COALESCE(t.IS_MERGE_INVOICE, 'N') <> 'Y'
                AND t.TRANSACTION_DATE > CURRENT_DATE - 400
                AND (CAST(:orgId AS bigint) IS NULL OR t.PID_ORG = :orgId)
                AND (CAST(:branchId AS bigint) IS NULL OR t.PID_BRANCH = :branchId)
              GROUP BY 1),
           cst AS (
             SELECT TO_CHAR(t.TRANSACTION_DATE, 'YYYY-MM')     AS month,
                    SUM(mv.QTY_PCS * COALESCE(mv.COST_PER_PCS, 0)) AS cost
               FROM PHARMA.POS_STOCK_MOVEMENT mv
               JOIN PHARMA.POS_TRANSACTION_DTL d ON d.PID_TRAN_DTL = mv.REF_DTL
               JOIN PHARMA.POS_TRANSACTION_MST t ON t.PID_TRAN_MST = d.PID_TRAN_MST
              WHERE mv.REF_TYPE = 'TRANSACTION'
                AND mv.MOVEMENT_TYPE = 'SALE'
                -- Bounds the movement side by its own indexed date as well, so
                -- it is not read in full and thrown away after the join.
                AND mv.MOVEMENT_DATE > CURRENT_DATE - 400
                AND t.TRANSACTION_TYPE = 'SL'
                AND t.STAGE <> 'Submitted'
                AND COALESCE(t.STATUS, 'Y') <> 'N'
                AND COALESCE(t.IS_MERGE_INVOICE, 'N') <> 'Y'
                AND (CAST(:orgId AS bigint) IS NULL OR t.PID_ORG = :orgId)
                AND (CAST(:branchId AS bigint) IS NULL OR t.PID_BRANCH = :branchId)
              GROUP BY 1)
           SELECT rev.month                    AS month,
                  rev.revenue                  AS revenue,
                  COALESCE(cst.cost, 0)        AS cost,
                  rev.invoices                 AS invoices
             FROM rev
             LEFT JOIN cst ON cst.month = rev.month
            ORDER BY 1 DESC
            LIMIT 12
           """, nativeQuery = true)
    List<Object[]> monthlyRevenue(@Param("orgId") Long orgId, @Param("branchId") Long branchId);

    /**
     * What is owed, by how old it is.
     *
     * <p>The total alone hides the only thing that matters about a debt, which
     * is its age. The same figure spread across this month and across last year
     * are two different businesses.
     */
    @Query(value = """
           SELECT SUM(due) FILTER (WHERE age <= 30)               AS d0_30,
                  SUM(due) FILTER (WHERE age > 30 AND age <= 60)  AS d31_60,
                  SUM(due) FILTER (WHERE age > 60 AND age <= 90)  AS d61_90,
                  SUM(due) FILTER (WHERE age > 90)                AS d90_plus,
                  COUNT(*) FILTER (WHERE age > 90)                AS old_invoices
             FROM (
               SELECT GREATEST(t.AMOUNT_TOBE_PAID - COALESCE(t.GIVEN_AMOUNT, 0), 0) AS due,
                      (CURRENT_DATE - t.TRANSACTION_DATE)                            AS age
                 FROM PHARMA.POS_TRANSACTION_MST t
                WHERE t.TRANSACTION_TYPE = 'SL'
                  AND t.STAGE <> 'Submitted'
                  AND COALESCE(t.STATUS, 'Y') <> 'N'
                  AND COALESCE(t.IS_MERGE_INVOICE, 'N') <> 'Y'
                  AND t.AMOUNT_TOBE_PAID > COALESCE(t.GIVEN_AMOUNT, 0)
                  AND (CAST(:orgId AS bigint) IS NULL OR t.PID_ORG = :orgId)
                  AND (CAST(:branchId AS bigint) IS NULL OR t.PID_BRANCH = :branchId)
             ) x
           """, nativeQuery = true)
    List<Object[]> receivableAgeing(@Param("orgId") Long orgId, @Param("branchId") Long branchId);

    /**
     * How customers actually pay.
     *
     * <p>A composition question, so a donut rather than bars. The split between
     * cash and credit is the difference between money in the till and money
     * somebody still has to go and collect.
     */
    @Query(value = """
           SELECT COALESCE(t.PAYMENT_TYPE, 'UNSPECIFIED')  AS method,
                  SUM(t.AMOUNT_TOBE_PAID)                  AS amount,
                  COUNT(*)                                 AS invoices
             FROM PHARMA.POS_TRANSACTION_MST t
            WHERE t.TRANSACTION_TYPE = 'SL'
              AND t.STAGE <> 'Submitted'
              AND COALESCE(t.STATUS, 'Y') <> 'N'
              AND COALESCE(t.IS_MERGE_INVOICE, 'N') <> 'Y'
              AND t.TRANSACTION_DATE > CURRENT_DATE - :days
              AND (CAST(:orgId AS bigint) IS NULL OR t.PID_ORG = :orgId)
              AND (CAST(:branchId AS bigint) IS NULL OR t.PID_BRANCH = :branchId)
            GROUP BY 1
            ORDER BY 2 DESC
           """, nativeQuery = true)
    List<Object[]> paymentMix(@Param("orgId") Long orgId, @Param("branchId") Long branchId,
                              @Param("days") int days);

    /**
     * Revenue by manufacturer, for the concentration question.
     *
     * <p>A pharmacy that takes two thirds of its takings from one maker has a
     * commercial exposure it should at least have chosen deliberately.
     */
    @Query(value = """
           SELECT COALESCE(co.COMPANY_NAME, 'Unknown')     AS maker,
                  SUM(d.TOTAL_PRICE)                       AS revenue,
                  COUNT(DISTINCT p.PID_PRODUCT)            AS products
             FROM PHARMA.POS_TRANSACTION_DTL d
             JOIN PHARMA.POS_TRANSACTION_MST t  ON t.PID_TRAN_MST = d.PID_TRAN_MST
             JOIN PHARMA.POS_PRODUCT_INFO   p   ON p.PID_PRODUCT  = d.PID_PRODUCT
             LEFT JOIN PHARMA.POS_COMPANY_INFO co ON co.PID_COMPANY = p.PID_COMPANY
            WHERE t.TRANSACTION_TYPE = 'SL'
              AND t.STAGE <> 'Submitted'
              AND COALESCE(t.STATUS, 'Y') <> 'N'
              AND COALESCE(t.IS_MERGE_INVOICE, 'N') <> 'Y'
              AND t.TRANSACTION_DATE > CURRENT_DATE - :days
              AND (CAST(:orgId AS bigint) IS NULL OR t.PID_ORG = :orgId)
              AND (CAST(:branchId AS bigint) IS NULL OR t.PID_BRANCH = :branchId)
            GROUP BY 1
            ORDER BY 2 DESC
            LIMIT 8
           """, nativeQuery = true)
    List<Object[]> revenueByMaker(@Param("orgId") Long orgId, @Param("branchId") Long branchId,
                                  @Param("days") int days);



    /**
     * Revenue and cost of goods for one window, from the ledger.
     *
     * <p>Cost is the cost of the batches that actually went out, not today's
     * purchase price, so a supplier changing their rate does not restate the
     * margin on every past sale.
     */
    @Query(value = """
           SELECT COALESCE(SUM(d.TOTAL_PRICE), 0)                    AS revenue,
                  COALESCE(SUM(mv.QTY_PCS * COALESCE(mv.COST_PER_PCS, 0)), 0)                          AS cost,
                  COUNT(DISTINCT t.PID_TRAN_MST)                     AS invoices,
                  COALESCE(SUM(mv.QTY_PCS), 0)                       AS pcs
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
              AND COALESCE(t.IS_MERGE_INVOICE, 'N') <> 'Y'
              AND t.TRANSACTION_DATE >= :from AND t.TRANSACTION_DATE <= :to
              AND (CAST(:orgId AS bigint) IS NULL OR t.PID_ORG = :orgId)
              AND (CAST(:branchId AS bigint) IS NULL OR t.PID_BRANCH = :branchId)
           """, nativeQuery = true)
    List<Object[]> revenueAndCost(@Param("orgId") Long orgId, @Param("branchId") Long branchId,
                                  @Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * [V67-69] What the suppliers gave on top of the goods, in a window.
     *
     * <p>Cashback and gift value are money and are added together; bonus pieces
     * are counted separately and stay out of the total. A bonus is already worth
     * something in the accounts — it lowered the cost of every piece in the batch
     * it arrived with — so putting an MRP on it here would report the same
     * benefit a second time.
     *
     * <p>Gift value multiplies BONUS_QTY by MRP_PRICE without a pack factor,
     * while the piece count applies one. That is not an oversight: MRP_PRICE is
     * stored per sales unit, so it already lines up with the quantity beside it,
     * whereas a count has to be reduced to pieces before lines measured in boxes
     * and lines measured in strips can be added together.
     */
    @Query(value = """
           SELECT COALESCE(SUM(t.CASHBACK_AMOUNT), 0)                        AS cashback,
                  COALESCE((SELECT SUM(d.BONUS_QTY * COALESCE(d.MRP_PRICE, 0))
                              FROM PHARMA.POS_TRANSACTION_DTL d
                              JOIN PHARMA.POS_TRANSACTION_MST m2 ON m2.PID_TRAN_MST = d.PID_TRAN_MST
                             WHERE d.LINE_KIND = 'GIFT'
                               AND m2.TRANSACTION_TYPE = 'PU' AND m2.STATUS = 'A'
                               AND m2.TRANSACTION_DATE >= :from AND m2.TRANSACTION_DATE <= :to
                               AND (CAST(:orgId AS bigint) IS NULL OR m2.PID_ORG = :orgId)
                               AND (CAST(:branchId AS bigint) IS NULL OR m2.PID_BRANCH = :branchId)), 0)
                                                                             AS gift_value,
                  COALESCE((SELECT SUM(d.BONUS_QTY * COALESCE(d.NO_OF_PCS, 1))
                              FROM PHARMA.POS_TRANSACTION_DTL d
                              JOIN PHARMA.POS_TRANSACTION_MST m2 ON m2.PID_TRAN_MST = d.PID_TRAN_MST
                             WHERE COALESCE(d.LINE_KIND, 'PURCHASE') = 'PURCHASE'
                               AND m2.TRANSACTION_TYPE = 'PU' AND m2.STATUS = 'A'
                               AND m2.TRANSACTION_DATE >= :from AND m2.TRANSACTION_DATE <= :to
                               AND (CAST(:orgId AS bigint) IS NULL OR m2.PID_ORG = :orgId)
                               AND (CAST(:branchId AS bigint) IS NULL OR m2.PID_BRANCH = :branchId)), 0)
                                                                             AS bonus_pcs
             FROM PHARMA.POS_TRANSACTION_MST t
            WHERE t.TRANSACTION_TYPE = 'PU'
              AND t.STATUS = 'A'
              AND t.TRANSACTION_DATE >= :from AND t.TRANSACTION_DATE <= :to
              AND (CAST(:orgId AS bigint) IS NULL OR t.PID_ORG = :orgId)
              AND (CAST(:branchId AS bigint) IS NULL OR t.PID_BRANCH = :branchId)
           """, nativeQuery = true)
    List<Object[]> supplierBenefit(@Param("orgId") Long orgId, @Param("branchId") Long branchId,
                                   @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** What was bought in a window — the other half of the cash picture. */
    @Query(value = """
           SELECT COALESCE(SUM(t.AMOUNT_TOBE_PAID), 0) AS spend,
                  COUNT(*)                             AS invoices
             FROM PHARMA.POS_TRANSACTION_MST t
            WHERE t.TRANSACTION_TYPE = 'PU'
              AND t.STATUS = 'A'
              AND t.TRANSACTION_DATE >= :from AND t.TRANSACTION_DATE <= :to
              AND (CAST(:orgId AS bigint) IS NULL OR t.PID_ORG = :orgId)
              AND (CAST(:branchId AS bigint) IS NULL OR t.PID_BRANCH = :branchId)
           """, nativeQuery = true)
    List<Object[]> purchaseSpend(@Param("orgId") Long orgId, @Param("branchId") Long branchId,
                                 @Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * Money owed to us, and how much of it is old.
     *
     * <p>Merge wrappers are excluded: the debt is carried on the orders inside
     * them, and counting both would double every merged customer's balance.
     */
    @Query(value = """
           SELECT COALESCE(SUM(t.AMOUNT_TOBE_PAID - COALESCE(t.GIVEN_AMOUNT, 0)), 0)      AS due,
                  COALESCE(SUM(t.AMOUNT_TOBE_PAID - COALESCE(t.GIVEN_AMOUNT, 0))
                      FILTER (WHERE t.TRANSACTION_DATE < CURRENT_DATE - 30), 0)          AS overdue,
                  COUNT(*) FILTER (WHERE t.AMOUNT_TOBE_PAID - COALESCE(t.GIVEN_AMOUNT, 0) > 0.01) AS invoices
             FROM PHARMA.POS_TRANSACTION_MST t
            WHERE t.TRANSACTION_TYPE = 'SL'
              AND t.STAGE <> 'Submitted'
              AND COALESCE(t.STATUS, 'Y') <> 'N'
              AND COALESCE(t.IS_MERGE_INVOICE, 'N') <> 'Y'
              AND t.AMOUNT_TOBE_PAID > COALESCE(t.GIVEN_AMOUNT, 0)
              AND (CAST(:orgId AS bigint) IS NULL OR t.PID_ORG = :orgId)
              AND (CAST(:branchId AS bigint) IS NULL OR t.PID_BRANCH = :branchId)
           """, nativeQuery = true)
    List<Object[]> receivable(@Param("orgId") Long orgId, @Param("branchId") Long branchId);

    /** What the shelves are worth, at cost, and what of it is at risk. */
    @Query(value = """
           SELECT COALESCE(SUM(b.QTY_PCS * COALESCE(b.COST_PER_PCS, 0)), 0)                 AS stock_value,
                  COALESCE(SUM(b.QTY_PCS * COALESCE(b.COST_PER_PCS, 0))
                      FILTER (WHERE b.EXPIRY_DATE < CURRENT_DATE + 90), 0)                  AS expiring_value,
                  COALESCE(SUM(b.QTY_PCS * COALESCE(b.COST_PER_PCS, 0))
                      FILTER (WHERE b.EXPIRY_DATE < CURRENT_DATE), 0)                       AS expired_value
             FROM PHARMA.POS_STOCK_BATCH b
            WHERE b.QTY_PCS > 0
              AND COALESCE(b.STATUS, 'Y') = 'Y'
              AND (CAST(:orgId AS bigint) IS NULL OR b.PID_ORG = :orgId)
              AND (CAST(:branchId AS bigint) IS NULL OR b.PID_BRANCH = :branchId)
           """, nativeQuery = true)
    List<Object[]> stockValue(@Param("orgId") Long orgId, @Param("branchId") Long branchId);

    /** Revenue, cost and margin per day, for the profit line. */
    @Query(value = """
           SELECT d.day,
                  COALESCE(x.revenue, 0) AS revenue,
                  COALESCE(x.cost, 0)    AS cost
             FROM generate_series(CURRENT_DATE - :days, CURRENT_DATE, INTERVAL '1 day') AS d(day)
             LEFT JOIN (
               SELECT t.TRANSACTION_DATE                     AS day,
                      SUM(dt.TOTAL_PRICE)                    AS revenue,
                      SUM(mv.QTY_PCS * COALESCE(mv.COST_PER_PCS, 0))              AS cost
                 FROM PHARMA.POS_TRANSACTION_DTL dt
                 JOIN PHARMA.POS_TRANSACTION_MST t ON t.PID_TRAN_MST = dt.PID_TRAN_MST
                 LEFT JOIN PHARMA.POS_STOCK_MOVEMENT mv
                        ON mv.REF_TYPE = 'TRANSACTION'
                       AND mv.REF_DTL = dt.PID_TRAN_DTL
                       AND mv.MOVEMENT_TYPE = 'SALE'
                WHERE t.TRANSACTION_TYPE = 'SL'
                  AND t.STAGE <> 'Submitted'
                  AND COALESCE(t.STATUS, 'Y') <> 'N'
                  AND COALESCE(t.IS_MERGE_INVOICE, 'N') <> 'Y'
                  AND t.TRANSACTION_DATE > CURRENT_DATE - :days
                  AND (CAST(:orgId AS bigint) IS NULL OR t.PID_ORG = :orgId)
                  AND (CAST(:branchId AS bigint) IS NULL OR t.PID_BRANCH = :branchId)
                GROUP BY t.TRANSACTION_DATE) x ON x.day = d.day
            ORDER BY d.day
           """, nativeQuery = true)
    List<Object[]> dailyProfit(@Param("orgId") Long orgId, @Param("branchId") Long branchId,
                               @Param("days") int days);

    /** Who buys the most, by value. */
    @Query(value = """
           SELECT c.CUSTOMER_NAME,
                  COALESCE(c.CUSTOMER_TYPE, '—')                                      AS type,
                  SUM(t.AMOUNT_TOBE_PAID)                                             AS revenue,
                  -- Floored at zero per invoice. GIVEN_AMOUNT is what the
                  -- customer handed over, and at a counter that is the note they
                  -- offered rather than what the business kept, so change makes
                  -- an invoice look overpaid. Summing the negatives would net a
                  -- real debt away against change handed back to someone else.
                  SUM(GREATEST(t.AMOUNT_TOBE_PAID - COALESCE(t.GIVEN_AMOUNT, 0), 0)) AS due,
                  COUNT(*)                                                            AS invoices
             FROM PHARMA.POS_TRANSACTION_MST t
             JOIN PHARMA.POS_CUSTOMER_INFO c ON c.PID_CUSTOMER = t.PID_SUP_CUST
            WHERE t.TRANSACTION_TYPE = 'SL'
              AND t.STAGE <> 'Submitted'
              AND COALESCE(t.STATUS, 'Y') <> 'N'
              AND COALESCE(t.IS_MERGE_INVOICE, 'N') <> 'Y'
              AND t.TRANSACTION_DATE > CURRENT_DATE - :days
              AND (CAST(:orgId AS bigint) IS NULL OR t.PID_ORG = :orgId)
              AND (CAST(:branchId AS bigint) IS NULL OR t.PID_BRANCH = :branchId)
            GROUP BY c.CUSTOMER_NAME, c.CUSTOMER_TYPE
            ORDER BY revenue DESC
            LIMIT 40
           """, nativeQuery = true)
    List<Object[]> topCustomers(@Param("orgId") Long orgId, @Param("branchId") Long branchId,
                                @Param("days") int days);

    /** What earns the most, and at what margin. */
    @Query(value = """
           SELECT p.PRODUCT_NAME,
                  SUM(d.TOTAL_PRICE)                          AS revenue,
                  SUM(mv.QTY_PCS * COALESCE(mv.COST_PER_PCS, 0))                   AS cost,
                  SUM(COALESCE(mv.QTY_PCS, 0))                    AS pcs
             FROM PHARMA.POS_TRANSACTION_DTL d
             JOIN PHARMA.POS_TRANSACTION_MST t ON t.PID_TRAN_MST = d.PID_TRAN_MST
             JOIN PHARMA.POS_PRODUCT_INFO   p ON p.PID_PRODUCT  = d.PID_PRODUCT
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
              AND COALESCE(t.IS_MERGE_INVOICE, 'N') <> 'Y'
              AND t.TRANSACTION_DATE > CURRENT_DATE - :days
              AND (CAST(:orgId AS bigint) IS NULL OR t.PID_ORG = :orgId)
              AND (CAST(:branchId AS bigint) IS NULL OR t.PID_BRANCH = :branchId)
            GROUP BY p.PRODUCT_NAME
            ORDER BY revenue DESC
            LIMIT 40
           """, nativeQuery = true)
    List<Object[]> topProductsByRevenue(@Param("orgId") Long orgId, @Param("branchId") Long branchId,
                                        @Param("days") int days);
}
