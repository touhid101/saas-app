package com.friendspharma.app.repository;

import com.friendspharma.app.entity.AccBusinessDay;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AccBusinessDayRepository extends JpaRepository<AccBusinessDay, Long> {
    Optional<AccBusinessDay> findByPidBranchAndBusinessDate(Long pidBranch, LocalDate businessDate);
    Optional<AccBusinessDay> findByPidBusinessDayAndPidOrg(Long id, Long pidOrg);
    @Query("select d from AccBusinessDay d where d.pidBranch = :branch and d.businessDate < :date and d.dayStatus in ('CLOSED','LOCKED') order by d.businessDate desc")
    List<AccBusinessDay> closedBefore(@Param("branch") Long pidBranch, @Param("date") LocalDate date, Pageable pageable);
    @Query("select d from AccBusinessDay d where d.pidBranch = :branch and d.dayStatus in ('OPEN','SUBMITTED','REOPENED') order by d.businessDate desc")
    List<AccBusinessDay> openDays(@Param("branch") Long pidBranch);
    @Query("select d from AccBusinessDay d where d.pidBranch = :branch and d.businessDate between :from and :to order by d.businessDate desc")
    List<AccBusinessDay> between(@Param("branch") Long pidBranch, @Param("from") LocalDate from, @Param("to") LocalDate to);
    @Query("select d from AccBusinessDay d where d.pidOrg = :org and d.dayStatus in ('OPEN','SUBMITTED','REOPENED') and d.businessDate < :today")
    List<AccBusinessDay> unclosedBefore(@Param("org") Long pidOrg, @Param("today") LocalDate today);
    List<AccBusinessDay> findByPidOrgAndDayStatusOrderByBusinessDateDesc(Long pidOrg, String dayStatus);
}
