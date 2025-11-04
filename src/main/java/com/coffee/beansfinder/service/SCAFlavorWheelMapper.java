package com.coffee.beansfinder.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Maps coffee tasting notes to SCA Coffee Taster's Flavor Wheel categories
 */
@Service
@Slf4j
public class SCAFlavorWheelMapper {

    // SCA Flavor Wheel mapping - based on the official SCA wheel
    private static final Map<String, List<String>> FLAVOR_MAPPINGS = new HashMap<>();

    static {
        // Fruity
        FLAVOR_MAPPINGS.put("fruity_berry", Arrays.asList(
            "blackberry", "raspberry", "blueberry", "strawberry", "boysenberry", "cranberry"
        ));
        FLAVOR_MAPPINGS.put("fruity_dried_fruit", Arrays.asList(
            "raisin", "prune", "date", "fig"
        ));
        FLAVOR_MAPPINGS.put("fruity_other", Arrays.asList(
            "coconut", "cherry", "pomegranate", "pineapple", "grape", "apple", "peach", "pear",
            "nashi pear", "stone fruit", "citrus", "lemon", "lime", "orange", "grapefruit"
        ));

        // Sour/Fermented
        FLAVOR_MAPPINGS.put("sour_fermented", Arrays.asList(
            "winey", "whiskey", "fermented", "overripe", "vinegar", "酸"
        ));
        FLAVOR_MAPPINGS.put("sour_sour", Arrays.asList(
            "sour", "acetic acid", "butyric acid", "isovaleric acid", "citric acid", "malic acid"
        ));

        // Green/Vegetative
        FLAVOR_MAPPINGS.put("green_olive_oil", Arrays.asList(
            "olive oil", "raw"
        ));
        FLAVOR_MAPPINGS.put("green_beany", Arrays.asList(
            "green", "vegetative", "hay-like", "herb-like", "beany"
        ));

        // Other (includes tea-like, papery, etc.)
        FLAVOR_MAPPINGS.put("other_papery", Arrays.asList(
            "stale", "cardboard", "papery", "woody", "moldy", "musty"
        ));
        FLAVOR_MAPPINGS.put("other_chemical", Arrays.asList(
            "bitter", "salty", "medicinal", "petroleum", "skunky", "rubber"
        ));

        // Roasted
        FLAVOR_MAPPINGS.put("roasted_pipe_tobacco", Arrays.asList(
            "pipe tobacco", "tobacco", "burnt", "smoky"
        ));
        FLAVOR_MAPPINGS.put("roasted_cereal", Arrays.asList(
            "grain", "malt", "toast"
        ));

        // Spices
        FLAVOR_MAPPINGS.put("spices_pungent", Arrays.asList(
            "pepper", "brown spice", "anise"
        ));
        FLAVOR_MAPPINGS.put("spices_brown_spice", Arrays.asList(
            "nutmeg", "cinnamon", "clove", "cardamom", "ginger"
        ));

        // Nutty/Cocoa
        FLAVOR_MAPPINGS.put("nutty_cocoa", Arrays.asList(
            "chocolate", "dark chocolate", "cocoa"
        ));
        FLAVOR_MAPPINGS.put("nutty_nutty", Arrays.asList(
            "peanuts", "hazelnut", "almond", "walnut", "nut"
        ));

        // Sweet
        FLAVOR_MAPPINGS.put("sweet_brown_sugar", Arrays.asList(
            "molasses", "maple syrup", "caramelized", "honey", "brown sugar", "sugar"
        ));
        FLAVOR_MAPPINGS.put("sweet_vanilla", Arrays.asList(
            "vanilla", "vanillin", "sweet", "creamy"
        ));
        FLAVOR_MAPPINGS.put("sweet_overall_sweet", Arrays.asList(
            "sweet aromatics"
        ));

        // Floral
        FLAVOR_MAPPINGS.put("floral_black_tea", Arrays.asList(
            "oolong", "black tea", "tea", "tea-like", "jasmine", "chamomile"
        ));
        FLAVOR_MAPPINGS.put("floral_floral", Arrays.asList(
            "floral", "rose", "lavender", "hibiscus", "elderflower", "delicate"
        ));
    }

    /**
     * Maps a single tasting note to SCA categories
     */
    public Map<String, String> mapTastingNote(String note) {
        if (note == null || note.trim().isEmpty()) {
            return Collections.emptyMap();
        }

        String normalizedNote = note.toLowerCase().trim();

        for (Map.Entry<String, List<String>> entry : FLAVOR_MAPPINGS.entrySet()) {
            for (String keyword : entry.getValue()) {
                if (normalizedNote.contains(keyword.toLowerCase())) {
                    String[] parts = entry.getKey().split("_", 2);
                    Map<String, String> result = new HashMap<>();
                    result.put("category", parts[0]);
                    result.put("subcategory", parts.length > 1 ? parts[1] : "");
                    result.put("specific", note);
                    return result;
                }
            }
        }

        // Default to "other" if no match found
        Map<String, String> result = new HashMap<>();
        result.put("category", "other");
        result.put("subcategory", "unclassified");
        result.put("specific", note);
        log.debug("Could not classify tasting note: {}", note);
        return result;
    }

    /**
     * Maps a list of tasting notes to organized SCA structure
     */
    public Map<String, Object> mapTastingNotes(List<String> notes) {
        Map<String, Set<String>> categoryMap = new HashMap<>();

        for (String note : notes) {
            Map<String, String> mapping = mapTastingNote(note);
            String category = mapping.get("category");

            if (category != null) {
                categoryMap.computeIfAbsent(category, k -> new HashSet<>()).add(note);
            }
        }

        // Convert sets to lists for JSON serialization
        Map<String, Object> result = new HashMap<>();
        categoryMap.forEach((key, value) -> result.put(key, new ArrayList<>(value)));

        return result;
    }

    /**
     * Get the primary SCA category for a flavor note
     */
    public String getPrimaryCategory(String note) {
        Map<String, String> mapping = mapTastingNote(note);
        return mapping.getOrDefault("category", "other");
    }

    /**
     * Get all SCA categories
     */
    public Set<String> getAllCategories() {
        return Set.of("fruity", "sour", "green", "other", "roasted", "spices", "nutty", "sweet", "floral");
    }
}
