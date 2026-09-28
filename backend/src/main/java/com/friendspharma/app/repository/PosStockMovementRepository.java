package com.friendspharma.app.repository;

import com.friendspharma.app.entity.PosStockMovement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * Stock movement log — reads only.
 *
 * <p>There is deliberately no update or delete path. Movements are corrected by
 * writing an opposing row, so that the history of a correction survives.
 */
@Repository
public interface PosStockMovementRepository extends JpaRepository<PosStockMovement, Long> {

    /**
     * Stock ledger: every movement in a branch, newest first, optionally
     * narrowed to one product or one date range.
     */
    @Query("""
           SELECT m FROM PosStockMovement m
            WHERE m.pidBranch = :branchId
              AND (:filterProducts = FALSE
                   OR m.pidProduct IN :productIds
                   OR (:searchBatch IS NOT NULL AND LOWER(m.batchNo) LIKE :searchBatch))
              AND (:batchNo IS NULL OR LOWER(m.batchNo) LIKE :batchNo)
              AND (CAST(:fromDate AS date) IS NULL OR m.movementDate >= :fromDate)
              AND (CAST(:toDate   AS date) IS NULL OR m.movementDate <= :toDate)
            ORDER BY m.movementDate DESC, m.pidMovement DESC
           """)
    Page<PosStockMovement> findLedger(@Param("branchId") Long branchId,
                                      @Param("filterProducts") boolean filterProducts,
                                      @Param("productIds") Collection<Long> productIds,
                                      @Param("searchBatch") String searchBatch,
                                      @Param("batchNo") String batchNo,
                                      @Param("fromDate") LocalDate fromDate,
                                      @Param("toDate") LocalDate toDate,
                                      Pageable pageable);

    /** Full movement history of one lot, oldest first — the audit trail for a batch. */
    List<PosStockMovement> findByPidStockOrderByPidMovementAsc(Long pidStock);

    /**
     * Reconciliation: lots whose stored balance disagrees with the sum of their
     * own movements.
     *
     * <p>This should always return nothing. When it does not, the movement log
     * wins and the balance is rebuilt from it — that is the point of keeping
     * both. Run nightly; a non-empty result is a bug that was previously
     * invisible, not a rounding artefact.
     */
    @Query(value = """
           SELECT b.PID_STOCK,
                  b.PID_BRANCH,
                  b.PID_PRODUCT,
                  b.BATCH_NO,
                  b.QTY_PCS                                    AS stored_balance,
                  COALESCE(SUM(CASE WHEN m.DIRECTION = 'I' THEN m.QTY_PCS
                                    ELSE -m.QTY_PCS END), 0)   AS ledger_balance
             FROM PHARMA.POS_STOCK_BATCH b
             LEFT JOIN PHARMA.POS_STOCK_MOVEMENT m ON m.PID_STOCK = b.PID_STOCK
            GROUP BY b.PID_STOCK, b.PID_BRANCH, b.PID_PRODUCT, b.BATCH_NO, b.QTY_PCS
           HAVING b.QTY_PCS <> COALESCE(SUM(CASE WHEN m.DIRECTION = 'I' THEN m.QTY_PCS
                                                 ELSE -m.QTY_PCS END), 0)
           """, nativeQuery = true)
    List<Object[]> findBalanceDivergence();

