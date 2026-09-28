package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * One payslip.
 *
 * <p>The columns here are totals by role — gross, deductions, the three named
 * recoveries, net — because those are what a payroll register prints and what
 * Accounts posts. The itemised arithmetic behind them is one level down, in
 * {@link HrPayslipComponent}.
 *
 * <p><b>Department and designation are copied, not joined.</b> An employee who
 * transfers in March must still appear under the department they were in when
 * February was paid, or every historical report moves under them.
 *
 * <p><b>Net is stored, not derived on read.</b> It is the number that was
 * approved and the number that will be paid. Recomputing it later against a
 * structure somebody has since edited would produce a different figure for a
 * month that is closed.
 */
@Entity @Table(name = "HR_PAYROLL_LINE", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HrPayrollLine extends BaseEntity {

    @Id @Column(name = "PID_PAYROLL_LINE")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_hr_payroll_line")
    @SequenceGenerator(name = "seq_hr_payroll_line", sequenceName = "SEQ_HR_PAYROLL_LINE",
                       allocationSize = 1)
    private Long pidPayrollLine;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "PID_PAYROLL", nullable = false)
    private HrPayroll payroll;

    @Column(name = "PID_EMPLOYEE", nullable = false) private Long pidEmployee;

    @Column(name = "PID_DEPARTMENT")  private Long pidDepartment;
    @Column(name = "PID_DESIGNATION") private Long pidDesignation;
    @Column(name = "EMP_ID", length = 20)   private String empId;
    @Column(name = "EMP_NAME", length = 50) private String empName;

    @Column(name = "BASIC_SALARY", precision = 18, scale = 2, nullable = false)
    @Builder.Default private BigDecimal basicSalary = BigDecimal.ZERO;

    @Column(name = "ALLOWANCE_TOTAL", precision = 18, scale = 2, nullable = false)
    @Builder.Default private BigDecimal allowanceTotal = BigDecimal.ZERO;

    @Column(name = "OVERTIME_AMOUNT", precision = 18, scale = 2, nullable = false)
    @Builder.Default private BigDecimal overtimeAmount = BigDecimal.ZERO;

    @Column(name = "BONUS_AMOUNT", precision = 18, scale = 2, nullable = false)
    @Builder.Default private BigDecimal bonusAmount = BigDecimal.ZERO;

    @Column(name = "GROSS_SALARY", precision = 18, scale = 2, nullable = false)
    @Builder.Default private BigDecimal grossSalary = BigDecimal.ZERO;

    @Column(name = "DEDUCTION_TOTAL", precision = 18, scale = 2, nullable = false)
    @Builder.Default private BigDecimal deductionTotal = BigDecimal.ZERO;

    @Column(name = "ABSENCE_DEDUCTION", precision = 18, scale = 2, nullable = false)
    @Builder.Default private BigDecimal absenceDeduction = BigDecimal.ZERO;

    @Column(name = "ADVANCE_RECOVERY", precision = 18, scale = 2, nullable = false)
    @Builder.Default private BigDecimal advanceRecovery = BigDecimal.ZERO;

    @Column(name = "LOAN_RECOVERY", precision = 18, scale = 2, nullable = false)
    @Builder.Default private BigDecimal loanRecovery = BigDecimal.ZERO;

    /**
     * The employee's provident fund share, already inside DEDUCTION_TOTAL.
     *
     * <p>Zero on every payslip computed before a branch had a scheme, which is
     * every payslip that existed when V82 added the column.
     */
    @Column(name = "PF_EMPLOYEE_AMOUNT", precision = 18, scale = 2)
    @Builder.Default private BigDecimal pfEmployeeAmount = BigDecimal.ZERO;

    /**
     * The company's share. Deliberately <b>not</b> in DEDUCTION_TOTAL and not in
     * NET_SALARY — it is not taken from anybody's salary. It sits here so the
     * cost of employing somebody can be reported without a second calculation.
     */
    @Column(name = "PF_EMPLOYER_AMOUNT", precision = 18, scale = 2)
    @Builder.Default private BigDecimal pfEmployerAmount = BigDecimal.ZERO;

    @Column(name = "TAX_AMOUNT", precision = 18, scale = 2, nullable = false)
    @Builder.Default private BigDecimal taxAmount = BigDecimal.ZERO;

    @Column(name = "NET_SALARY", precision = 18, scale = 2, nullable = false)
    @Builder.Default private BigDecimal netSalary = BigDecimal.ZERO;

    /* The attendance the figures were computed from, frozen with them. */
    @Column(name = "PRESENT_DAYS", precision = 5, scale = 2)      @Builder.Default private BigDecimal presentDays     = BigDecimal.ZERO;
    @Column(name = "ABSENT_DAYS", precision = 5, scale = 2)       @Builder.Default private BigDecimal absentDays      = BigDecimal.ZERO;
    @Column(name = "LEAVE_DAYS", precision = 5, scale = 2)        @Builder.Default private BigDecimal leaveDays       = BigDecimal.ZERO;
    @Column(name = "UNPAID_LEAVE_DAYS", precision = 5, scale = 2) @Builder.Default private BigDecimal unpaidLeaveDays = BigDecimal.ZERO;
    @Column(name = "OVERTIME_MINUTES")                            @Builder.Default private Integer overtimeMinutes   = 0;

    /** See {@link HrConstants.PaySlipStatus}. */
    @Column(name = "PAYMENT_STATUS", length = 20, nullable = false)
    @Builder.Default private String paymentStatus = HrConstants.PaySlipStatus.UNPAID;

    @Column(name = "PAID_AMOUNT", precision = 18, scale = 2, nullable = false)
    @Builder.Default private BigDecimal paidAmount = BigDecimal.ZERO;

    @Column(name = "REMARKS", length = 500) private String remarks;

    @OneToMany(mappedBy = "payrollLine", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, pidPayslipComponent ASC")
    @Builder.Default
    private List<HrPayslipComponent> components = new ArrayList<>();

    @Transient
    public BigDecimal getUnpaid() {
        BigDecimal net  = netSalary  == null ? BigDecimal.ZERO : netSalary;
        BigDecimal paid = paidAmount == null ? BigDecimal.ZERO : paidAmount;
        BigDecimal left = net.subtract(paid);
        return left.signum() < 0 ? BigDecimal.ZERO : left;
    }

    public boolean isActive() {
        return "Y".equals(getStatus());
    }
}
