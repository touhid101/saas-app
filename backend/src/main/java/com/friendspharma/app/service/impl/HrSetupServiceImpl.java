package com.friendspharma.app.service.impl;

import com.friendspharma.app.dto.request.HrRequests;
import com.friendspharma.app.dto.response.HrResponses;
import com.friendspharma.app.entity.*;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.HrLeaveTypeRepository;
import com.friendspharma.app.repository.HrSalaryComponentRepository;
import com.friendspharma.app.repository.HrSalaryStructureRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Salary structures and leave types — the two things that have to exist before
 * anything else in HR does anything useful.
 *
 * <p>Both are branch-scoped reference data, both are maintained on the screen
 * that consumes them (structures on Payroll, leave types on Leave), and both
 * are guarded by the module they appear under rather than by a role name.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrSetupServiceImpl {

    private final HrSalaryStructureRepository structureRepo;
    private final HrSalaryComponentRepository componentRepo;
    private final HrLeaveTypeRepository       leaveTypeRepo;
    private final HrScope                     scope;

    /* ══════════════════════════════════════════════════════════════════════
       Salary structure
       ══════════════════════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public List<HrResponses.SalaryStructureDto> listStructures(boolean activeOnly) {
        scope.require(HrScope.MOD_PAYROLL, PermissionGuard.Right.VIEW, "see salary structures");
        Long branch = scope.branchId();
        List<HrSalaryStructure> all = activeOnly
                ? structureRepo.findByPidBranchAndStatusOrderByStructureName(branch, "Y")
                : structureRepo.findByPidBranchOrderByStructureName(branch);
        return all.stream().map(this::mapStructure).toList();
    }

    @Transactional(readOnly = true)
    public HrResponses.SalaryStructureDto getStructure(Long id) {
        scope.require(HrScope.MOD_PAYROLL, PermissionGuard.Right.VIEW, "see salary structures");
        return mapStructure(structure(id));
    }

    @Transactional
    public HrResponses.SalaryStructureDto createStructure(HrRequests.SalaryStructure req) {
        scope.require(HrScope.MOD_PAYROLL, PermissionGuard.Right.CREATE, "add a salary structure");

        Long branch = scope.branchId();
        String name = trim(req.getStructureName());
        if (structureRepo.existsByPidBranchAndStructureNameIgnoreCase(branch, name)) {
            throw new BusinessException("A salary structure called " + name + " already exists here.",
                    HttpStatus.CONFLICT, "HR_STRUCTURE_EXISTS");
        }
        validateOvertimePolicy(req);
        validateDates(req);

        HrSalaryStructure s = HrSalaryStructure.builder()
                .pidOrg(scope.orgId())
                .pidCompany(scope.companyId())
                .pidBranch(branch)
                .structureName(name)
                .description(trim(req.getDescription()))
                .effectiveFrom(req.getEffectiveFrom())
                .effectiveTo(req.getEffectiveTo())
                .standardHoursPerDay(req.getStandardHoursPerDay())
                .overtimeMultiplier(req.getOvertimeMultiplier())
                .absenceBasis(absenceBasis(req.getAbsenceBasis()))
                .build();
        s.setStatus("Y");
        structureRepo.save(s);

        replaceComponents(s, req.getComponents());
        structureRepo.save(s);
        return mapStructure(s);
    }

    /**
     * Replaces a structure and its lines wholesale.
     *
     * <p><b>This does not rewrite history.</b> A payslip carries its own
     * arithmetic in {@code HR_PAYSLIP_COMPONENT}, copied at the moment it was
     * calculated, so editing a structure changes what the next run computes and
     * nothing about the runs already computed. That separation is the reason
     * the payslip stores its working rather than joining back to here.
     */
    @Transactional
    public HrResponses.SalaryStructureDto updateStructure(Long id, HrRequests.SalaryStructure req) {
        scope.require(HrScope.MOD_PAYROLL, PermissionGuard.Right.EDIT, "change a salary structure");

        HrSalaryStructure s = structure(id);
        String name = trim(req.getStructureName());
        if (name != null && structureRepo.existsByPidBranchAndStructureNameIgnoreCaseAndPidStructureNot(
                s.getPidBranch(), name, id)) {
            throw new BusinessException("A salary structure called " + name + " already exists here.",
                    HttpStatus.CONFLICT, "HR_STRUCTURE_EXISTS");
        }
        validateOvertimePolicy(req);
        validateDates(req);

        if (name != null) s.setStructureName(name);
        s.setDescription(trim(req.getDescription()));
        s.setEffectiveFrom(req.getEffectiveFrom());
        s.setEffectiveTo(req.getEffectiveTo());
        s.setStandardHoursPerDay(req.getStandardHoursPerDay());
        s.setOvertimeMultiplier(req.getOvertimeMultiplier());
        s.setAbsenceBasis(absenceBasis(req.getAbsenceBasis()));
        if (req.getStatus() != null) s.setStatus(req.getStatus());

        if (req.getComponents() != null) replaceComponents(s, req.getComponents());
        structureRepo.save(s);
        return mapStructure(s);
    }

    /**
     * Retires a structure rather than deleting it.
     *
     * <p>Deleting would orphan the employees pointing at it and leave the
     * payslips it valued describing a structure that no longer exists. It is
     * refused outright while anybody is still on it, because retiring a
     * structure thirty people are paid under is a decision, not a tidy-up.
     */
    @Transactional
    public void deactivateStructure(Long id) {
        scope.require(HrScope.MOD_PAYROLL, PermissionGuard.Right.DELETE, "retire a salary structure");

        HrSalaryStructure s = structure(id);
        long on = structureRepo.countEmployeesOn(id);
        if (on > 0) {
            throw new BusinessException(
                    s.getStructureName() + " is still attached to " + on
                    + (on == 1 ? " employee" : " employees")
                    + ". Move them to another structure first.",
                    HttpStatus.CONFLICT, "HR_STRUCTURE_IN_USE");
        }
        s.setStatus("N");
        structureRepo.save(s);
    }

    /* ── Components ─────────────────────────────────────────────────────── */

    /**
     * Rewrites a structure's lines from the request.
     *
     * <p>Whole-collection replacement rather than a per-line diff, because the
     * screen edits the structure as one form and a diff would need stable ids
     * the form does not carry for new rows. {@code orphanRemoval} does the
     * deletion; the ordering and the codes are validated first so a half-valid
     * set never reaches the table.
     */
    private void replaceComponents(HrSalaryStructure s, List<HrRequests.SalaryComponent> wanted) {
        List<HrRequests.SalaryComponent> incoming = wanted == null ? List.of() : wanted;

        Set<String> seen = new HashSet<>();
        for (HrRequests.SalaryComponent c : incoming) {
            String code = trim(c.getComponentCode());
            if (code == null) continue;
            if (!seen.add(code.toUpperCase())) {
                throw new BusinessException(
                        "The component code " + code + " appears twice in this structure.",
                        HttpStatus.CONFLICT, "HR_COMPONENT_DUPLICATE");
            }
            // BASIC is added by the calculator on every payslip. A structure
            // that also names it would pay it twice.
            if ("BASIC".equalsIgnoreCase(code)) {
                throw new BusinessException(
                        "Basic salary is on every payslip already and comes from the employee. "
                        + "Remove the BASIC component from this structure.",
                        HttpStatus.CONFLICT, "HR_COMPONENT_BASIC_RESERVED");
            }
            validateComponent(c);
        }

        /* Clear, then FLUSH, then insert.

           uq_hr_component_code is on (pid_structure, upper(component_code)) and
           Hibernate orders a transaction's inserts before its deletes. Without
           the flush, saving a structure whose components keep their codes —
           which is what every edit does — inserts the new HRA row while the old
           one is still there, and the index refuses it. The error reads as a
           duplicate component and is really a flush-order problem: the same
           trap the working policy and the PF scheme both hit.

           Clearing an orphanRemoval collection does not itself emit the DELETE;
           the flush is what does. */
        s.getComponents().clear();
        structureRepo.saveAndFlush(s);

        int fallbackOrder = 100;
        for (HrRequests.SalaryComponent c : incoming) {
            HrSalaryComponent e = HrSalaryComponent.builder()
                    .structure(s)
                    .componentCode(trim(c.getComponentCode()).toUpperCase())
                    .componentName(trim(c.getComponentName()))
                    .componentType(requireOneOf(c.getComponentType(),
                            HrConstants.ComponentType.ALL, "component type"))
                    .calcMethod(requireOneOf(c.getCalcMethod(),
                            HrConstants.CalcMethod.ALL, "calculation method"))
                    .amount(c.getAmount())
                    .pctValue(c.getPctValue())
                    .isTaxable(c.getIsTaxable() == null ? 1 : c.getIsTaxable())
                    /* Nothing is inferred from the component's name — a person
                       ticks this, or it stays off. See HrPfServiceImpl. */
                    .isPf(c.getIsPf() == null ? 0 : c.getIsPf())
                    .sortOrder(c.getSortOrder() == null ? fallbackOrder : c.getSortOrder())
                    .build();
            e.setStatus("Y");
            s.getComponents().add(e);
            fallbackOrder += 10;
        }
    }

    /**
     * A component must be computable.
     *
     * <p>The same rule as {@code ck_hr_component_value}, said in a sentence.
     * Without it a line can exist that silently contributes nothing, which is
     * very hard to see on a payslip and very easy to miss on a form.
     */
    private void validateComponent(HrRequests.SalaryComponent c) {
        String method = requireOneOf(c.getCalcMethod(), HrConstants.CalcMethod.ALL, "calculation method");
        String name   = trim(c.getComponentName());

        if (HrConstants.CalcMethod.FIXED.equals(method)) {
            if (c.getAmount() == null || c.getAmount().signum() < 0) {
                throw new BusinessException(name + " is a fixed amount, so it needs an amount.",
                        HttpStatus.BAD_REQUEST, "HR_COMPONENT_AMOUNT_REQUIRED");
            }
        } else {
            if (c.getPctValue() == null || c.getPctValue().signum() < 0) {
                throw new BusinessException(name + " is a percentage, so it needs a percentage.",
                        HttpStatus.BAD_REQUEST, "HR_COMPONENT_PCT_REQUIRED");
            }
            if (c.getPctValue().compareTo(new BigDecimal("100")) > 0) {
                throw new BusinessException(name + " is more than 100%. Check the figure.",
                        HttpStatus.BAD_REQUEST, "HR_COMPONENT_PCT_RANGE");
            }
        }
    }

    /**
     * Overtime needs both halves or neither.
     *
     * <p>A multiplier with no standard day has nothing to divide a monthly
     * salary by, so it would silently value every overtime hour at zero — which
     * looks exactly like overtime being unpaid on purpose.
     */
    private void validateOvertimePolicy(HrRequests.SalaryStructure req) {
        boolean hasMultiplier = req.getOvertimeMultiplier() != null
                             && req.getOvertimeMultiplier().signum() > 0;
        boolean hasHours = req.getStandardHoursPerDay() != null
                        && req.getStandardHoursPerDay().signum() > 0;
        if (hasMultiplier && !hasHours) {
            throw new BusinessException(
                    "An overtime multiplier needs a standard working day to be worth anything. "
                    + "Set the hours per day, or leave the multiplier empty and overtime will be "
                    + "recorded but not paid.",
                    HttpStatus.BAD_REQUEST, "HR_OVERTIME_INCOMPLETE");
        }
    }

    private void validateDates(HrRequests.SalaryStructure req) {
        if (req.getEffectiveFrom() != null && req.getEffectiveTo() != null
                && req.getEffectiveTo().isBefore(req.getEffectiveFrom())) {
            throw new BusinessException("The effective-to date is before the effective-from date.",
                    HttpStatus.BAD_REQUEST, "HR_STRUCTURE_DATES");
        }
    }

    /* ══════════════════════════════════════════════════════════════════════
       Leave types
       ══════════════════════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public List<HrResponses.LeaveTypeDto> listLeaveTypes(boolean activeOnly) {
        scope.require(HrScope.MOD_LEAVE, PermissionGuard.Right.VIEW, "see leave types");
        Long branch = scope.branchId();
        List<HrLeaveType> all = activeOnly
                ? leaveTypeRepo.findByPidBranchAndStatusOrderByTypeName(branch, "Y")
                : leaveTypeRepo.findByPidBranchOrderByTypeName(branch);
        return all.stream().map(this::mapLeaveType).toList();
    }

    @Transactional
    public HrResponses.LeaveTypeDto createLeaveType(HrRequests.LeaveType req) {
        scope.require(HrScope.MOD_LEAVE, PermissionGuard.Right.CREATE, "add a leave type");

        Long branch = scope.branchId();
        String code = trim(req.getTypeCode()).toUpperCase();
        leaveTypeRepo.findByPidBranchAndTypeCodeIgnoreCase(branch, code).ifPresent(x -> {
            throw new BusinessException("A leave type with the code " + code + " already exists here.",
                    HttpStatus.CONFLICT, "HR_LEAVE_TYPE_EXISTS");
        });
        validateCarryForward(req);

        HrLeaveType t = HrLeaveType.builder()
                .pidOrg(scope.orgId())
                .pidCompany(scope.companyId())
                .pidBranch(branch)
                .typeCode(code)
                .typeName(trim(req.getTypeName()))
                .description(trim(req.getDescription()))
                .annualQuota(req.getAnnualQuota())
                .isPaid(req.getIsPaid() == null ? 1 : req.getIsPaid())
                .carryForward(req.getCarryForward() == null ? 0 : req.getCarryForward())
                .maxCarryForward(req.getMaxCarryForward())
                .requiresApproval(req.getRequiresApproval() == null ? 1 : req.getRequiresApproval())
                .build();
        t.setStatus("Y");
        return mapLeaveType(leaveTypeRepo.save(t));
    }

    @Transactional
    public HrResponses.LeaveTypeDto updateLeaveType(Long id, HrRequests.LeaveType req) {
        scope.require(HrScope.MOD_LEAVE, PermissionGuard.Right.EDIT, "change a leave type");

        HrLeaveType t = leaveTypeRepo.findByPidLeaveTypeAndPidBranch(id, scope.branchId())
                .orElseThrow(() -> new ResourceNotFoundException("Leave type", id));

        String code = trim(req.getTypeCode());
        if (code != null && leaveTypeRepo.existsByPidBranchAndTypeCodeIgnoreCaseAndPidLeaveTypeNot(
                t.getPidBranch(), code, id)) {
            throw new BusinessException("A leave type with the code " + code + " already exists here.",
                    HttpStatus.CONFLICT, "HR_LEAVE_TYPE_EXISTS");
        }
        validateCarryForward(req);

        if (code != null) t.setTypeCode(code.toUpperCase());
        if (trim(req.getTypeName()) != null) t.setTypeName(trim(req.getTypeName()));
        t.setDescription(trim(req.getDescription()));
        t.setAnnualQuota(req.getAnnualQuota());
        if (req.getIsPaid()           != null) t.setIsPaid(req.getIsPaid());
        if (req.getCarryForward()     != null) t.setCarryForward(req.getCarryForward());
        t.setMaxCarryForward(req.getMaxCarryForward());
        if (req.getRequiresApproval() != null) t.setRequiresApproval(req.getRequiresApproval());
        if (req.getStatus()           != null) t.setStatus(req.getStatus());

        return mapLeaveType(leaveTypeRepo.save(t));
    }

    @Transactional
    public void deactivateLeaveType(Long id) {
        scope.require(HrScope.MOD_LEAVE, PermissionGuard.Right.DELETE, "retire a leave type");
        HrLeaveType t = leaveTypeRepo.findByPidLeaveTypeAndPidBranch(id, scope.branchId())
                .orElseThrow(() -> new ResourceNotFoundException("Leave type", id));
        t.setStatus("N");
        leaveTypeRepo.save(t);
    }

    private void validateCarryForward(HrRequests.LeaveType req) {
        boolean carries = req.getCarryForward() != null && req.getCarryForward() == 1;
        if (carries && req.getMaxCarryForward() == null) {
            throw new BusinessException(
                    "Say how many days may be carried forward, or turn carry-forward off. "
                    + "Without a ceiling an unused entitlement accumulates without limit.",
                    HttpStatus.BAD_REQUEST, "HR_CARRY_FORWARD_LIMIT");
        }
    }

    /* ══ Lookups and mapping ════════════════════════════════════════════ */

    HrSalaryStructure structure(Long id) {
        return structureRepo.findByPidStructureAndPidBranch(id, scope.branchId())
                .orElseThrow(() -> new ResourceNotFoundException("Salary structure", id));
    }

    private HrResponses.SalaryStructureDto mapStructure(HrSalaryStructure s) {
        List<HrResponses.SalaryComponentDto> lines = new ArrayList<>();
        for (HrSalaryComponent c : s.getComponents()) {
            if (!c.isActive()) continue;
            lines.add(HrResponses.SalaryComponentDto.builder()
                    .id(c.getPidComponent())
                    .componentCode(c.getComponentCode())
                    .componentName(c.getComponentName())
                    .componentType(c.getComponentType())
                    .componentTypeLabel(HrConstants.label(c.getComponentType()))
                    .calcMethod(c.getCalcMethod())
                    .calcMethodLabel(methodLabel(c.getCalcMethod()))
                    .amount(c.getAmount())
                    .pctValue(c.getPctValue())
                    .isTaxable(c.getIsTaxable())
                    .isPf(c.getIsPf())
                    .sortOrder(c.getSortOrder())
                    .status(c.getStatus())
                    .build());
        }
        lines.sort((a, b) -> Integer.compare(
                a.getSortOrder() == null ? 0 : a.getSortOrder(),
                b.getSortOrder() == null ? 0 : b.getSortOrder()));

        return HrResponses.SalaryStructureDto.builder()
                .id(s.getPidStructure())
                .structureName(s.getStructureName())
                .description(s.getDescription())
                .effectiveFrom(s.getEffectiveFrom())
                .effectiveTo(s.getEffectiveTo())
                .standardHoursPerDay(s.getStandardHoursPerDay())
                .overtimeMultiplier(s.getOvertimeMultiplier())
                .absenceBasis(s.getAbsenceBasis())
                .paysOvertime(s.paysOvertime())
                .status(s.getStatus())
                .employeesOn(structureRepo.countEmployeesOn(s.getPidStructure()))
                .components(lines)
                .build();
    }

    private HrResponses.LeaveTypeDto mapLeaveType(HrLeaveType t) {
        return HrResponses.LeaveTypeDto.builder()
                .id(t.getPidLeaveType())
                .typeCode(t.getTypeCode())
                .typeName(t.getTypeName())
                .description(t.getDescription())
                .annualQuota(t.getAnnualQuota())
                .quotaSet(t.isQuotaSet())
                .isPaid(t.getIsPaid())
                .carryForward(t.getCarryForward())
                .maxCarryForward(t.getMaxCarryForward())
                .requiresApproval(t.getRequiresApproval())
                .status(t.getStatus())
                .build();
    }

    /** "PCT_OF_BASIC" reads as "% of Basic" on a form, not as a constant. */
    private static String methodLabel(String method) {
        return switch (method == null ? "" : method) {
            case HrConstants.CalcMethod.FIXED        -> "Fixed amount";
            case HrConstants.CalcMethod.PCT_OF_BASIC -> "% of Basic";
            case HrConstants.CalcMethod.PCT_OF_GROSS -> "% of Gross";
            default -> method;
        };
    }

    private static String absenceBasis(String raw) {
        String v = HrConstants.normalise(
                List.of(HrSalaryStructure.ABSENCE_ON_BASIC, HrSalaryStructure.ABSENCE_ON_GROSS), raw);
        return v == null ? HrSalaryStructure.ABSENCE_ON_BASIC : v;
    }

    private static String requireOneOf(String raw, List<String> allowed, String what) {
        String v = HrConstants.normalise(allowed, raw);
        if (v == null) {
            throw new BusinessException(
                    "'" + raw + "' is not a " + what + ". Use one of: " + String.join(", ", allowed) + ".",
                    HttpStatus.BAD_REQUEST, "HR_INVALID_VALUE");
        }
        return v;
    }

    private static String trim(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
