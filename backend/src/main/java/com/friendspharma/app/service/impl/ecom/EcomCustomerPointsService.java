package com.friendspharma.app.service.impl.ecom;

import com.friendspharma.app.dto.response.EcomPointsResponses;
import com.friendspharma.app.entity.EcomPointLedger;
import com.friendspharma.app.repository.EcomOrderRepository;
import com.friendspharma.app.repository.EcomPointLedgerRepository;
import com.friendspharma.app.security.ecom.EcomCustomerPrincipal;
import com.friendspharma.app.security.ecom.EcomSecurity;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * A customer's own points, as a statement rather than a number
 * (docs/ecommerce Phase 13).
 *
 * <p>Every line says where it came from — an order, an expiry, somebody at the
 * pharmacy moving it by hand — and what the balance became. A balance without
 * its history is a figure a customer cannot argue with, which is the one thing
 * a loyalty balance must never be.
 */
@Service
@RequiredArgsConstructor
public class EcomCustomerPointsService {

    private final EcomPointLedgerRepository ledger;
    private final EcomOrderRepository orders;
    private final EcomPointsService points;

    @Transactional(readOnly = true)
    public EcomPointsResponses.Statement statement(int size) {
        EcomCustomerPrincipal p = EcomSecurity.require();
        BigDecimal balance = points.balanceOf(p.customerId());
        List<EcomPointsResponses.Entry> entries = entriesFor(p.customerId(), size);

        /* What a point is worth depends on where they spend it, so the
           statement does not promise a rate. The cart says what the points
           would do at that pharmacy, which is the only place the question has
           an answer. */
        return EcomPointsResponses.Statement.builder()
                .balance(balance)
                .note(balance.signum() > 0
                        ? "What your points are worth depends on the pharmacy — your cart shows it before you order."
                        : entries.isEmpty()
                        ? "You have no points yet. Pharmacies that run a points scheme give you points when an order arrives."
                        : "Nothing left just now — below is where your points went.")
                .entries(entries)
                .build();
    }

    /**
     * The same statement, read by somebody at the pharmacy.
     *
     * <p>Staff are shown the history rather than a single figure for the same
     * reason the customer is: an adjustment made by hand is a movement somebody
     * has to be able to point at afterwards.
     */
    @Transactional(readOnly = true)
    public EcomPointsResponses.Statement statementFor(Long customerId, int size) {
        BigDecimal balance = points.balanceOf(customerId);
        List<EcomPointsResponses.Entry> entries = entriesFor(customerId, size);
        return EcomPointsResponses.Statement.builder()
                .balance(balance)
                .note(balance.signum() > 0
                        ? "Every movement below was written to the ledger; the balance is its sum."
                        : entries.isEmpty()
                        ? "This customer has no points."
                        : "The balance is nil: everything earned has been spent or has expired.")
                .entries(entries)
                .build();
    }

    private List<EcomPointsResponses.Entry> entriesFor(Long customerId, int size) {
        int n = Math.min(Math.max(size, 1), 100);
        return ledger.findByPidEcomCustomerOrderByPidPointEntryDesc(customerId, PageRequest.of(0, n))
                .getContent().stream().map(this::entry).toList();
    }

    private EcomPointsResponses.Entry entry(EcomPointLedger l) {
        return EcomPointsResponses.Entry.builder()
                .type(l.getEntryType())
                .points(l.getPoints())
                .balanceAfter(l.getBalanceAfter())
                .valueAmount(l.getValueAmount())
                .orderNo(l.getPidEcomOrder() == null ? null
                        : orders.findById(l.getPidEcomOrder()).map(o -> o.getOrderNo()).orElse(null))
                .note(l.getNote())
                .at(l.getCreatedAt())
                .build();
    }
}
