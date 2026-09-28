package com.friendspharma.app.repository;

import com.friendspharma.app.entity.EcomPointLedger;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;

public interface EcomPointLedgerRepository extends JpaRepository<EcomPointLedger, Long> {

    /** The balance, from the ledger itself — the figure the cache is checked against. */
    @Query("SELECT COALESCE(SUM(l.points), 0) FROM EcomPointLedger l WHERE l.pidEcomCustomer = :customerId")
    BigDecimal balanceOf(@Param("customerId") Long customerId);

    Page<EcomPointLedger> findByPidEcomCustomerOrderByPidPointEntryDesc(Long customerId, Pageable pageable);

    Optional<EcomPointLedger> findByPidEcomOrderAndEntryType(Long pidEcomOrder, String entryType);

    boolean existsByPidEcomOrderAndEntryType(Long pidEcomOrder, String entryType);
}
