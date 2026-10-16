package com.bucea.webgl.modeldataservice.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "gaussian_model")
public class GaussianModel {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 200) private String name;
    @Column(nullable = false, length = 20) private String status;
    @Column(nullable = false, unique = true, length = 36) private String storageKey;
    @Column(nullable = false, unique = true, length = 129) private String contentKey;
    private Long sourceSplatCount;
    private Long totalSplatCount;
    private Integer chunkCount;
    private Integer radVersion;
    private Long totalSize;
    @Column(columnDefinition = "TEXT") private String boundsJson;
    @Column(columnDefinition = "TEXT") private String skyBoundsJson;
    @Column(columnDefinition = "TEXT") private String transformJson;
    @Column(columnDefinition = "TEXT") private String initialCameraJson;
    private boolean skyDefaultEnabled;
    @Column(nullable = false) private LocalDateTime createdAt;
    @Column(nullable = false) private LocalDateTime updatedAt;
}
