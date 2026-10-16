package com.bucea.webgl.modeldataservice.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.bucea.webgl.modeldataservice.dto.IngestionTaskInfo;
import com.bucea.webgl.modeldataservice.entity.ModelInfo;
import com.bucea.webgl.modeldataservice.entity.NodeFile;
import com.bucea.webgl.modeldataservice.entity.OctreeInfo;
import com.bucea.webgl.modeldataservice.entity.OctreeNode;
import com.bucea.webgl.modeldataservice.entity.PointCloudModelInfo;
import com.bucea.webgl.modeldataservice.repository.ModelInfoRepository;
import com.bucea.webgl.modeldataservice.repository.NodeFileRepository;
import com.bucea.webgl.modeldataservice.repository.OctreeInfoRepository;
import com.bucea.webgl.modeldataservice.repository.OctreeNodeRepository;
import com.bucea.webgl.modeldataservice.repository.PointCloudModelInfoRepository;
import com.bucea.webgl.modeldataservice.repository.DiseaseAnnotationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Service
public class DataIngestionService {
    public static final String CATEGORY_MODEL = "MODEL";
    public static final String CATEGORY_POINT_CLOUD = "POINT_CLOUD";

    @Value("${storage.root-path}")
    private String storageRootPath;

    @Autowired
    private ModelInfoRepository modelInfoRepository;

    @Autowired
    private OctreeInfoRepository octreeInfoRepository;

    @Autowired
    private OctreeNodeRepository octreeNodeRepository;

    @Autowired
    private NodeFileRepository nodeFileRepository;

    @Autowired
    private PointCloudModelInfoRepository pointCloudModelInfoRepository;

    @Autowired
    private DiseaseAnnotationRepository diseaseAnnotationRepository;

    @Autowired
    private IngestionTaskService ingestionTaskService;

