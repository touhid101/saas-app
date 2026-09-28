package com.friendspharma.app.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Everything the storefront account screens and the E-Commerce Customers
 * console send. One file of nested types, as {@code HrRequests}.
 *
 * <p><b>No request carries a branch, an organisation, a pricing tier the
 * customer decides for themselves, or an approval state.</b> A customer may
 * <em>ask</em> for a tier at registration ({@link Register#requestedType});
 * the platform grants it.
 */
public final class EcomCustomerRequests {
    private EcomCustomerRequests() {}

    /* ══ Customer side ═══════════════════════════════════════════════════ */

    @Data
    public static class Register {
        @NotBlank(message = "Your name is required.")
        @Size(max = 150)
        private String fullName;

        @NotBlank(message = "A mobile number is required.")
        @Size(max = 20)
        private String mobileNo;

        @Email(message = "That does not look like an e-mail address.")
        @Size(max = 150)
        private String email;

        @NotBlank(message = "A password is required.")
        @Size(max = 128)
        private String password;

        /** RETAILER, WHOLESALER or SPECIAL — what they are asking to be approved as. */
        @Size(max = 20)
        private String requestedType;

        @Size(max = 200) private String businessName;
        @Size(max = 60)  private String tradeLicenseNo;
        @Size(max = 60)  private String drugLicenseNo;

        @Size(max = 300) private String regAddress;
        @Size(max = 100) private String regArea;
        @Size(max = 100) private String regCity;
        @DecimalMin("-90")  @DecimalMax("90")  private BigDecimal regLatitude;
        @DecimalMin("-180") @DecimalMax("180") private BigDecimal regLongitude;
    }

    @Data
    public static class Login {
        @NotBlank(message = "Mobile number is required.")
        private String mobileNo;
        @NotBlank(message = "Password is required.")
        private String password;
    }

    @Data
    public static class Refresh {
        @NotBlank private String refreshToken;
    }

    @Data
    public static class UpdateProfile {
        @NotBlank(message = "Your name is required.")
        @Size(max = 150)
        private String fullName;
        @Email @Size(max = 150) private String email;
        @Size(max = 200) private String businessName;
        @Size(max = 60)  private String tradeLicenseNo;
        @Size(max = 60)  private String drugLicenseNo;
        @Size(max = 300) private String regAddress;
        @Size(max = 100) private String regArea;
        @Size(max = 100) private String regCity;
        @DecimalMin("-90")  @DecimalMax("90")  private BigDecimal regLatitude;
        @DecimalMin("-180") @DecimalMax("180") private BigDecimal regLongitude;
    }

    @Data
    public static class ChangePassword {
        @NotBlank private String currentPassword;
        @NotBlank @Size(max = 128) private String newPassword;
    }

    @Data
    public static class Address {
        @Size(max = 40)  private String label;
        @Size(max = 150) private String recipientName;
        @Size(max = 20)  private String phone;
        @NotBlank(message = "The address line is required.")
        @Size(max = 200) private String line1;
        @Size(max = 200) private String line2;
        @Size(max = 100) private String area;
        @Size(max = 100) private String city;
        @Size(max = 20)  private String postcode;
        @DecimalMin("-90")  @DecimalMax("90")  private BigDecimal latitude;
        @DecimalMin("-180") @DecimalMax("180") private BigDecimal longitude;
        private Boolean isDefault;
    }

    /* ══ Console side ════════════════════════════════════════════════════ */

    @Data
    public static class Decision {
        /** Required for reject and suspend; optional for approve. */
        @Size(max = 500) private String reason;
        /** On approve: the tier to grant. Defaults to what was requested, else RETAILER. */
        @Size(max = 20)  private String customerType;
    }

    @Data
    public static class SetType {
        @NotBlank(message = "A customer type is required.")
        @Size(max = 20) private String customerType;
        @NotBlank(message = "Say why the type is changing.")
        @Size(max = 300) private String reason;
    }

    /** Approving (or correcting) the region a wholesale customer buys in. */
    @Data
    public static class SetRegion {
        private Long regionId;
        private String reason;
    }
}
