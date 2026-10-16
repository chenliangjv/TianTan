package com.bucea.webgl.modeldataservice.service;

import com.bucea.webgl.modeldataservice.dto.IngestionTaskInfo;
import com.bucea.webgl.modeldataservice.entity.IngestionTask;
import com.bucea.webgl.modeldataservice.repository.IngestionTaskRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class IngestionTaskService {

    @Autowired
    private IngestionTaskRepository repository;

    private final ConcurrentHashMap<String, String> statusCache = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Transactional
    public IngestionTaskInfo createTask(String dataDirPath, String dataCategory, String modelName, String uploadedBy) {
        IngestionTask task = new IngestionTask();
        task.setTaskId(UUID.randomUUID().toString());
        task.setDataDirPath(dataDirPath);
        task.setDataCategory(dataCategory);
        task.setModelName(modelName);
        task.setStatus("PENDING");
        task.setMessage("等待执行");
        task.setCreateTime(LocalDateTime.now());
        task.setUploadedBy(uploadedBy);
        task = repository.save(task);
        statusCache.put(task.getTaskId(), "PENDING");
        return IngestionTaskInfo.fromEntity(task);
    }

    public void runAsync(String taskId, Runnable runnable) {
        executor.submit(runnable);
    }

    @Transactional
    public void markRunning(String taskId, String message) {
        update(taskId, task -> {
            task.setStatus("RUNNING");
            task.setMessage(message);
            task.setProcessedNodes(0);
            task.setProcessedFiles(0);
            task.setUpdateTime(LocalDateTime.now());
        });
        statusCache.put(taskId, "RUNNING");
    }

    @Transactional
    public void setTotals(String taskId, int totalNodes, int totalFiles) {
        update(taskId, task -> {
            task.setTotalNodes(totalNodes);
            task.setTotalFiles(totalFiles);
            task.setUpdateTime(LocalDateTime.now());
        });
    }

    @Transactional
    public void updateMessage(String taskId, String message) {
        update(taskId, task -> {
            task.setMessage(message);
            task.setUpdateTime(LocalDateTime.now());
        });
    }

    @Transactional
    public void updateModelName(String taskId, String modelName) {
        update(taskId, task -> {
            task.setModelName(modelName);
            task.setUpdateTime(LocalDateTime.now());
        });
    }

    @Transactional
    public void markSuccess(String taskId, String message) {
        update(taskId, task -> {
            task.setStatus("SUCCESS");
            task.setMessage(message);
            task.setUpdateTime(LocalDateTime.now());
        });
        statusCache.put(taskId, "SUCCESS");
    }

    @Transactional
    public void markFailed(String taskId, String message) {
        update(taskId, task -> {
            task.setStatus("FAILED");
            task.setMessage(message);
            task.setUpdateTime(LocalDateTime.now());
        });
        statusCache.put(taskId, "FAILED");
    }

    @Transactional
    public void incrementProcessedNode(String taskId) {
        update(taskId, task -> {
            task.setProcessedNodes(task.getProcessedNodes() + 1);
            task.setUpdateTime(LocalDateTime.now());
        });
    }

    @Transactional
    public void incrementProcessedFile(String taskId) {
        update(taskId, task -> {
            task.setProcessedFiles(task.getProcessedFiles() + 1);
            task.setUpdateTime(LocalDateTime.now());
        });
    }

    @Transactional
    public void incrementProcessedFileBulk(String taskId, int count) {
        update(taskId, task -> {
            task.setProcessedFiles(task.getProcessedFiles() + count);
            task.setUpdateTime(LocalDateTime.now());
        });
    }

    @Transactional(readOnly = true)
    public IngestionTaskInfo getTask(String taskId) {
        Optional<IngestionTask> opt = repository.findByTaskId(taskId);
        if (opt.isEmpty()) {
            // 可能还在缓存未入库
            if (statusCache.containsKey(taskId)) {
                IngestionTaskInfo info = new IngestionTaskInfo();
                info.setTaskId(taskId);
                info.setStatus(statusCache.get(taskId));
                return info;
            }
            return IngestionTaskInfo.fromEntity(repository.findByTaskId(taskId).orElse(null));
        }
        return IngestionTaskInfo.fromEntity(opt.get());
    }

    @Transactional(readOnly = true)
    public IngestionTaskInfo getLatestTask(String category) {
        Optional<IngestionTask> opt = repository.findFirstByDataCategoryOrderByCreateTimeDesc(category);
        return opt.map(IngestionTaskInfo::fromEntity).orElse(null);
    }

    private void update(String taskId, java.util.function.Consumer<IngestionTask> updater) {
        Optional<IngestionTask> opt = repository.findByTaskId(taskId);
        if (opt.isPresent()) {
            updater.accept(opt.get());
            repository.save(opt.get());
        }
    }
}
