package com.friendspharma.app.repository;

import com.friendspharma.app.entity.HrWorkingPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The working policy, per branch, effective-dated.
 */
@Repository
public interface HrWorkingPolicyRepository extends JpaRepository<HrWorkingPolicy, Long> {

    /**
     * The policy in force at a branch now.
     *
     * <p>Matches {@code uq_hr_policy_current}: one open-ended live policy per
     * branch. Superseded policies keep their {@code EFFECTIVE_TO} and stay,
     * because last month's attendance was judged against one of them.
     */
    @Query("""
           SELECT p FROM HrWorkingPolicy p
            WHERE p.pidBranch = :branchId
              AND p.status = 'Y'
              AND p.effectiveTo IS NULL
           """)
    Optional<HrWorkingPolicy> findCurrent(@Param("branchId") Long branchId);

    /**
     * The policy that governed a particular date.
     *
     * <p>A list rather than an Optional because effective-dated rows can in
     * principle overlap on a database that predates the unique index; taking
     * the newest start is a defined answer where {@code getSingleResult} would
     * be an exception nobody can act on.
     */
    @Query("""
           SELECT p FROM HrWorkingPolicy p
            WHERE p.pidBranch = :branchId
              AND p.status = 'Y'
              AND p.effectiveFrom <= :on
              AND (p.effectiveTo IS NULL OR p.effectiveTo >= :on)
            ORDER BY p.effectiveFrom DESC
           """)
    List<HrWorkingPolicy> findOn(@Param("branchId") Long branchId, @Param("on") LocalDate on);

    List<HrWorkingPolicy> findByPidBranchOrderByEffectiveFromDesc(Long pidBranch);

    Optional<HrWorkingPolicy> findByPidPolicyAndPidBranch(Long pidPolicy, Long pidBranch);
}
