package com.friendspharma.app.repository;

import com.friendspharma.app.entity.EcomOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EcomOrderRepository extends JpaRepository<EcomOrder, Long> {

    /** Ownership is part of the key: another customer's order number is a 404, not a 403. */
    Optional<EcomOrder> findByOrderNoAndPidEcomCustomer(String orderNo, Long pidEcomCustomer);

    Page<EcomOrder> findByPidEcomCustomerOrderByPlacedAtDesc(Long pidEcomCustomer, Pageable pageable);

    /** The board loads by branch as well as number: another pharmacy's order is a 404. */
    Optional<EcomOrder> findByOrderNoAndPidBranch(String orderNo, Long pidBranch);

    /** By number alone — callers that have already established who may see it. */
    Optional<EcomOrder> findByOrderNo(String orderNo);

    Optional<EcomOrder> findByPidTranMst(Long pidTranMst);

    /** One page of Sales Activity rows at a time — see { EcomSaleReferences}. */
    List<EcomOrder> findByPidTranMstIn(Collection<Long> pidTranMstIds);

    boolean existsByPidEcomCustomer(Long pidEcomCustomer);

    /**
     * The branch's order board.
     *
     * <p>Scoped by branch, never by org alone: a multi-branch company still
     * works one pharmacy at a time, because the order names the shelf the goods
     * come off (memory rule, and docs/ecommerce 12 §4).
     *
     * <p>{@code status} takes a single status or the word {@code OPEN}, which
     * is every order still needing work — what the board shows when nobody has
     * chosen a chip, so a day's work is not buried under months of delivered
     * ones.
     *
     * <p>Strings use '' for "no filter": PostgreSQL cannot infer the type of a
     * null parameter inside LOWER() and types it as bytea.
     */
    @Query("""
           SELECT o FROM EcomOrder o
            WHERE o.pidBranch = :branchId
              AND (:status = '' OR (:status = 'OPEN' AND o.ecomStatus NOT IN ('DELIVERED', 'CANCELLED', 'RETURNED'))
                                OR o.ecomStatus = :status)
              AND (CAST(:from AS java.time.LocalDateTime) IS NULL OR o.placedAt >= :from)
              AND (CAST(:to   AS java.time.LocalDateTime) IS NULL OR o.placedAt <= :to)
              AND (:q = ''
                   OR LOWER(o.orderNo)              LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(o.customerNameSnapshot) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(o.phoneSnapshot)        LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY o.placedAt DESC
           """)
    Page<EcomOrder> searchForBranch(@Param("branchId") Long branchId,
                                    @Param("status") String status,
                                    @Param("q") String q,
                                    @Param("from") java.time.LocalDateTime from,
                                    @Param("to") java.time.LocalDateTime to,
                                    Pageable pageable);

    @Query("SELECT o.ecomStatus, COUNT(o) FROM EcomOrder o WHERE o.pidBranch = :branchId GROUP BY o.ecomStatus")
    List<Object[]> countByStatusForBranch(@Param("branchId") Long branchId);
}
