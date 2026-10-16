package com.bucea.webgl.modeldataservice.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "octree_node", indexes = {
    @Index(name = "idx_octree_node", columnList = "octreeId, nodeCode")
})
public class OctreeNode {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long octreeId;
    
    private String nodeCode; // e.g., "1", "10"
    
    private String parentCode;
    
    // Bounding Box
    private Double minX;
    private Double minY;
    private Double minZ;
    private Double maxX;
    private Double maxY;
    private Double maxZ;
    
    private Integer triangleNum;
    private Integer vertexNum;
    private Long pointCount;
    private Long dataByteSize;
    private String dataFormat;
    private Integer childrenNum;
}
