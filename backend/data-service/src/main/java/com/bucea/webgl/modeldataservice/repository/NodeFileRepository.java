package com.bucea.webgl.modeldataservice.repository;

import com.bucea.webgl.modeldataservice.entity.NodeFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NodeFileRepository extends JpaRepository<NodeFile, Long> {
    NodeFile findByNodeIdAndFileName(Long nodeId, String fileName);
    List<NodeFile> findByNodeId(Long nodeId);

    @Modifying
    @Query(value = "DELETE FROM node_file WHERE node_id IN (SELECT id FROM octree_node WHERE octree_id IN (SELECT id FROM octree_info WHERE model_id = :modelId))", nativeQuery = true)
    void deleteByModelId(@Param("modelId") Long modelId);
}