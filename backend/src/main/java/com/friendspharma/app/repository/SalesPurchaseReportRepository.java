package com.friendspharma.app.repository;

import com.friendspharma.app.entity.PosTransactionMst;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Sales and purchase aggregates for the Reports screen.
 *
 * <p>Written fresh rather than repairing {@code ReportServiceImpl}, whose SQL
 * targets a schema this database does not have: it selects {@code INVOICE_DATE},
 * {@code PID_CUSTOMER}, {@code SUB_TOTAL} and {@code GRAND_TOTAL}, none of which
 * exist here — the real columns are {@code TRANSACTION_DATE},
 * {@code PID_SUP_CUST}, {@code TOTAL_AMOUNT} and {@code AMOUNT_TOBE_PAID}. It
 * also builds its WHERE clauses by string concatenation and calls Oracle's
 * SYSDATE, NVL and TRUNC. Every one of its 28 endpoints returns 500.
 *
 * <p>Everything here is native and schema-qualified, with every filter bound.
 * Strings use {@code ''} for "no filter" rather than null: PostgreSQL cannot
 * infer the type of a null parameter inside LOWER() and settles on bytea.
 */
@Repository
public interface SalesPurchaseReportRepository extends JpaRepository<PosTransactionMst, Long> {

    /* ══════════════════════════════════════════════════════════════════════
       SALES
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * The sales register: one row per invoice.
     *
     * <p>Excludes drafts. A Submitted invoice has reserved stock but is not yet
     * a sale, and counting it as revenue overstates every total on the screen.
     */
    @Query(value = """
           SELECT t.INVOICE_NO,
                  t.TRANSACTION_DATE,
                  c.CUSTOMER_NAME,
                  b.BRANCH_NAME,
                  t.PAYMENT_TYPE,
                  t.STAGE,
                  t.PAYMENT_STATUS,
                  COALESCE(t.TOTAL_AMOUNT, 0)      AS subtotal,
                  COALESCE(t.INVOICE_DIS_AMT, 0)   AS discount,
                  COALESCE(t.AMOUNT_TOBE_PAID, 0)  AS payable,
                  LEAST(COALESCE(t.GIVEN_AMOUNT, 0), COALESCE(t.AMOUNT_TOBE_PAID, 0)) AS paid,
                  GREATEST(ROUND(COALESCE(t.AMOUNT_TOBE_PAID,0),2) - ROUND(COALESCE(t.GIVEN_AMOUNT,0),2), 0) AS due,
                  -- What came back against this invoice. Only returns that name
                  -- it can be attributed to a row here; a credit note raised
                  -- without an invoice belongs to the customer, not to one bill,
                  -- and appears in the by-customer report instead.
                  COALESCE(ret.returned, 0) AS returned
             FROM PHARMA.POS_TRANSACTION_MST t
             LEFT JOIN PHARMA.POS_CUSTOMER_INFO c ON c.PID_CUSTOMER = t.PID_SUP_CUST
             LEFT JOIN PHARMA.ALL_BRANCH        b ON b.PID_BRANCH   = t.PID_BRANCH
             LEFT JOIN (SELECT r.PID_TRAN_MST_RETURN AS pid,
                               SUM(COALESCE(r.AMOUNT_TOBE_PAID, 0)) AS returned
                          FROM PHARMA.POS_TRANSACTION_MST r
                         WHERE r.TRANSACTION_TYPE = 'SR'
                           AND COALESCE(r.STATUS, 'Y') <> 'N'
                           AND r.PID_TRAN_MST_RETURN IS NOT NULL
                         GROUP BY r.PID_TRAN_MST_RETURN) ret ON ret.pid = t.PID_TRAN_MST
            WHERE t.PID_ORG          = :orgId
              AND t.TRANSACTION_TYPE = 'SL'
              -- A merge is a grouping document over orders that are already
              -- counted here. Including it would report the same money twice.
              AND COALESCE(t.IS_MERGE_INVOICE, 'N') <> 'Y'
              AND t.STAGE           <> 'Submitted'
              AND (CAST(:branchId AS bigint) IS NULL OR t.PID_BRANCH   = :branchId)
              AND (CAST(:customerId AS bigint) IS NULL OR t.PID_SUP_CUST = :customerId)
              AND (CAST(:from AS date) IS NULL OR t.TRANSACTION_DATE >= :from)
              AND (CAST(:to   AS date) IS NULL OR t.TRANSACTION_DATE <= :to)
              AND (:paymentStatus = '' OR t.PAYMENT_STATUS = :paymentStatus)
              AND (:q = ''
                   OR LOWER(t.INVOICE_NO)    LIKE LOWER('%' || :q || '%')
                   OR LOWER(c.CUSTOMER_NAME) LIKE LOWER('%' || :q || '%'))
            ORDER BY t.TRANSACTION_DATE DESC, t.PID_TRAN_MST DESC
           """, nativeQuery = true)
    List<Object[]> salesRegister(@Param("orgId") Long orgId,
                                 @Param("branchId") Long branchId,
                                 @Param("customerId") Long customerId,
                                 @Param("from") LocalDate from,
                                 @Param("to") LocalDate to,
                                 @Param("paymentStatus") String paymentStatus,
                                 @Param("q") String q);

