package com.friendspharma.app.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * One delivery man's record over a period.
 *
 * <p>Deliberately reports counts and money side by side. Deliveries completed
 * says how much work was done; cash collected against what was billed says
 * whether the money came back with them — a driver with a good completion rate
 * and a large outstanding balance is a different problem from one with neither.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeliveryPerformanceResponse {

    private Long   deliveryManId;
    private String deliveryManName;
    /** Null when the employee record has since been removed. */
    private String vehicleNo;
    private String phone;
    /** False once they leave; their history still counts. */
    private Boolean active;

    /** Every invoice ever dispatched to them in the period. */
    private long assigned;
    /** Reached Delivered or Collected. */
    private long delivered;
    /** Still out — Delivery Assigned or On The Way. */
    private long inProgress;

    /**
     * Delivered ÷ assigned, as a percentage.
     *
     * <p>Null rather than zero when nothing was assigned: "0% completion" reads
     * as failure, where the truth is that they were given no work.
     */
    private BigDecimal completionRate;

    /** Total billed across those invoices. */
    private BigDecimal billed;
    /** Total taken. */
    private BigDecimal collected;
    /** billed − collected, floored at zero. */
    private BigDecimal outstanding;

    /**
     * Collected ÷ billed, as a percentage. Null when nothing was billed.
     *
     * <p>This is the figure that matters for cash-on-delivery: goods handed over
     * without the money coming back are a loss dressed as a completed delivery.
     */
    private BigDecimal collectionRate;
}
