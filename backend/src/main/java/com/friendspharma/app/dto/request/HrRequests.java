package com.friendspharma.app.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * Everything the HR screens send.
 *
 * <p>One file of nested types rather than twenty files, following
 * {@code MasterDataRequests} and {@code SalesDiscountRequest}: these are shapes
 * on a wire, not domain objects, and scattering them makes the contract harder
 * to read than it makes it modular.
 *
 * <p><b>No request carries a branch or an organisation.</b> Both are resolved
 * from the session. A branch in a request body is a branch a client can change,
 * and the whole tenancy model of this product rests on it not being one.
 */
public final class HrRequests {

    private HrRequests() {}

    /* ══ Salary structure ═══════════════════════════════════════════════ */

    @Data
    public static class SalaryStructure {
        @NotBlank(message = "A name is required.")
        @Size(max = 120)
        private String structureName;

        @Size(max = 500)
        private String description;

        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;

        /**
         * Both null means overtime is recorded but not paid, which is a valid
         * answer and the one this application defaults to. See
         * {@code HrSalaryStructure}.
         */
        @DecimalMin(value = "0.0", inclusive = false, message = "A standard day must be more than zero hours.")
        @DecimalMax(value = "24.0", message = "A standard day cannot be more than 24 hours.")
        private BigDecimal standardHoursPerDay;

        @DecimalMin(value = "0.0", message = "An overtime multiplier cannot be negative.")
        private BigDecimal overtimeMultiplier;

        /** BASIC or GROSS. Blank means BASIC. */
        private String absenceBasis;

        private String status;

        @Valid
        private List<SalaryComponent> components;
    }

    @Data
    public static class SalaryComponent {
        private Long id;

        @NotBlank(message = "A component code is required.")
        @Size(max = 30)
        private String componentCode;

        @NotBlank(message = "A component name is required.")
        @Size(max = 120)
        private String componentName;

        @NotBlank(message = "Say whether this is an earning or a deduction.")
        private String componentType;

        @NotBlank(message = "Say how this component is calculated.")
        private String calcMethod;

        private BigDecimal amount;
        private BigDecimal pctValue;
        private Integer isTaxable;

        /**
         * 1 when this component IS the provident fund deduction.
         *
         * <p>Only matters once the branch has a PF scheme: the calculator then
         * skips this component and takes PF from the scheme, so it is not
         * deducted twice. With no scheme it behaves as any other deduction.
         * Nothing infers this from the component's name.
         */
        private Integer isPf;

        private Integer sortOrder;
    }

    /* ══ Leave type ═════════════════════════════════════════════════════ */

    @Data
    public static class LeaveType {
        @NotBlank(message = "A code is required.") @Size(max = 20)
        private String typeCode;

        @NotBlank(message = "A name is required.") @Size(max = 100)
        private String typeName;

        @Size(max = 500)
        private String description;

        /**
         * Null is allowed and means the quota has not been decided yet — the
         * state V73 seeds. Balances cannot be opened until it is set, which is
         * a question rather than a guess.
         */
        @DecimalMin(value = "0.0", message = "A quota cannot be negative.")
        private BigDecimal annualQuota;

        private Integer isPaid;
        private Integer carryForward;
        private BigDecimal maxCarryForward;
        private Integer requiresApproval;
        private String status;
    }

    /* ══ Working policy, shift and holiday ══════════════════════════════ */

    @Data
    public static class Shift {
        @NotBlank(message = "A shift code is required.") @Size(max = 20)
        private String shiftCode;

        @NotBlank(message = "A shift name is required.") @Size(max = 100)
        private String shiftName;

        /**
         * An end earlier than the start is an overnight shift, not an error.
         * Whether it crosses midnight is derived on the server — it is a
         * conclusion about these two times, and a client able to set it
         * separately could make it disagree with them.
         */
        @NotNull(message = "When does the shift start?")
        @JsonFormat(pattern = "HH:mm")
        private LocalTime startTime;

        @NotNull(message = "When does the shift end?")
        @JsonFormat(pattern = "HH:mm")
        private LocalTime endTime;

        @Min(value = 0, message = "A break cannot be negative.")
        private Integer breakMinutes;

        /** Minutes after the start that are still not late. */
        @Min(value = 0, message = "A grace period cannot be negative.")
        private Integer graceMinutes;

        private String status;
    }

