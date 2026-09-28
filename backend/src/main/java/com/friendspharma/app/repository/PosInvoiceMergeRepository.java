package com.friendspharma.app.repository;

import com.friendspharma.app.entity.PosInvoiceMerge;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface PosInvoiceMergeRepository extends JpaRepository<PosInvoiceMerge, Long> {

    Optional<PosInvoiceMerge> findByMergeNo(String mergeNo);

    Page<PosInvoiceMerge> findByPidOrgAndStatusOrderByMergeDateDesc(
            Long orgId, String status, Pageable pageable);

    Page<PosInvoiceMerge> findByPidOrgAndPidCustomerAndStatusOrderByMergeDateDesc(
            Long orgId, Long customerId, String status, Pageable pageable);

    Page<PosInvoiceMerge> findByPidOrgAndMergeDateBetweenOrderByMergeDateDesc(
            Long orgId, LocalDate from, LocalDate to, Pageable pageable);

    @Query("SELECT MAX(m.mergeNo) FROM PosInvoiceMerge m WHERE m.pidOrg = :orgId")
    String findLastMergeNo(@Param("orgId") Long orgId);
}
