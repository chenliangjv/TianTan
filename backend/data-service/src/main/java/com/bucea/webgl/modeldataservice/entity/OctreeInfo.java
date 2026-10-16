package com.bucea.webgl.modeldataservice.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "octree_info")
public class OctreeInfo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long modelId;
    
    private String name; // e.g., "xcdsz_d1_root_1" or "smd_root"
    
    private String rootNodeCode; // e.g., "1"
}