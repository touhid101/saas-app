package com.friendspharma.app.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Everything the HR screens read.
 *
 * <p>Labels travel with codes throughout — {@code advanceStatus} and
 * {@code advanceStatusLabel}, {@code separationType} and its label. The screen
 * should not hold a second copy of the vocabulary; when a value is added to
 * {@code HrConstants} the UI gets its wording without a change.
 */
public final class HrResponses {

    private HrResponses() {}

    /* ══ Setup ══════════════════════════════════════════════════════════ */

    @Data @Builder
    public static class SalaryStructureDto {
        private Long id;
        private String structureName;
        private String description;
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
        private BigDecimal standardHoursPerDay;
        private BigDecimal overtimeMultiplier;
        private String absenceBasis;
        /** False when no multiplier is set: overtime is recorded but not valued. */
        private boolean paysOvertime;
        private String status;
        private long employeesOn;
        private List<SalaryComponentDto> components;
    }

    @Data @Builder
    public static class SalaryComponentDto {
        private Long id;
        private String componentCode;
        private String componentName;
        private String componentType;
        private String componentTypeLabel;
        private String calcMethod;
        private String calcMethodLabel;
        private BigDecimal amount;
        private BigDecimal pctValue;
        private Integer isTaxable;
        /** 1 when this component IS the PF deduction — see {@code HrSalaryComponent}. */
        private Integer isPf;
        private Integer sortOrder;
        private String status;
    }

    @Data @Builder
    public static class LeaveTypeDto {
        private Long id;
        private String typeCode;
        private String typeName;
        private String description;
        private BigDecimal annualQuota;
        /** False until somebody sets the quota — balances cannot open before that. */
        private boolean quotaSet;
        private Integer isPaid;
        private Integer carryForward;
        private BigDecimal maxCarryForward;
        private Integer requiresApproval;
        private String status;
    }

    /* ══ Working policy, shift, holiday and the calendar ════════════════ */

    @Data @Builder
    public static class ShiftDto {
        private Long id;
        private String shiftCode;
        private String shiftName;
        private java.time.LocalTime startTime;
        private java.time.LocalTime endTime;
        /** True for a shift that ends on the following date, e.g. 22:00–06:00. */
        private boolean crossesMidnight;
        private Integer breakMinutes;
        private Integer graceMinutes;
        /** Gross span less the break, frozen so an edit cannot rewrite old days. */
        private Integer workingMinutes;
        private long employeesOn;
        private String status;
    }

    @Data @Builder
    public static class WorkingPolicyDto {
        private Long id;
        private String policyName;
        private Long defaultShiftId;
        private String defaultShiftName;
        /** ISO day numbers, e.g. "5,6". Null where no weekly off has been set. */
        private String weekendDays;
        /** The same days spelled out, so the screen does not hold a lookup table. */
        private List<String> weekendDayNames;
        /**
         * False where nobody has decided a weekly off.
         *
         * <p>The screen says so in words rather than showing an empty field,
         * because an unset weekend is a decision waiting to be made and not a
         * bug — leave counts every calendar day until it is set.
         */
        private boolean weekendSet;
        private Integer lateThresholdMinutes;
        private Integer earlyLeaveThresholdMinutes;
        private Integer halfDayMinutes;
        private Integer minWorkingMinutes;
        private boolean overtimeAllowed;
        private Integer overtimeMinMinutes;
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
        /** True for the one policy in force now. Superseded ones carry a to-date. */
        private boolean current;
        private String status;
    }

    @Data @Builder
    public static class HolidayDto {
        private Long id;
        private LocalDate holidayDate;
        private String holidayName;
        private String holidayType;
        private String holidayTypeLabel;
        private Integer holidayYear;
        private Integer isPaid;
        /** True for a holiday the whole organisation keeps, not just this branch. */
        private boolean orgWide;
        private String description;
        private String status;
    }