    @Data
    public static class WorkingPolicy {
        @Size(max = 120)
        private String policyName;

        private Long defaultShiftId;

        /**
         * The weekly off, as ISO day numbers or day names: "5,6" or
         * "FRIDAY,SATURDAY".
         *
         * <p><b>Blank is a valid and meaningful answer</b> — it means no weekly
         * off has been decided, which is the state the application ships in and
         * behaves exactly as it did before this existed: every day is a working
         * day. Nothing is defaulted here on the pharmacy's behalf.
         */
        private String weekendDays;

        @Min(value = 0) private Integer lateThresholdMinutes;
        @Min(value = 0) private Integer earlyLeaveThresholdMinutes;
        @Min(value = 1) private Integer halfDayMinutes;
        @Min(value = 1) private Integer minWorkingMinutes;

        /** Whether overtime may be claimed. What it is worth stays on the salary structure. */
        private Integer overtimeAllowed;
        @Min(value = 0) private Integer overtimeMinMinutes;

        /** Defaults to today. A new policy supersedes the live one from this date. */
        private LocalDate effectiveFrom;
    }

    @Data
    public static class Holiday {
        @NotNull(message = "Which date?")
        private LocalDate holidayDate;

        @NotBlank(message = "What is the holiday called?") @Size(max = 150)
        private String holidayName;

        /** GOVERNMENT, PUBLIC, COMPANY, SPECIAL or RELIGIOUS. Blank means COMPANY. */
        private String holidayType;

        /**
         * True narrows the holiday to this branch alone.
         *
         * <p>Default is organisation-wide, because most holidays are — a single
         * shop closing on its own is the exception worth ticking a box for.
         */
        private Boolean branchOnly;

        private Integer isPaid;

        @Size(max = 500)
        private String description;

        private String status;
    }

    /* ══ Attendance ═════════════════════════════════════════════════════ */

    @Data
    public static class Attendance {
        @NotNull(message = "Which employee?")
        private Long employeeId;

        @NotNull(message = "Which date?")
        private LocalDate attendanceDate;

        private LocalDateTime checkIn;
        private LocalDateTime checkOut;

        @NotBlank(message = "An attendance status is required.")
        private String attendanceStatus;

        @Min(value = 0, message = "Minutes cannot be negative.")
        private Integer lateMinutes;

        @Min(value = 0, message = "Minutes cannot be negative.")
        private Integer earlyLeaveMinutes;

        @Min(value = 0, message = "Minutes cannot be negative.")
        private Integer overtimeMinutes;

        /**
         * Working minutes are computed from check-in and check-out when both
         * are given, and only read from here when they are not — a total that
         * disagrees with its own timestamps is worse than no total.
         */
        @Min(value = 0, message = "Minutes cannot be negative.")
        private Integer workingMinutes;

        @Size(max = 500)
        private String remarks;

        /** MANUAL, DEVICE, IMPORT or SELF. Blank means MANUAL. */
        private String source;
    }

    /**
     * A whole day for a branch, marked in one go.
     *
     * <p>This is how attendance is actually taken: one screen, everybody on it,
     * one save. Row by row would be forty requests and forty chances for half
     * the morning to be recorded.
     */
    @Data
    public static class AttendanceDay {
        @NotNull(message = "Which date?")
        private LocalDate attendanceDate;

        @NotEmpty(message = "No rows to record.")
        @Valid
        private List<Attendance> rows;
    }

    /**
     * Mark everyone the filter matches who has no row yet.
     *
     * <p>The way a 2,500-person register gets finished: mark the exceptions by
     * hand, then say "everyone else was present". Done on the server, because
     * the alternative is a browser posting thousands of rows it had to download
     * first. Only the <b>unrecorded</b> are touched — a row somebody already
     * marked, a device punch, a day written by approved leave, all stay as they
     * are.
     */
    @Data
    public static class AttendanceFill {
        @NotNull(message = "Which date?")
        private LocalDate attendanceDate;

        @NotBlank(message = "Mark them as what?")
        private String attendanceStatus;

        /** The same narrowing the register is showing, so what you see is what is filled. */
        private String q;
        private Long departmentId;
        private Long shiftId;
    }

