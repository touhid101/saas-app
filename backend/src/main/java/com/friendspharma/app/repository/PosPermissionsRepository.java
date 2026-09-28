package com.friendspharma.app.repository;

import com.friendspharma.app.entity.PosPermissions;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PosPermissionsRepository extends JpaRepository<PosPermissions, Long> {

    @Query("SELECT p FROM PosPermissions p " +
           "JOIN FETCH p.role " +
           "JOIN FETCH p.module " +
           "WHERE p.role.pidGroup = :roleId")
    List<PosPermissions> findByRole_PidGroup(@Param("roleId") Long roleId);

    Optional<PosPermissions> findByRole_PidGroupAndModule_PidModule(Long roleId, Long moduleId);

    @Modifying
    @Query("DELETE FROM PosPermissions p WHERE p.role.pidGroup = :roleId")
    void deleteByRoleId(@Param("roleId") Long roleId);

    @Modifying
    @Query("DELETE FROM PosPermissions p WHERE p.module.pidModule = :moduleId")
    void deleteByModuleId(@Param("moduleId") Long moduleId);
    
    /**
     * Count permissions assigned to a specific module
     */
    long countByModule_PidModule(Long pidModule);
}
