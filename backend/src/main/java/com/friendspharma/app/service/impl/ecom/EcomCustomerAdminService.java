package com.friendspharma.app.service.impl.ecom;

import com.friendspharma.app.dto.request.EcomCustomerRequests;
import com.friendspharma.app.dto.response.EcomCustomerResponses;
import com.friendspharma.app.entity.AllRegion;
import com.friendspharma.app.entity.EcomCustomer;
import com.friendspharma.app.entity.EcomCustomerAuth;
import com.friendspharma.app.entity.EcomCustomerTypeHistory;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.*;
import com.friendspharma.app.security.SecurityUtils;
import com.friendspharma.app.service.impl.PermissionGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The E-Commerce Customers console: what a platform operator does to an online
 * customer. Every entry point passes {@link EcomScope#require} first.
 *
 * <p>Decisions and tier changes are the events RULE 8 of the brief makes
 * controlled actions: permissioned (MOD55 APPROVE), audited
 * ({@code ECOM_AUDIT_LOG}), and for the tier additionally journaled
 * ({@code ECOM_CUSTOMER_TYPE_HISTORY}) and propagated to every shadow row.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EcomCustomerAdminService {

    private final EcomScope scope;
    private final EcomCustomerRepository customers;
    private final EcomCustomerAuthRepository auths;
    private final EcomCustomerAddressRepository addresses;
    private final EcomCustomerTypeHistoryRepository typeHistory;
    private final EcomCustomerRefreshTokenRepository refreshTokens;
    private final EcomAuditLogRepository auditRows;
    private final EcomCustomerBridgeService bridge;
    private final AllBranchRepository branches;
    private final AllRegionRepository regions;
    private final EcomAuditRecorder audit;

    @Transactional(readOnly = true)
    public Page<EcomCustomerResponses.ConsoleRow> list(String status, String type, String q, int page, int size) {
        scope.require(PermissionGuard.Right.VIEW, "see online customers");
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200));
        /* The status and type are compared to a column, which gives PostgreSQL
           their type; the search term is concatenated, which does not. It goes
           down as "" rather than null. */
        return customers.search(blank(status), blank(type), q == null ? "" : q.trim(), pageable)
                .map(EcomCustomerResponses.ConsoleRow::of);
    }

    @Transactional(readOnly = true)
    public Map<String, Long> statusCounts() {
        scope.require(PermissionGuard.Right.VIEW, "see online customers");
        Map<String, Long> out = new LinkedHashMap<>();
        for (String s : List.of(EcomCustomer.APPROVAL_PENDING, EcomCustomer.APPROVAL_APPROVED,
                                EcomCustomer.APPROVAL_SUSPENDED, EcomCustomer.APPROVAL_REJECTED)) out.put(s, 0L);
        for (Object[] r : customers.countByApprovalStatus()) out.put((String) r[0], (Long) r[1]);
        return out;
    }

    @Transactional(readOnly = true)
    public EcomCustomerResponses.ConsoleDetail detail(Long id) {
        scope.require(PermissionGuard.Right.VIEW, "see online customers");
        EcomCustomer c = load(id);
        EcomCustomerAuth a = auths.findByPidEcomCustomer(id).orElse(null);
        List<EcomCustomerResponses.BranchLink> links = bridge.linksOf(id).stream().map(l ->
                EcomCustomerResponses.BranchLink.builder()
                        .branchId(l.getPidBranch())
                        .branchName(branches.findById(l.getPidBranch()).map(b -> b.getBranchName()).orElse("Branch " + l.getPidBranch()))
                        .posCustomerId(l.getPidCustomer()).linkMode(l.getLinkMode()).linkedAt(l.getLinkedAt())
                        .build()).toList();
        return EcomCustomerResponses.ConsoleDetail.builder()
                .customer(EcomCustomerResponses.Profile.of(c,
                        a != null && "Y".equals(a.getPhoneVerified()), a != null && "Y".equals(a.getEmailVerified())))
                .authStatus(a == null ? null : (a.isLockedNow() ? EcomCustomerAuth.STATUS_LOCKED : a.getAuthStatus()))
                .failedLoginCount(a == null ? null : a.getFailedLoginCount())
                .lockedUntil(a == null ? null : a.getLockedUntil())
                .lastLoginAt(a == null ? null : a.getLastLoginAt())
                .addresses(addresses.findByPidEcomCustomerAndStatusOrderByIsDefaultDescPidAddressAsc(id, "Y")
                        .stream().map(EcomCustomerResponses.Address::of).toList())
                .typeHistory(typeHistory.findByPidEcomCustomerOrderByChangedAtDesc(id)
                        .stream().map(EcomCustomerResponses.TypeChange::of).toList())
                .branchLinks(links)
                .approvedRegionName(c.getPidApprovedRegion() == null ? null
                        : regions.findById(c.getPidApprovedRegion()).map(AllRegion::getRegionName).orElse(null))
                .recentAudit(auditRows.findByPidEcomCustomerOrderByActedAtDesc(id, PageRequest.of(0, 30))
                        .stream().map(EcomCustomerResponses.AuditRow::of).toList())
                .build();
    }

    /* ── Decisions ────────────────────────────────────────────────────── */

    @Transactional
    public EcomCustomerResponses.ConsoleRow approve(Long id, EcomCustomerRequests.Decision req) {
        scope.require(PermissionGuard.Right.APPROVE, "approve an online customer");
        EcomCustomer c = load(id);
        if (c.isApproved()) {
            throw new BusinessException("This customer is already approved.", HttpStatus.CONFLICT, "ECOM_ALREADY_APPROVED");
        }
        String previous = c.getApprovalStatus();
        // A first approval grants what was asked for (or Retailer); reinstating a
        // suspended customer keeps the tier they already held. An explicit type wins either way.
        String grant = req != null && req.getCustomerType() != null && !req.getCustomerType().isBlank()
                ? EcomCustomerAuthService.normaliseType(req.getCustomerType())
                : EcomCustomer.APPROVAL_SUSPENDED.equals(previous) ? c.getCustomerType()
                : (c.getRequestedType() != null ? c.getRequestedType() : EcomCustomer.TYPE_RETAILER);
        c.setApprovalStatus(EcomCustomer.APPROVAL_APPROVED);
        c.setApprovedBy(actor());
        c.setApprovedAt(LocalDateTime.now());
        c.setRejectReason(null);
        c.setSuspendReason(null);
        customers.save(c);
        if (!grant.equals(c.getCustomerType())) {
            changeType(c, grant, "Granted on approval" + (req != null && req.getReason() != null ? ": " + req.getReason() : ""));
        }
        audit.record(previous.equals(EcomCustomer.APPROVAL_SUSPENDED) ? EcomAuditRecorder.REINSTATED : EcomAuditRecorder.APPROVED,
                id, "Approved as " + grant + reasonSuffix(req));
        log.info("E-commerce customer {} approved as {} by {}", c.getCustomerCode(), grant, actor());
        return EcomCustomerResponses.ConsoleRow.of(c);
    }

    @Transactional
    public EcomCustomerResponses.ConsoleRow reject(Long id, EcomCustomerRequests.Decision req) {
        scope.require(PermissionGuard.Right.APPROVE, "reject an online customer");
        EcomCustomer c = load(id);
        requireReason(req, "Say why the registration is rejected.");
        if (c.isApproved()) {
            throw new BusinessException("An approved customer is suspended, not rejected.", HttpStatus.CONFLICT, "ECOM_ALREADY_APPROVED");
        }
        c.setApprovalStatus(EcomCustomer.APPROVAL_REJECTED);
        c.setRejectReason(req.getReason().trim());
        customers.save(c);
        refreshTokens.revokeAllByCustomer(id);
        audit.record(EcomAuditRecorder.REJECTED, id, req.getReason());
        return EcomCustomerResponses.ConsoleRow.of(c);
    }

    @Transactional
    public EcomCustomerResponses.ConsoleRow suspend(Long id, EcomCustomerRequests.Decision req) {
        scope.require(PermissionGuard.Right.APPROVE, "suspend an online customer");
        EcomCustomer c = load(id);
        requireReason(req, "Say why the account is suspended.");
        if (!c.isApproved()) {
            throw new BusinessException("Only an approved customer can be suspended.", HttpStatus.CONFLICT, "ECOM_NOT_APPROVED");
        }
        c.setApprovalStatus(EcomCustomer.APPROVAL_SUSPENDED);
        c.setSuspendReason(req.getReason().trim());
        customers.save(c);
        // Signed out everywhere: the next request re-reads the row and finds SUSPENDED.
        refreshTokens.revokeAllByCustomer(id);
        audit.record(EcomAuditRecorder.SUSPENDED, id, req.getReason());
        return EcomCustomerResponses.ConsoleRow.of(c);
    }

