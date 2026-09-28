package com.friendspharma.app.service.impl;

import com.friendspharma.app.entity.AclUser;
import com.friendspharma.app.entity.PosInvoiceUnlock;
import com.friendspharma.app.entity.PosTransactionMst;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.exception.custom.ValidationException;
import com.friendspharma.app.repository.AclGroupRepository;
import com.friendspharma.app.repository.AclUserRepository;
import com.friendspharma.app.repository.PosInvoiceUnlockRepository;
import com.friendspharma.app.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * Reopening an invoice somebody else already saved.
 *
 * <p>Until now the counter operator who raised an invoice could edit it freely
 * while it sat at Submitted — change the lines, the customer, the money. That is
 * the oldest hole in any till: ring up a sale, take the cash, then quietly
 * reduce the invoice. Two separate faults kept it open.
 *
 * <p>The first was that the endpoint checked role codes only, so anyone the
 * screen hid the Edit button from could still call the API and change the
 * invoice anyway.
 *
 * <p>The second was that there was no way to permit a legitimate correction, so
 * the only workable configuration was to leave editing open to everybody.
 *
 * <p>Reopening is therefore an <b>administrator's</b> decision. Not the edit
 * right from the permission matrix: a branch manager holds that — it is what
 * lets them correct an invoice at all — so keying the gate to it would let the
 * manager reopen anything unchallenged, which is the opposite of the control.
 * An administrator either does it themselves or authorises it with their
 * password, and the authorisation is a single-use token bound to the invoice
 * and to the person who asked, valid for a few minutes, recorded either way.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceUnlockServiceImpl {

    private final PosInvoiceUnlockRepository unlockRepo;
    private final PermissionGuard permissions;
    private final AclUserRepository userRepo;
    private final AclGroupRepository groupRepo;
    private final PasswordEncoder passwordEncoder;

    /**
     * Long enough that a supervisor is not rushed, short enough that walking
     * away from an authorised till does not leave it open.
     */
    private static final int VALID_MINUTES = 10;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** What the caller needs in order to edit, and what it would take. */
    public record Authority(boolean allowed, boolean needsOverride, String reason) {}

    /* ══════════════════════════════════════════════════════════════════════
       WHO MAY EDIT
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * Whether this user may reopen a saved invoice on their own authority.
     *
     * <p>The Approve tick on Quick Sales / POS in the RBAC screen, not a role
     * named here. This used to be Super Admin only, so changing who may unlock
     * meant changing Java; now it is a checkbox.
     *
     * <p>Deliberately the Approve right and not Edit. A cashier holds Edit on
     * Quick Sales — it is what lets them build a sale at all — so keying unlock
     * to Edit would let anyone reopen anything, which is the opposite of the
     * control this exists to provide.
     *
     * <p><b>Read from the role, not the subscription.</b> Whether somebody is
     * senior enough to vouch for a correction is a fact about their job. Reading
     * it through the plan meant an Administrator (Branch) at a pharmacy on the
     * Basic template was told they were "not an administrator" while holding the
     * tick in RBAC — and, because the same right gates unaided editing, that
     * pharmacy had nobody at all who could correct an invoice. A plan may
     * withhold a feature; it should not withhold the only way to fix a mistake.
     */
    @Transactional(readOnly = true)
    public boolean mayEditUnaided(Long userId) {
        return permissions.hasByRole(userId, POS_MODULE, PermissionGuard.Right.APPROVE);
    }
    /**
     * Whether this user may authorise somebody else's edit.
     *
     * <p>The same right. If someone who could not unlock on their own authority
     * could nonetheless authorise, the password box would be a formality: the
     * requester would simply fetch a colleague who cannot be held to it.
     */
    @Transactional(readOnly = true)
    public boolean mayAuthorise(Long userId) {
        return permissions.hasByRole(userId, POS_MODULE, PermissionGuard.Right.APPROVE);
    }

    /** Quick Sales / POS — the module whose Approve tick governs unlocking. */
    private static final String POS_MODULE = "MOD05";

    /* ══════════════════════════════════════════════════════════════════════
       AUTHORISE
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * A supervisor permits one edit of one invoice.
     *
     * @return the token the caller must present with the edit. Returned once
     *         and never stored in plain form.
     */
    @Transactional
    public String authorise(PosTransactionMst sale, String username, String rawPassword, String reason) {
        Long requesterId = SecurityUtils.getCurrentUserId();
        if (requesterId == null) {
            throw new ValidationException("You must be signed in to request an unlock.");
        }
        if (username == null || username.isBlank() || rawPassword == null || rawPassword.isBlank()) {
            throw new ValidationException("Enter the supervisor's username and password.");
        }

        AclUser supervisor = userRepo.findByUsername(username.trim()).orElse(null);

        // One message for every kind of failure. Saying "no such user" would
        // turn this box into a way to discover who has authority.
        boolean ok = supervisor != null
                && "Y".equals(supervisor.getStatus())
                && supervisor.getPasswordHash() != null
                && passwordEncoder.matches(rawPassword, supervisor.getPasswordHash());

        if (!ok) {
            log.warn("Invoice unlock refused for {} — bad supervisor credentials (username tried: {})",
                    sale.getInvoiceNo(), username.trim());
            // Not 401: the person calling is signed in and their session is
            // fine — it is the credentials they typed into the dialog that
            // were rejected. A 401 here reads to any client as "your session
            // has gone" and invites exactly the logout this caused.
            throw new BusinessException(
                    "Those credentials were not accepted.",
                    HttpStatus.UNPROCESSABLE_ENTITY, "UNLOCK_REJECTED");
        }

        // Beyond this point the person is who they say they are; the remaining
        // checks are about whether they are allowed to say yes to this.
        if (supervisor.getPidUser().equals(requesterId)) {
            throw new BusinessException(
                    "An invoice cannot be unlocked with your own credentials. Ask a supervisor.",
                    HttpStatus.FORBIDDEN, "UNLOCK_SELF");
        }
        Long orgId = SecurityUtils.getCurrentOrgId();
        if (orgId != null && supervisor.getPidOrg() != null && !orgId.equals(supervisor.getPidOrg())) {
            throw new BusinessException("Those credentials were not accepted.",
                    HttpStatus.UNPROCESSABLE_ENTITY, "UNLOCK_REJECTED");
        }
        if (!mayAuthorise(supervisor.getPidUser())) {
            log.warn("Invoice unlock refused for {} — {} lacks Approve on {}",
                    sale.getInvoiceNo(), supervisor.getUserName(), POS_MODULE);
            // Names the tick that is missing, not just the verdict. The previous
            // wording said only "is not an administrator", which is unarguable
            // and unactionable — and was wrong often enough that the person
            // reading it went looking in the wrong place.
            throw new BusinessException(
                    (supervisor.getFullName() != null ? supervisor.getFullName() : "That user")
                            + " cannot authorise an invoice edit: their role does not hold "
                            + "Approve on Quick Sales / POS. Grant it in RBAC → Permissions, "
                            + "or ask someone whose role does.",
                    HttpStatus.FORBIDDEN, "UNLOCK_NOT_PERMITTED");
        }

        String token = newToken();
        LocalDateTime now = LocalDateTime.now();

        PosInvoiceUnlock u = new PosInvoiceUnlock();
        u.setPidOrg(sale.getPidOrg());
        u.setPidBranch(sale.getPidBranch());
        u.setPidTranMst(sale.getPidTranMst());
        u.setInvoiceNo(sale.getInvoiceNo());
        u.setRequestedBy(requesterId);
        u.setRequestedByName(SecurityUtils.getCurrentUsername());
        u.setAuthorisedBy(supervisor.getPidUser());
        u.setAuthorisedByName(supervisor.getFullName() != null
                ? supervisor.getFullName() : supervisor.getUserName());
        u.setReason(reason == null || reason.isBlank() ? null : reason.trim());
        u.setTokenHash(hash(token));
        u.setIssuedAt(now);
        u.setExpiresAt(now.plusMinutes(VALID_MINUTES));
        u.setStatus(PosInvoiceUnlock.ACTIVE);
        u.setInsertedBy(SecurityUtils.getCurrentUsername());
        u.setInsertedDate(now);

        unlockRepo.save(u);

        log.info("Invoice {} unlocked for {} by {} — {}", sale.getInvoiceNo(),
                u.getRequestedByName(), u.getAuthorisedByName(),
                u.getReason() == null ? "no reason given" : u.getReason());

        return token;
    }

    /* ══════════════════════════════════════════════════════════════════════
       SPEND
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * Checks the caller may edit this invoice, consuming an authorisation if
     * that is what permits it.
     *
     * <p>Called from the edit itself rather than from the controller: the rule
     * has to hold for every route into an edit, and a check in one endpoint is
     * a check somebody adds a second endpoint around.
     */
    @Transactional
    public void requireEditRight(PosTransactionMst sale, String token) {
        Long userId = SecurityUtils.getCurrentUserId();

        if (mayEditUnaided(userId)) return;

        if (token == null || token.isBlank()) {
            throw new BusinessException(
                    "Invoice " + sale.getInvoiceNo() + " has been saved. Changing a saved invoice "
                    + "needs an administrator — ask one to unlock it for you.",
                    HttpStatus.FORBIDDEN, "UNLOCK_REQUIRED");
        }

        PosInvoiceUnlock u = unlockRepo
                .findUsable(hash(token), sale.getPidTranMst(), userId, LocalDateTime.now())
                .orElseThrow(() -> new BusinessException(
                        "That authorisation is no longer valid — it may have expired, been used "
                        + "already, or been issued for a different invoice. Ask for it again.",
                        HttpStatus.FORBIDDEN, "UNLOCK_INVALID"));

        u.setUsedAt(LocalDateTime.now());
        u.setUpdatedBy(SecurityUtils.getCurrentUsername());
        u.setUpdatedDate(LocalDateTime.now());
        unlockRepo.save(u);

        log.info("Invoice {} edited by {} under authorisation from {}",
                sale.getInvoiceNo(), u.getRequestedByName(), u.getAuthorisedByName());
    }

    /* ══════════════════════════════════════════════════════════════════════
       TOKENS
       ══════════════════════════════════════════════════════════════════════ */

    /** 256 bits from a CSPRNG — not guessable inside the ten minutes it lives. */
    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM; if it is missing, failing shut
            // is the only safe answer.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /* ══════════════════════════════════════════════════════════════════════
       LOOKUP
       ══════════════════════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public PosTransactionMst requireSale(Long id,
                                         com.friendspharma.app.repository.PosTransactionMstRepository mstRepo) {
        PosTransactionMst m = mstRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sale", id));
        Long orgId = SecurityUtils.getCurrentOrgId();
        if (orgId != null && m.getPidOrg() != null && !orgId.equals(m.getPidOrg())) {
            throw new ResourceNotFoundException("Sale", id);
        }
        return m;
    }
}