    /** One day, and whether the branch works it. */
    @Data @Builder
    public static class CalendarDayDto {
        private LocalDate date;
        private boolean workingDay;
        private boolean weekend;
        private boolean holiday;
        private String holidayName;
        private boolean paidHoliday;

        /**
         * What is happening that day, on the HR calendar.
         *
         * <p>Null on the working calendar, which answers only whether the day is
         * worked. The two share this shape rather than each having their own,
         * because "is this a working day" has to have one answer.
         */
        private List<CalendarEventDto> events;
    }

    /**
     * One thing happening on a day.
     *
     * <p>Every kind is derived from a row that already exists — a holiday, an
     * approved leave request, a date on an employee, an approved increment.
     * Nothing is stored twice, so nothing can fall out of step.
     */
    @Data @Builder
    public static class CalendarEventDto {
        /** HOLIDAY, LEAVE, BIRTHDAY, ANNIVERSARY, CONFIRMATION or INCREMENT. */
        private String eventType;
        private Long employeeId;
        private String empName;
        private String title;
        private String detail;
        /** The underlying row, where opening it means something. */
        private Long refId;
    }

    /**
     * The HR calendar: the working calendar, with everything happening on it.
     *
     * <p>{@code configured} false still means the branch has no working policy
     * and every day is therefore a working day — the events are shown either
     * way, because leave and birthdays do not depend on a weekend being chosen.
     */
    @Data @Builder
    public static class HrCalendarDto {
        private LocalDate from;
        private LocalDate to;
        private boolean configured;
        private String weekendDays;
        private int workingDays;
        private int calendarDays;
        private int daysWithEvents;
        private int eventCount;
        /** Set when the calendar was narrowed to one person. */
        private Long employeeId;
        private List<CalendarDayDto> days;
    }

    @Data @Builder
    public static class WorkingCalendarDto {
        private LocalDate from;
        private LocalDate to;
        /**
         * False where the branch has no working policy at all.
         *
         * <p>Then every day is a working day — exactly as the application
         * behaved before the policy table existed — and the screen should say
         * that rather than implying a seven-day week was chosen.
         */
        private boolean configured;
        private String weekendDays;
        private int workingDays;
        private int calendarDays;
        private List<CalendarDayDto> days;
    }

    /* ══ Attendance ═════════════════════════════════════════════════════ */

    @Data @Builder
    public static class AttendanceDto {
        private Long id;
        private Long employeeId;
        private String empId;
        private String empName;
        /**
         * Who this is, at a glance. A register of 2,500 rows needs the
         * photograph and the department to tell two Rahims apart, and
         * fetching a profile per row to get them is not an option.
         */
        private String imagePath;
        private String initials;
        private String departmentName;
        private String shiftName;
        private LocalDate attendanceDate;
        private LocalDateTime checkIn;
        private LocalDateTime checkOut;
        private Integer workingMinutes;
        private Integer lateMinutes;
        private Integer earlyLeaveMinutes;
        private Integer overtimeMinutes;
        /** The same durations in hours, so the screen does not do the division. */
        private BigDecimal workingHours;
        private BigDecimal overtimeHours;
        private String attendanceStatus;
        private String attendanceStatusLabel;
        private String source;
        /** False for a device punch: evidence is overridden, not rewritten. */
        private boolean editable;
        private Long leaveRequestId;
        private String remarks;

        /* ── Correction ──
           correctionStatus REQUESTED means the day is disputed and the live
           values above are still what the register says. The proposed* fields
           are what is being asked for. */
        private String correctionStatus;
        private String proposedStatus;
        private LocalDateTime proposedCheckIn;
        private LocalDateTime proposedCheckOut;
        private Integer proposedOvertimeMinutes;
        private String correctionReason;
        private String correctionRequestedBy;
        private LocalDateTime correctionRequestedAt;
        private String correctedBy;
        private LocalDateTime correctedAt;
    }

