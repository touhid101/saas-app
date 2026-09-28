package com.friendspharma.app.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** What the AI Copilot screen is handed. */
public class AiCopilotResponse {

    /**
     * One product the buyer should look at, with the workings shown.
     *
     * <p>Every number the explanation quotes is a field here rather than prose
     * assembled on the server. A recommendation nobody can check is a
     * recommendation nobody should act on, and the buyer overriding the
     * quantity needs the same figures the suggestion came from.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Reorder {
        private Long       productId;
        private String     productName;
        private String     sku;
        private Long       companyId;
        private String     companyName;
        private String     categoryName;

        private int        pcsPerBox;
        private BigDecimal costPerBox;      // TP, what a box is bought for
        private BigDecimal mrpPerBox;

        private BigDecimal stockPcs;
        /** Stock that can realistically sell before it expires. See the repository. */
        private BigDecimal sellablePcs;
        private BigDecimal velocityPerDay;
        /** Days the sellable stock covers at the current rate; null when nothing moves. */
        private Integer    daysOfCover;
        private LocalDate  stockoutOn;

        private BigDecimal safetyStockPcs;
        private BigDecimal targetStockPcs;
        private BigDecimal deficitPcs;
        private int        suggestedBoxes;
        private BigDecimal estimatedCost;

        private String     priority;        // CRITICAL | HIGH | MEDIUM | LOW
        private String     confidence;      // HIGH | MEDIUM | LOW
        private int        sellingDays;     // days it actually sold on, of the last 30
        private BigDecimal trendPct;        // last 30 days against the 30 before

        private LocalDate  nearestExpiry;
        private BigDecimal expiringPcs;
        private BigDecimal expiringValue;

        /** Short phrases naming why it is on the list — the UI renders them as chips. */
        private List<String> reasons;
    }

    /** A product ranked by how it is selling, for the movers board. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Mover {
        private Long       productId;
        private String     productName;
        private String     companyName;
        private BigDecimal revenue;
        private BigDecimal margin;
        private BigDecimal marginPct;
        private BigDecimal pcs;
        private long       invoices;
        private BigDecimal velocityPerDay;
        private BigDecimal trendPct;
        private BigDecimal stockPcs;
        private Integer    daysOfCover;
        /** RISING | STEADY | SLIPPING | STALLED */
        private String     momentum;
        /** OK | LOW | CRITICAL | OUT | DEAD */
        private String     stockSignal;
    }

    /**
     * Stock that needs a decision rather than an order: it is dated, or it has
     * stopped moving. Both tie up money, and neither shows up on a reorder list
     * — buying more of something that is not selling is the opposite of the
     * advice.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Watch {
        private Long       productId;
        private String     productName;
        private String     companyName;
        private BigDecimal stockPcs;
        /** What will actually sell before the expiry date at the current rate. */
        private BigDecimal sellablePcs;
        /** Stock that will not sell in time — the write-off, in pieces and money. */
        private BigDecimal atRiskPcs;
        private BigDecimal atRiskValue;
        private BigDecimal stockValue;
        private LocalDate  nearestExpiry;
        private Integer    daysToExpiry;
        private LocalDate  lastSold;
        private Integer    idleDays;
        private BigDecimal velocityPerDay;
        /** EXPIRING | DEAD | BOTH */
        private String     kind;
        private String     suggestion;
    }

    /** A headline worth acting on, with the money it represents. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Insight {
        private String     key;
        private String     severity;        // CRITICAL | WARNING | INFO | GOOD
        private String     title;
        private String     detail;
        private BigDecimal amount;
        private Integer    count;
        /** Which tab answers it, so the card can take you there. */
        private String     action;
    }

    /** One day on the sales sparkline. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DayPoint {
        private LocalDate  day;
        private BigDecimal pcs;
    }

    /** Everything the screen needs, in one response. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Board {
        private List<Reorder>  reorder;
        private List<Mover>    movers;
        private List<Watch>    watch;
        private List<Insight>  insights;
        private List<DayPoint> trend;
        private Summary        summary;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Summary {
        private int        productsTracked;
        private int        needAttention;
        private BigDecimal suggestedSpend;
        private BigDecimal stockValue;
        private BigDecimal expiringValue;
        private BigDecimal deadStockValue;
        private int        outOfStock;
        private int        historyDays;
        /** True when there is too little history for the velocity maths to mean much. */
        private boolean    thinHistory;
    }

    /** The answer to a question typed into the assistant. */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Answer {
        private String       question;
        private String       headline;
        private List<String> bullets;
        /** Which tab to open for the detail behind the answer. */
        private String       action;
    }
}
