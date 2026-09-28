package com.friendspharma.app.scheduler;

import com.friendspharma.app.repository.PosCartRepository;
import com.friendspharma.app.service.interfaces.NotificationService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Scheduled jobs that replace the Oracle stored procedure
 * PROC_AUTO_CLOSE_NOT_SUBMITTED and related batch jobs.
 *
 * Schedules (all configurable via application properties):
 *   cart-cleanup         – every 30 min  – cancel OPEN/HELD carts idle > N hours
 *   low-stock-alerts     – every 6 hours – push low-stock notifications
 *   expiry-alerts        – daily 08:00   – push expiry notifications
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CartCleanupScheduler {

    private final PosCartRepository    cartRepo;
    private final NotificationService  notificationService;

    @PersistenceContext
    private EntityManager em;

    /** Hours of inactivity before a cart is auto-cancelled. Default 24h. */
    @Value("${app.scheduler.cart-idle-hours:24}")
    private int cartIdleHours;

    /** Org ID to use for broadcast notifications. Default 1. */
    @Value("${app.scheduler.default-org-id:1}")
    private Long defaultOrgId;

    // ══════════════════════════════════════════════════════════════════════
    // 1.  Cart cleanup  — every 30 minutes
    //     Replaces: PROC_AUTO_CLOSE_NOT_SUBMITTED
    // ══════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRateString = "${app.scheduler.cart-cleanup-ms:1800000}")
    @Transactional
    public void cancelStaleCarts() {
        LocalDateTime threshold = LocalDateTime.now().minusHours(cartIdleHours);
        var staleCarts = cartRepo.findStaleCarts(threshold);
        if (staleCarts.isEmpty()) return;

        staleCarts.forEach(cart -> {
            cart.setStage("CANCELLED");
            cartRepo.save(cart);
        });
        log.info("[CartCleanup] Auto-cancelled {} stale carts (idle > {}h)",
                staleCarts.size(), cartIdleHours);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 2.  Low-stock alerts — every 6 hours
    // ══════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRateString = "${app.scheduler.low-stock-check-ms:21600000}")
    @Transactional
    public void pushLowStockAlerts() {
        // Per branch, from the batches. POS_PRODUCT_INFO.STOCK_QTY is a
        // denormalised total across every pharmacy holding the product, so a
        // shelf that is short in one shop either never triggered or triggered
        // for all of them. Stock is per branch and so is the alert.
        String sql = """
            SELECT b.PID_ORG, b.PID_BRANCH, p.PID_PRODUCT, p.PRODUCT_NAME,
                   SUM(b.QTY_PCS) AS on_hand
              FROM PHARMA.POS_STOCK_BATCH b
              JOIN PHARMA.POS_PRODUCT_INFO p ON p.PID_PRODUCT = b.PID_PRODUCT
             WHERE COALESCE(p.STATUS, 'Y') = 'Y'
               AND p.ALERT_QTY IS NOT NULL
               AND COALESCE(b.STATUS, 'Y') = 'Y'
               AND (b.EXPIRY_DATE IS NULL OR b.EXPIRY_DATE >= CURRENT_DATE)
             GROUP BY b.PID_ORG, b.PID_BRANCH, p.PID_PRODUCT, p.PRODUCT_NAME, p.ALERT_QTY
            HAVING SUM(b.QTY_PCS) <= p.ALERT_QTY
            """;

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(sql).getResultList();
        if (rows.isEmpty()) return;

        for (Object[] r : rows) {
            Long orgId      = ((Number) r[0]).longValue();
            Long branchId   = ((Number) r[1]).longValue();
            Long productId  = ((Number) r[2]).longValue();
            String name     = r[3] != null ? r[3].toString() : "Unknown";
            double stock    = r[4] != null ? ((Number) r[4]).doubleValue() : 0;
            notificationService.pushLowStockAlert(orgId, branchId, productId, name, stock);
        }
        log.info("[LowStockAlerts] Pushed {} low-stock alerts", rows.size());
    }

    // ══════════════════════════════════════════════════════════════════════
    // 3.  Expiry alerts — daily at 08:00
    // ══════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "${app.scheduler.expiry-alert-cron:0 0 8 * * *}")
    @Transactional
    public void pushExpiryAlerts() {
        // Rewritten. The query this replaced read POS_TRANSACTION_MST.PID_TRANSACTION
        // and TRAN_TYPE — neither column exists in this database — so it had thrown
        // every morning since it was written and no expiry alert was ever raised.
        //
        // An expiry belongs to a batch, along with the branch holding it and the
        // quantity still on the shelf. Reading it from purchase lines also counted
        // stock that had already been sold.
        String sql = """
            SELECT b.PID_ORG, b.PID_BRANCH, p.PID_PRODUCT, p.PRODUCT_NAME,
                   MIN(b.EXPIRY_DATE - CURRENT_DATE) AS days_left
              FROM PHARMA.POS_STOCK_BATCH b
              JOIN PHARMA.POS_PRODUCT_INFO p ON p.PID_PRODUCT = b.PID_PRODUCT
             WHERE b.QTY_PCS > 0
               AND COALESCE(b.STATUS, 'Y') = 'Y'
               AND b.EXPIRY_DATE BETWEEN CURRENT_DATE AND CURRENT_DATE + 90
             GROUP BY b.PID_ORG, b.PID_BRANCH, p.PID_PRODUCT, p.PRODUCT_NAME
            """;

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(sql).getResultList();
        if (rows.isEmpty()) return;

        for (Object[] r : rows) {
            Long orgId      = ((Number) r[0]).longValue();
            Long branchId   = ((Number) r[1]).longValue();
            Long productId  = ((Number) r[2]).longValue();
            String name     = r[3] != null ? r[3].toString() : "Unknown";
            long daysLeft   = r[4] != null ? ((Number) r[4]).longValue() : 0;
            notificationService.pushExpiryAlert(orgId, branchId, productId, name, daysLeft);
        }
        log.info("[ExpiryAlerts] Pushed {} expiry alerts", rows.size());
    }
}
