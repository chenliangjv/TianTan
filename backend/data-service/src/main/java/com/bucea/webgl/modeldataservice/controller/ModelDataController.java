package com.bucea.webgl.modeldataservice.controller;

import com.bucea.webgl.modeldataservice.dto.IngestionTaskInfo;
import com.bucea.webgl.modeldataservice.entity.NodeFile;
import com.bucea.webgl.modeldataservice.entity.OctreeNode;
import com.bucea.webgl.modeldataservice.entity.PointCloudModelInfo;
import com.bucea.webgl.modeldataservice.repository.NodeFileRepository;
import com.bucea.webgl.modeldataservice.repository.OctreeNodeRepository;
import com.bucea.webgl.modeldataservice.repository.PointCloudModelInfoRepository;
import com.bucea.webgl.modeldataservice.service.DataIngestionService;
import com.bucea.webgl.modeldataservice.service.IngestionTaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import com.bucea.webgl.modeldataservice.entity.ModelInfo;
import com.bucea.webgl.modeldataservice.entity.OctreeInfo;
import com.bucea.webgl.modeldataservice.repository.ModelInfoRepository;
import com.bucea.webgl.modeldataservice.repository.OctreeInfoRepository;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.ServletOutputStream;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.web.multipart.MultipartFile;


@RestController
@RequestMapping("/api/data")
public class ModelDataController {

    private static final String IMMUTABLE_ASSET_CACHE_CONTROL = "public, max-age=86400, immutable";

    // ZIP archives made by Windows tools commonly use GBK for entry names when
    // the UTF-8 language encoding flag is absent. ZipInputStream still honors
    // that flag, so UTF-8 archives remain supported as well.
    private static final Charset ZIP_ENTRY_CHARSET = Charset.forName("GBK");

    @Autowired
    private DataIngestionService dataIngestionService;

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
    private IngestionTaskService ingestionTaskService;

    // Node files are immutable once an octree has been ingested. Avoid two
    // database lookups for every GLB request while users move around a model.
    private final Map<String, ResolvedNodeFile> nodeFileCache = new ConcurrentHashMap<>();

    @Value("${storage.root-path}")
    private String storageRootPath;

    @Value("${storage.temp-path}")
    private String storageTempPath;

    @Value("${storage.nginx-base-url}")
    private String nginxBaseUrl;

