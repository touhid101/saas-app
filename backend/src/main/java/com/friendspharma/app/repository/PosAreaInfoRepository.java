package com.friendspharma.app.repository;

import com.friendspharma.app.entity.PosAreaInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PosAreaInfoRepository extends JpaRepository<PosAreaInfo, Long>, JpaSpecificationExecutor<PosAreaInfo> {
    List<PosAreaInfo> findByPidBranchAndStatusOrderByAreaName(Long pidBranch, String status);
    List<PosAreaInfo> findByStatusOrderByAreaName(String status);
}
