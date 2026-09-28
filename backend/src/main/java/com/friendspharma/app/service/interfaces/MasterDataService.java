package com.friendspharma.app.service.interfaces;

import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.request.MasterDataRequests;
import com.friendspharma.app.dto.request.MasterDataRequests.*;
import com.friendspharma.app.dto.response.MasterDataResponses;
import com.friendspharma.app.dto.response.MasterDataResponses.*;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface MasterDataService {

    // Organization hierarchy
    List<OrganizationResponse> getAllOrganizations();
    OrganizationResponse createOrganization(OrganizationRequest req);
    OrganizationResponse updateOrganization(Long id, OrganizationRequest req);
    void deleteOrganization(Long id);

    List<CompanyResponse>  getCompaniesByOrg(Long orgId);
    CompanyResponse createCompany(CompanyRequest req);
    CompanyResponse updateCompany(Long id, CompanyRequest req);

    List<BranchResponse>   getBranches(Long orgId, Long regionId);
    BranchResponse createBranch(BranchRequest req);
    BranchResponse updateBranch(Long id, BranchRequest req);

    /** Sets a branch logo. Guarded on the Edit right of Organisation & Parties. */
    String setBranchLogo(Long branchId, org.springframework.web.multipart.MultipartFile file);

    /** Removes a branch logo, so the UI falls back to the pharmacy's initial. */
    void clearBranchLogo(Long branchId);

    List<RegionResponse> getAllRegions();
    RegionResponse createRegion(String name);
    RegionResponse updateRegion(Long id, String name);

    // Product catalogue
    List<CategoryResponse>    getCategories(Long orgId);
    CategoryResponse createCategory(CategoryRequest req);
    CategoryResponse updateCategory(Long id, CategoryRequest req);

    List<SubCategoryResponse> getSubCategories(Long orgId, Long categoryId);
    SubCategoryResponse createSubCategory(SubCategoryRequest req);
    SubCategoryResponse updateSubCategory(Long id, SubCategoryRequest req);

    List<UnitResponse>        getUnits(Long orgId);
    UnitResponse createUnit(UnitRequest req);

    List<GenericResponse>     getGenerics(Long orgId);
    GenericResponse createGeneric(GenericRequest req);
    GenericResponse updateGeneric(Long id, GenericRequest req);

    List<ProductCompanyResponse> getProductCompanies(Long orgId);
    ProductCompanyResponse createProductCompany(ProductCompanyRequest req);
    ProductCompanyResponse updateProductCompany(Long id, ProductCompanyRequest req);

    List<LeafResponse>  getLeafs(Long orgId);
    LeafResponse createLeaf(LeafRequest req);

    List<BoxResponse>   getBoxes(Long orgId, Long leafId);
    BoxResponse createBox(BoxRequest req);

    // Products — moved to ProductService / ProductServiceImpl (Product Master, V4).
    // The methods that lived here were a thin CRUD shim that trusted the client
    // for TP price, IGTA and buy price, and had no uniqueness or stock validation.

    // Parties
    PageResponse<CustomerResponse> searchCustomers(Long orgId, Long branchId, String q, Pageable pageable);
    CustomerResponse getCustomer(Long id);
    CustomerResponse createCustomer(CustomerRequest req);
    CustomerResponse updateCustomer(Long id, CustomerRequest req);
    /* Status-only changes. Deactivating is not an update: it must not require the
       rest of the record, and must not let a half-filled form through as one. */
    void toggleCategoryStatus(Long id);
    void toggleSubCategoryStatus(Long id);
    void toggleGenericStatus(Long id);
    void toggleProductCompanyStatus(Long id);

    void toggleCustomerStatus(Long id);

    PageResponse<SupplierResponse> searchSuppliers(Long orgId, String q, Pageable pageable);
    SupplierResponse getSupplier(Long id);
    SupplierResponse createSupplier(SupplierRequest req);
    SupplierResponse updateSupplier(Long id, SupplierRequest req);
    void toggleSupplierStatus(Long id);

    // HR
    List<DepartmentResponse>  getDepartments(Long orgId);
    DepartmentResponse createDepartment(DepartmentRequest req);

    List<DesignationResponse> getDesignations(Long orgId);
    DesignationResponse createDesignation(DesignationRequest req);

    PageResponse<EmployeeResponse> searchEmployees(Long orgId, Long branchId, String type, String q, Pageable pageable);
    EmployeeResponse getEmployee(Long id);
    EmployeeResponse createEmployee(EmployeeRequest req);
    EmployeeResponse updateEmployee(Long id, EmployeeRequest req);
    List<EmployeeResponse> getDeliveryMen(Long orgId, Long branchId);

    // Geography
    List<AreaResponse> getAreas(Long branchId);
    AreaResponse createArea(MasterDataRequests.AreaRequest req);
    AreaResponse updateArea(Long id, MasterDataRequests.AreaRequest req);

    // Geography gaps — Division / District / Thana (GAP-1/2/3)
    List<MasterDataResponses.DivisionResponse> getDivisions(Long regionId);
    MasterDataResponses.DivisionResponse getDivisionById(Long id);
    MasterDataResponses.DivisionResponse createDivision(MasterDataRequests.DivisionRequest req);
    MasterDataResponses.DivisionResponse updateDivision(Long id, MasterDataRequests.DivisionRequest req);

    List<MasterDataResponses.DistrictResponse> getDistricts(Long divisionId);
    MasterDataResponses.DistrictResponse getDistrictById(Long id);
    MasterDataResponses.DistrictResponse createDistrict(MasterDataRequests.DistrictRequest req);
    MasterDataResponses.DistrictResponse updateDistrict(Long id, MasterDataRequests.DistrictRequest req);

    List<MasterDataResponses.ThanaResponse> getThanas(Long districtId);
    MasterDataResponses.ThanaResponse getThanaById(Long id);
    MasterDataResponses.ThanaResponse createThana(MasterDataRequests.ThanaRequest req);
    MasterDataResponses.ThanaResponse updateThana(Long id, MasterDataRequests.ThanaRequest req);

    // GAP-5: Leaf / Box update
    LeafResponse updateLeaf(Long id, MasterDataRequests.LeafUpdateRequest req);
    BoxResponse  updateBox(Long id, MasterDataRequests.BoxUpdateRequest req);

    // GAP-6: Department / Designation update
    DepartmentResponse  updateDepartment(Long id, MasterDataRequests.DepartmentUpdateRequest req);
    DesignationResponse updateDesignation(Long id, MasterDataRequests.DesignationUpdateRequest req);
}
