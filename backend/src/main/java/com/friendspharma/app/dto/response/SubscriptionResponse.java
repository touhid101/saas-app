package com.friendspharma.app.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * What a pharmacy is paying for, and what it owes.
 *
 * <p>The screen must never work the expiry out for itself. Whether a branch is
 * inside its grace period, how many days remain and whether it may still trade
 * are all decided by the same code the filter uses, so the badge on the screen
 * and the gate on the API can never disagree.
 */
public class SubscriptionResponse {

    @Data @Builder @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Mine {
        private Long   branchId;
        private String branchName;

        /** PENDING, ACTIVE, SUSPENDED or EXPIRED — the branch, not the subscription. */
        private String tenantStatus;

        /** True while the application may be used at all. */
        private boolean allowed;
        /** Why not, when it may not. Already worded for the user. */
        private String  reason;

        private String     planCode;
        private String     planName;
        private BigDecimal price;
        private String     billingTerm;

        private LocalDate startsOn;
        private LocalDate expiresOn;
        /** Expiry plus the grace agreed when the subscription was written. */
        private LocalDate lastDayOfAccess;
        private Integer   graceDays;

        /** Days until access actually stops. Zero once it has. */
        private long    daysRemaining;
        /** Past the expiry date but still trading on grace. */
        private boolean inGrace;
        /** True close enough to expiry that the screen should say so. */
        private boolean renewalDue;

        private List<InvoiceDto> outstanding;
    }

    @Data @Builder @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class InvoiceDto {
        private Long       invoiceId;
        private String     invoiceNo;
        private LocalDate  periodFrom;
        private LocalDate  periodTo;
        private BigDecimal amount;
        private LocalDate  issuedOn;
        private LocalDate  dueOn;
        private String     payStatus;
    }

    @Data @Builder @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PaymentDto {
        private Long       paymentId;
        private Long       invoiceId;
        private String     invoiceNo;
        private BigDecimal amount;
        private LocalDate  paidOn;
        private String     method;
        private String     providerRef;
        private String     note;
    }

    @Data @Builder @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PlanDto {
        private Long       planId;
        private String     planCode;
        private String     planName;
        private String     description;
        private BigDecimal price;
        private String     billingTerm;
        private Integer    maxUsers;
        private Integer    graceDays;
    }

    /** One row of the Super Admin console. */
    @Data @Builder @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TenantRow {
        private Long      branchId;
        private Long      orgId;
        private String    branchName;
        /** The pharmacy's mark, so the console can show and edit it without a second call. */
        private String    logoUrl;
        private String    tenantStatus;
        private String    planName;
        private LocalDate expiresOn;
        private LocalDate lastDayOfAccess;
        private long      daysRemaining;
        private boolean   inGrace;
        private boolean   allowed;
        private int       outstandingInvoices;
        private BigDecimal outstandingAmount;
    }

    @Data @Builder @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AuditDto {
        private String        eventType;
        private String        detail;
        private String        oldValue;
        private String        newValue;
        private String        actedBy;
        private LocalDateTime actedAt;
    }
}
