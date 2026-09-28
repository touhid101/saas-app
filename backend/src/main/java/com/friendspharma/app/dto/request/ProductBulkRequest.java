package com.friendspharma.app.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * Bulk product import payload, produced by parsing the Excel template.
 *
 * <p>Rows carry master-data <b>names</b> rather than ids, because that is what a
 * person types into a spreadsheet. The server resolves each name to its id and
 * rejects the row if it does not match an active master record — the same rule
 * the single-product form enforces, reached by a different route.
 *
 * <p>Like {@link ProductMasterRequest}, this DTO has no tpPrice / productCode
 * fields: a spreadsheet column called "TP Price" cannot override the
 * calculation, and the importer ignores it. IGTA and Buy Price are the
 * exception, added with V89 to match the catalogue: a row may supply either,
 * and a value supplied is stored as it is; a blank cell is calculated as
 * before (IGTA = TP x VAT / 100, Buy Price = TP + IGTA).
 */
@Data
public class ProductBulkRequest {

    /** CREATE = fail rows whose SKU already exists. UPSERT = update them instead. */
    private String mode;

    @NotEmpty(message = "The file contains no data rows.")
    @Size(max = 2000, message = "A single import is limited to 2000 rows. Please split the file.")
    private List<Row> rows;

    /**
     * One spreadsheet row.
     *
     * <p>No Bean Validation annotations here on purpose: a bulk import must
     * report every bad row rather than rejecting the whole file on the first
     * problem, so each row is validated individually inside the service and its
     * errors collected.
     */
    @Data
    public static class Row {
        /** 1-based row number in the source sheet, used to point the user at the mistake. */
        private Integer rowNumber;

        private String productName;
        private String sku;
        private String barcode;

        private String genericName;
        private String companyName;
        private String categoryName;
        private String subCategoryName;
        private String unitName;
        private String boxName;

        private BigDecimal mrp;
        private BigDecimal conversionRate;
        private BigDecimal vatPct;
        private BigDecimal discountPct;
        /** Optional overrides of the calculated figures — see the class comment. */
        private BigDecimal igta;
        private BigDecimal buyPrice;

        private BigDecimal reorderLevel;
        private BigDecimal safetyStock;
        private BigDecimal minimumStock;
        private BigDecimal maximumStock;

        /** Accepts Yes/No, Y/N, TRUE/FALSE, 1/0 — see ProductBulkServiceImpl.toBool. */
        private String batchTracking;
        private String expiryTracking;
        private String prescriptionRequired;

        private String status;
    }
}
