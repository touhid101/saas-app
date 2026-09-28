package com.friendspharma.app.service.impl;

import com.friendspharma.app.dto.response.SubscriptionResponse.*;
import com.friendspharma.app.entity.*;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.exception.custom.ValidationException;
import com.friendspharma.app.repository.*;
import com.friendspharma.app.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.friendspharma.app.service.impl.accounting.AccountingHooks;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Subscriptions: raising what is owed, taking what is paid, and expiring what is not.
 *
 * <p><b>Why payment settles an invoice rather than extending a subscription.</b>
 * Money is recorded by hand today — somebody pays by bKash or bank transfer and
 * the Super Admin marks it received. But the shape here is the one a gateway
 * needs: a payment is a transition on an invoice, carrying a provider reference
 * and an idempotency key. When a real gateway arrives, its webhook calls
 * {@link #recordPayment} exactly as the console does. Adding it is a controller,
 * not a redesign of the state machine.
 *
 * <p><b>Why renewal extends from the old expiry, not from today.</b> A pharmacy
 * that pays three days late has still bought a month; starting the new term today
 * would quietly charge them for the gap. The exception is a subscription that
 * lapsed entirely — there the term starts now, because nobody is owed service for
 * the months they were locked out.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionServiceImpl {

    /** How close to expiry the screen should start asking for a renewal. */
    private static final int RENEWAL_WARNING_DAYS = 14;

    private final SubPlanRepository         planRepo;
    private final SubTermRepository         termRepo;
    private final SubSubscriptionRepository subRepo;
    private final SubInvoiceRepository      invoiceRepo;
    private final SubPaymentRepository      paymentRepo;
    private final SubAuditRepository        auditRepo;
    private final AllBranchRepository       branchRepo;
    private final TenantStatusService       tenantStatus;
    private final BranchScopeService        branchScope;
    /** Accounts: every financial event this service commits is booked through here, in the same transaction. */
    private final AccountingHooks accounting;

    /* ══════════════════════════════════════════════════════════════════════
       WHAT THE SIGNED-IN PHARMACY SEES
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * The signed-in pharmacy's own subscription.
     *
     * <p>Reachable while expired — it is the screen that explains why everything
     * else is not, so gating it behind a live subscription would leave a blocked
     * user with no way to find out what to do about it.
     */
    @Transactional(readOnly = true)
    public Mine mine() {
        return forBranch(branchScope.resolve(null));
    }

    @Transactional(readOnly = true)
    public Mine forBranch(Long branchId) {
        AllBranch branch = branchRepo.findById(branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Branch", branchId));

        TenantStatusService.Access access = tenantStatus.check(branchId);
        // The latest, not the live one. A lapsed pharmacy still needs to see what
        // it was on and what it will cost, or the renewal screen is a blank page
        // with a Pay button on it.
        SubSubscription sub = latestFor(branchId);
        SubPlan plan = sub == null ? null : planRepo.findById(sub.getPidPlan()).orElse(null);

        List<InvoiceDto> outstanding = invoiceRepo
                .findByPidBranchAndPayStatusOrderByDueOn(branchId, "PENDING")
                .stream().map(this::toInvoice).toList();

        return Mine.builder()
                .branchId(branchId)
                .branchName(branch.getBranchName())
                .tenantStatus(access.tenantStatus())
                .allowed(access.allowed())
                .reason(access.reason())
                .planCode(plan == null ? null : plan.getPlanCode())
                .planName(plan == null ? null : plan.getPlanName())
                .price(plan == null ? null : plan.getPrice())
                .billingTerm(plan == null ? null : plan.getBillingTerm())
                .startsOn(sub == null ? null : sub.getStartsOn())
                .expiresOn(sub == null ? null : sub.getExpiresOn())
                .lastDayOfAccess(sub == null ? null : sub.lastDayOfAccess())
                .graceDays(sub == null ? null : sub.getGraceDays())
                .daysRemaining(access.daysRemaining())
                .inGrace(access.inGrace())
                .renewalDue(!access.allowed()
                        || access.inGrace()
                        || access.daysRemaining() <= RENEWAL_WARNING_DAYS)
                .outstanding(outstanding)
                .build();
    }

    /** The most recent subscription a branch has held, live or not. */
    private SubSubscription latestFor(Long branchId) {
        return subRepo.findByPidBranchOrderByPidSubscriptionDesc(branchId)
                .stream().findFirst().orElse(null);
    }

    @Transactional(readOnly = true)
    public List<PlanDto> plans() {
        return planRepo.findByIsActiveOrderBySortOrder(1).stream()
                .map(p -> PlanDto.builder()
                        .planId(p.getPidPlan()).planCode(p.getPlanCode()).planName(p.getPlanName())
                        .description(p.getDescription()).price(p.getPrice())
                        .billingTerm(p.getBillingTerm()).maxUsers(p.getMaxUsers())
                        .graceDays(p.getGraceDays())
                        .build())
                .toList();
    }

    /* ══════════════════════════════════════════════════════════════════════
       RAISING AND SETTLING
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * Raises the next period's invoice for a branch.
     *
     * <p>Returns the invoice already outstanding rather than raising a second one:
     * a user clicking Renew twice should owe one month, not two.
     */
    @Transactional
    public InvoiceDto raiseRenewal(Long branchId, Long planId) {
        // Deliberately not findLiveForBranch. Once the sweep marks a subscription
        // EXPIRED it is no longer live, and a pharmacy that has lapsed is exactly
        // the one that needs to be able to pay: looking only at live rows meant an
        // expired customer could be blocked but never let themselves back in.
        SubSubscription sub = subRepo.findByPidBranchOrderByPidSubscriptionDesc(branchId)
                .stream().findFirst()
                .orElseThrow(() -> new BusinessException(
                        "This pharmacy has no subscription to renew. An administrator must set one up.",
                        HttpStatus.CONFLICT, "NO_SUBSCRIPTION"));

        List<SubInvoice> pending = invoiceRepo.findByPidBranchAndPayStatusOrderByDueOn(branchId, "PENDING");
        if (!pending.isEmpty()) return toInvoice(pending.get(0));

        SubPlan plan = planRepo.findById(planId != null ? planId : sub.getPidPlan())
                .orElseThrow(() -> new ResourceNotFoundException("Plan", planId));

        LocalDate from = nextTermStart(sub);
        LocalDate to   = addTerm(from, plan.getBillingTerm()).minusDays(1);

        SubInvoice inv = SubInvoice.builder()
                .pidSubscription(sub.getPidSubscription())
                .pidBranch(branchId)
                .invoiceNo(nextInvoiceNo())
                .periodFrom(from).periodTo(to)
                .amount(plan.getPrice())
                .issuedOn(LocalDate.now())
                .dueOn(sub.getExpiresOn())
                .payStatus("PENDING")
                .build();
        inv.setStatus("Y");
        invoiceRepo.save(inv);
        accounting.subscriptionInvoiced(inv);

        audit(branchId, "INVOICE_RAISED",
              "Invoice " + inv.getInvoiceNo() + " for " + plan.getPlanName(),
              null, inv.getAmount().toPlainString());
        return toInvoice(inv);
    }

    /**
     * Records money against an invoice and lets the pharmacy back in.
     *
     * <p>Everything here happens in one transaction: the payment, the settled
     * invoice, the extended subscription and the reactivated branch. A partial
     * version of this — money taken, access not restored — is the worst outcome
     * available, so it is not reachable.
     *
     * <p>{@code idempotencyKey} is what makes a gateway webhook that fires twice
     * settle the invoice once. The manual path passes null and is guarded instead
     * by the invoice's own status.
     */
    @Transactional
    public PaymentDto recordPayment(Long invoiceId, BigDecimal amount, String method,
                                    String providerRef, String idempotencyKey, String note) {

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            SubPayment already = paymentRepo.findByIdempotencyKey(idempotencyKey).orElse(null);
            if (already != null) {
                log.info("Payment {} replayed; returning the original.", idempotencyKey);
                return toPayment(already);
            }
        }

        SubInvoice inv = invoiceRepo.findById(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice", invoiceId));

        if ("PAID".equals(inv.getPayStatus())) {
            throw new BusinessException(
                    "Invoice " + inv.getInvoiceNo() + " has already been paid.",
                    HttpStatus.CONFLICT, "INVOICE_ALREADY_PAID");
        }
        if ("VOID".equals(inv.getPayStatus())) {
            throw new BusinessException(
                    "Invoice " + inv.getInvoiceNo() + " was cancelled and cannot be paid.",
                    HttpStatus.CONFLICT, "INVOICE_VOID");
        }

        BigDecimal paid = amount != null ? amount : inv.getAmount();
        if (paid.compareTo(inv.getAmount()) < 0) {
            throw new BusinessException(
                    "This invoice is for " + inv.getAmount().toPlainString()
                    + ". A part payment does not restore access; record the full amount.",
                    HttpStatus.BAD_REQUEST, "PART_PAYMENT_NOT_ACCEPTED");
        }

        SubPayment pay = SubPayment.builder()
                .pidInvoice(inv.getPidInvoice())
                .pidBranch(inv.getPidBranch())
                .amount(paid)
                .paidOn(LocalDate.now())
                .method(method == null || method.isBlank() ? "MANUAL" : method)
                .providerRef(providerRef)
                .idempotencyKey(idempotencyKey)
                .note(note)
                .build();
        pay.setStatus("Y");
        paymentRepo.save(pay);
        accounting.subscriptionPaid(pay, inv);

        inv.setPayStatus("PAID");
        invoiceRepo.save(inv);

        SubSubscription sub = subRepo.findById(inv.getPidSubscription())
                .orElseThrow(() -> new ResourceNotFoundException("Subscription", inv.getPidSubscription()));
        String wasExpiring = String.valueOf(sub.getExpiresOn());

        sub.setExpiresOn(inv.getPeriodTo());
        sub.setSubStatus("ACTIVE");
        subRepo.save(sub);

        AllBranch branch = branchRepo.findById(inv.getPidBranch()).orElse(null);
        if (branch != null && !"ACTIVE".equals(branch.getTenantStatus())) {
            branch.setTenantStatus("ACTIVE");
            branchRepo.save(branch);
        }

        // Without this the renewal appears to do nothing for up to a minute —
        // exactly the moment somebody is watching for it to work.
        tenantStatus.forget(inv.getPidBranch());

        audit(inv.getPidBranch(), "PAYMENT",
              "Invoice " + inv.getInvoiceNo() + " settled; access runs to " + sub.lastDayOfAccess(),
              wasExpiring, String.valueOf(sub.getExpiresOn()));

        log.info("Branch {} paid invoice {}; now expires {}", inv.getPidBranch(),
                 inv.getInvoiceNo(), sub.getExpiresOn());
        return toPayment(pay);
    }

    /* ══════════════════════════════════════════════════════════════════════
       PLANS
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * Adds or edits a plan.
     *
     * <p>Editing a price changes what the <em>next</em> invoice costs and nothing
     * else. Subscriptions already running keep the expiry and grace they were sold,
     * because those were copied onto the subscription when it was written — raising
     * a price must not move the date somebody already trading gets locked out.
     */
    @Transactional
    public SubPlan savePlan(Long planId, String code, String name, String description,
                            BigDecimal price, String term, Integer maxUsers, Integer graceDays) {

        boolean editing = planId != null;

        // On an edit, an omitted field keeps what it had. A form that forgets to
        // send the user cap should not silently remove it, and requiring every
        // field on every edit is how a price gets zeroed by a screen that only
        // meant to fix a spelling.
        if (!editing) {
            if (name == null || name.isBlank()) {
                throw new ValidationException("A plan needs a name.");
            }
            if (price == null || price.compareTo(BigDecimal.ZERO) < 0) {
                throw new ValidationException("A plan needs a price of zero or more.");
            }
        } else if (price != null && price.compareTo(BigDecimal.ZERO) < 0) {
            throw new ValidationException("A price cannot be negative.");
        }
        String billing = term == null || term.isBlank() ? null : term.trim().toUpperCase();
        if (billing != null && !List.of("MONTHLY", "YEARLY").contains(billing)) {
            throw new ValidationException("Billing term must be MONTHLY or YEARLY.");
        }

        SubPlan plan;
        if (planId != null) {
            plan = planRepo.findById(planId)
                    .orElseThrow(() -> new ResourceNotFoundException("Plan", planId));
        } else {
            if (code == null || code.isBlank()) {
                throw new ValidationException("A new plan needs a code.");
            }
            if (planRepo.findByPlanCodeIgnoreCase(code.trim()).isPresent()) {
                throw new BusinessException("A plan with the code '" + code.trim() + "' already exists.",
                        HttpStatus.CONFLICT, "PLAN_CODE_TAKEN");
            }
            plan = new SubPlan();
            plan.setPlanCode(code.trim().toUpperCase());
            plan.setIsActive(1);
            plan.setSortOrder(planRepo.findAll().size() + 1);
        }

        if (name != null && !name.isBlank())  plan.setPlanName(name.trim());
        if (description != null)              plan.setDescription(description);
        if (price != null)                    plan.setPrice(price);
        if (billing != null)                  plan.setBillingTerm(billing);
        if (maxUsers != null)                 plan.setMaxUsers(maxUsers);
        if (graceDays != null)                plan.setGraceDays(Math.max(0, graceDays));
        if (plan.getBillingTerm() == null)    plan.setBillingTerm("MONTHLY");
        if (plan.getGraceDays() == null)      plan.setGraceDays(10);
        plan.setStatus("Y");
        return planRepo.save(plan);
    }

    /**
     * Takes a plan off sale.
     *
     * <p>Deactivated, never deleted, and refused outright while anybody is on it.
     * Deleting a plan that a live subscription points at would leave that pharmacy
     * with a subscription to nothing — its price, its term and its grace all gone —
     * and the failure would surface as a blank renewal screen weeks later.
     */
    @Transactional
    public void retirePlan(Long planId) {
        SubPlan plan = planRepo.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("Plan", planId));

        long onIt = subRepo.findAllLive().stream()
                .filter(x -> planId.equals(x.getPidPlan())).count();
        if (onIt > 0) {
            throw new BusinessException(
                    onIt + " pharmac" + (onIt == 1 ? "y is" : "ies are") + " on this plan. "
                    + "Move them to another plan before retiring it.",
                    HttpStatus.CONFLICT, "PLAN_IN_USE");
        }
        plan.setIsActive(0);
        planRepo.save(plan);
    }

    /* ══════════════════════════════════════════════════════════════════════
       TERMS
       ══════════════════════════════════════════════════════════════════════ */

    /** Every term, retired ones included — the screen that edits them shows both. */
    @Transactional(readOnly = true)
    public List<SubTerm> allTerms() {
        return termRepo.findAllByOrderBySortOrderAscTermMonthsAsc();
    }

    /** The terms a pharmacy can actually be sold today. */
    @Transactional(readOnly = true)
    public List<SubTerm> sellableTerms() {
        return termRepo.findByIsActiveOrderBySortOrderAscTermMonthsAsc(1);
    }

    /**
     * Adds a term, or edits one.
     *
     * <p>As with a plan, an omitted field on an edit keeps what it had: a form
     * that only meant to fix a label must not blank the discount.
     */
    @Transactional
    public SubTerm saveTerm(Long termId, Integer months, String name,
                            String description, BigDecimal discountPct, Integer sortOrder) {

        boolean editing = termId != null;

        if (months != null && months < 1) {
            throw new ValidationException("A term has to be at least one month.");
        }
        if (discountPct != null
                && (discountPct.compareTo(BigDecimal.ZERO) < 0
                 || discountPct.compareTo(new BigDecimal("100")) > 0)) {
            throw new ValidationException("A discount has to be between 0 and 100 percent.");
        }

        SubTerm term;
        if (editing) {
            term = termRepo.findById(termId)
                    .orElseThrow(() -> new ResourceNotFoundException("Term", termId));
        } else {
            if (months == null) {
                throw new ValidationException("A term needs a length in months.");
            }
            term = new SubTerm();
            term.setIsActive(1);
            term.setSortOrder(termRepo.findAll().size() + 1);
        }

        // The length is the identity of a term — two rows offering twelve months
        // are indistinguishable in the dropdown, and whichever the operator picks
        // the pharmacy gets the same expiry. Checked on both paths, because an
        // edit can collide just as easily as an insert.
        if (months != null && !months.equals(term.getTermMonths())) {
            termRepo.findByTermMonths(months).ifPresent(other -> {
                if (!other.getPidTerm().equals(term.getPidTerm())) {
                    throw new BusinessException(
                            "A " + months + "-month term already exists.",
                            HttpStatus.CONFLICT, "TERM_MONTHS_TAKEN");
                }
            });
            term.setTermMonths(months);
        }

        if (name != null && !name.isBlank()) term.setTermName(name.trim());
        if (description != null)             term.setDescription(description);
        if (discountPct != null)             term.setDiscountPct(discountPct);
        if (sortOrder != null)               term.setSortOrder(sortOrder);

        // A term with no label of its own is named after its length rather than
        // reaching the dropdown blank.
        if (term.getTermName() == null || term.getTermName().isBlank()) {
            int m = term.getTermMonths();
            term.setTermName(m + " month" + (m == 1 ? "" : "s"));
        }
        term.setStatus("Y");
        return termRepo.save(term);
    }

    /**
     * Takes a term off the list, or puts it back.
     *
     * <p>Unlike a plan this is reversible from the same call, because nothing
     * depends on it: activation reads a term once to work out an expiry date and
     * stores the date, so no live subscription points here. Retiring one changes
     * what can be sold tomorrow and nothing about what was sold yesterday.
     */
    @Transactional
    public void setTermActive(Long termId, boolean active) {
        SubTerm term = termRepo.findById(termId)
                .orElseThrow(() -> new ResourceNotFoundException("Term", termId));

        if (!active && termRepo.findByIsActiveOrderBySortOrderAscTermMonthsAsc(1).stream()
                .allMatch(t -> t.getPidTerm().equals(termId))) {
            throw new BusinessException(
                    "This is the only term on the list. Add another before retiring it, "
                    + "or no pharmacy can be activated.",
                    HttpStatus.CONFLICT, "LAST_TERM");
        }
        term.setIsActive(active ? 1 : 0);
        termRepo.save(term);
    }

    /** Removes a term outright. Safe precisely because nothing refers to one. */
    @Transactional
    public void deleteTerm(Long termId) {
        SubTerm term = termRepo.findById(termId)
                .orElseThrow(() -> new ResourceNotFoundException("Term", termId));

        if (termRepo.count() <= 1) {
            throw new BusinessException(
                    "This is the last term. Add another before deleting it, "
                    + "or no pharmacy can be activated.",
                    HttpStatus.CONFLICT, "LAST_TERM");
        }
        termRepo.delete(term);
    }

    /* ══════════════════════════════════════════════════════════════════════
       CHANGING ONE PHARMACY'S SUBSCRIPTION
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * Moves a pharmacy to a different plan.
     *
     * <p>Takes effect at the next renewal, not today: they have paid for the term
     * they are in. The grace period is re-copied from the new plan, because that is
     * part of what the new plan is.
     */
    @Transactional
    public void changePlan(Long branchId, Long planId) {
        SubSubscription sub = latestFor(branchId);
        if (sub == null) {
            throw new BusinessException("This pharmacy has no subscription to change.",
                    HttpStatus.CONFLICT, "NO_SUBSCRIPTION");
        }
        SubPlan plan = planRepo.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("Plan", planId));

        SubPlan was = planRepo.findById(sub.getPidPlan()).orElse(null);
        sub.setPidPlan(plan.getPidPlan());
        sub.setGraceDays(plan.getGraceDays());
        subRepo.save(sub);
        tenantStatus.forget(branchId);

        audit(branchId, "PLAN_CHANGED",
              "Moved to " + plan.getPlanName() + ". The new price applies from the next renewal.",
              was == null ? null : was.getPlanName(), plan.getPlanName());
    }

    /**
     * Moves an expiry date by hand.
     *
     * <p>The honest way to give somebody a week's goodwill. The alternative was to
     * raise an invoice and mark it paid for money nobody sent, which puts a lie in
     * the payment history to achieve a date change.
     *
     * <p>Recorded with a reason, because a subscription date moved without one is
     * indistinguishable from a mistake when somebody looks at it in six months.
     */
    @Transactional
    public void setExpiry(Long branchId, LocalDate expiresOn, String reason) {
        if (expiresOn == null) {
            throw new ValidationException("Give the date the subscription should run to.");
        }
        if (reason == null || reason.isBlank()) {
            throw new ValidationException(
                    "Give a reason. A subscription date moved by hand without one cannot be told "
                    + "apart from a mistake later.");
        }
        SubSubscription sub = latestFor(branchId);
        if (sub == null) {
            throw new BusinessException("This pharmacy has no subscription to extend.",
                    HttpStatus.CONFLICT, "NO_SUBSCRIPTION");
        }
        if (expiresOn.isBefore(sub.getStartsOn())) {
            throw new ValidationException("The expiry cannot be before the subscription started.");
        }

        String was = String.valueOf(sub.getExpiresOn());
        sub.setExpiresOn(expiresOn);

        // Extending past today revives a lapsed subscription; that is the point of
        // extending one, and leaving it EXPIRED would keep the pharmacy locked out
        // of the access just granted.
        if (!LocalDate.now().isAfter(sub.lastDayOfAccess())) {
            sub.setSubStatus("ACTIVE");
            branchRepo.findById(branchId).ifPresent(b -> {
                if (!"SUSPENDED".equals(b.getTenantStatus())) {
                    b.setTenantStatus("ACTIVE");
                    branchRepo.save(b);
                }
            });
        }
        subRepo.save(sub);
        tenantStatus.forget(branchId);

        audit(branchId, "EXPIRY_CHANGED", reason, was, String.valueOf(expiresOn));
        log.info("Branch {} expiry moved from {} to {}: {}", branchId, was, expiresOn, reason);
    }

    /**
     * Cancels an invoice that should not have been raised.
     *
     * <p>Voided, not deleted. An invoice is a document somebody may already have
     * been shown, and a billing history with a hole in it cannot be reconciled —
     * asking what became of SUB-2026-08-0001 has to have an answer.
     *
     * <p>A paid invoice is refused. Money changed hands, and the way back from
     * that is a refund: a different act, with its own record.
     */
    @Transactional
    public void voidInvoice(Long invoiceId, String reason) {
        SubInvoice inv = invoiceRepo.findById(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice", invoiceId));

        if ("PAID".equals(inv.getPayStatus())) {
            throw new BusinessException(
                    "Invoice " + inv.getInvoiceNo() + " has been paid and cannot be cancelled. "
                    + "Refund it instead, so the money and the document agree.",
                    HttpStatus.CONFLICT, "INVOICE_ALREADY_PAID");
        }
        if ("VOID".equals(inv.getPayStatus())) return;   // already cancelled

        inv.setPayStatus("VOID");
        invoiceRepo.save(inv);
        accounting.subscriptionVoided(inv, reason);

        audit(inv.getPidBranch(), "INVOICE_VOIDED",
              reason == null || reason.isBlank()
                      ? "Invoice " + inv.getInvoiceNo() + " cancelled"
                      : "Invoice " + inv.getInvoiceNo() + " cancelled: " + reason,
              "PENDING", "VOID");
        log.info("Invoice {} voided for branch {}", inv.getInvoiceNo(), inv.getPidBranch());
    }

    /* ══════════════════════════════════════════════════════════════════════
       THE SUPER ADMIN CONSOLE
       ══════════════════════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public List<TenantRow> allTenants() {
        Map<Long, SubSubscription> live = subRepo.findAllLive().stream()
                .collect(Collectors.toMap(SubSubscription::getPidBranch, Function.identity(), (a, b) -> a));
        Map<Long, SubPlan> plans = planRepo.findAll().stream()
                .collect(Collectors.toMap(SubPlan::getPidPlan, Function.identity()));

        List<TenantRow> rows = new ArrayList<>();
        for (AllBranch b : branchRepo.findAll()) {
            SubSubscription s = live.get(b.getPidBranch());
            SubPlan p = s == null ? null : plans.get(s.getPidPlan());
            TenantStatusService.Access a = tenantStatus.check(b.getPidBranch());

            List<SubInvoice> due = invoiceRepo
                    .findByPidBranchAndPayStatusOrderByDueOn(b.getPidBranch(), "PENDING");

            rows.add(TenantRow.builder()
                    .branchId(b.getPidBranch())
                    .orgId(b.getPidOrg())
                    .branchName(b.getBranchName())
                    .logoUrl(b.getLogoUrl())
                    .tenantStatus(a.tenantStatus())
                    .planName(p == null ? null : p.getPlanName())
                    .expiresOn(s == null ? null : s.getExpiresOn())
                    .lastDayOfAccess(s == null ? null : s.lastDayOfAccess())
                    .daysRemaining(a.daysRemaining())
                    .inGrace(a.inGrace())
                    .allowed(a.allowed())
                    .outstandingInvoices(due.size())
                    .outstandingAmount(due.stream().map(SubInvoice::getAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add))
                    .build());
        }
        rows.sort(Comparator.comparing(TenantRow::getBranchId));
        return rows;
    }

    /** Holds a branch out, or lets it back in, regardless of its dates. */
    @Transactional
    public void setTenantStatus(Long branchId, String status, String reason) {
        List<String> allowed = List.of("PENDING", "ACTIVE", "SUSPENDED", "EXPIRED");
        if (!allowed.contains(status)) {
            throw new BusinessException(
                    "'" + status + "' is not a tenant status. Use one of: " + String.join(", ", allowed) + ".",
                    HttpStatus.BAD_REQUEST, "INVALID_TENANT_STATUS");
        }
        AllBranch branch = branchRepo.findById(branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Branch", branchId));

        String was = branch.getTenantStatus();
        branch.setTenantStatus(status);
        branchRepo.save(branch);
        tenantStatus.forget(branchId);

        audit(branchId, status.equals("SUSPENDED") ? "SUSPENDED" : "STATUS_CHANGED",
              reason, was, status);
        log.info("Branch {} moved from {} to {}", branchId, was, status);
    }

    @Transactional(readOnly = true)
    public List<AuditDto> auditFor(Long branchId) {
        return auditRepo.findTop100ByPidBranchOrderByActedAtDesc(branchId).stream()
                .map(a -> AuditDto.builder()
                        .eventType(a.getEventType()).detail(a.getDetail())
                        .oldValue(a.getOldValue()).newValue(a.getNewValue())
                        .actedBy(a.getActedBy()).actedAt(a.getActedAt())
                        .build())
                .toList();
    }

    /* ══════════════════════════════════════════════════════════════════════
       THE NIGHTLY SWEEP
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * Marks subscriptions whose grace has run out.
     *
     * <p>The gate does not depend on this having run — {@link TenantStatusService}
     * compares dates on every request, so a branch is blocked the moment its grace
     * ends whether or not the job fired last night. This only makes the stored
     * status agree with the dates, so the console shows the truth and the trail
     * records when it happened.
     */
    @Scheduled(cron = "0 15 1 * * *")
    @Transactional
    public int expireOverdue() {
        LocalDate today = LocalDate.now();
        int changed = 0;

        for (SubSubscription sub : subRepo.findPastExpiry(today)) {
            if (!today.isAfter(sub.lastDayOfAccess())) continue;   // still inside grace

            sub.setSubStatus("EXPIRED");
            subRepo.save(sub);

            AllBranch branch = branchRepo.findById(sub.getPidBranch()).orElse(null);
            if (branch != null) {
                branch.setTenantStatus("EXPIRED");
                branchRepo.save(branch);
            }
            tenantStatus.forget(sub.getPidBranch());
            audit(sub.getPidBranch(), "EXPIRED",
                  "Grace of " + sub.getGraceDays() + " days ended on " + sub.lastDayOfAccess(),
                  "ACTIVE", "EXPIRED");
            changed++;
        }
        if (changed > 0) log.info("Expired {} subscription(s) past grace.", changed);
        return changed;
    }

    /* ══════════════════════════════════════════════════════════════════════
       INTERNALS
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * Where the next term begins.
     *
     * <p>From the old expiry, so a pharmacy paying three days late still gets the
     * month it bought. Unless it lapsed entirely, in which case the term starts
     * today — nobody is owed service for the months they were locked out of.
     */
    private LocalDate nextTermStart(SubSubscription sub) {
        LocalDate dayAfterExpiry = sub.getExpiresOn().plusDays(1);
        return dayAfterExpiry.isBefore(LocalDate.now()) ? LocalDate.now() : dayAfterExpiry;
    }

    private LocalDate addTerm(LocalDate from, String term) {
        return "YEARLY".equalsIgnoreCase(term) ? from.plusYears(1) : from.plusMonths(1);
    }

    /**
     * SUB-YYYYMM-NNNN — readable, sortable, and unique.
     *
     * <p>The counter is the table's own maximum id rather than a per-month
     * sequence. A month-scoped counter has to be parsed back out of the last
     * number issued, and parsing a number out of a formatted string is how the
     * sales invoice numbering broke: {@code Integer.parseInt} on a twelve-digit
     * value overflowed, the exception was swallowed, and the counter restarted.
     */
    private String nextInvoiceNo() {
        long next = invoiceRepo.maxId() + 1;
        return String.format("SUB-%s-%04d", YearMonth.now(), next);
    }

    private void audit(Long branchId, String event, String detail, String oldValue, String newValue) {
        auditRepo.save(SubAudit.builder()
                .pidBranch(branchId)
                .eventType(event)
                .detail(detail)
                .oldValue(oldValue)
                .newValue(newValue)
                .actedBy(SecurityUtils.getCurrentUsername())
                .actedAt(LocalDateTime.now())
                .build());
    }

    private InvoiceDto toInvoice(SubInvoice i) {
        return InvoiceDto.builder()
                .invoiceId(i.getPidInvoice()).invoiceNo(i.getInvoiceNo())
                .periodFrom(i.getPeriodFrom()).periodTo(i.getPeriodTo())
                .amount(i.getAmount()).issuedOn(i.getIssuedOn()).dueOn(i.getDueOn())
                .payStatus(i.getPayStatus())
                .build();
    }

    private PaymentDto toPayment(SubPayment p) {
        String no = invoiceRepo.findById(p.getPidInvoice())
                .map(SubInvoice::getInvoiceNo).orElse(null);
        return PaymentDto.builder()
                .paymentId(p.getPidPayment()).invoiceId(p.getPidInvoice()).invoiceNo(no)
                .amount(p.getAmount()).paidOn(p.getPaidOn()).method(p.getMethod())
                .providerRef(p.getProviderRef()).note(p.getNote())
                .build();
    }
}
