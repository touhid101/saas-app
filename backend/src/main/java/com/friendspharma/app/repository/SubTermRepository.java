package com.friendspharma.app.repository;

import com.friendspharma.app.entity.SubTerm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** The subscription lengths on offer. */
@Repository
public interface SubTermRepository extends JpaRepository<SubTerm, Long> {

    List<SubTerm> findAllByOrderBySortOrderAscTermMonthsAsc();

    List<SubTerm> findByIsActiveOrderBySortOrderAscTermMonthsAsc(Integer isActive);

    Optional<SubTerm> findByTermMonths(Integer termMonths);
}