/**
     * Approves, corrects or clears the region a wholesale customer may buy in.
     *
     * <p>Only a wholesale account needs one: a retailer buys from whoever is
     * near them, and giving them a region would imply a restriction that does
     * not apply. Clearing it (a null region) is allowed and is not a
     * punishment — it is what "we need to check this again" looks like, and
     * the customer is told what to complete rather than simply refused.
     *
     * <p>Recorded with who did it. A region nobody can point at afterwards is
     * one nobody can defend.
     */
    @Transactional
    public EcomCustomerResponses.ConsoleRow setRegion(Long id, EcomCustomerRequests.SetRegion req) {
        scope.require(PermissionGuard.Right.APPROVE, "approve an online customer's wholesale region");
        EcomCustomer c = load(id);

        if (!c.isWholesaleBuyer()) {
            throw new BusinessException(
                    c.getFullName() + " is a retail account and buys from the pharmacies near them, "
                  + "so there is no wholesale region to approve.",
                    HttpStatus.CONFLICT, "ECOM_NOT_WHOLESALE");
        }

        Long next = req.getRegionId();
        if (next != null && regions.findById(next).isEmpty()) {
            throw new BusinessException("That region does not exist.", HttpStatus.NOT_FOUND, "ECOM_NO_REGION");
        }

        String was = c.getPidApprovedRegion() == null ? "none"
                : regions.findById(c.getPidApprovedRegion()).map(AllRegion::getRegionName).orElse(String.valueOf(c.getPidApprovedRegion()));
        String now = next == null ? "none"
                : regions.findById(next).map(AllRegion::getRegionName).orElse(String.valueOf(next));

        c.setPidApprovedRegion(next);
        c.setRegionApprovedBy(next == null ? null : SecurityUtils.getCurrentUsername());
        c.setRegionApprovedAt(next == null ? null : LocalDateTime.now());
        customers.save(c);

        audit.record("REGION_APPROVED", id, null, "ECOM_CUSTOMER", id,
                was + " → " + now + (req.getReason() == null || req.getReason().isBlank() ? "" : " · " + req.getReason().trim()));

        /* A cart at a pharmacy the new region does not cover is now unbuyable.
           It is left alone rather than emptied: the customer chose those lines,
           and checkout will say plainly why it cannot go through. */
        return EcomCustomerResponses.ConsoleRow.of(c);
    }

    @Transactional
    public EcomCustomerResponses.ConsoleRow setType(Long id, EcomCustomerRequests.SetType req) {
        scope.require(PermissionGuard.Right.APPROVE, "change an online customer's type");
        EcomCustomer c = load(id);
        String next = EcomCustomerAuthService.normaliseType(req.getCustomerType());
        if (next.equals(c.getCustomerType())) {
            throw new BusinessException("The customer is already " + next + ".", HttpStatus.CONFLICT, "ECOM_TYPE_UNCHANGED");
        }
        changeType(c, next, req.getReason().trim());
        return EcomCustomerResponses.ConsoleRow.of(c);
    }

    /**
     * Makes (or adopts) the pharmacy-side row for this customer at one branch now,
     * ahead of their first order there. The same bridge checkout will use; here
     * so the pairing can be made and seen from the console.
     */
    @Transactional
    public EcomCustomerResponses.BranchLink linkBranch(Long id, Long branchId) {
        scope.require(PermissionGuard.Right.APPROVE, "link an online customer to a pharmacy");
        EcomCustomer c = load(id);
        Long posId = bridge.ensureShadow(c.getPidEcomCustomer(), branchId);
        var l = bridge.linksOf(id).stream().filter(x -> x.getPidBranch().equals(branchId)).findFirst().orElseThrow();
        return EcomCustomerResponses.BranchLink.builder()
                .branchId(branchId)
                .branchName(branches.findById(branchId).map(b -> b.getBranchName()).orElse("Branch " + branchId))
                .posCustomerId(posId).linkMode(l.getLinkMode()).linkedAt(l.getLinkedAt())
                .build();
    }

    @Transactional
    public void unlock(Long id) {
        scope.require(PermissionGuard.Right.EDIT, "unlock an online customer");
        EcomCustomerAuth a = auths.findByPidEcomCustomer(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", id));
        a.setLockedUntil(null);
        a.setFailedLoginCount(0);
        auths.save(a);
        audit.record(EcomAuditRecorder.UNLOCKED, id, null);
    }

    /* ── The one place a tier changes ─────────────────────────────────── */

    private void changeType(EcomCustomer c, String next, String reason) {
        String old = c.getCustomerType();
        c.setCustomerType(next);
        customers.save(c);
        typeHistory.save(EcomCustomerTypeHistory.builder()
                .pidEcomCustomer(c.getPidEcomCustomer()).oldType(old).newType(next)
                .changedBy(actor()).reason(reason).build());
        int shadows = bridge.propagateType(c.getPidEcomCustomer(), next);
        audit.record(EcomAuditRecorder.TYPE_CHANGED, c.getPidEcomCustomer(),
                old + " -> " + next + " (" + reason + "); " + shadows + " pharmacy row(s) updated");
        log.info("E-commerce customer {} type {} -> {} by {} ({} shadow rows)", c.getCustomerCode(), old, next, actor(), shadows);
    }

    private EcomCustomer load(Long id) {
        return customers.findById(id).orElseThrow(() -> new ResourceNotFoundException("Customer", id));
    }

    private static String actor() {
        String u = SecurityUtils.getCurrentUsername();
        return u == null ? "SYSTEM" : u;
    }

    private static void requireReason(EcomCustomerRequests.Decision req, String message) {
        if (req == null || req.getReason() == null || req.getReason().isBlank()) {
            throw new BusinessException(message, HttpStatus.UNPROCESSABLE_ENTITY, "ECOM_REASON_REQUIRED");
        }
    }

    private static String reasonSuffix(EcomCustomerRequests.Decision req) {
        return req != null && req.getReason() != null && !req.getReason().isBlank() ? " — " + req.getReason().trim() : "";
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
