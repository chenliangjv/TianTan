package com.bucea.webgl.modeldataservice.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "design_image")
public class DesignImage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 200)
    private String name;
    @Column(nullable = false, length = 240)
    private String originalName;
    @Column(nullable = false, unique = true, length = 64)
    private String contentHash;
    @Column(nullable = false, unique = true, length = 36)
    private String storageKey;
    @Column(nullable = false, length = 10)
    private String format;
    @Column(nullable = false)
    private Integer width;
    @Column(nullable = false)
    private Integer height;
    @Column(nullable = false)
    private Long fileSize;
    @Column(nullable = false)
    private LocalDateTime createTime;
}
