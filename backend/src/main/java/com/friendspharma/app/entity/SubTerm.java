package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * How long a subscription is sold for in one go.
 *
 * <p>The term is not the plan's billing cycle. A plan priced MONTHLY can still be
 * sold twelve months at a time; the cycle says what the price covers, the term
 * says how much of it is being bought now. Keeping them apart is what lets a
 * yearly deal exist on a monthly plan without either number lying.
 *
 * <p>Nothing points at a row here once a pharmacy is activated — the term is read
 * once, to work out an expiry date, and the date is what is stored. That is why a
 * term can be removed outright while a plan can only be taken off sale: deleting
 * a term cannot orphan a subscription, because no subscription refers to one.
 */
@Entity
@Table(name = "SUB_TERM")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SubTerm extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_SUB_TERM")
    @SequenceGenerator(name = "SQ_SUB_TERM", sequenceName = "PHARMA.SQ_SUB_TERM", allocationSize = 1)
    @Column(name = "PID_TERM")
    private Long pidTerm;

    /** The length itself, in months. This is the number activation actually uses. */
    @Column(name = "TERM_MONTHS", nullable = false)
    private Integer termMonths;

    /** What the operator sees in the dropdown — "1 month", "Half year", "Annual". */
    @Column(name = "TERM_NAME", nullable = false, length = 60)
    private String termName;

    @Column(name = "DESCRIPTION", length = 300)
    private String description;

    /**
     * A discount off the plan price for committing to this length, as a
     * percentage. Recorded so the term list is the one place a "two months free
     * on an annual" arrangement is written down, rather than being applied by
     * hand on each invoice and remembered by nobody.
     */
    @Column(name = "DISCOUNT_PCT")
    private java.math.BigDecimal discountPct;

    /** Off the list, but kept — retiring a term must not rewrite past decisions. */
    @Column(name = "IS_ACTIVE", nullable = false)
    private Integer isActive;

    @Column(name = "SORT_ORDER", nullable = false)
    private Integer sortOrder;
}
