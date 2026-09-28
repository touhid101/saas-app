package com.friendspharma.app.repository;

import com.friendspharma.app.entity.GlobalUnit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GlobalUnitRepository extends JpaRepository<GlobalUnit, Long> {

    List<GlobalUnit> findByStatusOrderByUnitName(String status);

    Optional<GlobalUnit> findByUnitNameIgnoreCase(String unitName);
}
