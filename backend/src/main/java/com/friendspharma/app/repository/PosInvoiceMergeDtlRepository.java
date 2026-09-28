package com.friendspharma.app.repository;

import com.friendspharma.app.entity.PosInvoiceMergeDtl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PosInvoiceMergeDtlRepository extends JpaRepository<PosInvoiceMergeDtl, Long> {

    List<PosInvoiceMergeDtl> findByMerge_PidMerge(Long pidMerge);

    /** Check if a transaction is already part of an ACTIVE merge */
    @Query("SELECT COUNT(d) > 0 FROM PosInvoiceMergeDtl d " +
           "WHERE d.pidTransaction = :tranId AND d.merge.status = 'ACTIVE'")
    boolean isTransactionAlreadyMerged(@Param("tranId") Long tranId);
}
