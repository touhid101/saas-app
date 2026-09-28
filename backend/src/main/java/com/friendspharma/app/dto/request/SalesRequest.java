package com.friendspharma.app.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class SalesRequest {

    @NotNull(message = "Customer is required")
    private Long customerId;

    /**
     * Optional. Branch is resolved from the JWT when omitted, which is the
     * normal case — a single-branch user never picks one.
     *
     * <p>Deliberately not {@code @NotNull}: requiring it here asks the client to
     * state which branch's stock to consume, and the client is not the authority
     * on that. Supplied only when a multi-branch user deliberately sells from
     * another branch, and the service refuses the sale outright if neither the
     * request nor the session yields one.
     */
    private Long branchId;

    @NotNull(message = "Sale date is required")
    private LocalDate transactionDate;

    private String deliveryAddress;
    private BigDecimal deliveryCharge;
    private String paymentType;
    private BigDecimal invoiceDiscPct;

    /**
     * An invoice discount as an exact amount, when the caller has one.
     *
     * <p>A coupon is worth ৳100, not 7.3529411%. Deriving a percentage from an
     * amount and letting the ERP multiply it back loses a paisa often enough to
     * make the customer's order and the pharmacy's invoice disagree, which is
     * the one thing neither may do. When this is set it wins; when it is null
     * nothing changes and {@code invoiceDiscPct} behaves exactly as before.
     * [Phase 12]
     */
    private BigDecimal invoiceDiscAmt;
    private BigDecimal givenAmount;
    private String remarks;
    private Long deliveryManId;

    /**
     * Rung up at the till, with the goods handed across the counter.
     *
     * <p>It decides the stage a new invoice lands on, and it exists because a
     * counter sale and a delivery order are the same request otherwise. At the
     * till the goods are gone the moment the bill is printed, so an unpaid one
     * is <em>Delivered</em> — owed for, not awaiting despatch. An order raised
     * on the Sales page has not left the building, and must still travel
     * Confirmed → Delivery Assigned → On The Way before anyone may call it
     * delivered.
     *
     * <p>Absent or false keeps exactly the behaviour every other caller has
     * had, which is why it is not simply inferred.
     */
    private Boolean counterSale;

    @NotEmpty(message = "At least one product line is required")
    @Valid
    private List<SalesLine> lines;

    @Data
    public static class SalesLine {
        @NotNull(message = "Product is required") private Long productId;

        @NotNull @DecimalMin(value = "0.0001", message = "Quantity must be > 0")
        private BigDecimal quantity;

        @NotNull(message = "Sales unit is required (BOX/STRIP/PCS)")
        @Pattern(regexp = "BOX|STRIP|PCS", message = "Sales unit must be BOX, STRIP, or PCS")
        private String salesUnit;

        private BigDecimal discountPct;
        private BigDecimal flatDiscount;
    }
}
