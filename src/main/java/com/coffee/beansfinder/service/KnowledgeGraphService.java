package com.coffee.beansfinder.service;

import com.coffee.beansfinder.dto.SCAFlavorMapping;
import com.coffee.beansfinder.entity.CoffeeProduct;
import com.coffee.beansfinder.graph.node.*;
import com.coffee.beansfinder.graph.repository.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private final ObjectMapper objectMapper;

    /**
     * Create or update product in knowledge graph
     */
    @Transactional
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
            productNode.setBrand(product.getBrand().getName());
            productNode.setProductName(product.getProductName());
            productNode.setVariety(product.getVariety());
            productNode.setPrice(product.getPrice());
            productNode.setCurrency(product.getCurrency());
            productNode.setInStock(product.getInStock());
            productNode.setLastUpdate(product.getLastUpdateDate());

            // Link to origin
            if (product.getOrigin() != null) {
                OriginNode origin = findOrCreateOrigin(
                        product.getOrigin(),
                        product.getRegion(),
                        product.getAltitude());
                productNode.setOrigin(origin);
            }

            // Link to process
            if (product.getProcess() != null) {
                ProcessNode process = findOrCreateProcess(product.getProcess());
                productNode.setProcess(process);
            }

            // Link to producer
            if (product.getProducer() != null) {
                ProducerNode producer = findOrCreateProducer(
                        product.getProducer(),
                        product.getOrigin());
                productNode.setProducer(producer);
            }

            // Link to flavors
            if (product.getScaFlavorsJson() != null && !product.getScaFlavorsJson().isEmpty()) {
                Set<FlavorNode> flavors = createFlavorNodes(product.getScaFlavorsJson());
                productNode.setFlavors(flavors);
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
     * Find or create origin node
     */
    private OriginNode findOrCreateOrigin(String country, String region, String altitude) {
        return originNodeRepository.findByCountryAndRegion(country, region)
                .orElseGet(() -> {
                    OriginNode origin = OriginNode.builder()
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
        return processNodeRepository.findByType(processType)
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
        return producerNodeRepository.findByName(producerName)
                .orElseGet(() -> {
                    ProducerNode producer = ProducerNode.builder()
                            .name(producerName)
                            .country(country)
                            .build();
                    return producerNodeRepository.save(producer);
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

        // Get or create SCA category
        SCACategory category = scaCategoryRepository.findByName(categoryName)
                .orElseGet(() -> {
                    SCACategory cat = SCACategory.builder()
                            .name(categoryName)
                            .build();
                    return scaCategoryRepository.save(cat);
                });

        for (String noteName : notes) {
            FlavorNode flavor = flavorNodeRepository.findByName(noteName)
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
    @Transactional
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
    @Transactional
    public void initializeSCACategories() {
        String[] categories = {"fruity", "floral", "sweet", "nutty", "spices", "roasted", "green", "sour", "other"};

        for (String categoryName : categories) {
            if (scaCategoryRepository.findByName(categoryName).isEmpty()) {
                SCACategory category = SCACategory.builder()
                        .name(categoryName)
                        .build();
                scaCategoryRepository.save(category);
                log.info("Initialized SCA category: {}", categoryName);
            }
        }
    }
}
