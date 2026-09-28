package com.friendspharma.app.repository;

import com.friendspharma.app.entity.EcomCart;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EcomCartRepository extends JpaRepository<EcomCart, Long> {

    Optional<EcomCart> findByPidEcomCustomerAndPidBranchAndCartStatus(Long pidEcomCustomer, Long pidBranch, String cartStatus);

    List<EcomCart> findByPidEcomCustomerAndCartStatusOrderByUpdatedDateDesc(Long pidEcomCustomer, String cartStatus);

    /** Ownership is part of the key: another customer's cart id is a 404, not a 403. */
    Optional<EcomCart> findByPidCartAndPidEcomCustomer(Long pidCart, Long pidEcomCustomer);
}
