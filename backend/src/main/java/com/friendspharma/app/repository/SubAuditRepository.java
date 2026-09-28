package com.friendspharma.app.repository;

import com.friendspharma.app.entity.SubAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** The subscription trail. Append-only. */
@Repository
public interface SubAuditRepository extends JpaRepository<SubAudit, Long> {

    List<SubAudit> findTop100ByPidBranchOrderByActedAtDesc(Long pidBranch);

    List<SubAudit> findTop200ByOrderByActedAtDesc();

}