    @Data @Builder
    public static class AttendanceDayDto {
        private LocalDate attendanceDate;
        /** Active people at the branch — the denominator of the progress bar. */
        private long total;
        /** Over the whole branch, not the page: the tiles describe the day. */
        private long recorded;
        private long notRecorded;
        private Map<String, Long> byStatus;

        /**
         * One page of the register. {@code rows} keeps its name so the shape
         * the screen reads is the one it always read; the four fields after it
         * say which page this is. {@code matching} is how many people the
         * current search and filters select, which is not {@code total} once a
         * department or "only unrecorded" is chosen.
         */
        private List<AttendanceDto> rows;
        private int page;
        private int size;
        private long matching;
        private int totalPages;
    }

    /** What a fill did: how many rows it wrote, and for whom. */
    @Data @Builder
    public static class AttendanceFillDto {
        private LocalDate attendanceDate;
        private String attendanceStatus;
        private int recorded;
        private long stillUnrecorded;
    }

    /* ══ Leave ══════════════════════════════════════════════════════════ */

    @Data @Builder
    public static class LeaveRequestDto {
        private Long id;
        private String requestNo;
        private Long employeeId;
        private String empId;
        private String empName;
        private Long leaveTypeId;
        private String leaveTypeName;
        private boolean paidLeave;
        private LocalDate fromDate;
        private LocalDate toDate;
        private BigDecimal days;
        private Integer isHalfDay;
        private String reason;
        private String requestStatus;
        private String requestStatusLabel;
        private String submittedBy;
        private LocalDateTime submittedAt;
        private String decidedBy;
        private LocalDateTime decidedAt;
        private String decisionRemarks;
        private String cancelledReason;
        private boolean editable;
    }

    @Data @Builder
    public static class LeaveBalanceDto {
        private Long id;
        private Long employeeId;
        private String empName;
        private Long leaveTypeId;
        private String leaveTypeName;
        private Integer leaveYear;
        private BigDecimal entitled;
        private BigDecimal carriedForward;
        private BigDecimal taken;
        private BigDecimal pending;
        /** entitled + carriedForward − taken − pending */
        private BigDecimal available;
    }

    /* ══ Advance and loan ═══════════════════════════════════════════════ */

    @Data @Builder
    public static class AdvanceDto {
        private Long id;
        private String advanceNo;
        private Long employeeId;
        private String empId;
        private String empName;
        private LocalDate requestDate;
        private BigDecimal amount;
        private BigDecimal approvedAmount;
        private BigDecimal recoveredAmount;
        private BigDecimal outstanding;
        private BigDecimal installmentAmount;
        private Integer installments;
        private LocalDate recoverFrom;
        private String reason;
        private String advanceStatus;
        private String advanceStatusLabel;
        private String decidedBy;
        private LocalDateTime decidedAt;
        private String decisionRemarks;
        private LocalDate paidDate;
        private String paymentMode;
        private String paymentReference;
    }

    @Data @Builder
    public static class LoanDto {
        private Long id;
        private String loanNo;
        private Long employeeId;
        private String empId;
        private String empName;
        private LocalDate requestDate;
        private BigDecimal principal;
        private BigDecimal approvedPrincipal;
        private BigDecimal installmentAmount;
        private Integer installments;
        private BigDecimal recoveredAmount;
        private BigDecimal outstanding;
        /** Recorded, never applied — nothing computes interest. */
        private BigDecimal interestRate;
        private LocalDate startDate;
        private String purpose;
        private String loanStatus;
        private String loanStatusLabel;
        private String decidedBy;
        private LocalDateTime decidedAt;
        private String decisionRemarks;
        private LocalDate disbursedDate;
        private String paymentMode;
        private String paymentReference;
        private List<LoanScheduleDto> schedule;
    }

