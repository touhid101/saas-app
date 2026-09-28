package com.friendspharma.app.service.impl;

import com.friendspharma.app.dto.response.HrResponses;
import com.friendspharma.app.entity.*;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * The HR calendar: what is happening at this branch, day by day.
 *
 * <h3>A view, not a table</h3>
 *
 * <p>There is no migration behind this class and there should not be. An HR
 * calendar is holidays, weekends, approved leave, birthdays, service
 * anniversaries, probation ending and increments taking effect — every one of
 * which is already a row somewhere. Copying them into a calendar table would
 * create a second copy to keep in step, and the first time somebody cancelled
 * leave the two would disagree.
 *
 * <h3>Built on the working calendar, not beside it</h3>
 *
 * <p>Whether a day is worked is {@link HrCalendarService}'s question and it
 * stays that way. This class asks it and hangs events off the answer, so the
 * calendar and the payroll run cannot disagree about what a working day is.
 *
 * <h3>What it deliberately does not invent</h3>
 *
 * <p>Probation ending is shown only where the employee has both a hire date and
 * a probation length on record — it is not assumed to be three months, or six.
 * A birthday is shown only where a date of birth was entered. Nothing here fills
 * a blank with a plausible default.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrCalendarViewService {

    /** What kinds of thing land on a day. */
    public static final String HOLIDAY      = "HOLIDAY";
    public static final String LEAVE        = "LEAVE";
    public static final String BIRTHDAY     = "BIRTHDAY";
    public static final String ANNIVERSARY  = "ANNIVERSARY";
    public static final String CONFIRMATION = "CONFIRMATION";
    public static final String INCREMENT    = "INCREMENT";

    /** The most days a single request may span, so one call cannot walk a decade. */
    private static final int MAX_DAYS = 400;

    private final HrCalendarService     calendar;
    private final HrEmployeeRepository  employees;
    private final HrLeaveRequestRepository leaveRepo;
    private final HrLeaveTypeRepository leaveTypes;
    private final HrIncrementRepository increments;
    private final HrScope               scope;

    /**
     * Everything happening between two dates.
     *
     * @param employeeId narrow to one person, or null for the whole branch
     */
    @Transactional(readOnly = true)
    public HrResponses.HrCalendarDto view(LocalDate from, LocalDate to, Long employeeId) {
        scope.require(HrScope.MOD_ATTENDANCE, PermissionGuard.Right.VIEW, "see the HR calendar");

        LocalDate f = from == null ? LocalDate.now().withDayOfMonth(1) : from;
        LocalDate t = to == null ? f.plusMonths(1).minusDays(1) : to;
        if (t.isBefore(f)) {
            throw new BusinessException("The end date is before the start date.",
                    HttpStatus.BAD_REQUEST, "HR_DATE_RANGE");
        }
        if (ChronoUnit.DAYS.between(f, t) + 1 > MAX_DAYS) {
            throw new BusinessException(
                    "A calendar covers at most " + MAX_DAYS + " days at a time.",
                    HttpStatus.BAD_REQUEST, "HR_DATE_RANGE_TOO_WIDE");
        }

        Long branch = scope.branchId();
        HrCalendarService.Window w = calendar.window(scope.orgId(), branch, f, t);

        /* One read each, then everything is assembled in memory — a calendar
           screen that issued a query per day would issue thirty. */
        Map<Long, HrEmployee> staff = staffById(branch, employeeId);
        Map<LocalDate, List<HrResponses.CalendarEventDto>> byDay = new HashMap<>();

        addHolidays(byDay, w, f, t);
        addLeave(byDay, branch, f, t, staff, employeeId);
        addPeopleDates(byDay, f, t, staff);
        addIncrements(byDay, branch, f, t, staff, employeeId);

        List<HrResponses.CalendarDayDto> days = new ArrayList<>();
        int working = 0, withEvents = 0, totalEvents = 0;
        for (LocalDate d = f; !d.isAfter(t); d = d.plusDays(1)) {
            boolean isWorking = w.isWorkingDay(d);
            if (isWorking) working++;
            HrHoliday h = w.holidayOn(d);

            List<HrResponses.CalendarEventDto> events =
                    byDay.getOrDefault(d, List.of()).stream()
                         .sorted(Comparator.comparing(HrResponses.CalendarEventDto::getEventType)
                                           .thenComparing(e -> String.valueOf(e.getTitle())))
                         .toList();
            if (!events.isEmpty()) { withEvents++; totalEvents += events.size(); }

            days.add(HrResponses.CalendarDayDto.builder()
                    .date(d)
                    .workingDay(isWorking)
                    .weekend(w.isWeekend(d))
                    .holiday(h != null)
                    .holidayName(h == null ? null : h.getHolidayName())
                    .paidHoliday(h != null && h.isPaidHoliday())
                    .events(events)
                    .build());
        }

        return HrResponses.HrCalendarDto.builder()
                .from(f).to(t)
                .configured(w.hasPolicy())
                .weekendDays(w.hasPolicy() ? w.policy().getWeekendDays() : null)
                .workingDays(working)
                .calendarDays(days.size())
                .daysWithEvents(withEvents)
                .eventCount(totalEvents)
                .employeeId(employeeId)
                .days(days)
                .build();
    }

    /* ══ The sources ════════════════════════════════════════════════════ */

    private void addHolidays(Map<LocalDate, List<HrResponses.CalendarEventDto>> byDay,
                             HrCalendarService.Window w, LocalDate f, LocalDate t) {
        for (LocalDate d = f; !d.isAfter(t); d = d.plusDays(1)) {
            HrHoliday h = w.holidayOn(d);
            if (h == null) continue;
            add(byDay, d, HrResponses.CalendarEventDto.builder()
                    .eventType(HOLIDAY)
                    .title(h.getHolidayName())
                    .detail(h.isPaidHoliday() ? "Paid holiday" : "Unpaid holiday")
                    .build());
        }
    }

    /**
     * Approved leave, one event per person per day of it.
     *
     * <p>Per day rather than per request, because the question a calendar
     * answers is "who is away on Tuesday" and a request spanning a week cannot
     * answer it without being expanded anyway.
     */
    private void addLeave(Map<LocalDate, List<HrResponses.CalendarEventDto>> byDay,
                          Long branch, LocalDate f, LocalDate t,
                          Map<Long, HrEmployee> staff, Long employeeId) {
        Map<Long, String> typeNames = new HashMap<>();
        for (HrLeaveType lt : leaveTypes.findByPidBranchAndStatusOrderByTypeName(branch, "Y")) {
            typeNames.put(lt.getPidLeaveType(), lt.getTypeName());
        }

        for (HrLeaveRequest r : leaveRepo.findApprovedInRange(branch, f, t)) {
            if (employeeId != null && !employeeId.equals(r.getPidEmployee())) continue;
            HrEmployee e = staff.get(r.getPidEmployee());
            if (e == null) continue;

            LocalDate start = r.getFromDate().isBefore(f) ? f : r.getFromDate();
            LocalDate end   = r.getToDate().isAfter(t)    ? t : r.getToDate();
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                add(byDay, d, HrResponses.CalendarEventDto.builder()
                        .eventType(LEAVE)
                        .employeeId(e.getPidEmployee())
                        .empName(e.getEmpName())
                        .title(e.getEmpName() + " on leave")
                        .detail(typeNames.getOrDefault(r.getPidLeaveType(), "Leave"))
                        .refId(r.getPidLeaveRequest())
                        .build());
            }
        }
    }

    /**
     * Birthdays, service anniversaries and probation ending.
     *
     * <p>Each shown only where the date it needs is actually on the record.
     * Probation in particular: without both a hire date and a probation length
     * there is nothing to compute, and guessing three months would put a
     * confirmation date in somebody's calendar that nobody chose.
     */
    private void addPeopleDates(Map<LocalDate, List<HrResponses.CalendarEventDto>> byDay,
                                LocalDate f, LocalDate t, Map<Long, HrEmployee> staff) {
        for (HrEmployee e : staff.values()) {
            if (e.getDateOfBirth() != null) {
                onEachAnniversary(byDay, f, t, e.getDateOfBirth(), (d, years) ->
                        HrResponses.CalendarEventDto.builder()
                                .eventType(BIRTHDAY)
                                .employeeId(e.getPidEmployee())
                                .empName(e.getEmpName())
                                .title(e.getEmpName() + "'s birthday")
                                .detail(years > 0 ? "Turns " + years : null)
                                .build());
            }
            if (e.getHireDate() != null) {
                onEachAnniversary(byDay, f, t, e.getHireDate(), (d, years) ->
                        years <= 0 ? null : HrResponses.CalendarEventDto.builder()
                                .eventType(ANNIVERSARY)
                                .employeeId(e.getPidEmployee())
                                .empName(e.getEmpName())
                                .title(e.getEmpName() + " — " + years
                                       + (years == 1 ? " year" : " years") + " of service")
                                .detail("Joined " + e.getHireDate())
                                .build());
            }

            /* Probation. Nothing is shown where either figure is missing, and
               nothing is shown for somebody already confirmed. */
            if (e.getHireDate() != null && e.getProbationMonths() != null
                    && e.getProbationMonths() > 0 && e.getConfirmationDate() == null) {
                LocalDate due = e.getHireDate().plusMonths(e.getProbationMonths());
                if (!due.isBefore(f) && !due.isAfter(t)) {
                    add(byDay, due, HrResponses.CalendarEventDto.builder()
                            .eventType(CONFIRMATION)
                            .employeeId(e.getPidEmployee())
                            .empName(e.getEmpName())
                            .title(e.getEmpName() + "'s probation ends")
                            .detail(e.getProbationMonths() + " months from " + e.getHireDate())
                            .build());
                }
            }
        }
    }

    /** Approved increments whose effective date falls in the window. */
    private void addIncrements(Map<LocalDate, List<HrResponses.CalendarEventDto>> byDay,
                               Long branch, LocalDate f, LocalDate t,
                               Map<Long, HrEmployee> staff, Long employeeId) {
        for (HrIncrement i : increments.findByPidBranchAndIncrementStatusAndStatusOrderByEffectiveDate(
                branch, HrIncrement.APPROVED, "Y")) {
            LocalDate d = i.getEffectiveDate();
            if (d == null || d.isBefore(f) || d.isAfter(t)) continue;
            if (employeeId != null && !employeeId.equals(i.getPidEmployee())) continue;
            HrEmployee e = staff.get(i.getPidEmployee());
            if (e == null) continue;

            add(byDay, d, HrResponses.CalendarEventDto.builder()
                    .eventType(INCREMENT)
                    .employeeId(e.getPidEmployee())
                    .empName(e.getEmpName())
                    .title(e.getEmpName() + "'s increment takes effect")
                    .detail(i.getPreviousSalary() + " to " + i.getNewSalary())
                    .refId(i.getPidIncrement())
                    .build());
        }
    }

    /* ══ Helpers ════════════════════════════════════════════════════════ */

    /** Builds an event for a date, or null to skip it. */
    private interface Anniversary {
        HrResponses.CalendarEventDto at(LocalDate date, int years);
    }

    /**
     * Walks the years a recurring date falls on inside the window.
     *
     * <p>29 February lands on the 28th in a common year rather than vanishing:
     * somebody born on a leap day still has a birthday.
     */
    private void onEachAnniversary(Map<LocalDate, List<HrResponses.CalendarEventDto>> byDay,
                                   LocalDate f, LocalDate t, LocalDate origin,
                                   Anniversary build) {
        for (int year = f.getYear(); year <= t.getYear(); year++) {
            LocalDate d;
            try {
                d = origin.withYear(year);
            } catch (java.time.DateTimeException leapDay) {
                d = LocalDate.of(year, origin.getMonthValue(), 28);
            }
            if (d.isBefore(f) || d.isAfter(t)) continue;
            HrResponses.CalendarEventDto event = build.at(d, year - origin.getYear());
            if (event != null) add(byDay, d, event);
        }
    }

    private static void add(Map<LocalDate, List<HrResponses.CalendarEventDto>> byDay,
                            LocalDate d, HrResponses.CalendarEventDto e) {
        byDay.computeIfAbsent(d, k -> new ArrayList<>()).add(e);
    }

    private Map<Long, HrEmployee> staffById(Long branch, Long employeeId) {
        Map<Long, HrEmployee> byId = new HashMap<>();
        for (HrEmployee e : employees.findByPidBranchAndStatusOrderByEmpName(branch, "Y")) {
            if (employeeId != null && !employeeId.equals(e.getPidEmployee())) continue;
            byId.put(e.getPidEmployee(), e);
        }
        return byId;
    }
}
