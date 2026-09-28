package com.friendspharma.app.repository;

import com.friendspharma.app.entity.HrSalaryComponent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Components are normally reached through their structure, which cascades.
 *
 * <p>This exists for the one read that does not go that way: the payroll
 * calculator loading many structures at once, where walking each structure's
 * collection would be one query per employee.
 */
@Repository
public interface HrSalaryComponentRepository extends JpaRepository<HrSalaryComponent, Long> {

    List<HrSalaryComponent> findByStructure_PidStructureAndStatusOrderBySortOrder(
            Long pidStructure, String status);

    List<HrSalaryComponent> findByStructure_PidStructureInAndStatus(
            List<Long> structureIds, String status);

    /**
     * Every live component at a branch, through its structure.
     *
     * <p>Read by the provident fund screen so it can point at deduction lines
     * that look like PF and are not marked as such. That is a prompt for a
     * person, never an inference — see {@code HrPfServiceImpl}.
     */
    @Query("""
           SELECT c FROM HrSalaryComponent c
            WHERE c.structure.pidBranch = :branchId
              AND c.status = 'Y'
              AND c.structure.status = 'Y'
            ORDER BY c.sortOrder
           """)
    List<HrSalaryComponent> findByBranch(@Param("branchId") Long branchId);
}
