package com.friendspharma.app.repository;

import com.friendspharma.app.entity.AllRegion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AllRegionRepository extends JpaRepository<AllRegion, Long> {
    List<AllRegion> findByStatusOrderByRegionName(String status);
}
