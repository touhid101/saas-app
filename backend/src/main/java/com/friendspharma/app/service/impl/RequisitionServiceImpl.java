package com.friendspharma.app.service.impl;

import com.friendspharma.app.dto.request.RequisitionRequest;
import com.friendspharma.app.dto.response.RequisitionResponse;
import com.friendspharma.app.entity.PosRequisitionDtl;
import com.friendspharma.app.entity.PosRequisitionMst;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ValidationException;
import org.springframework.http.HttpStatus;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.security.SecurityUtils;
import com.friendspharma.app.repository.PosRequisitionDtlRepository;
import com.friendspharma.app.entity.PosCompanyInfo;
import com.friendspharma.app.entity.PosProductInfo;
import com.friendspharma.app.repository.PosCompanyInfoRepository;
import com.friendspharma.app.repository.PosProductInfoRepository;
import com.friendspharma.app.repository.PosRequisitionMstRepository;
import com.friendspharma.app.repository.RequisitionSourcingRepository;
import com.friendspharma.app.service.interfaces.RequisitionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RequisitionServiceImpl implements RequisitionService {

    private static final Set<String> EDITABLE_STAGES = Set.of("DRAFT", "SUBMITTED");
    private static final DateTimeFormatter REF_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final PosRequisitionMstRepository mstRepo;
    private final PosRequisitionDtlRepository dtlRepo;
    private final PermissionGuard permissions;
    private final BranchScopeService branchScope;
    private final PosProductInfoRepository productRepo;
    private final PosCompanyInfoRepository companyRepo;
    /** Live stock, sales and box pricing for the lines — the split prices from the same source. */
    private final RequisitionSourcingRepository sourcing;

    /** Requisition — the module whose Approve tick governs approving and rejecting. */
    private static final String MODULE = "MOD11";

    // ── Create ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public RequisitionResponse.RequisitionDto create(
            RequisitionRequest.CreateRequisitionRequest req, Long userId) {
        return doCreate(req, userId, "MANUAL");
    }

    @Override
    @Transactional
    public RequisitionResponse.RequisitionDto createFromAi(
            RequisitionRequest.CreateRequisitionRequest req, Long userId) {
        return doCreate(req, userId, "AI");
    }

    private RequisitionResponse.RequisitionDto doCreate(
            RequisitionRequest.CreateRequisitionRequest req, Long userId, String source) {

        LocalDate date = req.getReqDate() != null ? req.getReqDate() : LocalDate.now();

        /* Tenancy comes from the session, not the body.
         *
         * Both ids were read straight off the request, so a branch user could
         * file a requisition into another pharmacy by changing a number — and
         * it would then appear on that pharmacy's approval queue. resolve()
         * returns the caller's own branch when none is named, honours a named
         * one only for an administrator, and 404s anybody else. The Copilot
         * path never had this hole because it builds its own row and sets both
         * from the session; this is the manual path catching up. */
        Long branchId = branchScope.resolve(req.getPidBranch());
        Long orgId = SecurityUtils.getCurrentOrgId();

        PosRequisitionMst mst = PosRequisitionMst.builder()
                .requisitionNo(generateReqNo(orgId, date))
                .pidOrg(orgId)
                .pidBranch(branchId)
                .reqDate(date)
                .stage("DRAFT")
                .source(source)
                .pidCreatedBy(userId)
                .remarks(req.getRemarks())
                .build();

        Map<Long, PosProductInfo> products = productsFor(req.getLines(), branchId);
        req.getLines().forEach(l -> mst.getDetails().add(buildLine(mst, l, products)));
        snapshotLines(mst);
        return toDto(mstRepo.save(mst));
    }

    // ── Update ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public RequisitionResponse.RequisitionDto update(Long pidRequisition,
            RequisitionRequest.UpdateRequisitionRequest req) {

        PosRequisitionMst mst = getEditable(pidRequisition);
        if (req.getRemarks() != null) mst.setRemarks(req.getRemarks());

        if (req.getLines() != null) {
            mst.getDetails().clear();
            Map<Long, PosProductInfo> products = productsFor(req.getLines(), mst.getPidBranch());
            req.getLines().forEach(l -> mst.getDetails().add(buildLine(mst, l, products)));
            snapshotLines(mst);
        }
        return toDto(mstRepo.save(mst));
    }

    @Override
    @Transactional
    public RequisitionResponse.RequisitionDto updateLineQty(Long pidRequisition, Long pidReqDtl,
            RequisitionRequest.UpdateLineQtyRequest req) {

        PosRequisitionMst mst = getMst(pidRequisition);
        // Allow qty update in DRAFT / SUBMITTED / PENDING_APPROVAL (approval modal)
        if (Set.of("APPROVED","CONVERTED","CANCELLED","REJECTED").contains(mst.getStage())) {
            throw new BusinessException("Cannot edit lines in stage " + mst.getStage());
        }
        PosRequisitionDtl dtl = mst.getDetails().stream()
                .filter(d -> d.getPidReqDtl().equals(pidReqDtl))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("RequisitionLine", pidReqDtl));

        dtl.setRequestedQty(req.getRequestedQty());
        if (req.getRemarks() != null) dtl.setRemarks(req.getRemarks());
        return toDto(mstRepo.save(mst));
    }

    /**
     * Takes a line off a requisition that has not been approved yet.
     *
     * <p><b>Why it is bounded by the same stages as a quantity change.</b> Both
     * are the same act — deciding what this requisition asks for — and a
     * quantity of zero is how somebody removes a line without this. Once the
     * requisition is approved the list is what was agreed to, and orders are
     * split from it; removing a line then would change an agreement after the
     * fact, so the stage check refuses it and the screen hides the control.
     *
     * <p><b>The last line cannot go.</b> A requisition asking for nothing is not
     * a requisition — it would pass through approval and produce no order, with
     * nothing on screen to explain why. Cancel it instead, which is a different
     * verb with its own audit trail.
     */
    @Override
    @Transactional
    public RequisitionResponse.RequisitionDto removeLine(Long pidRequisition, Long pidReqDtl) {
        PosRequisitionMst mst = getMst(pidRequisition);

        if (Set.of("APPROVED", "CONVERTED", "CANCELLED", "REJECTED").contains(mst.getStage())) {
            throw new BusinessException(
                    "This requisition is " + mst.getStage().toLowerCase().replace('_', ' ')
                    + ", so its lines can no longer be changed.");
        }

        PosRequisitionDtl dtl = mst.getDetails().stream()
                .filter(d -> d.getPidReqDtl().equals(pidReqDtl))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("RequisitionLine", pidReqDtl));

        if (mst.getDetails().size() <= 1) {
            throw new BusinessException(
                    "This is the only item on the requisition. Cancel the requisition instead of "
                    + "emptying it.");
        }

        // orphanRemoval on the mapping turns this into the DELETE.
        mst.getDetails().remove(dtl);

        log.info("Requisition {}: line {} ({}) removed in stage {}",
                pidRequisition, pidReqDtl, dtl.getPidProduct(), mst.getStage());
        return toDto(mstRepo.save(mst));
    }

    // ── Workflow transitions ───────────────────────────────────────────────

    @Override
    @Transactional
    public RequisitionResponse.RequisitionDto submit(Long pidRequisition) {
        PosRequisitionMst mst = getMst(pidRequisition);
        requireStage(mst, "DRAFT");
        mst.setStage("SUBMITTED");
        return toDto(mstRepo.save(mst));
    }

    @Override
    @Transactional
    public RequisitionResponse.RequisitionDto pendingApproval(Long pidRequisition) {
        PosRequisitionMst mst = getMst(pidRequisition);
        requireStage(mst, "SUBMITTED");
        mst.setStage("PENDING_APPROVAL");
        return toDto(mstRepo.save(mst));
    }

    @Override
    @Transactional
    public RequisitionResponse.RequisitionDto approve(Long pidRequisition,
            RequisitionRequest.ApproveRequest req, Long approverId) {
        requireApproveAuthority("approve");
        PosRequisitionMst mst = getMst(pidRequisition);
        requireStage(mst, "PENDING_APPROVAL");
        mst.setStage("APPROVED");
        mst.setPidApprovedBy(approverId);
        mst.setApprovedAt(LocalDateTime.now());
        if (req.getRemarks() != null) mst.setRemarks(req.getRemarks());
        return toDto(mstRepo.save(mst));
    }

    @Override
    @Transactional
    public RequisitionResponse.RequisitionDto reject(Long pidRequisition,
            RequisitionRequest.RejectRequest req, Long approverId) {
        requireApproveAuthority("reject");
        PosRequisitionMst mst = getMst(pidRequisition);
        if (!Set.of("SUBMITTED","PENDING_APPROVAL").contains(mst.getStage())) {
            throw new BusinessException("Cannot reject a requisition in stage " + mst.getStage());
        }
        mst.setStage("REJECTED");
        mst.setPidApprovedBy(approverId);
        mst.setApprovedAt(LocalDateTime.now());
        mst.setRejectionReason(req.getReason());
        return toDto(mstRepo.save(mst));
    }

    @Override
    @Transactional
    public RequisitionResponse.RequisitionDto convert(Long pidRequisition, Long pidPurchaseOrder) {
        requireApproveAuthority("convert");
        PosRequisitionMst mst = getMst(pidRequisition);
        requireStage(mst, "APPROVED");
        mst.setStage("CONVERTED");
        mst.setPidPurchaseOrder(pidPurchaseOrder);
        return toDto(mstRepo.save(mst));
    }

    @Override
    @Transactional
    public void cancel(Long pidRequisition, String reason) {
        PosRequisitionMst mst = getMst(pidRequisition);
        if (Set.of("APPROVED","CONVERTED").contains(mst.getStage())) {
            throw new BusinessException("Cannot cancel an approved/converted requisition");
        }
        mst.setStage("CANCELLED");
        mst.setRejectionReason(reason);
        mstRepo.save(mst);
    }

    // ── Queries ────────────────────────────────────────────────────────────

    @Override
    /** Read-only transactional: the DTO walks the lines, which are lazy. */
    @Transactional(readOnly = true)
    public RequisitionResponse.RequisitionDto getById(Long pidRequisition) {
        return toDto(getMst(pidRequisition));
    }

    /**
     * <p>Read-only transactional so the line count can be taken. The summary
     * quotes how many lines a requisition has, which touches a lazy collection;
     * without a session open that threw LazyInitializationException and the
     * whole list came back a 500. Nothing noticed because nothing called it —
     * the screen was running on mock data.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<RequisitionResponse.RequisitionSummaryDto> list(Long orgId, Long branchId, String stage,
            LocalDate from, LocalDate to, Pageable pageable) {

        return mstRepo.search(orgId, branchId, stage, from, to, pageable).map(this::toSummary);
    }

    @Override
    public long countPendingApproval(Long branchId) {
        return mstRepo.countByBranchAndStage(
                SecurityUtils.getCurrentOrgId(), branchId, "PENDING_APPROVAL");
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private PosRequisitionMst getMst(Long id) {
        return mstRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Requisition", id));
    }

    private PosRequisitionMst getEditable(Long id) {
        PosRequisitionMst mst = getMst(id);
        if (!EDITABLE_STAGES.contains(mst.getStage())) {
            throw new BusinessException("Requisition cannot be edited in stage " + mst.getStage());
        }
        return mst;
    }

    /**
     * Approving and rejecting are whatever the permission matrix says they are.
     *
     * <p>This used to be {@code @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")}
     * on the endpoints, which was wrong twice over. {@code MANAGER} is granted to
     * nobody — no code path ever issues that authority — and {@code ADMIN} is
     * issued only to Super Admin. So an Administrator (Branch) holding CAN_APPROVE
     * on Requisition saw the Approve and Reject buttons, because the screen reads
     * the matrix, and then got 403, because the server did not.
     *
     * <p>Ticking Approve on Requisition now grants both, for any role, with no
     * code change — and the screen and the server read the same thing.
     *
     * <p>Checked here rather than on the endpoint so it holds for every route
     * into an approval, including any added later.
     */
    private void requireApproveAuthority(String action) {
        if (!permissions.has(MODULE, PermissionGuard.Right.APPROVE)) {
            throw new BusinessException(
                    "You do not have permission to " + action + " requisitions. "
                    + "Ask somebody whose role holds Approve on Requisition.",
                    HttpStatus.FORBIDDEN, "APPROVE_NOT_PERMITTED");
        }
    }

    private void requireStage(PosRequisitionMst mst, String expected) {
        if (!expected.equals(mst.getStage())) {
            throw new BusinessException(
                    "Requisition must be in " + expected + " state (current: " + mst.getStage() + ")");
        }
    }

    /**
     * One line, with the product's name and maker copied onto it.
     *
     * <p>PRODUCT_NAME and COMPANY_NAME are denormalised on the line on purpose:
     * a requisition is a record of what was asked for at the time, and renaming
     * a product afterwards must not rewrite an order somebody already approved.
     *
     * <p>The Copilot has always filled them. This path did not, so every
     * manually raised line came back with a null product name and the screen had
     * nothing to show where the medicine should be. The lookup is one query for
     * the whole requisition, not one per line.
     */
    private PosRequisitionDtl buildLine(PosRequisitionMst mst,
            RequisitionRequest.RequisitionLineRequest l,
            Map<Long, PosProductInfo> products) {

        PosProductInfo p = products.get(l.getPidProduct());
        if (p == null) {
            throw new ValidationException(
                    "Product " + l.getPidProduct() + " is not in this pharmacy's list.");
        }

        return PosRequisitionDtl.builder()
                .requisition(mst)
                .pidProduct(l.getPidProduct())
                .productName(p.getProductName())
                .pidCompany(p.getPidCompany())
                .companyName(companyName(p.getPidCompany()))
                .requestedQty(l.getRequestedQty())
                .unit(l.getUnit() != null ? l.getUnit() : "BOX")
                .estimatedUnitCost(l.getEstimatedUnitCost())
                .priority(l.getPriority() != null ? l.getPriority() : "MEDIUM")
                .remarks(l.getRemarks())
                .build();
    }

    /**
     * Fills in what a manually raised line did not carry: the stock and sales
     * it was raised against, and an estimate on the purchase-order basis.
     *
     * <p>The Copilot always recorded these; the manual path saved nulls, so the
     * screen showed a blank stock and "0 pcs" a month for every hand-raised
     * line. The estimate defaults to trade price + IGTA per box — the figure
     * the split prices with — so a requisition's total and its split agree
     * unless somebody typed a different estimate on purpose.
     */
    private void snapshotLines(PosRequisitionMst mst) {
        Set<Long> ids = mst.getDetails().stream().map(PosRequisitionDtl::getPidProduct).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) return;
        Map<Long, BigDecimal[]> live = liveFor(mst.getPidBranch(), ids);
        Map<Long, BigDecimal> cost = costNowFor(ids);
        for (PosRequisitionDtl d : mst.getDetails()) {
            BigDecimal[] l = live.get(d.getPidProduct());
            if (d.getCurrentStock() == null && l != null) d.setCurrentStock(l[0]);
            if (d.getAvgMonthlySales() == null && l != null) d.setAvgMonthlySales(l[1]);
            if (d.getEstimatedUnitCost() == null || d.getEstimatedUnitCost().signum() <= 0) d.setEstimatedUnitCost(cost.get(d.getPidProduct()));
        }
    }

    /** productId → [stockPcs, pcs30] for the branch. */
    private Map<Long, BigDecimal[]> liveFor(Long branchId, Set<Long> ids) {
        Map<Long, BigDecimal[]> out = new java.util.HashMap<>();
        if (branchId == null || ids.isEmpty()) return out;
        for (Object[] r : sourcing.liveStockAndSales(branchId, ids)) out.put(((Number) r[0]).longValue(), new BigDecimal[]{ num(r[1]), num(r[2]) });
        return out;
    }

    /** productId → trade price + IGTA per box, the purchase-order basis. */
    private Map<Long, BigDecimal> costNowFor(Set<Long> ids) {
        Map<Long, BigDecimal> out = new java.util.HashMap<>();
        if (ids.isEmpty()) return out;
        for (Object[] r : sourcing.boxPricingFor(ids)) out.put(((Number) r[0]).longValue(), num(r[2]).add(num(r[3])));
        return out;
    }

    private static BigDecimal num(Object o) { return o == null ? BigDecimal.ZERO : o instanceof BigDecimal b ? b : new BigDecimal(o.toString()); }

    /**
     * The products named on a request, fetched once and checked against the
     * caller's branch.
     *
     * <p>The branch check is not decoration: without it a line could name a
     * product belonging to another pharmacy, and the requisition would carry a
     * medicine this branch cannot buy.
     */
    private Map<Long, PosProductInfo> productsFor(
            List<RequisitionRequest.RequisitionLineRequest> lines, Long branchId) {

        List<Long> ids = lines.stream()
                .map(RequisitionRequest.RequisitionLineRequest::getPidProduct)
                .filter(java.util.Objects::nonNull).distinct().toList();

        return productRepo.findAllById(ids).stream()
                .filter(p -> p.getPidBranch() == null || p.getPidBranch().equals(branchId))
                .collect(Collectors.toMap(PosProductInfo::getPidProduct, p -> p, (a, b) -> a));
    }

    /** The maker's name, or null where the product has none recorded. */
    private String companyName(Long pidCompany) {
        if (pidCompany == null) return null;
        return companyRepo.findById(pidCompany)
                .map(PosCompanyInfo::getCompanyName)
                .orElse(null);
    }

    /**
     * The next REQ-yyyyMMdd-0001.
     *
     * <p>Only the trailing counter is parsed. Stripping every non-digit from
     * "REQ-20260824-0001" gives "202608240001", which does not fit in an int —
     * the parse threw, the exception was swallowed, and the sequence reset to 1
     * on every call. The second requisition of any day then collided with the
     * first on the unique index.
     */
    private String generateReqNo(Long orgId, LocalDate date) {
        return "REQ-" + date.format(REF_FMT) + "-"
               + String.format("%04d", nextSequence(mstRepo.findLastRequisitionNo(orgId)));
    }

    /** Package-private so the split service issues numbers the same way. */
    static int nextSequence(String lastRequisitionNo) {
        if (lastRequisitionNo == null || lastRequisitionNo.isBlank()) return 1;
        int dash = lastRequisitionNo.lastIndexOf('-');
        String tail = dash >= 0 ? lastRequisitionNo.substring(dash + 1) : lastRequisitionNo;
        try {
            return Integer.parseInt(tail.trim()) + 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private RequisitionResponse.RequisitionDto toDto(PosRequisitionMst m) {
        Set<Long> ids = m.getDetails().stream().map(PosRequisitionDtl::getPidProduct).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, BigDecimal[]> live = liveFor(m.getPidBranch(), ids);
        Map<Long, BigDecimal> costNow = costNowFor(ids);
        BigDecimal totalNow = BigDecimal.ZERO, totalRaised = BigDecimal.ZERO;
        for (PosRequisitionDtl d : m.getDetails()) {
            BigDecimal qty = d.getRequestedQty() == null ? BigDecimal.ZERO : d.getRequestedQty();
            BigDecimal now = costNow.get(d.getPidProduct());
            if (now == null || now.signum() <= 0) now = d.getEstimatedUnitCost() == null ? BigDecimal.ZERO : d.getEstimatedUnitCost();
            totalNow = totalNow.add(qty.multiply(now));
            if (d.getEstimatedUnitCost() != null) totalRaised = totalRaised.add(qty.multiply(d.getEstimatedUnitCost()));
        }
        List<RequisitionResponse.RequisitionLineDto> lines = m.getDetails().stream()
                .map(d -> RequisitionResponse.RequisitionLineDto.builder()
                        .pidReqDtl(d.getPidReqDtl())
                        .pidProduct(d.getPidProduct())
                        .productName(d.getProductName())
                        .pidCompany(d.getPidCompany())
                        .companyName(d.getCompanyName())
                        .aiRecommendedQty(d.getAiRecommendedQty())
                        .requestedQty(d.getRequestedQty())
                        .unit(d.getUnit())
                        .estimatedUnitCost(d.getEstimatedUnitCost())
                        .priority(d.getPriority())
                        .remarks(d.getRemarks())
                        .currentStock(live.containsKey(d.getPidProduct()) ? live.get(d.getPidProduct())[0] : d.getCurrentStock())
                        .avgMonthlySales(live.containsKey(d.getPidProduct()) ? live.get(d.getPidProduct())[1] : d.getAvgMonthlySales())
                        .stockAtRaise(d.getCurrentStock())
                        .salesAtRaise(d.getAvgMonthlySales())
                        .unitCostNow(costNow.get(d.getPidProduct()) != null && costNow.get(d.getPidProduct()).signum() > 0 ? costNow.get(d.getPidProduct()) : d.getEstimatedUnitCost())
                        .build())
                .collect(Collectors.toList());

        return RequisitionResponse.RequisitionDto.builder()
                .pidRequisition(m.getPidRequisition())
                .requisitionNo(m.getRequisitionNo())
                .pidOrg(m.getPidOrg())
                .pidBranch(m.getPidBranch())
                .reqDate(m.getReqDate())
                .stage(m.getStage())
                .estimatedTotal(totalNow.setScale(2, java.math.RoundingMode.HALF_UP))
                .estimatedTotalAtRaise(totalRaised.setScale(2, java.math.RoundingMode.HALF_UP))
                .source(m.getSource())
                .pidCreatedBy(m.getPidCreatedBy())
                .pidApprovedBy(m.getPidApprovedBy())
                .approvedAt(m.getApprovedAt())
                .remarks(m.getRemarks())
                .rejectionReason(m.getRejectionReason())
                .createdAt(m.getCreatedAt())
                .updatedAt(m.getUpdatedAt())
                .lines(lines)
                .build();
    }

    private RequisitionResponse.RequisitionSummaryDto toSummary(PosRequisitionMst m) {
        return RequisitionResponse.RequisitionSummaryDto.builder()
                .pidRequisition(m.getPidRequisition())
                .requisitionNo(m.getRequisitionNo())
                .reqDate(m.getReqDate())
                .stage(m.getStage())
                .source(m.getSource())
                .lineCount(m.getDetails().size())
                .createdAt(m.getCreatedAt())
                .build();
    }
}
