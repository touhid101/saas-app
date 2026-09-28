package com.friendspharma.app.dto.response;

import com.friendspharma.app.entity.EcomAuditLog;
import com.friendspharma.app.entity.EcomCustomer;
import com.friendspharma.app.entity.EcomCustomerAddress;
import com.friendspharma.app.entity.EcomCustomerTypeHistory;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** What the storefront account screens and the console read. */
public final class EcomCustomerResponses {
    private EcomCustomerResponses() {}

    /** The customer as they see themselves. No approval internals beyond the status. */
    @Getter @Builder
    public static class Profile {
        private final Long id;
        private final String customerCode;
        private final String fullName;
        private final String mobileNo;
        private final String email;
        private final String customerType;
        private final String requestedType;
        private final String approvalStatus;
        private final String rejectReason;
        private final String suspendReason;
        private final String businessName;
        private final String tradeLicenseNo;
        private final String drugLicenseNo;
        private final String regAddress;
        private final String regArea;
        private final String regCity;
        private final BigDecimal regLatitude;
        private final BigDecimal regLongitude;
        private final String avatarUrl;
        private final BigDecimal pointsBalance;
        /* [V112] The region this account may buy wholesale in, and who said so.
           Null on a retail account, where it would imply a restriction that
           does not apply. */
        private final Long approvedRegionId;
        private final String regionApprovedBy;
        private final LocalDateTime regionApprovedAt;
        private final boolean phoneVerified;
        private final boolean emailVerified;
        private final LocalDateTime registeredAt;
        private final LocalDateTime approvedAt;

        public static Profile of(EcomCustomer c, boolean phoneVerified, boolean emailVerified) {
            return Profile.builder()
                    .id(c.getPidEcomCustomer()).customerCode(c.getCustomerCode())
                    .fullName(c.getFullName()).mobileNo(c.getMobileNo()).email(c.getEmail())
                    .customerType(c.getCustomerType()).requestedType(c.getRequestedType())
                    .approvalStatus(c.getApprovalStatus())
                    .rejectReason(c.getRejectReason()).suspendReason(c.getSuspendReason())
                    .businessName(c.getBusinessName()).tradeLicenseNo(c.getTradeLicenseNo())
                    .drugLicenseNo(c.getDrugLicenseNo())
                    .regAddress(c.getRegAddress()).regArea(c.getRegArea()).regCity(c.getRegCity())
                    .regLatitude(c.getRegLatitude()).regLongitude(c.getRegLongitude())
                    .avatarUrl(c.getAvatarUrl()).pointsBalance(c.getPointsBalance())
                    .approvedRegionId(c.getPidApprovedRegion())
                    .regionApprovedBy(c.getRegionApprovedBy()).regionApprovedAt(c.getRegionApprovedAt())
                    .phoneVerified(phoneVerified).emailVerified(emailVerified)
                    .registeredAt(c.getInsertedDate()).approvedAt(c.getApprovedAt())
                    .build();
        }
    }

    @Getter @Builder
    public static class Session {
        private final String accessToken;
        private final String refreshToken;
        private final String tokenType;
        private final LocalDateTime expiresAt;
        private final Profile customer;
    }

    @Getter @Builder
    public static class Address {
        private final Long id;
        private final String label;
        private final String recipientName;
        private final String phone;
        private final String line1;
        private final String line2;
        private final String area;
        private final String city;
        private final String postcode;
        private final BigDecimal latitude;
        private final BigDecimal longitude;
        /** Named explicitly: Jackson would otherwise publish a boolean isDefault as "default". */
        @com.fasterxml.jackson.annotation.JsonProperty("isDefault")
        private final boolean isDefault;

        public static Address of(EcomCustomerAddress a) {
            return Address.builder()
                    .id(a.getPidAddress()).label(a.getLabel()).recipientName(a.getRecipientName())
                    .phone(a.getPhone()).line1(a.getLine1()).line2(a.getLine2()).area(a.getArea())
                    .city(a.getCity()).postcode(a.getPostcode())
                    .latitude(a.getLatitude()).longitude(a.getLongitude())
                    .isDefault("Y".equals(a.getIsDefault()))
                    .build();
        }
    }

    /* ── Console ─────────────────────────────────────────────────────────── */

    @Getter @Builder
    public static class ConsoleRow {
        private final Long id;
        private final String customerCode;
        private final String fullName;
        private final String mobileNo;
        private final String email;
        private final String customerType;
        private final String requestedType;
        private final String approvalStatus;
        private final String businessName;
        private final String regCity;
        private final LocalDateTime registeredAt;
        private final LocalDateTime approvedAt;
        private final String approvedBy;

        public static ConsoleRow of(EcomCustomer c) {
            return ConsoleRow.builder()
                    .id(c.getPidEcomCustomer()).customerCode(c.getCustomerCode())
                    .fullName(c.getFullName()).mobileNo(c.getMobileNo()).email(c.getEmail())
                    .customerType(c.getCustomerType()).requestedType(c.getRequestedType())
                    .approvalStatus(c.getApprovalStatus()).businessName(c.getBusinessName())
                    .regCity(c.getRegCity()).registeredAt(c.getInsertedDate())
                    .approvedAt(c.getApprovedAt()).approvedBy(c.getApprovedBy())
                    .build();
        }
    }

    @Getter @Builder
    public static class TypeChange {
        private final String oldType;
        private final String newType;
        private final String changedBy;
        private final String reason;
        private final LocalDateTime changedAt;

        public static TypeChange of(EcomCustomerTypeHistory h) {
            return TypeChange.builder().oldType(h.getOldType()).newType(h.getNewType())
                    .changedBy(h.getChangedBy()).reason(h.getReason()).changedAt(h.getChangedAt()).build();
        }
    }

    @Getter @Builder
    public static class AuditRow {
        private final String eventType;
        private final String actorKind;
        private final String actorId;
        private final String detail;
        private final LocalDateTime actedAt;

        public static AuditRow of(EcomAuditLog a) {
            return AuditRow.builder().eventType(a.getEventType()).actorKind(a.getActorKind())
                    .actorId(a.getActorId()).detail(a.getDetail()).actedAt(a.getActedAt()).build();
        }
    }

    @Getter @Builder
    public static class BranchLink {
        private final Long branchId;
        private final String branchName;
        private final Long posCustomerId;
        private final String linkMode;
        private final LocalDateTime linkedAt;
    }

    /** Everything the console shows for one customer. */
    @Getter @Builder
    public static class ConsoleDetail {
        private final Profile customer;
        private final String authStatus;
        private final Integer failedLoginCount;
        private final LocalDateTime lockedUntil;
        private final LocalDateTime lastLoginAt;
        private final List<Address> addresses;
        private final List<TypeChange> typeHistory;
        private final List<BranchLink> branchLinks;
        private final List<AuditRow> recentAudit;
        /** Resolved for the screen, so the drawer never shows a bare region id. */
        private final String approvedRegionName;
    }
}
