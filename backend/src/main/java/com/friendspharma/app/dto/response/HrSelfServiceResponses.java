package com.friendspharma.app.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * What the self-service portal reads: the signed-in employee, their figures,
 * and every request of theirs as one shape whatever module it came from.
 *
 * <p>The five request kinds keep their own DTOs in {@link HrResponses}; the
 * portal wraps them in a {@link RequestItem} so one list, one filter and one
 * progress rail can hold a leave request next to a loan. The wrapped record
 * travels with it in {@link RequestDetail#data}, untouched, so nothing a
 * module already says is said a second time here.
 */
public final class HrSelfServiceResponses {

    private HrSelfServiceResponses() {}

    /* ══ Who ═══════════════════════════════════════════════════════════════ */

    @Data @Builder
    public static class Me {
        /** False when the login has no employee record behind it. */
        private boolean linked;
        private Long employeeId;
        private String empId;
        private String empName;
        private String preferredName;
        private String imagePath;
        private String initials;
        private String designationName;
        private String departmentName;
        private String branchName;
        private String employmentType;
        private String employmentTypeLabel;
        private String empStatus;
        private LocalDate hireDate;
        private String managerName;
        private String shiftName;
        private String email;
        private String phone;
    }

    /* ══ One request, whichever module it came from ════════════════════════ */

    @Data @Builder
    public static class RequestItem {
        /** LEAVE, EXPENSE, ADVANCE, LOAN or ATTENDANCE. */
        private String type;
        private String typeLabel;
        private Long id;
        /** The module's own number — LV-…, EXP-…; the attendance date for a correction. */
        private String requestNo;
        /** "Annual leave", "Conveyance", "Salary advance"… */
        private String title;
        /** The one line under it: the dates, the places, the purpose. */
        private String subtitle;
        private BigDecimal amount;
        private BigDecimal approvedAmount;
        private BigDecimal days;
        private LocalDate fromDate;
        private LocalDate toDate;
        private LocalDate requestDate;
        /** DRAFT, PENDING, APPROVED, PROCESSING, COMPLETED, REJECTED, CANCELLED. */
        private String status;
        private String statusLabel;
        /** The module's own word, for the reader who knows it. */
        private String rawStatus;
        private String rawStatusLabel;
        /** Where it is right now, in words: "Waiting on the manager", "Approved — awaiting payment". */
        private String stage;
        private Integer stageNo;
        private Integer stageTotal;
        private LocalDateTime submittedAt;
        private LocalDateTime updatedAt;
        private String decidedBy;
        private LocalDateTime decidedAt;
        private String decisionRemarks;
        /** Something the employee has to do — a draft to submit, a receipt to attach. */
        private boolean actionRequired;
        private String actionText;
        /** What the employee may still do to it. */
        private boolean cancellable;
        private boolean submittable;
    }

    /** One step of the lifecycle rail. */
    @Data @Builder
    public static class Stage {
        private String key;
        private String label;
        /** DONE, CURRENT, PENDING, REJECTED, CANCELLED. */
        private String state;
        private String by;
        private LocalDateTime at;
        private String remarks;
    }

    @Data @Builder
    public static class RequestDetail {
        private RequestItem item;
        private List<Stage> stages;
        /** The module's own record: LeaveRequestDto, ExpenseClaimDto, AdvanceDto, LoanDto or AttendanceDto. */
        private Object data;
        /** The approval chain, when one applies. */
        private HrResponses.ApprovalChainDto chain;
        /** The human-readable audit line — every stamp the record and its chain carry, oldest first. */
        private List<Stage> history;
    }

    /* ══ The figures ═══════════════════════════════════════════════════════ */

    @Data @Builder
    public static class Summary {
        private List<HrResponses.LeaveBalanceDto> leaveBalances;
        private BigDecimal leaveAvailable;
        private long pendingRequests;
        private long approvedRequests;
        private long rejectedRequests;
        private long draftRequests;
        private long attendanceIssues;
        private long absentThisMonth;
        private long lateThisMonth;
        private long correctionsPending;
        private BigDecimal advanceOutstanding;
        private BigDecimal loanOutstanding;
        private BigDecimal monthlyRecovery;
        private BigDecimal expenseClaimedThisMonth;
        private BigDecimal expenseApprovedThisMonth;
        private BigDecimal expensePaidThisMonth;
        private long expensePending;
        private long expenseRejected;
        private HrResponses.AttendanceDto today;
        private AttendanceMonthSummary month;
        /** Requests waiting on the employee — drafts to submit, receipts to attach. */
        private List<RequestItem> actions;
        /** What is in flight, newest first. */
        private List<RequestItem> active;
        /** What changed lately — decided, paid, applied — newest first. */
        private List<Activity> activity;
        /** Requests with a payslip: the latest run's line, when there is one. */
        private PayslipPointer latestPayslip;
    }

    @Data @Builder
    public static class Activity {
        private String type;
        private Long id;
        private String requestNo;
        private String text;
        private String status;
        private LocalDateTime at;
    }

    @Data @Builder
    public static class PayslipPointer {
        private Long payrollId;
        private Long lineId;
        private String payrollNo;
        private String periodLabel;
        private BigDecimal netSalary;
        private String paymentStatus;
    }

    /* ══ Attendance ════════════════════════════════════════════════════════ */

    @Data @Builder
    public static class AttendanceMonthSummary {
        private String month;
        private int calendarDays;
        private int workingDays;
        private long present;
        private long absent;
        private long late;
        private long leave;
        private long halfDay;
        private long holiday;
        private long weekend;
        private long unrecorded;
        private BigDecimal workedHours;
        private BigDecimal overtimeHours;
        private long correctionsPending;
    }

    @Data @Builder
    public static class AttendanceMonth {
        private String month;
        private LocalDate from;
        private LocalDate to;
        private HrResponses.AttendanceDto today;
        private List<HrResponses.AttendanceDto> rows;
        private List<Holiday> holidays;
        private List<LocalDate> weekends;
        private AttendanceMonthSummary summary;
    }

    @Data @Builder
    public static class Holiday {
        private LocalDate date;
        private String name;
        private String type;
    }
}