    @PostMapping("/models/ingest")
    public ResponseEntity<?> ingestModelData(@RequestParam String dataPath) {
        try {
            String uploadedBy = "system";
            IngestionTaskInfo task = dataIngestionService.createModelIngestionTask(dataPath, uploadedBy);
            return ResponseEntity.ok(task);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("EXISTS:")) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error: " + e.getMessage());
        }
    }

    @PostMapping("/point-clouds/ingest")
    public ResponseEntity<?> ingestPointCloudData(@RequestParam String dataPath) {
        try {
            String uploadedBy = "system";
            IngestionTaskInfo task = dataIngestionService.createPointCloudIngestionTask(dataPath, uploadedBy);
            return ResponseEntity.ok(task);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("EXISTS:")) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error: " + e.getMessage());
        }
    }

    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<?> getTask(@PathVariable String taskId) {
        IngestionTaskInfo task = ingestionTaskService.getTask(taskId);
        if (task == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Task not found");
        }
        return ResponseEntity.ok(task);
    }

    @GetMapping("/tasks/latest")
    public ResponseEntity<?> getLatestTask(@RequestParam String category) {
        IngestionTaskInfo task = ingestionTaskService.getLatestTask(category);
        if (task == null) {
            return ResponseEntity.ok().body(null);
        }
        return ResponseEntity.ok(task);
    }

    @GetMapping("/models/list")
    public ResponseEntity<?> getModelList() {
        try {
            List<ModelInfo> models = dataIngestionService.getModelsByCategory(DataIngestionService.CATEGORY_MODEL);
            return ResponseEntity.ok(models);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error: " + e.getMessage());
        }
    }

    @GetMapping("/point-clouds/list")
    public ResponseEntity<?> getPointCloudList() {
        try {
            List<PointCloudModelInfo> models = dataIngestionService.getPointCloudModels();
            return ResponseEntity.ok(models);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error: " + e.getMessage());
        }
    }

    @GetMapping("/point-clouds/{id}/dataset")
    public ResponseEntity<?> getPointCloudDataset(@PathVariable Long id) {
        return pointCloudModelInfoRepository.findById(id).<ResponseEntity<?>>map(model -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", model.getId());
            result.put("name", model.getName());
            result.put("format", model.getFormat());
            result.put("points", model.getPointCount());
            result.put("encoding", model.getEncoding());
            result.put("metadataUrl", "/api/data/point-clouds/" + id + "/files/metadata.json");
            result.put("hierarchyUrl", "/api/data/point-clouds/" + id + "/files/hierarchy.bin");
            result.put("octreeUrl", "/api/data/point-clouds/" + id + "/files/octree.bin");
            result.put("bounds", Map.of("min", List.of(model.getBboxMinX(), model.getBboxMinY(), model.getBboxMinZ()),
                    "max", List.of(model.getBboxMaxX(), model.getBboxMaxY(), model.getBboxMaxZ())));
            return ResponseEntity.ok(result);
        }).orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body("点云数据不存在"));
    }

    @GetMapping("/point-clouds/{id}/files/{fileName:metadata\\.json|hierarchy\\.bin|octree\\.bin}")
    public void getPointCloudFile(@PathVariable Long id, @PathVariable String fileName,
                                  @RequestHeader(value = "Range", required = false) String range,
                                  HttpServletResponse response) throws IOException {
        PointCloudModelInfo model = pointCloudModelInfoRepository.findById(id).orElse(null);
        if (model == null) { response.sendError(HttpStatus.NOT_FOUND.value()); return; }
        Path root = Path.of(storageRootPath).toAbsolutePath().normalize();
        Path datasetRoot = root.resolve(model.getRootPath()).normalize();
        Path file = datasetRoot.resolve(fileName).normalize();
        if (!datasetRoot.startsWith(root) || !file.startsWith(datasetRoot) || !Files.isRegularFile(file)) {
            response.sendError(HttpStatus.NOT_FOUND.value()); return;
        }
        long size = Files.size(file);
        long start = 0;
        long end = size - 1;
        if (range != null && !range.isBlank()) {
            Matcher matcher = RANGE_PATTERN.matcher(range);
            if (!matcher.matches()) { response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + size); response.sendError(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value()); return; }
            if (matcher.group(1) != null) start = Long.parseLong(matcher.group(1));
            if (matcher.group(2) != null && !matcher.group(2).isBlank()) end = Long.parseLong(matcher.group(2));
            else if (matcher.group(1) != null) end = Math.min(size - 1, start + RANGE_CHUNK_SIZE - 1);
            else { long suffix = Long.parseLong(matcher.group(3)); start = Math.max(0, size - suffix); }
            if (start < 0 || start >= size || end < start) { response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + size); response.sendError(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value()); return; }
            end = Math.min(end, size - 1);
            response.setStatus(HttpStatus.PARTIAL_CONTENT.value());
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + size);
        }
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setHeader(HttpHeaders.CACHE_CONTROL, IMMUTABLE_ASSET_CACHE_CONTROL);
        response.setContentLengthLong(end - start + 1);
        response.setContentType(fileName.endsWith(".json") ? MediaType.APPLICATION_JSON_VALUE : MediaType.APPLICATION_OCTET_STREAM_VALUE);
        try (FileChannel channel = FileChannel.open(file); ServletOutputStream output = response.getOutputStream()) {
            channel.position(start);
            long remaining = end - start + 1;
            ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
            while (remaining > 0) {
                buffer.clear(); buffer.limit((int) Math.min(buffer.capacity(), remaining));
                int read = channel.read(buffer); if (read < 0) break;
                output.write(buffer.array(), 0, read); remaining -= read;
            }
        }
    }

    private static final Pattern RANGE_PATTERN = Pattern.compile("bytes=(?:(\\d+)-(\\d*)|-([0-9]+))");
    private static final long RANGE_CHUNK_SIZE = 8L * 1024 * 1024;

    @DeleteMapping("/models/{id}")
    public ResponseEntity<String> deleteModel(@PathVariable Long id) {
        try {
            dataIngestionService.deleteModel(id);
            return ResponseEntity.ok("Model deleted successfully");
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error: " + e.getMessage());
        }
    }

    @DeleteMapping("/models/clear")
    public ResponseEntity<String> clearAllModels() {
        try {
            dataIngestionService.clearByCategory(DataIngestionService.CATEGORY_MODEL);
            return ResponseEntity.ok("All model data cleared successfully");
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error: " + e.getMessage());
        }
    }

    @DeleteMapping("/point-clouds/clear")
    public ResponseEntity<String> clearAllPointClouds() {
        try {
            dataIngestionService.clearByCategory(DataIngestionService.CATEGORY_POINT_CLOUD);
            return ResponseEntity.ok("All point cloud data cleared successfully");
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error: " + e.getMessage());
        }
    }

    @DeleteMapping("/point-clouds/{id}")
    public ResponseEntity<String> deletePointCloud(@PathVariable Long id) {
        try {
            dataIngestionService.deletePointCloudModel(id);
            return ResponseEntity.ok("Point cloud dataset deleted successfully");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error: " + e.getMessage());
        }
    }
    @GetMapping("/models/{modelId}/octrees")
    public ResponseEntity<List<OctreeInfo>> getModelOctrees(@PathVariable("modelId") Long modelId) {
        List<OctreeInfo> octrees = octreeInfoRepository.findByModelId(modelId);
        return ResponseEntity.ok(octrees);
    }

    @GetMapping("/octree/{octreeId}/nodes")
    public ResponseEntity<List<OctreeNode>> getOctreeNodes(@PathVariable("octreeId") Long octreeId) {
        List<OctreeNode> nodes = octreeNodeRepository.findByOctreeId(octreeId);
        return ResponseEntity.ok(nodes);
    }

    // 提供文件下载接口，前端根据 octreeId, nodeCode, fileName 获取文件（从磁盘读取）
    @GetMapping("/files/{octreeId}/{nodeCode}/{fileName}")
    public void getFile(
            @PathVariable("octreeId") Long octreeId,
            @PathVariable("nodeCode") String nodeCode,
            @PathVariable("fileName") String fileName,
            HttpServletResponse response) throws IOException {
        
        ResolvedNodeFile file = resolveNodeFile(octreeId, nodeCode, fileName);
        if (file == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        String ext = file.fileType();
        if ("json".equals(ext)) {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        } else if ("glb".equals(ext)) {
            response.setContentType("model/gltf-binary");
        } else if ("jpg".equals(ext) || "jpeg".equals(ext)) {
            response.setContentType(MediaType.IMAGE_JPEG_VALUE);
        } else if ("png".equals(ext)) {
            response.setContentType(MediaType.IMAGE_PNG_VALUE);
        } else {
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        }

        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "inline; filename=\"" + URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20")
                + "\"; filename*=UTF-8''" + URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20"));
        response.setHeader(HttpHeaders.CACHE_CONTROL, IMMUTABLE_ASSET_CACHE_CONTROL);

        // 从磁盘读取文件
        Path localFile = Path.of(storageRootPath, file.filePath());
        if (!Files.exists(localFile)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        response.setContentLengthLong(Files.size(localFile));
        try (FileInputStream inputStream = new FileInputStream(localFile.toFile());
             ServletOutputStream out = response.getOutputStream()) {
            inputStream.transferTo(out);
            out.flush();
        }
    }

    // 获取文件 Nginx 访问 URL
    @GetMapping("/files/{octreeId}/{nodeCode}/{fileName}/url")
    public ResponseEntity<?> getFileUrl(
            @PathVariable("octreeId") Long octreeId,
            @PathVariable("nodeCode") String nodeCode,
            @PathVariable("fileName") String fileName) {
        ResolvedNodeFile file = resolveNodeFile(octreeId, nodeCode, fileName);
        if (file == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("文件不存在");
        }
        Map<String, Object> result = new HashMap<>();
        result.put("url", nginxBaseUrl + "/" + file.filePath());
        result.put("fileSize", file.fileSize());
        return ResponseEntity.ok(result);
    }

    private ResolvedNodeFile resolveNodeFile(Long octreeId, String nodeCode, String fileName) {
        String cacheKey = octreeId + "/" + nodeCode + "/" + fileName;
        ResolvedNodeFile cached = nodeFileCache.get(cacheKey);
        if (cached != null) return cached;

        OctreeNode node = octreeNodeRepository.findByOctreeIdAndNodeCode(octreeId, nodeCode);
        if (node == null) return null;
        NodeFile file = nodeFileRepository.findByNodeIdAndFileName(node.getId(), fileName);
        if (file == null || file.getFilePath() == null || file.getFilePath().isBlank()) return null;

        ResolvedNodeFile resolved = new ResolvedNodeFile(file.getFilePath(), file.getFileType(), file.getFileSize());
        ResolvedNodeFile prior = nodeFileCache.putIfAbsent(cacheKey, resolved);
        return prior == null ? resolved : prior;
    }

    private record ResolvedNodeFile(String filePath, String fileType, Long fileSize) {}

    @PostMapping("/models/upload-folder")
    public ResponseEntity<?> uploadModelFolder(@RequestParam("files") MultipartFile[] files,
                                                @RequestParam(value = "folderName", required = false) String incomingFolderName) {
        try {
            if (files == null || files.length == 0) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("未上传任何文件");
            }
            Path tempDir = createUploadTempDirectory("model_upload_");
            String folderName = saveFilesToTemp(files, tempDir, incomingFolderName);
            Path targetDir = tempDir.resolve(folderName);

            String uploadedBy = "system";
            IngestionTaskInfo task = dataIngestionService.createModelIngestionTask(
                    targetDir.toAbsolutePath().toString(), uploadedBy);
            scheduleCleanup(tempDir, task.getTaskId());
            return ResponseEntity.ok(task);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("EXISTS:")) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error: " + e.getMessage());
        }
    }

    @PostMapping("/point-clouds/upload-folder")
    public ResponseEntity<?> uploadPointCloudFolder(@RequestParam("files") MultipartFile[] files,
                                                     @RequestParam(value = "folderName", required = false) String incomingFolderName) {
        try {
            if (files == null || files.length == 0) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("未上传任何文件");
            }
            Path tempDir = createUploadTempDirectory("pc_upload_");
            String folderName = saveFilesToTemp(files, tempDir, incomingFolderName);
            Path targetDir = tempDir.resolve(folderName);

            String uploadedBy = "system";
            IngestionTaskInfo task = dataIngestionService.createPointCloudIngestionTask(
                    targetDir.toAbsolutePath().toString(), uploadedBy);
            scheduleCleanup(tempDir, task.getTaskId());
            return ResponseEntity.ok(task);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("EXISTS:")) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error: " + e.getMessage());
        }
    }

    /**
     * 接收 ZIP 压缩包并在服务器端流式解压。MultipartFile 的输入流不会被读入内存，
     * 解压后的目录交给现有异步入库流程，节点文件最终只在数据库中保存服务器相对路径。
     */
    @PostMapping(value = "/models/upload-archive", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadModelArchive(
            @RequestParam("archive") MultipartFile archive,
            @RequestParam(value = "folderName", required = false) String incomingFolderName) {
        return uploadArchive(archive, incomingFolderName, DataIngestionService.CATEGORY_MODEL);
    }

    @PostMapping(value = "/point-clouds/upload-archive", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadPointCloudArchive(
            @RequestParam("archive") MultipartFile archive,
            @RequestParam(value = "folderName", required = false) String incomingFolderName) {
        return uploadArchive(archive, incomingFolderName, DataIngestionService.CATEGORY_POINT_CLOUD);
    }

    private ResponseEntity<?> uploadArchive(MultipartFile archive, String incomingFolderName, String category) {
        Path tempDir = null;
        try {
            if (archive == null || archive.isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("未上传压缩包");
            }
            String originalName = archive.getOriginalFilename() == null ? "" : archive.getOriginalFilename();
            if (!originalName.toLowerCase().endsWith(".zip")) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("仅支持 ZIP 压缩包");
            }

            String prefix = category.equals(DataIngestionService.CATEGORY_POINT_CLOUD)
                    ? "pc_archive_" : "model_archive_";
            tempDir = createUploadTempDirectory(prefix);
            Path targetDir = extractZip(archive.getInputStream(), tempDir, incomingFolderName, originalName);
            String uploadedBy = "system";
            IngestionTaskInfo task = category.equals(DataIngestionService.CATEGORY_POINT_CLOUD)
                    ? dataIngestionService.createPointCloudIngestionTask(targetDir.toAbsolutePath().toString(), uploadedBy)
                    : dataIngestionService.createModelIngestionTask(targetDir.toAbsolutePath().toString(), uploadedBy);
            scheduleCleanup(tempDir, task.getTaskId());
            return ResponseEntity.ok(task);
        } catch (IllegalStateException e) {
            deleteDirectoryQuietly(tempDir);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        } catch (IllegalArgumentException e) {
            deleteDirectoryQuietly(tempDir);
            if (e.getMessage() != null && e.getMessage().startsWith("EXISTS:")) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (Exception e) {
            deleteDirectoryQuietly(tempDir);
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error: " + e.getMessage());
        }
    }

    private Path extractZip(InputStream input, Path tempDir, String incomingFolderName, String archiveName)
            throws IOException {
        Set<String> topLevelNames = new LinkedHashSet<>();
        try (ZipInputStream zip = new ZipInputStream(input, ZIP_ENTRY_CHARSET)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String entryName = entry.getName().replace('\\', '/');
                if (entryName.isBlank()) {
                    zip.closeEntry();
                    continue;
                }
                Path relative = safeRelativePath(entryName);
                if (relative == null) {
                    throw new IllegalArgumentException("压缩包包含非法路径: " + entry.getName());
                }
                topLevelNames.add(relative.getName(0).toString());
                Path destination = tempDir.resolve(relative).normalize();
                if (!destination.startsWith(tempDir.normalize())) {
                    throw new IllegalArgumentException("压缩包包含越界路径: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(zip, destination, StandardCopyOption.REPLACE_EXISTING);
                }
                zip.closeEntry();
            }
        }
        if (topLevelNames.isEmpty()) {
            throw new IllegalArgumentException("压缩包为空");
        }

        // 常见压缩包带一个根目录，直接将该目录作为模型目录；否则统一包一层目录。
        if (topLevelNames.size() == 1) {
            Path singleRoot = tempDir.resolve(topLevelNames.iterator().next());
            if (Files.isDirectory(singleRoot)) return singleRoot;
        }
        String requestedName = sanitizeSimpleFolderName(incomingFolderName);
        if (requestedName == null) {
            requestedName = sanitizeSimpleFolderName(stripExtension(Path.of(archiveName).getFileName().toString()));
        }
        Path wrappedRoot = tempDir.resolve(requestedName == null ? "upload" : requestedName).normalize();
        Files.createDirectories(wrappedRoot);
        List<Path> children;
        try (var stream = Files.list(tempDir)) {
            children = stream.filter(path -> !path.equals(wrappedRoot)).toList();
        }
        for (Path child : children) {
            Files.move(child, wrappedRoot.resolve(child.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
        return wrappedRoot;
    }

    private static String sanitizeSimpleFolderName(String value) {
        if (value == null || value.isBlank()) return null;
        String name = Path.of(value).getFileName().toString();
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return name.isBlank() || name.equals(".") || name.equals("..") ? null : name;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static void deleteDirectoryQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    private Path createUploadTempDirectory(String prefix) throws IOException {
        Path baseDir = Path.of(storageTempPath).toAbsolutePath().normalize();
        Files.createDirectories(baseDir);
        return Files.createTempDirectory(baseDir, prefix);
    }

    private String saveFilesToTemp(MultipartFile[] files, Path tempDir, String incomingFolderName) throws IOException {
        String folderName = sanitizeSimpleFolderName(incomingFolderName);
        Path normalizedTempDir = tempDir.toAbsolutePath().normalize();
        for (MultipartFile file : files) {
            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null || originalFilename.isBlank()) continue;
            Path relativePath = safeRelativePath(originalFilename);
            if (relativePath == null) {
                throw new IllegalArgumentException("上传文件包含非法路径: " + originalFilename);
            }

            // 如果前端没有传 folderName，从文件路径中提取。对于没有路径前缀
            // 的 multipart 文件名，统一放入 upload 目录，避免目标根目录变成单个文件。
            if (folderName == null) {
                folderName = relativePath.getNameCount() > 1
                        ? sanitizeSimpleFolderName(relativePath.getName(0).toString())
                        : "upload";
            }

            // Chrome 会保留 webkitRelativePath，但部分 Servlet 容器会只保留文件名。
            // 若根目录前缀被移除，使用独立提交的 folderName 重建同一目录结构。
            Path destinationRelativePath = relativePath;
            if (!relativePath.getName(0).toString().equalsIgnoreCase(folderName)) {
                destinationRelativePath = Path.of(folderName).resolve(relativePath);
            }
            Path destFile = normalizedTempDir.resolve(destinationRelativePath).normalize();
            if (!destFile.startsWith(normalizedTempDir)) {
                throw new IllegalArgumentException("上传文件路径越界: " + originalFilename);
            }
            Files.createDirectories(destFile.getParent());
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, destFile, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return folderName != null ? folderName : "upload";
    }

    /**
     * Accept only a relative path made of normal file-name segments. Browser folder
     * uploads and ZIP entries are external input, so a normalized path alone is not
     * enough to prevent accidental traversal or a Windows drive-qualified path.
     */
    private static Path safeRelativePath(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        if (normalized.isBlank()) return null;

        Path path;
        try {
            path = Path.of(normalized);
        } catch (Exception ignored) {
            return null;
        }
        if (path.isAbsolute() || path.getNameCount() == 0) return null;
        for (Path segment : path) {
            String name = segment.toString();
            if (name.isBlank() || name.equals(".") || name.equals("..") || name.contains(":")) {
                return null;
            }
        }
        return path.normalize();
    }

    private void scheduleCleanup(Path tempDir, String taskId) {
        new Thread(() -> {
            try {
                // 等待任务完成（轮询，最多等 60 分钟）
                for (int i = 0; i < 720; i++) {
                    Thread.sleep(5000);
                    IngestionTaskInfo task = ingestionTaskService.getTask(taskId);
                    if (task == null || "SUCCESS".equals(task.getStatus())
                            || "FAILED".equals(task.getStatus())) {
                        break;
                    }
                }
            } catch (InterruptedException ignored) {}
            try {
                if (Files.exists(tempDir)) {
                    Files.walk(tempDir)
                            .sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(File::delete);
                }
            } catch (IOException ignored) {}
        }).start();
    }
}
