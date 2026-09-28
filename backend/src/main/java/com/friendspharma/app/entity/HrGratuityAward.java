package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * What somebody was actually awarded, and the whole of the working.
 *
 * <h3>Frozen, not accrued</h3>
 *
 * <p>Service years, the wage, the daily rate, the days awarded and the amount
 * are all copied onto this row at approval and never recomputed. The reason is
 * the same one {@link HrIncrement} gives: an award recalculated later is a
 * decision changed after it was made. It also means an award can be read back
 * and checked years afterwards without the policy or the salary that produced
 * it still being reachable.
 *
 * <p>Nothing accrues monthly. Posting a gratuity accrual into a liability
 * account every month <em>is</em> an accounting policy, this build has no
 * Accounts module to post it to, and inventing the entry would be inventing the
 * policy.
 *
 * <h3>What it does not do to a separation</h3>
 *
 * <p>{@code HR_SEPARATION.SETTLEMENT_AMOUNT} is untouched. What belongs in a
 * final settlement — notice pay, leave encashment, gratuity — is P-05, still
 * unanswered, so an approved award is shown beside the settlement as a fact and
 * whoever settles it decides what to do with it.
 */
@Entity @Table(name = "HR_GRATUITY_AWARD", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HrGratuityAward extends BaseEntity {

    public static final String DRAFT     = "DRAFT";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String APPROVED  = "APPROVED";
    public static final String REJECTED  = "REJECTED";
    public static final String PAID      = "PAID";
    public static final String CANCELLED = "CANCELLED";

    /** Closed by ck_hr_grat_award_status (V82). */
    public static final List<String> ALL_STATUSES =
            List.of(DRAFT, SUBMITTED, APPROVED, REJECTED, PAID, CANCELLED);

    @Id @Column(name = "PID_GRATUITY_AWARD")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_hr_gratuity_award")
    @SequenceGenerator(name = "seq_hr_gratuity_award", sequenceName = "SEQ_HR_GRATUITY_AWARD",
                       allocationSize = 1)
    private Long pidGratuityAward;

    @Column(name = "PID_ORG", nullable = false)      private Long pidOrg;
    @Column(name = "PID_COMPANY")                    private Long pidCompany;
    @Column(name = "PID_BRANCH", nullable = false)   private Long pidBranch;
    @Column(name = "PID_EMPLOYEE", nullable = false) private Long pidEmployee;

    /** Null for an award made to somebody still employed. */
    @Column(name = "PID_SEPARATION")      private Long pidSeparation;
    @Column(name = "PID_GRATUITY_POLICY") private Long pidGratuityPolicy;

    @Column(name = "AWARD_NO", length = 30) private String awardNo;

    @Column(name = "SERVICE_FROM", nullable = false) private LocalDate serviceFrom;
    @Column(name = "SERVICE_TO", nullable = false)   private LocalDate serviceTo;

    /** Countable years after the part-year rule and any cap — frozen. */
    @Column(name = "SERVICE_YEARS", precision = 7, scale = 4, nullable = false)
    private BigDecimal serviceYears;

    @Column(name = "WAGE_BASIS", length = 10, nullable = false) private String wageBasis;

    @Column(name = "MONTHLY_WAGE", precision = 18, scale = 2, nullable = false)
    private BigDecimal monthlyWage;
    @Column(name = "DAYS_IN_MONTH", precision = 5, scale = 2, nullable = false)
    private BigDecimal daysInMonth;
    @Column(name = "DAILY_WAGE", precision = 18, scale = 4, nullable = false)
    private BigDecimal dailyWage;
    @Column(name = "DAYS_PER_YEAR", precision = 6, scale = 2, nullable = false)
    private BigDecimal daysPerYear;
    @Column(name = "DAYS_AWARDED", precision = 9, scale = 2, nullable = false)
    private BigDecimal daysAwarded;

    @Column(name = "GRATUITY_AMOUNT", precision = 18, scale = 2, nullable = false)
    private BigDecimal gratuityAmount;

    @Column(name = "AWARD_STATUS", length = 20, nullable = false)
    @Builder.Default private String awardStatus = DRAFT;

    @Column(name = "SUBMITTED_BY", length = 50) private String submittedBy;
    @Column(name = "SUBMITTED_AT")              private LocalDateTime submittedAt;
    @Column(name = "DECIDED_BY", length = 50)   private String decidedBy;
    @Column(name = "DECIDED_AT")                private LocalDateTime decidedAt;
    @Column(name = "DECISION_REMARKS", length = 1000) private String decisionRemarks;

    @Column(name = "PAID_DATE")                      private LocalDate paidDate;
    @Column(name = "PAYMENT_MODE", length = 20)      private String paymentMode;
    @Column(name = "PAYMENT_REFERENCE", length = 50) private String paymentReference;
    @Column(name = "PAID_BY", length = 50)           private String paidBy;

    @Column(name = "REASON", length = 1000) private String reason;

    public boolean isDraft()     { return DRAFT.equals(awardStatus); }
    public boolean isSubmitted() { return SUBMITTED.equals(awardStatus); }
    public boolean isApproved()  { return APPROVED.equals(awardStatus); }
    public boolean isPaid()      { return PAID.equals(awardStatus); }
    public boolean isActive()    { return "Y".equals(getStatus()); }
}
