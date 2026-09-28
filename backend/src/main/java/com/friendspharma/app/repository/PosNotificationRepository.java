package com.friendspharma.app.repository;

import com.friendspharma.app.entity.PosNotification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PosNotificationRepository extends JpaRepository<PosNotification, Long> {

    /** Unread notifications for a user (targeted + broadcast) */
    @Query("SELECT n FROM PosNotification n WHERE n.pidOrg = :orgId " +
           "AND (n.pidUser = :userId OR n.pidUser IS NULL) " +
           "AND n.isRead = 0 ORDER BY n.insertedDate DESC")
    List<PosNotification> findUnreadByUser(@Param("orgId") Long orgId,
                                            @Param("userId") Long userId);

    @Query("SELECT COUNT(n) FROM PosNotification n WHERE n.pidOrg = :orgId " +
           "AND (n.pidUser = :userId OR n.pidUser IS NULL) AND n.isRead = 0")
    long countUnread(@Param("orgId") Long orgId, @Param("userId") Long userId);

    Page<PosNotification> findByPidOrgAndPidUserOrderByInsertedDateDesc(
            Long orgId, Long userId, Pageable pageable);

    @Query("SELECT n FROM PosNotification n WHERE n.pidOrg = :orgId " +
           "AND (n.pidUser = :userId OR n.pidUser IS NULL) " +
           "ORDER BY n.insertedDate DESC")
    Page<PosNotification> findAllForUser(@Param("orgId") Long orgId,
                                          @Param("userId") Long userId,
                                          Pageable pageable);

    @Modifying
    @Query("UPDATE PosNotification n SET n.isRead = 1 " +
           "WHERE n.pidOrg = :orgId AND (n.pidUser = :userId OR n.pidUser IS NULL)")
    void markAllReadForUser(@Param("orgId") Long orgId, @Param("userId") Long userId);

    @Modifying
    @Query("UPDATE PosNotification n SET n.isRead = 1 WHERE n.pidNotification = :id")
    void markOneRead(@Param("id") Long id);
}
