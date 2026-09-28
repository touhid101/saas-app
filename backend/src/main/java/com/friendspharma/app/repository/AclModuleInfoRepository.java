package com.friendspharma.app.repository;

import com.friendspharma.app.entity.AclModuleInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AclModuleInfoRepository extends JpaRepository<AclModuleInfo, Long> {

    Optional<AclModuleInfo> findByModuleCode(String moduleCode);

    List<AclModuleInfo> findByIsActiveOrderBySortOrderAsc(Integer isActive);

    List<AclModuleInfo> findByPidParentModuleIsNullAndIsActiveOrderBySortOrderAsc(Integer isActive);

    List<AclModuleInfo> findByPidParentModuleAndIsActiveOrderBySortOrderAsc(
            Long parentId, Integer isActive);

    /** All modules accessible by a role (canView=1) */
    @Query("SELECT DISTINCT p.module FROM PosPermissions p " +
           "WHERE p.role.pidGroup = :roleId AND p.canView = 1 " +
           "ORDER BY p.module.sortOrder ASC")
    List<AclModuleInfo> findAccessibleModulesByRole(@Param("roleId") Long roleId);
    
    /**
     * Get only module codes without loading full entities into session
     */
    @Query("SELECT m.moduleCode FROM AclModuleInfo m")
    List<String> findAllModuleCodes();
}
