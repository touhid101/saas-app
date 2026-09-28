package com.friendspharma.app.repository;

import com.friendspharma.app.entity.PosStockBatch;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Batch stock reads and allocation.
 *
 * <p>Every query starts with {@code pidBranch}. Branch is not an optional
 * filter here — it is the scope. A query without it would silently report one
 * branch's stock to another, which is exactly the defect this module replaces.
 */
@Repository
public interface PosStockBatchRepository extends JpaRepository<PosStockBatch, Long> {

    /* ── Lot identity ─────────────────────────────────────────────────────── */

    Optional<PosStockBatch> findByPidBranchAndPidProductAndBatchNo(
            Long pidBranch, Long pidProduct, String batchNo);

    /* ── Taking stock out of a lot ────────────────────────────────────────── */

    /**
     * Takes pieces out of one lot, in the database, and only if they are still there.
     *
     * <p>The read-check-write this replaces loses updates. Two sales that both
     * read a lot of 60 pcs both passed their own check and both wrote
     * {@code 60 - 60 = 0}: 120 pieces left a lot that held 60, and nothing in
     * the ledger or on the shelf said so. A row lock alone does not settle it
     * either — a lot already loaded earlier in the same transaction (an
     * availability check, an allocation preview) stays in the persistence
     * context at its old quantity, and that stale figure is what would be
     * written back.
     *
     * <p>So the arithmetic happens where the truth is. PostgreSQL serialises
     * the two statements on the row and re-checks {@code qtyPcs >= :qty}
     * against the committed value, so the second one matches nothing and
     * returns 0 — the caller then refuses the sale instead of inventing stock.
     * This writes behind the persistence context's back on purpose, so callers
     * must refresh the entity before reading its balance again.
     *
     * @return 1 when the pieces were taken, 0 when the lot no longer holds them
     */
    @Modifying(flushAutomatically = true)
    @Query("""
           UPDATE PosStockBatch b
              SET b.qtyPcs      = b.qtyPcs - :qty,
                  b.issuedPcs   = COALESCE(b.issuedPcs, 0) + :qty,
                  b.status      = CASE WHEN b.qtyPcs - :qty = 0 THEN 'X' ELSE b.status END,
                  b.updatedDate = CURRENT_TIMESTAMP
            WHERE b.pidStock = :lotId
              AND b.qtyPcs  >= :qty
           """)
    int deductIfAvailable(@Param("lotId") Long lotId, @Param("qty") BigDecimal qty);

    /* ── Batch-wise FEFO allocation ───────────────────────────────────────── */

    /**
     * Issuable lots for one product in one branch, in allocation order.
     *
     * <p><b>Batch-wise FEFO.</b> The rows are batches, never expiry-date
     * buckets: two lots sharing an expiry stay separate, keep their own costs
     * and are consumed one after the other. Ordering is earliest expiry first,
     * then earliest receipt, then batch number so the sequence is total and
     * repeatable — an allocation preview and the allocation that follows it
     * must walk the lots in the same order or the preview is a lie.
     *
     * <p>Lots with no expiry sort last: an unknown expiry is not an imminent
     * one, and putting nulls first would drain undated stock before anything
     * that actually expires.
     */
    @Query("""
           SELECT b FROM PosStockBatch b
            WHERE b.pidBranch  = :branchId
              AND b.pidProduct = :productId
              AND b.status     = 'Y'
              AND b.qtyPcs     > 0
              AND (:includeExpired = TRUE OR b.expiryDate IS NULL OR b.expiryDate >= :today)
            ORDER BY CASE WHEN b.expiryDate IS NULL THEN 1 ELSE 0 END,
                     b.expiryDate ASC, b.receivedDate ASC, b.batchNo ASC
           """)
    List<PosStockBatch> findAllocatableBatches(@Param("branchId") Long branchId,
                                               @Param("productId") Long productId,
                                               @Param("today") LocalDate today,
                                               @Param("includeExpired") boolean includeExpired);

