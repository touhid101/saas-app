package com.friendspharma.app.repository;

import com.friendspharma.app.entity.AccPaymentMode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AccPaymentModeRepository extends JpaRepository<AccPaymentMode, Long> {
    @Query("select m from AccPaymentMode m where (m.pidOrg = :org or m.pidOrg is null) and m.isActive = 1 order by m.pidOrg nulls last")
    List<AccPaymentMode> findEffective(@Param("org") Long pidOrg);
}
