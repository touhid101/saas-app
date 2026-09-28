package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.request.PurchaseReturnRequest;
import com.friendspharma.app.dto.response.ReturnablePurchaseResponse;
import com.friendspharma.app.dto.response.TransactionResponse;
import com.friendspharma.app.service.impl.PurchaseReturnServiceImpl;
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
@RequestMapping("/api/purchase-returns")
@RequiredArgsConstructor
@Tag(name = "Purchase Returns", description = "Goods going back to the supplier, against the invoice they arrived on")
public class PurchaseReturnController {

    private final PurchaseReturnServiceImpl svc;

    @GetMapping
    @Operation(summary = "List purchase returns",
               description = "Search matches the debit note, the purchase it reverses, or the supplier.")
    public ResponseEntity<ApiResponse<PageResponse<TransactionResponse>>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(
                svc.list(q, from, to, PageRequest.of(page, size)))));
    }

    /**
     * What can still go back from one purchase.
     *
     * <p>Each line carries two limits — what was bought and not yet returned,
     * and what the batch still holds — plus the smaller of them. Goods already
     * dispensed cannot be sent back, and a screen built only from the purchased
     * quantity would offer amounts the save then refuses.
     */
    @GetMapping("/returnable/{purchaseId}")
    @Operation(summary = "The purchase, with what remains returnable on each line",
               description = "Returnable is capped by stock on hand as well as by the paperwork, and "
                           + "says which of the two is the binding limit.")
    public ResponseEntity<ApiResponse<ReturnablePurchaseResponse.Invoice>> returnable(
            @PathVariable Long purchaseId) {
        return ResponseEntity.ok(ApiResponse.ok(svc.returnableFor(purchaseId)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<TransactionResponse>> get(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(svc.get(id)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE05','ROLE07')")
    @Operation(summary = "Raise a purchase return",
               description = "Takes each line out of the batch it was received into, at the price it "
                           + "was received at, and records the returned quantity against the original "
                           + "line so the same goods cannot go back twice.")
    public ResponseEntity<ApiResponse<TransactionResponse>> create(
            @Valid @RequestBody PurchaseReturnRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(svc.createReturn(req)));
    }
}
