package com.friendspharma.app.service.impl;

import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.request.HrRequests;
import com.friendspharma.app.dto.response.HrResponses;
import com.friendspharma.app.entity.HrAttendance;
import com.friendspharma.app.entity.HrConstants;
import com.friendspharma.app.entity.HrEmployee;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.HrAttendanceRepository;
import com.friendspharma.app.repository.HrEmployeeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Attendance — one row per employee per day.
 *
 * <h3>The rules this enforces, and where each of them actually lives</h3>
 *
 * <p><b>One row per person per day.</b> Checked here so the user gets a
 * sentence, guaranteed by {@code uq_hr_attendance_day} because two devices
 * posting the same punch at the same moment both pass a check.
 *
 * <p><b>A device punch is evidence.</b> Only a MANUAL row may be edited. A
 * correction to a device row is a new manual row that supersedes it, never a
 * quiet rewrite of what the device recorded.
 *
 * <p><b>Minutes, never hours.</b> Working minutes are computed from the two
 * timestamps when both are present, and only read from the request when they
 * are not — a stated total that disagrees with its own timestamps is worse than
 * no total at all.
 *
 * <p><b>Rows written by the leave module are not touched here.</b> A LEAVE row
 * carries the request that created it; editing it through this screen would let
 * an absence be marked present without the leave being cancelled, and payroll
 * would then count the day twice.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrAttendanceServiceImpl {

    private final HrAttendanceRepository attendanceRepo;
    private final HrEmployeeRepository   employeeRepo;
    private final com.friendspharma.app.repository.AllDepartmentsRepository departmentRepo;
    private final com.friendspharma.app.repository.HrShiftRepository shiftRepo;
    private final HrScope                scope;

    /* ══ Reads ══════════════════════════════════════════════════════════ */

    /**
     * A whole branch for one day, including the people with no row yet.
     *
     * <p>The absences are the point of this screen. A list of the rows that
     * exist answers "who did we record", when the question being asked at nine
     * in the morning is "who have we not".
     */
    @Transactional(readOnly = true)
    public HrResponses.AttendanceDayDto day(LocalDate date) {
        return day(date, null, null, null, null, org.springframework.data.domain.PageRequest.of(0, 50));
    }

    /**
     * One page of the register, with the whole day's counts above it.
     *
     * <p>This used to load every active employee and every row for the date
     * and hand all of it back, which is fine at nine people and a 2,500-row
     * response on every date change at a branch of 2,500. The page is now
     * queried as a page. The counts are <b>not</b> taken from the page: they
     * are the branch's, from a GROUP BY over the day, so the tiles say how the
     * day is going and not how the fifty rows on screen are going.
     *
     * @param filter '' or null for everyone; NOT_RECORDED for people with no
     *               row yet; or an attendance status
     */
    @Transactional(readOnly = true)
    public HrResponses.AttendanceDayDto day(LocalDate date, String q, Long departmentId,
                                            Long shiftId, String filter, Pageable pageable) {
        scope.require(HrScope.MOD_ATTENDANCE, PermissionGuard.Right.VIEW, "see attendance");
        LocalDate on = date == null ? LocalDate.now() : date;
        Long branch = scope.branchId();

        String qq = q == null ? "" : q.trim();
        String ff = filter == null ? "" : filter.trim().toUpperCase();

        // The page of people, then their rows for the day in one query — not a
        // row lookup per person.
        var page = employeeRepo.registerPage(branch, on, qq, departmentId, shiftId, ff, pageable);

        Map<Long, HrAttendance> recorded = new HashMap<>();
        if (!page.isEmpty()) {
            List<Long> ids = page.getContent().stream().map(HrEmployee::getPidEmployee).toList();
            for (HrAttendance a : attendanceRepo.findBranchDay(branch, on)) {
                if (ids.contains(a.getPidEmployee())) recorded.put(a.getPidEmployee(), a);
            }
        }

        // Names for the codes on the row, looked up once for the page rather
        // than once per person.
        Map<Long, String> deptNames = new HashMap<>();
        for (var d : departmentRepo.findByPidOrgAndStatusOrderByDepartmentName(scope.orgId(), "Y")) {
            deptNames.put(d.getPidDepartment(), d.getDepartmentName());
        }
        Map<Long, String> shiftNames = new HashMap<>();
        for (var s : shiftRepo.findByPidBranchOrderByStartTime(branch)) {
            shiftNames.put(s.getPidShift(), s.getShiftName());
        }

        List<HrResponses.AttendanceDto> out = new ArrayList<>(page.getNumberOfElements());
        for (HrEmployee e : page.getContent()) {
            HrAttendance a = recorded.get(e.getPidEmployee());
            HrResponses.AttendanceDto dto = a == null ? blankRow(e, on) : map(a, e);
            dto.setImagePath(e.getImagePath());
            dto.setInitials(e.getInitials());
            dto.setDepartmentName(deptNames.get(e.getPidDepartment()));
            dto.setShiftName(shiftNames.get(e.getPidShift()));
            out.add(dto);
        }

        // The day's shape, over everybody at the branch.
        long total = employeeRepo.countByPidBranchAndStatus(branch, "Y");
        Map<String, Long> byStatus = new LinkedHashMap<>();
        long recordedAll = 0;
        for (Object[] row : attendanceRepo.countByStatusOn(branch, on)) {
            long n = ((Number) row[1]).longValue();
            byStatus.put((String) row[0], n);
            recordedAll += n;
        }

        return HrResponses.AttendanceDayDto.builder()
                .attendanceDate(on)
                .total(total)
                .recorded(recordedAll)
                .notRecorded(Math.max(0, total - recordedAll))
                .byStatus(byStatus)
                .rows(out)
                .page(page.getNumber())
                .size(page.getSize())
                .matching(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .build();
    }

    /**
     * Mark everyone the filter matches who has no row for the day.
     *
     * <p>How a large register is finished: the exceptions are marked by hand,
     * then "everyone else was present" is one request. Only people with
     * <b>no row</b> are touched — a mark already made, a device punch, a day
     * written by an approved leave request are all left exactly as they are,
     * because they already say something and this must not overwrite it.
     *
     * <p>Each row still goes through {@code upsert}, so every rule a single
     * record obeys — the future-date refusal, the leave lock, the branch
     * check — applies to all of them. All or nothing, like {@code recordDay}.
     */
    @Transactional
    public HrResponses.AttendanceFillDto fill(HrRequests.AttendanceFill req) {
        scope.require(HrScope.MOD_ATTENDANCE, PermissionGuard.Right.CREATE, "record attendance");

        LocalDate on = req.getAttendanceDate();
        if (on.isAfter(LocalDate.now())) {
            throw new BusinessException("Attendance cannot be filled in for a day that has not happened.",
                                        HttpStatus.CONFLICT, "HR_ATTENDANCE_FUTURE");
        }
        String status = req.getAttendanceStatus().trim().toUpperCase();
        if (!HrConstants.AttendanceStatus.ALL.contains(status)) {
            throw new BusinessException("'" + req.getAttendanceStatus() + "' is not an attendance status.",
                                        HttpStatus.BAD_REQUEST, "HR_ATTENDANCE_STATUS");
        }

        // LEAVE is written by the leave module against an approved request. A
        // fill typed as LEAVE would count as absences with nothing behind them.
        if (HrConstants.AttendanceStatus.LEAVE.equals(status)) {
            throw new BusinessException(
                    "Leave is recorded by approving a leave request, not by filling the day.",
                    HttpStatus.CONFLICT, "HR_LEAVE_NOT_MARKED_HERE");
        }

        Long branch = scope.branchId();
        String qq = req.getQ() == null ? "" : req.getQ().trim();

        // One statement, whatever the size of the branch. The entity path costs
        // four round trips a person; at 2,500 that measured 111 seconds.
        int written = attendanceRepo.fillDay(
                branch, on, status, qq, req.getDepartmentId(), req.getShiftId(), scope.actor());

        long total = employeeRepo.countByPidBranchAndStatus(branch, "Y");
        long recordedAll = 0;
        for (Object[] r : attendanceRepo.countByStatusOn(branch, on)) {
            recordedAll += ((Number) r[1]).longValue();
        }

        log.info("Attendance filled: {} row(s) as {} on {} at branch {}", written, status, on, branch);
        return HrResponses.AttendanceFillDto.builder()
                .attendanceDate(on)
                .attendanceStatus(status)
                .recorded(written)
                .stillUnrecorded(Math.max(0, total - recordedAll))
                .build();
    }

    @Transactional(readOnly = true)
    public PageResponse<HrResponses.AttendanceDto> search(Long employeeId, LocalDate from, LocalDate to,
                                                          String attStatus, Pageable pageable) {
        scope.require(HrScope.MOD_ATTENDANCE, PermissionGuard.Right.VIEW, "see attendance");
        LocalDate f = from == null ? LocalDate.now().withDayOfMonth(1) : from;
        LocalDate t = to   == null ? f.plusMonths(1).minusDays(1)      : to;
        requireOrder(f, t);

        Map<Long, HrEmployee> staff = employeesByIdForBranch();
        return PageResponse.from(
                attendanceRepo.search(scope.branchId(), employeeId, f, t,
                                      attStatus == null ? "" : attStatus.trim().toUpperCase(), pageable)
                        .map(a -> map(a, staff.get(a.getPidEmployee()))));
    }

    /** One person's month — what a profile shows and what payroll counts. */
    @Transactional(readOnly = true)
    public List<HrResponses.AttendanceDto> forEmployee(Long employeeId, LocalDate from, LocalDate to) {
        scope.require(HrScope.MOD_ATTENDANCE, PermissionGuard.Right.VIEW, "see attendance");
        HrEmployee e = scope.employee(employeeId);
        LocalDate f = from == null ? LocalDate.now().withDayOfMonth(1) : from;
        LocalDate t = to   == null ? f.plusMonths(1).minusDays(1)      : to;
        requireOrder(f, t);

        return attendanceRepo
                .findByPidEmployeeAndAttendanceDateBetweenAndStatusOrderByAttendanceDate(
                        employeeId, f, t, "Y")
                .stream().map(a -> map(a, e)).toList();
    }

    /* ══ Writes ═════════════════════════════════════════════════════════ */

    @Transactional
    public HrResponses.AttendanceDto record(HrRequests.Attendance req) {
        scope.require(HrScope.MOD_ATTENDANCE, PermissionGuard.Right.CREATE, "record attendance");
        return upsert(req, scope.activeEmployee(req.getEmployeeId()));
    }

    /**
     * A whole day marked in one transaction.
     *
     * <p>All or nothing deliberately. A partial save leaves the register saying
     * half the shop was recorded and half was not, and nobody can tell which
     * half failed — so one bad row takes the save down and the screen says
     * which row it was.
     */
    @Transactional
    public HrResponses.AttendanceDayDto recordDay(HrRequests.AttendanceDay req) {
        scope.require(HrScope.MOD_ATTENDANCE, PermissionGuard.Right.CREATE, "record attendance");

        LocalDate on = req.getAttendanceDate();
        for (HrRequests.Attendance row : req.getRows()) {
            // The date on the day wins over anything a row carries, so one save
            // cannot silently scatter rows across several dates.
            row.setAttendanceDate(on);
            upsert(row, scope.activeEmployee(row.getEmployeeId()));
        }
        return day(on);
    }

    @Transactional
    public HrResponses.AttendanceDto update(Long id, HrRequests.Attendance req) {
        scope.require(HrScope.MOD_ATTENDANCE, PermissionGuard.Right.EDIT, "change attendance");

        HrAttendance a = attendanceRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Attendance", id));
        requireOwnBranch(a);
        requireEditable(a);

        HrEmployee e = scope.employee(a.getPidEmployee());
        apply(a, req, e);
        return map(attendanceRepo.save(a), e);
    }

    /**
     * Withdraws a row rather than deleting it.
     *
     * <p>{@code uq_hr_attendance_day} only counts live rows, so the day can be
     * recorded again — while the withdrawn row stays as evidence that somebody
     * marked the day and then changed their mind.
     */
    @Transactional
    public void withdraw(Long id) {
        scope.require(HrScope.MOD_ATTENDANCE, PermissionGuard.Right.DELETE, "remove an attendance record");

        HrAttendance a = attendanceRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Attendance", id));
        requireOwnBranch(a);
        requireEditable(a);
        if (a.getPidLeaveRequest() != null) {
            throw new BusinessException(
                    "This day came from an approved leave request. Cancel the leave instead — "
                    + "removing the attendance alone would leave the leave still counted.",
                    HttpStatus.CONFLICT, "HR_ATTENDANCE_FROM_LEAVE");
        }
        a.setStatus("N");
        attendanceRepo.save(a);
    }

    /* ══ The one write path ═════════════════════════════════════════════ */

    /**
     * Creates the day, or replaces what is on it.
     *
     * <p>One path rather than separate create and update, because the screen
     * saves a whole day at once and does not know which rows already exist —
     * and two paths would be two places for the minute arithmetic to drift.
     */
    private HrResponses.AttendanceDto upsert(HrRequests.Attendance req, HrEmployee e) {
        LocalDate on = req.getAttendanceDate();
        if (on == null) {
            throw new BusinessException("Which date?", HttpStatus.BAD_REQUEST, "HR_DATE_REQUIRED");
        }
        if (on.isAfter(LocalDate.now())) {
            throw new BusinessException(
                    "Attendance cannot be recorded for a day that has not happened yet.",
                    HttpStatus.BAD_REQUEST, "HR_ATTENDANCE_FUTURE");
        }

        Optional<HrAttendance> existing = attendanceRepo
                .findByPidEmployeeAndAttendanceDateAndStatus(e.getPidEmployee(), on, "Y");

        HrAttendance a = existing.orElseGet(() -> {
            HrAttendance fresh = HrAttendance.builder()
                    .pidOrg(scope.orgId())
                    .pidCompany(scope.companyId())
                    .pidBranch(e.getPidBranch())
                    .pidEmployee(e.getPidEmployee())
                    .attendanceDate(on)
                    .build();
            fresh.setStatus("Y");
            return fresh;
        });

        if (existing.isPresent()) {
            requireEditable(a);
            if (a.getPidLeaveRequest() != null) {
                throw new BusinessException(
                        e.getEmpName() + " is on approved leave on " + on
                        + ". Cancel the leave before recording anything else for that day.",
                        HttpStatus.CONFLICT, "HR_ATTENDANCE_FROM_LEAVE");
            }
        }

        apply(a, req, e);
        return map(attendanceRepo.save(a), e);
    }

    /** Copies a request onto a row, computing what should be computed. */
    private void apply(HrAttendance a, HrRequests.Attendance req, HrEmployee e) {
        String status = HrConstants.normalise(
                HrConstants.AttendanceStatus.ALL, req.getAttendanceStatus());
        if (status == null) {
            throw new BusinessException(
                    "'" + req.getAttendanceStatus() + "' is not an attendance status. Use one of: "
                    + String.join(", ", HrConstants.AttendanceStatus.ALL) + ".",
                    HttpStatus.BAD_REQUEST, "HR_INVALID_VALUE");
        }
        // LEAVE is written by the leave module against an approved request. A
        // row typed as LEAVE from this screen would count as an absence with
        // nothing behind it, and no balance would have moved.
        if (HrConstants.AttendanceStatus.LEAVE.equals(status) && a.getPidLeaveRequest() == null) {
            throw new BusinessException(
                    "Leave is recorded by approving a leave request, not by marking the day. "
                    + "File the request on the Leave screen and approve it.",
                    HttpStatus.CONFLICT, "HR_LEAVE_NOT_MARKED_HERE");
        }
        a.setAttendanceStatus(status);

        String source = HrConstants.normalise(HrConstants.AttendanceSource.ALL, req.getSource());
        a.setSource(source == null ? HrConstants.AttendanceSource.MANUAL : source);

        a.setCheckIn(req.getCheckIn());
        a.setCheckOut(req.getCheckOut());
        if (a.getCheckIn() != null && a.getCheckOut() != null
                && a.getCheckOut().isBefore(a.getCheckIn())) {
            throw new BusinessException(
                    e.getEmpName() + " checked out before checking in on " + a.getAttendanceDate() + ".",
                    HttpStatus.BAD_REQUEST, "HR_ATTENDANCE_ORDER");
        }

        a.setLateMinutes(nz(req.getLateMinutes()));
        a.setEarlyLeaveMinutes(nz(req.getEarlyLeaveMinutes()));
        a.setOvertimeMinutes(nz(req.getOvertimeMinutes()));
        a.setWorkingMinutes(workedMinutes(a, req));
        a.setRemarks(req.getRemarks());
    }

    /**
     * Minutes worked.
     *
     * <p>Derived from the timestamps whenever both are there, because a total
     * that disagrees with its own check-in and check-out is a total nobody can
     * trust. Only when the day was recorded without times — which is how most
     * of it is recorded — is the figure taken from the request.
     */
    private static int workedMinutes(HrAttendance a, HrRequests.Attendance req) {
        if (a.getCheckIn() != null && a.getCheckOut() != null) {
            long minutes = Duration.between(a.getCheckIn(), a.getCheckOut()).toMinutes();
            return minutes < 0 ? 0 : (int) minutes;
        }
        return nz(req.getWorkingMinutes());
    }

    /* ══════════════════════════════════════════════════════════════════════
       Correction
       ══════════════════════════════════════════════════════════════════════

       <p>The route by which a day that is already recorded gets changed without
       being quietly overwritten — and the only route for a device punch, which
       {@link #requireEditable} otherwise refuses outright.

       <p><b>The row keeps saying what it said.</b> A request writes the proposal
       into the PROPOSED_ columns and marks the day disputed; the live values do
       not move until somebody with Approve says so. Applying the change first
       and treating approval as "keep it" would show a corrected figure to
       everybody before anybody had agreed to it, and a rejection would have to
       reconstruct the original from nothing. */

    /**
     * Asks for a day to be corrected.
     *
     * <p>Needs only Create, not Edit — the point is that the person who noticed
     * the mistake can raise it. Deciding it needs Approve.
     */
    @Transactional
    public HrResponses.AttendanceDto requestCorrection(Long id, HrRequests.AttendanceCorrection req) {
        scope.require(HrScope.MOD_ATTENDANCE, PermissionGuard.Right.CREATE,
                      "ask for an attendance correction");

        HrAttendance a = attendanceRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Attendance", id));
        requireOwnBranch(a);

        if (a.hasPendingCorrection()) {
            throw new BusinessException(
                    "A correction for this day is already waiting for a decision.",
                    HttpStatus.CONFLICT, "HR_CORRECTION_PENDING");
        }
        if (a.getPidLeaveRequest() != null) {
            throw new BusinessException(
                    "This day came from an approved leave request. Cancel the leave instead — "
                    + "correcting the attendance alone would leave the leave still counted.",
                    HttpStatus.CONFLICT, "HR_ATTENDANCE_FROM_LEAVE");
        }

        String wanted = null;
        if (req.getAttendanceStatus() != null && !req.getAttendanceStatus().isBlank()) {
            wanted = HrConstants.normalise(HrConstants.AttendanceStatus.ALL, req.getAttendanceStatus());
            if (wanted == null) {
                throw new BusinessException(
                        "'" + req.getAttendanceStatus() + "' is not an attendance status.",
                        HttpStatus.BAD_REQUEST, "HR_INVALID_VALUE");
            }
            if (HrConstants.AttendanceStatus.LEAVE.equals(wanted)) {
                throw new BusinessException(
                        "Leave is recorded by approving a leave request, not by correcting a day.",
                        HttpStatus.CONFLICT, "HR_LEAVE_NOT_MARKED_HERE");
            }
        }
        if (req.getCheckIn() != null && req.getCheckOut() != null
                && req.getCheckOut().isBefore(req.getCheckIn())) {
            throw new BusinessException("The proposed check-out is before the check-in.",
                    HttpStatus.BAD_REQUEST, "HR_ATTENDANCE_ORDER");
        }

        // A correction that changes nothing is a note, and there is a field for
        // notes. Saying so is kinder than accepting it and having an approver
        // wonder what they are being asked to agree to.
        boolean changesSomething = wanted != null
                || req.getCheckIn() != null || req.getCheckOut() != null
                || req.getOvertimeMinutes() != null;
        if (!changesSomething) {
            throw new BusinessException(
                    "A correction has to propose a change — a status, a time, or overtime.",
                    HttpStatus.BAD_REQUEST, "HR_CORRECTION_EMPTY");
        }

        a.setProposedStatus(wanted);
        a.setProposedCheckIn(req.getCheckIn());
        a.setProposedCheckOut(req.getCheckOut());
        a.setProposedOvertimeMinutes(req.getOvertimeMinutes());
        a.setCorrectionReason(req.getReason());
        a.setCorrectionStatus(HrAttendance.CORRECTION_REQUESTED);
        a.setCorrectionRequestedBy(scope.actor());
        a.setCorrectionRequestedAt(LocalDateTime.now());
        // Cleared, so a second correction is not decided by whoever settled the first.
        a.setCorrectedBy(null);
        a.setCorrectedAt(null);

        attendanceRepo.save(a);
        log.info("Attendance correction requested for {} on {} by {}",
                 a.getPidEmployee(), a.getAttendanceDate(), scope.actor());
        return map(a, scope.employee(a.getPidEmployee()));
    }

    /** Days waiting for a decision — the queue this workflow exists for. */
    @Transactional(readOnly = true)
    public List<HrResponses.AttendanceDto> pendingCorrections() {
        scope.require(HrScope.MOD_ATTENDANCE, PermissionGuard.Right.VIEW, "see attendance");
        Map<Long, HrEmployee> staff = employeesByIdForBranch();
        return attendanceRepo.findPendingCorrections(scope.branchId())
                .stream().map(a -> map(a, staff.get(a.getPidEmployee()))).toList();
    }

    /**
     * Approves a correction, applying it.
     *
     * <p>Only now do the live values move. Whatever the proposal left null stays
     * as it was — a correction to the times alone should not blank the status.
     */
    @Transactional
    public HrResponses.AttendanceDto approveCorrection(Long id, HrRequests.Decision d) {
        scope.require(HrScope.MOD_ATTENDANCE, PermissionGuard.Right.APPROVE,
                      "approve an attendance correction");

        HrAttendance a = pendingCorrection(id);

        if (a.getProposedStatus() != null)  a.setAttendanceStatus(a.getProposedStatus());
        if (a.getProposedCheckIn() != null) a.setCheckIn(a.getProposedCheckIn());
        if (a.getProposedCheckOut() != null) a.setCheckOut(a.getProposedCheckOut());
        if (a.getProposedOvertimeMinutes() != null) {
            a.setOvertimeMinutes(a.getProposedOvertimeMinutes());
        }

        /* Recomputed from whatever the times now are, by the same rule the
           register uses: a total that disagrees with its own check-in and
           check-out is worse than no total, and a correction is exactly where
           the two could drift apart. */
        if (a.getCheckIn() != null && a.getCheckOut() != null) {
            long minutes = Duration.between(a.getCheckIn(), a.getCheckOut()).toMinutes();
            a.setWorkingMinutes((int) Math.max(0, minutes));
        }

        /* The corrected day is a manual record from here, whatever it was
           before. A device punch that has been overruled is no longer what the
           device said, and leaving it marked DEVICE would make it un-editable
           on the strength of evidence that has just been set aside. */
        a.setSource(HrConstants.AttendanceSource.MANUAL);

        a.setCorrectionStatus(HrAttendance.CORRECTION_APPROVED);
        a.setCorrectedBy(scope.actor());
        a.setCorrectedAt(LocalDateTime.now());
        if (d != null && d.getRemarks() != null && !d.getRemarks().isBlank()) {
            a.setRemarks(d.getRemarks());
        }
        a.clearProposal();

        attendanceRepo.save(a);
        log.info("Attendance correction approved for {} on {} by {}",
                 a.getPidEmployee(), a.getAttendanceDate(), scope.actor());
        return map(a, scope.employee(a.getPidEmployee()));
    }

    /** Rejects a correction. The day is untouched; the request stays on the record. */
    @Transactional
    public HrResponses.AttendanceDto rejectCorrection(Long id, HrRequests.Decision d) {
        scope.require(HrScope.MOD_ATTENDANCE, PermissionGuard.Right.APPROVE,
                      "reject an attendance correction");

        HrAttendance a = pendingCorrection(id);
        a.setCorrectionStatus(HrAttendance.CORRECTION_REJECTED);
        a.setCorrectedBy(scope.actor());
        a.setCorrectedAt(LocalDateTime.now());
        if (d != null && d.getRemarks() != null && !d.getRemarks().isBlank()) {
            a.setCorrectionReason(a.getCorrectionReason() + " — rejected: " + d.getRemarks());
        }
        a.clearProposal();

        attendanceRepo.save(a);
        return map(a, scope.employee(a.getPidEmployee()));
    }

    private HrAttendance pendingCorrection(Long id) {
        HrAttendance a = attendanceRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Attendance", id));
        requireOwnBranch(a);
        if (!a.hasPendingCorrection()) {
            throw new BusinessException(
                    "There is no correction waiting on this day.",
                    HttpStatus.CONFLICT, "HR_NO_CORRECTION_PENDING");
        }
        return a;
    }

    /* ══ Guards ═════════════════════════════════════════════════════════ */

    private void requireOwnBranch(HrAttendance a) {
        Long branch = scope.branchId();
        if (branch != null && !branch.equals(a.getPidBranch())) {
            throw new ResourceNotFoundException("Attendance", a.getPidAttendance());
        }
    }

    private static void requireEditable(HrAttendance a) {
        if (!a.isEditable()) {
            throw new BusinessException(
                    "This day was recorded by a device, and a device punch is evidence. "
                    + "Add a manual record to override it rather than changing what was captured.",
                    HttpStatus.CONFLICT, "HR_ATTENDANCE_NOT_EDITABLE");
        }
    }

    private static void requireOrder(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new BusinessException("The end date is before the start date.",
                    HttpStatus.BAD_REQUEST, "HR_DATE_RANGE");
        }
    }

    /* ══ Mapping ════════════════════════════════════════════════════════ */

    private Map<Long, HrEmployee> employeesByIdForBranch() {
        Map<Long, HrEmployee> byId = new HashMap<>();
        for (HrEmployee e : employeeRepo.findByPidBranchAndStatusOrderByEmpName(scope.branchId(), "Y")) {
            byId.put(e.getPidEmployee(), e);
        }
        return byId;
    }

    private HrResponses.AttendanceDto map(HrAttendance a, HrEmployee e) {
        return HrResponses.AttendanceDto.builder()
                .id(a.getPidAttendance())
                .employeeId(a.getPidEmployee())
                .empId(e == null ? null : e.getEmpId())
                .empName(e == null ? null : e.getEmpName())
                .attendanceDate(a.getAttendanceDate())
                .checkIn(a.getCheckIn())
                .checkOut(a.getCheckOut())
                .workingMinutes(a.getWorkingMinutes())
                .lateMinutes(a.getLateMinutes())
                .earlyLeaveMinutes(a.getEarlyLeaveMinutes())
                .overtimeMinutes(a.getOvertimeMinutes())
                .workingHours(HrAttendance.hours(a.getWorkingMinutes()))
                .overtimeHours(HrAttendance.hours(a.getOvertimeMinutes()))
                .attendanceStatus(a.getAttendanceStatus())
                .attendanceStatusLabel(HrConstants.label(a.getAttendanceStatus()))
                .source(a.getSource())
                .editable(a.isEditable() && a.getPidLeaveRequest() == null && !a.hasPendingCorrection())
                .leaveRequestId(a.getPidLeaveRequest())
                .remarks(a.getRemarks())
                .correctionStatus(a.getCorrectionStatus())
                .proposedStatus(a.getProposedStatus())
                .proposedCheckIn(a.getProposedCheckIn())
                .proposedCheckOut(a.getProposedCheckOut())
                .proposedOvertimeMinutes(a.getProposedOvertimeMinutes())
                .correctionReason(a.getCorrectionReason())
                .correctionRequestedBy(a.getCorrectionRequestedBy())
                .correctionRequestedAt(a.getCorrectionRequestedAt())
                .correctedBy(a.getCorrectedBy())
                .correctedAt(a.getCorrectedAt())
                .build();
    }

    /** A person with nothing recorded yet — the row the screen is there to fill. */
    private static HrResponses.AttendanceDto blankRow(HrEmployee e, LocalDate on) {
        return HrResponses.AttendanceDto.builder()
                .employeeId(e.getPidEmployee())
                .empId(e.getEmpId())
                .empName(e.getEmpName())
                .attendanceDate(on)
                .editable(true)
                .build();
    }

    private static int nz(Integer v) {
        return v == null || v < 0 ? 0 : v;
    }
}
