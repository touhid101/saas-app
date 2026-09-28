package com.friendspharma.app.repository;

import com.friendspharma.app.entity.AccDenomination;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AccDenominationRepository extends JpaRepository<AccDenomination, Long> {
    List<AccDenomination> findByCurrencyAndIsActiveOrderBySortOrderAsc(String currency, Integer isActive);
}
