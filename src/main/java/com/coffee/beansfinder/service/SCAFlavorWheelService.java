package com.coffee.beansfinder.service;

import com.coffee.beansfinder.dto.SCAFlavorMapping;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for mapping tasting notes to World Coffee Research Sensory Lexicon
 * Full 3-tier hierarchical implementation: Category → Subcategory → Attribute
 *
 * Based on WCR Sensory Lexicon v2.0 (2017) - 110 attributes across 9 categories
 * Reference: https://worldcoffeeresearch.org/work/sensory-lexicon/
 *
 * Configuration: Loads from resources/config/sca-lexicon.yaml
 * Fallback: Uses hardcoded lexicon if YAML loading fails
 */
@Service
@Slf4j
public class SCAFlavorWheelService {

    private static final String LEXICON_CONFIG_PATH = "config/sca-lexicon.yaml";

    // WCR Sensory Lexicon - 3-Tier Hierarchical Structure
    // Category → Subcategory → Attributes (with keyword mappings)
    private Map<String, Map<String, List<String>>> hierarchicalFlavors;

    // Reverse mapping: Keyword → {Category, Subcategory}
    private Map<String, CategorySubcategory> keywordToCategory;

    @PostConstruct
    public void initialize() {
        try {
            loadLexiconFromYaml();
        } catch (Exception e) {
            log.error("Failed to load lexicon from YAML, using hardcoded fallback: {}", e.getMessage());
            initializeHardcodedLexicon();
        }

        buildKeywordIndex();

        log.info("Initialized WCR Sensory Lexicon: {} categories, {} keywords",
                hierarchicalFlavors.size(), keywordToCategory.size());
    }

    /**
     * Load WCR Sensory Lexicon from YAML configuration file
     */
    @SuppressWarnings("unchecked")
    private void loadLexiconFromYaml() throws Exception {
        ClassPathResource resource = new ClassPathResource(LEXICON_CONFIG_PATH);

        if (!resource.exists()) {
            throw new IllegalStateException("Lexicon YAML file not found: " + LEXICON_CONFIG_PATH);
        }

        Yaml yaml = new Yaml();
        Map<String, Object> config;

        try (InputStream inputStream = resource.getInputStream()) {
            config = yaml.load(inputStream);
        }

        if (config == null || !config.containsKey("categories")) {
            throw new IllegalStateException("Invalid YAML structure: missing 'categories' key");
        }

        hierarchicalFlavors = new HashMap<>();
        Map<String, Object> categories = (Map<String, Object>) config.get("categories");

        for (Map.Entry<String, Object> categoryEntry : categories.entrySet()) {
            String categoryName = categoryEntry.getKey();
            Map<String, Object> categoryData = (Map<String, Object>) categoryEntry.getValue();

            if (!categoryData.containsKey("subcategories")) {
                log.warn("Category {} missing subcategories, skipping", categoryName);
                continue;
            }

            Map<String, List<String>> subcategories = new HashMap<>();
            Map<String, Object> subcategoriesData = (Map<String, Object>) categoryData.get("subcategories");

            for (Map.Entry<String, Object> subcategoryEntry : subcategoriesData.entrySet()) {
                String subcategoryName = subcategoryEntry.getKey();
                Map<String, Object> subcategoryData = (Map<String, Object>) subcategoryEntry.getValue();

                if (subcategoryData.containsKey("keywords")) {
                    List<String> keywords = (List<String>) subcategoryData.get("keywords");
                    subcategories.put(subcategoryName, keywords);
                } else {
                    log.warn("Subcategory {}.{} missing keywords", categoryName, subcategoryName);
                }
            }

            hierarchicalFlavors.put(categoryName, subcategories);
        }

        // Validate that we loaded reasonable data
        if (hierarchicalFlavors.isEmpty()) {
            throw new IllegalStateException("No categories loaded from YAML");
        }

        // Log metadata if available
        if (config.containsKey("metadata")) {
            Map<String, Object> metadata = (Map<String, Object>) config.get("metadata");
            log.info("Loaded WCR Sensory Lexicon version: {}", metadata.get("version"));
        }
    }

