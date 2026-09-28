package com.friendspharma.app.service.impl;

import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.request.HrRequests;
import com.friendspharma.app.dto.response.HrResponses;
import com.friendspharma.app.entity.*;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.friendspharma.app.service.impl.accounting.AccountingHooks;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Conveyance and expense claims.
 *
 * <h3>A reimbursement never reaches a payslip</h3>
 *
 * <p>Money somebody spent on the pharmacy's behalf is theirs being returned, not
 * something they earned. An approved claim is paid on its own — a date, a mode
 * and a reference, exactly as an advance is disbursed — and nothing about
 * payroll changes.
 *
 * <p>Putting it in the gross would put it inside the taxable base and, since
 * V82, inside the provident fund base whenever a scheme is taken on gross: the
 * pharmacy would deduct PF from a rickshaw fare and hand back less than was
 * spent. That is the failure this shape exists to avoid, and it is why there is
 * no hook into {@code HrPayrollServiceImpl} anywhere in this class.
 *
 * <h3>What the caps do, and do not, decide</h3>
 *
 * <p>{@code MAX_PER_CLAIM} and {@code MAX_PER_DAY} are null until somebody sets
 * them, and a null cap is <b>not</b> a cap of nought — nothing is refused
 * against a limit nobody set. A cap that has been set is enforced when the claim
 * is filed rather than at approval, so somebody hears about it while they can
 * still fix it.
 *
 * <h3>Authorisation</h3>
 *
 * <p>Gated on <b>Expense Claims (MOD37)</b>, registered by V83 with its own row
 * on the RBAC screen. Unlike PF and gratuity — payroll money and part of leaving
 * — a claim is something ordinary staff file for themselves, so a pharmacy needs
 * to be able to let everybody claim without letting everybody see payroll.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrExpenseServiceImpl {

    private final HrExpenseTypeRepository      types;
    private final HrExpenseClaimRepository     claims;
    private final HrExpenseClaimLineRepository lineRepo;
    private final HrEmployeeRepository         employeeRepo;
    private final ImageStorageService          storage;
    private final HrApprovalService            approvals;
    private final HrScope                      scope;
    /** Accounts: every financial event this service commits is booked through here, in the same transaction. */
    private final AccountingHooks accounting;
    private final DocumentNumberService        documentNumbers;

    /** The claim number's prefix: EX2609 00001, in the series every document shares. */
    static final String CLAIM_PREFIX = "EX";

    /* ══ Types ══════════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public List<HrResponses.ExpenseTypeDto> listTypes(boolean activeOnly) {
        scope.require(HrScope.MOD_EXPENSE, PermissionGuard.Right.VIEW, "see expense claims");
        Long branch = scope.branchId();
        List<HrExpenseType> rows = activeOnly
                ? types.findByPidBranchAndStatusOrderBySortOrderAscTypeNameAsc(branch, "Y")
                : types.findByPidBranchOrderBySortOrderAscTypeNameAsc(branch);
        return rows.stream().map(this::map).toList();
    }

    @Transactional
    public HrResponses.ExpenseTypeDto createType(HrRequests.ExpenseType req) {
        scope.require(HrScope.MOD_EXPENSE, PermissionGuard.Right.CREATE, "add an expense type");

        Long branch = scope.branchId();
        String code = req.getTypeCode().trim().toUpperCase();
        if (types.existsByPidBranchAndTypeCodeIgnoreCaseAndStatus(branch, code, "Y")) {
            throw new BusinessException("An expense type with the code " + code + " already exists here.",
                    HttpStatus.CONFLICT, "HR_EXPENSE_TYPE_EXISTS");
        }
        requireCaps(req);

        HrExpenseType t = HrExpenseType.builder()
                .pidOrg(scope.orgId()).pidCompany(scope.companyId()).pidBranch(branch)
                .typeCode(code)
                .typeName(req.getTypeName().trim())
                .description(trim(req.getDescription()))
                .maxPerClaim(req.getMaxPerClaim())
                .maxPerDay(req.getMaxPerDay())
                .requiresReceipt(req.getRequiresReceipt() == null ? 0 : req.getRequiresReceipt())
                .sortOrder(req.getSortOrder() == null ? 100 : req.getSortOrder())
                .build();
        t.setStatus("Y");
        return map(types.save(t));
    }

    @Transactional
    public HrResponses.ExpenseTypeDto updateType(Long id, HrRequests.ExpenseType req) {
        scope.require(HrScope.MOD_EXPENSE, PermissionGuard.Right.EDIT, "change an expense type");

        HrExpenseType t = type(id);
        String code = req.getTypeCode().trim().toUpperCase();
        if (types.existsByPidBranchAndTypeCodeIgnoreCaseAndPidExpenseTypeNotAndStatus(
                t.getPidBranch(), code, id, "Y")) {
            throw new BusinessException("An expense type with the code " + code + " already exists here.",
                    HttpStatus.CONFLICT, "HR_EXPENSE_TYPE_EXISTS");
        }
        requireCaps(req);

        t.setTypeCode(code);
        t.setTypeName(req.getTypeName().trim());
        t.setDescription(trim(req.getDescription()));
        /* Null is written through, not ignored: clearing a cap is a decision to
           stop capping, and treating it as "no change" would make a limit
           impossible to remove. */
        t.setMaxPerClaim(req.getMaxPerClaim());
        t.setMaxPerDay(req.getMaxPerDay());
        if (req.getRequiresReceipt() != null) t.setRequiresReceipt(req.getRequiresReceipt());
        if (req.getSortOrder() != null) t.setSortOrder(req.getSortOrder());
        return map(types.save(t));
    }

    /**
     * Retires a type.
     *
     * <p>Deactivated, never deleted: claims already filed point at it, and a
     * report of what the pharmacy spent last year should still be able to say
     * what it was spent on.
     */
    @Transactional
    public void retireType(Long id) {
        scope.require(HrScope.MOD_EXPENSE, PermissionGuard.Right.DELETE, "retire an expense type");
        HrExpenseType t = type(id);
        t.setStatus("N");
        types.save(t);
        log.info("Expense type {} retired; {} lines keep pointing at it",
                 t.getTypeCode(), claims.countLinesOfType(id));
    }

    /* ══ Claims ═════════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public PageResponse<HrResponses.ExpenseClaimDto> search(Long employeeId, String status,
                                                             LocalDate from, LocalDate to,
                                                             Pageable pageable) {
        scope.require(HrScope.MOD_EXPENSE, PermissionGuard.Right.VIEW, "see expense claims");
        Map<Long, HrEmployee> staff = staffById();
        return PageResponse.from(
                claims.search(scope.branchId(), employeeId,
                              status == null ? "" : status.trim().toUpperCase(), from, to, pageable)
                        .map(c -> map(c, staff.get(c.getPidEmployee()), false)));
    }

    @Transactional(readOnly = true)
    public List<HrResponses.ExpenseClaimDto> pending() {
        scope.require(HrScope.MOD_EXPENSE, PermissionGuard.Right.VIEW, "see expense claims");
        Map<Long, HrEmployee> staff = staffById();
        return claims.findByPidBranchAndClaimStatusAndStatusOrderByClaimDate(
                        scope.branchId(), HrExpenseClaim.SUBMITTED, "Y")
                .stream().map(c -> map(c, staff.get(c.getPidEmployee()), true)).toList();
    }

    @Transactional(readOnly = true)
    public HrResponses.ExpenseClaimDto get(Long id) {
        scope.require(HrScope.MOD_EXPENSE, PermissionGuard.Right.VIEW, "see expense claims");
        HrExpenseClaim c = claim(id);
        return map(c, scope.employee(c.getPidEmployee()), true);
    }

    @Transactional
    public HrResponses.ExpenseClaimDto create(HrRequests.ExpenseClaim req) {
        scope.require(HrScope.MOD_EXPENSE, PermissionGuard.Right.CREATE, "file an expense claim");

        HrEmployee e = scope.activeEmployee(req.getEmployeeId());
        if (req.getLines() == null || req.getLines().isEmpty()) {
            throw new BusinessException("A claim with nothing on it is not a claim.",
                    HttpStatus.BAD_REQUEST, "HR_EXPENSE_NO_LINES");
        }

        HrExpenseClaim c = HrExpenseClaim.builder()
                .pidOrg(scope.orgId()).pidCompany(scope.companyId()).pidBranch(e.getPidBranch())
                .pidEmployee(e.getPidEmployee())
                .claimDate(req.getClaimDate() == null ? LocalDate.now() : req.getClaimDate())
                .purpose(req.getPurpose())
                .claimStatus(HrExpenseClaim.DRAFT)
                .build();
        c.setStatus("Y");

        replaceLines(c, e, req.getLines());
        claims.save(c);

        if (Boolean.TRUE.equals(req.getSubmit())) submitInternal(c);
        return map(c, e, true);
    }

    @Transactional
    public HrResponses.ExpenseClaimDto update(Long id, HrRequests.ExpenseClaim req) {
        scope.require(HrScope.MOD_EXPENSE, PermissionGuard.Right.EDIT, "change an expense claim");

        HrExpenseClaim c = claim(id);
        if (!c.isEditable()) {
            throw new BusinessException(
                    "Only a draft can be changed. This one is "
                    + HrConstants.label(c.getClaimStatus()).toLowerCase()
                    + " — cancel it and file again.",
                    HttpStatus.CONFLICT, "HR_EXPENSE_NOT_DRAFT");
        }
        HrEmployee e = scope.employee(c.getPidEmployee());

        if (req.getClaimDate() != null) c.setClaimDate(req.getClaimDate());
        c.setPurpose(req.getPurpose());
        if (req.getLines() != null) {
            if (req.getLines().isEmpty()) {
                throw new BusinessException("A claim with nothing on it is not a claim.",
                        HttpStatus.BAD_REQUEST, "HR_EXPENSE_NO_LINES");
            }
            replaceLines(c, e, req.getLines());
        }
        claims.save(c);

        if (Boolean.TRUE.equals(req.getSubmit())) submitInternal(c);
        return map(c, e, true);
    }

    @Transactional
    public HrResponses.ExpenseClaimDto submit(Long id) {
        scope.require(HrScope.MOD_EXPENSE, PermissionGuard.Right.CREATE, "submit an expense claim");
        HrExpenseClaim c = claim(id);
        submitInternal(c);
        return map(c, scope.employee(c.getPidEmployee()), true);
    }

    private void submitInternal(HrExpenseClaim c) {
        if (!c.isDraft()) {
            throw new BusinessException("This claim has already been submitted.",
                    HttpStatus.CONFLICT, "HR_EXPENSE_ALREADY_SUBMITTED");
        }
        requireReceipts(c);
        c.setClaimStatus(HrExpenseClaim.SUBMITTED);
        if (c.getClaimNo() == null) c.setClaimNo(documentNumbers.nextInvoiceNo(CLAIM_PREFIX));
        c.setSubmittedBy(scope.actor());
        c.setSubmittedAt(LocalDateTime.now());
        claims.saveAndFlush(c);

        /* Routed on what is being claimed back. */
        approvals.begin(HrScope.WF_EXPENSE, c.getPidExpenseClaim(),
                        scope.employee(c.getPidEmployee()), c.getClaimedAmount());
    }

    /**
     * Approves, line by line.
     *
     * <p>A decision on a claim is really several decisions — the fares allowed,
     * the dinner trimmed — so an approver may set an amount per line and the
     * header total is derived from what they allowed. Sending no lines approves
     * everything as claimed.
     */
    @Transactional
    public HrResponses.ExpenseClaimDto approve(Long id, HrRequests.ExpenseDecision d) {
        scope.require(HrScope.MOD_EXPENSE, PermissionGuard.Right.APPROVE, "approve an expense claim");

        HrExpenseClaim c = claim(id);
        requireSubmitted(c);

        /* Not necessarily the last signature. The per-line amounts are not
           written until it is, so an intermediate approver's figures cannot be
           mistaken for the decision. */
        if (approvals.decide(HrScope.WF_EXPENSE, id, true, d == null ? null : d.getRemarks())
                == HrApprovalService.Step.PENDING_NEXT) {
            return map(c, scope.employee(c.getPidEmployee()), true);
        }

        Map<Long, BigDecimal> allowed = new HashMap<>();
        if (d != null && d.getLines() != null) {
            for (HrRequests.ExpenseLineDecision l : d.getLines()) {
                if (l.getLineId() != null && l.getApprovedAmount() != null) {
                    allowed.put(l.getLineId(), l.getApprovedAmount());
                }
            }
        }

        BigDecimal total = BigDecimal.ZERO;
        for (HrExpenseClaimLine line : c.getLines()) {
            BigDecimal grant = allowed.containsKey(line.getPidExpenseLine())
                    ? money(allowed.get(line.getPidExpenseLine()))
                    : line.getClaimedAmount();
            if (grant.signum() < 0) {
                throw new BusinessException("A line cannot be approved for less than nothing.",
                        HttpStatus.BAD_REQUEST, "HR_EXPENSE_NEGATIVE_LINE");
            }
            if (grant.compareTo(line.getClaimedAmount()) > 0) {
                throw new BusinessException(
                        "More cannot be approved than was claimed on the "
                        + line.getExpenseDate() + " line — " + line.getClaimedAmount() + ".",
                        HttpStatus.BAD_REQUEST, "HR_EXPENSE_OVER_CLAIM");
            }
            line.setApprovedAmount(grant);
            total = total.add(grant);
        }

        if (total.signum() <= 0) {
            throw new BusinessException(
                    "Approving nothing is a rejection. Reject the claim instead.",
                    HttpStatus.BAD_REQUEST, "HR_EXPENSE_ZERO_APPROVAL");
        }

        c.setApprovedAmount(money(total));
        c.setClaimStatus(HrExpenseClaim.APPROVED);
        c.setDecidedBy(scope.actor());
        c.setDecidedAt(LocalDateTime.now());
        c.setDecisionRemarks(d == null ? null : d.getRemarks());
        claims.save(c);
        accounting.expenseClaimApproved(c);

        log.info("Expense claim {} approved: {} of {} claimed",
                 c.getPidExpenseClaim(), c.getApprovedAmount(), c.getClaimedAmount());
        return map(c, scope.employee(c.getPidEmployee()), true);
    }

    @Transactional
    public HrResponses.ExpenseClaimDto reject(Long id, HrRequests.Decision d) {
        scope.require(HrScope.MOD_EXPENSE, PermissionGuard.Right.APPROVE, "reject an expense claim");
        HrExpenseClaim c = claim(id);
        requireSubmitted(c);
        approvals.decide(HrScope.WF_EXPENSE, id, false, d == null ? null : d.getRemarks());
        c.setClaimStatus(HrExpenseClaim.REJECTED);
        c.setDecidedBy(scope.actor());
        c.setDecidedAt(LocalDateTime.now());
        c.setDecisionRemarks(d == null ? null : d.getRemarks());
        claims.save(c);
        return map(c, scope.employee(c.getPidEmployee()), true);
    }

    /**
     * Records the reimbursement.
     *
     * <p>Its own payment, with its own reference. Nothing here touches a payroll
     * run, and nothing on a payslip changes — see the class comment.
     */
    @Transactional
    public HrResponses.ExpenseClaimDto pay(Long id, HrRequests.ExpensePayment req) {
        scope.require(HrScope.MOD_EXPENSE, PermissionGuard.Right.APPROVE, "pay an expense claim");

        HrExpenseClaim c = claim(id);
        if (!c.isApproved()) {
            throw new BusinessException(
                    "Only an approved claim can be paid. This one is "
                    + HrConstants.label(c.getClaimStatus()).toLowerCase() + ".",
                    HttpStatus.CONFLICT, "HR_EXPENSE_NOT_APPROVED");
        }
        String mode = req == null || req.getPaymentMode() == null
                ? null : req.getPaymentMode().trim().toUpperCase();
        if (mode == null || !HrConstants.PaymentMode.ALL.contains(mode)) {
            throw new BusinessException(
                    "How was it paid? Expected one of " + HrConstants.PaymentMode.ALL + ".",
                    HttpStatus.BAD_REQUEST, "HR_PAYMENT_MODE_INVALID");
        }

        c.setClaimStatus(HrExpenseClaim.PAID);
        c.setPaidDate(req.getPaidDate() == null ? LocalDate.now() : req.getPaidDate());
        c.setPaymentMode(mode);
        c.setPaymentReference(trim(req.getPaymentReference()));
        c.setPaidBy(scope.actor());
        claims.save(c);
        accounting.expenseClaimPaid(c);

        log.info("Expense claim {} reimbursed: {} by {} — not through payroll",
                 c.getPidExpenseClaim(), c.getApprovedAmount(), mode);
        return map(c, scope.employee(c.getPidEmployee()), true);
    }

    /** A paid claim is the record of a payment and is not cancellable. */
    @Transactional
    public HrResponses.ExpenseClaimDto cancel(Long id, HrRequests.Decision d) {
        scope.require(HrScope.MOD_EXPENSE, PermissionGuard.Right.DELETE, "cancel an expense claim");

        HrExpenseClaim c = claim(id);
        if (c.isPaid()) {
            throw new BusinessException(
                    "This claim has been reimbursed. Cancelling it would not take the money back.",
                    HttpStatus.CONFLICT, "HR_EXPENSE_PAID");
        }
        if (HrExpenseClaim.CANCELLED.equals(c.getClaimStatus())
                || HrExpenseClaim.REJECTED.equals(c.getClaimStatus())) {
            throw new BusinessException("This claim is already closed.",
                    HttpStatus.CONFLICT, "HR_EXPENSE_ALREADY_CLOSED");
        }

        approvals.cancelFor(HrScope.WF_EXPENSE, id);
        c.setClaimStatus(HrExpenseClaim.CANCELLED);
        c.setDecidedBy(scope.actor());
        c.setDecidedAt(LocalDateTime.now());
        c.setDecisionRemarks(d == null ? null : d.getRemarks());
        claims.save(c);
        return map(c, scope.employee(c.getPidEmployee()), true);
    }

    /* ══ Receipts ═══════════════════════════════════════════════════════ */

    /**
     * Attaches a photograph of a receipt to one line.
     *
     * <p>Reuses {@link ImageStorageService} rather than storing the file here:
     * the extension whitelist, the MIME-and-extension agreement, the magic-byte
     * check and the path re-check all apply to a till slip exactly as they do to
     * a staff photograph, and a second implementation would be a second place
     * for one of them to be forgotten.
     *
     * <p>Only while the claim is a draft. A receipt swapped after somebody
     * approved the claim would change the evidence behind a decision already
     * made.
     */
    @Transactional
    public HrResponses.ExpenseLineDto attachReceipt(Long lineId, MultipartFile file) {
        scope.require(HrScope.MOD_EXPENSE, PermissionGuard.Right.EDIT, "attach a receipt");

        HrExpenseClaimLine line = lineRepo
                .findByPidExpenseLineAndClaim_PidBranch(lineId, scope.branchId())
                .orElseThrow(() -> new ResourceNotFoundException("Expense line", lineId));
        if (!line.getClaim().isEditable()) {
            throw new BusinessException(
                    "This claim has already been submitted. A receipt cannot be changed "
                    + "underneath a decision.",
                    HttpStatus.CONFLICT, "HR_EXPENSE_NOT_DRAFT");
        }

        String previous = line.getReceiptPath();
        String url = storage.store(file, ImageStorageService.Bucket.EXPENSE_RECEIPT, lineId);
        line.setReceiptPath(url);
        lineRepo.save(line);

        /* The old file goes only once the new one is safely stored and the row
           points at it — the other order loses the receipt if the upload fails. */
        if (previous != null && !previous.equals(url)) {
            storage.delete(ImageStorageService.Bucket.EXPENSE_RECEIPT, previous);
        }
        return map(line, typeName(line.getPidExpenseType()));
    }

    @Transactional
    public void removeReceipt(Long lineId) {
        scope.require(HrScope.MOD_EXPENSE, PermissionGuard.Right.EDIT, "remove a receipt");

        HrExpenseClaimLine line = lineRepo
                .findByPidExpenseLineAndClaim_PidBranch(lineId, scope.branchId())
                .orElseThrow(() -> new ResourceNotFoundException("Expense line", lineId));
        if (!line.getClaim().isEditable()) {
            throw new BusinessException(
                    "This claim has already been submitted.",
                    HttpStatus.CONFLICT, "HR_EXPENSE_NOT_DRAFT");
        }
        String previous = line.getReceiptPath();
        line.setReceiptPath(null);
        lineRepo.save(line);
        if (previous != null) storage.delete(ImageStorageService.Bucket.EXPENSE_RECEIPT, previous);
    }

    /* ══ Lines, caps and guards ═════════════════════════════════════════ */

    /**
     * Replaces a draft's lines and re-derives every total from them.
     *
     * <p>The header never carries a figure somebody typed: it is the sum of what
     * is actually on the claim, so the two cannot disagree.
     */
    private void replaceLines(HrExpenseClaim c, HrEmployee e,
                              List<HrRequests.ExpenseLine> incoming) {
        Map<Long, HrExpenseType> byId = typesById(e.getPidBranch());

        /* Clear, then FLUSH, then insert. The lines have no unique key of their
           own so this is not the flush-order trap the structures hit, but the
           children are removed before the new ones are counted so the totals
           below are computed from the claim as it will actually be. */
        c.getLines().clear();
        if (c.getPidExpenseClaim() != null) claims.saveAndFlush(c);

        BigDecimal total = BigDecimal.ZERO;
        LocalDate earliest = null, latest = null;
        int order = 10;

        /* Day totals accumulated across the claim, so three fares on one date
           are checked against a daily cap together rather than one at a time. */
        Map<String, BigDecimal> perDay = new HashMap<>();

        for (HrRequests.ExpenseLine in : incoming) {
            HrExpenseType t = byId.get(in.getExpenseTypeId());
            if (t == null || !t.isActive()) {
                throw new BusinessException(
                        "That expense type does not exist at this branch, or has been retired.",
                        HttpStatus.BAD_REQUEST, "HR_EXPENSE_TYPE_NOT_FOUND");
            }
            if (in.getExpenseDate() == null) {
                throw new BusinessException("Every expense needs a date.",
                        HttpStatus.BAD_REQUEST, "HR_EXPENSE_DATE_REQUIRED");
            }
            if (in.getExpenseDate().isAfter(LocalDate.now())) {
                throw new BusinessException(
                        "An expense cannot be claimed for a day that has not happened.",
                        HttpStatus.BAD_REQUEST, "HR_EXPENSE_FUTURE");
            }
            BigDecimal amount = money(in.getClaimedAmount());
            if (amount.signum() <= 0) {
                throw new BusinessException("An expense of nothing is not an expense.",
                        HttpStatus.BAD_REQUEST, "HR_EXPENSE_ZERO_LINE");
            }

            HrExpenseClaimLine line = HrExpenseClaimLine.builder()
                    .claim(c)
                    .pidExpenseType(t.getPidExpenseType())
                    .expenseDate(in.getExpenseDate())
                    .fromPlace(trim(in.getFromPlace()))
                    .toPlace(trim(in.getToPlace()))
                    .description(trim(in.getDescription()))
                    .claimedAmount(amount)
                    .receiptPath(trim(in.getReceiptPath()))
                    .sortOrder(order)
                    .build();
            line.setStatus("Y");
            c.getLines().add(line);

            total = total.add(amount);
            order += 10;
            if (earliest == null || in.getExpenseDate().isBefore(earliest)) earliest = in.getExpenseDate();
            if (latest == null   || in.getExpenseDate().isAfter(latest))    latest   = in.getExpenseDate();

            String key = t.getPidExpenseType() + "@" + in.getExpenseDate();
            perDay.merge(key, amount, BigDecimal::add);
        }

        c.setClaimedAmount(money(total));
        c.setPeriodFrom(earliest);
        c.setPeriodTo(latest);

        requireWithinCaps(c, e, byId, perDay);
    }

    /**
     * Enforces the caps somebody has actually set.
     *
     * <p>Checked when the claim is filed rather than at approval, so the person
     * who spent the money hears about it while they can still explain it. A type
     * with no cap is not checked at all — see the class comment.
     */
    private void requireWithinCaps(HrExpenseClaim c, HrEmployee e,
                                   Map<Long, HrExpenseType> byId,
                                   Map<String, BigDecimal> perDay) {
        for (HrExpenseClaimLine line : c.getLines()) {
            HrExpenseType t = byId.get(line.getPidExpenseType());
            if (t == null || !t.isCapped()) continue;

            if (t.getMaxPerClaim() != null
                    && line.getClaimedAmount().compareTo(t.getMaxPerClaim()) > 0) {
                throw new BusinessException(
                        t.getTypeName() + " is capped at " + t.getMaxPerClaim()
                        + " on one expense, and the " + line.getExpenseDate() + " line is "
                        + line.getClaimedAmount() + ".",
                        HttpStatus.CONFLICT, "HR_EXPENSE_OVER_CAP");
            }
        }

        for (Map.Entry<String, BigDecimal> day : perDay.entrySet()) {
            String[] parts = day.getKey().split("@");
            Long typeId = Long.valueOf(parts[0]);
            LocalDate on = LocalDate.parse(parts[1]);
            HrExpenseType t = byId.get(typeId);
            if (t == null || t.getMaxPerDay() == null) continue;

            /* What is already on other live claims for the same person, type and
               day. Without it, a daily cap is defeated by filing two claims. */
            BigDecimal elsewhere = nz(claims.claimedOnDay(
                    e.getPidEmployee(), typeId, on, c.getPidExpenseClaim()));
            BigDecimal together = day.getValue().add(elsewhere);
            if (together.compareTo(t.getMaxPerDay()) > 0) {
                throw new BusinessException(
                        t.getTypeName() + " is capped at " + t.getMaxPerDay() + " a day. "
                        + on + " would come to " + together
                        + (elsewhere.signum() > 0 ? " including " + elsewhere + " already claimed" : "")
                        + ".",
                        HttpStatus.CONFLICT, "HR_EXPENSE_OVER_DAILY_CAP");
            }
        }
    }

    /** Refuses a claim missing a receipt where the type asks for one. */
    private void requireReceipts(HrExpenseClaim c) {
        Map<Long, HrExpenseType> byId = typesById(c.getPidBranch());
        List<String> missing = new ArrayList<>();
        for (HrExpenseClaimLine line : c.getLines()) {
            HrExpenseType t = byId.get(line.getPidExpenseType());
            if (t != null && t.needsReceipt() && !line.hasReceipt()) {
                missing.add(t.getTypeName() + " on " + line.getExpenseDate());
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(
                    "A receipt is expected for " + String.join(", ", missing) + ".",
                    HttpStatus.CONFLICT, "HR_EXPENSE_RECEIPT_REQUIRED");
        }
    }

    private static void requireSubmitted(HrExpenseClaim c) {
        if (!c.isSubmitted()) {
            throw new BusinessException(
                    "Only a submitted claim can be decided. This one is "
                    + HrConstants.label(c.getClaimStatus()).toLowerCase() + ".",
                    HttpStatus.CONFLICT, "HR_EXPENSE_NOT_SUBMITTED");
        }
    }

    private static void requireCaps(HrRequests.ExpenseType req) {
        if (req.getMaxPerClaim() != null && req.getMaxPerDay() != null
                && req.getMaxPerDay().compareTo(req.getMaxPerClaim()) < 0) {
            throw new BusinessException(
                    "The daily cap is below the per-expense cap, so the per-expense one could "
                    + "never be reached.",
                    HttpStatus.BAD_REQUEST, "HR_EXPENSE_CAPS_INCONSISTENT");
        }
    }

    /* ══ Mapping and helpers ════════════════════════════════════════════ */

    private HrExpenseType type(Long id) {
        return types.findByPidExpenseTypeAndPidBranch(id, scope.branchId())
                .orElseThrow(() -> new ResourceNotFoundException("Expense type", id));
    }

    private HrExpenseClaim claim(Long id) {
        return claims.findByPidExpenseClaimAndPidBranch(id, scope.branchId())
                .orElseThrow(() -> new ResourceNotFoundException("Expense claim", id));
    }

    private Map<Long, HrExpenseType> typesById(Long branchId) {
        Map<Long, HrExpenseType> byId = new HashMap<>();
        for (HrExpenseType t : types.findByPidBranchOrderBySortOrderAscTypeNameAsc(branchId)) {
            byId.put(t.getPidExpenseType(), t);
        }
        return byId;
    }

    private String typeName(Long typeId) {
        return types.findById(typeId).map(HrExpenseType::getTypeName).orElse(null);
    }

    private Map<Long, HrEmployee> staffById() {
        Map<Long, HrEmployee> byId = new HashMap<>();
        for (HrEmployee e : employeeRepo.findByPidBranchAndStatusOrderByEmpName(scope.branchId(), "Y")) {
            byId.put(e.getPidEmployee(), e);
        }
        return byId;
    }

    HrResponses.ExpenseTypeDto map(HrExpenseType t) {
        return HrResponses.ExpenseTypeDto.builder()
                .id(t.getPidExpenseType())
                .typeCode(t.getTypeCode())
                .typeName(t.getTypeName())
                .description(t.getDescription())
                .maxPerClaim(t.getMaxPerClaim())
                .maxPerDay(t.getMaxPerDay())
                .capped(t.isCapped())
                .requiresReceipt(t.getRequiresReceipt())
                .sortOrder(t.getSortOrder())
                .status(t.getStatus())
                .build();
    }

    HrResponses.ExpenseLineDto map(HrExpenseClaimLine l, String typeName) {
        return HrResponses.ExpenseLineDto.builder()
                .id(l.getPidExpenseLine())
                .expenseTypeId(l.getPidExpenseType())
                .typeName(typeName)
                .expenseDate(l.getExpenseDate())
                .fromPlace(l.getFromPlace())
                .toPlace(l.getToPlace())
                .description(l.getDescription())
                .claimedAmount(l.getClaimedAmount())
                .approvedAmount(l.getApprovedAmount())
                .receiptPath(l.getReceiptPath())
                .sortOrder(l.getSortOrder())
                .build();
    }

    HrResponses.ExpenseClaimDto map(HrExpenseClaim c, HrEmployee e, boolean withLines) {
        List<HrResponses.ExpenseLineDto> lines = List.of();
        if (withLines) {
            Map<Long, HrExpenseType> byId = typesById(c.getPidBranch());
            lines = c.getLines().stream()
                    .map(l -> map(l, Optional.ofNullable(byId.get(l.getPidExpenseType()))
                            .map(HrExpenseType::getTypeName).orElse(null)))
                    .toList();
        }
        return HrResponses.ExpenseClaimDto.builder()
                .id(c.getPidExpenseClaim())
                .claimNo(c.getClaimNo())
                .employeeId(c.getPidEmployee())
                .empId(e == null ? null : e.getEmpId())
                .empName(e == null ? null : e.getEmpName())
                .claimDate(c.getClaimDate())
                .periodFrom(c.getPeriodFrom())
                .periodTo(c.getPeriodTo())
                .claimedAmount(c.getClaimedAmount())
                .approvedAmount(c.getApprovedAmount())
                .lineCount(c.getLines().size())
                .purpose(c.getPurpose())
                .claimStatus(c.getClaimStatus())
                .claimStatusLabel(HrConstants.label(c.getClaimStatus()))
                .submittedBy(c.getSubmittedBy())
                .submittedAt(c.getSubmittedAt())
                .decidedBy(c.getDecidedBy())
                .decidedAt(c.getDecidedAt())
                .decisionRemarks(c.getDecisionRemarks())
                .paidDate(c.getPaidDate())
                .paymentMode(c.getPaymentMode())
                .paymentReference(c.getPaymentReference())
                .editable(c.isEditable())
                .lines(lines)
                .build();
    }

    private static BigDecimal money(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
