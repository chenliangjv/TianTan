package com.bucea.webgl.modeldataservice.service;

import com.bucea.webgl.modeldataservice.dto.DesignImageInfo;
import com.bucea.webgl.modeldataservice.entity.DesignImage;
import com.bucea.webgl.modeldataservice.repository.DesignImageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.FileImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class DesignImageService {
    public static final long MAX_FILE_SIZE = 20L * 1024 * 1024;
    private static final long MAX_PIXELS = 40_000_000;
    private static final Logger log = LoggerFactory.getLogger(DesignImageService.class);
    private final DesignImageRepository repository;
    private final TransactionTemplate transactions;
    private final Path storageRoot;

    public DesignImageService(DesignImageRepository repository, TransactionTemplate transactions,
                              @Value("${storage.root-path}") String root) {
        this.repository = repository;
        this.transactions = transactions;
        this.storageRoot = Path.of(root).toAbsolutePath().normalize().resolve("design-images");
    }

    public record ImportResult(String originalName, String status, DesignImageInfo image, String message) {}
    public record ImageFile(Path path, String contentType, String originalName) {}

    public List<DesignImageInfo> list() {
        return repository.findAllByOrderByCreateTimeDescIdDesc().stream().map(DesignImageInfo::from).toList();
    }

    public ImportResult ingest(MultipartFile upload) throws IOException {
        String name = safeName(upload.getOriginalFilename());
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!List.of("png", "jpg", "jpeg").contains(extension)) throw new IllegalArgumentException("仅支持 PNG、JPG、JPEG 图片，暂不支持 DWG");
        if (upload.isEmpty() || upload.getSize() > MAX_FILE_SIZE) throw new IllegalArgumentException("图片不能为空，且单张不能超过 20 MB");
        Files.createDirectories(storageRoot);
        Path directory = Files.createDirectory(storageRoot.resolve(UUID.randomUUID().toString()));
        Path original = directory.resolve("original");
        Path thumbnail = directory.resolve("thumbnail.png");
        boolean retained = false;
        try {
            MessageDigest digest = sha256();
            long bytes = 0;
            try (var input = upload.getInputStream(); var output = Files.newOutputStream(original)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    bytes += read;
                    if (bytes > MAX_FILE_SIZE) throw new IllegalArgumentException("单张图片不能超过 20 MB");
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            String hash = HexFormat.of().formatHex(digest.digest());
            DesignImage existing = repository.findByContentHash(hash).orElse(null);
            if (existing != null) {
                if (extension.equals("png") != existing.getFormat().equals("PNG")) throw new IllegalArgumentException("图片内容与文件扩展名不匹配");
                return new ImportResult(name, "EXISTS", DesignImageInfo.from(existing), "相同图片已入库");
            }

            DesignImage image = readAndThumbnail(original, thumbnail, extension);
            image.setName(name.substring(0, Math.min(200, name.lastIndexOf('.'))));
            image.setOriginalName(name);
            image.setStorageKey(directory.getFileName().toString());
            image.setContentHash(hash);
            image.setFileSize(bytes);
            image.setCreateTime(LocalDateTime.now());
            try {
                DesignImage saved = transactions.execute(status -> repository.saveAndFlush(image));
                retained = true;
                return new ImportResult(name, "IMPORTED", DesignImageInfo.from(saved), "入库成功");
            } catch (DataIntegrityViolationException duplicate) {
                existing = repository.findByContentHash(hash).orElseThrow(() -> duplicate);
                return new ImportResult(name, "EXISTS", DesignImageInfo.from(existing), "相同图片已入库");
            }
        } finally {
            if (!retained) cleanup(directory);
        }
    }

    private DesignImage readAndThumbnail(Path original, Path thumbnail, String extension) throws IOException {
        try (var input = new FileImageInputStream(original.toFile())) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("无法识别图片内容，请检查文件是否损坏");
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!(format.equals("png") || format.equals("jpeg")) || (extension.equals("png") != format.equals("png"))) {
                    throw new IllegalArgumentException("图片内容与文件扩展名不匹配");
                }
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
                    throw new IllegalArgumentException("图片像素总量不能超过 4000 万，请先降低分辨率");
                }
                var params = reader.getDefaultReadParam();
                int step = Math.max(1, Math.max(width, height) / 720);
                params.setSourceSubsampling(step, step, 0, 0);
                BufferedImage decoded = reader.read(0, params);
                double scale = Math.min(1, 360.0 / Math.max(decoded.getWidth(), decoded.getHeight()));
                BufferedImage preview = new BufferedImage(Math.max(1, (int) (decoded.getWidth() * scale)),
                        Math.max(1, (int) (decoded.getHeight() * scale)), BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = preview.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    graphics.drawImage(decoded, 0, 0, preview.getWidth(), preview.getHeight(), null);
                    if (!ImageIO.write(preview, "png", thumbnail.toFile())) throw new IOException("无法生成缩略图");
                } finally {
                    graphics.dispose(); decoded.flush(); preview.flush();
                }
                DesignImage image = new DesignImage();
                image.setFormat(format.equals("png") ? "PNG" : "JPEG");
                image.setWidth(width); image.setHeight(height);
                return image;
            } finally { reader.dispose(); }
        }
    }

    public ImageFile file(Long id, boolean thumbnail) throws IOException {
        DesignImage image = repository.findById(id).orElseThrow(() -> new NoSuchElementException("图像不存在或已删除"));
        Path file = directory(image).resolve(thumbnail ? "thumbnail.png" : "original");
        if (!Files.isRegularFile(file) || !file.toRealPath().startsWith(storageRoot.toRealPath())) throw new NoSuchElementException("图像文件不存在");
        return new ImageFile(file, thumbnail || image.getFormat().equals("PNG") ? "image/png" : "image/jpeg", image.getOriginalName());
    }

    public void delete(Long id) throws IOException {
        DesignImage image = repository.findById(id).orElseThrow(() -> new NoSuchElementException("图像不存在或已删除"));
        Path directory = directory(image);
        transactions.executeWithoutResult(status -> { repository.delete(image); repository.flush(); });
        // Only our two generated files are removed, never public sample sources.
        // Database deletion is committed before cleanup so a DB failure cannot orphan the asset.
        cleanup(directory);
    }

    private Path directory(DesignImage image) throws IOException {
        if (!image.getStorageKey().matches("[0-9a-f-]{36}")) throw new IOException("非法图像存储位置");
        Path path = storageRoot.resolve(image.getStorageKey()).normalize();
        if (!path.startsWith(storageRoot) || (Files.exists(path) && !path.toRealPath().startsWith(storageRoot.toRealPath()))) {
            throw new IOException("非法图像存储位置");
        }
        return path;
    }

    private void cleanup(Path directory) {
        try {
            Files.deleteIfExists(directory.resolve("thumbnail.png"));
            Files.deleteIfExists(directory.resolve("original"));
            Files.deleteIfExists(directory);
        } catch (IOException error) { log.warn("图像临时或已删除文件清理失败: {}", directory, error); }
    }

    private static String safeName(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("图片名称不能为空");
        String name = raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_");
        if (name.length() > 240 || name.lastIndexOf('.') <= 0) throw new IllegalArgumentException("图片名称无效或过长");
        return name;
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
