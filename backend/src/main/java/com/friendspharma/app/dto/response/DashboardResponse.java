package com.friendspharma.app.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The dashboard, in two halves.
 *
 * <p>{@code operations} is what a pharmacist, store keeper or driver needs to
 * run the day: what is short, what is dated, what is waiting to go out. It
 * contains counts, quantities and dates, and no money at all.
 *
 * <p>{@code finance} is the owner's half — revenue, margin, what is owed each
 * way, what the shelves are worth. For anyone not entitled to it the field is
 * <b>null</b>, and the queries behind it were never run. Blanking the figures on
 * the client would still have sent them over the wire.
 */
public class DashboardResponse {

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Dashboard {
        private Scope      scope;
        private Operations operations;
        /** Null when the signed-in user may not see money. */
        private Finance    finance;
    }

    /** Who is asking and what they are looking at. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Scope {
        private Long    branchId;
        private String  branchName;
        private int     days;
        /** Whether the finance half was built. The screen reads this, not the role. */
        private boolean canSeeFinancials;
        private LocalDate asOf;
    }

    /* ══════════════════════════════════════════════════════════════════════
       OPERATIONS
       ══════════════════════════════════════════════════════════════════════ */

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Operations {
        private Today          today;
        private StockHealth    stock;
        private Delivery       delivery;
        private List<Mover>    movers;
        private List<Expiring> expiring;
        private List<DayPoint> trend;
        private List<Alert>    alerts;
        /** How long the catalogue will last, bucketed. */
        private List<Slice>    coverBuckets;
        /** Stock by remaining shelf life. Pieces for everyone; value only for some. */
        private List<Slice>    expiryRunway;
        /** The order pipeline, stage by stage. */
        private List<Slice>    pipeline;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Today {
        private long       ordersRaised;
        private long       ordersDelivered;
        private long       ordersPending;
        private BigDecimal piecesSold;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class StockHealth {
        private long       productsTracked;
        private long       outOfStock;
        private long       lowStock;
        private long       expiring30;
        private long       expiring90;
        private long       alreadyExpired;
        private long       deadStock;
        private BigDecimal piecesOnHand;
        private BigDecimal piecesExpiring90;
    }

    /** The delivery board, by stage. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Delivery {
        private long confirmed;
        private long assigned;
        private long onTheWay;
        private long delivered;
        private long collected;
        private long unassigned;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Mover {
        private String     productName;
        private String     companyName;
        private BigDecimal pcs;
        private BigDecimal onHand;
        /** Days of cover left at the current rate; null when nothing is moving. */
        private Integer    daysOfCover;
        private BigDecimal trendPct;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Expiring {
        private String     productName;
        private String     batchNo;
        private LocalDate  expiryDate;
        private BigDecimal pcs;
        private int        daysLeft;
    }

    /**
     * One day on a trend line.
     *
     * <p>Shared between the operational and the financial trend, so nulls are
     * dropped rather than serialised: a day on the operational board was still
     * carrying {@code "revenue":null,"margin":null}, which put the words on the
     * wire for a user who is not allowed the figures. No value escaped, but a
     * payload should not name what it will not answer.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public static class DayPoint {
        private LocalDate  day;
        private long       orders;
        private BigDecimal pcs;
        /** Only present on the finance trend. */
        private BigDecimal revenue;
        private BigDecimal margin;
    }

    /**
     * One labelled bar, slice or bucket.
     *
     * <p>The same shape serves the cover buckets, the expiry runway, the
     * ageing bands, the payment mix and the maker split. Five DTOs that differ
     * only in what the label means would be five things to keep in step.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public static class Slice {
        private String     label;
        /** The quantity or count the bar is drawn from. */
        private BigDecimal value;
        /** Money, where the caller is entitled to it. Absent otherwise. */
        private BigDecimal amount;
        private Integer    count;
        /** CRITICAL, WARNING, OK — lets the bar colour itself honestly. */
        private String     tone;
    }

    /** Revenue against what it cost, one calendar month. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MonthPoint {
        private String     month;
        private BigDecimal revenue;
        private BigDecimal cost;
        private BigDecimal margin;
        private BigDecimal marginPct;
        private long       invoices;
    }

    /**
     * Something worth acting on today.
     *
     * <p>Deliberately phrased as an instruction rather than a statistic. "14
     * products are out of stock" is a number; "14 products are out of stock —
     * the Copilot can raise the order" is a next step.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Alert {
        private String  key;
        private String  severity;   // CRITICAL | WARNING | INFO
        private String  title;
        private String  detail;
        private Integer count;
        /** Which screen answers it. */
        private String  action;
    }

    /* ══════════════════════════════════════════════════════════════════════
       FINANCE
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * [V67-69] Supplier generosity for the month, as three separate figures.
     *
     * <p>Cashback and gift value are money and make up {@code total}; bonus
     * pieces are a count and sit outside it, because a bonus has already been
     * banked as a lower cost per piece and valuing it again would report the
     * same benefit twice. {@code pctOfPurchases} is the total against what was
     * spent, which is the figure a buyer can carry into the next negotiation.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Benefit {
        private BigDecimal cashback;
        private BigDecimal giftValue;
        private BigDecimal bonusPcs;
        private BigDecimal total;
        /** Null rather than zero when nothing was bought — no purchases, no ratio. */
        private BigDecimal pctOfPurchases;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Finance {
        private Period            today;
        private Period            month;
        private Period            previousMonth;
        /** Month against the month before, as a percentage. Null with no prior trade. */
        private BigDecimal        revenueTrendPct;

        private BigDecimal        purchasesToday;
        private BigDecimal        purchasesMonth;

        /** [V67-69] What suppliers gave on top of the goods, this month. */
        private Benefit           supplierBenefit;

        private Owed              receivable;
        private Owed              payable;

        private BigDecimal        stockValue;
        private BigDecimal        expiringValue;
        private BigDecimal        expiredValue;

        private List<DayPoint>    trend;
        private List<TopCustomer> topCustomers;
        private List<TopProduct>  topProducts;
        /** Revenue against cost, by calendar month, oldest first. */
        private List<MonthPoint>  monthly;
        /** What is owed, by how old it is. */
        private List<Slice>       receivableAgeing;
        /** How customers pay. */
        private List<Slice>       paymentMix;
        /** Revenue by manufacturer — the concentration question. */
        private List<Slice>       revenueByMaker;
    }

    /** Revenue and what it cost, for one window. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Period {
        private BigDecimal revenue;
        private BigDecimal cost;
        private BigDecimal margin;
        private BigDecimal marginPct;
        private long       invoices;
        private BigDecimal pcs;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Owed {
        private BigDecimal total;
        private BigDecimal overdue;
        private long       count;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TopCustomer {
        private String     name;
        private String     type;
        private BigDecimal revenue;
        private BigDecimal due;
        private long       invoices;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TopProduct {
        private String     name;
        private BigDecimal revenue;
        private BigDecimal margin;
        private BigDecimal marginPct;
        private BigDecimal pcs;
    }
}
