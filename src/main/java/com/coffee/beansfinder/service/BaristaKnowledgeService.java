package com.coffee.beansfinder.service;

import com.coffee.beansfinder.dto.BaristaInsight;
import com.coffee.beansfinder.entity.CoffeeProduct;
import com.coffee.beansfinder.graph.node.ProductNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Server-side barista knowledge base.
 * Generates coffee insights at runtime based on origin, process, variety, and character axes.
 * No LLM needed - pure rule-based domain expertise.
 */
@Service
@Slf4j
public class BaristaKnowledgeService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==========================================
    // ORIGIN PATTERNS
    // ==========================================

    private static final Map<String, Map<String, OriginPattern>> ORIGIN_PATTERNS = new HashMap<>();

    static {
        // === AFRICA ===
        Map<String, OriginPattern> ethiopia = new HashMap<>();
        ethiopia.put("washed", new OriginPattern(
                "Classic washed Ethiopian",
                "tea-like clarity, floral and citrus",
                "Delicate, bright, almost like drinking flavored tea",
                "CROWD_PLEASER"
        ));
        ethiopia.put("natural", new OriginPattern(
                "Ethiopian \"blueberry bomb\"",
                "heavy fruit, jammy, wine-like ferment",
                "Intense berry sweetness, some funky ferment notes",
                "LOVE_OR_HATE"
        ));
        ethiopia.put("honey", new OriginPattern(
                "Honey-processed Ethiopian",
                "fruit-forward with balanced sweetness",
                "Sweeter than washed, cleaner than natural",
                "GENERALLY_LIKED"
        ));
        ethiopia.put("anaerobic", new OriginPattern(
                "Experimental Ethiopian",
                "intense fruit, candy-like, possibly boozy",
                "Wild, unexpected flavors from controlled fermentation",
                "ADVENTUROUS_ONLY"
        ));
        ethiopia.put("default", new OriginPattern(
                "Ethiopian origin",
                "bright, fruity, complex - the birthplace of coffee",
                "Expect complexity and fruit notes",
                "GENERALLY_LIKED"
        ));
        ORIGIN_PATTERNS.put("ethiopia", ethiopia);

        Map<String, OriginPattern> kenya = new HashMap<>();
        kenya.put("default", new OriginPattern(
                "Kenyan brightness",
                "intense acidity, blackcurrant, tomato-like savory notes",
                "Bold, punchy, unmistakably \"Kenyan\" character",
                "LOVE_OR_HATE"
        ));
        ORIGIN_PATTERNS.put("kenya", kenya);

        Map<String, OriginPattern> rwanda = new HashMap<>();
        rwanda.put("washed", new OriginPattern(
                "Clean Rwandan",
                "bright, citrus, tea-like clarity",
                "Elegant and refined, similar to washed Ethiopian",
                "CROWD_PLEASER"
        ));
        rwanda.put("natural", new OriginPattern(
                "Rwandan natural",
                "fruity sweetness with clean finish",
                "Fruit-forward but more restrained than Ethiopian naturals",
                "GENERALLY_LIKED"
        ));
        rwanda.put("default", new OriginPattern(
                "Rwandan origin",
                "clean, bright, often citrus-forward",
                "African elegance without intensity",
                "GENERALLY_LIKED"
        ));
        ORIGIN_PATTERNS.put("rwanda", rwanda);

        Map<String, OriginPattern> burundi = new HashMap<>();
        burundi.put("default", new OriginPattern(
                "Burundian",
                "bright, complex, berry and citrus notes",
                "Similar to Rwandan - clean, nuanced, elegant",
                "GENERALLY_LIKED"
        ));
        ORIGIN_PATTERNS.put("burundi", burundi);

        // === CENTRAL & SOUTH AMERICA ===
        Map<String, OriginPattern> colombia = new HashMap<>();
        colombia.put("washed", new OriginPattern(
                "Textbook Colombian",
                "balanced, chocolate-caramel, approachable",
                "No surprises - the \"safe\" specialty coffee",
                "CROWD_PLEASER"
        ));
        colombia.put("natural", new OriginPattern(
                "Colombian natural",
                "fruit-forward twist on classic Colombian",
                "More fruit than traditional, still balanced",
                "GENERALLY_LIKED"
        ));
        colombia.put("honey", new OriginPattern(
                "Honey-processed Colombian",
                "sweet, fruity, balanced body",
                "Sweetness enhanced, maintains Colombian character",
                "CROWD_PLEASER"
        ));
        colombia.put("anaerobic", new OriginPattern(
                "Experimental Colombian",
                "unusual fruit esters, candy-like, possibly boozy",
                "Won't taste like \"normal\" coffee - wild and funky",
                "ADVENTUROUS_ONLY"
        ));
        colombia.put("default", new OriginPattern(
                "Colombian origin",
                "balanced, nutty, easy drinking",
                "The reliable, crowd-pleasing choice",
                "CROWD_PLEASER"
        ));
        ORIGIN_PATTERNS.put("colombia", colombia);

        Map<String, OriginPattern> brazil = new HashMap<>();
        brazil.put("natural", new OriginPattern(
                "Brazilian natural",
                "nutty, chocolatey, heavy body, low acidity",
                "Smooth, easy drinking, espresso-friendly",
                "CROWD_PLEASER"
        ));
        brazil.put("default", new OriginPattern(
                "Brazilian classic",
                "nutty, chocolatey, low acidity, smooth",
                "The comfortable, familiar coffee experience",
                "CROWD_PLEASER"
        ));
        ORIGIN_PATTERNS.put("brazil", brazil);

        Map<String, OriginPattern> guatemala = new HashMap<>();
        guatemala.put("washed", new OriginPattern(
                "Guatemalan washed",
                "chocolate, spice, balanced acidity",
                "Rich and complex with subtle smoke or spice notes",
                "CROWD_PLEASER"
        ));
        guatemala.put("default", new OriginPattern(
                "Guatemalan origin",
                "chocolate, spice, often with subtle smoke",
                "Central American complexity",
                "GENERALLY_LIKED"
        ));
        ORIGIN_PATTERNS.put("guatemala", guatemala);

        Map<String, OriginPattern> costarica = new HashMap<>();
        costarica.put("honey", new OriginPattern(
                "Costa Rican honey",
                "bright, sweet, clean fruit notes",
                "Known for exceptional honey processing",
                "CROWD_PLEASER"
        ));
        costarica.put("washed", new OriginPattern(
                "Costa Rican washed",
                "bright, clean, citrus and honey sweetness",
                "Precise, refined, technically excellent",
                "CROWD_PLEASER"
        ));
        costarica.put("anaerobic", new OriginPattern(
                "Experimental Costa Rican",
                "intense fruit, innovative processing",
                "Costa Rica leads in experimental processing",
                "ADVENTUROUS_ONLY"
        ));
        costarica.put("default", new OriginPattern(
                "Costa Rican origin",
                "bright, clean, often honey-sweet",
                "Central American precision",
                "CROWD_PLEASER"
        ));
        ORIGIN_PATTERNS.put("costa rica", costarica);

        Map<String, OriginPattern> peru = new HashMap<>();
        peru.put("default", new OriginPattern(
                "Peruvian origin",
                "mild, balanced, nutty-sweet",
                "Gentle, approachable, good value",
                "CROWD_PLEASER"
        ));
        ORIGIN_PATTERNS.put("peru", peru);

        Map<String, OriginPattern> honduras = new HashMap<>();
        honduras.put("default", new OriginPattern(
                "Honduran origin",
                "sweet, fruity, good value specialty",
                "Underrated origin - often excellent quality for price",
                "CROWD_PLEASER"
        ));
        ORIGIN_PATTERNS.put("honduras", honduras);

        Map<String, OriginPattern> elsalvador = new HashMap<>();
        elsalvador.put("default", new OriginPattern(
                "El Salvador origin",
                "balanced, honey-sweet, stone fruit",
                "Often from bourbon variety - sweet and refined",
                "GENERALLY_LIKED"
        ));
        ORIGIN_PATTERNS.put("el salvador", elsalvador);

        Map<String, OriginPattern> nicaragua = new HashMap<>();
        nicaragua.put("default", new OriginPattern(
                "Nicaraguan origin",
                "balanced, fruity, chocolate undertones",
                "Reliable Central American character",
                "CROWD_PLEASER"
        ));
        ORIGIN_PATTERNS.put("nicaragua", nicaragua);

        Map<String, OriginPattern> mexico = new HashMap<>();
        mexico.put("default", new OriginPattern(
                "Mexican origin",
                "mild, nutty, chocolate notes",
                "Gentle, easy drinking, often organic",
                "CROWD_PLEASER"
        ));
        ORIGIN_PATTERNS.put("mexico", mexico);

        // === ASIA & OCEANIA ===
        Map<String, OriginPattern> indonesia = new HashMap<>();
        indonesia.put("wet hulled", new OriginPattern(
                "Sumatran wet-hulled",
                "earthy, herbal, heavy body, low acidity",
                "The classic \"Sumatran\" taste - love it or hate it",
                "LOVE_OR_HATE"
        ));
        indonesia.put("washed", new OriginPattern(
                "Washed Indonesian",
                "cleaner than traditional, still earthy undertones",
                "Unusual for Indonesia - more approachable",
                "GENERALLY_LIKED"
        ));
        indonesia.put("default", new OriginPattern(
                "Indonesian origin",
                "earthy, herbal, full body",
                "Bold, rustic character unlike anywhere else",
                "LOVE_OR_HATE"
        ));
        ORIGIN_PATTERNS.put("indonesia", indonesia);
        ORIGIN_PATTERNS.put("sumatra", indonesia);

        Map<String, OriginPattern> india = new HashMap<>();
        india.put("monsooned", new OriginPattern(
                "Monsooned Malabar",
                "muted acidity, spicy, musty, heavy body",
                "Unique processing - exposed to monsoon winds",
                "LOVE_OR_HATE"
        ));
        india.put("default", new OriginPattern(
                "Indian origin",
                "spicy, earthy, full body",
                "Bold, distinctive character",
                "GENERALLY_LIKED"
        ));
        ORIGIN_PATTERNS.put("india", india);

        Map<String, OriginPattern> yemen = new HashMap<>();
        yemen.put("default", new OriginPattern(
                "Yemeni heritage",
                "wild, wine-like, dried fruit, complex",
                "Ancient coffee tradition - rare and precious",
                "LOVE_OR_HATE",
                "very_rare"
        ));
        ORIGIN_PATTERNS.put("yemen", yemen);

        Map<String, OriginPattern> panama = new HashMap<>();
        panama.put("geisha", new OriginPattern(
                "Panama Geisha",
                "jasmine, bergamot, tea-like, exceptional",
                "The pinnacle of specialty coffee - delicate and floral",
                "GENERALLY_LIKED",
                "rare"
        ));
        panama.put("default", new OriginPattern(
                "Panamanian origin",
                "clean, bright, often exceptional quality",
                "Home of Geisha variety",
                "GENERALLY_LIKED"
        ));
        ORIGIN_PATTERNS.put("panama", panama);
    }

    // ==========================================
    // PROCESS INSIGHTS
    // ==========================================

    private static final Map<String, ProcessInsight> PROCESS_INSIGHTS = new HashMap<>();

    static {
        PROCESS_INSIGHTS.put("washed", new ProcessInsight(
                "clean, origin-forward character",
                "Fruit removed before drying - pure bean flavor",
                "lighter"
        ));
        PROCESS_INSIGHTS.put("natural", new ProcessInsight(
                "fruit-forward, heavy body, fermented notes",
                "Dried inside the cherry - fruit sugars infuse the bean",
                "heavier"
        ));
        PROCESS_INSIGHTS.put("honey", new ProcessInsight(
                "sweet, balanced, some fruit",
                "Partial fruit left during drying - middle ground",
                "medium"
        ));
        PROCESS_INSIGHTS.put("black honey", new ProcessInsight(
                "intense sweetness, fruit-forward",
                "Maximum fruit mucilage retained - almost like natural",
                "heavier"
        ));
        PROCESS_INSIGHTS.put("red honey", new ProcessInsight(
                "sweet, fruity, balanced",
                "Moderate mucilage retained",
                "medium"
        ));
        PROCESS_INSIGHTS.put("yellow honey", new ProcessInsight(
                "clean with subtle sweetness",
                "Minimal mucilage retained - closer to washed",
                "lighter"
        ));
        PROCESS_INSIGHTS.put("anaerobic", new ProcessInsight(
                "unusual fruit esters, candy-like, possibly boozy",
                "Oxygen-free fermentation creates unique flavors",
                "heavier"
        ));
        PROCESS_INSIGHTS.put("carbonic maceration", new ProcessInsight(
                "wine-like, berry, bright acidity",
                "Whole cherry CO2 fermentation - very wine-like",
                "medium"
        ));
        PROCESS_INSIGHTS.put("wet hulled", new ProcessInsight(
                "earthy, herbal, heavy body",
                "Traditional Indonesian method - unique character",
                "heavier"
        ));
        PROCESS_INSIGHTS.put("lactic", new ProcessInsight(
                "creamy, yogurt-like, tangy sweetness",
                "Lactic acid fermentation - dairy-like notes",
                "heavier"
        ));
    }

    // ==========================================
    // VARIETY INSIGHTS
    // ==========================================

    private static final Map<String, VarietyInsight> VARIETY_INSIGHTS = new HashMap<>();

    static {
        VARIETY_INSIGHTS.put("geisha", new VarietyInsight(
                "floral, tea-like, jasmine, bergamot",
                "The most prized variety - delicate and complex"
        ));
        VARIETY_INSIGHTS.put("bourbon", new VarietyInsight(
                "sweet, balanced, caramel",
                "Classic variety - refined sweetness"
        ));
        VARIETY_INSIGHTS.put("typica", new VarietyInsight(
                "clean, sweet, balanced",
                "The original arabica - elegant simplicity"
        ));
        VARIETY_INSIGHTS.put("sl28", new VarietyInsight(
                "bright, complex, blackcurrant",
                "Kenyan classic - intense and fruity"
        ));
        VARIETY_INSIGHTS.put("sl34", new VarietyInsight(
                "balanced, citrus, complex",
                "Kenyan variety - bright and refined"
        ));
        VARIETY_INSIGHTS.put("caturra", new VarietyInsight(
                "bright, citrus, clean",
                "Bourbon mutation - lively and crisp"
        ));
        VARIETY_INSIGHTS.put("pacamara", new VarietyInsight(
                "complex, floral, fruity, full body",
                "Large bean - bold and interesting"
        ));
        VARIETY_INSIGHTS.put("heirloom", new VarietyInsight(
                "complex, varied, often wild",
                "Ethiopian landraces - unique genetic diversity"
        ));
        VARIETY_INSIGHTS.put("pink bourbon", new VarietyInsight(
                "floral, sweet, complex",
                "Rare bourbon mutation - exceptional sweetness"
        ));
    }

    // ==========================================
    // BODY & ACIDITY DESCRIPTIONS
    // ==========================================

    private static final Map<String, String> BODY_DESCRIPTIONS = Map.of(
            "very_light", "Delicate, tea-like - almost weightless on the tongue",
            "light", "Light and clean - refreshing, easy drinking",
            "medium_light", "Smooth with gentle presence",
            "medium", "Balanced mouthfeel - neither light nor heavy",
            "medium_heavy", "Rich and satisfying - noticeable weight",
            "heavy", "Creamy, syrupy - coats the tongue",
            "very_heavy", "Dense, velvety - thick and luxurious"
    );

    private static final Map<String, String> ACIDITY_DESCRIPTIONS = Map.of(
            "very_low", "Muted, smooth - no sharp edges",
            "low", "Gentle, mellow - soft and approachable",
            "medium_low", "Subtle brightness - there but not dominant",
            "medium", "Balanced acidity - present but integrated",
            "medium_high", "Bright and lively - noticeable zing",
            "high", "Sparkling, vibrant - the star of the show",
            "very_high", "Intense, electric - bold and punchy"
    );

    private static final Map<String, String> POLARIZATION_TEXT = Map.of(
            "CROWD_PLEASER", "Crowd pleaser - safe for gifts, new drinkers, everyone",
            "GENERALLY_LIKED", "Easy to like, minimal surprises",
            "LOVE_OR_HATE", "Polarizing - people either love or hate this style",
            "ADVENTUROUS_ONLY", "For adventurous palates. Not your everyday coffee."
    );

    private static final Map<String, String> RARITY_TEXT = Map.of(
            "uncommon", "Less common origin - worth exploring",
            "rare", "Rare find - not often seen in the UK",
            "very_rare", "Very rare - exceptional opportunity"
    );

    // ==========================================
    // PUBLIC METHODS
    // ==========================================

    /**
     * Generate barista insight for a CoffeeProduct entity
     */
    public BaristaInsight generateInsight(CoffeeProduct product) {
        String origin = normalize(product.getOrigin());
        String process = normalize(product.getProcess());
        String variety = normalize(product.getVariety());

        List<Double> characterAxes = parseCharacterAxes(product.getCharacterAxesJson());

        return buildInsight(origin, process, variety, characterAxes);
    }

    /**
     * Generate barista insight for a ProductNode (Neo4j)
     */
    public BaristaInsight generateInsight(ProductNode product) {
        String origin = "";
        if (product.getOrigins() != null && !product.getOrigins().isEmpty()) {
            origin = normalize(product.getOrigins().iterator().next().getCountry());
        }

        String process = "";
        if (product.getProcesses() != null && !product.getProcesses().isEmpty()) {
            process = normalize(product.getProcesses().iterator().next().getType());
        }

        String variety = "";
        if (product.getVarieties() != null && !product.getVarieties().isEmpty()) {
            variety = normalize(product.getVarieties().iterator().next().getName());
        }

        List<Double> characterAxes = product.getCharacterAxes() != null
                ? product.getCharacterAxes()
                : List.of(0.0, 0.0, 0.0, 0.0);

        return buildInsight(origin, process, variety, characterAxes);
    }

    /**
     * Generate context string for Grok prompt injection
     */
    public String generateContextForPrompt(ProductNode product) {
        if (product == null) {
            return "";
        }

        BaristaInsight insight = generateInsight(product);
        StringBuilder sb = new StringBuilder();

        sb.append("=== BARISTA KNOWLEDGE ===\n");

        if (insight.getPattern() != null) {
            sb.append("Pattern: ").append(insight.getPattern()).append("\n");
        }
        if (insight.getCharacter() != null) {
            sb.append("Character: ").append(insight.getCharacter()).append("\n");
        }
        if (insight.getExpect() != null) {
            sb.append("Expect: ").append(insight.getExpect()).append("\n");
        }
        if (insight.getProcessWhy() != null) {
            sb.append("Process insight: ").append(insight.getProcessWhy()).append("\n");
        }
        if (insight.getAudience() != null) {
            sb.append("Audience: ").append(insight.getAudience()).append("\n");
        }
        if (insight.getRarity() != null) {
            sb.append("Rarity: ").append(insight.getRarity()).append("\n");
        }

        return sb.toString();
    }

    /**
     * Get origin expertise for EXPLAIN_ORIGIN intent
     */
    public String getOriginExpertise(String origin) {
        String normalized = normalize(origin);
        Map<String, OriginPattern> patterns = findOriginPatterns(normalized);

        if (patterns == null || patterns.isEmpty()) {
            return "I don't have detailed knowledge about " + origin + " coffee yet.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("About ").append(formatOriginName(origin)).append(" coffee:\n\n");

        OriginPattern defaultPattern = patterns.get("default");
        if (defaultPattern != null) {
            sb.append(defaultPattern.character).append("\n\n");
        }

        // Add process variations
        for (Map.Entry<String, OriginPattern> entry : patterns.entrySet()) {
            if (!"default".equals(entry.getKey())) {
                sb.append("- ").append(capitalize(entry.getKey())).append(" processed: ");
                sb.append(entry.getValue().expect).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * Get process expertise for EXPLAIN_PROCESS intent
     */
    public String getProcessExpertise(String process) {
        String normalized = normalize(process);
        ProcessInsight insight = findProcessInsight(normalized);

        if (insight == null) {
            return "I don't have detailed knowledge about " + process + " processing yet.";
        }

        return String.format("%s processing:\n\n%s\n\nThis typically creates: %s\n\nBody tendency: %s",
                capitalize(process),
                insight.why,
                insight.effect,
                insight.bodyTendency);
    }

    /**
     * Compare two products
     */
    public String compareProducts(ProductNode p1, ProductNode p2) {
        BaristaInsight insight1 = generateInsight(p1);
        BaristaInsight insight2 = generateInsight(p2);

        StringBuilder sb = new StringBuilder();
        sb.append("Comparing:\n\n");

        sb.append("**").append(p1.getProductName()).append("**\n");
        sb.append("- ").append(insight1.toOneLiner()).append("\n");
        if (insight1.getAudience() != null) {
            sb.append("- ").append(insight1.getAudience()).append("\n");
        }
        sb.append("\n");

        sb.append("**").append(p2.getProductName()).append("**\n");
        sb.append("- ").append(insight2.toOneLiner()).append("\n");
        if (insight2.getAudience() != null) {
            sb.append("- ").append(insight2.getAudience()).append("\n");
        }

        return sb.toString();
    }

    /**
     * Get brewing guidance based on product characteristics
     */
    public String getBrewingGuidance(ProductNode product) {
        String roastLevel = product.getRoastLevel() != null
                ? product.getRoastLevel().getLevel()
                : "Medium";
        List<Double> axes = product.getCharacterAxes() != null
                ? product.getCharacterAxes()
                : List.of(0.0, 0.0, 0.0, 0.0);

        double acidity = axes.size() > 0 ? axes.get(0) : 0.0;
        double body = axes.size() > 1 ? axes.get(1) : 0.0;

        StringBuilder sb = new StringBuilder();
        sb.append("Brewing suggestions for this coffee:\n\n");

        if ("Light".equalsIgnoreCase(roastLevel) || acidity > 0.3) {
            sb.append("RECOMMENDED: Pour-over (V60, Chemex, Kalita)\n");
            sb.append("- Ratio: 1:16 (15g coffee : 240ml water)\n");
            sb.append("- Water: 92-94C (not boiling - let it cool 30 sec)\n");
            sb.append("- Grind: Medium-fine (like table salt)\n");
            sb.append("\nWHY: Pour-over keeps the delicate, bright notes. The paper filter removes oils, making it cleaner.\n");
        } else if ("Dark".equalsIgnoreCase(roastLevel) || body > 0.3) {
            sb.append("RECOMMENDED: Espresso, Moka Pot, French Press\n");
            sb.append("- For espresso: 18g in, 36g out, 25-30 seconds\n");
            sb.append("- Works great with milk for lattes/flat whites\n");
            sb.append("\nWHY: Dark roasts and full-bodied coffees shine in high-pressure brewing.\n");
        } else {
            sb.append("VERSATILE: This coffee works well with most brewing methods.\n");
            sb.append("- Filter/pour-over for clarity\n");
            sb.append("- Espresso for intensity\n");
            sb.append("- French Press for body\n");
        }

        return sb.toString();
    }

    // ==========================================
    // PRIVATE HELPERS
    // ==========================================

    private BaristaInsight buildInsight(String origin, String process, String variety, List<Double> characterAxes) {
        // Find origin pattern
        Map<String, OriginPattern> originPatterns = findOriginPatterns(origin);
        OriginPattern pattern = null;

        if (originPatterns != null) {
            // Try to find process-specific pattern first
            pattern = findProcessPattern(originPatterns, process);
            if (pattern == null) {
                pattern = originPatterns.get("default");
            }
        }

        // Find process insight
        ProcessInsight processInsight = findProcessInsight(process);

        // Find variety insight
        VarietyInsight varietyInsight = findVarietyInsight(variety);

        // Calculate body/acidity descriptions from character axes
        double acidity = characterAxes.size() > 0 ? characterAxes.get(0) : 0.0;
        double body = characterAxes.size() > 1 ? characterAxes.get(1) : 0.0;

        String bodyFeel = getBodyDescription(body);
        String acidityFeel = getAcidityDescription(acidity);

        return BaristaInsight.builder()
                .pattern(pattern != null ? pattern.pattern : formatOriginName(origin) + " origin")
                .character(pattern != null ? pattern.character : null)
                .expect(pattern != null ? pattern.expect : null)
                .processEffect(processInsight != null ? processInsight.effect : null)
                .processWhy(processInsight != null ? processInsight.why : null)
                .varietyNote(varietyInsight != null ? varietyInsight.note : null)
                .bodyFeel(bodyFeel)
                .acidityFeel(acidityFeel)
                .polarization(pattern != null ? pattern.polarization : "GENERALLY_LIKED")
                .audience(pattern != null ? POLARIZATION_TEXT.get(pattern.polarization) : null)
                .rarity(pattern != null && pattern.rarity != null ? RARITY_TEXT.get(pattern.rarity) : null)
                .rare(pattern != null && pattern.rarity != null)
                .build();
    }

    private Map<String, OriginPattern> findOriginPatterns(String origin) {
        if (origin == null || origin.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, Map<String, OriginPattern>> entry : ORIGIN_PATTERNS.entrySet()) {
            if (origin.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private OriginPattern findProcessPattern(Map<String, OriginPattern> patterns, String process) {
        if (process == null || process.isEmpty()) {
            return null;
        }
        for (String key : patterns.keySet()) {
            if (!"default".equals(key) && process.contains(key)) {
                return patterns.get(key);
            }
        }
        return null;
    }

    private ProcessInsight findProcessInsight(String process) {
        if (process == null || process.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, ProcessInsight> entry : PROCESS_INSIGHTS.entrySet()) {
            if (process.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private VarietyInsight findVarietyInsight(String variety) {
        if (variety == null || variety.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, VarietyInsight> entry : VARIETY_INSIGHTS.entrySet()) {
            if (variety.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String getBodyDescription(double body) {
        if (body <= -0.6) return BODY_DESCRIPTIONS.get("very_light");
        if (body <= -0.3) return BODY_DESCRIPTIONS.get("light");
        if (body <= -0.1) return BODY_DESCRIPTIONS.get("medium_light");
        if (body <= 0.1) return BODY_DESCRIPTIONS.get("medium");
        if (body <= 0.3) return BODY_DESCRIPTIONS.get("medium_heavy");
        if (body <= 0.6) return BODY_DESCRIPTIONS.get("heavy");
        return BODY_DESCRIPTIONS.get("very_heavy");
    }

    private String getAcidityDescription(double acidity) {
        if (acidity <= -0.6) return ACIDITY_DESCRIPTIONS.get("very_low");
        if (acidity <= -0.3) return ACIDITY_DESCRIPTIONS.get("low");
        if (acidity <= -0.1) return ACIDITY_DESCRIPTIONS.get("medium_low");
        if (acidity <= 0.1) return ACIDITY_DESCRIPTIONS.get("medium");
        if (acidity <= 0.3) return ACIDITY_DESCRIPTIONS.get("medium_high");
        if (acidity <= 0.6) return ACIDITY_DESCRIPTIONS.get("high");
        return ACIDITY_DESCRIPTIONS.get("very_high");
    }

    private String normalize(String input) {
        return input != null ? input.toLowerCase().trim() : "";
    }

    private String formatOriginName(String origin) {
        if (origin == null || origin.isEmpty()) return "Unknown";
        return Arrays.stream(origin.split(" "))
                .map(this::capitalize)
                .reduce((a, b) -> a + " " + b)
                .orElse(origin);
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }

    private List<Double> parseCharacterAxes(String json) {
        if (json == null || json.isEmpty()) {
            return List.of(0.0, 0.0, 0.0, 0.0);
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Double>>() {});
        } catch (Exception e) {
            return List.of(0.0, 0.0, 0.0, 0.0);
        }
    }

    // ==========================================
    // INNER CLASSES
    // ==========================================

    private static class OriginPattern {
        String pattern;
        String character;
        String expect;
        String polarization;
        String rarity;

        OriginPattern(String pattern, String character, String expect, String polarization) {
            this.pattern = pattern;
            this.character = character;
            this.expect = expect;
            this.polarization = polarization;
        }

        OriginPattern(String pattern, String character, String expect, String polarization, String rarity) {
            this(pattern, character, expect, polarization);
            this.rarity = rarity;
        }
    }

    private static class ProcessInsight {
        String effect;
        String why;
        String bodyTendency;

        ProcessInsight(String effect, String why, String bodyTendency) {
            this.effect = effect;
            this.why = why;
            this.bodyTendency = bodyTendency;
        }
    }

    private static class VarietyInsight {
        String character;
        String note;

        VarietyInsight(String character, String note) {
            this.character = character;
            this.note = note;
        }
    }
}
