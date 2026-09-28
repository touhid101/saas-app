package com.friendspharma.app.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * One alert on the topbar bell.
 *
 * <p>Every field is measured. The screen this replaces built its alerts from
 * eight fixed strings in {@code mockData.js} — "8 products below alert
 * quantity", "3 products have zero stock" — the same eight for every user, every
 * branch and every day, describing stock nobody owned. Nothing here is written
 * unless a query returned it.
 *
 * <p>{@code count} is what the alert is about — products, batches, documents,
 * customers — and {@code amount} carries money where the alert is about money,
 * null where it is not. Both are sent raw so the client formats them once, in
 * the same place it formats every other figure.
 */
@Getter
@Builder
public class NotificationResponse {

    /** Stable across refreshes, so a dismissal can be remembered. */
    private String id;

    /** LOW_STOCK, EXPIRY, REQUISITION … — the client picks an icon from it. */
    private String type;

    private String title;
    private String message;

    /** The page this alert is about; the module whose View right gated it. */
    private String link;

    /** How many things the alert counts. Never zero — a zero raises no alert. */
    private long count;

    /** Money, where the alert is about money. Null otherwise. */
    private BigDecimal amount;

    /**
     * How much attention it deserves: INFO, WARN or URGENT.
     *
     * <p>Expired stock and overdue money are URGENT because they are already
     * losses. Approaching expiry is WARN — it is still avoidable.
     */
    private String severity;
}
