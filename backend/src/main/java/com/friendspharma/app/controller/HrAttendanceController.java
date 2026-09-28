package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.request.HrRequests;
import com.friendspharma.app.dto.response.HrResponses;
import com.friendspharma.app.service.impl.HrAttendanceServiceImpl;
import com.friendspharma.app.service.impl.HrPolicyServiceImpl;
import com.friendspharma.app.service.impl.HrCalendarViewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Attendance — one row per employee per day.
 *
 * <p>Authorisation is the matrix right on Attendance (MOD33), checked in the
 * service.
 */
@RestController
@RequestMapping("/api/v1/hr/attendance")
@RequiredArgsConstructor
@Tag(name = "HR — Attendance",
     description = "The daily register. One row per person per day, enforced by a unique index "
                 + "as well as by the service.")
public class HrAttendanceController {

    private final HrAttendanceServiceImpl attendance;

    /* Shifts, the weekly off and the holiday calendar live on this screen
       because the register is what they govern: a manager wondering why Friday
       is not on the sheet should find the answer on the sheet. */
    private final HrPolicyServiceImpl policy;

    /** The HR calendar is a view over the same working calendar, not a second one. */
    private final HrCalendarViewService calendarView;

    @GetMapping("/day")
    @Operation(summary = "One page of the register for one day, with the whole day's counts",
               description = "Lists people, not rows, so the ones with no row yet are on it — the "
                           + "absences are the point of this screen. Paged, because a branch of "
                           + "2,500 is not a screen. `filter` narrows to NOT_RECORDED or to one "
                           + "attendance status; the counts above the page always describe the "
                           + "whole branch.")
    public ApiResponse<HrResponses.AttendanceDayDto> day(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Long shiftId,
            @RequestParam(required = false) String filter,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(attendance.day(date, q, departmentId, shiftId, filter,
                                                  PageRequest.of(page, Math.min(Math.max(size, 1), 200))));
    }

    @PostMapping("/day/fill")
    @Operation(summary = "Mark everyone the filter matches who has no row yet",
               description = "How a large register is finished: mark the exceptions, then say "
                           + "everyone else was present. Only people with no row are touched; a "
                           + "mark already made, a device punch or a day written by leave is left "
                           + "alone. All or nothing.")
    public ApiResponse<HrResponses.AttendanceFillDto> fill(
            @Valid @RequestBody HrRequests.AttendanceFill req) {
        var out = attendance.fill(req);
        return ApiResponse.success(out, out.getRecorded() + " marked " + out.getAttendanceStatus());
    }

    @GetMapping
    @Operation(summary = "Search attendance")
    public ApiResponse<PageResponse<HrResponses.AttendanceDto>> search(
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(
                attendance.search(employeeId, from, to, status,
                                  PageRequest.of(page, Math.min(size, 200))));
    }

    @GetMapping("/employee/{employeeId}")
    @Operation(summary = "One person's month")
    public ApiResponse<List<HrResponses.AttendanceDto>> forEmployee(
            @PathVariable Long employeeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(attendance.forEmployee(employeeId, from, to));
    }

    /* ══ The rules the register is judged by ════════════════════════════ */

    @GetMapping("/calendar")
    @Operation(summary = "Which days this branch works, and why not",
               description = "configured=false means the branch has no working policy, and every "
                           + "day is therefore a working day — exactly as the application behaved "
                           + "before the policy table existed.")
    public ApiResponse<HrResponses.WorkingCalendarDto> calendar(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(policy.calendarFor(from, to));
    }

    @GetMapping("/hr-calendar")
    @Operation(summary = "What is happening at this branch, day by day",
               description = "The working calendar with everything hanging off it: holidays, "
                           + "approved leave, birthdays, service anniversaries, probation ending "
                           + "and increments taking effect. Every event comes from a row that "
                           + "already exists — nothing is stored twice, so nothing can fall out "
                           + "of step. Narrow to one person with employeeId.")
    public ApiResponse<HrResponses.HrCalendarDto> hrCalendar(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long employeeId) {
        return ApiResponse.success(calendarView.view(from, to, employeeId));
    }

    @GetMapping("/shifts")
    @Operation(summary = "Shifts at this branch")
    public ApiResponse<List<HrResponses.ShiftDto>> shifts(
            @RequestParam(defaultValue = "true") boolean activeOnly) {
        return ApiResponse.success(policy.listShifts(activeOnly));
    }

