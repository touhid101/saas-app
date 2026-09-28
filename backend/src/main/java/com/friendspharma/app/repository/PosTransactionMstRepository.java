package com.friendspharma.app.repository;

import com.friendspharma.app.entity.PosTransactionDtl;
import com.friendspharma.app.entity.PosTransactionMst;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface PosTransactionMstRepository
        extends JpaRepository<PosTransactionMst, Long>, JpaSpecificationExecutor<PosTransactionMst> {

    Optional<PosTransactionMst> findByInvoiceNoAndTransactionType(String invoiceNo, String type);

    Page<PosTransactionMst> findByPidOrgAndTransactionTypeAndStatusNot(
            Long orgId, String type, String status, Pageable pageable);

    List<PosTransactionMst> findByPidOrgAndTransactionTypeAndTransactionDateBetween(
            Long orgId, String type, LocalDate from, LocalDate to);

    /**
     * Purchases whose advance was carved from the whole discount.
     *
     * <p>"Pay the invoice discount too" once took the total discount (line
     * discounts included) as the advance. The signature of such a row is an
     * advance equal to the total discount on an invoice where the total is
     * larger than the invoice cut. A row where the two discounts are equal
     * carries the right advance already and is not returned; one with no
     * invoice discount is not returned either — there is nothing to restate
     * it to, and whether that money was an advance at all is a business call.
     */
    @Query("SELECT t FROM PosTransactionMst t WHERE t.pidOrg = :orgId AND t.transactionType = :type " +
           "AND t.advanceAmount > 0 AND t.invoiceDisAmt > 0 " +
           "AND t.totalDiscount > t.invoiceDisAmt " +
           "AND ABS(t.advanceAmount - t.totalDiscount) < 0.005 " +
           "ORDER BY t.pidTranMst")
    List<PosTransactionMst> findAdvanceCarvedFromTotalDiscount(@Param("orgId") Long orgId,
                                                               @Param("type") String type);

    @Query("SELECT t FROM PosTransactionMst t WHERE t.pidOrg = :orgId " +
           "AND t.transactionType = :type AND t.transactionDate BETWEEN :from AND :to " +
           "AND (:branchId IS NULL OR t.pidBranch = :branchId)")
    List<PosTransactionMst> findByOrgTypeDateBranch(@Param("orgId") Long orgId,
                                                     @Param("type") String type,
                                                     @Param("from") LocalDate from,
                                                     @Param("to") LocalDate to,
                                                     @Param("branchId") Long branchId);

    /**
     * A customer's orders that may still be merged.
     *
     * <p>Three conditions, each removing a way of merging something that should
     * not be: still Submitted (a confirmed order is already promised to the
     * customer), dated today (an earlier one belongs to a round already run),
     * and not already inside a merge.
     */
    @Query("""
           SELECT t FROM PosTransactionMst t
            WHERE t.pidOrg          = :orgId
              AND t.transactionType = 'SL'
              AND t.pidSupCust      = :custId
              AND t.stage           = :stage
              AND t.transactionDate = :onDate
              AND t.mergePidTranMst IS NULL
              AND t.isMergeInvoice  IS NULL
              AND t.status <> 'N'
            ORDER BY t.pidTranMst
           """)
    List<PosTransactionMst> findMergeCandidates(@Param("orgId") Long orgId,
                                                @Param("custId") Long custId,
                                                @Param("stage") String stage,
                                                @Param("onDate") LocalDate onDate);

    /** The orders sitting inside one merge. */
    List<PosTransactionMst> findByMergePidTranMst(Long mergePidTranMst);

    /** The grouping documents themselves, newest first. */
    @Query("""
           SELECT t FROM PosTransactionMst t
            WHERE t.pidOrg         = :orgId
              AND t.isMergeInvoice = 'Y'
              AND t.transactionDate >= :from
              AND t.transactionDate <= :to
            ORDER BY t.pidTranMst DESC
           """)
    List<PosTransactionMst> findMergedInvoices(@Param("orgId") Long orgId,
                                               @Param("from") LocalDate from,
                                               @Param("to") LocalDate to);

    /**
     * The Sales Activity list.
     *
     * <p>Every filter the screen offers is applied here rather than to the page
     * of rows that comes back. Filtering client-side would narrow only the
     * current page, so a search would silently miss matches on page two and the
     * result counts would be wrong.
     *
     * <p>Strings use '' as "no filter" rather than null: PostgreSQL cannot infer
     * the type of a null parameter inside LOWER(), and types it as bytea.
     *
     * <p>{@code includeVoid} decides whether cancelled documents (STATUS = 'N')
     * are listed. The activity board leaves them out, because a chip reading
     * "All 402" over a table of 405 rows is a contradiction, and asks for them
     * by name through the Cancelled filter. The invoice list shows them: a
     * cancelled invoice still has a number somebody may be searching for.
     *
     * <p>{@code hideMerged} drops orders that have been folded into a merge.
     * They are delivered and collected under that merge, so listing them here
     * as well would put the same goods on two rounds; the merge stands in
     * their place. The invoice list passes false and shows everything.
     */
    @Query("""
           SELECT t FROM PosTransactionMst t
            WHERE t.pidOrg          = :orgId
              AND t.transactionType = 'SL'
              AND (:stage    = '' OR t.stage         = :stage)
              AND (:payStatus = '' OR t.paymentStatus = :payStatus)
              AND (:branchId IS NULL OR t.pidBranch     = :branchId)
              AND (:customerId IS NULL OR t.pidSupCust  = :customerId)
              AND (:dmId     IS NULL OR t.deliveryMan   = :dmId)
              AND (:unassigned = FALSE OR t.deliveryMan IS NULL)
              AND (:hideMerged = FALSE OR t.mergePidTranMst IS NULL)
              AND (:source = '' OR COALESCE(t.fromSource, 'API') = :source)
              AND (:includeVoid = TRUE OR COALESCE(t.status, 'Y') <> 'N')
              AND t.transactionDate >= :from
              AND t.transactionDate <= :to
              AND (:q = ''
                   OR LOWER(t.invoiceNo)       LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(t.deliveryManName) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR EXISTS (SELECT 1 FROM PosCustomerInfo c
                               WHERE c.pidCustomer = t.pidSupCust
                                 AND LOWER(c.customerName) LIKE LOWER(CONCAT('%', :q, '%'))))
            ORDER BY t.transactionDate DESC, t.pidTranMst DESC
           """)
    Page<PosTransactionMst> findSalesActivity(@Param("orgId") Long orgId,
                                              @Param("stage") String stage,
                                              @Param("payStatus") String payStatus,
                                              @Param("branchId") Long branchId,
                                              @Param("customerId") Long customerId,
                                              @Param("dmId") Long dmId,
                                              @Param("unassigned") boolean unassigned,
                                              @Param("hideMerged") boolean hideMerged,
                                              @Param("source") String source,
                                              @Param("includeVoid") boolean includeVoid,
                                              @Param("from") LocalDate from,
                                              @Param("to") LocalDate to,
                                              @Param("q") String q,
                                              Pageable pageable);

    /** Returns already raised against one sale, newest first. */
    List<PosTransactionMst> findByPidTranMstReturnOrderByPidTranMstDesc(Long pidTranMstReturn);

    /**
     * Every unpaid invoice a customer has with this branch, newest first.
     *
     * <p>For the statement printed at the foot of an invoice: one row per
     * document rather than a single balance, because "you owe 124,060" invites
     * the question this answers — which invoices, and how old.
     *
     * <p>The invoice being printed is included. It is part of what the customer
     * owes, and leaving it out would make the grand total disagree with the
     * balance due directly above it on the same sheet.
     *
     * @return date, invoice number, invoice total, amount still due
     */
    @Query(value = """
           SELECT t.TRANSACTION_DATE,
                  t.INVOICE_NO,
                  COALESCE(t.AMOUNT_TOBE_PAID, 0),
                  GREATEST(ROUND(COALESCE(t.AMOUNT_TOBE_PAID,0),2) - ROUND(COALESCE(t.GIVEN_AMOUNT,0),2), 0) AS due,
                  t.PID_TRAN_MST
             FROM PHARMA.POS_TRANSACTION_MST t
            WHERE t.PID_ORG          = :orgId
              AND t.PID_BRANCH       = :branchId
              AND t.PID_SUP_CUST     = :customerId
              AND t.TRANSACTION_TYPE = 'SL'
              AND COALESCE(t.IS_MERGE_INVOICE, 'N') <> 'Y'
              AND t.STAGE           <> 'Submitted'
              AND GREATEST(ROUND(COALESCE(t.AMOUNT_TOBE_PAID,0),2) - ROUND(COALESCE(t.GIVEN_AMOUNT,0),2), 0) > 0
            ORDER BY t.TRANSACTION_DATE DESC, t.PID_TRAN_MST DESC
           """, nativeQuery = true)
    List<Object[]> customerOpenInvoices(@Param("orgId") Long orgId,
                                        @Param("branchId") Long branchId,
                                        @Param("customerId") Long customerId);
    /**
     * What one customer still owes this branch, excluding one invoice.
     *
     * <p>For the reminder printed at the foot of an invoice: the customer's
     * balance <em>before</em> this sale, so the document can show what was
     * already outstanding, what this invoice adds, and the total — three
     * separate facts that a single figure would blur.
     *
     * <p>The due formula is character for character the one in the Customer Due
     * report, so the reminder on a printed invoice and the report the shop chases
     * from can never disagree about the same customer.
     *
     * @return one row: open invoices, amount due, oldest unpaid date
     */
    @Query(value = """
           SELECT COUNT(*), COALESCE(SUM(x.due), 0), MIN(x.TRANSACTION_DATE)
             FROM (SELECT t.TRANSACTION_DATE,
                          GREATEST(COALESCE(t.AMOUNT_TOBE_PAID,0)
                                 - COALESCE(t.GIVEN_AMOUNT,0), 0) AS due
                     FROM PHARMA.POS_TRANSACTION_MST t
                    WHERE t.PID_ORG          = :orgId
                      AND t.PID_BRANCH       = :branchId
                      AND t.PID_SUP_CUST     = :customerId
                      AND t.TRANSACTION_TYPE = 'SL'
                      AND COALESCE(t.STATUS, 'Y') <> 'N'
                      AND COALESCE(t.IS_MERGE_INVOICE, 'N') <> 'Y'
                      AND t.STAGE           <> 'Submitted'
                      AND t.PID_TRAN_MST    <> :excludeId) x
            WHERE x.due > 0
           """, nativeQuery = true)
    Object customerOutstanding(@Param("orgId") Long orgId,
                               @Param("branchId") Long branchId,
                               @Param("customerId") Long customerId,
                               @Param("excludeId") Long excludeId);
    /**
     * The sales-return list.
     *
     * <p>Search covers the return document and the invoice it reverses, because
     * that is how anyone looks one up: they have the original invoice number in
     * hand, not the return number the system generated afterwards.
     */
    @Query("""
           SELECT t FROM PosTransactionMst t
            WHERE t.pidOrg          = :orgId
              AND t.transactionType = 'SR'
              AND (:branchId IS NULL OR t.pidBranch = :branchId)
              AND t.transactionDate >= :from
              AND t.transactionDate <= :to
              AND (:q = ''
                   OR LOWER(t.invoiceNo) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR EXISTS (SELECT 1 FROM PosTransactionMst o
                               WHERE o.pidTranMst = t.pidTranMstReturn
                                 AND LOWER(o.invoiceNo) LIKE LOWER(CONCAT('%', :q, '%')))
                   OR EXISTS (SELECT 1 FROM PosCustomerInfo c
                               WHERE c.pidCustomer = t.pidSupCust
                                 AND LOWER(c.customerName) LIKE LOWER(CONCAT('%', :q, '%'))))
            ORDER BY t.transactionDate DESC, t.pidTranMst DESC
           """)
    Page<PosTransactionMst> findSalesReturns(@Param("orgId") Long orgId,
                                             @Param("branchId") Long branchId,
                                             @Param("from") LocalDate from,
                                             @Param("to") LocalDate to,
                                             @Param("q") String q,
                                             Pageable pageable);
    /**
     * The purchase-return list.
     *
     * <p>Search covers the debit note, the purchase it reverses and the supplier,
     * because whoever looks one up has the supplier bill in hand rather than the
     * return number the system generated afterwards.
     */
    @Query("""
           SELECT t FROM PosTransactionMst t
            WHERE t.pidOrg          = :orgId
              AND t.transactionType = 'PR'
              AND t.transactionDate >= :from
              AND t.transactionDate <= :to
              AND (:q = ''
                   OR LOWER(t.invoiceNo) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR EXISTS (SELECT 1 FROM PosTransactionMst o
                               WHERE o.pidTranMst = t.pidTranMstReturn
                                 AND LOWER(o.invoiceNo) LIKE LOWER(CONCAT('%', :q, '%')))
                   OR EXISTS (SELECT 1 FROM PosSupplierInfo s
                               WHERE s.pidSupplier = t.pidSupCust
                                 AND LOWER(s.supplierName) LIKE LOWER(CONCAT('%', :q, '%'))))
            ORDER BY t.transactionDate DESC, t.pidTranMst DESC
           """)
    Page<PosTransactionMst> findPurchaseReturns(@Param("orgId") Long orgId,
                                                @Param("from") LocalDate from,
                                                @Param("to") LocalDate to,
                                                @Param("q") String q,
                                                Pageable pageable);
    /**
     * Delivery performance, one row per delivery man.
     *
     * <p>Aggregated in SQL rather than by walking invoices in Java: a branch
     * accumulates these forever, and the figures are sums over the whole set,
     * not over a page of it.
     *
     * <p>Money collected is capped at the amount billed, per invoice. GIVEN_AMOUNT
     * records what the customer handed over, which at a POS counter is the note
     * they offered — an 8.35 sale settled with a 100 note stores 100, and the
     * other 91.65 went back as change. Summed raw it reported Karim Rider at a
     * 832% collection rate. You cannot collect more than you billed.
     *
     * <p>Counts every invoice ever dispatched to them, so a driver who is now
     * inactive still shows their record. Their history does not stop being
     * theirs when they leave.
     *
     * <p>Orders folded into a merge are left out. They are dispatched under the
     * merge, which carries the driver and the money, so counting them as well
     * would credit one delivery twice.
     */
    @Query("""
           SELECT t.deliveryMan,
                  MIN(t.deliveryManName),
                  COUNT(t),
                  SUM(CASE WHEN t.stage IN ('Delivered', 'Collected') THEN 1 ELSE 0 END),
                  SUM(CASE WHEN t.stage IN ('Delivery Assigned', 'On The Way') THEN 1 ELSE 0 END),
                  COALESCE(SUM(t.amountTobePaid), 0),
                  COALESCE(SUM(
                      CASE WHEN t.givenAmount IS NULL THEN 0
                           WHEN t.givenAmount > t.amountTobePaid THEN t.amountTobePaid
                           ELSE t.givenAmount END), 0)
             FROM PosTransactionMst t
            WHERE t.pidOrg          = :orgId
              AND t.transactionType = 'SL'
              AND COALESCE(t.status, 'Y') <> 'N'
              AND t.deliveryMan IS NOT NULL
              AND t.mergePidTranMst IS NULL
              AND (:branchId IS NULL OR t.pidBranch = :branchId)
              AND t.transactionDate >= :from
              AND t.transactionDate <= :to
            GROUP BY t.deliveryMan
            ORDER BY COUNT(t) DESC
           """)
    List<Object[]> deliveryPerformance(@Param("orgId") Long orgId,
                                       @Param("branchId") Long branchId,
                                       @Param("from") LocalDate from,
                                       @Param("to") LocalDate to);
    /**
     * Counts and money per stage, for the chips and the delivery dashboard.
     *
     * <p>Scoped by driver when one is given. Without that a signed-in driver saw
     * four orders in the table under a chip reading "All 15" — the branch total,
     * which both contradicts the list and discloses how much work exists beyond
     * their own round.
     *
     * <p>Collected is capped at the amount billed per invoice: GIVEN_AMOUNT holds
     * what the customer handed over, and at a POS counter that is the note they
     * offered, not what the business kept.
     *
     * <p>Cancelled documents (STATUS = 'N') are left out. Their stock is back on
     * the shelf and their journals are reversed, so counting them would bill for
     * goods nobody received; {@link #countCancelledSales} reports them separately
     * for the chip that finds them.
     */
    @Query("""
           SELECT t.stage,
                  COUNT(t),
                  COALESCE(SUM(t.amountTobePaid), 0),
                  COALESCE(SUM(
                      CASE WHEN t.givenAmount IS NULL THEN 0
                           WHEN t.givenAmount > t.amountTobePaid THEN t.amountTobePaid
                           ELSE t.givenAmount END), 0)
             FROM PosTransactionMst t
            WHERE t.pidOrg = :orgId AND t.transactionType = 'SL'
              AND t.mergePidTranMst IS NULL
              AND COALESCE(t.status, 'Y') <> 'N'
              AND (:branchId IS NULL OR t.pidBranch   = :branchId)
              AND (:dmId     IS NULL OR t.deliveryMan = :dmId)
            GROUP BY t.stage
           """)
    List<Object[]> countSalesByStage(@Param("orgId") Long orgId,
                                     @Param("branchId") Long branchId,
                                     @Param("dmId") Long dmId);

    /** How many sales were called off — the one bucket the stage counts exclude. */
    @Query("""
           SELECT COUNT(t) FROM PosTransactionMst t
            WHERE t.pidOrg = :orgId AND t.transactionType = 'SL'
              AND t.mergePidTranMst IS NULL
              AND COALESCE(t.status, 'Y') = 'N'
              AND (:branchId IS NULL OR t.pidBranch   = :branchId)
              AND (:dmId     IS NULL OR t.deliveryMan = :dmId)
           """)
    long countCancelledSales(@Param("orgId") Long orgId,
                             @Param("branchId") Long branchId,
                             @Param("dmId") Long dmId);

    /**
     * <p>Merge wrappers are excluded. Their lines are copies of the orders'
     * lines, kept so the merged document can be printed, and summing both sides
     * would report the same goods twice.
     */
    @Query("SELECT COALESCE(SUM(d.totalPrice),0) FROM PosTransactionDtl d " +
           "JOIN PosTransactionMst t ON d.pidTranMst = t.pidTranMst " +
           "WHERE t.pidOrg = :orgId AND t.transactionType = :type " +
           // A cancelled document is void: stock back, journals reversed.
           "AND COALESCE(t.status, 'Y') <> 'N' " +
           "AND (t.isMergeInvoice IS NULL OR t.isMergeInvoice <> 'Y') " +
           "AND t.transactionDate BETWEEN :from AND :to")
    BigDecimal sumAmountByOrgTypeDate(@Param("orgId") Long orgId, @Param("type") String type,
                                       @Param("from") LocalDate from, @Param("to") LocalDate to);
}

// PosTransactionDtlRepository is defined in PosTransactionDtlRepository.java
