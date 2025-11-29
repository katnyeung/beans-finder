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
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for managing the Neo4j knowledge graph
 */
@Service
@Slf4j
public class KnowledgeGraphService {

    private final ProductNodeRepository productNodeRepository;
    private final OriginNodeRepository originNodeRepository;
    private final ProcessNodeRepository processNodeRepository;
    private final FlavorNodeRepository flavorNodeRepository;
    private final SCACategoryRepository scaCategoryRepository;
    private final SubcategoryNodeRepository subcategoryNodeRepository;
    private final AttributeNodeRepository attributeNodeRepository;
    private final TastingNoteNodeRepository tastingNoteNodeRepository;
    private final ProducerNodeRepository producerNodeRepository;
    private final VarietyNodeRepository varietyNodeRepository;
    private final BrandNodeRepository brandNodeRepository;
    private final RoastLevelNodeRepository roastLevelNodeRepository;
    private final CoffeeProductRepository coffeeProductRepository;
    private final ObjectMapper objectMapper;
    private final OpenAIService openAIService;
    private final SCAFlavorWheelService scaFlavorWheelService;
    private final NominatimGeolocationService geolocationService;

    // Constructor with @Lazy for OpenAIService to break circular dependency
    public KnowledgeGraphService(
            ProductNodeRepository productNodeRepository,
            OriginNodeRepository originNodeRepository,
            ProcessNodeRepository processNodeRepository,
            FlavorNodeRepository flavorNodeRepository,
            SCACategoryRepository scaCategoryRepository,
            SubcategoryNodeRepository subcategoryNodeRepository,
            AttributeNodeRepository attributeNodeRepository,
            TastingNoteNodeRepository tastingNoteNodeRepository,
            ProducerNodeRepository producerNodeRepository,
            VarietyNodeRepository varietyNodeRepository,
            BrandNodeRepository brandNodeRepository,
            RoastLevelNodeRepository roastLevelNodeRepository,
            CoffeeProductRepository coffeeProductRepository,
            ObjectMapper objectMapper,
            @Lazy OpenAIService openAIService,
            SCAFlavorWheelService scaFlavorWheelService,
            NominatimGeolocationService geolocationService) {
        this.productNodeRepository = productNodeRepository;
        this.originNodeRepository = originNodeRepository;
        this.processNodeRepository = processNodeRepository;
        this.flavorNodeRepository = flavorNodeRepository;
        this.scaCategoryRepository = scaCategoryRepository;
        this.subcategoryNodeRepository = subcategoryNodeRepository;
        this.attributeNodeRepository = attributeNodeRepository;
        this.tastingNoteNodeRepository = tastingNoteNodeRepository;
        this.producerNodeRepository = producerNodeRepository;
        this.varietyNodeRepository = varietyNodeRepository;
        this.brandNodeRepository = brandNodeRepository;
        this.roastLevelNodeRepository = roastLevelNodeRepository;
        this.coffeeProductRepository = coffeeProductRepository;
        this.objectMapper = objectMapper;
        this.openAIService = openAIService;
        this.scaFlavorWheelService = scaFlavorWheelService;
        this.geolocationService = geolocationService;
    }

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
            productNode.setSellerUrl(product.getSellerUrl());
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

            // Create TastingNoteNodes with 4-tier hierarchy links
            // Product -> TastingNote -> Attribute -> Subcategory -> SCACategory
            Set<TastingNoteNode> tastingNoteNodes = new HashSet<>();

