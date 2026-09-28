package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A medicine a customer saved — {@code PHARMA.ECOM_CUSTOMER_FAVOURITE} (V113).
 *
 * <p>A favourite is a product <em>at a pharmacy</em>, not a product on its own:
 * the same medicine is a separate listing at every branch, with its own price
 * and its own stock, so saving the medicine alone would save something that has
 * no price to show.
 */
@Entity
@Table(name = "ECOM_CUSTOMER_FAVOURITE", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EcomCustomerFavourite extends BaseEntity {

    @Id
    @Column(name = "PID_FAVOURITE")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_ecom_customer_favourite")
    @SequenceGenerator(name = "seq_ecom_customer_favourite",
                       sequenceName = "SEQ_ECOM_CUSTOMER_FAVOURITE", allocationSize = 1)
    private Long pidFavourite;

    @Column(name = "PID_ECOM_CUSTOMER", nullable = false)
    private Long pidEcomCustomer;

    @Column(name = "PID_BRANCH", nullable = false)
    private Long pidBranch;

    @Column(name = "PID_PRODUCT", nullable = false)
    private Long pidProduct;
}
