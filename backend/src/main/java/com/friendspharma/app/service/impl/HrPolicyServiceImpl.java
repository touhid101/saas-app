package com.friendspharma.app.service.impl;

import com.friendspharma.app.dto.request.HrRequests;
import com.friendspharma.app.dto.response.HrResponses;
import com.friendspharma.app.entity.HrHoliday;
import com.friendspharma.app.entity.HrShift;
import com.friendspharma.app.entity.HrWorkingPolicy;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.HrHolidayRepository;
import com.friendspharma.app.repository.HrShiftRepository;
import com.friendspharma.app.repository.HrWorkingPolicyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shifts, the working policy and the holiday calendar.
 *
 * <p>All three are maintained on the Attendance screen, because the register is
 * what they govern: a manager wondering why Friday is not on the sheet should
 * find the answer on the sheet, not three screens away.
 *
 * <h3>A policy is superseded, not edited</h3>
 *
 * <p>Changing a live policy in place would rewrite what last month's attendance
 * was judged against. So an update closes the current policy with an
 * {@code EFFECTIVE_TO} of the day before the new one starts, and inserts a new
 * row. The old one stays and still explains the months it governed.
 *
 * <p>{@code uq_hr_policy_current} enforces the half of this a database can: one
 * open-ended policy per branch.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrPolicyServiceImpl {

    private final HrShiftRepository   shifts;
    private final HrWorkingPolicyRepository policies;
    private final HrHolidayRepository holidays;
    private final HrCalendarService             calendar;
    private final HrScope                       scope;

    /* ══════════════════════════════════════════════════════════════════════
       Shifts
       ══════════════════════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public List<HrResponses.ShiftDto> listShifts(boolean activeOnly) {
        scope.require(HrScope.MOD_ATTENDANCE, PermissionGuard.Right.VIEW, "see shifts");
        Long branch = scope.branchId();
        List<HrShift> all = activeOnly
                ? shifts.findByPidBranchAndStatusOrderByStartTime(branch, "Y")
                : shifts.findByPidBranchOrderByStartTime(branch);
        return all.stream().map(this::map).toList();
    }

    @Transactional
    public HrResponses.ShiftDto createShift(HrRequests.Shift req) {
        scope.require(HrScope.MOD_ATTENDANCE, PermissionGuard.Right.CREATE, "add a shift");

        Long branch = scope.branchId();
        String code = trim(req.getShiftCode()).toUpperCase();
        shifts.findByPidBranchAndShiftCodeIgnoreCase(branch, code).ifPresent(x -> {
            throw new BusinessException("A shift with the code " + code + " already exists here.",
                    HttpStatus.CONFLICT, "HR_SHIFT_EXISTS");
        });

        HrShift s = HrShift.builder()
                .pidOrg(scope.orgId()).pidCompany(scope.companyId()).pidBranch(branch)
                .shiftCode(code).shiftName(trim(req.getShiftName()))
                .build();
        applyShift(s, req);
        s.setStatus("Y");
        return map(shifts.save(s));
    }

    @Transactional
    public HrResponses.ShiftDto updateShift(Long id, HrRequests.Shift req) {
        scope.require(HrScope.MOD_ATTENDANCE, PermissionGuard.Right.EDIT, "change a shift");

        HrShift s = shifts.findByPidShiftAndPidBranch(id, scope.branchId())
                .orElseThrow(() -> new ResourceNotFoundException("Shift", id));

        String code = trim(req.getShiftCode());
        if (code != null && shifts.existsByPidBranchAndShiftCodeIgnoreCaseAndPidShiftNot(
                s.getPidBranch(), code, id)) {
            throw new BusinessException("A shift with the code " + code + " already exists here.",
                    HttpStatus.CONFLICT, "HR_SHIFT_EXISTS");
        }
        if (code != null) s.setShiftCode(code.toUpperCase());
        if (trim(req.getShiftName()) != null) s.setShiftName(trim(req.getShiftName()));
        applyShift(s, req);
        if (req.getStatus() != null) s.setStatus(req.getStatus());
        return map(shifts.save(s));
    }

    @Transactional
    public void retireShift(Long id) {
        scope.require(HrScope.MOD_ATTENDANCE, PermissionGuard.Right.DELETE, "retire a shift");

        HrShift s = shifts.findByPidShiftAndPidBranch(id, scope.branchId())
                .orElseThrow(() -> new ResourceNotFoundException("Shift", id));
        long on = shifts.countEmployeesOn(id);
        if (on > 0) {
            throw new BusinessException(
                    s.getShiftName() + " is still assigned to " + on
                    + (on == 1 ? " employee" : " employees") + ". Move them to another shift first.",
                    HttpStatus.CONFLICT, "HR_SHIFT_IN_USE");
        }
        policies.findCurrent(s.getPidBranch()).ifPresent(p -> {
            if (id.equals(p.getPidDefaultShift())) {
                throw new BusinessException(
                        s.getShiftName() + " is the branch's default shift. Choose another default "
                        + "on the working policy first.",
                        HttpStatus.CONFLICT, "HR_SHIFT_IS_DEFAULT");
            }
        });
        s.setStatus("N");
        shifts.save(s);
    }

    /**
     * Copies a request onto a shift, deriving what should be derived.
     *
     * <p>{@code CROSSES_MIDNIGHT} and {@code WORKING_MINUTES} are computed from
     * the two times rather than accepted from the client — they are conclusions
     * about the times, and a client that could set them independently could make
     * them disagree with the times they describe.
     */
    private void applyShift(HrShift s, HrRequests.Shift req) {
        LocalTime start = req.getStartTime() != null ? req.getStartTime() : s.getStartTime();
        LocalTime end   = req.getEndTime()   != null ? req.getEndTime()   : s.getEndTime();
        if (start == null || end == null) {
            throw new BusinessException("A shift needs a start and an end time.",
                    HttpStatus.BAD_REQUEST, "HR_SHIFT_TIMES_REQUIRED");
        }
        if (start.equals(end)) {
            throw new BusinessException("A shift cannot start and end at the same moment.",
                    HttpStatus.BAD_REQUEST, "HR_SHIFT_ZERO_LENGTH");
        }

        int breakMinutes = req.getBreakMinutes() == null
                ? (s.getBreakMinutes() == null ? 0 : s.getBreakMinutes()) : req.getBreakMinutes();
        int net = HrShift.netMinutes(start, end, breakMinutes);
        if (net <= 0) {
            throw new BusinessException(
                    "The break is longer than the shift. Check the times and the break.",
                    HttpStatus.BAD_REQUEST, "HR_SHIFT_BREAK_TOO_LONG");
        }

        s.setStartTime(start);
        s.setEndTime(end);
        s.setCrossesMidnight(HrShift.spansMidnight(start, end) ? 1 : 0);
        s.setBreakMinutes(breakMinutes);
        s.setGraceMinutes(req.getGraceMinutes() == null
                ? (s.getGraceMinutes() == null ? 0 : s.getGraceMinutes()) : req.getGraceMinutes());
        s.setWorkingMinutes(net);
    }

    /* ══════════════════════════════════════════════════════════════════════
       Working policy
       ══════════════════════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public HrResponses.WorkingPolicyDto currentPolicy() {
        scope.require(HrScope.MOD_ATTENDANCE, PermissionGuard.Right.VIEW, "see the working policy");
        return policies.findCurrent(scope.branchId()).map(this::map).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<HrResponses.WorkingPolicyDto> policyHistory() {
        scope.require(HrScope.MOD_ATTENDANCE, PermissionGuard.Right.VIEW, "see the working policy");
        return policies.findByPidBranchOrderByEffectiveFromDesc(scope.branchId())
                .stream().map(this::map).toList();
    }

    /**
     * Sets the branch's working policy from a date.
     *
     * <p>Supersedes rather than edits: the live policy is closed the day before
     * the new one starts, and a new row is inserted. Attendance already judged
     * under the old policy keeps the rules it was judged under.
     */
    @Transactional
    public HrResponses.WorkingPolicyDto setPolicy(HrRequests.WorkingPolicy req) {
        scope.require(HrScope.MOD_ATTENDANCE, PermissionGuard.Right.EDIT, "set the working policy");

        Long branch = scope.branchId();
        LocalDate from = req.getEffectiveFrom() == null ? LocalDate.now() : req.getEffectiveFrom();

        String weekend = normaliseWeekend(req.getWeekendDays());
        if (req.getDefaultShiftId() != null) {
            shifts.findByPidShiftAndPidBranch(req.getDefaultShiftId(), branch)
                    .filter(HrShift::isActive)
                    .orElseThrow(() -> new BusinessException(
                            "That shift does not exist at this branch, or has been retired.",
                            HttpStatus.BAD_REQUEST, "HR_SHIFT_NOT_FOUND"));
        }

        policies.findCurrent(branch).ifPresent(live -> {
            if (!from.isAfter(live.getEffectiveFrom())) {
                throw new BusinessException(
                        "The current policy took effect on " + live.getEffectiveFrom()
                        + ". A new one has to start after that — otherwise there would be two "
                        + "policies governing the same day.",
                        HttpStatus.CONFLICT, "HR_POLICY_OVERLAP");
            }
            live.setEffectiveTo(from.minusDays(1));
            /* saveAndFlush, not save.

               uq_hr_policy_current allows one row per branch with a null
               EFFECTIVE_TO. Hibernate orders a transaction's inserts before its
               updates, so a plain save() here would insert the new open-ended
               policy while the old one was still open and the index would refuse
               it — a DATA_INTEGRITY_VIOLATION that looks like a duplicate
               policy and is really a flush-order problem. Closing the old row
               first makes the sequence match what the index is checking. */
            policies.saveAndFlush(live);
        });

        HrWorkingPolicy p = HrWorkingPolicy.builder()
                .pidOrg(scope.orgId()).pidCompany(scope.companyId()).pidBranch(branch)
                .policyName(trim(req.getPolicyName()) == null ? "Working policy" : trim(req.getPolicyName()))
                .pidDefaultShift(req.getDefaultShiftId())
                .weekendDays(weekend)
                .lateThresholdMinutes(nz(req.getLateThresholdMinutes()))
                .earlyLeaveThresholdMinutes(nz(req.getEarlyLeaveThresholdMinutes()))
                .halfDayMinutes(req.getHalfDayMinutes())
                .minWorkingMinutes(req.getMinWorkingMinutes())
                .overtimeAllowed(req.getOvertimeAllowed() == null ? 0 : req.getOvertimeAllowed())
                .overtimeMinMinutes(nz(req.getOvertimeMinMinutes()))
                .effectiveFrom(from)
                .build();
        p.setStatus("Y");
        policies.save(p);

        log.info("Working policy set for branch {} from {} — weekend [{}]",
                 branch, from, weekend == null ? "none" : weekend);
        return map(p);
    }

    /**
     * "5,6" or "FRIDAY,SATURDAY" both become "5,6".
     *
     * <p>Null and empty are preserved rather than defaulted, because "no weekly
     * off has been decided" is a real and shipped state — see
     * {@link HrWorkingPolicy}.
     */
    private static String normaliseWeekend(String raw) {
        if (raw == null || raw.isBlank()) return null;
        Set<DayOfWeek> days = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String t = part.trim();
            if (t.isEmpty()) continue;
            try {
                int n = Integer.parseInt(t);
                if (n < 1 || n > 7) throw new NumberFormatException();
                days.add(DayOfWeek.of(n));
            } catch (NumberFormatException notANumber) {
                try {
                    days.add(DayOfWeek.valueOf(t.toUpperCase()));
                } catch (IllegalArgumentException notADay) {
                    throw new BusinessException(
                            "'" + t + "' is not a day. Use 1–7 for Monday to Sunday, or a day name.",
                            HttpStatus.BAD_REQUEST, "HR_INVALID_WEEKEND_DAY");
                }
            }
        }
        if (days.size() == 7) {
            throw new BusinessException(
                    "Every day cannot be a weekly off — the branch would never work.",
                    HttpStatus.BAD_REQUEST, "HR_WEEKEND_ALL_DAYS");
        }
        return HrWorkingPolicy.formatWeekend(days);
    }

    /* ══════════════════════════════════════════════════════════════════════
       Holidays
       ══════════════════════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public List<HrResponses.HolidayDto> listHolidays(Integer year) {
        scope.require(HrScope.MOD_ATTENDANCE, PermissionGuard.Right.VIEW, "see holidays");
        int y = year == null ? LocalDate.now().getYear() : year;
        return holidays.findForYear(scope.orgId(), scope.branchId(), y)
                .stream().map(this::map).toList();
    }

    @Transactional
    public HrResponses.HolidayDto createHoliday(HrRequests.Holiday req) {
        scope.require(HrScope.MOD_ATTENDANCE, PermissionGuard.Right.CREATE, "add a holiday");

        /* Org-wide unless the caller asks for this branch only. The default is
           the wider one because most holidays are: a shop closing on its own is
           the exception worth ticking a box for. */
        Long branch = Boolean.TRUE.equals(req.getBranchOnly()) ? scope.branchId() : null;

        List<HrHoliday> clash = holidays.findClashing(scope.orgId(), branch, req.getHolidayDate(), null);
        if (!clash.isEmpty()) {
            throw new BusinessException(
                    req.getHolidayDate() + " is already a holiday — " + clash.get(0).getHolidayName() + ".",
                    HttpStatus.CONFLICT, "HR_HOLIDAY_EXISTS");
        }

        HrHoliday h = HrHoliday.builder()
                .pidOrg(scope.orgId()).pidCompany(scope.companyId()).pidBranch(branch)
                .holidayDate(req.getHolidayDate())
                .holidayName(trim(req.getHolidayName()))
                .holidayType(requireType(req.getHolidayType()))
                .holidayYear(req.getHolidayDate().getYear())
                .isPaid(req.getIsPaid() == null ? 1 : req.getIsPaid())
                .description(trim(req.getDescription()))
                .build();
        h.setStatus("Y");
        return map(holidays.save(h));
    }

    @Transactional
    public HrResponses.HolidayDto updateHoliday(Long id, HrRequests.Holiday req) {
        scope.require(HrScope.MOD_ATTENDANCE, PermissionGuard.Right.EDIT, "change a holiday");

        HrHoliday h = holidays.findByPidHolidayAndPidOrg(id, scope.orgId())
                .orElseThrow(() -> new ResourceNotFoundException("Holiday", id));

        if (req.getHolidayDate() != null && !req.getHolidayDate().equals(h.getHolidayDate())) {
            List<HrHoliday> clash = holidays.findClashing(
                    scope.orgId(), h.getPidBranch(), req.getHolidayDate(), id);
            if (!clash.isEmpty()) {
                throw new BusinessException(
                        req.getHolidayDate() + " is already a holiday — "
                        + clash.get(0).getHolidayName() + ".",
                        HttpStatus.CONFLICT, "HR_HOLIDAY_EXISTS");
            }
            h.setHolidayDate(req.getHolidayDate());
            // Kept in step by ck_hr_holiday_year, so it moves with the date.
            h.setHolidayYear(req.getHolidayDate().getYear());
        }
        if (trim(req.getHolidayName()) != null) h.setHolidayName(trim(req.getHolidayName()));
        if (req.getHolidayType() != null)       h.setHolidayType(requireType(req.getHolidayType()));
        if (req.getIsPaid() != null)            h.setIsPaid(req.getIsPaid());
        if (req.getDescription() != null)       h.setDescription(trim(req.getDescription()));
        if (req.getStatus() != null)            h.setStatus(req.getStatus());
        return map(holidays.save(h));
    }

    @Transactional
    public void removeHoliday(Long id) {
        scope.require(HrScope.MOD_ATTENDANCE, PermissionGuard.Right.DELETE, "remove a holiday");
        HrHoliday h = holidays.findByPidHolidayAndPidOrg(id, scope.orgId())
                .orElseThrow(() -> new ResourceNotFoundException("Holiday", id));
        h.setStatus("N");
        holidays.save(h);
    }

    private static String requireType(String raw) {
        if (raw == null || raw.isBlank()) return HrHoliday.COMPANY;
        String t = raw.trim().toUpperCase();
        if (!HrHoliday.TYPES.contains(t)) {
            throw new BusinessException(
                    "'" + raw + "' is not a holiday type. Use one of: "
                    + String.join(", ", HrHoliday.TYPES) + ".",
                    HttpStatus.BAD_REQUEST, "HR_INVALID_VALUE");
        }
        return t;
    }

    /* ══════════════════════════════════════════════════════════════════════
       The calendar, as the screens want it
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * What the branch's month looks like: which days are worked and why not.
     *
     * <p>The screen would otherwise ask the policy for the weekend, the holiday
     * table for the rest, and do the intersection itself — which is the third
     * implementation of a question this service exists to answer once.
     */
    @Transactional(readOnly = true)
    public HrResponses.WorkingCalendarDto calendarFor(LocalDate from, LocalDate to) {
        scope.require(HrScope.MOD_ATTENDANCE, PermissionGuard.Right.VIEW, "see the calendar");

        LocalDate f = from == null ? LocalDate.now().withDayOfMonth(1) : from;
        LocalDate t = to == null ? f.plusMonths(1).minusDays(1) : to;
        if (t.isBefore(f)) {
            throw new BusinessException("The end date is before the start date.",
                    HttpStatus.BAD_REQUEST, "HR_DATE_RANGE");
        }

        HrCalendarService.Window w = calendar.window(scope.orgId(), scope.branchId(), f, t);
        List<HrResponses.CalendarDayDto> days = new ArrayList<>();
        int working = 0;
        for (LocalDate d = f; !d.isAfter(t); d = d.plusDays(1)) {
            boolean isWorking = w.isWorkingDay(d);
            if (isWorking) working++;
            HrHoliday h = w.holidayOn(d);
            days.add(HrResponses.CalendarDayDto.builder()
                    .date(d)
                    .workingDay(isWorking)
                    .weekend(w.isWeekend(d))
                    .holiday(h != null)
                    .holidayName(h == null ? null : h.getHolidayName())
                    .paidHoliday(h != null && h.isPaidHoliday())
                    .build());
        }

        return HrResponses.WorkingCalendarDto.builder()
                .from(f).to(t)
                .configured(w.hasPolicy())
                .weekendDays(w.hasPolicy() ? w.policy().getWeekendDays() : null)
                .workingDays(working)
                .calendarDays(days.size())
                .days(days)
                .build();
    }

    /* ══ Mapping ════════════════════════════════════════════════════════ */

    private HrResponses.ShiftDto map(HrShift s) {
        return HrResponses.ShiftDto.builder()
                .id(s.getPidShift())
                .shiftCode(s.getShiftCode()).shiftName(s.getShiftName())
                .startTime(s.getStartTime()).endTime(s.getEndTime())
                .crossesMidnight(s.isOvernight())
                .breakMinutes(s.getBreakMinutes()).graceMinutes(s.getGraceMinutes())
                .workingMinutes(s.getWorkingMinutes())
                .employeesOn(shifts.countEmployeesOn(s.getPidShift()))
                .status(s.getStatus())
                .build();
    }

    private HrResponses.WorkingPolicyDto map(HrWorkingPolicy p) {
        List<String> names = p.weekend().stream()
                .map(d -> d.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH))
                .toList();
        String shiftName = p.getPidDefaultShift() == null ? null
                : shifts.findById(p.getPidDefaultShift()).map(HrShift::getShiftName).orElse(null);

        return HrResponses.WorkingPolicyDto.builder()
                .id(p.getPidPolicy())
                .policyName(p.getPolicyName())
                .defaultShiftId(p.getPidDefaultShift())
                .defaultShiftName(shiftName)
                .weekendDays(p.getWeekendDays())
                .weekendDayNames(names)
                .weekendSet(!p.hasNoWeekend())
                .lateThresholdMinutes(p.getLateThresholdMinutes())
                .earlyLeaveThresholdMinutes(p.getEarlyLeaveThresholdMinutes())
                .halfDayMinutes(p.getHalfDayMinutes())
                .minWorkingMinutes(p.getMinWorkingMinutes())
                .overtimeAllowed(p.allowsOvertime())
                .overtimeMinMinutes(p.getOvertimeMinMinutes())
                .effectiveFrom(p.getEffectiveFrom())
                .effectiveTo(p.getEffectiveTo())
                .current(p.getEffectiveTo() == null && p.isActive())
                .status(p.getStatus())
                .build();
    }

    private HrResponses.HolidayDto map(HrHoliday h) {
        return HrResponses.HolidayDto.builder()
                .id(h.getPidHoliday())
                .holidayDate(h.getHolidayDate())
                .holidayName(h.getHolidayName())
                .holidayType(h.getHolidayType())
                .holidayTypeLabel(com.friendspharma.app.entity.HrConstants.label(h.getHolidayType()))
                .holidayYear(h.getHolidayYear())
                .isPaid(h.getIsPaid())
                .orgWide(h.isOrgWide())
                .description(h.getDescription())
                .status(h.getStatus())
                .build();
    }

    private static int nz(Integer v)   { return v == null ? 0 : v; }
    private static String trim(String s) { return (s == null || s.isBlank()) ? null : s.trim(); }
}
