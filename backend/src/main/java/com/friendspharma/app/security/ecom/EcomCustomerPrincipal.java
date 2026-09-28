package com.friendspharma.app.security.ecom;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/**
 * Who is calling a customer endpoint.
 *
 * <p>Deliberately <b>not</b> a {@link org.springframework.security.core.userdetails.UserDetails}
 * and not a {@link com.friendspharma.app.security.UserPrincipal}. Every staff
 * filter and every {@code SecurityUtils} helper asks for the latter and treats
 * anything else as "nobody signed in", which is exactly right: a customer has
 * no organisation, no branch and no role in the ERP, and code that resolves a
 * branch from the session must find nothing here rather than something wrong.
 *
 * @param customerId      {@code ECOM_CUSTOMER.PID_ECOM_CUSTOMER}
 * @param loginIdentifier the normalised mobile number the token was issued to
 * @param customerType    the pricing tier at the time the request was authenticated
 * @param approvalStatus  PENDING / APPROVED / REJECTED / SUSPENDED at that moment
 */
public record EcomCustomerPrincipal(Long customerId, String loginIdentifier,
                                    String customerType, String approvalStatus) {

    public static final String ROLE = "ECOM_CUSTOMER";

    public List<GrantedAuthority> authorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + ROLE));
    }

    public boolean isApproved() { return "APPROVED".equals(approvalStatus); }

    /** How the audit trail and BaseEntity name this actor. */
    public String auditName() { return "CUST:" + customerId; }
}
