package com.friendspharma.app.repository;

import com.friendspharma.app.entity.AccProfitPolicy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AccProfitPolicyRepository extends JpaRepository<AccProfitPolicy, Long> {
    List<AccProfitPolicy> findByPidOrgOrderByEffectiveFromDesc(Long pidOrg);
    @Query("select p from AccProfitPolicy p where p.pidOrg = :org and p.isActive = 1 and p.effectiveFrom <= :d and (p.effectiveTo is null or p.effectiveTo >= :d) and (p.pidBranch = :branch or p.pidBranch is null) order by p.pidBranch nulls last, p.effectiveFrom desc")
    List<AccProfitPolicy> findEffective(@Param("org") Long pidOrg, @Param("branch") Long branch, @Param("d") LocalDate d);
}