    /**
     * Sales grouped by product, with the company and category it belongs to.
     *
     * <p>Sales returns are netted, not omitted. A return is a reversal of a sale:
     * counting only the sale reports revenue that was handed back, and a product
     * with more returns than sales should read as negative rather than as its
     * gross. Returns are signed into the same sums and also reported on their own
     * column, because "sold 100, kept 60" and "sold 60" are different facts about
     * a product and only one of them is actionable.
     *
     * <p>An SR document is included on its own merits rather than through a link
     * to the invoice it reverses: the return carries its own lines, so the product,
     * company and category it affects are known without the original. That also
     * means a credit note raised without naming an invoice still counts.
     */
    @Query(value = """
           SELECT p.PRODUCT_NAME,
                  p.SKU,
                  co.COMPANY_NAME,
                  cat.CATEGORY_NAME,
                  SUM(CASE WHEN t.TRANSACTION_TYPE = 'SR' THEN -d.QUANTITY
                           ELSE d.QUANTITY END)                     AS qty,
                  SUM(CASE WHEN t.TRANSACTION_TYPE = 'SR'
                           THEN -(d.QUANTITY * COALESCE(d.NO_OF_PCS, 1))
                           ELSE  (d.QUANTITY * COALESCE(d.NO_OF_PCS, 1)) END) AS pcs,
                  SUM(CASE WHEN t.TRANSACTION_TYPE = 'SR' THEN -COALESCE(d.TOTAL_PRICE, 0)
                           ELSE COALESCE(d.TOTAL_PRICE, 0) END)     AS revenue,
                  -- Invoices counts sales only. A return is not a sale, and
                  -- including it inflates the count the average is divided by.
                  COUNT(DISTINCT t.PID_TRAN_MST)
                        FILTER (WHERE t.TRANSACTION_TYPE = 'SL')    AS invoices,
                  SUM(CASE WHEN t.TRANSACTION_TYPE = 'SR' THEN COALESCE(d.TOTAL_PRICE, 0)
                           ELSE 0 END)                              AS returned
             FROM PHARMA.POS_TRANSACTION_DTL d
             JOIN PHARMA.POS_TRANSACTION_MST t   ON t.PID_TRAN_MST = d.PID_TRAN_MST
             JOIN PHARMA.POS_PRODUCT_INFO   p    ON p.PID_PRODUCT  = d.PID_PRODUCT
             LEFT JOIN PHARMA.POS_COMPANY_INFO      co  ON co.PID_COMPANY  = p.PID_COMPANY
             LEFT JOIN PHARMA.POS_PRODUCT_CATEGORY  cat ON cat.PID_CATEGORY = p.PID_CATEGORY
            WHERE t.PID_ORG          = :orgId
              AND t.TRANSACTION_TYPE IN ('SL', 'SR')
              -- A cancelled sale is void (STATUS = 'N'): its stock went back on the
              -- shelf and its journals were reversed, so its lines are not revenue.
              AND COALESCE(t.STATUS, 'Y') <> 'N'
              -- A merge is a grouping document over orders that are already
              -- counted here. Including it would report the same money twice.
              AND COALESCE(t.IS_MERGE_INVOICE, 'N') <> 'Y'
              AND t.STAGE           <> 'Submitted'
              AND (CAST(:branchId AS bigint) IS NULL OR t.PID_BRANCH  = :branchId)
              AND (CAST(:companyId AS bigint) IS NULL OR p.PID_COMPANY = :companyId)
              AND (CAST(:categoryId AS bigint) IS NULL OR p.PID_CATEGORY = :categoryId)
              AND (CAST(:from AS date) IS NULL OR t.TRANSACTION_DATE >= :from)
              AND (CAST(:to   AS date) IS NULL OR t.TRANSACTION_DATE <= :to)
              AND (:q = ''
                   OR LOWER(p.PRODUCT_NAME) LIKE LOWER('%' || :q || '%')
                   OR LOWER(p.SKU)          LIKE LOWER('%' || :q || '%'))
            GROUP BY p.PRODUCT_NAME, p.SKU, co.COMPANY_NAME, cat.CATEGORY_NAME
            ORDER BY revenue DESC
           """, nativeQuery = true)
    List<Object[]> salesByProduct(@Param("orgId") Long orgId,
                                  @Param("branchId") Long branchId,
                                  @Param("companyId") Long companyId,
                                  @Param("categoryId") Long categoryId,
                                  @Param("from") LocalDate from,
                                  @Param("to") LocalDate to,
                                  @Param("q") String q);

    /** Sales grouped by customer, with what they still owe across the period. */
    @Query(value = """
           SELECT c.CUSTOMER_NAME,
                  c.CUSTOMER_TYPE,
                  c.MOBILE_NO,
                  COUNT(*) FILTER (WHERE t.TRANSACTION_TYPE = 'SL')  AS invoices,
                  SUM(CASE WHEN t.TRANSACTION_TYPE = 'SR'
                           THEN -COALESCE(t.AMOUNT_TOBE_PAID, 0)
                           ELSE  COALESCE(t.AMOUNT_TOBE_PAID, 0) END) AS billed,
                  SUM(CASE WHEN t.TRANSACTION_TYPE = 'SR'
                           THEN -LEAST(COALESCE(t.GIVEN_AMOUNT,0), COALESCE(t.AMOUNT_TOBE_PAID,0))
                           ELSE  LEAST(COALESCE(t.GIVEN_AMOUNT,0), COALESCE(t.AMOUNT_TOBE_PAID,0)) END) AS paid,
                  -- A return where nothing was handed back in cash leaves a credit
                  -- standing to the customer, which is a negative due. Clamping it
                  -- at zero per document, as the sale side does, would report a
                  -- customer we owe as square.
                  SUM(CASE WHEN t.TRANSACTION_TYPE = 'SR'
                           THEN -GREATEST(ROUND(COALESCE(t.AMOUNT_TOBE_PAID,0),2) - ROUND(COALESCE(t.GIVEN_AMOUNT,0),2), 0)
                           ELSE  GREATEST(ROUND(COALESCE(t.AMOUNT_TOBE_PAID,0),2) - ROUND(COALESCE(t.GIVEN_AMOUNT,0),2), 0) END) AS due,
                  SUM(CASE WHEN t.TRANSACTION_TYPE = 'SR'
                           THEN COALESCE(t.AMOUNT_TOBE_PAID, 0) ELSE 0 END) AS returned
             FROM PHARMA.POS_TRANSACTION_MST t
             JOIN PHARMA.POS_CUSTOMER_INFO   c ON c.PID_CUSTOMER = t.PID_SUP_CUST
            WHERE t.PID_ORG          = :orgId
              AND t.TRANSACTION_TYPE IN ('SL', 'SR')
              -- A cancelled sale is void (STATUS = 'N'): its stock went back on the
              -- shelf and its journals were reversed, so its lines are not revenue.
              AND COALESCE(t.STATUS, 'Y') <> 'N'
              -- A merge is a grouping document over orders that are already
              -- counted here. Including it would report the same money twice.
              AND COALESCE(t.IS_MERGE_INVOICE, 'N') <> 'Y'
              AND t.STAGE           <> 'Submitted'
              AND (CAST(:branchId AS bigint) IS NULL OR t.PID_BRANCH = :branchId)
              AND (CAST(:from AS date) IS NULL OR t.TRANSACTION_DATE >= :from)
              AND (CAST(:to   AS date) IS NULL OR t.TRANSACTION_DATE <= :to)
              AND (:q = '' OR LOWER(c.CUSTOMER_NAME) LIKE LOWER('%' || :q || '%'))
            GROUP BY c.CUSTOMER_NAME, c.CUSTOMER_TYPE, c.MOBILE_NO
            -- Due-only is applied to the netted total rather than per document.
            -- Filtering the rows first would keep a customer whose one unpaid
            -- invoice has already been cancelled out by a credit note.
            HAVING (:dueOnly = FALSE
                    OR SUM(CASE WHEN t.TRANSACTION_TYPE = 'SR'
                                THEN -GREATEST(ROUND(COALESCE(t.AMOUNT_TOBE_PAID,0),2) - ROUND(COALESCE(t.GIVEN_AMOUNT,0),2), 0)
                                ELSE  GREATEST(ROUND(COALESCE(t.AMOUNT_TOBE_PAID,0),2) - ROUND(COALESCE(t.GIVEN_AMOUNT,0),2), 0) END) >= 0.01)
            ORDER BY billed DESC
           """, nativeQuery = true)
    List<Object[]> salesByCustomer(@Param("orgId") Long orgId,
                                   @Param("branchId") Long branchId,
                                   @Param("from") LocalDate from,
                                   @Param("to") LocalDate to,
                                   @Param("q") String q,
                                   @Param("dueOnly") boolean dueOnly);

