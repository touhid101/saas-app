package com.friendspharma.app.security;

import com.friendspharma.app.entity.AclGroup;
import com.friendspharma.app.entity.AclUser;
import com.friendspharma.app.repository.AclGroupRepository;
import com.friendspharma.app.repository.AclUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final AclUserRepository userRepo;
    private final AclGroupRepository groupRepo;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AclUser user = userRepo.findByUserNameIgnoreCase(username)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "No active account found for username: " + username));

        AclGroup role = null;
        if (user.getPidRole() != null) {
            role = groupRepo.findById(user.getPidRole()).orElse(null);
        }

        boolean isSuperAdmin = "Y".equalsIgnoreCase(user.getIsAdmin());

        return UserPrincipal.builder()
                .userId(user.getPidUser())
                .username(user.getUserName())
                .password(user.getPasswordHash() != null ? user.getPasswordHash() : user.getPinNo())
                .fullName(user.getFullName())
                .orgId(user.getPidOrg())
                .companyId(user.getPidCompany())
                .branchId(user.getPidBranch())
                .employeeId(user.getPidEmployee())
                .roleCode(role != null ? role.getRoleCode() : "ROLE08")
                .roleName(role != null ? role.getGroupName() : "Viewer")
                .superAdmin(isSuperAdmin)
                .status(user.getStatus())
                .build();
    }
}
