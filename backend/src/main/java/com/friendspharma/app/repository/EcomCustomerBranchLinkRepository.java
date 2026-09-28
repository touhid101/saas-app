package com.friendspharma.app.repository;

import com.friendspharma.app.entity.EcomCustomerBranchLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EcomCustomerBranchLinkRepository extends JpaRepository<EcomCustomerBranchLink, Long> {

    Optional<EcomCustomerBranchLink> findByPidEcomCustomerAndPidBranch(Long pidEcomCustomer, Long pidBranch);

    Optional<EcomCustomerBranchLink> findByPidCustomer(Long pidCustomer);

    List<EcomCustomerBranchLink> findByPidEcomCustomer(Long pidEcomCustomer);
}
