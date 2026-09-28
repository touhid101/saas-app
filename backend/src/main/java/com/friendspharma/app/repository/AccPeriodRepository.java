package com.friendspharma.app.repository;

import com.friendspharma.app.entity.AccPeriod;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AccPeriodRepository extends JpaRepository<AccPeriod, Long> {
    @Query("select p from AccPeriod p where p.pidOrg = :org and p.startDate <= :d and p.endDate >= :d")
    Optional<AccPeriod> findCovering(@Param("org") Long pidOrg, @Param("d") LocalDate d);
    List<AccPeriod> findByPidFiscalYearOrderByPeriodNoAsc(Long pidFiscalYear);
    List<AccPeriod> findByPidOrgOrderByStartDateDesc(Long pidOrg);
    @Query("select p from AccPeriod p where p.pidOrg = :org and p.startDate >= :from and p.endDate <= :to order by p.startDate")
    List<AccPeriod> findWithin(@Param("org") Long pidOrg, @Param("from") LocalDate from, @Param("to") LocalDate to);
}
