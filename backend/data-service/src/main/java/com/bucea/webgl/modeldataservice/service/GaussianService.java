package com.bucea.webgl.modeldataservice.service;

import com.bucea.webgl.modeldataservice.dto.*;
import com.bucea.webgl.modeldataservice.entity.*;
import com.bucea.webgl.modeldataservice.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.security.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

@Service
public class GaussianService {
    private static final Logger log = LoggerFactory.getLogger(GaussianService.class);
    private static final long MAX_BYTES = 20L * 1024 * 1024 * 1024;
    private static final int MAX_FILES = 1000;
    private final GaussianModelRepository models;
    private final GaussianAssetRepository assets;
    private final GaussianFileValidator validator;
    private final IngestionTaskService tasks;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final Path root, tempRoot;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), runnable -> { Thread t = new Thread(runnable, "gaussian-ingestion"); t.setDaemon(true); return t; });

    public GaussianService(GaussianModelRepository models, GaussianAssetRepository assets, GaussianFileValidator validator,
            IngestionTaskService tasks, TransactionTemplate transactions, ObjectMapper json,
            @Value("${storage.root-path}") String storage, @Value("${storage.temp-path}") String temp) {
        this.models = models; this.assets = assets; this.validator = validator; this.tasks = tasks;
        this.transactions = transactions; this.json = json;
        root = Path.of(storage).toAbsolutePath().normalize().resolve("gaussians");
        tempRoot = Path.of(temp).toAbsolutePath().normalize().resolve("gaussians");
    }
    @PreDestroy void shutdown() { executor.shutdownNow(); }
    public List<GaussianModelInfo> list() { return models.findAllByOrderByCreatedAtDescIdDesc().stream().map(m -> GaussianModelInfo.from(m, json)).toList(); }
    public GaussianModelInfo detail(long id) { return GaussianModelInfo.from(model(id), json); }
    private GaussianModel model(long id) { return models.findById(id).orElseThrow(() -> new NoSuchElementException("高斯场景不存在或已删除")); }

    public IngestionTaskInfo uploadFolder(MultipartFile[] files, String name) throws IOException {
        if (files.length == 0 || files.length > MAX_FILES) throw new IllegalArgumentException("请选择包含 RAD 的成果文件夹，文件数不能超过 1000");
        Path staging = staging(); boolean queued = false;
        try {
            long total = 0;
            for (MultipartFile file : files) {
                Path relative = safeRelative(file.getOriginalFilename());
                Path destination = staging.resolve(relative);
                Files.createDirectories(destination.getParent());
                try (InputStream input = file.getInputStream()) { total += copyLimited(input, destination, MAX_BYTES - total); }
            }
            IngestionTaskInfo task = enqueue(staging, null, cleanName(name)); queued = true; return task;
        } finally { if (!queued) cleanup(staging); }
    }
    public IngestionTaskInfo uploadArchive(MultipartFile archive, String name) throws IOException {
        String filename = archive.getOriginalFilename();
        if (archive.isEmpty() || filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".zip"))
            throw new IllegalArgumentException("请选择 ZIP 压缩包");
        Path staging = staging(); boolean queued = false;
        try {
            Path zip = staging.resolve("upload.zip");
            try (InputStream input = archive.getInputStream()) { copyLimited(input, zip, MAX_BYTES); }
            IngestionTaskInfo task = enqueue(staging, zip, cleanName(name)); queued = true; return task;
        } finally { if (!queued) cleanup(staging); }
    }
    private Path staging() throws IOException { Files.createDirectories(tempRoot); return Files.createTempDirectory(tempRoot, "upload-"); }
    private IngestionTaskInfo enqueue(Path staging, Path zip, String name) {
        IngestionTaskInfo task = tasks.createTask(staging.toString(), "GAUSSIAN", name, "system");
        try { executor.execute(() -> runImport(task.getTaskId(), staging, zip, name)); }
        catch (RejectedExecutionException error) { tasks.markFailed(task.getTaskId(), "入库队列已满，请稍后重试"); throw new IllegalArgumentException("入库队列已满，请稍后重试"); }
        return task;
    }
    private void runImport(String taskId, Path staging, Path zip, String name) {
        Path owned = null; boolean retained = false;
        try {
            tasks.markRunning(taskId, zip == null ? "正在识别成果文件" : "正在服务器解压 ZIP");
            Path input = staging;
            if (zip != null) { input = staging.resolve("extracted"); Files.createDirectory(input); extract(zip, input); }
            List<Path> entries;
            try (var stream = Files.walk(input)) { entries = stream.filter(Files::isRegularFile).toList(); }
            List<Path> mains = entries.stream().filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".rad")).toList();
            if (mains.size() != 1) throw new IllegalArgumentException("每次入库须包含且仅包含一个 RAD 主体，请按场景分别上传");
            Path main = mains.get(0);
            List<Path> skies = entries.stream().filter(p -> p.getFileName().toString().equalsIgnoreCase("environment.ply")).toList();
            if (skies.size() > 1 || (skies.size() == 1 && !skies.get(0).getParent().equals(main.getParent())))
                throw new IllegalArgumentException("environment.ply 必须与 RAD 位于同一目录，且只能有一个");
            if (entries.stream().anyMatch(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".ply") && !skies.contains(p)))
                throw new IllegalArgumentException("只接收转换后的 RAD 和 environment.ply，请移除原始模型 PLY");
            Path sky = skies.isEmpty() ? null : skies.get(0);
            tasks.setTotals(taskId, 1, sky == null ? 1 : 2);
            tasks.updateMessage(taskId, "正在逐块校验 RAD 与 LOD 树");
            GaussianFileValidator.RadInfo rad = validator.rad(main, done -> {
                if (done % 50 == 0) tasks.updateMessage(taskId, "已校验 " + done + " 个 RAD 数据块");
            });
            tasks.incrementProcessedFile(taskId);
            GaussianFileValidator.PlyInfo ply = null;
            if (sky != null) { tasks.updateMessage(taskId, "正在校验天空高斯"); ply = validator.ply(sky); tasks.incrementProcessedFile(taskId); }
            tasks.updateMessage(taskId, "正在校验文件摘要并归档");
            String mainHash = hash(main), skyHash = sky == null ? "" : hash(sky), contentKey = mainHash + ":" + skyHash;
            Optional<GaussianModel> existing = models.findByContentKey(contentKey);
            if (existing.isPresent()) {
                tasks.incrementProcessedNode(taskId); tasks.markSuccess(taskId, "相同场景及天空已入库：" + existing.get().getName()); return;
            }
            Files.createDirectories(root);
            String key = UUID.randomUUID().toString();
            owned = Files.createDirectory(root.resolve(".staging-" + key));
            Files.copy(main, owned.resolve("scene.rad"));
            if (sky != null) Files.copy(sky, owned.resolve("environment.ply"));
            GaussianModel model = new GaussianModel();
            model.setName(name); model.setStatus("READY"); model.setStorageKey(key); model.setContentKey(contentKey);
            model.setSourceSplatCount(rad.leafCount()); model.setTotalSplatCount(rad.totalCount()); model.setChunkCount(rad.chunks()); model.setRadVersion(rad.version());
            model.setBoundsJson(json.writeValueAsString(rad.bounds()));
            model.setSkyBoundsJson(ply == null ? null : json.writeValueAsString(ply.bounds()));
            model.setTransformJson("{\"position\":[0,0,0],\"quaternion\":[0,0,0,1],\"scale\":[1,1,1]}");
            model.setSkyDefaultEnabled(sky != null); model.setTotalSize(Files.size(main) + (sky == null ? 0 : Files.size(sky)));
            model.setCreatedAt(LocalDateTime.now()); model.setUpdatedAt(model.getCreatedAt());
            json.writeValue(owned.resolve("verification.json").toFile(), Map.of("rad", rad, "mainSha256", mainHash, "skySha256", skyHash, "verifiedAt", model.getCreatedAt().toString()));
            Path destination = root.resolve(key);
            Files.move(owned, destination); owned = destination;
            List<GaussianAsset> records = new ArrayList<>();
            records.add(asset(model, main, "MAIN", "RAD", "scene.rad", mainHash, rad.totalCount()));
            if (sky != null) records.add(asset(model, sky, "SKY", "PLY", "environment.ply", skyHash, ply.count()));
            transactions.executeWithoutResult(status -> { models.saveAndFlush(model); assets.saveAllAndFlush(records); });
            retained = true;
            tasks.incrementProcessedNode(taskId); tasks.markSuccess(taskId, "入库完成，已校验 " + rad.chunks() + " 个数据块" + (sky == null ? "（无天空）" : "，天空已关联"));
        } catch (Exception error) {
            log.error("高斯场景入库失败，任务 {}", taskId, error);
            tasks.markFailed(taskId, error instanceof IllegalArgumentException ? error.getMessage() : "入库失败，请检查文件完整性、存储空间或服务日志");
        } finally { if (!retained && owned != null) cleanup(owned); cleanup(staging); }
    }
    private GaussianAsset asset(GaussianModel model, Path source, String role, String format, String filename, String hash, long count) throws IOException {
        GaussianAsset asset = new GaussianAsset(); asset.setModel(model); asset.setRole(role); asset.setFormat(format);
        asset.setOriginalName(source.getFileName().toString()); asset.setRelativePath(model.getStorageKey() + "/" + filename);
        asset.setFileSize(Files.size(source)); asset.setSha256(hash); asset.setSplatCount(count); return asset;
    }
    public record AssetFile(Path path, String sha256) {}
    public AssetFile file(long id, String filename) throws IOException {
        if (!Set.of("scene.rad", "environment.ply").contains(filename)) throw new NoSuchElementException("文件不存在");
        GaussianModel model = model(id);
        GaussianAsset asset = assets.findByModelId(id).stream().filter(a -> a.getRole().equals(filename.endsWith(".rad") ? "MAIN" : "SKY"))
                .findFirst().orElseThrow(() -> new NoSuchElementException("场景未关联此文件"));
        Path file = directory(model).resolve(filename);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !file.toRealPath().startsWith(root.toRealPath())) throw new NoSuchElementException("高斯文件不存在");
        return new AssetFile(file, asset.getSha256());
    }
    public synchronized void delete(long id) throws IOException {
        GaussianModel model = model(id); Path directory = directory(model);
        // Move only this scene's owned directory, allowing a failed DB transaction to roll back.
        Path retired = root.resolve(".deleted-" + model.getStorageKey()); boolean moved = false;
        if (Files.exists(directory)) { Files.move(directory, retired); moved = true; }
        try { transactions.executeWithoutResult(status -> { assets.deleteForModel(id); models.delete(model); models.flush(); }); }
        catch (RuntimeException error) { if (moved) Files.move(retired, directory); throw error; }
        if (moved) cleanup(retired);
    }
    private Path directory(GaussianModel model) throws IOException {
        if (!model.getStorageKey().matches("[a-f0-9-]{36}")) throw new IOException("无效的场景存储标识");
        Path directory = root.resolve(model.getStorageKey());
        if (Files.exists(directory) && !directory.toRealPath().startsWith(root.toRealPath())) throw new IOException("场景路径越界");
        return directory;
    }
    private static String cleanName(String name) {
        if (name == null || name.isBlank()) return "高斯场景";
        String cleaned = name.strip(); if (cleaned.length() > 200) throw new IllegalArgumentException("场景名称不能超过 200 字"); return cleaned;
    }
    static Path safeRelative(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("文件路径不能为空");
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains(":")) throw new IllegalArgumentException("文件包含绝对路径或非法路径");
        for (String part : normalized.split("/")) {
            if (part.isBlank() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")
                    || part.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?") || part.matches(".*[<>\"|?*\\p{Cntrl}].*"))
                throw new IllegalArgumentException("文件包含非法路径: " + name);
        }
        return Path.of(normalized);
    }
    private static long copyLimited(InputStream input, Path destination, long limit) throws IOException {
        long total = 0;
        try (OutputStream output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
            byte[] bytes = new byte[1024 * 1024]; int n;
            while ((n = input.read(bytes)) != -1) { total += n; if (total > limit) throw new IllegalArgumentException("成果总大小不能超过 20 GiB"); output.write(bytes, 0, n); }
        }
        return total;
    }
    private static void extract(Path zip, Path target) throws IOException {
        // UTF-8 flag overrides the fallback charset; older Windows ZIPs use GBK names.
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zip), Charset.forName("GB18030"))) {
            ZipEntry entry; int count = 0; long total = 0;
            while ((entry = input.getNextEntry()) != null) {
                if (++count > MAX_FILES) throw new IllegalArgumentException("压缩包文件数不能超过 1000");
                String name = entry.getName(); if (entry.isDirectory()) name = name.replaceAll("[/\\\\]+$", "");
                Path destination = target.resolve(safeRelative(name)).normalize();
                if (!destination.startsWith(target)) throw new IllegalArgumentException("压缩包路径越界");
                if (entry.isDirectory()) Files.createDirectories(destination);
                else { Files.createDirectories(destination.getParent()); total += copyLimited(input, destination, MAX_BYTES - total); }
                input.closeEntry();
            }
        }
    }
    private static String hash(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) { byte[] bytes = new byte[1024 * 1024]; int n; while ((n = input.read(bytes)) != -1) digest.update(bytes, 0, n); }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private void cleanup(Path directory) {
        Path normalized = directory.toAbsolutePath().normalize();
        if (!(normalized.startsWith(root) && !normalized.equals(root)) && !(normalized.startsWith(tempRoot) && !normalized.equals(tempRoot)))
            throw new IllegalArgumentException("拒绝清理存储根目录以外的路径");
        if (!Files.exists(normalized)) return;
        try (var entries = Files.walk(normalized)) {
            for (Path path : entries.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException error) { log.warn("高斯临时目录清理失败: {}", normalized, error); }
    }
}
