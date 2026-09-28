package com.friendspharma.app.repository;

import com.friendspharma.app.entity.AclUserPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Per-person exceptions. */
@Repository
public interface AclUserPermissionRepository extends JpaRepository<AclUserPermission, Long> {

    List<AclUserPermission> findByPidUser(Long pidUser);

    Optional<AclUserPermission> findByPidUserAndPidModule(Long pidUser, Long pidModule);

    @Modifying
    @Query("DELETE FROM AclUserPermission p WHERE p.pidUser = :userId")
    void deleteByUser(@Param("userId") Long userId);

}