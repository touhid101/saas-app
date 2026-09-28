package com.friendspharma.app.service.impl;

import com.friendspharma.app.dto.request.PurchaseRequest;
import com.friendspharma.app.dto.response.PurchaseResponse;
import com.friendspharma.app.dto.response.RequisitionSplitResponse;
import com.friendspharma.app.dto.response.RequisitionSplitResponse.*;
import com.friendspharma.app.entity.PosRequisitionDtl;
import com.friendspharma.app.entity.PosRequisitionMst;
import com.friendspharma.app.entity.PosTransactionMst;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.exception.custom.ValidationException;
import com.friendspharma.app.repository.PosRequisitionMstRepository;
import com.friendspharma.app.repository.PosSupplierInfoRepository;
import com.friendspharma.app.repository.PosTransactionMstRepository;
import com.friendspharma.app.repository.RequisitionSourcingRepository;
import com.friendspharma.app.security.SecurityUtils;
import com.friendspharma.app.service.interfaces.PurchaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Turning one requisition into the purchase orders it actually is.
 *
 * <p><b>Why this step exists.</b> A requisition answers "what are we short of",
 * and a shortage does not respect who makes things — a week's reorder list spans
 * every manufacturer you stock. A purchase order answers "what are we buying and
 * from whom", and is always one supplier. Trying to make the requisition itself
 * single-supplier pushes the problem onto the buyer, who then keeps four lists
 * by hand and raises four of everything.
 *
 * <p>So the requisition is left alone and the split is where the decision lives.
 * It is shown before it is done: this is the point at which money is committed,
 * and the last point at which changing your mind costs nothing.
 *
 * <p><b>What it creates.</b> Draft purchases — status D. Nothing is received and
 * no stock moves. The buyer opens each one, checks it against what the supplier
 * confirms, and approves it when the goods arrive, which is when the existing
 * purchase flow posts the batches and the payable.
 *
 * <p><b>How a supplier is chosen.</b> In order: an override the buyer typed;
 * then the supplier the product was last actually bought from; then a supplier
 * who represents that product's manufacturer. If none of those answer, the line
 * is put aside as unassigned rather than guessed at — a line that disappears
 * quietly between the requisition and the order is the one that is missed on the
 * shelf a fortnight later.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequisitionSplitServiceImpl {

    private final PosRequisitionMstRepository reqRepo;
    private final RequisitionSourcingRepository sourcingRepo;
    private final PosSupplierInfoRepository supplierRepo;
    private final PurchaseService purchaseService;
    /* Added for ordersOf: reading back the orders a split created, and refusing
       to do it for another pharmacy. */
    private final PosTransactionMstRepository tranRepo;
    private final BranchScopeService branchScope;

    /** Only an approved requisition may be turned into an order. */
    private static final String STAGE_APPROVED  = "APPROVED";
    private static final String STAGE_CONVERTED = "CONVERTED";
    /** Draft: created, costed, and moving no stock until somebody approves it. */
    private static final String PURCHASE_DRAFT  = "D";

    /* ══════════════════════════════════════════════════════════════════════
       PREVIEW
       ══════════════════════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public Preview preview(Long requisitionId, Map<Long, Long> overrides) {
        PosRequisitionMst req = requisition(requisitionId);
        List<PosRequisitionDtl> lines = req.getDetails();
        if (lines.isEmpty()) {
            throw new ValidationException(
                    req.getRequisitionNo() + " has no lines, so there is nothing to order.");
        }

        Map<Long, Source> sources = sourcesFor(lines, overrides);
        Map<Long, Pricing> pricing = pricingFor(lines);

        List<Line> priced = new ArrayList<>();
        for (PosRequisitionDtl d : lines) {
            Source src = sources.get(d.getPidProduct());
            priced.add(toLine(d, src, pricing.get(d.getPidProduct())));
        }

        List<Line> unassigned = priced.stream()
                .filter(l -> l.getSupplierId() == null).toList();

        // Grouped in a LinkedHashMap so the order is the order the lines were
        // raised in, not whatever the hash decides — a proposal that reshuffles
        // between two looks is hard to trust.
        Map<Long, List<Line>> bySupplier = priced.stream()
                .filter(l -> l.getSupplierId() != null)
                .collect(Collectors.groupingBy(Line::getSupplierId,
                        LinkedHashMap::new, Collectors.toList()));

        List<Group> groups = bySupplier.entrySet().stream().map(e -> {
            List<Line> ls = e.getValue();
            return Group.builder()
                    .supplierId(e.getKey())
                    .supplierName(ls.get(0).getSupplierName())
                    .companyName(ls.get(0).getCompanyName())
                    .lineCount(ls.size())
                    .total(ls.stream().map(Line::getLineTotal)
                            .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP))
                    .lines(ls)
                    .build();
        }).sorted(Comparator.comparing(Group::getTotal).reversed()).toList();

        return Preview.builder()
                .requisitionId(req.getPidRequisition())
                .requisitionNo(req.getRequisitionNo())
                .stage(req.getStage())
                .lineCount(priced.size())
                .total(priced.stream().map(Line::getLineTotal)
                        .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP))
                .groups(groups)
                .unassigned(unassigned)
                .ready(unassigned.isEmpty() && !groups.isEmpty())
                .build();
    }

    /* ══════════════════════════════════════════════════════════════════════
       SPLIT
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * Creates one draft purchase per supplier.
     *
     * <p>Refuses while any line is unassigned. Creating the orders it can and
     * leaving the rest behind would be the quiet failure this whole step exists
     * to prevent: the buyer would believe the requisition had been placed.
     */
    @Transactional
    public Result split(Long requisitionId, Map<Long, Long> overrides, boolean allowPartial) {
        PosRequisitionMst req = requisition(requisitionId);

        if (!STAGE_APPROVED.equalsIgnoreCase(req.getStage())) {
            throw new BusinessException(
                    req.getRequisitionNo() + " is at stage '" + req.getStage() + "'. Only an "
                    + "approved requisition can be turned into purchase orders.",
                    HttpStatus.CONFLICT, "REQUISITION_NOT_APPROVED");
        }

        Preview p = preview(requisitionId, overrides);

        if (!p.getUnassigned().isEmpty() && !allowPartial) {
            String names = p.getUnassigned().stream().map(Line::getProductName)
                    .limit(5).collect(Collectors.joining(", "));
            throw new ValidationException(
                    p.getUnassigned().size() + " line" + (p.getUnassigned().size() == 1 ? "" : "s")
                    + " have no supplier — " + names + ". Choose one for each, or split the rest "
                    + "and raise those separately.");
        }
        if (p.getGroups().isEmpty()) {
            throw new ValidationException("No line has a supplier, so there is nothing to order.");
        }

        Map<Long, PosRequisitionDtl> byLineId = req.getDetails().stream()
                .collect(Collectors.toMap(PosRequisitionDtl::getPidReqDtl, d -> d));

        List<Created> created = new ArrayList<>();
        BigDecimal grand = BigDecimal.ZERO;

        for (Group g : p.getGroups()) {
            PurchaseRequest pr = new PurchaseRequest();
            pr.setSupplierId(g.getSupplierId());
            pr.setBranchId(req.getPidBranch());
            pr.setTransactionDate(LocalDate.now());
            pr.setPurchaseType("MNP");
            pr.setPaymentType("CREDIT");
            // Draft on purpose: this is an order being placed, not goods arriving.
            pr.setStatus(PURCHASE_DRAFT);
            pr.setRemarks(clip("From " + req.getRequisitionNo()
                    + (req.getRemarks() == null ? "" : " — " + req.getRemarks()), 240));

            pr.setLines(g.getLines().stream().map(l -> {
                PurchaseRequest.PurchaseLine pl = new PurchaseRequest.PurchaseLine();
                pl.setProductId(l.getProductId());
                pl.setQuantity(l.getBoxes());
                // Requisitions are raised in boxes, which is how stock is bought.
                pl.setSalesType("BOX");
                return pl;
            }).toList());

            PurchaseResponse po = purchaseService.create(pr);

            for (Line l : g.getLines()) {
                PosRequisitionDtl d = byLineId.get(l.getReqLineId());
                if (d != null) d.setPidPurchaseOrder(po.getId());
            }

            created.add(Created.builder()
                    .purchaseId(po.getId()).invoiceNo(po.getInvoiceNo())
                    .supplierId(g.getSupplierId()).supplierName(g.getSupplierName())
                    .lineCount(g.getLineCount())
                    .total(po.getAmountToBePaid() == null ? g.getTotal() : po.getAmountToBePaid())
                    .status(po.getStatus())
                    .build());
            grand = grand.add(created.get(created.size() - 1).getTotal());
        }

        req.setStage(STAGE_CONVERTED);
        // The master column holds one id, so it is only meaningful when the whole
        // requisition went to one supplier. Otherwise the lines carry the answer.
        req.setPidPurchaseOrder(created.size() == 1 ? created.get(0).getPurchaseId() : null);
        reqRepo.save(req);

        log.info("Split {} into {} draft purchase order(s), {}",
                req.getRequisitionNo(), created.size(), grand);

        return Result.builder()
                .requisitionId(req.getPidRequisition())
                .requisitionNo(req.getRequisitionNo())
                .stage(req.getStage())
                .ordersCreated(created.size())
                .total(grand.setScale(2, RoundingMode.HALF_UP))
                .orders(created)
                .build();
    }

    /**
     * The orders a requisition has already been split into.
     *
     * <p>Exists so the supplier copies can be printed again. The split returns
     * them once, in the dialog that confirms it — and a buyer who closed that
     * dialog, or who comes back the next morning to re-send a sheet a supplier
     * mislaid, had no way to reach them. The requisition's own PID_PURCHASE_ORDER
     * is no help: it is only filled when the whole requisition went to a single
     * supplier, so the answer lives on the lines.
     */
    @Transactional(readOnly = true)
    public List<Created> ordersOf(Long pidRequisition) {
        PosRequisitionMst req = reqRepo.findById(pidRequisition)
                .orElseThrow(() -> new ResourceNotFoundException("Requisition", pidRequisition));
        branchScope.resolve(req.getPidBranch());

        // Distinct, in the order they were created: several lines point at the
        // same order, and a buyer reads them as one sheet each.
        List<Long> ids = req.getDetails().stream()
                .map(PosRequisitionDtl::getPidPurchaseOrder)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .toList();

        List<Created> out = new ArrayList<>(ids.size());
        for (Long id : ids) {
            PosTransactionMst po = tranRepo.findById(id).orElse(null);
            if (po == null) continue;          // deleted since the split; skip rather than fail
            long lines = req.getDetails().stream()
                    .filter(d -> id.equals(d.getPidPurchaseOrder()))
                    .count();
            out.add(Created.builder()
                    .purchaseId(id)
                    .invoiceNo(po.getInvoiceNo())
                    .supplierId(po.getPidSupCust())
                    .supplierName(supplierName(po.getPidSupCust()))
                    .lineCount((int) lines)
                    .total(po.getAmountTobePaid() == null ? BigDecimal.ZERO : po.getAmountTobePaid())
                    .status(po.getStatus())
                    .build());
        }
        return out;
    }

    /* ══════════════════════════════════════════════════════════════════════
       SOURCING
       ══════════════════════════════════════════════════════════════════════ */

    /** A supplier for one product, and how confident we are about it. */
    private record Source(Long supplierId, String supplierName, BigDecimal ratePerPcs,
                          LocalDate lastPurchasedOn, String basis) { }

    private Map<Long, Source> sourcesFor(List<PosRequisitionDtl> lines, Map<Long, Long> overrides) {
        Long orgId = SecurityUtils.getCurrentOrgId();
        Set<Long> productIds = lines.stream().map(PosRequisitionDtl::getPidProduct)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (productIds.isEmpty()) return Map.of();

        Map<Long, Source> out = new HashMap<>();

        // Weakest first, so the stronger evidence overwrites it.
        for (Object[] r : sourcingRepo.manufacturerSupplierFor(productIds, orgId)) {
            out.put(lng(r[0]), new Source(lng(r[1]), str(r[2]), null, null, "MANUFACTURER"));
        }
        for (Object[] r : sourcingRepo.lastPurchaseFor(productIds, orgId)) {
            out.put(lng(r[0]), new Source(lng(r[1]), str(r[2]), dec(r[3]), date(r[4]), "LAST_PURCHASE"));
        }

        // The buyer's own choice beats both. Keyed by requisition line, because
        // the same product could in principle appear twice on one requisition.
        if (overrides != null && !overrides.isEmpty()) {
            Map<Long, PosRequisitionDtl> byLineId = lines.stream()
                    .collect(Collectors.toMap(PosRequisitionDtl::getPidReqDtl, d -> d));
            for (Map.Entry<Long, Long> e : overrides.entrySet()) {
                PosRequisitionDtl d = byLineId.get(e.getKey());
                if (d == null) continue;
                Source prior = out.get(d.getPidProduct());
                out.put(d.getPidProduct(), new Source(
                        e.getValue(),
                        supplierName(e.getValue()),
                        prior == null ? null : prior.ratePerPcs(),
                        prior == null ? null : prior.lastPurchasedOn(),
                        "OVERRIDE"));
            }
        }
        return out;
    }

    /** Refuses an unknown supplier rather than pricing lines against a ghost. */
    private String supplierName(Long supplierId) {
        if (supplierId == null) return null;
        return supplierRepo.findById(supplierId)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier", supplierId))
                .getSupplierName();
    }

    /** A box's trade price, its IGTA, and how many pieces it holds. */
    private record Pricing(int pcsPerBox, BigDecimal tp, BigDecimal igta) {
        static final Pricing NONE = new Pricing(1, BigDecimal.ZERO, BigDecimal.ZERO);
        /** The figure the purchase line will carry: qty x (TP + IGTA). */
        BigDecimal perBox() { return tp.add(igta); }
    }

    private Line toLine(PosRequisitionDtl d, Source src, Pricing pricing) {
        BigDecimal boxes = d.getRequestedQty() == null ? BigDecimal.ZERO : d.getRequestedQty();
        Pricing pr = pricing == null ? Pricing.NONE : pricing;

        // Priced the way the purchase flow prices, so the total shown here is
        // the total that gets created. Falls back to the estimate the
        // requisition was raised on only for a product with no trade price.
        BigDecimal unitCost = pr.perBox();
        if (unitCost.signum() <= 0) unitCost = d.getEstimatedUnitCost();
        if (unitCost == null) unitCost = BigDecimal.ZERO;

        // Kept separate: evidence about the price, not the price.
        BigDecimal lastPaid = src == null || src.ratePerPcs() == null ? null
                : src.ratePerPcs().multiply(BigDecimal.valueOf(pr.pcsPerBox()))
                     .setScale(2, RoundingMode.HALF_UP);

        return Line.builder()
                .reqLineId(d.getPidReqDtl())
                .productId(d.getPidProduct())
                .productName(d.getProductName())
                .companyId(d.getPidCompany())
                .companyName(d.getCompanyName())
                .boxes(boxes)
                .unit(d.getUnit() == null ? "BOX" : d.getUnit())
                .unitCost(unitCost.setScale(2, RoundingMode.HALF_UP))
                .lineTotal(unitCost.multiply(boxes).setScale(2, RoundingMode.HALF_UP))
                .lastPaidPerBox(lastPaid)
                .supplierId(src == null ? null : src.supplierId())
                .supplierName(src == null ? null : src.supplierName())
                .basis(src == null ? "NONE" : src.basis())
                .lastPurchasedOn(src == null ? null : src.lastPurchasedOn())
                .priority(d.getPriority())
                .build();
    }

    private Map<Long, Pricing> pricingFor(List<PosRequisitionDtl> lines) {
        Set<Long> ids = lines.stream().map(PosRequisitionDtl::getPidProduct)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        Map<Long, Pricing> out = new HashMap<>();
        for (Object[] r : sourcingRepo.boxPricingFor(ids)) {
            out.put(lng(r[0]), new Pricing(
                    Math.max(1, dec(r[1]).intValue()), dec(r[2]), dec(r[3])));
        }
        return out;
    }

    private PosRequisitionMst requisition(Long id) {
        PosRequisitionMst req = reqRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Requisition", id));
        Long orgId = SecurityUtils.getCurrentOrgId();
        if (orgId != null && req.getPidOrg() != null && !orgId.equals(req.getPidOrg())) {
            throw new ResourceNotFoundException("Requisition", id);
        }
        return req;
    }

    private static String clip(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }
    private static Long lng(Object o)   { return o == null ? null : ((Number) o).longValue(); }
    private static String str(Object o) { return o == null ? null : o.toString(); }
    private static BigDecimal dec(Object o) {
        return o == null ? null : o instanceof BigDecimal b ? b : new BigDecimal(o.toString());
    }
    private static LocalDate date(Object o) {
        return o == null ? null
             : o instanceof java.sql.Date d ? d.toLocalDate()
             : o instanceof LocalDate ld ? ld
             : LocalDate.parse(o.toString().substring(0, 10));
    }
}
