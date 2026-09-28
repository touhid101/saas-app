package com.friendspharma.app.repository;

import com.friendspharma.app.entity.HrGratuityAward;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface HrGratuityAwardRepository extends JpaRepository<HrGratuityAward, Long> {

    Optional<HrGratuityAward> findByPidGratuityAwardAndPidBranch(Long id, Long pidBranch);

    @Query("""
           SELECT a FROM HrGratuityAward a
            WHERE a.pidBranch = :branchId
              AND a.status = 'Y'
              AND (:employeeId IS NULL OR a.pidEmployee = :employeeId)
              AND (:awardStatus = '' OR a.awardStatus = :awardStatus)
            ORDER BY a.serviceTo DESC, a.pidGratuityAward DESC
           """)
    Page<HrGratuityAward> search(@Param("branchId") Long branchId,
                                 @Param("employeeId") Long employeeId,
                                 @Param("awardStatus") String awardStatus,
                                 Pageable pageable);

    /** The approval queue. */
    List<HrGratuityAward> findByPidBranchAndAwardStatusAndStatusOrderByServiceTo(
            Long pidBranch, String awardStatus, String status);

    long countByPidBranchAndAwardStatusAndStatus(Long pidBranch, String awardStatus, String status);

    List<HrGratuityAward> findByPidEmployeeAndStatusOrderByServiceToDesc(
            Long pidEmployee, String status);

    /** What is shown beside a separation — the award, if somebody made one. */
    List<HrGratuityAward> findByPidSeparationAndStatus(Long pidSeparation, String status);

    /**
     * Guards uq_hr_gratuity_award_live with a sentence rather than a constraint
     * name. Rejected and cancelled awards are excluded, so a refused one can be
     * redone against corrected figures.
     */
    @Query("""
           SELECT a FROM HrGratuityAward a
            WHERE a.pidEmployee = :employeeId
              AND a.serviceTo = :serviceTo
              AND a.status = 'Y'
              AND a.awardStatus IN ('DRAFT','SUBMITTED','APPROVED','PAID')
              AND (:excludeId IS NULL OR a.pidGratuityAward <> :excludeId)
           """)
    List<HrGratuityAward> findLiveFor(@Param("employeeId") Long employeeId,
                                      @Param("serviceTo") LocalDate serviceTo,
                                      @Param("excludeId") Long excludeId);
}
