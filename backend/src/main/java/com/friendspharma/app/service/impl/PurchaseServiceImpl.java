package com.friendspharma.app.service.impl;

import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.request.PurchaseRequest;
import com.friendspharma.app.dto.request.PurchaseRequest.PurchaseLine;
import com.friendspharma.app.dto.response.PurchaseListItemResponse;
import com.friendspharma.app.dto.response.PurchaseResponse;
import com.friendspharma.app.dto.response.PurchaseResponse.PurchaseLineResponse;
import com.friendspharma.app.dto.response.PurchaseResponse.Ref;
import com.friendspharma.app.entity.*;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.InactiveEntityException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.exception.custom.ValidationException;
import com.friendspharma.app.repository.*;
import com.friendspharma.app.security.SecurityUtils;
import com.friendspharma.app.service.interfaces.PurchaseService;
import com.friendspharma.app.service.interfaces.StockPostingService;
import com.friendspharma.app.specification.PurchaseSpecification;
import com.friendspharma.app.specification.PurchaseSpecification.PurchaseFilter;
import com.friendspharma.app.util.PurchaseCalculator;
import com.friendspharma.app.util.PurchaseCalculator.InvoiceTotals;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.friendspharma.app.service.impl.accounting.AccountingHooks;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Purchase invoice service.
 *
 * <p>Replaces a stub whose every method threw {@code UnsupportedOperationException},
 * so this is the module's first working implementation.
 *
 * <p>Four invariants this class exists to hold:
 * <ol>
 *   <li><b>Money is never trusted from the client.</b> Every line total and every
 *       invoice figure is recomputed by {@link PurchaseCalculator}. The request
 *       DTO has no fields for them.</li>
 *   <li><b>Document numbers are server-generated.</b> Invoice number and batch
 *       reference come from {@link DocumentNumberService} and are immutable once
 *       issued.</li>
 *   <li><b>Tenant comes from the JWT.</b> orgId, companyId and the default branch
 *       are read from the session, never from a parameter.</li>
 *   <li><b>Stock moves exactly once.</b> Only approval increases stock, and an
 *       already-approved invoice cannot be approved again.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseServiceImpl implements PurchaseService {

    private static final String TYPE_PURCHASE = "PU";
    private static final String FROM_SOURCE   = "DESKTOP";
    private static final String STATUS_DRAFT    = "D";
    private static final String STATUS_PENDING  = "P";
    private static final String STATUS_APPROVED = "A";
    private static final int    DEFAULT_EXPIRY_DAYS = 365;

    private final StockPostingService         stockPostingService;
    private final PermissionGuard permissions;
    private final BranchScopeService branchScope;
    private final PosTransactionMstRepository mstRepo;
    private final PosTransactionDtlRepository dtlRepo;
    private final PosSupplierInfoRepository   supplierRepo;
    private final PosStockBatchRepository batchRepo;
    private final PosProductInfoRepository    productRepo;
    private final AllBranchRepository         branchRepo;
    private final PosBoxInfoRepository        boxRepo;
    private final PosLeafInfoRepository       leafRepo;
    private final PosProductUnitRepository    unitRepo;
    private final PosGenericInfoRepository    genericRepo;
    private final PosCompanyInfoRepository    companyRepo;

    private final DocumentNumberService documentNumbers;
    private final SupplierPaymentServiceImpl supplierPayments;
    private final com.friendspharma.app.repository.PosSupplierPaymentRepository supplierPayRepo;
    /** Accounts: every financial event this service commits is booked through here, in the same transaction. */
    private final AccountingHooks accounting;

    /* ══════════════════════════════════════════════════════════════════════
       READ
       ══════════════════════════════════════════════════════════════════════ */

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PurchaseListItemResponse> search(PurchaseFilter filter, Pageable pageable) {
        Long orgId = requireOrgId();

        Page<PosTransactionMst> page =
                mstRepo.findAll(PurchaseSpecification.build(orgId, filter), pageable);

        List<Long> ids = page.getContent().stream().map(PosTransactionMst::getPidTranMst).toList();

        // Resolve supplier names and line counts for the whole page in two
        // queries rather than two per row.
        Map<Long, String> supplierNames = resolveSupplierNames(page.getContent());
        Map<Long, String> branchNames   = resolveBranchNames(page.getContent());
        Map<Long, Integer> lineCounts   = countLines(ids);
        // One more query for the page, so an invoice settled on the payment
        // screen does not report itself as still fully unpaid here.
        Map<Long, Settlement> settle   = settlementsFor(page.getContent());

        List<PurchaseListItemResponse> rows = page.getContent().stream()
                .map(m -> PurchaseListItemResponse.builder()
                        .id(m.getPidTranMst())
                        .invoiceNo(m.getInvoiceNo())
                        .batchNo(m.getBatchNo())
                        .challanNo(m.getChallanNo())
                        .transactionDate(m.getTransactionDate())
                        .supplierId(m.getPidSupCust())
                        .supplierName(lookup(supplierNames, m.getPidSupCust()))
                        .branchName(lookup(branchNames, m.getPidBranch()))
                        .purchaseType(m.getPurchaseType())
                        .productType(m.getProductType())
                        .paymentType(m.getPaymentType())
                        .totalAmount(m.getTotalAmount())
                        .totalDiscount(m.getTotalDiscount())
                        .amountToBePaid(m.getAmountTobePaid())
                        .givenAmount(m.getGivenAmount())
                        .dueAmount(settle.getOrDefault(m.getPidTranMst(),
                                new Settlement(BigDecimal.ZERO, BigDecimal.ZERO, due(m))).due())
                        .paidAmount(settle.getOrDefault(m.getPidTranMst(),
                                new Settlement(nvl(m.getGivenAmount()).subtract(nvl(m.getAdvanceAmount())),
                                               BigDecimal.ZERO, BigDecimal.ZERO)).paid())
                        .returnCredit(settle.getOrDefault(m.getPidTranMst(),
                                new Settlement(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)).returnCredit())
                        .advanceAmount(m.getAdvanceAmount())
                        .lineCount(lineCounts.getOrDefault(m.getPidTranMst(), 0))
                        .status(m.getStatus())
                        .statusLabel(PurchaseSpecification.statusLabel(m.getStatus()))
                        .editable(!STATUS_APPROVED.equals(m.getStatus()))
                        .createdBy(m.getInsertedBy())
                        .build())
                .toList();

        return PageResponse.from(page, rows);
    }

    @Override
    @Transactional(readOnly = true)
    public PurchaseResponse getById(Long id) {
        return buildDetail(loadOwnedPurchase(id));
    }

    /* ══════════════════════════════════════════════════════════════════════
       CREATE
       ══════════════════════════════════════════════════════════════════════ */

    @Override
    @Transactional
    public PurchaseResponse create(PurchaseRequest request) {
        Long orgId = requireOrgId();

        PosSupplierInfo supplier = validateSupplier(request.getSupplierId(), orgId);
        requireLineShape(request);
        requireLinesFromSupplier(supplier, request);

        PosTransactionMst mst = new PosTransactionMst();
        mst.setPidOrg(orgId);
        mst.setPidCompany(requireCompanyId());
        mst.setPidBranch(requireBranchId(request.getBranchId()));
        mst.setPidSupCust(supplier.getPidSupplier());
        mst.setTransactionType(TYPE_PURCHASE);
        mst.setFromSource(FROM_SOURCE);

        // Immutable once issued: generated here, never re-generated on update.
        mst.setInvoiceNo(documentNumbers.nextInvoiceNo(TYPE_PURCHASE));
        mst.setBatchNo(documentNumbers.nextBatchNo());

        applyHeader(mst, request);

        PosTransactionMst saved = mstRepo.save(mst);
        List<PosTransactionDtl> lines = buildLines(saved.getPidTranMst(), saved.getBatchNo(), request);
        dtlRepo.saveAll(lines);

        applyTotals(saved, lines, request);
        saved = mstRepo.save(saved);

        // Creating straight into APPROVED is allowed, and must do everything the
        // approve endpoint would — stock and the cash both.
        if (STATUS_APPROVED.equals(saved.getStatus())) {
            receive(saved);
        }

        log.info("Created purchase {} ({} lines, {}) for supplier {} in org {}",
                saved.getInvoiceNo(), lines.size(), saved.getStatus(),
                supplier.getSupplierName(), orgId);
        return buildDetail(saved);
    }

    /* ══════════════════════════════════════════════════════════════════════
       UPDATE
       ══════════════════════════════════════════════════════════════════════ */

    @Override
    @Transactional
    public PurchaseResponse update(Long id, PurchaseRequest request) {
        Long orgId = requireOrgId();
        PosTransactionMst mst = loadOwnedPurchase(id);

        // An approved invoice has already moved stock. Editing it would leave
        // the stock ledger describing quantities that no longer exist on the
        // invoice, so it is refused rather than silently reconciled.
        if (STATUS_APPROVED.equals(mst.getStatus())) {
            throw new BusinessException(
                    "Invoice " + mst.getInvoiceNo() + " is approved and can no longer be edited. "
                    + "Raise a purchase return to correct it.",
                    HttpStatus.CONFLICT, "PURCHASE_ALREADY_APPROVED");
        }

        PosSupplierInfo supplier = validateSupplier(request.getSupplierId(), orgId);
        requireLineShape(request);
        requireLinesFromSupplier(supplier, request);
        mst.setPidSupCust(supplier.getPidSupplier());
        if (request.getBranchId() != null) mst.setPidBranch(request.getBranchId());
        // Older rows predate V7 and may still carry a null company.
        if (mst.getPidCompany() == null) mst.setPidCompany(requireCompanyId());

        applyHeader(mst, request);

        // Replace the lines wholesale — the client sends the invoice as it
        // should now be, not a diff.
        dtlRepo.deleteAll(dtlRepo.findByPidTranMst(id));
        dtlRepo.flush();
        List<PosTransactionDtl> lines = buildLines(id, mst.getBatchNo(), request);
        dtlRepo.saveAll(lines);

        applyTotals(mst, lines, request);
        PosTransactionMst saved = mstRepo.save(mst);

        if (STATUS_APPROVED.equals(saved.getStatus())) {
            receive(saved);
        }

        log.info("Updated purchase {} ({} lines, {})",
                saved.getInvoiceNo(), lines.size(), saved.getStatus());
        return buildDetail(saved);
    }

    /* ══════════════════════════════════════════════════════════════════════
       APPROVE / CANCEL
       ══════════════════════════════════════════════════════════════════════ */

    @Override
    @Transactional
    public PurchaseResponse approve(Long id) {
        requireApproveAuthority();
        PosTransactionMst mst = loadOwnedPurchase(id);

        if (STATUS_APPROVED.equals(mst.getStatus())) {
            throw new BusinessException(
                    "Invoice " + mst.getInvoiceNo() + " is already approved.",
                    HttpStatus.CONFLICT, "PURCHASE_ALREADY_APPROVED");
        }

        List<PosTransactionDtl> lines = dtlRepo.findByPidTranMst(id);
        if (lines.isEmpty()) {
            throw new BusinessException(
                    "Invoice " + mst.getInvoiceNo() + " has no lines and cannot be approved.",
                    HttpStatus.BAD_REQUEST, "PURCHASE_EMPTY");
        }

        postToStock(mst);
        accounting.purchaseApproved(mst);

        mst.setStatus(STATUS_APPROVED);
        mst.setReceivedBy(SecurityUtils.getCurrentUsername());
        mst.setReceivedDate(java.time.LocalDateTime.now());
        PosTransactionMst saved = mstRepo.save(mst);

        // The cash keyed onto this invoice becomes a real payment now that the
        // liability exists. Until approval it was an intention against a bill
        // the business had not agreed to.
        //
        // Stock was posted above, before the status flipped, so only the cash
        // is left to do here.
        supplierPayments.mirrorInvoicePayment(saved);

        log.info("Approved purchase {} — {} lines moved into stock", saved.getInvoiceNo(), lines.size());
        return buildDetail(saved);
    }

    @Override
    @Transactional
    public void cancel(Long id) {
        PosTransactionMst mst = loadOwnedPurchase(id);

        if (STATUS_APPROVED.equals(mst.getStatus())) {
            throw new BusinessException(
                    "Invoice " + mst.getInvoiceNo() + " is approved and its stock has been received. "
                    + "Raise a purchase return instead of cancelling.",
                    HttpStatus.CONFLICT, "PURCHASE_ALREADY_APPROVED");
        }

        dtlRepo.deleteAll(dtlRepo.findByPidTranMst(id));
        mstRepo.delete(mst);
        log.info("Cancelled purchase {}", mst.getInvoiceNo());
    }

    /* ══════════════════════════════════════════════════════════════════════
       LOOKUPS
       ══════════════════════════════════════════════════════════════════════ */

    @Override
    @Transactional(readOnly = true)
    public Map<String, List<?>> getFormLookups() {
        Long orgId = requireOrgId();

        /* Each supplier carries the manufacturer it represents
           (POS_SUPPLIER_INFO.POS_PID_COMPANY). The purchase screen needs it the
           moment a supplier is chosen — to name the maker, to count what can be
           bought, and to say so plainly when a supplier has none — so it travels
           with the option rather than costing a second request per selection. */
        List<PosSupplierInfo> supplierRows = supplierRepo
                .findByPidOrgAndPidBranchAndStatusOrderBySupplierName(orgId, branch(), "Y");

        Map<Long, String> makerNames = companyRepo.findAllById(
                        supplierRows.stream().map(PosSupplierInfo::getPosPidCompany)
                                .filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(PosCompanyInfo::getPidCompany,
                                          PosCompanyInfo::getCompanyName, (a, b) -> a));

        List<Map<String, Object>> suppliers = supplierRows.stream()
                .map(s -> {
                    Map<String, Object> m = option(s.getPidSupplier(), s.getSupplierName());
                    /* A manufacturer that cannot be resolved — the column is
                       NOT NULL, but the row it points at can be missing or from
                       another tenant — is reported as none rather than as a
                       company with no name. The screen printed "Supplies null"
                       for exactly that case. */
                    String maker = s.getPosPidCompany() == null
                            ? null : makerNames.get(s.getPosPidCompany());
                    m.put("companyId", maker == null ? null : s.getPosPidCompany());
                    m.put("companyName", maker);
                    m.put("productCount", maker == null ? 0
                            : productRepo.countForBranchByCompany(orgId, branch(), s.getPosPidCompany()));
                    return m;
                })
                .toList();

        List<Map<String, Object>> branches = branchRepo.findAll().stream()
                .filter(b -> b.getPidOrg() == null || b.getPidOrg().equals(orgId))
                .filter(b -> "Y".equals(b.getStatus()))
                .map(b -> option(b.getPidBranch(), b.getBranchName()))
                .toList();

        Map<String, List<?>> lookups = new LinkedHashMap<>();
        lookups.put("suppliers", suppliers);
        lookups.put("branches", branches);
        lookups.put("purchaseTypes", List.of(
                Map.of("id", "MNP", "name", "MNP — trade price basis"),
                Map.of("id", "MRP", "name", "MRP — retail price basis")));
        lookups.put("productTypes", List.of(
                Map.of("id", "PHARMA", "name", "Pharma"),
                Map.of("id", "NON-PHARMA", "name", "Non-Pharma")));
        // Order is the order the tiles appear in on the entry form.
        lookups.put("paymentTypes", List.of(
                Map.of("id", "CASH",   "name", "Cash"),
                Map.of("id", "CARD",   "name", "Card"),
                Map.of("id", "BKASH",  "name", "bKash"),
                Map.of("id", "NAGAD",  "name", "Nagad"),
                Map.of("id", "BANK",   "name", "Bank Transfer"),
                Map.of("id", "CREDIT", "name", "Credit")));
        lookups.put("salesTypes", List.of(
                Map.of("id", "BOX", "name", "Box"),
                Map.of("id", "STRIP", "name", "Strip"),
                Map.of("id", "PCS", "name", "Pieces")));
        lookups.put("statuses", List.of(
                Map.of("id", "D", "name", "Draft"),
                Map.of("id", "P", "name", "Pending"),
                Map.of("id", "A", "name", "Approved")));
        lookups.put("defaults", List.of(Map.of(
                "purchaseType", "MNP",
                "productType", "PHARMA",
                "paymentType", "CASH",
                "status", "P",
                "expiryDays", DEFAULT_EXPIRY_DAYS)));
        return lookups;
    }

    /**
     * Manufacturers this branch stocks, each with a product count.
     *
     * <p>For adding a whole company's products at once: the count is what makes
     * that safe to press, because it says how many lines it is about to create.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> productCompaniesForBranch(boolean activeOnly) {
        return productRepo.companiesForBranch(requireOrgId(), branch(), activeOnly).stream()
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("companyId", r[0]);
                    m.put("companyName", r[1]);
                    m.put("products", ((Number) r[2]).longValue());
                    return m;
                })
                .toList();
    }
    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> searchProductsForEntry(String query, int limit, boolean activeOnly, Long companyId, Long supplierId) {
        Long orgId = requireOrgId();

        /* A supplier sells one manufacturer's products, and the server decides
           which — not the caller.
         *
         * The screen already filters by the supplier's maker, but a filter the
         * client applies is a filter the client can drop: the same endpoint with
         * companyId removed would list every product in the branch, and the line
         * that followed would look ordinary in the table. So when a supplier is
         * named, their manufacturer replaces whatever companyId arrived. The
         * save path refuses mismatched lines as well — this is the half that
         * stops one being offered in the first place. */
        if (supplierId != null) {
            PosSupplierInfo supplier = supplierRepo.findById(supplierId).orElse(null);
            if (supplier == null || !orgId.equals(supplier.getPidOrg())) {
                throw new ResourceNotFoundException("Supplier", supplierId);
            }
            // No maker assigned means nothing can be bought from them yet. An
            // empty list is the honest answer; the screen says why.
            if (supplier.getPosPidCompany() == null
                    || companyRepo.findById(supplier.getPosPidCompany()).isEmpty()) {
                return List.of();
            }
            companyId = supplier.getPosPidCompany();
        }

        /* A manufacturer is a whole answer, not a page of one.
         *
         * The cap used to be fifty for every call. Choosing a manufacturer on the
         * requisition screen means "everything this maker supplies" — the picker
         * even prints the count beside the name — and fifty of eight hundred is
         * not that. Worse, it was silent: the caller asked for five hundred, got
         * fifty, and the buyer had no way to tell which forty-two per cent of
         * their order was missing.
         *
         * So a request narrowed to one manufacturer returns all of it. A free
         * search is still a picker and still capped: nobody types two letters
         * meaning "give me nine thousand rows". */
        int cap = companyId != null
                ? Integer.MAX_VALUE
                : Math.min(Math.max(limit, 1), 100);

        /* One query for both, and branch-scoped either way.
         *
         * Browsing called findForBranch and searching called searchByNameOrBarcode,
         * which had no branch condition — so typing a name offered products from
         * other branches of the same organisation and clearing the box offered
         * only this one's. A product belonging to another pharmacy cannot be
         * bought or requisitioned here, so listing it only produces a line that
         * fails on save. */
        List<PosProductInfo> found = productRepo.searchForBranch(
                orgId, branch(), query == null ? "" : query.trim(), activeOnly, companyId);

        List<PosProductInfo> page = found.size() > cap ? found.subList(0, cap) : found;

        Map<Long, String> generics  = labelMap(page, PosProductInfo::getPidGeneric,
                genericRepo::findAllById, PosGenericInfo::getPidGeneric, PosGenericInfo::getGenericName);
        Map<Long, String> companies = labelMap(page, PosProductInfo::getPidCompany,
                companyRepo::findAllById, PosCompanyInfo::getPidCompany, PosCompanyInfo::getCompanyName);
        Map<Long, String> units     = labelMap(page, PosProductInfo::getPidUnitId,
                unitRepo::findAllById, PosProductUnit::getPidUnit, PosProductUnit::getUnitName);

        /* Stock from the lots, not from the product row.
         *
         * POS_PRODUCT_INFO.STOCK_QTY is denormalised and nothing maintains it —
         * it is null for almost every product in this database while the batches
         * behind them hold hundreds of pieces. A picker that says "0 in stock"
         * about a shelf with 497 on it is worse than one that says nothing.
         *
         * One query for the page rather than one per row. */
        List<Long> ids = page.stream().map(PosProductInfo::getPidProduct).toList();
        Map<Long, BigDecimal> onHand = ids.isEmpty() ? Map.of()
                : batchRepo.availablePcsFor(branch(), ids, LocalDate.now()).stream()
                        .collect(Collectors.toMap(
                                r -> ((Number) r[0]).longValue(),
                                r -> (BigDecimal) r[1],
                                (a, b) -> a));

        /* Packing, for the whole page in two queries rather than two per row.
           See piecesPerUnit(PosBoxInfo, PosLeafInfo) for what this replaced. */
        Map<Long, PosBoxInfo> boxes = page.stream()
                .map(PosProductInfo::getPidBox).filter(Objects::nonNull).distinct().toList()
                .isEmpty() ? Map.of()
                : boxRepo.findAllById(page.stream().map(PosProductInfo::getPidBox)
                        .filter(Objects::nonNull).distinct().toList()).stream()
                        .collect(Collectors.toMap(PosBoxInfo::getPidBox, b -> b, (a, b) -> a));

        List<Long> leafIds = boxes.values().stream()
                .map(PosBoxInfo::getPidLeaf).filter(Objects::nonNull).distinct().toList();
        Map<Long, PosLeafInfo> leaves = leafIds.isEmpty() ? Map.of()
                : leafRepo.findAllById(leafIds).stream()
                        .collect(Collectors.toMap(PosLeafInfo::getPidLeaf, l -> l, (a, b) -> a));

        return page.stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getPidProduct());
            m.put("productCode", p.getProductCode());
            m.put("productName", p.getProductName());
            // So the picker can mark a withdrawn product rather than quietly
            // offering it as though it were on the shelf like any other.
            m.put("status", p.getStatus());
            m.put("sku", p.getSku());
            m.put("barcode", p.getBarcode());
            m.put("genericName", lookup(generics, p.getPidGeneric()));
            m.put("companyId", p.getPidCompany());
            m.put("companyName", lookup(companies, p.getPidCompany()));
            m.put("unitName", lookupOr(units, p.getPidUnitId(), p.getPidUnit()));
            // Price snapshot the line calculator needs.
            m.put("tpPrice", nvl(p.getTpPrice()));
            m.put("mrpPrice", nvl(p.getMrpPrice()));
            m.put("igta", nvl(p.getIgta()));
            m.put("vatPct", nvl(p.getVatPct()));
            m.put("currentStock", onHand.getOrDefault(p.getPidProduct(), BigDecimal.ZERO));
            m.put("batchTracking", "Y".equals(p.getBatchTracking()));
            m.put("expiryTracking", "Y".equals(p.getExpiryTracking()));
            PosBoxInfo box = p.getPidBox() == null ? null : boxes.get(p.getPidBox());
            PosLeafInfo leaf = (box == null || box.getPidLeaf() == null)
                    ? null : leaves.get(box.getPidLeaf());
            m.putAll(piecesPerUnit(box, leaf));
            return m;
        }).toList();
    }

    /* ══════════════════════════════════════════════════════════════════════
       INTERNALS
       ══════════════════════════════════════════════════════════════════════ */

    /** Header fields shared by create and update, so the two cannot drift. */
    private void applyHeader(PosTransactionMst mst, PurchaseRequest req) {
        mst.setTransactionDate(req.getTransactionDate() != null ? req.getTransactionDate() : LocalDate.now());
        mst.setChallanNo(emptyToNull(req.getChallanNo()));
        mst.setPurchaseType(req.getPurchaseType().toUpperCase());
        mst.setProductType(req.getProductType() != null ? req.getProductType().toUpperCase() : "PHARMA");
        mst.setPaymentType(req.getPaymentType() != null ? req.getPaymentType().toUpperCase() : "CASH");
        mst.setRemarks(emptyToNull(req.getRemarks()));

        String status = req.getStatus() != null
                ? PurchaseSpecification.normaliseStatus(req.getStatus())
                : STATUS_PENDING;

        // Saving straight to APPROVED is a back door around the approve
        // endpoint's own authority check, so it is gated here too. Without this
        // a manager could simply POST status=A and receive the stock.
        if (STATUS_APPROVED.equals(status)) {
            requireApproveAuthority();
        }
        mst.setStatus(status);
    }

    /**
     * Approval is whatever the permission matrix says it is.
     *
     * <p>This used to name roles — Super Admin only, then Super Admin and Branch
     * Manager. Naming roles in code meant the RBAC screen could show CAN_APPROVE
     * ticked while the server refused it, and changing who may approve meant
     * changing Java. Ticking Approve on Purchase Invoice now grants it, for any
     * role, with no code change.
     *
     * <p>The controller enforces the same check on the endpoint; this guard covers
     * the create/update path, which an annotation cannot see.
     */
    private void requireApproveAuthority() {
        if (!permissions.has("MOD03", PermissionGuard.Right.APPROVE)) {
            throw new BusinessException(
                    "You do not have permission to approve purchase invoices. "
                    + "Save it as Draft or Pending and ask somebody who can approve it.",
                    HttpStatus.FORBIDDEN, "APPROVE_NOT_PERMITTED");
        }
    }

    /**
     * Builds the detail rows, snapshotting product prices and calculating each
     * line total. Nothing here reads a money value from the request.
     */
    private List<PosTransactionDtl> buildLines(Long mstId, String batchNo, PurchaseRequest req) {
        String purchaseType = req.getPurchaseType().toUpperCase();
        List<PosTransactionDtl> out = new ArrayList<>(req.getLines().size());

        int index = 0;
        for (PurchaseLine line : req.getLines()) {
            index++;
            PosProductInfo product = productRepo.findById(line.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product", line.getProductId()));

            if (!product.getPidOrg().equals(mstRepo.findById(mstId)
                    .map(PosTransactionMst::getPidOrg).orElse(product.getPidOrg()))) {
                throw new ResourceNotFoundException("Product is not available for your organization.");
            }
            if (!"Y".equals(product.getStatus())) {
                throw new InactiveEntityException("Product", product.getProductName());
            }

            // Batch and expiry are no longer typed in. Nothing to validate here:
            // the batch comes from the invoice's own generated reference and the
            // expiry is a fixed offset, so neither can be wrong.

            Map<String, Object> pieces = piecesPerUnit(product);
            Integer noOfPcs = pcsFor(line.getSalesType(), pieces);
            Integer pcsPerBox = firstPositive((Integer) pieces.get("pcsPerBox"), 1);

            // What one unit of this line is, as a share of a box.
            //
            // The product master prices a BOX — tpPrice, mrpPrice and igta are
            // all box figures — while PurchaseCalculator multiplies them by a
            // quantity counted in the line's own unit. Snapshotting the box
            // price onto a STRIP or PCS line therefore inflated it by the whole
            // pack factor: a single tablet was costed at the price of the
            // twelve-tablet box it came out of. That is not a data-entry
            // mistake anybody could avoid from the form, because the server
            // overwrote whatever price was sent.
            BigDecimal unitShare = BigDecimal.valueOf(noOfPcs)
                    .divide(BigDecimal.valueOf(pcsPerBox), 10, RoundingMode.HALF_UP);

            PosTransactionDtl dtl = new PosTransactionDtl();
            dtl.setPidTranMst(mstId);
            dtl.setPidProduct(product.getPidProduct());
            // nvl, not the raw value: QUANTITY is NOT NULL, and a gift line
            // arrives with the box empty. Null here reached the insert as an
            // explicit null, which no column default can rescue.
            dtl.setQuantity(nvl(line.getQuantity()));
            // [V67] Free units. Stored beside the quantity and deliberately not
            // fed to PurchaseCalculator — the line is priced for what was bought.
            dtl.setBonusQty(nvl(line.getBonusQty()));
            dtl.setLineKind(isGiftLine(line) ? "GIFT" : "PURCHASE");

            // Price snapshot: a historical line must keep reproducing its total
            // even after the product is repriced.
            dtl.setMnpPrice(perUnit(product.getTpPrice(), unitShare));
            dtl.setMrpPrice(perUnit(product.getMrpPrice(), unitShare));
            dtl.setIgta(perUnit(product.getIgta(), unitShare));

            dtl.setTpPct(nvl(line.getTpPct()));
            dtl.setDiscountPct(nvl(line.getDiscountPct()));
            dtl.setFlatDiscount(nvl(line.getFlatDiscount()));

            // The line batch is derived from the invoice's generated reference
            // rather than typed in. The column itself stays populated because
            // Inventory, Sales and POS all read it for batch stock and expiry
            // alerts — leaving it null would blank out those screens.
            dtl.setBatchNo(batchNo);
            dtl.setExpiryDate(LocalDate.now().plusDays(DEFAULT_EXPIRY_DAYS));
            dtl.setSalesType(line.getSalesType().toUpperCase());
            dtl.setSalesUnit(line.getSalesType().toUpperCase());
            dtl.setNoOfPcs(noOfPcs);

            dtl.setTotalPrice(PurchaseCalculator.calculateLineTotal(
                    purchaseType, dtl.getQuantity(), dtl.getMnpPrice(), dtl.getIgta(),
                    dtl.getMrpPrice(), dtl.getTpPct(), dtl.getDiscountPct(), dtl.getFlatDiscount()));

            if (dtl.getTotalPrice().compareTo(BigDecimal.ZERO) < 0) {
                throw new BusinessException(
                        "Line " + index + " (" + product.getProductName()
                        + "): the discounts exceed the line value, giving a negative total.",
                        HttpStatus.BAD_REQUEST, "NEGATIVE_LINE_TOTAL");
            }

            out.add(dtl);
        }
        return out;
    }

    /** Rolls the lines up onto the header. */
    private void applyTotals(PosTransactionMst mst, List<PosTransactionDtl> lines, PurchaseRequest req) {
        String purchaseType = mst.getPurchaseType();

        BigDecimal netSum = lines.stream()
                .map(PosTransactionDtl::getTotalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal grossSum = lines.stream()
                .map(d -> PurchaseCalculator.calculateLineGross(purchaseType, d.getQuantity(),
                        d.getMnpPrice(), d.getIgta(), d.getMrpPrice(), d.getTpPct()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        InvoiceTotals t = PurchaseCalculator.calculateInvoice(
                netSum, grossSum, req.getInvoiceDisPct(), req.getTotalTax(),
                req.getServiceCharge(), req.getCashbackAmount(), req.getGivenAmount());

        /* [V68] Cashback cannot exceed the bill it is given against.
         *
         * More back than was charged is not an incentive on this purchase — it
         * is a credit the supplier owes, and it belongs on the payment screen
         * where a credit can be tracked and applied. Allowed through here it
         * would produce a negative payable, which the ageing buckets would read
         * as a supplier who owes us money on an invoice we never paid. */
        BigDecimal cashback = nvl(req.getCashbackAmount());
        BigDecimal beforeCashback = t.amountToBePaid().add(cashback);
        if (cashback.compareTo(beforeCashback) > 0) {
            throw new BusinessException(
                    "Cashback of " + cashback.setScale(2, RoundingMode.HALF_UP)
                    + " is more than the invoice comes to ("
                    + beforeCashback.setScale(2, RoundingMode.HALF_UP)
                    + "). Record the difference as a supplier credit instead.",
                    HttpStatus.UNPROCESSABLE_ENTITY, "CASHBACK_EXCEEDS_INVOICE");
        }

        mst.setTotalAmount(t.totalAmount());
        mst.setInvoiceDisPct(t.invoiceDisPct());
        mst.setInvoiceDisAmt(t.invoiceDisAmt());
        mst.setTotalDiscount(t.totalDiscount());
        mst.setTotalTax(t.totalTax());
        mst.setServiceCharge(t.serviceCharge());
        mst.setCashbackAmount(t.cashbackAmount());
        mst.setAmountTobePaid(t.amountToBePaid());
        mst.setGivenAmount(t.givenAmount());

        // Resolved after the totals, because the discount it is taken from is
        // only known once they have been calculated.
        //
        // invoiceDisAmt, not totalDiscount. TP% and the line discounts are the
        // supplier's own price — never charged, so there is nothing to hand
        // back. The invoice cut is the one agreed on the gross and returned
        // later, and it is the same figure SupplierPaymentServiceImpl derives
        // for the same tick. Taking the whole discount asked for 143.43 across
        // on a bill whose invoice discount was 117.01, and the two screens
        // disagreed on what the supplier owed back.
        BigDecimal advance = resolveInvoiceAdvance(req, t.invoiceDisAmt(),
                                                   t.givenAmount(), t.amountToBePaid());
        mst.setAdvanceAmount(advance);

        // Only the part that is not an advance settles the bill. Counting the
        // whole of the cash would mark an invoice paid on the strength of money
        // the supplier is holding for us.
        BigDecimal settling = t.givenAmount().subtract(advance);
        BigDecimal due = t.amountToBePaid().subtract(settling);
        mst.setPaymentStatus(due.compareTo(BigDecimal.ZERO) <= 0 ? "PAID"
                : settling.compareTo(BigDecimal.ZERO) > 0 ? "PARTIAL" : "UNPAID");
    }

    /**
     * Receives the invoice into branch stock.
     *
     * <p>Called only on approval, so a draft or pending invoice never inflates
     * stock. The work itself belongs to {@link StockPostingService}, which owns
     * the rule for which way each document type moves stock — purchase in,
     * purchase return out, and so on — so that rule is stated once rather than
     * restated here and eventually contradicted.
     *
     * <p>What this replaced incremented {@code POS_PRODUCT_INFO.STOCK_QTY}: a
     * single mutable number per product, with no branch, no batch and no
     * history. It could not say which branch held the stock, what a piece cost,
     * or when it expired.
     */
    private void postToStock(PosTransactionMst mst) {
        stockPostingService.post(mst.getPidTranMst());
    }

    /**
     * Everything that happens the moment a purchase becomes real.
     *
     * <p>Stock arrives and the cash handed over becomes a payment. These are one
     * event and must never be done separately: approval reached this class by
     * three routes — the approve endpoint, and saving straight to APPROVED from
     * either create or update — and only one of them mirrored the cash. A
     * purchase approved with "Save &amp; Approve" received its stock and left no
     * trace of the money, so an advance paid across with it was invisible on
     * every screen built to chase it.
     *
     * <p>Both callers now go through here, so the pair cannot come apart again.
     */
    private void receive(PosTransactionMst mst) {
        postToStock(mst);
        accounting.purchaseApproved(mst);
        supplierPayments.mirrorInvoicePayment(mst);
    }

    /**
     * The line's landed cost, per box, strip and piece.
     *
     * <p>Trade price, IGTA, the line discount <em>and the line's share of the
     * invoice-level discount</em>, over the pieces received. That is the figure
     * the stock is valued at and profit is measured against, and it is not
     * {@code mnpPrice}, which is the trade price before any of it.
     *
     * <p>The header discount matters: a 2% invoice discount makes every piece on
     * it 2% cheaper to hold, and omitting it values stock above what was paid.
     * Uses the same apportionment as the stock posting path, so the cost shown
     * here is the cost actually stored on the lot.
     *
     * <p>Packing comes from the product's Box/Leaf rather than the line's
     * {@code NO_OF_PCS}: that field is the factor for the unit <em>transacted
     * in</em>, so on a STRIP purchase it would give a strip cost labelled as a
     * box. Null where a pack size is not configured — never zero, which would
     * read as "free".
     */
    private com.friendspharma.app.dto.response.StockResponses.CostBlock lineCost(
            PosTransactionMst mst, PosTransactionDtl d, PosProductInfo product) {

        BigDecimal pieces = nvl(d.getQuantity())
                .multiply(BigDecimal.valueOf(d.getNoOfPcs() != null && d.getNoOfPcs() > 0 ? d.getNoOfPcs() : 1));
        if (pieces.compareTo(BigDecimal.ZERO) <= 0 || d.getTotalPrice() == null) return null;

        BigDecimal perPcs = StockPostingServiceImpl.netLineTotal(mst, d)
                .divide(pieces, 6, java.math.RoundingMode.HALF_UP);

        Integer pcsPerBox = null, pcsPerStrip = null;
        if (product != null) {
            Map<String, Object> packing = piecesPerUnit(product);
            pcsPerBox   = (Integer) packing.get("pcsPerBox");
            pcsPerStrip = (Integer) packing.get("pcsPerStrip");
        }

        return com.friendspharma.app.dto.response.StockResponses.CostBlock.builder()
                .costPerPcs(perPcs.setScale(4, java.math.RoundingMode.HALF_UP))
                .costPerStrip(scaleCost(perPcs, pcsPerStrip))
                .costPerBox(scaleCost(perPcs, pcsPerBox))
                .pcsPerBox(pcsPerBox)
                .pcsPerStrip(pcsPerStrip)
                .averaged(false)   // one line, one delivery — nothing to average
                .build();
    }

    private static BigDecimal scaleCost(BigDecimal perPcs, Integer pcsPerUnit) {
        if (perPcs == null || pcsPerUnit == null || pcsPerUnit <= 0) return null;
        return perPcs.multiply(BigDecimal.valueOf(pcsPerUnit))
                     .setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /* ── Product unit maths ──────────────────────────────────────────────── */

    /**
     * Pieces per box / strip for a product, derived from Box → Leaf.
     * Returns 1 where nothing is configured, so a PCS purchase still works.
     */
    /** One product's packing, fetched on its own. Fine for a single line. */
    private Map<String, Object> piecesPerUnit(PosProductInfo product) {
        PosBoxInfo box = product.getPidBox() == null ? null
                : boxRepo.findById(product.getPidBox()).orElse(null);
        PosLeafInfo leaf = (box == null || box.getPidLeaf() == null) ? null
                : leafRepo.findById(box.getPidLeaf()).orElse(null);
        return piecesPerUnit(box, leaf);
    }

    /**
     * The same answer from boxes and leaves already in hand.
     *
     * <p><b>Why this exists.</b> The single-product version above costs two
     * queries, and the product picker called it once per row inside a stream —
     * so a page of fifty products was a hundred round trips on top of the three
     * the rest of the method carefully batches. That was tolerable only because
     * the page was capped at fifty. Lifting the cap so a whole manufacturer can
     * be loaded turns it into fifteen hundred, and the request stops being
     * usable. The picker now reads both maps once for the whole page.
     */
    private Map<String, Object> piecesPerUnit(PosBoxInfo box, PosLeafInfo leaf) {
        Integer stripsPerBox = box == null ? null : box.getNoOfLeaf();
        Integer pcsPerStrip  = leaf == null ? null : leaf.getNoOfPcs();

        Integer pcsPerBox = (stripsPerBox != null && pcsPerStrip != null)
                ? stripsPerBox * pcsPerStrip : null;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pcsPerStrip", pcsPerStrip);
        m.put("stripsPerBox", stripsPerBox);
        m.put("pcsPerBox", pcsPerBox);
        return m;
    }

    private Integer pcsFor(String salesType, Map<String, Object> pieces) {
        if (salesType == null) return 1;
        return switch (salesType.toUpperCase()) {
            case "BOX"   -> firstPositive((Integer) pieces.get("pcsPerBox"), 1);
            case "STRIP" -> firstPositive((Integer) pieces.get("pcsPerStrip"), 1);
            default      -> 1;
        };
    }

    /**
     * A box price restated for the unit actually being bought.
     *
     * <p>Four decimals to match the column, and because a twelfth or a
     * hundredth of a box price rarely lands on two: rounding earlier would make
     * the line total drift from the supplier's invoice by a few paisa a line.
     */
    /**
     * How much of the cash handed over is an advance rather than a settlement.
     *
     * <p>Two ways to say so: tick the invoice discount and let the invoice
     * supply the figure, or state an amount. An overpayment that declares
     * neither is refused — handing over more than the bill is far more often a
     * keying slip than a rebate, and quietly booking a receivable nobody agreed
     * is worse than a rejected form.
     */
    private BigDecimal resolveInvoiceAdvance(PurchaseRequest req, BigDecimal invoiceDiscount,
                                             BigDecimal given, BigDecimal payable) {
        BigDecimal advance;

        if (Boolean.TRUE.equals(req.getPayInvoiceDiscount())) {
            advance = nvl(invoiceDiscount);
            if (advance.compareTo(BigDecimal.ZERO) <= 0) {
                throw new ValidationException(
                        "This invoice carries no invoice discount, so there is none to pay across. "
                        + "Clear the tick, or enter an invoice discount % first.");
            }
            BigDecimal stated = req.getAdvanceAmount();
            if (stated != null && stated.subtract(advance).abs().compareTo(SETTLE_TOLERANCE) > 0) {
                throw new ValidationException(
                        "The invoice discount on this invoice is " + advance.setScale(2, RoundingMode.HALF_UP)
                        + ", which does not match the advance of "
                        + stated.setScale(2, RoundingMode.HALF_UP) + " entered. Clear one of the two.");
            }
        } else {
            advance = nvl(req.getAdvanceAmount());
        }

        if (advance.compareTo(BigDecimal.ZERO) < 0) {
            throw new ValidationException("An advance cannot be negative.");
        }
        if (advance.subtract(nvl(given)).compareTo(SETTLE_TOLERANCE) > 0) {
            throw new ValidationException(
                    "The advance of " + advance.setScale(2, RoundingMode.HALF_UP)
                    + " is more than the " + nvl(given).setScale(2, RoundingMode.HALF_UP)
                    + " handed over. An advance is the part of a payment that settles no part of "
                    + "the bill, so it cannot exceed the payment itself.");
        }
        advance = advance.min(nvl(given));

        BigDecimal settling = nvl(given).subtract(advance);
        if (settling.subtract(nvl(payable)).compareTo(SETTLE_TOLERANCE) > 0) {
            throw new ValidationException(
                    "This invoice is for " + nvl(payable).setScale(2, RoundingMode.HALF_UP)
                    + " but " + settling.setScale(2, RoundingMode.HALF_UP)
                    + " of the cash is being put against it. Either correct the amount, or record "
                    + "the extra " + settling.subtract(nvl(payable)).setScale(2, RoundingMode.HALF_UP)
                    + " as an advance the supplier owes back.");
        }

        return advance.setScale(4, RoundingMode.HALF_UP);
    }

    /** Sub-paisa is not a debt — the same tolerance the sales side settles on. */
    private static final BigDecimal SETTLE_TOLERANCE = new BigDecimal("0.01");

    private BigDecimal perUnit(BigDecimal boxPrice, BigDecimal unitShare) {
        return nvl(boxPrice).multiply(unitShare).setScale(4, RoundingMode.HALF_UP);
    }

    private Integer firstPositive(Integer value, int fallback) {
        return (value != null && value > 0) ? value : fallback;
    }

    /* ── Loading / mapping ───────────────────────────────────────────────── */

    private PosSupplierInfo validateSupplier(Long supplierId, Long orgId) {
        PosSupplierInfo supplier = supplierRepo.findById(supplierId)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier", supplierId));
        if (supplier.getPidOrg() != null && !supplier.getPidOrg().equals(orgId)) {
            throw new ResourceNotFoundException("Supplier is not available for your organization.");
        }
        if (!"Y".equals(supplier.getStatus())) {
            throw new InactiveEntityException("Supplier", supplier.getSupplierName());
        }
        return supplier;
    }

    /**
     * Every line must be something this supplier actually sells.
     *
     * <p>A supplier represents one manufacturer
     * ({@code POS_SUPPLIER_INFO.POS_PID_COMPANY}), and buying another maker's
     * product from them is not a purchase anybody can honour — the invoice would
     * be raised against a company that never shipped it, and the supplier
     * balance it creates is owed to the wrong party.
     *
     * <p><b>Checked here because the screen's filter is not a control.</b> The
     * picker only offers the supplier's own products, but the endpoint behind it
     * takes a product id, and a request built by hand can carry any id at all.
     * This is where a purchase is written, so this is where the rule has to
     * hold; the filter is what stops it being offered by accident.
     *
     * <p>Named rather than counted: "3 products are not from this supplier" sends
     * somebody hunting through the table, so each offending line says which
     * product and which maker it belongs to.
     */
    /** [V69] A line the supplier gave rather than sold. */
    static boolean isGiftLine(PurchaseLine line) {
        return "GIFT".equalsIgnoreCase(line.getLineKind());
    }

    /**
     * [V69] Each kind of line held to its own rule.
     *
     * <p>The two are mirror images and both matter. A purchase with no quantity
     * is a typo — the reason LINE_KIND exists rather than inferring a gift from
     * a zero. A gift with no bonus is a line that receives nothing, occupies a
     * row, and takes a share of the invoice cost away from the goods that did
     * arrive.
     */
    private void requireLineShape(PurchaseRequest req) {
        for (PurchaseLine line : req.getLines() == null ? List.<PurchaseLine>of() : req.getLines()) {
            boolean gift = isGiftLine(line);
            BigDecimal qty = nvl(line.getQuantity());
            BigDecimal bonus = nvl(line.getBonusQty());

            if (gift && bonus.compareTo(BigDecimal.ZERO) <= 0) {
                throw new ValidationException(
                        "A free gift line needs a bonus quantity — that is what was given.");
            }
            if (gift && qty.compareTo(BigDecimal.ZERO) > 0) {
                throw new ValidationException(
                        "A free gift is not bought, so it cannot have a purchase quantity. "
                        + "Put the units in Bonus, or make this an ordinary line.");
            }
            if (!gift && qty.compareTo(BigDecimal.ZERO) <= 0) {
                throw new ValidationException(
                        "Quantity must be greater than zero. If the supplier gave this free, "
                        + "mark the line as a gift.");
            }
        }
    }

    private void requireLinesFromSupplier(PosSupplierInfo supplier, PurchaseRequest req) {
        List<PurchaseLine> lines = req.getLines() == null ? List.of() : req.getLines();
        if (lines.isEmpty()) return;

        if (supplier.getPosPidCompany() == null) {
            throw new BusinessException(
                    supplier.getSupplierName() + " has no manufacturer assigned, so there is nothing "
                    + "they can supply. Set their Product Company in Master Data › Suppliers first.",
                    HttpStatus.CONFLICT, "SUPPLIER_NO_COMPANY");
        }

        /* [V69] A gift is the one legitimate exception. A supplier represents
           one manufacturer and may not sell another's goods — but giving one
           away is exactly what happens when the oil comes free with the Napa,
           and refusing it would make the commonest gift unrecordable. */
        List<Long> productIds = lines.stream()
                .filter(l -> !isGiftLine(l))
                .map(PurchaseLine::getProductId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, PosProductInfo> products = productRepo.findAllById(productIds).stream()
                .collect(Collectors.toMap(PosProductInfo::getPidProduct, p -> p, (a, b) -> a));

        Map<Long, String> makers = companyRepo.findAllById(
                        products.values().stream().map(PosProductInfo::getPidCompany)
                                .filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(PosCompanyInfo::getPidCompany,
                                          PosCompanyInfo::getCompanyName, (a, b) -> a));

        for (Long id : productIds) {
            PosProductInfo p = products.get(id);
            if (p == null || supplier.getPosPidCompany().equals(p.getPidCompany())) continue;

            String maker = p.getPidCompany() == null ? "no manufacturer"
                    : makers.getOrDefault(p.getPidCompany(), "another manufacturer");
            throw new BusinessException(
                    "\"" + p.getProductName() + "\" is from " + maker + ", and "
                    + supplier.getSupplierName() + " supplies "
                    + makers.getOrDefault(supplier.getPosPidCompany(), "a different manufacturer")
                    + ". Remove that line or raise a separate invoice for it.",
                    HttpStatus.CONFLICT, "PRODUCT_NOT_FROM_SUPPLIER");
        }
    }

    /**
     * Loads a purchase and confirms it belongs to the caller's organisation.
     * A cross-tenant id yields the same 404 as a missing one, so the API never
     * confirms that another organisation's invoice exists.
     */
    private PosTransactionMst loadOwnedPurchase(Long id) {
        Long orgId = requireOrgId();
        PosTransactionMst mst = mstRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase invoice", id));
        if (!orgId.equals(mst.getPidOrg()) || !TYPE_PURCHASE.equals(mst.getTransactionType())) {
            throw new ResourceNotFoundException("Purchase invoice", id);
        }
        return mst;
    }

    /**
     * [V67] What one unit of this line really cost, once the bonus is counted.
     *
     * <p>Net line total — the line's own money less its share of any invoice
     * discount — over the units that actually arrived. Ten boxes at 100 with two
     * free is 1,000 ÷ 12 = 83.33 a box, and that is the number the shelf is
     * valued at and the number COGS will use when a box is sold.
     *
     * <p>Reuses {@code StockPostingServiceImpl.netLineTotal} rather than
     * restating the discount arithmetic. The screen and the batch have to agree,
     * and the only way to be sure they do is for both to call the same function —
     * a second implementation here would be right on the day it was written and
     * wrong the first time the discount rule changed.
     *
     * @return null when the line has no money or no units, so the column shows a
     *         dash rather than a zero that looks like a free product
     */
    private BigDecimal effectiveUnitCost(PosTransactionMst mst, PosTransactionDtl line,
                                         StockPostingServiceImpl.CostAllocation allocation) {
        BigDecimal received = nvl(line.getQuantity()).add(nvl(line.getBonusQty()));
        if (received.compareTo(BigDecimal.ZERO) <= 0) return null;

        /* [V69] Through the allocation, not straight to netLineTotal. On an
           invoice carrying a gift the cost of every line is a share of the
           whole, and a gift has no line total of its own to fall back on — so
           reading the line alone would show the gift at nothing and the rest
           too dear, while the batch beside it held the allocated figures. */
        BigDecimal net = allocation.netFor(mst, line);
        if (net == null) return null;
        return net.divide(received, 4, RoundingMode.HALF_UP);
    }

    private PurchaseResponse buildDetail(PosTransactionMst mst) {
        PosSupplierInfo supplier = mst.getPidSupCust() != null
                ? supplierRepo.findById(mst.getPidSupCust()).orElse(null) : null;
        AllBranch branch = mst.getPidBranch() != null
                ? branchRepo.findById(mst.getPidBranch()).orElse(null) : null;

        Settlement paid = settlementOf(mst);

        List<PosTransactionDtl> lines = dtlRepo.findByPidTranMst(mst.getPidTranMst());

        Map<Long, PosProductInfo> products = lines.isEmpty() ? Map.of()
                : productRepo.findAllById(lines.stream().map(PosTransactionDtl::getPidProduct).toList())
                    .stream().collect(Collectors.toMap(PosProductInfo::getPidProduct, Function.identity(), (a, b) -> a));

        Collection<PosProductInfo> productList = products.values();
        Map<Long, String> generics = labelMap(productList, PosProductInfo::getPidGeneric,
                genericRepo::findAllById, PosGenericInfo::getPidGeneric, PosGenericInfo::getGenericName);
        Map<Long, String> companies = labelMap(productList, PosProductInfo::getPidCompany,
                companyRepo::findAllById, PosCompanyInfo::getPidCompany, PosCompanyInfo::getCompanyName);
        Map<Long, String> units = labelMap(productList, PosProductInfo::getPidUnitId,
                unitRepo::findAllById, PosProductUnit::getPidUnit, PosProductUnit::getUnitName);

        /* [V69] The same allocation the posting used, built from the same lines,
           so the effective cost on screen is the cost in the batch. */
        StockPostingServiceImpl.CostAllocation allocation =
                StockPostingServiceImpl.CostAllocation.of(mst, lines);

        List<PurchaseLineResponse> lineDtos = lines.stream().map(d -> {
            PosProductInfo p = products.get(d.getPidProduct());
            BigDecimal pcs = BigDecimal.valueOf(d.getNoOfPcs() != null ? d.getNoOfPcs() : 1);
            return PurchaseLineResponse.builder()
                    .id(d.getPidTranDtl())
                    .productId(d.getPidProduct())
                    .productCode(p != null ? p.getProductCode() : null)
                    .productName(p != null ? p.getProductName() : null)
                    .genericName(p != null ? lookup(generics, p.getPidGeneric()) : null)
                    .companyName(p != null ? lookup(companies, p.getPidCompany()) : null)
                    .unitName(p != null ? lookupOr(units, p.getPidUnitId(), p.getPidUnit()) : null)
                    .quantity(d.getQuantity())
                    .bonusQty(nvl(d.getBonusQty()))
                    .receivedQty(nvl(d.getQuantity()).add(nvl(d.getBonusQty())))
                    .lineKind(d.getLineKind() == null ? "PURCHASE" : d.getLineKind())
                    .effectiveUnitCost(effectiveUnitCost(mst, d, allocation))
                    .mnpPrice(d.getMnpPrice())
                    .mrpPrice(d.getMrpPrice())
                    .igta(d.getIgta())
                    .tpPct(d.getTpPct())
                    .discountPct(d.getDiscountPct())
                    .flatDiscount(d.getFlatDiscount())
                    .totalPrice(d.getTotalPrice())
                    .batchNo(d.getBatchNo())
                    .expiryDate(d.getExpiryDate())
                    .salesType(d.getSalesType())
                    .noOfPcs(d.getNoOfPcs())
                    /* [V67] Pieces that arrived, bonus included — the figure the
                       batch was given. Reporting the purchased pieces here while
                       the shelf held more is the inconsistency the whole feature
                       exists to remove. */
                    .totalPcs(nvl(d.getQuantity()).add(nvl(d.getBonusQty())).multiply(pcs))
                    .cost(lineCost(mst, d, p))
                    .build();
        }).toList();

        return PurchaseResponse.builder()
                .id(mst.getPidTranMst())
                .invoiceNo(mst.getInvoiceNo())
                .batchNo(mst.getBatchNo())
                .challanNo(mst.getChallanNo())
                .transactionDate(mst.getTransactionDate())
                .transactionType(mst.getTransactionType())
                .purchaseType(mst.getPurchaseType())
                .productType(mst.getProductType())
                .paymentType(mst.getPaymentType())
                .supplier(Ref.of(mst.getPidSupCust(), supplier != null ? supplier.getSupplierName() : null))
                .branch(Ref.of(mst.getPidBranch(), branch != null ? branch.getBranchName() : null))
                .totalAmount(mst.getTotalAmount())
                .invoiceDisPct(mst.getInvoiceDisPct())
                .invoiceDisAmt(mst.getInvoiceDisAmt())
                .totalDiscount(mst.getTotalDiscount())
                .totalTax(mst.getTotalTax())
                .serviceCharge(mst.getServiceCharge())
                .cashbackAmount(nvl(mst.getCashbackAmount()))
                .amountToBePaid(mst.getAmountTobePaid())
                .givenAmount(mst.getGivenAmount())
                .advanceAmount(mst.getAdvanceAmount())
                .paidAmount(paid.paid())
                .returnCredit(paid.returnCredit())
                .dueAmount(paid.due())
                .remarks(mst.getRemarks())
                .status(mst.getStatus())
                .statusLabel(PurchaseSpecification.statusLabel(mst.getStatus()))
                .editable(!STATUS_APPROVED.equals(mst.getStatus()))
                .fromSource(mst.getFromSource())
                .lineCount(lineDtos.size())
                .lines(lineDtos)
                .createdBy(mst.getInsertedBy())
                .createdDate(mst.getInsertedDate())
                .updatedBy(mst.getUpdatedBy())
                .updatedDate(mst.getUpdatedDate())
                .build();
    }

    private Map<Long, String> resolveSupplierNames(List<PosTransactionMst> page) {
        Set<Long> ids = page.stream().map(PosTransactionMst::getPidSupCust)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        return supplierRepo.findAllById(ids).stream()
                .collect(Collectors.toMap(PosSupplierInfo::getPidSupplier,
                        PosSupplierInfo::getSupplierName, (a, b) -> a));
    }

    private Map<Long, String> resolveBranchNames(List<PosTransactionMst> page) {
        Set<Long> ids = page.stream().map(PosTransactionMst::getPidBranch)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        return branchRepo.findAllById(ids).stream()
                .collect(Collectors.toMap(AllBranch::getPidBranch, AllBranch::getBranchName, (a, b) -> a));
    }

    /** One grouped query for the whole page instead of a count per row. */
    private Map<Long, Integer> countLines(List<Long> mstIds) {
        if (mstIds.isEmpty()) return Map.of();
        Map<Long, Integer> counts = new HashMap<>();
        for (PosTransactionDtl d : dtlRepo.findByPidTranMstIn(mstIds)) {
            counts.merge(d.getPidTranMst(), 1, Integer::sum);
        }
        return counts;
    }

    private <T> Map<Long, String> labelMap(Collection<PosProductInfo> products,
                                           Function<PosProductInfo, Long> idOf,
                                           Function<Iterable<Long>, List<T>> loader,
                                           Function<T, Long> keyOf,
                                           Function<T, String> nameOf) {
        Set<Long> ids = products.stream().map(idOf).filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        return loader.apply(ids).stream()
                .collect(Collectors.toMap(keyOf, nameOf, (a, b) -> a));
    }

    /**
     * Null-safe map read.
     *
     * <p>{@code Map.of()} — which the label helpers return when there is nothing
     * to load — rejects a null key with a NullPointerException rather than
     * returning null. Optional foreign keys are routinely null here (a branchless
     * session, a product predating the unit FK), so every lookup goes through
     * this instead of calling {@code get} directly.
     */
    private static <V> V lookup(Map<Long, V> map, Long key) {
        return key == null ? null : map.get(key);
    }

    private static String lookupOr(Map<Long, String> map, Long key, String fallback) {
        String found = lookup(map, key);
        return found != null ? found : fallback;
    }

    /**
     * What this invoice still owes.
     *
     * <p>Only the non-advance part of the cash settles it. Subtracting the whole
     * of GIVEN_AMOUNT reports a discounted invoice as overpaid — a negative due
     * the screen then labels "Change" — when in fact the extra is money the
     * supplier is holding for us.
     */
    private BigDecimal due(PosTransactionMst m) {
        BigDecimal settling = nvl(m.getGivenAmount()).subtract(nvl(m.getAdvanceAmount()));
        return nvl(m.getAmountTobePaid()).subtract(settling);
    }

    /**
     * What an invoice has been paid and still owes, from the shared view.
     *
     * <p>An approved purchase can be settled long after it was keyed in, on the
     * Supplier Payment screen. Reading GIVEN_AMOUNT alone — the cash at the
     * counter — reported those invoices as untouched, so the same bill showed
     * paid in one place and fully outstanding in another.
     *
     * <p>Drafts have no row in the view, because they are not liabilities yet;
     * for them the counter figure is all there is.
     */
    private Map<Long, Settlement> settlementsFor(List<PosTransactionMst> masters) {
        var approved = masters.stream()
                .filter(m -> STATUS_APPROVED.equals(m.getStatus()))
                .map(PosTransactionMst::getPidTranMst)
                .filter(Objects::nonNull)
                .toList();
        if (approved.isEmpty()) return Map.of();

        Map<Long, Settlement> out = new HashMap<>();
        for (Object[] r : supplierPayRepo.settlementFor(approved)) {
            out.put(((Number) r[0]).longValue(),
                    new Settlement(decimal(r[1]), decimal(r[2]), decimal(r[3])));
        }
        return out;
    }

    private Settlement settlementOf(PosTransactionMst m) {
        Map<Long, Settlement> one = settlementsFor(List.of(m));
        Settlement s = one.get(m.getPidTranMst());
        return s != null ? s
                : new Settlement(nvl(m.getGivenAmount()).subtract(nvl(m.getAdvanceAmount())),
                                 BigDecimal.ZERO, due(m));
    }

    /** Paid to date, credited by returns, and still owing. */
    private record Settlement(BigDecimal paid, BigDecimal returnCredit, BigDecimal due) {}

    private static BigDecimal decimal(Object o) {
        if (o == null) return BigDecimal.ZERO;
        return o instanceof BigDecimal b ? b : new BigDecimal(o.toString());
    }

    private Long requireOrgId() {
        Long orgId = SecurityUtils.getCurrentOrgId();
        if (orgId == null) {
            throw new BusinessException("No organization is associated with your session. Please log in again.",
                    HttpStatus.UNAUTHORIZED, "NO_ORG_CONTEXT");
        }
        return orgId;
    }

    /**
     * Company for the invoice, from the session.
     *
     * <p>PID_COMPANY is NOT NULL since V7. Reporting a missing one here gives the
     * administrator something they can act on — assign the user a company —
     * rather than surfacing a raw constraint violation from the database.
     */
    private Long requireCompanyId() {
        Long companyId = SecurityUtils.getCurrentCompanyId();
        if (companyId == null) {
            throw new BusinessException(
                    "Your user account has no company assigned, so a purchase cannot be recorded against one. "
                    + "Ask an administrator to set your company in RBAC › Users.",
                    HttpStatus.CONFLICT, "NO_COMPANY_CONTEXT");
        }
        return companyId;
    }

    /**
     * Branch for the invoice: an explicit choice wins, otherwise the session's.
     * Multi-branch users can purchase into a branch other than their own; single
     * branch users never see the field.
     */
    private Long requireBranchId(Long requestedBranchId) {
        Long branchId = requestedBranchId != null ? requestedBranchId : SecurityUtils.getCurrentBranchId();
        if (branchId == null) {
            throw new BusinessException(
                    "Your user account has no branch assigned, so a purchase cannot be recorded against one. "
                    + "Ask an administrator to set your branch in RBAC › Users.",
                    HttpStatus.CONFLICT, "NO_BRANCH_CONTEXT");
        }
        return branchId;
    }

    private static Map<String, Object> option(Long id, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        return m;
    }

    private static BigDecimal nvl(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }
    private static boolean isBlank(String v)    { return v == null || v.trim().isEmpty(); }
    private static String emptyToNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
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
