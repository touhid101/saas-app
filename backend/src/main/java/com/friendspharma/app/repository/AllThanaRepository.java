package com.friendspharma.app.repository;

import com.friendspharma.app.entity.AllThana;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AllThanaRepository extends JpaRepository<AllThana, Long> {
    List<AllThana> findByDistrictIdOrderByThanaName(Long districtId);
}