    /**
     * A request to change a day that has already been recorded.
     *
     * <p>The route by which a device punch, or somebody else's entry, gets
     * corrected without being quietly overwritten. What is asked for is held on
     * the row alongside what it currently says; the change is applied only when
     * somebody with Approve says so.
     */
    @Data
    public static class AttendanceCorrection {
        /** What it should say. Blank leaves the status as it is. */
        private String attendanceStatus;

        private LocalDateTime checkIn;
        private LocalDateTime checkOut;

        @Min(value = 0, message = "Minutes cannot be negative.")
        private Integer overtimeMinutes;

        @NotBlank(message = "Say why this day needs correcting.")
        @Size(max = 500)
        private String reason;
    }

    /* ══ Leave ══════════════════════════════════════════════════════════ */

    @Data
    public static class LeaveRequest {
        @NotNull(message = "Which employee?")
        private Long employeeId;

        @NotNull(message = "Which kind of leave?")
        private Long leaveTypeId;

        @NotNull(message = "From which date?")
        private LocalDate fromDate;

        @NotNull(message = "To which date?")
        private LocalDate toDate;

        private Integer isHalfDay;

        @Size(max = 1000)
        private String reason;

        /** True on create means submit straight away rather than save a draft. */
        private Boolean submit;
    }

    @Data
    public static class Decision {
        @Size(max = 1000)
        private String remarks;
    }

    @Data
    public static class LeaveBalance {
        @NotNull(message = "Which employee?")
        private Long employeeId;

        @NotNull(message = "Which kind of leave?")
        private Long leaveTypeId;

        @NotNull(message = "Which year?")
        private Integer leaveYear;

        @DecimalMin(value = "0.0", message = "An entitlement cannot be negative.")
        private BigDecimal entitled;

        @DecimalMin(value = "0.0", message = "A carried-forward figure cannot be negative.")
        private BigDecimal carriedForward;
    }

    /* ══ Advance ════════════════════════════════════════════════════════ */

    @Data
    public static class Advance {
        @NotNull(message = "Which employee?")
        private Long employeeId;

        private LocalDate requestDate;

        @NotNull(message = "How much?")
        @DecimalMin(value = "0.0", inclusive = false, message = "An advance must be more than zero.")
        private BigDecimal amount;

        @Min(value = 1, message = "An advance is recovered over at least one payslip.")
        private Integer installments;

        private LocalDate recoverFrom;

        @Size(max = 1000)
        private String reason;

        private Boolean submit;
    }

    @Data
    public static class AdvanceDecision {
        /** Null means approve the full amount asked for. */
        @DecimalMin(value = "0.0", inclusive = false, message = "An approved amount must be more than zero.")
        private BigDecimal approvedAmount;

        @Size(max = 1000)
        private String remarks;
    }

    /* ══ Loan ═══════════════════════════════════════════════════════════ */

    @Data
    public static class Loan {
        @NotNull(message = "Which employee?")
        private Long employeeId;

        private LocalDate requestDate;

        @NotNull(message = "How much?")
        @DecimalMin(value = "0.0", inclusive = false, message = "A loan must be more than zero.")
        private BigDecimal principal;

        @NotNull(message = "Over how many installments?")
        @Min(value = 1, message = "A loan is repaid over at least one installment.")
        private Integer installments;

        private LocalDate startDate;

        /**
         * Recorded, never applied. Nothing in this application computes
         * interest — see {@code HrLoan}.
         */
        @DecimalMin(value = "0.0", message = "An interest rate cannot be negative.")
        private BigDecimal interestRate;

        @Size(max = 1000)
        private String purpose;

        private Boolean submit;
    }

    @Data
    public static class LoanDecision {
        private BigDecimal approvedPrincipal;

        @Min(value = 1, message = "A loan is repaid over at least one installment.")
        private Integer installments;

        private LocalDate startDate;

        @Size(max = 1000)
        private String remarks;
    }

    /** Money leaving, for an advance or a loan. */
    @Data
    public static class Disbursement {
        private LocalDate paidDate;

        @NotBlank(message = "How was it paid?")
        private String paymentMode;

        @Size(max = 60)
        private String reference;
    }

    /* ══ Payroll ════════════════════════════════════════════════════════ */

    @Data
    public static class PayrollPeriod {
        @NotNull(message = "Which year?")
        @Min(2000) @Max(2999)
        private Integer periodYear;

        @NotNull(message = "Which month?")
        @Min(1) @Max(12)
        private Integer periodMonth;

