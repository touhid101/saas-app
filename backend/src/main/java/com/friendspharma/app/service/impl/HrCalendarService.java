package com.friendspharma.app.service.impl;

import com.friendspharma.app.entity.HrHoliday;
import com.friendspharma.app.entity.HrShift;
import com.friendspharma.app.entity.HrWorkingPolicy;
import com.friendspharma.app.repository.HrHolidayRepository;
import com.friendspharma.app.repository.HrShiftRepository;
import com.friendspharma.app.repository.HrWorkingPolicyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Whether a given date is a working day for a given branch.
 *
 * <p>One question, asked by three callers who must all get the same answer:
 * leave counting, payroll's working-day figure, and the attendance register.
 * Three implementations of "is this a Friday off" is two too many.
 *
 * <h3>The contract that makes this safe to add</h3>
 *
 * <p><b>A branch with no policy behaves exactly as the application did before
 * these tables existed:</b> every day is a working day, and nothing changes.
 * The same is true of a policy that names no weekend, which is the state V79
 * ships. Weekends and holidays only start to matter once somebody decides them,
 * which is the whole point of P-04 being a question rather than a default.
 *
 * <h3>A window is read once</h3>
 *
 * <p>{@link #window} pulls the policy and every holiday for a date range in two
 * queries and answers from memory. Payroll asks about thirty-one dates and a
 * leave request about however many it spans; per-date queries would be a round
 * trip each, inside a transaction, for a question whose inputs do not change
 * while it is being asked.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrCalendarService {

    private final HrWorkingPolicyRepository policies;
    private final HrHolidayRepository holidays;
    private final HrShiftRepository   shifts;

    /**
     * A branch's calendar over one range, resolved.
     *
     * <p>Immutable and cheap to pass around. {@code policy} may be null and
     * {@code holidayDates} may be empty — both mean "nothing decided", and every
     * method here treats that as "every day is a working day".
     */
    public static final class Window {
        private final HrWorkingPolicy policy;
        private final Map<LocalDate, HrHoliday> byDate;

        Window(HrWorkingPolicy policy, List<HrHoliday> found) {
            this.policy = policy;
            this.byDate = new HashMap<>();
            for (HrHoliday h : found) {
                // A branch-specific holiday wins over an organisation-wide one on
                // the same date: the branch made the more specific decision.
                HrHoliday existing = byDate.get(h.getHolidayDate());
                if (existing == null || (existing.isOrgWide() && !h.isOrgWide())) {
                    byDate.put(h.getHolidayDate(), h);
                }
            }
        }

        public HrWorkingPolicy policy()      { return policy; }
        public boolean hasPolicy()           { return policy != null; }
        public boolean isWeekend(LocalDate d){ return policy != null && policy.isWeekend(d); }
        public HrHoliday holidayOn(LocalDate d) { return byDate.get(d); }
        public boolean isHoliday(LocalDate d)   { return byDate.containsKey(d); }

        /** Neither a weekend nor a holiday. With nothing configured, always true. */
        public boolean isWorkingDay(LocalDate d) {
            return d != null && !isWeekend(d) && !isHoliday(d);
        }

        /**
         * Working days in a range, inclusive.
         *
         * <p>Counted rather than derived from a formula, because holidays do not
         * follow one.
         */
        public int workingDaysBetween(LocalDate from, LocalDate to) {
            if (from == null || to == null || to.isBefore(from)) return 0;
            int n = 0;
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                if (isWorkingDay(d)) n++;
            }
            return n;
        }

        /** Why a date is not worked, for a message. Null when it is. */
        public String notWorkedBecause(LocalDate d) {
            HrHoliday h = holidayOn(d);
            if (h != null) return h.getHolidayName();
            if (isWeekend(d)) return "weekly off";
            return null;
        }
    }

    /** The calendar for a branch over a range. Two queries, whatever the span. */
    @Transactional(readOnly = true)
    public Window window(Long orgId, Long branchId, LocalDate from, LocalDate to) {
        if (branchId == null || from == null || to == null || to.isBefore(from)) {
            return new Window(null, List.of());
        }
        HrWorkingPolicy policy = policies.findOn(branchId, from)
                .stream().findFirst()
                .orElseGet(() -> policies.findCurrent(branchId).orElse(null));

        List<HrHoliday> found = orgId == null ? List.of()
                : holidays.findInRange(orgId, branchId, from, to);

        return new Window(policy, found);
    }

    /** The calendar for a single date. */
    @Transactional(readOnly = true)
    public Window on(Long orgId, Long branchId, LocalDate date) {
        return window(orgId, branchId, date, date);
    }

    /* ── Convenience, for callers that want one answer ───────────────────── */

    @Transactional(readOnly = true)
    public boolean isWorkingDay(Long orgId, Long branchId, LocalDate date) {
        return on(orgId, branchId, date).isWorkingDay(date);
    }

    /**
     * Working days in a range.
     *
     * <p>Returned as a count, not a suggestion to store. The payroll period
     * keeps its own figure deliberately — see {@code HrPayrollPeriod} — and this
     * is what the screen offers as a starting point.
     */
    @Transactional(readOnly = true)
    public int workingDays(Long orgId, Long branchId, LocalDate from, LocalDate to) {
        return window(orgId, branchId, from, to).workingDaysBetween(from, to);
    }

    /** Leave days in a range, counting only days the branch actually works. */
    @Transactional(readOnly = true)
    public BigDecimal leaveDays(Long orgId, Long branchId, LocalDate from, LocalDate to,
                                boolean halfDay) {
        if (halfDay) return new BigDecimal("0.5");
        Window w = window(orgId, branchId, from, to);

        /* No policy and no holidays means the old behaviour, exactly: every
           calendar day counts. Said explicitly rather than falling out of the
           loop, because it is the compatibility guarantee and deserves to be
           visible. */
        if (!w.hasPolicy() && !hasAnyHoliday(w, from, to)) {
            return BigDecimal.valueOf(java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1);
        }
        return BigDecimal.valueOf(w.workingDaysBetween(from, to));
    }

    private static boolean hasAnyHoliday(Window w, LocalDate from, LocalDate to) {
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (w.isHoliday(d)) return true;
        }
        return false;
    }

    /* ── Shifts ──────────────────────────────────────────────────────────── */

    /**
     * The shift an employee works on a date: their own, or the branch default.
     *
     * <p>Empty where neither is set, and every caller treats that as "judge the
     * day by what was entered" — which is what the register did before shifts
     * existed.
     */
    @Transactional(readOnly = true)
    public Optional<HrShift> shiftFor(Long branchId, Long employeeShiftId, LocalDate date) {
        if (employeeShiftId != null) {
            Optional<HrShift> own = shifts.findByPidShiftAndPidBranch(employeeShiftId, branchId);
            if (own.isPresent() && own.get().isActive()) return own;
        }
        HrWorkingPolicy policy = policies.findOn(branchId, date)
                .stream().findFirst()
                .orElseGet(() -> policies.findCurrent(branchId).orElse(null));
        if (policy == null || policy.getPidDefaultShift() == null) return Optional.empty();

        return shifts.findByPidShiftAndPidBranch(policy.getPidDefaultShift(), branchId)
                .filter(HrShift::isActive);
    }
}