    /**
     * Product movement summary for a period — opening, everything that moved,
     * and closing, per product.
     *
     * <p>All four document types in one pass. Opening is every movement before
     * the window; the window's own movements are split by type; closing is
     * opening plus the net of them, so the row proves its own arithmetic.
     *
     * <p>Columns: productId, opening, purchased, purchaseReturned, sold,
     * salesReturned, adjusted, closing, purchaseValue, salesCostValue.
     *
     * <p>Sales and sales returns contribute nothing until those modules write
     * documents; the report is built to receive them rather than needing a
     * rewrite when they arrive.
     */
    @Query(value = """
           SELECT m.PID_PRODUCT,
                  COALESCE(SUM(CASE WHEN m.MOVEMENT_DATE < :fromDate
                                    THEN CASE WHEN m.DIRECTION = 'I' THEN m.QTY_PCS ELSE -m.QTY_PCS END
                                    ELSE 0 END), 0)                                   AS opening_pcs,

                  COALESCE(SUM(CASE WHEN m.MOVEMENT_DATE BETWEEN :fromDate AND :toDate
                                     AND m.MOVEMENT_TYPE IN ('PURCHASE','OPENING')
                                    THEN m.QTY_PCS ELSE 0 END), 0)                    AS purchased_pcs,

                  COALESCE(SUM(CASE WHEN m.MOVEMENT_DATE BETWEEN :fromDate AND :toDate
                                     AND m.MOVEMENT_TYPE = 'PURCHASE_RETURN'
                                    THEN m.QTY_PCS ELSE 0 END), 0)                    AS purchase_returned_pcs,

                  COALESCE(SUM(CASE WHEN m.MOVEMENT_DATE BETWEEN :fromDate AND :toDate
                                     AND m.MOVEMENT_TYPE = 'SALE'
                                    THEN m.QTY_PCS ELSE 0 END), 0)                    AS sold_pcs,

                  COALESCE(SUM(CASE WHEN m.MOVEMENT_DATE BETWEEN :fromDate AND :toDate
                                     AND m.MOVEMENT_TYPE = 'SALES_RETURN'
                                    THEN m.QTY_PCS ELSE 0 END), 0)                    AS sales_returned_pcs,

                  COALESCE(SUM(CASE WHEN m.MOVEMENT_DATE BETWEEN :fromDate AND :toDate
                                     AND m.MOVEMENT_TYPE IN ('ADJUST','TRANSFER_IN','TRANSFER_OUT')
                                    THEN CASE WHEN m.DIRECTION = 'I' THEN m.QTY_PCS ELSE -m.QTY_PCS END
                                    ELSE 0 END), 0)                                   AS adjusted_pcs,

                  COALESCE(SUM(CASE WHEN m.MOVEMENT_DATE <= :toDate
                                    THEN CASE WHEN m.DIRECTION = 'I' THEN m.QTY_PCS ELSE -m.QTY_PCS END
                                    ELSE 0 END), 0)                                   AS closing_pcs,

                  COALESCE(SUM(CASE WHEN m.MOVEMENT_DATE BETWEEN :fromDate AND :toDate
                                     AND m.MOVEMENT_TYPE IN ('PURCHASE','OPENING')
                                    THEN m.QTY_PCS * COALESCE(m.COST_PER_PCS, 0) ELSE 0 END), 0)
                                                                                      AS purchase_value,

                  COALESCE(SUM(CASE WHEN m.MOVEMENT_DATE BETWEEN :fromDate AND :toDate
                                     AND m.MOVEMENT_TYPE = 'SALE'
                                    THEN m.QTY_PCS * COALESCE(m.COST_PER_PCS, 0) ELSE 0 END), 0)
                                                                                      AS sales_cost_value
             FROM PHARMA.POS_STOCK_MOVEMENT m
            WHERE m.PID_BRANCH = :branchId
              AND (:filterProducts = FALSE
                   OR m.PID_PRODUCT IN (:productIds)
                   OR (CAST(:searchBatch AS text) IS NOT NULL AND LOWER(m.BATCH_NO) LIKE :searchBatch))
              AND (CAST(:batchNo AS text) IS NULL OR LOWER(m.BATCH_NO) LIKE :batchNo)
              AND m.MOVEMENT_DATE <= :toDate
            GROUP BY m.PID_PRODUCT
           HAVING COALESCE(SUM(CASE WHEN m.MOVEMENT_DATE <= :toDate
                                    THEN CASE WHEN m.DIRECTION = 'I' THEN m.QTY_PCS ELSE -m.QTY_PCS END
                                    ELSE 0 END), 0) <> 0
               OR COALESCE(SUM(CASE WHEN m.MOVEMENT_DATE BETWEEN :fromDate AND :toDate
                                    THEN m.QTY_PCS ELSE 0 END), 0) <> 0
            ORDER BY m.PID_PRODUCT
           """, nativeQuery = true)
    List<Object[]> movementSummary(@Param("branchId") Long branchId,
                                   @Param("filterProducts") boolean filterProducts,
                                   @Param("productIds") Collection<Long> productIds,
                                   @Param("searchBatch") String searchBatch,
                                   @Param("batchNo") String batchNo,
                                   @Param("fromDate") LocalDate fromDate,
                                   @Param("toDate") LocalDate toDate);

