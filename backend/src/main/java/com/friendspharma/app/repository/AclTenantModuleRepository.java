package com.friendspharma.app.repository;

import com.friendspharma.app.entity.AclTenantModule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** What each pharmacy's plan includes. */
@Repository
public interface AclTenantModuleRepository extends JpaRepository<AclTenantModule, Long> {

    List<AclTenantModule> findByPidBranch(Long pidBranch);

    /** Replacing a branch's grant wholesale is how a template is applied. */
    @Modifying
    @Query("DELETE FROM AclTenantModule t WHERE t.pidBranch = :branchId")
    void deleteByBranch(@Param("branchId") Long branchId);

}