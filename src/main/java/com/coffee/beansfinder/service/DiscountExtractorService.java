package com.coffee.beansfinder.service;

import com.coffee.beansfinder.entity.BrandDiscount;
import com.coffee.beansfinder.entity.CoffeeBrand;
import com.coffee.beansfinder.repository.BrandDiscountRepository;
import com.coffee.beansfinder.repository.CoffeeBrandRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Extracts discount/promotion information from brand homepages
 * Uses Playwright to render the page and OpenAI to extract promotions
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DiscountExtractorService {

    private final PlaywrightScraperService playwrightScraperService;
    private final BrandDiscountRepository brandDiscountRepository;
    private final CoffeeBrandRepository brandRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${openai.api.key:}")
    private String apiKey;

    @Value("${openai.api.url:https://api.openai.com/v1/chat/completions}")
    private String apiUrl;

    private static final String DISCOUNT_EXTRACTION_PROMPT = """
        Extract ALL promotions, discounts, and offers from this coffee roaster homepage.

        Look for:
        - Percentage discounts ("10% off", "Save 20%")
        - Free shipping offers ("Free UK shipping over £40")
        - Subscription discounts ("Save 20% with subscription")
        - Discount codes ("Use code WELCOME15")
        - First-order offers ("10% off your first order")
        - Loyalty rewards ("Spend £125, get £25 back")
        - Seasonal offers ("Order by Dec 17 for Christmas")

        Common locations: announcement bars, banners, popups, hero sections.

        Return a JSON array of promotions found:
        {
          "promotions": [
            {
              "title": "Short title (e.g., '10% Off First Order')",
              "description": "Full promotional text",
              "discount_code": "CODE123 or null if no code"
            }
          ]
        }

        Rules:
        - Return empty array [] if NO promotions found
        - Only include ACTUAL offers, not general product info
        - Deduplicate repeated text (some sites repeat banner text)
        - Ignore newsletter signup without discount
        - Ignore general shipping info without discount

        Homepage text:
        %s
        """;

    /**
     * Extract and save discounts for a brand
     * Uses content hash to skip LLM if homepage unchanged
     *
     * @param brand The brand to extract discounts for
     * @return Number of discounts found (-1 if skipped due to unchanged content)
     */
    public int extractAndSave(CoffeeBrand brand) {
        if (brand.getWebsite() == null || brand.getWebsite().isBlank()) {
            log.warn("Brand {} has no website URL, skipping discount extraction", brand.getName());
            return 0;
        }

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("OpenAI API key not configured, skipping discount extraction");
            return 0;
        }

        try {
            log.info("Extracting discounts from homepage for brand: {}", brand.getName());

            // 1. Render homepage with Playwright
            String homepageText = extractHomepageText(brand.getWebsite());
            if (homepageText == null || homepageText.isBlank()) {
                log.warn("Could not extract homepage text for {}", brand.getName());
                return 0;
            }

            // Limit text size to control costs
            if (homepageText.length() > 15000) {
                homepageText = homepageText.substring(0, 15000);
            }

            // Sanitize text - remove problematic characters that break JSON
            homepageText = sanitizeText(homepageText);

            log.info("Sanitized homepage text for {}: {}", brand.getName(),
                    homepageText.length() > 500 ? homepageText.substring(0, 500) + "..." : homepageText);

            // 2. Check content hash - skip LLM if unchanged
            String newHash = generateHash(homepageText);
            if (newHash.equals(brand.getDiscountContentHash())) {
                log.info("Homepage unchanged for brand: {} (hash match), skipping LLM", brand.getName());
                return -1; // Return -1 to indicate skipped
            }

            // 3. Extract promotions via OpenAI
            List<ExtractedDiscount> extracted = extractPromotionsWithLLM(homepageText);

            // 4. Update hash regardless of results
            brand.setDiscountContentHash(newHash);
            brandRepository.save(brand);

            if (extracted.isEmpty()) {
                log.info("No discounts found for brand: {}", brand.getName());
                // Delete any existing discounts
                brandDiscountRepository.deleteByBrandId(brand.getId());
                return 0;
            }

            // 5. Delete old discounts and insert new ones
            brandDiscountRepository.deleteByBrandId(brand.getId());

            for (ExtractedDiscount discount : extracted) {
                BrandDiscount entity = BrandDiscount.builder()
                        .brandId(brand.getId())
                        .title(discount.title())
                        .description(discount.description())
                        .discountCode(discount.discountCode())
                        .build();
                brandDiscountRepository.save(entity);
            }

            log.info("Saved {} discounts for brand: {}", extracted.size(), brand.getName());
            return extracted.size();

        } catch (Exception e) {
            log.error("Failed to extract discounts for brand {}: {}", brand.getName(), e.getMessage());
            return 0;
        }
    }

    /**
     * Sanitize text to prevent JSON parsing issues
     * Removes/replaces problematic characters
     */
    private String sanitizeText(String text) {
        if (text == null) return "";

        return text
                // Replace smart quotes with regular quotes (using Unicode escapes)
                .replace('\u201C', '"')  // Left double quote
                .replace('\u201D', '"')  // Right double quote
                .replace('\u2018', '\'') // Left single quote
                .replace('\u2019', '\'') // Right single quote
                // Replace other problematic characters
                .replace('\u2013', '-')  // En dash
                .replace('\u2014', '-')  // Em dash
                .replace("\u2026", "...") // Ellipsis
                // Remove control characters except newlines and tabs
                .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "")
                // Normalize whitespace
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Generate SHA-256 hash of content
     */
    private String generateHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            log.error("Failed to generate hash: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Extract text from brand homepage using Playwright
     * Uses dedicated method that keeps notification bars and announcement sections
     */
    private String extractHomepageText(String url) {
        try {
            // Use dedicated method for homepage discount extraction
            // This keeps header notification bars unlike extractProductText()
            return playwrightScraperService.extractHomepageForDiscounts(url);
        } catch (Exception e) {
            log.error("Failed to extract homepage text from {}: {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * Call OpenAI to extract promotions from homepage text
     */
    private List<ExtractedDiscount> extractPromotionsWithLLM(String homepageText) {
        try {
            // Build prompt by concatenation (not String.format) to avoid % character issues
            String prompt = DISCOUNT_EXTRACTION_PROMPT.replace("%s", homepageText);

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + apiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);

            // Build request body manually to ensure proper JSON escaping
            Map<String, Object> requestBody = new java.util.HashMap<>();
            requestBody.put("model", "gpt-4o-mini");
            requestBody.put("messages", List.of(
                    Map.of("role", "system", "content", "You are a promotion extraction assistant. Extract discount and promotional information from coffee roaster websites. Return valid JSON only."),
                    Map.of("role", "user", "content", prompt)
            ));
            requestBody.put("temperature", 0.1);
            requestBody.put("max_tokens", 1000);
            requestBody.put("response_format", Map.of("type", "json_object"));

            // Use ObjectMapper to properly serialize JSON (handles special characters)
            String jsonBody = objectMapper.writeValueAsString(requestBody);
            log.debug("OpenAI request body length: {} chars", jsonBody.length());

            HttpEntity<String> request = new HttpEntity<>(jsonBody, headers);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(apiUrl, request, Map.class);

            if (response == null) {
                log.warn("Empty response from OpenAI");
                return List.of();
            }

            // Parse response
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                return List.of();
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            String content = (String) message.get("content");

            log.debug("OpenAI discount extraction response: {}", content);

            // Parse JSON response
            Map<String, Object> parsed = objectMapper.readValue(content, new TypeReference<>() {});

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> promotions = (List<Map<String, Object>>) parsed.get("promotions");

            if (promotions == null) {
                return List.of();
            }

            List<ExtractedDiscount> result = new ArrayList<>();
            for (Map<String, Object> promo : promotions) {
                String title = (String) promo.get("title");
                String description = (String) promo.get("description");
                String code = (String) promo.get("discount_code");

                if (title != null && !title.isBlank()) {
                    result.add(new ExtractedDiscount(title, description, code));
                }
            }

            return result;

        } catch (Exception e) {
            log.error("Failed to extract promotions with LLM: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Simple record for extracted discount data
     */
    private record ExtractedDiscount(String title, String description, String discountCode) {}
}