        /** Both optional: blank means the calendar month. */
        private LocalDate periodFrom;
        private LocalDate periodTo;

        /**
         * Required, because payroll divides by it to value an absence and a
         * guessed figure changes what people are paid. Stored on the period so
         * a run reproduced next year uses the number that was used then.
         */
        @NotNull(message = "How many working days are in this period?")
        @DecimalMin(value = "0.0", inclusive = false, message = "A period has at least one working day.")
        private BigDecimal workingDays;

        @Size(max = 500)
        private String remarks;
    }

    @Data
    public static class PayrollCalculate {
        @NotNull(message = "Which period?")
        private Long periodId;

        /**
         * Ad hoc bonus for this run, keyed by employee id.
         *
         * <p>Per run rather than per employee record, because a festival bonus
         * is a decision about one month and storing it on the employee would
         * make it recur silently.
         */
        private Map<Long, BigDecimal> bonuses;

        /** Leave empty for everybody at the branch who is payroll-ready. */
        private List<Long> employeeIds;

        @Size(max = 1000)
        private String remarks;
    }

    /* ══ Salary payment ═════════════════════════════════════════════════ */

    @Data
    public static class SalaryPayment {
        @NotNull(message = "Which payroll run?")
        private Long payrollId;

        /** Null pays the whole run; set pays one payslip. */
        private Long payrollLineId;

        private LocalDate paymentDate;

        /** Null on a whole-run payment means "everything still owing". */
        @DecimalMin(value = "0.0", inclusive = false, message = "A payment must be more than zero.")
        private BigDecimal amount;

        @NotBlank(message = "How was it paid?")
        private String paymentMode;

        @Size(max = 100) private String bankName;
        @Size(max = 40)  private String chequeNo;
        private LocalDate chequeDate;
        @Size(max = 60)  private String referenceNo;
        @Size(max = 500) private String remarks;
    }

    /* ══ The employee record ════════════════════════════════════════════ */

    /**
     * Everything about a person that HR owns.
     *
     * <p><b>Deliberately not the whole record.</b> Name, code, phone, role,
     * department, designation and vehicle stay on {@code /api/employees}, which
     * has served the Master Data screen since before HR existed and which the
     * delivery list and the invoice salesperson read from. Duplicating them here
     * would be two screens editing one row and two sets of validation.
     *
     * <p>What is here is what that form has no business knowing: the personal
     * detail, the employment terms, and which long-service schemes apply. The
     * pay basis is separate again ({@link PayBasis}) because seeing salaries is
     * a different permission from maintaining a staff record.
     *
     * <p>Every field is optional. A partial update leaves the rest alone, so the
     * three tabs of the form can save independently.
     */
    @Data
    public static class EmployeeDetails {
        /* ── Personal ── */
        @Size(max = 50)  private String preferredName;
        private LocalDate dateOfBirth;
        /** SINGLE, MARRIED, DIVORCED, WIDOWED or OTHER. */
        private String maritalStatus;
        @Size(max = 20)  private String gender;
        @Size(max = 30)  private String nationalId;
        @Size(max = 5)   private String bloodGroup;
        @Size(max = 100) private String fatherName;
        @Size(max = 100) private String motherName;
        @Size(max = 20)  private String emergencyContact;
        @Size(max = 50)  private String email;
        @Size(max = 500) private String address;
        @Size(max = 500) private String presentAddress;

        /* ── Employment ── */
        private LocalDate hireDate;
        private LocalDate confirmationDate;
        /** PERMANENT, PROBATION, CONTRACT, PART_TIME, INTERN or CASUAL. */
        private String employmentType;
        @Size(max = 30) private String grade;

        /**
         * Who this person reports to.
         *
         * <p>Refused if it is themselves — a one-step cycle is the one that
         * happens by accident on a form, and {@code ck_hr_employees_manager}
         * refuses it at the database as well.
         */
        private Long managerId;

        /** The shift they normally work. Null falls back to the branch default. */
        private Long shiftId;

        @Min(value = 0, message = "A probation period cannot be negative.")
        private Integer probationMonths;

        @Min(value = 0, message = "A notice period cannot be negative.")
        private Integer noticePeriodDays;

