package com.friendspharma.app.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * All master-data response DTOs in one file for brevity.
 * Each controller uses the appropriate nested class.
 */
public final class MasterDataResponses {

    private MasterDataResponses() {}

    @Getter @Builder
    public static class OrganizationResponse {
        private Long id; private String name; private String shortName;
        private String email; private String phone; private String address; private String status;
    }

    @Getter @Builder
    public static class CompanyResponse {
        private Long id; private Long orgId; private String name;
        private String shortName; private String email; private String phone;
        private String address; private String status;
    }

    @Getter @Builder
    public static class BranchResponse {
        private Long id; private Long orgId; private Long companyId;
        private String name; private String address; private String email;
        private String phone; private String status;
        private Long regionId; private String regionName;
        private String reportHeader;
        /** Shown in this pharmacy's own sidebar and browser tab. */
        private String logoUrl;
        /* [V103] Online store settings. */
        private String ecomEnabled; private java.math.BigDecimal latitude; private java.math.BigDecimal longitude;
        /* [V112] RETAIL / WHOLESALE / BOTH — who this branch may sell to online. */
        private String branchTradeType;
        private java.math.BigDecimal ecomDeliveryRadiusKm; private java.math.BigDecimal ecomMinOrderAmount;
        private String ecomOpeningHours; private String ecomDescription;
        /* [V108] The delivery charge policy. No type means free delivery. */
        private String ecomDeliveryFeeType;
        private java.math.BigDecimal ecomDeliveryFlatFee;
        private java.math.BigDecimal ecomDeliveryFeePerKm;
        private java.math.BigDecimal ecomDeliveryFreeAbove;
        /* [V114] Whether somebody has actually seen this pharmacy's licence. */
        private String branchVerified;
        private String drugLicenseNo;
        private String verifiedBy;
        private java.time.LocalDateTime verifiedAt;
    }

    @Getter @Builder
    public static class RegionResponse {
        private Long id; private String name; private String status;
    }

    @Getter @Builder
    public static class CategoryResponse {
        private Long id; private String name; private Long orgId; private String status;
    }

    @Getter @Builder
    public static class SubCategoryResponse {
        private Long id; private String name; private Long categoryId;
        private String categoryName; private Long orgId; private String status;
    }

    @Getter @Builder
    public static class UnitResponse {
        private Long id; private String name; private Long orgId; private String status;
    }

    @Getter @Builder
    public static class GenericResponse {
        private Long id; private String name; private String description;
        private Long companyId; private String companyName; private Long orgId; private String status;
    }

    @Getter @Builder
    public static class ProductCompanyResponse {
        private Long id; private String name; private Long orgId;
        private String mobileNo; private String email; private String address; private String status;
    }

    @Getter @Builder
    public static class LeafResponse {
        private Long id; private String type; private Integer noOfPcs;
        private Long orgId; private String status;
    }

    @Getter @Builder
    public static class BoxResponse {
        private Long id; private Long leafId; private String leafType;
        private String title; private Integer noOfLeaf;
        private Integer pcsPerBox; private Long orgId; private String status;
    }

    // ProductResponse removed — superseded by dto/response/ProductMasterResponse
    // (detail) and ProductListItemResponse (grid rows).

    @Getter @Builder
    public static class CustomerResponse {
        private Long id; private String code; private String name; private String type;
        private Long orgId; private Long branchId; private String branchName;
        private Long areaId; private String areaName;
        private String mobileNo; private String email; private String address;
        private BigDecimal openingBal; private BigDecimal creditLimit; private String status;
    }

    @Getter @Builder
    public static class SupplierResponse {
        private Long id; private String name; private Long orgId; private Long branchId;
        private String branchName; private Long companyId; private String companyName;
        private String mobileNo; private String email; private String address;
        private BigDecimal openingBal; private BigDecimal creditLimit; private String status;
    }

    @Getter @Builder
    public static class DepartmentResponse {
        private Long id; private String name; private Long orgId; private String status;
    }

    @Getter @Builder
    public static class DesignationResponse {
        private Long id; private String name; private String description;
        private Long orgId; private String status;
    }

    @Getter @Builder
    public static class EmployeeResponse {
        private Long id; private String empId; private String empName; private String email;
        private String phone; private Long orgId; private Long branchId;
        private Long departmentId; private String departmentName;
        private Long designationId; private String designationName;
        private String vehicleNo; private String status;

        /**
         * What this employee does — see {@link com.friendspharma.app.entity.EmployeeType}.
         *
         * <p>{@code DELIVERY_MAN} is the only value the application branches on:
         * it gates who may be assigned an invoice to deliver.
         */
        private String empType;
        /** The same value spelled for display, e.g. "Delivery Man". */
        private String empTypeLabel;
        /**
         * True when this employee may be handed an invoice — an active
         * DELIVERY_MAN. Sent so the UI need not re-derive the rule the server
         * enforces on assignment.
         */
        private Boolean canDeliver;

        /**
         * Public URL of the photograph, or null.
         *
         * <p>Sent with {@link #initials} so a client never has to choose between
         * an empty src and a broken-image icon: it has something to draw before
         * the first image request is made.
         */
        private String imagePath;
        /** Two letters to draw when there is no photograph. */
        private String initials;
    }

    @Getter @Builder
    public static class AreaResponse {
        private Long id; private String name; private Long branchId;
        private String branchName; private String status;
    }

    /* ── Geography gaps ────────────────────────────────────────────── */

    @Getter @Builder
    public static class DivisionResponse {
        private Long id;          // divisionId
        private String name;      // divisionName
        private String nameBn;    // divisionBn
        private Long regionId;    // pidRegion
        private String regionName;
    }

    @Getter @Builder
    public static class DistrictResponse {
        private Long id;          // districtId
        private String name;      // districtName
        private String nameBn;    // districtBn
        private Long divisionId;
        private String divisionName;
    }

    @Getter @Builder
    public static class ThanaResponse {
        private Long id;          // thanaId
        private String name;      // thanaName
        private String nameBn;    // thanaBn
        private Long districtId;
        private String districtName;
    }
}
