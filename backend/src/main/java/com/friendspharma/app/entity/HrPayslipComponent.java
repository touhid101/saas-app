package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * One line of the arithmetic behind a payslip.
 *
 * <p>This is the level it is tempting to leave out, and the one that makes the
 * difference between "net 24,300" and an answer to "why". Without it a
 * recalculated payroll silently replaces a figure nobody can reconstruct, and
 * an employee's question has to be answered by re-running the calculation and
 * hoping the inputs have not changed since.
 *
 * <p>{@link #baseAmount} and {@link #pctValue} are stored beside the result, so
 * the row reads as a sentence — "House Rent, 40% of 20,000, = 8,000". A reader
 * can check it without the structure in front of them, and it stays checkable
 * after somebody edits the structure.
 */
@Entity @Table(name = "HR_PAYSLIP_COMPONENT", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HrPayslipComponent extends BaseEntity {

    @Id @Column(name = "PID_PAYSLIP_COMPONENT")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_hr_payslip_component")
    @SequenceGenerator(name = "seq_hr_payslip_component",
                       sequenceName = "SEQ_HR_PAYSLIP_COMPONENT", allocationSize = 1)
    private Long pidPayslipComponent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "PID_PAYROLL_LINE", nullable = false)
    private HrPayrollLine payrollLine;

    @Column(name = "COMPONENT_CODE", length = 30, nullable = false)  private String componentCode;
    @Column(name = "COMPONENT_NAME", length = 120, nullable = false) private String componentName;

    /** EARNING or DEDUCTION — see {@link HrConstants.ComponentType}. */
    @Column(name = "COMPONENT_TYPE", length = 10, nullable = false)  private String componentType;

    @Column(name = "CALC_METHOD", length = 20) private String calcMethod;

    /** What the percentage was taken of. Null for a fixed amount. */
    @Column(name = "BASE_AMOUNT", precision = 18, scale = 2) private BigDecimal baseAmount;

    @Column(name = "PCT_VALUE", precision = 9, scale = 4) private BigDecimal pctValue;

    @Column(name = "AMOUNT", precision = 18, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(name = "SORT_ORDER")
    @Builder.Default private Integer sortOrder = 100;

    public boolean isEarning() {
        return HrConstants.ComponentType.EARNING.equals(componentType);
    }
}
