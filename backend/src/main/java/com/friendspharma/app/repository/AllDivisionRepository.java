package com.friendspharma.app.repository;

import com.friendspharma.app.entity.AllDivision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AllDivisionRepository extends JpaRepository<AllDivision, Long> {
    List<AllDivision> findByPidRegionOrderByDivisionName(Long pidRegion);
}
