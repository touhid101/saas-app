package com.friendspharma.app.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What the merge screen reads.
 *
 * <p>A merge is a grouping document over a customer's open orders, so it is
 * described as the wrapper plus the orders beneath it — never as a flattened
 * list of products. The products belong to the orders, and that is where every
 * stock and product report reads them from.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SalesMergeResponse {

    /** One order — either eligible to be merged, or already inside a merge. */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Candidate {
        private Long       id;
        private String     invoiceNo;
        private LocalDate  transactionDate;
        private String     stage;
        private String     paymentType;
        private Integer    lineCount;
        private BigDecimal subtotal;
        /** What this order is billed at — the figure the merge sums. */
        private BigDecimal amount;
        private BigDecimal paid;
    }

    /** The grouping document. */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Merge {
        private Long       id;
        private String     invoiceNo;
        private LocalDate  mergeDate;
        private Long       customerId;
        private String     customerName;
        private String     stage;
        private String     status;
        private boolean    cancelled;
        private BigDecimal total;
        private BigDecimal paid;
        private String     remarks;
        /** The orders this merge was built from, still the accounting record. */
        private List<Candidate> sources;
    }
}
