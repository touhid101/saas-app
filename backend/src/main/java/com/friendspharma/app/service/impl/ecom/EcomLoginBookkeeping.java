package com.friendspharma.app.service.impl.ecom;

import com.friendspharma.app.entity.EcomCustomerAuth;
import com.friendspharma.app.repository.EcomCustomerAuthRepository;
import com.friendspharma.app.security.ecom.EcomProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * The failed-attempt counter, committed on its own.
 *
 * <p>A wrong password ends in an exception, and an exception rolls the
 * surrounding transaction back — including the very increment that is
 * supposed to count the attempt. Written here, in a transaction of its own,
 * the count survives the refusal it records.
 */
@Component
@RequiredArgsConstructor
public class EcomLoginBookkeeping {

    private final EcomCustomerAuthRepository auths;
    private final EcomProperties props;

    /** Counts one wrong password. Returns true when this one locked the account. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordFailure(Long pidAuth) {
        EcomCustomerAuth a = auths.findById(pidAuth).orElse(null);
        if (a == null) return false;
        int failed = a.getFailedLoginCount() + 1;
        if (failed >= props.getAuth().getMaxFailedLogins()) {
            a.setLockedUntil(LocalDateTime.now().plusMinutes(props.getAuth().getLockoutMinutes()));
            a.setFailedLoginCount(0);
            auths.save(a);
            return true;
        }
        a.setFailedLoginCount(failed);
        auths.save(a);
        return false;
    }
}
