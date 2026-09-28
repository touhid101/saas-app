package com.friendspharma.app.entity;

import java.util.List;
import java.util.Locale;

/**
 * The state names HR runs on, in one place.
 *
 * <p>Strings rather than JPA enums, for the same reason {@link EmployeeType}
 * is: the database stays readable, and a value read straight out of psql means
 * something. The cost is that these lists and the CHECK constraints in V73–V76
 * must agree, so every set here names the migration that closes it.
 *
 * <p>Nested classes rather than one flat namespace, because {@code APPROVED}
 * means something slightly different to a leave request and to a loan, and a
 * shared constant would hide that they are separate lifecycles which happen to
 * share a word.
 */
public final class HrConstants {

    private HrConstants() {}

    /** How somebody is engaged — not what they do. See {@link EmployeeType}. */
    public static final class EmploymentType {
        private EmploymentType() {}
        public static final String PERMANENT = "PERMANENT";
        public static final String PROBATION = "PROBATION";
        public static final String CONTRACT  = "CONTRACT";
        public static final String PART_TIME = "PART_TIME";
        public static final String INTERN    = "INTERN";
        public static final String CASUAL    = "CASUAL";
        /** Closed by ck_hr_employees_employment_type (V73). */
        public static final List<String> ALL =
                List.of(PERMANENT, PROBATION, CONTRACT, PART_TIME, INTERN, CASUAL);
    }

    /** Closed by ck_hr_employees_salary_mode (V73) and ck_hr_salpay_mode (V76). */
    public static final class PaymentMode {
        private PaymentMode() {}
        public static final String CASH   = "CASH";
        public static final String BANK   = "BANK";
        public static final String CHEQUE = "CHEQUE";
        public static final String MOBILE = "MOBILE";
        public static final List<String> ALL = List.of(CASH, BANK, CHEQUE, MOBILE);
    }

    public static final class ComponentType {
        private ComponentType() {}
        public static final String EARNING   = "EARNING";
        public static final String DEDUCTION = "DEDUCTION";
        public static final List<String> ALL = List.of(EARNING, DEDUCTION);
    }

    /**
     * How a salary component turns into money.
     *
     * <p>{@code PCT_OF_GROSS} is evaluated against the gross accumulated by the
     * earning lines before it, which is why sort order is part of the contract
     * and not presentation.
     */
    public static final class CalcMethod {
        private CalcMethod() {}
        public static final String FIXED        = "FIXED";
        public static final String PCT_OF_BASIC = "PCT_OF_BASIC";
        public static final String PCT_OF_GROSS = "PCT_OF_GROSS";
        public static final List<String> ALL = List.of(FIXED, PCT_OF_BASIC, PCT_OF_GROSS);
    }

    /** Closed by ck_hr_attendance_status (V74). */
    public static final class AttendanceStatus {
        private AttendanceStatus() {}
        public static final String PRESENT     = "PRESENT";
        public static final String ABSENT      = "ABSENT";
        public static final String LATE        = "LATE";
        public static final String EARLY_LEAVE = "EARLY_LEAVE";
        public static final String HALF_DAY    = "HALF_DAY";
        public static final String LEAVE       = "LEAVE";
        public static final String HOLIDAY     = "HOLIDAY";
        public static final String WEEKEND     = "WEEKEND";
        public static final List<String> ALL = List.of(
                PRESENT, ABSENT, LATE, EARLY_LEAVE, HALF_DAY, LEAVE, HOLIDAY, WEEKEND);

        /** The states payroll counts as a full day worked. */
        public static final List<String> WORKED = List.of(PRESENT, LATE, EARLY_LEAVE);
    }

    public static final class AttendanceSource {
        private AttendanceSource() {}
        public static final String MANUAL = "MANUAL";
        public static final String DEVICE = "DEVICE";
        public static final String IMPORT = "IMPORT";
        public static final String SELF   = "SELF";
        public static final List<String> ALL = List.of(MANUAL, DEVICE, IMPORT, SELF);
        /** Only a typed row may be typed over. A device punch is evidence. */
        public static boolean isEditable(String source) { return MANUAL.equals(source); }
    }

    /** Closed by ck_hr_leave_req_status (V74). */
    public static final class LeaveStatus {
        private LeaveStatus() {}
        public static final String DRAFT     = "DRAFT";
        public static final String SUBMITTED = "SUBMITTED";
        public static final String APPROVED  = "APPROVED";
        public static final String REJECTED  = "REJECTED";
        public static final String CANCELLED = "CANCELLED";
        public static final List<String> ALL =
                List.of(DRAFT, SUBMITTED, APPROVED, REJECTED, CANCELLED);
        /** Holds days against the balance without having consumed them yet. */
        public static boolean holdsBalance(String s) { return SUBMITTED.equals(s); }
    }

    /** Closed by ck_hr_advance_status (V75). */
    public static final class AdvanceStatus {
        private AdvanceStatus() {}
        public static final String DRAFT      = "DRAFT";
        public static final String SUBMITTED  = "SUBMITTED";
        public static final String APPROVED   = "APPROVED";
        public static final String REJECTED   = "REJECTED";
        public static final String PAID       = "PAID";
        public static final String RECOVERING = "RECOVERING";
        public static final String CLOSED     = "CLOSED";
        public static final String CANCELLED  = "CANCELLED";
        public static final List<String> ALL = List.of(
                DRAFT, SUBMITTED, APPROVED, REJECTED, PAID, RECOVERING, CLOSED, CANCELLED);
        /** Money is out and payroll should be taking it back. */
        public static final List<String> RECOVERABLE = List.of(PAID, RECOVERING);
    }

