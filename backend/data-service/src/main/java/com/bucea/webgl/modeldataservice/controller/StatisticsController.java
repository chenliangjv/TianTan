package com.bucea.webgl.modeldataservice.controller;

import com.bucea.webgl.modeldataservice.service.StatisticsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/data")
public class StatisticsController {
    private final StatisticsService statisticsService;

    public StatisticsController(StatisticsService statisticsService) {
        this.statisticsService = statisticsService;
    }

    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getStatistics(@RequestParam(defaultValue = "30") String days) {
        if (!java.util.List.of("7", "30", "90").contains(days)) {
            return ResponseEntity.badRequest().body(Map.of("message", "days 仅支持 7、30、90"));
        }
        return ResponseEntity.ok(statisticsService.getStatistics(Integer.parseInt(days)));
    }
}
