package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One expense on a claim.
 *
 * <p>{@link #approvedAmount} is separate from {@link #claimedAmount} because an
 * approver who allows a fare and trims a dinner has made two decisions, and a
 * single header total hides which was which. Null until the claim is decided.
 *
 * <p>{@link #receiptPath} holds the <b>public URL</b> of the receipt photograph,
 * relative to the configured upload prefix — never an absolute machine path. The
 * upload root moves between machines and a path baked into a row does not. Same
 * rule as {@code HrEmployee.imagePath}.
 */
@Entity @Table(name = "HR_EXPENSE_CLAIM_LINE", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HrExpenseClaimLine extends BaseEntity {

    @Id @Column(name = "PID_EXPENSE_LINE")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_hr_expense_claim_line")
    @SequenceGenerator(name = "seq_hr_expense_claim_line",
                       sequenceName = "SEQ_HR_EXPENSE_CLAIM_LINE", allocationSize = 1)
    private Long pidExpenseLine;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "PID_EXPENSE_CLAIM", nullable = false)
    private HrExpenseClaim claim;

    @Column(name = "PID_EXPENSE_TYPE", nullable = false) private Long pidExpenseType;

    @Column(name = "EXPENSE_DATE", nullable = false) private LocalDate expenseDate;

    @Column(name = "FROM_PLACE", length = 120) private String fromPlace;
    @Column(name = "TO_PLACE", length = 120)   private String toPlace;
    @Column(name = "DESCRIPTION", length = 500) private String description;

    @Column(name = "CLAIMED_AMOUNT", precision = 18, scale = 2, nullable = false)
    private BigDecimal claimedAmount;

    @Column(name = "APPROVED_AMOUNT", precision = 18, scale = 2) private BigDecimal approvedAmount;

    @Column(name = "RECEIPT_PATH", length = 300) private String receiptPath;

    @Column(name = "SORT_ORDER")
    @Builder.Default private Integer sortOrder = 100;

    public boolean isActive() { return "Y".equals(getStatus()); }

    /** What this line is actually worth: the approved figure once there is one. */
    @Transient
    public BigDecimal effectiveAmount() {
        return approvedAmount != null ? approvedAmount : claimedAmount;
    }

    @Transient
    public boolean hasReceipt() {
        return receiptPath != null && !receiptPath.isBlank();
    }
}
