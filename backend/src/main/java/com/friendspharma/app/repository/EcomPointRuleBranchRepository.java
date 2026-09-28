package com.friendspharma.app.repository;

import com.friendspharma.app.entity.EcomPointRuleBranch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EcomPointRuleBranchRepository extends JpaRepository<EcomPointRuleBranch, Long> {
    List<EcomPointRuleBranch> findByPidPointRule(Long pidPointRule);
    void deleteByPidPointRule(Long pidPointRule);
}