    /** Closed by ck_hr_loan_status (V75). */
    public static final class LoanStatus {
        private LoanStatus() {}
        public static final String DRAFT      = "DRAFT";
        public static final String SUBMITTED  = "SUBMITTED";
        public static final String APPROVED   = "APPROVED";
        public static final String REJECTED   = "REJECTED";
        public static final String DISBURSED  = "DISBURSED";
        public static final String RECOVERING = "RECOVERING";
        public static final String CLOSED     = "CLOSED";
        public static final String CANCELLED  = "CANCELLED";
        public static final List<String> ALL = List.of(
                DRAFT, SUBMITTED, APPROVED, REJECTED, DISBURSED, RECOVERING, CLOSED, CANCELLED);
        public static final List<String> RECOVERABLE = List.of(DISBURSED, RECOVERING);
    }

    public static final class ScheduleStatus {
        private ScheduleStatus() {}
        public static final String PENDING   = "PENDING";
        public static final String PARTIAL   = "PARTIAL";
        public static final String RECOVERED = "RECOVERED";
        public static final String WAIVED    = "WAIVED";
        public static final String CANCELLED = "CANCELLED";
        public static final List<String> ALL =
                List.of(PENDING, PARTIAL, RECOVERED, WAIVED, CANCELLED);
    }

    /**
     * The payroll lifecycle. Closed by ck_hr_payroll_status (V76).
     *
     * <p>{@link #POSTED} is where the run stops being editable: Accounts has a
     * journal against it, and rewriting the run would leave the journal
     * describing something that no longer exists. A correction after that point
     * is a reversal and a fresh run.
     */
    public static final class PayrollStatus {
        private PayrollStatus() {}
        public static final String DRAFT      = "DRAFT";
        public static final String CALCULATED = "CALCULATED";
        public static final String REVIEWED   = "REVIEWED";
        public static final String APPROVED   = "APPROVED";
        public static final String POSTED     = "POSTED";
        public static final String PAID       = "PAID";
        public static final String CANCELLED  = "CANCELLED";
        public static final List<String> ALL = List.of(
                DRAFT, CALCULATED, REVIEWED, APPROVED, POSTED, PAID, CANCELLED);

        /** Financially immutable: no recalculation, no edit, no delete. */
        public static boolean isFrozen(String s) { return POSTED.equals(s) || PAID.equals(s); }

        /** May still be recalculated. */
        public static boolean isOpen(String s) {
            return DRAFT.equals(s) || CALCULATED.equals(s) || REVIEWED.equals(s);
        }
    }

    /** Closed by ck_hr_separation_type (V76). */
    public static final class SeparationType {
        private SeparationType() {}
        public static final String RESIGNATION  = "RESIGNATION";
        public static final String TERMINATION  = "TERMINATION";
        public static final String RETIREMENT   = "RETIREMENT";
        public static final String CONTRACT_END = "CONTRACT_END";
        public static final String DEATH        = "DEATH";
        public static final String OTHER        = "OTHER";
        public static final List<String> ALL = List.of(
                RESIGNATION, TERMINATION, RETIREMENT, CONTRACT_END, DEATH, OTHER);
    }

    public static final class SeparationStatus {
        private SeparationStatus() {}
        public static final String DRAFT     = "DRAFT";
        public static final String SUBMITTED = "SUBMITTED";
        public static final String APPROVED  = "APPROVED";
        public static final String REJECTED  = "REJECTED";
        public static final String CANCELLED = "CANCELLED";
        public static final List<String> ALL =
                List.of(DRAFT, SUBMITTED, APPROVED, REJECTED, CANCELLED);
    }

    public static final class SettlementStatus {
        private SettlementStatus() {}
        public static final String PENDING = "PENDING";
        public static final String SETTLED = "SETTLED";
        public static final String WAIVED  = "WAIVED";
        public static final List<String> ALL = List.of(PENDING, SETTLED, WAIVED);
    }

    public static final class PaySlipStatus {
        private PaySlipStatus() {}
        public static final String UNPAID  = "UNPAID";
        public static final String PARTIAL = "PARTIAL";
        public static final String PAID    = "PAID";
        public static final List<String> ALL = List.of(UNPAID, PARTIAL, PAID);
    }

    /**
     * Accepts what a form is likely to send and returns null for anything not
     * in the set — the same contract as {@link EmployeeType#normalise}, and for
     * the same reason: quietly substituting a fallback hides a client bug, and
     * substituting the wrong one changes what somebody is paid.
     */
    public static String normalise(List<String> allowed, String raw) {
        if (raw == null || raw.isBlank()) return null;
        String t = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return allowed.contains(t) ? t : null;
    }

    /** PART_TIME becomes "Part Time", for messages and pickers. */
    public static String label(String value) {
        if (value == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String w : value.split("_")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(w.charAt(0)).append(w.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }
}
