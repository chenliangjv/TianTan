package com.bucea.webgl.modeldataservice.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.util.Date;

@Data
@Entity
@Table(name = "model_info")
public class ModelInfo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name; // smd, xzdx

    // MODEL or POINT_CLOUD
    private String dataCategory;
    
    private String type; // SINGLE_OCTREE, MULTI_OCTREE
    
    private Date createTime;

    @Column(name = "uploaded_by")
    private String uploadedBy;

    @Column(name = "upload_time")
    private Date uploadTime;
}
