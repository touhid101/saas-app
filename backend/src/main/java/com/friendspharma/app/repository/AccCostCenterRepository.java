package com.friendspharma.app.repository;

import com.friendspharma.app.entity.AccCostCenter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AccCostCenterRepository extends JpaRepository<AccCostCenter, Long> {
    List<AccCostCenter> findByPidOrgOrderByCcCodeAsc(Long pidOrg);

    /** What an entry screen offers: a retired cost centre stays on old postings but is not offered again. */
    List<AccCostCenter> findByPidOrgAndIsActiveOrderByCcCodeAsc(Long pidOrg, Integer isActive);
}
