package com.friendspharma.app.repository;

import com.friendspharma.app.entity.AccAccount;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AccAccountRepository extends JpaRepository<AccAccount, Long> {
    List<AccAccount> findByPidOrgOrderBySortOrderAscAccountCodeAsc(Long pidOrg);
    Optional<AccAccount> findByPidOrgAndRoleKey(Long pidOrg, String roleKey);
    Optional<AccAccount> findByPidOrgAndAccountCode(Long pidOrg, String accountCode);
    Optional<AccAccount> findByPidAccountAndPidOrg(Long pidAccount, Long pidOrg);
    long countByPidOrg(Long pidOrg);
    List<AccAccount> findByPidOrgAndAccountTypeAndIsGroupOrderBySortOrderAsc(Long pidOrg, String accountType, Integer isGroup);
}
