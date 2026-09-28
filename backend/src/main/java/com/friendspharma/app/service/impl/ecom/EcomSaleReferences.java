package com.friendspharma.app.service.impl.ecom;

import com.friendspharma.app.entity.EcomOrder;
import com.friendspharma.app.repository.EcomOrderRepository;
import com.friendspharma.app.service.interfaces.SaleSourceReferences;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Tells the ERP which of its invoices are marketplace orders, and what the
 * marketplace knows about them that the ERP does not: the customer's order
 * number, the pin on the map, and the number to ring at the door.
 *
 * <p>One query for a whole page of invoices, not one per row: Sales Activity
 * and Delivery render 25 at a time and a lookup per row would be 25 round trips
 * to answer a question most of them answer with "none".
 */
@Service
@RequiredArgsConstructor
public class EcomSaleReferences implements SaleSourceReferences {

    private final EcomOrderRepository orders;

    @Override
    @Transactional(readOnly = true)
    public Map<Long, Ref> referencesFor(Collection<Long> tranMstIds) {
        List<Long> ids = tranMstIds == null ? List.of()
                : tranMstIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();

        return orders.findByPidTranMstIn(ids).stream().collect(Collectors.toMap(
                EcomOrder::getPidTranMst,
                o -> new Ref(o.getOrderNo(), o.getDeliveryLatitude(), o.getDeliveryLongitude(),
                             o.getDeliveryPhone(), o.getDeliveryRecipient()),
                (a, b) -> a));
    }
}