    /**
     * Fallback: Initialize hardcoded lexicon (subset for safety)
     */
    private void initializeHardcodedLexicon() {
        hierarchicalFlavors = new HashMap<>();

        // Basic hardcoded structure as fallback
        Map<String, List<String>> fruity = new HashMap<>();
        fruity.put("berry", Arrays.asList("berry", "berries", "strawberry", "blueberry", "raspberry", "blackberry"));
        fruity.put("citrus_fruit", Arrays.asList("citrus", "lemon", "lime", "orange", "grapefruit"));
        hierarchicalFlavors.put("fruity", fruity);

        Map<String, List<String>> floral = new HashMap<>();
        floral.put("floral", Arrays.asList("floral", "jasmine", "rose", "chamomile", "lavender"));
        hierarchicalFlavors.put("floral", floral);

        Map<String, List<String>> sweet = new HashMap<>();
        sweet.put("brown_sugar", Arrays.asList("caramel", "honey", "maple", "molasses"));
        hierarchicalFlavors.put("sweet", sweet);

        Map<String, List<String>> nutty = new HashMap<>();
        nutty.put("nutty", Arrays.asList("nutty", "almond", "hazelnut", "peanut"));
        nutty.put("cocoa", Arrays.asList("chocolate", "cocoa", "dark chocolate"));
        hierarchicalFlavors.put("nutty", nutty);

        Map<String, List<String>> spices = new HashMap<>();
        spices.put("brown_spice", Arrays.asList("cinnamon", "clove", "nutmeg"));
        hierarchicalFlavors.put("spices", spices);

        Map<String, List<String>> roasted = new HashMap<>();
        roasted.put("roasted", Arrays.asList("roasted", "smoky", "tobacco"));
        hierarchicalFlavors.put("roasted", roasted);

        Map<String, List<String>> green = new HashMap<>();
        green.put("green_vegetative", Arrays.asList("green", "vegetative", "herbal", "grass"));
        hierarchicalFlavors.put("green", green);

        Map<String, List<String>> sour = new HashMap<>();
        sour.put("sour_aromatics", Arrays.asList("sour", "tart", "tangy"));
        sour.put("alcohol_fermented", Arrays.asList("fermented", "winey", "wine"));
        hierarchicalFlavors.put("sour", sour);

        Map<String, List<String>> other = new HashMap<>();
        other.put("earthy", Arrays.asList("earthy", "musty", "woody"));
        hierarchicalFlavors.put("other", other);

        log.warn("Using hardcoded fallback lexicon with limited coverage");
    }

    /**
     * Build reverse index: keyword → category + subcategory
     */
    private void buildKeywordIndex() {
        keywordToCategory = new HashMap<>();

        for (Map.Entry<String, Map<String, List<String>>> categoryEntry : hierarchicalFlavors.entrySet()) {
            String category = categoryEntry.getKey();

            for (Map.Entry<String, List<String>> subcategoryEntry : categoryEntry.getValue().entrySet()) {
                String subcategory = subcategoryEntry.getKey();

                for (String keyword : subcategoryEntry.getValue()) {
                    keywordToCategory.put(keyword.toLowerCase(),
                        new CategorySubcategory(category, subcategory));
                }
            }
        }
    }

    /**
     * Map a list of tasting notes to SCA flavor categories with subcategories
     */
    public SCAFlavorMapping mapTastingNotes(List<String> tastingNotes) {
        if (tastingNotes == null || tastingNotes.isEmpty()) {
            return SCAFlavorMapping.builder().build();
        }

        SCAFlavorMapping mapping = SCAFlavorMapping.builder()
                .fruity(new ArrayList<>())
                .floral(new ArrayList<>())
                .sweet(new ArrayList<>())
                .nutty(new ArrayList<>())
                .spices(new ArrayList<>())
                .roasted(new ArrayList<>())
                .green(new ArrayList<>())
                .sour(new ArrayList<>())
                .other(new ArrayList<>())
                .build();

        // Process each note
        for (String note : tastingNotes) {
            String normalizedNote = note.toLowerCase().trim();
            boolean matched = false;

            // Try exact match first, then substring match
            CategorySubcategory result = findCategorySubcategory(normalizedNote);

            if (result != null && !result.category.equals("other") ||
                (result != null && !result.subcategory.equals("uncategorized"))) {
                addToCategory(mapping, result.category, note);
                matched = true;
            }

            // If no match found, add to "other"
            if (!matched) {
                mapping.getOther().add(note);
                log.debug("Unmapped tasting note: {}", note);
            }
        }

        return mapping;
    }

