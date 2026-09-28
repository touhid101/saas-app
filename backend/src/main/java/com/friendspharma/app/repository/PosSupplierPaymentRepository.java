package com.friendspharma.app.repository;

import com.friendspharma.app.entity.PosSupplierPayment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The supplier payment ledger, and the two views that define what is owed.
 *
 * <p>Every "what does this supplier owe" question in the system routes through
 * {@code V_PURCHASE_PAYABLE} / {@code V_SUPPLIER_ACCOUNT} rather than being
 * re-derived per query. That is deliberate: the arithmetic used to live in each
 * report, and each copy of it counted draft purchases as liabilities and ignored
 * both the ledger and purchase-return credits.
 */
@Repository
public interface PosSupplierPaymentRepository extends JpaRepository<PosSupplierPayment, Long> {

    /* ══════════════════════════════════════════════════════════════════════
       THE LEDGER
       ══════════════════════════════════════════════════════════════════════ */

    @Query("""
           SELECT p FROM PosSupplierPayment p
            WHERE p.pidOrg = :orgId AND p.pidBranch = :branchId
              AND (:supplierId IS NULL OR p.pidSupplier = :supplierId)
              AND p.paymentDate >= :from
              AND p.paymentDate <= :to
              AND (:mode = ''    OR p.paymentMode = :mode)
              AND (:includeCancelled = TRUE OR p.status = 'Y')
              AND (:q = ''
                   OR LOWER(p.voucherNo)   LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(COALESCE(p.referenceNo, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(COALESCE(p.chequeNo, ''))    LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY p.paymentDate DESC, p.pidPayment DESC
           """)
    /* branchId is bound here as well as named in the query above. It was added
       to the JPQL when these queries were branch-scoped and not to the
       signature — which compiles and starts perfectly well, because Hibernate
       only checks that every named parameter has an argument when the query is
       actually run. The first person to open the Payments tab got a 500. */
    Page<PosSupplierPayment> search(@Param("orgId") Long orgId,
                                    @Param("branchId") Long branchId,
                                    @Param("supplierId") Long supplierId,
                                    @Param("from") LocalDate from,
                                    @Param("to") LocalDate to,
                                    @Param("mode") String mode,
                                    @Param("q") String q,
                                    @Param("includeCancelled") boolean includeCancelled,
                                    Pageable pageable);

    /* ══════════════════════════════════════════════════════════════════════
       WHAT IS OWED — the shared views
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * Every approved purchase still carrying a balance, for one supplier.
     *
     * <p>Ordered oldest first: that is the order they should be paid in, so the
     * screen does not have to teach it.
     */
    @Query(value = """
           SELECT v.PID_TRAN_MST, v.INVOICE_NO, v.TRANSACTION_DATE, v.PAYMENT_TYPE,
                  v.BILLED, v.PAID_AT_INVOICE, v.PAID_LATER, v.RETURN_CREDIT, v.DUE, v.AGE_DAYS,
                  v.INVOICE_DIS_AMT
             FROM PHARMA.V_PURCHASE_PAYABLE v
            WHERE v.PID_ORG = :orgId AND v.PID_BRANCH = :branchId
              AND v.PID_SUPPLIER = :supplierId
              AND (:openOnly = FALSE OR v.DUE > 0)
            ORDER BY v.TRANSACTION_DATE, v.PID_TRAN_MST
           """, nativeQuery = true)
    List<Object[]> payableInvoices(@Param("orgId") Long orgId,
                                   @Param("branchId") Long branchId,
                                   @Param("supplierId") Long supplierId,
                                   @Param("openOnly") boolean openOnly);

    /** One purchase's payable row, for the guard and for the screen after a save. */
    @Query(value = """
           SELECT v.PID_TRAN_MST, v.INVOICE_NO, v.TRANSACTION_DATE, v.PAYMENT_TYPE,
                  v.BILLED, v.PAID_AT_INVOICE, v.PAID_LATER, v.RETURN_CREDIT, v.DUE, v.AGE_DAYS,
                  v.INVOICE_DIS_AMT
             FROM PHARMA.V_PURCHASE_PAYABLE v
            WHERE v.PID_TRAN_MST = :purchaseId
           """, nativeQuery = true)
    List<Object[]> payableInvoice(@Param("purchaseId") Long purchaseId);

