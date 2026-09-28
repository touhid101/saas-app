package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * POS_TRANSACTION_MST — single table for Purchase (PU), Sales (SL),
 * Sales Return (SR), Purchase Return (PR) transactions.
 */
@Entity
@Table(name = "pos_transaction_mst")
@NoArgsConstructor @AllArgsConstructor @Builder
public class PosTransactionMst extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pid_tran_mst")
    private Long pidTranMst;

    @Column(name = "pid_org",    nullable = false) private Long pidOrg;
    @Column(name = "pid_company")                  private Long pidCompany;
    @Column(name = "pid_branch")                   private Long pidBranch;
    @Column(name = "pid_sup_cust")                 private Long pidSupCust;

    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;

    /** PU=Purchase, SL=Sales, SR=Sales Return, PR=Purchase Return */
    @Column(name = "transaction_type", length = 2, nullable = false)
    private String transactionType;

    @Column(name = "invoice_no",       length = 50)  private String invoiceNo;
    @Column(name = "challan_no",       length = 50)  private String challanNo;
    @Column(name = "total_amount",     precision = 14, scale = 4) private BigDecimal totalAmount;
    @Column(name = "invoice_dis_pct",  precision = 8,  scale = 4) private BigDecimal invoiceDisPct;
    @Column(name = "invoice_dis_amt",  precision = 14, scale = 4) private BigDecimal invoiceDisAmt;
    @Column(name = "total_discount",   precision = 14, scale = 4) private BigDecimal totalDiscount;
    @Column(name = "total_tax",        precision = 14, scale = 4) private BigDecimal totalTax;
    @Column(name = "service_charge",   precision = 14, scale = 4) private BigDecimal serviceCharge;
    /**
     * [V68] Supplier incentive taken off this invoice: reduces the payable and
     * the cost of the goods.
     *
     * <p>Initialised to zero rather than left null. The column is NOT NULL with
     * a default, but a default only applies to an INSERT that omits the column —
     * and this row is inserted once to obtain its id before the totals are
     * calculated, so Hibernate sends an explicit null and the insert is refused
     * with "Cashback amount is required". Every invoice has a cashback; on
     * almost all of them it is nothing.
     */
    @Column(name = "cashback_amount",  precision = 14, scale = 4)
    private BigDecimal cashbackAmount = BigDecimal.ZERO;
    @Column(name = "amount_tobe_paid", precision = 14, scale = 4) private BigDecimal amountTobePaid;
    @Column(name = "given_amount",     precision = 14, scale = 4) private BigDecimal givenAmount;
    @Column(name = "payment_type",     length = 20)  private String paymentType;
    @Column(name = "remarks",          length = 100) private String remarks;
    @Column(name = "PURCHASE_TYPE",    length = 5)   private String purchaseType;

    /** [V5] System-generated 6-digit purchase batch reference (SEQ_BATCH_NO). */
    @Column(name = "BATCH_NO",         length = 20)  private String batchNo;

    /** [V5] PHARMA or NON-PHARMA — classifies the whole invoice. */
    @Column(name = "PRODUCT_TYPE",     length = 20)  private String productType;
    @Column(name = "FROM_SOURCE",      length = 15)  private String fromSource;

    // Delivery
    @Column(name = "DELIVERY_ADDRESS",  length = 200) private String deliveryAddress;
    @Column(name = "DELIVERY_MAN")                    private Long   deliveryMan;
    @Column(name = "DELIVERY_MAN_NAME", length = 100) private String deliveryManName;
    @Column(name = "DELIVERY_CHARGE",   precision = 12, scale = 4) private BigDecimal deliveryCharge;
    @Column(name = "AREA_NAME",         length = 50)  private String areaName;

    // Merge
    @Column(name = "MERGE_PID_TRAN_MST") private Long   mergePidTranMst;
    @Column(name = "IS_MERGE_INVOICE",   length = 5)   private String isMergeInvoice;

    // Employee tracking
    @Column(name = "PID_EMPLOYEE")                    private Long          pidEmployee;
    @Column(name = "RECEIVED_BY",  length = 50)       private String        receivedBy;
    @Column(name = "RECEIVED_DATE")                   private LocalDateTime receivedDate;
    @Column(name = "DELIVERED_BY", length = 50)       private String        deliveredBy;
    @Column(name = "DELIVERED_DATE")                  private LocalDateTime deliveredDate;
    @Column(name = "PAID_BY",      length = 30)       private String        paidBy;
    @Column(name = "PAID_DATE")                       private LocalDateTime paidDate;
    // Cancellation [V106] — stock reversed, journals reversed, STAGE=Cancelled, STATUS=N
    @Column(name = "CANCELLED_BY",   length = 50)      private String        cancelledBy;
    @Column(name = "CANCELLED_DATE")                   private LocalDateTime cancelledDate;
    @Column(name = "CANCEL_REASON",  length = 300)     private String        cancelReason;

    @Column(name = "PID_TRAN_MST_RETURN")             private Long          pidTranMstReturn;

    // New workflow fields
    @Column(name = "STAGE",              length = 30)  private String stage;
    @Column(name = "REQUISITION_SOURCE", length = 10)  private String requisitionSource;
    @Column(name = "PAYMENT_STATUS",     length = 10)  private String paymentStatus;

    // Detail lines — fetched separately, not a mapped association to avoid N+1
    @Transient @Builder.Default
    private List<PosTransactionDtl> details = new ArrayList<>();

    // ── Stable aliases used by the new service layer ──────────────────────
    // Plain Java methods — no JPA annotation needed.

    /** Alias: pidTranMst → pidTransaction */
    public Long   getPidTransaction()    { return pidTranMst; }

    /** Alias: pidSupCust → pidCustomer */
    public Long   getPidCustomer()       { return pidSupCust; }
    public void   setPidCustomer(Long v) { this.pidSupCust = v; }

    /** Alias: amountTobePaid → grandTotal */
    public BigDecimal getGrandTotal()          { return amountTobePaid; }
    public void       setGrandTotal(BigDecimal v) { this.amountTobePaid = v; }

    /** Alias: totalAmount → subTotal */
    public BigDecimal getSubTotal()            { return totalAmount; }
    public void       setSubTotal(BigDecimal v)   { this.totalAmount = v; }

    /** Alias: totalDiscount → discountAmount */
    public BigDecimal getDiscountAmount()           { return totalDiscount; }
    public void       setDiscountAmount(BigDecimal v) { this.totalDiscount = v; }

    /** Alias: transactionType → tranType */
    public String getTranType()              { return transactionType; }

    /** Alias: transactionDate (as LocalDateTime) → invoiceDate */
    public LocalDateTime getInvoiceDate() {
        return transactionDate != null ? transactionDate.atStartOfDay() : null;
    }

    /** Alias: isMergeInvoice → mergeStatus */
    public String getMergeStatus()           { return isMergeInvoice; }
    public void   setMergeStatus(String v)   { this.isMergeInvoice = v; }

    /** Customer name placeholder — populated by service layer via join */
    public String getCustomerName()          { return null; }

    // ── Explicit Getters and Setters ──────────────────────────────────────
    public Long getPidTranMst() { return pidTranMst; }
    public void setPidTranMst(Long pidTranMst) { this.pidTranMst = pidTranMst; }

    public Long getPidOrg() { return pidOrg; }
    public void setPidOrg(Long pidOrg) { this.pidOrg = pidOrg; }

    public Long getPidCompany() { return pidCompany; }
    public void setPidCompany(Long pidCompany) { this.pidCompany = pidCompany; }

    public Long getPidBranch() { return pidBranch; }
    public void setPidBranch(Long pidBranch) { this.pidBranch = pidBranch; }

    public Long getPidSupCust() { return pidSupCust; }
    public void setPidSupCust(Long pidSupCust) { this.pidSupCust = pidSupCust; }

    public LocalDate getTransactionDate() { return transactionDate; }
    public void setTransactionDate(LocalDate transactionDate) { this.transactionDate = transactionDate; }

    public String getTransactionType() { return transactionType; }
    public void setTransactionType(String transactionType) { this.transactionType = transactionType; }

    public String getInvoiceNo() { return invoiceNo; }
    /**
     * The slice of {@code givenAmount} that settles no part of this bill.
     *
     * <p>Typically the invoice discount handed over with the payment on the
     * understanding the supplier returns it. Kept here rather than inferred
     * from an overpayment, because "paid more than the bill" is far more often
     * a keying error than a deliberate rebate, and the two must not be guessed
     * apart after the fact.
     */
    // Zero rather than null: the header row is inserted to obtain its id
    // before the totals are worked out, so the column would otherwise be null
    // on the first write and the NOT NULL constraint would reject it.
    @Column(name = "ADVANCE_AMOUNT", nullable = false)
    private java.math.BigDecimal advanceAmount = java.math.BigDecimal.ZERO;

    public java.math.BigDecimal getAdvanceAmount() { return advanceAmount; }
    public void setAdvanceAmount(java.math.BigDecimal v) { this.advanceAmount = v; }

    public void setInvoiceNo(String invoiceNo) { this.invoiceNo = invoiceNo; }

    public String getChallanNo() { return challanNo; }
    public void setChallanNo(String challanNo) { this.challanNo = challanNo; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public BigDecimal getInvoiceDisPct() { return invoiceDisPct; }
    public void setInvoiceDisPct(BigDecimal invoiceDisPct) { this.invoiceDisPct = invoiceDisPct; }

    public BigDecimal getInvoiceDisAmt() { return invoiceDisAmt; }
    public void setInvoiceDisAmt(BigDecimal invoiceDisAmt) { this.invoiceDisAmt = invoiceDisAmt; }

    public BigDecimal getTotalDiscount() { return totalDiscount; }
    public void setTotalDiscount(BigDecimal totalDiscount) { this.totalDiscount = totalDiscount; }

    public BigDecimal getTotalTax() { return totalTax; }
    public void setTotalTax(BigDecimal totalTax) { this.totalTax = totalTax; }

    public BigDecimal getCashbackAmount() { return cashbackAmount; }
    public void setCashbackAmount(BigDecimal cashbackAmount) { this.cashbackAmount = cashbackAmount; }

    public BigDecimal getServiceCharge() { return serviceCharge; }
    public void setServiceCharge(BigDecimal serviceCharge) { this.serviceCharge = serviceCharge; }

    public BigDecimal getAmountTobePaid() { return amountTobePaid; }
    public void setAmountTobePaid(BigDecimal amountTobePaid) { this.amountTobePaid = amountTobePaid; }

    public BigDecimal getGivenAmount() { return givenAmount; }
    public void setGivenAmount(BigDecimal givenAmount) { this.givenAmount = givenAmount; }

    public String getPaymentType() { return paymentType; }
    public void setPaymentType(String paymentType) { this.paymentType = paymentType; }

    public String getRemarks() { return remarks; }
    public void setRemarks(String remarks) { this.remarks = remarks; }

    public String getPurchaseType() { return purchaseType; }
    public void setPurchaseType(String purchaseType) { this.purchaseType = purchaseType; }

    /* [V5] Purchase module — this class hand-writes its accessors rather than
       using Lombok @Getter/@Setter, so new fields need theirs adding here too. */
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }

    public String getProductType() { return productType; }
    public void setProductType(String productType) { this.productType = productType; }

    public String getFromSource() { return fromSource; }
    public void setFromSource(String fromSource) { this.fromSource = fromSource; }

    public String getDeliveryAddress() { return deliveryAddress; }
    public void setDeliveryAddress(String deliveryAddress) { this.deliveryAddress = deliveryAddress; }

    public Long getDeliveryMan() { return deliveryMan; }
    public void setDeliveryMan(Long deliveryMan) { this.deliveryMan = deliveryMan; }

    public String getDeliveryManName() { return deliveryManName; }
    public void setDeliveryManName(String deliveryManName) { this.deliveryManName = deliveryManName; }

    public BigDecimal getDeliveryCharge() { return deliveryCharge; }
    public void setDeliveryCharge(BigDecimal deliveryCharge) { this.deliveryCharge = deliveryCharge; }

    public String getAreaName() { return areaName; }
    public void setAreaName(String areaName) { this.areaName = areaName; }

    public Long getMergePidTranMst() { return mergePidTranMst; }
    public void setMergePidTranMst(Long mergePidTranMst) { this.mergePidTranMst = mergePidTranMst; }

    public String getIsMergeInvoice() { return isMergeInvoice; }
    public void setIsMergeInvoice(String isMergeInvoice) { this.isMergeInvoice = isMergeInvoice; }

    public Long getPidEmployee() { return pidEmployee; }
    public void setPidEmployee(Long pidEmployee) { this.pidEmployee = pidEmployee; }

    public String getReceivedBy() { return receivedBy; }
    public void setReceivedBy(String receivedBy) { this.receivedBy = receivedBy; }

    public LocalDateTime getReceivedDate() { return receivedDate; }
    public void setReceivedDate(LocalDateTime receivedDate) { this.receivedDate = receivedDate; }

    public String getDeliveredBy() { return deliveredBy; }
    public void setDeliveredBy(String deliveredBy) { this.deliveredBy = deliveredBy; }

    public LocalDateTime getDeliveredDate() { return deliveredDate; }
    public void setDeliveredDate(LocalDateTime deliveredDate) { this.deliveredDate = deliveredDate; }

    public String getPaidBy() { return paidBy; }
    public void setPaidBy(String paidBy) { this.paidBy = paidBy; }

    public LocalDateTime getPaidDate() { return paidDate; }
    public void setPaidDate(LocalDateTime paidDate) { this.paidDate = paidDate; }

    public String getCancelledBy() { return cancelledBy; }
    public void setCancelledBy(String cancelledBy) { this.cancelledBy = cancelledBy; }

    public LocalDateTime getCancelledDate() { return cancelledDate; }
    public void setCancelledDate(LocalDateTime cancelledDate) { this.cancelledDate = cancelledDate; }

    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String cancelReason) { this.cancelReason = cancelReason; }

    public Long getPidTranMstReturn() { return pidTranMstReturn; }
    public void setPidTranMstReturn(Long pidTranMstReturn) { this.pidTranMstReturn = pidTranMstReturn; }

    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }

    public String getRequisitionSource() { return requisitionSource; }
    public void setRequisitionSource(String requisitionSource) { this.requisitionSource = requisitionSource; }

    public String getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(String paymentStatus) { this.paymentStatus = paymentStatus; }

    public List<PosTransactionDtl> getDetails() { return details; }
    public void setDetails(List<PosTransactionDtl> details) { this.details = details; }
}
