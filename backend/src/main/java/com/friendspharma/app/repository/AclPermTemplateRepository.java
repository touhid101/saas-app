package com.friendspharma.app.repository;

import com.friendspharma.app.entity.AclPermTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** The ready-made permission sets. */
@Repository
public interface AclPermTemplateRepository extends JpaRepository<AclPermTemplate, Long> {

    List<AclPermTemplate> findByIsActiveOrderBySortOrder(Integer isActive);

    Optional<AclPermTemplate> findByTemplateCodeIgnoreCase(String templateCode);

}