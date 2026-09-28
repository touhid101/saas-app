package com.friendspharma.app.repository;

import com.friendspharma.app.entity.AccFiscalYear;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AccFiscalYearRepository extends JpaRepository<AccFiscalYear, Long> {
    List<AccFiscalYear> findByPidOrgOrderByStartDateDesc(Long pidOrg);
    @Query("select y from AccFiscalYear y where y.pidOrg = :org and y.startDate <= :d and y.endDate >= :d")
    Optional<AccFiscalYear> findCovering(@Param("org") Long pidOrg, @Param("d") LocalDate d);
}
