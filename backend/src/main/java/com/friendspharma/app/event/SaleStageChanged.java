package com.friendspharma.app.event;

/**
 * A sale moved, or was called off.
 *
 * <p>Published by {@code SalesServiceImpl} so that anything built on top of a
 * sale can follow it without the sale path knowing who is listening. The
 * marketplace is the first such thing: a driver marks an order delivered from
 * the Delivery page, which knows nothing about online orders, and the customer's
 * screen still has to stop saying "on the way".
 *
 * @param tranMstId the invoice that moved
 * @param stage     the stage it moved to — or {@code Cancelled}
 * @param actor     who moved it, for the trail on the other side
 */
public record SaleStageChanged(Long tranMstId, String stage, String actor) {}
