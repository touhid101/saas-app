package com.friendspharma.app.repository;

import com.friendspharma.app.entity.HrOvertimeRequest;
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
public interface HrOvertimeRequestRepository extends JpaRepository<HrOvertimeRequest, Long> {

    Optional<HrOvertimeRequest> findByPidOtRequestAndPidBranch(Long id, Long pidBranch);

    @Query("""
           SELECT r FROM HrOvertimeRequest r
            WHERE r.pidBranch = :branchId
              AND r.status = 'Y'
              AND (:employeeId IS NULL OR r.pidEmployee = :employeeId)
              AND (:reqStatus = '' OR r.requestStatus = :reqStatus)
              AND (:from IS NULL OR r.otDate >= :from)
              AND (:to   IS NULL OR r.otDate <= :to)
            ORDER BY r.otDate DESC, r.pidOtRequest DESC
           """)
    Page<HrOvertimeRequest> search(@Param("branchId") Long branchId,
                                   @Param("employeeId") Long employeeId,
                                   @Param("reqStatus") String reqStatus,
                                   @Param("from") LocalDate from,
                                   @Param("to") LocalDate to,
                                   Pageable pageable);

    /** The approval queue. */
    List<HrOvertimeRequest> findByPidBranchAndRequestStatusAndStatusOrderByOtDate(
            Long pidBranch, String requestStatus, String status);

    long countByPidBranchAndRequestStatusAndStatus(
            Long pidBranch, String requestStatus, String status);

    /**
     * A claim already live for this person on this day.
     *
     * <p>Matches {@code uq_hr_ot_live}: rejected and cancelled claims are
     * excluded, so a refused one can be filed again with a better reason.
     */
    @Query("""
           SELECT r FROM HrOvertimeRequest r
            WHERE r.pidEmployee = :employeeId
              AND r.otDate = :on
              AND r.status = 'Y'
              AND r.requestStatus IN ('DRAFT','SUBMITTED','APPROVED')
              AND (:excludeId IS NULL OR r.pidOtRequest <> :excludeId)
           """)
    List<HrOvertimeRequest> findLiveFor(@Param("employeeId") Long employeeId,
                                        @Param("on") LocalDate on,
                                        @Param("excludeId") Long excludeId);

    List<HrOvertimeRequest> findByPidEmployeeAndStatusOrderByOtDateDesc(
            Long pidEmployee, String status);
}
