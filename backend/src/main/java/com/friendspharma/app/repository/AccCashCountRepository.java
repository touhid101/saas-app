package com.friendspharma.app.repository;

import com.friendspharma.app.entity.AccCashCount;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AccCashCountRepository extends JpaRepository<AccCashCount, Long> {
    List<AccCashCount> findByPidBusinessDayOrderByDenomValueDesc(Long pidBusinessDay);
    void deleteByPidBusinessDay(Long pidBusinessDay);
}
