package com.bucea.webgl.modeldataservice.service;

import com.bucea.webgl.modeldataservice.repository.GaussianModelRepository;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:gaussians;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureMockMvc
class GaussianIntegrationTest {
    @TempDir static Path storage;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("storage.root-path", () -> storage.toString());
        registry.add("storage.temp-path", () -> storage.resolve("temporary").toString());
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired GaussianModelRepository models;
    @Autowired GaussianService service;
    @Autowired GaussianFileValidator validator;
    @Autowired IngestionTaskService tasks;
    @AfterEach void cleanup() throws Exception { for (var m : models.findAll()) service.delete(m.getId()); }

    private byte[] rad(boolean invalidTree) throws Exception {
        int n = 3;
        Map<String, byte[]> properties = new LinkedHashMap<>();
        ByteBuffer centers = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : new float[]{0,0,0, -1,-2,-3, 1,2,3}) centers.putFloat(value);
        properties.put("center", centers.array());
        properties.put("alpha", new byte[]{0,60,0,60,0,60}); properties.put("rgb", new byte[9]);
        properties.put("scales", new byte[9]); properties.put("orientation", new byte[9]);
        properties.put("child_count", new byte[]{2,0,0,0,0,0}); properties.put("child_start", new byte[]{(byte)(invalidTree ? 0 : 1),0,0,0,0,0,0,0,0,0,0,0});
        Map<String, String> encodings = Map.of("center","f32","alpha","f16","rgb","r8","scales","ln_0r8","orientation","oct88r8","child_count","u16","child_start","u32");
        ByteArrayOutputStream payload = new ByteArrayOutputStream(); List<Object> refs = new ArrayList<>();
        for (var entry : properties.entrySet()) {
            Deflater compressor = new Deflater(6, true); byte[] packed;
            try { compressor.setInput(entry.getValue()); compressor.finish(); byte[] buffer = new byte[1024]; int length = compressor.deflate(buffer); packed = Arrays.copyOf(buffer, length); }
            finally { compressor.end(); }
            refs.add(Map.of("property",entry.getKey(),"encoding",encodings.get(entry.getKey()),"compression","gz","offset",payload.size(),"bytes",packed.length));
            payload.write(packed); pad(payload);
        }
        byte[] chunkHeader = json.writeValueAsBytes(Map.of("version",1,"base",0,"count",n,"lodTree",true,"properties",refs,"payloadBytes",payload.size()));
        ByteArrayOutputStream chunk = new ByteArrayOutputStream(); chunk.write("RADC".getBytes(StandardCharsets.US_ASCII)); writeInt(chunk, chunkHeader.length); chunk.write(chunkHeader); pad(chunk);
        chunk.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(payload.size()).array()); chunk.write(payload.toByteArray());
        byte[] header = json.writeValueAsBytes(Map.of("version",1,"count",n,"lodTree",true,"maxSh",0,"chunkSize",65536,"allChunkBytes",chunk.size(),
                "comment","{\"input_splat_count\":2}","chunks",List.of(Map.of("offset",0,"bytes",chunk.size()))));
        ByteArrayOutputStream output = new ByteArrayOutputStream(); output.write("RAD0".getBytes(StandardCharsets.US_ASCII)); writeInt(output, header.length); output.write(header); pad(output); output.write(chunk.toByteArray()); return output.toByteArray();
    }
    private static void writeInt(OutputStream out, int value) throws IOException { out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()); }
    private static void pad(ByteArrayOutputStream out) { while (out.size() % 8 != 0) out.write(0); }
    private byte[] sky() throws Exception {
        StringBuilder header = new StringBuilder("ply\nformat binary_little_endian 1.0\nelement vertex 1\n");
        for (String property : List.of("x","y","z","nx","ny","nz","f_dc_0","f_dc_1","f_dc_2","opacity","scale_0","scale_1","scale_2","rot_0","rot_1","rot_2","rot_3")) header.append("property float ").append(property).append('\n');
        header.append("end_header\n"); ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write(header.toString().getBytes(StandardCharsets.US_ASCII));
        ByteBuffer data = ByteBuffer.allocate(68).order(ByteOrder.LITTLE_ENDIAN); data.putFloat(8, 2000); data.putFloat(52, 1); out.write(data.array()); return out.toByteArray();
    }
    private JsonNode waitForTask(byte[] response, String expected) throws Exception {
        String id = json.readTree(response).path("taskId").asText(); long end = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < end) {
            var task = tasks.getTask(id);
            if (Set.of("SUCCESS","FAILED").contains(task.getStatus())) { assertEquals(expected, task.getStatus(), task.getMessage()); return json.valueToTree(task); }
            Thread.sleep(40);
        }
        fail("Import task did not finish"); return null;
    }
    private long folder(byte[] rad, byte[] sky) throws Exception {
        var request = multipart("/api/data/gaussians/upload-folder")
                .file(new MockMultipartFile("files","成果/scene.rad","application/octet-stream",rad));
        request.param("name","圜丘坛");
        if (sky != null) request.file(new MockMultipartFile("files","成果/environment.ply","application/octet-stream",sky));
        waitForTask(mvc.perform(request).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsByteArray(), "SUCCESS");
        return models.findAll().get(0).getId();
    }
    @Test void folderAssociatesSkyStreamsRangesAndDeletesBoth() throws Exception {
        byte[] bytes = rad(false), environment = sky(); long id = folder(bytes, environment);
        String base = "/api/data/gaussians/" + id;
        mvc.perform(get(base)).andExpect(jsonPath("$.hasSky").value(true)).andExpect(jsonPath("$.sourceSplatCount").value(2))
                .andExpect(jsonPath("$.bounds.max[2]").value(3)).andExpect(jsonPath("$.skyBounds.max[2]").value(2000));
        mvc.perform(get(base + "/files/scene.rad").header("Range","bytes=4-15")).andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range","bytes 4-15/" + bytes.length)).andExpect(content().bytes(Arrays.copyOfRange(bytes,4,16)));
        mvc.perform(get(base + "/files/scene.rad").header("Range","bytes=-8")).andExpect(status().isPartialContent()).andExpect(content().bytes(Arrays.copyOfRange(bytes,bytes.length-8,bytes.length)));
        mvc.perform(get(base + "/files/scene.rad").header("Range","bytes=9999999999999999999999999-")).andExpect(status().isRequestedRangeNotSatisfiable());
        mvc.perform(get(base + "/files/scene.rad").header("Range","bytes=0-1,3-4")).andExpect(status().isRequestedRangeNotSatisfiable());
        mvc.perform(head(base + "/files/scene.rad")).andExpect(header().string("Content-Length",String.valueOf(bytes.length))).andExpect(content().bytes(new byte[0]));
        mvc.perform(get(base + "/files/environment.ply")).andExpect(content().bytes(environment));
        mvc.perform(get(base + "/files/unknown.txt")).andExpect(status().isNotFound());
        assertEquals(id, folder(bytes, environment)); assertEquals(1, models.count());
        Path directory = storage.resolve("gaussians").resolve(models.findById(id).orElseThrow().getStorageKey());
        mvc.perform(delete(base)).andExpect(status().isNoContent()); assertFalse(Files.exists(directory));
        mvc.perform(get(base + "/files/environment.ply")).andExpect(status().isNotFound());
    }
    private byte[] zip(Map<String,byte[]> files) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) { for (var file : files.entrySet()) { zip.putNextEntry(new ZipEntry(file.getKey())); zip.write(file.getValue()); zip.closeEntry(); } }
        return output.toByteArray();
    }
    private JsonNode archive(Map<String,byte[]> files, String expected) throws Exception {
        return waitForTask(mvc.perform(multipart("/api/data/gaussians/upload-archive")
                .file(new MockMultipartFile("archive","场景.zip","application/zip",zip(files))).param("name","场景压缩包")).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsByteArray(), expected);
    }
    @Test void nestedArchiveImportsWithoutSky() throws Exception {
        archive(Map.of("外层/成果/main.rad",rad(false)), "SUCCESS");
        assertFalse(service.list().get(0).hasSky()); assertEquals(1, models.count());
    }
    @Test void archiveRejectsTraversalAndBrokenPairs() throws Exception {
        archive(Map.of("../escaped.rad",rad(false)), "FAILED");
        assertFalse(Files.exists(storage.resolve("escaped.rad")));
        archive(Map.of("scene.rad",rad(false),"another/environment.ply",sky()), "FAILED");
        archive(Map.of("scene.rad",rad(false),"environment.ply","bad".getBytes()), "FAILED");
        archive(Map.of("a.rad",rad(false),"b.rad",rad(false)), "FAILED");
        assertEquals(0, models.count());
    }
    @Test void corruptTreesAndTruncationAreRejected() throws Exception {
        archive(Map.of("scene.rad",rad(true)), "FAILED");
        byte[] bytes = rad(false);
        archive(Map.of("scene.rad",Arrays.copyOf(bytes,bytes.length-1)), "FAILED");
        assertEquals(0, models.count());
    }
    @Test void absoluteAndWindowsSpecialUploadNamesAreRejected() throws Exception {
        for (String name : List.of("../outside.rad","D:/scene.rad","/absolute.rad","CON.rad","bad /scene.rad")) {
            mvc.perform(multipart("/api/data/gaussians/upload-folder").file(new MockMultipartFile("files",name,"application/octet-stream",rad(false)))).andExpect(status().isBadRequest());
        }
        assertEquals(0, models.count());
    }
    @Test void actualConvertedScenePassesStreamingValidationWhenAvailable() throws Exception {
        Path directory = Path.of("../../3DGS/圜丘坛高斯格式/ply-result/point_cloud/output");
        Assumptions.assumeTrue(Files.exists(directory.resolve("point_cloud-lod.rad")) && Files.exists(directory.resolve("environment.ply")));
        var rad = validator.rad(directory.resolve("point_cloud-lod.rad"), done -> {});
        var ply = validator.ply(directory.resolve("environment.ply"));
        assertEquals(23823106, rad.leafCount()); assertEquals(540,rad.chunks()); assertEquals(15932,ply.count());
        json.writeValue(Path.of("target/gaussian-validation.json").toFile(), Map.of("rad",rad,"sky",ply));
    }
}
