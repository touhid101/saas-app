package com.friendspharma.app.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public class RequisitionRequest {

    @Data
    public static class RequisitionLineRequest {
        @NotNull  private Long pidProduct;
        @NotNull  @DecimalMin("0.01") private BigDecimal requestedQty;
        @Pattern(regexp = "BOX|STRIP|PCS") private String unit;
        private BigDecimal estimatedUnitCost;
        /**
         * CRITICAL is included because the Copilot raises lines at it — a line
         * that runs out before a delivery can arrive is not merely HIGH. The
         * pattern rejected it, so a requisition the system itself had written
         * could not be edited back through this endpoint.
         */
        @Pattern(regexp = "CRITICAL|HIGH|MEDIUM|LOW") private String priority;
        private String remarks;
    }

    @Data
    public static class CreateRequisitionRequest {
        /**
         * Both accepted and both optional, because neither is trusted.
         *
         * <p>They were @NotNull, which made the API demand two ids it then
         * overwrote — and rejected every caller that correctly sent none with
         * "must not be null", before any of the code that resolves them had run.
         *
         * <p>The organisation always comes from the session. The branch is
         * resolved through BranchScopeService: absent means your own, and a
         * named one is honoured only for an administrator. A branch id here is a
         * branch id somebody can change, so it is a request, not an instruction.
         */
        private Long pidOrg;
        private Long pidBranch;
        private LocalDate reqDate;          // defaults to today
        private String source;              // MANUAL (default) | AI
        private String remarks;
        @NotEmpty @Valid
        private List<RequisitionLineRequest> lines;
    }

    @Data
    public static class UpdateRequisitionRequest {
        private String remarks;
        @Valid
        private List<RequisitionLineRequest> lines;  // replaces all lines when provided
    }

    /** Update a single detail line qty (used from the approval modal) */
    @Data
    public static class UpdateLineQtyRequest {
        @NotNull @DecimalMin("0.01") private BigDecimal requestedQty;
        private String remarks;
    }

    @Data
    public static class ApproveRequest {
        private String remarks;
    }

    @Data
    public static class RejectRequest {
        @NotBlank private String reason;
    }
}
