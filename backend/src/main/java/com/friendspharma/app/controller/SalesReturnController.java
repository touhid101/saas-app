package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.request.SalesReturnRequest;
import com.friendspharma.app.dto.response.ReturnableLineResponse;
import com.friendspharma.app.dto.response.TransactionResponse;
import com.friendspharma.app.service.impl.SalesReturnServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/sales-returns")
@RequiredArgsConstructor
@Tag(name = "Sales Returns", description = "Goods coming back, against the invoice that sent them out")
public class SalesReturnController {

    private final SalesReturnServiceImpl svc;

    @GetMapping
    @Operation(summary = "List sales returns",
               description = "Search matches the return number, the invoice it reverses, or the customer — "
                           + "because whoever looks one up has the original invoice in hand, not the "
                           + "return number generated afterwards. Scoped to the caller's own branch; only "
                           + "an administrator may name another or ask for all of them.")
    public ResponseEntity<ApiResponse<PageResponse<TransactionResponse>>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long branchId,
            @RequestParam(defaultValue = "false") boolean allBranches,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(
                svc.list(q, from, to, branchId, allBranches, PageRequest.of(page, size)))));
    }

    /**
     * What can still come back from one invoice.
     *
     * <p>Separate from reading the invoice itself: an invoice line says what was
     * sold, which stops being the returnable figure the moment a partial return
     * exists. The screen is built from this so the counter is never offered a
     * quantity the save would refuse.
     */
    @GetMapping("/returnable/{saleId}")
    @Operation(summary = "The invoice, with what remains returnable on each line",
               description = "Carries the batch each line must go back into, the refund per unit net of "
                           + "any invoice discount, and prior returns already raised.")
    public ResponseEntity<ApiResponse<ReturnableLineResponse.Invoice>> returnable(@PathVariable Long saleId) {
        return ResponseEntity.ok(ApiResponse.ok(svc.returnableFor(saleId)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<TransactionResponse>> get(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(svc.get(id)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE03')")
    @Operation(summary = "Raise a sales return",
               description = "Puts each line back into the batch it was sold from, at the price it was "
                           + "sold at, and records the returned quantity against the original line so the "
                           + "same goods cannot come back twice.")
    public ResponseEntity<ApiResponse<TransactionResponse>> create(
            @Valid @RequestBody SalesReturnRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(svc.createReturn(req)));
    }
}
