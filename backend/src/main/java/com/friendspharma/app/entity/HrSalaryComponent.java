package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * One line of a payslip template.
 *
 * <p>{@link #calcMethod} decides which of {@link #amount} and {@link #pctValue}
 * is read:
 *
 * <pre>
 *   FIXED          amount, as entered
 *   PCT_OF_BASIC   pctValue % of the employee's BASIC_SALARY
 *   PCT_OF_GROSS   pctValue % of the gross accumulated so far
 * </pre>
 *
 * <p>{@link #sortOrder} is part of the arithmetic, not the presentation. A
 * PCT_OF_GROSS deduction evaluated before the allowances that make up that
 * gross computes against a smaller number and produces a different, wrong
 * answer — so the payroll calculator walks components in this order and the
 * order is stored rather than assumed.
 */
@Entity @Table(name = "HR_SALARY_COMPONENT", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HrSalaryComponent extends BaseEntity {

    @Id @Column(name = "PID_COMPONENT")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_hr_component")
    @SequenceGenerator(name = "seq_hr_component", sequenceName = "SEQ_HR_SALARY_COMPONENT",
                       allocationSize = 1)
    private Long pidComponent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "PID_STRUCTURE", nullable = false)
    private HrSalaryStructure structure;

    @Column(name = "COMPONENT_CODE", length = 30, nullable = false)
    private String componentCode;

    @Column(name = "COMPONENT_NAME", length = 120, nullable = false)
    private String componentName;

    /** EARNING or DEDUCTION — see {@link HrConstants.ComponentType}. */
    @Column(name = "COMPONENT_TYPE", length = 10, nullable = false)
    private String componentType;

    /** See {@link HrConstants.CalcMethod}. */
    @Column(name = "CALC_METHOD", length = 20, nullable = false)
    private String calcMethod;

    @Column(name = "AMOUNT", precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "PCT_VALUE", precision = 9, scale = 4)
    private BigDecimal pctValue;

    /**
     * Whether this line sits inside the base tax is computed on.
     *
     * <p>No rate lives anywhere in HR. What is taxable, at what rate, and under
     * which rules, is a policy and legal question this implementation does not
     * answer — see {@code 11-financial-period-tax.md}. This flag records the
     * pharmacy's own classification so a rate, once configured, has something
     * to apply to.
     */
    @Column(name = "IS_TAXABLE")
    @Builder.Default
    private Integer isTaxable = 1;

    /**
     * Marks this component AS the provident fund deduction.
     *
     * <p>A structure can already carry PF as an ordinary DEDUCTION component,
     * and at least one does. Once a branch has a PF scheme, deducting both the
     * component and the scheme would take it twice — so the calculator skips a
     * component marked here and uses the scheme instead. With no scheme, the
     * component is evaluated exactly as any other.
     *
     * <p>Nothing infers this from a component's name. A screen may suggest a
     * candidate; only a person marks one.
     */
    @Column(name = "IS_PF")
    @Builder.Default private Integer isPf = 0;

    @Column(name = "SORT_ORDER")
    @Builder.Default
    private Integer sortOrder = 100;

    public boolean isEarning() {
        return HrConstants.ComponentType.EARNING.equals(componentType);
    }

    public boolean isDeduction() {
        return HrConstants.ComponentType.DEDUCTION.equals(componentType);
    }

    public boolean isActive() {
        return "Y".equals(getStatus());
    }
}
