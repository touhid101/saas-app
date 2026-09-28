package com.friendspharma.app.service.impl;

import com.friendspharma.app.dto.request.LoginRequest;
import com.friendspharma.app.dto.request.RbacRequest;
import com.friendspharma.app.dto.request.UpdateProfileRequest;
import com.friendspharma.app.dto.response.AuthResponse;
import com.friendspharma.app.dto.response.UserResponse;
import com.friendspharma.app.entity.AclUser;
import com.friendspharma.app.entity.PosRefreshToken;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.AclGroupRepository;
import com.friendspharma.app.repository.AclUserRepository;
import com.friendspharma.app.repository.AllBranchRepository;
import com.friendspharma.app.repository.PosRefreshTokenRepository;
import com.friendspharma.app.security.CustomUserDetailsService;
import com.friendspharma.app.security.JwtTokenProvider;
import com.friendspharma.app.security.SecurityUtils;
import com.friendspharma.app.security.UserPrincipal;
import com.friendspharma.app.service.interfaces.AuthService;
import com.friendspharma.app.service.interfaces.PermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final AuthenticationManager authManager;
    private final JwtTokenProvider tokenProvider;
    private final CustomUserDetailsService userDetailsService;
    private final AclUserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final AclGroupRepository groupRepo;
    private final PosRefreshTokenRepository refreshTokenRepo;
    private final AllBranchRepository branchRepo;
    private final ImageStorageService images;
    private final PermissionService permissionService;

    @Value("${app.jwt.refresh-token-expiry-ms}")
    private long refreshExpiryMs;

    @Value("${app.jwt.access-token-expiry-ms}")
    private long accessExpiryMs;

    @Override
    @Transactional
    public AuthResponse login(LoginRequest request) {
        Authentication auth = authManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
        );

        UserPrincipal principal = (UserPrincipal) auth.getPrincipal();

        // The one moment the plaintext exists is the moment it is typed, so this
        // is the only place a password stored before hashing can be migrated.
        upgradeStoredPassword(principal.getUserId(), request.getPassword());

        // Update last login
        userRepo.updateLastLogin(principal.getUserId(), LocalDateTime.now());

        String accessToken  = tokenProvider.generateAccessToken(principal);
        String refreshToken = tokenProvider.generateRefreshToken(principal);

        // Revoke old refresh tokens, store new one
        refreshTokenRepo.revokeAllByUser(principal.getUserId());
        refreshTokenRepo.save(PosRefreshToken.builder()
                .pidUser(principal.getUserId())
                .tokenValue(refreshToken)
                .expiresAt(LocalDateTime.now().plusSeconds(refreshExpiryMs / 1000))
                .build());

        return buildAuthResponse(accessToken, refreshToken, principal);
    }

    /**
     * Rewrites a pre-hashing password as BCrypt, once, on the sign-in that proves it.
     *
     * <p>Only runs when the stored value is not already in the current scheme, so
     * it costs one string comparison per login thereafter. Authentication has
     * already succeeded by the time this is called — the raw password is known to
     * be correct, and re-encoding it changes what is stored, never what is valid.
     *
     * <p>A failure here must not fail the login. The user typed the right
     * password; refusing them entry because a housekeeping write did not land
     * would turn a silent improvement into an outage.
     */
    private void upgradeStoredPassword(Long userId, String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) return;
        try {
            AclUser user = userRepo.findById(userId).orElse(null);
            if (user == null) return;

            String stored = user.getPasswordHash();
            if (stored == null || !passwordEncoder.upgradeEncoding(stored)) return;

            user.setPasswordHash(passwordEncoder.encode(rawPassword));
            userRepo.save(user);
            log.info("Migrated the stored password for user {} to BCrypt.", userId);
        } catch (RuntimeException e) {
            log.warn("Could not migrate the stored password for user {}; login proceeds.", userId, e);
        }
    }

    @Override
    @Transactional
    public AuthResponse refresh(String refreshToken) {
        PosRefreshToken stored = refreshTokenRepo
                .findByTokenValueAndRevokedNot(refreshToken, "Y")
                .orElseThrow(() -> new BusinessException(
                        "Refresh token is invalid or has been revoked. Please log in again.",
                        HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN"));

        if (stored.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException("Refresh token has expired. Please log in again.",
                    HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_EXPIRED");
        }

        if (!tokenProvider.isTokenValid(refreshToken)) {
            throw new BusinessException("Refresh token signature is invalid.",
                    HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN");
        }

        String username = tokenProvider.extractUsername(refreshToken);
        UserPrincipal principal = (UserPrincipal) userDetailsService.loadUserByUsername(username);

        String newAccess  = tokenProvider.generateAccessToken(principal);
        String newRefresh = tokenProvider.generateRefreshToken(principal);

        // Rotate refresh token
        refreshTokenRepo.revokeByToken(refreshToken);
        refreshTokenRepo.save(PosRefreshToken.builder()
                .pidUser(principal.getUserId())
                .tokenValue(newRefresh)
                .expiresAt(LocalDateTime.now().plusSeconds(refreshExpiryMs / 1000))
                .build());

        return buildAuthResponse(newAccess, newRefresh, principal);
    }

    @Override
    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            refreshTokenRepo.revokeByToken(refreshToken);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getCurrentUser() {
        UserPrincipal principal = SecurityUtils.getCurrentUser();
        AclUser user = userRepo.findById(principal.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User", principal.getUserId()));
        return mapToUserResponse(user, principal);
    }

    /**
     * The signed-in user's own photo.
     *
     * <p><b>Why this changed.</b> It used to base64-encode the image into a data
     * URL and write that to {@code ACL_USER.AVATAR_URL}, a {@code VARCHAR(500)}
     * column. The shortest possible JPEG runs to thousands of characters, so
     * every upload overflowed the column and came back as "A database constraint
     * was violated". It had never once succeeded: no row in the table has an
     * avatar longer than the 41 characters a file path takes.
     *
     * <p>The image now goes to disk and the column holds its URL, which is what
     * the admin route beside it has always done — the five avatars that do exist
     * were all set that way. One storage path, one shape of value in the column.
     *
     * <p>A data URL was the wrong shape regardless of the column width: it
     * travels in every {@code /api/auth/me} response and into localStorage, so a
     * 2 MB photo became 2.7 MB of base64 on every sign-in.
     */
    @Override
    @Transactional
    public UserResponse uploadAvatar(MultipartFile file) {
        UserPrincipal principal = SecurityUtils.getCurrentUser();
        AclUser user = userRepo.findById(principal.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User", principal.getUserId()));

        String previous = user.getAvatarUrl();
        String url = images.store(file, ImageStorageService.Bucket.AVATAR, user.getPidUser());

        user.setAvatarUrl(url);
        // The blob columns are left alone. Nothing reads them — no row in the
        // table has ever had one — and ACL_USER.IMAGE is an oid, so writing
        // bytes there creates a large object that nothing would unlink.
        userRepo.save(user);

        // Only once the new file is safely written, and never the same file.
        if (previous != null && !previous.equals(url)) {
            images.delete(ImageStorageService.Bucket.AVATAR, previous);
        }

        return mapToUserResponse(user, principal);
    }

    @Override
    @Transactional
    public UserResponse updateMyProfile(UpdateProfileRequest request) {
        UserPrincipal principal = SecurityUtils.getCurrentUser();
        AclUser user = userRepo.findById(principal.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User", principal.getUserId()));

        // Reject an email already claimed by a different account
        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            userRepo.findAll().stream()
                    .filter(u -> request.getEmail().equalsIgnoreCase(u.getEmail()))
                    .filter(u -> !u.getPidUser().equals(user.getPidUser()))
                    .findFirst()
                    .ifPresent(u -> {
                        throw new BusinessException("That email address is already in use.",
                                HttpStatus.CONFLICT, "EMAIL_TAKEN");
                    });
        }

        user.setFullName(request.getFullName().trim());
        user.setEmail(request.getEmail() == null || request.getEmail().isBlank()
                ? null : request.getEmail().trim());
        user.setMobileNo(request.getMobileNo() == null || request.getMobileNo().isBlank()
                ? null : request.getMobileNo().trim());

        userRepo.save(user);
        log.info("Profile updated for user {}", user.getPidUser());
        return mapToUserResponse(user, principal);
    }

    @Override
    @Transactional
    public void changeMyPassword(RbacRequest.ChangePasswordRequest request) {
        UserPrincipal principal = SecurityUtils.getCurrentUser();
        // Delegates to the single implementation of the password rules. Because the
        // target id is the caller's own id, the current-password check is enforced.
        permissionService.changePassword(principal.getUserId(), request);
    }

    /* ── helpers ── */

    private AuthResponse buildAuthResponse(String accessToken, String refreshToken, UserPrincipal principal) {
        AclUser user = userRepo.findById(principal.getUserId()).orElseThrow();
        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresAt(LocalDateTime.now().plusSeconds(accessExpiryMs / 1000))
                .user(mapToUserResponse(user, principal))
                .mustChangePassword(user.mustChangePassword())
                .build();
    }

    private UserResponse mapToUserResponse(AclUser user, UserPrincipal principal) {
        String roleColor = null;
        if (user.getPidRole() != null) {
            roleColor = groupRepo.findById(user.getPidRole())
                    .map(g -> g.getRoleColor()).orElse(null);
        }
        /* The branch label and mark, so the UI can show whose pharmacy this is
           straight off the session rather than asking again. Read together from
           one row: they are the same fact about the same place, and fetching
           them separately is two queries for one answer. */
        String branchName = null;
        String branchLogoUrl = null;
        if (user.getPidBranch() != null) {
            var branch = branchRepo.findById(user.getPidBranch()).orElse(null);
            if (branch != null) {
                branchName = branch.getBranchName();
                branchLogoUrl = branch.getLogoUrl();
            }
        }
        return UserResponse.builder()
                .branchName(branchName)
                .branchLogoUrl(branchLogoUrl)
                .userId(user.getPidUser())
                .username(user.getUserName())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .mobileNo(user.getMobileNo())
                .orgId(user.getPidOrg())
                .companyId(user.getPidCompany())
                .branchId(user.getPidBranch())
                .roleCode(principal.getRoleCode())
                .roleName(principal.getRoleName())
                .roleColor(roleColor)
                .superAdmin(principal.isSuperAdmin())
                .status(user.getStatus())
                .avatarUrl(user.getAvatarUrl())
                .employeeId(user.getPidEmployee())
                .lastLogin(user.getLastLogin())
                .build();
    }
}
