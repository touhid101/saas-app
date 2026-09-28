package com.friendspharma.app.service.impl;

import com.friendspharma.app.dto.response.InvoiceDocumentResponse;
import com.friendspharma.app.dto.response.PayslipDocumentResponse;
import com.friendspharma.app.dto.response.PayslipDocumentResponse.Attendance;
import com.friendspharma.app.dto.response.PayslipDocumentResponse.Employee;
import com.friendspharma.app.dto.response.PayslipDocumentResponse.Line;
import com.friendspharma.app.dto.response.PayslipDocumentResponse.Payment;
import com.friendspharma.app.dto.response.PayslipDocumentResponse.Slip;
import com.friendspharma.app.dto.response.PayslipDocumentResponse.Totals;
import com.friendspharma.app.entity.AllBranch;
import com.friendspharma.app.entity.HrAttendance;
import com.friendspharma.app.entity.HrConstants;
import com.friendspharma.app.entity.HrEmployee;
import com.friendspharma.app.entity.HrPayroll;
import com.friendspharma.app.entity.HrPayrollLine;
import com.friendspharma.app.entity.HrPayrollPeriod;
import com.friendspharma.app.entity.HrPayslipComponent;
import com.friendspharma.app.entity.HrSalaryPayment;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.AllBranchRepository;
import com.friendspharma.app.repository.AllDepartmentsRepository;
import com.friendspharma.app.repository.HrDesignationRepository;
import com.friendspharma.app.repository.HrEmployeeRepository;
import com.friendspharma.app.repository.HrPayrollLineRepository;
import com.friendspharma.app.repository.HrPayrollPeriodRepository;
import com.friendspharma.app.repository.HrPayrollRepository;
import com.friendspharma.app.repository.HrSalaryPaymentRepository;
import com.friendspharma.app.util.AmountInWords;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A payslip, assembled for printing.
 *
 * <p>Built exactly like {@link InvoiceDocumentService}: the letterhead read from
 * the branch, the tenancy wall — a payslip id from another pharmacy is "not
 * found", never "forbidden" — and every figure computed here, so the sheet the
 * employee takes home and the one the office files came from one place.
 *
 * <p>The figures are the run's, not today's. A payslip is the record of what
 * was paid, and a reprint that re-read the current salary would be a different
 * document wearing the same number.
 */
@Service
@RequiredArgsConstructor
public class PayslipDocumentService {

    private final HrPayrollRepository        payrollRepo;
    private final HrPayrollLineRepository    lineRepo;
    private final HrPayrollPeriodRepository  periodRepo;
    private final HrSalaryPaymentRepository  paymentRepo;
    private final HrEmployeeRepository       employeeRepo;
    private final AllDepartmentsRepository   departmentRepo;
    private final HrDesignationRepository    designationRepo;
    private final AllBranchRepository        branchRepo;
    private final HrScope                    scope;

    @Transactional(readOnly = true)
    public PayslipDocumentResponse forLine(Long payrollId, Long lineId) {
        scope.require(HrScope.MOD_PAYROLL, PermissionGuard.Right.VIEW, "print a payslip");

        // The run is found through the session branch, so a run belonging to
        // another branch — or another pharmacy — does not exist here.
        HrPayroll run = payrollRepo.findByPidPayrollAndPidBranch(payrollId, scope.branchId())
                .orElseThrow(() -> new ResourceNotFoundException("Payroll", payrollId));
        HrPayrollLine line = lineRepo.findById(lineId)
                .filter(l -> l.getPayroll().getPidPayroll().equals(run.getPidPayroll()))
                .orElseThrow(() -> new ResourceNotFoundException("Payslip", lineId));
        HrPayrollPeriod period = periodRepo.findById(run.getPidPeriod()).orElse(null);
        HrEmployee employee = employeeRepo.findById(line.getPidEmployee()).orElse(null);

        List<HrPayslipComponent> comps = new ArrayList<>(line.getComponents());
        comps.sort(Comparator.comparing(c -> c.getSortOrder() == null ? Integer.MAX_VALUE : c.getSortOrder()));

        return PayslipDocumentResponse.builder()
                .branch(branchOf(run))
                .slip(slipOf(run, line, period))
                .employee(employeeOf(line, employee))
                .earnings(linesOf(comps, "EARNING"))
                .deductions(linesOf(comps, "DEDUCTION"))
                .attendance(attendanceOf(line, period))
                .totals(totalsOf(line))
                .payments(paymentsOf(line))
                .amountInWords(AmountInWords.format(nz(line.getNetSalary())))
                .build();
    }

    /* ── Letterhead ──────────────────────────────────────────────────────── */

    private InvoiceDocumentResponse.Branch branchOf(HrPayroll run) {
        AllBranch b = branchRepo.findById(run.getPidBranch()).orElse(null);
        if (b == null) return InvoiceDocumentResponse.Branch.builder().build();
        String trading = blankToNull(b.getReportHeader());
        return InvoiceDocumentResponse.Branch.builder()
                .name(b.getBranchName())
                .tradingName(trading != null ? trading : b.getBranchName())
                .address(blankToNull(b.getBranchAddress()))
                .phone(blankToNull(b.getBranchPhoneNo()))
                .email(blankToNull(b.getBranchEmail()))
                .logoUrl(blankToNull(b.getLogoUrl()))
                .build();
    }

    /* ── The slip ────────────────────────────────────────────────────────── */

