package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.request.MasterDataRequests;
import com.friendspharma.app.dto.request.MasterDataRequests.*;
import com.friendspharma.app.dto.response.MasterDataResponses;
import com.friendspharma.app.dto.response.MasterDataResponses.*;
import com.friendspharma.app.entity.EmployeeType;
import com.friendspharma.app.security.SecurityUtils;
import com.friendspharma.app.service.interfaces.MasterDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Tag(name = "Master Data", description = "Organization hierarchy, products, customers, suppliers, HR")
public class MasterDataController {

    private final MasterDataService svc;

    /* ── Organization ──────────────────────────────────────────────── */
    @GetMapping("/api/organizations")
    public ResponseEntity<ApiResponse<List<OrganizationResponse>>> listOrgs() {
        return ResponseEntity.ok(ApiResponse.ok(svc.getAllOrganizations()));
    }

    @PostMapping("/api/organizations")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02')")
    public ResponseEntity<ApiResponse<OrganizationResponse>> createOrg(@Valid @RequestBody OrganizationRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(svc.createOrganization(req)));
    }

    @PutMapping("/api/organizations/{id}")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02')")
    public ResponseEntity<ApiResponse<OrganizationResponse>> updateOrg(@PathVariable Long id, @Valid @RequestBody OrganizationRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(svc.updateOrganization(id, req)));
    }

    /* ── Companies ─────────────────────────────────────────────────── */
    @GetMapping("/api/companies")
    public ResponseEntity<ApiResponse<List<CompanyResponse>>> listCompanies(@RequestParam(required = false) Long orgId) {
        Long oid = orgId != null ? orgId : SecurityUtils.getCurrentOrgId();
        return ResponseEntity.ok(ApiResponse.ok(svc.getCompaniesByOrg(oid)));
    }

    @PostMapping("/api/companies")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02')")
    public ResponseEntity<ApiResponse<CompanyResponse>> createCompany(@Valid @RequestBody CompanyRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(svc.createCompany(req)));
    }

    @PutMapping("/api/companies/{id}")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02')")
    public ResponseEntity<ApiResponse<CompanyResponse>> updateCompany(@PathVariable Long id, @Valid @RequestBody CompanyRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(svc.updateCompany(id, req)));
    }

    /* ── Branches ──────────────────────────────────────────────────── */
    @GetMapping("/api/branches")
    public ResponseEntity<ApiResponse<List<BranchResponse>>> listBranches(
            @RequestParam(required = false) Long orgId,
            @RequestParam(required = false) Long regionId) {
        Long oid = orgId != null ? orgId : SecurityUtils.getCurrentOrgId();
        return ResponseEntity.ok(ApiResponse.ok(svc.getBranches(oid, regionId)));
    }

    /**
     * Opening a pharmacy — the platform's act, not a pharmacy's.
     *
     * <p>This was {@code ROLE01, ROLE02}, so a Branch Administrator could create
     * branches. That is not the job: they maintain the one they are in, which is
     * what the PUT below is for. A branch created from inside a pharmacy also
     * arrives carrying that caller's organisation, which is how an estate grows
     * rows nobody on the platform decided to add.
     *
     * <p>ROLE01 alone is the whole test, because ROLE01 exists only at the
     * platform branch — {@code PermissionServiceImpl} refuses it anywhere else.
     */
    @PostMapping("/api/branches")
    @PreAuthorize("hasRole('ROLE01')")
    public ResponseEntity<ApiResponse<BranchResponse>> createBranch(@Valid @RequestBody BranchRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(svc.createBranch(req)));
    }

    @PutMapping("/api/branches/{id}")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02')")
    public ResponseEntity<ApiResponse<BranchResponse>> updateBranch(@PathVariable Long id, @Valid @RequestBody BranchRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(svc.updateBranch(id, req)));
    }


    /* The pharmacy's own mark, beside the branch it belongs to.
     *
     * No role is named here, on purpose. The gate is the Edit right on
     * Organisation & Parties in the RBAC screen, checked in the service — see
     * MasterDataServiceImpl.requireBranchEditRight. These endpoints were
     * previously on the Super Admin onboarding controller, which meant a
     * pharmacy's own administrator could not set their own logo however the
     * matrix was configured. */
    @PostMapping(path = "/api/branches/{id}/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Set a branch logo",
               description = "JPG, PNG or WEBP. Replaces any existing logo; the old file is "
                           + "removed only once the new one is safely written.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> setBranchLogo(
            @PathVariable Long id, @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(ApiResponse.ok("Logo updated.",
                Map.of("logoUrl", svc.setBranchLogo(id, file))));
    }

    @DeleteMapping("/api/branches/{id}/logo")
    @Operation(summary = "Remove a branch logo",
               description = "The sidebar and favicon fall back to the pharmacy's initial.")
    public ResponseEntity<ApiResponse<Void>> clearBranchLogo(@PathVariable Long id) {
        svc.clearBranchLogo(id);
        return ResponseEntity.ok(ApiResponse.noContent("Logo removed."));
    }
    /* ── Regions ───────────────────────────────────────────────────── */
    @GetMapping("/api/regions")
    public ResponseEntity<ApiResponse<List<RegionResponse>>> listRegions() {
        return ResponseEntity.ok(ApiResponse.ok(svc.getAllRegions()));
    }

    @PostMapping("/api/regions")
    @PreAuthorize("hasRole('ROLE01')")
    public ResponseEntity<ApiResponse<RegionResponse>> createRegion(@RequestBody Map<String, String> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(svc.createRegion(body.get("name"))));
    }

    @PutMapping("/api/regions/{id}")
    @PreAuthorize("hasRole('ROLE01')")
    public ResponseEntity<ApiResponse<RegionResponse>> updateRegion(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(ApiResponse.ok(svc.updateRegion(id, body.get("name"))));
    }

    /* ── Categories ────────────────────────────────────────────────── */
    @GetMapping("/api/categories")
    public ResponseEntity<ApiResponse<List<CategoryResponse>>> listCats(@RequestParam(required = false) Long orgId) {
        Long oid = orgId != null ? orgId : SecurityUtils.getCurrentOrgId();
        return ResponseEntity.ok(ApiResponse.ok(svc.getCategories(oid)));
    }

    @PostMapping("/api/categories")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE05')")
    public ResponseEntity<ApiResponse<CategoryResponse>> createCat(@Valid @RequestBody CategoryRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(svc.createCategory(req)));
    }

    @PutMapping("/api/categories/{id}")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE05')")
    public ResponseEntity<ApiResponse<CategoryResponse>> updateCat(@PathVariable Long id, @Valid @RequestBody CategoryRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(svc.updateCategory(id, req)));
    }

    /* ── Sub-Categories ─────────────────────────────────────────────── */
    @GetMapping("/api/sub-categories")
    public ResponseEntity<ApiResponse<List<SubCategoryResponse>>> listSubCats(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long orgId) {
        Long oid = orgId != null ? orgId : SecurityUtils.getCurrentOrgId();
        return ResponseEntity.ok(ApiResponse.ok(svc.getSubCategories(oid, categoryId)));
    }

    @PostMapping("/api/sub-categories")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE05')")
    public ResponseEntity<ApiResponse<SubCategoryResponse>> createSubCat(@Valid @RequestBody SubCategoryRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(svc.createSubCategory(req)));
    }

    @PutMapping("/api/sub-categories/{id}")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE05')")
    public ResponseEntity<ApiResponse<SubCategoryResponse>> updateSubCat(@PathVariable Long id, @Valid @RequestBody SubCategoryRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(svc.updateSubCategory(id, req)));
    }

    /* ── Units ─────────────────────────────────────────────────────── */
    @GetMapping("/api/units")
    public ResponseEntity<ApiResponse<List<UnitResponse>>> listUnits(@RequestParam(required = false) Long orgId) {
        Long oid = orgId != null ? orgId : SecurityUtils.getCurrentOrgId();
        return ResponseEntity.ok(ApiResponse.ok(svc.getUnits(oid)));
    }

    @PostMapping("/api/units")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02')")
    public ResponseEntity<ApiResponse<UnitResponse>> createUnit(@Valid @RequestBody UnitRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(svc.createUnit(req)));
    }

    /* ── Generics ──────────────────────────────────────────────────── */
    @GetMapping("/api/generics")
    public ResponseEntity<ApiResponse<List<GenericResponse>>> listGenerics(@RequestParam(required = false) Long orgId) {
        Long oid = orgId != null ? orgId : SecurityUtils.getCurrentOrgId();
        return ResponseEntity.ok(ApiResponse.ok(svc.getGenerics(oid)));
    }

    @PostMapping("/api/generics")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE05')")
    public ResponseEntity<ApiResponse<GenericResponse>> createGeneric(@Valid @RequestBody GenericRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(svc.createGeneric(req)));
    }

    @PutMapping("/api/generics/{id}")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE05')")
    public ResponseEntity<ApiResponse<GenericResponse>> updateGeneric(@PathVariable Long id, @Valid @RequestBody GenericRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(svc.updateGeneric(id, req)));
    }

    /* ── Product Companies ─────────────────────────────────────────── */
    @GetMapping("/api/product-companies")
    public ResponseEntity<ApiResponse<List<ProductCompanyResponse>>> listProductCompanies(@RequestParam(required = false) Long orgId) {
        Long oid = orgId != null ? orgId : SecurityUtils.getCurrentOrgId();
        return ResponseEntity.ok(ApiResponse.ok(svc.getProductCompanies(oid)));
    }

    @PostMapping("/api/product-companies")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE05')")
    public ResponseEntity<ApiResponse<ProductCompanyResponse>> createProductCompany(@Valid @RequestBody ProductCompanyRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(svc.createProductCompany(req)));
    }

    @PutMapping("/api/product-companies/{id}")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE05')")
    public ResponseEntity<ApiResponse<ProductCompanyResponse>> updateProductCompany(@PathVariable Long id, @Valid @RequestBody ProductCompanyRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(svc.updateProductCompany(id, req)));
    }

    /* ── Leafs / Boxes ─────────────────────────────────────────────── */
    @GetMapping("/api/leafs")
    public ResponseEntity<ApiResponse<List<LeafResponse>>> listLeafs(@RequestParam(required = false) Long orgId) {
        Long oid = orgId != null ? orgId : SecurityUtils.getCurrentOrgId();
        return ResponseEntity.ok(ApiResponse.ok(svc.getLeafs(oid)));
    }

    @PostMapping("/api/leafs")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE05')")
    public ResponseEntity<ApiResponse<LeafResponse>> createLeaf(@Valid @RequestBody LeafRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(svc.createLeaf(req)));
    }

    @GetMapping("/api/boxes")
    public ResponseEntity<ApiResponse<List<BoxResponse>>> listBoxes(
            @RequestParam(required = false) Long orgId,
            @RequestParam(required = false) Long leafId) {
        Long oid = orgId != null ? orgId : SecurityUtils.getCurrentOrgId();
        return ResponseEntity.ok(ApiResponse.ok(svc.getBoxes(oid, leafId)));
    }

    @PostMapping("/api/boxes")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE05')")
    public ResponseEntity<ApiResponse<BoxResponse>> createBox(@Valid @RequestBody BoxRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(svc.createBox(req)));
    }

    /* ── Products ──────────────────────────────────────────────────────
       MOVED. Every /api/products route now lives on ProductController, which
       adds server-side pricing calculation, generated product codes, uniqueness
       and stock-ladder validation, secure image storage and the transaction-
       history delete guard.

       These mappings were removed rather than left in place: two controllers
       mapping /api/products is an ambiguous-mapping failure at startup, and the
       old endpoints also accepted a client-supplied ?orgId, which let one
       organisation read another's catalogue.

       The MasterDataService product methods behind them were removed too — see
       MasterDataService / MasterDataServiceImpl.                              */

    /* ── Customers ─────────────────────────────────────────────────── */
    @GetMapping("/api/customers")
    public ResponseEntity<ApiResponse<PageResponse<CustomerResponse>>> listCustomers(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        Long oid = SecurityUtils.getCurrentOrgId();
        return ResponseEntity.ok(ApiResponse.ok(svc.searchCustomers(oid, branchId, q, PageRequest.of(page, size))));
    }

    @GetMapping("/api/customers/{id}")
    public ResponseEntity<ApiResponse<CustomerResponse>> getCustomer(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(svc.getCustomer(id)));
    }

    @PostMapping("/api/customers")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE03','ROLE04')")
    public ResponseEntity<ApiResponse<CustomerResponse>> createCustomer(@Valid @RequestBody CustomerRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(svc.createCustomer(req)));
    }

    @PutMapping("/api/customers/{id}")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE04')")
    public ResponseEntity<ApiResponse<CustomerResponse>> updateCustomer(@PathVariable Long id, @Valid @RequestBody CustomerRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(svc.updateCustomer(id, req)));
    }


    @PostMapping("/api/categories/{id}/toggle-status")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02')")
    public ResponseEntity<ApiResponse<Void>> toggleCategory(@PathVariable Long id) {
        svc.toggleCategoryStatus(id);
        return ResponseEntity.ok(ApiResponse.noContent("Category status updated"));
    }


    @PostMapping("/api/sub-categories/{id}/toggle-status")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02')")
    public ResponseEntity<ApiResponse<Void>> toggleSubCategory(@PathVariable Long id) {
        svc.toggleSubCategoryStatus(id);
        return ResponseEntity.ok(ApiResponse.noContent("Sub-category status updated"));
    }


    @PostMapping("/api/generics/{id}/toggle-status")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02')")
    public ResponseEntity<ApiResponse<Void>> toggleGeneric(@PathVariable Long id) {
        svc.toggleGenericStatus(id);
        return ResponseEntity.ok(ApiResponse.noContent("Generic status updated"));
    }


    @PostMapping("/api/product-companies/{id}/toggle-status")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02')")
    public ResponseEntity<ApiResponse<Void>> toggleProductCompany(@PathVariable Long id) {
        svc.toggleProductCompanyStatus(id);
        return ResponseEntity.ok(ApiResponse.noContent("Product company status updated"));
    }

    @PostMapping("/api/customers/{id}/toggle-status")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02')")
    public ResponseEntity<ApiResponse<Void>> toggleCustomer(@PathVariable Long id) {
        svc.toggleCustomerStatus(id);
        return ResponseEntity.ok(ApiResponse.noContent("Customer status updated"));
    }

    /* ── Suppliers ─────────────────────────────────────────────────── */
    @GetMapping("/api/suppliers")
    public ResponseEntity<ApiResponse<PageResponse<SupplierResponse>>> listSuppliers(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        Long oid = SecurityUtils.getCurrentOrgId();
        return ResponseEntity.ok(ApiResponse.ok(svc.searchSuppliers(oid, q, PageRequest.of(page, size))));
    }

    @GetMapping("/api/suppliers/{id}")
    public ResponseEntity<ApiResponse<SupplierResponse>> getSupplier(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(svc.getSupplier(id)));
    }

    @PostMapping("/api/suppliers")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE05')")
    public ResponseEntity<ApiResponse<SupplierResponse>> createSupplier(@Valid @RequestBody SupplierRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(svc.createSupplier(req)));
    }

    @PutMapping("/api/suppliers/{id}")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE05')")
    public ResponseEntity<ApiResponse<SupplierResponse>> updateSupplier(@PathVariable Long id, @Valid @RequestBody SupplierRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(svc.updateSupplier(id, req)));
    }

    @PostMapping("/api/suppliers/{id}/toggle-status")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02')")
    public ResponseEntity<ApiResponse<Void>> toggleSupplier(@PathVariable Long id) {
        svc.toggleSupplierStatus(id);
        return ResponseEntity.ok(ApiResponse.noContent("Supplier status updated"));
    }

    /* ── HR ────────────────────────────────────────────────────────── */
    @GetMapping("/api/departments")
    public ResponseEntity<ApiResponse<List<DepartmentResponse>>> listDepts() {
        return ResponseEntity.ok(ApiResponse.ok(svc.getDepartments(SecurityUtils.getCurrentOrgId())));
    }

    @PostMapping("/api/departments")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02')")
    public ResponseEntity<ApiResponse<DepartmentResponse>> createDept(@Valid @RequestBody DepartmentRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(svc.createDepartment(req)));
    }

    @GetMapping("/api/designations")
    public ResponseEntity<ApiResponse<List<DesignationResponse>>> listDesig() {
        return ResponseEntity.ok(ApiResponse.ok(svc.getDesignations(SecurityUtils.getCurrentOrgId())));
    }

    @PostMapping("/api/designations")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02')")
    public ResponseEntity<ApiResponse<DesignationResponse>> createDesig(@Valid @RequestBody DesignationRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(svc.createDesignation(req)));
    }

    @GetMapping("/api/employees")
    public ResponseEntity<ApiResponse<PageResponse<EmployeeResponse>>> listEmployees(
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        Long oid = SecurityUtils.getCurrentOrgId();
        return ResponseEntity.ok(ApiResponse.ok(
                svc.searchEmployees(oid, branchId, type, q, PageRequest.of(page, size))));
    }

    @GetMapping("/api/employees/types")
    /** The employee types this deployment accepts, so pickers are not hard-coded client-side. */
    public ResponseEntity<ApiResponse<List<Map<String, String>>>> employeeTypes() {
        return ResponseEntity.ok(ApiResponse.ok(EmployeeType.ALL.stream()
                .map(t -> Map.of("value", t, "label", EmployeeType.label(t)))
                .toList()));
    }

    @GetMapping("/api/employees/{id}")
    public ResponseEntity<ApiResponse<EmployeeResponse>> getEmployee(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(svc.getEmployee(id)));
    }

    @PostMapping("/api/employees")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02')")
    public ResponseEntity<ApiResponse<EmployeeResponse>> createEmployee(@Valid @RequestBody EmployeeRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(svc.createEmployee(req)));
    }

    @PutMapping("/api/employees/{id}")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02')")
    public ResponseEntity<ApiResponse<EmployeeResponse>> updateEmployee(@PathVariable Long id, @Valid @RequestBody EmployeeRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(svc.updateEmployee(id, req)));
    }

    @GetMapping("/api/employees/delivery-men")
    public ResponseEntity<ApiResponse<List<EmployeeResponse>>> deliveryMen(
            @RequestParam(required = false) Long branchId) {
        Long oid = SecurityUtils.getCurrentOrgId();
        return ResponseEntity.ok(ApiResponse.ok(svc.getDeliveryMen(oid, branchId)));
    }

    /* ── Areas ─────────────────────────────────────────────────────── */
    @GetMapping("/api/areas")
    public ResponseEntity<ApiResponse<List<AreaResponse>>> listAreas(@RequestParam(required = false) Long branchId) {
        return ResponseEntity.ok(ApiResponse.ok(svc.getAreas(branchId)));
    }

    @PostMapping("/api/areas")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02')")
    public ResponseEntity<ApiResponse<AreaResponse>> createArea(@Valid @RequestBody MasterDataRequests.AreaRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(svc.createArea(req)));
    }

    @PutMapping("/api/areas/{id}")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02')")
    public ResponseEntity<ApiResponse<AreaResponse>> updateArea(
            @PathVariable Long id, @Valid @RequestBody MasterDataRequests.AreaRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(svc.updateArea(id, req)));
    }

    /* ── Divisions (GAP-1) ──────────────────────────────────────────── */
    @GetMapping("/api/divisions")
    public ResponseEntity<ApiResponse<List<MasterDataResponses.DivisionResponse>>> listDivisions(
            @RequestParam(required = false) Long regionId) {
        return ResponseEntity.ok(ApiResponse.ok(svc.getDivisions(regionId)));
    }

    @GetMapping("/api/divisions/{id}")
    public ResponseEntity<ApiResponse<MasterDataResponses.DivisionResponse>> getDivision(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(svc.getDivisionById(id)));
    }

    @PostMapping("/api/divisions")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02')")
    public ResponseEntity<ApiResponse<MasterDataResponses.DivisionResponse>> createDivision(
            @Valid @RequestBody MasterDataRequests.DivisionRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(svc.createDivision(req)));
    }

    @PutMapping("/api/divisions/{id}")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02')")
    public ResponseEntity<ApiResponse<MasterDataResponses.DivisionResponse>> updateDivision(
            @PathVariable Long id, @Valid @RequestBody MasterDataRequests.DivisionRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(svc.updateDivision(id, req)));
    }

    /* ── Districts (GAP-2) ──────────────────────────────────────────── */
    @GetMapping("/api/districts")
    public ResponseEntity<ApiResponse<List<MasterDataResponses.DistrictResponse>>> listDistricts(
            @RequestParam(required = false) Long divisionId) {
        return ResponseEntity.ok(ApiResponse.ok(svc.getDistricts(divisionId)));
    }

    @GetMapping("/api/districts/{id}")
    public ResponseEntity<ApiResponse<MasterDataResponses.DistrictResponse>> getDistrict(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(svc.getDistrictById(id)));
    }

    @PostMapping("/api/districts")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02')")
    public ResponseEntity<ApiResponse<MasterDataResponses.DistrictResponse>> createDistrict(
            @Valid @RequestBody MasterDataRequests.DistrictRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(svc.createDistrict(req)));
    }

    @PutMapping("/api/districts/{id}")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02')")
    public ResponseEntity<ApiResponse<MasterDataResponses.DistrictResponse>> updateDistrict(
            @PathVariable Long id, @Valid @RequestBody MasterDataRequests.DistrictRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(svc.updateDistrict(id, req)));
    }

    /* ── Thanas (GAP-3) ─────────────────────────────────────────────── */
    @GetMapping("/api/thanas")
    public ResponseEntity<ApiResponse<List<MasterDataResponses.ThanaResponse>>> listThanas(
            @RequestParam(required = false) Long districtId) {
        return ResponseEntity.ok(ApiResponse.ok(svc.getThanas(districtId)));
    }

    @GetMapping("/api/thanas/{id}")
    public ResponseEntity<ApiResponse<MasterDataResponses.ThanaResponse>> getThana(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(svc.getThanaById(id)));
    }

    @PostMapping("/api/thanas")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02')")
    public ResponseEntity<ApiResponse<MasterDataResponses.ThanaResponse>> createThana(
            @Valid @RequestBody MasterDataRequests.ThanaRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(svc.createThana(req)));
    }

    @PutMapping("/api/thanas/{id}")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02')")
    public ResponseEntity<ApiResponse<MasterDataResponses.ThanaResponse>> updateThana(
            @PathVariable Long id, @Valid @RequestBody MasterDataRequests.ThanaRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(svc.updateThana(id, req)));
    }

    /* ── Leaf PUT (GAP-5) ───────────────────────────────────────────── */
    @PutMapping("/api/leafs/{id}")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE05')")
    public ResponseEntity<ApiResponse<LeafResponse>> updateLeaf(
            @PathVariable Long id, @Valid @RequestBody MasterDataRequests.LeafUpdateRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(svc.updateLeaf(id, req)));
    }

    /* ── Box PUT (GAP-5) ────────────────────────────────────────────── */
    @PutMapping("/api/boxes/{id}")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE05')")
    public ResponseEntity<ApiResponse<BoxResponse>> updateBox(
            @PathVariable Long id, @Valid @RequestBody MasterDataRequests.BoxUpdateRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(svc.updateBox(id, req)));
    }

    /* ── Department PUT (GAP-6) ─────────────────────────────────────── */
    @PutMapping("/api/departments/{id}")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02')")
    public ResponseEntity<ApiResponse<DepartmentResponse>> updateDept(
            @PathVariable Long id, @Valid @RequestBody MasterDataRequests.DepartmentUpdateRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(svc.updateDepartment(id, req)));
    }

    /* ── Designation PUT (GAP-6) ────────────────────────────────────── */
    @PutMapping("/api/designations/{id}")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02')")
    public ResponseEntity<ApiResponse<DesignationResponse>> updateDesig(
            @PathVariable Long id, @Valid @RequestBody MasterDataRequests.DesignationUpdateRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(svc.updateDesignation(id, req)));
    }
}
