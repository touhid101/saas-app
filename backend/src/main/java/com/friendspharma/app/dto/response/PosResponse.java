package com.friendspharma.app.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class PosResponse {

    @Data @Builder
    public static class CartItemDto {
        private Long pidCartItem;
        private Long pidProduct;
        private String productName;
        private String productImageUrl;
        private String salesUnit;
        private BigDecimal qty;
        private BigDecimal unitPrice;
        private BigDecimal discountPct;
        private BigDecimal lineTotal;
        private LocalDate expiryDate;
        private String batchNo;
    }

    @Data @Builder
    public static class CartDto {
        private Long pidCart;
        private Long pidOrg;
        private Long pidBranch;
        private Long pidCustomer;
        private String customerName;
        private String stage;
        private String holdNote;
        private String cartRef;
        private BigDecimal subTotal;
        private BigDecimal discountAmount;
        private BigDecimal additionalDiscountPct;
        private BigDecimal grandTotal;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private List<CartItemDto> items;
    }

    @Data @Builder
    public static class BarcodeProductDto {
        private Long pidProduct;
        private String productName;
        private String productCode;
        private String barcodeNo;
        private String productImageUrl;
        private String genericName;
        private String company;
        private String category;
        private BigDecimal mrpBox;
        private BigDecimal mrpStrip;
        private BigDecimal mrpPcs;
        private Integer pcsPerStrip;
        private Integer stripPerBox;
        private BigDecimal stockQty;
        private LocalDate nearestExpiry;
    }

    @Data @Builder
    public static class ReceiptDto {
        private String invoiceNo;
        private LocalDateTime invoiceDate;
        private String customerName;
        private String customerAddress;
        private String branchName;
        private String branchAddress;
        private List<ReceiptLineDto> lines;
        private BigDecimal subTotal;
        private BigDecimal discountAmount;
        private BigDecimal grandTotal;
        private String paymentType;
        private String servedBy;
    }

    @Data @Builder
    public static class ReceiptLineDto {
        private String productName;
        private String salesUnit;
        private BigDecimal qty;
        private BigDecimal unitPrice;
        private BigDecimal discountPct;
        private BigDecimal lineTotal;
        private LocalDate expiryDate;
    }
}
