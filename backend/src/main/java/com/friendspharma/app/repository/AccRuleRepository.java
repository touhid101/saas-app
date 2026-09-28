package com.friendspharma.app.repository;

import com.friendspharma.app.entity.AccRule;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AccRuleRepository extends JpaRepository<AccRule, Long> {
    @Query("select r from AccRule r where r.eventType = :type and (r.pidOrg = :org or r.pidOrg is null) and r.isActive = 1 order by r.pidOrg nulls last")
    List<AccRule> findEffective(@Param("org") Long pidOrg, @Param("type") String eventType);
    @Query("select r from AccRule r where r.pidOrg = :org or r.pidOrg is null order by r.eventType, r.pidOrg nulls last")
    List<AccRule> findAllEffective(@Param("org") Long pidOrg);
}
