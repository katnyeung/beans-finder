package com.coffee.beansfinder.service;

import com.coffee.beansfinder.dto.ExtractedProductData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * OpenAI GPT-4o-mini service for text extraction
 * 20x cheaper than Perplexity for extraction tasks
 * Cost: ~$0.15/$0.60 per 1M tokens (input/output)
 * Use case: Extract structured data from Playwright-rendered text
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OpenAIService {

    @Value("${openai.api.key:}")
    private String apiKey;

    @Value("${openai.api.url:https://api.openai.com/v1/chat/completions}")
    private String apiUrl;

    @Value("${openai.model:gpt-4o-mini}")
    private String model;

    @Value("${openai.temperature:0.1}")
    private double temperature;

    @Value("${openai.max.tokens:1500}")
    private int maxTokens;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Extract product data from text content using GPT-4o-mini
     * Cost: ~$0.0004 per product (20x cheaper than Perplexity)
     *
     * @param productText Clean product text from Playwright (10KB)
     * @param brandName Brand name
     * @param productUrl Product URL
     * @return Extracted product data
     */
    public ExtractedProductData extractFromText(
            String productText,
            String brandName,
            String productUrl) {

        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("OpenAI API key not configured");
            return null;
        }

        if (productText == null || productText.isEmpty()) {
            log.warn("Empty text provided for extraction");
            return null;
        }

        // Truncate if too large (GPT-4o-mini has 128K context, but we want to keep costs low)
        // 40KB ≈ 10,000 tokens for clean product text (no scripts/styles)
        // Cost impact: ~$0.0015 per product (still very cheap)
        // Increased from 20KB to capture all product details (origin, process, variety, etc.)
        String textSnippet = productText.length() > 40000
                ? productText.substring(0, 40000)
                : productText;

        log.info("Extracting from text ({} chars) using OpenAI: {}", textSnippet.length(), productUrl);

        try {
            String prompt = buildExtractionPrompt(textSnippet, brandName, productUrl);
            String response = callOpenAI(prompt);

            log.info("=== OPENAI RAW RESPONSE ===");
            log.info("Response length: {} chars", response.length());
            log.debug("Response: {}", response.length() > 500 ? response.substring(0, 500) + "..." : response);

            // Parse JSON response
            ExtractedProductData extracted = objectMapper.readValue(response, ExtractedProductData.class);

            // Set the product URL (OpenAI doesn't extract this, we provide it)
            extracted.setProductUrl(productUrl);

            log.info("=== EXTRACTION SUCCESS ===");
            log.info("Product: {}", extracted.getProductName());
            log.info("Origin: {}, Process: {}, Tasting Notes: {}",
                    extracted.getOrigin(),
                    extracted.getProcess(),
                    extracted.getTastingNotes() != null ? extracted.getTastingNotes().size() : 0);

            return extracted;

        } catch (Exception e) {
            log.error("=== OPENAI EXTRACTION ERROR ===");
            log.error("Failed to extract from text: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Build extraction prompt for OpenAI
     */
    private String buildExtractionPrompt(String productText, String brandName, String productUrl) {
        return String.format("""
                Extract coffee product data from this product page text.

                Brand: %s
                URL: %s

                Page Text:
                %s

                Extract and return a JSON object with these fields:
                {
                  "product_name": "Full product name (required)",
                  "origin": "Country/countries of origin - look for 'Country:', geographic names like Ethiopia, Colombia, Brazil, El Salvador, Kenya, etc. For blends with multiple origins, combine with ' / ' (e.g., 'Ethiopia / El Salvador') or null",
                  "region": "Farm/Region/Growing area (e.g., Sidama, Huehuetenango, Yirgacheffe) or null",
                  "process": "Processing method - look for: Natural, Washed, Honey, Anaerobic, Wet, Dry, Pulped Natural, Semi-Washed, etc. For multiple processes combine with '/' (e.g., 'Natural/Honey') or null",
                  "producer": "Farm name, producer name, or cooperative - look for 'Producer:', farmer names, estate names, or 'Various Smallholders'. Combine multiple with 'and' (e.g., 'José Arnulfo Montiel and Various Smallholders') or null",
                  "variety": "Coffee variety/cultivar - look for: Bourbon, Geisha, Caturra, Typica, Pacamara, Catuai, SL28, SL34, Heirloom, etc. Combine multiple with ', ' (e.g., 'Pacamara, Gibirinna 74110') or null",
                  "altitude": "Altitude/elevation (e.g., '1,800 MASL', '2000-2200m', '1600m') or null",
                  "tasting_notes": ["flavor1", "flavor2", "flavor3"] - Extract ALL flavors, descriptors, and cupping notes mentioned,
                  "price": 12.50 (number) - The SMALLEST/cheapest price option as decimal, remove currency symbols (e.g., "£10.00" → 10.00) or null,
                  "price_variants": [{"size": "250g", "price": 10.00}, {"size": "1kg", "price": 35.00}] - ALL available size/price combinations, or null if only one price,
                  "in_stock": true (default) or false ONLY if page explicitly says "out of stock", "sold out", "unavailable", or "currently unavailable",
                  "raw_description": "Main product description text - capture the coffee's story, tasting profile, and key details",

                  "flavor_profile": [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0],
                  "character_axes": [0.0, 0.0, 0.0, 0.0]
                }

                === FLAVOR PROFILE (9 dimensions, 0.0 to 1.0) ===
                A 9-dimensional intensity vector for flavor presence.

                INDEX MAPPING:
                [0] fruity   - berry, citrus, tropical, stone fruit, apple, grape
                [1] floral   - jasmine, rose, lavender, chamomile, hibiscus
                [2] sweet    - honey, caramel, chocolate, vanilla, maple, brown sugar
                [3] nutty    - almond, hazelnut, walnut, peanut, pecan
                [4] spices   - cinnamon, clove, pepper, cardamom, ginger
                [5] roasted  - dark chocolate, tobacco, smoky, burnt, bitter
                [6] green    - vegetative, herbal, grassy, tea-like, fresh
                [7] sour     - citric acid, bright acidity, tangy, fermented
                [8] other    - earthy, woody, leather, mushroom

                INTENSITY SCALE (use continuous values, not just 0.2/0.4/0.6/0.8):
                0.0 = not detected
                0.1-0.25 = subtle hint ("hints of", "slight", "delicate")
                0.3-0.45 = noticeable ("notes of", "with", "touch of")
                0.5-0.65 = prominent ("bright", "vibrant", "juicy")
                0.7-0.85 = defining ("pronounced", "bold", "rich")
                0.9-1.0 = dominant ("overwhelming", "intense", "explosive")

                IMPORTANT: Use the FULL continuous scale (e.g., 0.35, 0.55, 0.72) based on descriptive intensity. Do NOT round to 0.2/0.4/0.6/0.8.

                === CHARACTER AXES (4 dimensions, -1.0 to +1.0) ===
                A 4-dimensional bipolar vector for coffee character.

                INDEX MAPPING:
                [0] acidity:    -1.0=flat/smooth/mellow    to  +1.0=bright/tangy/citric
                [1] body:       -1.0=light/tea-like/thin   to  +1.0=heavy/syrupy/full
                [2] roast:      -1.0=light/origin-forward  to  +1.0=dark/roast-forward
                [3] complexity: -1.0=clean/simple          to  +1.0=complex/funky/winey

                INFERENCE RULES (when not explicitly stated):
                - Ethiopian/Kenyan + washed -> acidity +0.35 to +0.5
                - Brazilian/Indonesian -> acidity -0.15 to -0.3
                - Natural/honey process -> body +0.25 to +0.4, complexity +0.2 to +0.4
                - Washed process -> body -0.1 to -0.25, complexity -0.1 to -0.2
                - Dark roast -> acidity -0.2 to -0.4, body +0.15 to +0.3, roast +0.5 to +0.7
                - Light roast -> roast -0.5 to -0.7
                - Prominent fruit/floral -> roast -0.2 to -0.4
                - "funky", "winey", "fermented" -> complexity +0.5 to +0.7

                === COMPLETE EXAMPLES ===

                1. "Light roast Ethiopian Yirgacheffe. Bright citrus acidity, tea-like body. Blueberry, jasmine, lemon."
                   flavor_profile: [0.75, 0.55, 0.0, 0.0, 0.0, 0.0, 0.0, 0.45, 0.0]
                   character_axes: [0.65, -0.45, -0.55, 0.1]

                2. "Dark roast Brazilian. Full body, low acidity, chocolate, nutty, caramel."
                   flavor_profile: [0.0, 0.0, 0.52, 0.58, 0.0, 0.65, 0.0, 0.0, 0.0]
                   character_axes: [-0.55, 0.58, 0.68, -0.25]

                3. "Natural process Geisha. Explosive tropical fruit, syrupy, winey, funky ferment."
                   flavor_profile: [0.92, 0.35, 0.38, 0.0, 0.0, 0.0, 0.0, 0.42, 0.0]
                   character_axes: [0.28, 0.72, -0.38, 0.78]

                4. "Medium roast Colombia. Balanced, clean cup, chocolate, caramel, hint of citrus."
                   flavor_profile: [0.18, 0.0, 0.58, 0.0, 0.0, 0.42, 0.0, 0.0, 0.0]
                   character_axes: [0.12, 0.05, 0.0, -0.35]

                5. "Sumatra Mandheling. Earthy, cedar, herbal, full body, low acid."
                   flavor_profile: [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.38, 0.0, 0.72]
                   character_axes: [-0.62, 0.55, 0.32, 0.18]

                IMPORTANT extraction rules:
                - origin: Look for field labels like "Country:", or geographic names in product details
                - For blends, combine all origin countries (e.g., "Ethiopia / El Salvador" from "Ethiopia" and "El Salvador")
                - process: Look for "Process:", "Processing:", or method names (Natural/Washed/Honey)
                - producer: Look for "Producer:", farmer/farm names, or "Various Smallholders"
                - variety: Look for "Variety:", cultivar names (Bourbon, Geisha, Pacamara, etc.)
                - tasting_notes: Extract ALL flavor descriptors, not just the obvious ones
                - in_stock: DEFAULT TO TRUE. Only set to false if the product is CLEARLY unavailable for purchase. Ignore hidden HTML elements or template text like "Unavailable" in hidden divs. If you see "Add to Cart", "Buy Now", "Add to Bag", or price options visible, the product IS in stock (true). Only set false if you see prominent "SOLD OUT", "OUT OF STOCK", or disabled purchase buttons as the main call-to-action.
                - price: Extract the SMALLEST price (usually the smallest bag size like 200g or 250g)
                - price_variants: If multiple sizes exist (e.g., "200g £10 | 1kg £35"), extract ALL as array. Look for size selectors, dropdown options, or multiple price listings.
                - flavor_profile: Sum does NOT need to equal 1.0 (independent categories). Always output exactly 9 values.
                - character_axes: Use inference rules when descriptors are missing. Always output exactly 4 values.
                - Use null for truly missing fields, not empty strings
                - Return ONLY valid JSON, no markdown code blocks

                Return the JSON object:
                """, brandName, productUrl, productText);
    }

    /**
     * Call OpenAI API
     */
    public String callOpenAI(String prompt) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", "You are a coffee product data extraction assistant. Extract structured data from product pages and return valid JSON."),
                        Map.of("role", "user", "content", prompt)
                ),
                "temperature", temperature,
                "max_tokens", maxTokens,
                "response_format", Map.of("type", "json_object") // Force JSON output
        );

        log.debug("Calling OpenAI API: model={}, temperature={}, max_tokens={}", model, temperature, maxTokens);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(apiUrl, request, String.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            log.error("OpenAI API error: {}", response.getBody());
            throw new RuntimeException("OpenAI API returned error: " + response.getStatusCode());
        }

        // Extract content from response
        String responseBody = response.getBody();
        JsonNode root = objectMapper.readTree(responseBody);

        if (!root.has("choices") || root.get("choices").size() == 0) {
            throw new RuntimeException("No choices in OpenAI response");
        }

        JsonNode firstChoice = root.get("choices").get(0);
        if (!firstChoice.has("message") || !firstChoice.get("message").has("content")) {
            throw new RuntimeException("No content in OpenAI response");
        }

        String content = firstChoice.get("message").get("content").asText();

        log.debug("Extracted content from OpenAI response: {} chars", content.length());

        return content;
    }

    /**
     * Categorize coffee flavor notes into SCA categories using GPT-4o-mini
     * Processes flavors in batches for efficiency
     * Cost: ~$0.0001 per 10 flavors
     *
     * @param flavorNames List of flavor names to categorize
     * @return Map of flavor name -> SCA category
     */
    public Map<String, String> categorizeFlavors(List<String> flavorNames) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("OpenAI API key not configured");
        }

        String prompt = buildFlavorCategorizationPrompt(flavorNames);

        try {
            // Build request
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "messages", List.of(
                            Map.of("role", "system", "content", "You are a coffee flavor expert familiar with the SCA (Specialty Coffee Association) flavor wheel."),
                            Map.of("role", "user", "content", prompt)
                    ),
                    "temperature", 0.1,
                    "max_tokens", 500
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

            log.info("Calling OpenAI to categorize {} flavors", flavorNames.size());

            ResponseEntity<String> response = restTemplate.postForEntity(apiUrl, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("OpenAI API returned status: " + response.getStatusCode());
            }

            // Extract content from OpenAI response
            String responseBody = response.getBody();
            JsonNode root = objectMapper.readTree(responseBody);

            if (!root.has("choices") || root.get("choices").size() == 0) {
                throw new RuntimeException("No choices in OpenAI response");
            }

            JsonNode firstChoice = root.get("choices").get(0);
            if (!firstChoice.has("message") || !firstChoice.get("message").has("content")) {
                throw new RuntimeException("No content in OpenAI response");
            }

            String jsonResponse = firstChoice.get("message").get("content").asText();

            // Clean markdown code blocks if present (```json ... ```)
            jsonResponse = cleanMarkdownCodeBlocks(jsonResponse);

            // Parse JSON response
            return objectMapper.readValue(jsonResponse, Map.class);

        } catch (Exception e) {
            log.error("Failed to categorize flavors with OpenAI: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to categorize flavors: " + e.getMessage(), e);
        }
    }

    /**
     * Clean markdown code blocks from response
     * Handles cases like: ```json\n{...}\n``` or ```{...}```
     */
    private String cleanMarkdownCodeBlocks(String text) {
        if (text == null) return null;

        // Remove markdown code blocks: ```json ... ``` or ``` ... ```
        text = text.trim();

        if (text.startsWith("```")) {
            // Find the first newline after opening ```
            int firstNewline = text.indexOf('\n');
            if (firstNewline != -1) {
                text = text.substring(firstNewline + 1);
            } else {
                // No newline, just remove ```
                text = text.substring(3);
            }

            // Remove closing ```
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }

            text = text.trim();
        }

        return text;
    }

    /**
     * Build prompt for flavor categorization
     */
    private String buildFlavorCategorizationPrompt(List<String> flavorNames) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Categorize these coffee tasting notes into ONE of the SCA flavor wheel categories:\n\n");
        prompt.append("Categories:\n");
        prompt.append("- fruity (berry, citrus, stone fruit, tropical)\n");
        prompt.append("- floral (jasmine, rose, chamomile, lavender)\n");
        prompt.append("- sweet (honey, caramel, vanilla, chocolate, maple)\n");
        prompt.append("- nutty (almond, hazelnut, peanut, walnut)\n");
        prompt.append("- spices (cinnamon, clove, pepper, cardamom)\n");
        prompt.append("- roasted (dark chocolate, tobacco, smoky, burnt)\n");
        prompt.append("- green (vegetative, herbal, grass, fresh)\n");
        prompt.append("- sour (acetic, vinegar, fermented, citric)\n");
        prompt.append("- other (if none of the above fit)\n\n");

        prompt.append("Flavor notes to categorize:\n");
        for (String flavor : flavorNames) {
            prompt.append("- ").append(flavor).append("\n");
        }

        prompt.append("\nReturn ONLY a valid JSON object mapping each flavor to its category.\n");
        prompt.append("Format: {\"flavor1\": \"category\", \"flavor2\": \"category\", ...}\n");
        prompt.append("Example: {\"strawberry\": \"fruity\", \"jasmine\": \"floral\", \"caramel\": \"sweet\"}\n");

        return prompt.toString();
    }

    /**
     * Generate embedding for text using OpenAI text-embedding-3-small
     * Cost: ~$0.00002 per embedding (1,536 dimensions)
     * Use case: Semantic caching, similarity search
     *
     * @param text Text to embed (e.g., user query or product description)
     * @return 1,536-dimension embedding vector
     */
    public float[] embedText(String text) {
        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("OpenAI API key not configured");
            return null;
        }

        if (text == null || text.isEmpty()) {
            log.warn("Empty text provided for embedding");
            return null;
        }

        try {
            // Create request
            Map<String, Object> request = Map.of(
                "input", text,
                "model", "text-embedding-3-small" // 1,536 dimensions, $0.00002 per 1K tokens
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            // Call OpenAI embedding API
            ResponseEntity<String> response = restTemplate.postForEntity(
                    "https://api.openai.com/v1/embeddings",
                    entity,
                    String.class
            );

            // Parse response
            JsonNode rootNode = objectMapper.readTree(response.getBody());
            JsonNode embeddingNode = rootNode
                    .path("data")
                    .get(0)
                    .path("embedding");

            // Convert to float array
            float[] embedding = new float[embeddingNode.size()];
            for (int i = 0; i < embeddingNode.size(); i++) {
                embedding[i] = (float) embeddingNode.get(i).asDouble();
            }

            log.debug("Generated embedding for text: {} chars → {} dimensions",
                    text.length(), embedding.length);

            return embedding;

        } catch (Exception e) {
            log.error("Failed to generate embedding: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Map tasting notes to SCA Flavor Wheel attributes (Tier 3) using GPT-4o-mini.
     * Returns the best matching attribute for each tasting note.
     * Cost: ~$0.0002 per batch of 50 notes
     *
     * @param tastingNotes List of raw tasting notes (e.g., "bright citrus acidity", "blackberry jam")
     * @param availableAttributes List of valid attribute IDs from the hierarchy (e.g., "blackberry", "lemon", "caramel")
     * @return Map of tasting note -> attribute ID (or null if no good match)
     */
    public Map<String, String> mapTastingNotesToAttributes(List<String> tastingNotes, List<String> availableAttributes) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("OpenAI API key not configured");
        }

        if (tastingNotes == null || tastingNotes.isEmpty()) {
            return Map.of();
        }

        String prompt = buildAttributeMappingPrompt(tastingNotes, availableAttributes);

        try {
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "messages", List.of(
                            Map.of("role", "system", "content", "You are a coffee flavor expert. Match tasting notes to the closest SCA flavor wheel attribute."),
                            Map.of("role", "user", "content", prompt)
                    ),
                    "temperature", 0.1,
                    "max_tokens", 2000
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

            log.info("Calling OpenAI to map {} tasting notes to attributes", tastingNotes.size());

            ResponseEntity<String> response = restTemplate.postForEntity(apiUrl, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("OpenAI API returned status: " + response.getStatusCode());
            }

            String responseBody = response.getBody();
            JsonNode root = objectMapper.readTree(responseBody);

            if (!root.has("choices") || root.get("choices").size() == 0) {
                throw new RuntimeException("No choices in OpenAI response");
            }

            JsonNode firstChoice = root.get("choices").get(0);
            if (!firstChoice.has("message") || !firstChoice.get("message").has("content")) {
                throw new RuntimeException("No content in OpenAI response");
            }

            String jsonResponse = firstChoice.get("message").get("content").asText();
            jsonResponse = cleanMarkdownCodeBlocks(jsonResponse);

            return objectMapper.readValue(jsonResponse, Map.class);

        } catch (Exception e) {
            log.error("Failed to map tasting notes to attributes: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to map tasting notes: " + e.getMessage(), e);
        }
    }

    /**
     * Build prompt for attribute mapping
     */
    private String buildAttributeMappingPrompt(List<String> tastingNotes, List<String> availableAttributes) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Match each coffee tasting note to the CLOSEST attribute from the SCA Flavor Wheel.\n\n");

        prompt.append("Available attributes (choose ONLY from this list):\n");
        for (String attr : availableAttributes) {
            prompt.append("- ").append(attr).append("\n");
        }

        prompt.append("\nTasting notes to match:\n");
        for (String note : tastingNotes) {
            prompt.append("- ").append(note).append("\n");
        }

        prompt.append("\nRules:\n");
        prompt.append("1. Match each note to the SINGLE closest attribute from the list above\n");
        prompt.append("2. If a note contains an attribute word, use that (e.g., 'bright citrus' → 'citrus')\n");
        prompt.append("3. If no good match exists, use null\n");
        prompt.append("4. Match based on flavor meaning, not just words (e.g., 'berry-like sweetness' → 'berry')\n\n");

        prompt.append("Return ONLY a valid JSON object.\n");
        prompt.append("Format: {\"tasting note\": \"attribute\" or null}\n");
        prompt.append("Example: {\"bright citrus acidity\": \"citrus\", \"jammy berries\": \"berry\", \"smooth finish\": null}\n");

        return prompt.toString();
    }
}
