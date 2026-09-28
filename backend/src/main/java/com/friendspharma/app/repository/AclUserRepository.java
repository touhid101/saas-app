package com.friendspharma.app.repository;

import com.friendspharma.app.entity.AclUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface AclUserRepository extends JpaRepository<AclUser, Long>,
        JpaSpecificationExecutor<AclUser> {

    Optional<AclUser> findByUserNameIgnoreCase(String userName);

    boolean existsByUserNameIgnoreCase(String userName);

    /** New-service alias */
    default Optional<AclUser> findByUsername(String username) {
        return findByUserNameIgnoreCase(username);
    }

    /** New-service alias */
    default boolean existsByUsername(String username) {
        return existsByUserNameIgnoreCase(username);
    }

    @Query("SELECT u FROM AclUser u WHERE u.pidOrg = :orgId ORDER BY u.fullName")
    java.util.List<AclUser> findByPidOrg(@Param("orgId") Long orgId);

    /**
     * The people who work at one pharmacy.
     *
     * <p>Listing by organisation showed every branch manager all eleven accounts
     * on the estate — including the platform administrators, whose usernames are
     * the first thing worth knowing if you intend to guess a password.
     */
    @Query("SELECT u FROM AclUser u WHERE u.pidOrg = :orgId AND u.pidBranch = :branchId ORDER BY u.fullName")
    java.util.List<AclUser> findByPidOrgAndPidBranch(@Param("orgId") Long orgId,
                                                     @Param("branchId") Long branchId);

    @Modifying
    @Query("UPDATE AclUser u SET u.lastLogin = :now WHERE u.pidUser = :id")
    void updateLastLogin(@Param("id") Long id, @Param("now") LocalDateTime now);
    
    /**
     * Count users assigned to a specific role
     */
    long countByPidRole(Long pidRole);

    /** At most one login per employee — the partial unique index enforces it too. */
    Optional<AclUser> findByPidEmployee(Long pidEmployee);
}
