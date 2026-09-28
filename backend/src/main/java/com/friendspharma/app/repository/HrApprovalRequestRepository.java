package com.friendspharma.app.repository;

import com.friendspharma.app.entity.HrApprovalRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HrApprovalRequestRepository extends JpaRepository<HrApprovalRequest, Long> {

    Optional<HrApprovalRequest> findByPidApprovalAndPidBranch(Long id, Long pidBranch);

    /** The live chain for one object, if there is one. Matches uq_hr_approval_live. */
    @Query("""
           SELECT r FROM HrApprovalRequest r
            WHERE r.workflowCode = :workflowCode
              AND r.refId = :refId
              AND r.status = 'Y'
              AND r.requestStatus = 'PENDING'
           """)
    Optional<HrApprovalRequest> findLive(@Param("workflowCode") String workflowCode,
                                         @Param("refId") Long refId);

    /** Every chain an object has ever had, newest first — the trail on a screen. */
    List<HrApprovalRequest> findByWorkflowCodeAndRefIdAndStatusOrderByPidApprovalDesc(
            String workflowCode, Long refId, String status);

    /** Everything still waiting at this branch, whoever it is waiting on. */
    List<HrApprovalRequest> findByPidBranchAndRequestStatusAndStatusOrderByStartedAt(
            Long pidBranch, String requestStatus, String status);

    @Query("""
           SELECT r FROM HrApprovalRequest r
            WHERE r.pidBranch = :branchId
              AND r.status = 'Y'
              AND (:workflowCode = '' OR r.workflowCode = :workflowCode)
              AND (:reqStatus = '' OR r.requestStatus = :reqStatus)
            ORDER BY r.pidApproval DESC
           """)
    Page<HrApprovalRequest> search(@Param("branchId") Long branchId,
                                   @Param("workflowCode") String workflowCode,
                                   @Param("reqStatus") String reqStatus,
                                   Pageable pageable);

    long countByPidBranchAndRequestStatusAndStatus(
            Long pidBranch, String requestStatus, String status);
}
