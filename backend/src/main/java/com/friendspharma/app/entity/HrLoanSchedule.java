package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One installment of a loan.
 *
 * <p>{@link #pidPayroll} records which run took it. That is the audit answer to
 * "why is my salary short this month", and it is also what makes recovery
 * idempotent: a payroll recalculated twice finds the installment already marked
 * against that run and does not take it again.
 */
@Entity @Table(name = "HR_LOAN_SCHEDULE", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HrLoanSchedule extends BaseEntity {

    @Id @Column(name = "PID_SCHEDULE")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_hr_loan_schedule")
    @SequenceGenerator(name = "seq_hr_loan_schedule", sequenceName = "SEQ_HR_LOAN_SCHEDULE",
                       allocationSize = 1)
    private Long pidSchedule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "PID_LOAN", nullable = false)
    private HrLoan loan;

    @Column(name = "INSTALLMENT_NO", nullable = false) private Integer installmentNo;
    @Column(name = "DUE_DATE", nullable = false)       private LocalDate dueDate;

    @Column(name = "DUE_AMOUNT", precision = 18, scale = 2, nullable = false)
    private BigDecimal dueAmount;

    @Column(name = "RECOVERED_AMOUNT", precision = 18, scale = 2, nullable = false)
    @Builder.Default private BigDecimal recoveredAmount = BigDecimal.ZERO;

    /** See {@link HrConstants.ScheduleStatus}. */
    @Column(name = "SCHEDULE_STATUS", length = 20, nullable = false)
    @Builder.Default private String scheduleStatus = HrConstants.ScheduleStatus.PENDING;

    @Column(name = "PID_PAYROLL")   private Long pidPayroll;
    @Column(name = "RECOVERED_ON")  private LocalDate recoveredOn;
    @Column(name = "REMARKS", length = 500) private String remarks;

    @Transient
    public BigDecimal getRemaining() {
        BigDecimal due  = dueAmount == null ? BigDecimal.ZERO : dueAmount;
        BigDecimal back = recoveredAmount == null ? BigDecimal.ZERO : recoveredAmount;
        BigDecimal left = due.subtract(back);
        return left.signum() < 0 ? BigDecimal.ZERO : left;
    }

    public boolean isOpen() {
        return HrConstants.ScheduleStatus.PENDING.equals(scheduleStatus)
                || HrConstants.ScheduleStatus.PARTIAL.equals(scheduleStatus);
    }

    public boolean isActive() {
        return "Y".equals(getStatus());
    }
}
