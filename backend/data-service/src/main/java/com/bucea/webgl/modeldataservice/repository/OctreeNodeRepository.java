package com.bucea.webgl.modeldataservice.repository;

import com.bucea.webgl.modeldataservice.entity.OctreeNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OctreeNodeRepository extends JpaRepository<OctreeNode, Long> {
    List<OctreeNode> findByOctreeId(Long octreeId);
    OctreeNode findByOctreeIdAndNodeCode(Long octreeId, String nodeCode);

    @Modifying
    @Query(value = "DELETE FROM octree_node WHERE octree_id IN (SELECT id FROM octree_info WHERE model_id = :modelId)", nativeQuery = true)
    void deleteByModelId(@Param("modelId") Long modelId);
}