    /**
     * Actual profit per product for a period.
     *
     * <p>Revenue comes from the sale lines; cost comes from the movement rows
     * those lines produced, each carrying the cost of the specific batch it
     * consumed. That pairing is the whole point of batch-level tracking — an
     * average product cost would give a plausible-looking number that is wrong
     * for every individual sale.
     *
     * <p>Joined on {@code REF_DTL} so a line that spanned two lots contributes
     * both lots' costs, at their own rates. Sales returns are netted off both
     * revenue and cost, so a returned item stops counting as profit.
     *
     * <p>Revenue is each line's own {@code TOTAL_PRICE} less its share of the
     * <b>invoice-level</b> discount — what the customer was actually charged,
     * not what the lines added up to before the header discount came off.
     *
     * <p>This mirrors the cost side exactly. Taking the raw line total
     * overstates revenue by the header percentage and overstates margin with
     * it, which is the same fault {@code V11} corrected for landed cost, just
     * pointing the other way.
     *
     * <p>The join to the detail line is deliberately an <b>inner</b> join.
     * Editing an unconfirmed invoice deletes its lines and writes new ones,
     * leaving the original SALE movements pointing at rows that no longer
     * exist. An outer join counted their cost against no revenue and
     * manufactured a loss out of nothing — a product selling at a healthy
     * margin reported as sold below cost. A sale movement with no surviving
     * line belongs to no invoice and contributes neither side.
     *
     * <p>Columns: productId, soldPcs, returnedPcs, netPcs, revenue, cost.
     *
     * <p>No {@code --} comments in the SQL below: Spring Data tokenises the
     * string without stripping them, so an apostrophe in a comment reads as an
     * unterminated quote and the repository fails to build at startup.
     */
    // Written as a derived table rather than a CTE: Spring Data refuses to
    // parse a native query beginning with WITH, and fails at context startup
    // rather than at call time.
    @Query(value = """
           SELECT sc.PID_PRODUCT,
                  COALESCE(SUM(CASE WHEN sc.MOVEMENT_TYPE = 'SALE'         THEN sc.pcs  ELSE 0 END), 0) AS sold_pcs,
                  COALESCE(SUM(CASE WHEN sc.MOVEMENT_TYPE = 'SALES_RETURN' THEN sc.pcs  ELSE 0 END), 0) AS returned_pcs,
                  COALESCE(SUM(CASE WHEN sc.MOVEMENT_TYPE = 'SALE'         THEN sc.pcs
                                    ELSE -sc.pcs END), 0)                                              AS net_pcs,

                  COALESCE(SUM(CASE WHEN sc.MOVEMENT_TYPE = 'SALE'
                                    THEN COALESCE(d.TOTAL_PRICE, 0)
                                       - ROUND(COALESCE(d.TOTAL_PRICE, 0)
                                               * COALESCE(sm.INVOICE_DIS_PCT, 0) / 100, 2)
                                    ELSE 0 END), 0)
                - COALESCE(SUM(CASE WHEN sc.MOVEMENT_TYPE = 'SALES_RETURN'
                                    THEN COALESCE(d.TOTAL_PRICE, 0)
                                       - ROUND(COALESCE(d.TOTAL_PRICE, 0)
                                               * COALESCE(sm.INVOICE_DIS_PCT, 0) / 100, 2)
                                    ELSE 0 END), 0)                                                     AS revenue,

                  COALESCE(SUM(CASE WHEN sc.MOVEMENT_TYPE = 'SALE'
                                    THEN sc.cost ELSE -sc.cost END), 0)                                 AS cost
             FROM (
                   SELECT m.PID_PRODUCT,
                          m.REF_DTL,
                          m.MOVEMENT_TYPE,
                          SUM(m.QTY_PCS)                                AS pcs,
                          SUM(m.QTY_PCS * COALESCE(m.COST_PER_PCS, 0))  AS cost
                     FROM PHARMA.POS_STOCK_MOVEMENT m
                    WHERE m.PID_BRANCH = :branchId
                      AND m.MOVEMENT_TYPE IN ('SALE','SALES_RETURN')
                      AND m.MOVEMENT_DATE BETWEEN :fromDate AND :toDate
                      AND (:filterProducts = FALSE
                           OR m.PID_PRODUCT IN (:productIds)
                           OR (CAST(:searchBatch AS text) IS NOT NULL AND LOWER(m.BATCH_NO) LIKE :searchBatch))
                      AND (CAST(:batchNo AS text) IS NULL OR LOWER(m.BATCH_NO) LIKE :batchNo)
                    GROUP BY m.PID_PRODUCT, m.REF_DTL, m.MOVEMENT_TYPE
             ) sc
             JOIN PHARMA.POS_TRANSACTION_DTL d ON d.PID_TRAN_DTL = sc.REF_DTL
             JOIN PHARMA.POS_TRANSACTION_MST sm ON sm.PID_TRAN_MST = d.PID_TRAN_MST
            GROUP BY sc.PID_PRODUCT
            ORDER BY sc.PID_PRODUCT
           """, nativeQuery = true)
    List<Object[]> profitByProduct(@Param("branchId") Long branchId,
                                   @Param("filterProducts") boolean filterProducts,
                                   @Param("productIds") Collection<Long> productIds,
                                   @Param("searchBatch") String searchBatch,
                                   @Param("batchNo") String batchNo,
                                   @Param("fromDate") LocalDate fromDate,
                                   @Param("toDate") LocalDate toDate);

    /** Guards double-posting when an invoice is approved more than once. */
    boolean existsByRefTypeAndRefDtlAndMovementType(String refType, Long refDtl, String movementType);

    /** Every movement a document posted — the rows a cancellation must oppose. */
    List<PosStockMovement> findByRefTypeAndRefMstAndMovementType(
            String refType, Long refMst, String movementType);

    /**
     * The movements one line already posted.
     *
     * <p>Lets a replayed posting report what actually happened rather than
     * recomputing an allocation against stock that has since moved on.
     */
    List<PosStockMovement> findByRefTypeAndRefDtlAndMovementTypeOrderByPidMovementAsc(
            String refType, Long refDtl, String movementType);
}
