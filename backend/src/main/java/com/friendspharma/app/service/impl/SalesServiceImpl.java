package com.friendspharma.app.service.impl;

import static com.friendspharma.app.util.DateRange.since;
import static com.friendspharma.app.util.DateRange.until;
import com.friendspharma.app.dto.request.SalesRequest;
import com.friendspharma.app.dto.response.SalesPricingResponse;
import com.friendspharma.app.dto.response.StockResponses.StockAllocation;
import com.friendspharma.app.dto.response.DeliveryPerformanceResponse;
import com.friendspharma.app.dto.response.DeliverySummaryResponse;
import com.friendspharma.app.dto.response.TransactionResponse;
import com.friendspharma.app.entity.HrEmployee;
import com.friendspharma.app.entity.PosCustomerInfo;
import com.friendspharma.app.entity.PosProductInfo;
import com.friendspharma.app.entity.PosTransactionDtl;
import com.friendspharma.app.entity.PosTransactionMst;
import com.friendspharma.app.exception.custom.*;
import com.friendspharma.app.repository.*;
import com.friendspharma.app.security.SecurityUtils;
import com.friendspharma.app.service.interfaces.SaleSourceReferences;
import com.friendspharma.app.service.interfaces.SalesDiscountService;
import com.friendspharma.app.service.interfaces.StockPostingService;
import com.friendspharma.app.service.interfaces.StockService;
import com.friendspharma.app.util.CustomerPricing;
import com.friendspharma.app.util.SalesPriceCalculator;
import com.friendspharma.app.util.StockUnitConverter;
import com.friendspharma.app.util.TransactionCalculator;
import com.friendspharma.app.util.TransactionCalculator.InvoiceTotals;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.friendspharma.app.service.impl.accounting.AccountingHooks;

import java.util.stream.Collectors;

