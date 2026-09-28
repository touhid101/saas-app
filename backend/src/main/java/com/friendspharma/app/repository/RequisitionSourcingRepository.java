package com.friendspharma.app.repository;

import com.friendspharma.app.entity.PosRequisitionDtl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * Who can supply what.
 *
 * <p>Both queries answer the same question from different evidence, and are run
 * once for the whole requisition rather than once per line — a forty-line
 * requisition would otherwise be eighty round trips to draw one screen.
 */
@Repository
public interface RequisitionSourcingRepository extends JpaRepository<PosRequisitionDtl, Long> {

    /**
     * The supplier each product was last actually bought from, and at what rate.
     *
     * <p>The strongest evidence available without a supplier–product catalogue:
     * a purchase that was received is proof that this supplier sells this thing
     * at a price you accepted. Drafts are excluded by the view.
     */
    @Query(value = """
           SELECT v.PID_PRODUCT,
                  v.PID_SUPPLIER,
                  v.SUPPLIER_NAME,
                  v.RATE_PER_PCS,
                  v.LAST_PURCHASED_ON
             FROM PHARMA.V_PRODUCT_LAST_PURCHASE v
            WHERE v.PID_PRODUCT IN (:productIds)
              AND (CAST(:orgId AS bigint) IS NULL OR v.PID_ORG = :orgId)
           """, nativeQuery = true)
    List<Object[]> lastPurchaseFor(@Param("productIds") Collection<Long> productIds,
                                   @Param("orgId") Long orgId);

    /**
     * Suppliers that list each product's manufacturer.
     *
     * <p>Weaker evidence, and used only when nothing has been bought before: it
     * says the supplier represents the maker, not that they stock the line. A
     * product matched this way is worth the buyer glancing at, which is why the
     * basis travels with it to the screen.
     *
     * <p>More than one supplier can list the same maker. The lowest id wins so
     * the proposal is stable between refreshes — an arbitrary but repeatable
     * choice is better than one that moves while somebody is reading it.
     */
    @Query(value = """
           SELECT DISTINCT ON (p.PID_PRODUCT)
                  p.PID_PRODUCT,
                  s.PID_SUPPLIER,
                  s.SUPPLIER_NAME
             FROM PHARMA.POS_PRODUCT_INFO p
             JOIN PHARMA.POS_SUPPLIER_INFO s ON s.POS_PID_COMPANY = p.PID_COMPANY
            WHERE p.PID_PRODUCT IN (:productIds)
              AND s.STATUS = 'Y'
              AND (CAST(:orgId AS bigint) IS NULL OR s.PID_ORG = :orgId)
            ORDER BY p.PID_PRODUCT, s.PID_SUPPLIER
           """, nativeQuery = true)
    List<Object[]> manufacturerSupplierFor(@Param("productIds") Collection<Long> productIds,
                                           @Param("orgId") Long orgId);

    /**
     * What a box will actually be billed at, and how many pieces are in it.
     *
     * <p>TP and IGTA both come from the product master because that is where
     * the purchase flow takes them from: a purchase line is priced
     * {@code qty x (TP + IGTA)}, and the request carries no price field to
     * override it with. Pricing the proposal any other way would show the buyer
     * one total and create another, which is the one thing a preview must not
     * do.
     *
     * <p>The pack size is two joins away down the Box to Leaf ladder; a product
     * missing either is counted as one piece per box rather than dropped from
     * the proposal.
     */
    @Query(value = """
           SELECT p.PID_PRODUCT,
                  COALESCE(bx.NO_OF_LEAF * lf.NO_OF_PCS, 1) AS pcs_per_box,
                  COALESCE(p.TP_PRICE, 0)                   AS tp_price,
                  COALESCE(p.IGTA, 0)                       AS igta
             FROM PHARMA.POS_PRODUCT_INFO p
             LEFT JOIN PHARMA.POS_BOX_INFO  bx ON bx.PID_BOX  = p.PID_BOX
             LEFT JOIN PHARMA.POS_LEAF_INFO lf ON lf.PID_LEAF = bx.PID_LEAF
            WHERE p.PID_PRODUCT IN (:productIds)
           """, nativeQuery = true)
    List<Object[]> boxPricingFor(@Param("productIds") Collection<Long> productIds);

    /**
     * What the Inventory page and the Sales reports say <em>now</em> about the
     * products on a requisition, in one query.
     *
     * <p>Stock is the branch's live batch balance (the Current Stock figure);
     * sales are the last thirty days of SALE less SALES_RETURN movements in
     * the branch — the same ledger and the same window the Copilot's
     * "sells/month" uses, so the two never disagree. A requisition keeps the
     * figures it was raised on as history; the screen shows these beside them.
     *
     * <p>Columns: productId, stockPcs, pcs30.
     */
    @Query(value = """
           SELECT p.PID_PRODUCT,
                  COALESCE((SELECT SUM(b.QTY_PCS) FROM PHARMA.POS_STOCK_BATCH b
                             WHERE b.PID_PRODUCT = p.PID_PRODUCT AND b.PID_BRANCH = :branchId
                               AND COALESCE(b.STATUS, 'Y') = 'Y' AND b.QTY_PCS > 0), 0)          AS stock_pcs,
                  COALESCE((SELECT SUM(CASE WHEN m.DIRECTION = 'O' THEN m.QTY_PCS ELSE -m.QTY_PCS END)
                              FROM PHARMA.POS_STOCK_MOVEMENT m
                             WHERE m.PID_PRODUCT = p.PID_PRODUCT AND m.PID_BRANCH = :branchId
                               AND m.MOVEMENT_TYPE IN ('SALE', 'SALES_RETURN')
                               AND m.MOVEMENT_DATE > CURRENT_DATE - 30), 0)                     AS pcs_30
             FROM PHARMA.POS_PRODUCT_INFO p
            WHERE p.PID_PRODUCT IN (:productIds)
           """, nativeQuery = true)
    List<Object[]> liveStockAndSales(@Param("branchId") Long branchId,
                                     @Param("productIds") Collection<Long> productIds);
}
