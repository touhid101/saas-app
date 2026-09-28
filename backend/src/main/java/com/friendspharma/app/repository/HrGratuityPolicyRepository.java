package com.friendspharma.app.repository;

import com.friendspharma.app.entity.HrGratuityPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** The gratuity formula, per branch, effective-dated. */
@Repository
public interface HrGratuityPolicyRepository extends JpaRepository<HrGratuityPolicy, Long> {

    /** Matches uq_hr_gratuity_policy_current: one open-ended live policy per branch. */
    @Query("""
           SELECT p FROM HrGratuityPolicy p
            WHERE p.pidBranch = :branchId
              AND p.status = 'Y'
              AND p.effectiveTo IS NULL
           """)
    Optional<HrGratuityPolicy> findCurrent(@Param("branchId") Long branchId);

    /** The policy that governed a particular date — an award is made under one. */
    @Query("""
           SELECT p FROM HrGratuityPolicy p
            WHERE p.pidBranch = :branchId
              AND p.status = 'Y'
              AND p.effectiveFrom <= :on
              AND (p.effectiveTo IS NULL OR p.effectiveTo >= :on)
            ORDER BY p.effectiveFrom DESC
           """)
    List<HrGratuityPolicy> findOn(@Param("branchId") Long branchId, @Param("on") LocalDate on);

    List<HrGratuityPolicy> findByPidBranchOrderByEffectiveFromDesc(Long pidBranch);

    Optional<HrGratuityPolicy> findByPidGratuityPolicyAndPidBranch(Long id, Long pidBranch);
}
