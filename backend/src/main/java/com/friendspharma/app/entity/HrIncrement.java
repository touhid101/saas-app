package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A change to what somebody is paid.
 *
 * <p><b>The three money figures are frozen at approval, not recomputed when the
 * increment is applied.</b> A percentage signed off in March against 20,000 is
 * worth 2,000. If somebody edits the basic salary to 25,000 in April, applying
 * that increment must still be the 2,000 that was approved — not 2,500 nobody
 * agreed to. Recomputing at apply time would quietly change a decision after it
 * was made.
 *
 * <p>The service goes further and refuses to apply an increment whose
 * {@link #previousSalary} no longer matches what the employee is actually on:
 * the figure moved underneath it, and overwriting would discard whatever change
 * did that.
 *
 * <p>This is also the salary history. {@code HR_EMPLOYEES.BASIC_SALARY} holds
 * only the current figure; these rows are the record of how it got there, which
 * is why an applied one is never deleted.
 */
@Entity @Table(name = "HR_INCREMENT", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HrIncrement extends BaseEntity {

    public static final String DRAFT     = "DRAFT";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String APPROVED  = "APPROVED";
    public static final String REJECTED  = "REJECTED";
    public static final String APPLIED   = "APPLIED";
    public static final String CANCELLED = "CANCELLED";

    public static final List<String> ALL_STATUSES =
            List.of(DRAFT, SUBMITTED, APPROVED, REJECTED, APPLIED, CANCELLED);

    /** Closed by ck_hr_increment_type (V81). */
    public static final List<String> TYPES =
            List.of("ANNUAL", "PROMOTION", "PERFORMANCE", "ADJUSTMENT");

    public static final String PERCENT = "PERCENT";
    public static final String FIXED   = "FIXED";
    public static final List<String> BASES = List.of(PERCENT, FIXED);

    @Id @Column(name = "PID_INCREMENT")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_hr_increment")
    @SequenceGenerator(name = "seq_hr_increment", sequenceName = "SEQ_HR_INCREMENT",
                       allocationSize = 1)
    private Long pidIncrement;

    @Column(name = "PID_ORG", nullable = false)      private Long pidOrg;
    @Column(name = "PID_COMPANY")                    private Long pidCompany;
    @Column(name = "PID_BRANCH", nullable = false)   private Long pidBranch;
    @Column(name = "PID_EMPLOYEE", nullable = false) private Long pidEmployee;

    @Column(name = "INCREMENT_NO", length = 30) private String incrementNo;

    @Column(name = "INCREMENT_TYPE", length = 20, nullable = false)
    @Builder.Default private String incrementType = "ANNUAL";

    /** PERCENT or FIXED — which of the two figures below is read. */
    @Column(name = "INCREMENT_BASIS", length = 10, nullable = false)
    private String incrementBasis;

    @Column(name = "PCT_VALUE", precision = 9, scale = 4)      private BigDecimal pctValue;
    @Column(name = "FIXED_AMOUNT", precision = 18, scale = 2)  private BigDecimal fixedAmount;

    @Column(name = "PREVIOUS_SALARY", precision = 18, scale = 2)  private BigDecimal previousSalary;
    @Column(name = "INCREMENT_AMOUNT", precision = 18, scale = 2) private BigDecimal incrementAmount;
    @Column(name = "NEW_SALARY", precision = 18, scale = 2)       private BigDecimal newSalary;

    @Column(name = "EFFECTIVE_DATE", nullable = false) private LocalDate effectiveDate;

    /** A promotion may move these too. An annual increment usually moves neither. */
    @Column(name = "PID_NEW_DESIGNATION") private Long pidNewDesignation;
    @Column(name = "NEW_GRADE", length = 30) private String newGrade;

    @Column(name = "REASON", length = 1000) private String reason;

    @Column(name = "INCREMENT_STATUS", length = 20, nullable = false)
    @Builder.Default private String incrementStatus = DRAFT;

    @Column(name = "SUBMITTED_BY", length = 50) private String submittedBy;
    @Column(name = "SUBMITTED_AT")              private LocalDateTime submittedAt;
    @Column(name = "DECIDED_BY", length = 50)   private String decidedBy;
    @Column(name = "DECIDED_AT")                private LocalDateTime decidedAt;
    @Column(name = "DECISION_REMARKS", length = 1000) private String decisionRemarks;
    @Column(name = "APPLIED_BY", length = 50)   private String appliedBy;
    @Column(name = "APPLIED_AT")                private LocalDateTime appliedAt;

    public boolean isDraft()     { return DRAFT.equals(incrementStatus); }
    public boolean isSubmitted() { return SUBMITTED.equals(incrementStatus); }
    public boolean isApproved()  { return APPROVED.equals(incrementStatus); }
    public boolean isApplied()   { return APPLIED.equals(incrementStatus); }
    public boolean isActive()    { return "Y".equals(getStatus()); }

    /**
     * What this increment is worth against a given salary.
     *
     * <p>The one place the arithmetic lives. A percentage rounds to two places
     * HALF_UP, matching every other money calculation in this application.
     */
    public static BigDecimal amountFor(String basis, BigDecimal pct, BigDecimal fixed,
                                       BigDecimal current) {
        if (FIXED.equals(basis)) {
            return fixed == null ? BigDecimal.ZERO : fixed.setScale(2, RoundingMode.HALF_UP);
        }
        if (pct == null || current == null) return BigDecimal.ZERO;
        return current.multiply(pct)
                      .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    }
}
