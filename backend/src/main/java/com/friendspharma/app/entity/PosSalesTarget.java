package com.friendspharma.app.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A month's sales target for one person, in one branch.
 *
 * <p>Actuals are not stored here. They are counted from
 * {@code POS_TRANSACTION_MST.INSERTED_BY}, which is the only record of who
 * raised an invoice — {@code PID_EMPLOYEE} is NULL on every sale in the
 * database and has never been written.
 *
 * <p>Identity generation rather than a sequence: five migrations in this schema
 * exist to repair sequences left behind their tables by hand-picked ids, and an
 * identity column cannot drift.
 */
@Entity
@Table(name = "POS_SALES_TARGET")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PosSalesTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "PID_TARGET")
    private Long pidTarget;

    @Column(name = "PID_ORG", nullable = false)
    private Long pidOrg;

    @Column(name = "PID_BRANCH", nullable = false)
    private Long pidBranch;

    @Column(name = "PID_USER", nullable = false)
    private Long pidUser;

    /** Always the first of the month; the database enforces it. */
    @Column(name = "PERIOD_MONTH", nullable = false)
    private LocalDate periodMonth;

    @Column(name = "TARGET_AMOUNT", nullable = false)
    private BigDecimal targetAmount;

    /** Percent, not a fraction: 2.5 means 2.5%. */
    @Column(name = "COMMISSION_PCT", nullable = false)
    private BigDecimal commissionPct;

    @Column(name = "STATUS", length = 1)
    private String status;

    @Column(name = "INSERTED_BY", length = 50)
    private String insertedBy;

    @Column(name = "INSERTED_DATE")
    private LocalDateTime insertedDate;

    @Column(name = "UPDATED_BY", length = 50)
    private String updatedBy;

    @Column(name = "UPDATED_DATE")
    private LocalDateTime updatedDate;
}