    @Data @Builder
    public static class LoanScheduleDto {
        private Long id;
        private Integer installmentNo;
        private LocalDate dueDate;
        private BigDecimal dueAmount;
        private BigDecimal recoveredAmount;
        private BigDecimal remaining;
        private String scheduleStatus;
        private String scheduleStatusLabel;
        /** Which run took it — the audit answer to a short payslip. */
        private Long payrollId;
        private LocalDate recoveredOn;
    }

    /* ══ Payroll ════════════════════════════════════════════════════════ */

    @Data @Builder
    public static class PayrollPeriodDto {
        private Long id;
        private Integer periodYear;
        private Integer periodMonth;
        private String label;
        private LocalDate periodFrom;
        private LocalDate periodTo;
        private BigDecimal workingDays;
        private String periodStatus;
        private String remarks;
        /** The live run for this period, if one has been started. */
        private Long payrollId;
        private String payrollStatus;
    }

    @Data @Builder
    public static class PayrollDto {
        private Long id;
        private String payrollNo;
        private Long periodId;
        private String periodLabel;
        private LocalDate periodFrom;
        private LocalDate periodTo;
        private BigDecimal workingDays;
        private String payrollStatus;
        private String payrollStatusLabel;
        private Integer employeeCount;
        private BigDecimal totalGross;
        private BigDecimal totalDeduction;
        private BigDecimal totalTax;
        private BigDecimal totalNet;
        private BigDecimal totalPaid;
        private BigDecimal unpaid;
        private String calculatedBy; private LocalDateTime calculatedAt;
        private String reviewedBy;   private LocalDateTime reviewedAt;
        private String approvedBy;   private LocalDateTime approvedAt;
        private String postedBy;     private LocalDateTime postedAt;
        private String cancelledBy;  private LocalDateTime cancelledAt;
        private String cancelReason;
        private String remarks;
        /** Read-only from here: a journal exists against it. */
        private boolean frozen;
        /** What the caller may do next, so the screen does not re-derive the lifecycle. */
        private List<String> allowedActions;
        private List<PayslipDto> lines;
    }

    @Data @Builder
    public static class PayslipDto {
        private Long id;
        private Long employeeId;
        private String empId;
        private String empName;
        private Long departmentId;
        private String departmentName;
        private Long designationId;
        private String designationName;

        private BigDecimal basicSalary;
        private BigDecimal allowanceTotal;
        private BigDecimal overtimeAmount;
        private BigDecimal bonusAmount;
        private BigDecimal grossSalary;

        private BigDecimal deductionTotal;
        private BigDecimal absenceDeduction;
        private BigDecimal advanceRecovery;
        private BigDecimal loanRecovery;

        /** The employee's provident fund share, already inside deductionTotal. */
        private BigDecimal pfEmployeeAmount;
        /**
         * The company's share. Deliberately outside deductionTotal and netSalary
         * — it is not taken from anybody's salary.
         */
        private BigDecimal pfEmployerAmount;

        private BigDecimal taxAmount;
        private BigDecimal netSalary;

        private BigDecimal presentDays;
        private BigDecimal absentDays;
        private BigDecimal leaveDays;
        private BigDecimal unpaidLeaveDays;
        private Integer overtimeMinutes;
        private BigDecimal overtimeHours;

        private String paymentStatus;
        private BigDecimal paidAmount;
        private BigDecimal unpaid;
        private String remarks;

        /** The arithmetic, so the payslip explains itself. */
        private List<PayslipLineDto> components;
    }

    @Data @Builder
    public static class PayslipLineDto {
        private String componentCode;
        private String componentName;
        private String componentType;
        private String calcMethod;
        /** What a percentage was taken of. Null for a fixed amount. */
        private BigDecimal baseAmount;
        private BigDecimal pctValue;
        private BigDecimal amount;
        private Integer sortOrder;
    }

