package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The global e-commerce customer — {@code PHARMA.ECOM_CUSTOMER} (V100).
 *
 * <p>Deliberately carries no organisation and no branch. A pharmacy's own
 * customer list is {@link PosCustomerInfo}, which is branch-owned; this is the
 * one identity behind a phone number, able to buy from any pharmacy on the
 * platform. The pairing with a pharmacy's row happens per branch, on first
 * order, through {@link EcomCustomerBranchLink}.
 *
 * <p>{@link #customerType} is the pricing tier the sale path reads
 * ({@code CustomerPricing.tierOf}), stored in the same three spellings the ERP
 * check constraint allows once copied to a shadow row. It is changed only from
 * the E-Commerce Customers console, and every change is written to
 * {@link EcomCustomerTypeHistory}.
 */
@Entity
@Table(name = "ECOM_CUSTOMER", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EcomCustomer extends BaseEntity {

    public static final String TYPE_RETAILER   = "RETAILER";
    public static final String TYPE_WHOLESALER = "WHOLESALER";
    public static final String TYPE_SPECIAL    = "SPECIAL";

    public static final String APPROVAL_PENDING   = "PENDING";
    public static final String APPROVAL_APPROVED  = "APPROVED";
    public static final String APPROVAL_REJECTED  = "REJECTED";
    public static final String APPROVAL_SUSPENDED = "SUSPENDED";

    @Id
    @Column(name = "PID_ECOM_CUSTOMER")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_ecom_customer")
    @SequenceGenerator(name = "seq_ecom_customer", sequenceName = "SEQ_ECOM_CUSTOMER", allocationSize = 1)
    private Long pidEcomCustomer;

    /** EC000123, derived from the id after insert; never accepted from a client. */
    @Column(name = "CUSTOMER_CODE", length = 30)
    private String customerCode;

    @Column(name = "FULL_NAME", length = 150, nullable = false)
    private String fullName;

    /** Digits only, normalised by the application. Unique on the platform. */
    @Column(name = "MOBILE_NO", length = 20, nullable = false)
    private String mobileNo;

    @Column(name = "EMAIL", length = 150)
    private String email;

    @Column(name = "CUSTOMER_TYPE", length = 20, nullable = false)
    @Builder.Default
    private String customerType = TYPE_RETAILER;

    /** What the customer asked for at registration; shown to the approver, never priced from. */
    @Column(name = "REQUESTED_TYPE", length = 20)
    private String requestedType;

    @Column(name = "APPROVAL_STATUS", length = 20, nullable = false)
    @Builder.Default
    private String approvalStatus = APPROVAL_PENDING;

    @Column(name = "APPROVED_BY", length = 50)
    private String approvedBy;

    @Column(name = "APPROVED_AT")
    private LocalDateTime approvedAt;

    @Column(name = "REJECT_REASON", length = 500)
    private String rejectReason;

    @Column(name = "SUSPEND_REASON", length = 500)
    private String suspendReason;

    @Column(name = "BUSINESS_NAME", length = 200)
    private String businessName;

    @Column(name = "TRADE_LICENSE_NO", length = 60)
    private String tradeLicenseNo;

    @Column(name = "DRUG_LICENSE_NO", length = 60)
    private String drugLicenseNo;

    @Column(name = "REG_ADDRESS", length = 300)
    private String regAddress;

    @Column(name = "REG_AREA", length = 100)
    private String regArea;

    @Column(name = "REG_CITY", length = 100)
    private String regCity;

    @Column(name = "REG_LATITUDE", precision = 9, scale = 6)
    private BigDecimal regLatitude;

    @Column(name = "REG_LONGITUDE", precision = 9, scale = 6)
    private BigDecimal regLongitude;

    @Column(name = "AVATAR_URL", length = 500)
    private String avatarUrl;

    /** Cache of the loyalty ledger (Phase 13). Never edited directly. */
    @Column(name = "POINTS_BALANCE", precision = 14, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal pointsBalance = BigDecimal.ZERO;

    /* Where this customer may buy wholesale (V112). Null until somebody
       approves one — and null is not "Dhaka", it is "not yet decided", which
       is why nothing in the code substitutes a default for it. It narrows the
       pharmacies they may choose from; it is not branch ownership. */
    @Column(name = "PID_APPROVED_REGION")
    private Long pidApprovedRegion;

    @Column(name = "REGION_APPROVED_BY", length = 50)
    private String regionApprovedBy;

    @Column(name = "REGION_APPROVED_AT")
    private LocalDateTime regionApprovedAt;

    public boolean isApproved() { return APPROVAL_APPROVED.equals(approvalStatus); }

    /** Buys from one chosen pharmacy in an approved region, not from whoever is nearest. */
    public boolean isWholesaleBuyer() {
        return TYPE_WHOLESALER.equals(customerType) || TYPE_SPECIAL.equals(customerType);
    }

    /**
     * The spelling {@code POS_CUSTOMER_INFO.CUSTOMER_TYPE} accepts for this tier
     * (its check constraint, V45). What the shadow row is written with.
     */
    public static String posCustomerTypeOf(String ecomType) {
        return switch (ecomType == null ? "" : ecomType) {
            case TYPE_WHOLESALER -> "Wholesaler";
            case TYPE_SPECIAL    -> "Special";
            default              -> "Retailer";
        };
    }

    public static boolean isKnownType(String type) {
        return TYPE_RETAILER.equals(type) || TYPE_WHOLESALER.equals(type) || TYPE_SPECIAL.equals(type);
    }
}
