package com.friendspharma.app.service.impl;

import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.request.ProductMasterRequest;
import com.friendspharma.app.dto.response.ProductListItemResponse;
import com.friendspharma.app.dto.response.ProductMasterResponse;
import com.friendspharma.app.entity.*;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ProductInUseException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.mapper.ProductMasterMapper;
import com.friendspharma.app.repository.*;
import com.friendspharma.app.security.SecurityUtils;
import com.friendspharma.app.service.interfaces.ProductService;
import com.friendspharma.app.specification.ProductSpecification;
import com.friendspharma.app.specification.ProductSpecification.ProductFilter;
import com.friendspharma.app.util.ProductPricingCalculator;
import com.friendspharma.app.util.ProductPricingCalculator.CalculatedPrices;
import com.friendspharma.app.validation.ProductValidator;
import com.friendspharma.app.validation.ProductValidator.ValidatedReferences;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Product Master service.
 *
 * <p>Three invariants this class exists to hold:
 * <ol>
 *   <li><b>Derived prices are never trusted from the client.</b> TP_PRICE, IGTA
 *       and BUY_PRICE are recomputed from MRP / CONVERSION_RATE / VAT_PCT on
 *       every create and update. The request DTO has no fields for them at all.</li>
 *   <li><b>Product code is server-generated and immutable.</b> Derived from the
 *       PID_PRODUCT sequence value, so uniqueness is guaranteed by the primary
 *       key and concurrent inserts cannot collide. Update never touches it.</li>
 *   <li><b>Tenant comes from the JWT.</b> orgId is read from SecurityUtils, never
 *       from a parameter, so one organisation cannot read or write another's
 *       catalogue.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private static final String PRODUCT_CODE_PREFIX = "PRD";
    private static final int    PRODUCT_CODE_DIGITS = 6;

    private final PosProductInfoRepository        productRepo;
    private final BranchScopeService              branchScope;
    private final PosGenericInfoRepository        genericRepo;
    private final PosCompanyInfoRepository        companyRepo;
    private final PosProductCategoryRepository    categoryRepo;
    private final PosProductSubCategoryRepository subCategoryRepo;
    private final PosProductUnitRepository        unitRepo;
    private final PosBoxInfoRepository            boxRepo;
    private final PosLeafInfoRepository           leafRepo;

    private final PosTransactionDtlRepository  transactionDtlRepo;
    private final PosRequisitionDtlRepository  requisitionDtlRepo;
    private final PosCartItemRepository        cartItemRepo;

    private final ProductValidator            validator;
    private final ProductImageStorageService  imageStorage;

    /**
     * For the bulk statements that carry live state to a new version.
     *
     * <p>Constructor-injected with everything else rather than
     * {@code @PersistenceContext} on the field. Spring is happy either way; the
     * difference is that a field set after construction is invisible to a unit
     * test's {@code @InjectMocks}, which builds the object through its
     * constructor and then stops — so the mock was never wired and every test
     * touching this path failed on a null.
     */
    private final jakarta.persistence.EntityManager em;

    /* ══════════════════════════════════════════════════════════════════════
       READ
       ══════════════════════════════════════════════════════════════════════ */

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ProductListItemResponse> search(ProductFilter filter, Pageable pageable) {
        Long orgId = requireOrgId();

        // The branch comes off the session, never the filter: a product belonging
        // to another pharmacy is not something this request can ask to see.
        Page<PosProductInfo> page = productRepo.findAll(
                ProductSpecification.build(orgId, branchScope.resolve(null), filter), pageable);

        // Batch-resolve the master-data labels for the page in four queries
        // instead of four per row. With 25 rows that is 4 queries rather than 100.
        LabelCache labels = LabelCache.forPage(page.getContent(),
                genericRepo, companyRepo, categoryRepo, unitRepo);

        // Same for the delete guard: one aggregate pass, not three per row.
        Set<Long> withHistory = productIdsWithTransactionHistory(page.getContent());

        List<ProductListItemResponse> rows = page.getContent().stream()
                .map(p -> ProductMasterMapper.toListItem(
                        p,
                        labels.generic(p.getPidGeneric()),
                        labels.company(p.getPidCompany()),
                        labels.category(p.getPidCategory()),
                        labels.unit(p.getPidUnitId()),
                        imageStorage.toPublicUrl(p.getProductImage()),
                        !withHistory.contains(p.getPidProduct())))
                .toList();

        return PageResponse.from(page, rows);
    }

    @Override
    @Transactional(readOnly = true)
    public ProductMasterResponse getById(Long id) {
        return buildDetail(loadOwnedProduct(id));
    }

    @Override
    @Transactional(readOnly = true)
    public ProductMasterResponse getByBarcode(String barcode) {
        Long orgId = requireOrgId();
        PosProductInfo product = productRepo.findByBarcodeAndPidOrg(barcode, orgId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No product found with barcode: " + barcode));
        return buildDetail(product);
    }

    /* ══════════════════════════════════════════════════════════════════════
       CREATE
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * IGTA and buy price supplied rather than calculated.
     *
     * <p>Not on {@link ProductMasterRequest} — a screen still cannot send them —
     * but reachable from the bulk import, whose template gained the two columns
     * (V89) to match the catalogue. Either may be null, meaning "calculate it".
     * A buy price left blank beside a supplied IGTA is TP + that IGTA, so the
     * two never disagree by accident.
     */
    @Override
    @Transactional
    public ProductMasterResponse create(ProductMasterRequest request) {
        return create(request, PriceOverride.NONE);
    }

    @Override
    @Transactional
    public ProductMasterResponse create(ProductMasterRequest request, PriceOverride override) {
        Long orgId    = requireOrgId();
        Long branchId = SecurityUtils.getCurrentBranchId();

        ValidatedReferences refs = validator.validate(request, orgId, null);

        PosProductInfo product = new PosProductInfo();
        product.setPidOrg(orgId);
        product.setPidBranch(branchId);
        applyRequest(product, request, refs, override);

        // Defaults that only apply on create — an edit must never reset these.
        product.setStatus(request.getStatus() != null
                ? ProductMasterMapper.toDbStatus(request.getStatus())
                : "Y");
        if (product.getStockQty() == null) product.setStockQty(BigDecimal.ZERO);

        // First save assigns PID_PRODUCT from SEQ_PID_PRODUCT; the product code
        // is derived from it, so it inherits the sequence's uniqueness and
        // concurrency guarantees without a second sequence to keep in step.
        PosProductInfo saved = productRepo.save(product);
        saved.setProductCode(generateProductCode(saved.getPidProduct()));
        // [V66] A brand-new product is version 1 of itself, and the root of its
        // own chain. Every later edit copies this forward.
        saved.setPidRootProduct(saved.getPidProduct());
        saved.setVersionNo(1);
        saved = productRepo.save(saved);

        log.info("Created product {} ({}) in org {}",
                saved.getProductCode(), saved.getProductName(), orgId);
        return buildDetail(saved);
    }

    /* ══════════════════════════════════════════════════════════════════════
       UPDATE
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * An edit writes a new version and retires the one it replaces.
     *
     * <p><b>Why nothing is overwritten.</b> A sale line, a stock movement and a
     * printed invoice all name a product by its id. Editing that row in place
     * rewrites what those documents say: reprint March's invoice after a
     * September re-pricing and it comes back at September's price, under
     * September's name, and March's figures no longer add up. Writing a new row
     * leaves every document naming exactly the product it was raised against.
     * History is immutable here because nothing rewrites it, not because
     * anything guards it afterwards.
     *
     * <p><b>What moves and what stays.</b> Completed documents stay — sale and
     * purchase lines, stock movements, requisitions. Live state moves, because
     * it describes the shelf rather than the past:
     *
     * <ul>
     *   <li><b>Stock batches</b> — the stock is physically the same stock. Left
     *       behind, the new version would show zero and the pharmacy could not
     *       sell what is on the shelf. This is the one that would have made the
     *       feature look finished and quietly stop trade.</li>
     *   <li><b>Open cart lines</b> — a sale half-rung-up when somebody in the
     *       office pressed Save must not fail at payment.</li>
     *   <li><b>Discount rules</b> — a rule attached to the product is a rule
     *       about the product, not about a version of it.</li>
     * </ul>
     *
     * <p><b>Order matters.</b> The old row is retired and flushed before the new
     * one is inserted. Barcode, SKU, product code and the catalogue link are
     * unique per branch among ACTIVE rows (V66), and two live rows carrying the
     * same barcode exist for as long as it takes to write them — the index would
     * reject the insert. Retiring first means they never overlap.
     *
     * <p>All of it in one transaction: if any step throws, the retirement rolls
     * back with it and the product is left exactly as it was.
     */
    @Override
    @Transactional
    public ProductMasterResponse update(Long id, ProductMasterRequest request) {
        return update(id, request, PriceOverride.NONE);
    }

    @Override
    @Transactional
    public ProductMasterResponse update(Long id, ProductMasterRequest request, PriceOverride override) {
        Long orgId = requireOrgId();
        PosProductInfo current = loadOwnedProduct(id);

        /* Editing a version that something else has already replaced would fork
           the chain: two live versions of one product, and no way to say which
           the shelf means. */
        if (current.getSupersededBy() != null) {
            throw new BusinessException(
                    "This is version " + current.getVersionNo() + " of "
                    + current.getProductName() + ", which was replaced on "
                    + current.getSupersededDate() + ". Open the current version to edit it.",
                    HttpStatus.CONFLICT, "PRODUCT_VERSION_SUPERSEDED");
        }

        ValidatedReferences refs = validator.validate(request, orgId, id);

        // Retire first, and make it hit the database before the insert.
        String actor = SecurityUtils.getCurrentUsername();
        current.setStatus("N");
        current.setSupersededDate(java.time.LocalDateTime.now());
        current.setSupersededByUser(actor);
        productRepo.saveAndFlush(current);

        PosProductInfo next = copyForNextVersion(current);
        applyRequest(next, request, refs, override);

        /* An edit that arrives asking for INACTIVE is retiring the product, not
           editing it — the new version is written all the same, so the change is
           recorded, and then it too is inactive. */
        if (request.getStatus() != null) {
            next.setStatus(ProductMasterMapper.toDbStatus(request.getStatus()));
        }

        PosProductInfo saved = productRepo.saveAndFlush(next);

        // Now that the new row has an id, close the chain.
        current.setSupersededBy(saved.getPidProduct());
        productRepo.save(current);

        int batches = carryLiveStateForward(current.getPidProduct(), saved.getPidProduct());

        log.info("Product {} superseded: v{} (id {}) -> v{} (id {}) by {}; {} live row(s) carried forward",
                saved.getProductCode(), current.getVersionNo(), current.getPidProduct(),
                saved.getVersionNo(), saved.getPidProduct(), actor, batches);

        return buildDetail(saved);
    }

    /**
     * The next version of a product: everything the old row held, minus the
     * things that identify a row rather than a product.
     *
     * <p>Copied field by field rather than by reflection or a detached-entity
     * trick. It is longer, and it is the only way a reader can tell what a new
     * version inherits — a column added to the entity and forgotten here shows
     * up as a value that silently resets on the next edit, which is exactly the
     * class of bug this whole feature exists to prevent.
     */
    private PosProductInfo copyForNextVersion(PosProductInfo from) {
        PosProductInfo next = new PosProductInfo();

        next.setPidOrg(from.getPidOrg());
        next.setPidBranch(from.getPidBranch());
        next.setPidGlobalProduct(from.getPidGlobalProduct());

        /* The code stays. It is what a person calls this product, it is printed
           on labels, and the chain is what it names — the unique index is over
           active rows only, so exactly one live version holds it. */
        next.setProductCode(from.getProductCode());

        // Stock is the same stock; the batches move below and this is the figure
        // the list renders. Zero here would read as an empty shelf.
        next.setStockQty(from.getStockQty());

        // Owned by their own endpoints, so an edit must carry them, not blank them.
        next.setProductImage(from.getProductImage());
        next.setImage(from.getImage());
        next.setFileName(from.getFileName());
        next.setFileMimeType(from.getFileMimeType());
        next.setImgColor(from.getImgColor());

        // Sales percentages are set on their own screen and are not in this form.
        next.setSalesPer(from.getSalesPer());
        next.setSalesPerLeaf(from.getSalesPerLeaf());
        next.setSalesPerPcs(from.getSalesPerPcs());
        next.setSalesPerSpc(from.getSalesPerSpc());
        next.setSalesPerSpcLeaf(from.getSalesPerSpcLeaf());
        next.setSalesPerSpcRet(from.getSalesPerSpcRet());

        next.setPidRootProduct(from.getPidRootProduct() != null
                ? from.getPidRootProduct() : from.getPidProduct());
        next.setVersionNo((from.getVersionNo() == null ? 1 : from.getVersionNo()) + 1);
        next.setStatus("Y");

        return next;
    }

    /**
     * Moves what describes the shelf, and leaves what describes the past.
     *
     * <p>Bulk statements rather than loading entities: a product with a thousand
     * batches is one UPDATE, not a thousand round trips, and none of these rows
     * needs its own audit fields touched.
     *
     * @return how many stock batches moved, for the log
     */
    private int carryLiveStateForward(Long oldId, Long newId) {
        int batches = em.createNativeQuery(
                        "UPDATE PHARMA.POS_STOCK_BATCH SET PID_PRODUCT = :next WHERE PID_PRODUCT = :prev")
                .setParameter("next", newId).setParameter("prev", oldId)
                .executeUpdate();

        em.createNativeQuery(
                        "UPDATE PHARMA.POS_CART_ITEM SET PID_PRODUCT = :next WHERE PID_PRODUCT = :prev")
                .setParameter("next", newId).setParameter("prev", oldId)
                .executeUpdate();

        em.createNativeQuery(
                        "UPDATE PHARMA.POS_SALES_DISCOUNT SET PID_PRODUCT = :next WHERE PID_PRODUCT = :prev")
                .setParameter("next", newId).setParameter("prev", oldId)
                .executeUpdate();

        return batches;
    }

    /**
     * Every version of one product, newest first.
     *
     * <p>Asked by root id, so it answers the same list whichever version the
     * caller happens to be holding.
     */
    /**
     * The save-time name rule, asked early.
     *
     * <p>Deliberately the same repository probe {@code ProductValidator} uses,
     * so the answer the field gives while you are still typing and the answer
     * Save gives cannot disagree. A blank name is reported available — it is not
     * taken, and "required" is a different message the field already shows.
     */
    @Override
    @Transactional(readOnly = true)
    public boolean isProductNameAvailable(String name, Long excludeId) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) return true;
        return !productRepo.existsByProductNameInBranch(
                requireOrgId(), SecurityUtils.getCurrentBranchId(), clean,
                excludeId == null ? -1L : excludeId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductMasterResponse> versionHistory(Long id) {
        PosProductInfo product = loadOwnedProduct(id);
        Long root = product.getPidRootProduct() != null
                ? product.getPidRootProduct() : product.getPidProduct();

        return productRepo.findVersionChain(root).stream()
                .map(this::buildDetail)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public ProductMasterResponse changeStatus(Long id, String status) {
        if (status == null || !(ProductMasterMapper.STATUS_ACTIVE.equalsIgnoreCase(status)
                             || ProductMasterMapper.STATUS_INACTIVE.equalsIgnoreCase(status))) {
            throw new BusinessException("Status must be ACTIVE or INACTIVE.",
                    HttpStatus.BAD_REQUEST, "INVALID_STATUS");
        }
        PosProductInfo product = loadOwnedProduct(id);
        product.setStatus(ProductMasterMapper.toDbStatus(status));
        PosProductInfo saved = productRepo.save(product);

        log.info("Product {} status changed to {}", saved.getProductCode(), status.toUpperCase());
        return buildDetail(saved);
    }

    /* ══════════════════════════════════════════════════════════════════════
       DELETE
       ══════════════════════════════════════════════════════════════════════ */

    @Override
    @Transactional
    public void delete(Long id) {
        PosProductInfo product = loadOwnedProduct(id);

        String usage = describeTransactionHistory(id);
        if (usage != null) {
            // Refuse rather than cascade: removing a product that appears on a
            // historical invoice would silently rewrite that invoice's meaning.
            throw new ProductInUseException(product.getProductName(), usage);
        }

        imageStorage.delete(product.getProductImage());
        productRepo.delete(product);
        log.info("Deleted product {} ({}) — no transaction history",
                product.getProductCode(), product.getProductName());
    }

    /* ══════════════════════════════════════════════════════════════════════
       IMAGE
       ══════════════════════════════════════════════════════════════════════ */

    @Override
    @Transactional
    public ProductMasterResponse uploadImage(Long id, MultipartFile file) {
        PosProductInfo product = loadOwnedProduct(id);

        String previousImage = product.getProductImage();
        // In the pharmacy's own folder, product_image/<branch>/<product>.<ext>.
        String storedName    = imageStorage.store(file, id, product.getPidBranch());

        product.setProductImage(storedName);
        // Clear the legacy BLOB so a stale pre-V4 image cannot outrank the new file.
        product.setImage(null);
        product.setFileName(null);
        product.setFileMimeType(null);
        PosProductInfo saved = productRepo.save(product);

        // Only remove the old file once the new reference is safely persisted.
        if (previousImage != null && !previousImage.equals(storedName)) {
            imageStorage.delete(previousImage);
        }
        return buildDetail(saved);
    }

    @Override
    @Transactional
    public ProductMasterResponse deleteImage(Long id) {
        PosProductInfo product = loadOwnedProduct(id);
        String previousImage = product.getProductImage();

        product.setProductImage(null);
        product.setImage(null);
        product.setFileName(null);
        product.setFileMimeType(null);
        PosProductInfo saved = productRepo.save(product);

        imageStorage.delete(previousImage);
        return buildDetail(saved);
    }

    /* ══════════════════════════════════════════════════════════════════════
       LOOKUPS
       ══════════════════════════════════════════════════════════════════════ */

    @Override
    @Transactional(readOnly = true)
    public Map<String, List<?>> getFormLookups() {
        Long orgId = requireOrgId();

        // Only active master rows are offered — an inactive generic must not be
        // selectable, and ProductValidator rejects it server-side regardless.
        List<Map<String, Object>> generics = genericRepo
                .findByPidOrgAndPidBranchAndStatusOrderByGenericName(orgId, branch(), "Y").stream()
                .map(g -> option(g.getPidGeneric(), g.getGenericName()))
                .toList();

        List<Map<String, Object>> companies = companyRepo
                .findByPidOrgAndPidBranchAndStatusOrderByCompanyName(orgId, branch(), "Y").stream()
                .map(c -> option(c.getPidCompany(), c.getCompanyName()))
                .toList();

        List<Map<String, Object>> categories = categoryRepo
                .findByPidOrgAndPidBranchAndStatusOrderByCategoryName(orgId, branch(), "Y").stream()
                .map(c -> option(c.getPidCategory(), c.getCategoryName()))
                .toList();

        List<Map<String, Object>> subCategories = subCategoryRepo
                .findByPidOrgAndPidBranchAndStatusOrderBySubCategoryName(orgId, branch(), "Y").stream()
                .map(s -> {
                    Map<String, Object> m = option(s.getPidSubCategory(), s.getSubCategoryName());
                    m.put("categoryId", s.getPidCategory());   // lets the UI cascade
                    return m;
                })
                .toList();

        List<Map<String, Object>> units = unitRepo
                .findByPidOrgAndPidBranchAndStatusOrderByUnitName(orgId, branch(), "Y").stream()
                .map(u -> option(u.getPidUnit(), u.getUnitName()))
                .toList();

        // Boxes carry the derived noOfPcs so the form can display it the moment
        // a box is picked, with no extra request.
        Map<Long, PosLeafInfo> leaves = leafRepo.findAll().stream()
                .filter(l -> l.getPidLeaf() != null)
                .collect(Collectors.toMap(PosLeafInfo::getPidLeaf, Function.identity(), (a, b) -> a));

        List<Map<String, Object>> boxes = boxRepo
                .findByPidOrgAndPidBranchAndStatusOrderByBoxSizeTitle(orgId, branch(), "Y").stream()
                .map(b -> {
                    Map<String, Object> m = option(b.getPidBox(), b.getBoxSizeTitle());
                    PosLeafInfo leaf   = b.getPidLeaf() != null ? leaves.get(b.getPidLeaf()) : null;
                    Integer pcsPerLeaf = leaf != null ? leaf.getNoOfPcs() : null;
                    m.put("noOfLeaf",   b.getNoOfLeaf());
                    m.put("pcsPerLeaf", pcsPerLeaf);
                    m.put("noOfPcs", (b.getNoOfLeaf() != null && pcsPerLeaf != null)
                            ? b.getNoOfLeaf() * pcsPerLeaf : null);
                    return m;
                })
                .toList();

        Map<String, List<?>> lookups = new LinkedHashMap<>();
        lookups.put("generics",      generics);
        lookups.put("companies",     companies);
        lookups.put("categories",    categories);
        lookups.put("subCategories", subCategories);
        lookups.put("units",         units);
        lookups.put("boxes",         boxes);
        lookups.put("defaults", List.of(Map.of(
                "conversionRate", ProductPricingCalculator.DEFAULT_CONVERSION_RATE,
                "vatPct",         ProductPricingCalculator.DEFAULT_VAT_PCT)));
        return lookups;
    }

    /* ══════════════════════════════════════════════════════════════════════
       INTERNALS
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * Copies the request onto the entity and recalculates every derived value.
     * Shared by create and update so the two paths cannot diverge.
     */
    private void applyRequest(PosProductInfo product,
                              ProductMasterRequest request,
                              ValidatedReferences refs,
                              PriceOverride override) {

        product.setProductName(request.getProductName().trim());

        // Absent when omitted, never "". The unique index on SKU is partial
        // (WHERE sku IS NOT NULL), so two general products storing an empty
        // string would collide with each other while two storing NULL do not.
        // .trim() outright would also throw here — a general product legitimately
        // sends no SKU at all.
        product.setSku(emptyToNull(request.getSku()));
        product.setBarcode(emptyToNull(request.getBarcode()));

        // PHARMA unless the caller says otherwise: an older client that does not
        // know about types is describing medicine, which is all this table held
        // before the column existed.
        product.setProductType("GENERAL".equalsIgnoreCase(request.getProductType())
                ? "GENERAL" : "PHARMA");

        product.setPidGeneric(request.getGenericId());
        product.setPidCompany(request.getCompanyId());
        product.setPidCategory(request.getCategoryId());
        product.setPidSubCategory(request.getSubCategoryId());
        product.setPidBox(request.getBoxId());

        // Both unit representations are written: the FK for correctness, the
        // denormalised name because POS and transaction code reads PID_UNIT.
        product.setPidUnitId(request.getUnitId());
        product.setPidUnit(refs.unit() != null ? refs.unit().getUnitName() : null);

        // ── Pricing — server-side recalculation, in dependency order ────────
        // Whatever the client sent for tpPrice/igta/buyPrice is irrelevant: the
        // DTO has no such fields, and these three values are computed here.
        CalculatedPrices prices = ProductPricingCalculator.calculateAll(
                request.getMrp(), request.getConversionRate(), request.getVatPct());

        product.setMrpPrice(prices.mrp());
        product.setConversionRate(prices.conversionRate());
        product.setTpPrice(prices.tpPrice());
        product.setVatPct(prices.vatPct());
        product.setIgta(prices.igta());
        product.setBuyPrice(prices.buyPrice());
        if (override != null && override.any()) {
            // The bulk template's figures, stored as typed. A buy price left
            // blank beside a supplied IGTA follows it, so the row stays coherent.
            BigDecimal igta = override.igta() != null ? override.igta() : prices.igta();
            BigDecimal buy  = override.buyPrice() != null ? override.buyPrice()
                            : (prices.tpPrice() != null && igta != null ? prices.tpPrice().add(igta) : prices.buyPrice());
            product.setIgta(igta);
            product.setBuyPrice(buy);
        }
        product.setDiscountPct(request.getDiscountPct());

        // ── Stock levels ───────────────────────────────────────────────────
        product.setReorderLevel(nvl(request.getReorderLevel()));
        product.setSafetyStock(nvl(request.getSafetyStock()));
        product.setMinimumStock(nvl(request.getMinimumStock()));
        product.setMaximumStock(nvl(request.getMaximumStock()));
        // Mirror into the legacy column the Inventory module still reads.
        product.setAlertQty(nvl(request.getReorderLevel()).intValue());

        // ── Flags ──────────────────────────────────────────────────────────
        product.setBatchTracking(ProductMasterMapper.toYn(request.getBatchTracking()));
        product.setExpiryTracking(ProductMasterMapper.toYn(request.getExpiryTracking()));
        product.setPrescriptionFlag(ProductMasterMapper.toYn(request.getPrescriptionRequired()));

        product.setImgColor(normaliseHex(request.getImgColor()));
    }

    /** PRD + zero-padded PID_PRODUCT, e.g. 42 → "PRD000042". */
    private String generateProductCode(Long pidProduct) {
        return PRODUCT_CODE_PREFIX + String.format("%0" + PRODUCT_CODE_DIGITS + "d", pidProduct);
    }

    /**
     * Loads a product and confirms it belongs to the caller's organisation.
     * A cross-tenant id yields the same 404 as a non-existent one, so the API
     * does not leak whether another organisation's product exists.
     */
    private PosProductInfo loadOwnedProduct(Long id) {
        Long orgId = requireOrgId();
        return productRepo.findByIdAndOrg(id, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));
    }

    private Long requireOrgId() {
        Long orgId = SecurityUtils.getCurrentOrgId();
        if (orgId == null) {
            throw new BusinessException("No organization is associated with your session. Please log in again.",
                    HttpStatus.UNAUTHORIZED, "NO_ORG_CONTEXT");
        }
        return orgId;
    }

    /** Human-readable usage summary, or null when the product is free to delete. */
    private String describeTransactionHistory(Long productId) {
        long transactions = transactionDtlRepo.countByProduct(productId);
        long requisitions = requisitionDtlRepo.countByProduct(productId);
        long cartLines    = cartItemRepo.countByProduct(productId);

        List<String> parts = new ArrayList<>(3);
        if (transactions > 0) parts.add(transactions + " sales/purchase line" + plural(transactions));
        if (requisitions > 0) parts.add(requisitions + " requisition line"    + plural(requisitions));
        if (cartLines    > 0) parts.add(cartLines    + " open cart line"      + plural(cartLines));

        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    /** Ids among the given page that carry any transaction history. */
    private Set<Long> productIdsWithTransactionHistory(List<PosProductInfo> products) {
        Set<Long> blocked = new HashSet<>();
        for (PosProductInfo p : products) {
            Long id = p.getPidProduct();
            if (transactionDtlRepo.countByProduct(id) > 0
                    || requisitionDtlRepo.countByProduct(id) > 0
                    || cartItemRepo.countByProduct(id) > 0) {
                blocked.add(id);
            }
        }
        return blocked;
    }

    /** Assembles the detail DTO, resolving each master-data label. */
    private ProductMasterResponse buildDetail(PosProductInfo p) {
        PosGenericInfo        generic  = p.getPidGeneric()     != null ? genericRepo.findById(p.getPidGeneric()).orElse(null)         : null;
        PosCompanyInfo        company  = p.getPidCompany()     != null ? companyRepo.findById(p.getPidCompany()).orElse(null)         : null;
        PosProductCategory    category = p.getPidCategory()    != null ? categoryRepo.findById(p.getPidCategory()).orElse(null)       : null;
        PosProductSubCategory subCat   = p.getPidSubCategory() != null ? subCategoryRepo.findById(p.getPidSubCategory()).orElse(null) : null;
        PosProductUnit        unit     = p.getPidUnitId()      != null ? unitRepo.findById(p.getPidUnitId()).orElse(null)             : null;
        PosBoxInfo            box      = p.getPidBox()         != null ? boxRepo.findById(p.getPidBox()).orElse(null)                 : null;
        PosLeafInfo           leaf     = (box != null && box.getPidLeaf() != null)
                ? leafRepo.findById(box.getPidLeaf()).orElse(null) : null;

        String usage = describeTransactionHistory(p.getPidProduct());

        return ProductMasterMapper.toResponse(
                p, generic, company, category, subCat, unit, box, leaf,
                imageStorage.toPublicUrl(p.getProductImage()),
                usage == null,
                usage);
    }

    private static Map<String, Object> option(Long id, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        return m;
    }

    private static String plural(long n)          { return n == 1 ? "" : "s"; }
    private static BigDecimal nvl(BigDecimal v)   { return v != null ? v : BigDecimal.ZERO; }
    private static String emptyToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
    private static String normaliseHex(String v) {
        if (v == null || v.isBlank()) return null;
        String t = v.trim();
        return t.startsWith("#") ? t : "#" + t;
    }

    /**
     * Per-page label lookup. Fetches each master table once for the whole page
     * and serves labels from memory, which is what keeps the list endpoint at a
     * constant query count instead of one per row per reference.
     */
    private record LabelCache(Map<Long, String> generics,
                              Map<Long, String> companies,
                              Map<Long, String> categories,
                              Map<Long, String> units) {

        static LabelCache forPage(List<PosProductInfo> page,
                                  PosGenericInfoRepository genericRepo,
                                  PosCompanyInfoRepository companyRepo,
                                  PosProductCategoryRepository categoryRepo,
                                  PosProductUnitRepository unitRepo) {

            Set<Long> genericIds  = idsOf(page, PosProductInfo::getPidGeneric);
            Set<Long> companyIds  = idsOf(page, PosProductInfo::getPidCompany);
            Set<Long> categoryIds = idsOf(page, PosProductInfo::getPidCategory);
            Set<Long> unitIds     = idsOf(page, PosProductInfo::getPidUnitId);

            return new LabelCache(
                    genericIds.isEmpty()  ? Map.of() : genericRepo.findAllById(genericIds).stream()
                            .collect(Collectors.toMap(PosGenericInfo::getPidGeneric, PosGenericInfo::getGenericName, (a, b) -> a)),
                    companyIds.isEmpty()  ? Map.of() : companyRepo.findAllById(companyIds).stream()
                            .collect(Collectors.toMap(PosCompanyInfo::getPidCompany, PosCompanyInfo::getCompanyName, (a, b) -> a)),
                    categoryIds.isEmpty() ? Map.of() : categoryRepo.findAllById(categoryIds).stream()
                            .collect(Collectors.toMap(PosProductCategory::getPidCategory, PosProductCategory::getCategoryName, (a, b) -> a)),
                    unitIds.isEmpty()     ? Map.of() : unitRepo.findAllById(unitIds).stream()
                            .collect(Collectors.toMap(PosProductUnit::getPidUnit, PosProductUnit::getUnitName, (a, b) -> a)));
        }

        private static Set<Long> idsOf(List<PosProductInfo> page, Function<PosProductInfo, Long> getter) {
            return page.stream().map(getter).filter(Objects::nonNull).collect(Collectors.toSet());
        }

        String generic(Long id)  { return id == null ? null : generics.get(id); }
        String company(Long id)  { return id == null ? null : companies.get(id); }
        String category(Long id) { return id == null ? null : categories.get(id); }
        String unit(Long id)     { return id == null ? null : units.get(id); }
    }

    /**
     * The pharmacy whose master data this request may see.
     *
     * <p>Deliberately not {@code SecurityUtils.getCurrentBranchId()}: that
     * returns null where an account has no branch, and a null branch widens the
     * lookups back to every pharmacy in the organisation.
     */
    private Long branch() { return branchScope.resolve(null); }
}
