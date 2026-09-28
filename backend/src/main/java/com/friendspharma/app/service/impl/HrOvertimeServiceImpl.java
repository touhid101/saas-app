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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Overtime claims.
 *
 * <h3>The double count this design exists to avoid</h3>
 *
 * <p>{@code PayrollCalculator} already values overtime: it reads
 * {@code HR_ATTENDANCE.OVERTIME_MINUTES} and multiplies by the structure's
 * rate. A claim table that also fed payroll would pay every approved hour
 * twice.
 *
 * <p>So <b>the claim feeds the register, not payroll</b>. Approving writes the
 * approved minutes onto the attendance row for that date; payroll goes on
 * reading exactly what it read before. The workflow sits in front of a single
 * source of truth rather than beside it.
 *
 * <p>Cancelling an approved claim takes back <em>exactly</em> what it added,
 * found through {@code PID_ATTENDANCE} — not by zeroing the day, which would
 * discard overtime recorded for another reason.
 *
 * <h3>What the branch policy decides</h3>
 *
 * <p>{@code HR_WORKING_POLICY.OVERTIME_ALLOWED} says whether overtime may be
 * claimed here at all, and {@code OVERTIME_MIN_MINUTES} the least that counts.
 * <b>What an hour is worth stays on the salary structure</b> — a branch decides
 * whether overtime is claimable, and a pay structure decides what it pays. A
 * claim can therefore be approved at a branch whose structures value overtime
 * at nothing, and the payslip will say so.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrOvertimeServiceImpl {

    private final HrOvertimeRequestRepository requestRepo;
    private final HrAttendanceRepository      attendanceRepo;
    private final HrEmployeeRepository        employeeRepo;
    private final HrWorkingPolicyRepository   policyRepo;
    private final HrApprovalService           approvals;
    private final HrScope                     scope;

    /* ══ Reads ══════════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public PageResponse<HrResponses.OvertimeDto> search(Long employeeId, String status,
                                                         LocalDate from, LocalDate to,
                                                         Pageable pageable) {
        scope.require(HrScope.MOD_ATTENDANCE, PermissionGuard.Right.VIEW, "see overtime claims");
        Map<Long, HrEmployee> staff = staffById();
        return PageResponse.from(
                requestRepo.search(scope.branchId(), employeeId,
                                   status == null ? "" : status.trim().toUpperCase(),
                                   from, to, pageable)
                        .map(r -> map(r, staff.get(r.getPidEmployee()))));
    }

    @Transactional(readOnly = true)
    public List<HrResponses.OvertimeDto> pending() {
        scope.require(HrScope.MOD_ATTENDANCE, PermissionGuard.Right.VIEW, "see overtime claims");
        Map<Long, HrEmployee> staff = staffById();
        return requestRepo.findByPidBranchAndRequestStatusAndStatusOrderByOtDate(
                        scope.branchId(), HrOvertimeRequest.SUBMITTED, "Y")
                .stream().map(r -> map(r, staff.get(r.getPidEmployee()))).toList();
    }

    /* ══ The lifecycle ══════════════════════════════════════════════════ */

    @Transactional
    public HrResponses.OvertimeDto create(HrRequests.Overtime req) {
        scope.require(HrScope.MOD_ATTENDANCE, PermissionGuard.Right.CREATE, "claim overtime");

        HrEmployee e = scope.activeEmployee(req.getEmployeeId());
        requireClaimable(e, req.getOtDate(), req.getClaimedMinutes());

        List<HrOvertimeRequest> live = requestRepo.findLiveFor(e.getPidEmployee(), req.getOtDate(), null);
        if (!live.isEmpty()) {
            throw new BusinessException(
                    e.getEmpName() + " already has an overtime claim for " + req.getOtDate() + ".",
                    HttpStatus.CONFLICT, "HR_OT_ALREADY_CLAIMED");
        }

        HrOvertimeRequest r = HrOvertimeRequest.builder()
                .pidOrg(scope.orgId()).pidCompany(scope.companyId()).pidBranch(e.getPidBranch())
                .pidEmployee(e.getPidEmployee())
                .otDate(req.getOtDate())
                .claimedMinutes(req.getClaimedMinutes())
                .reason(req.getReason())
                .requestStatus(HrOvertimeRequest.DRAFT)
                .build();
        r.setStatus("Y");
        requestRepo.save(r);

        if (Boolean.TRUE.equals(req.getSubmit())) submitInternal(r);
        return map(r, e);
    }

    @Transactional
    public HrResponses.OvertimeDto submit(Long id) {
        scope.require(HrScope.MOD_ATTENDANCE, PermissionGuard.Right.CREATE, "submit an overtime claim");
        HrOvertimeRequest r = request(id);
        submitInternal(r);
        return map(r, scope.employee(r.getPidEmployee()));
    }

    private void submitInternal(HrOvertimeRequest r) {
        if (!r.isDraft()) {
            throw new BusinessException("This claim has already been submitted.",
                    HttpStatus.CONFLICT, "HR_OT_ALREADY_SUBMITTED");
        }
        r.setRequestStatus(HrOvertimeRequest.SUBMITTED);
        r.setSubmittedBy(scope.actor());
        r.setSubmittedAt(LocalDateTime.now());
        requestRepo.saveAndFlush(r);

        /* Routed on the minutes claimed, where this branch has a chain for
           overtime. Nothing happens where it has none. */
        approvals.begin(HrScope.WF_OVERTIME, r.getPidOtRequest(),
                        scope.employee(r.getPidEmployee()),
                        java.math.BigDecimal.valueOf(r.getClaimedMinutes()));
    }

    /**
     * Approves a claim, and writes the minutes onto the day.
     *
     * <p>This is the whole point of the workflow: the register is where payroll
     * reads from, so approval is what makes an hour payable.
     */
    @Transactional
    public HrResponses.OvertimeDto approve(Long id, HrRequests.OvertimeDecision d) {
        scope.require(HrScope.MOD_ATTENDANCE, PermissionGuard.Right.APPROVE, "approve overtime");

        HrOvertimeRequest r = request(id);
        requireSubmitted(r);
        HrEmployee e = scope.employee(r.getPidEmployee());

        /* Where a chain applies, this may not be the last signature — and the
           register must not be written until it is, or payroll would value
           minutes nobody has finished approving. */
        if (approvals.decide(HrScope.WF_OVERTIME, id, true, d == null ? null : d.getRemarks())
                == HrApprovalService.Step.PENDING_NEXT) {
            return map(r, e);
        }

        int granted = d != null && d.getApprovedMinutes() != null
                ? d.getApprovedMinutes() : r.getClaimedMinutes();
        if (granted <= 0) {
            throw new BusinessException(
                    "Approving nothing is a rejection. Reject the claim instead.",
                    HttpStatus.BAD_REQUEST, "HR_OT_ZERO_APPROVAL");
        }
        if (granted > r.getClaimedMinutes()) {
            throw new BusinessException(
                    "More cannot be approved than was claimed — " + r.getClaimedMinutes()
                    + " minutes. Ask for a new claim instead.",
                    HttpStatus.BAD_REQUEST, "HR_OT_OVER_CLAIM");
        }

        HrAttendance day = attendanceRepo
                .findByPidEmployeeAndAttendanceDateAndStatus(e.getPidEmployee(), r.getOtDate(), "Y")
                .orElseThrow(() -> new BusinessException(
                        "There is no attendance recorded for " + e.getEmpName() + " on "
                        + r.getOtDate() + ". Record the day before approving overtime on it — "
                        + "payroll reads the register, not the claim.",
                        HttpStatus.CONFLICT, "HR_OT_NO_ATTENDANCE"));

        /* Added to whatever the day already holds rather than replacing it: a
           register entry and a claim are two different people saying something
           about the same day, and the claim is not automatically the whole
           story. */
        int before = day.getOvertimeMinutes() == null ? 0 : day.getOvertimeMinutes();
        day.setOvertimeMinutes(before + granted);
        attendanceRepo.save(day);

        r.setApprovedMinutes(granted);
        r.setRequestStatus(HrOvertimeRequest.APPROVED);
        r.setDecidedBy(scope.actor());
        r.setDecidedAt(LocalDateTime.now());
        r.setDecisionRemarks(d == null ? null : d.getRemarks());
        r.setPidAttendance(day.getPidAttendance());
        r.setAppliedAt(LocalDateTime.now());
        requestRepo.save(r);

        log.info("Overtime approved: {} minutes for employee {} on {}, register now {}",
                 granted, e.getPidEmployee(), r.getOtDate(), day.getOvertimeMinutes());
        return map(r, e);
    }

    @Transactional
    public HrResponses.OvertimeDto reject(Long id, HrRequests.Decision d) {
        scope.require(HrScope.MOD_ATTENDANCE, PermissionGuard.Right.APPROVE, "reject overtime");
        HrOvertimeRequest r = request(id);
        requireSubmitted(r);
        approvals.decide(HrScope.WF_OVERTIME, id, false, d == null ? null : d.getRemarks());
        r.setRequestStatus(HrOvertimeRequest.REJECTED);
        r.setDecidedBy(scope.actor());
        r.setDecidedAt(LocalDateTime.now());
        r.setDecisionRemarks(d == null ? null : d.getRemarks());
        return map(requestRepo.save(r), scope.employee(r.getPidEmployee()));
    }

    /**
     * Cancels a claim, taking back exactly what it added.
     *
     * <p>Found through {@code PID_ATTENDANCE} and subtracted, never zeroed: the
     * day may carry overtime that was entered directly on the register, and
     * that is not this claim's to remove.
     */
    @Transactional
    public HrResponses.OvertimeDto cancel(Long id, HrRequests.Decision d) {
        scope.require(HrScope.MOD_ATTENDANCE, PermissionGuard.Right.DELETE, "cancel an overtime claim");

        HrOvertimeRequest r = request(id);
        if (HrOvertimeRequest.CANCELLED.equals(r.getRequestStatus())
                || HrOvertimeRequest.REJECTED.equals(r.getRequestStatus())) {
            throw new BusinessException("This claim is already closed.",
                    HttpStatus.CONFLICT, "HR_OT_ALREADY_CLOSED");
        }

        approvals.cancelFor(HrScope.WF_OVERTIME, id);

        if (r.isApproved() && r.getPidAttendance() != null && r.getApprovedMinutes() != null) {
            Optional<HrAttendance> day = attendanceRepo.findById(r.getPidAttendance());
            if (day.isPresent()) {
                HrAttendance a = day.get();
                int now = a.getOvertimeMinutes() == null ? 0 : a.getOvertimeMinutes();
                a.setOvertimeMinutes(Math.max(0, now - r.getApprovedMinutes()));
                attendanceRepo.save(a);
                log.info("Overtime claim {} cancelled: register for {} back to {} minutes",
                         id, a.getAttendanceDate(), a.getOvertimeMinutes());
            }
        }

        r.setRequestStatus(HrOvertimeRequest.CANCELLED);
        r.setDecidedBy(scope.actor());
        r.setDecidedAt(LocalDateTime.now());
        r.setDecisionRemarks(d == null ? null : d.getRemarks());
        r.setPidAttendance(null);
        return map(requestRepo.save(r), scope.employee(r.getPidEmployee()));
    }

    /* ══ Guards ═════════════════════════════════════════════════════════ */

    /**
     * Whether this branch takes overtime claims at all, and whether this one is
     * worth taking.
     *
     * <p>A branch with no working policy is unrestricted — the same
     * compatibility contract everything else here follows: nothing changes until
     * somebody decides something.
     */
    private void requireClaimable(HrEmployee e, LocalDate on, Integer minutes) {
        if (on == null) {
            throw new BusinessException("Which day?", HttpStatus.BAD_REQUEST, "HR_DATE_REQUIRED");
        }
        if (on.isAfter(LocalDate.now())) {
            throw new BusinessException("Overtime cannot be claimed for a day that has not happened.",
                    HttpStatus.BAD_REQUEST, "HR_OT_FUTURE");
        }

        HrWorkingPolicy policy = policyRepo.findOn(e.getPidBranch(), on)
                .stream().findFirst()
                .orElseGet(() -> policyRepo.findCurrent(e.getPidBranch()).orElse(null));

        if (policy != null && !policy.allowsOvertime()) {
            throw new BusinessException(
                    "Overtime is not claimed at this branch. Turn it on in the working policy "
                    + "first — what an hour is worth is then set on the salary structure.",
                    HttpStatus.CONFLICT, "HR_OT_NOT_ALLOWED");
        }
        if (policy != null && policy.getOvertimeMinMinutes() != null
                && minutes != null && minutes < policy.getOvertimeMinMinutes()) {
            throw new BusinessException(
                    "The least overtime this branch counts is " + policy.getOvertimeMinMinutes()
                    + " minutes.",
                    HttpStatus.BAD_REQUEST, "HR_OT_BELOW_MINIMUM");
        }
    }

    private static void requireSubmitted(HrOvertimeRequest r) {
        if (!r.isSubmitted()) {
            throw new BusinessException(
                    "Only a submitted claim can be decided. This one is "
                    + HrConstants.label(r.getRequestStatus()).toLowerCase() + ".",
                    HttpStatus.CONFLICT, "HR_OT_NOT_SUBMITTED");
        }
    }

    private HrOvertimeRequest request(Long id) {
        return requestRepo.findByPidOtRequestAndPidBranch(id, scope.branchId())
                .orElseThrow(() -> new ResourceNotFoundException("Overtime claim", id));
    }

    private Map<Long, HrEmployee> staffById() {
        Map<Long, HrEmployee> byId = new HashMap<>();
        for (HrEmployee e : employeeRepo.findByPidBranchAndStatusOrderByEmpName(scope.branchId(), "Y")) {
            byId.put(e.getPidEmployee(), e);
        }
        return byId;
    }

    HrResponses.OvertimeDto map(HrOvertimeRequest r, HrEmployee e) {
        return HrResponses.OvertimeDto.builder()
                .id(r.getPidOtRequest())
                .requestNo(r.getRequestNo())
                .employeeId(r.getPidEmployee())
                .empId(e == null ? null : e.getEmpId())
                .empName(e == null ? null : e.getEmpName())
                .otDate(r.getOtDate())
                .claimedMinutes(r.getClaimedMinutes())
                .claimedHours(HrAttendance.hours(r.getClaimedMinutes()))
                .approvedMinutes(r.getApprovedMinutes())
                .approvedHours(HrAttendance.hours(r.getApprovedMinutes()))
                .reason(r.getReason())
                .requestStatus(r.getRequestStatus())
                .requestStatusLabel(HrConstants.label(r.getRequestStatus()))
                .submittedBy(r.getSubmittedBy())
                .submittedAt(r.getSubmittedAt())
                .decidedBy(r.getDecidedBy())
                .decidedAt(r.getDecidedAt())
                .decisionRemarks(r.getDecisionRemarks())
                .attendanceId(r.getPidAttendance())
                .appliedAt(r.getAppliedAt())
                .editable(r.isDraft())
                .build();
    }
}
