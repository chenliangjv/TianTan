package com.bucea.webgl.modeldataservice.repository;

import com.bucea.webgl.modeldataservice.entity.DiseaseAnnotation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface DiseaseAnnotationRepository extends JpaRepository<DiseaseAnnotation, Long> {
    long countByStatus(String status);

    @EntityGraph(attributePaths = "points")
    List<DiseaseAnnotation> findByModelIdAndStatusOrderByCreatedTimeDesc(Long modelId, String status);

    @EntityGraph(attributePaths = "points")
    @Query("select d from DiseaseAnnotation d where d.id = :id and d.status = 'ACTIVE'")
    Optional<DiseaseAnnotation> findWithPointsById(@Param("id") Long id);

    @Modifying
    @Transactional
    @Query("update DiseaseAnnotation d set d.status = 'DELETED', d.updatedTime = CURRENT_TIMESTAMP where d.modelId = :modelId and d.status = 'ACTIVE'")
    int markActiveByModelIdDeleted(@Param("modelId") Long modelId);

    @Modifying
    @Transactional
    @Query("update DiseaseAnnotation d set d.status = 'DELETED', d.updatedTime = CURRENT_TIMESTAMP where d.status = 'ACTIVE'")
    int markAllActiveDeleted();
}
