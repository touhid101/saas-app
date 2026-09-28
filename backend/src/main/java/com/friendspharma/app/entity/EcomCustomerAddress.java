package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * A delivery address of a global customer — {@code PHARMA.ECOM_CUSTOMER_ADDRESS} (V100).
 * Many per customer, at most one default among the live ones (partial unique index).
 */
@Entity
@Table(name = "ECOM_CUSTOMER_ADDRESS", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EcomCustomerAddress extends BaseEntity {

    @Id
    @Column(name = "PID_ADDRESS")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_ecom_customer_address")
    @SequenceGenerator(name = "seq_ecom_customer_address", sequenceName = "SEQ_ECOM_CUSTOMER_ADDRESS", allocationSize = 1)
    private Long pidAddress;

    @Column(name = "PID_ECOM_CUSTOMER", nullable = false)
    private Long pidEcomCustomer;

    @Column(name = "LABEL", length = 40)
    private String label;

    @Column(name = "RECIPIENT_NAME", length = 150)
    private String recipientName;

    @Column(name = "PHONE", length = 20)
    private String phone;

    @Column(name = "LINE1", length = 200, nullable = false)
    private String line1;

    @Column(name = "LINE2", length = 200)
    private String line2;

    @Column(name = "AREA", length = 100)
    private String area;

    @Column(name = "CITY", length = 100)
    private String city;

    @Column(name = "POSTCODE", length = 20)
    private String postcode;

    @Column(name = "LATITUDE", precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "LONGITUDE", precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(name = "IS_DEFAULT", length = 1, nullable = false)
    @Builder.Default
    private String isDefault = "N";
}