    /** Sales grouped by the manufacturer whose products were sold. */
    @Query(value = """
           SELECT COALESCE(co.COMPANY_NAME, 'Unassigned'),
                  COUNT(DISTINCT d.PID_PRODUCT)                AS products,
                  SUM(CASE WHEN t.TRANSACTION_TYPE = 'SR' THEN -d.QUANTITY
                           ELSE d.QUANTITY END)                AS qty,
                  SUM(CASE WHEN t.TRANSACTION_TYPE = 'SR' THEN -COALESCE(d.TOTAL_PRICE, 0)
                           ELSE COALESCE(d.TOTAL_PRICE, 0) END) AS revenue,
                  COUNT(DISTINCT t.PID_TRAN_MST)
                        FILTER (WHERE t.TRANSACTION_TYPE = 'SL') AS invoices,
                  SUM(CASE WHEN t.TRANSACTION_TYPE = 'SR' THEN COALESCE(d.TOTAL_PRICE, 0)
                           ELSE 0 END)                          AS returned
             FROM PHARMA.POS_TRANSACTION_DTL d
             JOIN PHARMA.POS_TRANSACTION_MST t  ON t.PID_TRAN_MST = d.PID_TRAN_MST
             JOIN PHARMA.POS_PRODUCT_INFO   p   ON p.PID_PRODUCT  = d.PID_PRODUCT
             LEFT JOIN PHARMA.POS_COMPANY_INFO co ON co.PID_COMPANY = p.PID_COMPANY
            WHERE t.PID_ORG          = :orgId
              AND t.TRANSACTION_TYPE IN ('SL', 'SR')
              -- A cancelled sale is void (STATUS = 'N'): its stock went back on the
              -- shelf and its journals were reversed, so its lines are not revenue.
              AND COALESCE(t.STATUS, 'Y') <> 'N'
              -- A merge is a grouping document over orders that are already
              -- counted here. Including it would report the same money twice.
              AND COALESCE(t.IS_MERGE_INVOICE, 'N') <> 'Y'
              AND t.STAGE           <> 'Submitted'
              AND (CAST(:branchId AS bigint) IS NULL OR t.PID_BRANCH = :branchId)
              AND (CAST(:from AS date) IS NULL OR t.TRANSACTION_DATE >= :from)
              AND (CAST(:to   AS date) IS NULL OR t.TRANSACTION_DATE <= :to)
            GROUP BY COALESCE(co.COMPANY_NAME, 'Unassigned')
            ORDER BY revenue DESC
           """, nativeQuery = true)
    List<Object[]> salesByCompany(@Param("orgId") Long orgId,
                                  @Param("branchId") Long branchId,
                                  @Param("from") LocalDate from,
                                  @Param("to") LocalDate to);

    /** Sales grouped by product category. */
    @Query(value = """
           SELECT COALESCE(cat.CATEGORY_NAME, 'Uncategorised'),
                  COUNT(DISTINCT d.PID_PRODUCT)                AS products,
                  SUM(CASE WHEN t.TRANSACTION_TYPE = 'SR' THEN -d.QUANTITY
                           ELSE d.QUANTITY END)                AS qty,
                  SUM(CASE WHEN t.TRANSACTION_TYPE = 'SR' THEN -COALESCE(d.TOTAL_PRICE, 0)
                           ELSE COALESCE(d.TOTAL_PRICE, 0) END) AS revenue,
                  SUM(CASE WHEN t.TRANSACTION_TYPE = 'SR' THEN COALESCE(d.TOTAL_PRICE, 0)
                           ELSE 0 END)                          AS returned
             FROM PHARMA.POS_TRANSACTION_DTL d
             JOIN PHARMA.POS_TRANSACTION_MST t   ON t.PID_TRAN_MST = d.PID_TRAN_MST
             JOIN PHARMA.POS_PRODUCT_INFO   p    ON p.PID_PRODUCT  = d.PID_PRODUCT
             LEFT JOIN PHARMA.POS_PRODUCT_CATEGORY cat ON cat.PID_CATEGORY = p.PID_CATEGORY
            WHERE t.PID_ORG          = :orgId
              AND t.TRANSACTION_TYPE IN ('SL', 'SR')
              -- A cancelled sale is void (STATUS = 'N'): its stock went back on the
              -- shelf and its journals were reversed, so its lines are not revenue.
              AND COALESCE(t.STATUS, 'Y') <> 'N'
              -- A merge is a grouping document over orders that are already
              -- counted here. Including it would report the same money twice.
              AND COALESCE(t.IS_MERGE_INVOICE, 'N') <> 'Y'
              AND t.STAGE           <> 'Submitted'
              AND (CAST(:branchId AS bigint) IS NULL OR t.PID_BRANCH = :branchId)
              AND (CAST(:from AS date) IS NULL OR t.TRANSACTION_DATE >= :from)
              AND (CAST(:to   AS date) IS NULL OR t.TRANSACTION_DATE <= :to)
            GROUP BY COALESCE(cat.CATEGORY_NAME, 'Uncategorised')
            ORDER BY revenue DESC
           """, nativeQuery = true)
    List<Object[]> salesByCategory(@Param("orgId") Long orgId,
                                   @Param("branchId") Long branchId,
                                   @Param("from") LocalDate from,
                                   @Param("to") LocalDate to);

