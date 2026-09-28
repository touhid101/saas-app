package com.friendspharma.app.repository;

import com.friendspharma.app.entity.AccEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AccEventRepository extends JpaRepository<AccEvent, Long> {
    Optional<AccEvent> findByPidOrgAndSourceModuleAndSourceTypeAndSourceIdAndEventKey(Long pidOrg, String sourceModule, String sourceType, Long sourceId, String eventKey);
    List<AccEvent> findBySourceModuleAndSourceTypeAndSourceIdOrderByPidEventAsc(String sourceModule, String sourceType, Long sourceId);
    @Query("select e from AccEvent e where e.pidOrg = :org and (:branch is null or e.pidBranch = :branch) and (:status is null or e.eventStatus = :status) and (:module is null or e.sourceModule = :module) and e.eventDate between :from and :to order by e.pidEvent desc")
    Page<AccEvent> monitor(@Param("org") Long pidOrg, @Param("branch") Long branch, @Param("status") String status, @Param("module") String module, @Param("from") LocalDate from, @Param("to") LocalDate to, Pageable pageable);
    long countByPidOrgAndEventStatus(Long pidOrg, String eventStatus);
    @Query("select count(e) from AccEvent e where e.sourceModule = :module and e.sourceType = :type and e.pidOrg = :org and e.eventStatus = 'POSTED'")
    long countPosted(@Param("org") Long pidOrg, @Param("module") String module, @Param("type") String type);
}
