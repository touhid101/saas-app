package com.friendspharma.app.repository;

import com.friendspharma.app.entity.GlobalBox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GlobalBoxRepository extends JpaRepository<GlobalBox, Long> {

    List<GlobalBox> findByStatusOrderByBoxSizeTitle(String status);

    Optional<GlobalBox> findByBoxSizeTitleIgnoreCase(String boxSizeTitle);

    /** Used before retiring a leaf: a box cannot outlive the strip it is built from. */
    long countByPidGlobalLeaf(Long pidGlobalLeaf);
}
