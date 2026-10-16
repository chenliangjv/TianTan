package com.bucea.webgl.modeldataservice.dto;

import com.bucea.webgl.modeldataservice.entity.GaussianModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public record GaussianModelInfo(Long id, String name, String status, Long sourceSplatCount,
        Long totalSplatCount, Integer chunkCount, Long totalSize, boolean hasSky, boolean skyDefaultEnabled,
        JsonNode bounds, JsonNode skyBounds, JsonNode transform, JsonNode initialCamera,
        String mainUrl, String skyUrl, String createdAt) {
    public static GaussianModelInfo from(GaussianModel m, ObjectMapper json) {
        try {
            boolean sky = m.getSkyBoundsJson() != null;
            String base = "/api/data/gaussians/" + m.getId() + "/files/";
            return new GaussianModelInfo(m.getId(), m.getName(), m.getStatus(), m.getSourceSplatCount(),
                    m.getTotalSplatCount(), m.getChunkCount(), m.getTotalSize(), sky, m.isSkyDefaultEnabled(),
                    json.readTree(m.getBoundsJson()), sky ? json.readTree(m.getSkyBoundsJson()) : null,
                    json.readTree(m.getTransformJson()), m.getInitialCameraJson() == null ? null : json.readTree(m.getInitialCameraJson()),
                    base + "scene.rad", sky ? base + "environment.ply" : null, m.getCreatedAt().toString());
        } catch (Exception error) { throw new IllegalStateException("高斯场景元数据损坏", error); }
    }
}
