package com.friendspharma.app.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

public final class MasterDataRequests {
    private MasterDataRequests() {}

    @Data public static class OrganizationRequest {
        @NotBlank(message = "Organization name is required") private String name;
        private String shortName; private String email; private String phone;
        private String address; private String status;
    }

    @Data public static class CompanyRequest {
        @NotNull(message = "Organization is required") private Long orgId;
        @NotBlank(message = "Company name is required") private String name;
        private String shortName; private String email; private String phone;
        private String address; private String status;
    }

    @Data public static class BranchRequest {
        @NotNull private Long orgId;
        @NotNull private Long companyId;
        @NotBlank(message = "Branch name is required") private String name;
        private String address; private String email; private String phone;
        private String status; private Long regionId;
        /* [V103] Online store settings — optional; absent means "leave as is". */
        private String ecomEnabled; private java.math.BigDecimal latitude; private java.math.BigDecimal longitude;
        /* [V112] RETAIL / WHOLESALE / BOTH — who this branch may sell to online. */
        private String branchTradeType;
        private java.math.BigDecimal ecomDeliveryRadiusKm; private java.math.BigDecimal ecomMinOrderAmount;
        private String ecomOpeningHours; private String ecomDescription;
        /* [V108] What this pharmacy charges to deliver an online order. */
        private String ecomDeliveryFeeType;
        private java.math.BigDecimal ecomDeliveryFlatFee;
        private java.math.BigDecimal ecomDeliveryFeePerKm;
        private java.math.BigDecimal ecomDeliveryFreeAbove;
        /* [V114] The licence check behind the storefront's Verified badge. */
        private String branchVerified;
        private String drugLicenseNo;
    }

    @Data public static class CategoryRequest {
        @NotBlank(message = "Category name is required") private String name;
        private String status;
    }

    @Data public static class SubCategoryRequest {
        @NotBlank(message = "Sub-category name is required") private String name;
        @NotNull(message = "Category is required") private Long categoryId;
        private String status;
    }

    @Data public static class UnitRequest {
        @NotBlank(message = "Unit name is required") private String name;
        private String status;
    }

    @Data public static class GenericRequest {
        @NotBlank(message = "Generic name is required") private String name;
        private String description; private Long companyId; private String status;
    }

    @Data public static class ProductCompanyRequest {
        @NotBlank(message = "Company name is required") private String name;
        private String mobileNo; private String email; private String address; private String status;
    }

    @Data public static class LeafRequest {
        @NotBlank(message = "Leaf type is required") private String type;
        @NotNull(message = "PCS per leaf is required")
        @Min(value = 1, message = "PCS per leaf must be > 0") private Integer noOfPcs;
        private String status;
    }

    @Data public static class BoxRequest {
        @NotNull(message = "Leaf reference is required") private Long leafId;
        @NotBlank(message = "Box title is required") private String title;
        @NotNull(message = "Leaves per box is required")
        @Min(value = 1, message = "Leaves per box must be > 0") private Integer noOfLeaf;
        private String status;
    }

    // ProductRequest removed — superseded by dto/request/ProductMasterRequest.
    // The old shape accepted tpPrice from the client; Product Master calculates
    // it (and IGTA and buyPrice) server-side and has no field to bind them to.

    @Data public static class CustomerRequest {
        @NotBlank(message = "Customer name is required") private String customerName;
        private String customerCode;
        /**
         * Optional.
         *
         * <p>It was required, and at the counter that is a rule about the wrong
         * thing: a walk-in buying paracetamol has no reason to give a phone
         * number, and refusing to save the customer stops the sale rather than
         * improving the record. Still checked when one is given, so a number
         * that is present is a number that can be dialled.
         */
        @Pattern(regexp = "^$|^0[0-9]{10}$",
                 message = "Mobile number must be 11 digits starting with 0, or left blank")
        private String mobileNo;
        private String emailAddress; private String address; private String city;
        /** Ignored. The pharmacy comes from the session; kept so old clients still parse. */
        private Long branchId;
        private Long areaId; private String customerType;
        private BigDecimal openingBal; private BigDecimal creditLimit; private String status;
    }

    @Data public static class SupplierRequest {
        @NotBlank(message = "Supplier name is required") private String supplierName;
        /** Ignored. The pharmacy comes from the session; kept so old clients still parse. */
        private Long branchId;
        private Long companyId; private String mobileNo; private String emailAddress;
        private String address; private String responsiblePerson;
        private BigDecimal openingBal; private BigDecimal creditLimit; private String status;
    }

    @Data public static class DepartmentRequest {
        @NotBlank(message = "Department name is required") private String name; private String status;
    }

    @Data public static class DesignationRequest {
        @NotBlank(message = "Designation name is required") private String name;
        private String description; private String status;
    }

    /* ── Geography gaps ──────────────────────────────────────────────── */

    @Data public static class DivisionRequest {
        @NotBlank(message = "Division name is required") private String divisionName;
        private String divisionBn;
        @NotNull(message = "Region is required") private Long pidRegion;
    }

    @Data public static class DistrictRequest {
        @NotBlank(message = "District name is required") private String districtName;
        private String districtBn;
        @NotNull(message = "Division is required") private Long divisionId;
    }

    @Data public static class ThanaRequest {
        @NotBlank(message = "Thana name is required") private String thanaName;
        private String thanaBn;
        @NotNull(message = "District is required") private Long districtId;
    }

    @Data public static class AreaRequest {
        @NotBlank(message = "Area name is required") private String areaName;
        @NotNull(message = "Branch is required") private Long branchId;
        private Long thanaId;
        private Long employeeId;
        private String status;
    }

    /* ── GAP-5: Leaf/Box update requests ────────────────────────────── */

    @Data public static class LeafUpdateRequest {
        private String type;
        @Min(value = 1, message = "PCS per leaf must be > 0") private Integer noOfPcs;
        private String status;
    }

    @Data public static class BoxUpdateRequest {
        private Long leafId;
        private String title;
        @Min(value = 1, message = "Leaves per box must be > 0") private Integer noOfLeaf;
        private String status;
    }

    /* ── GAP-6: Department/Designation update requests ──────────────── */

    @Data public static class DepartmentUpdateRequest {
        private String name;
        private String status;
    }

    @Data public static class DesignationUpdateRequest {
        private String name;
        private String description;
        private String status;
    }

    @Data public static class EmployeeRequest {
        @NotBlank(message = "Employee ID is required") private String empId;
        @NotBlank(message = "Employee name is required") private String empName;
        private String email; private String phone;
        /** Ignored. The pharmacy comes from the session; kept so old clients still parse. */
        private Long branchId;
        private Long departmentId; private Long designationId;
        private String gender; private String address; private String vehicleNo; private String status;

        /**
         * DELIVERY_MAN, SALES_REP, CASHIER, PHARMACIST, MANAGER, ACCOUNTANT or
         * OTHER. Validated in the service rather than by a pattern here, so the
         * allowed set lives in one place beside the CHECK constraint it mirrors.
         *
         * <p>Blank is accepted and means OTHER, so existing clients that never
         * sent a type keep working instead of failing validation.
         */
        private String empType;
    }
}
