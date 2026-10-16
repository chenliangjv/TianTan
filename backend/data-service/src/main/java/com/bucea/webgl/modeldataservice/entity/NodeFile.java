package com.bucea.webgl.modeldataservice.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "node_file", indexes = {
    @Index(name = "idx_node_id", columnList = "nodeId")
})
public class NodeFile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long nodeId;
    
    private String fileType; // json, glb, obj, mtl, jpg
    
    private String fileName;

    @Column(name = "file_path", length = 500)
    private String filePath;

    private Long fileSize;
}
