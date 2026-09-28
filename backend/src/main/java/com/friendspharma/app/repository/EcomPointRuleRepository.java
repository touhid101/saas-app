package com.friendspharma.app.repository;

import com.friendspharma.app.entity.EcomPointRule;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface EcomPointRuleRepository extends JpaRepository<EcomPointRule, Long> {

    /**
     * The rules that could apply at this branch right now, most specific first.
     *
     * <p>A rule a pharmacy wrote for itself beats one the platform wrote for
     * everybody, and a rule for one customer type beats one for all of them —
     * so the first row is the one that applies, and the ordering is the
     * precedence rather than a preference.
     */
    @Query("""
           SELECT r FROM EcomPointRule r
            WHERE r.isActive = 'Y' AND r.status = 'Y'
              AND (r.startsAt IS NULL OR r.startsAt <= :now)
              AND (r.endsAt IS NULL OR r.endsAt >= :now)
              AND (r.customerTypeScope = 'ALL' OR r.customerTypeScope = :tier)
              AND (r.branchScope = 'ALL_BRANCHES'
                   OR EXISTS (SELECT 1 FROM EcomPointRuleBranch b
                               WHERE b.pidPointRule = r.pidPointRule AND b.pidBranch = :branchId))
            ORDER BY CASE WHEN r.branchScope = 'SELECTED_BRANCHES' THEN 0 ELSE 1 END,
                     CASE WHEN r.customerTypeScope = 'ALL' THEN 1 ELSE 0 END,
                     r.pidPointRule DESC
           """)
    List<EcomPointRule> applicable(@Param("branchId") Long branchId, @Param("tier") String tier,
                                   @Param("now") LocalDateTime now);

    @Query("""
           SELECT r FROM EcomPointRule r
            WHERE (:active = '' OR r.isActive = :active)
              AND (:q = '' OR LOWER(r.ruleName) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY r.isActive DESC, r.pidPointRule DESC
           """)
    Page<EcomPointRule> search(@Param("active") String active, @Param("q") String q, Pageable pageable);
}
