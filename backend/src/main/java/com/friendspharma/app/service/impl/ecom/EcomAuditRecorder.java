package com.friendspharma.app.service.impl.ecom;

import com.friendspharma.app.entity.EcomAuditLog;
import com.friendspharma.app.repository.EcomAuditLogRepository;
import com.friendspharma.app.security.SecurityUtils;
import com.friendspharma.app.security.ecom.EcomCustomerPrincipal;
import com.friendspharma.app.security.ecom.EcomSecurity;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Writes {@code ECOM_AUDIT_LOG} in its own transaction.
 *
 * <p>Same reasoning as {@code PasswordResetAuditRecorder}: a row written inside
 * the action's transaction disappears with the action when it is refused, and
 * a refused sign-in or a refused approval is exactly what the trail is for.
 * A failure to record never fails the action — but it is logged at error.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EcomAuditRecorder {

    public static final String REGISTERED         = "REGISTERED";
    public static final String LOGIN_OK           = "LOGIN_OK";
    public static final String LOGIN_FAILED       = "LOGIN_FAILED";
    public static final String LOGIN_LOCKED       = "LOGIN_LOCKED";
    public static final String LOGOUT             = "LOGOUT";
    public static final String PASSWORD_CHANGED   = "PASSWORD_CHANGED";
    public static final String PROFILE_UPDATED    = "PROFILE_UPDATED";
    public static final String ADDRESS_ADDED      = "ADDRESS_ADDED";
    public static final String ADDRESS_UPDATED    = "ADDRESS_UPDATED";
    public static final String ADDRESS_REMOVED    = "ADDRESS_REMOVED";
    public static final String APPROVED           = "APPROVED";
    public static final String REJECTED           = "REJECTED";
    public static final String SUSPENDED          = "SUSPENDED";
    public static final String REINSTATED         = "REINSTATED";
    public static final String TYPE_CHANGED       = "TYPE_CHANGED";
    public static final String UNLOCKED           = "UNLOCKED";
    public static final String BRANCH_LINKED      = "BRANCH_LINKED";

    private final EcomAuditLogRepository repo;

    /** Records an event by whoever is on the current request (customer, staff or nobody = SYSTEM). */
    public void record(String eventType, Long customerId, String detail) {
        record(eventType, customerId, null, null, null, detail);
    }

    public void record(String eventType, Long customerId, Long branchId,
                       String refType, Long refId, String detail) {
        String actorKind;
        String actorId;
        EcomCustomerPrincipal customer = EcomSecurity.current();
        if (customer != null) {
            actorKind = EcomAuditLog.ACTOR_CUSTOMER;
            actorId = customer.auditName();
        } else if (SecurityUtils.getCurrentUserId() != null) {
            actorKind = EcomAuditLog.ACTOR_STAFF;
            actorId = SecurityUtils.getCurrentUsername();
        } else {
            actorKind = EcomAuditLog.ACTOR_SYSTEM;
            actorId = null;
        }
        write(eventType, actorKind, actorId, customerId, branchId, refType, refId, detail, clientIp());
    }

    /** For events where the actor is known but not signed in — a failed login names the identifier tried. */
    public void recordAnonymous(String eventType, Long customerId, String actorId, String detail) {
        write(eventType, EcomAuditLog.ACTOR_SYSTEM, actorId, customerId, null, null, null, detail, clientIp());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(String eventType, String actorKind, String actorId, Long customerId, Long branchId,
                      String refType, Long refId, String detail, String ip) {
        try {
            repo.save(EcomAuditLog.builder()
                    .eventType(eventType).actorKind(actorKind).actorId(trim(actorId, 80))
                    .pidEcomCustomer(customerId).pidBranch(branchId)
                    .refType(refType).refId(refId)
                    .detail(trim(detail, 1000)).ipAddress(trim(ip, 64))
                    .build());
        } catch (RuntimeException e) {
            log.error("Could not write the e-commerce audit row {} for customer {}: {}",
                    eventType, customerId, e.getMessage(), e);
        }
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String clientIp() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            HttpServletRequest req = attrs.getRequest();
            String fwd = req.getHeader("X-Forwarded-For");
            if (fwd != null && !fwd.isBlank()) return fwd.split(",")[0].trim();
            return req.getRemoteAddr();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
