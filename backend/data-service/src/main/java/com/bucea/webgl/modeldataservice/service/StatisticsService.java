package com.bucea.webgl.modeldataservice.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

/** Database aggregates only: no entity graphs, asset downloads or filesystem scans. */
@Service
public class StatisticsService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String MODEL_FILTER = "(m.data_category = 'MODEL' OR m.data_category IS NULL)";
    private static final String MODEL_TREES = " FROM octree_info o JOIN model_info m ON m.id = o.model_id WHERE " + MODEL_FILTER;
    private static final String MODEL_NODES = " FROM octree_node n JOIN octree_info o ON o.id = n.octree_id JOIN model_info m ON m.id = o.model_id WHERE " + MODEL_FILTER;
    private static final String MODEL_FILES = " FROM node_file f JOIN octree_node n ON n.id = f.node_id JOIN octree_info o ON o.id = n.octree_id JOIN model_info m ON m.id = o.model_id WHERE " + MODEL_FILTER;
    private static final List<String> CATEGORIES = List.of("MODEL", "POINT_CLOUD", "GAUSSIAN", "DESIGN");
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public StatisticsService(JdbcTemplate jdbc) {
        this(jdbc, Clock.system(ZONE));
    }

    StatisticsService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> getStatistics(int days) {
        if (!List.of(7, 30, 90).contains(days)) throw new IllegalArgumentException("Unsupported statistics period");
        var now = ZonedDateTime.now(clock).withZoneSameInstant(ZONE);
        Map<String, Object> models = new LinkedHashMap<>();
        models.put("totalModels", count("SELECT COUNT(*) FROM model_info m WHERE " + MODEL_FILTER));
        models.put("octreeInfoCount", count("SELECT COUNT(*)" + MODEL_TREES));
        models.put("octreeNodes", count("SELECT COUNT(*)" + MODEL_NODES));
        models.put("nodeFiles", count("SELECT COUNT(*)" + MODEL_FILES));
        models.put("fileSizeBytes", count("SELECT COALESCE(SUM(f.file_size), 0)" + MODEL_FILES));

        var pointClouds = aggregate("SELECT COUNT(*), COALESCE(SUM(point_count), 0), COALESCE(SUM(total_size), 0) FROM point_cloud_model_info",
                "totalPointCloudModels", "totalPoints", "fileSizeBytes");
        pointClouds.put("datasets", pointClouds.get("totalPointCloudModels"));
        pointClouds.put("format", "POTREE_2");

        // Aggregate scenes separately from assets: joining MAIN and SKY would multiply splat totals.
        var gaussians = aggregate("SELECT COUNT(*), COALESCE(SUM(source_splat_count), 0), COALESCE(SUM(total_splat_count), 0), COALESCE(SUM(chunk_count), 0), COALESCE(SUM(total_size), 0), COUNT(source_splat_count) FROM gaussian_model",
                "totalScenes", "sourceSplatCount", "totalSplatCount", "chunkCount", "fileSizeBytes", "sourceCountKnownScenes");
        var sky = aggregate("SELECT COUNT(*), COALESCE(SUM(a.file_size), 0) FROM gaussian_asset a JOIN gaussian_model g ON g.id = a.model_id WHERE a.role = 'SKY'", "scenesWithSky", "skyBytes");
        gaussians.putAll(sky);
        gaussians.put("mainBytes", count("SELECT COALESCE(SUM(a.file_size), 0) FROM gaussian_asset a JOIN gaussian_model g ON g.id = a.model_id WHERE a.role = 'MAIN'"));

        var designs = aggregate("SELECT COUNT(*), COALESCE(SUM(file_size), 0) FROM design_image", "totalImages", "fileSizeBytes");
        Map<String, Long> formats = new LinkedHashMap<>();
        jdbc.query("SELECT format, COUNT(*) FROM design_image GROUP BY format ORDER BY format", rs -> {
            formats.put(rs.getString(1), rs.getLong(2));
        });
        designs.put("formats", formats);

        var diseases = aggregate("SELECT COUNT(*), COALESCE(SUM(CASE WHEN status = 'ACTIVE' THEN 1 ELSE 0 END), 0) FROM disease_annotation", "total", "active");
        Map<String, Object> tasks = new LinkedHashMap<>();
        for (String status : List.of("pending", "running", "success", "failed")) tasks.put(status, 0L);
        jdbc.query("SELECT status, COUNT(*) FROM ingestion_task WHERE data_category IN ('MODEL', 'POINT_CLOUD', 'GAUSSIAN') GROUP BY status", rs -> {
            String key = rs.getString(1) == null ? "" : rs.getString(1).toLowerCase(Locale.ROOT);
            if (tasks.containsKey(key)) tasks.put(key, rs.getLong(2));
        });
        tasks.put("total", tasks.values().stream().mapToLong(value -> ((Number) value).longValue()).sum());

        var categories = List.of(
                category("MODEL", "三维模型", models, "totalModels"),
                category("POINT_CLOUD", "点云", pointClouds, "totalPointCloudModels"),
                category("GAUSSIAN", "高斯场景", gaussians, "totalScenes"),
                category("DESIGN", "设计图", designs, "totalImages"));
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("total3DModels", models.get("totalModels"));
        totals.put("totalPointCloudModels", pointClouds.get("totalPointCloudModels"));
        totals.put("totalGaussianScenes", gaussians.get("totalScenes"));
        totals.put("totalDesignImages", designs.get("totalImages"));
        totals.put("totalAssets", categories.stream().mapToLong(c -> ((Number)c.get("count")).longValue()).sum());
        totals.put("totalBytesAll", categories.stream().mapToLong(c -> ((Number)c.get("fileSizeBytes")).longValue()).sum());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("models", models);
        result.put("pointClouds", pointClouds);
        result.put("gaussians", gaussians);
        result.put("designs", designs);
        result.put("diseases", diseases);
        result.put("ingestionTasks", tasks);
        result.put("totals", totals);
        result.put("categories", categories);
        result.put("trend", trend(days, now.toLocalDate()));
        result.put("updatedAt", now.toOffsetDateTime().toString());
        result.put("timezone", ZONE.getId());
        return result;
    }

    private Map<String, Object> trend(int days, LocalDate today) {
        LocalDate start = today.minusDays(days - 1L);
        Map<String, Map<String, Object>> buckets = new LinkedHashMap<>();
        for (LocalDate day = start; !day.isAfter(today); day = day.plusDays(1)) {
            Map<String, Object> bucket = new LinkedHashMap<>();
            bucket.put("date", day.toString());
            CATEGORIES.forEach(category -> bucket.put(category, 0L));
            buckets.put(day.toString(), bucket);
        }
        // DATETIME columns use Shanghai wall time (MySQL serverTimezone=Asia/Shanghai).
        // String boundaries avoid reinterpretation using the host JVM time zone.
        addTrend(buckets, "MODEL", "model_info m", "COALESCE(m.upload_time, m.create_time)", MODEL_FILTER, start, today);
        addTrend(buckets, "POINT_CLOUD", "point_cloud_model_info", "COALESCE(upload_time, create_time)", "1=1", start, today);
        addTrend(buckets, "GAUSSIAN", "gaussian_model", "created_at", "1=1", start, today);
        addTrend(buckets, "DESIGN", "design_image", "create_time", "1=1", start, today);
        return Map.of("days", days, "startDate", start.toString(), "endDate", today.toString(), "items", new ArrayList<>(buckets.values()));
    }

    private void addTrend(Map<String, Map<String, Object>> buckets, String category, String table,
                          String dateColumn, String filter, LocalDate start, LocalDate end) {
        String day = "CAST(" + dateColumn + " AS DATE)";
        String sql = "SELECT " + day + ", COUNT(*) FROM " + table + " WHERE " + filter
                + " AND " + dateColumn + " >= ? AND " + dateColumn + " < ? GROUP BY " + day;
        jdbc.query(sql, rs -> {
            var bucket = buckets.get(rs.getString(1));
            if (bucket != null) bucket.put(category, rs.getLong(2));
        }, start.toString(), end.plusDays(1).toString());
    }

    private long count(String sql) { return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class)); }

    private Map<String, Object> aggregate(String sql, String... fields) {
        return jdbc.queryForObject(sql, (rs, row) -> {
            Map<String, Object> values = new LinkedHashMap<>();
            for (int i = 0; i < fields.length; i++) values.put(fields[i], rs.getLong(i + 1));
            return values;
        });
    }

    private Map<String, Object> category(String key, String label, Map<String, Object> values, String countKey) {
        return Map.of("key", key, "label", label, "count", values.get(countKey), "fileSizeBytes", values.get("fileSizeBytes"));
    }
}
