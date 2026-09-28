package com.friendspharma.app.service.interfaces;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;

/**
 * What an outside channel knows about a sale the ERP raised for it.
 *
 * <p>Sales Activity and Delivery show invoices. Some of them started life as a
 * marketplace order, which carries things the ERP has no column for: the number
 * the customer reads out on the phone, the pin they dropped on a map, and who
 * to call at the door.
 *
 * <p>Declared here, on the ERP side, so the sales service can ask the question
 * without knowing who answers it. The e-commerce module supplies the answer;
 * nothing in the sale path depends on its tables, its entities or its existence
 * beyond this one method.
 */
public interface SaleSourceReferences {

    /**
     * What the channel knows about one invoice.
     *
     * @param orderNo       the customer-facing order number
     * @param latitude      the delivery pin, when the customer dropped one
     * @param longitude     the delivery pin, when the customer dropped one
     * @param deliveryPhone the number to ring at the door
     * @param recipient     who is expecting it, when that is not the buyer
     */
    record Ref(String orderNo, BigDecimal latitude, BigDecimal longitude,
               String deliveryPhone, String recipient) {}

    /**
     * @param tranMstIds the invoices on the page being rendered
     * @return invoice id → what the channel knows, containing only the invoices
     *         that came from one
     */
    Map<Long, Ref> referencesFor(Collection<Long> tranMstIds);
}
