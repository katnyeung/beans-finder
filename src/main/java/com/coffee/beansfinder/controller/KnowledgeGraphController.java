package com.coffee.beansfinder.controller;

import com.coffee.beansfinder.graph.node.ProductNode;
import com.coffee.beansfinder.service.KnowledgeGraphService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API for querying the knowledge graph
 */
@RestController
@RequestMapping("/api/graph")
@RequiredArgsConstructor
@Slf4j
public class KnowledgeGraphController {

    private final KnowledgeGraphService graphService;

    /**
     * Find products by flavor
     */
    @GetMapping("/products/flavor/{flavorName}")
    public List<ProductNode> findByFlavor(@PathVariable String flavorName) {
        return graphService.findProductsByFlavor(flavorName);
    }

    /**
     * Find products by SCA category
     */
    @GetMapping("/products/sca-category/{categoryName}")
    public List<ProductNode> findBySCACategory(@PathVariable String categoryName) {
        return graphService.findProductsBySCACategory(categoryName);
    }

    /**
     * Find products by origin
     */
    @GetMapping("/products/origin/{country}")
    public List<ProductNode> findByOrigin(@PathVariable String country) {
        return graphService.findProductsByOrigin(country);
    }

    /**
     * Find products by process
     */
    @GetMapping("/products/process/{processType}")
    public List<ProductNode> findByProcess(@PathVariable String processType) {
        return graphService.findProductsByProcess(processType);
    }

    /**
     * Complex query: Find products by process AND flavor
     * Example: honey-processed Geishas with pear-like sweetness
     */
    @GetMapping("/products/complex")
    public List<ProductNode> findByProcessAndFlavor(
            @RequestParam String process,
            @RequestParam String flavor) {
        return graphService.findProductsByProcessAndFlavor(process, flavor);
    }

    /**
     * Initialize SCA categories in graph
     */
    @PostMapping("/init-categories")
    public String initializeCategories() {
        graphService.initializeSCACategories();
        return "SCA categories initialized";
    }
}
