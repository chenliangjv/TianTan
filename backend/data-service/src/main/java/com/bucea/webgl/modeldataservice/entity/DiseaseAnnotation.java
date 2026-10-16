package com.bucea.webgl.modeldataservice.entity;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Entity
@Table(name = "disease_annotation", indexes = {
        @Index(name = "idx_disease_model", columnList = "model_id"),
        @Index(name = "idx_disease_created", columnList = "created_time")
})
public class DiseaseAnnotation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "model_id", nullable = false)
    private Long modelId;

    @Column(name = "monomer_id")
    private Long monomerId;

    @Column(nullable = false, length = 80)
    private String type;

    @Column(nullable = false, length = 20)
    private String severity;

    @Column(name = "record_date")
    private LocalDate recordDate;

    @Column(length = 80)
    private String recorder;

    @Column(length = 2000)
    private String description;

    @Column(name = "coordinate_system", length = 40)
    private String coordinateSystem = "MODEL_WORLD";

    @Column(name = "model_version", length = 100)
    private String modelVersion;

    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    @Column(name = "created_by", length = 80)
    private String createdBy;

    @Column(name = "created_time", nullable = false)
    private LocalDateTime createdTime;

    @Column(name = "updated_time", nullable = false)
    private LocalDateTime updatedTime;

    @OneToMany(mappedBy = "annotation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sequenceNo ASC")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<DiseasePoint> points = new ArrayList<>();

    @PrePersist
    void beforeInsert() {
        LocalDateTime now = LocalDateTime.now();
        if (createdTime == null) createdTime = now;
        if (updatedTime == null) updatedTime = now;
    }

    @PreUpdate
    void beforeUpdate() {
        updatedTime = LocalDateTime.now();
    }
}
