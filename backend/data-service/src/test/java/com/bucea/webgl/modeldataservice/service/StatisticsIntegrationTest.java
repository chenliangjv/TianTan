package com.bucea.webgl.modeldataservice.service;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:statistics;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureMockMvc
@Transactional
class StatisticsIntegrationTest {
    @TempDir static Path storage;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("storage.root-path", () -> storage.toString());
        registry.add("storage.temp-path", () -> storage.resolve("temporary").toString());
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    private JsonNode statistics(int days) {
        // 16:30 UTC is already September 22 in Beijing.
        return json.valueToTree(new StatisticsService(jdbc, Clock.fixed(Instant.parse("2026-09-21T16:30:00Z"), ZoneOffset.UTC)).getStatistics(days));
    }

    private void seed() {
        jdbc.update("INSERT INTO model_info(id, name, data_category, create_time, upload_time) VALUES (101, 'legacy', NULL, '2026-09-16 00:00:00', NULL), (102, 'model', 'MODEL', '2020-01-01', '2026-09-22 00:00:00'), (103, 'old cloud', 'POINT_CLOUD', '2026-09-22', NULL)");
        jdbc.update("INSERT INTO octree_info(id, model_id) VALUES (101,101),(102,102),(103,103)");
        jdbc.update("INSERT INTO octree_node(id, octree_id) VALUES (101,101),(102,102),(103,103)");
        jdbc.update("INSERT INTO node_file(node_id,file_size) VALUES (101,100),(102,200),(102,NULL),(103,9999),(999,9999)");
        jdbc.update("INSERT INTO point_cloud_model_info(name,storage_key,format,root_path,status,point_count,total_size,create_time) VALUES ('cloud','cloud','POTREE_2','test','READY',1000,400,'2026-09-21 23:59:59')");
        jdbc.update("INSERT INTO gaussian_model(id,name,status,storage_key,content_key,source_splat_count,total_splat_count,chunk_count,total_size,sky_default_enabled,created_at,updated_at) VALUES (101,'scene','READY','scene','scene',2000,3000,8,600,false,'2026-09-22 00:00:00','2026-09-22 00:00:00')");
        jdbc.update("INSERT INTO gaussian_asset(model_id,role,format,original_name,relative_path,file_size,sha256) VALUES (101,'MAIN','RAD','scene.rad','scene.rad',500,'main'),(101,'SKY','PLY','sky.ply','sky.ply',100,'sky')");
        jdbc.update("INSERT INTO design_image(id,name,original_name,content_hash,storage_key,format,width,height,file_size,create_time) VALUES (101,'drawing','drawing.png','drawing','drawing','PNG',20,30,50,'2026-09-15 23:59:59'),(102,'drawing2','drawing.jpg','drawing2','drawing2','JPEG',20,30,70,'2026-09-22 23:59:59')");
        for (String category : new String[]{"MODEL", "POINT_CLOUD", "GAUSSIAN", "DESIGN", "DWG"}) {
            for (String status : new String[]{"PENDING", "RUNNING", "SUCCESS", "FAILED"}) {
                jdbc.update("INSERT INTO ingestion_task(task_id,data_dir_path,data_category,status,create_time) VALUES (?, 'test', ?, ?, '2026-09-22')", category + status, category, status);
            }
        }
        jdbc.update("INSERT INTO disease_annotation(model_id,type,severity,status,created_time,updated_time) VALUES (101,'crack','LOW','ACTIVE','2026-09-22','2026-09-22'),(101,'crack','LOW','ARCHIVED','2026-09-22','2026-09-22')");
    }