    /* ══════════════════════════════════════════════════════════════════════
       PURCHASE
       ══════════════════════════════════════════════════════════════════════ */

    @Query(value = """
           SELECT t.INVOICE_NO,
                  t.TRANSACTION_DATE,
                  s.SUPPLIER_NAME,
                  b.BRANCH_NAME,
                  t.PAYMENT_TYPE,
                  t.STATUS,
                  COALESCE(t.TOTAL_AMOUNT, 0)     AS subtotal,
                  COALESCE(t.INVOICE_DIS_AMT, 0)  AS discount,
                  COALESCE(t.AMOUNT_TOBE_PAID, 0) AS payable,
                  -- Paid and due come from V_PURCHASE_PAYABLE, which knows about
                  -- the payment ledger and about credits from purchase returns.
                  -- Read off the invoice alone, this column showed a supplier as
                  -- still owed money the day after they were paid.
                  COALESCE(v.PAID_AT_INVOICE, 0) + COALESCE(v.PAID_LATER, 0) AS paid,
                  COALESCE(v.RETURN_CREDIT, 0)    AS credited,
                  -- The view has no row for a draft or pending purchase, so due
                  -- is zero there rather than the full bill: nothing has been
                  -- received and nothing is owed yet.
                  COALESCE(v.DUE, 0)              AS due
             FROM PHARMA.POS_TRANSACTION_MST t
             LEFT JOIN PHARMA.POS_SUPPLIER_INFO s ON s.PID_SUPPLIER = t.PID_SUP_CUST
             LEFT JOIN PHARMA.ALL_BRANCH        b ON b.PID_BRANCH   = t.PID_BRANCH
             LEFT JOIN PHARMA.V_PURCHASE_PAYABLE v ON v.PID_TRAN_MST = t.PID_TRAN_MST
            WHERE t.PID_ORG          = :orgId
              AND t.TRANSACTION_TYPE = :type
              AND (CAST(:branchId AS bigint) IS NULL OR t.PID_BRANCH   = :branchId)
              AND (CAST(:supplierId AS bigint) IS NULL OR t.PID_SUP_CUST = :supplierId)
              AND (CAST(:from AS date) IS NULL OR t.TRANSACTION_DATE >= :from)
              AND (CAST(:to   AS date) IS NULL OR t.TRANSACTION_DATE <= :to)
              AND (:q = ''
                   OR LOWER(t.INVOICE_NO)    LIKE LOWER('%' || :q || '%')
                   OR LOWER(s.SUPPLIER_NAME) LIKE LOWER('%' || :q || '%'))
            ORDER BY t.TRANSACTION_DATE DESC, t.PID_TRAN_MST DESC
           """, nativeQuery = true)
    List<Object[]> purchaseRegister(@Param("orgId") Long orgId,
                                    @Param("type") String type,
                                    @Param("branchId") Long branchId,
                                    @Param("supplierId") Long supplierId,
                                    @Param("from") LocalDate from,
                                    @Param("to") LocalDate to,
                                    @Param("q") String q);

    /**
     * Spend with each supplier over a window.
     *
     * <p>Sourced from {@code V_PURCHASE_PAYABLE}, so it counts approved
     * purchases only and its paid figure includes the payment ledger. The
     * previous version summed every document in the table — a draft purchase
     * that never received stock was reported as money spent and money owed.
     */
    @Query(value = """
           SELECT s.SUPPLIER_NAME,
                  s.MOBILE_NO,
                  COUNT(*)                              AS invoices,
                  SUM(v.BILLED)                         AS billed,
                  SUM(v.PAID_AT_INVOICE + v.PAID_LATER) AS paid,
                  SUM(v.RETURN_CREDIT)                  AS credited,
                  SUM(v.DUE)                            AS due
             FROM PHARMA.V_PURCHASE_PAYABLE v
             JOIN PHARMA.POS_SUPPLIER_INFO  s ON s.PID_SUPPLIER = v.PID_SUPPLIER
            WHERE v.PID_ORG = :orgId
              AND (CAST(:branchId AS bigint) IS NULL OR v.PID_BRANCH = :branchId)
              AND (CAST(:from AS date) IS NULL OR v.TRANSACTION_DATE >= :from)
              AND (CAST(:to   AS date) IS NULL OR v.TRANSACTION_DATE <= :to)
              AND (:q = '' OR LOWER(s.SUPPLIER_NAME) LIKE LOWER('%' || :q || '%'))
            GROUP BY s.SUPPLIER_NAME, s.MOBILE_NO
            ORDER BY billed DESC
           """, nativeQuery = true)
    List<Object[]> purchaseBySupplier(@Param("orgId") Long orgId,
                                      @Param("branchId") Long branchId,
                                      @Param("from") LocalDate from,
                                      @Param("to") LocalDate to,
                                      @Param("q") String q);