/**
 * Sales service — batch-wise FEFO allocation against branch stock.
 *
 * Workflow:
 *  1. Validate customer, products, stock
 *  2. Build POS_TRANSACTION_MST (status=Y, stage=Submitted)
 *  3. For each line: allocate batch-wise FEFO, write one DTL row per batch consumed
 *  4. Post the movements to stock, then update invoice totals on MST
 *  5. Return response
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SalesServiceImpl {

    private final PosTransactionMstRepository mstRepo;
    private final PosTransactionDtlRepository dtlRepo;
    private final PosCustomerInfoRepository customerRepo;
    private final PosProductInfoRepository productRepo;
    private final StockService            stockService;
    private final StockPostingService stockPostingService;
    private final InvoiceUnlockServiceImpl invoiceUnlock;
    private final PosBoxInfoRepository    boxRepo;
    private final PosLeafInfoRepository   leafRepo;
    private final SalesDiscountService discountService;
    /** The one place that decides who may be handed an invoice to deliver. */
    private final DeliveryManService   deliveryManService;
    private final HrEmployeeRepository employeeRepo;
    private final BranchScopeService   branchScope;
    /** Accounts: every financial event this service commits is booked through here, in the same transaction. */
    private final AccountingHooks accounting;
    /** Cash taken on the doorstep: read before a cancellation, never written here. */
    private final PosDeliveryCollectionRepository collectionRepo;
    /** Which invoices are marketplace orders, and under what number. Answered outside this module. */
    private final SaleSourceReferences sourceRefs;
    /** Says what happened to a sale, without knowing who is interested. [Phase 10] */
    private final org.springframework.context.ApplicationEventPublisher events;

    private static final String STAGE_SUBMITTED = "Submitted";

    /**
     * Money in hand, nothing left to go back for.
     *
     * <p>The end of the line for a sale: collect() has always moved an invoice
     * here once the last payment settles it. A counter sale reaches the same
     * state the instant it is rung up, which is why it is set at creation too.
     */
    private static final String STAGE_COLLECTED = "Collected";

    /**
     * Goods handed over, money still owed.
     *
     * <p>Where a till sale lands when it is not settled at the counter. Taken
     * from {@link #STAGES} rather than spelled again, so the two cannot drift.
     */
    private static final String STAGE_DELIVERED = "Delivered";

    /** The stages an order moves through, in order. */
    private static final List<String> STAGES = List.of(
            "Submitted", "Confirmed", "Delivery Assigned", "On The Way", "Delivered", "Collected");

    /**
     * Who the sale is being written for, when the caller is not a staff session.
     *
     * <p>Everything a till sale reads off the signed-in user — the branch, the
     * organisation, the company — and the one word that says where the sale came
     * from. The e-commerce checkout has a customer on the request and no staff
     * user at all, so it states this instead of the session stating it.
     *
     * @param source what goes in {@code FROM_SOURCE}: {@code API} for the till,
     *               {@code ECOM} for an order placed on the marketplace
     */
    public record SaleContext(Long branchId, Long orgId, Long companyId, String source) {
        public static final String SOURCE_API  = "API";
        public static final String SOURCE_ECOM = "ECOM";
    }

    /**
     * A sale made by a signed-in member of staff — the till, unchanged.
     *
     * <p>The context comes from the session exactly as it always did: the branch
     * through {@link BranchScopeService} (so the acting branch wins and a branch
     * id in the body cannot), the org and company from the principal.
     */
    @Transactional
    public TransactionResponse createSale(SalesRequest req) {
        Long branchId = branchScope.resolve(req.getBranchId());
        if (branchId == null) {
            throw new BusinessException(
                    "Your user account has no branch assigned, so a sale cannot be recorded against one. "
                    + "Ask an administrator to set your branch in RBAC › Users.",
                    HttpStatus.CONFLICT, "NO_BRANCH_CONTEXT");
        }
        return createSale(req, new SaleContext(branchId, SecurityUtils.getCurrentOrgId(),
                SecurityUtils.getCurrentCompanyId(), SaleContext.SOURCE_API));
    }

    /**
     * The one place a sale is written, whoever asked for it.
     *
     * <p>Pricing, tier eligibility, FEFO allocation, the batch-level detail rows,
     * the stock movements and the ledger posting are the same for a till sale and
     * an online order, because they are this method. What differs is only the
     * context above — and a caller that is not staff must have been checked by
     * whoever built that context, since nothing here can re-derive it.
     */
    @Transactional
    public TransactionResponse createSale(SalesRequest req, SaleContext ctx) {
        // Validate customer
        var customer = customerRepo.findById(req.getCustomerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer", req.getCustomerId()));
        if (!"Y".equals(customer.getStatus()))
            throw new InactiveEntityException("Customer", customer.getCustomerName());

        /* The branch the sale is made from. Stock is held per branch, so a sale
           that does not know its branch cannot know whether it has the goods.

           For a till sale this was resolved from the session by the caller above,
           through BranchScopeService — the acting branch wins, and a branch id in
           the request body cannot decide it. Reading it straight off the session
           was wrong twice: it ignored the acting branch, so an invoice raised
           while supporting a pharmacy was written into the operator's own branch
           and then could not be found by the list, which does honour it — and it
           took branchId from the request body unchecked, which let a caller file
           a sale against another pharmacy.

           For an online order the checkout states it, because the cart already
           belongs to one pharmacy and there is no staff session to ask. */
        Long branchId = ctx.branchId();
        if (branchId == null) {
            throw new BusinessException(
                    "This sale has no branch, so the goods it consumes could not be found.",
                    HttpStatus.CONFLICT, "NO_BRANCH_CONTEXT");
        }

        // The customer's tier decides both which units may be sold and which
        // price list applies. Resolved once, before any line is priced, so a
        // single invoice cannot mix tiers.
        CustomerPricing.Tier tier = CustomerPricing.tierOf(customer.getCustomerType());

        // Product state, tier eligibility and branch stock — all refused
        // server-side, not merely hidden in the UI. A retailer cannot buy a box
        // however the request was constructed, and a line with no price for its
        // tier is refused rather than quietly falling back to MRP.
        validateLines(req, tier, branchId, customer.getCustomerName());

        Long orgId = ctx.orgId();
        String invoiceNo = "SL" + LocalDate.now().toString().replace("-","").substring(2,6)
                + String.format("%05d", (int)(Math.random()*99999));

        // Create MST first (FEFO procedure needs the MST ID)
        PosTransactionMst mst = new PosTransactionMst();
        mst.setPidOrg(orgId);
        // PID_COMPANY was never set here, which left every sale with a null
        // company. V7 makes the column NOT NULL, so it is taken from the
        // session like the org is.
        mst.setPidCompany(ctx.companyId());
        // The branch already resolved and validated above. Re-deriving it here
        // would let the header and the stock it consumed disagree.
        mst.setPidBranch(branchId);
        mst.setPidSupCust(req.getCustomerId());
        mst.setTransactionDate(req.getTransactionDate());
        mst.setTransactionType("SL");
        mst.setInvoiceNo(invoiceNo);
        mst.setPaymentType(req.getPaymentType());
        mst.setDeliveryAddress(req.getDeliveryAddress());
        mst.setDeliveryCharge(req.getDeliveryCharge());
        mst.setInvoiceDisPct(req.getInvoiceDiscPct());
        mst.setGivenAmount(req.getGivenAmount());
        mst.setRemarks(req.getRemarks());
        mst.setStage(STAGE_SUBMITTED);
        mst.setStatus("Y");
        mst.setFromSource(ctx.source());

        if (req.getDeliveryManId() != null) {
            // Same check the assignment endpoints run: an id that is not an
            // active delivery man is refused here rather than written and
            // discovered later by whoever goes looking for the goods.
            var dm = deliveryManService.requireDeliveryMan(req.getDeliveryManId());
            mst.setDeliveryMan(dm.getPidEmployee());
            mst.setDeliveryManName(dm.getEmpName());
        }

        PosTransactionMst saved = mstRepo.save(mst);
        Long mstId = saved.getPidTranMst();

        writeLinesAndPost(mstId, req, tier, branchId, orgId);
        List<PosTransactionDtl> dtls = applyInvoiceTotals(saved, req);
        accounting.saleCreated(saved);

        log.info("Sale created: {} customer={} stage={}", invoiceNo, customer.getCustomerName(), STAGE_SUBMITTED);
        return buildResponse(saved, dtls, null, customer.getCustomerName());
    }

    /* ══════════════════════════════════════════════════════════════════════
       UPDATE — only while the invoice is still a draft
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * Rewrites an unconfirmed invoice.
     *
     * <p>Editable only at {@code Submitted}. Once confirmed the goods are on
     * their way to a customer and the stock movement is a fact — changing the
     * invoice then would leave the ledger describing quantities that never left
     * the shelf. A confirmed invoice is corrected with a sales return.
     *
     * <p>The lines are replaced wholesale rather than diffed: the client sends
     * the invoice as it should now be. That means the previous allocation is
     * reversed first, putting every consumed piece back in the lot it came from,
     * before the new lines are allocated. Editing without reversing would leak
     * stock on every save.
     */
    /**
     * A supervisor authorises one edit of one invoice.
     *
     * <p>Refuses outright once the invoice has moved past Submitted: no
     * authority reopens a confirmed sale, because the stock has already left
     * and the correction is a return. Unlocking one would offer a way round
     * that which the edit itself would then refuse anyway.
     */
    @Transactional
    public String unlockForEdit(Long id, com.friendspharma.app.dto.request.InvoiceUnlockRequest req) {
        PosTransactionMst mst = mstRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sale", id));
        Long orgId = SecurityUtils.getCurrentOrgId();
        if (orgId != null && mst.getPidOrg() != null && !orgId.equals(mst.getPidOrg())) {
            throw new ResourceNotFoundException("Sale", id);
        }
        if (!STAGE_SUBMITTED.equalsIgnoreCase(mst.getStage())) {
            throw new BusinessException(
                    "Invoice " + mst.getInvoiceNo() + " is at stage '" + mst.getStage()
                    + "' and cannot be reopened by anyone. Raise a sales return to correct it.",
                    HttpStatus.CONFLICT, "SALE_ALREADY_CONFIRMED");
        }
        return invoiceUnlock.authorise(mst, req.getUsername(), req.getPassword(), req.getReason());
    }

    @Transactional
    public TransactionResponse updateSale(Long id, SalesRequest req) {
        return updateSale(id, req, null);
    }

    /**
     * Edits an invoice that is still at Submitted.
     *
     * @param unlockToken a supervisor's authorisation, when the caller does not
     *                    hold the edit right themselves. Checked here rather
     *                    than in the controller so every route into an edit
     *                    passes the same gate.
     */
    @Transactional
    public TransactionResponse updateSale(Long id, SalesRequest req, String unlockToken) {
        PosTransactionMst mst = mstRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sale", id));

        if (!"SL".equals(mst.getTransactionType())) {
            throw new BusinessException("Invoice " + mst.getInvoiceNo() + " is not a sale.",
                    HttpStatus.BAD_REQUEST, "NOT_A_SALE");
        }
        if (!STAGE_SUBMITTED.equalsIgnoreCase(mst.getStage())) {
            throw new BusinessException(
                    "Invoice " + mst.getInvoiceNo() + " is at stage '" + mst.getStage()
                    + "' and can no longer be edited. Raise a sales return to correct it.",
                    HttpStatus.CONFLICT, "SALE_ALREADY_CONFIRMED");
        }

        // A merge is composed of the orders inside it, and its lines are copies
        // of theirs. Editing it directly would reprice and repost a document
        // that owns nothing — issuing stock the orders have already issued.
        if (MERGE_FLAG.equals(mst.getIsMergeInvoice())) {
            throw new BusinessException(
                    "Invoice " + mst.getInvoiceNo() + " is a merged invoice. Change the orders "
                    + "inside it, or split the merge first.",
                    HttpStatus.CONFLICT, "MERGE_NOT_EDITABLE");
        }

        // A merged order is delivered and collected under its merge. Editing it
        // in place would change what that merge is worth without the merge
        // knowing, so the correction is to split the merge first.
        if (mst.getMergePidTranMst() != null) {
            throw new BusinessException(
                    "Invoice " + mst.getInvoiceNo() + " has been merged and is now read-only. "
                    + "Split the merge first if it needs changing.",
                    HttpStatus.CONFLICT, "ORDER_MERGED");
        }

        // Saved is saved. Changing somebody else's invoice takes either the
        // right to do so or a supervisor's say-so, and this consumes the
        // authorisation if that is what is permitting it.
        invoiceUnlock.requireEditRight(mst, unlockToken);

        var customer = customerRepo.findById(req.getCustomerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer", req.getCustomerId()));
        if (!"Y".equals(customer.getStatus()))
            throw new InactiveEntityException("Customer", customer.getCustomerName());

        Long orgId = SecurityUtils.getCurrentOrgId();
        Long branchId = mst.getPidBranch();
        CustomerPricing.Tier tier = CustomerPricing.tierOf(customer.getCustomerType());

        // Put the old allocation back before validating the new one — otherwise
        // the stock check sees the pieces this very invoice is holding as
        // unavailable and refuses an edit that only shuffles quantities.
        stockPostingService.reverse(id, "Edit of " + mst.getInvoiceNo());
        dtlRepo.deleteAll(dtlRepo.findByPidTranMst(id));
        dtlRepo.flush();

        validateLines(req, tier, branchId, customer.getCustomerName());

        applySalesHeader(mst, req, customer.getPidCustomer());
        PosTransactionMst saved = mstRepo.save(mst);

        writeLinesAndPost(id, req, tier, branchId, orgId);
        List<PosTransactionDtl> dtls = applyInvoiceTotals(saved, req);
        accounting.saleUpdated(saved);

        log.info("Sale updated: {} ({} lines)", saved.getInvoiceNo(), dtls.size());
        return buildResponse(saved, dtls, null, customer.getCustomerName());
    }

    /* ══════════════════════════════════════════════════════════════════════
       SHARED WRITE PATH
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * Allocates each requested line batch-wise and writes one detail row per
     * batch consumed, then posts the movements.
     *
     * <p>A pharmacy invoice has to carry the batch numbers it dispensed, so a
     * line spanning two lots becomes two rows — which is what the Oracle
     * procedure produced as well.
     */
    private void writeLinesAndPost(Long mstId, SalesRequest req, CustomerPricing.Tier tier,
                                   Long branchId, Long orgId) {
        for (SalesRequest.SalesLine line : req.getLines()) {
            var product = productRepo.findById(line.getProductId()).orElseThrow();

            // Get effective discount from SalesDiscount rules
            BigDecimal discPct = discountService.resolveEffectiveDiscount(
                    orgId, product.getPidProduct(), 
                    product.getPidCompany() != null ? product.getPidCompany() : 0L,
                    line.getSalesUnit());
            if (line.getDiscountPct() != null && line.getDiscountPct().compareTo(discPct) > 0) {
                discPct = line.getDiscountPct();
            }

            // Batch-wise FEFO, in Java.
            //
            // This replaced a SimpleJdbcCall to PROC_EXPIRY_WISE_SALES_QTY_DIS,
            // an Oracle procedure that does not exist in PostgreSQL — so sale
            // creation had been failing outright since the migration. The old
            // procedure also priced a BOX sale by multiplying the allocated
            // *pieces* by the *box* price, inflating the line by the pack size;
            // pricing here is per unit sold, as it should be.
            var allocation = stockService.preview(branchId, product.getPidProduct(),
                    line.getQuantity(), line.getSalesUnit());

            if (!allocation.isFullyAllocated()) {
                // Availability was checked above, so reaching here means stock
                // moved underneath us between the check and now.
                throw new InsufficientStockException(
                        product.getProductName(), "branch " + branchId, line.getSalesUnit(),
                        line.getQuantity(), null,
                        allocation.getRequestedPcs(), allocation.getAllocatedPcs());
            }

            // One detail row per batch consumed. A pharmacy invoice has to carry
            // the batch numbers it dispensed, so a sale spanning two lots is two
            // lines — which is what the old procedure produced as well.
            BigDecimal unitPrice = tierPrice(product, tier, line.getSalesUnit());
            Integer pcsPerUnit = pcsPerUnit(product, line.getSalesUnit());

            for (var alloc : allocation.getLines()) {
                BigDecimal qtyInUnit = pcsPerUnit == null || pcsPerUnit == 0
                        ? alloc.getAllocatedPcs()
                        : alloc.getAllocatedPcs().divide(BigDecimal.valueOf(pcsPerUnit),
                                                         4, java.math.RoundingMode.HALF_UP);

                PosTransactionDtl dtl = new PosTransactionDtl();
                dtl.setPidTranMst(mstId);
                dtl.setPidProduct(product.getPidProduct());
                dtl.setQuantity(qtyInUnit);
                dtl.setNoOfPcs(pcsPerUnit != null && pcsPerUnit > 0 ? pcsPerUnit : 1);
                dtl.setBatchNo(alloc.getBatchNo());
                dtl.setExpiryDate(alloc.getExpiryDate());
                dtl.setSalesUnit(line.getSalesUnit());
                dtl.setSalesType(line.getSalesUnit());
                dtl.setMrpPrice(product.getMrpPrice());
                dtl.setSalesPrice(unitPrice);
                dtl.setTotalPrice(unitPrice.multiply(qtyInUnit));
                dtlRepo.save(dtl);
            }

            // Apply discount to the dtl rows just inserted
            if (discPct.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal finalDisc = discPct;
                dtlRepo.findByPidTranMst(mstId).stream()
                        .filter(d -> d.getPidProduct().equals(product.getPidProduct()))
                        .forEach(d -> {
                            BigDecimal discAmt = TransactionCalculator.calculateDiscount(d.getTotalPrice(), finalDisc);
                            d.setDiscountPct(finalDisc);
                            d.setFlatDiscount(discAmt);
                            d.setTotalPrice(d.getTotalPrice().subtract(discAmt));
                            dtlRepo.save(d);
                        });
            }
        }

        // Move the stock. Every line already names the batch it was allocated
        // from, so the posting honours those rather than re-running FEFO and
        // possibly picking different lots than the invoice prints.
        stockPostingService.post(mstId);
    }

    /** Recomputes invoice totals from the detail rows the server just wrote. */
    private List<PosTransactionDtl> applyInvoiceTotals(PosTransactionMst mst, SalesRequest req) {
        List<PosTransactionDtl> dtls = dtlRepo.findByPidTranMst(mst.getPidTranMst());
        List<BigDecimal> lineTotals = dtls.stream()
                .map(d -> d.getTotalPrice() != null ? d.getTotalPrice() : BigDecimal.ZERO).toList();

        /* An exact discount, when the caller has one to give. A coupon is a
           sum of money; a percentage derived from it and multiplied back is the
           same sum give or take a paisa, and "give or take" is not a thing an
           invoice may do. Nothing else in the ERP sets this, so every staff
           sale takes the percentage path it always took. [Phase 12] */
        InvoiceTotals totals = req.getInvoiceDiscAmt() != null
                ? TransactionCalculator.calculateInvoiceTotals(
                        lineTotals, req.getInvoiceDiscAmt(), true, req.getDeliveryCharge())
                : TransactionCalculator.calculateInvoiceTotals(
                        lineTotals, req.getInvoiceDiscPct(), req.getDeliveryCharge());

        BigDecimal due = TransactionCalculator.calculateDue(totals.amountTobePaid(), req.getGivenAmount());
        String payStatus = due.compareTo(BigDecimal.ZERO) <= 0 ? "Paid"
                : (req.getGivenAmount() != null && req.getGivenAmount().compareTo(BigDecimal.ZERO) > 0) ? "Partial"
                : "Unpaid";

        mst.setTotalAmount(totals.subtotal());
        mst.setInvoiceDisAmt(totals.invoiceDiscAmt());
        mst.setAmountTobePaid(totals.amountTobePaid());
        mst.setPaymentStatus(payStatus);

        /* A sale paid in full is collected, not merely submitted.
         *
         * Every sale was created as Submitted whatever was handed over, and only
         * collect() ever moved it on — so a Quick Sale, which is paid at the
         * counter before the customer walks away, sat in a stage that means
         * "raised, awaiting payment" with nothing outstanding to await. It
         * showed as unfinished on every list and report that reads the stage.
         *
         * Only ever Submitted -> Collected. An invoice already Confirmed,
         * Delivered or Cancelled has been moved by somebody for a reason, and
         * recomputing totals on an edit is not grounds to overrule them.
         *
         * Gated on payStatus rather than re-deriving the arithmetic, so the two
         * fields are decided by the same line and cannot end up disagreeing.
         */
        if (STAGE_SUBMITTED.equals(mst.getStage())) {
            if ("Paid".equals(payStatus)) {
                mst.setStage(STAGE_COLLECTED);
            } else if (Boolean.TRUE.equals(req.getCounterSale())) {
                /* A till sale with money still owing.
                 *
                 * The goods went over the counter as the bill was printed, so
                 * the invoice is Delivered — the customer has the medicine and
                 * owes for it. It sat at Submitted, which reads as "raised,
                 * nothing has happened yet", and left the shop with no record
                 * that the stock had gone.
                 *
                 * Only for a counter sale. An order from the Sales page has not
                 * been delivered by being unpaid, and still has to travel
                 * Confirmed → Delivery Assigned → On The Way before anybody may
                 * say that it has.
                 */
                mst.setStage(STAGE_DELIVERED);
            }
        }

        mstRepo.save(mst);
        return dtls;
    }

    /** Header fields a client may change on an edit. */
    private void applySalesHeader(PosTransactionMst mst, SalesRequest req, Long customerId) {
        mst.setPidSupCust(customerId);
        mst.setTransactionDate(req.getTransactionDate());
        mst.setPaymentType(req.getPaymentType());
        mst.setDeliveryAddress(req.getDeliveryAddress());
        mst.setDeliveryCharge(req.getDeliveryCharge());
        mst.setInvoiceDisPct(req.getInvoiceDiscPct());
        mst.setGivenAmount(req.getGivenAmount());
        mst.setRemarks(req.getRemarks());
        if (req.getDeliveryManId() != null) {
            // Same check the assignment endpoints run: an id that is not an
            // active delivery man is refused here rather than written and
            // discovered later by whoever goes looking for the goods.
            var dm = deliveryManService.requireDeliveryMan(req.getDeliveryManId());
            mst.setDeliveryMan(dm.getPidEmployee());
            mst.setDeliveryManName(dm.getEmpName());
        }
    }

    /**
     * Product state, tier eligibility and stock availability for every line.
     * Runs before anything is written, so a bad line fails the whole request
     * rather than leaving a half-built invoice.
     */
    private void validateLines(SalesRequest req, CustomerPricing.Tier tier,
                               Long branchId, String customerName) {
        for (SalesRequest.SalesLine line : req.getLines()) {
            var product = productRepo.findById(line.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product", line.getProductId()));
            if (!"Y".equals(product.getStatus()))
                throw new InactiveEntityException("Product", product.getProductName());
            if (line.getQuantity() == null || line.getQuantity().compareTo(BigDecimal.ZERO) <= 0)
                throw new ValidationException("Quantity for '" + product.getProductName() + "' must be > 0.");

            String reason = CustomerPricing.unavailableReason(
                    tier, line.getSalesUnit(), unitPricesFor(product));
            if (reason != null) {
                throw new BusinessException(
                        "'" + product.getProductName() + "' cannot be sold to " + customerName
                        + " in " + line.getSalesUnit() + ". " + reason,
                        HttpStatus.UNPROCESSABLE_ENTITY, "UNIT_NOT_SELLABLE");
            }

            stockService.assertAvailable(branchId, product.getPidProduct(),
                    line.getQuantity(), line.getSalesUnit());
        }
    }

    /**
     * Sale price for one unit, from the product's Sales% against its MRP.
     *
     * <p>{@code MRP − (MRP × Sales% / 100)}, using the percentage set for the
     * unit being sold. Falls back to MRP when no percentage is configured —
     * selling at full MRP is the safe direction to be wrong in.
     */
    /**
     * The product's six prices, with MRP apportioned to each unit.
     *
     * <p>Packing comes from the product rather than a batch here: this is the
     * price list, which is a property of the product, not of the lot the pieces
     * happen to come from.
     */
    private SalesPriceCalculator.UnitPrices unitPricesFor(
            com.friendspharma.app.entity.PosProductInfo product) {
        // Sale-side: see unitPricesForSale. Null percentages price at MRP so a
        // product with no discount set can still be put on an invoice.
        return SalesPriceCalculator.unitPricesForSale(
                product.getMrpPrice(),
                pcsPerUnit(product, StockUnitConverter.BOX),
                pcsPerUnit(product, StockUnitConverter.STRIP),
                product.getSalesPer(), product.getSalesPerLeaf(), product.getSalesPerPcs(),
                product.getSalesPerSpc(), product.getSalesPerSpcLeaf(), product.getSalesPerSpcRet());
    }

    /**
     * What this customer pays for one unit of this product.
     *
     * <p>Never falls back to MRP. A line whose tier has no price is refused
     * during validation, so reaching here with a null would mean charging list
     * price for something quoted at a discount — the failure is loud instead.
     */
    private BigDecimal tierPrice(com.friendspharma.app.entity.PosProductInfo product,
                                 CustomerPricing.Tier tier, String unit) {
        BigDecimal price = CustomerPricing.priceFor(tier, unit, unitPricesFor(product));
        if (price == null) {
            // No longer reachable by "the discount was never set" — that prices
            // at MRP now. What is left is a unit this tier may not buy, or a
            // product with no packing to work the unit's share of MRP out from,
            // so the message names those rather than sending somebody to
            // Sales Discount% to set a percentage that would not help.
            throw new BusinessException(
                    "'" + product.getProductName() + "' cannot be sold in "
                    + StockUnitConverter.normalise(unit) + " to a "
                    + CustomerPricing.label(tier).toLowerCase()
                    + ". Either that customer type may not buy this unit, or the product has "
                    + "no box/strip packing set in Product Master.",
                    HttpStatus.UNPROCESSABLE_ENTITY, "PRICE_NOT_CONFIGURED");
        }
        return price;
    }

    /* ══════════════════════════════════════════════════════════════════════
       PRICING LOOKUP — what this customer may buy of this product
       ══════════════════════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public SalesPricingResponse getPricing(Long customerId, Long productId,
                                           BigDecimal qty, String unit, Long branchIdIn) {
        var customer = customerRepo.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", customerId));
        var product = productRepo.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));

        // Same resolution as the sale itself, so the price quoted and the stock
        // checked come from the branch the invoice will be written to.
        Long branchId = branchScope.resolve(branchIdIn);
        if (branchId == null) {
            throw new BusinessException(
                    "Your user account has no branch assigned, so stock cannot be checked.",
                    HttpStatus.CONFLICT, "NO_BRANCH_CONTEXT");
        }

        CustomerPricing.Tier tier = CustomerPricing.tierOf(customer.getCustomerType());
        var prices = unitPricesFor(product);

        Integer pcsPerBox   = pcsPerUnit(product, StockUnitConverter.BOX);
        Integer pcsPerStrip = pcsPerUnit(product, StockUnitConverter.STRIP);
        BigDecimal availablePcs = stockService.availablePcs(branchId, productId);

        // Every unit is returned, including the ones this customer cannot have,
        // each with the reason. Omitting them would leave the UI unable to say
        // why a box option is missing.
        List<SalesPricingResponse.UnitOption> options = new ArrayList<>();
        for (String u : List.of(StockUnitConverter.BOX, StockUnitConverter.STRIP, StockUnitConverter.PCS)) {
            Integer per = pcsPerUnit(product, u);
            BigDecimal price = CustomerPricing.priceFor(tier, u, prices);
            String reason = CustomerPricing.unavailableReason(tier, u, prices);

            options.add(SalesPricingResponse.UnitOption.builder()
                    .unit(u)
                    .allowed(reason == null)
                    .price(price)
                    .mrp(CustomerPricing.mrpFor(u, prices))
                    .discountPct(pctFor(product, tier, u))
                    .pcsPerUnit(per)
                    // Whole units the branch can actually assemble, counted lot
                    // by lot. Dividing the piece total would offer a box that
                    // exists only as pieces spread across two batches.
                    .maxQty(per == null || per <= 0 ? null
                            : stockService.availableWholeUnits(branchId, productId, u))
                    .reason(reason)
                    .build());
        }

        // The batch split only makes sense once a quantity is known.
        StockAllocation allocation = null;
        if (qty != null && qty.compareTo(BigDecimal.ZERO) > 0
                && CustomerPricing.allows(tier, unit)) {
            allocation = stockService.preview(branchId, productId, qty, unit);
        }

        return SalesPricingResponse.builder()
                .customerId(customer.getPidCustomer())
                .customerName(customer.getCustomerName())
                .customerType(customer.getCustomerType())
                .tier(tier.name())
                .tierLabel(CustomerPricing.label(tier))
                .productId(product.getPidProduct())
                .productName(product.getProductName())
                .sku(product.getSku())
                .allowedUnits(CustomerPricing.allowedUnits(tier))
                .availablePcs(availablePcs)
                .availableDisplay(StockUnitConverter.describe(availablePcs, pcsPerBox, pcsPerStrip))
                .pcsPerBox(pcsPerBox)
                .pcsPerStrip(pcsPerStrip)
                .units(options)
                .allocation(allocation)
                .build();
    }

    /** The percentage behind a tier's price, for the UI to show its working. */
    private BigDecimal pctFor(com.friendspharma.app.entity.PosProductInfo p,
                              CustomerPricing.Tier tier, String unit) {
        String u = StockUnitConverter.normalise(unit);
        boolean special = tier == CustomerPricing.Tier.SPECIAL;
        return switch (u) {
            case StockUnitConverter.BOX   -> special ? p.getSalesPerSpc()     : p.getSalesPer();
            case StockUnitConverter.STRIP -> special ? p.getSalesPerSpcLeaf() : p.getSalesPerLeaf();
            case StockUnitConverter.PCS   -> special ? p.getSalesPerSpcRet()  : p.getSalesPerPcs();
            default -> null;
        };
    }

    /** Pieces in one unit of {@code unit} for this product, or null when unconfigured. */
    private Integer pcsPerUnit(com.friendspharma.app.entity.PosProductInfo product, String unit) {
        Integer pcsPerStrip = null;
        Integer stripsPerBox = null;

        if (product.getPidBox() != null) {
            var box = boxRepo.findById(product.getPidBox()).orElse(null);
            if (box != null) {
                stripsPerBox = box.getNoOfLeaf();
                if (box.getPidLeaf() != null) {
                    var leaf = leafRepo.findById(box.getPidLeaf()).orElse(null);
                    if (leaf != null) pcsPerStrip = leaf.getNoOfPcs();
                }
            }
        }

        Integer pcsPerBox = (stripsPerBox != null && pcsPerStrip != null)
                ? stripsPerBox * pcsPerStrip : null;

        return com.friendspharma.app.util.StockUnitConverter.factorFor(unit, pcsPerBox, pcsPerStrip);
    }

    /**
     * The invoice list, with the filters the Sales screen offers.
     *
     * <p>Shares {@link #listActivity} rather than growing a second query: the
     * two screens ask the same question of the same rows, and two queries drift
     * — one gains a filter, the other keeps returning what it always did.
     */
    @Transactional(readOnly = true)
    public Page<TransactionResponse> listSales(String stage, String payStatus, Long branchId,
                                               Long customerId, LocalDate from, LocalDate to,
                                               String q, Pageable pageable) {
        // The invoice list shows everything, merged orders included. They are
        // what the goods actually went out on and have to stay auditable — the
        // screen marks them read-only rather than hiding them.
        //
        // The branch used to be passed as null here, which is why one pharmacy
        // could read another's invoices: the query supports the filter, the
        // caller simply declined to use it.
        return listActivity(stage, payStatus, branchId, customerId, null, false,
                            from, to, q, false, null, true, pageable);
    }

    /**
     * The Sales Activity list, with every filter the screen offers.
     *
     * <p>All of them are pushed into the query rather than applied to the page
     * that comes back: filtering afterwards would narrow only the rows already
     * fetched, so a search would miss matches beyond the first page while the
     * total still counted them.
     */
    @Transactional(readOnly = true)
    public Page<TransactionResponse> listActivity(String stage, String payStatus, Long branchId,
                                                  Long dmId, boolean unassigned,
                                                  LocalDate from, LocalDate to, String q,
                                                  Pageable pageable) {
        return listActivity(stage, payStatus, branchId, dmId, unassigned, from, to, q, null, pageable);
    }

    /**
     * @param source {@code ECOM} for marketplace orders, {@code API} for what
     *               the branch rang up itself, null for both
     */
    @Transactional(readOnly = true)
    public Page<TransactionResponse> listActivity(String stage, String payStatus, Long branchId,
                                                  Long dmId, boolean unassigned,
                                                  LocalDate from, LocalDate to, String q,
                                                  String source, Pageable pageable) {
        // Sales Activity is the delivery board: an order folded into a merge is
        // delivered under that merge, so it is hidden here and the merge stands
        // in its place. Listing both would put the same goods on two rounds.
        //
        // Cancelled documents are hidden for the same kind of reason: they are
        // nobody's work any more, and leaving them in would put the table out of
        // step with the chips above it, which do not count them. Asking for the
        // Cancelled filter is how you see them.
        return listActivity(stage, payStatus, branchId, null, dmId, unassigned,
                            from, to, q, true, source,
                            STAGE_CANCELLED.equalsIgnoreCase(stage), pageable);
    }

    @Transactional(readOnly = true)
    public Page<TransactionResponse> listActivity(String stage, String payStatus, Long branchId,
                                                  Long customerId, Long dmId, boolean unassigned,
                                                  LocalDate from, LocalDate to, String q,
                                                  boolean hideMerged, Pageable pageable) {
        return listActivity(stage, payStatus, branchId, customerId, dmId, unassigned,
                            from, to, q, hideMerged, null, true, pageable);
    }

    @Transactional(readOnly = true)
    public Page<TransactionResponse> listActivity(String stage, String payStatus, Long branchId,
                                                  Long customerId, Long dmId, boolean unassigned,
                                                  LocalDate from, LocalDate to, String q,
                                                  boolean hideMerged, String source,
                                                  boolean includeVoid, Pageable pageable) {
        Long orgId = SecurityUtils.getCurrentOrgId();

        // Delivery staff see their own rounds and nothing else. Forced here
        // rather than trusted from the request: a driver could otherwise pass
        // another driver's id, or none at all, and read the whole branch.
        Long scopedDm = dmId;
        boolean scopedUnassigned = unassigned;
        if (SecurityUtils.isDeliveryStaff()) {
            scopedDm = requireOwnEmployeeId();
            scopedUnassigned = false;   // an unassigned order is nobody's round
        }

        Page<PosTransactionMst> page = mstRepo.findSalesActivity(
                orgId, blank(stage), blank(payStatus), branchId, customerId, scopedDm,
                scopedUnassigned, hideMerged, blank(source), includeVoid,
                since(from), until(to), blank(q), pageable);

        // Customer names for the whole page in one query. They were passed as
        // null before, so every row on the sales list rendered a blank customer.
        java.util.Map<Long, String> names = customerRepo.findAllById(
                        page.getContent().stream()
                                .map(PosTransactionMst::getPidSupCust)
                                .filter(java.util.Objects::nonNull)
                                .collect(java.util.stream.Collectors.toSet()))
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        c -> c.getPidCustomer(), c -> c.getCustomerName(), (a, b) -> a));

        /* The marketplace order number for the rows that have one, in one query
           for the page. The counter and the customer are then looking at the
           same document under both of its names. */
        java.util.Map<Long, SaleSourceReferences.Ref> refs = sourceRefs.referencesFor(
                page.getContent().stream()
                        .filter(m -> SaleContext.SOURCE_ECOM.equals(m.getFromSource()))
                        .map(PosTransactionMst::getPidTranMst).toList());

        return page.map(m -> buildResponse(
                m, dtlRepo.findByPidTranMst(m.getPidTranMst()),
                null, names.get(m.getPidSupCust()), refs.get(m.getPidTranMst())));
    }

    @Transactional(readOnly = true)
    public TransactionResponse getSale(Long id) {
        PosTransactionMst mst = mstRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sale", id));
        return buildResponse(mst, dtlRepo.findByPidTranMst(id), null, customerNameOf(mst));
    }

    @Transactional
    public TransactionResponse finalizeSale(Long id) {
        PosTransactionMst mst = mstRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sale", id));
        requireNotMerged(mst, "confirmed");

        List<PosTransactionMst> grouped = mergedSourcesOf(mst);
        // A merge carries no lines of its own — the orders inside it do. Judging
        // it empty on its own detail rows would make every merge unconfirmable.
        if (grouped.isEmpty() && dtlRepo.findByPidTranMst(id).isEmpty())
            throw new BusinessException("Cannot finalize an empty sale invoice.",
                    HttpStatus.UNPROCESSABLE_ENTITY, "EMPTY_INVOICE");

        mst.setStage("Confirmed");
        cascadeStage(grouped, "Confirmed", null, null);
        return buildResponse(mstRepo.save(mst), dtlRepo.findByPidTranMst(id), null, customerNameOf(mst));
    }

    /** '' is the query's "no filter" marker; null would be typed as bytea by PostgreSQL. */
    private static String blank(String s) { return (s == null || s.isBlank() || "All".equalsIgnoreCase(s)) ? "" : s; }

    /* ══════════════════════════════════════════════════════════════════════
       DELIVERY STAFF SCOPING

       A driver may see and act on the invoices dispatched to them, and no
       others. Enforced here rather than by hiding buttons: the rule has to hold
       against a hand-made request, not merely against the UI.
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * The signed-in driver's own employee id.
     *
     * <p>A ROLE06 account with no employee link cannot be matched to any
     * invoice. Refusing with an explanation beats returning an empty list, which
     * would read as "you have no deliveries today" when the real problem is that
     * nobody linked the account to a staff record.
     */
    private Long requireOwnEmployeeId() {
        Long employeeId = SecurityUtils.getCurrentEmployeeId();
        if (employeeId == null) {
            throw new BusinessException(
                    "Your login is not linked to a staff record, so your deliveries cannot be "
                    + "identified. Ask an administrator to link it in RBAC › Users.",
                    HttpStatus.CONFLICT, "NO_EMPLOYEE_LINK");
        }
        return employeeId;
    }

    /**
     * Refuses a driver acting on an invoice that is not theirs.
     *
     * <p>Not-found rather than forbidden for an invoice belonging to someone
     * else: whether a given invoice exists, and who holds it, is not this
     * driver's business.
     */
    private void requireOwnDelivery(PosTransactionMst mst) {
        if (!SecurityUtils.isDeliveryStaff()) return;
        Long mine = requireOwnEmployeeId();
        if (!mine.equals(mst.getDeliveryMan())) {
            throw new ResourceNotFoundException("Sale", mst.getPidTranMst());
        }
    }

    /** The only transitions a driver may make: along their own run. */
    private static final List<String> DRIVER_STAGES = List.of("On The Way", "Delivered");

    /**
     * Keeps a driver inside their lane.
     *
     * <p>They can say a parcel is on its way and that it arrived. They cannot
     * confirm an order, assign it, or mark it collected — confirming is a sales
     * decision and collection is settled by taking money, through
     * {@link #collect}, not by declaring it.
     */
    private void requireDriverStageAllowed(String newStage) {
        if (!SecurityUtils.isDeliveryStaff()) return;
        if (!DRIVER_STAGES.contains(newStage)) {
            throw new BusinessException(
                    "As delivery staff you can move an order to "
                    + String.join(" or ", DRIVER_STAGES) + " only.",
                    HttpStatus.FORBIDDEN, "STAGE_NOT_ALLOWED");
        }
    }

    /**
     * Delivery performance, one row per driver.
     *
     * <p>A driver sees only their own record. The comparison between drivers is
     * a management question, and handing one of them the whole league table is
     * not what "see how I am doing" asks for.
     */
    @Transactional(readOnly = true)
    public List<DeliveryPerformanceResponse> deliveryPerformance(Long branchId, LocalDate from, LocalDate to) {
        Long orgId = SecurityUtils.getCurrentOrgId();
        Long onlyMine = SecurityUtils.isDeliveryStaff() ? requireOwnEmployeeId() : null;

        List<Object[]> rows = mstRepo.deliveryPerformance(orgId, branchId, from, to);

        // One lookup for the whole page rather than per row, and it also supplies
        // the vehicle and phone the aggregate cannot carry.
        Map<Long, HrEmployee> staff = employeeRepo.findAllById(
                        rows.stream().map(r -> (Long) r[0]).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(HrEmployee::getPidEmployee, e -> e, (a, b) -> a));

        List<DeliveryPerformanceResponse> out = new ArrayList<>();
        for (Object[] r : rows) {
            Long dmId = (Long) r[0];
            if (onlyMine != null && !onlyMine.equals(dmId)) continue;

            long assigned   = ((Number) r[2]).longValue();
            long delivered  = ((Number) r[3]).longValue();
            long inProgress = ((Number) r[4]).longValue();
            BigDecimal billed    = (BigDecimal) r[5];
            BigDecimal collected = (BigDecimal) r[6];
            HrEmployee e = staff.get(dmId);

            out.add(DeliveryPerformanceResponse.builder()
                    .deliveryManId(dmId)
                    .deliveryManName(e != null ? e.getEmpName() : (String) r[1])
                    .vehicleNo(e != null ? e.getVehicleNo() : null)
                    .phone(e != null ? e.getPhone() : null)
                    .active(e != null ? e.isActive() : null)
                    .assigned(assigned)
                    .delivered(delivered)
                    .inProgress(inProgress)
                    .completionRate(rate(delivered, assigned))
                    .billed(billed)
                    .collected(collected)
                    .outstanding(billed.subtract(collected).max(BigDecimal.ZERO))
                    .collectionRate(rate(collected, billed))
                    .build());
        }
        return out;
    }

    /** Null on a zero denominator — no work given is not nought per cent done. */
    private static BigDecimal rate(long part, long whole) {
        return whole == 0 ? null
                : BigDecimal.valueOf(part).multiply(BigDecimal.valueOf(100))
                            .divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal rate(BigDecimal part, BigDecimal whole) {
        return (whole == null || whole.compareTo(BigDecimal.ZERO) == 0) ? null
                : part.multiply(BigDecimal.valueOf(100)).divide(whole, 1, RoundingMode.HALF_UP);
    }

    /** Stage counts across the whole branch, so the chips are not page-local. */
    @Transactional(readOnly = true)
    public Map<String, Long> stageCounts(Long branchId) {
        return deliverySummary(branchId).getByStage();
    }

    /**
     * Counts and money for the delivery dashboard, in one query.
     *
     * <p>Scoped by driver for delivery staff, exactly as the order list is. An
     * unscoped summary above a scoped table would put two contradictory totals
     * on one screen — and tell a driver how much work exists that is not theirs.
     */
    @Transactional(readOnly = true)
    public DeliverySummaryResponse deliverySummary(Long branchId) {
        boolean ownRoundOnly = SecurityUtils.isDeliveryStaff();
        Long dmId = ownRoundOnly ? requireOwnEmployeeId() : null;

        Map<String, Long> byStage = new LinkedHashMap<>();
        for (String st : STAGES) byStage.put(st, 0L);

        long total = 0;
        BigDecimal billed = BigDecimal.ZERO;
        BigDecimal collected = BigDecimal.ZERO;

        for (Object[] row : mstRepo.countSalesByStage(SecurityUtils.getCurrentOrgId(), branchId, dmId)) {
            String st = (String) row[0];
            long n = ((Number) row[1]).longValue();
            total += n;
            billed = billed.add((BigDecimal) row[2]);
            collected = collected.add((BigDecimal) row[3]);
            // A stage the application does not know about still counts toward the
            // totals — dropping it would make the parts stop summing to the whole.
            if (st != null && byStage.containsKey(st)) byStage.merge(st, n, Long::sum);
        }
        byStage.put("All", total);

        /* Cancelled is not a stage — it is where a document goes when it stops
           being one. Counted separately so the screen can offer a chip for it
           without the figure joining any stage total or the money above. */
        byStage.put(STAGE_CANCELLED,
                mstRepo.countCancelledSales(SecurityUtils.getCurrentOrgId(), branchId, dmId));

        long onTheRoad  = byStage.get("Delivery Assigned") + byStage.get("On The Way");
        long completed  = byStage.get("Delivered") + byStage.get("Collected");

        return DeliverySummaryResponse.builder()
                .byStage(byStage)
                .totalOrders(total)
                .awaitingAssignment(byStage.get("Confirmed"))
                .onTheRoad(onTheRoad)
                .completed(completed)
                .billed(billed)
                .collected(collected)
                .outstanding(billed.subtract(collected).max(BigDecimal.ZERO))
                .ownRoundOnly(ownRoundOnly)
                .build();
    }

    /**
     * A balance this small is rounding dust, not debt.
     *
     * <p>Invoice totals are stored to four decimal places, but the smallest coin
     * anyone can hand over is one paisa. An invoice payable of 5.2920 is shown —
     * correctly — as 5.29, and paying it left 0.0020 outstanding: the status
     * stayed Partial, the screen rounded the remainder to 0.00 so the Collect
     * button disappeared, and the invoice could never be settled by anyone.
     *
     * <p>The comparison is strictly less-than, so a genuine one-paisa debt still
     * counts as owed.
     */
    private static final BigDecimal SETTLEMENT_TOLERANCE = new BigDecimal("0.01");

    private static boolean isSettled(BigDecimal owing) {
        return owing.compareTo(SETTLEMENT_TOLERANCE) < 0;
    }

    /**
     * Records money taken against an invoice.
     *
     * <p>The screen had no endpoint for this at all — collections were applied
     * to local state and lost on navigation. Amounts accumulate into
     * GIVEN_AMOUNT, and the payment status follows from what is left owing
     * rather than being passed in, so the two cannot disagree.
     */
    @Transactional
    public TransactionResponse collect(Long id, BigDecimal amount, String paymentType) {
        PosTransactionMst mst = mstRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sale", id));

        // Money is taken by whoever handed over the goods, so a driver may
        // collect — but only against the invoices dispatched to them.
        requireOwnDelivery(mst);
        requireNotMerged(mst, "collected against");

        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("Enter an amount greater than zero.");
        }

        BigDecimal payable = mst.getAmountTobePaid() != null ? mst.getAmountTobePaid() : BigDecimal.ZERO;
        BigDecimal paid    = mst.getGivenAmount()    != null ? mst.getGivenAmount()    : BigDecimal.ZERO;
        BigDecimal owing   = payable.subtract(paid);

        if (isSettled(owing)) {
            // The stored status can disagree with the arithmetic: invoices paid
            // before this tolerance existed kept a Partial status over a
            // sub-paisa remainder, and the screen offered no way to clear it.
            // Correct the record rather than refuse, so a stuck row unsticks.
            if (!"Paid".equals(mst.getPaymentStatus())) {
                mst.setPaymentStatus("Paid");
                mst.setStage(STAGE_COLLECTED);
                log.info("Invoice {} settled: {} remainder was below one paisa.",
                         mst.getInvoiceNo(), owing);
                return buildResponse(mstRepo.save(mst), dtlRepo.findByPidTranMst(id), null, customerNameOf(mst));
            }
            throw new ValidationException("This invoice is already settled.");
        }
        // Refused rather than truncated: taking more than is owed would leave
        // the ledger showing a credit nobody recorded a reason for. Compared
        // against the rounded figure, because that is the one the customer was
        // shown and the only one they can actually hand over.
        if (amount.compareTo(owing.setScale(2, RoundingMode.HALF_UP)) > 0) {
            throw new ValidationException("That is more than the "
                    + owing.setScale(2, RoundingMode.HALF_UP) + " outstanding on this invoice.");
        }

        BigDecimal nowPaid = paid.add(amount);
        mst.setGivenAmount(nowPaid);
        if (paymentType != null && !paymentType.isBlank()) mst.setPaymentType(paymentType);

        boolean settled = isSettled(payable.subtract(nowPaid));
        mst.setPaymentStatus(settled ? "Paid" : "Partial");
        // Only a fully settled invoice is Collected. A part payment leaves the
        // order where it was, because there is still something to go back for.
        if (settled) mst.setStage(STAGE_COLLECTED);

        // The customer's debt is carried on the orders, not on the wrapper, so
        // money taken here has to reach them or the account stays overdue.
        cascadeCollection(mergedSourcesOf(mst), amount);
        // A merge carries no debt of its own (the hook skips it); its orders were booked above.
        accounting.saleCollected(mst, amount, paymentType);

        return buildResponse(mstRepo.save(mst), dtlRepo.findByPidTranMst(id), null, customerNameOf(mst));
    }
    /** Advance stage: Submitted→Confirmed→Delivery Assigned→On The Way→Delivered→Collected */
    @Transactional
    public TransactionResponse advanceStage(Long id, String newStage, Long deliveryManId) {
        PosTransactionMst mst = mstRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sale", id));

        // Any string used to be accepted, so a typo from a client became the
        // order's stage and every stage-based filter and report stopped seeing it.
        if (!STAGES.contains(newStage)) {
            throw new ValidationException(
                    "'" + newStage + "' is not an order stage. Use one of: "
                    + String.join(", ", STAGES) + ".");
        }
        // A driver may only touch their own run, and only within it.
        requireOwnDelivery(mst);
        requireDriverStageAllowed(newStage);
        requireNotMerged(mst, "moved to another stage");

        mst.setStage(newStage);

        if (deliveryManId != null) {
            // Reassignment is an administrative act. A driver handing a round
            // to someone else would take it off their own performance record
            // and put goods with a person nobody dispatched them to.
            if (SecurityUtils.isDeliveryStaff()) {
                throw new BusinessException(
                        "Only a manager can change who delivers an order.",
                        HttpStatus.FORBIDDEN, "REASSIGN_NOT_ALLOWED");
            }
            // Checked, not looked up hopefully. The previous `ifPresent` left a
            // bad id written to DELIVERY_MAN with the name silently unchanged —
            // an invoice assigned to nobody, or still showing the last driver.
            // requireDeliveryMan also refuses employees who are not delivery men,
            // which nothing enforced before.
            var dm = deliveryManService.requireDeliveryMan(deliveryManId);
            mst.setDeliveryMan(dm.getPidEmployee());
            mst.setDeliveryManName(dm.getEmpName());
        }

        if ("Collected".equals(newStage)) {
            mst.setPaymentStatus("Paid");
        }

        // The orders inside a merge are what the goods went out on and what the
        // reports count. Left at Submitted they would drop out of every revenue
        // figure the moment the merge they belong to was delivered.
        cascadeStage(mergedSourcesOf(mst), newStage,
                     mst.getDeliveryMan(), mst.getDeliveryManName());

        /* Said out loud, so that whatever was built on top of this sale can keep
           up with it. A driver marking an order delivered from the Delivery page
           has no idea it came from the marketplace; the customer waiting for it
           still needs their screen to change. */
        events.publishEvent(new com.friendspharma.app.event.SaleStageChanged(
                id, newStage, SecurityUtils.getCurrentUsername()));

        return buildResponse(mstRepo.save(mst), dtlRepo.findByPidTranMst(id), null, customerNameOf(mst));
    }


    /* ══════════════════════════════════════════════════════════════════════
       CANCELLATION

       Calling a sale off, properly: the stock goes back on the shelf, the
       journals are reversed, and the document stays where it is with the three
       facts an auditor asks for written on it.

       This did not exist. `POST /{id}/cancel` called advanceStage("Cancelled"),
       "Cancelled" is not one of the stages, and the call threw — so a mistaken
       invoice kept holding its stock until somebody raised a sales return
       against goods that had never left the building.
       ══════════════════════════════════════════════════════════════════════ */

    /** Where a cancelled document stands. Deliberately not in {@link #STAGES}. */
    public static final String STAGE_CANCELLED = "Cancelled";

    /** What {@code STATUS} says about a document that no longer counts. */
    private static final String STATUS_VOID = "N";

    /** The stages a sale may still be called off from — before the goods leave. */
    private static final List<String> CANCELLABLE_STAGES =
            List.of("Submitted", "Confirmed", "Delivery Assigned");

    /**
     * Cancels a sale on behalf of the signed-in user.
     */
    @Transactional
    public TransactionResponse cancelSale(Long id, String reason) {
        return cancelSale(id, reason, SecurityUtils.getCurrentUsername());
    }

    /**
     * Cancels a sale: stock back, journals reversed, document marked.
     *
     * <p>"Cancelled" is <b>not</b> added to {@link #STAGES}. Every stage filter,
     * chip, delivery summary and report iterates that list, and a seventh entry
     * would quietly change what all of them mean. A cancelled document leaves
     * the stage machine instead: {@code STAGE = 'Cancelled'} with {@code STATUS =
     * 'N'}, which is exactly what splitting a merge already does to the wrapper
     * it voids (SalesMergeServiceImpl) and what the dashboards already read as
     * void. So it counts towards nothing while staying on the list, findable,
     * with its reason attached.
     *
     * <p>Refused once the goods are out (On The Way and beyond) and refused
     * once money has been taken. Both are corrected with a sales return, which
     * is a document of its own describing what actually happened, rather than a
     * cancellation pretending the sale never did.
     *
     * @param actor who is cancelling — a staff username, or the e-commerce
     *              customer calling their own order off
     */
    @Transactional
    public TransactionResponse cancelSale(Long id, String reason, String actor) {
        PosTransactionMst mst = mstRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sale", id));

        if (!"SL".equals(mst.getTransactionType())) {
            throw new BusinessException("Invoice " + mst.getInvoiceNo() + " is not a sale.",
                    HttpStatus.BAD_REQUEST, "NOT_A_SALE");
        }
        if (STAGE_CANCELLED.equalsIgnoreCase(mst.getStage())) {
            throw new BusinessException(
                    "Invoice " + mst.getInvoiceNo() + " was already cancelled"
                    + (mst.getCancelledDate() == null ? "" : " on " + mst.getCancelledDate().toLocalDate()) + ".",
                    HttpStatus.CONFLICT, "SALE_ALREADY_CANCELLED");
        }

        // A merge is the document the goods went out on; its orders hold the
        // stock and the money. Cancelling either half alone would leave the
        // other describing a delivery that is not happening.
        if (MERGE_FLAG.equals(mst.getIsMergeInvoice())) {
            throw new BusinessException(
                    "Invoice " + mst.getInvoiceNo() + " is a merged invoice. Split the merge first, "
                    + "then cancel the orders that are not going out.",
                    HttpStatus.CONFLICT, "MERGE_NOT_CANCELLABLE");
        }
        requireNotMerged(mst, "cancelled");

        if (!CANCELLABLE_STAGES.contains(mst.getStage())) {
            throw new BusinessException(
                    "Invoice " + mst.getInvoiceNo() + " is at stage '" + mst.getStage()
                    + "'. The goods have left the branch, so it cannot be cancelled — raise a "
                    + "sales return for what comes back.",
                    HttpStatus.CONFLICT, "SALE_TOO_LATE_TO_CANCEL");
        }

        // Money already taken is money that has to go back, and that is a
        // decision with a cash drawer at the end of it. The books could express
        // it (the reversal takes the receipt with it), but a refund should be
        // somebody's deliberate act, not a side effect of pressing Cancel.
        BigDecimal paid = nvlAmount(mst.getGivenAmount());
        BigDecimal collected = nvlAmount(collectionRepo.sumCollectedByTransaction(id));
        if (paid.signum() > 0 || collected.signum() > 0) {
            throw new BusinessException(
                    "Invoice " + mst.getInvoiceNo() + " has "
                    + plainAmount(paid.max(collected)) + " paid against it. Refund it and raise a "
                    + "sales return instead of cancelling, so the money and the goods are both accounted for.",
                    HttpStatus.CONFLICT, "SALE_ALREADY_PAID");
        }

        // Stock first: if a lot cannot take its pieces back the whole thing
        // rolls back, and nothing has been marked cancelled that is not.
        stockPostingService.reverse(id, "Cancelled: " + shortReason(reason, mst.getInvoiceNo()));
        accounting.saleCancelled(mst, reason);

        mst.setStage(STAGE_CANCELLED);
        mst.setStatus(STATUS_VOID);
        mst.setCancelledBy(trim(actor, 50));
        mst.setCancelledDate(LocalDateTime.now());
        mst.setCancelReason(trim(shortReason(reason, mst.getInvoiceNo()), 300));

        PosTransactionMst saved = mstRepo.save(mst);
        events.publishEvent(new com.friendspharma.app.event.SaleStageChanged(
                id, STAGE_CANCELLED, saved.getCancelledBy()));
        log.info("Sale {} cancelled by {} — stock reversed, journals reversed ({})",
                saved.getInvoiceNo(), saved.getCancelledBy(), saved.getCancelReason());

        return buildResponse(saved, dtlRepo.findByPidTranMst(id), null, customerNameOf(saved));
    }

    /** A reason there is no point storing blank. */
    private static String shortReason(String reason, String invoiceNo) {
        return reason == null || reason.isBlank() ? "Cancelled" : reason.trim();
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static String plainAmount(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }
    /* ══════════════════════════════════════════════════════════════════════
       MERGED ORDERS

       A merge is a grouping document: it is delivered and collected, while the
       orders inside it hold the lines, the stock and the money that the reports
       read. Both halves have to stay in step, so everything done to the merge
       is applied to its orders, and nothing may be done to an order directly
       while it belongs to one.
       ══════════════════════════════════════════════════════════════════════ */

    /** Marks the grouping document, as opposed to the orders inside it. */
    private static final String MERGE_FLAG = "Y";

    /** The customer this invoice is for, looked up one invoice at a time. */
    private String customerNameOf(PosTransactionMst mst) {
        if (mst.getPidSupCust() == null || !"SL".equals(mst.getTransactionType())) return null;
        return customerRepo.findById(mst.getPidSupCust())
                .map(PosCustomerInfo::getCustomerName).orElse(null);
    }

    /** The orders inside this document, or empty if it is not a merge. */
    private List<PosTransactionMst> mergedSourcesOf(PosTransactionMst mst) {
        return MERGE_FLAG.equals(mst.getIsMergeInvoice())
                ? mstRepo.findByMergePidTranMst(mst.getPidTranMst())
                : List.of();
    }

    /** Refuses an operation on an order that is being run under a merge. */
    private void requireNotMerged(PosTransactionMst mst, String verb) {
        if (mst.getMergePidTranMst() != null) {
            throw new BusinessException(
                    "Invoice " + mst.getInvoiceNo() + " is part of a merge and cannot be "
                    + verb + " on its own. Do it on the merged invoice, or split the merge first.",
                    HttpStatus.CONFLICT, "ORDER_MERGED");
        }
    }

    private void cascadeStage(List<PosTransactionMst> sources, String stage,
                              Long dmId, String dmName) {
        for (PosTransactionMst t : sources) {
            t.setStage(stage);
            if (dmId != null) {
                t.setDeliveryMan(dmId);
                t.setDeliveryManName(dmName);
            }
            if ("Collected".equals(stage)) {
                t.setPaymentStatus("Paid");
                t.setGivenAmount(nvlAmount(t.getAmountTobePaid()));
            }
            mstRepo.save(t);
        }
    }

    /**
     * Spreads money taken on a merge across the orders inside it.
     *
     * <p>Oldest first, settling each in turn rather than splitting the payment
     * pro rata: a customer paying part of a round is paying off invoices, and a
     * statement showing three invoices each two-thirds paid describes something
     * that did not happen.
     */
    private void cascadeCollection(List<PosTransactionMst> sources, BigDecimal amount) {
        BigDecimal left = amount;
        for (PosTransactionMst t : sources) {
            if (left.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal payable = nvlAmount(t.getAmountTobePaid());
            BigDecimal paid    = nvlAmount(t.getGivenAmount());
            BigDecimal owing   = payable.subtract(paid);
            if (owing.compareTo(BigDecimal.ZERO) <= 0) continue;

            BigDecimal applied = left.min(owing);
            BigDecimal nowPaid = paid.add(applied);
            left = left.subtract(applied);

            t.setGivenAmount(nowPaid);
            boolean settled = isSettled(payable.subtract(nowPaid));
            t.setPaymentStatus(settled ? "Paid" : "Partial");
            if (settled) t.setStage(STAGE_COLLECTED);
            mstRepo.save(t);
            accounting.saleCollected(t, applied, t.getPaymentType());
        }
    }

    private static BigDecimal nvlAmount(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** Bulk assign delivery man to multiple confirmed invoices */
    @Transactional
    public int bulkAssignDeliveryMan(List<Long> invoiceIds, Long deliveryManId) {
        // Validated once, before any invoice is touched. If the person is not a
        // delivery man the whole call fails rather than reporting "0 assigned",
        // which reads as "none of those invoices were eligible" — a different
        // problem, and the dispatcher would go looking in the wrong place.
        var dm = deliveryManService.requireDeliveryMan(deliveryManId);

        int count = 0;
        for (Long id : invoiceIds) {
            try {
                PosTransactionMst mst = mstRepo.findById(id).orElse(null);
                // An order inside a merge is dispatched under that merge. Giving
                // it its own driver would put the same goods on two rounds.
                if (mst != null && mst.getMergePidTranMst() != null) {
                    log.warn("Invoice {} is part of a merge; assign the merge instead.",
                             mst.getInvoiceNo());
                    continue;
                }
                if (mst != null && ("Confirmed".equals(mst.getStage()) || "Submitted".equals(mst.getStage()))) {
                    mst.setDeliveryMan(dm.getPidEmployee());
                    mst.setDeliveryManName(dm.getEmpName());
                    mst.setStage("Delivery Assigned");
                    mstRepo.save(mst);
                    cascadeStage(mergedSourcesOf(mst), "Delivery Assigned",
                                 dm.getPidEmployee(), dm.getEmpName());
                    count++;
                }
            } catch (Exception e) {
                log.warn("Could not assign DM to invoice {}: {}", id, e.getMessage());
            }
        }
        return count;
    }

    private TransactionResponse buildResponse(PosTransactionMst m, List<PosTransactionDtl> lines,
                                               String supplierName, String customerName) {
        return buildResponse(m, lines, supplierName, customerName, null);
    }

    /** @param ref what the marketplace knows about this invoice, when it came from one */
    private TransactionResponse buildResponse(PosTransactionMst m, List<PosTransactionDtl> lines,
                                               String supplierName, String customerName,
                                               SaleSourceReferences.Ref ref) {
        /* A cancelled document owes nothing. Its journals were reversed and its
           goods went back, so the figure it was once worth is history rather
           than debt — and a screen that prints it as due invites somebody to
           chase a customer for an invoice nobody has to pay. */
        BigDecimal due = STATUS_VOID.equals(m.getStatus()) ? BigDecimal.ZERO
                : TransactionCalculator.calculateDue(
                        m.getAmountTobePaid() != null ? m.getAmountTobePaid() : BigDecimal.ZERO,
                        m.getGivenAmount());
        // Names for the lines, in one query rather than one per line.
        //
        // The field was declared on the DTO from the start but never filled in,
        // so every caller got a null and had to fall back to the id — a POS
        // receipt that listed "#7" instead of the medicine dispensed.
        Map<Long, String> productNames = lines.isEmpty() ? Map.of()
                : productRepo.findAllById(lines.stream()
                        .map(PosTransactionDtl::getPidProduct)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList())
                    .stream()
                    .collect(Collectors.toMap(PosProductInfo::getPidProduct,
                                              PosProductInfo::getProductName,
                                              (a, b) -> a));

        var lineResp = lines.stream().map(d -> TransactionResponse.TransactionLineResponse.builder()
                .lineId(d.getPidTranDtl()).productId(d.getPidProduct())
                .productName(productNames.get(d.getPidProduct()))
                .batchNo(d.getBatchNo()).expiryDate(d.getExpiryDate())
                .quantity(d.getQuantity()).mnpPrice(d.getMnpPrice()).mrpPrice(d.getMrpPrice())
                .salesPrice(d.getSalesPrice()).discountPct(d.getDiscountPct())
                .flatDiscount(d.getFlatDiscount()).totalPrice(d.getTotalPrice())
                .salesUnit(d.getSalesUnit()).returnQty(d.getReturnQty()).build()).toList();

        return TransactionResponse.builder()
                .id(m.getPidTranMst()).transactionType(m.getTransactionType())
                .invoiceNo(m.getInvoiceNo()).transactionDate(m.getTransactionDate())
                .paymentType(m.getPaymentType()).stage(m.getStage()).status(m.getStatus())
                .paymentStatus(m.getPaymentStatus()).supplierId(m.getPidSupCust())
                .supplierName(supplierName).customerId(m.getPidSupCust()).customerName(customerName)
                .branchId(m.getPidBranch()).deliveryAddress(m.getDeliveryAddress())
                .deliveryManId(m.getDeliveryMan()).deliveryManName(m.getDeliveryManName())
                .deliveryCharge(m.getDeliveryCharge())
                .subtotal(m.getTotalAmount()).invoiceDiscPct(m.getInvoiceDisPct())
                .invoiceDiscAmt(m.getInvoiceDisAmt()).amountTobePaid(m.getAmountTobePaid())
                .givenAmount(m.getGivenAmount()).due(due).remarks(m.getRemarks())
                .source(m.getFromSource())
                .ecomOrderNo(ref == null ? null : ref.orderNo())
                .deliveryLatitude(ref == null ? null : ref.latitude())
                .deliveryLongitude(ref == null ? null : ref.longitude())
                .deliveryPhone(ref == null ? null : ref.deliveryPhone())
                .deliveryRecipient(ref == null ? null : ref.recipient())
                .cancelledBy(m.getCancelledBy()).cancelledDate(m.getCancelledDate())
                .cancelReason(m.getCancelReason())
                .mergedIntoId(m.getMergePidTranMst())
                .mergeInvoice("Y".equals(m.getIsMergeInvoice()) ? Boolean.TRUE : null)
                .lines(lineResp).build();
    }
}
