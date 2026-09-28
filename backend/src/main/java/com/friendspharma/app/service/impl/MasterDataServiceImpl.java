package com.friendspharma.app.service.impl;

import com.friendspharma.app.repository.AllBranchRepository;
import com.friendspharma.app.entity.AllBranch;
import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.request.MasterDataRequests;
import com.friendspharma.app.dto.request.MasterDataRequests.*;
import com.friendspharma.app.dto.response.MasterDataResponses;
import com.friendspharma.app.dto.response.MasterDataResponses.*;
import com.friendspharma.app.entity.*;
import com.friendspharma.app.exception.custom.*;
import com.friendspharma.app.repository.*;
import com.friendspharma.app.security.SecurityUtils;
import com.friendspharma.app.service.interfaces.MasterDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MasterDataServiceImpl implements MasterDataService {

    // Repositories
    private final AllOrganizationRepository orgRepo;
    private final AllCompanyRepository companyRepo;
    private final AllBranchRepository branchRepo;
    private final AllRegionRepository regionRepo;
    private final PosProductCategoryRepository catRepo;
    private final PosProductSubCategoryRepository subCatRepo;
    private final PosProductUnitRepository unitRepo;
    private final PosGenericInfoRepository genericRepo;
    private final PosCompanyInfoRepository productCompanyRepo;
    private final PosLeafInfoRepository leafRepo;
    private final PosBoxInfoRepository boxRepo;
    private final PosProductInfoRepository productRepo;
    private final PosCustomerInfoRepository customerRepo;
    private final PosSupplierInfoRepository supplierRepo;
    private final AllDepartmentsRepository deptRepo;
    private final HrDesignationRepository designRepo;
    private final HrEmployeeRepository empRepo;
    private final PosAreaInfoRepository areaRepo;
    private final AllDivisionRepository divisionRepo;
    private final AllDistrictRepository districtRepo;
    private final AllThanaRepository thanaRepo;
    private final BranchScopeService branchScope;
    private final BranchLogoStorageService logoStorage;
    private final ProductImageStorageService productImages;
    private final PermissionGuard permissions;

    @Value("${app.upload.max-size-bytes:2097152}")
    private long maxUploadBytes;

    private Long getOrgId() { return SecurityUtils.getCurrentOrgId(); }
    private Long getBranchId() { return SecurityUtils.getCurrentBranchId(); }

    /**
     * An empty string is not a value, it is the absence of one.
     *
     * <p>Kept out of the column so that "no phone number" is one thing rather
     * than two, and so the unique index — which ignores nulls but not empty
     * strings — does not make the second customer without a phone a duplicate of
     * the first.
     */
    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * The pharmacy whose master data this request may see.
     *
     * <p>Not {@link SecurityUtils#getCurrentBranchId()}: that returns null for an
     * account with no branch, and a null branch widens every query below back to
     * the whole organisation — which is exactly the leak this closes.
     * {@code BranchScopeService} refuses instead of widening.
     */
    private Long branch() { return branchScope.resolve(null); }

    /**
     * The company the session's pharmacy belongs to.
     *
     * <p>Read from the branch rather than taken from the request, for the same
     * reason the branch itself is: it has to agree with the branch anyway, and a
     * company in a payload is a company the caller can change.
     */
    private Long company() { return companyOfBranch(branch()); }

    private Long companyOfBranch(Long branchId) {
        if (branchId == null) return null;
        return branchRepo.findById(branchId).map(AllBranch::getPidCompany).orElse(null);
    }

    /* ── Organization ── */

    @Override
    @Transactional(readOnly = true)
    public List<OrganizationResponse> getAllOrganizations() {
        return orgRepo.findByStatusOrderByOrgName("Y").stream().map(this::mapOrg).toList();
    }

    @Override
    @Transactional
    public OrganizationResponse createOrganization(OrganizationRequest req) {
        AllOrganization e = AllOrganization.builder()
                .orgName(req.getName()).orgShortname(req.getShortName())
                .email(req.getEmail()).phone(req.getPhone())
                .companyAddress(req.getAddress()).build();
        e.setStatus("Y");
        return mapOrg(orgRepo.save(e));
    }

    @Override
    @Transactional
    public OrganizationResponse updateOrganization(Long id, OrganizationRequest req) {
        AllOrganization e = orgRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Organization", id));
        e.setOrgName(req.getName()); e.setOrgShortname(req.getShortName());
        e.setEmail(req.getEmail()); e.setPhone(req.getPhone());
        if (req.getStatus() != null) e.setStatus(req.getStatus());
        return mapOrg(orgRepo.save(e));
    }

    @Override
    @Transactional
    public void deleteOrganization(Long id) { orgRepo.deleteById(id); }

    private OrganizationResponse mapOrg(AllOrganization e) {
        return OrganizationResponse.builder().id(e.getPidOrg()).name(e.getOrgName())
                .shortName(e.getOrgShortname()).email(e.getEmail()).phone(e.getPhone())
                .address(e.getCompanyAddress()).status(e.getStatus()).build();
    }

    /* ── Company ── */

    @Override @Transactional(readOnly = true)
    public List<CompanyResponse> getCompaniesByOrg(Long orgId) {
        return companyRepo.findByPidOrgOrderByCompanyName(orgId).stream().map(this::mapCo).toList();
    }

    @Override @Transactional
    public CompanyResponse createCompany(CompanyRequest req) {
        AllCompany e = AllCompany.builder().pidOrg(req.getOrgId()).companyName(req.getName())
                .companyShortName(req.getShortName()).email(req.getEmail()).phone(req.getPhone())
                .companyAddress(req.getAddress()).build();
        e.setStatus("Y");
        return mapCo(companyRepo.save(e));
    }

    @Override @Transactional
    public CompanyResponse updateCompany(Long id, CompanyRequest req) {
        AllCompany e = companyRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Company", id));
        e.setCompanyName(req.getName()); e.setCompanyShortName(req.getShortName());
        if (req.getStatus() != null) e.setStatus(req.getStatus());
        return mapCo(companyRepo.save(e));
    }

    private CompanyResponse mapCo(AllCompany e) {
        return CompanyResponse.builder().id(e.getPidCompany()).orgId(e.getPidOrg())
                .name(e.getCompanyName()).shortName(e.getCompanyShortName())
                .email(e.getEmail()).phone(e.getPhone()).address(e.getCompanyAddress())
                .status(e.getStatus()).build();
    }

    /* ── Branch ── */

    @Override @Transactional(readOnly = true)
    public List<BranchResponse> getBranches(Long orgId, Long regionId) {
        List<AllBranch> list = regionId != null
                ? branchRepo.findByPidRegionAndStatusOrderByBranchName(regionId, "Y")
                : branchRepo.findByPidOrgAndPidBranchAndStatusOrderByBranchName(orgId, branch(), "Y");
        return list.stream().map(e -> BranchResponse.builder().id(e.getPidBranch())
                .orgId(e.getPidOrg()).companyId(e.getPidCompany()).name(e.getBranchName())
                .address(e.getBranchAddress()).email(e.getBranchEmail()).phone(e.getBranchPhoneNo())
                .status(e.getStatus()).regionId(e.getPidRegion())
                // So the Master Data form can show the mark it is about to replace,
                // rather than always offering to upload a first one.
                .logoUrl(e.getLogoUrl())
                .ecomEnabled(e.getEcomEnabled()).branchTradeType(e.getBranchTradeType())
                .branchVerified(e.getBranchVerified()).drugLicenseNo(e.getDrugLicenseNo())
                .verifiedBy(e.getVerifiedBy()).verifiedAt(e.getVerifiedAt())
                .latitude(e.getLatitude()).longitude(e.getLongitude())
                .ecomDeliveryRadiusKm(e.getEcomDeliveryRadiusKm()).ecomMinOrderAmount(e.getEcomMinOrderAmount())
                .ecomOpeningHours(e.getEcomOpeningHours()).ecomDescription(e.getEcomDescription())
                .ecomDeliveryFeeType(e.getEcomDeliveryFeeType()).ecomDeliveryFlatFee(e.getEcomDeliveryFlatFee())
                .ecomDeliveryFeePerKm(e.getEcomDeliveryFeePerKm()).ecomDeliveryFreeAbove(e.getEcomDeliveryFreeAbove())
                .build()).toList();
    }

    /**
     * Opens a branch. Only ROLE01 reaches here — see MasterDataController.
     *
     * <p><b>Why the tenant status is written explicitly.</b> The column is NOT
     * NULL with a default of PENDING, and a default only applies to a column an
     * insert leaves out. This one did not leave it out: the field was never set,
     * so Hibernate sent an explicit NULL and every attempt came back
     * "Tenant status is required." Creating a branch from this screen has never
     * once worked.
     *
     * <p><b>Why ACTIVE and not PENDING.</b> PENDING is a refusal —
     * TenantStatusService turns it into a 402 for everyone at the branch — and it
     * exists for the self-service registration path, where somebody applies and
     * the platform decides later. Nobody applied here. A platform administrator
     * opened the branch deliberately, and a branch that cannot be used until a
     * second, unmentioned step on another screen is not what they asked for. It
     * is also what all nine existing branches carry, and where the onboarding
     * path lands once a subscription is approved. Suspending it afterwards is a
     * click on the Tenants page.
     */
    @Override @Transactional
    public BranchResponse createBranch(BranchRequest req) {
        AllBranch e = AllBranch.builder().pidOrg(req.getOrgId()).pidCompany(req.getCompanyId())
                .branchName(req.getName()).branchAddress(req.getAddress())
                .branchEmail(req.getEmail()).branchPhoneNo(req.getPhone())
                .pidRegion(req.getRegionId()).tenantStatus("ACTIVE").build();
        e.setStatus("Y");
        AllBranch saved = branchRepo.save(e);
        // Its picture folder, product_image/<branchId>, from the first moment.
        productImages.ensureBranchDirectory(saved.getPidBranch());
        return BranchResponse.builder().id(saved.getPidBranch()).orgId(saved.getPidOrg())
                .companyId(saved.getPidCompany()).name(saved.getBranchName())
                .address(saved.getBranchAddress()).status(saved.getStatus()).build();
    }

    @Override @Transactional
    public BranchResponse updateBranch(Long id, BranchRequest req) {
        AllBranch e = branchRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Branch", id));
        e.setBranchName(req.getName()); e.setBranchAddress(req.getAddress());
        if (req.getRegionId() != null) e.setPidRegion(req.getRegionId());
        if (req.getStatus() != null) e.setStatus(req.getStatus());
        applyOnlineStore(e, req);
        applyVerification(e, req);
        AllBranch saved = branchRepo.save(e);
        // The logo is carried back untouched. updateBranch never sets it — it has
        // its own endpoint — and omitting it here would make the form think the
        // pharmacy had lost its mark the moment anything else was saved.
        return BranchResponse.builder().id(saved.getPidBranch()).name(saved.getBranchName())
                .orgId(saved.getPidOrg()).companyId(saved.getPidCompany())
                .status(saved.getStatus()).logoUrl(saved.getLogoUrl())
                .ecomEnabled(saved.getEcomEnabled()).branchTradeType(saved.getBranchTradeType())
                .branchVerified(saved.getBranchVerified()).drugLicenseNo(saved.getDrugLicenseNo())
                .verifiedBy(saved.getVerifiedBy()).verifiedAt(saved.getVerifiedAt())
                .latitude(saved.getLatitude()).longitude(saved.getLongitude())
                .ecomDeliveryRadiusKm(saved.getEcomDeliveryRadiusKm()).ecomMinOrderAmount(saved.getEcomMinOrderAmount())
                .ecomOpeningHours(saved.getEcomOpeningHours()).ecomDescription(saved.getEcomDescription())
                .ecomDeliveryFeeType(saved.getEcomDeliveryFeeType()).ecomDeliveryFlatFee(saved.getEcomDeliveryFlatFee())
                .ecomDeliveryFeePerKm(saved.getEcomDeliveryFeePerKm()).ecomDeliveryFreeAbove(saved.getEcomDeliveryFreeAbove())
                .build();
    }

    /**
     * [V103] The online-store block of the branch form. A field left out of the
     * request is left alone on the row, so the older callers that know nothing
     * of the online store cannot switch it off by saving the address.
     */
    /**
     * [V114] The licence check.
     *
     * <p>Switching it to Y stamps who said so and when, because a trust badge
     * with nobody's name behind it is how the last one ended up meaning
     * nothing. Turning it back to N clears the stamp rather than leaving a
     * signature on a claim that has been withdrawn.
     */
    private static void applyVerification(AllBranch e, BranchRequest req) {
        if (req.getDrugLicenseNo() != null) e.setDrugLicenseNo(req.getDrugLicenseNo().trim());
        if (req.getBranchVerified() == null) return;

        String v = req.getBranchVerified().trim().toUpperCase();
        if (!"Y".equals(v) && !"N".equals(v)) {
            throw new ValidationException("Licence seen must be Y or N.");
        }
        String licence = req.getDrugLicenseNo() != null ? req.getDrugLicenseNo() : e.getDrugLicenseNo();
        if ("Y".equals(v) && (licence == null || licence.trim().isEmpty())) {
            throw new ValidationException(
                    "Record the drug licence number before marking this pharmacy verified: "
                    + "the badge tells customers somebody checked, so there has to be something checked.");
        }
        boolean was = e.isVerified();
        e.setBranchVerified(v);
        if ("Y".equals(v) && !was) {
            e.setVerifiedBy(com.friendspharma.app.security.SecurityUtils.getCurrentUsername());
            e.setVerifiedAt(java.time.LocalDateTime.now());
        } else if ("N".equals(v)) {
            e.setVerifiedBy(null);
            e.setVerifiedAt(null);
        }
    }

    private static void applyOnlineStore(AllBranch e, BranchRequest req) {
        if (req.getEcomEnabled() != null) {
            String v = req.getEcomEnabled().trim().toUpperCase();
            if (!"Y".equals(v) && !"N".equals(v)) {
                throw new ValidationException("Online store must be Y or N.");
            }
            if ("Y".equals(v) && (coalesce(req.getLatitude(), e.getLatitude()) == null
                    || coalesce(req.getLongitude(), e.getLongitude()) == null)) {
                throw new ValidationException(
                        "Set the pharmacy's latitude and longitude before switching the online store on: "
                        + "customers find it by distance.");
            }
            e.setEcomEnabled(v);
        }
        /* [V112] Who the branch sells to online. Checked here as well as by the
           CHECK constraint so a typo comes back as a sentence rather than a
           constraint violation — and a branch is never made wholesale by
           accident, because the default stays RETAIL until somebody chooses. */
        if (req.getBranchTradeType() != null) {
            String t = req.getBranchTradeType().trim().toUpperCase();
            if (t.isEmpty()) t = "RETAIL";
            if (!java.util.List.of("RETAIL", "WHOLESALE", "BOTH").contains(t)) {
                throw new ValidationException("A branch sells Retail, Wholesale or Both — '"
                        + req.getBranchTradeType() + "' is none of them.");
            }
            if (!"RETAIL".equals(t) && coalesce(req.getRegionId(), e.getPidRegion()) == null) {
                throw new ValidationException(
                        "Set the branch's region before it sells wholesale: wholesale customers "
                      + "are only shown the pharmacies in their own approved region.");
            }
            e.setBranchTradeType(t);
        }
        if (req.getLatitude() != null)  e.setLatitude(req.getLatitude());
        if (req.getLongitude() != null) e.setLongitude(req.getLongitude());
        if (req.getEcomDeliveryRadiusKm() != null) e.setEcomDeliveryRadiusKm(req.getEcomDeliveryRadiusKm());
        if (req.getEcomMinOrderAmount() != null)   e.setEcomMinOrderAmount(req.getEcomMinOrderAmount());
        if (req.getEcomOpeningHours() != null)     e.setEcomOpeningHours(blankToNull(req.getEcomOpeningHours()));
        if (req.getEcomDescription() != null)      e.setEcomDescription(blankToNull(req.getEcomDescription()));

        /* [V108] The delivery charge policy. The words are checked here as well
           as by the CHECK constraint: a typo should come back as a sentence a
           person can act on, not as a constraint violation. */
        if (req.getEcomDeliveryFeeType() != null) {
            String t = req.getEcomDeliveryFeeType().trim().toUpperCase();
            if (t.isEmpty()) t = "NONE";
            if (!java.util.List.of("NONE", "FLAT", "DISTANCE").contains(t)) {
                throw new ValidationException(
                        "Delivery charge must be None, Flat or Distance — '" + req.getEcomDeliveryFeeType() + "' is none of them.");
            }
            if ("DISTANCE".equals(t) && coalesce(req.getLatitude(), e.getLatitude()) == null) {
                throw new ValidationException(
                        "Charging by distance needs the pharmacy's own latitude and longitude to measure from.");
            }
            e.setEcomDeliveryFeeType(t);
        }
        /* The CHECK constraint refuses these too, but it answers in the language
           of constraints. Somebody typing a minus sign into a form deserves a
           sentence about delivery charges. */
        requireNotNegative(req.getEcomDeliveryFlatFee(), "flat delivery fee");
        requireNotNegative(req.getEcomDeliveryFeePerKm(), "per-kilometre delivery fee");
        requireNotNegative(req.getEcomDeliveryFreeAbove(), "free-delivery threshold");
        if (req.getEcomDeliveryFlatFee() != null)   e.setEcomDeliveryFlatFee(req.getEcomDeliveryFlatFee());
        if (req.getEcomDeliveryFeePerKm() != null)  e.setEcomDeliveryFeePerKm(req.getEcomDeliveryFeePerKm());
        if (req.getEcomDeliveryFreeAbove() != null) e.setEcomDeliveryFreeAbove(req.getEcomDeliveryFreeAbove());
    }

    /** A negative delivery charge is a refund wearing a delivery charge's clothes. */
    private static void requireNotNegative(java.math.BigDecimal v, String what) {
        if (v != null && v.signum() < 0) {
            throw new ValidationException("The " + what + " cannot be less than zero.");
        }
    }

    private static <T> T coalesce(T a, T b) { return a != null ? a : b; }

    /* ── Branch logo ──────────────────────────────────────────────────────
       The mark a pharmacy shows in its own sidebar and browser tab.

       Kept beside the rest of the branch, not in the onboarding service: it is
       an attribute of a branch, and it lives where branches are administered so
       that the same permission governs both.

       Guarded on the Edit right of the module that owns Branch, not on a role.
       These endpoints began life on the Super Admin onboarding controller,
       which meant a pharmacy's own administrator could not set their own logo —
       and no amount of ticking in RBAC would have changed that. */

    /** Organisation & Parties — the module Branch is administered on. */
    private static final String BRANCH_MODULE = "MOD02";

    private void requireBranchEditRight() {
        if (!permissions.has(BRANCH_MODULE, PermissionGuard.Right.EDIT)) {
            throw new BusinessException(
                    "You do not have permission to change a branch. Ask for the Edit right on "
                    + "Organisation & Parties in RBAC.",
                    HttpStatus.FORBIDDEN, "BRANCH_EDIT_NOT_PERMITTED");
        }
    }

    @Override
    @Transactional
    public String setBranchLogo(Long branchId, MultipartFile file) {
        requireBranchEditRight();
        // Resolved, not trusted: a branch id in a path is one the caller can
        // change, and this is the same rule every read on the screen already
        // follows. A branch outside the caller's reach comes back 404.
        Long scoped = branchScope.resolve(branchId);
        AllBranch branch = branchRepo.findById(scoped)
                .orElseThrow(() -> new ResourceNotFoundException("Branch", branchId));

        String previous = branch.getLogoUrl();
        String url = logoStorage.store(file, scoped);
        branch.setLogoUrl(url);
        branchRepo.save(branch);

        // Deleted only once the new file is safely written: a failed upload
        // should leave the old mark in place, not leave the pharmacy with none.
        if (previous != null && !previous.equals(url)) logoStorage.delete(previous);
        log.info("Logo set for branch {} ({})", scoped, branch.getBranchName());
        return url;
    }

    @Override
    @Transactional
    public void clearBranchLogo(Long branchId) {
        requireBranchEditRight();
        Long scoped = branchScope.resolve(branchId);
        AllBranch branch = branchRepo.findById(scoped)
                .orElseThrow(() -> new ResourceNotFoundException("Branch", branchId));

        String previous = branch.getLogoUrl();
        branch.setLogoUrl(null);
        branchRepo.save(branch);
        logoStorage.delete(previous);
        log.info("Logo cleared for branch {} ({})", scoped, branch.getBranchName());
    }

    /* ── Region ── */

    @Override @Transactional(readOnly = true)
    public List<RegionResponse> getAllRegions() {
        return regionRepo.findByStatusOrderByRegionName("Y").stream()
                .map(r -> RegionResponse.builder().id(r.getPidRegion()).name(r.getRegionName()).status(r.getStatus()).build())
                .toList();
    }

    @Override @Transactional
    public RegionResponse createRegion(String name) {
        AllRegion r = AllRegion.builder().regionName(name).build();
        r.setStatus("Y");
        AllRegion saved = regionRepo.save(r);
        return RegionResponse.builder().id(saved.getPidRegion()).name(saved.getRegionName()).status(saved.getStatus()).build();
    }

    @Override @Transactional
    public RegionResponse updateRegion(Long id, String name) {
        AllRegion r = regionRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Region", id));
        r.setRegionName(name);
        regionRepo.save(r);
        return RegionResponse.builder().id(r.getPidRegion()).name(r.getRegionName()).status(r.getStatus()).build();
    }

    /* ── Category ── */

    @Override @Transactional(readOnly = true)
    public List<CategoryResponse> getCategories(Long orgId) {
        return catRepo.findByPidOrgAndPidBranchAndStatusOrderByCategoryName(orgId, branch(), "Y").stream()
                .map(c -> CategoryResponse.builder().id(c.getPidCategory()).name(c.getCategoryName())
                        .orgId(c.getPidOrg()).status(c.getStatus()).build()).toList();
    }

    @Override @Transactional
    public CategoryResponse createCategory(CategoryRequest req) {
        Long oid = getOrgId();
        PosProductCategory e = PosProductCategory.builder().pidOrg(oid).pidBranch(branch()).pidCompany(company())
                .categoryName(req.getName()).build();
        e.setStatus("Y");
        PosProductCategory saved = catRepo.save(e);
        return CategoryResponse.builder().id(saved.getPidCategory()).name(saved.getCategoryName())
                .orgId(saved.getPidOrg()).status(saved.getStatus()).build();
    }

    @Override @Transactional
    public CategoryResponse updateCategory(Long id, CategoryRequest req) {
        PosProductCategory e = catRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Category", id));
        e.setCategoryName(req.getName());
        if (req.getStatus() != null) e.setStatus(req.getStatus());
        catRepo.save(e);
        return CategoryResponse.builder().id(e.getPidCategory()).name(e.getCategoryName())
                .orgId(e.getPidOrg()).status(e.getStatus()).build();
    }

    /* ── Sub-Category ── */

    @Override @Transactional(readOnly = true)
    public List<SubCategoryResponse> getSubCategories(Long orgId, Long categoryId) {
        List<PosProductSubCategory> list = categoryId != null
                ? subCatRepo.findByPidCategoryAndStatusOrderBySubCategoryName(categoryId, "Y")
                : subCatRepo.findByPidOrgAndPidBranchAndStatusOrderBySubCategoryName(orgId, branch(), "Y");
        return list.stream().map(s -> SubCategoryResponse.builder().id(s.getPidSubCategory())
                .name(s.getSubCategoryName()).categoryId(s.getPidCategory())
                .orgId(s.getPidOrg()).status(s.getStatus()).build()).toList();
    }

    @Override @Transactional
    public SubCategoryResponse createSubCategory(SubCategoryRequest req) {
        PosProductSubCategory e = PosProductSubCategory.builder().pidOrg(getOrgId()).pidBranch(branch()).pidCompany(company())
                .subCategoryName(req.getName()).pidCategory(req.getCategoryId()).build();
        e.setStatus("Y");
        subCatRepo.save(e);
        return SubCategoryResponse.builder().id(e.getPidSubCategory()).name(e.getSubCategoryName())
                .categoryId(e.getPidCategory()).status(e.getStatus()).build();
    }

    @Override @Transactional
    public SubCategoryResponse updateSubCategory(Long id, SubCategoryRequest req) {
        PosProductSubCategory e = subCatRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Sub-Category", id));
        e.setSubCategoryName(req.getName());
        if (req.getCategoryId() != null) e.setPidCategory(req.getCategoryId());
        if (req.getStatus() != null) e.setStatus(req.getStatus());
        subCatRepo.save(e);
        return SubCategoryResponse.builder().id(e.getPidSubCategory()).name(e.getSubCategoryName())
                .categoryId(e.getPidCategory()).status(e.getStatus()).build();
    }

    /* ── Unit ── */

    @Override @Transactional(readOnly = true)
    public List<UnitResponse> getUnits(Long orgId) {
        return unitRepo.findByPidOrgAndPidBranchAndStatusOrderByUnitName(orgId, branch(), "Y").stream()
                .map(u -> UnitResponse.builder().id(u.getPidUnit()).name(u.getUnitName())
                        .orgId(u.getPidOrg()).status(u.getStatus()).build()).toList();
    }

    @Override @Transactional
    public UnitResponse createUnit(UnitRequest req) {
        PosProductUnit e = PosProductUnit.builder().pidOrg(getOrgId()).pidBranch(branch()).pidCompany(company())
                .unitName(req.getName()).build();
        e.setStatus("Y");
        unitRepo.save(e);
        return UnitResponse.builder().id(e.getPidUnit()).name(e.getUnitName()).status(e.getStatus()).build();
    }

    /* ── Generic ── */

    @Override @Transactional(readOnly = true)
    public List<GenericResponse> getGenerics(Long orgId) {
        return genericRepo.findByPidOrgAndPidBranchAndStatusOrderByGenericName(orgId, branch(), "Y").stream()
                .map(g -> GenericResponse.builder().id(g.getPidGeneric()).name(g.getGenericName())
                        .description(g.getDescription()).companyId(g.getPosPidCompany())
                        .orgId(g.getPidOrg()).status(g.getStatus()).build()).toList();
    }

    @Override @Transactional
    public GenericResponse createGeneric(GenericRequest req) {
        PosGenericInfo e = PosGenericInfo.builder().pidOrg(getOrgId()).pidBranch(branch()).pidCompany(company())
                .genericName(req.getName()).description(req.getDescription())
                .posPidCompany(req.getCompanyId()).build();
        e.setStatus("Y");
        genericRepo.save(e);
        return GenericResponse.builder().id(e.getPidGeneric()).name(e.getGenericName())
                .description(e.getDescription()).status(e.getStatus()).build();
    }

    @Override @Transactional
    public GenericResponse updateGeneric(Long id, GenericRequest req) {
        PosGenericInfo e = genericRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Generic", id));
        if (req.getName() != null) e.setGenericName(req.getName());
        if (req.getDescription() != null) e.setDescription(req.getDescription());
        if (req.getStatus() != null) e.setStatus(req.getStatus());
        // The manufacturer was never carried through here, so editing it on screen
        // silently did nothing.
        if (req.getCompanyId() != null) e.setPosPidCompany(req.getCompanyId());
        genericRepo.save(e);
        return GenericResponse.builder().id(e.getPidGeneric()).name(e.getGenericName())
                .description(e.getDescription()).companyId(e.getPosPidCompany())
                .orgId(e.getPidOrg()).status(e.getStatus()).build();
    }

    /* ── Product Company ── */

    @Override @Transactional(readOnly = true)
    public List<ProductCompanyResponse> getProductCompanies(Long orgId) {
        return productCompanyRepo.findByPidOrgAndPidBranchAndStatusOrderByCompanyName(orgId, branch(), "Y").stream()
                .map(c -> ProductCompanyResponse.builder().id(c.getPidCompany()).name(c.getCompanyName())
                        .orgId(c.getPidOrg()).mobileNo(c.getMobileNo()).email(c.getEmailAddress())
                        .address(c.getAddress()).status(c.getStatus()).build()).toList();
    }

    @Override @Transactional
    public ProductCompanyResponse createProductCompany(ProductCompanyRequest req) {
        PosCompanyInfo e = PosCompanyInfo.builder().pidOrg(getOrgId()).pidBranch(branch()).companyName(req.getName())
                .mobileNo(req.getMobileNo()).emailAddress(req.getEmail())
                .address(req.getAddress()).build();
        e.setStatus("Y");
        productCompanyRepo.save(e);
        return ProductCompanyResponse.builder().id(e.getPidCompany()).name(e.getCompanyName()).status(e.getStatus()).build();
    }

    @Override @Transactional
    public ProductCompanyResponse updateProductCompany(Long id, ProductCompanyRequest req) {
        PosCompanyInfo e = productCompanyRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Product Company", id));
        if (req.getName()    != null) e.setCompanyName(req.getName());
        if (req.getMobileNo()!= null) e.setMobileNo(req.getMobileNo());
        if (req.getStatus()  != null) e.setStatus(req.getStatus());
        productCompanyRepo.save(e);
        return ProductCompanyResponse.builder().id(e.getPidCompany()).name(e.getCompanyName()).status(e.getStatus()).build();
    }

    /* ── Leaf / Box ── */

    @Override @Transactional(readOnly = true)
    public List<LeafResponse> getLeafs(Long orgId) {
        return leafRepo.findByPidOrgAndPidBranchAndStatusOrderByLeafType(orgId, branch(), "Y").stream()
                .map(l -> LeafResponse.builder().id(l.getPidLeaf()).type(l.getLeafType())
                        .noOfPcs(l.getNoOfPcs()).status(l.getStatus()).build()).toList();
    }

    @Override @Transactional
    public LeafResponse createLeaf(LeafRequest req) {
        PosLeafInfo e = PosLeafInfo.builder().pidOrg(getOrgId()).pidBranch(branch()).pidCompany(company()).leafType(req.getType())
                .noOfPcs(req.getNoOfPcs()).build();
        e.setStatus("Y");
        leafRepo.save(e);
        return LeafResponse.builder().id(e.getPidLeaf()).type(e.getLeafType()).noOfPcs(e.getNoOfPcs()).status(e.getStatus()).build();
    }

    @Override @Transactional(readOnly = true)
    public List<BoxResponse> getBoxes(Long orgId, Long leafId) {
        List<PosBoxInfo> boxes = leafId != null
                ? boxRepo.findByPidLeafAndStatusOrderByBoxSizeTitle(leafId, "Y")
                : boxRepo.findByPidOrgAndPidBranchAndStatusOrderByBoxSizeTitle(orgId, branch(), "Y");
        return boxes.stream().map(b -> {
            PosLeafInfo leaf = b.getPidLeaf() != null ? leafRepo.findById(b.getPidLeaf()).orElse(null) : null;
            Integer pcsPerBox = (leaf != null && b.getNoOfLeaf() != null) ? leaf.getNoOfPcs() * b.getNoOfLeaf() : null;
            return BoxResponse.builder().id(b.getPidBox()).leafId(b.getPidLeaf())
                    .leafType(leaf != null ? leaf.getLeafType() : null).title(b.getBoxSizeTitle())
                    .noOfLeaf(b.getNoOfLeaf()).pcsPerBox(pcsPerBox).status(b.getStatus()).build();
        }).toList();
    }

    @Override @Transactional
    public BoxResponse createBox(BoxRequest req) {
        PosBoxInfo e = PosBoxInfo.builder().pidOrg(getOrgId()).pidBranch(branch()).pidCompany(company()).pidLeaf(req.getLeafId())
                .boxSizeTitle(req.getTitle()).noOfLeaf(req.getNoOfLeaf()).build();
        e.setStatus("Y");
        boxRepo.save(e);
        PosLeafInfo leaf = leafRepo.findById(req.getLeafId()).orElse(null);
        Integer pcsPerBox = (leaf != null) ? leaf.getNoOfPcs() * req.getNoOfLeaf() : null;
        return BoxResponse.builder().id(e.getPidBox()).leafId(e.getPidLeaf()).title(e.getBoxSizeTitle())
                .noOfLeaf(e.getNoOfLeaf()).pcsPerBox(pcsPerBox).status(e.getStatus()).build();
    }

    /* ── Product ──────────────────────────────────────────────────────────
       MOVED to ProductServiceImpl (Product Master, V4). The implementations
       that lived here trusted client-supplied tpPrice/vatPct, generated no
       product code, and enforced no SKU/barcode uniqueness or stock rules.
       ProductMapper and the legacy Product DTOs were deleted with them.
     */


    /* ── Customer ── */

    @Override @Transactional(readOnly = true)
    public PageResponse<CustomerResponse> searchCustomers(Long orgId, Long branchId, String q, Pageable pageable) {
        // One path, always branch-scoped. The search ignored the branch entirely,
        // and the listing fell back to the whole organisation whenever the
        // session carried no branch — the two ways a customer list leaked.
        List<PosCustomerInfo> list = q != null && !q.isBlank()
                ? customerRepo.searchByNameOrPhone(orgId, branch(), q)
                : customerRepo.findByPidOrgAndPidBranchAndStatusOrderByCustomerName(orgId, branch(), "Y");
        List<CustomerResponse> mapped = list.stream().map(this::mapCustomer).toList();
        int start = (int) pageable.getOffset(), end = Math.min(start + pageable.getPageSize(), mapped.size());
        List<CustomerResponse> page = start < mapped.size() ? mapped.subList(start, end) : List.of();
        return PageResponse.from(new PageImpl<>(page, pageable, mapped.size()), page);
    }

    @Override @Transactional(readOnly = true)
    public CustomerResponse getCustomer(Long id) {
        return mapCustomer(customerRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Customer", id)));
    }

    @Override @Transactional
    public CustomerResponse createCustomer(CustomerRequest req) {
        // Blank and absent mean the same thing — no number — and storing "" would
        // make one customer without a phone collide with the next.
        String mobile = blankToNull(req.getMobileNo());
        if (mobile != null && customerRepo.existsByPidBranchAndMobileNo(branch(), mobile))
            throw new BusinessException("A customer with mobile " + mobile + " already exists.", HttpStatus.CONFLICT, "DUPLICATE_MOBILE");
        PosCustomerInfo e = PosCustomerInfo.builder().pidOrg(getOrgId()).pidBranch(branch()).pidCompany(company())
                .pidArea(req.getAreaId()).customerName(req.getCustomerName())
                .customerCode(req.getCustomerCode()).mobileNo(mobile)
                .emailAddress(req.getEmailAddress()).address(req.getAddress()).city(req.getCity())
                .openingBal(req.getOpeningBal()).creditLimit(req.getCreditLimit())
                .customerType(req.getCustomerType()).build();
        e.setStatus("Y");
        customerRepo.save(e);
        return mapCustomer(e);
    }

    @Override @Transactional
    public CustomerResponse updateCustomer(Long id, CustomerRequest req) {
        PosCustomerInfo e = customerRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Customer", id));
        if (req.getCustomerName() != null) e.setCustomerName(req.getCustomerName());
        if (req.getCustomerCode() != null) e.setCustomerCode(req.getCustomerCode());
        if (req.getMobileNo()     != null) {
            String mobile = blankToNull(req.getMobileNo());
            if (mobile != null && customerRepo.existsByPidBranchAndMobileNoAndPidCustomerNot(
                    e.getPidBranch(), mobile, e.getPidCustomer())) {
                throw new BusinessException("A customer with mobile " + mobile + " already exists.",
                        HttpStatus.CONFLICT, "DUPLICATE_MOBILE");
            }
            // An explicit "" clears the number, which is how a wrong one is
            // removed now that having none is allowed.
            e.setMobileNo(mobile);
        }
        if (req.getEmailAddress() != null) e.setEmailAddress(req.getEmailAddress());
        if (req.getAddress()      != null) e.setAddress(req.getAddress());
        if (req.getCity()         != null) e.setCity(req.getCity());
        // Deliberately not honoured: the pharmacy a record belongs to is the
        // session's, and accepting it from the body let a caller move a record
        // into another pharmacy. Kept as a no-op so old clients do not break.
        if (req.getAreaId()       != null) e.setPidArea(req.getAreaId());
        /* [V102] The shadow of an online customer carries a copy of the global
           pricing tier. Editing the copy here would leave it disagreeing with
           the identity it mirrors and with every other pharmacy's copy, so the
           tier is changed in one place — the E-Commerce Customers console —
           and propagated to all of them. Everything else on the row (name,
           area, credit) is still the pharmacy's to keep. */
        if (req.getCustomerType() != null && e.isEcomManaged()
                && !req.getCustomerType().equalsIgnoreCase(e.getCustomerType())) {
            throw new BusinessException(
                    "This customer registered online. Their customer type is set from "
                    + "E-Commerce › E-Commerce Customers, not here.",
                    HttpStatus.CONFLICT, "ECOM_MANAGED_TYPE");
        }
        if (req.getCustomerType() != null) e.setCustomerType(req.getCustomerType());
        if (req.getOpeningBal()   != null) e.setOpeningBal(req.getOpeningBal());
        if (req.getCreditLimit()  != null) e.setCreditLimit(req.getCreditLimit());
        if (req.getStatus()       != null) e.setStatus(req.getStatus());
        customerRepo.save(e);
        return mapCustomer(e);
    }

    /* ── status-only changes ──
       Deactivating is not an update: it must not require the rest of the record,
       and must not let a half-filled form through as one. Customer and Supplier
       already worked this way; these four were calling the update endpoint with
       nothing but a status and failing validation every time. */

    @Override @Transactional
    public void toggleCategoryStatus(Long id) {
        PosProductCategory e = catRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category", id));
        e.setStatus("Y".equals(e.getStatus()) ? "N" : "Y");
        catRepo.save(e);
    }


    @Override @Transactional
    public void toggleSubCategoryStatus(Long id) {
        PosProductSubCategory e = subCatRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sub-Category", id));
        e.setStatus("Y".equals(e.getStatus()) ? "N" : "Y");
        subCatRepo.save(e);
    }


    @Override @Transactional
    public void toggleGenericStatus(Long id) {
        PosGenericInfo e = genericRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Generic", id));
        e.setStatus("Y".equals(e.getStatus()) ? "N" : "Y");
        genericRepo.save(e);
    }


    @Override @Transactional
    public void toggleProductCompanyStatus(Long id) {
        PosCompanyInfo e = productCompanyRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product Company", id));
        e.setStatus("Y".equals(e.getStatus()) ? "N" : "Y");
        productCompanyRepo.save(e);
    }

    @Override @Transactional
    public void toggleCustomerStatus(Long id) {
        PosCustomerInfo e = customerRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Customer", id));
        e.setStatus("Y".equals(e.getStatus()) ? "N" : "Y");
        customerRepo.save(e);
    }

    private CustomerResponse mapCustomer(PosCustomerInfo e) {
        return CustomerResponse.builder().id(e.getPidCustomer()).code(e.getCustomerCode())
                .name(e.getCustomerName()).type(e.getCustomerType())
                .orgId(e.getPidOrg()).branchId(e.getPidBranch()).areaId(e.getPidArea())
                .mobileNo(e.getMobileNo()).email(e.getEmailAddress()).address(e.getAddress())
                .openingBal(e.getOpeningBal()).creditLimit(e.getCreditLimit())
                .status(e.getStatus()).build();
    }

    /* ── Supplier ── */

    @Override @Transactional(readOnly = true)
    public PageResponse<SupplierResponse> searchSuppliers(Long orgId, String q, Pageable pageable) {
        List<PosSupplierInfo> list = q != null && !q.isBlank()
                ? supplierRepo.searchByNameOrPhone(orgId, branch(), q)
                : supplierRepo.findByPidOrgAndPidBranchAndStatusOrderBySupplierName(orgId, branch(), "Y");
        List<SupplierResponse> mapped = list.stream().map(this::mapSupplier).toList();
        int start = (int) pageable.getOffset(), end = Math.min(start + pageable.getPageSize(), mapped.size());
        List<SupplierResponse> page = start < mapped.size() ? mapped.subList(start, end) : List.of();
        return PageResponse.from(new PageImpl<>(page, pageable, mapped.size()), page);
    }

    @Override @Transactional(readOnly = true)
    public SupplierResponse getSupplier(Long id) {
        return mapSupplier(supplierRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Supplier", id)));
    }

    @Override @Transactional
    public SupplierResponse createSupplier(SupplierRequest req) {
        PosSupplierInfo e = PosSupplierInfo.builder().pidOrg(getOrgId()).pidBranch(branch()).pidCompany(company())
                .posPidCompany(req.getCompanyId()).supplierName(req.getSupplierName())
                .mobileNo(req.getMobileNo()).emailAddress(req.getEmailAddress())
                .address(req.getAddress()).responsiblePerson(req.getResponsiblePerson())
                .openingBal(req.getOpeningBal()).creditLimit(req.getCreditLimit()).build();
        e.setStatus("Y");
        supplierRepo.save(e);
        return mapSupplier(e);
    }

    @Override @Transactional
    public SupplierResponse updateSupplier(Long id, SupplierRequest req) {
        PosSupplierInfo e = supplierRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Supplier", id));
        if (req.getSupplierName()      != null) e.setSupplierName(req.getSupplierName());
        // Deliberately not honoured: the pharmacy a record belongs to is the
        // session's, and accepting it from the body let a caller move a record
        // into another pharmacy. Kept as a no-op so old clients do not break.
        if (req.getCompanyId()         != null) e.setPosPidCompany(req.getCompanyId());
        if (req.getMobileNo()          != null) e.setMobileNo(req.getMobileNo());
        if (req.getEmailAddress()      != null) e.setEmailAddress(req.getEmailAddress());
        if (req.getAddress()           != null) e.setAddress(req.getAddress());
        if (req.getResponsiblePerson() != null) e.setResponsiblePerson(req.getResponsiblePerson());
        if (req.getOpeningBal()        != null) e.setOpeningBal(req.getOpeningBal());
        if (req.getCreditLimit()       != null) e.setCreditLimit(req.getCreditLimit());
        if (req.getStatus()            != null) e.setStatus(req.getStatus());
        supplierRepo.save(e);
        return mapSupplier(e);
    }

    @Override @Transactional
    public void toggleSupplierStatus(Long id) {
        PosSupplierInfo e = supplierRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Supplier", id));
        e.setStatus("Y".equals(e.getStatus()) ? "N" : "Y");
        supplierRepo.save(e);
    }

    /**
     * <p>The manufacturer's name is resolved, not just its id. Every screen that
     * shows a supplier wants to say which brand it carries — the field was on
     * the response already and had been coming back null, so each caller either
     * did its own lookup or showed nothing.
     */
    private SupplierResponse mapSupplier(PosSupplierInfo e) {
        String companyName = e.getPosPidCompany() == null ? null
                : productCompanyRepo.findById(e.getPosPidCompany())
                        .map(PosCompanyInfo::getCompanyName).orElse(null);
        return SupplierResponse.builder().id(e.getPidSupplier()).name(e.getSupplierName())
                .orgId(e.getPidOrg()).branchId(e.getPidBranch())
                .companyId(e.getPosPidCompany()).companyName(companyName)
                .mobileNo(e.getMobileNo()).email(e.getEmailAddress()).address(e.getAddress())
                .openingBal(e.getOpeningBal()).creditLimit(e.getCreditLimit())
                .status(e.getStatus()).build();
    }

    /* ── HR ── */

    @Override @Transactional(readOnly = true)
    public List<DepartmentResponse> getDepartments(Long orgId) {
        return deptRepo.findByPidOrgAndPidBranchAndStatusOrderByDepartmentName(orgId, branch(), "Y").stream()
                .map(d -> DepartmentResponse.builder().id(d.getPidDepartment()).name(d.getDepartmentName())
                        .orgId(d.getPidOrg()).status(d.getStatus()).build()).toList();
    }

    @Override @Transactional
    public DepartmentResponse createDepartment(DepartmentRequest req) {
        AllDepartments e = AllDepartments.builder().pidOrg(getOrgId()).pidBranch(branch()).pidCompany(company())
                .departmentName(req.getName()).build();
        e.setStatus("Y");
        deptRepo.save(e);
        return DepartmentResponse.builder().id(e.getPidDepartment()).name(e.getDepartmentName()).status(e.getStatus()).build();
    }

    @Override @Transactional(readOnly = true)
    public List<DesignationResponse> getDesignations(Long orgId) {
        return designRepo.findByPidOrgAndPidBranchAndStatusOrderByDesignationName(orgId, branch(), "Y").stream()
                .map(d -> DesignationResponse.builder().id(d.getPidDesignation()).name(d.getDesignationName())
                        .description(d.getDescription()).orgId(d.getPidOrg()).status(d.getStatus()).build()).toList();
    }

    @Override @Transactional
    public DesignationResponse createDesignation(DesignationRequest req) {
        HrDesignation e = HrDesignation.builder().pidOrg(getOrgId()).pidBranch(branch()).pidCompany(company()).designationName(req.getName())
                .description(req.getDescription()).build();
        e.setStatus("Y");
        designRepo.save(e);
        return DesignationResponse.builder().id(e.getPidDesignation()).name(e.getDesignationName()).status(e.getStatus()).build();
    }

    @Override @Transactional(readOnly = true)
    public PageResponse<EmployeeResponse> searchEmployees(Long orgId, Long branchId, String type, String q, Pageable pageable) {
        // Paged in the database rather than by slicing a fully-materialised
        // list. The old path also routed free-text search through
        // searchDeliveryMen, which despite its name filtered on nothing but org
        // and status — so searching returned active staff while browsing
        // returned the branch, two different populations from one screen.
        String wanted = null;
        if (type != null && !type.isBlank() && !"ALL".equalsIgnoreCase(type)) {
            wanted = EmployeeType.normalise(type);
            // An unrecognised filter must not fall through to "everyone" — that
            // reads as "no such type has any staff", a different fact entirely.
            if (wanted == null) {
                throw new ValidationException("'" + type + "' is not an employee type. Use one of: "
                        + String.join(", ", EmployeeType.ALL) + ".");
            }
        }
        return PageResponse.from(
                empRepo.search(orgId, branch(),
                               wanted == null ? "" : wanted, "",
                               (q == null || q.isBlank()) ? "" : q, pageable)
                       .map(this::mapEmployee));
    }

    @Override @Transactional(readOnly = true)
    public EmployeeResponse getEmployee(Long id) {
        return mapEmployee(empRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Employee", id)));
    }

    @Override @Transactional
    public EmployeeResponse createEmployee(EmployeeRequest req) {
        String type = resolveEmpType(req.getEmpType());
        HrEmployee e = HrEmployee.builder().pidOrg(getOrgId()).pidBranch(branch()).pidCompany(company())
                .pidDepartment(req.getDepartmentId()).pidDesignation(req.getDesignationId())
                .empId(req.getEmpId()).empName(req.getEmpName()).email(req.getEmail())
                .phone(req.getPhone()).gender(req.getGender()).address(req.getAddress())
                .empType(type)
                .vehicleNo(vehicleFor(type, req.getVehicleNo())).build();
        e.setStatus("Y");
        empRepo.save(e);
        return mapEmployee(e);
    }

    @Override @Transactional
    public EmployeeResponse updateEmployee(Long id, EmployeeRequest req) {
        HrEmployee e = empRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Employee", id));
        if (req.getEmpName()   != null) e.setEmpName(req.getEmpName());
        if (req.getPhone()     != null) e.setPhone(req.getPhone());
        if (req.getStatus()    != null) e.setStatus(req.getStatus());
        if (req.getEmpType()   != null) e.setEmpType(resolveEmpType(req.getEmpType()));

        // Re-checked against the type that will actually be stored, whether the
        // type changed, the vehicle changed, or only one of them did. Someone
        // reclassified away from delivery keeps no vehicle behind them.
        e.setVehicleNo(vehicleFor(e.getEmpType(),
                req.getVehicleNo() != null ? req.getVehicleNo() : e.getVehicleNo()));

        empRepo.save(e);
        return mapEmployee(e);
    }

    @Override @Transactional(readOnly = true)
    public List<EmployeeResponse> getDeliveryMen(Long orgId, Long branchId) {
        // Was inferred: anyone holding a vehicle number, or whose designation
        // happened to read "Delivery Man" — against HR_DESIGNATIONS, a table
        // this database does not have. EMP_TYPE now records the fact directly,
        // and this list matches exactly who the assignment check will accept.
        return empRepo.findDeliveryMen(orgId, branch(), "")
                .stream().map(this::mapEmployee).toList();
    }

    /** Blank means OTHER, so clients written before this column keep working. */
    private String resolveEmpType(String raw) {
        if (raw == null || raw.isBlank()) return EmployeeType.OTHER;
        String t = EmployeeType.normalise(raw);
        if (t == null) {
            throw new ValidationException("'" + raw + "' is not an employee type. Use one of: "
                    + String.join(", ", EmployeeType.ALL) + ".");
        }
        return t;
    }

    /**
     * A vehicle number belongs to a delivery man and to nobody else.
     *
     * <p>Enforced both ways. Requiring it makes the delivery list usable —
     * dispatch needs to know which van. Clearing it for other types keeps the
     * column meaning one thing: V12 read a vehicle number as evidence of who
     * drives, and one left on someone reclassified to PHARMACIST would make that
     * reading wrong for anyone who repeats it.
     */
    private String vehicleFor(String type, String vehicleNo) {
        String v = (vehicleNo == null || vehicleNo.isBlank()) ? null : vehicleNo.trim();
        if (EmployeeType.DELIVERY_MAN.equals(type)) {
            if (v == null) throw new ValidationException("A delivery man needs a vehicle number.");
            return v;
        }
        if (v != null) {
            throw new ValidationException("A vehicle number only applies to a delivery man, not a "
                    + EmployeeType.label(type) + ".");
        }
        return null;
    }

    private EmployeeResponse mapEmployee(HrEmployee e) {
        return EmployeeResponse.builder().id(e.getPidEmployee()).empId(e.getEmpId())
                .empName(e.getEmpName()).email(e.getEmail()).phone(e.getPhone())
                .orgId(e.getPidOrg()).branchId(e.getPidBranch())
                .departmentId(e.getPidDepartment()).designationId(e.getPidDesignation())
                .vehicleNo(e.getVehicleNo()).status(e.getStatus())
                .empType(e.getEmpType())
                .empTypeLabel(EmployeeType.label(e.getEmpType()))
                .canDeliver(e.isDeliveryMan() && e.isActive())
                .imagePath(e.getImagePath())
                .initials(e.getInitials())
                .build();
    }

    /* ── Area ── */

    @Override @Transactional(readOnly = true)
    public List<AreaResponse> getAreas(Long branchId) {
        List<PosAreaInfo> list = branchId != null
                ? areaRepo.findByPidBranchAndStatusOrderByAreaName(branchId, "Y")
                : areaRepo.findByStatusOrderByAreaName("Y");
        return list.stream().map(a -> AreaResponse.builder().id(a.getPidArea())
                .name(a.getAreaName()).branchId(a.getPidBranch()).status(a.getStatus()).build()).toList();
    }

    @Override @Transactional
    public AreaResponse createArea(MasterDataRequests.AreaRequest req) {
        PosAreaInfo e = PosAreaInfo.builder()
                .pidBranch(req.getBranchId())
                .pidThana(req.getThanaId())
                .areaName(req.getAreaName())
                .pidEmployee(req.getEmployeeId())
                .build();
        e.setStatus(req.getStatus() != null ? req.getStatus() : "Y");
        areaRepo.save(e);
        return mapArea(e);
    }

    @Override @Transactional
    public AreaResponse updateArea(Long id, MasterDataRequests.AreaRequest req) {
        PosAreaInfo e = areaRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Area", id));
        if (req.getAreaName()   != null) e.setAreaName(req.getAreaName());
        if (req.getBranchId()   != null) e.setPidBranch(req.getBranchId());
        if (req.getThanaId()    != null) e.setPidThana(req.getThanaId());
        if (req.getEmployeeId() != null) e.setPidEmployee(req.getEmployeeId());
        if (req.getStatus()     != null) e.setStatus(req.getStatus());
        areaRepo.save(e);
        return mapArea(e);
    }

    private AreaResponse mapArea(PosAreaInfo a) {
        return AreaResponse.builder().id(a.getPidArea()).name(a.getAreaName())
                .branchId(a.getPidBranch()).status(a.getStatus()).build();
    }

    /* ── Division (GAP-1) ── */

    @Override @Transactional(readOnly = true)
    public List<MasterDataResponses.DivisionResponse> getDivisions(Long regionId) {
        List<AllDivision> list = regionId != null
                ? divisionRepo.findByPidRegionOrderByDivisionName(regionId)
                : divisionRepo.findAll();
        return list.stream().map(this::mapDivision).toList();
    }

    @Override @Transactional(readOnly = true)
    public MasterDataResponses.DivisionResponse getDivisionById(Long id) {
        return mapDivision(divisionRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Division", id)));
    }

    @Override @Transactional
    public MasterDataResponses.DivisionResponse createDivision(MasterDataRequests.DivisionRequest req) {
        // AllDivision uses a manual PK (no sequence) — generate from max+1
        Long nextId = divisionRepo.findAll().stream()
                .mapToLong(AllDivision::getDivisionId).max().orElse(0L) + 1;
        AllDivision e = AllDivision.builder()
                .divisionId(nextId)
                .divisionName(req.getDivisionName())
                .divisionBn(req.getDivisionBn())
                .pidRegion(req.getPidRegion())
                .build();
        divisionRepo.save(e);
        return mapDivision(e);
    }

    @Override @Transactional
    public MasterDataResponses.DivisionResponse updateDivision(Long id, MasterDataRequests.DivisionRequest req) {
        AllDivision e = divisionRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Division", id));
        if (req.getDivisionName() != null) e.setDivisionName(req.getDivisionName());
        if (req.getDivisionBn()   != null) e.setDivisionBn(req.getDivisionBn());
        if (req.getPidRegion()    != null) e.setPidRegion(req.getPidRegion());
        divisionRepo.save(e);
        return mapDivision(e);
    }

    private MasterDataResponses.DivisionResponse mapDivision(AllDivision e) {
        String regionName = null;
        if (e.getPidRegion() != null) {
            regionName = regionRepo.findById(e.getPidRegion())
                    .map(r -> r.getRegionName()).orElse(null);
        }
        return MasterDataResponses.DivisionResponse.builder()
                .id(e.getDivisionId())
                .name(e.getDivisionName())
                .nameBn(e.getDivisionBn())
                .regionId(e.getPidRegion())
                .regionName(regionName)
                .build();
    }

    /* ── District (GAP-2) ── */

    @Override @Transactional(readOnly = true)
    public List<MasterDataResponses.DistrictResponse> getDistricts(Long divisionId) {
        List<AllDistrict> list = divisionId != null
                ? districtRepo.findByDivisionIdOrderByDistrictName(divisionId)
                : districtRepo.findAll();
        return list.stream().map(this::mapDistrict).toList();
    }

    @Override @Transactional(readOnly = true)
    public MasterDataResponses.DistrictResponse getDistrictById(Long id) {
        return mapDistrict(districtRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("District", id)));
    }

    @Override @Transactional
    public MasterDataResponses.DistrictResponse createDistrict(MasterDataRequests.DistrictRequest req) {
        Long nextId = districtRepo.findAll().stream()
                .mapToLong(AllDistrict::getDistrictId).max().orElse(0L) + 1;
        AllDistrict e = AllDistrict.builder()
                .districtId(nextId)
                .districtName(req.getDistrictName())
                .districtBn(req.getDistrictBn())
                .divisionId(req.getDivisionId())
                .build();
        districtRepo.save(e);
        return mapDistrict(e);
    }

    @Override @Transactional
    public MasterDataResponses.DistrictResponse updateDistrict(Long id, MasterDataRequests.DistrictRequest req) {
        AllDistrict e = districtRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("District", id));
        if (req.getDistrictName() != null) e.setDistrictName(req.getDistrictName());
        if (req.getDistrictBn()   != null) e.setDistrictBn(req.getDistrictBn());
        if (req.getDivisionId()   != null) e.setDivisionId(req.getDivisionId());
        districtRepo.save(e);
        return mapDistrict(e);
    }

    private MasterDataResponses.DistrictResponse mapDistrict(AllDistrict e) {
        String divisionName = null;
        if (e.getDivisionId() != null) {
            divisionName = divisionRepo.findById(e.getDivisionId())
                    .map(AllDivision::getDivisionName).orElse(null);
        }
        return MasterDataResponses.DistrictResponse.builder()
                .id(e.getDistrictId())
                .name(e.getDistrictName())
                .nameBn(e.getDistrictBn())
                .divisionId(e.getDivisionId())
                .divisionName(divisionName)
                .build();
    }

    /* ── Thana (GAP-3) ── */

    @Override @Transactional(readOnly = true)
    public List<MasterDataResponses.ThanaResponse> getThanas(Long districtId) {
        List<AllThana> list = districtId != null
                ? thanaRepo.findByDistrictIdOrderByThanaName(districtId)
                : thanaRepo.findAll();
        return list.stream().map(this::mapThana).toList();
    }

    @Override @Transactional(readOnly = true)
    public MasterDataResponses.ThanaResponse getThanaById(Long id) {
        return mapThana(thanaRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Thana", id)));
    }

    @Override @Transactional
    public MasterDataResponses.ThanaResponse createThana(MasterDataRequests.ThanaRequest req) {
        AllThana e = AllThana.builder()
                .districtId(req.getDistrictId())
                .thanaName(req.getThanaName())
                .thanaBn(req.getThanaBn())
                .build();
        thanaRepo.save(e);
        return mapThana(e);
    }

    @Override @Transactional
    public MasterDataResponses.ThanaResponse updateThana(Long id, MasterDataRequests.ThanaRequest req) {
        AllThana e = thanaRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Thana", id));
        if (req.getThanaName()  != null) e.setThanaName(req.getThanaName());
        if (req.getThanaBn()    != null) e.setThanaBn(req.getThanaBn());
        if (req.getDistrictId() != null) e.setDistrictId(req.getDistrictId());
        thanaRepo.save(e);
        return mapThana(e);
    }

    private MasterDataResponses.ThanaResponse mapThana(AllThana e) {
        String districtName = null;
        if (e.getDistrictId() != null) {
            districtName = districtRepo.findById(e.getDistrictId())
                    .map(AllDistrict::getDistrictName).orElse(null);
        }
        return MasterDataResponses.ThanaResponse.builder()
                .id(e.getThanaId())
                .name(e.getThanaName())
                .nameBn(e.getThanaBn())
                .districtId(e.getDistrictId())
                .districtName(districtName)
                .build();
    }

    /* ── Leaf update (GAP-5) ── */

    @Override @Transactional
    public LeafResponse updateLeaf(Long id, MasterDataRequests.LeafUpdateRequest req) {
        PosLeafInfo e = leafRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Leaf", id));
        if (req.getType()    != null) e.setLeafType(req.getType());
        if (req.getNoOfPcs() != null) e.setNoOfPcs(req.getNoOfPcs());
        if (req.getStatus()  != null) e.setStatus(req.getStatus());
        leafRepo.save(e);
        return LeafResponse.builder().id(e.getPidLeaf()).type(e.getLeafType())
                .noOfPcs(e.getNoOfPcs()).orgId(e.getPidOrg()).status(e.getStatus()).build();
    }

    /* ── Box update (GAP-5) ── */

    @Override @Transactional
    public BoxResponse updateBox(Long id, MasterDataRequests.BoxUpdateRequest req) {
        PosBoxInfo e = boxRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Box", id));
        if (req.getLeafId()  != null) e.setPidLeaf(req.getLeafId());
        if (req.getTitle()   != null) e.setBoxSizeTitle(req.getTitle());
        if (req.getNoOfLeaf()!= null) e.setNoOfLeaf(req.getNoOfLeaf());
        if (req.getStatus()  != null) e.setStatus(req.getStatus());
        boxRepo.save(e);
        PosLeafInfo leaf = e.getPidLeaf() != null ? leafRepo.findById(e.getPidLeaf()).orElse(null) : null;
        Integer pcsPerBox = (leaf != null && e.getNoOfLeaf() != null)
                ? leaf.getNoOfPcs() * e.getNoOfLeaf() : null;
        return BoxResponse.builder().id(e.getPidBox()).leafId(e.getPidLeaf())
                .leafType(leaf != null ? leaf.getLeafType() : null)
                .title(e.getBoxSizeTitle()).noOfLeaf(e.getNoOfLeaf())
                .pcsPerBox(pcsPerBox).orgId(e.getPidOrg()).status(e.getStatus()).build();
    }

    /* ── Department update (GAP-6) ── */

    @Override @Transactional
    public DepartmentResponse updateDepartment(Long id, MasterDataRequests.DepartmentUpdateRequest req) {
        AllDepartments e = deptRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Department", id));
        if (req.getName()   != null) e.setDepartmentName(req.getName());
        if (req.getStatus() != null) e.setStatus(req.getStatus());
        deptRepo.save(e);
        return DepartmentResponse.builder().id(e.getPidDepartment()).name(e.getDepartmentName())
                .orgId(e.getPidOrg()).status(e.getStatus()).build();
    }

    /* ── Designation update (GAP-6) ── */

    @Override @Transactional
    public DesignationResponse updateDesignation(Long id, MasterDataRequests.DesignationUpdateRequest req) {
        HrDesignation e = designRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Designation", id));
        if (req.getName()        != null) e.setDesignationName(req.getName());
        if (req.getDescription() != null) e.setDescription(req.getDescription());
        if (req.getStatus()      != null) e.setStatus(req.getStatus());
        designRepo.save(e);
        return DesignationResponse.builder().id(e.getPidDesignation()).name(e.getDesignationName())
                .description(e.getDescription()).orgId(e.getPidOrg()).status(e.getStatus()).build();
    }
}