    /**
     * [V67-69] What each supplier actually gave, beyond the price on the invoice.
     *
     * <p>Bonus units, free gift products and cashback are three different
     * generosities that the business has never been able to see together. A
     * buyer choosing between two suppliers quoting the same rate needs this
     * number, and until now it existed only inside individual invoices.
     *
     * <p><b>Two figures are counted and one is valued.</b> Bonus units and gift
     * units are counts — a box is a box — and adding them to taka would be
     * adding unlike things. Gift value is money, because a gift is a different
     * product whose worth is its MRP; benefit is therefore cashback plus gift
     * value, and the bonus columns sit beside it as quantities rather than being
     * folded in. Valuing a bonus at MRP would double-count: the bonus already
     * shows up as a lower cost per piece on goods that were paid for.
     *
     * <p>Approved purchases only, joined through V_PURCHASE_PAYABLE, so the
     * period, the branch and the "approved only" rule are the same ones every
     * other purchase report uses.
     */
    @Query(value = """
           SELECT s.SUPPLIER_NAME,
                  COUNT(DISTINCT v.PID_TRAN_MST)                                  AS invoices,
                  -- In pieces, not as entered. QUANTITY and BONUS_QTY are held in
                  -- the sales unit of each line, so 10 on one line may be boxes and
                  -- 10 on the next loose strips; adding those gives 20 of nothing.
                  -- NO_OF_PCS is the pack factor captured at entry, and multiplying
                  -- by it puts every line on the one scale that can be summed.
                  -- MRP_PRICE needs no such treatment: it is stored per sales unit,
                  -- so it already matches the quantity beside it.
                  COALESCE(SUM(CASE WHEN d.LINE_KIND = 'PURCHASE'
                                    THEN COALESCE(d.BONUS_QTY, 0) * COALESCE(d.NO_OF_PCS, 1)
                                    ELSE 0 END), 0)                               AS bonus_units,
                  COALESCE(SUM(CASE WHEN d.LINE_KIND = 'GIFT'
                                    THEN COALESCE(d.BONUS_QTY, 0) * COALESCE(d.NO_OF_PCS, 1)
                                    ELSE 0 END), 0)                               AS gift_units,
                  COALESCE(SUM(CASE WHEN d.LINE_KIND = 'GIFT'
                                    THEN COALESCE(d.BONUS_QTY, 0) * COALESCE(d.MRP_PRICE, 0)
                                    ELSE 0 END), 0)                               AS gift_value,
                  COALESCE(MAX(m.CASHBACK_TOTAL), 0)                              AS cashback,
                  COALESCE(MAX(m.BILLED_TOTAL), 0)                                AS billed
             FROM PHARMA.V_PURCHASE_PAYABLE v
             JOIN PHARMA.POS_SUPPLIER_INFO  s ON s.PID_SUPPLIER = v.PID_SUPPLIER
             LEFT JOIN PHARMA.POS_TRANSACTION_DTL d ON d.PID_TRAN_MST = v.PID_TRAN_MST
             LEFT JOIN (
                    SELECT t.PID_SUP_CUST,
                           SUM(COALESCE(t.CASHBACK_AMOUNT, 0)) AS CASHBACK_TOTAL,
                           SUM(COALESCE(t.AMOUNT_TOBE_PAID, 0)) AS BILLED_TOTAL
                      FROM PHARMA.POS_TRANSACTION_MST t
                     WHERE t.TRANSACTION_TYPE = 'PU' AND t.STATUS = 'A'
                       AND t.PID_ORG = :orgId
                       AND (CAST(:branchId AS bigint) IS NULL OR t.PID_BRANCH = :branchId)
                       AND (CAST(:from AS date) IS NULL OR t.TRANSACTION_DATE >= :from)
                       AND (CAST(:to   AS date) IS NULL OR t.TRANSACTION_DATE <= :to)
                     GROUP BY t.PID_SUP_CUST
                  ) m ON m.PID_SUP_CUST = v.PID_SUPPLIER
            WHERE v.PID_ORG = :orgId
              AND (CAST(:branchId AS bigint) IS NULL OR v.PID_BRANCH = :branchId)
              AND (CAST(:from AS date) IS NULL OR v.TRANSACTION_DATE >= :from)
              AND (CAST(:to   AS date) IS NULL OR v.TRANSACTION_DATE <= :to)
              AND (:q = '' OR LOWER(s.SUPPLIER_NAME) LIKE LOWER('%' || :q || '%'))
            GROUP BY v.PID_SUPPLIER, s.SUPPLIER_NAME
           HAVING COALESCE(SUM(COALESCE(d.BONUS_QTY, 0)), 0) > 0
               OR COALESCE(MAX(m.CASHBACK_TOTAL), 0) > 0
            ORDER BY COALESCE(MAX(m.CASHBACK_TOTAL), 0)
                   + COALESCE(SUM(CASE WHEN d.LINE_KIND = 'GIFT'
                                       THEN COALESCE(d.BONUS_QTY, 0) * COALESCE(d.MRP_PRICE, 0)
                                       ELSE 0 END), 0) DESC,
                     s.SUPPLIER_NAME
           """, nativeQuery = true)
    List<Object[]> supplierBenefit(@Param("orgId") Long orgId,
                                   @Param("branchId") Long branchId,
                                   @Param("from") LocalDate from,
                                   @Param("to") LocalDate to,
                                   @Param("q") String q);

    /**
     * What each supplier is owed right now, with ageing.
     *
     * <p>Deliberately not date-filtered. An outstanding balance is a position as
     * at today, not an activity total for a period: narrowing it to a date range
     * would drop older unpaid bills and report a smaller debt than exists. The
     * ageing columns are how the period question gets answered here.
     *
     * <p>Scoped to one branch. It reads V_SUPPLIER_ACCOUNT_BRANCH rather than
     * V_SUPPLIER_ACCOUNT because the latter is grained per organisation: filtered
     * on PID_ORG alone, every branch of a multi-branch organisation was shown the
     * combined debt of all of them. Both views are built from V_PURCHASE_PAYABLE
     * and V_SUPPLIER_PAYMENT_LINE, the same sources the Supplier Payment screen
     * reads, so none of the three can drift apart.
     */
    @Query(value = """
           SELECT a.SUPPLIER_NAME, a.MOBILE_NO,
                  a.INVOICES, a.BILLED, a.PAID, a.RETURN_CREDIT, a.OUTSTANDING,
                  a.DUE_0_30, a.DUE_31_60, a.DUE_61_90, a.DUE_90_PLUS,
                  a.LAST_PURCHASE, a.LAST_PAYMENT_DATE, a.ON_ACCOUNT,
                  a.ADVANCE_BALANCE, a.NET_POSITION, a.UNAPPLIED_CREDIT
             FROM PHARMA.V_SUPPLIER_ACCOUNT_BRANCH a
            WHERE a.PID_ORG = :orgId
              AND (CAST(:branchId AS bigint) IS NULL OR a.PID_BRANCH = :branchId)
              AND (:q = '' OR LOWER(a.SUPPLIER_NAME) LIKE LOWER('%' || :q || '%'))
              -- Either direction. A supplier holding an advance belongs on this
              -- report as much as one we owe: both are open positions, and
              -- filtering on OUTSTANDING alone hides every rebate not yet back.
              AND (a.OUTSTANDING > 0 OR a.ADVANCE_BALANCE > 0)
            ORDER BY a.OUTSTANDING DESC
           """, nativeQuery = true)
    List<Object[]> supplierOutstanding(@Param("orgId") Long orgId,
                                       @Param("branchId") Long branchId,
                                       @Param("q") String q);

