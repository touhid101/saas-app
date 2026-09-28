package com.friendspharma.app.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The delivery dashboard in one call.
 *
 * <p>Scoped the same way the order list is: a driver gets their own round, a
 * manager gets the branch. The alternative — an unscoped summary above a scoped
 * table — puts two contradictory numbers on the same screen and tells a driver
 * how much work exists that is not theirs.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeliverySummaryResponse {

    /**
     * Orders at each stage, every stage present even at zero.
     *
     * <p>A missing key and a zero read the same to a person but not to a UI, and
     * a stage that vanishes from the dashboard when it empties is worse than one
     * showing 0.
     */
    private Map<String, Long> byStage;

    /** Every sale in scope, whatever its stage. */
    private long totalOrders;

    /** Confirmed but not yet dispatched — the queue waiting for a driver. */
    private long awaitingAssignment;

    /** Dispatched and not yet delivered: Delivery Assigned or On The Way. */
    private long onTheRoad;

    /** Reached Delivered or Collected. */
    private long completed;

    /** Billed across every order in scope. */
    private BigDecimal billed;

    /** Taken, capped per invoice at what was billed — change is not revenue. */
    private BigDecimal collected;

    /** billed − collected, floored at zero. */
    private BigDecimal outstanding;

    /**
     * True when the figures cover only the signed-in driver's own round.
     *
     * <p>Sent so the screen can say whose numbers these are. A driver looking at
     * a total with no such label has no way to tell it apart from the branch's.
     */
    private Boolean ownRoundOnly;
}
