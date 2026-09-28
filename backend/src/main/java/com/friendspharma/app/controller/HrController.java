package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.request.HrRequests;
import com.friendspharma.app.dto.response.HrResponses;
import com.friendspharma.app.entity.HrConstants;
import com.friendspharma.app.service.impl.HrEmployeeImageService;
import com.friendspharma.app.service.impl.HrEmployeeServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Employees — the HR home screen.
 *
 * <p><b>There is no employee CRUD here.</b> {@code /api/employees} on
 * {@code MasterDataController} already creates and edits staff records, and has
 * since before HR existed. This adds what the master screen has no business
 * knowing about: the pay basis, the whole record in one read, and departure.
 *
 * <p>Authorisation is the matrix right on Employees (MOD32), checked in the
 * service — see {@code HrScope.require}. No role names appear here.
 */
@RestController
@RequestMapping("/api/v1/hr")
@RequiredArgsConstructor
@Tag(name = "HR — Employees",
     description = "Summary, employee record, pay basis and separation. Employee create and edit "
                 + "remain on /api/employees, which HR extends rather than duplicates.")
public class HrController {

    private final HrEmployeeServiceImpl  employees;
    private final HrEmployeeImageService images;

    /* ── Summary ───────────────────────────────────────────────────────── */

    @GetMapping("/summary")
    @Operation(summary = "The HR screen in one read",
               description = "Headcount, who is in today, what is waiting for a decision, and "
                           + "what is owed. One call rather than six, so no two numbers on the "
                           + "page disagree with each other.")
    public ApiResponse<HrResponses.HrSummaryDto> summary() {
        return ApiResponse.success(employees.summary());
    }

    /* ── One person ────────────────────────────────────────────────────── */

    @GetMapping("/employees/{id}/profile")
    @Operation(summary = "Everything about one employee",
               description = "Record, pay basis, leave balances, advances, loans, recent payslips "
                           + "and any separation.")
    public ApiResponse<HrResponses.EmployeeProfileDto> profile(@PathVariable Long id) {
        return ApiResponse.success(employees.profile(id));
    }

    @PutMapping("/employees/{id}/pay-basis")
    @Operation(summary = "Set what an employee is paid, and how",
               description = "Gated by Edit on Employees rather than by the Master Data right, so "
                           + "somebody may maintain staff records without seeing salaries.")
    public ApiResponse<HrResponses.EmployeeProfileDto> setPayBasis(
            @PathVariable Long id, @Valid @RequestBody HrRequests.PayBasis req) {
        return ApiResponse.success(
                employees.setPayBasis(id, req.getBasicSalary(), req.getSalaryStructureId(),
                                      req.getEmploymentType(), req.getSalaryPaymentMode(),
                                      req.getBankName(), req.getBankBranch(),
                                      req.getBankAccountNo(), req.getMobileWalletNo(),
                                      req.getConfirmationDate()),
                "Pay basis updated");
    }

    @PutMapping("/employees/{id}/details")
    @Operation(summary = "Update the employee record HR owns",
               description = "Personal detail, employment terms and scheme membership. Name, code, "
                           + "phone, role, department and designation stay on /api/employees, which "
                           + "the Master Data screen and the delivery list have always used. Every "
                           + "field is optional, so the sections of the form save independently.")
    public ApiResponse<HrResponses.EmployeeProfileDto> updateDetails(
            @PathVariable Long id, @Valid @RequestBody HrRequests.EmployeeDetails req) {
        return ApiResponse.success(employees.updateDetails(id, req), "Record updated");
    }

    /* ── Photograph ────────────────────────────────────────────────────── */

    /**
     * Replaces an employee's photograph.
     *
     * <p>Multipart, and the file is validated by {@code ImageStorageService} —
     * extension whitelist, MIME agreeing with the extension, real image magic
     * bytes, size limit, and the resolved path re-checked to be inside the
     * configured root. The browser's declared type is never trusted on its own.
     *
     * <p>The file lands under {@code ${UPLOAD_ROOT}/employee_image} and the row
     * keeps only the public URL. No filesystem path reaches the database.
     */
    @PostMapping(value = "/employees/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload or replace an employee photograph",
               description = "JPG, PNG or WEBP. Either Edit on Employees, or the employee "
                           + "themselves — which is what lets self-service work without granting "
                           + "everybody the right to edit the staff list.")
    public ApiResponse<Map<String, String>> uploadImage(
            @PathVariable Long id, @RequestParam("file") MultipartFile file) {
        HrEmployeeImageService.requireFile(file);
        return ApiResponse.success(Map.of("imagePath", images.upload(id, file)),
                                   "Photograph updated");
    }

