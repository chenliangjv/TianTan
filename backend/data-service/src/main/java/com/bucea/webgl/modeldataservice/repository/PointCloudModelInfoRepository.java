package com.bucea.webgl.modeldataservice.repository;

import com.bucea.webgl.modeldataservice.entity.PointCloudModelInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PointCloudModelInfoRepository extends JpaRepository<PointCloudModelInfo, Long> {
    PointCloudModelInfo findByName(String name);

    boolean existsByStorageKey(String storageKey);

    List<PointCloudModelInfo> findAllByOrderByCreateTimeDesc();
}