    /**
     * Advances by voucher — what was handed over beyond the bill, and what has
     * come back.
     *
     * <p>Listed per voucher rather than netted per supplier, because the
     * question this answers is which specific payments carried a rebate that is
     * still owed, and a single net figure cannot be chased with a supplier.
     */
    @Query(value = """
           SELECT l.PAYMENT_DATE, l.VOUCHER_NO, s.SUPPLIER_NAME, t.INVOICE_NO,
                  CASE WHEN l.ENTRY_TYPE = 'ADVANCE_RECOVERY' THEN 'RECOVERY' ELSE 'ADVANCE' END AS kind,
                  l.PAYMENT_MODE,
                  CASE WHEN l.ENTRY_TYPE = 'PAYMENT' THEN l.ADVANCE_AMOUNT ELSE 0 END AS advanced,
                  CASE WHEN l.ENTRY_TYPE = 'ADVANCE_RECOVERY' THEN l.AMOUNT ELSE 0 END AS recovered,
                  l.REMARKS
             FROM PHARMA.V_SUPPLIER_PAYMENT_LINE l
             JOIN PHARMA.POS_SUPPLIER_INFO s ON s.PID_SUPPLIER = l.PID_SUPPLIER
             LEFT JOIN PHARMA.POS_TRANSACTION_MST t ON t.PID_TRAN_MST = l.PID_TRAN_MST
            WHERE l.PID_ORG = :orgId
              AND (l.ADVANCE_AMOUNT > 0 OR l.ENTRY_TYPE = 'ADVANCE_RECOVERY')
              AND (CAST(:branchId AS bigint) IS NULL OR l.PID_BRANCH = :branchId)
              AND (CAST(:supplierId AS bigint) IS NULL OR l.PID_SUPPLIER = :supplierId)
              AND (CAST(:from AS date) IS NULL OR l.PAYMENT_DATE >= :from)
              AND (CAST(:to   AS date) IS NULL OR l.PAYMENT_DATE <= :to)
              AND (:q = '' OR LOWER(s.SUPPLIER_NAME) LIKE LOWER('%' || :q || '%'))
            ORDER BY l.PAYMENT_DATE DESC, l.PID_PAYMENT DESC
           """, nativeQuery = true)
    List<Object[]> supplierAdvanceRegister(@Param("orgId") Long orgId,
                                           @Param("branchId") Long branchId,
                                           @Param("supplierId") Long supplierId,
                                           @Param("from") LocalDate from,
                                           @Param("to") LocalDate to,
                                           @Param("q") String q);

    /**
     * The supplier payment register — money out, by voucher.
     *
     * <p>Cancelled vouchers are listed at zero with their reason rather than
     * dropped. A payments report that silently omitted them would not reconcile
     * against a bank statement where the entry was made and then reversed.
     */
    @Query(value = """
           SELECT p.VOUCHER_NO, p.PAYMENT_DATE, s.SUPPLIER_NAME,
                  t.INVOICE_NO, p.PAYMENT_MODE,
                  COALESCE(p.CHEQUE_NO, p.REFERENCE_NO) AS ref,
                  CASE WHEN p.ENTRY_TYPE = 'ADVANCE_RECOVERY' THEN 'RECOVERY' ELSE 'PAYMENT' END AS kind,
                  CASE WHEN p.STATUS = 'C' THEN 'CANCELLED' ELSE 'LIVE' END AS state,
                  -- Negative for a recovery: this column has to total to net
                  -- cash out to be worth reconciling against a bank statement,
                  -- and an advance coming back is money arriving.
                  CASE WHEN p.STATUS = 'C' THEN 0
                       WHEN p.ENTRY_TYPE = 'ADVANCE_RECOVERY' THEN -p.AMOUNT
                       ELSE p.AMOUNT END AS amount,
                  COALESCE(p.CANCEL_REASON, p.REMARKS) AS note
             FROM PHARMA.POS_SUPPLIER_PAYMENT p
             JOIN PHARMA.POS_SUPPLIER_INFO s ON s.PID_SUPPLIER = p.PID_SUPPLIER
             LEFT JOIN PHARMA.POS_TRANSACTION_MST t ON t.PID_TRAN_MST = p.PID_TRAN_MST
            WHERE p.PID_ORG = :orgId
              AND (CAST(:branchId AS bigint) IS NULL OR p.PID_BRANCH = :branchId)
              AND (CAST(:supplierId AS bigint) IS NULL OR p.PID_SUPPLIER = :supplierId)
              AND (CAST(:from AS date) IS NULL OR p.PAYMENT_DATE >= :from)
              AND (CAST(:to   AS date) IS NULL OR p.PAYMENT_DATE <= :to)
              AND (:q = ''
                   OR LOWER(p.VOUCHER_NO)    LIKE LOWER('%' || :q || '%')
                   OR LOWER(s.SUPPLIER_NAME) LIKE LOWER('%' || :q || '%'))
            ORDER BY p.PAYMENT_DATE DESC, p.PID_PAYMENT DESC
           """, nativeQuery = true)
    List<Object[]> supplierPaymentRegister(@Param("orgId") Long orgId,
                                           @Param("branchId") Long branchId,
                                           @Param("supplierId") Long supplierId,
                                           @Param("from") LocalDate from,
                                           @Param("to") LocalDate to,
                                           @Param("q") String q);

    @Query(value = """
           SELECT p.PRODUCT_NAME,
                  p.SKU,
                  co.COMPANY_NAME,
                  SUM(d.QUANTITY)                      AS qty,
                  SUM(COALESCE(d.TOTAL_PRICE, 0))      AS cost,
                  COUNT(DISTINCT t.PID_TRAN_MST)       AS invoices
             FROM PHARMA.POS_TRANSACTION_DTL d
             JOIN PHARMA.POS_TRANSACTION_MST t  ON t.PID_TRAN_MST = d.PID_TRAN_MST
             JOIN PHARMA.POS_PRODUCT_INFO   p   ON p.PID_PRODUCT  = d.PID_PRODUCT
             LEFT JOIN PHARMA.POS_COMPANY_INFO co ON co.PID_COMPANY = p.PID_COMPANY
            WHERE t.PID_ORG          = :orgId
              AND t.TRANSACTION_TYPE = 'PU'
              -- Approved only. A draft purchase has received no stock, and
              -- counting it as spend overstates what was actually bought.
              AND t.STATUS           = 'A'
              AND (CAST(:branchId AS bigint) IS NULL OR t.PID_BRANCH  = :branchId)
              AND (CAST(:companyId AS bigint) IS NULL OR p.PID_COMPANY = :companyId)
              AND (CAST(:from AS date) IS NULL OR t.TRANSACTION_DATE >= :from)
              AND (CAST(:to   AS date) IS NULL OR t.TRANSACTION_DATE <= :to)
              AND (:q = ''
                   OR LOWER(p.PRODUCT_NAME) LIKE LOWER('%' || :q || '%')
                   OR LOWER(p.SKU)          LIKE LOWER('%' || :q || '%'))
            GROUP BY p.PRODUCT_NAME, p.SKU, co.COMPANY_NAME
            ORDER BY cost DESC
           """, nativeQuery = true)
    List<Object[]> purchaseByProduct(@Param("orgId") Long orgId,
                                     @Param("branchId") Long branchId,
                                     @Param("companyId") Long companyId,
                                     @Param("from") LocalDate from,
                                     @Param("to") LocalDate to,
                                     @Param("q") String q);

