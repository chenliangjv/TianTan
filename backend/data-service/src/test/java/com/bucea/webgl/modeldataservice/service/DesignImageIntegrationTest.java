package com.bucea.webgl.modeldataservice.service;

import com.bucea.webgl.modeldataservice.repository.DesignImageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:designimages;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureMockMvc
class DesignImageIntegrationTest {
    @TempDir static Path storage;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("storage.root-path", () -> storage.toString());
        registry.add("storage.temp-path", () -> storage.toString());
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired DesignImageRepository repository;
    @Autowired DesignImageService service;

    @AfterEach void cleanup() throws Exception {
        for (var image : repository.findAll()) service.delete(image.getId());
    }

    private byte[] png() throws Exception {
        var image = new BufferedImage(32, 20, BufferedImage.TYPE_INT_RGB);
        image.setRGB(4, 4, 0x55d6e6);
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
    private JsonNode upload(String name, byte[] bytes) throws Exception {
        var result = mvc.perform(multipart("/api/data/design-images/upload")
                        .file(new MockMultipartFile("files", name, "image/png", bytes)))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsByteArray()).path("results").get(0);
    }

    @Test void uploadPersistsOriginalAndThumbnailAndCanDelete() throws Exception {
        byte[] bytes = png();
        var result = upload("圜丘.png", bytes);
        assertEquals("IMPORTED", result.path("status").asText());
        long id = result.path("image").path("id").asLong();
        assertEquals(32, result.path("image").path("width").asInt());
        var entity = repository.findById(id).orElseThrow();
        Path directory = storage.resolve("design-images").resolve(entity.getStorageKey());
        mvc.perform(get("/api/data/design-images/list")).andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("圜丘"));
        mvc.perform(get("/api/data/design-images/" + id + "/content")).andExpect(status().isOk())
                .andExpect(content().contentType("image/png")).andExpect(content().bytes(bytes)).andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(get("/api/data/design-images/" + id + "/thumbnail")).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        assertNotNull(ImageIO.read(directory.resolve("thumbnail.png").toFile()));
        mvc.perform(delete("/api/data/design-images/" + id)).andExpect(status().isNoContent());
        assertFalse(Files.exists(directory));
        assertFalse(repository.existsById(id));
        mvc.perform(get("/api/data/design-images/" + id + "/content")).andExpect(status().isNotFound());
    }

    @Test void repeatContentIsIdempotentAndDoesNotLeaveExtraDirectories() throws Exception {
        var first = upload("first.png", png());
        var second = upload("renamed.png", png());
        assertEquals("EXISTS", second.path("status").asText());
        assertEquals(first.path("image").path("id"), second.path("image").path("id"));
        assertEquals(1, repository.count());
        try (var entries = Files.list(storage.resolve("design-images"))) { assertEquals(1, entries.count()); }
    }

    @Test void invalidContentIsRejectedAndValidBatchEntryStillImports() throws Exception {
        mvc.perform(multipart("/api/data/design-images/upload")
                        .file(new MockMultipartFile("files", "bad.png", "image/png", "not a png".getBytes()))
                        .file(new MockMultipartFile("files", "valid.png", "image/png", png())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.results[0].status").value("FAILED"))
                .andExpect(jsonPath("$.results[1].status").value("IMPORTED"));
        assertEquals("FAILED", upload("wrong.jpg", png()).path("status").asText());
        assertEquals("FAILED", upload("plan.dwg", png()).path("status").asText());
        assertEquals("FAILED", upload("empty.png", new byte[0]).path("status").asText());
        assertEquals(1, repository.count());
    }

    @Test void pngHeaderPixelBombIsRejectedBeforeDecode() throws Exception {
        byte[] bytes = png();
        // Corrupt dimensions in the IHDR: check happens before any full decode.
        Arrays.fill(bytes, 16, 24, (byte) 0x7f);
        assertEquals("FAILED", upload("huge.png", bytes).path("status").asText());
        assertEquals(0, repository.count());
    }

    @Test void filenameCannotEscapeStorage() throws Exception {
        var result = upload("../../outside.png", png());
        assertEquals("IMPORTED", result.path("status").asText());
        assertEquals("outside.png", result.path("image").path("originalName").asText());
        assertFalse(Files.exists(storage.resolve("outside.png")));
    }

    @Test void actualFourSamplesCanBeImportedAndRead() throws Exception {
        Path publicDir = Path.of("../../frontend/public").toAbsolutePath().normalize();
        for (int i = 1; i <= 4; i++) {
            String name = "yq" + i + ".png";
            byte[] bytes = Files.readAllBytes(publicDir.resolve(name));
            var result = upload(name, bytes);
            assertEquals("IMPORTED", result.path("status").asText());
            assertTrue(result.path("image").path("width").asInt() > 100);
            mvc.perform(get(result.path("image").path("imageUrl").asText())).andExpect(content().bytes(bytes));
        }
        assertEquals(4, repository.count());
    }

    @Test void jpegIsAcceptedAndOriginalBytesPreserved() throws Exception {
        var source = new BufferedImage(50, 30, BufferedImage.TYPE_INT_RGB);
        var output = new ByteArrayOutputStream();
        ImageIO.write(source, "jpeg", output);
        var result = upload("detail.jpg", output.toByteArray());
        assertEquals("IMPORTED", result.path("status").asText());
        assertEquals("JPEG", result.path("image").path("format").asText());
        mvc.perform(get(result.path("image").path("imageUrl").asText()))
                .andExpect(content().contentType("image/jpeg")).andExpect(content().bytes(output.toByteArray()));
    }

    @Test void rejectsOversizedFilesAndExcessiveBatchBeforeStoring() throws Exception {
        var oversized = new MockMultipartFile("files", "large.png", "image/png", png()) {
            @Override public long getSize() { return DesignImageService.MAX_FILE_SIZE + 1; }
        };
        assertThrows(IllegalArgumentException.class, () -> service.ingest(oversized));
        var request = multipart("/api/data/design-images/upload");
        for (int i = 0; i < 21; i++) request.file(new MockMultipartFile("files", "plan.png", "image/png", png()));
        mvc.perform(request).andExpect(status().isBadRequest());
        assertEquals(0, repository.count());
    }
}
