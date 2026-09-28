package com.friendspharma.app.repository;

import com.friendspharma.app.entity.HrSeparation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HrSeparationRepository extends JpaRepository<HrSeparation, Long> {

    Optional<HrSeparation> findByPidSeparationAndPidBranch(Long id, Long pidBranch);

    /**
     * A live separation for this employee, if one exists.
     *
     * <p>Matches {@code uq_hr_separation_live}: rejected and cancelled records
     * stay in the table — a resignation that was withdrawn is still part of the
     * history — but only one may be in progress at a time.
     */
    @Query("""
           SELECT s FROM HrSeparation s
            WHERE s.pidEmployee = :employeeId
              AND s.status = 'Y'
              AND s.separationStatus IN ('DRAFT','SUBMITTED','APPROVED')
           """)
    Optional<HrSeparation> findLiveFor(@Param("employeeId") Long employeeId);

    List<HrSeparation> findByPidEmployeeOrderByLastWorkingDateDesc(Long pidEmployee);

    @Query("""
           SELECT s FROM HrSeparation s
            WHERE s.pidBranch = :branchId
              AND s.status = 'Y'
              AND (:sepStatus = '' OR s.separationStatus = :sepStatus)
            ORDER BY s.lastWorkingDate DESC, s.pidSeparation DESC
           """)
    Page<HrSeparation> search(@Param("branchId") Long branchId,
                              @Param("sepStatus") String sepStatus,
                              Pageable pageable);

    long countByPidBranchAndSeparationStatusAndStatus(Long pidBranch, String separationStatus, String status);
}