    @Data @Builder
    public static class SalaryPaymentDto {
        private Long id;
        private String voucherNo;
        private Long payrollId;
        private String payrollNo;
        private Long payrollLineId;
        private Long employeeId;
        private String empName;
        private LocalDate paymentDate;
        private BigDecimal amount;
        private String paymentMode;
        private String paymentModeLabel;
        private String bankName;
        private String chequeNo;
        private LocalDate chequeDate;
        private String referenceNo;
        private String remarks;
        private String paymentStatus;
        private String cancelReason;
    }

    /* ══ Separation ═════════════════════════════════════════════════════ */

    @Data @Builder
    public static class SeparationDto {
        private Long id;
        private Long employeeId;
        private String empId;
        private String empName;
        private String separationType;
        private String separationTypeLabel;
        private LocalDate noticeDate;
        private LocalDate lastWorkingDate;
        private Integer noticePeriodDays;
        private String reason;
        private String separationStatus;
        private String separationStatusLabel;
        private String decidedBy;
        private LocalDateTime decidedAt;
        private String decisionRemarks;
        private String settlementStatus;
        private BigDecimal settlementAmount;
        private LocalDate settlementDate;
        private String remarks;
        private boolean editable;
    }

    /* ══ Dashboard and profile ══════════════════════════════════════════ */

    /** The HR screen's summary. One read, not six. */
    @Data @Builder
    public static class HrSummaryDto {
        private long activeEmployees;
        private long payrollReady;
        /** Active staff with no salary or no structure — payroll skips them. */
        private long notPayrollReady;
        private Map<String, Long> byEmployeeType;
        private long presentToday;
        private long absentToday;
        private long onLeaveToday;
        private long attendanceNotRecordedToday;
        private long pendingLeaveRequests;
        private long pendingAdvances;
        private long pendingLoans;
        private long pendingSeparations;
        /**
         * Claims submitted and undecided.
         *
         * <p>Added after the fact: expense claims (V83) and attendance
         * corrections landed after this summary was written, so the two things
         * most likely to be waiting on somebody were the two it could not
         * count. A sidebar badge and a dashboard tile both read from here, and
         * a count missing from the summary is a count missing from both.
         */
        private long pendingExpenseClaims;
        /** Days somebody is disputing. The register still says what it said. */
        private long pendingAttendanceCorrections;
        private BigDecimal advanceOutstanding;
        private BigDecimal loanOutstanding;
        private PayrollDto latestPayroll;
    }

    /**
     * Everything about one person, in one read.
     *
     * <p>The profile screen would otherwise be five requests that arrive out of
     * order and paint the page in stages.
     */
    @Data @Builder
    public static class EmployeeProfileDto {
        private Long employeeId;
        private String empId;
        private String empName;
        private String preferredName;
        /** Public URL of the photograph, or null. */
        private String imagePath;
        /** Two letters to draw when there is none. */
        private String initials;
        private String empType;
        private String empTypeLabel;
        private String employmentType;
        private String employmentTypeLabel;
        private String designationName;
        private String departmentName;
        private LocalDate hireDate;
        private LocalDate confirmationDate;
        private String phone;
        private String email;
        private String status;

        /* ── The rest of the record (V73/V78). Until this release these columns
           existed and had no form anywhere in the application. ── */
        private LocalDate dateOfBirth;
        private String maritalStatus;
        private String gender;
        private String nationalId;
        private String bloodGroup;
        private String fatherName;
        private String motherName;
        private String emergencyContact;
        private String address;
        private String presentAddress;
        private String grade;
        private Integer probationMonths;
        private Integer noticePeriodDays;
        private Long managerId;
        private String managerName;
        private Long shiftId;
        private String shiftName;
        /** Recorded only — nothing computes a contribution. */
        private Integer pfApplicable;
        private Integer gratuityApplicable;
        private Integer taxApplicable;
        private String vehicleNo;

        private BigDecimal basicSalary;
        private Long salaryStructureId;
        private String salaryStructureName;
        private boolean payrollReady;
        /** Why not, when they are not — the screen should say rather than hint. */
        private String payrollBlockedReason;

