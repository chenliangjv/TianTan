package com.bucea.webgl.modeldataservice.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "gaussian_asset", uniqueConstraints = @UniqueConstraint(columnNames = {"model_id", "role"}))
public class GaussianAsset {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false) private GaussianModel model;
    @Column(nullable = false, length = 10) private String role;
    @Column(nullable = false, length = 10) private String format;
    @Column(nullable = false, length = 255) private String originalName;
    @Column(nullable = false, length = 300) private String relativePath;
    private Long fileSize;
    @Column(nullable = false, length = 64) private String sha256;
    private Long splatCount;
}
