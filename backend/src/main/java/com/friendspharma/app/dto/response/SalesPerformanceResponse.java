package com.friendspharma.app.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Sales performance for one branch, one month.
 *
 * <p>Every figure is counted. The screen this serves used to generate its own
 * from a seeded random number generator in the browser — eight salespeople who
 * do not work here, with targets and commissions nobody had agreed.
 *
 * <p>Where a person has no target set, {@code target}, {@code achievementPct}
 * and {@code commission} are null rather than zero. Null draws as "not set";
 * zero would draw as a target of nothing, which every seller beats.
 */
@Getter
@Builder
public class SalesPerformanceResponse {

    /** First day of the month reported. */
    private LocalDate month;

    /** True where the caller may set targets — the Edit right on this module. */
    private boolean canSetTargets;

    private List<Row> rows;
    private Totals totals;
    private List<TrendPoint> trend;

    @Getter
    @Builder
    public static class Row {
        /** The username on the invoice. Not always a login — see below. */
        private String person;

        /** Null where the name in INSERTED_BY is not a user of this branch. */
        private Long userId;

        private String displayName;

        /**
         * Null where the invoices were raised by something that is not a login —
         * a seed script or an import. Those sales are real and are reported, so
         * that this page reconciles with the Sales Register.
         */
        private String roleName;

        private long invoices;
        private long customers;
        private BigDecimal billed;
        private BigDecimal collected;
        private BigDecimal outstanding;
        private BigDecimal discount;
        private BigDecimal averageInvoice;
        private LocalDate lastSale;

        /** Null where no target has been set for this person this month. */
        private BigDecimal target;
        private BigDecimal achievementPct;
        private BigDecimal commissionPct;
        private BigDecimal commission;

        /** True where a target can be set against this row — a real login only. */
        private boolean targetable;
    }

    @Getter
    @Builder
    public static class Totals {
        private long people;
        private long invoices;
        private BigDecimal billed;
        private BigDecimal collected;
        private BigDecimal outstanding;
        private BigDecimal discount;

        /** Sum of the targets that exist, not of every person. */
        private BigDecimal target;
        private BigDecimal achievementPct;
        private BigDecimal commission;

        /** How many of the people below have a target, so the screen can say so. */
        private long withTarget;
    }

    @Getter
    @Builder
    public static class TrendPoint {
        private LocalDate month;
        private String label;
        private long invoices;
        private BigDecimal billed;
        /** The branch's total target that month, or null where none was set. */
        private BigDecimal target;
    }
}
