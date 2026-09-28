package com.friendspharma.app.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Where an order is, and what happened at the door. */
public final class EcomTrackingResponses {
    private EcomTrackingResponses() {}

    /** What the driver's device gets back for a position it reported. */
    @Getter @Builder
    public static class Ping {
        private final String orderNo;
        private final LocalDateTime recordedAt;
        /** How far the driver still is from the customer, when both points are known. */
        private final Double kmFromCustomer;
        private final long pointsRecorded;
    }

    /** Evidence of the handover. */
    @Getter @Builder
    public static class Proof {
        private final String orderNo;
        /** PHOTO · NAME · NOTE */
        private final String kind;
        private final String receivedByName;
        private final String photoUrl;
        private final String note;
        private final BigDecimal latitude;
        private final BigDecimal longitude;
        private final LocalDateTime capturedAt;
        private final String capturedBy;
    }

    /**
     * What a customer may know about where their order is.
     *
     * <p>One point, not a trail, and only while {@code live}. There is no
     * estimated time: nothing here knows about roads.
     */
    @Getter @Builder
    public static class Tracking {
        private final String orderNo;
        private final String status;
        /** True while the order is out and a position may be shown. */
        private final boolean live;

        private final BigDecimal latitude;
        private final BigDecimal longitude;
        private final LocalDateTime recordedAt;
        private final Long minutesAgo;
        private final Double kmAway;
        /** The sentence to show, written server-side so it cannot overstate what is known. */
        private final String note;

        private final String deliveryManName;
        private final String branchName;
        private final String branchPhone;

        /** Present once it has been handed over. */
        private final Proof proof;
    }
}
