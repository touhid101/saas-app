package com.friendspharma.app.service.impl.ecom;

import com.friendspharma.app.dto.request.EcomCustomerRequests;
import com.friendspharma.app.dto.response.EcomCustomerResponses;
import com.friendspharma.app.entity.EcomCustomer;
import com.friendspharma.app.entity.EcomCustomerAuth;
import com.friendspharma.app.entity.EcomCustomerRefreshToken;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.repository.EcomCustomerAuthRepository;
import com.friendspharma.app.repository.EcomCustomerRefreshTokenRepository;
import com.friendspharma.app.repository.EcomCustomerRepository;
import com.friendspharma.app.security.ecom.EcomCustomerPrincipal;
import com.friendspharma.app.security.ecom.EcomProperties;
import com.friendspharma.app.security.ecom.EcomSecurity;
import com.friendspharma.app.security.ecom.EcomTokenProvider;
import com.friendspharma.app.util.PhoneNumbers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Registration and sign-in for e-commerce customers.
 *
 * <p>The customer counterpart of {@code AuthServiceImpl}, on the customer
 * tables and the customer key. The one thing shared with staff sign-in is the
 * {@link PasswordEncoder} bean, so a customer hash is a {@code {bcrypt}} hash
 * like everybody else's. Unlike staff sign-in there is no plaintext fallback,
 * and there is a lockout: wrong passwords in a row lock the account for a
 * while, and every attempt — right, wrong, locked — is on the audit trail.
 *
 * <p>Registration creates the identity and nothing else: no pharmacy row, no
 * branch, no approval. The customer can sign in at once and see that they are
 * pending; ordering waits for the platform.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EcomCustomerAuthService {

    private static final String BAD_CREDENTIALS = "The mobile number or password is not right.";

    private final EcomCustomerRepository customers;
    private final EcomCustomerAuthRepository auths;
    private final EcomCustomerRefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final EcomTokenProvider tokens;
    private final EcomProperties props;
    private final EcomAuditRecorder audit;
    private final EcomLoginBookkeeping bookkeeping;

    /* ══ Register ═══════════════════════════════════════════════════════ */

    @Transactional
    public EcomCustomerResponses.Session register(EcomCustomerRequests.Register req) {
        String mobile = PhoneNumbers.normalise(req.getMobileNo());
        if (mobile == null) {
            throw new BusinessException("Enter a valid mobile number.", HttpStatus.UNPROCESSABLE_ENTITY, "ECOM_BAD_MOBILE");
        }
        requirePasswordStrength(req.getPassword());
        String email = blankToNull(req.getEmail());

        if (customers.existsByMobileNo(mobile) || auths.existsByLoginIdentifier(mobile)) {
            throw new BusinessException(
                    "An account already exists for this mobile number. Sign in, or reset your password.",
                    HttpStatus.CONFLICT, "ECOM_MOBILE_TAKEN");
        }
        if (email != null && customers.existsByEmailIgnoreCase(email)) {
            throw new BusinessException("An account already exists for this e-mail address.",
                    HttpStatus.CONFLICT, "ECOM_EMAIL_TAKEN");
        }

        String requested = normaliseType(req.getRequestedType());

        EcomCustomer c = EcomCustomer.builder()
                .fullName(req.getFullName().trim())
                .mobileNo(mobile).email(email)
                .customerType(EcomCustomer.TYPE_RETAILER)   // granted, never chosen — raised on approval
                .requestedType(requested)
                .approvalStatus(EcomCustomer.APPROVAL_PENDING)
                .businessName(blankToNull(req.getBusinessName()))
                .tradeLicenseNo(blankToNull(req.getTradeLicenseNo()))
                .drugLicenseNo(blankToNull(req.getDrugLicenseNo()))
                .regAddress(blankToNull(req.getRegAddress())).regArea(blankToNull(req.getRegArea()))
                .regCity(blankToNull(req.getRegCity()))
                .regLatitude(req.getRegLatitude()).regLongitude(req.getRegLongitude())
                .build();
        c.setStatus("Y");
        c = customers.save(c);
        c.setCustomerCode(String.format("EC%06d", c.getPidEcomCustomer()));
        c = customers.save(c);

        EcomCustomerAuth a = EcomCustomerAuth.builder()
                .pidEcomCustomer(c.getPidEcomCustomer())
                .loginIdentifier(mobile)
                .passwordHash(passwordEncoder.encode(req.getPassword()))
                .passwordChangedAt(LocalDateTime.now())
                .build();
        a.setStatus("Y");
        auths.save(a);

        audit.recordAnonymous(EcomAuditRecorder.REGISTERED, c.getPidEcomCustomer(), "CUST:" + c.getPidEcomCustomer(),
                "Registered as " + c.getFullName() + " (" + mobile + "), asked for " + (requested == null ? "no tier" : requested));
        log.info("E-commerce customer {} registered ({})", c.getCustomerCode(), mobile);

        return issueSession(c, a);
    }

    /* ══ Sign in ════════════════════════════════════════════════════════ */

    @Transactional
    public EcomCustomerResponses.Session login(EcomCustomerRequests.Login req) {
        String mobile = PhoneNumbers.normalise(req.getMobileNo());
        Optional<EcomCustomerAuth> found = mobile == null ? Optional.empty() : auths.findByLoginIdentifier(mobile);
        if (found.isEmpty()) {
            // Same message as a wrong password: whether a number is registered
            // is not something a stranger should be able to find out here.
            audit.recordAnonymous(EcomAuditRecorder.LOGIN_FAILED, null, mobile, "Unknown identifier");
            throw new BusinessException(BAD_CREDENTIALS, HttpStatus.UNAUTHORIZED, "ECOM_BAD_CREDENTIALS");
        }
        EcomCustomerAuth a = found.get();
        EcomCustomer c = customers.findById(a.getPidEcomCustomer()).orElseThrow();

        if (EcomCustomerAuth.STATUS_DISABLED.equals(a.getAuthStatus()) || !"Y".equals(c.getStatus())) {
            audit.recordAnonymous(EcomAuditRecorder.LOGIN_FAILED, c.getPidEcomCustomer(), mobile, "Account disabled");
            throw new BusinessException("This account has been disabled.", HttpStatus.FORBIDDEN, "ECOM_ACCOUNT_DISABLED");
        }
        if (a.isLockedNow()) {
            audit.recordAnonymous(EcomAuditRecorder.LOGIN_LOCKED, c.getPidEcomCustomer(), mobile,
                    "Locked until " + a.getLockedUntil());
            throw new BusinessException(
                    "Too many wrong attempts. Try again after " + props.getAuth().getLockoutMinutes() + " minutes.",
                    HttpStatus.LOCKED, "ECOM_ACCOUNT_LOCKED");
        }
        if (!passwordEncoder.matches(req.getPassword(), a.getPasswordHash())) {
            // Counted in its own transaction: the exception below rolls this
            // one back, and the count has to outlive the refusal it records.
            boolean lockedNow = bookkeeping.recordFailure(a.getPidAuth());
            if (lockedNow) {
                audit.recordAnonymous(EcomAuditRecorder.LOGIN_LOCKED, c.getPidEcomCustomer(), mobile,
                        "Locked after " + props.getAuth().getMaxFailedLogins() + " wrong passwords");
                throw new BusinessException(
                        "Too many wrong attempts. Try again after " + props.getAuth().getLockoutMinutes() + " minutes.",
                        HttpStatus.LOCKED, "ECOM_ACCOUNT_LOCKED");
            }
            audit.recordAnonymous(EcomAuditRecorder.LOGIN_FAILED, c.getPidEcomCustomer(), mobile, "Wrong password");
            throw new BusinessException(BAD_CREDENTIALS, HttpStatus.UNAUTHORIZED, "ECOM_BAD_CREDENTIALS");
        }

        a.setFailedLoginCount(0);
        a.setLockedUntil(null);
        a.setLastLoginAt(LocalDateTime.now());
        auths.save(a);
        refreshTokens.revokeAllByCustomer(c.getPidEcomCustomer());
        audit.recordAnonymous(EcomAuditRecorder.LOGIN_OK, c.getPidEcomCustomer(), "CUST:" + c.getPidEcomCustomer(), null);
        return issueSession(c, a);
    }

    @Transactional
    public EcomCustomerResponses.Session refresh(String refreshToken) {
        EcomCustomerRefreshToken stored = refreshTokens.findByTokenValueAndRevokedNot(refreshToken, "Y")
                .orElseThrow(() -> new BusinessException(
                        "Your session has ended. Please sign in again.", HttpStatus.UNAUTHORIZED, "ECOM_INVALID_REFRESH"));
        if (stored.getExpiresAt().isBefore(LocalDateTime.now()) || tokens.parse(refreshToken, EcomTokenProvider.TYPE_REFRESH).isEmpty()) {
            refreshTokens.revokeByToken(refreshToken);
            throw new BusinessException("Your session has ended. Please sign in again.", HttpStatus.UNAUTHORIZED, "ECOM_INVALID_REFRESH");
        }
        EcomCustomer c = customers.findById(stored.getPidEcomCustomer()).orElseThrow();
        EcomCustomerAuth a = auths.findByPidEcomCustomer(c.getPidEcomCustomer()).orElseThrow();
        if (EcomCustomerAuth.STATUS_DISABLED.equals(a.getAuthStatus()) || !"Y".equals(c.getStatus())) {
            refreshTokens.revokeAllByCustomer(c.getPidEcomCustomer());
            throw new BusinessException("This account has been disabled.", HttpStatus.FORBIDDEN, "ECOM_ACCOUNT_DISABLED");
        }
        refreshTokens.revokeByToken(refreshToken);   // rotate
        return issueSession(c, a);
    }

    @Transactional
    public void logout(String refreshToken) {
        EcomCustomerPrincipal p = EcomSecurity.require();
        if (refreshToken != null && !refreshToken.isBlank()) {
            refreshTokens.findByTokenValueAndRevokedNot(refreshToken, "Y")
                    .filter(t -> t.getPidEcomCustomer().equals(p.customerId()))
                    .ifPresent(t -> refreshTokens.revokeByToken(refreshToken));
        } else {
            refreshTokens.revokeAllByCustomer(p.customerId());
        }
        audit.record(EcomAuditRecorder.LOGOUT, p.customerId(), null);
    }

    @Transactional
    public void changePassword(EcomCustomerRequests.ChangePassword req) {
        EcomCustomerPrincipal p = EcomSecurity.require();
        EcomCustomerAuth a = auths.findByPidEcomCustomer(p.customerId()).orElseThrow();
        if (!passwordEncoder.matches(req.getCurrentPassword(), a.getPasswordHash())) {
            throw new BusinessException("The current password is not right.", HttpStatus.UNPROCESSABLE_ENTITY, "ECOM_BAD_CREDENTIALS");
        }
        requirePasswordStrength(req.getNewPassword());
        a.setPasswordHash(passwordEncoder.encode(req.getNewPassword()));
        a.setPasswordChangedAt(LocalDateTime.now());
        auths.save(a);
        // Every other device signs out; the one that changed the password keeps its access token until it expires.
        refreshTokens.revokeAllByCustomer(p.customerId());
        audit.record(EcomAuditRecorder.PASSWORD_CHANGED, p.customerId(), null);
    }

    /* ══ Helpers ════════════════════════════════════════════════════════ */

    private EcomCustomerResponses.Session issueSession(EcomCustomer c, EcomCustomerAuth a) {
        EcomCustomerPrincipal principal = new EcomCustomerPrincipal(
                c.getPidEcomCustomer(), c.getMobileNo(), c.getCustomerType(), c.getApprovalStatus());
        String access = tokens.accessToken(principal);
        String refresh = tokens.refreshToken(principal);
        refreshTokens.save(EcomCustomerRefreshToken.builder()
                .pidEcomCustomer(c.getPidEcomCustomer())
                .tokenValue(refresh)
                .expiresAt(LocalDateTime.now().plusSeconds(tokens.refreshExpiryMs() / 1000))
                .build());
        return EcomCustomerResponses.Session.builder()
                .accessToken(access).refreshToken(refresh).tokenType("Bearer")
                .expiresAt(LocalDateTime.now().plusSeconds(tokens.accessExpiryMs() / 1000))
                .customer(EcomCustomerResponses.Profile.of(c, "Y".equals(a.getPhoneVerified()), "Y".equals(a.getEmailVerified())))
                .build();
    }

    private void requirePasswordStrength(String password) {
        int min = props.getAuth().getMinPasswordLength();
        if (password == null || password.length() < min) {
            throw new BusinessException("Use a password of at least " + min + " characters.",
                    HttpStatus.UNPROCESSABLE_ENTITY, "ECOM_WEAK_PASSWORD");
        }
    }

    static String normaliseType(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String t = raw.trim().toUpperCase();
        if (!EcomCustomer.isKnownType(t)) {
            throw new BusinessException("Customer type must be RETAILER, WHOLESALER or SPECIAL.",
                    HttpStatus.UNPROCESSABLE_ENTITY, "ECOM_BAD_TYPE");
        }
        return t;
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
