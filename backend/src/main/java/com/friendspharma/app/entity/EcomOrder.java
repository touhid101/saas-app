package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A marketplace order — {@code PHARMA.ECOM_ORDER} (V105).
 *
 * <p>The customer-facing half of an order. The pharmacy's half is the invoice
 * it created, {@link PosTransactionMst}, linked by {@link #pidTranMst}: that is
 * where the stock movement, the batch detail and the ledger posting live, and
 * it is what Sales Activity, Delivery, Collection and every report read.
 *
 * <p>The snapshot columns are the promise made at the moment of ordering.
 * Prices move and a customer's tier can change; neither may rewrite what was
 * charged.
 */
@Entity
@Table(name = "ECOM_ORDER", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EcomOrder extends BaseEntity {

    public static final String NEW        = "NEW";
    public static final String CONFIRMED  = "CONFIRMED";
    public static final String PREPARING  = "PREPARING";
    public static final String READY      = "READY";
    public static final String ON_THE_WAY = "ON_THE_WAY";
    public static final String DELIVERED  = "DELIVERED";
    public static final String CANCELLED  = "CANCELLED";
    public static final String RETURNED   = "RETURNED";

    @Id
    @Column(name = "PID_ECOM_ORDER")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_ecom_order")
    @SequenceGenerator(name = "seq_ecom_order", sequenceName = "SEQ_ECOM_ORDER", allocationSize = 1)
    private Long pidEcomOrder;

    @Column(name = "ORDER_NO", length = 30, nullable = false)
    private String orderNo;

    @Column(name = "PID_ECOM_CUSTOMER", nullable = false)
    private Long pidEcomCustomer;

    @Column(name = "PID_BRANCH", nullable = false)
    private Long pidBranch;

    @Column(name = "PID_ORG")
    private Long pidOrg;

    /** The pharmacy's invoice — {@code POS_TRANSACTION_MST}. One order, one invoice. */
    @Column(name = "PID_TRAN_MST")
    private Long pidTranMst;

    /** The shadow {@link PosCustomerInfo} row that invoice points at. */
    @Column(name = "PID_CUSTOMER")
    private Long pidCustomer;

    @Column(name = "ECOM_STATUS", length = 20, nullable = false)
    @Builder.Default
    private String ecomStatus = NEW;

    @Column(name = "CUSTOMER_NAME_SNAPSHOT", length = 150) private String customerNameSnapshot;
    @Column(name = "PHONE_SNAPSHOT", length = 20)          private String phoneSnapshot;
    @Column(name = "CUSTOMER_TYPE_SNAPSHOT", length = 20)  private String customerTypeSnapshot;

    @Column(name = "DELIVERY_LABEL", length = 40)      private String deliveryLabel;
    @Column(name = "DELIVERY_RECIPIENT", length = 150) private String deliveryRecipient;
    @Column(name = "DELIVERY_PHONE", length = 20)      private String deliveryPhone;
    @Column(name = "DELIVERY_LINE1", length = 200)     private String deliveryLine1;
    @Column(name = "DELIVERY_LINE2", length = 200)     private String deliveryLine2;
    @Column(name = "DELIVERY_AREA", length = 100)      private String deliveryArea;
    @Column(name = "DELIVERY_CITY", length = 100)      private String deliveryCity;
    @Column(name = "DELIVERY_POSTCODE", length = 20)   private String deliveryPostcode;
    @Column(name = "DELIVERY_LATITUDE", precision = 9, scale = 6)  private BigDecimal deliveryLatitude;
    @Column(name = "DELIVERY_LONGITUDE", precision = 9, scale = 6) private BigDecimal deliveryLongitude;

    @Column(name = "PAYMENT_METHOD", length = 20, nullable = false)
    @Builder.Default
    private String paymentMethod = "COD";

    @Column(name = "PAYMENT_REF", length = 100) private String paymentRef;

    @Column(name = "SUBTOTAL", precision = 14, scale = 2, nullable = false)        @Builder.Default private BigDecimal subtotal = BigDecimal.ZERO;
    @Column(name = "DISCOUNT_AMOUNT", precision = 14, scale = 2, nullable = false) @Builder.Default private BigDecimal discountAmount = BigDecimal.ZERO;
    @Column(name = "COUPON_CODE", length = 40) private String couponCode;
    @Column(name = "COUPON_AMOUNT", precision = 14, scale = 2, nullable = false)   @Builder.Default private BigDecimal couponAmount = BigDecimal.ZERO;
    @Column(name = "OFFER_AMOUNT", precision = 14, scale = 2, nullable = false)    @Builder.Default private BigDecimal offerAmount = BigDecimal.ZERO;
    @Column(name = "POINTS_REDEEMED", precision = 14, scale = 2, nullable = false) @Builder.Default private BigDecimal pointsRedeemed = BigDecimal.ZERO;
    @Column(name = "POINTS_VALUE", precision = 14, scale = 2, nullable = false)    @Builder.Default private BigDecimal pointsValue = BigDecimal.ZERO;
    @Column(name = "DELIVERY_CHARGE", precision = 14, scale = 2, nullable = false) @Builder.Default private BigDecimal deliveryCharge = BigDecimal.ZERO;
    @Column(name = "TAX_AMOUNT", precision = 14, scale = 2, nullable = false)      @Builder.Default private BigDecimal taxAmount = BigDecimal.ZERO;
    @Column(name = "GRAND_TOTAL", precision = 14, scale = 2, nullable = false)     @Builder.Default private BigDecimal grandTotal = BigDecimal.ZERO;

    @Column(name = "CUSTOMER_NOTE", length = 300) private String customerNote;

    @Column(name = "PLACED_AT", nullable = false)
    @Builder.Default
    private LocalDateTime placedAt = LocalDateTime.now();

    @Column(name = "CONFIRMED_AT") private LocalDateTime confirmedAt;
    @Column(name = "DELIVERED_AT") private LocalDateTime deliveredAt;
    @Column(name = "CANCELLED_AT") private LocalDateTime cancelledAt;
    @Column(name = "CANCEL_REASON", length = 300) private String cancelReason;
    @Column(name = "CANCELLED_BY_KIND", length = 10) private String cancelledByKind;
}
