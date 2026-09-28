package com.friendspharma.app.repository;

import com.friendspharma.app.entity.AllDistrict;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AllDistrictRepository extends JpaRepository<AllDistrict, Long> {
    List<AllDistrict> findByDivisionIdOrderByDistrictName(Long divisionId);
}
