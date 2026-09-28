package com.friendspharma.app.service.impl;

import com.friendspharma.app.dto.response.PasswordAdminResponse.*;
import com.friendspharma.app.entity.AclPasswordResetAudit;
import com.friendspharma.app.entity.AclUser;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.AclPasswordResetAuditRepository;
import com.friendspharma.app.repository.AclUserRepository;
import com.friendspharma.app.repository.AllBranchRepository;
import com.friendspharma.app.repository.PasswordAdminRepository;
import com.friendspharma.app.security.SecurityUtils;
import com.friendspharma.app.security.TemporaryPassword;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.*;

/**
 * [V71] Super Admin password administration.
 *
 * <p><b>Why there is no "show the password".</b> Passwords are stored as BCrypt
 * hashes, which are one-way by construction: there is no key and no algorithm
 * that turns the stored value back into what was typed. This is not a policy
 * this class enforces, it is the property that makes storing passwords safe at
 * all. A screen that displayed them could only display the accounts whose
 * passwords had never been hashed — which are precisely the accounts that need
 * repairing rather than exposing — so those are surfaced as WEAK and the rest
 * are described by their state instead.
 *
 * <p>The operation offered in its place is a reset: a new random password is
 * generated, shown to the Super Admin exactly once so it can be handed over, and
 * stored only as a hash. The account is then required to replace it at the next
 * sign-in.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordAdminService {

    private final PasswordAdminRepository accounts;
    private final AclUserRepository users;
    private final AclPasswordResetAuditRepository audit;
    private final PasswordEncoder encoder;
    private final BranchScopeService branchScope;
    private final AllBranchRepository branches;
    private final PasswordResetAuditRecorder recorder;

    /** ACL_GROUP row for ROLE01 — the administrator role the security layer names. */
    private static final long ADMIN_ROLE_ID = 1L;

    /** How long a handed-over password stays usable. */
    @Value("${app.security.temp-password-hours:24}")
    private long tempPasswordHours;

    /* ══════════════════════════════════════════════════════════════════════
       WHO MAY BE HERE AT ALL
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * Refuses anyone who is not the platform's own administrator.
     *
     * <p>Stricter than the {@code hasRole('ROLE01')} on the controller, and
     * deliberately so. ROLE01 is held by the administrator of every pharmacy on
     * the installation as well as by the platform operator; a screen listing
     * every account in every branch, gated on ROLE01 alone, would let one
     * customer's administrator reset passwords belonging to a different
     * customer. {@code isPlatformOperator()} adds the half of the test a
     * customer cannot configure their way past — being in the platform's own
     * organisation.
     */
    private void requirePlatformOperator() {
        if (!branchScope.isPlatformOperator()) {
            throw new BusinessException(
                    "Password administration is limited to the platform operator. "
                    + "A pharmacy administrator can reset passwords within their own "
                    + "branch from User Permissions.",
                    HttpStatus.FORBIDDEN, "NOT_PLATFORM_OPERATOR");
        }
    }

    /* ══════════════════════════════════════════════════════════════════════
       THE LIST
       ══════════════════════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public AccountList list() {
        requirePlatformOperator();

        List<Object[]> raw = accounts.allAccounts();

        /* One query for the whole trail rather than one per row. The list is
           every account on the installation, so a per-row lookup here is the
           difference between one round trip and several hundred. */
        List<Long> ids = raw.stream().map(r -> num(r[0])).filter(Objects::nonNull).toList();
        Map<Long, AclPasswordResetAudit> lastReset = new HashMap<>();
        if (!ids.isEmpty()) {
            // Ordered newest first, so the first one seen for an id is the latest.
            for (AclPasswordResetAudit a : audit.recentResetsFor(ids)) {
                lastReset.putIfAbsent(a.getPidUser(), a);
            }
        }

        Long me = SecurityUtils.getCurrentUserId();
        List<Account> out = new ArrayList<>(raw.size());
        int weak = 0, temporary = 0;

        for (Object[] r : raw) {
            Long   pidUser = num(r[0]);
            String hash    = str(r[10]);
            boolean must   = "Y".equalsIgnoreCase(str(r[11]));
            LocalDateTime tempExpires = time(r[13]);

            Status st = classify(hash, must, tempExpires);
            if (st.word().equals("WEAK")) weak++;
            if (st.word().equals("TEMPORARY") || st.word().equals("EXPIRED")) temporary++;

            AclPasswordResetAudit last = lastReset.get(pidUser);
            String refusal = whyNotResettable(pidUser, me);

            out.add(Account.builder()
                    .pidUser(pidUser)
                    .userName(str(r[1]))
                    .fullName(str(r[2]))
                    .status("Y".equalsIgnoreCase(str(r[3])) ? "ACTIVE" : "INACTIVE")
                    .pidBranch(num(r[4]))
                    .branchName(str(r[5]))
                    .pidOrg(num(r[6]))
                    .roleName(str(r[7]))
                    .roleId(roleCode(num(r[8])))
                    .lastLogin(time(r[9]))
                    .passwordStatus(st.word())
                    .passwordStatusNote(st.note())
                    .passwordChangedAt(time(r[12]))
                    .tempPasswordExpires(tempExpires)
                    .mustChangePassword(must)
                    .email(str(r[14]))
                    .mobileNo(str(r[15]))
                    .lastResetAt(last == null ? null : last.getResetAt())
                    .lastResetBy(last == null ? null : last.getResetByName())
                    .resettable(refusal == null)
                    .notResettableReason(refusal)
                    .build());
        }

        return AccountList.builder()
                .accounts(out).total(out.size())
                .weakCount(weak).temporaryCount(temporary)
                .build();
    }

    private record Status(String word, String note) {}

    /**
     * What state a stored credential is in.
     *
     * <p>The order matters. An account mid-reset is reported as such even if the
     * stored hash is fine, because "holding a password somebody else chose" is
     * the more urgent fact about it. WEAK is checked before ACTIVE for the same
     * reason in the other direction: a password that was never hashed is not an
     * active password in good standing, however long it has worked.
     */
    private Status classify(String hash, boolean mustChange, LocalDateTime tempExpires) {
        if (hash == null || hash.isBlank()) {
            return new Status("UNSET", "No password is stored. This account cannot sign in.");
        }
        if (mustChange) {
            boolean expired = tempExpires != null && tempExpires.isBefore(LocalDateTime.now());
            return expired
                    ? new Status("EXPIRED",
                        "The temporary password has expired and no longer works. Reset it again.")
                    : new Status("TEMPORARY",
                        "Holding a temporary password. The user must set their own at next sign-in.");
        }
        if (!isHashed(hash)) {
            return new Status("WEAK",
                    "Stored without hashing, from before the migration. It is upgraded "
                    + "automatically the next time this user signs in — or immediately by "
                    + "resetting it here.");
        }
        return new Status("ACTIVE", "Set by the user and stored as a one-way hash.");
    }

    /**
     * Whether the stored value is a hash rather than the password itself.
     *
     * <p>Matches how the encoder writes them. Spring's delegating encoder
     * prefixes the algorithm in braces; a bare BCrypt hash starts with $2.
     */
    private boolean isHashed(String stored) {
        return stored.startsWith("{") || stored.startsWith("$2");
    }

    /**
     * Why this account may not be reset from this screen, or null if it may.
     *
     * <p>Two refusals, for different reasons. Resetting yourself here is refused
     * because it would take away your own access on the next request and could
     * leave the installation with nobody able to reach this screen — the
     * change-my-own-password endpoint is the way to do that safely. Resetting
     * another platform operator is refused because operators resetting one
     * another is the one case where a single compromised account can quietly
     * take over the rest.
     */
    private String whyNotResettable(Long pidUser, Long me) {
        if (pidUser != null && pidUser.equals(me)) {
            return "This is your own account — change your password from your profile.";
        }
        if (isOtherPlatformOperator(pidUser)) {
            return "Another platform operator. Resets between operators are not done from "
                 + "here, so no single account can take over the others.";
        }
        return null;
    }

    /**
     * True when this account is itself a platform operator, other than the caller.
     *
     * <p>Both halves are required, and the second one is easy to leave out. Being
     * in the platform's own organisation is not what makes somebody an operator —
     * the platform runs its own pharmacy, so the dispensers, cashiers and
     * accountants working in it are in that organisation too. Testing the
     * organisation alone refused a reset for every ordinary member of staff at
     * the platform's own branch, which on this installation is most of the user
     * list.
     *
     * <p>Mirrors {@link BranchScopeService#isPlatformOperator()} deliberately:
     * the administrator role <i>and</i> the platform organisation. That method
     * asks it of the caller; this asks it of somebody else.
     */
    private boolean isOtherPlatformOperator(Long pidUser) {
        if (pidUser == null || pidUser.equals(SecurityUtils.getCurrentUserId())) return false;
        AclUser u = users.findById(pidUser).orElse(null);
        if (u == null) return false;

        Long org = u.getPidOrg();
        boolean inPlatformOrg = org != null && org == branchScope.platformOrgId();
        boolean isAdministrator = u.getPidRole() != null && u.getPidRole() == ADMIN_ROLE_ID;
        return inPlatformOrg && isAdministrator;
    }

    /* ══════════════════════════════════════════════════════════════════════
       THE RESET
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * Issues a temporary password for one account.
     *
     * <p>Every refusal is written to the trail before it is thrown. A table that
     * records only what succeeded cannot show a Super Admin account probing
     * repeatedly at somebody it is not allowed to touch, and that pattern is the
     * one worth being able to see.
     */
    @Transactional
    public ResetResult reset(Long pidUser, String ip) {
        requirePlatformOperator();

        Long   actorId   = SecurityUtils.getCurrentUserId();
        String actorName = SecurityUtils.getCurrentUsername();

        AclUser target = users.findById(pidUser).orElse(null);
        if (target == null) {
            record(pidUser, "(unknown)", null, null, null, actorId, actorName, ip,
                   "REFUSED_NOT_FOUND", "No such account.");
            throw new ResourceNotFoundException("User", pidUser);
        }

        String branchName = branchName(target.getPidBranch());

        if (pidUser.equals(actorId)) {
            record(pidUser, target.getUserName(), target.getFullName(), target.getPidBranch(),
                   branchName, actorId, actorName, ip, "REFUSED_SELF",
                   "A Super Admin may not reset their own password from this screen.");
            throw new BusinessException(
                    "You cannot reset your own password here — it would lock you out of this "
                    + "screen on the next request. Change it from your profile instead.",
                    HttpStatus.CONFLICT, "CANNOT_RESET_SELF");
        }

        if (isOtherPlatformOperator(pidUser)) {
            record(pidUser, target.getUserName(), target.getFullName(), target.getPidBranch(),
                   branchName, actorId, actorName, ip, "REFUSED_PEER",
                   "Resets between platform operators are not permitted from this screen.");
            throw new BusinessException(
                    target.getUserName() + " is another platform operator. Resets between "
                    + "operators are not done from this screen, so that no single account can "
                    + "take over the others.",
                    HttpStatus.CONFLICT, "CANNOT_RESET_PEER");
        }

        String temp = TemporaryPassword.generate();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expires = now.plusHours(tempPasswordHours);

        target.setPasswordHash(encoder.encode(temp));
        target.setMustChangePassword("Y");
        target.setPasswordChangedAt(now);
        target.setPasswordResetBy(actorId);
        target.setTempPasswordExpires(expires);
        users.save(target);

        record(pidUser, target.getUserName(), target.getFullName(), target.getPidBranch(),
               branchName, actorId, actorName, ip, "RESET", null);

        /* The username is safe to log; the password is not, and is the one value
           that must never reach a log file, a metric or an error report. */
        log.info("Super Admin {} reset the password for {} (user {}, branch {}).",
                actorName, target.getUserName(), pidUser, branchName);

        return ResetResult.builder()
                .pidUser(pidUser)
                .userName(target.getUserName())
                .fullName(target.getFullName())
                .branchName(branchName)
                .temporaryPassword(temp)
                .expiresAt(expires)
                .resetAt(now)
                .resetBy(actorName)
                .build();
    }

    /**
     * Hands the audit row to a bean that commits it separately.
     *
     * <p>Every refusal below records and then throws, and the throw rolls this
     * method's transaction back. Saved through the ordinary repository the row
     * would go with it, leaving the trail holding successes only — so the one
     * pattern worth catching, an operator probing at accounts it may not touch,
     * would erase itself as it happened.
     */
    private void record(Long pidUser, String userName, String fullName, Long pidBranch,
                        String branchName, Long actorId, String actorName, String ip,
                        String outcome, String reason) {
        recorder.record(pidUser, userName, fullName, pidBranch, branchName,
                        SecurityUtils.getRealOrgId(), actorId, actorName, ip, outcome, reason);
    }

    /* ══════════════════════════════════════════════════════════════════════
       THE TRAIL
       ══════════════════════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public List<AuditEntry> trail(Long pidUser, int limit) {
        requirePlatformOperator();
        return audit.trail(pidUser, PageRequest.of(0, Math.max(1, Math.min(limit, 500))))
                .map(a -> AuditEntry.builder()
                        .pidAudit(a.getPidAudit()).pidUser(a.getPidUser())
                        .userName(a.getUserName()).fullName(a.getFullName())
                        .branchName(a.getBranchName()).resetByName(a.getResetByName())
                        .resetAt(a.getResetAt()).ipAddress(a.getIpAddress())
                        .outcome(a.getOutcome()).reason(a.getReason())
                        .build())
                .getContent();
    }

    /* ══════════════════════════════════════════════════════════════════════
       Reading the native row
       ══════════════════════════════════════════════════════════════════════ */

    private static Long num(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        if (o instanceof BigInteger b) return b.longValue();
        return null;
    }
    private static String str(Object o) { return o == null ? null : o.toString(); }
    private static LocalDateTime time(Object o) {
        if (o == null) return null;
        if (o instanceof java.sql.Timestamp t) return t.toLocalDateTime();
        if (o instanceof LocalDateTime d) return d;
        return null;
    }

    private String branchName(Long pidBranch) {
        if (pidBranch == null) return null;
        return branches.findById(pidBranch).map(b -> b.getBranchName()).orElse(null);
    }

    /** ROLE01 … ROLE0n from the group id, matching how the UI colours a role. */
    private static String roleCode(Long pidRole) {
        return pidRole == null ? null : String.format("ROLE%02d", pidRole);
    }
}