    /**
     * Same order as {@link #findAllocatableBatches}, but row-locked.
     *
     * <p>Used by the write path. Without the lock two concurrent sales both
     * read 10 pcs available, both pass the check and both deduct — overselling
     * the lot. The {@code CK_STOCK_QTY_POSITIVE} constraint would catch the
     * second one, but as a failed transaction rather than an orderly refusal.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
           SELECT b FROM PosStockBatch b
            WHERE b.pidBranch  = :branchId
              AND b.pidProduct = :productId
              AND b.status     = 'Y'
              AND b.qtyPcs     > 0
              AND (b.expiryDate IS NULL OR b.expiryDate >= :today)
            ORDER BY CASE WHEN b.expiryDate IS NULL THEN 1 ELSE 0 END,
                     b.expiryDate ASC, b.receivedDate ASC, b.batchNo ASC
           """)
    List<PosStockBatch> lockAllocatableBatches(@Param("branchId") Long branchId,
                                               @Param("productId") Long productId,
                                               @Param("today") LocalDate today);

    /* ── Availability ─────────────────────────────────────────────────────── */

    /**
     * Sellable pieces for many products at once, in one branch.
     *
     * <p>The same figure {@link #availablePcs} gives, asked once for a whole
     * page of products instead of once each — a picker listing a hundred rows
     * should not be a hundred queries.
     *
     * <p>Callers used POS_PRODUCT_INFO.STOCK_QTY for this, which is a
     * denormalised field nothing maintains: in this database it is null for
     * almost every product while the lots behind them hold hundreds of pieces.
     * Stock lives in the batches, and this reads it there.
     */
    @Query("""
           SELECT b.pidProduct, COALESCE(SUM(b.qtyPcs), 0)
             FROM PosStockBatch b
            WHERE b.pidBranch = :branchId
              AND b.pidProduct IN :productIds
              AND b.status = 'Y'
              AND (b.expiryDate IS NULL OR b.expiryDate >= :today)
            GROUP BY b.pidProduct
           """)
    List<Object[]> availablePcsFor(@Param("branchId") Long branchId,
                                   @Param("productIds") List<Long> productIds,
                                   @Param("today") LocalDate today);
    /** Sellable pieces of one product in one branch — expired stock excluded. */
    @Query("""
           SELECT COALESCE(SUM(b.qtyPcs), 0) FROM PosStockBatch b
            WHERE b.pidBranch  = :branchId
              AND b.pidProduct = :productId
              AND b.status     = 'Y'
              AND (b.expiryDate IS NULL OR b.expiryDate >= :today)
           """)
    BigDecimal availablePcs(@Param("branchId") Long branchId,
                            @Param("productId") Long productId,
                            @Param("today") LocalDate today);

    /* ── Batch stock report ───────────────────────────────────────────────── */

    /**
     * Lot-level stock, filtered.
     *
     * <p>{@code productIds} carries the company / SKU / name / category filter,
     * resolved to a concrete id set by the service. {@code filterProducts}
     * distinguishes "no product filter" from "a filter that matched nothing" —
     * a nullable collection parameter cannot express that difference, and
     * Hibernate will not bind a null {@code IN} list anyway.
     */
    @Query("""
           SELECT b FROM PosStockBatch b
            WHERE b.pidBranch = :branchId
              AND (:filterProducts = FALSE
                   OR b.pidProduct IN :productIds
                   OR (:searchBatch IS NOT NULL AND LOWER(b.batchNo) LIKE :searchBatch))
              AND (:batchNo IS NULL OR LOWER(b.batchNo) LIKE :batchNo)
              AND (:includeEmpty = TRUE OR b.qtyPcs > 0)
            ORDER BY CASE WHEN b.expiryDate IS NULL THEN 1 ELSE 0 END,
                     b.expiryDate ASC, b.pidProduct ASC, b.batchNo ASC
           """)
    Page<PosStockBatch> findBatchStock(@Param("branchId") Long branchId,
                                       @Param("filterProducts") boolean filterProducts,
                                       @Param("productIds") Collection<Long> productIds,
                                       @Param("searchBatch") String searchBatch,
                                       @Param("batchNo") String batchNo,
                                       @Param("includeEmpty") boolean includeEmpty,
                                       Pageable pageable);

    /* ── Expiry ───────────────────────────────────────────────────────────── */