    /**
     * Find category and subcategory for a given tasting note
     * Returns both category and subcategory for hierarchical navigation
     */
    public CategorySubcategory findCategorySubcategory(String tastingNote) {
        if (tastingNote == null || tastingNote.trim().isEmpty()) {
            return new CategorySubcategory("other", "uncategorized");
        }

        String normalized = tastingNote.toLowerCase().trim();

        // Try exact match first
        if (keywordToCategory.containsKey(normalized)) {
            return keywordToCategory.get(normalized);
        }

        // Try substring match (longest match first)
        List<Map.Entry<String, CategorySubcategory>> matches = keywordToCategory.entrySet().stream()
            .filter(entry -> normalized.contains(entry.getKey()))
            .sorted((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()))
            .toList();

        if (!matches.isEmpty()) {
            return matches.get(0).getValue();
        }

        return new CategorySubcategory("other", "uncategorized");
    }

    /**
     * Map a single tasting note to its SCA category (legacy method for backward compatibility)
     */
    public String getCategoryForNote(String tastingNote) {
        CategorySubcategory result = findCategorySubcategory(tastingNote);
        return result != null ? result.category : "other";
    }

    /**
     * Get subcategory for a tasting note
     */
    public String getSubcategoryForNote(String tastingNote) {
        CategorySubcategory result = findCategorySubcategory(tastingNote);
        return result != null ? result.subcategory : "uncategorized";
    }

    /**
     * Get all subcategories for a specific category
     */
    public Set<String> getSubcategoriesForCategory(String category) {
        Map<String, List<String>> subcategories = hierarchicalFlavors.get(category.toLowerCase());
        return subcategories != null ? subcategories.keySet() : new HashSet<>();
    }

    /**
     * Get all keywords for a specific subcategory
     */
    public List<String> getKeywordsForSubcategory(String category, String subcategory) {
        Map<String, List<String>> subcategories = hierarchicalFlavors.get(category.toLowerCase());
        if (subcategories != null) {
            return subcategories.getOrDefault(subcategory.toLowerCase(), new ArrayList<>());
        }
        return new ArrayList<>();
    }

    /**
     * Get all keywords for a specific SCA category (legacy method)
     */
    public List<String> getKeywordsForCategory(String category) {
        Map<String, List<String>> subcategories = hierarchicalFlavors.get(category.toLowerCase());
        if (subcategories == null) {
            return new ArrayList<>();
        }

        return subcategories.values().stream()
            .flatMap(List::stream)
            .collect(Collectors.toList());
    }

    /**
     * Get all SCA categories
     */
    public Set<String> getAllCategories() {
        return hierarchicalFlavors.keySet();
    }

    /**
     * Get hierarchical structure (for frontend visualization)
     */
    public Map<String, Map<String, List<String>>> getHierarchicalStructure() {
        return new HashMap<>(hierarchicalFlavors);
    }

    /**
     * Helper method to add note to appropriate category
     */
    private void addToCategory(SCAFlavorMapping mapping, String category, String note) {
        switch (category) {
            case "fruity" -> mapping.getFruity().add(note);
            case "floral" -> mapping.getFloral().add(note);
            case "sweet" -> mapping.getSweet().add(note);
            case "nutty" -> mapping.getNutty().add(note);
            case "spices" -> mapping.getSpices().add(note);
            case "roasted" -> mapping.getRoasted().add(note);
            case "green" -> mapping.getGreen().add(note);
            case "sour" -> mapping.getSour().add(note);
            default -> mapping.getOther().add(note);
        }
    }

    /**
     * Validate if a note belongs to a specific category
     */
    public boolean isInCategory(String tastingNote, String category) {
        return getCategoryForNote(tastingNote).equalsIgnoreCase(category);
    }

    /**
     * Get dominant category from a list of tasting notes
     */
    public String getDominantCategory(List<String> tastingNotes) {
        if (tastingNotes == null || tastingNotes.isEmpty()) {
            return "other";
        }

        Map<String, Long> categoryCounts = tastingNotes.stream()
                .map(this::getCategoryForNote)
                .collect(Collectors.groupingBy(c -> c, Collectors.counting()));

        return categoryCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("other");
    }

    /**
     * Inner class to hold category + subcategory pair
     */
    public static class CategorySubcategory {
        public final String category;
        public final String subcategory;

        public CategorySubcategory(String category, String subcategory) {
            this.category = category;
            this.subcategory = subcategory;
        }

        @Override
        public String toString() {
            return category + " → " + subcategory;
        }
    }
}
