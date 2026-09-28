package com.friendspharma.app.repository;

import com.friendspharma.app.entity.HrPfScheme;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** The provident fund scheme, per branch, effective-dated. */
@Repository
public interface HrPfSchemeRepository extends JpaRepository<HrPfScheme, Long> {

    /**
     * The scheme in force at a branch now.
     *
     * <p>Matches uq_hr_pf_scheme_current: one open-ended live scheme per branch.
     * Superseded schemes keep their EFFECTIVE_TO and stay, because last month's
     * payroll was computed against one of them.
     */
    @Query("""
           SELECT s FROM HrPfScheme s
            WHERE s.pidBranch = :branchId
              AND s.status = 'Y'
              AND s.effectiveTo IS NULL
           """)
    Optional<HrPfScheme> findCurrent(@Param("branchId") Long branchId);

    /**
     * The scheme that governed a particular date.
     *
     * <p>A list rather than an Optional for the same reason the working policy
     * returns one: effective-dated rows can overlap on a database that predates
     * the unique index, and taking the newest start is a defined answer where
     * getSingleResult would be an exception nobody can act on.
     */
    @Query("""
           SELECT s FROM HrPfScheme s
            WHERE s.pidBranch = :branchId
              AND s.status = 'Y'
              AND s.effectiveFrom <= :on
              AND (s.effectiveTo IS NULL OR s.effectiveTo >= :on)
            ORDER BY s.effectiveFrom DESC
           """)
    List<HrPfScheme> findOn(@Param("branchId") Long branchId, @Param("on") LocalDate on);

    List<HrPfScheme> findByPidBranchOrderByEffectiveFromDesc(Long pidBranch);

    Optional<HrPfScheme> findByPidPfSchemeAndPidBranch(Long pidPfScheme, Long pidBranch);
}
