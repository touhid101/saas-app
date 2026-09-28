package com.friendspharma.app.service.impl;

import com.friendspharma.app.entity.*;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.exception.custom.ValidationException;
import com.friendspharma.app.repository.*;
import com.friendspharma.app.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.List;

/**
 * A pharmacy signing up, and a Super Admin turning it into a working tenant.
 *
 * <p><b>Registration creates nothing.</b> It writes one row to a queue. No branch,
 * no user, no subscription — because anything a stranger can create by filling in
 * a form is something a stranger can create a thousand of. The password they
 * choose is hashed on arrival, so a pending application never holds a readable
 * one even while it waits.
 *
 * <p><b>Activation is one transaction.</b> The brief asks for a single "Activate
 * Branch" action that does eight things, and the reason to insist on one
 * transaction is not tidiness — it is that a half-provisioned tenant is worse
 * than none. A branch with no subscription cannot log in and cannot be billed; a
 * subscription with no admin account is a customer paying for a door with no key.
 * Either all of it happens or none of it does.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantOnboardingService {

    private final TenantRegistrationRepository  regRepo;
    private final AllBranchRepository           branchRepo;
    private final AllOrganizationRepository     orgRepo;
    private final AllCompanyRepository          companyRepo;
    private final AclUserRepository             userRepo;
    private final AclGroupRepository            roleRepo;
    private final SubPlanRepository             planRepo;
    private final SubTermRepository             termRepo;
    private final SubSubscriptionRepository     subRepo;
    private final SubAuditRepository            auditRepo;
    private final AclPermTemplateRepository     templateRepo;
    private final AclPermTemplateLineRepository templateLineRepo;
    private final AclTenantModuleRepository     tenantModuleRepo;
    private final AclModuleInfoRepository       moduleRepo;
    private final AclUserPermissionRepository   userPermRepo;
    private final PosPermissionsRepository      rolePermRepo;
    private final PosProductUnitRepository      unitRepo;
    private final PosProductCategoryRepository  categoryRepo;
    private final PosCustomerInfoRepository     customerRepo;
    private final PasswordEncoder               passwordEncoder;
    private final TenantStatusService           tenantStatus;
    private final GlobalProductServiceImpl      catalogue;
    private final ProductImageStorageService    productImages;
    /** Accounts: a new pharmacy gets its chart of accounts the moment it exists. */
    private final com.friendspharma.app.service.impl.accounting.AccChartService accChart;

    /* ══════════════════════════════════════════════════════════════════════
       REGISTRATION — reachable without signing in
       ══════════════════════════════════════════════════════════════════════ */

    @Transactional
    public TenantRegistration register(String pharmacyName, String contactName, String contactEmail,
                                       String contactPhone, String address,
                                       String adminUsername, String adminPassword,
                                       String preferredPlanCode, Integer preferredTermMonths) {

        require(pharmacyName, "the pharmacy name");
        require(contactName, "a contact name");
        require(contactPhone, "a contact phone number");
        require(adminUsername, "a username for the administrator");
        if (adminPassword == null || adminPassword.length() < 6) {
            throw new ValidationException("Choose a password of at least six characters.");
        }

        String username = adminUsername.trim();

        // Two checks, because a name can be taken by a live account or by another
        // application still waiting. Saying "that name is taken" either way tells
        // the applicant nothing about who holds it.
        if (userRepo.findByUsername(username).isPresent()
                || regRepo.findByAdminUsernameIgnoreCaseAndRegStatus(username, "PENDING").isPresent()) {
            throw new BusinessException(
                    "That username is already taken. Please choose another.",
                    HttpStatus.CONFLICT, "USERNAME_TAKEN");
        }

        // A preference arrives from a page anybody can open, so it is checked
        // against what is on sale rather than stored as typed. An unknown or
        // withdrawn plan is dropped, not rejected: the applicant does not control
        // the price list, and refusing their whole application because a plan was
        // retired while they filled in the form would lose a customer over
        // something they could not have known.
        String planCode = null;
        if (preferredPlanCode != null && !preferredPlanCode.isBlank()) {
            planCode = planRepo.findByPlanCodeIgnoreCase(preferredPlanCode.trim())
                    .filter(pl -> pl.getIsActive() != null && pl.getIsActive() == 1)
                    .map(SubPlan::getPlanCode)
                    .orElse(null);
        }
        Integer termMonths = null;
        if (preferredTermMonths != null) {
            termMonths = termRepo.findByTermMonths(preferredTermMonths)
                    .filter(t -> t.getIsActive() != null && t.getIsActive() == 1)
                    .map(SubTerm::getTermMonths)
                    .orElse(null);
        }

        TenantRegistration reg = TenantRegistration.builder()
                .preferredPlanCode(planCode)
                .preferredTermMonths(termMonths)
                .pharmacyName(pharmacyName.trim())
                .contactName(contactName.trim())
                .contactEmail(contactEmail == null ? null : contactEmail.trim())
                .contactPhone(contactPhone.trim())
                .address(address == null ? null : address.trim())
                .adminUsername(username)
                // Hashed here, not on approval: the queue must never hold a
                // readable password, however long it sits there.
                .adminPassword(passwordEncoder.encode(adminPassword))
                .regStatus("PENDING")
                .build();
        reg.setStatus("Y");
        regRepo.save(reg);

        log.info("Pharmacy '{}' registered and is awaiting approval.", reg.getPharmacyName());
        return reg;
    }

    @Transactional(readOnly = true)
    public List<TenantRegistration> queue(String status) {
        return status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)
                ? regRepo.findAllByOrderByInsertedDateDesc()
                : regRepo.findByRegStatusOrderByInsertedDateDesc(status.toUpperCase());
    }

    @Transactional(readOnly = true)
    public long pendingCount() { return regRepo.countByRegStatus("PENDING"); }

    /* ══════════════════════════════════════════════════════════════════════
       ACTIVATION — one transaction, eight steps
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * Turns an application into a working pharmacy.
     *
     * <p>In order: the branch, its subscription, the admin account, the module
     * grant from the template, and enough master data that the first screen is
     * not empty. Any failure rolls all of it back.
     */
    @Transactional
    public AllBranch activate(Long registrationId, Long planId, String templateCode,
                              Integer termMonths, Long roleId, Boolean copyCatalogue) {

        TenantRegistration reg = regRepo.findById(registrationId)
                .orElseThrow(() -> new ResourceNotFoundException("Registration", registrationId));

        if (!"PENDING".equals(reg.getRegStatus())) {
            throw new BusinessException(
                    "This registration has already been " + reg.getRegStatus().toLowerCase() + ".",
                    HttpStatus.CONFLICT, "REGISTRATION_NOT_PENDING");
        }

        SubPlan plan = planRepo.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("Plan", planId));

        AclPermTemplate template = templateRepo
                .findByTemplateCodeIgnoreCase(templateCode == null ? plan.getPlanCode() : templateCode)
                .orElseThrow(() -> new ValidationException(
                        "'" + templateCode + "' is not a permission template."));

        AclGroup role = roleId != null
                ? roleRepo.findById(roleId).orElseThrow(() -> new ResourceNotFoundException("Role", roleId))
                : roleRepo.findAll().stream()
                    .filter(g -> "ROLE02".equals(g.getRoleCode())).findFirst()
                    .orElseThrow(() -> new ValidationException(
                            "No Branch Manager role exists to assign the new administrator to."));

        /* ── 1. the organisation ──
           Its own org as well as its own branch. Both filters then apply to every
           query, so a forgotten branch predicate is still contained by the org
           rather than exposing one pharmacy to another. */
        AllOrganization org = AllOrganization.builder()
                .orgName(reg.getPharmacyName())
                .orgShortname(shortName(reg.getPharmacyName()))
                .email(reg.getContactEmail())
                .phone(reg.getContactPhone())
                .companyAddress(reg.getAddress())
                .build();
        org.setStatus("Y");
        orgRepo.save(org);

        /* ── 1b. its company ──
           Activation used to stop at the organisation, leaving ALL_BRANCH.PID_COMPANY
           null on every pharmacy onboarded since. Nothing crashed, because nothing
           checks it — which is why it went unnoticed until somebody looked at the
           column. A branch belongs to a company; giving it one at birth is cheaper
           than discovering later which reports quietly return nothing. */
        AllCompany company = AllCompany.builder()
                .pidOrg(org.getPidOrg())
                .companyName(reg.getPharmacyName())
                .companyShortName(shortName(reg.getPharmacyName()))
                .email(reg.getContactEmail())
                .phone(reg.getContactPhone())
                .companyAddress(reg.getAddress())
                .build();
        company.setStatus("Y");
        companyRepo.save(company);

        accChart.ensureProvisioned(org.getPidOrg());

        /* ── 2. the branch ── */
        AllBranch branch = AllBranch.builder()
                .pidOrg(org.getPidOrg())
                .pidCompany(company.getPidCompany())
                .branchName(reg.getPharmacyName())
                .branchAddress(reg.getAddress())
                .branchEmail(reg.getContactEmail())
                .branchPhoneNo(reg.getContactPhone())
                .tenantStatus("ACTIVE")
                .build();
        branch.setStatus("Y");
        branchRepo.save(branch);
        // Its picture folder, product_image/<branchId>, from the first moment.
        productImages.ensureBranchDirectory(branch.getPidBranch());

        /* ── 3. the subscription ── */
        int months = termMonths != null && termMonths > 0 ? termMonths
                : "YEARLY".equalsIgnoreCase(plan.getBillingTerm()) ? 12 : 1;
        SubSubscription sub = SubSubscription.builder()
                .pidBranch(branch.getPidBranch())
                .pidOrg(org.getPidOrg())
                .pidPlan(plan.getPidPlan())
                .startsOn(LocalDate.now())
                .expiresOn(LocalDate.now().plusMonths(months))
                // Copied, not referenced: editing the plan later must not move the
                // date this pharmacy gets locked out on.
                .graceDays(plan.getGraceDays())
                .subStatus("ACTIVE")
                .build();
        sub.setStatus("Y");
        subRepo.save(sub);

        /* ── 4. the administrator ── */
        AclUser admin = new AclUser();
        admin.setUserName(reg.getAdminUsername());
        admin.setFullName(reg.getContactName());
        admin.setEmail(reg.getContactEmail());
        admin.setPhoneNo(reg.getContactPhone());
        admin.setPidOrg(org.getPidOrg());
        admin.setPidBranch(branch.getPidBranch());
        admin.setPidCompany(company.getPidCompany());
        admin.setPidGroup(role.getPidGroup());
        admin.setIsActive(1);
        // Already hashed when they registered; re-encoding would lock them out of
        // the password they chose.
        admin.setPasswordHash(reg.getAdminPassword());
        admin.setStatus("Y");
        userRepo.save(admin);

        /* ── 5. what the plan includes ── */
        applyTemplate(branch.getPidBranch(), template);

        /* ── 6. enough master data that the first screen is not empty ── */
        seedMasterData(branch.getPidBranch(), org.getPidOrg(), company.getPidCompany());

        /* ── 6b. optionally, the catalogue ──
           Off unless asked for. A pharmacy that wants to type in its own list
           should not have to delete two thousand rows first, and a pharmacy that
           wants the catalogue can be given it here or from the console later. */
        if (Boolean.TRUE.equals(copyCatalogue)) {
            var r = catalogue.importInto(branch.getPidBranch(), null);
            log.info("Seeded {} with {} catalogue product(s).", reg.getPharmacyName(), r.imported());
        }

        /* ── 7. close the queue row ── */
        reg.setRegStatus("APPROVED");
        reg.setPidBranch(branch.getPidBranch());
        reg.setPidUser(admin.getPidUser());
        reg.setReviewedBy(SecurityUtils.getCurrentUsername());
        reg.setReviewedAt(LocalDateTime.now());
        regRepo.save(reg);

        /* ── 8. say what happened ── */
        auditRepo.save(SubAudit.builder()
                .pidBranch(branch.getPidBranch())
                .eventType("ACTIVATED")
                .detail(reg.getPharmacyName() + " activated on " + plan.getPlanName()
                        + " with the " + template.getTemplateName() + " template")
                .newValue("ACTIVE")
                .actedBy(SecurityUtils.getCurrentUsername())
                .actedAt(LocalDateTime.now())
                .build());

        tenantStatus.forget(branch.getPidBranch());
        log.info("Activated '{}' as branch {} under org {} on plan {}",
                 reg.getPharmacyName(), branch.getPidBranch(), org.getPidOrg(), plan.getPlanCode());
        return branch;
    }

    /**
     * Creates a pharmacy directly, without waiting for it to register.
     *
     * <p>The common case in practice: a customer is signed up over the phone or in
     * person, and asking them to go and fill in a web form first is a step that
     * exists only because the software was built registration-first.
     *
     * <p>Deliberately routed through the same registration row and the same
     * {@link #activate} transaction rather than writing the tables directly. A
     * second provisioning path would drift from the first, and the one that drifts
     * is always the one used less often — which would be this one, discovered
     * months later as a tenant with no permissions.
     */
    @Transactional
    public AllBranch createDirectly(String pharmacyName, String contactName, String contactEmail,
                                    String contactPhone, String address,
                                    String adminUsername, String adminPassword,
                                    Long planId, String templateCode, Integer termMonths,
                                    Long roleId, Boolean copyCatalogue) {

        // No preference: nobody applied. The plan and term are being chosen right
        // here by the person creating the pharmacy, and the subscription records
        // them a line later — writing them as a 'preference' too would invent an
        // applicant who never existed.
        TenantRegistration reg = register(pharmacyName, contactName, contactEmail,
                                          contactPhone, address, adminUsername, adminPassword,
                                          null, null);
        return activate(reg.getPidRegistration(), planId, templateCode, termMonths,
                        roleId, copyCatalogue);
    }

    @Transactional
    public void reject(Long registrationId, String reason) {
        TenantRegistration reg = regRepo.findById(registrationId)
                .orElseThrow(() -> new ResourceNotFoundException("Registration", registrationId));
        if (!"PENDING".equals(reg.getRegStatus())) {
            throw new BusinessException("This registration has already been reviewed.",
                    HttpStatus.CONFLICT, "REGISTRATION_NOT_PENDING");
        }
        reg.setRegStatus("REJECTED");
        reg.setRejectReason(reason);
        reg.setReviewedBy(SecurityUtils.getCurrentUsername());
        reg.setReviewedAt(LocalDateTime.now());
        regRepo.save(reg);
    }

    /** Re-applies a template to a branch that already exists. */
    @Transactional
    public int applyTemplate(Long branchId, String templateCode) {
        AclPermTemplate t = templateRepo.findByTemplateCodeIgnoreCase(templateCode)
                .orElseThrow(() -> new ValidationException(
                        "'" + templateCode + "' is not a permission template."));
        int n = applyTemplate(branchId, t);
        tenantStatus.forget(branchId);
        return n;
    }

    /* ══════════════════════════════════════════════════════════════════════
       WHAT ONE PHARMACY'S PLAN INCLUDES
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * Every module, with what this pharmacy has been granted of it.
     *
     * <p>Returns the whole module list rather than only the granted ones, because
     * the screen editing this needs to offer what is missing as much as show what
     * is there.
     *
     * <p>A branch with no grant at all is unrestricted, and that is reported as
     * such rather than as an empty list — the two look identical in the data and
     * mean opposite things.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> modulesFor(Long branchId) {
        List<AclTenantModule> granted = tenantModuleRepo.findByPidBranch(branchId);
        Map<Long, AclTenantModule> byModule = granted.stream()
                .collect(Collectors.toMap(AclTenantModule::getPidModule, g -> g, (a, b) -> a));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (AclModuleInfo m : moduleRepo.findAll()) {
            if (m.getIsActive() == null || m.getIsActive() != 1) continue;
            AclTenantModule g = byModule.get(m.getPidModule());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("moduleId", m.getPidModule());
            row.put("moduleCode", m.getModuleCode());
            row.put("moduleName", m.getModuleName());
            row.put("parentId", m.getPidParentModule());
            row.put("granted", g != null);
            row.put("canView",    g == null ? 1 : g.getCanView());
            row.put("canCreate",  g == null ? 1 : g.getCanCreate());
            row.put("canEdit",    g == null ? 1 : g.getCanEdit());
            row.put("canDelete",  g == null ? 1 : g.getCanDelete());
            row.put("canApprove", g == null ? 1 : g.getCanApprove());
            row.put("canExport",  g == null ? 1 : g.getCanExport());
            row.put("canPrint",   g == null ? 1 : g.getCanPrint());
            rows.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("branchId", branchId);
        out.put("unrestricted", granted.isEmpty());
        out.put("modules", rows);
        return out;
    }

    /**
     * Sets exactly what this pharmacy has.
     *
     * <p>Replaced wholesale rather than merged, for the same reason a template is:
     * this is a statement of what the customer bought, and a module left behind
     * from a previous edit is something given away that nobody chose to give.
     *
     * <p>An empty list means unrestricted — everything the roles allow — not
     * "nothing". Refusing to save an empty grant would leave no way back from a
     * restriction applied by mistake.
     */
    @Transactional
    public int setModules(Long branchId, List<Map<String, Object>> wanted) {
        branchRepo.findById(branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Branch", branchId));

        tenantModuleRepo.deleteByBranch(branchId);
        if (wanted == null || wanted.isEmpty()) {
            tenantStatus.forget(branchId);
            return 0;
        }

        int saved = 0;
        for (Map<String, Object> w : wanted) {
            Long moduleId = w.get("moduleId") == null
                    ? null : Long.valueOf(String.valueOf(w.get("moduleId")));
            if (moduleId == null) continue;

            AclTenantModule g = AclTenantModule.builder()
                    .pidBranch(branchId)
                    .pidModule(moduleId)
                    .canView(flag(w.get("canView")))
                    .canCreate(flag(w.get("canCreate")))
                    .canEdit(flag(w.get("canEdit")))
                    .canDelete(flag(w.get("canDelete")))
                    .canApprove(flag(w.get("canApprove")))
                    .canExport(flag(w.get("canExport")))
                    .canPrint(flag(w.get("canPrint")))
                    .build();
            g.setStatus("Y");
            tenantModuleRepo.save(g);
            saved++;
        }
        tenantStatus.forget(branchId);
        log.info("Branch {} now has {} module(s) granted.", branchId, saved);
        return saved;
    }

    private static Integer flag(Object o) {
        if (o == null) return 0;
        if (o instanceof Boolean b) return b ? 1 : 0;
        return "1".equals(String.valueOf(o)) || "true".equalsIgnoreCase(String.valueOf(o)) ? 1 : 0;
    }

    /* ══════════════════════════════════════════════════════════════════════
       INTERNALS
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * What the sign-up page may show a stranger: the plans on sale and the terms
     * they can be bought for.
     *
     * <p>Sale-facing fields only. Grace days, user caps and sort orders are how
     * the platform is run, not what is being sold, and this endpoint answers
     * without a token — everything it returns is published to the internet.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> publicOptions() {
        List<Map<String, Object>> plans = planRepo.findAll().stream()
                .filter(pl -> pl.getIsActive() != null && pl.getIsActive() == 1)
                .sorted(java.util.Comparator.comparing(SubPlan::getSortOrder,
                        java.util.Comparator.nullsLast(Integer::compareTo)))
                .map(pl -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("planCode", pl.getPlanCode());
                    m.put("planName", pl.getPlanName());
                    m.put("description", pl.getDescription());
                    m.put("price", pl.getPrice());
                    m.put("billingTerm", pl.getBillingTerm());
                    m.put("maxUsers", pl.getMaxUsers());
                    return m;
                }).toList();

        List<Map<String, Object>> terms = termRepo
                .findByIsActiveOrderBySortOrderAscTermMonthsAsc(1).stream()
                .map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("termMonths", t.getTermMonths());
                    m.put("termName", t.getTermName());
                    m.put("description", t.getDescription());
                    m.put("discountPct", t.getDiscountPct());
                    return m;
                }).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plans", plans);
        out.put("terms", terms);
        return out;
    }

    /* ══════════════════════════════════════════════════════════════════════
       EDITING THE TEMPLATES THEMSELVES
       ══════════════════════════════════════════════════════════════════════ */

    /** Every template, retired ones included — the screen editing them shows both. */
    @Transactional(readOnly = true)
    public List<AclPermTemplate> allTemplates() {
        return templateRepo.findAll().stream()
                .sorted(java.util.Comparator.comparing(AclPermTemplate::getSortOrder,
                        java.util.Comparator.nullsLast(Integer::compareTo)))
                .toList();
    }

    /** How many modules each template grants, so the list need not load them all. */
    @Transactional(readOnly = true)
    public Map<Long, Integer> templateLineCounts() {
        Map<Long, Integer> out = new LinkedHashMap<>();
        for (AclPermTemplate t : templateRepo.findAll()) {
            out.put(t.getPidTemplate(), templateLineRepo.findByPidTemplate(t.getPidTemplate()).size());
        }
        return out;
    }

    /** Adds a template, or edits one. An omitted field on an edit keeps what it had. */
    @Transactional
    public AclPermTemplate saveTemplate(Long templateId, String code, String name,
                                        String description, String planCode, Integer sortOrder) {
        boolean editing = templateId != null;

        AclPermTemplate t;
        if (editing) {
            t = templateRepo.findById(templateId)
                    .orElseThrow(() -> new ResourceNotFoundException("Permission template", templateId));
        } else {
            if (code == null || code.isBlank()) {
                throw new ValidationException("A new template needs a code.");
            }
            if (name == null || name.isBlank()) {
                throw new ValidationException("A template needs a name.");
            }
            if (templateRepo.findByTemplateCodeIgnoreCase(code.trim()).isPresent()) {
                throw new BusinessException(
                        "A template with the code '" + code.trim() + "' already exists.",
                        HttpStatus.CONFLICT, "TEMPLATE_CODE_TAKEN");
            }
            t = new AclPermTemplate();
            t.setTemplateCode(code.trim().toUpperCase());
            t.setIsActive(1);
            t.setSortOrder(templateRepo.findAll().size() + 1);
        }

        if (name != null && !name.isBlank()) t.setTemplateName(name.trim());
        if (description != null)             t.setDescription(description);
        if (planCode != null)                t.setPlanCode(planCode.isBlank() ? null : planCode.trim().toUpperCase());
        if (sortOrder != null)               t.setSortOrder(sortOrder);
        t.setStatus("Y");
        return templateRepo.save(t);
    }

    /**
     * Takes a template off the list, or puts it back.
     *
     * <p>A template is copied into a pharmacy's grants at activation and never
     * consulted again, so no branch depends on this row. Retiring exists so a set
     * nobody should pick today can still be recognised when somebody asks what a
     * pharmacy was activated on.
     */
    @Transactional
    public void setTemplateActive(Long templateId, boolean active) {
        AclPermTemplate t = templateRepo.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("Permission template", templateId));

        if (!active && templateRepo.findByIsActiveOrderBySortOrder(1).stream()
                .allMatch(x -> x.getPidTemplate().equals(templateId))) {
            throw new BusinessException(
                    "This is the only template on the list. Add another before retiring it, "
                    + "or no pharmacy can be activated.",
                    HttpStatus.CONFLICT, "LAST_TEMPLATE");
        }
        t.setIsActive(active ? 1 : 0);
        templateRepo.save(t);
    }

    /** Removes a template and the lines under it. */
    @Transactional
    public void deleteTemplate(Long templateId) {
        AclPermTemplate t = templateRepo.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("Permission template", templateId));

        if (templateRepo.count() <= 1) {
            throw new BusinessException(
                    "This is the last template. Add another before deleting it, "
                    + "or no pharmacy can be activated.",
                    HttpStatus.CONFLICT, "LAST_TEMPLATE");
        }
        // The lines go first: nothing cascades, and orphaned lines would be handed
        // to whatever template next reused the id.
        templateLineRepo.deleteAll(templateLineRepo.findByPidTemplate(templateId));
        templateRepo.delete(t);
    }

    /**
     * What one template grants, against the full module list.
     *
     * <p>Every module is returned, not only the granted ones — the screen editing
     * this has to offer what is missing as much as show what is there, which is
     * the same reason {@link #modulesFor(Long)} works that way.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> templateModules(Long templateId) {
        AclPermTemplate t = templateRepo.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("Permission template", templateId));

        Map<Long, AclPermTemplateLine> granted = templateLineRepo.findByPidTemplate(templateId)
                .stream().collect(Collectors.toMap(AclPermTemplateLine::getPidModule, x -> x, (a, b) -> a));

        List<Map<String, Object>> modules = new ArrayList<>();
        for (AclModuleInfo m : moduleRepo.findAll().stream()
                .filter(x -> x.getIsActive() == null || x.getIsActive() == 1)
                .sorted(java.util.Comparator.comparing(AclModuleInfo::getSortOrder,
                        java.util.Comparator.nullsLast(Integer::compareTo)))
                .toList()) {

            AclPermTemplateLine l = granted.get(m.getPidModule());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("moduleId", m.getPidModule());
            row.put("moduleCode", m.getModuleCode());
            row.put("moduleName", m.getModuleName());
            row.put("granted", l != null);
            row.put("canView",    on(l == null ? null : l.getCanView()));
            row.put("canCreate",  on(l == null ? null : l.getCanCreate()));
            row.put("canEdit",    on(l == null ? null : l.getCanEdit()));
            row.put("canDelete",  on(l == null ? null : l.getCanDelete()));
            row.put("canApprove", on(l == null ? null : l.getCanApprove()));
            row.put("canExport",  on(l == null ? null : l.getCanExport()));
            row.put("canPrint",   on(l == null ? null : l.getCanPrint()));
            modules.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("templateId", t.getPidTemplate());
        out.put("templateCode", t.getTemplateCode());
        out.put("templateName", t.getTemplateName());
        out.put("modules", modules);
        return out;
    }

    private static boolean on(Integer flag) { return flag != null && flag == 1; }

    /**
     * Replaces what a template grants.
     *
     * <p>Wholesale, like applying one: a template is a statement of what a
     * pharmacy gets, and merging would leave behind a module nobody chose to keep.
     * Rows arriving with nothing ticked are dropped rather than written as an
     * all-zero line — a module granted with no permission on it is a menu entry
     * that opens onto a permission error.
     *
     * <p>This does not touch a pharmacy already activated on the template. Those
     * grants were copied at activation; re-permissioning a live customer is what
     * {@code POST /branches/{id}/template} is for — deliberately, one at a time.
     */
    @Transactional
    public int saveTemplateModules(Long templateId, List<Map<String, Object>> rows) {
        AclPermTemplate t = templateRepo.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("Permission template", templateId));

        templateLineRepo.deleteAll(templateLineRepo.findByPidTemplate(templateId));

        int written = 0;
        for (Map<String, Object> r : rows == null ? List.<Map<String, Object>>of() : rows) {
            if (r.get("moduleId") == null) continue;
            Long moduleId = Long.valueOf(String.valueOf(r.get("moduleId")));

            int view = flag(r.get("canView")), create = flag(r.get("canCreate")),
                edit = flag(r.get("canEdit")), del = flag(r.get("canDelete")),
                approve = flag(r.get("canApprove")), export = flag(r.get("canExport")),
                print = flag(r.get("canPrint"));

            if (view + create + edit + del + approve + export + print == 0) continue;

            AclPermTemplateLine line = AclPermTemplateLine.builder()
                    .pidTemplate(templateId).pidModule(moduleId)
                    .canView(view).canCreate(create).canEdit(edit).canDelete(del)
                    .canApprove(approve).canExport(export).canPrint(print)
                    .build();
            templateLineRepo.save(line);
            written++;
        }
        log.info("Template {} now grants {} module(s).", t.getTemplateCode(), written);
        return written;
    }

    private int applyTemplate(Long branchId, AclPermTemplate template) {
        // Replaced wholesale rather than merged. A template is a statement of what
        // the pharmacy has, so leaving behind a module from a previous one would
        // quietly give away something nobody chose to give.
        tenantModuleRepo.deleteByBranch(branchId);

        List<AclPermTemplateLine> lines = templateLineRepo.findByPidTemplate(template.getPidTemplate());
        for (AclPermTemplateLine l : lines) {
            AclTenantModule g = AclTenantModule.builder()
                    .pidBranch(branchId)
                    .pidModule(l.getPidModule())
                    .canView(l.getCanView()).canCreate(l.getCanCreate()).canEdit(l.getCanEdit())
                    .canDelete(l.getCanDelete()).canApprove(l.getCanApprove())
                    .canExport(l.getCanExport()).canPrint(l.getCanPrint())
                    .build();
            g.setStatus("Y");
            tenantModuleRepo.save(g);
        }
        return lines.size();
    }

    /**
     * The few rows without which the first screen is unusable.
     *
     * <p>Deliberately small. A new pharmacy should meet an empty product list it
     * fills in itself, not somebody else's catalogue — but it cannot record a
     * single sale without a unit to sell in and a customer to sell to.
     */
    private void seedMasterData(Long branchId, Long orgId, Long companyId) {
        for (String unit : List.of("Piece", "Strip", "Box")) {
            PosProductUnit u = PosProductUnit.builder()
                    .pidOrg(orgId).pidBranch(branchId).pidCompany(companyId)
                    .unitName(unit).build();
            u.setStatus("Y");
            unitRepo.save(u);
        }
        PosProductCategory c = PosProductCategory.builder()
                .pidOrg(orgId).pidBranch(branchId).pidCompany(companyId)
                .categoryName("General").build();
        c.setStatus("Y");
        categoryRepo.save(c);

        // Retailer, because that is what an over-the-counter sale is. The column
        // is NOT NULL and the pharmacy cannot ring up a single sale without a
        // customer to ring it up against.
        PosCustomerInfo walkIn = PosCustomerInfo.builder()
                .pidOrg(orgId).pidBranch(branchId).pidCompany(companyId)
                .customerName("Walk-in Customer").customerType("Retailer")
                .mobileNo("0000000000").build();
        walkIn.setStatus("Y");
        customerRepo.save(walkIn);
    }

    /**
     * Ids are not assigned here.
     *
     * <p>Both AllBranch and AllOrganization already carry @GeneratedValue, so a
     * hand-set id is silently ignored: the row lands under a generated one while
     * every child row written in the same breath points at the id that was
     * thrown away. The branch existed and nothing could find it.
     *
     * <p>So the entity is saved first and its id read back, and everything below
     * is keyed to what the database actually assigned.
     */

    private static String shortName(String name) {
        String s = name.replaceAll("[^A-Za-z0-9 ]", "").trim();
        return s.length() <= 20 ? s : s.substring(0, 20);
    }

    private static void require(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new ValidationException("Please give " + what + ".");
        }
    }

    /* ══════════════════════════════════════════════════════════════════════
       ONE PERSON'S PERMISSIONS
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * What one person in this pharmacy can actually do, module by module.
     *
     * <p>Six numbers per right, because "can they?" has several different answers
     * and conflating them is how a permission screen misleads:
     * <ul>
     *   <li><b>role</b> — what their job grants everyone who holds it;</li>
     *   <li><b>plan</b> — what this pharmacy has by default;</li>
     *   <li><b>fallback</b> — role and plan together: what they get if nobody
     *       decides anything for them;</li>
     *   <li><b>override</b> — what has been set for this person alone, or null
     *       meaning "keep following the default";</li>
     *   <li><b>effective</b> — the answer;</li>
     *   <li><b>ceiling</b> — the role, which an exception can never exceed.</li>
     * </ul>
     *
     * <p><b>The plan is a default, not a ceiling.</b> It decides what everyone at
     * this pharmacy starts with, and a named person can be set above or below it
     * up to what their role allows. Module-level stays absolute: a screen the
     * pharmacy never bought cannot be handed to somebody one at a time.
     *
     * <p>Showing only the effective value would leave somebody unable to tell a
     * right their role never had from one taken away by hand.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> userPermissions(Long branchId, Long userId) {
        AclUser user = userRepo.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        // The pharmacy being managed must be this person's own. Without it the
        // console becomes a way to edit anybody's rights by guessing an id.
        if (!branchId.equals(user.getPidBranch())) {
            throw new ValidationException("That person does not work at this pharmacy.");
        }

        Map<Long, PosPermissions> role = user.getPidGroup() == null ? Map.of()
                : rolePermRepo.findByRole_PidGroup(user.getPidGroup()).stream()
                        .collect(Collectors.toMap(p -> p.getModule().getPidModule(), p -> p, (a, b) -> a));

        List<AclTenantModule> plan = tenantModuleRepo.findByPidBranch(branchId);
        boolean planRestricts = !plan.isEmpty();
        Map<Long, AclTenantModule> byModule = plan.stream()
                .collect(Collectors.toMap(AclTenantModule::getPidModule, g -> g, (a, b) -> a));

        Map<Long, AclUserPermission> overrides = userPermRepo.findByPidUser(userId).stream()
                .collect(Collectors.toMap(AclUserPermission::getPidModule, o -> o, (a, b) -> a));
        /* Headings by name, resolved here. The client groups rows under their
           parent, and the parent is a container that is deliberately not in the
           list — so without this every row would fall into one unnamed group. */
        /* Which modules are headings: the ones something else points at. Computed
           once — asking per row turned this loop into a query per module. */
        java.util.Set<Long> parentModuleIds = moduleRepo.findAll().stream()
                .map(AclModuleInfo::getPidParentModule)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, String> moduleNames = moduleRepo.findAll().stream()
                .collect(Collectors.toMap(AclModuleInfo::getPidModule,
                        AclModuleInfo::getModuleName, (a, b) -> a));


        List<Map<String, Object>> rows = new ArrayList<>();
        for (AclModuleInfo m : moduleRepo.findAll()) {
            if (m.getIsActive() == null || m.getIsActive() != 1) continue;

            /* A heading, not a page: no route behind it and no permission row of
               its own, so there is nothing on it to grant. It reached this list
               when the "role must grant it" filter came out, and arrived on
               screen as a row of checkboxes that could be ticked and would mean
               nothing. It belongs above the rows it names, which is where the
               client puts it using parentId.

               A heading is a route-less module that something else points at. A
               route-less module with nothing beneath it is the other thing: a
               capability such as Notifications, which has no page but is granted
               here like any other. Skipping every route-less row, as this did,
               made those ungrantable — while the role matrix beside it listed
               them, so the two screens disagreed about the same module. */
            if (ModuleShape.isHeading(m.getRoutePath(),
                                      parentModuleIds.contains(m.getPidModule()))) continue;

            AclTenantModule bought = byModule.get(m.getPidModule());
            // The one boundary that is not this screen's to move: a module the
            // pharmacy does not have is commercial, not personal.
            if (planRestricts && bought == null) continue;

            // May be null. A module the role does not grant is still offered,
            // because deciding what one named person may do is the whole purpose
            // of this screen — the role supplies the default, not the limit.
            PosPermissions r = role.get(m.getPidModule());

            AclUserPermission o = overrides.get(m.getPidModule());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("moduleId",   m.getPidModule());
            row.put("moduleCode", m.getModuleCode());
            row.put("moduleName", m.getModuleName());
            row.put("parentId",   m.getPidParentModule());
            row.put("parentName", m.getPidParentModule() == null ? null
                    : moduleNames.get(m.getPidParentModule()));

            for (String right : RIGHTS) {
                Integer roleFlag  = r == null ? Integer.valueOf(0) : roleRight(r, right);
                Integer planFlag  = bought == null ? Integer.valueOf(1) : planRight(bought, right);
                Integer overrideF = o == null ? null : userRight(o, right);

                /* What this person gets if nobody decides anything for them: the
                   role, narrowed by what the pharmacy has. */
                int fallback = Math.min(one(roleFlag), one(planFlag));

                /* An explicit decision about this person wins outright. Role and
                   plan are reported alongside so the screen can say what the
                   answer would have been, but neither caps it. */
                int effective = overrideF == null ? fallback : one(overrideF);

                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("role", one(roleFlag));
                cell.put("plan", one(planFlag));
                cell.put("fallback", fallback);
                cell.put("override", overrideF);
                cell.put("effective", effective);
                row.put(right, cell);
            }
            rows.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("branchId", branchId);
        out.put("userId", userId);
        out.put("userName", user.getUserName());
        out.put("fullName", user.getFullName());
        out.put("roleName", user.getPidGroup() == null ? null
                : roleRepo.findById(user.getPidGroup()).map(AclGroup::getGroupName).orElse(null));
        out.put("modules", rows);
        return out;
    }

    /**
     * Sets what is different about this person.
     *
     * <p>Only differences are stored. A module whose rights all match the role is
     * deleted rather than written as a row of nulls, so the override table stays a
     * list of exceptions — which is what keeps it readable, and what lets a later
     * change to the role still reach everyone who was never singled out.
     *
     * @return how many modules now carry an exception
     */
    @Transactional
    public int setUserPermissions(Long branchId, Long userId, List<Map<String, Object>> rows) {
        AclUser user = userRepo.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        if (!branchId.equals(user.getPidBranch())) {
            throw new ValidationException("That person does not work at this pharmacy.");
        }

        Map<Long, AclUserPermission> existing = userPermRepo.findByPidUser(userId).stream()
                .collect(Collectors.toMap(AclUserPermission::getPidModule, o -> o, (a, b) -> a));

        Map<Long, PosPermissions> role = user.getPidGroup() == null ? Map.of()
                : rolePermRepo.findByRole_PidGroup(user.getPidGroup()).stream()
                        .collect(Collectors.toMap(p -> p.getModule().getPidModule(), p -> p, (a, b) -> a));

        // What the pharmacy bought, so a plan-imposed limit is not mistaken for
        // a decision about this person.
        Map<Long, AclTenantModule> planByModule = tenantModuleRepo.findByPidBranch(branchId).stream()
                .collect(Collectors.toMap(AclTenantModule::getPidModule, g -> g, (a, b) -> a));

        int kept = 0;
        for (Map<String, Object> row : rows == null ? List.<Map<String, Object>>of() : rows) {
            Long moduleId = asLong(row.get("moduleId"));
            if (moduleId == null) continue;

            PosPermissions r = role.get(moduleId);   // may be null: see userPermissions

            AclUserPermission o = existing.get(moduleId);
            AclUserPermission draft = o != null ? o
                    : AclUserPermission.builder().pidUser(userId).pidModule(moduleId).build();

            AclTenantModule bought = planByModule.get(moduleId);

            boolean anyDifference = false;
            for (String right : RIGHTS) {
                Integer asked    = asInt(row.get(right));
                Integer roleFlag = r == null ? Integer.valueOf(0) : roleRight(r, right);
                Integer planFlag = bought == null ? Integer.valueOf(1) : planRight(bought, right);

                /* What this person would get with no exception at all: the role,
                   narrowed by what the pharmacy has. */
                int fallback = Math.min(one(roleFlag), one(planFlag));

                /* Taken as asked. Role and plan set the default this is compared
                   against; neither clamps it. Clamping here would accept a tick,
                   store something else, and show the difference only after a
                   reload — the failure this screen keeps being fixed for. */
                int want = asked == null ? fallback : one(asked);

                /* Stored whenever it differs from that default, in either
                   direction. A value equal to the default is left null so it
                   keeps following the plan when the plan changes. */
                Integer store = want == fallback ? null : Integer.valueOf(want);
                setUserRight(draft, right, store);
                if (store != null) anyDifference = true;
            }

            if (anyDifference) {
                draft.setStatus("Y");
                userPermRepo.save(draft);
                kept++;
            } else if (o != null) {
                userPermRepo.delete(o);
            }
        }

        log.info("Permissions for {} at branch {}: {} module(s) now differ from the role.",
                 user.getUserName(), branchId, kept);
        return kept;
    }

    private static final List<String> RIGHTS =
            List.of("canView", "canCreate", "canEdit", "canDelete", "canApprove", "canExport", "canPrint");

    private static int one(Integer v) { return v != null && v == 1 ? 1 : 0; }

    private static Integer roleRight(PosPermissions p, String right) {
        return switch (right) {
            case "canView"    -> p.getCanView();
            case "canCreate"  -> p.getCanCreate();
            case "canEdit"    -> p.getCanEdit();
            case "canDelete"  -> p.getCanDelete();
            case "canApprove" -> p.getCanApprove();
            case "canExport"  -> p.getCanExport();
            default           -> p.getCanPrint();
        };
    }

    private static Integer planRight(AclTenantModule t, String right) {
        return switch (right) {
            case "canView"    -> t.getCanView();
            case "canCreate"  -> t.getCanCreate();
            case "canEdit"    -> t.getCanEdit();
            case "canDelete"  -> t.getCanDelete();
            case "canApprove" -> t.getCanApprove();
            case "canExport"  -> t.getCanExport();
            default           -> t.getCanPrint();
        };
    }

    private static Integer userRight(AclUserPermission u, String right) {
        return switch (right) {
            case "canView"    -> u.getCanView();
            case "canCreate"  -> u.getCanCreate();
            case "canEdit"    -> u.getCanEdit();
            case "canDelete"  -> u.getCanDelete();
            case "canApprove" -> u.getCanApprove();
            case "canExport"  -> u.getCanExport();
            default           -> u.getCanPrint();
        };
    }

    private static void setUserRight(AclUserPermission u, String right, Integer v) {
        switch (right) {
            case "canView"    -> u.setCanView(v);
            case "canCreate"  -> u.setCanCreate(v);
            case "canEdit"    -> u.setCanEdit(v);
            case "canDelete"  -> u.setCanDelete(v);
            case "canApprove" -> u.setCanApprove(v);
            case "canExport"  -> u.setCanExport(v);
            default           -> u.setCanPrint(v);
        }
    }

    private static Long asLong(Object o) {
        if (o == null) return null;
        try { return Long.valueOf(String.valueOf(o).trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private static Integer asInt(Object o) {
        if (o == null) return null;
        if (o instanceof Boolean b) return b ? 1 : 0;
        try { return Integer.valueOf(String.valueOf(o).trim()); }
        catch (NumberFormatException e) { return null; }
    }


}
