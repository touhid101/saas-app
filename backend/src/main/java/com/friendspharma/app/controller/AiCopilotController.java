package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.dto.response.AiCopilotResponse;
import com.friendspharma.app.service.impl.AiCopilotServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The AI Copilot.
 *
 * <p>One call draws the whole screen. The reorder list, the movers board, the
 * insight cards and the trend line all come from the same two aggregates, and
 * splitting them across four endpoints would mean scanning the ledger four
 * times to render one page.
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Tag(name = "AI Copilot",
     description = "Reorder recommendations, movers, expiry and dead-stock exposure, "
                 + "computed from the stock ledger")
public class AiCopilotController {

    private final AiCopilotServiceImpl svc;

    /** Defaults: a week to get a delivery, a fortnight of cover, 30% headroom. */
    private static final int DEFAULT_LEAD_TIME = 7;
    private static final int DEFAULT_COVER     = 14;

    @GetMapping("/board")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Everything the Copilot screen shows",
               description = "Lead time is how long a delivery takes; cover is how long the order "
                           + "should last once it lands; the safety factor is the headroom on top "
                           + "for the weeks that run hot. Give a supplierId to narrow the whole "
                           + "board to the products that supplier's manufacturer makes — which is "
                           + "what makes the result orderable as a single purchase.\n\n"
                           + "Scoped to the signed-in user's branch unless another branchId is "
                           + "given, or allBranches is set. Stock and sales are both held per "
                           + "branch, so a board spanning all of them hides a branch that has run "
                           + "out behind another branch's surplus.")
    public ResponseEntity<ApiResponse<AiCopilotResponse.Board>> board(
            @RequestParam(required = false) Long branchId,
            @RequestParam(defaultValue = "false") boolean allBranches,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(defaultValue = "" + DEFAULT_LEAD_TIME) int leadTimeDays,
            @RequestParam(defaultValue = "" + DEFAULT_COVER) int coverDays,
            @RequestParam(defaultValue = "1.3") BigDecimal safetyFactor,
            @RequestParam(defaultValue = "25") int limit) {

        return ResponseEntity.ok(ApiResponse.ok(svc.board(
                branchId, allBranches, supplierId, clamp(leadTimeDays, 1, 90),
                clamp(coverDays, 1, 180), clampFactor(safetyFactor), clamp(limit, 1, 200))));
    }

    @PostMapping("/ask")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Answer a question from the same figures",
               description = "Deterministic, not generative: the answer is drawn from the ledger, "
                           + "so it can be checked against the tabs it points at.")
    public ResponseEntity<ApiResponse<AiCopilotResponse.Answer>> ask(
            @RequestBody Map<String, Object> body) {

        String question = body.get("question") == null ? "" : body.get("question").toString();
        return ResponseEntity.ok(ApiResponse.ok(svc.ask(
                question,
                body.get("branchId") == null ? null : Long.valueOf(body.get("branchId").toString()),
                flag(body.get("allBranches")),
                body.get("supplierId") == null ? null : Long.valueOf(body.get("supplierId").toString()),
                intOr(body.get("leadTimeDays"), DEFAULT_LEAD_TIME),
                intOr(body.get("coverDays"), DEFAULT_COVER),
                body.get("safetyFactor") == null ? BigDecimal.valueOf(1.3)
                        : new BigDecimal(body.get("safetyFactor").toString()))));
    }

    @PostMapping("/requisition")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE03')")
    @Operation(summary = "Raise a requisition from chosen recommendations",
               description = "Saved as a DRAFT so the buyer reviews it before it goes for "
                           + "approval. The quantity ordered and the quantity suggested are both "
                           + "kept on the line. With a supplierId the requisition is recorded "
                           + "against that supplier and every line is checked against what their "
                           + "manufacturer makes.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> requisition(
            @RequestBody Map<String, Object> body) {

        Map<Long, Integer> boxes = new LinkedHashMap<>();
        Object lines = body.get("lines");
        if (lines instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && m.get("productId") != null) {
                    boxes.put(Long.valueOf(m.get("productId").toString()),
                              m.get("boxes") == null ? null : Integer.valueOf(m.get("boxes").toString()));
                }
            }
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
                "Requisition drafted from the Copilot",
                svc.createRequisition(
                        boxes,
                        body.get("remarks") == null ? null : body.get("remarks").toString(),
                        body.get("branchId") == null ? null : Long.valueOf(body.get("branchId").toString()),
                        flag(body.get("allBranches")),
                        body.get("supplierId") == null ? null : Long.valueOf(body.get("supplierId").toString()),
                        intOr(body.get("leadTimeDays"), DEFAULT_LEAD_TIME),
                        intOr(body.get("coverDays"), DEFAULT_COVER),
                        body.get("safetyFactor") == null ? BigDecimal.valueOf(1.3)
                                : new BigDecimal(body.get("safetyFactor").toString()))));
    }

    /* Bounds, so a hand-edited query string cannot ask for a ten-year lead time
       and get a recommendation to buy the supplier's entire warehouse. */
    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private static BigDecimal clampFactor(BigDecimal v) {
        if (v == null) return BigDecimal.valueOf(1.3);
        return v.max(BigDecimal.ONE).min(BigDecimal.valueOf(3));
    }
    private static boolean flag(Object o) {
        return Boolean.TRUE.equals(o) || "true".equalsIgnoreCase(String.valueOf(o));
    }
    private static int intOr(Object o, int fallback) {
        try { return o == null ? fallback : Integer.parseInt(o.toString()); }
        catch (NumberFormatException e) { return fallback; }
    }
}
