package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Groups two or more unmerged sales invoices (same customer) into a single merged invoice.
 * Rules enforced in service:
 *   - minimum 2 source invoices
 *   - all source invoices must be UNMERGED
 *   - all source invoices must belong to the same customer
 * STATUS: ACTIVE | CANCELLED
 */
@Entity
@Table(name = "POS_INVOICE_MERGE",
        indexes = {
            @Index(name = "IDX_MERGE_CUST",  columnList = "PID_CUSTOMER"),
            @Index(name = "IDX_MERGE_DATE",  columnList = "MERGE_DATE"),
            @Index(name = "IDX_MERGE_STATUS",columnList = "STATUS")
        })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PosInvoiceMerge extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_INV_MERGE")
    @SequenceGenerator(name = "SQ_INV_MERGE", sequenceName = "SQ_INV_MERGE", allocationSize = 1)
    @Column(name = "PID_MERGE")
    private Long pidMerge;

    @Column(name = "MERGE_NO", length = 30, unique = true)
    private String mergeNo;

    @Column(name = "PID_ORG", nullable = false)
    private Long pidOrg;

    @Column(name = "PID_BRANCH", nullable = false)
    private Long pidBranch;

    @Column(name = "PID_CUSTOMER", nullable = false)
    private Long pidCustomer;

    @Column(name = "CUSTOMER_NAME", length = 300)
    private String customerName;

    @Column(name = "MERGE_DATE", nullable = false)
    private LocalDate mergeDate;

    /** Sum of all source invoice grand totals */
    @Column(name = "TOTAL_AMOUNT", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalAmount;

    /** ACTIVE | CANCELLED */
    @Column(name = "STATUS", nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "REMARKS", length = 500)
    private String remarks;

    @Column(name = "CANCELLED_REASON", length = 500)
    private String cancelledReason;

    @OneToMany(mappedBy = "merge", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PosInvoiceMergeDtl> details = new ArrayList<>();
}