            // Process raw tasting notes from crawl
            if (product.getTastingNotesJson() != null && !product.getTastingNotesJson().isEmpty()) {
                try {
                    List<String> tastingNotes = objectMapper.readValue(
                            product.getTastingNotesJson(),
                            new TypeReference<List<String>>() {}
                    );
                    for (String note : tastingNotes) {
                        if (note != null && !note.trim().isEmpty()) {
                            TastingNoteNode tastingNoteNode = findOrCreateTastingNote(note);
                            if (tastingNoteNode != null) {
                                tastingNoteNodes.add(tastingNoteNode);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("Failed to parse tasting notes JSON for product {}: {}", product.getId(), e.getMessage());
                }
            }

            // Also process SCA-mapped flavors as tasting notes
            if (product.getScaFlavorsJson() != null && !product.getScaFlavorsJson().isEmpty()) {
                try {
                    SCAFlavorMapping mapping = objectMapper.readValue(product.getScaFlavorsJson(), SCAFlavorMapping.class);
                    List<String> allFlavors = new ArrayList<>();
                    if (mapping.getFruity() != null) allFlavors.addAll(mapping.getFruity());
                    if (mapping.getFloral() != null) allFlavors.addAll(mapping.getFloral());
                    if (mapping.getSweet() != null) allFlavors.addAll(mapping.getSweet());
                    if (mapping.getNutty() != null) allFlavors.addAll(mapping.getNutty());
                    if (mapping.getSpices() != null) allFlavors.addAll(mapping.getSpices());
                    if (mapping.getRoasted() != null) allFlavors.addAll(mapping.getRoasted());
                    if (mapping.getGreen() != null) allFlavors.addAll(mapping.getGreen());
                    if (mapping.getSour() != null) allFlavors.addAll(mapping.getSour());
                    if (mapping.getOther() != null) allFlavors.addAll(mapping.getOther());

                    for (String flavor : allFlavors) {
                        if (flavor != null && !flavor.trim().isEmpty()) {
                            TastingNoteNode tastingNoteNode = findOrCreateTastingNote(flavor);
                            if (tastingNoteNode != null) {
                                tastingNoteNodes.add(tastingNoteNode);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("Failed to parse SCA flavors JSON for product {}: {}", product.getId(), e.getMessage());
                }
            }

            productNode.setTastingNotes(tastingNoteNodes);

            // Extract and link to roast level
            String roastLevel = extractRoastLevel(product);
            if (roastLevel != null) {
                RoastLevelNode roastLevelNode = findOrCreateRoastLevel(roastLevel);
                productNode.setRoastLevel(roastLevelNode);

                // Sync roast level back to PostgreSQL if not already set
                if (product.getRoastLevel() == null || !product.getRoastLevel().equals(roastLevel)) {
                    product.setRoastLevel(roastLevel);
                    coffeeProductRepository.save(product);
                    log.info("Synced roast level '{}' to PostgreSQL for product {}", roastLevel, product.getId());
                }
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
                .map(existingNode -> {
                    // Update existing node if needed (e.g., altitude changed)
                    if (altitude != null && !altitude.equals(existingNode.getAltitude())) {
                        existingNode.setAltitude(altitude);
                        return originNodeRepository.save(existingNode);
                    }
                    return existingNode;
                })
                .orElseGet(() -> {
                    OriginNode origin = OriginNode.builder()
                            .id(originId)
                            .country(country)
                            .region(region)
                            .altitude(altitude)
                            .build();

                    // Try to geocode from cache (won't call API if not in cache)
                    try {
                        com.coffee.beansfinder.entity.LocationCoordinates coords =
                            geolocationService.geocode(
                                region != null ? region + ", " + country : country,
                                country,
                                region
                            );
                        if (coords != null) {
                            origin.setLatitude(coords.getLatitude());
                            origin.setLongitude(coords.getLongitude());
                            origin.setBoundingBox(coords.getBoundingBox());
                            log.debug("Geocoded origin {} from cache: ({}, {})",
                                    originId, coords.getLatitude(), coords.getLongitude());
                        }
                    } catch (Exception e) {
                        log.debug("Could not geocode origin {} (may not be in cache): {}",
                                originId, e.getMessage());
                    }

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
     * Add flavors from a specific category with subcategory support (WCR Sensory Lexicon v2.0)
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
            // NORMALIZE TO LOWERCASE for FlavorNode ID
            String normalizedName = noteName.toLowerCase().trim();

            // Get subcategory from enhanced SCAFlavorWheelService
            String subcategory = scaFlavorWheelService.getSubcategoryForNote(noteName);

            // name is the @Id for FlavorNode
            FlavorNode flavor = flavorNodeRepository.findById(normalizedName)
                    .orElseGet(() -> {
                        FlavorNode newFlavor = FlavorNode.builder()
                                .name(normalizedName)
                                .scaCategory(categoryName)
                                .scaSubcategory(subcategory)  // ⭐ NEW: Populate subcategory
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
     * Initialize SCA categories in the graph (Tier 1)
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
     * Initialize the full 4-tier SCA Flavor Wheel hierarchy in Neo4j.
     * Reads structure from sca-lexicon.yaml via SCAFlavorWheelService.
     *
     * Hierarchy: SCACategory (9) → Subcategory (35) → Attribute (~110) → TastingNote (created on crawl)
     */
    @Transactional(transactionManager = "neo4jTransactionManager")
    public void initializeFlavorHierarchy() {
        log.info("Initializing 4-tier SCA Flavor Wheel hierarchy...");

        // Get the hierarchical structure from YAML
        Map<String, Map<String, List<String>>> hierarchy = scaFlavorWheelService.getHierarchicalStructure();

        int subcategoryCount = 0;
        int attributeCount = 0;

        for (Map.Entry<String, Map<String, List<String>>> categoryEntry : hierarchy.entrySet()) {
            String categoryName = categoryEntry.getKey();
            Map<String, List<String>> subcategories = categoryEntry.getValue();

            // Ensure category exists (Tier 1)
            SCACategory category = scaCategoryRepository.findById(categoryName)
                    .orElseGet(() -> {
                        SCACategory newCat = SCACategory.builder().name(categoryName).build();
                        return scaCategoryRepository.save(newCat);
                    });

            // Create subcategories (Tier 2)
            for (Map.Entry<String, List<String>> subcategoryEntry : subcategories.entrySet()) {
                String subcategoryId = subcategoryEntry.getKey();
                List<String> keywords = subcategoryEntry.getValue();

                // Get display name from service (converts "citrus_fruit" to "Citrus Fruit")
                String displayName = formatDisplayName(subcategoryId);

                SubcategoryNode subcategory = subcategoryNodeRepository.findById(subcategoryId)
                        .orElseGet(() -> {
                            SubcategoryNode newSubcat = SubcategoryNode.builder()
                                    .id(subcategoryId)
                                    .displayName(displayName)
                                    .category(category)
                                    .build();
                            return subcategoryNodeRepository.save(newSubcat);
                        });
                subcategoryCount++;

                // Create attributes from keywords (Tier 3)
                for (String keyword : keywords) {
                    String attributeId = keyword.toLowerCase().trim();
                    String attrDisplayName = formatDisplayName(attributeId);

                    if (attributeNodeRepository.findById(attributeId).isEmpty()) {
                        AttributeNode attribute = AttributeNode.builder()
                                .id(attributeId)
                                .displayName(attrDisplayName)
                                .subcategory(subcategory)
                                .build();
                        attributeNodeRepository.save(attribute);
                        attributeCount++;
                    }
                }
            }
        }

        log.info("Initialized SCA hierarchy: {} subcategories, {} attributes", subcategoryCount, attributeCount);
    }

    /**
     * Format ID to display name (e.g., "citrus_fruit" → "Citrus Fruit", "blackberry" → "Blackberry")
     */
    private String formatDisplayName(String id) {
        if (id == null || id.isEmpty()) return "";
        return Arrays.stream(id.split("[_\\s]+"))
                .map(word -> word.substring(0, 1).toUpperCase() + word.substring(1).toLowerCase())
                .collect(Collectors.joining(" "));
    }

    /**
     * Find or create a TastingNote node and link it to the appropriate Attribute.
     * Used when syncing products to the graph.
     */
    @Transactional(transactionManager = "neo4jTransactionManager")
    public TastingNoteNode findOrCreateTastingNote(String rawText) {
        if (rawText == null || rawText.trim().isEmpty()) {
            return null;
        }

        String noteId = rawText.toLowerCase().trim();

        // Check if already exists
        Optional<TastingNoteNode> existing = tastingNoteNodeRepository.findById(noteId);
        if (existing.isPresent()) {
            return existing.get();
        }

        // Find the best matching attribute using SCAFlavorWheelService
        SCAFlavorWheelService.CategorySubcategory mapping = scaFlavorWheelService.findCategorySubcategory(rawText);

        AttributeNode attribute = null;
        if (mapping != null && mapping.subcategory != null) {
            // Try to find attribute that matches the raw text or subcategory keyword
            String attributeId = findBestAttributeId(rawText, mapping.subcategory);
            attribute = attributeNodeRepository.findById(attributeId).orElse(null);
        }

        // Create and save the tasting note
        TastingNoteNode tastingNote = TastingNoteNode.builder()
                .id(noteId)
                .rawText(rawText)
                .attribute(attribute)
                .build();

        return tastingNoteNodeRepository.save(tastingNote);
    }

    /**
     * Find the best matching attribute ID for a raw tasting note.
     */
    private String findBestAttributeId(String rawText, String subcategory) {
        String normalized = rawText.toLowerCase().trim();

        // First, check if the raw text itself is an attribute
        if (attributeNodeRepository.findById(normalized).isPresent()) {
            return normalized;
        }

        // Try to find the keyword that best matches within the raw text
        // e.g., "blackberry with tea" should match "blackberry"
        Map<String, Map<String, List<String>>> hierarchy = scaFlavorWheelService.getHierarchicalStructure();

        for (Map.Entry<String, Map<String, List<String>>> catEntry : hierarchy.entrySet()) {
            Map<String, List<String>> subcats = catEntry.getValue();
            if (subcats.containsKey(subcategory)) {
                for (String keyword : subcats.get(subcategory)) {
                    if (normalized.contains(keyword.toLowerCase())) {
                        return keyword.toLowerCase();
                    }
                }
                // If no keyword found in text, return the first keyword as default
                List<String> keywords = subcats.get(subcategory);
                if (!keywords.isEmpty()) {
                    return keywords.get(0).toLowerCase();
                }
            }
        }

        // Fallback: return the subcategory itself as attribute
        return subcategory;
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
                .map(existingNode -> {
                    // Update existing node with latest data including coordinates
                    existingNode.setBrandId(coffeeBrand.getId());
                    existingNode.setWebsite(coffeeBrand.getWebsite());
                    existingNode.setCountry(coffeeBrand.getCountry());
                    existingNode.setDescription(coffeeBrand.getDescription());
                    existingNode.setLatitude(coffeeBrand.getLatitude());
                    existingNode.setLongitude(coffeeBrand.getLongitude());
                    return brandNodeRepository.save(existingNode);
                })
                .orElseGet(() -> {
                    BrandNode brandNode = new BrandNode(coffeeBrand.getName());
                    brandNode.setBrandId(coffeeBrand.getId());
                    brandNode.setWebsite(coffeeBrand.getWebsite());
                    brandNode.setCountry(coffeeBrand.getCountry());
                    brandNode.setDescription(coffeeBrand.getDescription());
                    brandNode.setLatitude(coffeeBrand.getLatitude());
                    brandNode.setLongitude(coffeeBrand.getLongitude());
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

    /**
     * Delete invalid origin nodes (blends, multi-origins, malformed country names)
     * Examples: "Blend (Colombia", "Brazil)", "Ethiopia)", "Huila, Minas Gerais, Sidamo"
     */
    @Transactional
    public int deleteInvalidOriginNodes() {
        log.info("Deleting invalid origin nodes from Neo4j");

        List<OriginNode> allOrigins = (List<OriginNode>) originNodeRepository.findAll();
        int deletedCount = 0;

        for (OriginNode origin : allOrigins) {
            if (isInvalidOrigin(origin)) {
                log.info("Deleting invalid origin: id={}, country={}, region={}",
                        origin.getId(), origin.getCountry(), origin.getRegion());
                originNodeRepository.delete(origin);
                deletedCount++;
            }
        }

        log.info("Deleted {} invalid origin nodes", deletedCount);
        return deletedCount;
    }

    /**
     * Check if an origin node is invalid (blend, multi-origin, or malformed)
     */
    private boolean isInvalidOrigin(OriginNode origin) {
        String country = origin.getCountry();
        String region = origin.getRegion();

        // Check country for invalid patterns
        if (country != null) {
            String normalized = country.trim().toLowerCase();

            // Malformed country names with unmatched parentheses
            if (normalized.endsWith(")") || normalized.startsWith("(")) {
                return true; // "Brazil)", "Ethiopia)", "(Colombia", "Blend (Colombia"
            }

            // Blend indicators
            if (normalized.startsWith("blend") || normalized.contains("blend")) {
                return true;
            }

            // Multi-country indicators
            if (normalized.contains(" & ") || normalized.contains(" and ")) {
                return true; // "Colombia & Ethiopia", "Brazil and Ethiopia"
            }

            // Percentage indicators (blend ratios)
            if (normalized.contains("%")) {
                return true; // "30% Brazil", "20% Nicaragua)"
            }

            // Single Origin is not a real country
            if (normalized.equals("single origin")) {
                return true;
            }
        }

        // Check region for multi-origin patterns
        if (region != null) {
            String normalized = region.trim().toLowerCase();

            // Check if region contains multiple distinct coffee-producing regions (cross-country blends)
            if (normalized.contains("huila") && (normalized.contains("minas gerais") || normalized.contains("sidamo"))) {
                return true; // Huila (Colombia) mixed with Brazil/Ethiopia regions
            }
            if (normalized.contains("minas gerais") && normalized.contains("sidamo")) {
                return true; // Brazil mixed with Ethiopia regions
            }

            // Check for explicit country names in region (indicates blend)
            if (normalized.matches(".*\\b(colombia|brazil|ethiopia)\\b.*")) {
                return true; // Region contains actual country names = blend
            }

            // General pattern: if region contains 4+ commas, it's likely a multi-origin blend
            if (normalized.split(",").length > 4) {
                return true;
            }
        }

        return false;
    }

    /**
     * Fix null SCA categories - set all flavors with null category to 'other'
     */
    @Transactional
    public int fixNullScaCategories() {
        log.info("Fixing null SCA categories...");

        // Get all flavors with null scaCategory
        List<FlavorNode> allFlavors = flavorNodeRepository.findAll();
        int fixedCount = 0;

        for (FlavorNode flavor : allFlavors) {
            if (flavor.getScaCategory() == null || flavor.getScaCategory().isEmpty()) {
                flavor.setScaCategory("other");
                flavorNodeRepository.save(flavor);
                fixedCount++;
                log.debug("Fixed flavor: {} -> other", flavor.getName());
            }
        }

        log.info("Fixed {} flavors with null SCA categories", fixedCount);
        return fixedCount;
    }

    /**
     * Common descriptors/metadata that are NOT actual flavors
     */
    private static final Set<String> NON_FLAVOR_KEYWORDS = Set.of(
        // Texture/Mouthfeel
        "smooth", "creamy", "silky", "velvety", "thick", "thin", "light", "heavy",
        "crisp", "clean", "dry", "wet", "oily", "watery",

        // Quality descriptors
        "balanced", "rich", "mellow", "vibrant", "punchy", "bold", "intense",
        "complex", "simple", "easy to drink", "gentle enjoyment", "boundary-pushing",

        // Body descriptors
        "body", "medium body", "full body", "light body", "body: medium", "body: full",

        // Acidity descriptors
        "acidity", "low acidity", "high acidity", "bright acidity", "acidity: low",
        "acidity: medium", "acidity: high",

        // Sweetness descriptors
        "sweetness", "sweetness: low", "sweetness: medium", "sweetness: high",

        // Processing/Metadata
        "organic", "naturally decaffeinated", "decaf", "swiss water process",
        "fair trade", "single origin", "blend",

        // General descriptors
        "juicy", "fresh", "ripe", "mature", "young", "aged",
        "dark", "mild", "strong"
    );

    /**
     * Cleanup and merge duplicate tasting notes (case-insensitive)
     * Also deletes old FlavorNode nodes that are no longer used.
     * TastingNoteNode IDs are already normalized (lowercase), so duplicates are rare.
     *
     * @return Map with statistics
     */
    @Transactional(transactionManager = "neo4jTransactionManager")
    public Map<String, Object> cleanupAndMergeFlavors() {
        log.info("Starting tasting note cleanup...");

        // Step 1: Clean up old FlavorNode nodes (deprecated)
        List<FlavorNode> allFlavors = flavorNodeRepository.findAll();
        int flavorNodesDeleted = allFlavors.size();
        if (!allFlavors.isEmpty()) {
            log.info("Deleting {} deprecated FlavorNode nodes", flavorNodesDeleted);
            flavorNodeRepository.deleteAll();
        }

        // Step 2: Check for duplicate TastingNoteNodes (should be rare since IDs are normalized)
        List<TastingNoteNode> allNotes = tastingNoteNodeRepository.findAll();
        log.info("Found {} tasting note nodes", allNotes.size());

        int merged = 0;
        int deleted = 0;

        // Group by lowercase ID (should already be lowercase)
        Map<String, List<TastingNoteNode>> noteGroups = allNotes.stream()
            .collect(Collectors.groupingBy(n -> n.getId().toLowerCase()));

        for (Map.Entry<String, List<TastingNoteNode>> entry : noteGroups.entrySet()) {
            List<TastingNoteNode> duplicates = entry.getValue();
            if (duplicates.size() > 1) {
                // Found duplicates - keep the first one
                log.info("Found {} duplicates for note: {}", duplicates.size(), entry.getKey());
                TastingNoteNode canonical = duplicates.get(0);

                for (int i = 1; i < duplicates.size(); i++) {
                    TastingNoteNode duplicate = duplicates.get(i);
                    // Find products linked to this duplicate and reassign
                    List<ProductNode> products = productNodeRepository.findAll().stream()
                        .filter(p -> p.getTastingNotes() != null &&
                               p.getTastingNotes().stream().anyMatch(n -> n.getId().equals(duplicate.getId())))
                        .collect(Collectors.toList());

                    for (ProductNode product : products) {
                        Set<TastingNoteNode> notes = product.getTastingNotes();
                        notes.removeIf(n -> n.getId().equals(duplicate.getId()));
                        notes.add(canonical);
                        productNodeRepository.save(product);
                    }

                    tastingNoteNodeRepository.delete(duplicate);
                    deleted++;
                }
                merged++;
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("flavorNodesDeleted", flavorNodesDeleted);
        result.put("tastingNotes", allNotes.size());
        result.put("mergedGroups", merged);
        result.put("duplicatesDeleted", deleted);
        result.put("message", String.format(
            "Cleanup complete: deleted %d deprecated FlavorNodes, %d TastingNotes (%d duplicates merged)",
            flavorNodesDeleted, allNotes.size(), merged));

        log.info("Cleanup complete: {} FlavorNodes deleted, {} TastingNotes, {} duplicates merged",
            flavorNodesDeleted, allNotes.size(), merged);

        return result;
    }

    /**
     * Check if a flavor name is actually a descriptor/metadata (not a real flavor)
     */
    private boolean shouldFilterOut(String lowerName) {
        // Direct match
        if (NON_FLAVOR_KEYWORDS.contains(lowerName)) {
            return true;
        }

        // Contains pattern matching
        return lowerName.contains("body:") ||
               lowerName.contains("acidity:") ||
               lowerName.contains("sweetness:") ||
               lowerName.startsWith("body ") ||
               lowerName.startsWith("acidity ") ||
               lowerName.startsWith("sweetness ");
    }

    /**
     * Re-analyze 'other' category flavors using OpenAI to properly categorize them
     * Processes flavors in batches of 50 to avoid token limits
     * Cost: ~$0.001 per 50 flavors (~$0.02 for 814 flavors)
     *
     * @return Map with statistics (total, categorized, failed, batchCount)
     */
    @Transactional(transactionManager = "neo4jTransactionManager")
    public Map<String, Object> reAnalyzeOtherFlavorsWithOpenAI() {
        log.info("Re-analyzing 'other' category flavors with OpenAI...");

        // Get all flavors in 'other' category
        List<FlavorNode> otherFlavors = flavorNodeRepository.findByScaCategory("other");

        if (otherFlavors.isEmpty()) {
            log.info("No flavors in 'other' category to re-analyze");
            return Map.of(
                "total", 0,
                "categorized", 0,
                "failed", 0,
                "message", "No flavors to re-analyze"
            );
        }

        log.info("Found {} flavors in 'other' category", otherFlavors.size());

        int batchSize = 50;
        int totalCategorized = 0;
        int totalFailed = 0;
        int batchCount = 0;

        // Process in batches
        for (int i = 0; i < otherFlavors.size(); i += batchSize) {
            int end = Math.min(i + batchSize, otherFlavors.size());
            List<FlavorNode> batch = otherFlavors.subList(i, end);
            batchCount++;

            log.info("Processing batch {}/{}: flavors {}-{}",
                batchCount,
                (otherFlavors.size() + batchSize - 1) / batchSize,
                i + 1,
                end);

            try {
                // Extract flavor names
                List<String> flavorNames = batch.stream()
                    .map(FlavorNode::getName)
                    .collect(Collectors.toList());

                // Call OpenAI to categorize
                Map<String, String> categorizations = openAIService.categorizeFlavors(flavorNames);

                // Update flavor nodes
                for (FlavorNode flavor : batch) {
                    String newCategory = categorizations.get(flavor.getName());
                    if (newCategory != null && !newCategory.equals("other")) {
                        String oldCategory = flavor.getScaCategory();
                        flavor.setScaCategory(newCategory);
                        flavorNodeRepository.save(flavor);
                        totalCategorized++;
                        log.debug("Updated flavor '{}': {} -> {}",
                            flavor.getName(), oldCategory, newCategory);
                    } else {
                        log.debug("Flavor '{}' remains in 'other' category", flavor.getName());
                    }
                }

                // Small delay between batches to avoid rate limiting
                if (i + batchSize < otherFlavors.size()) {
                    Thread.sleep(500);
                }

            } catch (Exception e) {
                log.error("Failed to process batch {}: {}", batchCount, e.getMessage());
                totalFailed += batch.size();
            }
        }

        int stillOther = otherFlavors.size() - totalCategorized - totalFailed;

        Map<String, Object> result = new HashMap<>();
        result.put("total", otherFlavors.size());
        result.put("categorized", totalCategorized);
        result.put("failed", totalFailed);
        result.put("stillOther", stillOther);
        result.put("batchCount", batchCount);
        result.put("message", String.format(
            "Re-analyzed %d flavors: %d categorized, %d failed, %d remain in 'other'",
            otherFlavors.size(), totalCategorized, totalFailed, stillOther));

        log.info("Re-analysis complete: {} categorized, {} failed, {} still in 'other'",
            totalCategorized, totalFailed, stillOther);

        return result;
    }

    /**
     * Re-link unmatched TastingNotes to Attributes using OpenAI LLM.
     * Processes notes in batches of 50 to avoid token limits.
     * Cost: ~$0.0002 per batch of 50 notes (~$0.003 for 666 notes)
     *
     * @return Map with statistics (total, linked, failed, batchCount)
     */
    @Transactional(transactionManager = "neo4jTransactionManager")
    public Map<String, Object> relinkUnmatchedTastingNotesWithLLM() {
        log.info("Re-linking unmatched TastingNotes using OpenAI LLM...");

        // Get all TastingNotes without attribute link
        List<TastingNoteNode> unmatchedNotes = tastingNoteNodeRepository.findAll().stream()
                .filter(tn -> tn.getAttribute() == null)
                .collect(Collectors.toList());

        if (unmatchedNotes.isEmpty()) {
            log.info("No unmatched TastingNotes to re-link");
            return Map.of(
                    "total", 0,
                    "linked", 0,
                    "failed", 0,
                    "message", "No unmatched TastingNotes to re-link"
            );
        }

        log.info("Found {} unmatched TastingNotes", unmatchedNotes.size());

        // Get all available attribute IDs
        List<String> availableAttributes = attributeNodeRepository.findAll().stream()
                .map(AttributeNode::getId)
                .collect(Collectors.toList());

        if (availableAttributes.isEmpty()) {
            log.warn("No attributes found! Run init-flavor-hierarchy first.");
            return Map.of(
                    "total", unmatchedNotes.size(),
                    "linked", 0,
                    "failed", unmatchedNotes.size(),
                    "message", "No attributes found - run init-flavor-hierarchy first"
            );
        }

        log.info("Available attributes: {}", availableAttributes.size());

        int batchSize = 50;
        int totalLinked = 0;
        int totalFailed = 0;
        int batchCount = 0;

        // Process in batches
        for (int i = 0; i < unmatchedNotes.size(); i += batchSize) {
            int end = Math.min(i + batchSize, unmatchedNotes.size());
            List<TastingNoteNode> batch = unmatchedNotes.subList(i, end);
            batchCount++;

            log.info("Processing batch {}/{}: notes {}-{}",
                    batchCount,
                    (unmatchedNotes.size() + batchSize - 1) / batchSize,
                    i + 1,
                    end);

            try {
                // Extract raw text from batch
                List<String> noteTexts = batch.stream()
                        .map(tn -> tn.getRawText() != null ? tn.getRawText() : tn.getId())
                        .collect(Collectors.toList());

                // Call OpenAI to map notes to attributes
                Map<String, String> mappings = openAIService.mapTastingNotesToAttributes(noteTexts, availableAttributes);

                // Update TastingNote nodes with attribute links
                for (TastingNoteNode note : batch) {
                    String noteText = note.getRawText() != null ? note.getRawText() : note.getId();
                    String attributeId = mappings.get(noteText);

                    if (attributeId != null && !attributeId.equals("null")) {
                        Optional<AttributeNode> attribute = attributeNodeRepository.findById(attributeId.toLowerCase());
                        if (attribute.isPresent()) {
                            note.setAttribute(attribute.get());
                            tastingNoteNodeRepository.save(note);
                            totalLinked++;
                            log.debug("Linked '{}' -> '{}'", noteText, attributeId);
                        } else {
                            log.warn("Attribute '{}' not found for note '{}'", attributeId, noteText);
                            totalFailed++;
                        }
                    } else {
                        log.debug("No match for note '{}'", noteText);
                        // Not counted as failed - just no good match exists
                    }
                }

                // Small delay between batches to avoid rate limiting
                if (i + batchSize < unmatchedNotes.size()) {
                    Thread.sleep(500);
                }

            } catch (Exception e) {
                log.error("Failed to process batch {}: {}", batchCount, e.getMessage());
                totalFailed += batch.size();
            }
        }

        int stillUnmatched = unmatchedNotes.size() - totalLinked - totalFailed;

        Map<String, Object> result = new HashMap<>();
        result.put("total", unmatchedNotes.size());
        result.put("linked", totalLinked);
        result.put("failed", totalFailed);
        result.put("stillUnmatched", stillUnmatched);
        result.put("batchCount", batchCount);
        result.put("message", String.format(
                "Re-linked %d TastingNotes: %d linked, %d failed, %d no match found",
                unmatchedNotes.size(), totalLinked, totalFailed, stillUnmatched));

        log.info("Re-link complete: {} linked, {} failed, {} no match",
                totalLinked, totalFailed, stillUnmatched);

        return result;
    }

    /**
     * Get list of all available attribute IDs (for reference)
     */
    public List<String> getAvailableAttributeIds() {
        return attributeNodeRepository.findAll().stream()
                .map(AttributeNode::getId)
                .sorted()
                .collect(Collectors.toList());
    }
}
