package com.friendspharma.app.repository;

import com.friendspharma.app.entity.PosDeliveryCollection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface PosDeliveryCollectionRepository extends JpaRepository<PosDeliveryCollection, Long> {

    List<PosDeliveryCollection> findByPidTransactionOrderByCollectionDateDesc(Long transactionId);

    Page<PosDeliveryCollection> findByPidOrgAndPidBranchAndCollectionDateBetween(
            Long orgId, Long branchId, LocalDate from, LocalDate to, Pageable pageable);

    Page<PosDeliveryCollection> findByPidDeliveryManAndCollectionDateBetween(
            Long deliveryManId, LocalDate from, LocalDate to, Pageable pageable);

    @Query("SELECT SUM(c.collectedAmount) FROM PosDeliveryCollection c " +
           "WHERE c.pidTransaction = :tranId AND c.status = 'COLLECTED'")
    BigDecimal sumCollectedByTransaction(@Param("tranId") Long tranId);

    @Query("SELECT SUM(c.collectedAmount) FROM PosDeliveryCollection c " +
           "WHERE c.pidOrg = :orgId AND c.pidBranch = :branchId " +
           "AND c.collectionDate = :date AND c.status = 'COLLECTED'")
    BigDecimal sumCollectedByBranchAndDate(@Param("orgId") Long orgId,
                                           @Param("branchId") Long branchId,
                                           @Param("date") LocalDate date);

    @Query("SELECT SUM(c.collectedAmount) FROM PosDeliveryCollection c " +
           "WHERE c.pidDeliveryMan = :dmId AND c.collectionDate = :date AND c.status = 'COLLECTED'")
    BigDecimal sumCollectedByDeliveryManAndDate(@Param("dmId") Long dmId,
                                                @Param("date") LocalDate date);
}
