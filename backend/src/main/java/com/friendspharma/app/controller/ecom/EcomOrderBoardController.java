package com.friendspharma.app.controller.ecom;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.response.EcomBoardResponses;
import com.friendspharma.app.service.impl.ecom.EcomOrderBoardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/**
 * The branch's E-Commerce Orders board (MOD53).
 *
 * <p>Staff JWT, which is why it sits under {@code /api/ecom/admin/}: every
 * other path below {@code /api/ecom/} is a customer route, and the customer
 * filter clears a staff session on those deliberately, so a pharmacist's token
 * cannot read a customer's screens. This is the staff side of the same module.
 *
 * <p>There is no {@code @PreAuthorize} role list: the service checks the RBAC
 * matrix for every call and resolves the branch through
 * {@code BranchScopeService}, so the rule lives in one place instead of two
 * that can drift apart.
 */
@RestController
@RequestMapping("/api/ecom/admin/orders")
@RequiredArgsConstructor
@Tag(name = "E-Commerce · Orders board", description = "The pharmacy's own board for orders placed online")
public class EcomOrderBoardController {

    private final EcomOrderBoardService board;

    @Data
    public static class Action {
        /** CONFIRM · PREPARE · READY · DISPATCH · DELIVER · CANCEL */
        private String action;
        /** Required by DISPATCH — who is taking it out. */
        private Long deliveryManId;
        @Size(max = 300) private String reason;
    }

    @Operation(summary = "Online orders for this branch, newest first",
               description = "status takes one ECOM status, or OPEN for everything still needing work.")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<EcomBoardResponses.Row>>> list(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(
                board.list(branchId, status, q, from, to, page, size))));
    }

    @Operation(summary = "How many orders sit at each status, plus OPEN and ALL")
    @GetMapping("/status-counts")
    public ResponseEntity<ApiResponse<Map<String, Long>>> counts(@RequestParam(required = false) Long branchId) {
        return ResponseEntity.ok(ApiResponse.ok(board.counts(branchId)));
    }

    @Operation(summary = "One order: what was ordered, which batches the invoice took, where it goes, and its history")
    @GetMapping("/{orderNo}")
    public ResponseEntity<ApiResponse<EcomBoardResponses.Detail>> detail(
            @PathVariable String orderNo, @RequestParam(required = false) Long branchId) {
        return ResponseEntity.ok(ApiResponse.ok(board.detail(branchId, orderNo)));
    }

    @Operation(summary = "Move an order along — and the ERP document with it",
               description = "Confirm and Cancel need Approve on this module; the rest need Edit. "
                           + "Cancel reverses the invoice, its stock and its journals, and is refused "
                           + "once the goods are out or money has been taken.")
    @PostMapping("/{orderNo}/action")
    public ResponseEntity<ApiResponse<EcomBoardResponses.Detail>> act(
            @PathVariable String orderNo,
            @RequestParam(required = false) Long branchId,
            @RequestBody Action req) {
        EcomBoardResponses.Detail after = board.act(branchId, orderNo, req.getAction(),
                req.getDeliveryManId(), req.getReason());
        return ResponseEntity.ok(ApiResponse.ok("Order " + after.getStatus().toLowerCase().replace('_', ' '), after));
    }
}
