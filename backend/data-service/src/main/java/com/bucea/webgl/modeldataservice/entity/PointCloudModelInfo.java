package com.bucea.webgl.modeldataservice.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.util.Date;

@Data
@Entity
@Table(name = "point_cloud_model_info")
public class PointCloudModelInfo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Column(nullable = false, unique = true, length = 120)
    private String storageKey;

    @Column(nullable = false, length = 32)
    private String format = "POTREE_2";

    @Column(nullable = false, length = 512)
    private String rootPath;

    private Long pointCount;
    private String encoding;
    private Integer hierarchyDepth;
    private Integer hierarchyStepSize;
    private Long hierarchyFirstChunkSize;
    private Double bboxMinX;
    private Double bboxMinY;
    private Double bboxMinZ;
    private Double bboxMaxX;
    private Double bboxMaxY;
    private Double bboxMaxZ;
    private Double scaleX;
    private Double scaleY;
    private Double scaleZ;
    private Double offsetX;
    private Double offsetY;
    private Double offsetZ;
    private Long totalSize;
    @Column(nullable = false, length = 24)
    private String status = "READY";

    private Date createTime;

    @Column(name = "uploaded_by")
    private String uploadedBy;

    @Column(name = "upload_time")
    private Date uploadTime;
}
