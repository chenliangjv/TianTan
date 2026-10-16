package com.bucea.webgl.schedulingservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;
import java.util.Map;

@FeignClient(name = "data-service", url = "${TIANTAN_DATA_SERVICE_URL:http://127.0.0.1:8081}")
public interface ModelDataClient {

    @GetMapping("/api/data/octree/{octreeId}/nodes")
    List<Map<String, Object>> getOctreeNodes(@PathVariable("octreeId") Long octreeId);

}
