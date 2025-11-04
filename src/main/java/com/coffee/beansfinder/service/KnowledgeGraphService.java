package com.coffee.beansfinder.service;

import com.coffee.beansfinder.entity.CoffeeProduct;
import com.coffee.beansfinder.neo4j.*;
import com.coffee.beansfinder.repository.CoffeeNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service for managing Neo4j knowledge graph
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class KnowledgeGraphService {

    private final CoffeeNodeRepository coffeeNodeRepository;
    private final SCAFlavorWheelMapper flavorMapper;

    /**
     * Create or update a product node in the knowledge graph
     */
    @Transactional
    public CoffeeNode createOrUpdateProductNode(CoffeeProduct product) {
        log.info("Creating/updating knowledge graph node for product: {}", product.getId());

        // Find existing node or create new one
        CoffeeNode node = coffeeNodeRepository.findByProductId(product.getId())
            .orElse(CoffeeNode.builder()
                .productId(product.getId())
                .build());

        // Update basic properties
        node.setName(product.getProductName());
        node.setBrand(product.getBrand());
        node.setPrice(product.getPrice());
        node.setCurrency(product.getCurrency());

        // Create relationships
        node.setOrigins(createOriginNodes(product));
        node.setProcesses(createProcessNodes(product));
        node.setFlavors(createFlavorNodes(product));
        node.setVarieties(createVarietyNodes(product));

        return coffeeNodeRepository.save(node);
    }

    private Set<OriginNode> createOriginNodes(CoffeeProduct product) {
        Set<OriginNode> origins = new HashSet<>();

        if (product.getOrigin() != null) {
            OriginNode origin = OriginNode.builder()
                .country(product.getOrigin())
                .region(product.getRegion())
                .build();
            origins.add(origin);
        }

        return origins;
    }

    private Set<ProcessNode> createProcessNodes(CoffeeProduct product) {
        Set<ProcessNode> processes = new HashSet<>();

        if (product.getProcess() != null) {
            ProcessNode process = ProcessNode.builder()
                .type(product.getProcess())
                .build();
            processes.add(process);
        }

        return processes;
    }

    private Set<FlavorNode> createFlavorNodes(CoffeeProduct product) {
        Set<FlavorNode> flavors = new HashSet<>();

        if (product.getTastingNotes() != null) {
            for (String note : product.getTastingNotes()) {
                Map<String, String> mapping = flavorMapper.mapTastingNote(note);

                FlavorNode flavor = FlavorNode.builder()
                    .specific(note)
                    .scaCategory(mapping.get("category"))
                    .scaSubcategory(mapping.get("subcategory"))
                    .build();

                // Create SCA wheel relationship
                if (mapping.get("category") != null) {
                    SCAWheelNode scaNode = SCAWheelNode.builder()
                        .category(mapping.get("category"))
                        .subcategory(mapping.get("subcategory"))
                        .build();

                    Set<SCAWheelNode> scaCategories = new HashSet<>();
                    scaCategories.add(scaNode);
                    flavor.setScaCategories(scaCategories);
                }

                flavors.add(flavor);
            }
        }

        return flavors;
    }

    private Set<VarietyNode> createVarietyNodes(CoffeeProduct product) {
        Set<VarietyNode> varieties = new HashSet<>();

        if (product.getVariety() != null) {
            VarietyNode variety = VarietyNode.builder()
                .name(product.getVariety())
                .build();
            varieties.add(variety);
        }

        return varieties;
    }

    /**
     * Query knowledge graph by SCA category
     */
    public List<CoffeeNode> findBySCACategory(String category) {
        return coffeeNodeRepository.findBySCACategory(category);
    }

    /**
     * Query knowledge graph by process and SCA category
     */
    public List<CoffeeNode> findByProcessAndFlavor(String process, String scaCategory) {
        return coffeeNodeRepository.findByProcessAndSCACategory(process, scaCategory);
    }

    /**
     * Query knowledge graph by flavor keyword
     */
    public List<CoffeeNode> findByFlavor(String flavor) {
        return coffeeNodeRepository.findByFlavorContaining(flavor);
    }

    /**
     * Query knowledge graph by variety
     */
    public List<CoffeeNode> findByVariety(String variety) {
        return coffeeNodeRepository.findByVariety(variety);
    }

    /**
     * Query knowledge graph by origin
     */
    public List<CoffeeNode> findByOrigin(String country) {
        return coffeeNodeRepository.findByOriginCountry(country);
    }
}
