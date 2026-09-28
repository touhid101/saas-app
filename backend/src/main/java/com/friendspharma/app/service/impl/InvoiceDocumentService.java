package com.friendspharma.app.service.impl;

import com.friendspharma.app.dto.response.InvoiceDocumentResponse;
import com.friendspharma.app.dto.response.InvoiceDocumentResponse.Branch;
import com.friendspharma.app.dto.response.InvoiceDocumentResponse.Invoice;
import com.friendspharma.app.dto.response.InvoiceDocumentResponse.Line;
import com.friendspharma.app.dto.response.InvoiceDocumentResponse.Party;
import com.friendspharma.app.dto.response.InvoiceDocumentResponse.Totals;
import com.friendspharma.app.entity.AllBranch;
import com.friendspharma.app.entity.PosTransactionDtl;
import com.friendspharma.app.entity.PosTransactionMst;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.AclUserRepository;
import com.friendspharma.app.repository.AllBranchRepository;
import com.friendspharma.app.repository.HrEmployeeRepository;
import com.friendspharma.app.repository.PosCustomerInfoRepository;
import com.friendspharma.app.repository.PosBoxInfoRepository;
import com.friendspharma.app.repository.PosLeafInfoRepository;
import com.friendspharma.app.repository.PosProductInfoRepository;
import com.friendspharma.app.repository.PosTransactionDtlRepository;
import com.friendspharma.app.repository.PosTransactionMstRepository;
import com.friendspharma.app.util.AmountInWords;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A sale, assembled for printing.
 *
 * <p>One document behind both layouts — the A4 invoice and the till receipt.
 * They differ in what they show and how it is arranged, never in the numbers,
 * because the numbers are computed once, here.
 *
 * <p><b>The letterhead is the branch's own.</b> Name, address, phone and logo
 * come from ALL_BRANCH for the branch that raised the sale, so a pharmacy prints
 * under its own identity — and a group running several prints each under the
 * right one. Nothing about the letterhead is hard-coded.
 *
 * <p>Branch-scoped like every other read: an invoice belonging to another
 * pharmacy is a 404, not a printable document.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceDocumentService {

    private final PosTransactionMstRepository mstRepo;
    /** The seeded placeholder each branch sells anonymous cash sales against. */
    private static final String WALK_IN = "Walk-in Customer";
    private final PosTransactionDtlRepository dtlRepo;
    private final PosCustomerInfoRepository customerRepo;
    private final PosProductInfoRepository productRepo;
    private final PosBoxInfoRepository boxRepo;
    private final PosLeafInfoRepository leafRepo;
    private final AllBranchRepository branchRepo;
    private final HrEmployeeRepository employeeRepo;
    private final AclUserRepository userRepo;
    private final BranchScopeService branchScope;

    @Transactional(readOnly = true)
    public InvoiceDocumentResponse forSale(Long saleId) {
        PosTransactionMst m = mstRepo.findById(saleId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice", saleId));

        // The tenancy wall. resolve() throws for anyone who may not read this
        // branch, so an invoice id from another pharmacy cannot be printed by
        // guessing the number.
        Long allowed = branchScope.resolve(m.getPidBranch());
        if (!allowed.equals(m.getPidBranch())) {
            throw new ResourceNotFoundException("Invoice", saleId);
        }

        List<PosTransactionDtl> details = dtlRepo.findByPidTranMst(saleId);
        List<Line> lines = linesOf(details);

        return InvoiceDocumentResponse.builder()
                .branch(branchOf(m))
                .invoice(invoiceOf(m))
                .customer(customerOf(m))
                .lines(lines)
                .totals(totalsOf(m, lines))
                .account(accountOf(m))
                .amountInWords(AmountInWords.format(m.getAmountTobePaid()))
                .build();
    }

    /* ── Letterhead ──────────────────────────────────────────────────────── */

    private Branch branchOf(PosTransactionMst m) {
        AllBranch b = m.getPidBranch() == null ? null
                : branchRepo.findById(m.getPidBranch()).orElse(null);
        if (b == null) return Branch.builder().build();

        String trading = blankToNull(b.getReportHeader());
        return Branch.builder()
                .name(b.getBranchName())
                // The name over the door where one is set, the registered name
                // otherwise — never an empty heading.
                .tradingName(trading != null ? trading : b.getBranchName())
                .address(blankToNull(b.getBranchAddress()))
                .phone(blankToNull(b.getBranchPhoneNo()))
                .email(blankToNull(b.getBranchEmail()))
                .logoUrl(blankToNull(b.getLogoUrl()))
                .build();
    }

    /* ── The document ────────────────────────────────────────────────────── */

    private Invoice invoiceOf(PosTransactionMst m) {
        String createdBy = m.getInsertedBy();
        // The full name where the username belongs to a login, so the invoice
        // reads as a person rather than as an account.
        String displayName = createdBy == null ? null
                : userRepo.findByUserNameIgnoreCase(createdBy)
                          .map(u -> blankToNull(u.getFullName()) != null ? u.getFullName() : createdBy)
                          .orElse(createdBy);

        String dmPhone = m.getDeliveryMan() == null ? null
                : employeeRepo.findById(m.getDeliveryMan())
                              .map(e -> blankToNull(e.getPhone()))
                              .orElse(null);

        return Invoice.builder()
                .id(m.getPidTranMst())
                .invoiceNo(m.getInvoiceNo())
                .challanNo(blankToNull(m.getChallanNo()))
                .date(m.getTransactionDate())
                .printedAt(LocalDateTime.now())
                .createdBy(displayName)
                .deliveryManName(blankToNull(m.getDeliveryManName()))
                .deliveryManPhone(dmPhone)
                .paymentType(m.getPaymentType())
                .paymentStatus(m.getPaymentStatus())
                .stage(m.getStage())
                .remarks(blankToNull(m.getRemarks()))
                .build();
    }

    private Party customerOf(PosTransactionMst m) {
        if (m.getPidSupCust() == null) return Party.builder().name("Walk-in customer").build();

        return customerRepo.findById(m.getPidSupCust())
                .map(c -> Party.builder()
                        .name(c.getCustomerName())
                        .mobile(blankToNull(c.getMobileNo()))
                        // The address the goods went to beats the one on file:
                        // a delivery address is entered because it differs.
                        .address(blankToNull(m.getDeliveryAddress()) != null
                                ? m.getDeliveryAddress() : blankToNull(c.getAddress()))
                        .build())
                .orElseGet(() -> Party.builder().name("Walk-in customer").build());
    }

    /* ── The customer's wider balance ────────────────────────────────────── */

    /**
     * What this customer owes beyond the invoice in hand.
     *
     * <p>Printed as a reminder, so it is deliberately narrow about when it
     * applies:
     *
     * <ul>
     *   <li><b>Never for a walk-in.</b> "Walk-in Customer" is one shared row per
     *       branch — its balance is every anonymous credit sale added together,
     *       and printing that as one person's debt would be wrong.</li>
     *   <li><b>Never when nothing is owed.</b> A reminder that says zero is not a
     *       reminder, and a paid invoice should not carry a demand.</li>
     * </ul>
     *
     * <p>The walk-in is recognised by the name the system seeds it with, there
     * being no flag on the customer to ask. That is the same convention
     * TenantOnboardingService writes, so the two agree — but a flag would be
     * better than a name, and this is the reason to add one.
     */
    /**
     * The most invoices a printed statement will list.
     *
     * <p>Past this the sheet stops being an invoice with a reminder on it. The
     * remainder is summarised in one line so the grand total is still the whole
     * debt — a truncated list that quietly understated it would be worse than no
     * list at all.
     */
    private static final int MAX_STATEMENT_ROWS = 12;

    private InvoiceDocumentResponse.Account accountOf(PosTransactionMst m) {
        Long customerId = m.getPidSupCust();
        if (customerId == null) return null;

        String name = customerRepo.findById(customerId)
                .map(c -> c.getCustomerName()).orElse(null);
        if (name == null || name.trim().equalsIgnoreCase(WALK_IN)) return null;

        // A draft is not a sale yet, so it is not in this list — the same rule
        // every other money figure in the system follows.
        boolean draft = "Submitted".equalsIgnoreCase(m.getStage());

        List<Object[]> open = mstRepo.customerOpenInvoices(
                m.getPidOrg(), m.getPidBranch(), customerId);
        if (open.isEmpty()) return null;

        LocalDate today = LocalDate.now();
        List<InvoiceDocumentResponse.OpenInvoice> rows = new ArrayList<>();
        BigDecimal listed = BigDecimal.ZERO;
        BigDecimal all = BigDecimal.ZERO;
        LocalDate oldest = null;

        long kept = 0;

        for (Object[] r : open) {
            LocalDate date = ((java.sql.Date) r[0]).toLocalDate();
            BigDecimal due = new BigDecimal(r[3].toString()).setScale(2, RoundingMode.HALF_UP);
            // The query rounds before differencing, so anything reaching here is
            // a real balance; this guards the case of a row that rounds away.
            if (due.signum() <= 0) continue;

            kept++;
            all = all.add(due);
            if (oldest == null || date.isBefore(oldest)) oldest = date;

            // Counted on rows kept, not on the loop index: a row that rounded
            // away would otherwise consume a slot and shift the cap.
            if (rows.size() < MAX_STATEMENT_ROWS) {
                listed = listed.add(due);
                rows.add(InvoiceDocumentResponse.OpenInvoice.builder()
                        .date(date)
                        .invoiceNo(r[1] == null ? null : r[1].toString())
                        .invoiceTotal(new BigDecimal(r[2].toString()).setScale(2, RoundingMode.HALF_UP))
                        .due(due)
                        .ageDays(ChronoUnit.DAYS.between(date, today))
                        .current(m.getPidTranMst().equals(((Number) r[4]).longValue()))
                        .build());
            }
        }

        if (kept == 0) return null;          // every balance rounded away
        long more = Math.max(0, kept - rows.size());

        return InvoiceDocumentResponse.Account.builder()
                .invoices(rows)
                .moreCount(more)
                .moreDue(all.subtract(listed))
                .grandTotal(all)
                .openInvoices(kept)
                .currentIsDraft(draft)
                .oldestDue(oldest)
                .build();
    }
    /* ── Lines ───────────────────────────────────────────────────────────── */

    private List<Line> linesOf(List<PosTransactionDtl> details) {
        var products = productRepo.findAllById(
                details.stream().map(PosTransactionDtl::getPidProduct)
                       .filter(java.util.Objects::nonNull).toList());

        Map<Long, String> names = products.stream()
                .collect(Collectors.toMap(p -> p.getPidProduct(), p -> p.getProductName(), (a, b) -> a));

        // Pieces per box, per product, for apportioning the MRP below.
        Map<Long, Integer> pcsPerBox = new java.util.HashMap<>();
        for (var p : products) {
            Integer perBox = pcsPerBoxOf(p.getPidBox());
            if (perBox != null) pcsPerBox.put(p.getPidProduct(), perBox);
        }

        List<Line> out = new ArrayList<>();
        int sl = 1;
        for (PosTransactionDtl d : details) {
            BigDecimal unitQty = nz(d.getQuantity());
            BigDecimal total   = nz(d.getTotalPrice());

            // Pieces behind the units sold. NO_OF_PCS is the packing frozen on
            // the line at the time of sale, so a later change to the product's
            // packing cannot rewrite what an old invoice says was handed over.
            int perUnit = d.getNoOfPcs() == null || d.getNoOfPcs() <= 0 ? 1 : d.getNoOfPcs();

            /* The MRP for the unit actually sold.
             *
             * POS_TRANSACTION_DTL.MRP_PRICE holds the product's pack MRP, not
             * the price of the unit on the line — SalesServiceImpl writes
             * product.getMrpPrice() whatever the line is sold in. On a line sold
             * in pieces that made the printed "Total MRP" the price of a whole
             * box: a strip of Ketorol showed ৳184.00 against ৳15.34 charged, and
             * a discount of ৳168.66 that nobody gave.
             *
             * Apportioned the way SalesPriceCalculator prices a unit in the
             * first place — boxMrp x pieces-in-unit / pieces-per-box — so the
             * invoice states the same MRP the till charged against. Where the
             * packing is unknown the stored figure stands rather than a guess.
             */
            BigDecimal mrp = unitMrp(nz(d.getMrpPrice()), perUnit, pcsPerBox.get(d.getPidProduct()));
            BigDecimal totalMrp = mrp.multiply(unitQty).setScale(2, RoundingMode.HALF_UP);

            out.add(Line.builder()
                    .sl(sl++)
                    .product(names.getOrDefault(d.getPidProduct(), "#" + d.getPidProduct()))
                    .batchNo(blankToNull(d.getBatchNo()))
                    .expiryDate(d.getExpiryDate())
                    .mrp(mrp)
                    .unit(d.getSalesUnit())
                    .unitQty(unitQty)
                    .totalMrp(totalMrp)
                    // Stated as money, not a percentage: the customer is checking
                    // what came off the price, not the rate that produced it.
                    .discountAmount(totalMrp.subtract(total).max(BigDecimal.ZERO)
                            .setScale(2, RoundingMode.HALF_UP))
                    .unitPrice(nz(d.getSalesPrice()))
                    .quantityPcs(unitQty.multiply(BigDecimal.valueOf(perUnit)))
                    .totalPrice(total)
                    .build());
        }
        return out;
    }

    /* ── Foot ────────────────────────────────────────────────────────────── */

    /**
     * The foot, summed from the lines the body printed.
     *
     * <p>Deliberately not recomputed from the detail rows: the MRP on those is
     * the pack price, and summing it here while the lines above show the
     * apportioned figure would put two different totals on one sheet.
     */
    private Totals totalsOf(PosTransactionMst m, List<Line> lines) {
        BigDecimal totalMrp = lines.stream()
                .map(Line::getTotalMrp)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal subtotal = lines.stream()
                .map(Line::getTotalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        /* Rounded to the poisha before they are compared.
         *
         * Both are stored to four decimal places, so an invoice of 5.2920 paid
         * with 5.2900 left a "balance" of two-tenths of a poisha — a sum nobody
         * can pay and no drawer can hold. It printed as 0.00 on the invoice
         * while still counting as a debt on the Customer Due report, which put a
         * customer on a chase list for nothing. The smallest unit of the
         * currency is the smallest thing that can be owed. */
        BigDecimal payable = nz(m.getAmountTobePaid()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal paid    = nz(m.getGivenAmount()).setScale(2, RoundingMode.HALF_UP);

        // What has come back against this invoice. Shown even at zero on the A4,
        // because a blank line where money should be invites the question.
        BigDecimal returned = mstRepo.findByPidTranMstReturnOrderByPidTranMstDesc(m.getPidTranMst()).stream()
                .map(r -> nz(r.getAmountTobePaid()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        return Totals.builder()
                .totalMrp(totalMrp)
                .subtotal(subtotal)
                .discount(totalMrp.subtract(subtotal).max(BigDecimal.ZERO))
                .specialDiscount(nz(m.getInvoiceDisAmt()))
                .deliveryCharge(nz(m.getDeliveryCharge()))
                .totalBill(payable)
                .returnAmount(returned)
                // Capped at the bill: cash tendered above it went back as change
                // and was never payment against the invoice.
                .paid(paid.min(payable))
                .balanceDue(payable.subtract(paid).max(BigDecimal.ZERO))
                .build();
    }

    /* ── plumbing ────────────────────────────────────────────────────────── */

    /** Pieces per box: leaves in the box times pieces in a leaf. Null if unset. */
    private Integer pcsPerBoxOf(Long pidBox) {
        if (pidBox == null) return null;
        var box = boxRepo.findById(pidBox).orElse(null);
        if (box == null || box.getNoOfLeaf() == null || box.getPidLeaf() == null) return null;
        var leaf = leafRepo.findById(box.getPidLeaf()).orElse(null);
        if (leaf == null || leaf.getNoOfPcs() == null || leaf.getNoOfPcs() <= 0) return null;
        int perBox = box.getNoOfLeaf() * leaf.getNoOfPcs();
        return perBox > 0 ? perBox : null;
    }

    /**
     * A unit's share of the pack MRP.
     *
     * <p>Returns the pack MRP untouched where the packing is unknown: an
     * unapportioned figure is at least a real one, and inventing a divisor would
     * put a price on the invoice that nothing in the system agrees with.
     */
    private static BigDecimal unitMrp(BigDecimal packMrp, int pcsInUnit, Integer pcsPerBox) {
        if (pcsPerBox == null || pcsPerBox <= 0 || pcsInUnit <= 0) return packMrp;
        return packMrp.multiply(BigDecimal.valueOf(pcsInUnit))
                      .divide(BigDecimal.valueOf(pcsPerBox), 4, RoundingMode.HALF_UP);
    }

    /**
     * The single row a scalar aggregate returns.
     *
     * <p>A native query projecting one row comes back as {@code Object[]} or as
     * a one-element list of them depending on how Hibernate types the
     * projection. Handled here rather than letting a ClassCastException reach a
     * printed invoice.
     */
    private static Object[] firstRow(Object result) {
        if (result instanceof Object[] arr) {
            return (arr.length > 0 && arr[0] instanceof Object[] nested) ? nested : arr;
        }
        if (result instanceof java.util.List<?> list && !list.isEmpty()
                && list.get(0) instanceof Object[] arr) {
            return arr;
        }
        return null;
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
