package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.request.HrRequests;
import com.friendspharma.app.dto.response.HrResponses;
import com.friendspharma.app.service.impl.HrPfServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * The provident fund.
 *
 * <p>Authorisation is the matrix right on <b>Payroll (MOD36)</b>, checked in the
 * service: PF is payroll money, deducted by a payroll run and administered by
 * the same people. Recording a movement by hand needs Approve, because it moves
 * money without a payslip behind it.
 *
 * <p><b>No rate is shipped.</b> A branch has no scheme until somebody enters
 * one, and until then payroll deducts no PF from anybody — P-07 in
 * {@code 01-hr-gap-analysis.md}. A null rate means undecided, not nought.
 */
@RestController
@RequestMapping("/api/v1/hr/pf")
@RequiredArgsConstructor
@Tag(name = "HR — Provident Fund",
     description = "The scheme, the ledger and each person's balance. Contributions are written "
                 + "when a payroll run is posted, never when it is calculated.")
public class HrPfController {

    private final HrPfServiceImpl pf;

    /* ── The scheme ────────────────────────────────────────────────────── */

    @GetMapping("/scheme")
    @Operation(summary = "The scheme in force, or nothing",
               description = "Null when the branch has none, which is the state the application "
                           + "ships in. A scheme with no rates on it comes back with rateSet=false "
                           + "and deducting=false: it exists, and it still takes nothing.")
    public ApiResponse<HrResponses.PfSchemeDto> scheme() {
        return ApiResponse.success(pf.current());
    }

    @GetMapping("/scheme/history")
    @Operation(summary = "Every scheme this branch has had",
               description = "Superseded schemes keep their end date and stay — last month's "
                           + "payroll was computed against one of them.")
    public ApiResponse<List<HrResponses.PfSchemeDto>> schemeHistory() {
        return ApiResponse.success(pf.history());
    }

    @PostMapping("/scheme")
    @Operation(summary = "Set the scheme",
               description = "Supersedes the live one from the effective date rather than editing "
                           + "it. Rates may be left blank, which means undecided and deducts "
                           + "nothing — this application does not choose them.")
    public ApiResponse<HrResponses.PfSchemeDto> setScheme(@Valid @RequestBody HrRequests.PfScheme req) {
        return ApiResponse.success(pf.setScheme(req), "Provident fund scheme set");
    }

    /* ── The ledger ────────────────────────────────────────────────────── */

    @GetMapping("/ledger")
    @Operation(summary = "Search fund movements")
    public ApiResponse<PageResponse<HrResponses.PfLedgerDto>> ledger(
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) String entryType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(
                pf.search(employeeId, entryType, from, to, PageRequest.of(page, Math.min(size, 100))));
    }

    @GetMapping("/statement/{employeeId}")
    @Operation(summary = "One person's statement",
               description = "Every movement in order, with the balance after each. Rates are the "
                           + "ones that applied at the time, copied onto the row rather than joined "
                           + "to today's scheme.")
    public ApiResponse<HrResponses.PfStatementDto> statement(@PathVariable Long employeeId) {
        return ApiResponse.success(pf.statement(employeeId));
    }

    @PostMapping("/ledger")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record a withdrawal, an adjustment or a settlement",
               description = "A contribution is not one of these — it is written by posting "
                           + "payroll, and letting it be typed would put money in the fund that no "
                           + "payslip accounts for. A withdrawal is refused when the fund does not "
                           + "hold it.")
    public ApiResponse<HrResponses.PfLedgerDto> record(@Valid @RequestBody HrRequests.PfEntry req) {
        return ApiResponse.created(pf.record(req));
    }
}
