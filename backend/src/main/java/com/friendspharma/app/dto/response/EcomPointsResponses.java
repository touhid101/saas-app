package com.friendspharma.app.dto.response;

import lombok.Builder;
import lombok.Data;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Loyalty points: the rules a pharmacy sets, and the statement a customer reads. */
public final class EcomPointsResponses {
    private EcomPointsResponses() {}

    /** A rule as the pharmacy that wrote it sees it. */
    @Getter @Builder
    public static class Rule {
        private final Long id;
        private final String name;
        private final String description;
        private final BigDecimal earnPointsPer100;
        private final BigDecimal minOrderToEarn;
        private final BigDecimal pointValue;
        private final Integer minPointsToRedeem;
        private final BigDecimal maxRedeemPercent;
        private final Integer expiryMonths;
        private final String branchScope;
        private final String customerTypeScope;
        private final LocalDateTime startsAt;
        private final LocalDateTime endsAt;
        private final boolean active;
        private final String state;
        /** The whole thing in one sentence, for a list. */
        private final String summary;
        private final List<Long> branchIds;
        private final List<String> branchNames;
    }

    /** One movement on a customer's balance. */
    @Getter @Builder
    public static class Entry {
        private final String type;
        private final BigDecimal points;
        private final BigDecimal balanceAfter;
        private final BigDecimal valueAmount;
        private final String orderNo;
        private final String note;
        private final LocalDateTime at;
    }

    /** What a customer sees of their own points. */
    @Getter @Builder
    public static class Statement {
        private final BigDecimal balance;
        /** Null when no pharmacy they can reach has a scheme. */
        private final BigDecimal pointValue;
        private final String note;
        private final List<Entry> entries;
    }

    /** What the screen sends back when writing a rule. */
    @Data
    public static class Form {
        private String name;
        private String description;
        private BigDecimal earnPointsPer100;
        private BigDecimal minOrderToEarn;
        private BigDecimal pointValue;
        private Integer minPointsToRedeem;
        private BigDecimal maxRedeemPercent;
        private Integer expiryMonths;
        private String branchScope;
        private List<Long> branchIds;
        private String customerTypeScope;
        private LocalDateTime startsAt;
        private LocalDateTime endsAt;
        private String isActive;
    }
}
