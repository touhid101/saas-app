package com.friendspharma.app.repository;

import com.friendspharma.app.entity.EcomOrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EcomOrderItemRepository extends JpaRepository<EcomOrderItem, Long> {
    List<EcomOrderItem> findByPidEcomOrderOrderByPidOrderItem(Long pidEcomOrder);

    /**
     * Units actually sold online, per product per pharmacy, since a moment.
     *
     * <p>Cancelled and returned orders are excluded: a sale that came back is
     * not a sale, and counting it would put a "sold" figure on the storefront
     * that the pharmacy's own books disagree with. The figure is in pieces,
     * because a box and a strip of the same medicine are the same shelf.
     *
     * <p>Returns rows of {branchId, productId, piecesSold}.
     */
    @Query("""
           SELECT o.pidBranch, i.pidProduct, SUM(COALESCE(i.qtyPcs, 0))
             FROM EcomOrderItem i, EcomOrder o
            WHERE i.pidEcomOrder = o.pidEcomOrder
              AND o.pidBranch IN :branches
              AND o.placedAt >= :since
              AND o.ecomStatus NOT IN ('CANCELLED', 'RETURNED')
            GROUP BY o.pidBranch, i.pidProduct
           """)
    List<Object[]> piecesSoldSince(@Param("branches") java.util.Collection<Long> branches,
                                   @Param("since") java.time.LocalDateTime since);

    /**
     * The most recent order lines at these pharmacies — what was bought and
     * when, and deliberately <b>not</b> by whom.
     *
     * <p>No customer id, name or address is selected. On a pharmacy a "someone
     * just bought this" feed is a list of what people in the area are being
     * treated for, so the buyer never leaves this query.
     *
     * <p>Returns rows of {branchId, productId, productName, qtyPcs, placedAt}.
     */
    @Query("""
           SELECT o.pidBranch, i.pidProduct, i.productNameSnapshot,
                  COALESCE(i.qtyPcs, 0), o.placedAt
             FROM EcomOrderItem i, EcomOrder o
            WHERE i.pidEcomOrder = o.pidEcomOrder
              AND o.pidBranch IN :branches
              AND o.placedAt >= :since
              AND o.ecomStatus NOT IN ('CANCELLED', 'RETURNED')
              AND COALESCE(i.prescriptionFlagSnapshot, 'N') <> 'Y'
            ORDER BY o.placedAt DESC
           """)
    List<Object[]> recentLines(@Param("branches") java.util.Collection<Long> branches,
                               @Param("since") java.time.LocalDateTime since,
                               org.springframework.data.domain.Pageable page);
}
