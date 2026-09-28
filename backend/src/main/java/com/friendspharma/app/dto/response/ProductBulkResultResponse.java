package com.friendspharma.app.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Per-row outcome of a bulk import.
 *
 * <p>The import is deliberately not all-or-nothing. A 500-row file with three
 * bad rows should land 497 products and tell the user exactly which three
 * failed and why — forcing them to fix and re-upload the whole file would be
 * worse, and re-running the file is safe because SKU uniqueness stops the good
 * rows from duplicating.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProductBulkResultResponse {

    private int totalRows;
    private int created;
    private int updated;
    private int failed;

    /** Every row's outcome, in file order, so the UI can render a result table. */
    private List<RowResult> results;

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RowResult {
        /** 1-based row number from the source sheet. */
        private Integer rowNumber;
        private String  sku;
        private String  productName;
        /** CREATED / UPDATED / FAILED / SKIPPED */
        private String  status;
        /** Assigned product code, when the row succeeded. */
        private String  productCode;
        /** Why the row failed — shown verbatim to the user. */
        private String  error;
    }
}
