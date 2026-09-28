package com.friendspharma.app.repository;

import com.friendspharma.app.entity.EcomCustomerFavourite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EcomCustomerFavouriteRepository extends JpaRepository<EcomCustomerFavourite, Long> {

    /** This customer's list, newest saved first. */
    @Query("""
           SELECT f FROM EcomCustomerFavourite f
            WHERE f.pidEcomCustomer = :customerId
              AND f.status = 'Y'
            ORDER BY f.insertedDate DESC, f.pidFavourite DESC
           """)
    List<EcomCustomerFavourite> listFor(@Param("customerId") Long customerId);

    @Query("""
           SELECT f FROM EcomCustomerFavourite f
            WHERE f.pidEcomCustomer = :customerId
              AND f.pidBranch = :branchId
              AND f.pidProduct = :productId
           """)
    Optional<EcomCustomerFavourite> find(@Param("customerId") Long customerId,
                                         @Param("branchId") Long branchId,
                                         @Param("productId") Long productId);

    /**
     * Just the keys, for the storefront to mark hearts on a grid it has already
     * loaded. Cheaper than sending the whole list back with every catalogue read.
     */
    @Query("""
           SELECT CONCAT(CAST(f.pidBranch AS string), ':', CAST(f.pidProduct AS string))
             FROM EcomCustomerFavourite f
            WHERE f.pidEcomCustomer = :customerId
              AND f.status = 'Y'
           """)
    List<String> keysFor(@Param("customerId") Long customerId);
}
