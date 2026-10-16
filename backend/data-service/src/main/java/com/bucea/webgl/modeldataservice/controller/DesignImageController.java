package com.bucea.webgl.modeldataservice.controller;

import com.bucea.webgl.modeldataservice.dto.DesignImageInfo;
import com.bucea.webgl.modeldataservice.service.DesignImageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/data/design-images")
public class DesignImageController {
    private static final Logger log = LoggerFactory.getLogger(DesignImageController.class);
    private final DesignImageService service;
    public DesignImageController(DesignImageService service) { this.service = service; }

    @GetMapping("/list")
    public List<DesignImageInfo> list() { return service.list(); }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestParam("files") MultipartFile[] files) {
        if (files.length == 0 || files.length > 20) throw new IllegalArgumentException("每批请选择 1 至 20 张图片");
        List<DesignImageService.ImportResult> results = new ArrayList<>();
        for (MultipartFile file : files) {
            try { results.add(service.ingest(file)); }
            catch (IllegalArgumentException error) {
                results.add(new DesignImageService.ImportResult(file.getOriginalFilename(), "FAILED", null, error.getMessage()));
            } catch (Exception error) {
                log.error("图像入库失败", error);
                results.add(new DesignImageService.ImportResult(file.getOriginalFilename(), "FAILED", null, "图像入库失败，请检查图片内容或稍后重试"));
            }
        }
        return Map.of("results", results);
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<Resource> content(@PathVariable Long id) throws IOException { return image(id, false); }

    @GetMapping("/{id}/thumbnail")
    public ResponseEntity<Resource> thumbnail(@PathVariable Long id) throws IOException { return image(id, true); }

    private ResponseEntity<Resource> image(Long id, boolean thumbnail) throws IOException {
        var file = service.file(id, thumbnail);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(Files.size(file.path())).cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(file.originalName(), StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(file.path()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) throws IOException {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
