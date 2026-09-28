package com.friendspharma.app.exception.custom;

import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

public class InsufficientStockException extends BusinessException {

    public InsufficientStockException(String productName, int available, int requested) {
        super(
            String.format("Cannot complete sale: %d PCS requested for '%s' but only %d PCS are available.",
                requested, productName, available),
            HttpStatus.UNPROCESSABLE_ENTITY,
            "INSUFFICIENT_STOCK"
        );
    }

    /**
     * Reports the shortfall in the unit the caller actually asked for, with the
     * piece counts alongside.
     *
     * <p>Someone who asked for one box is not helped by being told that five
     * pieces are available — the number is true and the answer is useless. They
     * need to know they are short of a box, and how far short.
     *
     * @param requestedQty  quantity as typed, in {@code unit}
     * @param availableQty  what the branch holds, expressed in the same unit,
     *                      or {@code null} when the product has no packing set
     *                      up for that unit
     */
    public InsufficientStockException(String productName, String branchLabel, String unit,
                                      BigDecimal requestedQty, BigDecimal availableQty,
                                      BigDecimal requestedPcs, BigDecimal availablePcs) {
        super(buildMessage(productName, branchLabel, unit, requestedQty, availableQty,
                           requestedPcs, availablePcs),
              HttpStatus.UNPROCESSABLE_ENTITY,
              "INSUFFICIENT_STOCK");
    }

    private static String buildMessage(String productName, String branchLabel, String unit,
                                       BigDecimal requestedQty, BigDecimal availableQty,
                                       BigDecimal requestedPcs, BigDecimal availablePcs) {
        StringBuilder sb = new StringBuilder();
        sb.append("Not enough stock for '").append(productName).append("'");
        if (branchLabel != null && !branchLabel.isBlank()) {
            sb.append(" at ").append(branchLabel);
        }
        sb.append(": you asked for ").append(plain(requestedQty)).append(' ').append(unit);

        if (availableQty != null) {
            sb.append(" but only ").append(plain(availableQty)).append(' ').append(unit)
              .append(" is available");
        } else {
            sb.append(" but the available stock does not make up a whole ").append(unit);
        }

        sb.append(" (").append(plain(availablePcs)).append(" of ")
          .append(plain(requestedPcs)).append(" pcs).");
        return sb.toString();
    }

    private static String plain(BigDecimal v) {
        if (v == null) return "0";
        return v.stripTrailingZeros().toPlainString();
    }
}
