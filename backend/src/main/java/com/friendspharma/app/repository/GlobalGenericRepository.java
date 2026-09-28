package com.friendspharma.app.repository;

import com.friendspharma.app.entity.GlobalGeneric;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GlobalGenericRepository extends JpaRepository<GlobalGeneric, Long> {

    List<GlobalGeneric> findByStatusOrderByGenericName(String status);

    Optional<GlobalGeneric> findByGenericNameIgnoreCase(String genericName);
}
