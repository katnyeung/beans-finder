package com.coffee.beansfinder.service;

import com.coffee.beansfinder.dto.SCAFlavorMapping;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for mapping tasting notes to SCA Coffee Taster's Flavor Wheel categories
 * Based on the official SCA flavor wheel taxonomy
 */
@Service
@Slf4j
public class SCAFlavorWheelService {

    // SCA Flavor Wheel Mapping - Primary Categories and Keywords
    private static final Map<String, List<String>> FLAVOR_KEYWORDS = new HashMap<>();

    static {
        // Fruity category
        FLAVOR_KEYWORDS.put("fruity", Arrays.asList(
                "berry", "berries", "strawberry", "blueberry", "raspberry", "blackberry",
                "cherry", "stone fruit", "peach", "apricot", "plum", "nectarine",
                "citrus", "lemon", "lime", "orange", "grapefruit", "tangerine",
                "tropical", "pineapple", "mango", "papaya", "passion fruit", "guava",
                "apple", "pear", "nashi", "grape", "pomegranate", "dried fruit",
                "raisin", "prune", "date", "fig", "currant", "cranberry"
        ));

        // Floral category
        FLAVOR_KEYWORDS.put("floral", Arrays.asList(
                "floral", "jasmine", "rose", "chamomile", "lavender", "hibiscus",
                "elderflower", "orange blossom", "perfume", "fragrant", "tea rose",
                "bergamot", "delicate", "aromatic"
        ));

        // Sweet category
        FLAVOR_KEYWORDS.put("sweet", Arrays.asList(
                "honey", "caramel", "maple", "molasses", "brown sugar", "vanilla",
                "sweet", "butterscotch", "toffee", "syrup", "nectar", "candy",
                "sugar", "milk chocolate", "white chocolate"
        ));

        // Nutty category
        FLAVOR_KEYWORDS.put("nutty", Arrays.asList(
                "almond", "hazelnut", "peanut", "walnut", "pecan", "cashew",
                "nutty", "malt", "grain", "toast", "cereal"
        ));

        // Spices category
        FLAVOR_KEYWORDS.put("spices", Arrays.asList(
                "cinnamon", "clove", "nutmeg", "anise", "cardamom", "pepper",
                "ginger", "spice", "spicy", "pungent", "warming"
        ));

        // Roasted category
        FLAVOR_KEYWORDS.put("roasted", Arrays.asList(
                "dark chocolate", "cocoa", "chocolate", "bitter chocolate",
                "roasted", "smoky", "tobacco", "pipe tobacco", "burnt", "ashy",
                "carbon", "acrid"
        ));

        // Green/Vegetative category
        FLAVOR_KEYWORDS.put("green", Arrays.asList(
                "green", "vegetative", "herbal", "grass", "hay", "fresh",
                "green beans", "cucumber", "bell pepper", "tomato", "olive"
        ));

        // Sour/Fermented category
        FLAVOR_KEYWORDS.put("sour", Arrays.asList(
                "sour", "acetic", "vinegar", "fermented", "winey", "wine",
                "whiskey", "alcoholic", "tart", "tangy", "acidic", "yogurt"
        ));

        // Other/Special
        FLAVOR_KEYWORDS.put("other", Arrays.asList(
                "tea", "oolong", "black tea", "green tea", "herbal tea",
                "mineral", "earthy", "soil", "mushroom", "woody", "cedar",
                "creamy", "buttery", "silky", "smooth", "round", "balanced",
                "complex", "clean", "bright", "crisp", "clarity", "cherimoya"
        ));
    }

    /**
     * Map a list of tasting notes to SCA flavor categories
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

        // Normalize and process each note
        for (String note : tastingNotes) {
            String normalizedNote = note.toLowerCase().trim();
            boolean matched = false;

            // Check each category
            for (Map.Entry<String, List<String>> entry : FLAVOR_KEYWORDS.entrySet()) {
                String category = entry.getKey();
                List<String> keywords = entry.getValue();

                // Check if note matches any keyword in this category
                for (String keyword : keywords) {
                    if (normalizedNote.contains(keyword)) {
                        addToCategory(mapping, category, note);
                        matched = true;
                        break;
                    }
                }

                if (matched) break;
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
     * Map a single tasting note to its SCA category
     */
    public String getCategoryForNote(String tastingNote) {
        if (tastingNote == null || tastingNote.trim().isEmpty()) {
            return "other";
        }

        String normalized = tastingNote.toLowerCase().trim();

        for (Map.Entry<String, List<String>> entry : FLAVOR_KEYWORDS.entrySet()) {
            String category = entry.getKey();
            List<String> keywords = entry.getValue();

            for (String keyword : keywords) {
                if (normalized.contains(keyword)) {
                    return category;
                }
            }
        }

        return "other";
    }

    /**
     * Get all keywords for a specific SCA category
     */
    public List<String> getKeywordsForCategory(String category) {
        return FLAVOR_KEYWORDS.getOrDefault(category.toLowerCase(), new ArrayList<>());
    }

    /**
     * Get all SCA categories
     */
    public Set<String> getAllCategories() {
        return FLAVOR_KEYWORDS.keySet();
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
}
