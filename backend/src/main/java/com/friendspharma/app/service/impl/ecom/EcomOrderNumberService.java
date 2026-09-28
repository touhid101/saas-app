package com.friendspharma.app.service.impl.ecom;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The customer-facing order number: {@code ECOM-2609-001042}.
 *
 * <p>From a database sequence, not from random digits and not from a count of
 * rows. An order number is read out on the phone and written on a parcel; two
 * orders sharing one would be a real problem, and a sequence is the only way to
 * be sure under concurrency. ({@code POS_TRANSACTION_MST.INVOICE_NO} is five
 * random digits and can collide — see docs/ecommerce 11 §2 — which is exactly
 * why the marketplace does not reuse it.)
 *
 * <p>Drawn in its own transaction: a number handed out and then rolled back is
 * a gap, which is harmless, while two callers waiting on each other's sequence
 * would not be.
 */
@Service
@RequiredArgsConstructor
public class EcomOrderNumberService {

    @PersistenceContext
    private EntityManager em;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String next() {
        Number n = (Number) em.createNativeQuery("SELECT nextval('pharma.seq_ecom_order_no')").getSingleResult();
        java.time.LocalDate today = java.time.LocalDate.now();
        return String.format("ECOM-%02d%02d-%06d", today.getYear() % 100, today.getMonthValue(), n.longValue());
    }
}
