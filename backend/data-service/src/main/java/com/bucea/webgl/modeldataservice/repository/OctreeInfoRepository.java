package com.bucea.webgl.modeldataservice.repository;

import com.bucea.webgl.modeldataservice.entity.OctreeInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OctreeInfoRepository extends JpaRepository<OctreeInfo, Long> {
    List<OctreeInfo> findByModelId(Long modelId);

    @Modifying
    @Query("DELETE FROM OctreeInfo o WHERE o.modelId = :modelId")
    void deleteByModelId(@Param("modelId") Long modelId);
}