        private String salaryPaymentMode;
        private String bankName;
        private String bankBranch;
        private String bankAccountNo;
        private String mobileWalletNo;

        private List<LeaveBalanceDto> leaveBalances;
        private List<AdvanceDto> advances;
        private List<LoanDto> loans;
        private List<PayslipDto> recentPayslips;
        private SeparationDto separation;
    }

    /* ══ Overtime ═══════════════════════════════════════════════════════ */

    @Data @Builder
    public static class OvertimeDto {
        private Long id;
        private String requestNo;
        private Long employeeId;
        private String empId;
        private String empName;
        private LocalDate otDate;

        /** Minutes is what the table holds; hours is what the screen shows. Both travel. */
        private Integer claimedMinutes;
        private BigDecimal claimedHours;
        private Integer approvedMinutes;
        private BigDecimal approvedHours;

        private String reason;
        private String requestStatus;
        private String requestStatusLabel;
        private String submittedBy;
        private LocalDateTime submittedAt;
        private String decidedBy;
        private LocalDateTime decidedAt;
        private String decisionRemarks;

        /** The attendance row the approved minutes were written to — where payroll reads them. */
        private Long attendanceId;
        private LocalDateTime appliedAt;

        private boolean editable;
    }

    /* ══ Increment ══════════════════════════════════════════════════════ */

    @Data @Builder
    public static class IncrementDto {
        private Long id;
        private String incrementNo;
        private Long employeeId;
        private String empId;
        private String empName;

        private String incrementType;
        private String incrementTypeLabel;
        private String incrementBasis;
        private BigDecimal pctValue;
        private BigDecimal fixedAmount;

        /** Indicative while a draft; frozen from approval onwards. */
        private BigDecimal previousSalary;
        private BigDecimal incrementAmount;
        private BigDecimal newSalary;

        private LocalDate effectiveDate;
        private Long newDesignationId;
        private String newGrade;
        private String reason;

        private String incrementStatus;
        private String incrementStatusLabel;
        private String submittedBy;
        private LocalDateTime submittedAt;
        private String decidedBy;
        private LocalDateTime decidedAt;
        private String decisionRemarks;
        private String appliedBy;
        private LocalDateTime appliedAt;

        private boolean editable;
        /** Approved and the effective date has arrived — the Apply button's condition. */
        private boolean applicable;
    }

    /* ══ Provident fund ═════════════════════════════════════════════════ */

    @Data @Builder
    public static class PfSchemeDto {
        private Long id;
        private String schemeName;
        private BigDecimal employeePct;
        private BigDecimal employerPct;
        private String contributionBase;
        private BigDecimal wageCeiling;
        private Integer minServiceMonths;
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
        private boolean current;

        /** False while both rates are still blank — the state a branch ships in. */
        private boolean rateSet;
        /** True only when a rate would actually come off a payslip. */
        private boolean deducting;

        private String remarks;

        /**
         * Deduction components at this branch that look like PF and are not
         * marked as such.
         *
         * <p>Matched by name, and therefore <b>advisory only</b> — a prompt for
         * somebody to tick the right box, never something the calculator acts
         * on. An unmarked component is deducted alongside the scheme, which is
         * how PF gets taken twice.
         */
        private List<String> unmarkedPfComponents;
    }

    @Data @Builder
    public static class PfLedgerDto {
        private Long id;
        private Long employeeId;
        private String empId;
        private String empName;
        private LocalDate entryDate;
        private LocalDate periodFrom;
        private LocalDate periodTo;
        private String entryType;
        private String entryTypeLabel;
        private BigDecimal baseAmount;
        private BigDecimal employeePct;
        private BigDecimal employerPct;
        private BigDecimal employeeAmount;
        private BigDecimal employerAmount;
        /** Negative for a withdrawal or a settlement. The row's effect on the balance. */
        private BigDecimal signedTotal;
        /** Running balance after this row. Set on a statement, null in a search. */
        private BigDecimal balanceAfter;
        private Long payrollId;
        private String referenceNo;
        private String remarks;
        private String recordedBy;
        private LocalDateTime recordedAt;
    }

