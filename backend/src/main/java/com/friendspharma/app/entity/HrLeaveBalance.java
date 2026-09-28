package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * What one employee has left, of one kind of leave, in one year.
 *
 * <p><b>Entitled is copied, not joined.</b> The figure is taken from the leave
 * type's quota when the balance is opened. Reading it through a join would mean
 * that changing next year's policy silently rewrote what somebody was entitled
 * to last year, and a closed year would stop agreeing with the payslips issued
 * inside it.
 *
 * <p><b>Pending is separate from taken.</b> A submitted request has not been
 * approved and has not been taken, but it must still reduce what the employee
 * can ask for — otherwise two requests filed the same morning both pass the
 * balance check and the second one is approved into an overdraft.
 *
 * <pre>
 *   available = entitled + carriedForward - taken - pending
 * </pre>
 */
@Entity @Table(name = "HR_LEAVE_BALANCE", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HrLeaveBalance extends BaseEntity {

    @Id @Column(name = "PID_BALANCE")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_hr_leave_balance")
    @SequenceGenerator(name = "seq_hr_leave_balance", sequenceName = "SEQ_HR_LEAVE_BALANCE",
                       allocationSize = 1)
    private Long pidBalance;

    @Column(name = "PID_ORG", nullable = false)        private Long pidOrg;
    @Column(name = "PID_BRANCH", nullable = false)     private Long pidBranch;
    @Column(name = "PID_EMPLOYEE", nullable = false)   private Long pidEmployee;
    @Column(name = "PID_LEAVE_TYPE", nullable = false) private Long pidLeaveType;

    @Column(name = "LEAVE_YEAR", nullable = false)
    private Integer leaveYear;

    @Column(name = "ENTITLED", precision = 6, scale = 2, nullable = false)
    @Builder.Default private BigDecimal entitled = BigDecimal.ZERO;

    @Column(name = "CARRIED_FORWARD", precision = 6, scale = 2, nullable = false)
    @Builder.Default private BigDecimal carriedForward = BigDecimal.ZERO;

    @Column(name = "TAKEN", precision = 6, scale = 2, nullable = false)
    @Builder.Default private BigDecimal taken = BigDecimal.ZERO;

    @Column(name = "PENDING", precision = 6, scale = 2, nullable = false)
    @Builder.Default private BigDecimal pending = BigDecimal.ZERO;

    /** Days the employee may still ask for. Never negative on a sound balance. */
    @Transient
    public BigDecimal getAvailable() {
        return nz(entitled).add(nz(carriedForward)).subtract(nz(taken)).subtract(nz(pending));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    public boolean isActive() {
        return "Y".equals(getStatus());
    }
}
