package com.bucea.webgl.modeldataservice.controller;

import com.bucea.webgl.modeldataservice.entity.DiseaseAnnotation;
import com.bucea.webgl.modeldataservice.entity.DiseasePoint;
import com.bucea.webgl.modeldataservice.repository.DiseaseAnnotationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/data/diseases")
public class DiseaseAnnotationController {
    private final DiseaseAnnotationRepository repository;

    public DiseaseAnnotationController(DiseaseAnnotationRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<DiseaseAnnotation> list(@RequestParam Long modelId) {
        return repository.findByModelIdAndStatusOrderByCreatedTimeDesc(modelId, "ACTIVE");
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<DiseaseAnnotation> get(@PathVariable Long id) {
        return repository.findWithPointsById(id).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody DiseaseRequest request) {
        try {
            DiseaseAnnotation annotation = toEntity(request, new DiseaseAnnotation());
            annotation.setCreatedBy(request.recorder == null || request.recorder.isBlank() ? "system" : request.recorder);
            return ResponseEntity.status(HttpStatus.CREATED).body(repository.save(annotation));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody DiseaseRequest request) {
        return repository.findWithPointsById(id).map(existing -> {
            try {
                return ResponseEntity.ok(repository.save(toEntity(request, existing)));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(e.getMessage());
            }
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        return repository.findById(id).map(annotation -> {
            if ("ACTIVE".equals(annotation.getStatus())) {
                annotation.setStatus("DELETED");
                repository.save(annotation);
            }
            return ResponseEntity.noContent().<Void>build();
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    private DiseaseAnnotation toEntity(DiseaseRequest request, DiseaseAnnotation target) {
        if (request == null || request.modelId == null) throw new IllegalArgumentException("modelId 不能为空");
        if (request.type == null || request.type.isBlank()) throw new IllegalArgumentException("病害类型不能为空");
        if (request.points == null || request.points.size() < 3) throw new IllegalArgumentException("至少需要 3 个三维标注点");

        target.setModelId(request.modelId);
        target.setMonomerId(request.monomerId);
        target.setType(request.type);
        target.setSeverity(request.severity == null || request.severity.isBlank() ? "中度" : request.severity);
        target.setRecordDate(request.recordDate == null || request.recordDate.isBlank()
                ? LocalDate.now() : LocalDate.parse(request.recordDate));
        target.setRecorder(request.recorder);
        target.setDescription(request.description);
        target.setCoordinateSystem(request.coordinateSystem == null ? "MODEL_WORLD" : request.coordinateSystem);
        target.setModelVersion(request.modelVersion);
        target.setStatus("ACTIVE");

        target.getPoints().clear();
        for (int i = 0; i < request.points.size(); i++) {
            Map<String, Object> source = request.points.get(i);
            DiseasePoint point = new DiseasePoint();
            point.setAnnotation(target);
            point.setSequenceNo(i);
            point.setX(number(source, "x"));
            point.setY(number(source, "y"));
            point.setZ(number(source, "z"));
            target.getPoints().add(point);
        }
        return target;
    }

    private static double number(Map<String, Object> point, String key) {
        Object value = point == null ? null : point.get(key);
        if (value == null) throw new IllegalArgumentException("标注点缺少 " + key);
        return Double.parseDouble(value.toString());
    }

    public static class DiseaseRequest {
        public Long modelId;
        public Long monomerId;
        public String type;
        public String severity;
        public String recordDate;
        public String recorder;
        public String description;
        public String coordinateSystem;
        public String modelVersion;
        public List<Map<String, Object>> points = new ArrayList<>();
    }
}
