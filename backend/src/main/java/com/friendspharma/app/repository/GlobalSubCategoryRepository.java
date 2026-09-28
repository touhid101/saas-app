package com.friendspharma.app.repository;

import com.friendspharma.app.entity.GlobalSubCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GlobalSubCategoryRepository extends JpaRepository<GlobalSubCategory, Long> {

    List<GlobalSubCategory> findByStatusOrderBySubCategoryName(String status);

    Optional<GlobalSubCategory> findBySubCategoryNameIgnoreCase(String subCategoryName);
}