        /* ── Scheme membership ──
           All three default to off in the database. Enrolling somebody is a
           decision; nothing here computes a contribution, because no rate is
           configured anywhere — see 01-hr-gap-analysis.md, P-07 and P-08. */
        private Integer pfApplicable;
        private Integer gratuityApplicable;
        private Integer taxApplicable;
    }

    /* ══ Pay basis ══════════════════════════════════════════════════════ */

    /**
     * What somebody is paid, and how it reaches them.
     *
     * <p>Separate from the employee form on the Master Data screen, and gated
     * by a different module, because maintaining a staff record and setting a
     * salary are different jobs and often different people.
     */
    @Data
    public static class PayBasis {
        @DecimalMin(value = "0.0", message = "A basic salary cannot be negative.")
        private BigDecimal basicSalary;

        private Long salaryStructureId;
        private String employmentType;
        private LocalDate confirmationDate;

        /** CASH, BANK, CHEQUE or MOBILE. */
        private String salaryPaymentMode;
        @Size(max = 100) private String bankName;
        @Size(max = 100) private String bankBranch;
        @Size(max = 50)  private String bankAccountNo;
        @Size(max = 20)  private String mobileWalletNo;
    }

    /* ══ Separation ═════════════════════════════════════════════════════ */

    @Data
    public static class Separation {
        @NotNull(message = "Which employee?")
        private Long employeeId;

        @NotBlank(message = "Why are they leaving?")
        private String separationType;

        private LocalDate noticeDate;

        @NotNull(message = "What is their last working day?")
        private LocalDate lastWorkingDate;

        @Min(value = 0, message = "A notice period cannot be negative.")
        private Integer noticePeriodDays;

        @Size(max = 1000)
        private String reason;

        private Boolean submit;
    }

    @Data
    public static class Settlement {
        /**
         * Entered, never computed. What belongs in a final settlement — notice
         * pay, leave encashment, gratuity — is policy and in places statute,
         * and this application does not decide it. See {@code HrSeparation}.
         */
        @DecimalMin(value = "0.0", message = "A settlement cannot be negative.")
        private BigDecimal settlementAmount;

        private LocalDate settlementDate;

        /** SETTLED or WAIVED. */
        @NotBlank(message = "Say how the settlement was closed.")
        private String settlementStatus;

        @Size(max = 1000)
        private String remarks;
    }

    /* ══ Overtime ═══════════════════════════════════════════════════════ */

    @Data
    public static class Overtime {
        @NotNull(message = "Which employee?")
        private Long employeeId;

        @NotNull(message = "Which day?")
        private LocalDate otDate;

        /**
         * Minutes, never hours — the same unit the attendance row uses, so the
         * approved figure lands on the register without a conversion in between.
         */
        @NotNull(message = "How much overtime?")
        @Min(value = 1, message = "An overtime claim must be more than zero minutes.")
        @Max(value = 1440, message = "A day has 1440 minutes.")
        private Integer claimedMinutes;

        @Size(max = 1000)
        private String reason;

        /** True on create means submit straight away rather than save a draft. */
        private Boolean submit;
    }

    @Data
    public static class OvertimeDecision {
        /** Null means approve every minute claimed. Never more than was claimed. */
        @Min(value = 1, message = "Approving nothing is a rejection. Reject the claim instead.")
        @Max(value = 1440, message = "A day has 1440 minutes.")
        private Integer approvedMinutes;

        @Size(max = 1000)
        private String remarks;
    }

    /* ══ Increment ══════════════════════════════════════════════════════ */

    @Data
    public static class Increment {
        @NotNull(message = "Which employee?")
        private Long employeeId;

        /** ANNUAL, PROMOTION, PERFORMANCE or ADJUSTMENT. Defaults to ANNUAL. */
        private String incrementType;

        @NotBlank(message = "Is this a percentage or a fixed amount?")
        private String incrementBasis;

        @DecimalMin(value = "0.0", inclusive = false, message = "A percentage must be more than zero.")
        @DecimalMax(value = "1000.0", message = "That percentage looks like a typing mistake.")
        private BigDecimal pctValue;

        @DecimalMin(value = "0.0", inclusive = false, message = "An increment must be more than zero.")
        private BigDecimal fixedAmount;

        @NotNull(message = "From which date?")
        private LocalDate effectiveDate;

        /** A promotion may move these. An annual increment usually moves neither. */
        private Long newDesignationId;

        @Size(max = 30)
        private String newGrade;

        @Size(max = 1000)
        private String reason;

