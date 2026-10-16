package com.bucea.webgl.modeldataservice.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "ingestion_task")
public class IngestionTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String taskId;

    @Column(nullable = false)
    private String dataDirPath;

    @Column(nullable = false)
    private String dataCategory;

    private String modelName;

    @Column(nullable = false)
    private String status; // PENDING, RUNNING, SUCCESS, FAILED

    private String message;

    private Integer totalNodes;
    private Integer totalFiles;
    private Integer processedNodes = 0;
    private Integer processedFiles = 0;

    @Column(nullable = false)
    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @Column(name = "uploaded_by", length = 50)
    private String uploadedBy;

    // ===== Getters / Setters =====

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getDataDirPath() { return dataDirPath; }
    public void setDataDirPath(String dataDirPath) { this.dataDirPath = dataDirPath; }

    public String getDataCategory() { return dataCategory; }
    public void setDataCategory(String dataCategory) { this.dataCategory = dataCategory; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Integer getTotalNodes() { return totalNodes; }
    public void setTotalNodes(Integer totalNodes) { this.totalNodes = totalNodes; }

    public Integer getTotalFiles() { return totalFiles; }
    public void setTotalFiles(Integer totalFiles) { this.totalFiles = totalFiles; }

    public Integer getProcessedNodes() { return processedNodes; }
    public void setProcessedNodes(Integer processedNodes) { this.processedNodes = processedNodes; }

    public Integer getProcessedFiles() { return processedFiles; }
    public void setProcessedFiles(Integer processedFiles) { this.processedFiles = processedFiles; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }

    public String getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(String uploadedBy) { this.uploadedBy = uploadedBy; }
}
