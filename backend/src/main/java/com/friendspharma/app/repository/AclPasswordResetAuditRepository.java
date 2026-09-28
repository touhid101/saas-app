package com.friendspharma.app.repository;

import com.friendspharma.app.entity.AclPasswordResetAudit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** [V71] The password-reset trail. Read-only in practice: nothing updates a row here. */
public interface AclPasswordResetAuditRepository extends JpaRepository<AclPasswordResetAudit, Long> {

    /**
     * The trail, newest first, optionally narrowed to one account.
     *
     * <p>Deliberately not filtered by organisation. Only a platform operator can
     * reach this, and the point of the trail is that it covers every pharmacy on
     * the installation — scoping it to the caller would hide the resets a Super
     * Admin performed on somebody else.
     */
    @Query("""
           SELECT a FROM AclPasswordResetAudit a
            WHERE (:pidUser IS NULL OR a.pidUser = :pidUser)
            ORDER BY a.resetAt DESC, a.pidAudit DESC
           """)
    Page<AclPasswordResetAudit> trail(@Param("pidUser") Long pidUser, Pageable page);

    /** The most recent entries for a set of users, to show "last reset" on the list. */
    @Query("""
           SELECT a FROM AclPasswordResetAudit a
            WHERE a.pidUser IN :ids AND a.outcome = 'RESET'
            ORDER BY a.resetAt DESC
           """)
    List<AclPasswordResetAudit> recentResetsFor(@Param("ids") List<Long> ids);
}