    /**
     * Lots expiring on or before {@code cutoff} that still hold stock.
     * Already-expired lots are included — they are the more urgent case, and
     * hiding them until someone asks for a negative window is how expired
     * stock stays on a shelf.
     */
    @Query("""
           SELECT b FROM PosStockBatch b
            WHERE b.pidBranch   = :branchId
              AND b.qtyPcs      > 0
              AND b.expiryDate IS NOT NULL
              AND b.expiryDate <= :cutoff
              AND (:filterProducts = FALSE
                   OR b.pidProduct IN :productIds
                   OR (:searchBatch IS NOT NULL AND LOWER(b.batchNo) LIKE :searchBatch))
              AND (:batchNo IS NULL OR LOWER(b.batchNo) LIKE :batchNo)
            ORDER BY b.expiryDate ASC, b.pidProduct ASC
           """)
    Page<PosStockBatch> findExpiringBatches(@Param("branchId") Long branchId,
                                            @Param("cutoff") LocalDate cutoff,
                                            @Param("filterProducts") boolean filterProducts,
                                            @Param("productIds") Collection<Long> productIds,
                                            @Param("searchBatch") String searchBatch,
                                            @Param("batchNo") String batchNo,
                                            Pageable pageable);

    /* ── Rollups ──────────────────────────────────────────────────────────── */

    /**
     * Per-product stock rollup for one branch, aggregated in SQL.
     *
     * <p>Columns: productId, stockPcs, stockValue, batchCount, earliestExpiry,
     * pcsPerBox, pcsPerStrip. Returned as a projection rather than entities so
     * the ledger is one query instead of one per product.
     *
     * <p>The packing comes from the lots rather than from current master data,
     * so "8 box" on a report describes the stock actually held even after
     * someone edits the product's pack size.
     */
    @Query("""
           SELECT b.pidProduct,
                  COALESCE(SUM(b.qtyPcs), 0),
                  COALESCE(SUM(b.qtyPcs * COALESCE(b.costPerPcs, 0)), 0),
                  COUNT(b),
                  MIN(b.expiryDate),
                  MAX(b.pcsPerBox),
                  MAX(b.pcsPerStrip)
             FROM PosStockBatch b
            WHERE b.pidBranch = :branchId
              AND b.qtyPcs    > 0
            GROUP BY b.pidProduct
           """)
    List<Object[]> stockRollupByProduct(@Param("branchId") Long branchId);

    /**
     * Whole-branch dashboard totals in a single round trip: total value, total
     * pieces, distinct products holding stock, lots expiring within the window,
     * lots already expired.
     *
     * <p>Native because {@code FILTER (WHERE ...)} has no JPQL equivalent, and
     * the alternative is the four separate table scans this replaces.
     */
    @Query(value = """
           SELECT COALESCE(SUM(b.QTY_PCS * COALESCE(b.COST_PER_PCS, 0)), 0)          AS total_value,
                  COALESCE(SUM(b.QTY_PCS), 0)                                        AS total_pcs,
                  COUNT(DISTINCT b.PID_PRODUCT)                                      AS products_in_stock,
                  COUNT(*) FILTER (WHERE b.EXPIRY_DATE IS NOT NULL
                                     AND b.EXPIRY_DATE >= :today
                                     AND b.EXPIRY_DATE <= :cutoff)                   AS near_expiry,
                  COUNT(*) FILTER (WHERE b.EXPIRY_DATE IS NOT NULL
                                     AND b.EXPIRY_DATE <  :today)                    AS expired
             FROM PHARMA.POS_STOCK_BATCH b
            WHERE b.PID_BRANCH = :branchId
              AND b.QTY_PCS    > 0
           """, nativeQuery = true)
    Object[] dashboardTotals(@Param("branchId") Long branchId,
                             @Param("today") LocalDate today,
                             @Param("cutoff") LocalDate cutoff);

