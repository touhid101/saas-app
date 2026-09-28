package com.friendspharma.app.service.impl;

import com.friendspharma.app.entity.AllBranch;
import com.friendspharma.app.entity.SubSubscription;
import com.friendspharma.app.repository.AllBranchRepository;
import com.friendspharma.app.repository.SubSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether a pharmacy may use the application right now.
 *
 * <p><b>Why this is cached.</b> The answer is needed on all 264 endpoints, and a
 * database round trip per request would give back the performance work that took
 * the dashboard from ten seconds to under one. The answer also changes rarely —
 * a subscription expires once — so a short time-to-live is the right trade: at
 * worst a branch keeps working for {@value #TTL_SECONDS} seconds past a change
 * nobody made through this application. Every change that <em>is</em> made
 * through it calls {@link #forget(Long)} and takes effect at once.
 *
 * <p><b>Why the branch and not the org.</b> The branch is the tenant; a chain's
 * outlets are separately subscribed, so one falling behind does not stop the
 * others trading.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantStatusService {

    /** Long enough to keep the filter off the database, short enough to feel immediate. */
    public static final int TTL_SECONDS = 60;

    private final AllBranchRepository branchRepo;
    private final SubSubscriptionRepository subRepo;

    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

    /** What the filter needs to know, and what the screen needs to say. */
    public record Access(boolean allowed,
                         String tenantStatus,
                         String reason,
                         LocalDate expiresOn,
                         LocalDate lastDayOfAccess,
                         long daysRemaining,
                         boolean inGrace) {

        static Access ok(String status, LocalDate expiry, LocalDate last, long days, boolean grace) {
            return new Access(true, status, null, expiry, last, days, grace);
        }
    }

    private record Cached(Access access, Instant at) {
        boolean fresh() { return Duration.between(at, Instant.now()).getSeconds() < TTL_SECONDS; }
    }

    /** The message a blocked user sees. Their wording, kept in one place. */
    public static final String EXPIRED_MESSAGE =
            "Your subscription has expired. Please renew your subscription to continue using the application.";

    public Access check(Long branchId) {
        if (branchId == null) {
            return new Access(false, "UNKNOWN",
                    "Your account has no branch assigned, so there is no subscription to check. "
                    + "Ask an administrator to set your branch.",
                    null, null, 0, false);
        }
        Cached c = cache.get(branchId);
        if (c != null && c.fresh()) return c.access();

        Access fresh = resolve(branchId);
        cache.put(branchId, new Cached(fresh, Instant.now()));
        return fresh;
    }

    /**
     * Drops a branch from the cache so the next request re-reads it.
     *
     * <p>Called by anything that changes a subscription. Without it a renewal
     * would appear to do nothing for up to a minute, which is exactly the moment
     * a user is watching for it to work.
     */
    public void forget(Long branchId) {
        if (branchId != null) cache.remove(branchId);
    }

    public void forgetAll() { cache.clear(); }

    private Access resolve(Long branchId) {
        AllBranch branch = branchRepo.findById(branchId).orElse(null);
        if (branch == null) {
            return new Access(false, "UNKNOWN",
                    "This branch no longer exists.", null, null, 0, false);
        }

        String tenantStatus = branch.getTenantStatus() == null ? "ACTIVE" : branch.getTenantStatus();

        // A branch held back by an administrator is blocked whatever its dates
        // say — the two are separate decisions and the administrator's wins.
        if ("SUSPENDED".equals(tenantStatus)) {
            return new Access(false, tenantStatus,
                    "This pharmacy's account has been suspended. Please contact support.",
                    null, null, 0, false);
        }
        if ("PENDING".equals(tenantStatus)) {
            return new Access(false, tenantStatus,
                    "This pharmacy's registration has not been approved yet.",
                    null, null, 0, false);
        }

        SubSubscription sub = subRepo.findLiveForBranch(branchId).orElse(null);
        if (sub == null) {
            return new Access(false, "EXPIRED", EXPIRED_MESSAGE, null, null, 0, false);
        }

        LocalDate today = LocalDate.now();
        LocalDate last = sub.lastDayOfAccess();

        if (today.isAfter(last)) {
            return new Access(false, "EXPIRED", EXPIRED_MESSAGE,
                    sub.getExpiresOn(), last, 0, false);
        }

        // Past the expiry date but inside grace: still working, and the screen
        // says so rather than waiting to surprise them on the last morning.
        boolean inGrace = today.isAfter(sub.getExpiresOn());
        long daysLeft = ChronoUnit.DAYS.between(today, last);

        return Access.ok("ACTIVE", sub.getExpiresOn(), last, Math.max(0, daysLeft), inGrace);
    }
}
