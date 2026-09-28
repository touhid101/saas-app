package com.friendspharma.app.repository;

import com.friendspharma.app.entity.AclUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * [V71] The account list behind the Super Admin password screen.
 *
 * <p>Separate from AclUserRepository because it reads across every organisation
 * on the installation, which nothing else may do. Keeping it in its own type
 * means the cross-tenant query cannot be reached by accident from code that is
 * supposed to be scoped to one pharmacy.
 */
public interface PasswordAdminRepository extends JpaRepository<AclUser, Long> {

    /**
     * Every account, with the branch and role names the screen shows.
     *
     * <p>The password hash comes back so the caller can classify how it is
     * stored — whether it is BCrypt or one of the plaintext values the lazy
     * migration has not reached yet. It is used to derive a status word and is
     * never returned to the client.
     */
    @Query(value = """
           SELECT u.PID_USER, u.USER_NAME, u.FULL_NAME, u.STATUS,
                  u.PID_BRANCH, b.BRANCH_NAME, u.PID_ORG,
                  g.GROUP_NAME, u.PID_ROLE,
                  u.LAST_LOGIN, u.PASSWORD_HASH,
                  u.MUST_CHANGE_PASSWORD, u.PASSWORD_CHANGED_AT, u.TEMP_PASSWORD_EXPIRES,
                  u.EMAIL, u.MOBILE_NO
             FROM PHARMA.ACL_USER u
             LEFT JOIN PHARMA.ALL_BRANCH b ON b.PID_BRANCH = u.PID_BRANCH
             LEFT JOIN PHARMA.ACL_GROUP  g ON g.PID_GROUP  = u.PID_ROLE
            ORDER BY COALESCE(b.BRANCH_NAME, ''), u.USER_NAME
           """, nativeQuery = true)
    List<Object[]> allAccounts();
}