        private Boolean submit;
    }

    @Data
    public static class IncrementDecision {
        /**
         * Null means approve what the proposal works out to against the current
         * salary. A figure here is the approver settling on an amount, and it is
         * what gets frozen — after this the increment is that number, not a
         * percentage to be worked out again later.
         */
        @DecimalMin(value = "0.0", inclusive = false, message = "An increment must be more than zero.")
        private BigDecimal incrementAmount;

        @Size(max = 1000)
        private String remarks;
    }

    /* ══ Provident fund ═════════════════════════════════════════════════ */

    @Data
    public static class PfScheme {
        @Size(max = 120)
        private String schemeName;

        /**
         * Null is allowed and meaningful: it means the rate has not been
         * decided, and no PF is deducted. P-07 says this application does not
         * get to choose it, so the field accepts an answer and never invents
         * one.
         */
        @DecimalMin(value = "0.0", message = "A contribution rate cannot be negative.")
        @DecimalMax(value = "100.0", message = "A contribution rate is a percentage.")
        private BigDecimal employeePct;

        @DecimalMin(value = "0.0", message = "A contribution rate cannot be negative.")
        @DecimalMax(value = "100.0", message = "A contribution rate is a percentage.")
        private BigDecimal employerPct;

        /** BASIC or GROSS. Which wage the percentages apply to. */
        private String contributionBase;

        @DecimalMin(value = "0.0", inclusive = false, message = "A ceiling must be more than zero.")
        private BigDecimal wageCeiling;

        @Min(value = 0, message = "A qualifying period cannot be negative.")
        private Integer minServiceMonths;

        /** Defaults to today. A new scheme supersedes the live one from this date. */
        private LocalDate effectiveFrom;

        @Size(max = 1000)
        private String remarks;
    }

    @Data
    public static class PfEntry {
        @NotNull(message = "Which employee?")
        private Long employeeId;

        /** WITHDRAWAL, ADJUSTMENT or SETTLEMENT. A contribution comes from posting payroll. */
        @NotBlank(message = "What kind of movement?")
        private String entryType;

        private LocalDate entryDate;

        @DecimalMin(value = "0.0", message = "An amount cannot be negative.")
        private BigDecimal employeeAmount;

        @DecimalMin(value = "0.0", message = "An amount cannot be negative.")
        private BigDecimal employerAmount;

        @Size(max = 50)
        private String referenceNo;

        @Size(max = 1000)
        private String remarks;
    }

    /* ══ Gratuity ═══════════════════════════════════════════════════════ */

    @Data
    public static class GratuityPolicy {
        @Size(max = 120)
        private String policyName;

        /**
         * Days of pay per completed year of service. Null means undecided, and
         * nothing is computed for anybody — P-08. Nothing here defaults it.
         */
        @DecimalMin(value = "0.0", inclusive = false, message = "Days per year must be more than zero.")
        @DecimalMax(value = "365.0", message = "That is more than a year of pay per year of service.")
        private BigDecimal daysPerYear;

        /**
         * The divisor that turns a monthly wage into a daily one. Policy in its
         * own right: 26 and 30 give materially different answers on the same
         * salary, so it is entered rather than assumed.
         */
        @DecimalMin(value = "0.0", inclusive = false, message = "The divisor must be more than zero.")
        @DecimalMax(value = "31.0", message = "A month has at most 31 days.")
        private BigDecimal daysInMonth;

        /** BASIC or GROSS. */
        private String wageBasis;

        @DecimalMin(value = "0.0", message = "A qualifying period cannot be negative.")
        private BigDecimal minServiceYears;

        @DecimalMin(value = "0.0", inclusive = false, message = "A service cap must be more than zero.")
        private BigDecimal maxServiceYears;

        /** IGNORE, PRO_RATA or ROUND_UP — what a part year is worth. */
        private String partialYearRule;

        private LocalDate effectiveFrom;

        @Size(max = 1000)
        private String remarks;
    }

    @Data
    public static class GratuityAward {
        @NotNull(message = "Which employee?")
        private Long employeeId;

        /** When this settles somebody leaving. Optional — an award can be made in service. */
        private Long separationId;

        /** Defaults to the employee's hire date. */
        private LocalDate serviceFrom;
        /** Defaults to the separation's last working day, or today. */
        private LocalDate serviceTo;