    /**
     * Lots with stock on the shelf that have not sold for a while: money tied
     * up, and the next expiry write-off if nothing changes.
     *
     * <p>"Sold" is the last SALE movement of the product in this branch, not of
     * the lot — a newer lot of a product that is still selling is not slow, it
     * is queued behind the older one under FEFO. A product that has never sold
     * here is idle from the day its lot was received, so a lot that arrived
     * yesterday is not slow-moving; one that arrived four months ago is.
     *
     * <p>Columns: stockId, productId, productName, sku, company, batchNo,
     * expiryDate, qtyPcs, costPerPcs, value, lastSold, receivedDate, pcsPerBox,
     * pcsPerStrip.
     */
    @Query(value = """
           SELECT b.PID_STOCK, b.PID_PRODUCT, p.PRODUCT_NAME, p.SKU, co.COMPANY_NAME, b.BATCH_NO, b.EXPIRY_DATE,
                  b.QTY_PCS, b.COST_PER_PCS, b.QTY_PCS * COALESCE(b.COST_PER_PCS, 0) AS value,
                  ls.last_sold, b.RECEIVED_DATE, b.PCS_PER_BOX, b.PCS_PER_STRIP
             FROM PHARMA.POS_STOCK_BATCH b
             JOIN PHARMA.POS_PRODUCT_INFO p ON p.PID_PRODUCT = b.PID_PRODUCT
             LEFT JOIN PHARMA.POS_COMPANY_INFO co ON co.PID_COMPANY = p.PID_COMPANY
             LEFT JOIN (SELECT m.PID_PRODUCT, MAX(m.MOVEMENT_DATE) AS last_sold
                          FROM PHARMA.POS_STOCK_MOVEMENT m
                         WHERE m.PID_BRANCH = :branchId AND m.MOVEMENT_TYPE = 'SALE'
                         GROUP BY m.PID_PRODUCT) ls ON ls.PID_PRODUCT = b.PID_PRODUCT
            WHERE b.PID_ORG    = :orgId
              AND b.PID_BRANCH = :branchId
              AND b.STATUS     = 'Y'
              AND b.QTY_PCS    > 0
              AND COALESCE(ls.last_sold, b.RECEIVED_DATE, b.INSERTED_DATE::date) < :cutoff
              AND (CAST(:companyId AS bigint) IS NULL OR p.PID_COMPANY = :companyId)
              AND (CAST(:search AS text) IS NULL OR LOWER(p.PRODUCT_NAME) LIKE :search OR LOWER(b.BATCH_NO) LIKE :search)
            ORDER BY value DESC, ls.last_sold NULLS FIRST
           """, nativeQuery = true)
    List<Object[]> findSlowMoving(@Param("orgId") Long orgId,
                                  @Param("branchId") Long branchId,
                                  @Param("cutoff") LocalDate cutoff,
                                  @Param("companyId") Long companyId,
                                  @Param("search") String search);

    /** Products in this branch whose sellable stock has fallen to or below their alert level. */
    @Query(value = """
           SELECT p.PID_PRODUCT, p.PRODUCT_NAME, p.IMG_COLOR, p.ALERT_QTY,
                  COALESCE(s.stock_pcs, 0) AS stock_pcs,
                  COALESCE(s.stock_value, 0) AS stock_value,
                  s.pcs_per_box, s.pcs_per_strip
             FROM PHARMA.POS_PRODUCT_INFO p
             LEFT JOIN (
                   SELECT b.PID_PRODUCT,
                          SUM(b.QTY_PCS)                                AS stock_pcs,
                          SUM(b.QTY_PCS * COALESCE(b.COST_PER_PCS, 0))  AS stock_value,
                          MAX(b.PCS_PER_BOX)                            AS pcs_per_box,
                          MAX(b.PCS_PER_STRIP)                          AS pcs_per_strip
                     FROM PHARMA.POS_STOCK_BATCH b
                    WHERE b.PID_BRANCH = :branchId
                      AND b.STATUS     = 'Y'
                      AND (b.EXPIRY_DATE IS NULL OR b.EXPIRY_DATE >= :today)
                    GROUP BY b.PID_PRODUCT
             ) s ON s.PID_PRODUCT = p.PID_PRODUCT
            WHERE p.PID_ORG = :orgId
              -- Same rule as the out-of-stock alert beside this one: a product
              -- carrying a branch belongs to that pharmacy, one carrying none is
              -- the shared catalogue.
              AND (p.PID_BRANCH IS NULL OR p.PID_BRANCH = :branchId)
              AND p.STATUS  = 'Y'
              AND p.ALERT_QTY IS NOT NULL
              AND COALESCE(s.stock_pcs, 0) > 0
              AND COALESCE(s.stock_pcs, 0) <= p.ALERT_QTY
              AND (CAST(:companyId AS bigint) IS NULL OR p.PID_COMPANY = :companyId)
              AND (CAST(:search AS text) IS NULL
                   OR LOWER(p.PRODUCT_NAME) LIKE :search
                   OR LOWER(p.SKU) LIKE :search)
            ORDER BY COALESCE(s.stock_pcs, 0) ASC
           """, nativeQuery = true)
    List<Object[]> findLowStock(@Param("orgId") Long orgId,
                                @Param("branchId") Long branchId,
                                @Param("today") LocalDate today,
                                @Param("companyId") Long companyId,
                                @Param("search") String search);

