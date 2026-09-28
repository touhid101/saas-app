package com.friendspharma.app.repository;

import com.friendspharma.app.entity.PosTransactionDtl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PosTransactionDtlRepository extends JpaRepository<PosTransactionDtl, Long> {

    List<PosTransactionDtl> findByPidTranMstOrderByExpiryDate(Long pidTranMst);

    List<PosTransactionDtl> findByPidTranMst(Long pidTranMst);

    /**
     * [V5 Purchase] Lines for a whole page of invoices in one query, so the
     * history grid does not run a count per row.
     */
    List<PosTransactionDtl> findByPidTranMstIn(List<Long> pidTranMstIds);

    void deleteByPidTranMst(Long pidTranMst);

    @Query("SELECT d FROM PosTransactionDtl d WHERE d.pidProduct = :productId " +
           "AND d.expiryDate >= CURRENT_DATE ORDER BY d.expiryDate ASC")
    List<PosTransactionDtl> findActiveBatchesByProduct(@Param("productId") Long productId);

    /**
     * [V4 Product Master] Number of sales/purchase invoice lines referencing this
     * product. Drives the delete guard — a product with transaction history can
     * only be deactivated, never removed.
     */
    @Query("SELECT COUNT(d) FROM PosTransactionDtl d WHERE d.pidProduct = :productId")
    long countByProduct(@Param("productId") Long productId);
}
