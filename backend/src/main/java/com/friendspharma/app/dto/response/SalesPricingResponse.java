package com.friendspharma.app.dto.response;

import com.friendspharma.app.dto.response.StockResponses.StockAllocation;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/**
 * What one customer may buy of one product, and at what price.
 *
 * <p>Answers the question a salesperson actually has at the counter: which
 * units can I sell this in, how much is there, and what do I charge? The tier
 * decides both the sellable units and the price list, and those are separate
 * decisions — a retailer is restricted to pieces but pays the wholesaler's
 * piece rate.
 *
 * <p>Every unit the product could be sold in is returned, including the ones
 * this customer cannot have, each carrying {@code allowed} and a {@code reason}.
 * Returning only the permitted units would leave the UI unable to explain why a
 * box option vanished.
 */
@Getter
@Builder
public class SalesPricingResponse {

    private Long       customerId;
    private String     customerName;
    private String     customerType;
    /** WHOLESALER | RETAILER | SPECIAL */
    private String     tier;
    private String     tierLabel;

    private Long       productId;
    private String     productName;
    private String     sku;

    /** Units this tier may transact in, in display order. */
    private List<String> allowedUnits;

    /** Sellable pieces in this branch — expired lots excluded. */
    private BigDecimal availablePcs;
    private String     availableDisplay;

    private Integer    pcsPerBox;
    private Integer    pcsPerStrip;

    private List<UnitOption> units;

    /** Which batches the default quantity would consume. Null until asked for. */
    private StockAllocation allocation;

    /**
     * One sellable unit.
     *
     * @param price     what this customer pays per unit, or null when the unit
     *                  is unavailable to them or unpriced
     * @param mrp       the MRP this price was derived from
     * @param maxQty    how many whole units the branch can currently supply
     * @param reason    why the unit is unavailable; null when it is available
     */
    @Getter
    @Builder
    public static class UnitOption {
        private String     unit;
        private boolean    allowed;
        private BigDecimal price;
        private BigDecimal mrp;
        /** Percentage off MRP that produced the price, for the tooltip. */
        private BigDecimal discountPct;
        private Integer    pcsPerUnit;
        private BigDecimal maxQty;
        private String     reason;
    }
}