    @Data @Builder
    public static class PfStatementDto {
        private Long employeeId;
        private String empId;
        private String empName;
        /** Whether PF_APPLICABLE is ticked on the employee. */
        private boolean enrolled;
        /** Whether the branch has a scheme with a rate on it at all. */
        private boolean schemeConfigured;
        private BigDecimal employeeTotal;
        private BigDecimal employerTotal;
        private BigDecimal balance;
        private List<PfLedgerDto> entries;
    }

    /* ══ Gratuity ═══════════════════════════════════════════════════════ */

    @Data @Builder
    public static class GratuityPolicyDto {
        private Long id;
        private String policyName;
        private BigDecimal daysPerYear;
        private BigDecimal daysInMonth;
        private String wageBasis;
        private BigDecimal minServiceYears;
        private BigDecimal maxServiceYears;
        private String partialYearRule;
        private String partialYearRuleLabel;
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
        private boolean current;
        /** False while days per year or the divisor is still blank. Nothing is computed then. */
        private boolean complete;
        private String remarks;
    }

    /**
     * The whole working, whether or not it is ever saved.
     *
     * <p>{@code configured} false is not a zero entitlement — it is the absence
     * of an answer, and the screen says so rather than printing nought.
     */
    @Data @Builder
    public static class GratuityEstimateDto {
        private Long employeeId;
        private String empId;
        private String empName;
        /** Whether GRATUITY_APPLICABLE is ticked on the employee. */
        private boolean entitled;

        private boolean configured;
        /** What is unanswered, in words, when configured is false. */
        private String unansweredPolicy;

        private Long policyId;
        private String policyName;

        private LocalDate serviceFrom;
        private LocalDate serviceTo;
        /** Service actually served, before the part-year rule and any cap. */
        private BigDecimal rawYears;
        /** What the policy counts, after both. */
        private BigDecimal countableYears;
        private boolean qualified;
        private BigDecimal minServiceYears;
        private BigDecimal maxServiceYears;
        private String partialYearRule;

        private String wageBasis;
        private BigDecimal monthlyWage;
        /** Where the wage came from, in words — a payslip or the employee record. */
        private String wageNote;
        private BigDecimal daysInMonth;
        private BigDecimal dailyWage;
        private BigDecimal daysPerYear;
        private BigDecimal daysAwarded;
        private BigDecimal gratuityAmount;
    }

    @Data @Builder
    public static class GratuityAwardDto {
        private Long id;
        private String awardNo;
        private Long employeeId;
        private String empId;
        private String empName;
        private Long separationId;

        private LocalDate serviceFrom;
        private LocalDate serviceTo;
        /** Frozen at approval, along with every money figure below it. */
        private BigDecimal serviceYears;
        private String wageBasis;
        private BigDecimal monthlyWage;
        private BigDecimal daysInMonth;
        private BigDecimal dailyWage;
        private BigDecimal daysPerYear;
        private BigDecimal daysAwarded;
        private BigDecimal gratuityAmount;

        private String awardStatus;
        private String awardStatusLabel;
        private String submittedBy;
        private LocalDateTime submittedAt;
        private String decidedBy;
        private LocalDateTime decidedAt;
        private String decisionRemarks;
        private LocalDate paidDate;
        private String paymentMode;
        private String paymentReference;
        private String reason;

        private boolean editable;
    }

    /* ══ Expense claims ═════════════════════════════════════════════════ */

