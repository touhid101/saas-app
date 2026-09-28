package com.friendspharma.app.repository;

import com.friendspharma.app.entity.AccProfitDistribution;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AccProfitDistributionRepository extends JpaRepository<AccProfitDistribution, Long> {
    Optional<AccProfitDistribution> findByPidDistributionAndPidOrg(Long id, Long pidOrg);
    List<AccProfitDistribution> findByPidOrgOrderByPeriodFromDescPidDistributionDesc(Long pidOrg);
    @Query("select d from AccProfitDistribution d where d.pidOrg = :org and d.distStatus <> 'CANCELLED' and d.periodFrom <= :to and d.periodTo >= :from and ((:branch is null and d.pidBranch is null) or d.pidBranch = :branch)")
    List<AccProfitDistribution> overlapping(@Param("org") Long pidOrg, @Param("branch") Long branch, @Param("from") LocalDate from, @Param("to") LocalDate to);
}
