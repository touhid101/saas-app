package com.friendspharma.app.repository;

import com.friendspharma.app.entity.SubPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** The plans on sale. */
@Repository
public interface SubPlanRepository extends JpaRepository<SubPlan, Long> {

    List<SubPlan> findByIsActiveOrderBySortOrder(Integer isActive);

    Optional<SubPlan> findByPlanCodeIgnoreCase(String planCode);

}