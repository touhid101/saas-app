package com.friendspharma.app.service.impl;

import com.friendspharma.app.entity.PosNotification;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.PosNotificationRepository;
import com.friendspharma.app.security.SecurityUtils;
import com.friendspharma.app.service.interfaces.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationServiceImpl implements NotificationService {

    private final PosNotificationRepository repo;
    private final com.friendspharma.app.repository.AclUserRepository userRepo;

    @Override
    @Transactional
    public PosNotification send(Long orgId, Long userId, String type, String title,
            String message, String severity, String actionUrl, Long refId, String refType) {
        return repo.save(PosNotification.builder()
                .pidOrg(orgId).pidBranch(branchOf(userId)).pidUser(userId).notifType(type)
                .title(title).message(message).severity(severity)
                .actionUrl(actionUrl).refId(refId).refType(refType).isRead(0).build());
    }

    @Override
    @Transactional
    public PosNotification broadcast(Long orgId, String type, String title,
            String message, String severity, String actionUrl) {
        return broadcastTo(orgId, SecurityUtils.getCurrentBranchId(), type,
                title, message, severity, actionUrl);
    }

    /**
     * A notice for one pharmacy.
     *
     * <p>Separate from {@link #broadcast} because the branch cannot always come
     * off the session: the nightly alerts run with nobody signed in, and a null
     * branch there used to reach the database and fail on the constraint.
     */
    @Transactional
    public PosNotification broadcastTo(Long orgId, Long branchId, String type, String title,
                                       String message, String severity, String actionUrl) {
        if (branchId == null) {
            log.warn("Dropping a {} notice with no branch: '{}'. A notice belongs to a pharmacy.",
                     type, title);
            return null;
        }
        return repo.save(PosNotification.builder()
                .pidOrg(orgId).pidBranch(branchId).pidUser(null).notifType(type)
                .title(title).message(message).severity(severity)
                .actionUrl(actionUrl).isRead(0).build());
    }

    /** The branch a notice for one person belongs to: theirs. */
    private Long branchOf(Long userId) {
        if (userId == null) return SecurityUtils.getCurrentBranchId();
        return userRepo.findById(userId).map(u -> u.getPidBranch())
                .orElseGet(SecurityUtils::getCurrentBranchId);
    }

    @Override
    public List<PosNotification> getUnread(Long orgId, Long userId) {
        return repo.findUnreadByUser(orgId, userId);
    }

    @Override
    public long countUnread(Long orgId, Long userId) {
        return repo.countUnread(orgId, userId);
    }

    @Override
    public Page<PosNotification> getAll(Long orgId, Long userId, Pageable pageable) {
        return repo.findAllForUser(orgId, userId, pageable);
    }

    @Override
    @Transactional
    public void markRead(Long notificationId) {
        repo.markOneRead(notificationId);
    }

    @Override
    @Transactional
    public void markAllRead(Long orgId, Long userId) {
        repo.markAllReadForUser(orgId, userId);
    }

    @Override
    @Transactional
    public void delete(Long notificationId) {
        if (!repo.existsById(notificationId))
            throw new ResourceNotFoundException("Notification", notificationId);
        repo.deleteById(notificationId);
    }

    @Override
    @Transactional
    public void pushLowStockAlert(Long orgId, Long productId, String productName, double currentStock) {
        pushLowStockAlert(orgId, null, productId, productName, currentStock);
    }

    /**
     * Low stock, for one pharmacy.
     *
     * <p>The branch is not optional in the database and should never have been
     * optional here: a shelf is short in one shop, not in all of them.
     */
    @Override
    @Transactional
    public void pushLowStockAlert(Long orgId, Long branchId, Long productId,
                                  String productName, double currentStock) {
        broadcastTo(orgId, branchId, "LOW_STOCK",
                "Low Stock Alert: " + productName,
                productName + " has only " + (int) currentStock + " units remaining.",
                "WARNING", "/inventory");
    }

    @Override
    @Transactional
    public void pushExpiryAlert(Long orgId, Long productId, String productName, long daysToExpiry) {
        pushExpiryAlert(orgId, null, productId, productName, daysToExpiry);
    }

    /** Short-dated stock, for one pharmacy. */
    @Override
    @Transactional
    public void pushExpiryAlert(Long orgId, Long branchId, Long productId,
                                String productName, long daysToExpiry) {
        broadcastTo(orgId, branchId, "EXPIRY_ALERT",
                "Expiry Alert: " + productName,
                productName + " expires in " + daysToExpiry + " days.",
                daysToExpiry <= 30 ? "ERROR" : "WARNING", "/inventory");
    }

    @Override
    @Transactional
    public void pushPendingApprovalAlert(Long orgId, Long requisitionId, String requisitionNo) {
        broadcast(orgId, "PENDING_APPROVAL",
                "Requisition Pending Approval",
                "Requisition " + requisitionNo + " is awaiting approval.",
                "INFO", "/requisitions/" + requisitionId);
    }
}