    /**
     * Supplier accounts — outstanding, ageing, and the dates performance is
     * judged on.
     *
     * <p>Suppliers with no approved purchases and no balance are dropped unless
     * asked for: a list of dormant names is noise on a payables screen.
     */
    @Query(value = """
           SELECT a.PID_SUPPLIER, a.SUPPLIER_NAME, a.MOBILE_NO, a.RESPONSIBLE_PERSON,
                  a.CREDIT_LIMIT,
                  -- The branch view has no OPENING_BAL, and deliberately so: an
                  -- opening balance is recorded against the supplier with no
                  -- branch on it, so repeating it on every branch row would
                  -- multiply the debt by the number of branches. See V53. The
                  -- column is held here only to keep the result shape.
                  NULL::numeric AS OPENING_BAL,
                  a.INVOICES, a.BILLED, a.PAID,
                  a.RETURN_CREDIT, a.ON_ACCOUNT, a.ADVANCE_BALANCE, a.OUTSTANDING, a.NET_POSITION,
                  a.DUE_0_30, a.DUE_31_60, a.DUE_61_90, a.DUE_90_PLUS,
                  a.FIRST_PURCHASE, a.LAST_PURCHASE,
                  a.LAST_PAYMENT_DATE, a.LAST_PAYMENT_AMOUNT,
                  a.UNAPPLIED_CREDIT
             FROM PHARMA.V_SUPPLIER_ACCOUNT_BRANCH a
            WHERE a.PID_ORG = :orgId
              AND a.PID_BRANCH = :branchId
              AND (:q = '' OR LOWER(a.SUPPLIER_NAME) LIKE LOWER('%' || :q || '%'))
              AND (:dueOnly = FALSE OR a.OUTSTANDING > 0)
              AND (:includeDormant = TRUE OR a.INVOICES > 0 OR a.OUTSTANDING <> 0)
            ORDER BY a.OUTSTANDING DESC, a.SUPPLIER_NAME
           """, nativeQuery = true)
    List<Object[]> supplierAccounts(@Param("orgId") Long orgId,
                                    @Param("branchId") Long branchId,
                                    @Param("q") String q,
                                    @Param("dueOnly") boolean dueOnly,
                                    @Param("includeDormant") boolean includeDormant);

