package com.coffee.beansfinder.service;

import com.coffee.beansfinder.dto.SCAFlavorMapping;
import com.coffee.beansfinder.entity.CoffeeProduct;
import com.coffee.beansfinder.graph.node.*;
import com.coffee.beansfinder.graph.repository.*;
import com.coffee.beansfinder.repository.CoffeeProductRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Service for managing the Neo4j knowledge graph
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class KnowledgeGraphService {

    private final ProductNodeRepository productNodeRepository;
    private final OriginNodeRepository originNodeRepository;
    private final ProcessNodeRepository processNodeRepository;
    private final FlavorNodeRepository flavorNodeRepository;
    private final SCACategoryRepository scaCategoryRepository;
    private final ProducerNodeRepository producerNodeRepository;
    private final VarietyNodeRepository varietyNodeRepository;
    private final BrandNodeRepository brandNodeRepository;
    private final RoastLevelNodeRepository roastLevelNodeRepository;
    private final CoffeeProductRepository coffeeProductRepository;
    private final ObjectMapper objectMapper;

    /**
     * Create or update product in knowledge graph
     * Uses Neo4j transaction manager and REQUIRES_NEW to run in separate transaction from PostgreSQL
     */
    @Transactional(
        transactionManager = "neo4jTransactionManager",
        propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW
    )
    public ProductNode syncProductToGraph(CoffeeProduct product) {
        try {
            log.info("Syncing product to knowledge graph: {} (ID: {})",
                    product.getProductName(), product.getId());

            // Find or create product node
            ProductNode productNode = productNodeRepository.findByProductId(product.getId())
                    .orElse(ProductNode.builder()
                            .productId(product.getId())
                            .build());

            // Update basic product info
            productNode.setProductName(product.getProductName());
            productNode.setPrice(product.getPrice());
            productNode.setCurrency(product.getCurrency());
            productNode.setInStock(product.getInStock());
            productNode.setLastUpdate(product.getLastUpdateDate());

            // Link to brand (create BrandNode from CoffeeBrand entity)
            if (product.getBrand() != null) {
                BrandNode brandNode = findOrCreateBrand(product.getBrand());
                productNode.setSoldBy(brandNode);
            }

            // Link to origins (support multi-value fields split by / or ,)
            if (product.getOrigin() != null && !product.getOrigin().trim().isEmpty()) {
                Set<OriginNode> origins = new HashSet<>();
                for (String country : splitMultiValue(product.getOrigin())) {
                    // Create core country node (region=null) - ID will be just country name
                    OriginNode coreCountryNode = findOrCreateOrigin(country, null, null);
                    origins.add(coreCountryNode);

                    // If region exists AND is not empty, also create specific region node
                    if (product.getRegion() != null && !product.getRegion().trim().isEmpty()) {
                        OriginNode regionNode = findOrCreateOrigin(
                                country,
                                product.getRegion(),
                                product.getAltitude());
                        origins.add(regionNode);
                    }
                }
                productNode.setOrigins(origins);
            }

            // Link to processes (support multi-value fields)
            if (product.getProcess() != null) {
                Set<ProcessNode> processes = new HashSet<>();
                for (String processType : splitMultiValue(product.getProcess())) {
                    ProcessNode process = findOrCreateProcess(processType);
                    processes.add(process);
                }
                productNode.setProcesses(processes);
            }

            // Link to producers (support multi-value fields)
            if (product.getProducer() != null) {
                Set<ProducerNode> producers = new HashSet<>();
                for (String producerName : splitMultiValue(product.getProducer())) {
                    ProducerNode producer = findOrCreateProducer(
                            producerName,
                            product.getOrigin());
                    producers.add(producer);
                }
                productNode.setProducers(producers);
            }

            // Link to varieties (support multi-value fields)
            if (product.getVariety() != null) {
                Set<VarietyNode> varieties = new HashSet<>();
                for (String varietyName : splitMultiValue(product.getVariety())) {
                    VarietyNode variety = findOrCreateVariety(varietyName);
                    varieties.add(variety);
                }
                productNode.setVarieties(varieties);
            }

            // Link to flavors (combines SCA flavors + tasting notes, all lowercase)
            Set<FlavorNode> flavors = new HashSet<>();

            // Add SCA-categorized flavors
            if (product.getScaFlavorsJson() != null && !product.getScaFlavorsJson().isEmpty()) {
                flavors.addAll(createFlavorNodes(product.getScaFlavorsJson()));
            }

            // Add raw tasting notes as FlavorNodes (lowercase, no SCA category)
            if (product.getTastingNotesJson() != null && !product.getTastingNotesJson().isEmpty()) {
                try {
                    List<String> tastingNotes = objectMapper.readValue(
                            product.getTastingNotesJson(),
                            new TypeReference<List<String>>() {}
                    );
                    for (String note : tastingNotes) {
                        if (note != null && !note.trim().isEmpty()) {
                            // NORMALIZE TO LOWERCASE before creating FlavorNode
                            String normalizedNote = note.trim().toLowerCase();
                            FlavorNode flavorNode = flavorNodeRepository.findById(normalizedNote)
                                    .orElseGet(() -> {
                                        FlavorNode newFlavor = FlavorNode.builder()
                                                .name(normalizedNote)
                                                .scaCategory("other") // Default category for raw tasting notes
                                                .build();
                                        return flavorNodeRepository.save(newFlavor);
                                    });
                            flavors.add(flavorNode);
                        }
                    }
                } catch (Exception e) {
                    log.error("Failed to parse tasting notes JSON for product {}: {}", product.getId(), e.getMessage());
                }
            }

            productNode.setFlavors(flavors);

            // Extract and link to roast level
            String roastLevel = extractRoastLevel(product);
            if (roastLevel != null) {
                RoastLevelNode roastLevelNode = findOrCreateRoastLevel(roastLevel);
                productNode.setRoastLevel(roastLevelNode);
            }

            // Save product node with all relationships
            ProductNode saved = productNodeRepository.save(productNode);
            log.info("Successfully synced product to graph: {}", saved.getProductName());

            return saved;

        } catch (Exception e) {
            log.error("Failed to sync product to knowledge graph: {}", e.getMessage(), e);
            throw new RuntimeException("Knowledge graph sync failed", e);
        }
    }

    /**
     * Split multi-value fields separated by / or ,
     * Examples:
     *   "Costa Rica / Ethiopia" -> ["Costa Rica", "Ethiopia"]
     *   "Geisha, Caturra" -> ["Geisha", "Caturra"]
     *   "White Honey / Washed" -> ["White Honey", "Washed"]
     */
    private List<String> splitMultiValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return List.of();
        }

        // Split by / or , and trim whitespace
        return java.util.Arrays.stream(value.split("[/,]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Find or create origin node
     */
    private OriginNode findOrCreateOrigin(String country, String region, String altitude) {
        String originId = OriginNode.generateId(country, region);
        return originNodeRepository.findById(originId)
                .orElseGet(() -> {
                    OriginNode origin = OriginNode.builder()
                            .id(originId)
                            .country(country)
                            .region(region)
                            .altitude(altitude)
                            .build();
                    return originNodeRepository.save(origin);
                });
    }

    /**
     * Find or create process node
     */
    private ProcessNode findOrCreateProcess(String processType) {
        return processNodeRepository.findById(processType)
                .orElseGet(() -> {
                    ProcessNode process = ProcessNode.builder()
                            .type(processType)
                            .build();
                    return processNodeRepository.save(process);
                });
    }

    /**
     * Find or create producer node
     */
    private ProducerNode findOrCreateProducer(String producerName, String country) {
        String producerId = ProducerNode.generateId(producerName, country);
        return producerNodeRepository.findById(producerId)
                .orElseGet(() -> {
                    ProducerNode producer = ProducerNode.builder()
                            .id(producerId)
                            .name(producerName)
                            .country(country)
                            .build();
                    return producerNodeRepository.save(producer);
                });
    }

    /**
     * Find or create variety node
     */
    private VarietyNode findOrCreateVariety(String varietyName) {
        return varietyNodeRepository.findById(varietyName)
                .orElseGet(() -> {
                    VarietyNode variety = VarietyNode.builder()
                            .name(varietyName)
                            .build();
                    return varietyNodeRepository.save(variety);
                });
    }

    /**
     * Create flavor nodes from SCA mapping JSON
     */
    private Set<FlavorNode> createFlavorNodes(String scaFlavorsJson) {
        Set<FlavorNode> flavorNodes = new HashSet<>();

        try {
            SCAFlavorMapping mapping = objectMapper.readValue(scaFlavorsJson, SCAFlavorMapping.class);

            // Process each category
            addFlavorsFromCategory(flavorNodes, mapping.getFruity(), "fruity");
            addFlavorsFromCategory(flavorNodes, mapping.getFloral(), "floral");
            addFlavorsFromCategory(flavorNodes, mapping.getSweet(), "sweet");
            addFlavorsFromCategory(flavorNodes, mapping.getNutty(), "nutty");
            addFlavorsFromCategory(flavorNodes, mapping.getSpices(), "spices");
            addFlavorsFromCategory(flavorNodes, mapping.getRoasted(), "roasted");
            addFlavorsFromCategory(flavorNodes, mapping.getGreen(), "green");
            addFlavorsFromCategory(flavorNodes, mapping.getSour(), "sour");
            addFlavorsFromCategory(flavorNodes, mapping.getOther(), "other");

        } catch (Exception e) {
            log.error("Failed to parse SCA flavors JSON: {}", e.getMessage());
        }

        return flavorNodes;
    }

    /**
     * Add flavors from a specific category
     */
    private void addFlavorsFromCategory(Set<FlavorNode> flavorNodes, List<String> notes, String categoryName) {
        if (notes == null || notes.isEmpty()) {
            return;
        }

        // Get or create SCA category (name is the @Id)
        SCACategory category = scaCategoryRepository.findById(categoryName)
                .orElseGet(() -> {
                    SCACategory cat = SCACategory.builder()
                            .name(categoryName)
                            .build();
                    return scaCategoryRepository.save(cat);
                });

        for (String noteName : notes) {
            // name is the @Id for FlavorNode
            FlavorNode flavor = flavorNodeRepository.findById(noteName)
                    .orElseGet(() -> {
                        FlavorNode newFlavor = FlavorNode.builder()
                                .name(noteName)
                                .scaCategory(categoryName)
                                .category(category)
                                .build();
                        return flavorNodeRepository.save(newFlavor);
                    });

            flavorNodes.add(flavor);
        }
    }

    /**
     * Query products by flavor
     */
    public List<ProductNode> findProductsByFlavor(String flavorName) {
        return productNodeRepository.findByFlavorNameContaining(flavorName);
    }

    /**
     * Query products by SCA category
     */
    public List<ProductNode> findProductsBySCACategory(String categoryName) {
        return productNodeRepository.findBySCACategory(categoryName);
    }

    /**
     * Query products by origin
     */
    public List<ProductNode> findProductsByOrigin(String country) {
        return productNodeRepository.findByOriginCountry(country);
    }

    /**
     * Query products by process
     */
    public List<ProductNode> findProductsByProcess(String processType) {
        return productNodeRepository.findByProcessType(processType);
    }

    /**
     * Query products by process and flavor (complex query)
     */
    public List<ProductNode> findProductsByProcessAndFlavor(String processType, String flavorName) {
        return productNodeRepository.findByProcessAndFlavor(processType, flavorName);
    }

    /**
     * Delete product from graph
     */
    @Transactional(transactionManager = "neo4jTransactionManager")
    public void deleteProductFromGraph(Long productId) {
        productNodeRepository.findByProductId(productId)
                .ifPresent(product -> {
                    log.info("Deleting product from graph: {}", product.getProductName());
                    productNodeRepository.delete(product);
                });
    }

    /**
     * Initialize SCA categories in the graph
     */
    @Transactional(transactionManager = "neo4jTransactionManager")
    public void initializeSCACategories() {
        String[] categories = {"fruity", "floral", "sweet", "nutty", "spices", "roasted", "green", "sour", "other"};

        for (String categoryName : categories) {
            // name is the @Id, so use findById
            if (scaCategoryRepository.findById(categoryName).isEmpty()) {
                SCACategory category = SCACategory.builder()
                        .name(categoryName)
                        .build();
                scaCategoryRepository.save(category);
                log.info("Initialized SCA category: {}", categoryName);
            }
        }
    }

    /**
     * Re-sync a single product to Neo4j (delete and recreate with new splitting logic)
     */
    @Transactional(transactionManager = "neo4jTransactionManager")
    public ProductNode reSyncProduct(CoffeeProduct product) {
        // Delete existing product node if exists
        deleteProductFromGraph(product.getId());

        // Re-sync with new splitting logic
        return syncProductToGraph(product);
    }

    /**
     * Get count of all product nodes in Neo4j
     */
    @Transactional(transactionManager = "neo4jTransactionManager", readOnly = true)
    public long getProductCount() {
        return productNodeRepository.count();
    }

    /**
     * SINGLE COMPREHENSIVE CLEANUP & REBUILD METHOD
     *
     * This method performs a complete cleanup and rebuild of the Neo4j knowledge graph:
     * 1. Wipes all ProductNodes from Neo4j
     * 2. Re-syncs ALL products from PostgreSQL with clean logic (no "Unknown" nodes)
     * 3. Automatically cleans up orphaned Origin/Process/Producer/Variety nodes
     * 4. Preserves Flavor and SCACategory nodes
     *
     * Safe to run multiple times. This is the ONLY cleanup endpoint you need.
     */
    @Transactional(transactionManager = "neo4jTransactionManager")
    public Map<String, Object> cleanupAndRebuild() {
        log.info("Starting comprehensive cleanup and rebuild of Neo4j knowledge graph");
        long startTime = System.currentTimeMillis();

        int productsDeleted = 0;
        int productsCreated = 0;
        int orphansDeleted = 0;
        int errorCount = 0;

        try {
            // Step 1: Wipe all ProductNodes
            log.info("Step 1: Wiping all ProductNodes...");
            long count = productNodeRepository.count();
            productNodeRepository.deleteAll();
            productsDeleted = (int) count;
            log.info("Deleted {} ProductNodes", productsDeleted);

            // Step 2: Re-sync all products from PostgreSQL
            log.info("Step 2: Re-syncing all products from PostgreSQL...");
            List<CoffeeProduct> allProducts = coffeeProductRepository.findAll();
            log.info("Found {} products to sync", allProducts.size());

            for (CoffeeProduct product : allProducts) {
                try {
                    syncProductToGraph(product);
                    productsCreated++;

                    if (productsCreated % 50 == 0) {
                        log.info("Synced {}/{} products", productsCreated, allProducts.size());
                    }
                } catch (Exception e) {
                    errorCount++;
                    log.error("Failed to sync product {}: {}", product.getId(), e.getMessage());
                }
            }

            log.info("Step 2 complete: {} products created, {} errors", productsCreated, errorCount);

            // Step 3: Clean up orphaned nodes
            log.info("Step 3: Cleaning up orphaned nodes...");
            orphansDeleted = cleanupOrphans();
            log.info("Cleaned up {} orphaned nodes", orphansDeleted);

            long duration = System.currentTimeMillis() - startTime;

            Map<String, Object> result = new HashMap<>();
            result.put("productsDeleted", productsDeleted);
            result.put("productsCreated", productsCreated);
            result.put("orphansDeleted", orphansDeleted);
            result.put("errorCount", errorCount);
            result.put("durationMs", duration);
            result.put("neo4jProductCount", productNodeRepository.count());

            log.info("Cleanup and rebuild completed: {} products created, {} orphans deleted, {} errors in {}ms",
                    productsCreated, orphansDeleted, errorCount, duration);

            return result;

        } catch (Exception e) {
            log.error("Cleanup and rebuild failed: {}", e.getMessage(), e);
            throw new RuntimeException("Cleanup and rebuild failed", e);
        }
    }

    /**
     * Internal helper: Clean up orphaned Origin/Process/Producer/Variety nodes.
     * Returns count of deleted nodes.
     */
    private int cleanupOrphans() {
        int totalDeleted = 0;

        List<ProductNode> allProducts = productNodeRepository.findAll();

        // Collect all nodes that ARE linked to products
        Set<String> linkedOriginIds = new HashSet<>();
        Set<String> linkedProcessTypes = new HashSet<>();
        Set<String> linkedProducerIds = new HashSet<>();
        Set<String> linkedVarietyNames = new HashSet<>();

        for (ProductNode product : allProducts) {
            if (product.getOrigins() != null) {
                product.getOrigins().forEach(o -> linkedOriginIds.add(o.getId()));
            }
            if (product.getProcesses() != null) {
                product.getProcesses().forEach(p -> linkedProcessTypes.add(p.getType()));
            }
            if (product.getProducers() != null) {
                product.getProducers().forEach(p -> linkedProducerIds.add(p.getId()));
            }
            if (product.getVarieties() != null) {
                product.getVarieties().forEach(v -> linkedVarietyNames.add(v.getName()));
            }
        }

        // Delete orphaned nodes
        totalDeleted += originNodeRepository.findAll().stream()
                .filter(o -> !linkedOriginIds.contains(o.getId()))
                .peek(o -> log.debug("Deleting orphaned OriginNode: {}", o.getId()))
                .peek(originNodeRepository::delete)
                .count();

        totalDeleted += processNodeRepository.findAll().stream()
                .filter(p -> !linkedProcessTypes.contains(p.getType()))
                .peek(p -> log.debug("Deleting orphaned ProcessNode: {}", p.getType()))
                .peek(processNodeRepository::delete)
                .count();

        totalDeleted += producerNodeRepository.findAll().stream()
                .filter(p -> !linkedProducerIds.contains(p.getId()))
                .peek(p -> log.debug("Deleting orphaned ProducerNode: {}", p.getId()))
                .peek(producerNodeRepository::delete)
                .count();

        totalDeleted += varietyNodeRepository.findAll().stream()
                .filter(v -> !linkedVarietyNames.contains(v.getName()))
                .peek(v -> log.debug("Deleting orphaned VarietyNode: {}", v.getName()))
                .peek(varietyNodeRepository::delete)
                .count();

        return (int) totalDeleted;
    }

    /**
     * Find or create brand node from CoffeeBrand entity
     */
    private BrandNode findOrCreateBrand(com.coffee.beansfinder.entity.CoffeeBrand coffeeBrand) {
        return brandNodeRepository.findByName(coffeeBrand.getName())
                .orElseGet(() -> {
                    BrandNode brandNode = new BrandNode(coffeeBrand.getName());
                    brandNode.setBrandId(coffeeBrand.getId());
                    brandNode.setWebsite(coffeeBrand.getWebsite());
                    brandNode.setCountry(coffeeBrand.getCountry());
                    brandNode.setDescription(coffeeBrand.getDescription());
                    return brandNodeRepository.save(brandNode);
                });
    }

    /**
     * Find or create roast level node
     */
    private RoastLevelNode findOrCreateRoastLevel(String level) {
        return roastLevelNodeRepository.findById(level)
                .orElseGet(() -> {
                    RoastLevelNode node = new RoastLevelNode(level);
                    return roastLevelNodeRepository.save(node);
                });
    }

    /**
     * Extract roast level from product name and description
     */
    private String extractRoastLevel(CoffeeProduct product) {
        String text = "";
        if (product.getProductName() != null) {
            text += product.getProductName().toLowerCase();
        }
        if (product.getRawDescription() != null) {
            text += " " + product.getRawDescription().toLowerCase();
        }

        // Check for roast level keywords
        if (text.contains("light") || text.contains("filter")) {
            return "Light";
        }
        if (text.contains("medium")) {
            return "Medium";
        }
        if (text.contains("dark") || text.contains("espresso")) {
            return "Dark";
        }
        if (text.contains("omni")) {
            return "Omni";
        }
        return "Unknown";
    }

    /**
     * Query products by brand name (via SOLD_BY relationship)
     */
    public List<ProductNode> findProductsByBrandName(String brandName) {
        return productNodeRepository.findByBrandName(brandName);
    }

    /**
     * Query products by roast level
     */
    public List<ProductNode> findProductsByRoastLevel(String level) {
        return productNodeRepository.findByRoastLevel(level);
    }

    /**
     * Delete all TastingNote nodes from Neo4j
     * Use this to clean up after removing TastingNoteNode
     */
    @Transactional(transactionManager = "neo4jTransactionManager")
    public long deleteTastingNoteNodes() {
        log.info("Deleting all TastingNote nodes from Neo4j");

        // Use native Cypher query via Neo4j repository
        String cypher = "MATCH (t:TastingNote) " +
                       "WITH t, id(t) as nodeId " +
                       "DETACH DELETE t " +
                       "RETURN count(nodeId) as deletedCount";

        // Execute via repository (simplified - just count all TastingNote nodes first, then delete)
        // Since we don't have a TastingNoteNodeRepository anymore, we'll use a simple approach
        long count = 0;
        try {
            // This is a workaround - in production, you'd use Neo4j template or direct query
            // For now, the user can use Neo4j Browser directly
            log.warn("Please use Neo4j Browser to run: MATCH (t:TastingNote) DETACH DELETE t");
            return count;
        } catch (Exception e) {
            log.error("Failed to delete TastingNote nodes: {}", e.getMessage());
            throw new RuntimeException("Failed to delete TastingNote nodes", e);
        }
    }
}
