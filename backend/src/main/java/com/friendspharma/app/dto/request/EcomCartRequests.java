package com.friendspharma.app.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

/**
 * What the storefront sends about a cart.
 *
 * <p><b>No price, ever.</b> A line is a product, a unit and a quantity; what it
 * costs is the pharmacy's to say and is worked out server-side on every read.
 */
public final class EcomCartRequests {
    private EcomCartRequests() {}

    @Data
    public static class AddLine {
        @NotNull(message = "A pharmacy is required.")
        private Long branchId;
        @NotNull(message = "A product is required.")
        private Long productId;
        @NotBlank(message = "A unit is required.")
        private String unit;
        @NotNull(message = "A quantity is required.")
        @DecimalMin(value = "0.01", message = "Quantity must be more than zero.")
        @DecimalMax(value = "99999", message = "That is more than any pharmacy stocks.")
        private BigDecimal qty;
        /** Add to what is already on the line (the default), or set it outright. */
        private Boolean replace;
    }

    @Data
    public static class SetQty {
        @NotNull @DecimalMin(value = "0", message = "Quantity cannot be negative.")
        @DecimalMax(value = "99999") private BigDecimal qty;
    }

    @Data
    public static class CartNote {
        @Size(max = 300) private String note;
    }
}
