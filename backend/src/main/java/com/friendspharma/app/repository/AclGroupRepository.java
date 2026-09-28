package com.friendspharma.app.repository;

import com.friendspharma.app.entity.AclGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AclGroupRepository extends JpaRepository<AclGroup, Long> {
    List<AclGroup> findByPidOrgOrderByGroupName(Long orgId);
    List<AclGroup> findAllByOrderByGroupCode();
    Optional<AclGroup> findByGroupCode(String groupCode);
    Optional<AclGroup> findByGroupName(String groupName);
    
    /**
     * Get only role codes without loading full entities into session
     */
    @Query("SELECT g.groupCode FROM AclGroup g")
    List<String> findAllRoleCodes();
}
