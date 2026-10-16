package com.bucea.webgl.modeldataservice.dto;

import com.bucea.webgl.modeldataservice.entity.IngestionTask;

public class IngestionTaskInfo {

    private Long id;
    private String taskId;
    private String dataDirPath;
    private String dataCategory;
    private String modelName;
    private String status;
    private String message;
    private Integer totalNodes;
    private Integer totalFiles;
    private Integer processedNodes;
    private Integer processedFiles;
    private String createTime;
    private String updateTime;

    public static IngestionTaskInfo fromEntity(IngestionTask entity) {
        IngestionTaskInfo dto = new IngestionTaskInfo();
        dto.setId(entity.getId());
        dto.setTaskId(entity.getTaskId());
        dto.setDataDirPath(entity.getDataDirPath());
        dto.setDataCategory(entity.getDataCategory());
        dto.setModelName(entity.getModelName());
        dto.setStatus(entity.getStatus());
        dto.setMessage(entity.getMessage());
        dto.setTotalNodes(entity.getTotalNodes());
        dto.setTotalFiles(entity.getTotalFiles());
        dto.setProcessedNodes(entity.getProcessedNodes());
        dto.setProcessedFiles(entity.getProcessedFiles());
        if (entity.getCreateTime() != null) dto.setCreateTime(entity.getCreateTime().toString());
        if (entity.getUpdateTime() != null) dto.setUpdateTime(entity.getUpdateTime().toString());
        return dto;
    }

    public int getProgressPercent() {
        if (totalNodes == null || totalNodes == 0) {
            if (processedNodes == null || processedNodes == 0) return 0;
            return 50;
        }
        return (int)((long)processedNodes * 100 / totalNodes);
    }

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

    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }

    public String getUpdateTime() { return updateTime; }
    public void setUpdateTime(String updateTime) { this.updateTime = updateTime; }
}
