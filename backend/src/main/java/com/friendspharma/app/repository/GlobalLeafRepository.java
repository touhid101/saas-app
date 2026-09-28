package com.friendspharma.app.repository;

import com.friendspharma.app.entity.GlobalLeaf;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GlobalLeafRepository extends JpaRepository<GlobalLeaf, Long> {

    List<GlobalLeaf> findByStatusOrderByLeafType(String status);

    Optional<GlobalLeaf> findByLeafTypeIgnoreCase(String leafType);
}
