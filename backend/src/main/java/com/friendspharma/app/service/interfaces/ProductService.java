package com.friendspharma.app.service.interfaces;

import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.request.ProductMasterRequest;
import com.friendspharma.app.dto.response.ProductListItemResponse;
import com.friendspharma.app.dto.response.ProductMasterResponse;
import com.friendspharma.app.specification.ProductSpecification.ProductFilter;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Product Master use cases.
 *
 * <p>Every method scopes itself to the caller's organisation, resolved from the
 * JWT inside the implementation. No method accepts an orgId parameter — that is
 * deliberate, so a controller cannot pass a client-supplied tenant through.
 */
public interface ProductService {

    /** Paginated, filtered, sorted Product List. All work happens in SQL. */
    PageResponse<ProductListItemResponse> search(ProductFilter filter, Pageable pageable);

    ProductMasterResponse getById(Long id);

    ProductMasterResponse getByBarcode(String barcode);

    ProductMasterResponse create(ProductMasterRequest request);

    /**
     * IGTA and buy price supplied rather than calculated — the bulk template
     * may carry them (V89); a screen still cannot. Either may be null, meaning
     * "calculate it".
     */
    record PriceOverride(java.math.BigDecimal igta, java.math.BigDecimal buyPrice) {
        public static final PriceOverride NONE = new PriceOverride(null, null);
        public boolean any() { return igta != null || buyPrice != null; }
    }

    ProductMasterResponse create(ProductMasterRequest request, PriceOverride override);

    /**
     * [V66] Supersedes the product: writes a NEW version carrying the changes and
     * retires the one given. The response is the new version, with a new id.
     */
    ProductMasterResponse update(Long id, ProductMasterRequest request);
    ProductMasterResponse update(Long id, ProductMasterRequest request, PriceOverride override);

    /** [V66] Every version of this product, newest first. */
    java.util.List<ProductMasterResponse> versionHistory(Long id);

    /**
     * Is this product name free on the caller's branch?
     *
     * <p>The same question the validator asks on save, asked early so the form
     * can answer it while the cursor leaves the field rather than after
     * everything else has been filled in.
     *
     * @param excludeId the product being edited, so it does not collide with itself
     */
    boolean isProductNameAvailable(String name, Long excludeId);

    /** Sets status to ACTIVE or INACTIVE. */
    ProductMasterResponse changeStatus(Long id, String status);

    /**
     * Hard delete. Refuses with a 409 when transaction history exists — the
     * caller should deactivate instead.
     */
    void delete(Long id);

    ProductMasterResponse uploadImage(Long id, MultipartFile file);

    ProductMasterResponse deleteImage(Long id);

    /**
     * Every dropdown the Product form needs, in one call: generics, companies,
     * categories, sub-categories, units and boxes (boxes carry their derived
     * noOfPcs). One round trip instead of six.
     */
    Map<String, List<?>> getFormLookups();
}
