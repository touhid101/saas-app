package com.friendspharma.app.repository;

import com.friendspharma.app.entity.PosRefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PosRefreshTokenRepository extends JpaRepository<PosRefreshToken, Long> {

    Optional<PosRefreshToken> findByTokenValueAndRevokedNot(String tokenValue, String revoked);

    @Modifying
    @Query("UPDATE PosRefreshToken t SET t.revoked = 'Y' WHERE t.pidUser = :userId")
    void revokeAllByUser(@Param("userId") Long userId);

    @Modifying
    @Query("UPDATE PosRefreshToken t SET t.revoked = 'Y' WHERE t.tokenValue = :token")
    void revokeByToken(@Param("token") String token);
}
