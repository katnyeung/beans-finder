package com.coffee.beansfinder.controller;

import com.coffee.beansfinder.neo4j.CoffeeNode;
import com.coffee.beansfinder.service.KnowledgeGraphService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API for knowledge graph queries
 */
@RestController
@RequestMapping("/api/graph")
@RequiredArgsConstructor
@Slf4j
public class KnowledgeGraphController {

    private final KnowledgeGraphService graphService;

    /**
     * Find products by SCA flavor category
     * GET /api/graph/sca/{category}
     * Example: /api/graph/sca/fruity
     */
    @GetMapping("/sca/{category}")
    public ResponseEntity<List<CoffeeNode>> findBySCACategory(@PathVariable String category) {
        return ResponseEntity.ok(graphService.findBySCACategory(category));
    }

    /**
     * Find products by specific flavor
     * GET /api/graph/flavor/{flavor}
     * Example: /api/graph/flavor/pear
     */
    @GetMapping("/flavor/{flavor}")
    public ResponseEntity<List<CoffeeNode>> findByFlavor(@PathVariable String flavor) {
        return ResponseEntity.ok(graphService.findByFlavor(flavor));
    }

    /**
     * Find products by variety
     * GET /api/graph/variety/{variety}
     * Example: /api/graph/variety/Geisha
     */
    @GetMapping("/variety/{variety}")
    public ResponseEntity<List<CoffeeNode>> findByVariety(@PathVariable String variety) {
        return ResponseEntity.ok(graphService.findByVariety(variety));
    }

    /**
     * Find products by origin country
     * GET /api/graph/origin/{country}
     * Example: /api/graph/origin/Colombia
     */
    @GetMapping("/origin/{country}")
    public ResponseEntity<List<CoffeeNode>> findByOrigin(@PathVariable String country) {
        return ResponseEntity.ok(graphService.findByOrigin(country));
    }

    /**
     * Find products by process and SCA category
     * GET /api/graph/query?process=Honey&sca=fruity
     * Example: Find honey-processed coffees with fruity notes
     */
    @GetMapping("/query")
    public ResponseEntity<List<CoffeeNode>> findByProcessAndFlavor(
        @RequestParam String process,
        @RequestParam(name = "sca") String scaCategory
    ) {
        return ResponseEntity.ok(graphService.findByProcessAndFlavor(process, scaCategory));
    }
}
