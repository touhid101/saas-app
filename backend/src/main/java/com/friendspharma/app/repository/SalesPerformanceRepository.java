package com.friendspharma.app.repository;

import com.friendspharma.app.entity.PosSalesTarget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Sales performance: what each person actually sold, and what they were asked to.
 *
 * <p>Attribution comes from {@code INSERTED_BY}, the username that raised the
 * invoice. {@code PID_EMPLOYEE} is the column that looks right and is not — it
 * is NULL on all 140,390 sales here and nothing has ever written it.
 *
 * <p>Every query is branch-scoped. A branch is a pharmacy, and one pharmacy's
 * takings are not another's business.
 */
@Repository
public interface SalesPerformanceRepository extends JpaRepository<PosSalesTarget, Long> {

    /**
     * What each person sold in a window.
     *
     * <p>The filters match the Sales Register exactly — confirmed invoices
     * onward, merge documents excluded — so this page and that report agree
     * about what a sale is. A merge invoice groups orders already counted here;
     * including it would report the same money twice.
     *
     * <p>LEFT JOIN to ACL_USER because not every value in INSERTED_BY is a
     * login: seeded and imported rows carry the name of whatever wrote them.
     * Those invoices are real money and are reported rather than dropped, which
     * is also what lets the totals here reconcile with the register.
     *
     * <p>Collected is capped at the amount billed per invoice, because cash
     * tendered above the total went back as change and was never revenue.
     *
     * @return username, pidUser, displayName, roleName, invoices, customers,
     *         billed, collected, discount, lastSale
     */
    @Query(value = """
           SELECT t.INSERTED_BY                                   AS person,
                  u.PID_USER,
                  COALESCE(u.FULL_NAME, t.INSERTED_BY)            AS display_name,
                  g.GROUP_NAME                                    AS role_name,
                  COUNT(*)                                        AS invoices,
                  COUNT(DISTINCT t.PID_SUP_CUST)                  AS customers,
                  COALESCE(SUM(t.AMOUNT_TOBE_PAID), 0)            AS billed,
                  COALESCE(SUM(LEAST(COALESCE(t.GIVEN_AMOUNT, 0),
                                     COALESCE(t.AMOUNT_TOBE_PAID, 0))), 0) AS collected,
                  COALESCE(SUM(COALESCE(t.TOTAL_DISCOUNT, 0)
                             + COALESCE(t.INVOICE_DIS_AMT, 0)), 0) AS discount,
                  MAX(t.TRANSACTION_DATE)                         AS last_sale
             FROM PHARMA.POS_TRANSACTION_MST t
             LEFT JOIN PHARMA.ACL_USER  u ON u.USER_NAME = t.INSERTED_BY
                                         AND u.PID_BRANCH = t.PID_BRANCH
             LEFT JOIN PHARMA.ACL_GROUP g ON g.PID_GROUP = u.PID_ROLE
            WHERE t.PID_ORG          = :orgId
              AND t.PID_BRANCH       = :branchId
              AND t.TRANSACTION_TYPE = 'SL'
              AND COALESCE(t.STATUS, 'Y') <> 'N'
              AND COALESCE(t.IS_MERGE_INVOICE, 'N') <> 'Y'
              AND t.STAGE           <> 'Submitted'
              AND t.TRANSACTION_DATE BETWEEN :from AND :to
              AND t.INSERTED_BY IS NOT NULL
            GROUP BY t.INSERTED_BY, u.PID_USER, u.FULL_NAME, g.GROUP_NAME
            ORDER BY billed DESC
           """, nativeQuery = true)
    List<Object[]> actualsByPerson(@Param("orgId") Long orgId,
                                   @Param("branchId") Long branchId,
                                   @Param("from") LocalDate from,
                                   @Param("to") LocalDate to);

    /**
     * The branch's monthly totals, for the trend behind the table.
     *
     * <p>Months with no sales are absent rather than zero; the service fills the
     * gaps, because a missing bar and a zero bar mean different things and only
     * the caller knows which months it asked about.
     */
    @Query(value = """
           SELECT date_trunc('month', t.TRANSACTION_DATE)::date  AS period,
                  COUNT(*)                                       AS invoices,
                  COALESCE(SUM(t.AMOUNT_TOBE_PAID), 0)           AS billed
             FROM PHARMA.POS_TRANSACTION_MST t
            WHERE t.PID_ORG          = :orgId
              AND t.PID_BRANCH       = :branchId
              AND t.TRANSACTION_TYPE = 'SL'
              AND COALESCE(t.STATUS, 'Y') <> 'N'
              AND COALESCE(t.IS_MERGE_INVOICE, 'N') <> 'Y'
              AND t.STAGE           <> 'Submitted'
              AND t.TRANSACTION_DATE BETWEEN :from AND :to
            GROUP BY 1
            ORDER BY 1
           """, nativeQuery = true)
    List<Object[]> monthlyTotals(@Param("orgId") Long orgId,
                                 @Param("branchId") Long branchId,
                                 @Param("from") LocalDate from,
                                 @Param("to") LocalDate to);

    /** The branch's targets for one month. */
    @Query("""
           SELECT t FROM PosSalesTarget t
            WHERE t.pidBranch = :branchId AND t.periodMonth = :month AND t.status = 'Y'
           """)
    List<PosSalesTarget> targetsFor(@Param("branchId") Long branchId,
                                    @Param("month") LocalDate month);

    /** One person's target for one month, if they have been given one. */
    @Query("""
           SELECT t FROM PosSalesTarget t
            WHERE t.pidBranch = :branchId AND t.pidUser = :pidUser AND t.periodMonth = :month
           """)
    Optional<PosSalesTarget> findTarget(@Param("branchId") Long branchId,
                                        @Param("pidUser") Long pidUser,
                                        @Param("month") LocalDate month);
}