    /**
     * Supplier performance over a window.
     *
     * <p>Two figures here are judgements about the supplier rather than about
     * our bookkeeping, and both come from data we already hold:
     *
     * <ul>
     *   <li><b>Return rate</b> — value sent back over value bought. The nearest
     *       thing to a quality score this schema can honestly produce.</li>
     *   <li><b>Average days to pay</b> — weighted by amount, so one large late
     *       cheque is not hidden by a dozen small prompt ones. This measures us,
     *       not them, but it is what a supplier looks at before extending
     *       credit, so it belongs on the same screen.</li>
     * </ul>
     */
    @Query(value = """
           WITH bought AS (
                SELECT v.PID_SUPPLIER,
                       COUNT(*)        AS INVOICES,
                       SUM(v.BILLED)   AS BILLED,
                       SUM(v.DUE)      AS DUE,
                       MAX(v.TRANSACTION_DATE) AS LAST_PURCHASE
                  FROM PHARMA.V_PURCHASE_PAYABLE v
                 WHERE v.PID_ORG = :orgId AND v.PID_BRANCH = :branchId AND v.PID_BRANCH = :branchId
                   AND (CAST(:from AS date) IS NULL OR v.TRANSACTION_DATE >= :from)
                   AND (CAST(:to   AS date) IS NULL OR v.TRANSACTION_DATE <= :to)
                 GROUP BY v.PID_SUPPLIER
           ), returned AS (
                SELECT r.PID_SUP_CUST AS PID_SUPPLIER,
                       COUNT(*) AS RETURN_COUNT,
                       SUM(COALESCE(r.AMOUNT_TOBE_PAID,0)) AS RETURNED
                  FROM PHARMA.POS_TRANSACTION_MST r
                 WHERE r.PID_ORG = :orgId AND r.PID_BRANCH = :branchId
                   AND r.TRANSACTION_TYPE = 'PR'
                   AND r.STATUS = 'A'
                   AND (CAST(:from AS date) IS NULL OR r.TRANSACTION_DATE >= :from)
                   AND (CAST(:to   AS date) IS NULL OR r.TRANSACTION_DATE <= :to)
                 GROUP BY r.PID_SUP_CUST
           ), settled AS (
                -- Only payments tied to an invoice can be timed. On-account
                -- money has no bill to measure against, so it is counted in the
                -- total paid but left out of the days-to-pay average rather
                -- than dated from an arbitrary invoice.
                SELECT p.PID_SUPPLIER,
                       SUM(p.AMOUNT) AS PAID_IN_WINDOW,
                       SUM(p.AMOUNT * (p.PAYMENT_DATE - t.TRANSACTION_DATE))
                         FILTER (WHERE p.PID_TRAN_MST IS NOT NULL) AS WEIGHTED_DAYS,
                       SUM(p.AMOUNT) FILTER (WHERE p.PID_TRAN_MST IS NOT NULL) AS DATED_PAID
                  FROM PHARMA.POS_SUPPLIER_PAYMENT p
                  LEFT JOIN PHARMA.POS_TRANSACTION_MST t ON t.PID_TRAN_MST = p.PID_TRAN_MST
                 WHERE p.PID_ORG = :orgId AND p.PID_BRANCH = :branchId
                   AND p.STATUS = 'Y'
                   AND (CAST(:from AS date) IS NULL OR p.PAYMENT_DATE >= :from)
                   AND (CAST(:to   AS date) IS NULL OR p.PAYMENT_DATE <= :to)
                 GROUP BY p.PID_SUPPLIER
           )
           SELECT s.PID_SUPPLIER,
                  s.SUPPLIER_NAME,
                  s.MOBILE_NO,
                  s.CREDIT_LIMIT,
                  COALESCE(b.INVOICES, 0)        AS INVOICES,
                  COALESCE(b.BILLED, 0)          AS BILLED,
                  COALESCE(pd.PAID_IN_WINDOW, 0) AS PAID,
                  COALESCE(b.DUE, 0)             AS DUE,
                  COALESCE(rt.RETURN_COUNT, 0)   AS RETURN_COUNT,
                  COALESCE(rt.RETURNED, 0)       AS RETURNED,
                  CASE WHEN COALESCE(b.BILLED,0) > 0
                       THEN ROUND(COALESCE(rt.RETURNED,0) * 100.0 / b.BILLED, 2)
                       ELSE 0 END                AS RETURN_PCT,
                  CASE WHEN COALESCE(pd.DATED_PAID,0) > 0
                       THEN ROUND(pd.WEIGHTED_DAYS / pd.DATED_PAID, 1)
                       ELSE NULL END             AS AVG_DAYS_TO_PAY,
                  b.LAST_PURCHASE
             FROM PHARMA.POS_SUPPLIER_INFO s
             LEFT JOIN bought   b  ON b.PID_SUPPLIER  = s.PID_SUPPLIER
             LEFT JOIN returned rt ON rt.PID_SUPPLIER = s.PID_SUPPLIER
             LEFT JOIN settled  pd ON pd.PID_SUPPLIER = s.PID_SUPPLIER
            WHERE s.PID_ORG = :orgId AND s.PID_BRANCH = :branchId
              AND (:q = '' OR LOWER(s.SUPPLIER_NAME) LIKE LOWER('%' || :q || '%'))
              AND (COALESCE(b.INVOICES,0) > 0 OR COALESCE(rt.RETURN_COUNT,0) > 0
                   OR COALESCE(pd.PAID_IN_WINDOW,0) <> 0)
            ORDER BY COALESCE(b.BILLED, 0) DESC, s.SUPPLIER_NAME
           """, nativeQuery = true)
    List<Object[]> performance(@Param("orgId") Long orgId,
                               @Param("branchId") Long branchId,
                               @Param("from") LocalDate from,
                               @Param("to") LocalDate to,
                               @Param("q") String q);

    /**
     * Paid-to-date and still-owing for a set of purchases.
     *
     * <p>For the purchase screens, which otherwise read only GIVEN_AMOUNT — the
     * cash handed over at the counter — and so reported an invoice settled
     * later through the payment screen as still fully unpaid.
     */
    @Query(value = """
           SELECT v.PID_TRAN_MST,
                  v.PAID_AT_INVOICE + v.PAID_LATER AS PAID,
                  v.RETURN_CREDIT,
                  v.DUE
             FROM PHARMA.V_PURCHASE_PAYABLE v
            WHERE v.PID_TRAN_MST IN (:ids)
           """, nativeQuery = true)
    List<Object[]> settlementFor(@Param("ids") java.util.Collection<Long> ids);

