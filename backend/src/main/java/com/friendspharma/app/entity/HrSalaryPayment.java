package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Money actually handed over against a payroll run.
 *
 * <p>Separate from the run itself, because calculating what is owed and paying
 * it are different acts on different days, often by different people, and a
 * month can be paid in two goes.
 *
 * <p>{@link #pidPayrollLine} is nullable: a payment may cover one employee or
 * the whole run. Both are real — a bank transfer settles everybody at once,
 * while the one person who was away gets paid in cash a week later.
 *
 * <p>The accounting entry for this payment is Accounts' business. This row is
 * the operational fact: who was paid, how much, by what route, on what day.
 */
@Entity @Table(name = "HR_SALARY_PAYMENT", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HrSalaryPayment extends BaseEntity {

    public static final String PAID      = "PAID";
    public static final String CANCELLED = "CANCELLED";

    @Id @Column(name = "PID_SALARY_PAYMENT")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_hr_salary_payment")
    @SequenceGenerator(name = "seq_hr_salary_payment", sequenceName = "SEQ_HR_SALARY_PAYMENT",
                       allocationSize = 1)
    private Long pidSalaryPayment;

    @Column(name = "PID_ORG", nullable = false)     private Long pidOrg;
    @Column(name = "PID_COMPANY")                   private Long pidCompany;
    @Column(name = "PID_BRANCH", nullable = false)  private Long pidBranch;
    @Column(name = "PID_PAYROLL", nullable = false) private Long pidPayroll;
    @Column(name = "PID_PAYROLL_LINE")              private Long pidPayrollLine;
    @Column(name = "PID_EMPLOYEE")                  private Long pidEmployee;

    @Column(name = "VOUCHER_NO", length = 30)      private String voucherNo;
    @Column(name = "PAYMENT_DATE", nullable = false) private LocalDate paymentDate;

    @Column(name = "AMOUNT", precision = 18, scale = 2, nullable = false)
    private BigDecimal amount;

    /** See {@link HrConstants.PaymentMode}. */
    @Column(name = "PAYMENT_MODE", length = 20, nullable = false) private String paymentMode;

    @Column(name = "BANK_NAME", length = 100)  private String bankName;
    @Column(name = "CHEQUE_NO", length = 40)   private String chequeNo;
    @Column(name = "CHEQUE_DATE")              private LocalDate chequeDate;
    @Column(name = "REFERENCE_NO", length = 60) private String referenceNo;
    @Column(name = "REMARKS", length = 500)    private String remarks;

    @Column(name = "PAYMENT_STATUS", length = 20, nullable = false)
    @Builder.Default private String paymentStatus = PAID;

    @Column(name = "CANCEL_REASON", length = 500) private String cancelReason;

    public boolean isPaid()   { return PAID.equals(paymentStatus); }
    public boolean isActive() { return "Y".equals(getStatus()); }
}