    @Data @Builder
    public static class ExpenseTypeDto {
        private Long id;
        private String typeCode;
        private String typeName;
        private String description;
        private BigDecimal maxPerClaim;
        private BigDecimal maxPerDay;
        /** False while both caps are blank — the state every branch ships in. */
        private boolean capped;
        private Integer requiresReceipt;
        private Integer sortOrder;
        private String status;
    }

    @Data @Builder
    public static class ExpenseLineDto {
        private Long id;
        private Long expenseTypeId;
        private String typeName;
        private LocalDate expenseDate;
        private String fromPlace;
        private String toPlace;
        private String description;
        private BigDecimal claimedAmount;
        /** Null until the claim is decided; a line may be allowed less than it asked. */
        private BigDecimal approvedAmount;
        /** Public URL of the receipt photograph, or null. */
        private String receiptPath;
        private Integer sortOrder;
    }

    @Data @Builder
    public static class ExpenseClaimDto {
        private Long id;
        private String claimNo;
        private Long employeeId;
        private String empId;
        private String empName;

        private LocalDate claimDate;
        /** The span the lines cover, derived from them. */
        private LocalDate periodFrom;
        private LocalDate periodTo;

        private BigDecimal claimedAmount;
        private BigDecimal approvedAmount;
        private int lineCount;

        private String purpose;
        private String claimStatus;
        private String claimStatusLabel;

        private String submittedBy;
        private LocalDateTime submittedAt;
        private String decidedBy;
        private LocalDateTime decidedAt;
        private String decisionRemarks;

        /**
         * The reimbursement, paid on its own. Deliberately not a payslip figure
         * — see {@code HrExpenseClaim}.
         */
        private LocalDate paidDate;
        private String paymentMode;
        private String paymentReference;

        private boolean editable;
        private List<ExpenseLineDto> lines;
    }

    /* ══ Approval flows ═════════════════════════════════════════════════ */

    @Data @Builder
    public static class ApprovalLevelDto {
        private Long id;
        private Integer levelNo;
        private String approverRole;
        private String levelLabel;
    }

    @Data @Builder
    public static class ApprovalFlowDto {
        private Long id;
        private String workflowCode;
        private String workflowLabel;
        private String flowName;
        /** Null means this chain applies to anything. */
        private BigDecimal minValue;
        private String description;
        private String status;
        private List<ApprovalLevelDto> levels;
    }

    @Data @Builder
    public static class ApprovalActionDto {
        private Integer levelNo;
        private String levelLabel;
        private String decision;
        private String decidedBy;
        private LocalDateTime decidedAt;
        private String remarks;
    }

    @Data @Builder
    public static class ApprovalChainDto {
        private Long id;
        private String workflowCode;
        private String workflowLabel;
        private Long refId;
        private Long employeeId;
        private String flowName;
        /** What routed it to this chain, frozen when the chain began. */
        private BigDecimal routedValue;
        private Integer currentLevel;
        private Integer totalLevels;
        private String progress;
        private String requestStatus;
        private String requestStatusLabel;
        private LocalDateTime startedAt;
        private LocalDateTime closedAt;
        /** Every level's decision, including ones given before a refusal. */
        private List<ApprovalActionDto> actions;
    }

    /**
     * One thing waiting for a decision, from any HR workflow.
     *
     * <p>{@code chained} false means the object is simply submitted with no
     * multi-level chain — the state every module was in before flows existed and
     * still is wherever none is configured.
     */
    @Data @Builder
    public static class ApprovalInboxDto {
        private String workflowCode;
        private String workflowLabel;
        private Long refId;
        private Long employeeId;
        private String empName;
        /** Days, hours or money — whatever the workflow measures itself in. */
        private String summary;
        private String when;

        private boolean chained;
        /** "Level 2 of 3" while a chain is running, null otherwise. */
        private String progress;
        private String flowName;

        /**
         * Whether this reader can sign it now. False for a chain waiting on
         * somebody else — showing it as actionable would be a lie the button
         * then refuses.
         */
        private boolean mine;
    }
}