    /* ══════════════════════════════════════════════════════════════════════
       FINANCE
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * Daily profit: revenue as invoiced against the cost of the batches actually
     * consumed, paired through the movement ledger.
     *
     * <p>Cost comes from POS_STOCK_MOVEMENT rather than from the product's list
     * price, because that is the only record of which lots a sale really took
     * and what each of them cost. Sales returns net off both sides.
     */
    @Query(value = """
           SELECT m.MOVEMENT_DATE                                        AS day,
                  COUNT(DISTINCT m.REF_MST) FILTER (WHERE m.MOVEMENT_TYPE = 'SALE') AS invoices,
                  SUM(CASE WHEN m.MOVEMENT_TYPE = 'SALE'         THEN  m.QTY_PCS
                           WHEN m.MOVEMENT_TYPE = 'SALES_RETURN' THEN -m.QTY_PCS
                           ELSE 0 END)                                   AS pcs,
                  SUM(CASE WHEN m.MOVEMENT_TYPE = 'SALE'         THEN  COALESCE(d.TOTAL_PRICE, 0)
                           WHEN m.MOVEMENT_TYPE = 'SALES_RETURN' THEN -COALESCE(d.TOTAL_PRICE, 0)
                           ELSE 0 END)                                   AS revenue,
                  SUM(CASE WHEN m.MOVEMENT_TYPE = 'SALE'         THEN  m.QTY_PCS * COALESCE(m.COST_PER_PCS, 0)
                           WHEN m.MOVEMENT_TYPE = 'SALES_RETURN' THEN -m.QTY_PCS * COALESCE(m.COST_PER_PCS, 0)
                           ELSE 0 END)                                   AS cogs
             FROM PHARMA.POS_STOCK_MOVEMENT m
             JOIN PHARMA.POS_TRANSACTION_DTL d ON d.PID_TRAN_DTL = m.REF_DTL
            WHERE m.PID_ORG = :orgId
              AND m.MOVEMENT_TYPE IN ('SALE', 'SALES_RETURN')
              AND (CAST(:branchId AS bigint) IS NULL OR m.PID_BRANCH = :branchId)
              AND (CAST(:from AS date) IS NULL OR m.MOVEMENT_DATE >= :from)
              AND (CAST(:to   AS date) IS NULL OR m.MOVEMENT_DATE <= :to)
            GROUP BY m.MOVEMENT_DATE
            ORDER BY m.MOVEMENT_DATE DESC
           """, nativeQuery = true)
    List<Object[]> dailyProfit(@Param("orgId") Long orgId,
                               @Param("branchId") Long branchId,
                               @Param("from") LocalDate from,
                               @Param("to") LocalDate to);

    /**
     * Profit per customer for a period.
     *
     * <p>The same pairing as {@code PosStockMovementRepository.profitByProduct}
     * — revenue from the sale line less its share of the invoice discount, cost
     * from the batch the line actually consumed — grouped by the customer on the
     * invoice instead of the product. Returns are netted off both sides, so a
     * customer who returns half of what they buy shows the margin they really
     * leave behind.
     *
     * <p>Inner join to the line for the same reason as the product query: a
     * movement whose line was rewritten by an edit belongs to no invoice.
     *
     * <p>Columns: customer, type, invoices, netPcs, revenue, discount, cost.
     */
    @Query(value = """
           SELECT c.CUSTOMER_NAME,
                  c.CUSTOMER_TYPE,
                  COUNT(DISTINCT m.REF_MST) FILTER (WHERE m.MOVEMENT_TYPE = 'SALE')          AS invoices,
                  SUM(CASE WHEN m.MOVEMENT_TYPE = 'SALE' THEN m.QTY_PCS ELSE -m.QTY_PCS END)  AS net_pcs,
                  SUM(CASE WHEN m.MOVEMENT_TYPE = 'SALE'
                           THEN  COALESCE(d.TOTAL_PRICE, 0) - ROUND(COALESCE(d.TOTAL_PRICE, 0) * COALESCE(t.INVOICE_DIS_PCT, 0) / 100, 2)
                           ELSE -(COALESCE(d.TOTAL_PRICE, 0) - ROUND(COALESCE(d.TOTAL_PRICE, 0) * COALESCE(t.INVOICE_DIS_PCT, 0) / 100, 2)) END) AS revenue,
                  SUM(CASE WHEN m.MOVEMENT_TYPE = 'SALE'
                           THEN  ROUND(COALESCE(d.TOTAL_PRICE, 0) * COALESCE(t.INVOICE_DIS_PCT, 0) / 100, 2)
                           ELSE -ROUND(COALESCE(d.TOTAL_PRICE, 0) * COALESCE(t.INVOICE_DIS_PCT, 0) / 100, 2) END) AS discount,
                  SUM(CASE WHEN m.MOVEMENT_TYPE = 'SALE'
                           THEN  m.QTY_PCS * COALESCE(m.COST_PER_PCS, 0)
                           ELSE -m.QTY_PCS * COALESCE(m.COST_PER_PCS, 0) END)               AS cost
             FROM PHARMA.POS_STOCK_MOVEMENT m
             JOIN PHARMA.POS_TRANSACTION_DTL d ON d.PID_TRAN_DTL = m.REF_DTL
             JOIN PHARMA.POS_TRANSACTION_MST t ON t.PID_TRAN_MST = d.PID_TRAN_MST
             JOIN PHARMA.POS_CUSTOMER_INFO   c ON c.PID_CUSTOMER = t.PID_SUP_CUST
            WHERE m.PID_ORG = :orgId
              AND m.MOVEMENT_TYPE IN ('SALE', 'SALES_RETURN')
              AND (CAST(:branchId AS bigint) IS NULL OR m.PID_BRANCH = :branchId)
              AND (CAST(:from AS date) IS NULL OR m.MOVEMENT_DATE >= :from)
              AND (CAST(:to   AS date) IS NULL OR m.MOVEMENT_DATE <= :to)
              AND (:q = '' OR LOWER(c.CUSTOMER_NAME) LIKE LOWER('%' || :q || '%'))
            GROUP BY c.CUSTOMER_NAME, c.CUSTOMER_TYPE
            ORDER BY revenue DESC
           """, nativeQuery = true)
    List<Object[]> profitByCustomer(@Param("orgId") Long orgId,
                                    @Param("branchId") Long branchId,
                                    @Param("from") LocalDate from,
                                    @Param("to") LocalDate to,
                                    @Param("q") String q);

