package com.friendspharma.app.service.impl;

import com.friendspharma.app.dto.request.RbacRequest;
import com.friendspharma.app.dto.response.RbacResponse;
import com.friendspharma.app.entity.AclGroup;
import com.friendspharma.app.entity.AclModuleInfo;
import com.friendspharma.app.entity.EmployeeType;
import com.friendspharma.app.entity.HrEmployee;
import com.friendspharma.app.entity.AclUser;
import com.friendspharma.app.entity.PosPermissions;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.*;
import com.friendspharma.app.repository.AllBranchRepository;
import com.friendspharma.app.security.SecurityUtils;
import com.friendspharma.app.service.interfaces.PermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.LinkedHashMap;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PermissionServiceImpl implements PermissionService {

    private final AclModuleInfoRepository  moduleRepo;
    private final PosPermissionsRepository permRepo;
    private final AclGroupRepository       groupRepo;
    private final AclUserRepository        userRepo;
    /** Needed to validate the employee a login is tied to. */
    private final HrEmployeeRepository     employeeRepo;
    private final PasswordEncoder          passwordEncoder;
    private final PermissionResolver       resolver;
    private final BranchScopeService       branchScope;
    private final ImageStorageService      images;
    private final AllBranchRepository      branchRepo;


    // ══════════════════════════════════════════════════════════════════════
    // MODULES
    // ══════════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public RbacResponse.ModuleDto createModule(RbacRequest.CreateModuleRequest req) {
        // Auto-generate moduleCode if not provided
        String moduleCode = req.getModuleCode();
        if (moduleCode == null || moduleCode.isBlank()) {
            moduleCode = generateModuleCode();
        } else {
            // If provided, check for duplicates
            if (moduleRepo.findByModuleCode(moduleCode).isPresent()) {
                throw new BusinessException("Module code '" + moduleCode + "' already exists");
            }
        }
        
        AclModuleInfo m = AclModuleInfo.builder()
                .moduleCode(moduleCode)
                .moduleName(req.getModuleName())
                .moduleDesc(req.getModuleDesc())
                .iconKey(req.getIconKey())
                .routePath(req.getRoutePath())
                .pidParentModule(req.getPidParentModule())
                .sortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0)
                .isActive(1)
                .build();
        return toModuleDto(moduleRepo.save(m), false);
    }
    
    /**
     * Auto-generate module code in format MOD01, MOD02, etc.
     */
    private String generateModuleCode() {
        // Use native query to avoid loading entities into session
        List<String> moduleCodes = moduleRepo.findAllModuleCodes();
        int maxNumber = 0;
        
        for (String code : moduleCodes) {
            if (code != null && code.startsWith("MOD")) {
                try {
                    int num = Integer.parseInt(code.substring(3));
                    if (num > maxNumber) {
                        maxNumber = num;
                    }
                } catch (NumberFormatException ignored) {
                    // Skip non-numeric codes
                }
            }
        }
        
        return String.format("MOD%02d", maxNumber + 1);
    }

    @Override
    @Transactional
    public RbacResponse.ModuleDto updateModule(Long pidModule, RbacRequest.UpdateModuleRequest req) {
        AclModuleInfo m = getModule(pidModule);
        if (req.getModuleName() != null) m.setModuleName(req.getModuleName());
        if (req.getModuleDesc() != null) m.setModuleDesc(req.getModuleDesc());
        if (req.getIconKey()    != null) m.setIconKey(req.getIconKey());
        if (req.getRoutePath()  != null) m.setRoutePath(req.getRoutePath());
        if (req.getSortOrder()  != null) m.setSortOrder(req.getSortOrder());
        if (req.getIsActive()   != null) m.setIsActive(req.getIsActive());

        // A menu group is a row with children and no route, so clearing the
        // route is how one is made. Needs its own flag: a null routePath means
        // "leave it alone" in every other field here.
        if (Boolean.TRUE.equals(req.getClearRoutePath())) m.setRoutePath(null);

        if (Boolean.TRUE.equals(req.getClearParent())) {
            m.setPidParentModule(null);
        } else if (req.getPidParentModule() != null) {
            if (req.getPidParentModule().equals(pidModule)) {
                throw new BusinessException("A module cannot be its own parent.");
            }
            AclModuleInfo parent = getModule(req.getPidParentModule());
            // Two levels only. A grandchild would be stored and then never
            // rendered, which looks like the save silently failing.
            if (parent.getPidParentModule() != null) {
                throw new BusinessException(
                        parent.getModuleName() + " is already inside a menu group. "
                        + "Menus are two levels deep.");
            }
            m.setPidParentModule(parent.getPidModule());
        }

        return toModuleDto(moduleRepo.save(m), false);
    }

    @Override
    @Transactional
    public void deleteModule(Long pidModule) {
        AclModuleInfo module = getModule(pidModule);
        
        // Check if module has child modules
        List<AclModuleInfo> children = moduleRepo.findByPidParentModuleAndIsActiveOrderBySortOrderAsc(pidModule, 1);
        if (!children.isEmpty()) {
            throw new BusinessException("Cannot delete module: it has " + children.size() + " child module(s)");
        }
        
        // Check if module has permissions assigned
        long permCount = permRepo.countByModule_PidModule(pidModule);
        if (permCount > 0) {
            throw new BusinessException("Cannot delete module: " + permCount + " permission(s) are assigned to this module");
        }
        
        permRepo.deleteByModuleId(pidModule);
        moduleRepo.deleteById(pidModule);
    }

    @Override
    public RbacResponse.ModuleDto getModuleById(Long pidModule) {
        return toModuleDto(getModule(pidModule), false);
    }

    @Override
    public List<RbacResponse.ModuleDto> getAllModules(boolean activeOnly) {
        List<AclModuleInfo> all = activeOnly
                ? moduleRepo.findByIsActiveOrderBySortOrderAsc(1)
                : moduleRepo.findAll();
        return all.stream().map(m -> toModuleDto(m, false)).collect(Collectors.toList());
    }

    @Override
    public List<RbacResponse.ModuleDto> getModuleTree(boolean activeOnly) {
        List<AclModuleInfo> roots = activeOnly
                ? moduleRepo.findByPidParentModuleIsNullAndIsActiveOrderBySortOrderAsc(1)
                : moduleRepo.findByPidParentModuleIsNullAndIsActiveOrderBySortOrderAsc(1);

        return roots.stream().map(m -> {
            List<RbacResponse.ModuleDto> children = moduleRepo
                    .findByPidParentModuleAndIsActiveOrderBySortOrderAsc(m.getPidModule(), 1)
                    .stream().map(c -> toModuleDto(c, false)).collect(Collectors.toList());
            RbacResponse.ModuleDto dto = toModuleDto(m, false);
            dto.setChildren(children);
            return dto;
        }).collect(Collectors.toList());
    }

    // ══════════════════════════════════════════════════════════════════════
    // ROLES
    // ══════════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public RbacResponse.RoleDto createRole(RbacRequest.CreateRoleRequest req) {
        // Check for duplicate role name
        if (groupRepo.findByGroupName(req.getGroupName()).isPresent()) {
            throw new BusinessException("Role name '" + req.getGroupName() + "' already exists");
        }
        
        // Auto-generate groupCode if not provided
        String groupCode = req.getGroupCode();
        if (groupCode == null || groupCode.isBlank()) {
            groupCode = generateRoleCode();
        } else {
            // If provided, check for duplicates
            if (groupRepo.findByGroupCode(groupCode).isPresent()) {
                throw new BusinessException("Role code '" + groupCode + "' already exists");
            }
        }
        
        AclGroup g = AclGroup.builder()
                .pidOrg(1L) // Default org
                .groupName(req.getGroupName())
                .groupCode(groupCode)
                .groupDescription(req.getDescription())
                .roleColor(req.getRoleColor())  // Set role color from frontend
                .isActive(1)
                .build();
        return toRoleDto(groupRepo.save(g), false);
    }
    
    /**
     * Auto-generate role code in format ROLE01, ROLE02, etc.
     */
    private String generateRoleCode() {
        // Use native query to avoid loading entities into session
        List<String> roleCodes = groupRepo.findAllRoleCodes();
        int maxNumber = 0;
        
        for (String code : roleCodes) {
            if (code != null && code.startsWith("ROLE")) {
                try {
                    int num = Integer.parseInt(code.substring(4));
                    if (num > maxNumber) {
                        maxNumber = num;
                    }
                } catch (NumberFormatException ignored) {
                    // Skip non-numeric codes
                }
            }
        }
        
        return String.format("ROLE%02d", maxNumber + 1);
    }

    @Override
    @Transactional
    public RbacResponse.RoleDto updateRole(Long pidGroup, RbacRequest.UpdateRoleRequest req) {
        AclGroup g = getGroup(pidGroup);
        if (req.getGroupName()  != null) g.setGroupName(req.getGroupName());
        if (req.getDescription() != null) g.setGroupDescription(req.getDescription());
        if (req.getRoleColor()   != null) g.setRoleColor(req.getRoleColor());
        if (req.getIsActive()   != null) g.setIsActive(req.getIsActive());
        return toRoleDto(groupRepo.save(g), false);
    }

    @Override
    @Transactional
    public void deleteRole(Long pidGroup) {
        AclGroup role = getGroup(pidGroup);
        
        // Prevent deletion of system roles
        if ("ROLE01".equals(role.getGroupCode()) || "Super Admin".equalsIgnoreCase(role.getGroupName())) {
            throw new BusinessException("Cannot delete system role: " + role.getGroupName());
        }
        
        // Check if role is assigned to any users
        long userCount = userRepo.countByPidRole(pidGroup);
        if (userCount > 0) {
            throw new BusinessException("Cannot delete role: " + userCount + " user(s) are assigned to this role");
        }
        
        permRepo.deleteByRoleId(pidGroup);
        groupRepo.deleteById(pidGroup);
    }

    @Override
    public RbacResponse.RoleDto getRoleById(Long pidGroup) {
        AclGroup g = getGroup(pidGroup);
        List<RbacResponse.PermissionDto> perms = permRepo.findByRole_PidGroup(pidGroup)
                .stream().map(this::toPermDto).collect(Collectors.toList());
        RbacResponse.RoleDto dto = toRoleDto(g, false);
        dto.setPermissions(perms);
        return dto;
    }

    @Override
    public List<RbacResponse.RoleDto> getAllRoles(boolean withPermissions) {
        return groupRepo.findAll().stream().map(g -> {
            RbacResponse.RoleDto dto = toRoleDto(g, false);
            if (withPermissions) {
                dto.setPermissions(permRepo.findByRole_PidGroup(g.getPidGroup())
                        .stream().map(this::toPermDto).collect(Collectors.toList()));
            }
            return dto;
        }).collect(Collectors.toList());
    }

    // ══════════════════════════════════════════════════════════════════════
    // PERMISSIONS
    // ══════════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public List<RbacResponse.PermissionDto> setRolePermissions(Long pidGroup,
            RbacRequest.SetRolePermissionsRequest req) {

        AclGroup role = getGroup(pidGroup);
        permRepo.deleteByRoleId(pidGroup);

        List<PosPermissions> saved = new ArrayList<>();
        for (RbacRequest.PermissionEntry e : req.getPermissions()) {
            AclModuleInfo module = getModule(e.getPidModule());
            PosPermissions perm = PosPermissions.builder()
                    .role(role).module(module)
                    .canView(e.getCanView()).canCreate(e.getCanCreate())
                    .canEdit(e.getCanEdit()).canDelete(e.getCanDelete())
                    .canExport(e.getCanExport()).canApprove(e.getCanApprove())
                    .build();
            saved.add(permRepo.save(perm));
        }
        return saved.stream().map(this::toPermDto).collect(Collectors.toList());
    }

    @Override
    public List<RbacResponse.PermissionDto> getRolePermissions(Long pidGroup) {
        return permRepo.findByRole_PidGroup(pidGroup)
                .stream().map(this::toPermDto).collect(Collectors.toList());
    }

    // ══════════════════════════════════════════════════════════════════════
    // USERS
    // ══════════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public RbacResponse.UserRbacDto createUser(RbacRequest.CreateUserRequest req) {
        if (userRepo.findByUsername(req.getUsername()).isPresent()) {
            throw new BusinessException("Username '" + req.getUsername() + "' is already taken");
        }
        AclGroup group = getGroup(req.getPidGroup());
        AclUser u = new AclUser();
        u.setUserName(req.getUsername());
        u.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        u.setFullName(req.getFullName());
        u.setEmail(req.getEmail());
        u.setMobileNo(req.getPhoneNo());
        u.setPidRole(group.getPidGroup());
        // The organisation is the caller's, never the one in the payload.
        u.setPidOrg(SecurityUtils.getCurrentOrgId());
        // Checked against who is asking: an administrator may place a user at
        // any branch of the organisation, a branch manager only at their own.
        // Anything else is a 404, so guessing branch numbers tells you nothing.
        Long branchId = branchScope.resolve(req.getPidBranch());
        refuseSuperAdminOffPlatformBranch(group.getPidGroup(), branchId);
        u.setPidBranch(branchId);
        // From the branch, not the request. It has to agree with the branch
        // anyway, and a company in a payload is a company the caller can change.
        // It was simply never set, so every user created through the API had a
        // null company — invisible until somebody read the column.
        u.setPidCompany(companyOfBranch(branchId));
        u.setAvatarUrl(req.getAvatarUrl());
        u.setStatus("Y");
        applyEmployeeLink(u, req.getPidEmployee(), group.getRoleCode());
        return toUserDto(userRepo.save(u));
    }

    @Override
    @Transactional
    public RbacResponse.UserRbacDto updateUser(Long pidUser, RbacRequest.UpdateUserRequest req) {
        AclUser u = getUser(pidUser);
        if (req.getFullName() != null) u.setFullName(req.getFullName());
        if (req.getEmail()    != null) u.setEmail(req.getEmail());
        if (req.getPhoneNo()  != null) u.setMobileNo(req.getPhoneNo());
        if (req.getPidGroup() != null) {
            getGroup(req.getPidGroup()); // validate
            u.setPidRole(req.getPidGroup());
        }
        if (req.getIsActive()  != null) u.setIsActive(req.getIsActive());
        if (req.getAvatarUrl() != null) u.setAvatarUrl(req.getAvatarUrl());
        // Moving somebody between branches changes everything they can see, so
        // it goes through the same check as creating them there would.
        if (req.getPidBranch() != null) {
            Long moved = branchScope.resolve(req.getPidBranch());
            u.setPidBranch(moved);
            // Moving somebody between pharmacies moves their company with them,
            // or the two disagree from the moment the branch changes.
            u.setPidCompany(companyOfBranch(moved));
        }

        // Re-checked against the role that will actually be stored, so changing
        // either the role or the link alone still lands somewhere valid.
        if (req.getPidEmployee() != null || req.getPidGroup() != null) {
            Long link = req.getPidEmployee() != null ? req.getPidEmployee() : u.getPidEmployee();
            applyEmployeeLink(u, link, getGroup(u.getPidRole()).getRoleCode());
        }

        /* Against what will be stored, not against what arrived. Either field
           alone is harmless and the pair is what breaks the rule, so checking
           the request would leave two ways round it: give somebody at another
           pharmacy the role, or give somebody at the platform the role and then
           move them. Both end at the same place, and both are refused here. */
        refuseSuperAdminOffPlatformBranch(u.getPidRole(), u.getPidBranch());

        return toUserDto(userRepo.save(u));
    }

    /** The platform's own role. Not a job in a pharmacy. */
    private static final String SUPER_ADMIN = "ROLE01";

    /**
     * Super Admin belongs to the platform's branch and nowhere else.
     *
     * <p>ROLE01 is not a senior version of Branch Administrator — it is the
     * platform's own role, and
     * {@link com.friendspharma.app.security.UserPrincipal#getAuthorities()} grants
     * everyone holding it every role code there is. Given to a user at a
     * customer pharmacy it passes every {@code @PreAuthorize} in the
     * application; only the organisation check in
     * {@link BranchScopeService#isPlatformOperator()} then stands between them
     * and somebody else's trade, and that is one wall where there should be two.
     *
     * <p>Which branch counts is {@code app.platform.branch-id}, default 1 — the
     * same reasoning as the organisation beside it. The rule is on the user's
     * own branch rather than on who is asking, because that is the thing that
     * has to stay true: however the account was made, and by whom, a Super
     * Admin sitting at another pharmacy is the state to prevent.
     *
     * <p>Nothing existing is disturbed until it is edited, and then only if it
     * already breaks the rule. Both accounts holding ROLE01 are at branch 1.
     */
    private void refuseSuperAdminOffPlatformBranch(Long pidRole, Long branchId) {
        if (pidRole == null) return;
        if (!SUPER_ADMIN.equals(getGroup(pidRole).getRoleCode())) return;
        if (branchScope.isPlatformBranch(branchId)) return;

        throw new BusinessException(
                "Super Admin belongs to the platform and can only be held by a user of branch "
                + branchScope.platformBranchId() + ". Choose a role for this pharmacy instead.",
                HttpStatus.FORBIDDEN, "SUPER_ADMIN_OFF_PLATFORM_BRANCH");
    }

    /**
     * An administrator setting someone else's photo.
     *
     * <p>Shares {@link ImageStorageService} with the self-service route, which
     * is the point: this one trusted the filename extension and checked nothing
     * else, while the self-service route wrote base64 into a VARCHAR(500) and
     * failed every time. Two implementations of one idea, disagreeing about both
     * where the image goes and whether it is really an image.
     */
    @Override
    @Transactional
    public RbacResponse.UserRbacDto uploadUserAvatar(Long pidUser, MultipartFile file) {
        AclUser u = getUser(pidUser);

        String previous = u.getAvatarUrl();
        String url = images.store(file, ImageStorageService.Bucket.AVATAR, pidUser);

        u.setAvatarUrl(url);
        RbacResponse.UserRbacDto dto = toUserDto(userRepo.save(u));

        // Only after the new file is safely written.
        if (previous != null && !previous.equals(url)) {
            images.delete(ImageStorageService.Bucket.AVATAR, previous);
        }
        return dto;
    }

    @Override
    @Transactional
    public void changePassword(Long pidUser, RbacRequest.ChangePasswordRequest req) {
        AclUser u = getUser(pidUser);

        Long callerId  = SecurityUtils.getCurrentUserId();
        boolean isSelf = callerId != null && callerId.equals(pidUser);
        boolean isAdmin = SecurityUtils.isSuperAdmin()
                || SecurityUtils.hasRole("ROLE01")
                || SecurityUtils.hasRole("ROLE02");

        // A non-admin may only ever change their own password.
        if (!isSelf && !isAdmin) {
            throw new BusinessException(
                    "You are not allowed to change another user's password.",
                    HttpStatus.FORBIDDEN, "PASSWORD_CHANGE_FORBIDDEN");
        }

        // The stored credential: password_hash when set, else the legacy pin_no.
        String stored = u.getPasswordHash() != null ? u.getPasswordHash() : u.getPinNo();

        // Changing your own password always requires proving the current one.
        // An admin resetting someone else's password does not.
        if (isSelf) {
            if (req.getOldPassword() == null || req.getOldPassword().isBlank()) {
                throw new BusinessException("Current password is required.",
                        HttpStatus.BAD_REQUEST, "CURRENT_PASSWORD_REQUIRED");
            }
            if (stored == null || !passwordEncoder.matches(req.getOldPassword(), stored)) {
                throw new BusinessException("Current password is incorrect.",
                        HttpStatus.BAD_REQUEST, "CURRENT_PASSWORD_MISMATCH");
            }
        }

        // The new password must actually be different from the one in use.
        if (stored != null && passwordEncoder.matches(req.getNewPassword(), stored)) {
            throw new BusinessException(
                    "New password must be different from your current password.",
                    HttpStatus.BAD_REQUEST, "PASSWORD_REUSED");
        }

        u.setPasswordHash(passwordEncoder.encode(req.getNewPassword()));
        // Retire the legacy PIN so the previous credential can no longer authenticate
        // through the pin_no fallback in CustomUserDetailsService.
        u.setPinNo(null);
        u.setPasswordChangedAt(java.time.LocalDateTime.now());

        /* [V71] The forced change clears only when the user sets it themselves.
           That is the whole condition being enforced — the account is held to
           this screen until it holds a password nobody else has seen — and
           isSelf is exactly the moment that becomes true.

           An administrator setting a password for somebody else deliberately
           does not clear it. They have just chosen a password on that user's
           behalf, which is the same situation the flag exists to describe, so
           an account mid-reset stays mid-reset. */
        if (isSelf) {
            u.setMustChangePassword("N");
            u.setTempPasswordExpires(null);
            u.setPasswordResetBy(null);
        }

        userRepo.save(u);
        log.info("Password changed for user {} by caller {}{}", pidUser, callerId,
                isSelf ? "" : " (set on their behalf; the forced-change flag is unchanged)");
    }

    @Override
    @Transactional
    public void toggleUserActive(Long pidUser, boolean active) {
        AclUser u = getUser(pidUser);
        u.setIsActive(active ? 1 : 0);
        userRepo.save(u);
    }

    @Override
    public RbacResponse.UserRbacDto getUserById(Long pidUser) {
        return toUserDto(getUser(pidUser));
    }

    @Override
    public List<RbacResponse.UserRbacDto> getAllUsers(Long orgId) {
        // The signed-in pharmacy, not the whole organisation. A branch manager
        // listing users was shown every account on the estate, platform
        // administrators included — the usernames worth having before you start
        // guessing passwords.
        //
        // A platform operator acting as a pharmacy resolves to that pharmacy, so
        // the support console sees the right people through the same call.
        return userRepo.findByPidOrgAndPidBranch(orgId, branchScope.resolve(null))
                .stream().map(this::toUserDto).collect(Collectors.toList());
    }

    // ══════════════════════════════════════════════════════════════════════
    // Menu builder
    // ══════════════════════════════════════════════════════════════════════

    @Override
    public List<RbacResponse.MenuItemDto> buildMenuForUser(Long pidUser) {
        AclUser user = getUser(pidUser);
        if (user.getPidGroup() == null) return Collections.emptyList();

        // Three layers, not one: the role, what the pharmacy's plan includes, and
        // the person's own exceptions. Resolved in PermissionResolver so the menu
        // and the API cannot come to different conclusions about the same user.
        Map<Long, RbacResponse.PermissionDto> permMap = new java.util.HashMap<>();
        resolver.resolve(user).forEach((moduleId, r) -> permMap.put(moduleId,
                RbacResponse.PermissionDto.builder()
                        .pidModule(moduleId)
                        .canView(r.view()).canCreate(r.create()).canEdit(r.edit())
                        .canDelete(r.delete()).canApprove(r.approve()).canExport(r.export())
                        .canPrint(r.print())
                        .build()));

        // Build top-level items
        List<AclModuleInfo> roots = moduleRepo
                .findByPidParentModuleIsNullAndIsActiveOrderBySortOrderAsc(1);

        List<RbacResponse.MenuItemDto> menu = new ArrayList<>();
        for (AclModuleInfo root : roots) {
            List<AclModuleInfo> childModules = moduleRepo
                    .findByPidParentModuleAndIsActiveOrderBySortOrderAsc(root.getPidModule(), 1);

            List<RbacResponse.MenuItemDto> children = new ArrayList<>();
            for (AclModuleInfo child : childModules) {
                RbacResponse.PermissionDto cp = permMap.get(child.getPidModule());
                if (cp != null && cp.getCanView() == 1) {
                    children.add(toMenuItem(child, cp));
                }
            }

            RbacResponse.PermissionDto perm = permMap.get(root.getPidModule());

            // A container is a heading, not a page: it has children and no route
            // of its own. Its visibility follows from whether the user can see
            // anything inside it, so it needs no permission row.
            //
            // Requiring one would mean every new child also needed its parent
            // granted to each role — and forgetting that hides a page the user
            // is entitled to, with nothing on screen to say why.
            boolean hasChildren = !childModules.isEmpty();

            // A capability is granted in RBAC but has nowhere to navigate to, so
            // a menu entry for it would be a link to a page that does not exist.
            if (ModuleShape.isCapability(root.getRoutePath(), hasChildren)) continue;

            boolean isContainer = ModuleShape.isHeading(root.getRoutePath(), hasChildren);

            if (isContainer) {
                if (children.isEmpty()) continue;          // nothing inside they may see
            } else if (perm == null || perm.getCanView() == 0) {
                continue;
            }

            RbacResponse.MenuItemDto item = toMenuItem(root, perm);
            item.setChildren(children);
            menu.add(item);
        }
        return menu;
    }

    /**
     * The capabilities this user holds.
     *
     * <p>The complement of {@link #buildMenuForUser}: that returns what a person
     * can navigate to, this returns what they can do that has no page —
     * Notifications, Global Search, and whatever comes next.
     *
     * <p>It exists because the client cannot work these out for itself. Every
     * other permission reaches the browser through the menu, and capabilities
     * are deliberately absent from it, so without this the topbar would have to
     * probe an endpoint per feature to discover whether to draw a button.
     *
     * <p>Returns module codes rather than a richer shape because View is the
     * only right a capability has: there is nothing to create or edit on a
     * search box.
     */
    @Override
    public List<String> capabilitiesForUser(Long pidUser) {
        AclUser user = getUser(pidUser);
        if (user.getPidGroup() == null) return List.of();

        Set<Long> parents = moduleRepo.findAll().stream()
                .map(AclModuleInfo::getPidParentModule)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, PermissionResolver.Rights> resolved = resolver.resolve(user);

        return moduleRepo.findAll().stream()
                .filter(m -> m.getIsActive() != null && m.getIsActive() == 1)
                .filter(m -> ModuleShape.isCapability(m.getRoutePath(),
                                                      parents.contains(m.getPidModule())))
                .filter(m -> {
                    var r = resolved.get(m.getPidModule());
                    return r != null && r.view() == 1;
                })
                .map(AclModuleInfo::getModuleCode)
                .sorted()
                .toList();
    }
    // ── Helpers ────────────────────────────────────────────────────────────

    private AclModuleInfo getModule(Long id) {
        return moduleRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Module", id));
    }
    private AclGroup getGroup(Long id) {
        return groupRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Role", id));
    }
    /** The company a branch belongs to, or null where the branch has none. */
    private Long companyOfBranch(Long branchId) {
        if (branchId == null) return null;
        return branchRepo.findById(branchId).map(b -> b.getPidCompany()).orElse(null);
    }

    /**
     * One user, and only if the caller is entitled to them.
     *
     * <p>It used to be {@code findById} and nothing else, while every write that
     * takes a user id — rename, change role, move branch, activate, deactivate,
     * reset password — came through here. A branch manager could therefore pass
     * any id on the estate: renaming the platform's Super Admin and switching it
     * off both returned 200. The list beside it was scoped all along, so the ids
     * were the only thing anyone had to guess, and they are small integers.
     *
     * <p>The branch is put through {@link BranchScopeService#resolve(Long)}, the
     * same rule the rest of the application is scoped by: an administrator may
     * reach any branch in the organisation, a branch manager only their own, and
     * anything else is a 404 rather than a 403 — so probing ids tells you
     * nothing about who exists.
     */
    private AclUser getUser(Long id) {
        AclUser u = userRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));

        // A user with no branch belongs to nobody's pharmacy; only somebody who
        // may read across branches has any business with them.
        if (u.getPidBranch() == null) {
            if (!branchScope.isAdmin()) throw new ResourceNotFoundException("User", id);
            return u;
        }
        Long allowed = branchScope.resolve(u.getPidBranch());
        if (!allowed.equals(u.getPidBranch())) throw new ResourceNotFoundException("User", id);
        return u;
    }

    private RbacResponse.ModuleDto toModuleDto(AclModuleInfo m, boolean withChildren) {
        return RbacResponse.ModuleDto.builder()
                .pidModule(m.getPidModule()).moduleCode(m.getModuleCode())
                .moduleName(m.getModuleName()).moduleDesc(m.getModuleDesc())
                .iconKey(m.getIconKey()).routePath(m.getRoutePath())
                .pidParentModule(m.getPidParentModule())
                .sortOrder(m.getSortOrder()).isActive(m.getIsActive())
                .children(withChildren ? new ArrayList<>() : null)
                // So the Modules list can say, without opening anything, that a
                // page no role holds is a page nobody can reach.
                .roleCount(permRepo.countByModule_PidModule(m.getPidModule()))
                .build();
    }

    private RbacResponse.PermissionDto toPermDto(PosPermissions p) {
        return RbacResponse.PermissionDto.builder()
                .pidPermission(p.getPidPermission())
                .pidRole(p.getRole().getPidGroup()).roleName(p.getRole().getGroupName())
                .pidModule(p.getModule().getPidModule()).moduleName(p.getModule().getModuleName())
                .moduleCode(p.getModule().getModuleCode()).iconKey(p.getModule().getIconKey())
                .canView(p.getCanView()).canCreate(p.getCanCreate()).canEdit(p.getCanEdit())
                .canDelete(p.getCanDelete()).canExport(p.getCanExport()).canApprove(p.getCanApprove())
                .build();
    }

    private RbacResponse.RoleDto toRoleDto(AclGroup g, boolean withPerms) {
        return RbacResponse.RoleDto.builder()
                .pidGroup(g.getPidGroup()).groupName(g.getGroupName())
                .groupCode(g.getGroupCode()).description(g.getGroupDescription())
                .roleColor(g.getRoleColor())
                .isActive(g.getIsActive())
                .createdAt(g.getCreatedAt()).permissions(withPerms ? new ArrayList<>() : null)
                .build();
    }

    /**
     * Ties a login to a staff record, refusing the links that would mislead.
     *
     * <p>Three rules, each protecting something specific:
     *
     * <ul>
     *   <li><b>Delivery staff must be linked.</b> An unlinked ROLE06 account can
     *       be signed into but matches no invoice, so the driver sees an empty
     *       round and reasonably concludes there is no work.
     *   <li><b>The employee must be a delivery man.</b> Pointing a driver's login
     *       at the accountant's record would hand them the accountant's identity
     *       for every delivery the system knows about.
     *   <li><b>One login per employee.</b> Two accounts on the same driver would
     *       each see the other's rounds as their own and could both collect
     *       against the same invoice. The database enforces this too; catching it
     *       here turns a constraint violation into a sentence.
     * </ul>
     */
    private void applyEmployeeLink(AclUser user, Long employeeId, String roleCode) {
        boolean isDeliveryRole = "ROLE06".equals(roleCode);

        if (employeeId == null) {
            if (isDeliveryRole) {
                throw new BusinessException(
                        "Delivery staff must be linked to an employee record, or their deliveries "
                        + "cannot be identified. Pick the staff member this login belongs to.");
            }
            user.setPidEmployee(null);
            return;
        }

        HrEmployee emp = employeeRepo.findById(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee", employeeId));

        if (isDeliveryRole && !emp.isDeliveryMan()) {
            throw new BusinessException(
                    emp.getEmpName() + " is a " + EmployeeType.label(emp.getEmpType())
                    + ", not a delivery man. Change their type in Master Data › Employees "
                    + "before giving them a delivery login.");
        }

        userRepo.findByPidEmployee(employeeId)
                .filter(other -> !other.getPidUser().equals(user.getPidUser()))
                .ifPresent(other -> {
                    throw new BusinessException(
                            emp.getEmpName() + " is already linked to the login '"
                            + other.getUsername() + "'.");
                });

        user.setPidEmployee(employeeId);
    }

    private RbacResponse.UserRbacDto toUserDto(AclUser u) {
        String groupName = null;
        List<RbacResponse.PermissionDto> perms = Collections.emptyList();
        if (u.getPidGroup() != null) {
            groupName = groupRepo.findById(u.getPidGroup()).map(AclGroup::getGroupName).orElse(null);
            perms = permRepo.findByRole_PidGroup(u.getPidGroup())
                    .stream().map(this::toPermDto).collect(Collectors.toList());
        }
        HrEmployee emp = u.getPidEmployee() == null ? null
                : employeeRepo.findById(u.getPidEmployee()).orElse(null);

        return RbacResponse.UserRbacDto.builder()
                .pidUser(u.getPidUser()).username(u.getUsername())
                .fullName(u.getFullName()).email(u.getEmail())
                .avatarUrl(u.getAvatarUrl())
                .pidGroup(u.getPidGroup()).groupName(groupName)
                .pidBranch(u.getPidBranch())
                .branchName(u.getPidBranch() == null ? null
                        : branchRepo.findById(u.getPidBranch())
                                .map(b -> b.getBranchName()).orElse(null))
                .isActive(u.getIsActive()).createdAt(u.getCreatedAt())
                .pidEmployee(u.getPidEmployee())
                .employeeName(emp != null ? emp.getEmpName() : null)
                .employeeType(emp != null ? emp.getEmpType() : null)
                .permissions(perms)
                .build();
    }

    /**
     * One menu entry.
     *
     * <p>{@code p} is null for a container — a heading with children and no page
     * behind it, which carries no permission row of its own. It is reported as
     * viewable but grants nothing else, because there is nothing to grant: a
     * heading cannot be created, edited or exported.
     */
    private RbacResponse.MenuItemDto toMenuItem(AclModuleInfo m, RbacResponse.PermissionDto p) {
        boolean has = p != null;
        return RbacResponse.MenuItemDto.builder()
                .pidModule(m.getPidModule()).moduleCode(m.getModuleCode())
                .moduleName(m.getModuleName()).iconKey(m.getIconKey())
                .routePath(m.getRoutePath()).sortOrder(m.getSortOrder())
                .canView(!has || p.getCanView() == 1)
                .canCreate(has && p.getCanCreate() == 1)
                .canEdit(has && p.getCanEdit() == 1)
                .canDelete(has && p.getCanDelete() == 1)
                .canExport(has && p.getCanExport() == 1)
                .canApprove(has && p.getCanApprove() == 1)
                .children(new ArrayList<>())
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════
    // ONE MODULE, EVERY ROLE
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Which roles hold which rights on one module.
     *
     * <p>The Permissions tab answers the other question — one role across every
     * module — and answering "who can reach Purchase Invoice?" there means
     * clicking through nine roles and remembering what each said. This is the
     * same data read down the other axis.
     *
     * <p>Every role is returned, including those with no grant at all, because an
     * absent row and a row of zeros mean the same thing here and a role missing
     * from the list would read as a question the screen had not asked.
     */
    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getModuleRoles(Long pidModule) {
        AclModuleInfo module = getModule(pidModule);

        List<Map<String, Object>> out = new ArrayList<>();
        for (AclGroup role : groupRepo.findAll()) {
            if (!"Y".equalsIgnoreCase(String.valueOf(role.getStatus()))) continue;

            PosPermissions p = permRepo
                    .findByRole_PidGroupAndModule_PidModule(role.getPidGroup(), module.getPidModule())
                    .orElse(null);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("pidGroup",  role.getPidGroup());
            row.put("roleCode",  role.getGroupCode());
            row.put("roleName",  role.getGroupName());
            // Super Admin is the platform's own role and is not edited here, for
            // the same reason the Permissions tab refuses it: a console that can
            // remove its own access is one somebody eventually locks themselves
            // out of.
            row.put("locked",    "ROLE01".equalsIgnoreCase(role.getGroupCode()));
            row.put("canView",   flag(p == null ? null : p.getCanView()));
            row.put("canCreate", flag(p == null ? null : p.getCanCreate()));
            row.put("canEdit",   flag(p == null ? null : p.getCanEdit()));
            row.put("canDelete", flag(p == null ? null : p.getCanDelete()));
            row.put("canApprove",flag(p == null ? null : p.getCanApprove()));
            row.put("canExport", flag(p == null ? null : p.getCanExport()));
            row.put("canPrint",  flag(p == null ? null : p.getCanPrint()));
            out.add(row);
        }
        return out;
    }

    /**
     * Grants or removes one module across several roles at once.
     *
     * <p>Works a role and a module at a time rather than replacing a role
     * wholesale, which is what {@code setRolePermissions} does: saving from this
     * screen must not disturb the twenty-odd other modules a role holds, and a
     * replace-everything save would do exactly that from a screen that never
     * showed them.
     *
     * <p>A role left with no right at all has its row deleted rather than stored
     * as zeros, so the table stays a list of what is granted.
     *
     * @return how many roles now hold something on this module
     */
    @Override
    @Transactional
    public int setModuleRoles(Long pidModule, List<Map<String, Object>> rows) {
        AclModuleInfo module = getModule(pidModule);

        int granted = 0;
        for (Map<String, Object> row : rows == null ? List.<Map<String, Object>>of() : rows) {
            Long pidGroup = asLongValue(row.get("pidGroup"));
            if (pidGroup == null) continue;

            AclGroup role = getGroup(pidGroup);
            if ("ROLE01".equalsIgnoreCase(role.getGroupCode())) continue;   // never edited here

            PosPermissions existing = permRepo
                    .findByRole_PidGroupAndModule_PidModule(pidGroup, module.getPidModule())
                    .orElse(null);

            int view    = intValue(row.get("canView"));
            int create  = intValue(row.get("canCreate"));
            int edit    = intValue(row.get("canEdit"));
            int delete  = intValue(row.get("canDelete"));
            int approve = intValue(row.get("canApprove"));
            int export  = intValue(row.get("canExport"));
            int print   = intValue(row.get("canPrint"));

            /* A right that acts on a page nobody can open is not a right. The
               Permissions tab already enforces this on the way in; doing it here
               too means the rule holds whichever screen was used. */
            if (create + edit + delete + approve + export + print > 0) view = 1;

            boolean any = view + create + edit + delete + approve + export + print > 0;

            if (!any) {
                if (existing != null) permRepo.delete(existing);
                continue;
            }

            PosPermissions perm = existing != null ? existing
                    : PosPermissions.builder().role(role).module(module).build();
            perm.setCanView(view);
            perm.setCanCreate(create);
            perm.setCanEdit(edit);
            perm.setCanDelete(delete);
            perm.setCanApprove(approve);
            perm.setCanExport(export);
            perm.setCanPrint(print);
            perm.setStatus("Y");
            permRepo.save(perm);
            granted++;
        }

        log.info("Module {} is now granted to {} role(s).", module.getModuleCode(), granted);
        return granted;
    }

    private static int flag(Integer v) { return v != null && v == 1 ? 1 : 0; }

    private static int intValue(Object o) {
        if (o == null) return 0;
        if (o instanceof Boolean b) return b ? 1 : 0;
        try { return Integer.parseInt(String.valueOf(o).trim()) == 1 ? 1 : 0; }
        catch (NumberFormatException e) { return 0; }
    }

    private static Long asLongValue(Object o) {
        if (o == null) return null;
        try { return Long.valueOf(String.valueOf(o).trim()); }
        catch (NumberFormatException e) { return null; }
    }

}
