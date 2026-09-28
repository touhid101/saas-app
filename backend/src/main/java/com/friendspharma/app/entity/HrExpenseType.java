package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * A kind of expense that can be claimed for.
 *
 * <p>V83 seeds five names per branch — Conveyance, Meal, Medical, Phone and
 * Other — because those are vocabulary rather than policy, exactly as V73 seeded
 * leave type names. They can be renamed or retired.
 *
 * <p><b>Every limit is null and stays null.</b> What a day's conveyance is worth
 * at this pharmacy is a business rule, and a seeded cap would be a number nobody
 * chose being enforced against people's own money. Null means no cap has been
 * decided — not a cap of nought — and nothing is refused against a limit nobody
 * set. The same contract as the overtime multiplier, the leave quota, the PF
 * rate and the gratuity formula.
 */
@Entity @Table(name = "HR_EXPENSE_TYPE", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HrExpenseType extends BaseEntity {

    @Id @Column(name = "PID_EXPENSE_TYPE")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_hr_expense_type")
    @SequenceGenerator(name = "seq_hr_expense_type", sequenceName = "SEQ_HR_EXPENSE_TYPE",
                       allocationSize = 1)
    private Long pidExpenseType;

    @Column(name = "PID_ORG", nullable = false)    private Long pidOrg;
    @Column(name = "PID_COMPANY")                  private Long pidCompany;
    @Column(name = "PID_BRANCH", nullable = false) private Long pidBranch;

    @Column(name = "TYPE_CODE", length = 20, nullable = false)  private String typeCode;
    @Column(name = "TYPE_NAME", length = 120, nullable = false) private String typeName;
    @Column(name = "DESCRIPTION", length = 500)                 private String description;

    /** Null means undecided. See the class comment. */
    @Column(name = "MAX_PER_CLAIM", precision = 18, scale = 2) private BigDecimal maxPerClaim;
    @Column(name = "MAX_PER_DAY", precision = 18, scale = 2)   private BigDecimal maxPerDay;

    @Column(name = "REQUIRES_RECEIPT")
    @Builder.Default private Integer requiresReceipt = 0;

    /** V90: the ledger account claims of this type are charged to. Null falls back to Staff Expenses. */
    @Column(name = "PID_ACCOUNT") private Long pidAccount;

    @Column(name = "SORT_ORDER")
    @Builder.Default private Integer sortOrder = 100;

    public boolean isActive() { return "Y".equals(getStatus()); }

    /** True only when somebody has actually set a limit. */
    @Transient
    public boolean isCapped() { return maxPerClaim != null || maxPerDay != null; }

    @Transient
    public boolean needsReceipt() { return requiresReceipt != null && requiresReceipt == 1; }
}