    private Slip slipOf(HrPayroll run, HrPayrollLine line, HrPayrollPeriod period) {
        return Slip.builder()
                .payslipNo(run.getPayrollNo() + " / " + line.getPidPayrollLine())
                .payrollId(run.getPidPayroll())
                .payrollLineId(line.getPidPayrollLine())
                .payrollNo(run.getPayrollNo())
                .periodLabel(period == null ? null : period.getLabel())
                .periodFrom(period == null ? null : period.getPeriodFrom())
                .periodTo(period == null ? null : period.getPeriodTo())
                .workingDays(period == null ? null : period.getWorkingDays())
                .payrollStatus(run.getPayrollStatus())
                .payrollStatusLabel(HrConstants.label(run.getPayrollStatus()))
                .paymentStatus(line.getPaymentStatus())
                .approvedBy(run.getApprovedBy())
                .approvedAt(run.getApprovedAt())
                .postedAt(run.getPostedAt())
                .remarks(blankToNull(line.getRemarks()))
                .printedAt(LocalDateTime.now())
                .build();
    }

    /* ── Who it is for ───────────────────────────────────────────────────── */

    private Employee employeeOf(HrPayrollLine line, HrEmployee e) {
        // The name and code are the ones written on the line when the run was
        // calculated — a person renamed since is still paid under the name the
        // run used. Department and designation are looked up by the ids the
        // line carries for the same reason.
        String department = line.getPidDepartment() == null ? null
                : departmentRepo.findById(line.getPidDepartment()).map(d -> d.getDepartmentName()).orElse(null);
        String designation = line.getPidDesignation() == null ? null
                : designationRepo.findById(line.getPidDesignation()).map(d -> d.getDesignationName()).orElse(null);

        String mode = e == null ? null : blankToNull(e.getSalaryPaymentMode());
        return Employee.builder()
                .id(line.getPidEmployee())
                .name(line.getEmpName())
                .code(line.getEmpId())
                .designation(designation)
                .department(department)
                .hireDate(e == null ? null : e.getHireDate())
                .paymentMode(mode)
                .paymentModeLabel(mode == null ? null : HrConstants.label(mode))
                .bankName(e == null ? null : blankToNull(e.getBankName()))
                .bankAccountMasked(e == null ? null : mask(e.getBankAccountNo()))
                .mobileWalletMasked(e == null ? null : mask(e.getMobileWalletNo()))
                .build();
    }

    /** "•••• 1234" — enough to recognise the account, not enough to use it. */
    static String mask(String no) {
        String s = blankToNull(no);
        if (s == null) return null;
        String digits = s.replaceAll("\\s+", "");
        if (digits.length() <= 4) return "•••• " + digits;
        return "•••• " + digits.substring(digits.length() - 4);
    }

    /* ── The arithmetic ──────────────────────────────────────────────────── */

    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");

    private List<Line> linesOf(List<HrPayslipComponent> comps, String type) {
        List<Line> out = new ArrayList<>();
        for (HrPayslipComponent c : comps) {
            if (!type.equals(c.getComponentType())) continue;
            String basis = null;
            if (c.getPctValue() != null && c.getBaseAmount() != null) {
                basis = c.getPctValue().stripTrailingZeros().toPlainString() + "% of "
                      + MONEY.format(c.getBaseAmount());
            }
            out.add(Line.builder()
                    .sl(out.size() + 1)
                    .code(c.getComponentCode())
                    .name(c.getComponentName())
                    .basis(basis)
                    .amount(nz(c.getAmount()))
                    .build());
        }
        return out;
    }

    private Attendance attendanceOf(HrPayrollLine line, HrPayrollPeriod period) {
        return Attendance.builder()
                .workingDays(period == null ? null : period.getWorkingDays())
                .presentDays(nz(line.getPresentDays()))
                .absentDays(nz(line.getAbsentDays()))
                .leaveDays(nz(line.getLeaveDays()))
                .unpaidLeaveDays(nz(line.getUnpaidLeaveDays()))
                .overtimeMinutes(line.getOvertimeMinutes() == null ? 0 : line.getOvertimeMinutes())
                .overtimeHours(HrAttendance.hours(line.getOvertimeMinutes()))
                .build();
    }

    private Totals totalsOf(HrPayrollLine line) {
        BigDecimal net = nz(line.getNetSalary());
        BigDecimal paid = nz(line.getPaidAmount());
        return Totals.builder()
                .basicSalary(nz(line.getBasicSalary()))
                .grossSalary(nz(line.getGrossSalary()))
                .totalDeductions(nz(line.getDeductionTotal()))
                .taxAmount(nz(line.getTaxAmount()))
                .netSalary(net)
                .paid(paid)
                .balanceDue(net.subtract(paid).max(BigDecimal.ZERO))
                .pfEmployer(nz(line.getPfEmployerAmount()))
                .build();
    }

    private List<Payment> paymentsOf(HrPayrollLine line) {
        return paymentRepo.findByPidPayrollLineAndStatus(line.getPidPayrollLine(), "Y").stream()
                .filter(p -> HrSalaryPayment.PAID.equals(p.getPaymentStatus()))
                .sorted(Comparator.comparing(HrSalaryPayment::getPaymentDate)
                        .thenComparing(HrSalaryPayment::getPidSalaryPayment))
                .map(p -> Payment.builder()
                        .voucherNo(p.getVoucherNo())
                        .date(p.getPaymentDate())
                        .mode(p.getPaymentMode())
                        .modeLabel(HrConstants.label(p.getPaymentMode()))
                        .amount(nz(p.getAmount()))
                        .reference(blankToNull(p.getReferenceNo() != null ? p.getReferenceNo() : p.getChequeNo()))
                        .build())
                .toList();
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
