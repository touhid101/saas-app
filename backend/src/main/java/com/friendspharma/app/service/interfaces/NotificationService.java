package com.friendspharma.app.service.interfaces;

import com.friendspharma.app.entity.PosNotification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface NotificationService {

    /** Create a targeted notification for one user */
    PosNotification send(Long orgId, Long userId, String type, String title,
                         String message, String severity, String actionUrl,
                         Long refId, String refType);

    /** Broadcast notification to all users in org */
    PosNotification broadcast(Long orgId, String type, String title,
                               String message, String severity, String actionUrl);

    List<PosNotification> getUnread(Long orgId, Long userId);
    long countUnread(Long orgId, Long userId);
    Page<PosNotification> getAll(Long orgId, Long userId, Pageable pageable);
    void markRead(Long notificationId);
    void markAllRead(Long orgId, Long userId);
    void delete(Long notificationId);

    /** Called by schedulers and services to push system notifications */
    void pushLowStockAlert(Long orgId, Long productId, String productName, double currentStock);

    /** The same, for one pharmacy. The nightly sweep has no session to read a branch from. */
    void pushLowStockAlert(Long orgId, Long branchId, Long productId, String productName, double currentStock);
    void pushExpiryAlert(Long orgId, Long productId, String productName, long daysToExpiry);

    /** The same, for one pharmacy. */
    void pushExpiryAlert(Long orgId, Long branchId, Long productId, String productName, long daysToExpiry);
    void pushPendingApprovalAlert(Long orgId, Long requisitionId, String requisitionNo);
}