    /**
     * Products with no sellable stock in this branch.
     *
     * <p>A product whose only lots have expired counts as out of stock, which
     * is why the join carries the same expiry predicate as the alert above.
     */
    @Query(value = """
           SELECT p.PID_PRODUCT, p.PRODUCT_NAME, p.IMG_COLOR, p.ALERT_QTY
             FROM PHARMA.POS_PRODUCT_INFO p
            WHERE p.PID_ORG = :orgId
              -- A product carrying a branch is that pharmacy's alone; one carrying
              -- none is the shared catalogue. Without this every pharmacy's own
              -- products showed up here as out of stock in every other pharmacy.
              AND (p.PID_BRANCH IS NULL OR p.PID_BRANCH = :branchId)
              AND p.STATUS  = 'Y'
              AND NOT EXISTS (
                    SELECT 1 FROM PHARMA.POS_STOCK_BATCH b
                     WHERE b.PID_PRODUCT = p.PID_PRODUCT
                       AND b.PID_BRANCH  = :branchId
                       AND b.STATUS      = 'Y'
                       AND b.QTY_PCS     > 0
                       AND (b.EXPIRY_DATE IS NULL OR b.EXPIRY_DATE >= :today)
              )
              AND (CAST(:companyId AS bigint) IS NULL OR p.PID_COMPANY = :companyId)
              AND (CAST(:search AS text) IS NULL
                   OR LOWER(p.PRODUCT_NAME) LIKE :search
                   OR LOWER(p.SKU) LIKE :search)
            ORDER BY p.PRODUCT_NAME
           """, nativeQuery = true)
    List<Object[]> findOutOfStock(@Param("orgId") Long orgId,
                                  @Param("branchId") Long branchId,
                                  @Param("today") LocalDate today,
                                  @Param("companyId") Long companyId,
                                  @Param("search") String search);

    /**
     * Companies that actually hold stock in this branch, for the filter bar.
     *
     * <p>Only companies with stock — offering a manufacturer whose every lot is
     * exhausted just produces an empty report and a puzzled user.
     */
    @Query(value = """
           SELECT DISTINCT c.PID_COMPANY, c.COMPANY_NAME
             FROM PHARMA.POS_STOCK_BATCH b
             JOIN PHARMA.POS_PRODUCT_INFO p ON p.PID_PRODUCT = b.PID_PRODUCT
             JOIN PHARMA.POS_COMPANY_INFO c ON c.PID_COMPANY = p.PID_COMPANY
            WHERE b.PID_BRANCH = :branchId
              AND b.QTY_PCS    > 0
            ORDER BY c.COMPANY_NAME
           """, nativeQuery = true)
    List<Object[]> findCompaniesWithStock(@Param("branchId") Long branchId);

    /** Distinct batch references held in this branch, newest receipt first. */
    @Query(value = """
           SELECT DISTINCT b.BATCH_NO
             FROM PHARMA.POS_STOCK_BATCH b
            WHERE b.PID_BRANCH = :branchId
              AND b.QTY_PCS    > 0
            ORDER BY b.BATCH_NO
           """, nativeQuery = true)
    List<String> findBatchNumbersWithStock(@Param("branchId") Long branchId);
}