    @DeleteMapping("/employees/{id}/image")
    @Operation(summary = "Remove an employee photograph",
               description = "The screens fall back to initials; they never show a broken image.")
    public ApiResponse<Void> removeImage(@PathVariable Long id) {
        images.remove(id);
        return ApiResponse.success(null, "Photograph removed");
    }

    /* ── Separation ────────────────────────────────────────────────────── */

    @GetMapping("/separations")
    @Operation(summary = "Separations at this branch")
    public ApiResponse<PageResponse<HrResponses.SeparationDto>> separations(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(
                employees.searchSeparations(status, PageRequest.of(page, Math.min(size, 100))));
    }

    @PostMapping("/separations")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record somebody leaving",
               description = "The employee record is never deleted — approving the separation "
                           + "deactivates it, and the history that references it stays intact.")
    public ApiResponse<HrResponses.SeparationDto> createSeparation(
            @Valid @RequestBody HrRequests.Separation req) {
        return ApiResponse.created(employees.createSeparation(req));
    }

    @PostMapping("/separations/{id}/approve")
    @Operation(summary = "Approve a separation and deactivate the employee",
               description = "Refused while an advance or loan is still outstanding: a deactivated "
                           + "employee is skipped by every payroll run, so the money could never "
                           + "be recovered from a payslip afterwards.")
    public ApiResponse<HrResponses.SeparationDto> approveSeparation(
            @PathVariable Long id, @RequestBody(required = false) HrRequests.Decision d) {
        return ApiResponse.success(employees.approveSeparation(id, d), "Separation approved");
    }

    @PostMapping("/separations/{id}/reject")
    @Operation(summary = "Reject a separation")
    public ApiResponse<HrResponses.SeparationDto> rejectSeparation(
            @PathVariable Long id, @RequestBody(required = false) HrRequests.Decision d) {
        return ApiResponse.success(employees.rejectSeparation(id, d), "Separation rejected");
    }

    @PostMapping("/separations/{id}/cancel")
    @Operation(summary = "Cancel a separation",
               description = "An approved separation that is cancelled reactivates the employee. "
                           + "One that never got that far leaves them exactly as they were.")
    public ApiResponse<HrResponses.SeparationDto> cancelSeparation(
            @PathVariable Long id, @RequestBody(required = false) HrRequests.Decision d) {
        return ApiResponse.success(employees.cancelSeparation(id, d), "Separation cancelled");
    }

    @PostMapping("/separations/{id}/settle")
    @Operation(summary = "Close the final settlement",
               description = "The amount is entered, never computed. What belongs in a final "
                           + "settlement is policy and in places statute, and this application "
                           + "does not decide it.")
    public ApiResponse<HrResponses.SeparationDto> settle(
            @PathVariable Long id, @Valid @RequestBody HrRequests.Settlement req) {
        return ApiResponse.success(employees.settle(id, req), "Settlement recorded");
    }

    /* ── Vocabulary ────────────────────────────────────────────────────── */

    /**
     * The value sets the screens use, so pickers are not hard-coded client-side.
     *
     * <p>Adding a value to {@code HrConstants} then reaches the UI without a
     * frontend change — the same reason {@code /api/employees/types} exists.
     */
    @GetMapping("/vocabulary")
    @Operation(summary = "The value sets the HR screens use")
    public ApiResponse<Map<String, List<Map<String, String>>>> vocabulary() {
        return ApiResponse.success(Map.of(
                "employmentType",   options(HrConstants.EmploymentType.ALL),
                "paymentMode",      options(HrConstants.PaymentMode.ALL),
                "attendanceStatus", options(HrConstants.AttendanceStatus.ALL),
                "attendanceSource", options(HrConstants.AttendanceSource.ALL),
                "componentType",    options(HrConstants.ComponentType.ALL),
                "calcMethod",       options(HrConstants.CalcMethod.ALL),
                "separationType",   options(HrConstants.SeparationType.ALL),
                "payrollStatus",    options(HrConstants.PayrollStatus.ALL),
                "leaveStatus",      options(HrConstants.LeaveStatus.ALL)));
    }

    private static List<Map<String, String>> options(List<String> values) {
        return values.stream()
                .map(v -> Map.of("value", v, "label", HrConstants.label(v)))
                .toList();
    }
}
