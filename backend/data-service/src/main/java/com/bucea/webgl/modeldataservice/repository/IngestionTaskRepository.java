package com.bucea.webgl.modeldataservice.repository;

import com.bucea.webgl.modeldataservice.entity.IngestionTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IngestionTaskRepository extends JpaRepository<IngestionTask, Long> {
    Optional<IngestionTask> findByTaskId(String taskId);

    Optional<IngestionTask> findFirstByDataCategoryOrderByCreateTimeDesc(String dataCategory);
}
