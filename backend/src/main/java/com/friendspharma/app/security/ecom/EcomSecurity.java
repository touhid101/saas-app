package com.friendspharma.app.security.ecom;

import com.friendspharma.app.exception.custom.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** The signed-in customer, the way {@code SecurityUtils} gives the signed-in staff member. */
public final class EcomSecurity {

    private EcomSecurity() {}

    /** The customer on this request, or null when the caller is not a customer. */
    public static EcomCustomerPrincipal current() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated()) return null;
        return a.getPrincipal() instanceof EcomCustomerPrincipal p ? p : null;
    }

    /** The customer on this request, or 401. */
    public static EcomCustomerPrincipal require() {
        EcomCustomerPrincipal p = current();
        if (p == null) {
            throw new BusinessException("Please sign in to continue.",
                    HttpStatus.UNAUTHORIZED, "ECOM_NOT_SIGNED_IN");
        }
        return p;
    }

    public static Long currentCustomerId() {
        EcomCustomerPrincipal p = current();
        return p == null ? null : p.customerId();
    }
}
