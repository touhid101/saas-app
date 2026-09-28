package com.friendspharma.app.service.impl;

import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.request.HrRequests;
import com.friendspharma.app.dto.response.HrResponses;
import com.friendspharma.app.dto.response.HrResponses.AdvanceDto;
import com.friendspharma.app.dto.response.HrResponses.AttendanceDto;
import com.friendspharma.app.dto.response.HrResponses.ExpenseClaimDto;
import com.friendspharma.app.dto.response.HrResponses.LeaveRequestDto;
import com.friendspharma.app.dto.response.HrResponses.LoanDto;
import com.friendspharma.app.dto.response.HrSelfServiceResponses.Activity;
import com.friendspharma.app.dto.response.HrSelfServiceResponses.AttendanceMonth;
import com.friendspharma.app.dto.response.HrSelfServiceResponses.AttendanceMonthSummary;
import com.friendspharma.app.dto.response.HrSelfServiceResponses.Holiday;
import com.friendspharma.app.dto.response.HrSelfServiceResponses.Me;
import com.friendspharma.app.dto.response.HrSelfServiceResponses.PayslipPointer;
import com.friendspharma.app.dto.response.HrSelfServiceResponses.RequestDetail;
import com.friendspharma.app.dto.response.HrSelfServiceResponses.RequestItem;
import com.friendspharma.app.dto.response.HrSelfServiceResponses.Stage;
import com.friendspharma.app.dto.response.HrSelfServiceResponses.Summary;
import com.friendspharma.app.entity.AllBranch;
import com.friendspharma.app.entity.HrApprovalFlow;
import com.friendspharma.app.entity.HrApprovalFlowLevel;
import com.friendspharma.app.entity.HrApprovalRequest;
import com.friendspharma.app.entity.HrAttendance;
import com.friendspharma.app.entity.HrConstants;
import com.friendspharma.app.entity.HrEmployee;
import com.friendspharma.app.entity.HrHoliday;
import com.friendspharma.app.entity.HrPayrollLine;
import com.friendspharma.app.entity.HrPayrollPeriod;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.AllBranchRepository;
import com.friendspharma.app.repository.HrApprovalFlowRepository;
import com.friendspharma.app.repository.HrApprovalRequestRepository;
import com.friendspharma.app.repository.HrAttendanceRepository;
import com.friendspharma.app.repository.HrEmployeeRepository;
import com.friendspharma.app.repository.HrExpenseClaimLineRepository;
import com.friendspharma.app.repository.HrPayrollLineRepository;
import com.friendspharma.app.repository.HrPayrollPeriodRepository;
import com.friendspharma.app.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.Set;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Employee self-service: one person's HR, seen and acted on by that person.
 *
 * <p><b>Who.</b> The signed-in user is resolved to an employee through the
 * link on their login ({@code ACL_USER.PID_EMPLOYEE}); nothing about identity
 * is taken from a request. An employee id in a payload is ignored and replaced
 * with the caller's own; an id addressing a record is checked to be theirs
 * before anything is read or written, and a record that is not theirs is
 * <i>not found</i> — never <i>forbidden</i>, which would confirm it exists.
 *
 * <p><b>Authority.</b> Every write goes through the module's own service —
 * the same validation, numbering, workflow and audit as a request filed by
 * HR — inside {@link HrScope#asSelf}, which lets those services run without
 * the module rights an employee does not hold, for every right except
 * APPROVE. This class never calls an approve, reject, pay or disburse.
 *
 * <p><b>One shape.</b> Five kinds of request become one {@link RequestItem}
 * so the portal can list, filter and rail them together. The mapping of each
 * module's status to the portal's seven words lives here and nowhere else.
 */
@Service
@RequiredArgsConstructor
public class HrSelfServiceImpl {

    /* The portal's own vocabulary. */
    public static final String S_DRAFT = "DRAFT", S_PENDING = "PENDING", S_APPROVED = "APPROVED",
            S_PROCESSING = "PROCESSING", S_COMPLETED = "COMPLETED", S_REJECTED = "REJECTED",
            S_CANCELLED = "CANCELLED";
    public static final String T_LEAVE = "LEAVE", T_EXPENSE = "EXPENSE", T_ADVANCE = "ADVANCE",
            T_LOAN = "LOAN", T_ATTENDANCE = "ATTENDANCE";

    private static final int HISTORY_MONTHS = 12;

    private final HrScope                      scope;
    private final HrEmployeeRepository         employeeRepo;
    private final AllBranchRepository          branchRepo;
    private final HrEmployeeServiceImpl        employees;
    private final HrLeaveServiceImpl           leave;
    private final HrSetupServiceImpl           setup;
    private final HrExpenseServiceImpl         expense;
    private final HrExpenseClaimLineRepository expenseLines;
    private final HrAdvanceLoanServiceImpl     money;
    private final HrAttendanceServiceImpl      attendance;
    private final HrAttendanceRepository       attendanceRepo;
    private final HrCalendarService            calendar;
    private final HrCalendarViewService        calendarView;
    private final HrApprovalRequestRepository  approvals;
    private final HrApprovalFlowRepository     flows;
    private final HrPayrollLineRepository      payrollLines;
    private final HrPayrollPeriodRepository    periods;
    private final Validator                    validator;

    /* ══ Identity ═══════════════════════════════════════════════════════════ */

    /**
     * The employee behind the login, or null when there is none.
     *
     * <p>Also refused when the employee is not of the session's branch: the
     * link is set by an administrator, and a link across branches is a
     * mistake this must not quietly honour.
     */
    private HrEmployee whoAmI() {
        Long id = SecurityUtils.getCurrentEmployeeId();
        if (id == null) return null;
        HrEmployee e = employeeRepo.findById(id).orElse(null);
        if (e == null || !Objects.equals(e.getPidBranch(), scope.branchId())) return null;
        return e;
    }

    private HrEmployee requireMe() {
        HrEmployee e = whoAmI();
        if (e == null) {
            throw new BusinessException(
                    "Your login is not linked to an employee record at this branch. Ask HR to link it.",
                    HttpStatus.FORBIDDEN, "HR_NOT_AN_EMPLOYEE");
        }
        return e;
    }

    private <T> T self(HrEmployee me, Supplier<T> work) {
        return scope.asSelf(me.getPidEmployee(), work);
    }

    @Transactional(readOnly = true)
    public Me me() {
        HrEmployee e = whoAmI();
        if (e == null) return Me.builder().linked(false).build();
        HrResponses.EmployeeProfileDto p = self(e, () -> employees.profile(e.getPidEmployee()));
        String branch = branchRepo.findById(e.getPidBranch()).map(AllBranch::getBranchName).orElse(null);
        return Me.builder()
                .linked(true)
                .employeeId(p.getEmployeeId())
                .empId(p.getEmpId())
                .empName(p.getEmpName())
                .preferredName(p.getPreferredName())
                .imagePath(p.getImagePath())
                .initials(p.getInitials())
                .designationName(p.getDesignationName())
                .departmentName(p.getDepartmentName())
                .branchName(branch)
                .employmentType(p.getEmploymentType())
                .employmentTypeLabel(p.getEmploymentTypeLabel())
                .empStatus("Y".equals(p.getStatus()) ? "Active" : "Inactive")
                .hireDate(p.getHireDate())
                .managerName(p.getManagerName())
                .shiftName(p.getShiftName())
                .email(p.getEmail())
                .phone(p.getPhone())
                .build();
    }

    /* ══ Reads ══════════════════════════════════════════════════════════════ */

    /** Every request of mine, across the five modules, newest first. */
    private List<RequestItem> allMine(HrEmployee me) {
        Long id = me.getPidEmployee();
        LocalDate since = LocalDate.now().minusMonths(HISTORY_MONTHS);
        List<RequestItem> out = new ArrayList<>();
        self(me, () -> {
            var big = PageRequest.of(0, 500);
            leave.search(id, null, null, null, big).getContent().forEach(r -> out.add(ofLeave(r)));
            expense.search(id, null, null, null, big).getContent().forEach(c -> out.add(ofExpense(c)));
            money.searchAdvances(id, null, big).getContent().forEach(a -> out.add(ofAdvance(a)));
            money.searchLoans(id, null, big).getContent().forEach(l -> out.add(ofLoan(l)));
            attendance.forEmployee(id, since, LocalDate.now()).stream()
                    .filter(a -> a.getCorrectionStatus() != null)
                    .forEach(a -> out.add(ofCorrection(a)));
            return null;
        });
        out.sort(Comparator.comparing((RequestItem r) -> r.getUpdatedAt() == null ? LocalDateTime.MIN : r.getUpdatedAt())
                .reversed());
        return out;
    }

    @Transactional(readOnly = true)
    public PageResponse<RequestItem> requests(String type, String status, String q, int page, int size) {
        HrEmployee me = requireMe();
        String t = blank(type) ? null : type.trim().toUpperCase(Locale.ROOT);
        String s = blank(status) ? null : status.trim().toUpperCase(Locale.ROOT);
        String needle = blank(q) ? null : q.trim().toLowerCase(Locale.ROOT);

        List<RequestItem> all = allMine(me).stream()
                .filter(r -> t == null || t.equals(r.getType()))
                .filter(r -> s == null || s.equals(r.getStatus()))
                .filter(r -> needle == null || hay(r).contains(needle))
                .toList();
        int from = Math.max(0, page * size);
        int to = Math.min(all.size(), from + size);
        List<RequestItem> slice = from >= all.size() ? List.of() : all.subList(from, to);
        return PageResponse.from(new org.springframework.data.domain.PageImpl<>(
                slice, PageRequest.of(page, Math.max(1, size)), all.size()));
    }

    private static String hay(RequestItem r) {
        return String.join(" ",
                nz(r.getRequestNo()), nz(r.getTitle()), nz(r.getSubtitle()), nz(r.getTypeLabel()),
                nz(r.getStatusLabel()), nz(r.getStage())).toLowerCase(Locale.ROOT);
    }

    @Transactional(readOnly = true)
    public RequestDetail detail(String type, Long id) {
        HrEmployee me = requireMe();
        String t = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        return self(me, () -> switch (t) {
            case T_LEAVE -> {
                LeaveRequestDto r = leave.search(me.getPidEmployee(), null, null, null, PageRequest.of(0, 500))
                        .getContent().stream().filter(x -> x.getId().equals(id)).findFirst()
                        .orElseThrow(() -> new ResourceNotFoundException("Request", id));
                yield detailOf(ofLeave(r), r, HrScope.WF_LEAVE, id, me);
            }
            case T_EXPENSE -> {
                ExpenseClaimDto c = expense.get(id);
                own(c.getEmployeeId(), me, id);
                yield detailOf(ofExpense(c), c, HrScope.WF_EXPENSE, id, me);
            }
            case T_ADVANCE -> {
                AdvanceDto a = money.searchAdvances(me.getPidEmployee(), null, PageRequest.of(0, 500))
                        .getContent().stream().filter(x -> x.getId().equals(id)).findFirst()
                        .orElseThrow(() -> new ResourceNotFoundException("Request", id));
                yield detailOf(ofAdvance(a), a, HrScope.WF_ADVANCE, id, me);
            }
            case T_LOAN -> {
                LoanDto l = money.getLoan(id);
                own(l.getEmployeeId(), me, id);
                yield detailOf(ofLoan(l), l, HrScope.WF_LOAN, id, me);
            }
            case T_ATTENDANCE -> {
                HrAttendance a = attendanceRepo.findById(id)
                        .orElseThrow(() -> new ResourceNotFoundException("Request", id));
                own(a.getPidEmployee(), me, id);
                AttendanceDto dto = attendance.forEmployee(me.getPidEmployee(), a.getAttendanceDate(), a.getAttendanceDate())
                        .stream().filter(x -> x.getId().equals(id)).findFirst()
                        .orElseThrow(() -> new ResourceNotFoundException("Request", id));
                yield detailOf(ofCorrection(dto), dto, null, id, me);
            }
            default -> throw new ResourceNotFoundException("Request", id);
        });
    }

    private static void own(Long owner, HrEmployee me, Long id) {
        if (owner == null || !owner.equals(me.getPidEmployee())) {
            throw new ResourceNotFoundException("Request", id);
        }
    }

    private RequestDetail detailOf(RequestItem item, Object data, String workflow, Long refId, HrEmployee me) {
        HrApprovalRequest chain = workflow == null ? null
                : approvals.findByWorkflowCodeAndRefIdAndStatusOrderByPidApprovalDesc(workflow, refId, "Y")
                           .stream().filter(r -> Objects.equals(r.getPidEmployee(), me.getPidEmployee()))
                           .findFirst().orElse(null);
        List<Stage> stages = stagesOf(item, chain);
        return RequestDetail.builder()
                .item(item)
                .stages(stages)
                .data(data)
                .chain(chain == null ? null : chainDto(chain))
                .history(historyOf(item, chain))
                .build();
    }

    private HrResponses.ApprovalChainDto chainDto(HrApprovalRequest r) {
        return HrResponses.ApprovalChainDto.builder()
                .id(r.getPidApproval())
                .workflowCode(r.getWorkflowCode())
                .workflowLabel(HrScope.workflowLabel(r.getWorkflowCode()))
                .refId(r.getRefId())
                .employeeId(r.getPidEmployee())
                .flowName(r.getFlowName())
                .routedValue(r.getRoutedValue())
                .currentLevel(r.getCurrentLevel())
                .totalLevels(r.getTotalLevels())
                .progress(r.progress())
                .requestStatus(r.getRequestStatus())
                .requestStatusLabel(HrConstants.label(r.getRequestStatus()))
                .startedAt(r.getStartedAt())
                .closedAt(r.getClosedAt())
                .actions(r.getActions().stream()
                        .sorted(Comparator.comparing(a -> a.getLevelNo() == null ? 0 : a.getLevelNo()))
                        .map(a -> HrResponses.ApprovalActionDto.builder()
                                .levelNo(a.getLevelNo())
                                .levelLabel(a.getLevelLabel())
                                .decision(a.getDecision())
                                .decidedBy(a.getDecidedBy())
                                .decidedAt(a.getDecidedAt())
                                .remarks(a.getRemarks())
                                .build())
                        .toList())
                .build();
    }

    /* ══ The lifecycle ══════════════════════════════════════════════════════

       One rail for every kind: Submitted → the signatures → what happens after
       approval → done. The signatures come from the chain the request actually
       took; with no chain configured a request takes one, and the rail says so
       with a single "Review" step rather than inventing three. */

    private List<Stage> stagesOf(RequestItem item, HrApprovalRequest chain) {
        List<Stage> out = new ArrayList<>();
        String st = item.getStatus();
        boolean submitted = !S_DRAFT.equals(st);
        boolean decided = S_APPROVED.equals(st) || S_PROCESSING.equals(st) || S_COMPLETED.equals(st);
        boolean rejected = S_REJECTED.equals(st);
        boolean cancelled = S_CANCELLED.equals(st);

        out.add(stage("SUBMITTED", T_ATTENDANCE.equals(item.getType()) ? "Requested" : "Submitted",
                submitted ? "DONE" : "CURRENT", null, item.getSubmittedAt(), null));

        /* The signatures. */
        if (chain != null) {
            List<HrApprovalFlowLevel> levels = chain.getPidFlow() == null ? List.of()
                    : flows.findById(chain.getPidFlow()).map(HrApprovalFlow::getLevels).orElse(List.of());
            int total = chain.getTotalLevels() == null ? levels.size() : chain.getTotalLevels();
            for (int n = 1; n <= total; n++) {
                final int levelNo = n;
                var action = chain.getActions().stream()
                        .filter(a -> Objects.equals(a.getLevelNo(), levelNo)).findFirst();
                String label = action.map(a -> a.getLevelLabel()).filter(l -> !blank(l))
                        .orElseGet(() -> levels.stream().filter(l -> Objects.equals(l.getLevelNo(), levelNo))
                                .findFirst().map(l -> blank(l.getLevelLabel()) ? roleLabel(l.getApproverRole()) : l.getLevelLabel())
                                .orElse("Review " + levelNo));
                String state;
                if (action.isPresent()) {
                    state = "REJECTED".equals(action.get().getDecision()) ? "REJECTED" : "DONE";
                } else if (cancelled) {
                    state = "CANCELLED";
                } else if (rejected) {
                    state = "PENDING";
                } else if (submitted && !decided && Objects.equals(chain.getCurrentLevel(), levelNo)) {
                    state = "CURRENT";
                } else {
                    state = "PENDING";
                }
                out.add(stage("LEVEL_" + n, label, state,
                        action.map(a -> a.getDecidedBy()).orElse(null),
                        action.map(a -> a.getDecidedAt()).orElse(null),
                        action.map(a -> a.getRemarks()).orElse(null)));
            }
        } else {
            String state = decided ? "DONE" : rejected ? "REJECTED" : cancelled ? "CANCELLED"
                    : submitted ? "CURRENT" : "PENDING";
            out.add(stage("REVIEW", "Review", state, item.getDecidedBy(),
                    (decided || rejected) ? item.getDecidedAt() : null, item.getDecisionRemarks()));
        }

        /* After approval: what the module does with it. */
        String raw = item.getRawStatus();
        switch (item.getType()) {
            case T_LEAVE -> out.add(stage("APPROVED", "Approved", decided ? "DONE" : tail(rejected, cancelled),
                    decided ? item.getDecidedBy() : null, decided ? item.getDecidedAt() : null, null));
            case T_EXPENSE -> {
                out.add(stage("APPROVED", "Approved", decided ? "DONE" : tail(rejected, cancelled),
                        decided ? item.getDecidedBy() : null, decided ? item.getDecidedAt() : null, null));
                out.add(stage("PAID", "Paid", "PAID".equals(raw) ? "DONE" : decided ? "CURRENT" : tail(rejected, cancelled),
                        null, "PAID".equals(raw) ? item.getUpdatedAt() : null, null));
            }
            case T_ADVANCE -> {
                out.add(stage("APPROVED", "Approved", decided ? "DONE" : tail(rejected, cancelled),
                        decided ? item.getDecidedBy() : null, decided ? item.getDecidedAt() : null, null));
                boolean paid = List.of("PAID", "RECOVERING", "CLOSED").contains(raw);
                out.add(stage("PAID", "Paid out", paid ? "DONE" : decided ? "CURRENT" : tail(rejected, cancelled), null, null, null));
                out.add(stage("RECOVERED", "Recovered through payroll",
                        "CLOSED".equals(raw) ? "DONE" : paid ? "CURRENT" : tail(rejected, cancelled), null, null, null));
            }
            case T_LOAN -> {
                out.add(stage("APPROVED", "Approved", decided ? "DONE" : tail(rejected, cancelled),
                        decided ? item.getDecidedBy() : null, decided ? item.getDecidedAt() : null, null));
                boolean paid = List.of("DISBURSED", "RECOVERING", "CLOSED").contains(raw);
                out.add(stage("DISBURSED", "Disbursed", paid ? "DONE" : decided ? "CURRENT" : tail(rejected, cancelled), null, null, null));
                out.add(stage("REPAID", "Repaid through payroll",
                        "CLOSED".equals(raw) ? "DONE" : paid ? "CURRENT" : tail(rejected, cancelled), null, null, null));
            }
            case T_ATTENDANCE -> out.add(stage("APPLIED", "Attendance updated", decided ? "DONE" : tail(rejected, cancelled),
                    decided ? item.getDecidedBy() : null, decided ? item.getDecidedAt() : null, null));
            default -> { }
        }
        return out;
    }

    private static String tail(boolean rejected, boolean cancelled) {
        return cancelled ? "CANCELLED" : "PENDING";
    }

    private static Stage stage(String key, String label, String state, String by, LocalDateTime at, String remarks) {
        return Stage.builder().key(key).label(label).state(state).by(by).at(at).remarks(remarks).build();
    }

    /** The audit line, oldest first — every stamp the record and its chain carry. */
    private List<Stage> historyOf(RequestItem item, HrApprovalRequest chain) {
        List<Stage> h = new ArrayList<>();
        if (item.getRequestDate() != null && item.getSubmittedAt() == null) {
            h.add(stage("CREATED", "Created", "DONE", null, item.getRequestDate().atStartOfDay(), null));
        }
        if (item.getSubmittedAt() != null) {
            h.add(stage("SUBMITTED", T_ATTENDANCE.equals(item.getType()) ? "Correction requested" : "Submitted",
                    "DONE", null, item.getSubmittedAt(), null));
        }
        if (chain != null) {
            chain.getActions().stream()
                    .sorted(Comparator.comparing(a -> a.getDecidedAt() == null ? LocalDateTime.MIN : a.getDecidedAt()))
                    .forEach(a -> h.add(stage("LEVEL_" + a.getLevelNo(),
                            (blank(a.getLevelLabel()) ? "Level " + a.getLevelNo() : a.getLevelLabel())
                                    + (" — " + HrConstants.label(a.getDecision())),
                            "REJECTED".equals(a.getDecision()) ? "REJECTED" : "DONE",
                            a.getDecidedBy(), a.getDecidedAt(), a.getRemarks())));
        }
        if (item.getDecidedAt() != null) {
            String what = S_REJECTED.equals(item.getStatus()) ? "Rejected"
                    : S_CANCELLED.equals(item.getStatus()) ? "Cancelled" : "Approved";
            h.add(stage("DECIDED", what, S_REJECTED.equals(item.getStatus()) ? "REJECTED" : "DONE",
                    item.getDecidedBy(), item.getDecidedAt(), item.getDecisionRemarks()));
        }
        if (List.of("PAID", "DISBURSED", "RECOVERING", "CLOSED").contains(nz(item.getRawStatus()))
                && !T_LEAVE.equals(item.getType()) && !T_ATTENDANCE.equals(item.getType())) {
            h.add(stage("PAID", T_LOAN.equals(item.getType()) ? "Disbursed" : "Paid", "DONE", null, null, null));
        }
        if ("CLOSED".equals(item.getRawStatus())) h.add(stage("CLOSED", "Closed", "DONE", null, null, null));
        return h;
    }

    private static String roleLabel(String role) {
        return switch (role == null ? "" : role) {
            case "MANAGER" -> "The employee's own manager";
            case "HR"      -> "HR";
            case "FINANCE" -> "Finance";
            case "ADMIN"   -> "Branch administrator";
            default        -> HrConstants.label(role);
        };
    }

    /* ══ One shape for five kinds ═══════════════════════════════════════════ */

    private static RequestItem ofLeave(LeaveRequestDto r) {
        String raw = r.getRequestStatus();
        String st = switch (nz(raw)) {
            case "DRAFT" -> S_DRAFT;
            case "SUBMITTED" -> S_PENDING;
            case "APPROVED" -> r.getToDate() != null && r.getToDate().isBefore(LocalDate.now()) ? S_COMPLETED : S_APPROVED;
            case "REJECTED" -> S_REJECTED;
            case "CANCELLED" -> S_CANCELLED;
            default -> S_PENDING;
        };
        /* When it last moved: the decision, else the submission. The leave dates
           are what it is about, not when it changed. */
        LocalDateTime updated = latest(r.getDecidedAt(), r.getSubmittedAt());
        if (updated == null && r.getFromDate() != null) updated = r.getFromDate().atStartOfDay();
        String when = r.getFromDate() == null ? "" : r.getFromDate().equals(r.getToDate())
                ? r.getFromDate().toString() : r.getFromDate() + " to " + r.getToDate();
        return RequestItem.builder()
                .type(T_LEAVE).typeLabel("Leave").id(r.getId()).requestNo(r.getRequestNo())
                .title(nz(r.getLeaveTypeName(), "Leave"))
                .subtitle(when + (r.getDays() == null ? "" : " · " + plain(r.getDays()) + (r.getDays().compareTo(BigDecimal.ONE) == 0 ? " day" : " days")))
                .days(r.getDays()).fromDate(r.getFromDate()).toDate(r.getToDate())
                .requestDate(r.getSubmittedAt() == null ? r.getFromDate() : r.getSubmittedAt().toLocalDate())
                .status(st).statusLabel(portalLabel(st)).rawStatus(raw).rawStatusLabel(r.getRequestStatusLabel())
                .stage(switch (st) {
                    case S_DRAFT -> "Not submitted yet";
                    case S_PENDING -> "Waiting for a decision";
                    case S_APPROVED -> "Approved";
                    case S_COMPLETED -> "Approved · taken";
                    case S_REJECTED -> "Rejected";
                    default -> "Cancelled";
                })
                .submittedAt(r.getSubmittedAt()).updatedAt(updated)
                .decidedBy(r.getDecidedBy()).decidedAt(r.getDecidedAt())
                .decisionRemarks(nz(r.getDecisionRemarks(), r.getCancelledReason()))
                .actionRequired(S_DRAFT.equals(st)).actionText(S_DRAFT.equals(st) ? "Submit this request" : null)
                .cancellable(S_DRAFT.equals(st) || S_PENDING.equals(st)
                        || (S_APPROVED.equals(st) && r.getFromDate() != null && !r.getFromDate().isBefore(LocalDate.now())))
                .submittable(S_DRAFT.equals(st))
                .build();
    }

    private static RequestItem ofExpense(ExpenseClaimDto c) {
        String raw = c.getClaimStatus();
        String st = switch (nz(raw)) {
            case "DRAFT" -> S_DRAFT;
            case "SUBMITTED" -> S_PENDING;
            case "APPROVED" -> S_PROCESSING;
            case "PAID" -> S_COMPLETED;
            case "REJECTED" -> S_REJECTED;
            case "CANCELLED" -> S_CANCELLED;
            default -> S_PENDING;
        };
        LocalDateTime updated = latest(c.getPaidDate() == null ? null : c.getPaidDate().atStartOfDay(),
                c.getDecidedAt(), c.getSubmittedAt(), c.getClaimDate() == null ? null : c.getClaimDate().atStartOfDay());
        String covering = c.getPeriodFrom() == null ? "" : c.getPeriodFrom().equals(c.getPeriodTo())
                ? c.getPeriodFrom().toString() : c.getPeriodFrom() + " to " + c.getPeriodTo();
        return RequestItem.builder()
                .type(T_EXPENSE).typeLabel("Expense claim").id(c.getId()).requestNo(c.getClaimNo())
                .title(nz(c.getPurpose(), "Expense claim"))
                .subtitle(covering + " · " + c.getLineCount() + (c.getLineCount() == 1 ? " item" : " items"))
                .amount(c.getClaimedAmount()).approvedAmount(c.getApprovedAmount())
                .fromDate(c.getPeriodFrom()).toDate(c.getPeriodTo()).requestDate(c.getClaimDate())
                .status(st).statusLabel(portalLabel(st)).rawStatus(raw).rawStatusLabel(c.getClaimStatusLabel())
                .stage(switch (st) {
                    case S_DRAFT -> "Not submitted yet";
                    case S_PENDING -> "Waiting for a decision";
                    case S_PROCESSING -> "Approved — awaiting payment";
                    case S_COMPLETED -> "Paid" + (c.getPaidDate() == null ? "" : " on " + c.getPaidDate());
                    case S_REJECTED -> "Rejected";
                    default -> "Cancelled";
                })
                .submittedAt(c.getSubmittedAt()).updatedAt(updated)
                .decidedBy(c.getDecidedBy()).decidedAt(c.getDecidedAt()).decisionRemarks(c.getDecisionRemarks())
                .actionRequired(S_DRAFT.equals(st)).actionText(S_DRAFT.equals(st) ? "Attach receipts and submit" : null)
                .cancellable(S_DRAFT.equals(st) || S_PENDING.equals(st))
                .submittable(S_DRAFT.equals(st))
                .build();
    }

    private static RequestItem ofAdvance(AdvanceDto a) {
        String raw = a.getAdvanceStatus();
        String st = switch (nz(raw)) {
            case "DRAFT" -> S_DRAFT;
            case "SUBMITTED" -> S_PENDING;
            case "APPROVED" -> S_PROCESSING;
            case "PAID", "RECOVERING" -> S_PROCESSING;
            case "CLOSED" -> S_COMPLETED;
            case "REJECTED" -> S_REJECTED;
            case "CANCELLED" -> S_CANCELLED;
            default -> S_PENDING;
        };
        LocalDateTime updated = latest(a.getPaidDate() == null ? null : a.getPaidDate().atStartOfDay(),
                a.getDecidedAt(), a.getRequestDate() == null ? null : a.getRequestDate().atStartOfDay());
        return RequestItem.builder()
                .type(T_ADVANCE).typeLabel("Advance").id(a.getId()).requestNo(a.getAdvanceNo())
                .title("Salary advance")
                .subtitle((a.getInstallments() == null ? "" : "Recovered over " + a.getInstallments()
                        + (a.getInstallments() == 1 ? " payslip" : " payslips"))
                        + (blank(a.getReason()) ? "" : " · " + a.getReason()))
                .amount(a.getAmount()).approvedAmount(a.getApprovedAmount()).requestDate(a.getRequestDate())
                .status(st).statusLabel(portalLabel(st)).rawStatus(raw).rawStatusLabel(a.getAdvanceStatusLabel())
                .stage(switch (nz(raw)) {
                    case "DRAFT" -> "Not submitted yet";
                    case "SUBMITTED" -> "Waiting for a decision";
                    case "APPROVED" -> "Approved — awaiting pay-out";
                    case "PAID" -> "Paid out — recovery starts with the next payroll";
                    case "RECOVERING" -> "Being recovered · " + money(a.getOutstanding()) + " outstanding";
                    case "CLOSED" -> "Fully recovered";
                    case "REJECTED" -> "Rejected";
                    default -> "Cancelled";
                })
                .submittedAt(a.getRequestDate() == null ? null : a.getRequestDate().atStartOfDay()).updatedAt(updated)
                .decidedBy(a.getDecidedBy()).decidedAt(a.getDecidedAt()).decisionRemarks(a.getDecisionRemarks())
                .actionRequired(S_DRAFT.equals(st)).actionText(S_DRAFT.equals(st) ? "Submit this request" : null)
                .cancellable(S_DRAFT.equals(st) || S_PENDING.equals(st))
                .submittable(S_DRAFT.equals(st))
                .build();
    }

    private static RequestItem ofLoan(LoanDto l) {
        String raw = l.getLoanStatus();
        String st = switch (nz(raw)) {
            case "DRAFT" -> S_DRAFT;
            case "SUBMITTED" -> S_PENDING;
            case "APPROVED", "DISBURSED", "RECOVERING" -> S_PROCESSING;
            case "CLOSED" -> S_COMPLETED;
            case "REJECTED" -> S_REJECTED;
            case "CANCELLED" -> S_CANCELLED;
            default -> S_PENDING;
        };
        LocalDateTime updated = latest(l.getDisbursedDate() == null ? null : l.getDisbursedDate().atStartOfDay(),
                l.getDecidedAt(), l.getRequestDate() == null ? null : l.getRequestDate().atStartOfDay());
        return RequestItem.builder()
                .type(T_LOAN).typeLabel("Loan").id(l.getId()).requestNo(l.getLoanNo())
                .title("Employee loan")
                .subtitle((l.getInstallments() == null ? "" : l.getInstallments() + " instalments"
                        + (l.getInstallmentAmount() == null ? "" : " of " + money(l.getInstallmentAmount())))
                        + (blank(l.getPurpose()) ? "" : " · " + l.getPurpose()))
                .amount(l.getPrincipal()).approvedAmount(l.getApprovedPrincipal()).requestDate(l.getRequestDate())
                .status(st).statusLabel(portalLabel(st)).rawStatus(raw).rawStatusLabel(l.getLoanStatusLabel())
                .stage(switch (nz(raw)) {
                    case "DRAFT" -> "Not submitted yet";
                    case "SUBMITTED" -> "Waiting for a decision";
                    case "APPROVED" -> "Approved — awaiting disbursement";
                    case "DISBURSED" -> "Disbursed — repayment starts with the next payroll";
                    case "RECOVERING" -> "Repaying · " + money(l.getOutstanding()) + " outstanding";
                    case "CLOSED" -> "Repaid in full";
                    case "REJECTED" -> "Rejected";
                    default -> "Cancelled";
                })
                .submittedAt(l.getRequestDate() == null ? null : l.getRequestDate().atStartOfDay()).updatedAt(updated)
                .decidedBy(l.getDecidedBy()).decidedAt(l.getDecidedAt()).decisionRemarks(l.getDecisionRemarks())
                .actionRequired(S_DRAFT.equals(st)).actionText(S_DRAFT.equals(st) ? "Submit this request" : null)
                .cancellable(S_DRAFT.equals(st) || S_PENDING.equals(st))
                .submittable(S_DRAFT.equals(st))
                .build();
    }

    private static RequestItem ofCorrection(AttendanceDto a) {
        String raw = a.getCorrectionStatus();
        String st = switch (nz(raw)) {
            case "REQUESTED" -> S_PENDING;
            case "APPROVED" -> S_COMPLETED;
            case "REJECTED" -> S_REJECTED;
            default -> S_PENDING;
        };
        String asked = a.getProposedStatus() == null ? "" : HrConstants.label(a.getProposedStatus());
        if (a.getProposedCheckIn() != null || a.getProposedCheckOut() != null) {
            asked += (asked.isEmpty() ? "" : " · ")
                    + (a.getProposedCheckIn() == null ? "—" : a.getProposedCheckIn().toLocalTime())
                    + " to " + (a.getProposedCheckOut() == null ? "—" : a.getProposedCheckOut().toLocalTime());
        }
        LocalDateTime updated = latest(a.getCorrectedAt(), a.getCorrectionRequestedAt());
        return RequestItem.builder()
                .type(T_ATTENDANCE).typeLabel("Attendance correction").id(a.getId())
                .requestNo(a.getAttendanceDate() == null ? null : a.getAttendanceDate().toString())
                .title("Correction for " + a.getAttendanceDate())
                .subtitle((a.getAttendanceStatusLabel() == null ? "" : "Recorded " + a.getAttendanceStatusLabel())
                        + (asked.isEmpty() ? "" : " → asked " + asked))
                .fromDate(a.getAttendanceDate()).toDate(a.getAttendanceDate())
                .requestDate(a.getCorrectionRequestedAt() == null ? a.getAttendanceDate() : a.getCorrectionRequestedAt().toLocalDate())
                .status(st).statusLabel(portalLabel(st)).rawStatus(raw).rawStatusLabel(HrConstants.label(raw))
                .stage(switch (st) {
                    case S_PENDING -> "Waiting for a decision";
                    case S_COMPLETED -> "Approved · attendance updated";
                    default -> "Rejected · the day stands as recorded";
                })
                .submittedAt(a.getCorrectionRequestedAt()).updatedAt(updated)
                .decidedBy(a.getCorrectedBy()).decidedAt(a.getCorrectedAt())
                .decisionRemarks(null)
                .actionRequired(false).cancellable(false).submittable(false)
                .build();
    }

    static String portalLabel(String st) {
        return switch (nz(st)) {
            case S_DRAFT -> "Draft";
            case S_PENDING -> "Pending";
            case S_APPROVED -> "Approved";
            case S_PROCESSING -> "Processing";
            case S_COMPLETED -> "Completed";
            case S_REJECTED -> "Rejected";
            case S_CANCELLED -> "Cancelled";
            default -> HrConstants.label(st);
        };
    }

    /* ══ The summary ════════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public Summary summary() {
        HrEmployee me = requireMe();
        List<RequestItem> all = allMine(me);
        YearMonth ym = YearMonth.now();
        LocalDate from = ym.atDay(1), to = ym.atEndOfMonth();

        List<HrResponses.LeaveBalanceDto> balances = self(me, () -> leave.balances(me.getPidEmployee(), ym.getYear()));
        BigDecimal available = balances.stream().map(b -> nzd(b.getAvailable())).reduce(BigDecimal.ZERO, BigDecimal::add);

        AttendanceMonth month = attendanceMonthOf(me, ym);

        List<AdvanceDto> advances = self(me, () -> money.searchAdvances(me.getPidEmployee(), null, PageRequest.of(0, 200)).getContent());
        List<LoanDto> loans = self(me, () -> money.searchLoans(me.getPidEmployee(), null, PageRequest.of(0, 200)).getContent());
        BigDecimal advOut = advances.stream().filter(a -> List.of("PAID", "RECOVERING").contains(nz(a.getAdvanceStatus())))
                .map(a -> nzd(a.getOutstanding())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal loanOut = loans.stream().filter(l -> List.of("DISBURSED", "RECOVERING").contains(nz(l.getLoanStatus())))
                .map(l -> nzd(l.getOutstanding())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal recovery = BigDecimal.ZERO;
        for (AdvanceDto a : advances) if (List.of("PAID", "RECOVERING").contains(nz(a.getAdvanceStatus()))) recovery = recovery.add(nzd(a.getInstallmentAmount()).min(nzd(a.getOutstanding())));
        for (LoanDto l : loans) if (List.of("DISBURSED", "RECOVERING").contains(nz(l.getLoanStatus()))) recovery = recovery.add(nzd(l.getInstallmentAmount()).min(nzd(l.getOutstanding())));

        List<RequestItem> claims = all.stream().filter(r -> T_EXPENSE.equals(r.getType())).toList();
        BigDecimal claimed = BigDecimal.ZERO, approved = BigDecimal.ZERO, paid = BigDecimal.ZERO;
        long expPending = 0, expRejected = 0;
        for (RequestItem c : claims) {
            boolean thisMonth = c.getRequestDate() != null && !c.getRequestDate().isBefore(from) && !c.getRequestDate().isAfter(to);
            if (thisMonth && !S_CANCELLED.equals(c.getStatus()) && !S_DRAFT.equals(c.getStatus())) claimed = claimed.add(nzd(c.getAmount()));
            if (thisMonth && (S_PROCESSING.equals(c.getStatus()) || S_COMPLETED.equals(c.getStatus()))) approved = approved.add(nzd(c.getApprovedAmount()));
            if (thisMonth && S_COMPLETED.equals(c.getStatus())) paid = paid.add(nzd(c.getApprovedAmount()));
            if (S_PENDING.equals(c.getStatus())) expPending++;
            if (S_REJECTED.equals(c.getStatus()) && c.getDecidedAt() != null && c.getDecidedAt().isAfter(LocalDateTime.now().minusDays(90))) expRejected++;
        }

        LocalDateTime recent = LocalDateTime.now().minusDays(90);
        long pending = all.stream().filter(r -> S_PENDING.equals(r.getStatus())).count();
        long approvedN = all.stream().filter(r -> (S_APPROVED.equals(r.getStatus()) || S_PROCESSING.equals(r.getStatus()) || S_COMPLETED.equals(r.getStatus()))
                && r.getDecidedAt() != null && r.getDecidedAt().isAfter(recent)).count();
        long rejectedN = all.stream().filter(r -> S_REJECTED.equals(r.getStatus()) && r.getDecidedAt() != null && r.getDecidedAt().isAfter(recent)).count();
        long drafts = all.stream().filter(r -> S_DRAFT.equals(r.getStatus())).count();

        List<RequestItem> actions = all.stream().filter(RequestItem::isActionRequired).limit(10).toList();
        List<RequestItem> active = all.stream()
                .filter(r -> S_PENDING.equals(r.getStatus()) || S_PROCESSING.equals(r.getStatus()) || S_APPROVED.equals(r.getStatus()))
                .limit(8).toList();

        List<Activity> activity = all.stream()
                .filter(r -> r.getUpdatedAt() != null && r.getUpdatedAt().isAfter(LocalDateTime.now().minusDays(60)))
                .limit(10)
                .map(r -> Activity.builder().type(r.getType()).id(r.getId()).requestNo(r.getRequestNo())
                        .text(r.getTypeLabel() + (r.getRequestNo() == null ? "" : " " + r.getRequestNo()) + " — " + r.getStage())
                        .status(r.getStatus()).at(r.getUpdatedAt()).build())
                .toList();

        AttendanceMonthSummary ms = month.getSummary();
        return Summary.builder()
                .leaveBalances(balances).leaveAvailable(available)
                .pendingRequests(pending).approvedRequests(approvedN).rejectedRequests(rejectedN).draftRequests(drafts)
                .attendanceIssues(ms.getAbsent() + ms.getLate() + ms.getCorrectionsPending())
                .absentThisMonth(ms.getAbsent()).lateThisMonth(ms.getLate()).correctionsPending(ms.getCorrectionsPending())
                .advanceOutstanding(advOut).loanOutstanding(loanOut).monthlyRecovery(recovery)
                .expenseClaimedThisMonth(claimed).expenseApprovedThisMonth(approved).expensePaidThisMonth(paid)
                .expensePending(expPending).expenseRejected(expRejected)
                .today(month.getToday()).month(ms)
                .actions(actions).active(active).activity(activity)
                .latestPayslip(latestPayslip(me))
                .build();
    }

    private PayslipPointer latestPayslip(HrEmployee me) {
        List<HrPayrollLine> lines = payrollLines.findForEmployee(me.getPidEmployee());
        if (lines.isEmpty()) return null;
        HrPayrollLine l = lines.get(0);
        HrPayrollPeriod p = l.getPayroll() == null ? null : periods.findById(l.getPayroll().getPidPeriod()).orElse(null);
        return PayslipPointer.builder()
                .payrollId(l.getPayroll() == null ? null : l.getPayroll().getPidPayroll())
                .lineId(l.getPidPayrollLine())
                .payrollNo(l.getPayroll() == null ? null : l.getPayroll().getPayrollNo())
                .periodLabel(p == null ? null : p.getLabel())
                .netSalary(l.getNetSalary())
                .paymentStatus(l.getPaymentStatus())
                .build();
    }

    /* ══ Attendance ═════════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public AttendanceMonth attendanceMonth(String month) {
        HrEmployee me = requireMe();
        YearMonth ym = blank(month) ? YearMonth.now() : YearMonth.parse(month.trim());
        return attendanceMonthOf(me, ym);
    }

    private AttendanceMonth attendanceMonthOf(HrEmployee me, YearMonth ym) {
        LocalDate from = ym.atDay(1), to = ym.atEndOfMonth();
        List<AttendanceDto> rows = self(me, () -> attendance.forEmployee(me.getPidEmployee(), from, to));
        HrCalendarService.Window w = calendar.window(scope.orgId(), me.getPidBranch(), from, to);

        List<Holiday> holidays = new ArrayList<>();
        List<LocalDate> weekends = new ArrayList<>();
        int working = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            HrHoliday h = w.holidayOn(d);
            if (h != null) holidays.add(Holiday.builder().date(d).name(h.getHolidayName()).type(h.getHolidayType()).build());
            else if (w.isWeekend(d)) weekends.add(d);
            if (w.isWorkingDay(d)) working++;
        }

        long present = 0, absent = 0, late = 0, lv = 0, half = 0, corrections = 0;
        int workedMin = 0, otMin = 0;
        for (AttendanceDto a : rows) {
            switch (nz(a.getAttendanceStatus())) {
                case "PRESENT", "EARLY_LEAVE" -> present++;
                case "LATE" -> { present++; late++; }
                case "ABSENT" -> absent++;
                case "LEAVE" -> lv++;
                case "HALF_DAY" -> { present++; half++; }
                default -> { }
            }
            if (a.getWorkingMinutes() != null) workedMin += a.getWorkingMinutes();
            if (a.getOvertimeMinutes() != null) otMin += a.getOvertimeMinutes();
            if ("REQUESTED".equals(a.getCorrectionStatus())) corrections++;
        }
        LocalDate lastDay = to.isAfter(LocalDate.now()) ? LocalDate.now() : to;
        long recordedWorking = rows.stream().filter(a -> !List.of("HOLIDAY", "WEEKEND").contains(nz(a.getAttendanceStatus()))).count();
        int workingSoFar = 0;
        for (LocalDate d = from; !d.isAfter(lastDay); d = d.plusDays(1)) if (w.isWorkingDay(d)) workingSoFar++;
        long unrecorded = Math.max(0, workingSoFar - recordedWorking);

        AttendanceDto today = rows.stream().filter(a -> LocalDate.now().equals(a.getAttendanceDate())).findFirst().orElse(null);
        return AttendanceMonth.builder()
                .month(ym.toString()).from(from).to(to).today(today).rows(rows)
                .holidays(holidays).weekends(weekends)
                .summary(AttendanceMonthSummary.builder()
                        .month(ym.toString()).calendarDays(ym.lengthOfMonth()).workingDays(working)
                        .present(present).absent(absent).late(late).leave(lv).halfDay(half)
                        .holiday(holidays.size()).weekend(weekends.size()).unrecorded(unrecorded)
                        .workedHours(HrAttendance.hours(workedMin)).overtimeHours(HrAttendance.hours(otMin))
                        .correctionsPending(corrections)
                        .build())
                .build();
    }

    @Transactional(readOnly = true)
    public HrResponses.HrCalendarDto calendar(String month) {
        HrEmployee me = requireMe();
        YearMonth ym = blank(month) ? YearMonth.now() : YearMonth.parse(month.trim());
        return self(me, () -> calendarView.view(ym.atDay(1), ym.atEndOfMonth(), me.getPidEmployee()));
    }

    /* ══ Reference lists ════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public List<HrResponses.LeaveBalanceDto> leaveBalances() {
        HrEmployee me = requireMe();
        return self(me, () -> leave.balances(me.getPidEmployee(), LocalDate.now().getYear()));
    }

    @Transactional(readOnly = true)
    public List<HrResponses.LeaveTypeDto> leaveTypes() {
        HrEmployee me = requireMe();
        return self(me, () -> setup.listLeaveTypes(true));
    }

    @Transactional(readOnly = true)
    public List<HrResponses.ExpenseTypeDto> expenseTypes() {
        HrEmployee me = requireMe();
        return self(me, () -> expense.listTypes(true));
    }

    /* ══ Writes — always as me, never a decision ════════════════════════════ */

    /**
     * Bean validation, run after the employee has been filled in from the
     * login — the same constraints the HR screens meet at the controller.
     */
    private void validate(Object req) {
        Set<ConstraintViolation<Object>> bad = validator.validate(req);
        if (!bad.isEmpty()) throw new ConstraintViolationException(bad);
    }

    @Transactional
    public LeaveRequestDto fileLeave(HrRequests.LeaveRequest req) {
        HrEmployee me = requireMe();
        req.setEmployeeId(me.getPidEmployee());
        if (req.getSubmit() == null) req.setSubmit(true);
        validate(req);
        return self(me, () -> leave.create(req));
    }

    @Transactional
    public ExpenseClaimDto fileExpense(HrRequests.ExpenseClaim req) {
        HrEmployee me = requireMe();
        req.setEmployeeId(me.getPidEmployee());
        validate(req);
        return self(me, () -> expense.create(req));
    }

    @Transactional
    public ExpenseClaimDto submitExpense(Long claimId) {
        HrEmployee me = requireMe();
        return self(me, () -> { own(expense.get(claimId).getEmployeeId(), me, claimId); return expense.submit(claimId); });
    }

    @Transactional
    public HrResponses.ExpenseLineDto attachReceipt(Long lineId, MultipartFile file) {
        HrEmployee me = requireMe();
        ownLine(lineId, me);
        return self(me, () -> expense.attachReceipt(lineId, file));
    }

    @Transactional
    public void removeReceipt(Long lineId) {
        HrEmployee me = requireMe();
        ownLine(lineId, me);
        self(me, () -> { expense.removeReceipt(lineId); return null; });
    }

    private void ownLine(Long lineId, HrEmployee me) {
        var line = expenseLines.findByPidExpenseLineAndClaim_PidBranch(lineId, me.getPidBranch())
                .orElseThrow(() -> new ResourceNotFoundException("Expense line", lineId));
        own(line.getClaim().getPidEmployee(), me, lineId);
    }

    @Transactional
    public AdvanceDto requestAdvance(HrRequests.Advance req) {
        HrEmployee me = requireMe();
        req.setEmployeeId(me.getPidEmployee());
        if (req.getSubmit() == null) req.setSubmit(true);
        validate(req);
        return self(me, () -> money.createAdvance(req));
    }

    @Transactional
    public LoanDto requestLoan(HrRequests.Loan req) {
        HrEmployee me = requireMe();
        req.setEmployeeId(me.getPidEmployee());
        if (req.getSubmit() == null) req.setSubmit(true);
        validate(req);
        return self(me, () -> money.createLoan(req));
    }

    @Transactional
    public AttendanceDto requestCorrection(Long attendanceId, HrRequests.AttendanceCorrection req) {
        HrEmployee me = requireMe();
        HrAttendance a = attendanceRepo.findById(attendanceId)
                .orElseThrow(() -> new ResourceNotFoundException("Attendance", attendanceId));
        own(a.getPidEmployee(), me, attendanceId);
        return self(me, () -> attendance.requestCorrection(attendanceId, req));
    }

    /** Withdraws one of my own requests. Never a decision — a decision is somebody else's. */
    @Transactional
    public RequestItem cancel(String type, Long id, String reason) {
        HrEmployee me = requireMe();
        HrRequests.Decision d = new HrRequests.Decision();
        d.setRemarks(blank(reason) ? "Withdrawn by the employee" : reason.trim());
        String t = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        return self(me, () -> switch (t) {
            case T_LEAVE -> {
                LeaveRequestDto r = leave.search(me.getPidEmployee(), null, null, null, PageRequest.of(0, 500))
                        .getContent().stream().filter(x -> x.getId().equals(id)).findFirst()
                        .orElseThrow(() -> new ResourceNotFoundException("Request", id));
                yield ofLeave(leave.cancel(r.getId(), d));
            }
            case T_EXPENSE -> { own(expense.get(id).getEmployeeId(), me, id); yield ofExpense(expense.cancel(id, d)); }
            case T_ADVANCE -> {
                AdvanceDto a = money.searchAdvances(me.getPidEmployee(), null, PageRequest.of(0, 500))
                        .getContent().stream().filter(x -> x.getId().equals(id)).findFirst()
                        .orElseThrow(() -> new ResourceNotFoundException("Request", id));
                yield ofAdvance(money.cancelAdvance(a.getId(), d));
            }
            case T_LOAN -> { own(money.getLoan(id).getEmployeeId(), me, id); yield ofLoan(money.cancelLoan(id, d)); }
            default -> throw new BusinessException("This kind of request cannot be withdrawn here.",
                    HttpStatus.BAD_REQUEST, "HR_SELF_NOT_CANCELLABLE");
        });
    }

    /* ══ Small helpers ══════════════════════════════════════════════════════ */

    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String nz(String s) { return s == null ? "" : s; }
    private static String nz(String s, String fallback) { return blank(s) ? fallback : s; }
    private static BigDecimal nzd(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private static String plain(BigDecimal v) { return v == null ? "" : v.stripTrailingZeros().toPlainString(); }
    private static String money(BigDecimal v) { return String.format("%,.2f", nzd(v)); }

    @SafeVarargs
    private static LocalDateTime latest(LocalDateTime... ts) {
        LocalDateTime best = null;
        for (LocalDateTime t : ts) if (t != null && (best == null || t.isAfter(best))) best = t;
        return best;
    }

    /** Whether the signed-in user is somebody an employee record is linked to. */
    @Transactional(readOnly = true)
    public Optional<HrEmployee> linkedEmployee() { return Optional.ofNullable(whoAmI()); }
}