    @Transactional(rollbackFor = Exception.class)
    public void deleteModel(Long modelId) {
        // 查模型名，用于后续清理空目录
        String modelName = modelInfoRepository.findById(modelId)
                .map(m -> sanitizePathName(m.getName())).orElse(null);

        // 1. 收集磁盘文件路径。文件在数据库删除成功后再清理，避免事务失败导致文件先丢失。
        List<OctreeInfo> octrees = octreeInfoRepository.findByModelId(modelId);
        List<Path> filesToDelete = new java.util.ArrayList<>();
        for (OctreeInfo octree : octrees) {
            List<OctreeNode> nodes = octreeNodeRepository.findByOctreeId(octree.getId());
            for (OctreeNode node : nodes) {
                List<NodeFile> nodeFiles = nodeFileRepository.findByNodeId(node.getId());
                for (NodeFile f : nodeFiles) {
                    if (f.getFilePath() != null && !f.getFilePath().isBlank()) {
                        filesToDelete.add(Path.of(storageRootPath, f.getFilePath()));
                    }
                }
            }
        }
        // 2. 保留病害历史记录，但从有效病害中移除。
        diseaseAnnotationRepository.markActiveByModelIdDeleted(modelId);
        // 3. 删除数据库记录
        nodeFileRepository.deleteByModelId(modelId);
        octreeNodeRepository.deleteByModelId(modelId);
        octreeInfoRepository.deleteByModelId(modelId);
        modelInfoRepository.deleteById(modelId);

        // 4. 只有数据库事务提交成功后才清理磁盘文件和空目录。
        List<Long> octreeIds = octrees.stream().map(OctreeInfo::getId).toList();
        runAfterCommit(() -> {
            for (Path file : filesToDelete) {
                try { Files.deleteIfExists(file); } catch (IOException ignored) {}
            }
            if (modelName != null) {
                for (Long octreeId : octreeIds) {
                    cleanupEmptyDirs(Path.of(storageRootPath, "models", String.valueOf(octreeId), modelName));
                    cleanupEmptyDirs(Path.of(storageRootPath, "models", String.valueOf(octreeId)));
                }
            }
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public void clearAllData() {
        diseaseAnnotationRepository.markAllActiveDeleted();
        // Use deleteAllInBatch to prevent JPA from fetching all entities into memory
        nodeFileRepository.deleteAllInBatch();
        octreeNodeRepository.deleteAllInBatch();
        octreeInfoRepository.deleteAllInBatch();
        modelInfoRepository.deleteAllInBatch();
    }

    @Transactional(rollbackFor = Exception.class)
    public void clearByCategory(String dataCategory) {
        if (CATEGORY_POINT_CLOUD.equals(dataCategory)) {
            clearAllPointCloudData();
            return;
        }
        List<ModelInfo> models = modelInfoRepository.findByCategoryCompatible(
                dataCategory,
                CATEGORY_MODEL.equals(dataCategory)
        );
        for (ModelInfo model : models) {
            deleteModel(model.getId());
        }
    }

    public List<ModelInfo> getModelsByCategory(String dataCategory) {
        if (CATEGORY_POINT_CLOUD.equals(dataCategory)) {
            return java.util.Collections.emptyList();
        }
        return modelInfoRepository.findByCategoryCompatible(
                dataCategory,
                CATEGORY_MODEL.equals(dataCategory)
        );
    }

    public List<PointCloudModelInfo> getPointCloudModels() {
        return pointCloudModelInfoRepository.findAllByOrderByCreateTimeDesc();
    }

    @Transactional(rollbackFor = Exception.class)
    public void deletePointCloudModel(Long modelId) {
        PointCloudModelInfo model = pointCloudModelInfoRepository.findById(modelId)
                .orElseThrow(() -> new IllegalArgumentException("点云数据不存在: " + modelId));
        pointCloudModelInfoRepository.delete(model);
        pointCloudModelInfoRepository.flush();
        String storagePath = model.getRootPath();
        runAfterCommit(() -> deleteStorageDirectory(storagePath));
    }

    @Transactional(rollbackFor = Exception.class)
    public void clearAllPointCloudData() {
        List<String> storagePaths = pointCloudModelInfoRepository.findAll().stream()
                .map(PointCloudModelInfo::getRootPath)
                .toList();
        pointCloudModelInfoRepository.deleteAllInBatch();
        runAfterCommit(() -> storagePaths.forEach(this::deleteStorageDirectory));
    }

    private void runAfterCommit(Runnable cleanup) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cleanup.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cleanup.run();
            }
        });
    }

    public IngestionTaskInfo createModelIngestionTask(String dataDirPath, String uploadedBy) {
        return createIngestionTask(dataDirPath, CATEGORY_MODEL, uploadedBy);
    }

    public IngestionTaskInfo createPointCloudIngestionTask(String dataDirPath, String uploadedBy) {
        return createIngestionTask(dataDirPath, CATEGORY_POINT_CLOUD, uploadedBy);
    }

    private IngestionTaskInfo createIngestionTask(String dataDirPath, String dataCategory, String uploadedBy) {
        File modelDir = new File(dataDirPath);
        String modelName = modelDir.getName();
        IngestionTaskInfo task = ingestionTaskService.createTask(dataDirPath, dataCategory, modelName, uploadedBy);
        ingestionTaskService.runAsync(task.getTaskId(), () -> runIngestionTask(task.getTaskId(), dataDirPath, dataCategory, uploadedBy));
        return task;
    }

    private void runIngestionTask(String taskId, String dataDirPath, String dataCategory, String uploadedBy) {
        try {
            ingestionTaskService.markRunning(taskId, "正在分析目录结构");
            ScanStatistics statistics = CATEGORY_POINT_CLOUD.equals(dataCategory)
                    ? scanPotreeStatistics(dataDirPath) : scanStatistics(dataDirPath);
            ingestionTaskService.setTotals(taskId, (int) statistics.totalNodes, (int) statistics.totalFiles);
            ingestionTaskService.updateMessage(taskId, "目录分析完成，开始写入数据库");
            if (CATEGORY_POINT_CLOUD.equals(dataCategory)) {
                scanAndIngestPointCloud(taskId, dataDirPath, uploadedBy);
            } else {
                scanAndIngest(taskId, dataDirPath, dataCategory, uploadedBy);
            }
            ingestionTaskService.markSuccess(taskId, "入库完成");
        } catch (Exception e) {
            ingestionTaskService.markFailed(taskId, e.getMessage());
        }
    }

    // 移除 @Transactional 避免加载大量文件时撑爆内存 (OutOfMemory) 或数据库事务超时
    private void scanAndIngest(String taskId, String dataDirPath, String dataCategory, String uploadedBy) throws IOException {
        File modelDir = new File(dataDirPath);
        if (!modelDir.exists() || !modelDir.isDirectory()) {
            throw new IllegalArgumentException("Model directory does not exist: " + dataDirPath);
        }

        String modelName = modelDir.getName();
        
        ModelInfo modelInfo = modelInfoRepository.findExistingByNameAndCategory(
                modelName,
                dataCategory,
                CATEGORY_MODEL.equals(dataCategory)
        );
        if (modelInfo != null) {
            throw new IllegalArgumentException("EXISTS: 数据 '" + modelName + "' 已经存在于数据库中，请勿重复入库！");
        }

        ingestionTaskService.updateMessage(taskId, "正在创建模型主记录");

        modelInfo = new ModelInfo();
        modelInfo.setName(modelName);
        modelInfo.setDataCategory(dataCategory);
        modelInfo.setCreateTime(new Date());
        
        File[] subDirs = modelDir.listFiles(File::isDirectory);
        boolean isMulti = false;
        if (subDirs != null) {
            for (File sub : subDirs) {
                if (!sub.getName().matches("\\d+")) {
                    isMulti = true;
                    break;
                }
            }
        }
        
        modelInfo.setType(isMulti ? "MULTI_OCTREE" : "SINGLE_OCTREE");
        modelInfo = modelInfoRepository.save(modelInfo);

        try {
            if ("SINGLE_OCTREE".equals(modelInfo.getType())) {
                File[] rootNodes = modelDir.listFiles(File::isDirectory);
                if (rootNodes != null && rootNodes.length > 0) {
                    for (File rootNodeDir : rootNodes) {
                        processOctree(taskId, modelInfo, "default", rootNodeDir, dataCategory);
                    }
                }
            } else {
                File[] octreeDirs = modelDir.listFiles(File::isDirectory);
                if (octreeDirs != null) {
                    for (File octreeDir : octreeDirs) {
                        File[] rootNodes = octreeDir.listFiles(File::isDirectory);
                        if (rootNodes != null && rootNodes.length > 0) {
                            for (File rootNodeDir : rootNodes) {
                                processOctree(taskId, modelInfo, octreeDir.getName(), rootNodeDir, dataCategory);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            ingestionTaskService.updateMessage(taskId, "入库失败，正在清理已写入数据");
            try {
                deleteModel(modelInfo.getId());
            } catch (Exception cleanupException) {
                throw new RuntimeException("入库失败，且清理残留数据时出错: " + cleanupException.getMessage(), cleanupException);
            }
            throw new RuntimeException("入库过程出错，已回滚数据。原因: " + e.getMessage(), e);
        }
    }

    private void scanAndIngestPointCloud(String taskId, String dataDirPath, String uploadedBy) throws IOException {
        Path sourceRoot = discoverPotreeRoot(Path.of(dataDirPath));
        JSONObject metadata = parseMetadata(sourceRoot.resolve("metadata.json"));
        String modelName = metadata.getString("name");
        if (modelName == null || modelName.isBlank()) modelName = sourceRoot.getFileName().toString();
        ingestionTaskService.updateModelName(taskId, modelName);
        if (pointCloudModelInfoRepository.findByName(modelName) != null) {
            throw new IllegalArgumentException("EXISTS: 数据 '" + modelName + "' 已经存在于数据库中，请勿重复入库！");
        }

        String storageKey = sanitizePathName(modelName) + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path targetRoot = Path.of(storageRootPath, "pointclouds", storageKey).toAbsolutePath().normalize();
        Files.createDirectories(targetRoot);
        try {
            ingestionTaskService.updateMessage(taskId, "正在复制 Potree 数据集");
            for (String fileName : POTREE_FILES) {
                Files.copy(sourceRoot.resolve(fileName), targetRoot.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
                ingestionTaskService.incrementProcessedFile(taskId);
            }
            PointCloudModelInfo model = createPotreeModel(modelName, storageKey, targetRoot, metadata, uploadedBy);
            pointCloudModelInfoRepository.save(model);
            ingestionTaskService.incrementProcessedNode(taskId);
            ingestionTaskService.updateMessage(taskId, "Potree 数据集已入库");
        } catch (Exception ex) {
            deleteStorageDirectory(targetRoot.toString());
            throw ex;
        }
    }

    private static final String[] POTREE_FILES = {"metadata.json", "hierarchy.bin", "octree.bin"};

    private PointCloudModelInfo createPotreeModel(String modelName, String storageKey, Path targetRoot,
                                                   JSONObject metadata, String uploadedBy) throws IOException {
        PointCloudModelInfo model = new PointCloudModelInfo();
        model.setName(modelName);
        model.setStorageKey(storageKey);
        model.setRootPath(Path.of(storageRootPath).relativize(targetRoot).toString().replace('\\', '/'));
        model.setFormat("POTREE_2");
        model.setPointCount(metadata.getLong("points"));
        model.setEncoding(metadata.getString("encoding"));
        JSONObject hierarchy = metadata.getJSONObject("hierarchy");
        if (hierarchy != null) {
            model.setHierarchyDepth(hierarchy.getInteger("depth"));
            model.setHierarchyStepSize(hierarchy.getInteger("stepSize"));
            model.setHierarchyFirstChunkSize(hierarchy.getLong("firstChunkSize"));
        }
        List<Double> bbox = doubles(metadata.getJSONObject("boundingBox"), "min", "max");
        if (bbox.size() == 6) {
            model.setBboxMinX(bbox.get(0)); model.setBboxMinY(bbox.get(1)); model.setBboxMinZ(bbox.get(2));
            model.setBboxMaxX(bbox.get(3)); model.setBboxMaxY(bbox.get(4)); model.setBboxMaxZ(bbox.get(5));
        }
        List<Double> scale = array(metadata.getJSONArray("scale"));
        List<Double> offset = array(metadata.getJSONArray("offset"));
        if (scale.size() == 3) { model.setScaleX(scale.get(0)); model.setScaleY(scale.get(1)); model.setScaleZ(scale.get(2)); }
        if (offset.size() == 3) { model.setOffsetX(offset.get(0)); model.setOffsetY(offset.get(1)); model.setOffsetZ(offset.get(2)); }
        model.setTotalSize(Files.size(targetRoot.resolve("metadata.json")) + Files.size(targetRoot.resolve("hierarchy.bin")) + Files.size(targetRoot.resolve("octree.bin")));
        model.setCreateTime(new Date());
        model.setUploadTime(new Date());
        model.setUploadedBy(uploadedBy);
        model.setStatus("READY");
        return model;
    }

    private JSONObject parseMetadata(Path metadataPath) throws IOException {
        try {
            JSONObject metadata = JSONObject.parseObject(Files.readString(metadataPath));
            if (!"2.0".equals(metadata.getString("version"))) throw new IllegalArgumentException("仅支持 Potree 2.0 数据集");
            if (metadata.getLongValue("points") <= 0 || metadata.getJSONObject("hierarchy") == null) throw new IllegalArgumentException("metadata.json 缺少有效的点数或层级信息");
            return metadata;
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("metadata.json 解析失败: " + e.getMessage(), e); }
    }

    public Path discoverPotreeRoot(Path input) {
        Path root = input.toAbsolutePath().normalize();
        if (isPotreeRoot(root)) return root;
        try (var stream = Files.walk(root, 3)) {
            return stream.filter(this::isPotreeRoot).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("未找到 Potree 数据集，必须包含 metadata.json、hierarchy.bin、octree.bin"));
        } catch (IOException e) { throw new IllegalArgumentException("无法扫描点云目录: " + e.getMessage(), e); }
    }

    private boolean isPotreeRoot(Path path) {
        return Files.isDirectory(path) && Files.isRegularFile(path.resolve("metadata.json"))
                && Files.isRegularFile(path.resolve("hierarchy.bin")) && Files.isRegularFile(path.resolve("octree.bin"))
                && fileSize(path.resolve("octree.bin")) > 0 && fileSize(path.resolve("hierarchy.bin")) > 0;
    }

    private static long fileSize(Path path) { try { return Files.size(path); } catch (IOException e) { return 0; } }

    private static List<Double> array(com.alibaba.fastjson.JSONArray values) {
        List<Double> result = new java.util.ArrayList<>();
        if (values != null) for (Object value : values) result.add(Double.valueOf(value.toString()));
        return result;
    }

    private static List<Double> doubles(JSONObject bbox, String minKey, String maxKey) {
        if (bbox == null) return java.util.Collections.emptyList();
        List<Double> result = array(bbox.getJSONArray(minKey)); result.addAll(array(bbox.getJSONArray(maxKey))); return result;
    }

    private void deleteStorageDirectory(String relativeOrAbsolute) {
        if (relativeOrAbsolute == null || relativeOrAbsolute.isBlank()) return;
        Path candidate = Path.of(relativeOrAbsolute);
        if (!candidate.isAbsolute()) candidate = Path.of(storageRootPath, relativeOrAbsolute);
        Path root = Path.of(storageRootPath).toAbsolutePath().normalize();
        candidate = candidate.toAbsolutePath().normalize();
        if (!candidate.startsWith(root) || candidate.equals(root)) return;
        try (var stream = Files.walk(candidate)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    private void processOctree(String taskId, ModelInfo modelInfo, String octreeName, File rootNodeDir, String dataCategory) throws IOException {
        ingestionTaskService.updateMessage(taskId, "正在创建八叉树: " + octreeName);
        OctreeInfo octree = new OctreeInfo();
        octree.setModelId(modelInfo.getId());
        octree.setName(octreeName);
        octree.setRootNodeCode(rootNodeDir.getName());
        octree = octreeInfoRepository.save(octree);

        processNode(taskId, octree, null, null, rootNodeDir, dataCategory, modelInfo);
    }

    private void processNode(String taskId, OctreeInfo octree, String parentCode, String parentPath,
                              File nodeDir, String dataCategory,
                              ModelInfo modelInfo) throws IOException {
        String nodeCode = nodeDir.getName();
        // 保留完整目录层级路径，用于磁盘存储（而非扁平化为仅目录名）
        String fullNodePath = (parentPath == null || parentPath.isEmpty()) ? nodeCode : parentPath + "/" + nodeCode;
        ingestionTaskService.updateMessage(taskId, "正在处理节点: " + nodeCode);
        
        OctreeNode node = new OctreeNode();
        node.setOctreeId(octree.getId());
        node.setNodeCode(nodeCode);
        node.setParentCode(parentCode);

        // Read json to get bounding box
        File jsonFile = new File(nodeDir, nodeCode + ".json");
        if (jsonFile.exists()) {
            String jsonContent = new String(Files.readAllBytes(jsonFile.toPath()));
            JSONObject jsonObj = JSON.parseObject(jsonContent);
            JSONObject bbox = jsonObj.getJSONObject("boundingBox");
            if (bbox != null) {
                node.setMinX(bbox.getDouble("lx"));
                node.setMinY(bbox.getDouble("ly"));
                node.setMinZ(bbox.getDouble("lz"));
                node.setMaxX(bbox.getDouble("ux"));
                node.setMaxY(bbox.getDouble("uy"));
                node.setMaxZ(bbox.getDouble("uz"));
            }

            if (CATEGORY_POINT_CLOUD.equals(dataCategory)) {
                node.setPointCount(jsonObj.getLong("pointCount"));
                node.setDataByteSize(jsonObj.getLong("dataByteSize"));
                node.setDataFormat(jsonObj.getString("dataFormat"));
            } else {
                node.setTriangleNum(jsonObj.getInteger("triangleNum"));
                node.setVertexNum(jsonObj.getInteger("vertexNum"));
            }

            node.setChildrenNum(jsonObj.getInteger("childrenNum"));
        }
        node = octreeNodeRepository.save(node);
        ingestionTaskService.incrementProcessedNode(taskId);

        // Save all files in this node directory
        File[] files = nodeDir.listFiles(File::isFile);
        if (files != null) {
            for (File file : files) {
                ingestionTaskService.updateMessage(taskId, "正在写入文件: " + file.getName());
                NodeFile nodeFile = new NodeFile();
                nodeFile.setNodeId(node.getId());
                nodeFile.setFileName(file.getName());
                
                nodeFile.setFileType(getFileExtension(file.getName()));
                nodeFile.setFileSize(file.length());

                // 将文件写入磁盘，数据库只记录相对路径
                String categoryDir = CATEGORY_POINT_CLOUD.equals(dataCategory) ? "pointclouds" : "models";
                String relativePath = buildRelativePath(categoryDir, modelInfo.getName(),
                        String.valueOf(octree.getId()), fullNodePath, file.getName());
                Path targetPath = Path.of(storageRootPath, relativePath);
                Files.createDirectories(targetPath.getParent());
                Files.copy(file.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);
                nodeFile.setFilePath(relativePath);

                
                nodeFileRepository.save(nodeFile);
                ingestionTaskService.incrementProcessedFile(taskId);
                
                // Clear the persistence context immediately to keep the session light.
            }
        }
        
        // Explicitly clear references for the current directory level
        files = null;

        // Recursively process child nodes
        File[] subDirs = nodeDir.listFiles(File::isDirectory);
        if (subDirs != null) {
            for (File subDir : subDirs) {
                processNode(taskId, octree, nodeCode, fullNodePath, subDir, dataCategory, modelInfo);
            }
        }
    }

    private ScanStatistics scanStatistics(String dataDirPath) {
        File modelDir = new File(dataDirPath);
        if (!modelDir.exists() || !modelDir.isDirectory()) {
            throw new IllegalArgumentException("Model directory does not exist: " + dataDirPath);
        }

        ScanStatistics statistics = new ScanStatistics();
        File[] subDirs = modelDir.listFiles(File::isDirectory);
        if (subDirs == null) {
            return statistics;
        }

        boolean isMulti = false;
        for (File subDir : subDirs) {
            if (!subDir.getName().matches("\\d+")) {
                isMulti = true;
                break;
            }
        }

        if (isMulti) {
            for (File octreeDir : subDirs) {
                File[] rootNodes = octreeDir.listFiles(File::isDirectory);
                if (rootNodes != null) {
                    for (File rootNodeDir : rootNodes) {
                        countNodeTree(rootNodeDir, statistics);
                    }
                }
            }
        } else {
            for (File rootNodeDir : subDirs) {
                countNodeTree(rootNodeDir, statistics);
            }
        }

        return statistics;
    }

    private ScanStatistics scanPotreeStatistics(String dataDirPath) {
        Path root = discoverPotreeRoot(Path.of(dataDirPath));
        ScanStatistics statistics = new ScanStatistics();
        statistics.totalNodes = 1;
        statistics.totalFiles = POTREE_FILES.length;
        return statistics;
    }

    private void countNodeTree(File nodeDir, ScanStatistics statistics) {
        statistics.totalNodes++;
        File[] files = nodeDir.listFiles(File::isFile);
        if (files != null) {
            statistics.totalFiles += files.length;
        }

        File[] subDirs = nodeDir.listFiles(File::isDirectory);
        if (subDirs != null) {
            for (File subDir : subDirs) {
                countNodeTree(subDir, statistics);
            }
        }
    }

    private static class ScanStatistics {
        private long totalNodes;
        private long totalFiles;
    }

    private static String getFileExtension(String fileName) {
        int i = fileName.lastIndexOf('.');
        if (i > 0) {
            return fileName.substring(i + 1).toLowerCase();
        }
        return "";
    }

    /**
     * 构建文件在存储目录下的相对路径
     * 格式: {category}/{octreeId}/{modelName}/{nodeCode}/{fileName}
     */
    private static String buildRelativePath(String category, String modelName,
                                            String octreeId, String nodeCode, String fileName) {
        return category + "/" + octreeId + "/" + sanitizePathName(modelName) + "/" + nodeCode + "/" + fileName;
    }

    /** 清理路径名中的特殊字符 */
    private static String sanitizePathName(String name) {
        if (name == null) return "unknown";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /**
     * 递归清理指定目录下所有空子目录（从最深往最浅删），根目录本身也为空时一并删除
     */
    private void cleanupEmptyDirs(Path dir) {
        try {
            if (!Files.exists(dir) || !Files.isDirectory(dir)) return;
            Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .filter(Files::isDirectory)
                .forEach(p -> {
                    try {
                        if (Files.isDirectory(p) && isDirEmpty(p)) {
                            Files.delete(p);
                        }
                    } catch (IOException ignored) {}
                });
        } catch (IOException ignored) {}
    }

    private boolean isDirEmpty(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.findAny().isEmpty();
        }
    }
}