        @Size(max = 1000)
        private String reason;

        private Boolean submit;
    }

    @Data
    public static class GratuityPayment {
        private LocalDate paidDate;

        @NotBlank(message = "How was it paid?")
        private String paymentMode;

        @Size(max = 50)
        private String paymentReference;
    }

    /* ══ Expense claims ═════════════════════════════════════════════════ */

    @Data
    public static class ExpenseType {
        @NotBlank(message = "A code is required.") @Size(max = 20)
        private String typeCode;

        @NotBlank(message = "A name is required.") @Size(max = 120)
        private String typeName;

        @Size(max = 500)
        private String description;

        /**
         * Null is allowed and meaningful: no cap has been decided, and nothing
         * is refused against a limit nobody set. What a day's conveyance is
         * worth at this pharmacy is not a number this application gets to
         * choose. Sending null on an update <b>clears</b> an existing cap.
         */
        @DecimalMin(value = "0.0", inclusive = false, message = "A cap must be more than zero.")
        private BigDecimal maxPerClaim;

        @DecimalMin(value = "0.0", inclusive = false, message = "A cap must be more than zero.")
        private BigDecimal maxPerDay;

        /** 1 when a photograph of the receipt is expected before submitting. */
        private Integer requiresReceipt;

        private Integer sortOrder;
    }

    @Data
    public static class ExpenseLine {
        @NotNull(message = "What kind of expense?")
        private Long expenseTypeId;

        @NotNull(message = "Which day?")
        private LocalDate expenseDate;

        @Size(max = 120) private String fromPlace;
        @Size(max = 120) private String toPlace;
        @Size(max = 500) private String description;

        @NotNull(message = "How much?")
        @DecimalMin(value = "0.0", inclusive = false, message = "An expense must be more than zero.")
        private BigDecimal claimedAmount;

        /** Carried through when a draft is re-saved, so an attached receipt survives. */
        @Size(max = 300)
        private String receiptPath;
    }

    @Data
    public static class ExpenseClaim {
        @NotNull(message = "Which employee?")
        private Long employeeId;

        private LocalDate claimDate;

        @Size(max = 1000)
        private String purpose;

        @Valid
        private List<ExpenseLine> lines;

        /** True on create means submit straight away rather than save a draft. */
        private Boolean submit;
    }

    @Data
    public static class ExpenseLineDecision {
        @NotNull private Long lineId;

        /** What this line is allowed. Never more than it claimed. */
        @DecimalMin(value = "0.0", message = "A line cannot be approved for less than nothing.")
        private BigDecimal approvedAmount;
    }

    @Data
    public static class ExpenseDecision {
        /**
         * Per-line amounts. Omit a line, or send none at all, to approve what
         * was claimed — an approver who allows the fares and trims the dinner
         * has made two decisions, and this is where the second one goes.
         */
        @Valid
        private List<ExpenseLineDecision> lines;

        @Size(max = 1000)
        private String remarks;
    }

    @Data
    public static class ExpensePayment {
        private LocalDate paidDate;

        @NotBlank(message = "How was it paid?")
        private String paymentMode;

        @Size(max = 60)
        private String paymentReference;
    }

    /* ══ Approval flows ═════════════════════════════════════════════════ */

    @Data
    public static class ApprovalLevel {
        /**
         * A role code, or MANAGER meaning the employee's own manager. A role
         * outlives a person; a named approver does not.
         */
        @NotBlank(message = "Every level needs somebody to sign it.")
        @Size(max = 20)
        private String approverRole;

        @Size(max = 120)
        private String levelLabel;
    }

    @Data
    public static class ApprovalFlow {
        /** LEAVE, OVERTIME, ADVANCE, LOAN, EXPENSE, INCREMENT or GRATUITY. */
        @NotBlank(message = "Which workflow is this for?")
        private String workflowCode;

        @Size(max = 120)
        private String flowName;

        /**
         * The floor this chain applies from, in whatever the workflow measures
         * itself in — days for leave, money for an advance or a claim. Null
         * means it applies to anything, which is the single-chain case.
         */
        @DecimalMin(value = "0.0", message = "A threshold cannot be negative.")
        private BigDecimal minValue;

        @Size(max = 500)
        private String description;

        /** In order. Level numbers are assigned from the order they arrive in. */
        @Valid
        private List<ApprovalLevel> levels;
    }
}
