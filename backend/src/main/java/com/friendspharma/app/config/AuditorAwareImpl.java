package com.friendspharma.app.config;

import com.friendspharma.app.security.SecurityUtils;
import com.friendspharma.app.security.ecom.EcomCustomerPrincipal;
import com.friendspharma.app.security.ecom.EcomSecurity;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component("auditorAwareImpl")
public class AuditorAwareImpl implements AuditorAware<String> {

    @Override
    public Optional<String> getCurrentAuditor() {
        try {
            // A signed-in e-commerce customer is not a staff user, so the
            // username lookup finds nobody. Name them the way the audit trail
            // does (CUST:<id>) rather than falling through to SYSTEM.
            EcomCustomerPrincipal customer = EcomSecurity.current();
            if (customer != null) return Optional.of(customer.auditName());
            return Optional.of(SecurityUtils.getCurrentUsername());
        } catch (Exception e) {
            return Optional.of("SYSTEM");
        }
    }
}