    @Test void aggregatesFourCategoriesWithoutLegacyCloudOrSkyDoubleCounting() {
        seed();
        var s = statistics(7);
        assertEquals(2, s.at("/models/totalModels").asLong());
        assertEquals(2, s.at("/models/octreeInfoCount").asLong());
        assertEquals(2, s.at("/models/octreeNodes").asLong());
        assertEquals(3, s.at("/models/nodeFiles").asLong());
        assertEquals(300, s.at("/models/fileSizeBytes").asLong());
        assertEquals(1000, s.at("/pointClouds/totalPoints").asLong());
        assertEquals(1, s.at("/gaussians/totalScenes").asLong());
        assertEquals(2000, s.at("/gaussians/sourceSplatCount").asLong());
        assertEquals(3000, s.at("/gaussians/totalSplatCount").asLong());
        assertEquals(8, s.at("/gaussians/chunkCount").asLong());
        assertEquals(1, s.at("/gaussians/scenesWithSky").asLong());
        assertEquals(500, s.at("/gaussians/mainBytes").asLong());
        assertEquals(100, s.at("/gaussians/skyBytes").asLong());
        assertEquals(600, s.at("/gaussians/fileSizeBytes").asLong());
        assertEquals(2, s.at("/designs/totalImages").asLong());
        assertEquals(1, s.at("/designs/formats/PNG").asLong());
        assertEquals(1, s.at("/designs/formats/JPEG").asLong());
        assertEquals(1420, s.at("/totals/totalBytesAll").asLong());
        assertEquals(6, s.at("/totals/totalAssets").asLong());
        assertEquals(12, s.at("/ingestionTasks/total").asLong());
        assertEquals(3, s.at("/ingestionTasks/success").asLong());
        assertEquals(2, s.at("/diseases/total").asLong());
        assertEquals(1, s.at("/diseases/active").asLong());
    }

    @Test void fillsDaysAndUsesBeijingMidnightWithoutFilteringTotals() {
        seed();
        for (int days : new int[]{7,30,90}) {
            var s = statistics(days);
            assertEquals(days, s.at("/trend/items").size());
            assertEquals("2026-09-22", s.at("/trend/endDate").asText());
            assertEquals(1420, s.at("/totals/totalBytesAll").asLong());
            assertEquals(1, s.at("/trend/items/" + (days-1) + "/GAUSSIAN").asLong());
        }
        var s = statistics(7);
        assertEquals("2026-09-16", s.at("/trend/startDate").asText());
        assertEquals(1, s.at("/trend/items/0/MODEL").asLong());
        assertEquals(0, s.at("/trend/items/0/DESIGN").asLong());
        assertEquals(0, s.at("/trend/items/1/MODEL").asLong());
        assertEquals(1, s.at("/trend/items/5/POINT_CLOUD").asLong());
        assertEquals(1, s.at("/trend/items/6/MODEL").asLong());
        assertEquals(1, s.at("/trend/items/6/DESIGN").asLong());
    }

    @Test void deletionIsReflectedWithoutCachedCounts() {
        seed();
        statistics(7);
        jdbc.update("DELETE FROM gaussian_asset WHERE model_id=101");
        jdbc.update("DELETE FROM gaussian_model WHERE id=101");
        jdbc.update("DELETE FROM design_image WHERE id=102");
        var s = statistics(7);
        assertEquals(0, s.at("/gaussians/totalScenes").asLong());
        assertEquals(0, s.at("/gaussians/skyBytes").asLong());
        assertEquals(1, s.at("/designs/totalImages").asLong());
        assertEquals(750, s.at("/totals/totalBytesAll").asLong());
        assertEquals(0, s.at("/trend/items/6/GAUSSIAN").asLong());
        assertEquals(0, s.at("/trend/items/6/DESIGN").asLong());
    }

    @Test void emptyDatabaseReturnsZerosAndValidPeriod() throws Exception {
        var s = statistics(30);
        assertEquals(0, s.at("/totals/totalAssets").asLong());
        assertEquals(0, s.at("/totals/totalBytesAll").asLong());
        assertEquals(4, s.path("categories").size());
        assertEquals(30, s.at("/trend/items").size());
        mvc.perform(get("/api/data/statistics")).andExpect(status().isOk()).andExpect(jsonPath("$.trend.days").value(30));
        mvc.perform(get("/api/data/statistics?days=90")).andExpect(status().isOk()).andExpect(jsonPath("$.trend.days").value(90));
        mvc.perform(get("/api/data/statistics?days=8")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/data/statistics?days=abc")).andExpect(status().isBadRequest());
    }
}
