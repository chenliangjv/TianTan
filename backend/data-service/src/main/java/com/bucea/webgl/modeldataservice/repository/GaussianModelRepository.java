package com.bucea.webgl.modeldataservice.repository;

import com.bucea.webgl.modeldataservice.entity.GaussianModel;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface GaussianModelRepository extends JpaRepository<GaussianModel, Long> {
    List<GaussianModel> findAllByOrderByCreatedAtDescIdDesc();
    Optional<GaussianModel> findByContentKey(String contentKey);
}
