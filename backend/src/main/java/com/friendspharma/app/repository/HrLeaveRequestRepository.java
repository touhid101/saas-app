package com.friendspharma.app.repository;

import com.friendspharma.app.entity.HrLeaveRequest;
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
public interface HrLeaveRequestRepository extends JpaRepository<HrLeaveRequest, Long> {

    Optional<HrLeaveRequest> findByPidLeaveRequestAndPidBranch(Long id, Long pidBranch);

    @Query("""
           SELECT r FROM HrLeaveRequest r
            WHERE r.pidBranch = :branchId
              AND r.status = 'Y'
              AND (:employeeId IS NULL OR r.pidEmployee = :employeeId)
              AND (:reqStatus = '' OR r.requestStatus = :reqStatus)
              AND (:from IS NULL OR r.toDate   >= :from)
              AND (:to   IS NULL OR r.fromDate <= :to)
            ORDER BY r.fromDate DESC, r.pidLeaveRequest DESC
           """)
    Page<HrLeaveRequest> search(@Param("branchId") Long branchId,
                                @Param("employeeId") Long employeeId,
                                @Param("reqStatus") String reqStatus,
                                @Param("from") LocalDate from,
                                @Param("to") LocalDate to,
                                Pageable pageable);

    /** The approval queue — the screen this module is opened for. */
    List<HrLeaveRequest> findByPidBranchAndRequestStatusAndStatusOrderByFromDate(
            Long pidBranch, String requestStatus, String status);

    long countByPidBranchAndRequestStatusAndStatus(Long pidBranch, String requestStatus, String status);

    /**
     * Live requests that overlap a date range for one employee.
     *
     * <p>Two absences on the same day is either a mistake or a double count in
     * payroll, so a request overlapping a live one is refused. Rejected and
     * cancelled requests are excluded: a refused request does not reserve a day.
     */
    @Query("""
           SELECT r FROM HrLeaveRequest r
            WHERE r.pidEmployee = :employeeId
              AND r.status = 'Y'
              AND r.requestStatus IN ('DRAFT','SUBMITTED','APPROVED')
              AND r.fromDate <= :to
              AND r.toDate   >= :from
              AND (:excludeId IS NULL OR r.pidLeaveRequest <> :excludeId)
           """)
    List<HrLeaveRequest> findOverlapping(@Param("employeeId") Long employeeId,
                                         @Param("from") LocalDate from,
                                         @Param("to") LocalDate to,
                                         @Param("excludeId") Long excludeId);

    /** Approved absence inside a payroll period, for the whole branch. */
    @Query("""
           SELECT r FROM HrLeaveRequest r
            WHERE r.pidBranch = :branchId
              AND r.status = 'Y'
              AND r.requestStatus = 'APPROVED'
              AND r.fromDate <= :to
              AND r.toDate   >= :from
           """)
    List<HrLeaveRequest> findApprovedInRange(@Param("branchId") Long branchId,
                                             @Param("from") LocalDate from,
                                             @Param("to") LocalDate to);
}
