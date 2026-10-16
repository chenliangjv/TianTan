package com.bucea.webgl.modeldataservice.repository;

import com.bucea.webgl.modeldataservice.entity.DesignImage;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DesignImageRepository extends JpaRepository<DesignImage, Long> {
    List<DesignImage> findAllByOrderByCreateTimeDescIdDesc();
    Optional<DesignImage> findByContentHash(String hash);
}
