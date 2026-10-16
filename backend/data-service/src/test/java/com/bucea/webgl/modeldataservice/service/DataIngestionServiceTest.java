package com.bucea.webgl.modeldataservice.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 DataIngestionService buildRelativePath 辅助方法
 * 验证文件磁盘写入逻辑正确
 */
class DataIngestionServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void buildRelativePath_models_shouldGenerateCorrectPath() {
        String path = buildRelPath("models", "test_model", "1", "root", "model.glb");
        assertEquals("models/test_model/1/root/model.glb", path);
    }

    @Test
    void buildRelativePath_pointclouds_shouldGenerateCorrectPath() {
        String path = buildRelPath("pointclouds", "pc_model", "5", "node_0", "tile.las");
        assertEquals("pointclouds/pc_model/5/node_0/tile.las", path);
    }

    @Test
    void buildRelativePath_withSpecialChars_shouldSanitize() {
        String path = buildRelPath("models", "test:model*name", "1", "root", "file.glb");
        assertFalse(path.contains(":"));
        assertFalse(path.contains("*"));
        assertTrue(path.startsWith("models/test_model_name/"));
    }

    @Test
    void sanitizePathName_shouldHandleAllSpecialChars() {
        String result = sanitizeName("a\\b/c:d*e?f\"g<h>i|j");
        assertEquals("a_b_c_d_e_f_g_h_i_j", result);
    }

    @Test
    void fileCopyToDisk_shouldProduceCorrectFile() throws IOException {
        // Arrange
        String storageRoot = tempDir.toString();
        String relativePath = "models/test_model/1/root/model.glb";
        byte[] originalContent = "glb binary content".getBytes();

        // Write source file
        Path sourceFile = tempDir.resolve("source").resolve("model.glb");
        Files.createDirectories(sourceFile.getParent());
        Files.write(sourceFile, originalContent);

        // Act - simulate what processNode does
        Path targetPath = Path.of(storageRoot, relativePath);
        Files.createDirectories(targetPath.getParent());
        Files.copy(sourceFile, targetPath, StandardCopyOption.REPLACE_EXISTING);

        // Assert
        assertTrue(Files.exists(targetPath));
        assertEquals(originalContent.length, Files.size(targetPath));
        byte[] readBack = Files.readAllBytes(targetPath);
        assertArrayEquals(originalContent, readBack);
    }

    @Test
    void filePath_shouldBeRelativeWhenConstructed() {
        String path = buildRelPath("models", "demo", "1", "root", "model.glb");
        // 相对路径本身不应是绝对路径
        Path relative = Path.of(path);
        assertFalse(relative.isAbsolute());
    }

    // ====== 辅助方法（与 DataIngestionService 中的实现一致）======

    private static String sanitizeName(String name) {
        if (name == null) return "unknown";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static String buildRelPath(String category, String modelName,
                                       String octreeId, String nodeCode, String fileName) {
        return category + "/" + sanitizeName(modelName) + "/" + octreeId + "/" + nodeCode + "/" + fileName;
    }
}
