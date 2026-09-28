package com.friendspharma.app.service.impl;

import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.request.HrRequests;
import com.friendspharma.app.dto.response.HrResponses;
import com.friendspharma.app.entity.*;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Leave — requests, decisions and the balance behind them.
 *
 * <h3>What holds this together</h3>
 *
 * <p><b>A submitted request reserves its days.</b> Submitting moves days into
 * {@code PENDING}, approving moves them from PENDING to {@code TAKEN}, and
 * rejecting or cancelling releases them. Without the pending reservation, two
 * requests filed the same morning both pass the balance check and the second is
 * approved into an overdraft.
 *
 * <p><b>The balance row is locked before it moves.</b> {@code lockFor} takes a
 * row lock so the check and the change are one step. Reading, deciding and then
 * writing without a lock is the same defect one transaction wider.
 *
 * <p><b>Approving writes attendance.</b> An approved absence becomes LEAVE rows
 * on the days it covers, tagged with the request that made them, so payroll can
 * count a month from attendance alone. Cancelling removes exactly those rows
 * rather than every LEAVE row in the range.
 *
 * <p><b>Days are counted once and stored.</b> A half day is half a day, and
 * what was counted at the time is what payroll must read back — recomputing it
 * later against a calendar somebody has edited would change a closed month.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrLeaveServiceImpl {

    private final HrLeaveRequestRepository requestRepo;
    private final HrLeaveBalanceRepository balanceRepo;
    private final HrLeaveTypeRepository    typeRepo;
    private final HrAttendanceRepository   attendanceRepo;
    private final HrEmployeeRepository     employeeRepo;
    private final HrCalendarService        calendar;
    private final HrApprovalService        approvals;
    private final HrScope                  scope;
    private final DocumentNumberService    documentNumbers;

    /** The request number's prefix: LV2609 00001, in the series every document shares. */
    static final String LEAVE_PREFIX = "LV";

    private static final BigDecimal HALF = new BigDecimal("0.5");

    /* ══ Reads ══════════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public PageResponse<HrResponses.LeaveRequestDto> search(Long employeeId, String reqStatus,
                                                            LocalDate from, LocalDate to,
                                                            Pageable pageable) {
        scope.require(HrScope.MOD_LEAVE, PermissionGuard.Right.VIEW, "see leave");
        Map<Long, HrEmployee> staff = staffById();
        Map<Long, HrLeaveType> types = typesById();
        return PageResponse.from(
                requestRepo.search(scope.branchId(), employeeId,
                                   reqStatus == null ? "" : reqStatus.trim().toUpperCase(),
                                   from, to, pageable)
                        .map(r -> map(r, staff.get(r.getPidEmployee()), types.get(r.getPidLeaveType()))));
    }

    /** The approval queue. */
    @Transactional(readOnly = true)
    public List<HrResponses.LeaveRequestDto> pending() {
        scope.require(HrScope.MOD_LEAVE, PermissionGuard.Right.VIEW, "see leave");
        Map<Long, HrEmployee> staff = staffById();
        Map<Long, HrLeaveType> types = typesById();
        return requestRepo.findByPidBranchAndRequestStatusAndStatusOrderByFromDate(
                        scope.branchId(), HrConstants.LeaveStatus.SUBMITTED, "Y")
                .stream()
                .map(r -> map(r, staff.get(r.getPidEmployee()), types.get(r.getPidLeaveType())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<HrResponses.LeaveBalanceDto> balances(Long employeeId, Integer year) {
        scope.require(HrScope.MOD_LEAVE, PermissionGuard.Right.VIEW, "see leave balances");
        HrEmployee e = scope.employee(employeeId);
        int y = year == null ? LocalDate.now().getYear() : year;
        Map<Long, HrLeaveType> types = typesById();
        return balanceRepo.findByPidEmployeeAndLeaveYearOrderByPidLeaveType(employeeId, y)
                .stream().map(b -> map(b, e, types.get(b.getPidLeaveType()))).toList();
    }

    /* ══ Balances ═══════════════════════════════════════════════════════ */

    /**
     * Opens or adjusts one balance.
     *
     * <p>{@code ENTITLED} is copied from the type's quota when no figure is
     * given, and the copy is deliberate: reading it through a join would mean
     * that changing next year's policy silently rewrote what somebody was
     * entitled to last year, and a closed year would stop agreeing with the
     * payslips issued inside it.
     */
    @Transactional
    public HrResponses.LeaveBalanceDto openBalance(HrRequests.LeaveBalance req) {
        scope.require(HrScope.MOD_LEAVE, PermissionGuard.Right.EDIT, "set a leave balance");

        HrEmployee e = scope.activeEmployee(req.getEmployeeId());
        HrLeaveType t = leaveType(req.getLeaveTypeId());

        BigDecimal entitled = req.getEntitled() != null ? req.getEntitled() : t.getAnnualQuota();
        if (entitled == null) {
            throw new BusinessException(
                    "No annual quota has been set for " + t.getTypeName()
                    + ". Set the quota on the leave type, or enter an entitlement for this employee.",
                    HttpStatus.BAD_REQUEST, "HR_LEAVE_QUOTA_UNSET");
        }

        HrLeaveBalance b = balanceRepo
                .findByPidEmployeeAndPidLeaveTypeAndLeaveYear(
                        e.getPidEmployee(), t.getPidLeaveType(), req.getLeaveYear())
                .orElseGet(() -> {
                    HrLeaveBalance fresh = HrLeaveBalance.builder()
                            .pidOrg(scope.orgId())
                            .pidBranch(e.getPidBranch())
                            .pidEmployee(e.getPidEmployee())
                            .pidLeaveType(t.getPidLeaveType())
                            .leaveYear(req.getLeaveYear())
                            .build();
                    fresh.setStatus("Y");
                    return fresh;
                });

        // Lowering an entitlement below what has already been taken would make
        // the balance describe something that did not happen.
        BigDecimal used = nz(b.getTaken()).add(nz(b.getPending()));
        BigDecimal carried = req.getCarriedForward() != null ? req.getCarriedForward() : nz(b.getCarriedForward());
        if (entitled.add(carried).compareTo(used) < 0) {
            throw new BusinessException(
                    e.getEmpName() + " has already used " + used + " days of " + t.getTypeName()
                    + ". The entitlement cannot be set below that.",
                    HttpStatus.CONFLICT, "HR_LEAVE_BALANCE_BELOW_USED");
        }

        b.setEntitled(entitled);
        b.setCarriedForward(carried);
        return map(balanceRepo.save(b), e, t);
    }

    /* ══ The request lifecycle ══════════════════════════════════════════ */

    @Transactional
    public HrResponses.LeaveRequestDto create(HrRequests.LeaveRequest req) {
        scope.require(HrScope.MOD_LEAVE, PermissionGuard.Right.CREATE, "file a leave request");

        HrEmployee e = scope.activeEmployee(req.getEmployeeId());
        HrLeaveType t = leaveType(req.getLeaveTypeId());
        boolean halfDay = req.getIsHalfDay() != null && req.getIsHalfDay() == 1;

        if (req.getToDate().isBefore(req.getFromDate())) {
            throw new BusinessException("The last day of leave is before the first.",
                    HttpStatus.BAD_REQUEST, "HR_DATE_RANGE");
        }
        if (halfDay && !req.getFromDate().equals(req.getToDate())) {
            throw new BusinessException("A half day is one day. Set the same date for both.",
                    HttpStatus.BAD_REQUEST, "HR_LEAVE_HALF_DAY_RANGE");
        }
        requireNoOverlap(e, req.getFromDate(), req.getToDate(), null);

        BigDecimal days = countDays(e, req.getFromDate(), req.getToDate(), halfDay);

        HrLeaveRequest r = HrLeaveRequest.builder()
                .pidOrg(scope.orgId())
                .pidCompany(scope.companyId())
                .pidBranch(e.getPidBranch())
                .pidEmployee(e.getPidEmployee())
                .pidLeaveType(t.getPidLeaveType())
                .fromDate(req.getFromDate())
                .toDate(req.getToDate())
                .days(days)
                .isHalfDay(halfDay ? 1 : 0)
                .reason(req.getReason())
                .requestStatus(HrConstants.LeaveStatus.DRAFT)
                .build();
        r.setStatus("Y");
        requestRepo.save(r);

        if (Boolean.TRUE.equals(req.getSubmit())) submitInternal(r, e, t);
        return map(r, e, t);
    }

    @Transactional
    public HrResponses.LeaveRequestDto update(Long id, HrRequests.LeaveRequest req) {
        scope.require(HrScope.MOD_LEAVE, PermissionGuard.Right.EDIT, "change a leave request");

        HrLeaveRequest r = request(id);
        if (!r.isDraft()) {
            throw new BusinessException(
                    "This request has been submitted. Cancel it and file again rather than "
                    + "changing it under the approver.",
                    HttpStatus.CONFLICT, "HR_LEAVE_NOT_EDITABLE");
        }
        HrEmployee e = scope.employee(r.getPidEmployee());
        HrLeaveType t = leaveType(req.getLeaveTypeId() != null ? req.getLeaveTypeId() : r.getPidLeaveType());

        LocalDate from = req.getFromDate() != null ? req.getFromDate() : r.getFromDate();
        LocalDate to   = req.getToDate()   != null ? req.getToDate()   : r.getToDate();
        boolean halfDay = req.getIsHalfDay() != null
                ? req.getIsHalfDay() == 1
                : (r.getIsHalfDay() != null && r.getIsHalfDay() == 1);

        if (to.isBefore(from)) {
            throw new BusinessException("The last day of leave is before the first.",
                    HttpStatus.BAD_REQUEST, "HR_DATE_RANGE");
        }
        requireNoOverlap(e, from, to, id);

        r.setPidLeaveType(t.getPidLeaveType());
        r.setFromDate(from);
        r.setToDate(to);
        r.setIsHalfDay(halfDay ? 1 : 0);
        r.setDays(countDays(e, from, to, halfDay));
        if (req.getReason() != null) r.setReason(req.getReason());
        requestRepo.save(r);

        if (Boolean.TRUE.equals(req.getSubmit())) submitInternal(r, e, t);
        return map(r, e, t);
    }

    @Transactional
    public HrResponses.LeaveRequestDto submit(Long id) {
        scope.require(HrScope.MOD_LEAVE, PermissionGuard.Right.CREATE, "submit a leave request");
        HrLeaveRequest r = request(id);
        HrEmployee e = scope.employee(r.getPidEmployee());
        HrLeaveType t = leaveType(r.getPidLeaveType());
        submitInternal(r, e, t);
        return map(r, e, t);
    }

    /**
     * Submits, reserving the days against the balance.
     *
     * <p>The balance check happens here rather than at approval because the
     * point of a reservation is that it is taken before anybody decides. A
     * request that could not be honoured should be refused when it is filed,
     * not accepted and then refused a week later.
     */
    private void submitInternal(HrLeaveRequest r, HrEmployee e, HrLeaveType t) {
        if (!r.isDraft()) {
            throw new BusinessException("This request has already been submitted.",
                    HttpStatus.CONFLICT, "HR_LEAVE_ALREADY_SUBMITTED");
        }
        reserve(e, t, r.getFromDate().getYear(), r.getDays());

        r.setRequestStatus(HrConstants.LeaveStatus.SUBMITTED);
        if (r.getRequestNo() == null) r.setRequestNo(documentNumbers.nextInvoiceNo(LEAVE_PREFIX));
        r.setSubmittedBy(scope.actor());
        r.setSubmittedAt(LocalDateTime.now());
        requestRepo.saveAndFlush(r);

        /* Starts a chain if this branch has one for leave, routed on the number
           of days. Does nothing at all where none is configured, which is how
           every branch starts. */
        approvals.begin(HrScope.WF_LEAVE, r.getPidLeaveRequest(), e, r.getDays());
    }

    @Transactional
    public HrResponses.LeaveRequestDto approve(Long id, HrRequests.Decision decision) {
        scope.require(HrScope.MOD_LEAVE, PermissionGuard.Right.APPROVE, "approve leave");

        HrLeaveRequest r = request(id);
        requireSubmitted(r);
        HrEmployee e = scope.employee(r.getPidEmployee());
        HrLeaveType t = leaveType(r.getPidLeaveType());

        /* Where a chain applies, this signature may not be the last one. The
           request stays SUBMITTED and the balance does not move until it is —
           moving days on an intermediate signature would count leave nobody has
           finished approving. */
        if (approvals.decide(HrScope.WF_LEAVE, id, true,
                             decision == null ? null : decision.getRemarks())
                == HrApprovalService.Step.PENDING_NEXT) {
            return map(r, e, t);
        }

        // Pending becomes taken. The days were already reserved at submission,
        // so the available figure does not move and no second check is needed.
        HrLeaveBalance b = lockedBalance(e, t, r.getFromDate().getYear());
        b.setPending(floorAtZero(nz(b.getPending()).subtract(r.getDays())));
        b.setTaken(nz(b.getTaken()).add(r.getDays()));
        balanceRepo.save(b);

        r.setRequestStatus(HrConstants.LeaveStatus.APPROVED);
        r.setDecidedBy(scope.actor());
        r.setDecidedAt(LocalDateTime.now());
        r.setDecisionRemarks(decision == null ? null : decision.getRemarks());
        requestRepo.save(r);

        writeAttendance(r, e);
        return map(r, e, t);
    }

    @Transactional
    public HrResponses.LeaveRequestDto reject(Long id, HrRequests.Decision decision) {
        scope.require(HrScope.MOD_LEAVE, PermissionGuard.Right.APPROVE, "reject leave");

        HrLeaveRequest r = request(id);
        requireSubmitted(r);
        HrEmployee e = scope.employee(r.getPidEmployee());
        HrLeaveType t = leaveType(r.getPidLeaveType());

        /* One refusal ends a chain wherever it had got to, so this needs no
           branch: the request is refused either way. */
        approvals.decide(HrScope.WF_LEAVE, id, false,
                         decision == null ? null : decision.getRemarks());

        release(e, t, r.getFromDate().getYear(), r.getDays());

        r.setRequestStatus(HrConstants.LeaveStatus.REJECTED);
        r.setDecidedBy(scope.actor());
        r.setDecidedAt(LocalDateTime.now());
        r.setDecisionRemarks(decision == null ? null : decision.getRemarks());
        requestRepo.save(r);
        return map(r, e, t);
    }

    /**
     * Cancels a request, whatever stage it reached.
     *
     * <p>An approved request gives its days back and takes its attendance rows
     * with it — exactly the rows it created, found by the request id rather
     * than by date, so a colleague's leave on the same days is untouched.
     */
    @Transactional
    public HrResponses.LeaveRequestDto cancel(Long id, HrRequests.Decision decision) {
        scope.require(HrScope.MOD_LEAVE, PermissionGuard.Right.DELETE, "cancel leave");

        HrLeaveRequest r = request(id);
        if (HrConstants.LeaveStatus.CANCELLED.equals(r.getRequestStatus())
                || HrConstants.LeaveStatus.REJECTED.equals(r.getRequestStatus())) {
            throw new BusinessException("This request is already closed.",
                    HttpStatus.CONFLICT, "HR_LEAVE_ALREADY_CLOSED");
        }
        HrEmployee e = scope.employee(r.getPidEmployee());
        HrLeaveType t = leaveType(r.getPidLeaveType());
        int year = r.getFromDate().getYear();

        /* A withdrawn request should stop appearing in anybody's inbox. */
        approvals.cancelFor(HrScope.WF_LEAVE, id);

        if (r.isApproved()) {
            HrLeaveBalance b = lockedBalance(e, t, year);
            b.setTaken(floorAtZero(nz(b.getTaken()).subtract(r.getDays())));
            balanceRepo.save(b);
            removeAttendance(r);
        } else if (r.isSubmitted()) {
            release(e, t, year, r.getDays());
        }

        r.setRequestStatus(HrConstants.LeaveStatus.CANCELLED);
        r.setCancelledReason(decision == null ? null : decision.getRemarks());
        r.setDecidedBy(scope.actor());
        r.setDecidedAt(LocalDateTime.now());
        requestRepo.save(r);
        return map(r, e, t);
    }

    /* ══ Balance movement ═══════════════════════════════════════════════ */

    /** Moves days into PENDING, refusing if there are not enough. */
    private void reserve(HrEmployee e, HrLeaveType t, int year, BigDecimal days) {
        HrLeaveBalance b = lockedBalance(e, t, year);
        BigDecimal available = b.getAvailable();
        if (available.compareTo(days) < 0) {
            throw new BusinessException(
                    e.getEmpName() + " has " + strip(available) + " day"
                    + (available.compareTo(BigDecimal.ONE) == 0 ? "" : "s") + " of "
                    + t.getTypeName() + " left, and this request is for " + strip(days) + ".",
                    HttpStatus.CONFLICT, "HR_LEAVE_INSUFFICIENT");
        }
        b.setPending(nz(b.getPending()).add(days));
        balanceRepo.save(b);
    }

    private void release(HrEmployee e, HrLeaveType t, int year, BigDecimal days) {
        HrLeaveBalance b = lockedBalance(e, t, year);
        b.setPending(floorAtZero(nz(b.getPending()).subtract(days)));
        balanceRepo.save(b);
    }

    /**
     * The balance row, locked.
     *
     * <p>Refuses when no balance has been opened rather than creating one on
     * the fly: an entitlement nobody has set is not zero days, it is a question
     * nobody has answered, and inventing zero here would refuse every request
     * with a message about an empty balance.
     */
    private HrLeaveBalance lockedBalance(HrEmployee e, HrLeaveType t, int year) {
        return balanceRepo.lockFor(e.getPidEmployee(), t.getPidLeaveType(), year)
                .orElseThrow(() -> new BusinessException(
                        "No " + t.getTypeName() + " balance has been opened for "
                        + e.getEmpName() + " for " + year + ". Open it on the Leave screen first.",
                        HttpStatus.CONFLICT, "HR_LEAVE_NO_BALANCE"));
    }

    /* ══ Attendance written from leave ══════════════════════════════════ */

    /**
     * Marks the days an approved request covers.
     *
     * <p>A day that already has attendance is left alone rather than
     * overwritten — somebody who worked a day they later took leave for has a
     * record of working it, and this is not the place to decide which is right.
     * The remaining days become LEAVE rows tagged with the request.
     */
    private void writeAttendance(HrLeaveRequest r, HrEmployee e) {
        /* The same calendar the day count used. Marking a weekly off as LEAVE
           would put a day on the register that the request was never charged
           for, and the month would then show more absence than the balance. */
        HrCalendarService.Window cal = calendar.window(
                r.getPidOrg(), r.getPidBranch(), r.getFromDate(), r.getToDate());
        boolean configured = cal.hasPolicy();

        for (LocalDate d = r.getFromDate(); !d.isAfter(r.getToDate()); d = d.plusDays(1)) {
            if (configured && !cal.isWorkingDay(d)) continue;

            Optional<HrAttendance> existing = attendanceRepo
                    .findByPidEmployeeAndAttendanceDateAndStatus(e.getPidEmployee(), d, "Y");
            if (existing.isPresent()) continue;

            HrAttendance a = HrAttendance.builder()
                    .pidOrg(r.getPidOrg())
                    .pidCompany(r.getPidCompany())
                    .pidBranch(r.getPidBranch())
                    .pidEmployee(e.getPidEmployee())
                    .attendanceDate(d)
                    .attendanceStatus(HrConstants.AttendanceStatus.LEAVE)
                    .source(HrConstants.AttendanceSource.MANUAL)
                    .pidLeaveRequest(r.getPidLeaveRequest())
                    .remarks("Approved leave")
                    .build();
            a.setStatus("Y");
            attendanceRepo.save(a);
        }
    }

    private void removeAttendance(HrLeaveRequest r) {
        for (HrAttendance a : attendanceRepo.findByPidLeaveRequestAndStatus(r.getPidLeaveRequest(), "Y")) {
            a.setStatus("N");
            attendanceRepo.save(a);
        }
    }

    /* ══ Counting ═══════════════════════════════════════════════════════ */

    /**
     * How many days a request is worth.
     *
     * <p><b>Weekends and holidays are now excluded</b>, which closes P-04. The
     * branch's working policy says which days those are; where no policy exists
     * and no holiday falls in the range, this returns every calendar day exactly
     * as it did before {@code HR_WORKING_POLICY} existed — so adding the table
     * changed nothing for a branch that has not configured one.
     *
     * <p>A request landing entirely on non-working days is refused rather than
     * counted as zero: {@code ck_hr_leave_req_days} requires more than zero, and
     * "you cannot take leave on your day off" is a better answer than a
     * constraint violation.
     */
    private BigDecimal countDays(HrEmployee e, LocalDate from, LocalDate to, boolean halfDay) {
        BigDecimal days = calendar.leaveDays(e.getPidOrg(), e.getPidBranch(), from, to, halfDay);

        if (days.signum() <= 0) {
            HrCalendarService.Window w = calendar.window(e.getPidOrg(), e.getPidBranch(), from, to);
            String why = w.notWorkedBecause(from);
            throw new BusinessException(
                    from.equals(to)
                        ? from + " is not a working day at this branch"
                          + (why == null ? "." : " (" + why + ").")
                        : "Every day from " + from + " to " + to
                          + " is a weekly off or a holiday at this branch.",
                    HttpStatus.BAD_REQUEST, "HR_LEAVE_NO_WORKING_DAYS");
        }
        return days;
    }

    /* ══ Guards, lookups, mapping ═══════════════════════════════════════ */

    private void requireNoOverlap(HrEmployee e, LocalDate from, LocalDate to, Long excludeId) {
        List<HrLeaveRequest> clash = requestRepo.findOverlapping(e.getPidEmployee(), from, to, excludeId);
        if (!clash.isEmpty()) {
            HrLeaveRequest first = clash.get(0);
            throw new BusinessException(
                    e.getEmpName() + " already has leave from " + first.getFromDate()
                    + " to " + first.getToDate() + ". Two absences on one day would be counted twice.",
                    HttpStatus.CONFLICT, "HR_LEAVE_OVERLAP");
        }
    }

    private static void requireSubmitted(HrLeaveRequest r) {
        if (!r.isSubmitted()) {
            throw new BusinessException(
                    "Only a submitted request can be decided. This one is "
                    + HrConstants.label(r.getRequestStatus()).toLowerCase() + ".",
                    HttpStatus.CONFLICT, "HR_LEAVE_NOT_SUBMITTED");
        }
    }

    private HrLeaveRequest request(Long id) {
        return requestRepo.findByPidLeaveRequestAndPidBranch(id, scope.branchId())
                .orElseThrow(() -> new ResourceNotFoundException("Leave request", id));
    }

    private HrLeaveType leaveType(Long id) {
        HrLeaveType t = typeRepo.findByPidLeaveTypeAndPidBranch(id, scope.branchId())
                .orElseThrow(() -> new ResourceNotFoundException("Leave type", id));
        if (!t.isActive()) {
            throw new BusinessException(t.getTypeName() + " is no longer offered.",
                    HttpStatus.CONFLICT, "HR_LEAVE_TYPE_INACTIVE");
        }
        return t;
    }

    private Map<Long, HrEmployee> staffById() {
        Map<Long, HrEmployee> byId = new HashMap<>();
        for (HrEmployee e : employeeRepo.findByPidBranchAndStatusOrderByEmpName(scope.branchId(), "Y")) {
            byId.put(e.getPidEmployee(), e);
        }
        return byId;
    }

    private Map<Long, HrLeaveType> typesById() {
        Map<Long, HrLeaveType> byId = new HashMap<>();
        for (HrLeaveType t : typeRepo.findByPidBranchOrderByTypeName(scope.branchId())) {
            byId.put(t.getPidLeaveType(), t);
        }
        return byId;
    }

    private HrResponses.LeaveRequestDto map(HrLeaveRequest r, HrEmployee e, HrLeaveType t) {
        return HrResponses.LeaveRequestDto.builder()
                .id(r.getPidLeaveRequest())
                .requestNo(r.getRequestNo())
                .employeeId(r.getPidEmployee())
                .empId(e == null ? null : e.getEmpId())
                .empName(e == null ? null : e.getEmpName())
                .leaveTypeId(r.getPidLeaveType())
                .leaveTypeName(t == null ? null : t.getTypeName())
                .paidLeave(t != null && t.isPaidLeave())
                .fromDate(r.getFromDate())
                .toDate(r.getToDate())
                .days(r.getDays())
                .isHalfDay(r.getIsHalfDay())
                .reason(r.getReason())
                .requestStatus(r.getRequestStatus())
                .requestStatusLabel(HrConstants.label(r.getRequestStatus()))
                .submittedBy(r.getSubmittedBy())
                .submittedAt(r.getSubmittedAt())
                .decidedBy(r.getDecidedBy())
                .decidedAt(r.getDecidedAt())
                .decisionRemarks(r.getDecisionRemarks())
                .cancelledReason(r.getCancelledReason())
                .editable(r.isDraft())
                .build();
    }

    private HrResponses.LeaveBalanceDto map(HrLeaveBalance b, HrEmployee e, HrLeaveType t) {
        return HrResponses.LeaveBalanceDto.builder()
                .id(b.getPidBalance())
                .employeeId(b.getPidEmployee())
                .empName(e == null ? null : e.getEmpName())
                .leaveTypeId(b.getPidLeaveType())
                .leaveTypeName(t == null ? null : t.getTypeName())
                .leaveYear(b.getLeaveYear())
                .entitled(b.getEntitled())
                .carriedForward(b.getCarriedForward())
                .taken(b.getTaken())
                .pending(b.getPending())
                .available(b.getAvailable())
                .build();
    }

    private static BigDecimal nz(BigDecimal v)          { return v == null ? BigDecimal.ZERO : v; }
    private static BigDecimal floorAtZero(BigDecimal v) { return v.signum() < 0 ? BigDecimal.ZERO : v; }
    private static String strip(BigDecimal v)           { return v.stripTrailingZeros().toPlainString(); }
}
