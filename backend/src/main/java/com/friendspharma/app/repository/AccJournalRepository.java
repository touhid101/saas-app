package com.friendspharma.app.repository;

import com.friendspharma.app.entity.AccJournal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AccJournalRepository extends JpaRepository<AccJournal, Long> {
    Optional<AccJournal> findByPidJournalAndPidOrg(Long pidJournal, Long pidOrg);
    @Query("select j from AccJournal j where j.pidOrg = :org and (:branch is null or j.pidBranch = :branch) and (:status is null or j.journalStatus = :status) and (:type is null or j.journalType = :type) and j.journalDate between :from and :to and (:q = '' or lower(j.journalNo) like :q or lower(coalesce(j.reference,'')) like :q or lower(coalesce(j.description,'')) like :q) order by j.journalDate desc, j.pidJournal desc")
    Page<AccJournal> search(@Param("org") Long pidOrg, @Param("branch") Long branch, @Param("status") String status, @Param("type") String type, @Param("from") LocalDate from, @Param("to") LocalDate to, @Param("q") String q, Pageable pageable);
    long countByPidPeriodAndJournalStatus(Long pidPeriod, String journalStatus);
    List<AccJournal> findBySourceModuleAndSourceTypeAndSourceIdAndJournalStatus(String sourceModule, String sourceType, Long sourceId, String journalStatus);
}