    /** The same figures rolled up to calendar months. */
    @Query(value = """
           SELECT TO_CHAR(m.MOVEMENT_DATE, 'YYYY-MM')                    AS month,
                  COUNT(DISTINCT m.REF_MST) FILTER (WHERE m.MOVEMENT_TYPE = 'SALE') AS invoices,
                  SUM(CASE WHEN m.MOVEMENT_TYPE = 'SALE'         THEN  COALESCE(d.TOTAL_PRICE, 0)
                           WHEN m.MOVEMENT_TYPE = 'SALES_RETURN' THEN -COALESCE(d.TOTAL_PRICE, 0)
                           ELSE 0 END)                                   AS revenue,
                  SUM(CASE WHEN m.MOVEMENT_TYPE = 'SALE'         THEN  m.QTY_PCS * COALESCE(m.COST_PER_PCS, 0)
                           WHEN m.MOVEMENT_TYPE = 'SALES_RETURN' THEN -m.QTY_PCS * COALESCE(m.COST_PER_PCS, 0)
                           ELSE 0 END)                                   AS cogs
             FROM PHARMA.POS_STOCK_MOVEMENT m
             JOIN PHARMA.POS_TRANSACTION_DTL d ON d.PID_TRAN_DTL = m.REF_DTL
            WHERE m.PID_ORG = :orgId
              AND m.MOVEMENT_TYPE IN ('SALE', 'SALES_RETURN')
              AND (CAST(:branchId AS bigint) IS NULL OR m.PID_BRANCH = :branchId)
              AND (CAST(:from AS date) IS NULL OR m.MOVEMENT_DATE >= :from)
              AND (CAST(:to   AS date) IS NULL OR m.MOVEMENT_DATE <= :to)
            GROUP BY TO_CHAR(m.MOVEMENT_DATE, 'YYYY-MM')
            ORDER BY month DESC
           """, nativeQuery = true)
    List<Object[]> monthlyProfit(@Param("orgId") Long orgId,
                                 @Param("branchId") Long branchId,
                                 @Param("from") LocalDate from,
                                 @Param("to") LocalDate to);

    /* ══════════════════════════════════════════════════════════════════════
       COLLECTION
       ══════════════════════════════════════════════════════════════════════ */

    /** What was billed and what came back in, per day. */
    @Query(value = """
           SELECT t.TRANSACTION_DATE,
                  COUNT(*)                                          AS invoices,
                  SUM(COALESCE(t.AMOUNT_TOBE_PAID, 0))              AS billed,
                  SUM(LEAST(COALESCE(t.GIVEN_AMOUNT,0), COALESCE(t.AMOUNT_TOBE_PAID,0))) AS collected,
                  SUM(GREATEST(ROUND(COALESCE(t.AMOUNT_TOBE_PAID,0),2) - ROUND(COALESCE(t.GIVEN_AMOUNT,0),2), 0)) AS outstanding
             FROM PHARMA.POS_TRANSACTION_MST t
            WHERE t.PID_ORG          = :orgId
              AND t.TRANSACTION_TYPE = 'SL'
              AND COALESCE(t.STATUS, 'Y') <> 'N'
              -- A merge is a grouping document over orders that are already
              -- counted here. Including it would report the same money twice.
              AND COALESCE(t.IS_MERGE_INVOICE, 'N') <> 'Y'
              AND t.STAGE           <> 'Submitted'
              AND (CAST(:branchId AS bigint) IS NULL OR t.PID_BRANCH = :branchId)
              AND (CAST(:from AS date) IS NULL OR t.TRANSACTION_DATE >= :from)
              AND (CAST(:to   AS date) IS NULL OR t.TRANSACTION_DATE <= :to)
            GROUP BY t.TRANSACTION_DATE
            ORDER BY t.TRANSACTION_DATE DESC
           """, nativeQuery = true)
    List<Object[]> collectionSummary(@Param("orgId") Long orgId,
                                     @Param("branchId") Long branchId,
                                     @Param("from") LocalDate from,
                                     @Param("to") LocalDate to);

    /**
     * How many customers owe money, and how much, in one branch.
     *
     * <p>For the notification bell, which needs the count and not the rows. The
     * due formula is character for character the one in {@link #salesByCustomer},
     * so the alert and the Customer Due report can never disagree about who is
     * behind — an alert that does not reconcile with the report it links to is
     * worse than no alert.
     */
    @Query(value = """
           SELECT COUNT(*), COALESCE(SUM(due), 0)
             FROM (SELECT t.PID_SUP_CUST,
                          SUM(GREATEST(COALESCE(t.AMOUNT_TOBE_PAID,0)
                                     - COALESCE(t.GIVEN_AMOUNT,0), 0)) AS due
                     FROM PHARMA.POS_TRANSACTION_MST t
                    WHERE t.PID_ORG          = :orgId
                      AND t.TRANSACTION_TYPE = 'SL'
                      AND COALESCE(t.STATUS, 'Y') <> 'N'
                      AND COALESCE(t.IS_MERGE_INVOICE, 'N') <> 'Y'
                      AND t.STAGE           <> 'Submitted'
                      AND (CAST(:branchId AS bigint) IS NULL OR t.PID_BRANCH = :branchId)
                    GROUP BY t.PID_SUP_CUST) x
            WHERE x.due > 0
           """, nativeQuery = true)
    Object customerDueTotals(@Param("orgId") Long orgId, @Param("branchId") Long branchId);
}
