package com.friendspharma.app.repository;

import com.friendspharma.app.entity.GlobalCompany;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GlobalCompanyRepository extends JpaRepository<GlobalCompany, Long> {

    List<GlobalCompany> findByStatusOrderByCompanyName(String status);

    Optional<GlobalCompany> findByCompanyNameIgnoreCase(String companyName);
}
