package com.bucea.webgl.schedulingservice.websocket;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.bucea.webgl.schedulingservice.client.ModelDataClient;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Calculates visible octree nodes for model viewers. */
@Component
public class LodWebSocketHandler extends TextWebSocketHandler {
    private static final double MODEL_REFINE_PIXELS = 180.0;

    private final ModelDataClient modelDataClient;
    // The node hierarchy is immutable for a stored octree. Build it once instead
    // of reparsing every database row for every camera update.
    private final Map<Long, ModelOctree> octreeCache = new ConcurrentHashMap<>();
    private final Set<String> processingSessions = ConcurrentHashMap.newKeySet();

    public LodWebSocketHandler(ModelDataClient modelDataClient) {
        this.modelDataClient = modelDataClient;
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status) {
        processingSessions.remove(session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        if (!processingSessions.add(session.getId())) return;
        try {
            JSONObject request = JSON.parseObject(message.getPayload());
            JSONArray octreeIds = readOctreeIds(request);
            JSONObject camera = request.getJSONObject("camera");
            JSONArray position = camera == null ? null : camera.getJSONArray("position");
            if (position == null || position.size() < 3) {
                sendError(session, "camera.position is required");
                return;
            }

            double viewportHeight = positiveOrDefault(request.getDoubleValue("viewportHeight"), 1080.0);
            double cameraFov = positiveOrDefault(request.getDoubleValue("cameraFov"), 60.0);
            JSONArray frustumPlanes = request.getJSONArray("frustum");
            double cameraX = position.getDoubleValue(0);
            double cameraY = position.getDoubleValue(1);
            double cameraZ = position.getDoubleValue(2);

            List<String> visibleNodes = new ArrayList<>();
            for (int i = 0; i < octreeIds.size(); i++) {
                Long octreeId = octreeIds.getLong(i);
                if (octreeId == null) continue;
                ModelOctree octree = loadNodes(octreeId);
                visibleNodes.addAll(selectModelNodes(octree, cameraX, cameraY, cameraZ,
                        frustumPlanes, viewportHeight, cameraFov));
            }

            JSONObject response = new JSONObject();
            response.put("action", "updateVisibility");
            response.put("requestId", request.getLong("requestId"));
            response.put("visibleNodes", visibleNodes);
            send(session, response);
        } catch (Exception error) {
            sendError(session, error.getMessage() == null ? "LOD scheduling failed" : error.getMessage());
        } finally {
            processingSessions.remove(session.getId());
        }
    }

    private JSONArray readOctreeIds(JSONObject request) {
        JSONArray ids = request.getJSONArray("octreeIds");
        if (ids != null && !ids.isEmpty()) return ids;
        Long singleId = request.getLong("octreeId");
        if (singleId == null) throw new IllegalArgumentException("octreeId or octreeIds is required");
        JSONArray result = new JSONArray();
        result.add(singleId);
        return result;
    }

    private ModelOctree loadNodes(Long octreeId) {
        return octreeCache.computeIfAbsent(octreeId,
                id -> ModelOctree.from(id, modelDataClient.getOctreeNodes(id)));
    }

    private List<String> selectModelNodes(ModelOctree octree,
                                          double cameraX, double cameraY, double cameraZ,
                                          JSONArray frustumPlanes, double viewportHeight, double cameraFov) {
        List<String> selected = new ArrayList<>();
        for (ModelNode root : octree.roots) {
            selectModelNode(root, octree.childrenByParent, cameraX, cameraY, cameraZ, frustumPlanes,
                    viewportHeight, cameraFov, selected);
        }
        return selected;
    }

    private boolean selectModelNode(ModelNode node, Map<String, List<ModelNode>> childrenByParent,
                                    double cameraX, double cameraY, double cameraZ, JSONArray frustumPlanes,
                                    double viewportHeight, double cameraFov, List<String> selected) {
        if (!isAabbInFrustum(node.minX, node.minY, node.minZ, node.maxX, node.maxY, node.maxZ, frustumPlanes)) {
            return false;
        }
        // Without a usable bounding box there is no safe way to decide which
        // descendants cover the node. Keep this node as the visible fallback.
        if (!hasValidBounds(node.minX, node.minY, node.minZ, node.maxX, node.maxY, node.maxZ)) {
            selected.add(node.visibleId);
            return true;
        }
        List<ModelNode> children = childrenByParent.getOrDefault(node.nodeCode, Collections.emptyList());
        double projectedPixels = projectedPixels(node.minX, node.minY, node.minZ, node.maxX, node.maxY, node.maxZ,
                cameraX, cameraY, cameraZ, viewportHeight, cameraFov);
        if (!children.isEmpty() && projectedPixels >= MODEL_REFINE_PIXELS) {
            int visibleChildren = 0;
            int selectedChildren = 0;
            for (ModelNode child : children) {
                if (isAabbInFrustum(child.minX, child.minY, child.minZ, child.maxX, child.maxY, child.maxZ, frustumPlanes)) {
                    visibleChildren++;
                    if (selectModelNode(child, childrenByParent, cameraX, cameraY, cameraZ, frustumPlanes,
                            viewportHeight, cameraFov, selected)) selectedChildren++;
                }
            }
            // Keep the parent as a coverage fallback whenever a visible child
            // could not be represented. The client can unload it only after
            // every required child has finished loading.
            if (visibleChildren == 0 || selectedChildren < visibleChildren) selected.add(node.visibleId);
            return true;
        }
        selected.add(node.visibleId);
        return true;
    }

    private double projectedPixels(Double minX, Double minY, Double minZ, Double maxX, Double maxY, Double maxZ,
                                   double cameraX, double cameraY, double cameraZ,
                                   double viewportHeight, double cameraFov) {
        if (!hasValidBounds(minX, minY, minZ, maxX, maxY, maxZ)) {
            return Double.MAX_VALUE;
        }
        double centerX = (minX + maxX) / 2.0;
        double centerY = (minY + maxY) / 2.0;
        double centerZ = (minZ + maxZ) / 2.0;
        double distance = Math.max(1e-6, Math.sqrt(Math.pow(cameraX - centerX, 2)
                + Math.pow(cameraY - centerY, 2) + Math.pow(cameraZ - centerZ, 2)));
        double diagonal = Math.max(1e-6, Math.sqrt(Math.pow(maxX - minX, 2)
                + Math.pow(maxY - minY, 2) + Math.pow(maxZ - minZ, 2)));
        double focalLength = viewportHeight / (2.0 * Math.tan(Math.toRadians(cameraFov) / 2.0));
        return diagonal * focalLength / distance;
    }

    private boolean isAabbInFrustum(Double minX, Double minY, Double minZ, Double maxX, Double maxY, Double maxZ,
                                    JSONArray frustumPlanes) {
        // A number of source leaves contain valid GLB geometry but an exporter
        // sentinel bounding box (Float.MAX_VALUE / -Float.MAX_VALUE). Culling
        // those nodes creates permanent holes, so invalid bounds are visible
        // until the source metadata is repaired.
        if (!hasValidBounds(minX, minY, minZ, maxX, maxY, maxZ)
                || frustumPlanes == null || frustumPlanes.size() < 6) return true;
        for (int i = 0; i < 6; i++) {
            JSONArray plane = frustumPlanes.getJSONArray(i);
            double normalX = plane.getDoubleValue(0);
            double normalY = plane.getDoubleValue(1);
            double normalZ = plane.getDoubleValue(2);
            double d = plane.getDoubleValue(3);
            double pointX = normalX > 0 ? maxX : minX;
            double pointY = normalY > 0 ? maxY : minY;
            double pointZ = normalZ > 0 ? maxZ : minZ;
            if (normalX * pointX + normalY * pointY + normalZ * pointZ + d < 0) return false;
        }
        return true;
    }

    private boolean hasValidBounds(Double minX, Double minY, Double minZ,
                                   Double maxX, Double maxY, Double maxZ) {
        return minX != null && minY != null && minZ != null && maxX != null && maxY != null && maxZ != null
                && Double.isFinite(minX) && Double.isFinite(minY) && Double.isFinite(minZ)
                && Double.isFinite(maxX) && Double.isFinite(maxY) && Double.isFinite(maxZ)
                && minX <= maxX && minY <= maxY && minZ <= maxZ;
    }

    private static double positiveOrDefault(double value, double defaultValue) {
        return value > 0 ? value : defaultValue;
    }

    private void sendError(WebSocketSession session, String message) {
        JSONObject response = new JSONObject();
        response.put("error", message);
        send(session, response);
    }

    private void send(WebSocketSession session, JSONObject response) {
        if (!session.isOpen()) return;
        try {
            session.sendMessage(new TextMessage(response.toJSONString()));
        } catch (IOException ignored) {
            // The client can disconnect while an LOD calculation is in progress.
        }
    }

    private record ModelNode(String visibleId, String nodeCode, String parentCode,
                             Double minX, Double minY, Double minZ, Double maxX, Double maxY, Double maxZ) {
        static ModelNode from(Long octreeId, Map<String, Object> source) {
            String nodeCode = value(source, "nodeCode");
            return nodeCode == null || nodeCode.isBlank() ? null : new ModelNode(
                    octreeId + "_" + nodeCode, nodeCode, value(source, "parentCode"),
                    decimal(source, "minX"), decimal(source, "minY"), decimal(source, "minZ"),
                    decimal(source, "maxX"), decimal(source, "maxY"), decimal(source, "maxZ"));
        }
    }

    private record ModelOctree(List<ModelNode> roots, Map<String, List<ModelNode>> childrenByParent) {
        static ModelOctree from(Long octreeId, List<Map<String, Object>> rawNodes) {
            Map<String, List<ModelNode>> childrenByParent = new HashMap<>();
            List<ModelNode> roots = new ArrayList<>();
            List<ModelNode> allNodes = new ArrayList<>();
            for (Map<String, Object> rawNode : rawNodes) {
                ModelNode node = ModelNode.from(octreeId, rawNode);
                if (node == null) continue;
                allNodes.add(node);
                if (node.parentCode == null || node.parentCode.isBlank()) roots.add(node);
                else childrenByParent.computeIfAbsent(node.parentCode, ignored -> new ArrayList<>()).add(node);
            }
            if (roots.isEmpty()) roots.addAll(allNodes);
            return new ModelOctree(List.copyOf(roots), Map.copyOf(childrenByParent));
        }
    }

    private static String value(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value == null ? null : value.toString();
    }

    private static Double decimal(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value == null ? null : Double.parseDouble(value.toString());
    }
}
