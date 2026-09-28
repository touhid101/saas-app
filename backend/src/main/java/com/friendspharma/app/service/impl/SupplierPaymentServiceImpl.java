package com.friendspharma.app.service.impl;

import static com.friendspharma.app.util.DateRange.since;
import static com.friendspharma.app.util.DateRange.until;
import com.friendspharma.app.dto.request.AdvanceRecoveryRequest;
import com.friendspharma.app.dto.request.SupplierPaymentRequest;
import com.friendspharma.app.dto.response.SupplierPaymentResponse;
import com.friendspharma.app.entity.PosSupplierInfo;
import com.friendspharma.app.entity.PosSupplierPayment;
import com.friendspharma.app.entity.PosTransactionMst;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.exception.custom.ValidationException;
import com.friendspharma.app.repository.PosSupplierInfoRepository;
import com.friendspharma.app.repository.PosSupplierPaymentRepository;
import com.friendspharma.app.repository.PosTransactionMstRepository;
import com.friendspharma.app.security.SecurityUtils;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Paying suppliers, and everything that follows from having a payment ledger.
 *
 * <p>The arithmetic of what is owed deliberately lives in the database views
 * {@code V_PURCHASE_PAYABLE} and {@code V_SUPPLIER_ACCOUNT}, not here. Before
 * they existed each report computed outstanding itself, and every copy shared
 * the same three mistakes: draft purchases counted as liabilities, the payment
 * ledger ignored, and purchase-return credits never netted off. A screen and a
 * report that disagree about what a supplier is owed is worse than either being
 * wrong on its own, because there is no way to tell which to believe.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierPaymentServiceImpl {

    private final PosSupplierPaymentRepository payRepo;
    private final BranchScopeService branchScope;
    private final PosSupplierInfoRepository supplierRepo;
    private final PosTransactionMstRepository mstRepo;
    private final DocumentNumberService documentNumbers;
    /** Accounts: every financial event this service commits is booked through here, in the same transaction. */
    private final AccountingHooks accounting;

    /** Same tolerance the sales side settles on — sub-paisa is not a debt. */
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private static final Set<String> MODES =
            Set.of("CASH", "BANK", "CHEQUE", "BKASH", "NAGAD", "ROCKET", "ADJUSTMENT");

    /* ══════════════════════════════════════════════════════════════════════
       PAY
       ══════════════════════════════════════════════════════════════════════ */

    @Transactional
    public SupplierPaymentResponse record(SupplierPaymentRequest req) {
        Long orgId = requireOrg();
        PosSupplierInfo supplier = supplierRepo.findById(req.getSupplierId())
                .orElseThrow(() -> new ResourceNotFoundException("Supplier", req.getSupplierId()));
        if (supplier.getPidOrg() != null && !supplier.getPidOrg().equals(orgId)) {
            throw new ResourceNotFoundException("Supplier", req.getSupplierId());
        }

        String mode = req.getPaymentMode().trim().toUpperCase();
        if (!MODES.contains(mode)) {
            throw new ValidationException(
                    "'" + req.getPaymentMode() + "' is not a payment mode. Use one of: "
                    + String.join(", ", new java.util.TreeSet<>(MODES)) + ".");
        }

        LocalDate when = req.getPaymentDate() != null ? req.getPaymentDate() : LocalDate.now();
        if (when.isAfter(LocalDate.now())) {
            throw new ValidationException("A payment cannot be dated in the future.");
        }

        BigDecimal amount = req.getAmount().setScale(4, RoundingMode.HALF_UP);
        PosTransactionMst purchase = null;
        SupplierPaymentResponse.PayableInvoice payable = null;

        if (req.getPurchaseId() != null) {
            purchase = mstRepo.findById(req.getPurchaseId())
                    .orElseThrow(() -> new ResourceNotFoundException("Purchase", req.getPurchaseId()));

            if (!"PU".equals(purchase.getTransactionType())) {
                throw new ValidationException("Document " + purchase.getInvoiceNo() + " is not a purchase.");
            }
            if (!Objects.equals(purchase.getPidSupCust(), supplier.getPidSupplier())) {
                throw new ValidationException(
                        "Purchase " + purchase.getInvoiceNo() + " belongs to a different supplier.");
            }
            // The payable view only contains approved purchases, so an
            // unapproved one simply has no row — say why rather than reporting
            // a missing invoice.
            payable = payableOrNull(purchase.getPidTranMst());
            if (payable == null) {
                throw new BusinessException(
                        "Purchase " + purchase.getInvoiceNo() + " has not been approved, so it is not "
                        + "yet a liability. Approve the purchase before paying against it.",
                        HttpStatus.CONFLICT, "PURCHASE_NOT_APPROVED");
            }
        }

        BigDecimal advance = resolveAdvance(req, purchase, payable, amount);
        // What is left after the advance is carved out — the money actually
        // settling bills.
        BigDecimal settling = amount.subtract(advance);

        if (payable != null) {
            BigDecimal due = payable.getDue();
            if (due.compareTo(TOLERANCE) < 0 && settling.compareTo(TOLERANCE) > 0) {
                throw new BusinessException(
                        "Purchase " + purchase.getInvoiceNo() + " is already settled — nothing is "
                        + "outstanding on it. Record this as a payment on account instead.",
                        HttpStatus.CONFLICT, "INVOICE_ALREADY_SETTLED");
            }
            if (settling.subtract(due).compareTo(TOLERANCE) > 0) {
                // The message names the advance route, because paying above the
                // bill is exactly what happens when the discount is handed over
                // with it — and a flat refusal would send someone to enter the
                // difference as something it is not.
                throw new ValidationException(
                        "Purchase " + purchase.getInvoiceNo() + " has " + money(due) + " outstanding, "
                        + "so " + money(settling) + " cannot be put against it. Either pay " + money(due)
                        + " and record the extra " + money(settling.subtract(due)) + " as an advance the "
                        + "supplier owes back, or split it across the other open bills.");
            }
        }

        PosSupplierPayment p = new PosSupplierPayment();
        p.setPidOrg(orgId);
        p.setPidCompany(purchase != null ? purchase.getPidCompany() : SecurityUtils.getCurrentCompanyId());
        Long branch = purchase != null ? purchase.getPidBranch() : SecurityUtils.getCurrentBranchId();
        if (branch == null) {
            throw new ValidationException("Your account has no branch assigned, so the payment cannot be booked.");
        }
        p.setPidBranch(branch);
        p.setPidSupplier(supplier.getPidSupplier());
        p.setPidTranMst(purchase != null ? purchase.getPidTranMst() : null);
        p.setVoucherNo(documentNumbers.nextInvoiceNo("SP"));
        p.setPaymentDate(when);
        p.setAmount(amount);
        p.setPaymentMode(mode);
        p.setBankName(trim(req.getBankName()));
        p.setChequeNo(trim(req.getChequeNo()));
        p.setChequeDate(req.getChequeDate());
        p.setReferenceNo(trim(req.getReferenceNo()));
        p.setRemarks(trim(req.getRemarks()));
        p.setEntryType(PosSupplierPayment.PAYMENT);
        p.setSource(PosSupplierPayment.MANUAL);
        p.setAdvanceAmount(advance);
        p.setStatus(PosSupplierPayment.ACTIVE);

        PosSupplierPayment saved = payRepo.save(p);
        accounting.supplierPaymentRecorded(saved);

        // The purchase carries its own PAYMENT_STATUS, which the purchase list
        // and several older screens read. Left alone it would still say UNPAID
        // after the bill was settled here.
        if (purchase != null) syncPaymentStatus(purchase);

        log.info("Supplier payment {} — {} to {} ({}{}{})", saved.getVoucherNo(), money(amount),
                supplier.getSupplierName(), mode,
                purchase != null ? " against " + purchase.getInvoiceNo() : ", on account",
                advance.compareTo(BigDecimal.ZERO) > 0 ? ", incl. advance " + money(advance) : "");

        return toResponse(saved, supplier.getSupplierName(),
                purchase != null ? purchase.getInvoiceNo() : null);
    }

    /**
     * How much of this payment is an advance rather than a settlement.
     *
     * <p>Three routes in, in order of how much the server can vouch for:
     * the invoice's own discount figure, an amount stated by the caller, or
     * nothing. An overpayment that declares neither is refused rather than
     * silently reclassified — the excess is far more often a typo than a rebate,
     * and quietly booking a receivable that nobody agreed is worse than a
     * rejected form.
     */
    private BigDecimal resolveAdvance(SupplierPaymentRequest req, PosTransactionMst purchase,
                                      SupplierPaymentResponse.PayableInvoice payable, BigDecimal amount) {
        BigDecimal advance;

        if (Boolean.TRUE.equals(req.getPayInvoiceDiscount())) {
            if (payable == null) {
                throw new ValidationException(
                        "Paying the invoice discount needs the invoice it belongs to. Choose the "
                        + "purchase being settled, or enter the advance amount directly.");
            }
            advance = nvl(payable.getInvoiceDiscAmt());
            if (advance.compareTo(BigDecimal.ZERO) <= 0) {
                throw new ValidationException(
                        "Purchase " + purchase.getInvoiceNo() + " carries no invoice discount, so "
                        + "there is none to pay across.");
            }
            BigDecimal stated = req.getAdvanceAmount();
            if (stated != null && stated.subtract(advance).abs().compareTo(TOLERANCE) > 0) {
                throw new ValidationException(
                        "Purchase " + purchase.getInvoiceNo() + " has a discount of " + money(advance)
                        + ", which does not match the advance of " + money(stated) + " entered. Clear "
                        + "one of the two.");
            }
        } else {
            advance = nvl(req.getAdvanceAmount()).setScale(4, RoundingMode.HALF_UP);
        }

        if (advance.compareTo(BigDecimal.ZERO) < 0) {
            throw new ValidationException("An advance cannot be negative.");
        }
        if (advance.subtract(amount).compareTo(TOLERANCE) > 0) {
            throw new ValidationException(
                    "The advance of " + money(advance) + " is more than the " + money(amount)
                    + " being paid. An advance is the part of a payment that settles no bill, so it "
                    + "cannot exceed the payment itself.");
        }
        return advance.min(amount);
    }

    /**
     * Mirrors the cash recorded on a purchase into the ledger.
     *
     * <p>Called from approval, which is also when the liability starts to exist.
     * Cash keyed onto a draft is an intention: mirroring it earlier would show
     * money paid against a bill the business has not agreed to.
     *
     * <p>Silent when there is nothing to mirror or a row already exists, so
     * re-approval cannot double the cash.
     */
    @Transactional
    public void mirrorInvoicePayment(PosTransactionMst purchase) {
        BigDecimal given = nvl(purchase.getGivenAmount());
        if (given.compareTo(BigDecimal.ZERO) <= 0) return;
        if (payRepo.existsByPidTranMstAndSource(purchase.getPidTranMst(), PosSupplierPayment.FROM_INVOICE)) return;

        // The full cash goes in — this row has to tie to what left the bank.
        // What it settles is AMOUNT less ADVANCE, which the payable view works
        // out for itself, so nothing is capped here. Truncating at the bill (as
        // this once did) silently discarded a rebate the supplier owes back.
        BigDecimal advance = nvl(purchase.getAdvanceAmount()).min(given);
        BigDecimal amount = given;
        if (amount.compareTo(BigDecimal.ZERO) <= 0) return;

        PosSupplierPayment p = new PosSupplierPayment();
        p.setPidOrg(purchase.getPidOrg());
        p.setPidCompany(purchase.getPidCompany());
        p.setPidBranch(purchase.getPidBranch());
        p.setPidSupplier(purchase.getPidSupCust());
        p.setPidTranMst(purchase.getPidTranMst());
        // Named for the invoice it mirrors rather than drawn from the number
        // series: no voucher was written for this, and issuing one would imply
        // a document that does not exist.
        p.setVoucherNo("INV-" + purchase.getInvoiceNo());
        p.setPaymentDate(purchase.getTransactionDate());
        p.setAmount(amount);
        p.setPaymentMode(purchase.getPaymentType() == null ? "CASH" : purchase.getPaymentType());
        p.setEntryType(PosSupplierPayment.PAYMENT);
        p.setSource(PosSupplierPayment.FROM_INVOICE);
        p.setAdvanceAmount(advance);
        p.setRemarks(advance.compareTo(BigDecimal.ZERO) > 0
                ? "Paid with the invoice, including " + money(advance) + " recoverable"
                : "Paid with the invoice");
        p.setStatus(PosSupplierPayment.ACTIVE);

        accounting.supplierPaymentRecorded(payRepo.save(p));
        log.info("Mirrored {} paid with purchase {} into the supplier ledger{}",
                money(amount), purchase.getInvoiceNo(),
                advance.compareTo(BigDecimal.ZERO) > 0 ? ", incl. advance " + money(advance) : "");
    }

    /**
     * Restates invoice-time advances that were carved from the whole discount.
     *
     * <p>"Pay the invoice discount too" on a purchase once took the total
     * discount — TP%, the line discounts and the invoice cut — as the advance
     * the supplier owed back. Only the invoice discount is ever handed across
     * and returned; the line discounts are the supplier's own price and were
     * never charged. A purchase saved under the old reading shows too large a
     * figure as recoverable, its bill as still due, and the Advances tab as
     * holding money the supplier never had.
     *
     * <p>For each such purchase in the caller's organisation this sets the
     * advance to the invoice discount, recomputes the payment status the way a
     * save does, corrects the mirrored supplier payment, and books the
     * difference in the ledger as a restatement (AP down, supplier advance
     * down) — the posted journal is left as it was. Purchases with no invoice
     * discount are not touched: there is nothing to restate them to, and
     * whether that money was an advance at all is a business decision.
     * Safe to run again; a repaired row no longer matches.
     */
    @Transactional
    public List<SupplierPaymentResponse.AdvanceRestatement> restateInvoiceAdvances() {
        Long orgId = requireOrg();
        List<SupplierPaymentResponse.AdvanceRestatement> out = new ArrayList<>();
        for (PosTransactionMst pu : mstRepo.findAdvanceCarvedFromTotalDiscount(orgId, "PU")) {
            BigDecimal given   = nvl(pu.getGivenAmount());
            BigDecimal payable = nvl(pu.getAmountTobePaid());
            BigDecimal before  = nvl(pu.getAdvanceAmount());
            BigDecimal after   = nvl(pu.getInvoiceDisAmt()).setScale(2, RoundingMode.HALF_UP).min(given);
            BigDecimal delta   = before.subtract(after).setScale(2, RoundingMode.HALF_UP);
            if (delta.signum() <= 0) continue;

            // The same reading of the cash as a save: only the part that is
            // not an advance settles the bill.
            BigDecimal settling = given.subtract(after);
            BigDecimal due = payable.subtract(settling);
            String statusBefore = pu.getPaymentStatus();
            String statusAfter = due.compareTo(BigDecimal.ZERO) <= 0 ? "PAID"
                    : settling.compareTo(BigDecimal.ZERO) > 0 ? "PARTIAL" : "UNPAID";
            pu.setAdvanceAmount(after);
            pu.setPaymentStatus(statusAfter);
            mstRepo.save(pu);

            boolean ledger = false;
            String reason = "line discounts of " + money(delta) + " on " + pu.getInvoiceNo()
                    + " were never handed across; the advance is the invoice discount " + money(after);
            for (PosSupplierPayment p : payRepo.findByPidTranMstAndSourceOrderByPidPaymentAsc(
                    pu.getPidTranMst(), PosSupplierPayment.FROM_INVOICE)) {
                if (!PosSupplierPayment.ACTIVE.equals(p.getStatus())) continue;
                // Only a mirror that still carries the old advance is restated;
                // one already corrected by hand is left alone.
                if (nvl(p.getAdvanceAmount()).subtract(before).abs().compareTo(TOLERANCE) > 0) continue;
                p.setAdvanceAmount(after);
                p.setRemarks(after.signum() > 0
                        ? "Paid with the invoice, including " + money(after) + " recoverable (restated)"
                        : "Paid with the invoice (advance restated)");
                payRepo.save(p);
                accounting.supplierAdvanceRestated(p, delta, reason);
                ledger = true;
            }

            log.info("Restated the advance on purchase {} from {} to {} ({} settled the bill; status {} -> {})",
                    pu.getInvoiceNo(), money(before), money(after), money(delta), statusBefore, statusAfter);
            out.add(SupplierPaymentResponse.AdvanceRestatement.builder()
                    .purchaseId(pu.getPidTranMst()).invoiceNo(pu.getInvoiceNo())
                    .supplierName(supplierRepo.findById(pu.getPidSupCust()).map(PosSupplierInfo::getSupplierName).orElse(null))
                    .advanceBefore(before).advanceAfter(after).movedToBill(delta)
                    .paymentStatusBefore(statusBefore).paymentStatusAfter(statusAfter)
                    .ledgerAdjusted(ledger)
                    .build());
        }
        return out;
    }

    /**
     * The supplier returning an advance.
     *
     * <p>Either in money, or as a credit against a later bill. The second is not
     * cash arriving — it is the advance being consumed by a settlement — so it
     * must name the purchase it credits, and the ledger records it as reducing
     * both the advance and that bill.
     */
    @Transactional
    public SupplierPaymentResponse recoverAdvance(AdvanceRecoveryRequest req) {
        Long orgId = requireOrg();
        PosSupplierInfo supplier = supplierRepo.findById(req.getSupplierId())
                .orElseThrow(() -> new ResourceNotFoundException("Supplier", req.getSupplierId()));
        if (supplier.getPidOrg() != null && !supplier.getPidOrg().equals(orgId)) {
            throw new ResourceNotFoundException("Supplier", req.getSupplierId());
        }

        String mode = req.getPaymentMode().trim().toUpperCase();
        if (!MODES.contains(mode)) {
            throw new ValidationException(
                    "'" + req.getPaymentMode() + "' is not a payment mode. Use one of: "
                    + String.join(", ", new java.util.TreeSet<>(MODES)) + ".");
        }

        LocalDate when = req.getRecoveryDate() != null ? req.getRecoveryDate() : LocalDate.now();
        if (when.isAfter(LocalDate.now())) {
            throw new ValidationException("A recovery cannot be dated in the future.");
        }

        BigDecimal amount = req.getAmount().setScale(4, RoundingMode.HALF_UP);
        BigDecimal held = advanceBalance(supplier.getPidSupplier());
        if (amount.subtract(held).compareTo(TOLERANCE) > 0) {
            throw new ValidationException(
                    supplier.getSupplierName() + " holds " + money(held) + " of our money, so "
                    + money(amount) + " cannot be recovered. Recovering more than was advanced would "
                    + "leave the supplier owing a balance that was never paid to them.");
        }

        PosTransactionMst purchase = null;
        if (req.getPurchaseId() != null) {
            purchase = mstRepo.findById(req.getPurchaseId())
                    .orElseThrow(() -> new ResourceNotFoundException("Purchase", req.getPurchaseId()));
            if (!Objects.equals(purchase.getPidSupCust(), supplier.getPidSupplier())) {
                throw new ValidationException(
                        "Purchase " + purchase.getInvoiceNo() + " belongs to a different supplier.");
            }
            SupplierPaymentResponse.PayableInvoice payable = payableOrNull(purchase.getPidTranMst());
            if (payable == null) {
                throw new BusinessException(
                        "Purchase " + purchase.getInvoiceNo() + " has not been approved, so the advance "
                        + "cannot be credited against it yet.",
                        HttpStatus.CONFLICT, "PURCHASE_NOT_APPROVED");
            }
            if (amount.subtract(payable.getDue()).compareTo(TOLERANCE) > 0) {
                throw new ValidationException(
                        "Purchase " + purchase.getInvoiceNo() + " only has " + money(payable.getDue())
                        + " outstanding, so " + money(amount) + " cannot be credited against it. "
                        + "Credit " + money(payable.getDue()) + " here and take the rest in money.");
            }
        } else if ("ADJUSTMENT".equals(mode)) {
            throw new ValidationException(
                    "An adjustment has to name the bill it is credited against — otherwise nothing "
                    + "records where the money went. Choose the purchase, or pick how the money came back.");
        }

        Long branch = purchase != null ? purchase.getPidBranch() : SecurityUtils.getCurrentBranchId();
        if (branch == null) {
            throw new ValidationException("Your account has no branch assigned, so the recovery cannot be booked.");
        }

        PosSupplierPayment p = new PosSupplierPayment();
        p.setPidOrg(orgId);
        p.setPidCompany(purchase != null ? purchase.getPidCompany() : SecurityUtils.getCurrentCompanyId());
        p.setPidBranch(branch);
        p.setPidSupplier(supplier.getPidSupplier());
        p.setPidTranMst(purchase != null ? purchase.getPidTranMst() : null);
        p.setVoucherNo(documentNumbers.nextInvoiceNo("AR"));
        p.setPaymentDate(when);
        p.setAmount(amount);
        p.setPaymentMode(mode);
        p.setEntryType(PosSupplierPayment.ADVANCE_RECOVERY);
        p.setSource(PosSupplierPayment.MANUAL);
        p.setAdvanceAmount(BigDecimal.ZERO);
        p.setBankName(trim(req.getBankName()));
        p.setChequeNo(trim(req.getChequeNo()));
        p.setChequeDate(req.getChequeDate());
        p.setReferenceNo(trim(req.getReferenceNo()));
        p.setRemarks(trim(req.getRemarks()));
        p.setStatus(PosSupplierPayment.ACTIVE);

        PosSupplierPayment saved = payRepo.save(p);
        accounting.supplierPaymentRecorded(saved);
        if (purchase != null) syncPaymentStatus(purchase);

        log.info("Advance recovery {} — {} back from {} ({}{})", saved.getVoucherNo(), money(amount),
                supplier.getSupplierName(), mode,
                purchase != null ? " against " + purchase.getInvoiceNo() : "");

        return toResponse(saved, supplier.getSupplierName(),
                purchase != null ? purchase.getInvoiceNo() : null);
    }

    /** What the supplier still holds of ours. */
    @Transactional(readOnly = true)
    public BigDecimal advanceBalance(Long supplierId) {
        return nvl(payRepo.advanceBalance(supplierId));
    }

    /** Advances still outstanding, newest first, for the recovery screen. */
    @Transactional(readOnly = true)
    public List<SupplierPaymentResponse.Advance> advances(Long supplierId, boolean openOnly) {
        return payRepo.advances(requireOrg(), branch(), supplierId, openOnly).stream()
                .map(r -> SupplierPaymentResponse.Advance.builder()
                        .supplierId(lng(r[0])).supplierName(str(r[1]))
                        .advanced(dec(r[2])).recovered(dec(r[3])).balance(dec(r[4]))
                        .firstAdvance(date(r[5])).lastMovement(date(r[6]))
                        .build())
                .toList();
    }

    /**
     * Reverse a payment.
     *
     * <p>Never a delete. The money left the business and the reason it was
     * reversed — a bounced cheque, a duplicate entry — is the part someone will
     * need later.
     */
    @Transactional
    public SupplierPaymentResponse cancel(Long id, String reason) {
        PosSupplierPayment p = payRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier payment", id));
        requireSameOrg(p);

        if (PosSupplierPayment.CANCELLED.equals(p.getStatus())) {
            throw new BusinessException(
                    "Payment " + p.getVoucherNo() + " is already cancelled.",
                    HttpStatus.CONFLICT, "ALREADY_CANCELLED");
        }
        if (reason == null || reason.isBlank()) {
            throw new ValidationException("Say why the payment is being reversed — it is the part of "
                                        + "the record that explains the entry.");
        }

        if (PosSupplierPayment.FROM_INVOICE.equals(p.getSource())) {
            throw new BusinessException(
                    "Voucher " + p.getVoucherNo() + " is the cash recorded on purchase "
                    + invoiceNo(p.getPidTranMst()) + " itself, not a separate payment. Correct it on "
                    + "the purchase, so the invoice and the ledger cannot disagree.",
                    HttpStatus.CONFLICT, "INVOICE_SOURCED_PAYMENT");
        }
        // Reversing a payment takes its advance away too. If the supplier has
        // already given that money back, removing it now would leave them
        // holding a negative balance — a debt to us that was never advanced.
        if (PosSupplierPayment.PAYMENT.equals(p.getEntryType())
                && nvl(p.getAdvanceAmount()).compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal held = advanceBalance(p.getPidSupplier());
            if (nvl(p.getAdvanceAmount()).subtract(held).compareTo(TOLERANCE) > 0) {
                throw new BusinessException(
                        "Voucher " + p.getVoucherNo() + " carries an advance of "
                        + money(nvl(p.getAdvanceAmount())) + ", but only " + money(held)
                        + " is still held — the rest has already been recovered. Reverse the "
                        + "recovery first, then this payment.",
                        HttpStatus.CONFLICT, "ADVANCE_ALREADY_RECOVERED");
            }
        }

        p.setStatus(PosSupplierPayment.CANCELLED);
        p.setCancelReason(reason.trim());
        PosSupplierPayment saved = payRepo.save(p);
        accounting.supplierPaymentCancelled(saved, reason.trim());

        if (p.getPidTranMst() != null) {
            mstRepo.findById(p.getPidTranMst()).ifPresent(this::syncPaymentStatus);
        }

        log.info("Supplier payment {} cancelled — {}", p.getVoucherNo(), reason.trim());
        return toResponse(saved, supplierName(p.getPidSupplier()), invoiceNo(p.getPidTranMst()));
    }

    /**
     * Keeps {@code POS_TRANSACTION_MST.PAYMENT_STATUS} honest.
     *
     * <p>That column predates the ledger and is read by the purchase list. It is
     * a cache of what the payable view already knows, so it is recomputed from
     * the view rather than incremented — an increment drifts the first time a
     * payment is cancelled.
     */
    private void syncPaymentStatus(PosTransactionMst purchase) {
        SupplierPaymentResponse.PayableInvoice v = payableOrNull(purchase.getPidTranMst());
        if (v == null) return;

        BigDecimal settled = v.getPaidAtInvoice().add(v.getPaidLater()).add(v.getReturnCredit());
        String status = v.getDue().compareTo(TOLERANCE) < 0 ? "PAID"
                      : settled.compareTo(BigDecimal.ZERO) > 0 ? "PARTIAL"
                      : "UNPAID";
        if (!status.equals(purchase.getPaymentStatus())) {
            purchase.setPaymentStatus(status);
            mstRepo.save(purchase);
        }
    }

    /* ══════════════════════════════════════════════════════════════════════
       READ
       ══════════════════════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public Page<SupplierPaymentResponse> list(Long supplierId, LocalDate from, LocalDate to,
                                              String mode, String q, boolean includeCancelled,
                                              Pageable pageable) {
        Page<PosSupplierPayment> page = payRepo.search(
                // branch() as every other read here does: the ledger shows this
                // pharmacy's payments, and a payment made by another one is not
                // something this screen can be asked for.
                requireOrg(), branch(), supplierId, since(from), until(to),
                blank(mode) ? "" : mode.trim().toUpperCase(), blank(q) ? "" : q.trim(),
                includeCancelled, pageable);

        Map<Long, String> suppliers = supplierNames(page.getContent());
        Map<Long, String> invoices  = invoiceNos(page.getContent());
        return page.map(p -> toResponse(p, suppliers.get(p.getPidSupplier()),
                                        invoices.get(p.getPidTranMst())));
    }

    /** Supplier accounts — the outstanding screen. */
    @Transactional(readOnly = true)
    public List<SupplierPaymentResponse.Account> accounts(String q, boolean dueOnly, boolean includeDormant) {
        return payRepo.supplierAccounts(requireOrg(), branch(), blank(q) ? "" : q.trim(), dueOnly, includeDormant)
                .stream().map(SupplierPaymentServiceImpl::toAccount).toList();
    }

    /** One supplier, with the open bills and recent payments the pay screen needs. */
    @Transactional(readOnly = true)
    public SupplierPaymentResponse.SupplierDetail detail(Long supplierId) {
        Long orgId = requireOrg();
        PosSupplierInfo s = supplierRepo.findById(supplierId)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier", supplierId));
        if (s.getPidOrg() != null && !s.getPidOrg().equals(orgId)) {
            throw new ResourceNotFoundException("Supplier", supplierId);
        }

        var account = payRepo.supplierAccounts(orgId, branch(), "", false, true).stream()
                .map(SupplierPaymentServiceImpl::toAccount)
                .filter(a -> a.getSupplierId().equals(supplierId))
                .findFirst().orElse(null);

        var open = payRepo.payableInvoices(orgId, branch(), supplierId, true).stream()
                .map(SupplierPaymentServiceImpl::toPayable).toList();

        var recent = payRepo.search(orgId, branch(), supplierId, since(null), until(null), "", "", true,
                        org.springframework.data.domain.PageRequest.of(0, 10))
                .map(p -> toResponse(p, s.getSupplierName(), invoiceNo(p.getPidTranMst())))
                .getContent();

        // Null when nothing is held, so the screen has a fact to branch on
        // rather than a zero it has to interpret.
        var advance = advances(supplierId, true).stream().findFirst().orElse(null);

        return SupplierPaymentResponse.SupplierDetail.builder()
                .account(account).openInvoices(open).recentPayments(recent)
                .advance(advance).build();
    }

    @Transactional(readOnly = true)
    public List<SupplierPaymentResponse.PayableInvoice> openInvoices(Long supplierId, boolean openOnly) {
        return payRepo.payableInvoices(requireOrg(), branch(), supplierId, openOnly)
                .stream().map(SupplierPaymentServiceImpl::toPayable).toList();
    }

    @Transactional(readOnly = true)
    public List<SupplierPaymentResponse.Performance> performance(LocalDate from, LocalDate to, String q) {
        return payRepo.performance(requireOrg(), branch(), from, to, blank(q) ? "" : q.trim())
                .stream().map(SupplierPaymentServiceImpl::toPerformance).toList();
    }

    /**
     * A running statement for one supplier.
     *
     * <p>The balance is accumulated here rather than in SQL: a window function
     * would have to repeat the debit/credit rules that the union already states,
     * and the two would drift.
     */
    @Transactional(readOnly = true)
    public List<SupplierPaymentResponse.StatementLine> statement(Long supplierId, LocalDate from, LocalDate to) {
        List<SupplierPaymentResponse.StatementLine> out = new ArrayList<>();
        BigDecimal running = BigDecimal.ZERO;

        for (Object[] r : payRepo.statement(requireOrg(), branch(), supplierId, from, to)) {
            BigDecimal debit  = dec(r[3]);
            BigDecimal credit = dec(r[4]);
            running = running.add(debit).subtract(credit);
            out.add(SupplierPaymentResponse.StatementLine.builder()
                    .date(date(r[0])).kind(str(r[1])).docNo(str(r[2]))
                    .debit(debit).credit(credit)
                    .mode(str(r[5])).note(str(r[6]))
                    .balance(running)
                    .build());
        }
        return out;
    }

    /* ══════════════════════════════════════════════════════════════════════
       MAPPING
       ══════════════════════════════════════════════════════════════════════ */

    private SupplierPaymentResponse.PayableInvoice payableOrNull(Long purchaseId) {
        var rows = payRepo.payableInvoice(purchaseId);
        return rows.isEmpty() ? null : toPayable(rows.get(0));
    }

    private static SupplierPaymentResponse.PayableInvoice toPayable(Object[] r) {
        return SupplierPaymentResponse.PayableInvoice.builder()
                .purchaseId(lng(r[0])).invoiceNo(str(r[1])).transactionDate(date(r[2]))
                .paymentType(str(r[3])).billed(dec(r[4])).paidAtInvoice(dec(r[5]))
                .paidLater(dec(r[6])).returnCredit(dec(r[7])).due(dec(r[8]))
                .ageDays(intg(r[9])).invoiceDiscAmt(dec(r[10]))
                .build();
    }

    private static SupplierPaymentResponse.Account toAccount(Object[] r) {
        BigDecimal limit = dec(r[4]);
        BigDecimal outstanding = dec(r[12]);

        BigDecimal usedPct = null;
        String warning = null;
        if (limit.compareTo(BigDecimal.ZERO) > 0) {
            usedPct = outstanding.multiply(BigDecimal.valueOf(100))
                    .divide(limit, 1, RoundingMode.HALF_UP);
            if (outstanding.compareTo(limit) > 0) {
                warning = "Over the agreed credit limit by " + money(outstanding.subtract(limit)) + ".";
            }
        }

        return SupplierPaymentResponse.Account.builder()
                .supplierId(lng(r[0])).supplierName(str(r[1])).mobileNo(str(r[2]))
                .responsiblePerson(str(r[3])).creditLimit(nullIfZero(limit)).openingBal(dec(r[5]))
                .invoices(intg(r[6])).billed(dec(r[7])).paid(dec(r[8])).returnCredit(dec(r[9]))
                .onAccount(dec(r[10]))
                .advanceBalance(dec(r[11]))
                .outstanding(outstanding)
                .netPosition(dec(r[13]))
                .due0To30(dec(r[14])).due31To60(dec(r[15])).due61To90(dec(r[16])).due90Plus(dec(r[17]))
                .firstPurchase(date(r[18])).lastPurchase(date(r[19]))
                .lastPaymentDate(date(r[20])).lastPaymentAmount(dec(r[21]))
                .unappliedCredit(dec(r[22]))
                .creditUsedPct(usedPct).creditWarning(warning)
                .build();
    }

    private static SupplierPaymentResponse.Performance toPerformance(Object[] r) {
        return SupplierPaymentResponse.Performance.builder()
                .supplierId(lng(r[0])).supplierName(str(r[1])).mobileNo(str(r[2]))
                .creditLimit(nullIfZero(dec(r[3])))
                .invoices(intg(r[4])).billed(dec(r[5])).paid(dec(r[6])).due(dec(r[7]))
                .returnCount(intg(r[8])).returned(dec(r[9])).returnPct(dec(r[10]))
                // Left null rather than zeroed: nothing paid against a named
                // invoice is not the same as paying on the day of the bill.
                .avgDaysToPay(r[11] == null ? null : dec(r[11]))
                .lastPurchase(date(r[12]))
                .build();
    }

    private static SupplierPaymentResponse toResponse(PosSupplierPayment p, String supplierName, String invoiceNo) {
        return SupplierPaymentResponse.builder()
                .id(p.getPidPayment()).voucherNo(p.getVoucherNo()).paymentDate(p.getPaymentDate())
                .supplierId(p.getPidSupplier()).supplierName(supplierName)
                .purchaseId(p.getPidTranMst()).purchaseInvoiceNo(invoiceNo)
                .amount(p.getAmount()).paymentMode(p.getPaymentMode())
                .entryType(p.getEntryType()).source(p.getSource())
                .advanceAmount(p.getAdvanceAmount())
                .bankName(p.getBankName()).chequeNo(p.getChequeNo()).chequeDate(p.getChequeDate())
                .referenceNo(p.getReferenceNo()).remarks(p.getRemarks())
                .status(p.getStatus()).cancelReason(p.getCancelReason())
                .build();
    }

    /* ══════════════════════════════════════════════════════════════════════
       INTERNALS
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * The pharmacy whose supplier ledger this request may see.
     *
     * <p>Every query here filtered on the organisation alone, and branches in
     * one organisation share it — so one pharmacy could read another's
     * payables, ageing and supplier performance. BranchScopeService refuses
     * rather than widening when a user has no branch, which is why the branch
     * is resolved through it and not read from the token directly.
     */
    private Long branch() { return branchScope.resolve(null); }

    private Long requireOrg() {
        Long orgId = SecurityUtils.getCurrentOrgId();
        if (orgId == null) throw new ValidationException("Your account has no organisation assigned.");
        return orgId;
    }

    private void requireSameOrg(PosSupplierPayment p) {
        Long orgId = SecurityUtils.getCurrentOrgId();
        if (orgId != null && p.getPidOrg() != null && !orgId.equals(p.getPidOrg())) {
            throw new ResourceNotFoundException("Supplier payment", p.getPidPayment());
        }
    }

    private Map<Long, String> supplierNames(List<PosSupplierPayment> rows) {
        var ids = rows.stream().map(PosSupplierPayment::getPidSupplier)
                .filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        return supplierRepo.findAllById(ids).stream()
                .collect(Collectors.toMap(PosSupplierInfo::getPidSupplier,
                                          PosSupplierInfo::getSupplierName, (a, b) -> a));
    }

    private Map<Long, String> invoiceNos(List<PosSupplierPayment> rows) {
        var ids = rows.stream().map(PosSupplierPayment::getPidTranMst)
                .filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        return mstRepo.findAllById(ids).stream()
                .collect(Collectors.toMap(PosTransactionMst::getPidTranMst,
                                          PosTransactionMst::getInvoiceNo, (a, b) -> a));
    }

    private String supplierName(Long id) {
        return id == null ? null
                : supplierRepo.findById(id).map(PosSupplierInfo::getSupplierName).orElse(null);
    }

    private String invoiceNo(Long id) {
        return id == null ? null
                : mstRepo.findById(id).map(PosTransactionMst::getInvoiceNo).orElse(null);
    }

    private static String money(BigDecimal v) {
        return "BDT " + v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static BigDecimal nvl(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String trim(String s)   { return blank(s) ? null : s.trim(); }

    private static BigDecimal nullIfZero(BigDecimal v) {
        return v == null || v.compareTo(BigDecimal.ZERO) == 0 ? null : v;
    }

    /* Native queries hand back Object[]; these keep the mapping honest about
       nulls instead of letting a null become a silent zero elsewhere. */

    private static BigDecimal dec(Object o) {
        if (o == null) return BigDecimal.ZERO;
        return o instanceof BigDecimal b ? b : new BigDecimal(o.toString());
    }

    private static Long lng(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }

    private static Integer intg(Object o) {
        return o == null ? null : ((Number) o).intValue();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static LocalDate date(Object o) {
        if (o == null) return null;
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        if (o instanceof LocalDate d) return d;
        String v = o.toString();
        // Native queries hand back a positional array, so a column added to the
        // view silently shifts every index after it. Naming the value that was
        // not a date turns that into a one-line diagnosis instead of an
        // out-of-bounds error from deep inside the parser.
        if (v.length() < 10) {
            throw new IllegalStateException(
                    "Expected a date in this column but found '" + v + "'. A query and its "
                    + "positional mapping have drifted apart.");
        }
        return LocalDate.parse(v.substring(0, 10));
    }

}
