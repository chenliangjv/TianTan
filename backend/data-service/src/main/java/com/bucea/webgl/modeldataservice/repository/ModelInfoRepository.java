package com.bucea.webgl.modeldataservice.repository;

import com.bucea.webgl.modeldataservice.entity.ModelInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ModelInfoRepository extends JpaRepository<ModelInfo, Long> {
    ModelInfo findByName(String name);
    ModelInfo findByNameAndDataCategory(String name, String dataCategory);
    List<ModelInfo> findByDataCategoryOrderByCreateTimeDesc(String dataCategory);

    @Query("""
            SELECT m FROM ModelInfo m
            WHERE m.name = :name
              AND (m.dataCategory = :dataCategory OR (:includeNull = true AND m.dataCategory IS NULL))
            """)
    ModelInfo findExistingByNameAndCategory(@Param("name") String name,
                                            @Param("dataCategory") String dataCategory,
                                            @Param("includeNull") boolean includeNull);

    @Query("""
            SELECT m FROM ModelInfo m
            WHERE m.dataCategory = :dataCategory OR (:includeNull = true AND m.dataCategory IS NULL)
            ORDER BY m.createTime DESC
            """)
    List<ModelInfo> findByCategoryCompatible(@Param("dataCategory") String dataCategory,
                                             @Param("includeNull") boolean includeNull);
}
