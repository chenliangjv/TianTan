package com.bucea.webgl.modeldataservice.controller;

import com.bucea.webgl.modeldataservice.dto.*;
import com.bucea.webgl.modeldataservice.service.GaussianService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.util.List;

@RestController
@RequestMapping("/api/data/gaussians")
public class GaussianController {
    private final GaussianService service;
    public GaussianController(GaussianService service) { this.service = service; }
    @GetMapping("/list") public List<GaussianModelInfo> list() { return service.list(); }
    @GetMapping("/{id}") public GaussianModelInfo detail(@PathVariable long id) { return service.detail(id); }
    @PostMapping(value = "/upload-folder", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<IngestionTaskInfo> folder(@RequestParam("files") MultipartFile[] files, @RequestParam(defaultValue = "高斯场景") String name) throws IOException {
        return ResponseEntity.accepted().body(service.uploadFolder(files, name));
    }
    @PostMapping(value = "/upload-archive", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<IngestionTaskInfo> archive(@RequestParam MultipartFile archive, @RequestParam(defaultValue = "高斯场景") String name) throws IOException {
        return ResponseEntity.accepted().body(service.uploadArchive(archive, name));
    }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable long id) throws IOException {
        service.delete(id); return ResponseEntity.noContent().build();
    }
    @RequestMapping(value = "/{id}/files/{filename}", method = {RequestMethod.GET, RequestMethod.HEAD})
    public void content(@PathVariable long id, @PathVariable String filename, HttpServletRequest request, HttpServletResponse response) throws IOException {
        var asset = service.file(id, filename); long size = Files.size(asset.path());
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        String etag = "\"" + asset.sha256() + "\"";
        response.setHeader(HttpHeaders.ETAG, etag);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        long start = 0, end = size - 1;
        String range = request.getHeader(HttpHeaders.RANGE);
        String ifRange = request.getHeader(HttpHeaders.IF_RANGE);
        if (ifRange != null && !ifRange.equals(etag)) range = null;
        if (range != null) {
            try {
                List<HttpRange> ranges = HttpRange.parseRanges(range);
                if (ranges.size() != 1) throw new IllegalArgumentException();
                start = ranges.get(0).getRangeStart(size); end = ranges.get(0).getRangeEnd(size);
                if (start < 0 || start >= size || end < start) throw new IllegalArgumentException();
            } catch (IllegalArgumentException error) {
                response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + size); response.setStatus(416); return;
            }
            response.setStatus(206); response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + size);
        }
        response.setContentLengthLong(end - start + 1);
        if (request.getMethod().equals("HEAD")) return;
        try (FileChannel channel = FileChannel.open(asset.path())) {
            channel.position(start); long remaining = end - start + 1; ByteBuffer buffer = ByteBuffer.allocate(256 * 1024);
            OutputStream output = response.getOutputStream();
            while (remaining > 0) {
                buffer.clear(); buffer.limit((int) Math.min(buffer.capacity(), remaining)); int n = channel.read(buffer);
                if (n < 0) throw new EOFException("高斯文件在传输中被截断");
                output.write(buffer.array(), 0, n); remaining -= n;
            }
        }
    }
}
