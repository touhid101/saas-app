package com.friendspharma.app.repository;

import com.friendspharma.app.entity.EcomCustomerRefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface EcomCustomerRefreshTokenRepository extends JpaRepository<EcomCustomerRefreshToken, Long> {

    Optional<EcomCustomerRefreshToken> findByTokenValueAndRevokedNot(String tokenValue, String revoked);

    @Modifying
    @Query("UPDATE EcomCustomerRefreshToken t SET t.revoked = 'Y' WHERE t.pidEcomCustomer = :customerId")
    void revokeAllByCustomer(@Param("customerId") Long customerId);

    @Modifying
    @Query("UPDATE EcomCustomerRefreshToken t SET t.revoked = 'Y' WHERE t.tokenValue = :token")
    void revokeByToken(@Param("token") String token);
}
