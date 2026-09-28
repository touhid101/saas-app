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
 * The Employees screen: the HR summary, one person's whole record, and their
 * departure.
 *
 * <h3>Why there is no employee CRUD here</h3>
 *
 * <p>There already is one. {@code MasterDataServiceImpl} serves
 * {@code /api/employees} and has since before HR existed; the employee form on
 * the Master Data screen writes through it, {@code DeliveryManService} reads
 * the same rows, and {@code AclUser.pidEmployee} points at them. A second
 * create-and-update path here would be two screens editing one list, two sets
 * of validation, and one of them eventually wrong. So this service adds what
 * the master screen has no business knowing about — pay basis, leave, money
 * lent, payslips, separation — and leaves the record itself where it is.
 *
 * <h3>Separation, and why nobody is deleted</h3>
 *
 * <p>Staff records are referenced by invoices and by the delivery history.
 * Removing one would leave those rows pointing at nothing, and a report of who
 * sold what last year would quietly lose a person. Approving a separation sets
 * {@code STATUS} to N — the same flag every other master in this schema uses to
 * mean "not in the pickers any more" — and the record stays.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrEmployeeServiceImpl {

    private final HrEmployeeRepository        employeeRepo;
    private final HrSeparationRepository      separationRepo;
    private final HrSalaryStructureRepository structureRepo;
    private final HrLeaveBalanceRepository    balanceRepo;
    private final HrLeaveTypeRepository       leaveTypeRepo;
    private final HrLeaveRequestRepository    leaveRepo;
    private final HrAdvanceRepository         advanceRepo;
    private final HrLoanRepository            loanRepo;
    private final HrPayrollRepository         payrollRepo;
    private final HrPayrollLineRepository     lineRepo;
    private final HrAttendanceRepository      attendanceRepo;
    private final HrExpenseClaimRepository    expenseClaimRepo;
    private final HrShiftRepository           shiftRepo;
    private final HrAdvanceLoanServiceImpl    advanceLoan;
    private final HrPayrollServiceImpl        payroll;
    private final HrScope                     scope;

    /* ══════════════════════════════════════════════════════════════════════
       The summary
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * Everything the HR home screen shows, in one read.
     *
     * <p>Six requests painting a page in stages is six chances to show a number
     * that disagrees with the one beside it.
     */
    @Transactional(readOnly = true)
    public HrResponses.HrSummaryDto summary() {
        scope.require(HrScope.MOD_EMPLOYEE, PermissionGuard.Right.VIEW, "see the HR summary");

        Long branch = scope.branchId();
        LocalDate today = LocalDate.now();

        List<HrEmployee> staff = employeeRepo.findByPidBranchAndStatusOrderByEmpName(branch, "Y");
        long ready = staff.stream().filter(HrEmployee::isPayrollReady).count();

        Map<String, Long> byType = new LinkedHashMap<>();
        for (HrEmployee e : staff) byType.merge(e.getEmpType(), 1L, Long::sum);

        long present = 0, absent = 0, onLeave = 0, recorded = 0;
        for (Object[] row : attendanceRepo.countByStatusOn(branch, today)) {
            String status = (String) row[0];
            long n = ((Number) row[1]).longValue();
            recorded += n;
            if (HrConstants.AttendanceStatus.WORKED.contains(status)) present += n;
            else if (HrConstants.AttendanceStatus.ABSENT.equals(status)) absent += n;
            else if (HrConstants.AttendanceStatus.LEAVE.equals(status))  onLeave += n;
        }

        BigDecimal advanceOut = BigDecimal.ZERO;
        for (HrAdvance a : advanceRepo.findRecoverable(branch, today)) {
            advanceOut = advanceOut.add(a.getOutstanding());
        }
        BigDecimal loanOut = BigDecimal.ZERO;
        for (HrLoan l : loanRepo.findRecoverable(branch)) {
            loanOut = loanOut.add(l.getOutstanding());
        }

        // The latest run, whatever state it is in — a manager opening HR wants
        // to know whether this month has been done, not only whether it is paid.
        HrResponses.PayrollDto latest = payrollRepo
                .findByPidBranchAndStatusOrderByPidPayrollDesc(branch, "Y")
                .stream().findFirst()
                .map(p -> payroll.get(p.getPidPayroll()))
                .orElse(null);

        return HrResponses.HrSummaryDto.builder()
                .activeEmployees(staff.size())
                .payrollReady(ready)
                .notPayrollReady(staff.size() - ready)
                .byEmployeeType(byType)
                .presentToday(present)
                .absentToday(absent)
                .onLeaveToday(onLeave)
                .attendanceNotRecordedToday(Math.max(0, staff.size() - recorded))
                .pendingLeaveRequests(leaveRepo.countByPidBranchAndRequestStatusAndStatus(
                        branch, HrConstants.LeaveStatus.SUBMITTED, "Y"))
                .pendingAdvances(advanceRepo.countByPidBranchAndAdvanceStatusAndStatus(
                        branch, HrConstants.AdvanceStatus.SUBMITTED, "Y"))
                .pendingLoans(loanRepo.countByPidBranchAndLoanStatusAndStatus(
                        branch, HrConstants.LoanStatus.SUBMITTED, "Y"))
                .pendingSeparations(separationRepo.countByPidBranchAndSeparationStatusAndStatus(
                        branch, HrConstants.SeparationStatus.SUBMITTED, "Y"))
                .pendingExpenseClaims(expenseClaimRepo.countByPidBranchAndClaimStatusAndStatus(
                        branch, HrExpenseClaim.SUBMITTED, "Y"))
                .pendingAttendanceCorrections(
                        attendanceRepo.countByPidBranchAndCorrectionStatusAndStatus(
                                branch, HrAttendance.CORRECTION_REQUESTED, "Y"))
                .advanceOutstanding(advanceOut)
                .loanOutstanding(loanOut)
                .latestPayroll(latest)
                .build();
    }

    /* ══════════════════════════════════════════════════════════════════════
       One person
       ══════════════════════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public HrResponses.EmployeeProfileDto profile(Long employeeId) {
        scope.require(HrScope.MOD_EMPLOYEE, PermissionGuard.Right.VIEW, "see an employee record");

        HrEmployee e = scope.employee(employeeId);
        int year = LocalDate.now().getYear();

        Map<Long, HrLeaveType> types = new HashMap<>();
        for (HrLeaveType t : leaveTypeRepo.findByPidBranchOrderByTypeName(e.getPidBranch())) {
            types.put(t.getPidLeaveType(), t);
        }

        List<HrResponses.LeaveBalanceDto> balances = balanceRepo
                .findByPidEmployeeAndLeaveYearOrderByPidLeaveType(employeeId, year)
                .stream()
                .map(b -> HrResponses.LeaveBalanceDto.builder()
                        .id(b.getPidBalance())
                        .employeeId(b.getPidEmployee())
                        .empName(e.getEmpName())
                        .leaveTypeId(b.getPidLeaveType())
                        .leaveTypeName(Optional.ofNullable(types.get(b.getPidLeaveType()))
                                .map(HrLeaveType::getTypeName).orElse(null))
                        .leaveYear(b.getLeaveYear())
                        .entitled(b.getEntitled())
                        .carriedForward(b.getCarriedForward())
                        .taken(b.getTaken())
                        .pending(b.getPending())
                        .available(b.getAvailable())
                        .build())
                .toList();

        List<HrResponses.AdvanceDto> advances = advanceRepo
                .findByPidEmployeeAndStatusOrderByRequestDateDesc(employeeId, "Y")
                .stream().map(a -> advanceLoan.map(a, e)).toList();

        List<HrResponses.LoanDto> loans = loanRepo
                .findByPidEmployeeAndStatusOrderByRequestDateDesc(employeeId, "Y")
                .stream().map(l -> advanceLoan.map(l, e, true)).toList();

        // Six months is a year of payslips on a screen and more than anybody
        // reads; the payroll register is where a longer history belongs.
        List<HrResponses.PayslipDto> payslips = lineRepo.findForEmployee(employeeId)
                .stream().limit(6).map(payroll::mapPayslip).toList();

        HrSeparation sep = separationRepo.findByPidEmployeeOrderByLastWorkingDateDesc(employeeId)
                .stream().findFirst().orElse(null);

        String structureName = e.getPidSalaryStructure() == null ? null
                : structureRepo.findById(e.getPidSalaryStructure())
                        .map(HrSalaryStructure::getStructureName).orElse(null);

        return HrResponses.EmployeeProfileDto.builder()
                .employeeId(e.getPidEmployee())
                .empId(e.getEmpId())
                .empName(e.getEmpName())
                .preferredName(e.getPreferredName())
                .imagePath(e.getImagePath())
                .initials(e.getInitials())
                .empType(e.getEmpType())
                .empTypeLabel(EmployeeType.label(e.getEmpType()))
                .employmentType(e.getEmploymentType())
                .employmentTypeLabel(HrConstants.label(e.getEmploymentType()))
                .hireDate(e.getHireDate())
                .confirmationDate(e.getConfirmationDate())
                .phone(e.getPhone())
                .email(e.getEmail())
                .status(e.getStatus())
                .dateOfBirth(e.getDateOfBirth())
                .maritalStatus(e.getMaritalStatus())
                .gender(e.getGender())
                .nationalId(e.getNationalId())
                .bloodGroup(e.getBloodGroup())
                .fatherName(e.getFatherName())
                .motherName(e.getMotherName())
                .emergencyContact(e.getEmergencyContact())
                .address(e.getAddress())
                .presentAddress(e.getPresentAddress())
                .grade(e.getGrade())
                .probationMonths(e.getProbationMonths())
                .noticePeriodDays(e.getNoticePeriodDays())
                .managerId(e.getPidManager())
                .managerName(e.getPidManager() == null ? null
                        : employeeRepo.findById(e.getPidManager())
                                      .map(HrEmployee::getEmpName).orElse(null))
                .shiftId(e.getPidShift())
                .shiftName(e.getPidShift() == null ? null
                        : shiftRepo.findById(e.getPidShift())
                                   .map(HrShift::getShiftName).orElse(null))
                .pfApplicable(e.getPfApplicable())
                .gratuityApplicable(e.getGratuityApplicable())
                .taxApplicable(e.getTaxApplicable())
                .vehicleNo(e.getVehicleNo())
                .basicSalary(e.getBasicSalary())
                .salaryStructureId(e.getPidSalaryStructure())
                .salaryStructureName(structureName)
                .payrollReady(e.isPayrollReady())
                .payrollBlockedReason(payrollBlockedReason(e))
                .salaryPaymentMode(e.getSalaryPaymentMode())
                .bankName(e.getBankName())
                .bankBranch(e.getBankBranch())
                .bankAccountNo(e.getBankAccountNo())
                .mobileWalletNo(e.getMobileWalletNo())
                .leaveBalances(balances)
                .advances(advances)
                .loans(loans)
                .recentPayslips(payslips)
                .separation(sep == null ? null : mapSeparation(sep, e))
                .build();
    }

    /**
     * Why payroll will skip somebody.
     *
     * <p>The screen should say rather than hint. "Not payroll ready" beside a
     * name leaves a manager guessing between a missing salary, a missing
     * structure and a leaver.
     */
    private static String payrollBlockedReason(HrEmployee e) {
        if (!e.isActive())                return "No longer active.";
        if (e.getBasicSalary() == null)   return "No basic salary has been set.";
        if (e.getBasicSalary().signum() <= 0) return "The basic salary is zero.";
        if (e.getPidSalaryStructure() == null) return "No salary structure has been chosen.";
        return null;
    }

    /* ══════════════════════════════════════════════════════════════════════
       The employee record
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * Updates the part of a staff record HR owns.
     *
     * <p><b>Not the whole record, on purpose.</b> Name, code, phone, role,
     * department, designation and vehicle stay on {@code /api/employees}, which
     * the Master Data screen and the delivery list have used since before HR
     * existed. What is here is the personal detail and the employment terms —
     * columns V73 and V78 added, which until now had no form at all and were
     * therefore unreachable from anywhere in the application.
     *
     * <p>Every field is optional and a null leaves the existing value alone, so
     * the three sections of the form save independently.
     */
    @Transactional
    public HrResponses.EmployeeProfileDto updateDetails(Long employeeId,
                                                        HrRequests.EmployeeDetails req) {
        scope.require(HrScope.MOD_EMPLOYEE, PermissionGuard.Right.EDIT, "change an employee record");

        HrEmployee e = scope.employee(employeeId);

        /* ── Personal ── */
        if (req.getPreferredName()    != null) e.setPreferredName(blankToNull(req.getPreferredName()));
        if (req.getDateOfBirth()      != null) e.setDateOfBirth(req.getDateOfBirth());
        if (req.getGender()           != null) e.setGender(blankToNull(req.getGender()));
        if (req.getNationalId()       != null) e.setNationalId(blankToNull(req.getNationalId()));
        if (req.getBloodGroup()       != null) e.setBloodGroup(blankToNull(req.getBloodGroup()));
        if (req.getFatherName()       != null) e.setFatherName(blankToNull(req.getFatherName()));
        if (req.getMotherName()       != null) e.setMotherName(blankToNull(req.getMotherName()));
        if (req.getEmergencyContact() != null) e.setEmergencyContact(blankToNull(req.getEmergencyContact()));
        if (req.getEmail()            != null) e.setEmail(blankToNull(req.getEmail()));
        if (req.getAddress()          != null) e.setAddress(blankToNull(req.getAddress()));
        if (req.getPresentAddress()   != null) e.setPresentAddress(blankToNull(req.getPresentAddress()));

        if (req.getMaritalStatus() != null && !req.getMaritalStatus().isBlank()) {
            e.setMaritalStatus(requireOneOf(req.getMaritalStatus(),
                    List.of("SINGLE", "MARRIED", "DIVORCED", "WIDOWED", "OTHER"), "marital status"));
        }

        /* A date of birth in the future, or one that makes somebody a hundred
           and forty, is a typo rather than a person. */
        if (e.getDateOfBirth() != null) {
            LocalDate dob = e.getDateOfBirth();
            if (dob.isAfter(LocalDate.now())) {
                throw new BusinessException("The date of birth is in the future.",
                        HttpStatus.BAD_REQUEST, "HR_DOB_FUTURE");
            }
            if (dob.isBefore(LocalDate.now().minusYears(120))) {
                throw new BusinessException("Check the date of birth — that is over 120 years ago.",
                        HttpStatus.BAD_REQUEST, "HR_DOB_IMPLAUSIBLE");
            }
        }

        /* ── Employment ── */
        if (req.getHireDate()         != null) e.setHireDate(req.getHireDate());
        if (req.getConfirmationDate() != null) e.setConfirmationDate(req.getConfirmationDate());
        if (req.getGrade()            != null) e.setGrade(blankToNull(req.getGrade()));
        if (req.getProbationMonths()  != null) e.setProbationMonths(req.getProbationMonths());
        if (req.getNoticePeriodDays() != null) e.setNoticePeriodDays(req.getNoticePeriodDays());

        if (req.getEmploymentType() != null && !req.getEmploymentType().isBlank()) {
            e.setEmploymentType(requireOneOf(req.getEmploymentType(),
                    HrConstants.EmploymentType.ALL, "employment type"));
        }
        if (e.getConfirmationDate() != null && e.getHireDate() != null
                && e.getConfirmationDate().isBefore(e.getHireDate())) {
            throw new BusinessException("Confirmation cannot be before the joining date.",
                    HttpStatus.BAD_REQUEST, "HR_CONFIRMED_BEFORE_HIRED");
        }

        if (req.getManagerId() != null) {
            if (req.getManagerId().equals(employeeId)) {
                throw new BusinessException(e.getEmpName() + " cannot report to themselves.",
                        HttpStatus.BAD_REQUEST, "HR_MANAGER_SELF");
            }
            // Resolved through scope, so a manager at another branch is not
            // found rather than refused — the same rule as everywhere else.
            HrEmployee manager = scope.employee(req.getManagerId());
            requireNoReportingCycle(e, manager);
            e.setPidManager(manager.getPidEmployee());
        }

        if (req.getShiftId() != null) {
            shiftRepo.findByPidShiftAndPidBranch(req.getShiftId(), e.getPidBranch())
                    .filter(s -> "Y".equals(s.getStatus()))
                    .orElseThrow(() -> new BusinessException(
                            "That shift does not exist at this branch, or has been retired.",
                            HttpStatus.BAD_REQUEST, "HR_SHIFT_NOT_FOUND"));
            e.setPidShift(req.getShiftId());
        }

        /* ── Schemes ──
           Recorded only. Nothing computes a contribution or an entitlement,
           because no rate or formula is configured anywhere — see P-07 and P-08
           in 01-hr-gap-analysis.md. */
        if (req.getPfApplicable()       != null) e.setPfApplicable(flag(req.getPfApplicable()));
        if (req.getGratuityApplicable() != null) e.setGratuityApplicable(flag(req.getGratuityApplicable()));
        if (req.getTaxApplicable()      != null) e.setTaxApplicable(flag(req.getTaxApplicable()));

        employeeRepo.save(e);
        return profile(employeeId);
    }

    /**
     * Refuses a reporting line that loops back on itself.
     *
     * <p>The database catches the one-step case; this catches the rest. A cycle
     * makes a team view recurse for ever, and it is entered one honest step at a
     * time — A reports to B today, B to A next month.
     */
    private void requireNoReportingCycle(HrEmployee employee, HrEmployee manager) {
        Long target = employee.getPidEmployee();
        HrEmployee walk = manager;
        int guard = 0;
        while (walk != null && guard++ < 50) {
            if (target.equals(walk.getPidEmployee())) {
                throw new BusinessException(
                        manager.getEmpName() + " already reports to " + employee.getEmpName()
                        + ", directly or through somebody else. That would be a loop.",
                        HttpStatus.CONFLICT, "HR_MANAGER_CYCLE");
            }
            Long next = walk.getPidManager();
            walk = next == null ? null : employeeRepo.findById(next).orElse(null);
        }
    }

    private static Integer flag(Integer raw) {
        return raw != null && raw == 1 ? 1 : 0;
    }

    /* ══════════════════════════════════════════════════════════════════════
       Pay basis
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * Sets what somebody is paid and how.
     *
     * <p>Separate from the employee form on purpose: a branch administrator may
     * legitimately maintain staff records without being allowed to see or set
     * salaries, and the two screens are gated by different modules —
     * {@code MOD02} for the record, {@code MOD32} for the money.
     */
    @Transactional
    public HrResponses.EmployeeProfileDto setPayBasis(Long employeeId, BigDecimal basicSalary,
                                                      Long structureId, String employmentType,
                                                      String paymentMode, String bankName,
                                                      String bankBranch, String bankAccountNo,
                                                      String mobileWalletNo, LocalDate confirmationDate) {
        scope.require(HrScope.MOD_EMPLOYEE, PermissionGuard.Right.EDIT, "set an employee pay basis");

        HrEmployee e = scope.employee(employeeId);

        if (basicSalary != null) {
            if (basicSalary.signum() < 0) {
                throw new BusinessException("A basic salary cannot be negative.",
                        HttpStatus.BAD_REQUEST, "HR_SALARY_NEGATIVE");
            }
            e.setBasicSalary(basicSalary);
        }
        if (structureId != null) {
            HrSalaryStructure s = structureRepo.findByPidStructureAndPidBranch(structureId, e.getPidBranch())
                    .orElseThrow(() -> new ResourceNotFoundException("Salary structure", structureId));
            if (!s.isActive()) {
                throw new BusinessException(
                        s.getStructureName() + " has been retired. Choose an active structure.",
                        HttpStatus.CONFLICT, "HR_STRUCTURE_INACTIVE");
            }
            e.setPidSalaryStructure(structureId);
        }
        if (employmentType != null) {
            e.setEmploymentType(requireOneOf(employmentType,
                    HrConstants.EmploymentType.ALL, "employment type"));
        }
        if (paymentMode != null) {
            e.setSalaryPaymentMode(requireOneOf(paymentMode,
                    HrConstants.PaymentMode.ALL, "payment method"));
        }
        if (bankName       != null) e.setBankName(blankToNull(bankName));
        if (bankBranch     != null) e.setBankBranch(blankToNull(bankBranch));
        if (bankAccountNo  != null) e.setBankAccountNo(blankToNull(bankAccountNo));
        if (mobileWalletNo != null) e.setMobileWalletNo(blankToNull(mobileWalletNo));
        if (confirmationDate != null) e.setConfirmationDate(confirmationDate);

        // A bank payment with no account number is a payment nobody can make.
        if (HrConstants.PaymentMode.BANK.equals(e.getSalaryPaymentMode())
                && blankToNull(e.getBankAccountNo()) == null) {
            throw new BusinessException(
                    "Salary is set to be paid by bank, so an account number is needed.",
                    HttpStatus.BAD_REQUEST, "HR_BANK_ACCOUNT_REQUIRED");
        }
        if (HrConstants.PaymentMode.MOBILE.equals(e.getSalaryPaymentMode())
                && blankToNull(e.getMobileWalletNo()) == null) {
            throw new BusinessException(
                    "Salary is set to be paid to a mobile wallet, so a wallet number is needed.",
                    HttpStatus.BAD_REQUEST, "HR_WALLET_REQUIRED");
        }

        employeeRepo.save(e);
        return profile(employeeId);
    }

    /* ══════════════════════════════════════════════════════════════════════
       Separation
       ══════════════════════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public PageResponse<HrResponses.SeparationDto> searchSeparations(String status, Pageable pageable) {
        scope.require(HrScope.MOD_EMPLOYEE, PermissionGuard.Right.VIEW, "see separations");
        Map<Long, HrEmployee> staff = new HashMap<>();
        for (HrEmployee e : employeeRepo.findByPidBranchAndStatusOrderByEmpName(scope.branchId(), "Y")) {
            staff.put(e.getPidEmployee(), e);
        }
        return PageResponse.from(
                separationRepo.search(scope.branchId(),
                                      status == null ? "" : status.trim().toUpperCase(), pageable)
                        .map(s -> mapSeparation(s, staff.get(s.getPidEmployee()))));
    }

    @Transactional
    public HrResponses.SeparationDto createSeparation(HrRequests.Separation req) {
        scope.require(HrScope.MOD_EMPLOYEE, PermissionGuard.Right.CREATE, "record a separation");

        HrEmployee e = scope.activeEmployee(req.getEmployeeId());
        separationRepo.findLiveFor(e.getPidEmployee()).ifPresent(x -> {
            throw new BusinessException(
                    e.getEmpName() + " already has a separation in progress.",
                    HttpStatus.CONFLICT, "HR_SEPARATION_EXISTS");
        });

        String type = requireOneOf(req.getSeparationType(),
                HrConstants.SeparationType.ALL, "separation type");
        if (req.getNoticeDate() != null && req.getLastWorkingDate().isBefore(req.getNoticeDate())) {
            throw new BusinessException("The last working day is before notice was given.",
                    HttpStatus.BAD_REQUEST, "HR_DATE_RANGE");
        }

        HrSeparation s = HrSeparation.builder()
                .pidOrg(scope.orgId())
                .pidCompany(scope.companyId())
                .pidBranch(e.getPidBranch())
                .pidEmployee(e.getPidEmployee())
                .separationType(type)
                .noticeDate(req.getNoticeDate())
                .lastWorkingDate(req.getLastWorkingDate())
                .noticePeriodDays(req.getNoticePeriodDays())
                .reason(req.getReason())
                .separationStatus(HrConstants.SeparationStatus.DRAFT)
                .settlementStatus(HrConstants.SettlementStatus.PENDING)
                .build();
        s.setStatus("Y");
        separationRepo.save(s);

        if (Boolean.TRUE.equals(req.getSubmit())) {
            s.setSeparationStatus(HrConstants.SeparationStatus.SUBMITTED);
            separationRepo.save(s);
        }
        return mapSeparation(s, e);
    }

    /**
     * Approves a separation, which deactivates the employee.
     *
     * <p>Refused while money is still owed in either direction. An employee
     * with an unrecovered advance who is deactivated drops out of every payroll
     * run, and the advance can then never be recovered from a payslip — so the
     * question has to be answered before the door closes, not after.
     */
    @Transactional
    public HrResponses.SeparationDto approveSeparation(Long id, HrRequests.Decision d) {
        scope.require(HrScope.MOD_EMPLOYEE, PermissionGuard.Right.APPROVE, "approve a separation");

        HrSeparation s = separation(id);
        if (!HrConstants.SeparationStatus.SUBMITTED.equals(s.getSeparationStatus())) {
            throw new BusinessException(
                    "Only a submitted separation can be approved. This one is "
                    + HrConstants.label(s.getSeparationStatus()).toLowerCase() + ".",
                    HttpStatus.CONFLICT, "HR_WRONG_STATE");
        }
        HrEmployee e = scope.employee(s.getPidEmployee());

        BigDecimal owed = outstandingFor(e);
        if (owed.signum() > 0) {
            throw new BusinessException(
                    e.getEmpName() + " still owes " + owed.stripTrailingZeros().toPlainString()
                    + " on advances or loans. Recover or write it off before approving the "
                    + "separation, because a deactivated employee is skipped by every payroll run.",
                    HttpStatus.CONFLICT, "HR_SEPARATION_MONEY_OWED");
        }

        s.setSeparationStatus(HrConstants.SeparationStatus.APPROVED);
        s.setDecidedBy(scope.actor());
        s.setDecidedAt(LocalDateTime.now());
        s.setDecisionRemarks(d == null ? null : d.getRemarks());
        separationRepo.save(s);

        // Deactivated, never deleted — see the class comment.
        e.setStatus("N");
        employeeRepo.save(e);

        log.info("Employee {} deactivated by separation {}", e.getPidEmployee(), s.getPidSeparation());
        return mapSeparation(s, e);
    }

    @Transactional
    public HrResponses.SeparationDto rejectSeparation(Long id, HrRequests.Decision d) {
        scope.require(HrScope.MOD_EMPLOYEE, PermissionGuard.Right.APPROVE, "reject a separation");
        HrSeparation s = separation(id);
        if (!HrConstants.SeparationStatus.SUBMITTED.equals(s.getSeparationStatus())) {
            throw new BusinessException("Only a submitted separation can be rejected.",
                    HttpStatus.CONFLICT, "HR_WRONG_STATE");
        }
        s.setSeparationStatus(HrConstants.SeparationStatus.REJECTED);
        s.setDecidedBy(scope.actor());
        s.setDecidedAt(LocalDateTime.now());
        s.setDecisionRemarks(d == null ? null : d.getRemarks());
        return mapSeparation(separationRepo.save(s), scope.employee(s.getPidEmployee()));
    }

    /**
     * Cancels a separation, bringing the employee back.
     *
     * <p>Reactivation only happens for a separation that was approved: a
     * withdrawn resignation should leave somebody exactly as they were, and
     * that includes not reactivating an account that was deactivated for an
     * unrelated reason.
     */
    @Transactional
    public HrResponses.SeparationDto cancelSeparation(Long id, HrRequests.Decision d) {
        scope.require(HrScope.MOD_EMPLOYEE, PermissionGuard.Right.DELETE, "cancel a separation");

        HrSeparation s = separation(id);
        boolean wasApproved = s.isApproved();
        s.setSeparationStatus(HrConstants.SeparationStatus.CANCELLED);
        s.setDecidedBy(scope.actor());
        s.setDecidedAt(LocalDateTime.now());
        s.setDecisionRemarks(d == null ? null : d.getRemarks());
        separationRepo.save(s);

        HrEmployee e = scope.employee(s.getPidEmployee());
        if (wasApproved) {
            e.setStatus("Y");
            employeeRepo.save(e);
        }
        return mapSeparation(s, e);
    }

    @Transactional
    public HrResponses.SeparationDto settle(Long id, HrRequests.Settlement req) {
        scope.require(HrScope.MOD_EMPLOYEE, PermissionGuard.Right.EDIT, "close a settlement");

        HrSeparation s = separation(id);
        if (!s.isApproved()) {
            throw new BusinessException(
                    "The separation has not been approved yet, so there is nothing to settle.",
                    HttpStatus.CONFLICT, "HR_SEPARATION_NOT_APPROVED");
        }
        String status = requireOneOf(req.getSettlementStatus(),
                List.of(HrConstants.SettlementStatus.SETTLED, HrConstants.SettlementStatus.WAIVED),
                "settlement status");

        s.setSettlementStatus(status);
        s.setSettlementAmount(req.getSettlementAmount());
        s.setSettlementDate(req.getSettlementDate() == null ? LocalDate.now() : req.getSettlementDate());
        if (req.getRemarks() != null) s.setRemarks(req.getRemarks());
        return mapSeparation(separationRepo.save(s), scope.employee(s.getPidEmployee()));
    }

    /* ══ Helpers ════════════════════════════════════════════════════════ */

    /** Advances and loans still outstanding for one person. */
    private BigDecimal outstandingFor(HrEmployee e) {
        BigDecimal owed = BigDecimal.ZERO;
        for (HrAdvance a : advanceRepo.findByPidEmployeeAndStatusOrderByRequestDateDesc(
                e.getPidEmployee(), "Y")) {
            if (a.isRecoverable()) owed = owed.add(a.getOutstanding());
        }
        for (HrLoan l : loanRepo.findByPidEmployeeAndStatusOrderByRequestDateDesc(
                e.getPidEmployee(), "Y")) {
            if (l.isRecoverable()) owed = owed.add(l.getOutstanding());
        }
        return owed;
    }

    private HrSeparation separation(Long id) {
        return separationRepo.findByPidSeparationAndPidBranch(id, scope.branchId())
                .orElseThrow(() -> new ResourceNotFoundException("Separation", id));
    }

    private HrResponses.SeparationDto mapSeparation(HrSeparation s, HrEmployee e) {
        return HrResponses.SeparationDto.builder()
                .id(s.getPidSeparation())
                .employeeId(s.getPidEmployee())
                .empId(e == null ? null : e.getEmpId())
                .empName(e == null ? null : e.getEmpName())
                .separationType(s.getSeparationType())
                .separationTypeLabel(HrConstants.label(s.getSeparationType()))
                .noticeDate(s.getNoticeDate())
                .lastWorkingDate(s.getLastWorkingDate())
                .noticePeriodDays(s.getNoticePeriodDays())
                .reason(s.getReason())
                .separationStatus(s.getSeparationStatus())
                .separationStatusLabel(HrConstants.label(s.getSeparationStatus()))
                .decidedBy(s.getDecidedBy())
                .decidedAt(s.getDecidedAt())
                .decisionRemarks(s.getDecisionRemarks())
                .settlementStatus(s.getSettlementStatus())
                .settlementAmount(s.getSettlementAmount())
                .settlementDate(s.getSettlementDate())
                .remarks(s.getRemarks())
                .editable(s.isEditable())
                .build();
    }

    private static String requireOneOf(String raw, List<String> allowed, String what) {
        String v = HrConstants.normalise(allowed, raw);
        if (v == null) {
            throw new BusinessException(
                    "'" + raw + "' is not a " + what + ". Use one of: "
                    + String.join(", ", allowed) + ".",
                    HttpStatus.BAD_REQUEST, "HR_INVALID_VALUE");
        }
        return v;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
