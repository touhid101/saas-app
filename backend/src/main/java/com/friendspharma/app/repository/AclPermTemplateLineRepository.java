package com.friendspharma.app.repository;

import com.friendspharma.app.entity.AclPermTemplateLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** The modules inside a template. */
@Repository
public interface AclPermTemplateLineRepository extends JpaRepository<AclPermTemplateLine, Long> {

    List<AclPermTemplateLine> findByPidTemplate(Long pidTemplate);

}