    /** True when the cash on a purchase has already been mirrored here. */
    boolean existsByPidTranMstAndSource(Long pidTranMst, String source);

    /** The mirror rows of a purchase — one, in practice, unless a cancelled one was replaced. */
    List<PosSupplierPayment> findByPidTranMstAndSourceOrderByPidPaymentAsc(Long pidTranMst, String source);

    /**
     * What one supplier still holds of ours.
     *
     * <p>Advances less recoveries, from the line view so the sign rules are not
     * restated here. Null when the supplier has never been advanced anything.
     */
    @Query(value = """
           SELECT SUM(l.ADVANCE_DELTA) FROM PHARMA.V_SUPPLIER_PAYMENT_LINE l
            WHERE l.PID_SUPPLIER = :supplierId
           """, nativeQuery = true)
    BigDecimal advanceBalance(@Param("supplierId") Long supplierId);

    /**
     * Advances by supplier — paid, recovered, and still held.
     *
     * <p>Grouped by supplier rather than listed per voucher: an advance is not
     * recovered voucher by voucher. The supplier returns a sum, and pretending
     * it maps to a particular payment would be an allocation nobody made.
     */
    @Query(value = """
           SELECT l.PID_SUPPLIER,
                  s.SUPPLIER_NAME,
                  SUM(CASE WHEN l.ENTRY_TYPE = 'PAYMENT' THEN l.ADVANCE_AMOUNT ELSE 0 END) AS ADVANCED,
                  SUM(CASE WHEN l.ENTRY_TYPE = 'ADVANCE_RECOVERY' THEN l.AMOUNT ELSE 0 END) AS RECOVERED,
                  SUM(l.ADVANCE_DELTA) AS BALANCE,
                  MIN(CASE WHEN l.ADVANCE_AMOUNT > 0 THEN l.PAYMENT_DATE END) AS FIRST_ADVANCE,
                  MAX(l.PAYMENT_DATE) AS LAST_MOVEMENT
             FROM PHARMA.V_SUPPLIER_PAYMENT_LINE l
             JOIN PHARMA.POS_SUPPLIER_INFO s ON s.PID_SUPPLIER = l.PID_SUPPLIER
            WHERE l.PID_ORG = :orgId AND l.PID_BRANCH = :branchId
              AND (CAST(:supplierId AS bigint) IS NULL OR l.PID_SUPPLIER = :supplierId)
              AND (l.ADVANCE_AMOUNT > 0 OR l.ENTRY_TYPE = 'ADVANCE_RECOVERY')
            GROUP BY l.PID_SUPPLIER, s.SUPPLIER_NAME
           HAVING (:openOnly = FALSE OR SUM(l.ADVANCE_DELTA) > 0)
            ORDER BY BALANCE DESC, s.SUPPLIER_NAME
           """, nativeQuery = true)
    List<Object[]> advances(@Param("orgId") Long orgId,
                            @Param("branchId") Long branchId,
                            @Param("supplierId") Long supplierId,
                            @Param("openOnly") boolean openOnly);

