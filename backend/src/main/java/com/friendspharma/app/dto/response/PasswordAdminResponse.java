package com.friendspharma.app.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** [V71] What the Super Admin password screen shows. */
public class PasswordAdminResponse {

    /**
     * One account.
     *
     * <p>There is deliberately no password field, and there cannot be one. The
     * stored value is a BCrypt hash — a one-way function — so the password that
     * made it is not recoverable from it by this application or by anyone else.
     * {@code passwordStatus} is what replaces it: it answers the question the
     * screen is actually there to answer, which is what state the credential is
     * in and whether it needs attention.
     */
    @Data @Builder
    public static class Account {
        private Long   pidUser;
        private String userName;
        private String fullName;
        private String email;
        private String mobileNo;

        private Long   pidBranch;
        private String branchName;
        private Long   pidOrg;

        private String roleName;
        private String roleId;

        /** ACTIVE or INACTIVE, from the account itself. */
        private String status;
        private LocalDateTime lastLogin;

        /**
         * TEMPORARY, EXPIRED, WEAK, ACTIVE or UNSET.
         *
         * <p>WEAK means the stored value is not a BCrypt hash — a password from
         * before hashing that the migration has not reached, because the
         * migration runs on the sign-in that proves the password and a user who
         * has not signed in since has never triggered it. It is the one status
         * on this screen that is a defect rather than a state.
         */
        private String passwordStatus;
        private String passwordStatusNote;
        private LocalDateTime passwordChangedAt;
        private LocalDateTime tempPasswordExpires;
        private boolean mustChangePassword;

        /** From the audit trail: when this account was last reset, and by whom. */
        private LocalDateTime lastResetAt;
        private String lastResetBy;

        /** False when this account may not be reset from here, with the reason. */
        private boolean resettable;
        private String  notResettableReason;
    }

    /** The result of a reset — the one moment the temporary password exists. */
    @Data @Builder
    public static class ResetResult {
        private Long   pidUser;
        private String userName;
        private String fullName;
        private String branchName;

        /**
         * The temporary password, in clear, returned exactly once.
         *
         * <p>It is not stored anywhere in this form — only its BCrypt hash is —
         * so it cannot be retrieved again. If it is lost before it reaches the
         * user, the answer is another reset, not a lookup.
         */
        private String temporaryPassword;
        private LocalDateTime expiresAt;
        private LocalDateTime resetAt;
        private String resetBy;
    }

    /** One line of the reset trail. */
    @Data @Builder
    public static class AuditEntry {
        private Long   pidAudit;
        private Long   pidUser;
        private String userName;
        private String fullName;
        private String branchName;
        private String resetByName;
        private LocalDateTime resetAt;
        private String ipAddress;
        private String outcome;
        private String reason;
    }

    @Data @Builder
    public static class AccountList {
        private List<Account> accounts;
        private int total;
        /** How many accounts still hold a password that was never hashed. */
        private int weakCount;
        /** How many are mid-reset, holding a temporary password. */
        private int temporaryCount;
    }
}
