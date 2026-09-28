package com.friendspharma.app.repository;

import com.friendspharma.app.entity.GlobalCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GlobalCategoryRepository extends JpaRepository<GlobalCategory, Long> {

    List<GlobalCategory> findByStatusOrderByCategoryName(String status);

    Optional<GlobalCategory> findByCategoryNameIgnoreCase(String categoryName);
}
