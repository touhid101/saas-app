package com.friendspharma.app.service.interfaces;

import com.friendspharma.app.dto.request.ProductBulkRequest;
import com.friendspharma.app.dto.response.ProductBulkResultResponse;

/**
 * Excel bulk import for Product Master.
 *
 * <p>Rows are validated and saved individually through the same ProductService
 * the single-product form uses, so the import path enforces identical rules.
 */
public interface ProductBulkService {

    /**
     * Imports every row, reporting each outcome separately.
     * A failing row does not abort the rows around it.
     */
    ProductBulkResultResponse importRows(ProductBulkRequest request);
}
