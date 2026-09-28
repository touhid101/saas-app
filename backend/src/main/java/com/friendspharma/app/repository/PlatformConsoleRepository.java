package com.friendspharma.app.repository;

import com.friendspharma.app.entity.SubSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * The only queries in the application that deliberately cross the tenant boundary.
 *
 * <p><b>Why a separate repository.</b> Every other query in this codebase is
 * scoped to one pharmacy, and Phase 1 spent a week making sure of it. The Super
 * Admin console genuinely needs to see across tenants — but the way to give it
 * that is a small, named set of queries written for the purpose, guarded by a
 * role check on one controller. Loosening the filter on an existing endpoint so
 * it can serve both would put the "may I see everything" decision inside thirty
 * ordinary queries, and one of them would get it wrong.
 *
 * <p>Nothing here returns a document, a customer or a line of anybody's trade.
 * It returns counts and totals about pharmacies — which is what running a
 * platform requires and where the legitimate interest stops.
 */
@Repository
public interface PlatformConsoleRepository extends JpaRepository<SubSubscription, Long> {

    /**
     * Recurring revenue, normalised to a month.
     *
     * <p>A yearly plan divided by twelve rather than counted at face value: a
     * platform with one yearly customer does not earn that in January.
     */
    @Query(value = """
           SELECT COALESCE(SUM(CASE WHEN p.BILLING_TERM = 'YEARLY'
                                    THEN p.PRICE / 12 ELSE p.PRICE END), 0)
             FROM PHARMA.SUB_SUBSCRIPTION s
             JOIN PHARMA.SUB_PLAN p ON p.PID_PLAN = s.PID_PLAN
            WHERE s.SUB_STATUS = 'ACTIVE'
           """, nativeQuery = true)
    java.math.BigDecimal monthlyRecurringRevenue();

    /** Pharmacies by lifecycle status, for the strip along the top. */
    @Query(value = """
           SELECT b.TENANT_STATUS, COUNT(*)
             FROM PHARMA.ALL_BRANCH b
            GROUP BY b.TENANT_STATUS
           """, nativeQuery = true)
    List<Object[]> tenantsByStatus();

    /** What has been collected, month by month, for the last year. */
    @Query(value = """
           SELECT TO_CHAR(pay.PAID_ON, 'YYYY-MM') AS month,
                  SUM(pay.AMOUNT)                 AS collected,
                  COUNT(*)                        AS payments
             FROM PHARMA.SUB_PAYMENT pay
            WHERE pay.PAID_ON > CURRENT_DATE - 400
            GROUP BY 1
            ORDER BY 1
           """, nativeQuery = true)
    List<Object[]> collectionsByMonth();

    /** Everything unpaid, oldest first — the list somebody chases from. */
    @Query(value = """
           SELECT i.PID_INVOICE, i.INVOICE_NO, b.PID_BRANCH, b.BRANCH_NAME,
                  i.AMOUNT, i.DUE_ON, CURRENT_DATE - i.DUE_ON AS days_overdue
             FROM PHARMA.SUB_INVOICE i
             JOIN PHARMA.ALL_BRANCH b ON b.PID_BRANCH = i.PID_BRANCH
            WHERE i.PAY_STATUS = 'PENDING'
            ORDER BY i.DUE_ON
           """, nativeQuery = true)
    List<Object[]> outstandingInvoices();

    /** Subscriptions ending soon, so somebody can call before they lapse. */
    @Query(value = """
           SELECT b.PID_BRANCH, b.BRANCH_NAME, p.PLAN_NAME, s.EXPIRES_ON,
                  s.EXPIRES_ON - CURRENT_DATE AS days_left, s.GRACE_DAYS
             FROM PHARMA.SUB_SUBSCRIPTION s
             JOIN PHARMA.ALL_BRANCH b ON b.PID_BRANCH = s.PID_BRANCH
             JOIN PHARMA.SUB_PLAN   p ON p.PID_PLAN   = s.PID_PLAN
            WHERE s.SUB_STATUS = 'ACTIVE'
              AND s.EXPIRES_ON <= CURRENT_DATE + :withinDays
            ORDER BY s.EXPIRES_ON
           """, nativeQuery = true)
    List<Object[]> expiringWithin(@Param("withinDays") int withinDays);

    /**
     * How much each pharmacy is actually trading.
     *
     * <p>The number that matters most on this screen, and the one a billing report
     * does not contain: a pharmacy paying its subscription and ringing up nothing
     * has already stopped using the product and has not got round to cancelling.
     * That is a phone call this week, not a surprise next quarter.
     *
     * <p>Counts and totals only — no invoice, no customer, no line of trade.
     */
    @Query(value = """
           SELECT b.PID_BRANCH,
                  b.BRANCH_NAME,
                  COUNT(t.PID_TRAN_MST) FILTER (WHERE t.TRANSACTION_DATE > CURRENT_DATE - 30)  AS sales_30,
                  COUNT(t.PID_TRAN_MST) FILTER (WHERE t.TRANSACTION_DATE > CURRENT_DATE - 60
                                                  AND t.TRANSACTION_DATE <= CURRENT_DATE - 30) AS sales_prev_30,
                  MAX(t.TRANSACTION_DATE)                                                      AS last_sale,
                  (SELECT COUNT(*) FROM PHARMA.ACL_USER u
                    WHERE u.PID_BRANCH = b.PID_BRANCH AND COALESCE(u.STATUS, 'Y') = 'Y')       AS users
             FROM PHARMA.ALL_BRANCH b
             LEFT JOIN PHARMA.POS_TRANSACTION_MST t
                    ON t.PID_BRANCH = b.PID_BRANCH
                   AND t.TRANSACTION_TYPE = 'SL'
                   AND t.STAGE <> 'Submitted'
                   AND COALESCE(t.IS_MERGE_INVOICE, 'N') <> 'Y'
                   AND t.TRANSACTION_DATE > CURRENT_DATE - 60
            GROUP BY b.PID_BRANCH, b.BRANCH_NAME
            ORDER BY b.BRANCH_NAME
           """, nativeQuery = true)
    List<Object[]> tenantActivity();

    /** Everything that has happened to any subscription lately. */
    @Query(value = """
           SELECT a.PID_BRANCH, b.BRANCH_NAME, a.EVENT_TYPE, a.DETAIL,
                  a.OLD_VALUE, a.NEW_VALUE, a.ACTED_BY, a.ACTED_AT
             FROM PHARMA.SUB_AUDIT a
             LEFT JOIN PHARMA.ALL_BRANCH b ON b.PID_BRANCH = a.PID_BRANCH
            ORDER BY a.ACTED_AT DESC
            LIMIT 100
           """, nativeQuery = true)
    List<Object[]> recentAudit();

    /** New pharmacies by month, for the growth line. */
    @Query(value = """
           SELECT TO_CHAR(s.STARTS_ON, 'YYYY-MM') AS month, COUNT(*)
             FROM PHARMA.SUB_SUBSCRIPTION s
            WHERE s.STARTS_ON > CURRENT_DATE - 400
            GROUP BY 1
            ORDER BY 1
           """, nativeQuery = true)
    List<Object[]> signupsByMonth();

    @Query(value = "SELECT COUNT(*) FROM PHARMA.TENANT_REGISTRATION WHERE REG_STATUS = 'PENDING'",
           nativeQuery = true)
    long pendingApplications();
}
