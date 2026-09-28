package com.friendspharma.app.repository;

import com.friendspharma.app.entity.HrApprovalFlow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** Approval chains, per branch and module. */
@Repository
public interface HrApprovalFlowRepository extends JpaRepository<HrApprovalFlow, Long> {

    List<HrApprovalFlow> findByPidBranchAndStatusOrderByWorkflowCodeAscMinValueAsc(
            Long pidBranch, String status);

    Optional<HrApprovalFlow> findByPidFlowAndPidBranch(Long pidFlow, Long pidBranch);

    /**
     * The flows a value could route to, dearest threshold first.
     *
     * <p>The caller takes the first: the highest floor at or below the value, and
     * failing that the flow with no floor at all. Ordering NULLS LAST is what
     * makes "anything" the fallback rather than the winner.
     */
    @Query("""
           SELECT f FROM HrApprovalFlow f
            WHERE f.pidBranch = :branchId
              AND f.workflowCode = :workflowCode
              AND f.status = 'Y'
              AND (f.minValue IS NULL OR f.minValue <= :value)
            ORDER BY f.minValue DESC NULLS LAST
           """)
    List<HrApprovalFlow> findMatching(@Param("branchId") Long branchId,
                                      @Param("workflowCode") String workflowCode,
                                      @Param("value") BigDecimal value);

    boolean existsByPidBranchAndWorkflowCodeAndStatus(
            Long pidBranch, String workflowCode, String status);
}
