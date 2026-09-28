package com.friendspharma.app.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One payslip, assembled for printing.
 *
 * <p>Built in the shape of {@link InvoiceDocumentResponse}: the same letterhead
 * read from the branch, every figure — down to the amount in words — computed
 * here rather than in a browser, and one document behind the sheet handed to
 * the employee and the copy filed, so the two cannot disagree.
 *
 * <p>Nothing on it is recomputed from today's salary or today's structure. The
 * lines are the ones the run wrote, and the attendance is what the run counted,
 * so a payslip reprinted next year says what it said the month it was paid.
 */
@Data
@Builder
public class PayslipDocumentResponse {

    /** The letterhead, exactly as the invoice prints it. */
    private InvoiceDocumentResponse.Branch branch;

    private Slip slip;
    private Employee employee;

    /** What was earned, in the order the run computed it. */
    private List<Line> earnings;
    /** What was taken, in the order the run computed it. */
    private List<Line> deductions;

    private Attendance attendance;
    private Totals totals;

    /** Every payment made against this payslip, oldest first. */
    private List<Payment> payments;

    /** The net salary, in words. */
    private String amountInWords;

    @Data
    @Builder
    public static class Slip {
        /** The run's number and this line's id: "PAY-2026-09 / 41". */
        private String payslipNo;
        private Long payrollId;
        private Long payrollLineId;
        private String payrollNo;
        private String periodLabel;
        private LocalDate periodFrom;
        private LocalDate periodTo;
        private BigDecimal workingDays;
        /** DRAFT … PAID — where the run is, not where the line is. */
        private String payrollStatus;
        private String payrollStatusLabel;
        /** UNPAID, PARTIAL or PAID — where this line is. */
        private String paymentStatus;
        /** Who approved the run; the signature a payslip is issued under. */
        private String approvedBy;
        private LocalDateTime approvedAt;
        private LocalDateTime postedAt;
        private String remarks;
        /** When this copy was produced, so two printouts can be told apart. */
        private LocalDateTime printedAt;
    }

    @Data
    @Builder
    public static class Employee {
        private Long id;
        private String name;
        private String code;
        private String designation;
        private String department;
        private LocalDate hireDate;
        /** CASH, BANK, CHEQUE, MOBILE — how this person is normally paid. */
        private String paymentMode;
        private String paymentModeLabel;
        private String bankName;
        /** Last four digits only; the whole number does not belong on paper. */
        private String bankAccountMasked;
        private String mobileWalletMasked;
    }

    @Data
    @Builder
    public static class Line {
        private int sl;
        private String code;
        private String name;
        /** "12% of 25,000.00" for a percentage line; null for a fixed amount. */
        private String basis;
        private BigDecimal amount;
    }

    @Data
    @Builder
    public static class Attendance {
        private BigDecimal workingDays;
        private BigDecimal presentDays;
        private BigDecimal absentDays;
        private BigDecimal leaveDays;
        private BigDecimal unpaidLeaveDays;
        private Integer overtimeMinutes;
        private BigDecimal overtimeHours;
    }

    @Data
    @Builder
    public static class Totals {
        private BigDecimal basicSalary;
        private BigDecimal grossSalary;
        private BigDecimal totalDeductions;
        private BigDecimal taxAmount;
        private BigDecimal netSalary;
        private BigDecimal paid;
        private BigDecimal balanceDue;
        /** The company's provident fund share — shown, never deducted. */
        private BigDecimal pfEmployer;
    }

    @Data
    @Builder
    public static class Payment {
        private String voucherNo;
        private LocalDate date;
        private String mode;
        private String modeLabel;
        private BigDecimal amount;
        private String reference;
    }
}
