package com.friendspharma.app.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * The pharmacy-side shadow of a global customer — {@code PHARMA.ECOM_CUSTOMER_BRANCH_LINK} (V100).
 *
 * <p>One row per (customer, branch): the {@link PosCustomerInfo} row that the
 * pharmacy's invoices, returns and reports see. The branch never owns the
 * identity; deleting a link would not delete the customer.
 */
@Entity
@Table(name = "ECOM_CUSTOMER_BRANCH_LINK", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EcomCustomerBranchLink {

    public static final String MODE_CREATED = "CREATED";
    public static final String MODE_LINKED  = "LINKED";

    @Id
    @Column(name = "PID_LINK")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_ecom_customer_branch_link")
    @SequenceGenerator(name = "seq_ecom_customer_branch_link", sequenceName = "SEQ_ECOM_CUSTOMER_BRANCH_LINK", allocationSize = 1)
    private Long pidLink;

    @Column(name = "PID_ECOM_CUSTOMER", nullable = false)
    private Long pidEcomCustomer;

    @Column(name = "PID_BRANCH", nullable = false)
    private Long pidBranch;

    @Column(name = "PID_CUSTOMER", nullable = false)
    private Long pidCustomer;

    @Column(name = "LINK_MODE", length = 10, nullable = false)
    @Builder.Default
    private String linkMode = MODE_CREATED;

    @Column(name = "LINKED_AT", nullable = false)
    @Builder.Default
    private LocalDateTime linkedAt = LocalDateTime.now();

    @Column(name = "LINKED_BY", length = 50)
    private String linkedBy;
}
