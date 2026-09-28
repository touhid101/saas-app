package com.friendspharma.app.repository;

import com.friendspharma.app.entity.AllCompany;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AllCompanyRepository extends JpaRepository<AllCompany, Long>, JpaSpecificationExecutor<AllCompany> {
    List<AllCompany> findByPidOrgAndStatusOrderByCompanyName(Long pidOrg, String status);
    List<AllCompany> findByPidOrgOrderByCompanyName(Long pidOrg);
}
