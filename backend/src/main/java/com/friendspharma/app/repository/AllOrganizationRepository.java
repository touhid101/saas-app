package com.friendspharma.app.repository;

import com.friendspharma.app.entity.AllOrganization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AllOrganizationRepository extends JpaRepository<AllOrganization, Long>, JpaSpecificationExecutor<AllOrganization> {
    List<AllOrganization> findByStatusOrderByOrgName(String status);
}
