package com.friendspharma.app.repository;

import com.friendspharma.app.entity.AccInvestorTxn;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AccInvestorTxnRepository extends JpaRepository<AccInvestorTxn, Long> {
    List<AccInvestorTxn> findByPidInvestorOrderByTxnDateAscPidInvestorTxnAsc(Long pidInvestor);
    Optional<AccInvestorTxn> findByPidInvestorTxnAndPidOrg(Long id, Long pidOrg);
    @Query("select t from AccInvestorTxn t where t.pidOrg = :org and (:investor is null or t.pidInvestor = :investor) and (:status is null or t.txnStatus = :status) order by t.txnDate desc, t.pidInvestorTxn desc")
    Page<AccInvestorTxn> search(@Param("org") Long pidOrg, @Param("investor") Long investor, @Param("status") String status, Pageable pageable);
    List<AccInvestorTxn> findByPidAllocationAndTxnStatus(Long pidAllocation, String txnStatus);
}