    @PostMapping("/shifts")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a shift",
               description = "An end time earlier than the start is an overnight shift, not an "
                           + "error. Whether it crosses midnight, and what a full day of it is "
                           + "worth, are both derived from the times rather than accepted.")
    public ApiResponse<HrResponses.ShiftDto> createShift(@Valid @RequestBody HrRequests.Shift req) {
        return ApiResponse.created(policy.createShift(req));
    }

    @PutMapping("/shifts/{id}")
    @Operation(summary = "Change a shift")
    public ApiResponse<HrResponses.ShiftDto> updateShift(
            @PathVariable Long id, @Valid @RequestBody HrRequests.Shift req) {
        return ApiResponse.success(policy.updateShift(id, req), "Shift updated");
    }

    @DeleteMapping("/shifts/{id}")
    @Operation(summary = "Retire a shift",
               description = "Refused while staff are assigned to it, or while it is the branch "
                           + "default.")
    public ApiResponse<Void> retireShift(@PathVariable Long id) {
        policy.retireShift(id);
        return ApiResponse.success(null, "Shift retired");
    }

    @GetMapping("/policy")
    @Operation(summary = "The working policy in force")
    public ApiResponse<HrResponses.WorkingPolicyDto> currentPolicy() {
        return ApiResponse.success(policy.currentPolicy());
    }

    @GetMapping("/policy/history")
    @Operation(summary = "Every policy this branch has had",
               description = "Superseded policies are kept: last month's attendance was judged "
                           + "under one of them.")
    public ApiResponse<List<HrResponses.WorkingPolicyDto>> policyHistory() {
        return ApiResponse.success(policy.policyHistory());
    }

    @PostMapping("/policy")
    @Operation(summary = "Set the working policy from a date",
               description = "Supersedes rather than edits — the live policy is closed the day "
                           + "before this one starts. Leaving the weekend blank is a valid answer: "
                           + "it means no weekly off has been decided, and leave then counts every "
                           + "calendar day.")
    public ApiResponse<HrResponses.WorkingPolicyDto> setPolicy(
            @Valid @RequestBody HrRequests.WorkingPolicy req) {
        return ApiResponse.success(policy.setPolicy(req), "Working policy set");
    }

    @GetMapping("/holidays")
    @Operation(summary = "Holidays for a year",
               description = "Organisation-wide holidays and this branch's own, together.")
    public ApiResponse<List<HrResponses.HolidayDto>> holidays(
            @RequestParam(required = false) Integer year) {
        return ApiResponse.success(policy.listHolidays(year));
    }

    @PostMapping("/holidays")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a holiday",
               description = "Organisation-wide unless branchOnly is set. No government holidays "
                           + "are seeded: they change every year and are declared by gazette, not "
                           + "derived by formula.")
    public ApiResponse<HrResponses.HolidayDto> createHoliday(
            @Valid @RequestBody HrRequests.Holiday req) {
        return ApiResponse.created(policy.createHoliday(req));
    }

    @PutMapping("/holidays/{id}")
    @Operation(summary = "Change a holiday")
    public ApiResponse<HrResponses.HolidayDto> updateHoliday(
            @PathVariable Long id, @Valid @RequestBody HrRequests.Holiday req) {
        return ApiResponse.success(policy.updateHoliday(id, req), "Holiday updated");
    }

    @DeleteMapping("/holidays/{id}")
    @Operation(summary = "Remove a holiday")
    public ApiResponse<Void> removeHoliday(@PathVariable Long id) {
        policy.removeHoliday(id);
        return ApiResponse.success(null, "Holiday removed");
    }

    /* ══ The register ═══════════════════════════════════════════════════ */

    @PostMapping
    @Operation(summary = "Record one person's day",
               description = "Creates the row, or replaces what is on it. One path rather than a "
                           + "separate create and update, because the caller does not know which "
                           + "it is and two paths would be two places for the arithmetic to drift.")
    public ApiResponse<HrResponses.AttendanceDto> record(
            @Valid @RequestBody HrRequests.Attendance req) {
        return ApiResponse.success(attendance.record(req), "Attendance recorded");
    }

    @PostMapping("/day")
    @Operation(summary = "Record a whole day for the branch",
               description = "All or nothing. A partial save leaves the register saying half the "
                           + "shop was recorded, with nobody able to tell which half failed.")
    public ApiResponse<HrResponses.AttendanceDayDto> recordDay(
            @Valid @RequestBody HrRequests.AttendanceDay req) {
        return ApiResponse.success(attendance.recordDay(req), "Attendance recorded");
    }

    @GetMapping("/corrections")
    @Operation(summary = "Days waiting for a correction decision")
    public ApiResponse<List<HrResponses.AttendanceDto>> pendingCorrections() {
        return ApiResponse.success(attendance.pendingCorrections());
    }

    @PostMapping("/{id}/correction")
    @Operation(summary = "Ask for a recorded day to be corrected",
               description = "The route by which a device punch is challenged without the evidence "
                           + "being rewritten. The row keeps saying what it says; the proposal sits "
                           + "beside it until somebody with Approve decides. Needs only Create, so "
                           + "whoever noticed the mistake can raise it.")
    public ApiResponse<HrResponses.AttendanceDto> requestCorrection(
            @PathVariable Long id, @Valid @RequestBody HrRequests.AttendanceCorrection req) {
        return ApiResponse.success(attendance.requestCorrection(id, req), "Correction requested");
    }

    @PostMapping("/{id}/correction/approve")
    @Operation(summary = "Approve a correction, applying it",
               description = "Only now do the live values move. Whatever the proposal left blank "
                           + "stays as it was, and the worked total is recomputed from the times.")
    public ApiResponse<HrResponses.AttendanceDto> approveCorrection(
            @PathVariable Long id, @RequestBody(required = false) HrRequests.Decision d) {
        return ApiResponse.success(attendance.approveCorrection(id, d), "Correction approved");
    }

    @PostMapping("/{id}/correction/reject")
    @Operation(summary = "Reject a correction",
               description = "The day is untouched; the request stays on the record.")
    public ApiResponse<HrResponses.AttendanceDto> rejectCorrection(
            @PathVariable Long id, @RequestBody(required = false) HrRequests.Decision d) {
        return ApiResponse.success(attendance.rejectCorrection(id, d), "Correction rejected");
    }

    @PutMapping("/{id}")
    @Operation(summary = "Correct an attendance row",
               description = "Only a manually entered row may be changed. A device punch is "
                           + "evidence: override it with a manual record rather than rewriting it.")
    public ApiResponse<HrResponses.AttendanceDto> update(
            @PathVariable Long id, @Valid @RequestBody HrRequests.Attendance req) {
        return ApiResponse.success(attendance.update(id, req), "Attendance updated");
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Withdraw an attendance row",
               description = "Withdrawn, not deleted: the day can be recorded again while the "
                           + "withdrawn row stays as evidence that somebody changed their mind.")
    public ApiResponse<Void> withdraw(@PathVariable Long id) {
        attendance.withdraw(id);
        return ApiResponse.success(null, "Attendance withdrawn");
    }
}