    /* ══════════════════════════════════════════════════════════════════════
       LEDGER FOR ONE SUPPLIER — invoices and payments in one stream
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * A running statement: purchases raise the balance, payments and adjusted
     * credits reduce it. Union rather than a join, because these are different
     * kinds of event and a statement reads by date, not by invoice.
     */
    @Query(value = """
           SELECT * FROM (
               SELECT v.TRANSACTION_DATE AS EVENT_DATE, 'PURCHASE' AS KIND,
                      v.INVOICE_NO AS DOC_NO, v.BILLED AS DEBIT, 0 AS CREDIT,
                      v.PAYMENT_TYPE AS MODE, NULL AS NOTE, v.PID_TRAN_MST AS REF_ID
                 FROM PHARMA.V_PURCHASE_PAYABLE v
                WHERE v.PID_ORG = :orgId AND v.PID_BRANCH = :branchId AND v.PID_SUPPLIER = :supplierId
               UNION ALL
               SELECT t.TRANSACTION_DATE, 'RETURN', t.INVOICE_NO, 0,
                      COALESCE(t.AMOUNT_TOBE_PAID,0), t.PAYMENT_TYPE, t.REMARKS, t.PID_TRAN_MST
                 FROM PHARMA.POS_TRANSACTION_MST t
                WHERE t.PID_ORG = :orgId AND t.PID_BRANCH = :branchId AND t.PID_SUP_CUST = :supplierId
                  AND t.TRANSACTION_TYPE = 'PR' AND t.STATUS = 'A'
                  AND COALESCE(t.PAYMENT_TYPE,'ADJUSTMENT') = 'ADJUSTMENT'
               UNION ALL
               -- Only the part that settles bills moves this balance. An
               -- advance is money out, but it is not a payment against the
               -- account — it becomes a debt the other way, tracked separately.
               SELECT l.PAYMENT_DATE,
                      -- An advance taken as credit settles the bill without any cash
                      -- moving, so calling it a payment would misdescribe it on a
                      -- statement someone reconciles against a bank.
                      CASE WHEN l.ENTRY_TYPE = 'ADVANCE_RECOVERY' THEN 'ADV CREDIT'
                           ELSE 'PAYMENT' END,
                      l.VOUCHER_NO, 0, l.APPLIED,
                      l.PAYMENT_MODE, l.REMARKS, l.PID_PAYMENT
                 FROM PHARMA.V_SUPPLIER_PAYMENT_LINE l
                WHERE l.PID_ORG = :orgId AND l.PID_BRANCH = :branchId AND l.PID_SUPPLIER = :supplierId
                  AND l.APPLIED <> 0
               UNION ALL
               -- Shown at zero so an advance appears on the statement in date
               -- order without moving the balance. Leaving it off entirely
               -- makes a large payment look smaller than the bank shows.
               SELECT l.PAYMENT_DATE, 'ADVANCE', l.VOUCHER_NO, 0, 0,
                      l.PAYMENT_MODE,
                      'Advance of ' || TO_CHAR(l.ADVANCE_AMOUNT, 'FM999999990.00')
                        || ' — recoverable from the supplier', l.PID_PAYMENT
                 FROM PHARMA.V_SUPPLIER_PAYMENT_LINE l
                WHERE l.PID_ORG = :orgId AND l.PID_BRANCH = :branchId AND l.PID_SUPPLIER = :supplierId
                  AND l.ENTRY_TYPE = 'PAYMENT' AND l.ADVANCE_AMOUNT > 0
               UNION ALL
               SELECT l.PAYMENT_DATE, 'RECOVERY', l.VOUCHER_NO, 0, 0,
                      l.PAYMENT_MODE, l.REMARKS, l.PID_PAYMENT
                 FROM PHARMA.V_SUPPLIER_PAYMENT_LINE l
                WHERE l.PID_ORG = :orgId AND l.PID_BRANCH = :branchId AND l.PID_SUPPLIER = :supplierId
                  AND l.ENTRY_TYPE = 'ADVANCE_RECOVERY' AND l.APPLIED = 0
           ) x
            WHERE (CAST(:from AS date) IS NULL OR x.EVENT_DATE >= :from)
              AND (CAST(:to   AS date) IS NULL OR x.EVENT_DATE <= :to)
            ORDER BY x.EVENT_DATE,
                     -- Not alphabetical: on one date a statement has to read as
                     -- the bill, then what was done about it. Sorting by name
                     -- put PAYMENT before PURCHASE and showed the account in
                     -- credit before the invoice it was paying had appeared.
                     CASE x.KIND WHEN 'PURCHASE' THEN 1 WHEN 'RETURN'   THEN 2
                                 WHEN 'ADVANCE'  THEN 3 WHEN 'PAYMENT'  THEN 4
                                 WHEN 'ADV CREDIT' THEN 4
                                 ELSE 5 END,
                     x.REF_ID
           """, nativeQuery = true)
    List<Object[]> statement(@Param("orgId") Long orgId,
                             @Param("branchId") Long branchId,
                             @Param("supplierId") Long supplierId,
                             @Param("from") LocalDate from,
                             @Param("to") LocalDate to);
}
