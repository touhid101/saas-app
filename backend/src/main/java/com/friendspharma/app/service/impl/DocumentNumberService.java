package com.friendspharma.app.service.impl;

import com.friendspharma.app.exception.custom.BusinessException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Generates the document numbers a purchase needs.
 *
 * <p>Port of the Oracle {@code FN_INVOICE_NO_GEN} function. The format is
 * unchanged — {@code <TYPE><YYMM><5-digit counter>}, e.g. {@code PU260800001} —
 * and the counter still resets on a year-month boundary.
 *
 * <p><b>How this differs from the Oracle original, and why.</b> The PL/SQL
 * version resets by running {@code ALTER SEQUENCE ... RESTART} inside a
 * {@code PRAGMA AUTONOMOUS_TRANSACTION}. That is not safe to reproduce here:
 * runtime DDL is not transactional, and two callers crossing the month boundary
 * at the same moment would both attempt the reset, with the second able to undo
 * the first's allocation. Instead the counter lives in {@code SEQUENCE_TRACKER}
 * and is advanced under a {@code SELECT ... FOR UPDATE} row lock, which gives
 * the same month-reset behaviour with a real concurrency guarantee.
 *
 * <p>{@link Propagation#REQUIRES_NEW} reproduces the autonomous-transaction
 * semantics: the number is committed the moment it is issued, so it is consumed
 * even if the purchase that asked for it later fails. That trades occasional
 * gaps for never handing the same number to two invoices — the right way round
 * for a document number, and the same trade a database sequence makes.
 */
@Slf4j
@Service
public class DocumentNumberService {

    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyMM");
    private static final String INVOICE_SEQ = "SEQ_INVOICE_NO";

    @PersistenceContext
    private EntityManager em;

    /**
     * Next invoice number for a transaction type.
     *
     * @param transactionType e.g. "PU" for purchase — becomes the prefix
     * @return {@code PU} + {@code yyMM} + counter padded to 5 digits
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String nextInvoiceNo(String transactionType) {
        if (transactionType == null || transactionType.isBlank()) {
            throw new BusinessException("Transaction type is required to generate an invoice number.",
                    HttpStatus.BAD_REQUEST, "MISSING_TRANSACTION_TYPE");
        }

        String yearMonth = LocalDate.now().format(YEAR_MONTH);
        long next = nextCounter(INVOICE_SEQ, yearMonth);

        return transactionType.toUpperCase() + yearMonth + String.format("%05d", next);
    }

    /**
     * Next number in a named monthly series: {@code <prefix><yyMM><00000>}.
     *
     * <p>The Accounts module's journals (JV), cash vouchers (CV), investor
     * transactions (IV) and profit distributions (PD) each keep their own row
     * in the tracker (V90), so a busy sales month never advances the journal
     * counter and a backfill of a hundred thousand journals never advances the
     * invoice counter. Same lock, same reset rule as {@link #nextInvoiceNo}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String nextNumber(String seqName, String prefix) {
        String yearMonth = LocalDate.now().format(YEAR_MONTH);
        long next = nextCounter(seqName, yearMonth);
        return prefix + yearMonth + String.format("%05d", next);
    }

    /**
     * Next purchase batch reference — a continuous 6-digit series that does not
     * reset monthly, so a batch number stays unique for the life of the system.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String nextBatchNo() {
        Object value = em.createNativeQuery("SELECT nextval('PHARMA.SEQ_BATCH_NO')").getSingleResult();
        return String.format("%06d", Long.parseLong(value.toString()));
    }

    /**
     * Advances one tracked counter, resetting it when the year-month changes.
     *
     * <p>The row lock is taken before the year-month is compared, so the check
     * and the update are a single atomic step — without it, two callers could
     * both read the old month and both reset to 1.
     */
    private long nextCounter(String seqName, String yearMonth) {
        Object[] row;
        try {
            row = (Object[]) em.createNativeQuery(
                            "SELECT LAST_YEAR_MONTH, LAST_VALUE FROM PHARMA.SEQUENCE_TRACKER " +
                            "WHERE SEQ_NAME = :name FOR UPDATE")
                    .setParameter("name", seqName)
                    .getSingleResult();
        } catch (jakarta.persistence.NoResultException e) {
            throw new BusinessException(
                    "Number series '" + seqName + "' is not configured. Run migration V5.",
                    HttpStatus.INTERNAL_SERVER_ERROR, "SEQUENCE_NOT_CONFIGURED");
        }

        String lastYearMonth = row[0] != null ? row[0].toString() : null;
        long lastValue = row[1] != null ? Long.parseLong(row[1].toString()) : 0L;

        long next = yearMonth.equals(lastYearMonth) ? lastValue + 1 : 1L;
        if (next == 1L && !yearMonth.equals(lastYearMonth)) {
            log.info("Number series {} rolled over to {} — counter reset", seqName, yearMonth);
        }

        em.createNativeQuery(
                        "UPDATE PHARMA.SEQUENCE_TRACKER " +
                        "SET LAST_YEAR_MONTH = :ym, LAST_VALUE = :val, UPDATED_DATE = CURRENT_TIMESTAMP " +
                        "WHERE SEQ_NAME = :name")
                .setParameter("ym", yearMonth)
                .setParameter("val", next)
                .setParameter("name", seqName)
                .executeUpdate();

        return next;
    }
}
