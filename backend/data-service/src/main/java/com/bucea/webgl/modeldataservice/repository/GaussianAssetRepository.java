package com.bucea.webgl.modeldataservice.repository;

import com.bucea.webgl.modeldataservice.entity.GaussianAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface GaussianAssetRepository extends JpaRepository<GaussianAsset, Long> {
    List<GaussianAsset> findByModelId(Long modelId);
    @Modifying @Query("delete from GaussianAsset a where a.model.id = :id")
    void deleteForModel(Long id);
}
