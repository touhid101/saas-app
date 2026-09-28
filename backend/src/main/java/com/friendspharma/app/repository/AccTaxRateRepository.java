package com.friendspharma.app.repository;

import com.friendspharma.app.entity.AccTaxRate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AccTaxRateRepository extends JpaRepository<AccTaxRate, Long> {
    List<AccTaxRate> findByPidOrgOrderByTaxCodeAscEffectiveFromDesc(Long pidOrg);
}
