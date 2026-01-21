package com.coffee.beansfinder.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class GeminiImageService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${gemini.api.key:}")
    private String apiKey;

    @Value("${gemini.image.model:gemini-2.0-flash-exp}")
    private String model;

    @Value("${gemini.image.storage.path:src/main/resources/static/instagram-images}")
    private String imageStoragePath;

    @Value("${gemini.image.base.url:/instagram-images}")
    private String imageBaseUrl;

    // Gemini 2.0/2.5 uses generateContent endpoint
    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";

    /**
     * Check if Gemini is configured
     */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isEmpty();
    }

    /**
     * Generate an image using Gemini 2.0/2.5 Flash and save it locally
     *
     * @param prompt The image generation prompt
     * @return Local URL of the saved image
     */
    public String generateImage(String prompt) {
        if (!isConfigured()) {
            log.warn("Gemini API key not configured");
            return null;
        }

        log.info("Generating Gemini image with model {} and prompt: {}...", model, prompt.substring(0, Math.min(100, prompt.length())));

        try {
            String url = String.format(GEMINI_API_URL, model, apiKey);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // Gemini 2.0/2.5 generateContent API format
            Map<String, Object> requestBody = Map.of(
                    "contents", List.of(
                            Map.of("parts", List.of(
                                    Map.of("text", "Generate an image: " + prompt)
                            ))
                    ),
                    "generationConfig", Map.of(
                            "responseModalities", List.of("Text", "Image")
                    )
            );

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            String response = restTemplate.postForObject(url, entity, String.class);

            if (response != null) {
                JsonNode root = objectMapper.readTree(response);
                JsonNode candidates = root.path("candidates");

                if (candidates.isArray() && candidates.size() > 0) {
                    JsonNode parts = candidates.get(0).path("content").path("parts");

                    // Find the image part in the response
                    for (JsonNode part : parts) {
                        JsonNode inlineData = part.path("inlineData");
                        if (!inlineData.isMissingNode()) {
                            String base64Image = inlineData.path("data").asText();
                            String mimeType = inlineData.path("mimeType").asText();

                            if (base64Image != null && !base64Image.isEmpty()) {
                                String extension = mimeType.contains("png") ? ".png" : ".jpg";
                                String localPath = saveBase64Image(base64Image, extension);
                                log.info("Gemini image saved locally: {}", localPath);
                                return localPath;
                            }
                        }
                    }
                }
            }

            log.error("Gemini response did not contain image data");
            return null;

        } catch (Exception e) {
            log.error("Gemini image generation failed: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Save base64 encoded image to local storage
     *
     * @param base64Image Base64 encoded image data
     * @param extension File extension (.png or .jpg)
     * @return Local URL path (e.g., /instagram-images/abc123.png)
     */
    private String saveBase64Image(String base64Image, String extension) {
        try {
            // Ensure storage directory exists
            Path storagePath = Paths.get(imageStoragePath);
            if (!Files.exists(storagePath)) {
                Files.createDirectories(storagePath);
                log.info("Created image storage directory: {}", storagePath);
            }

            // Generate unique filename
            String filename = UUID.randomUUID().toString() + extension;
            Path targetPath = storagePath.resolve(filename);

            // Decode and save
            byte[] imageBytes = Base64.getDecoder().decode(base64Image);
            Files.write(targetPath, imageBytes);

            log.info("Image saved to: {}", targetPath);
            return imageBaseUrl + "/" + filename;

        } catch (Exception e) {
            log.error("Failed to save base64 image: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Calculate estimated cost for image generation
     * Gemini 2.5 Flash Image: $0.039 per image (1290 output tokens @ $30/1M tokens)
     * Gemini 2.0 Flash Exp: Free during experimental period
     */
    public double getEstimatedCost() {
        if (model.contains("2.0-flash-exp")) {
            return 0.0; // Free during experimental
        }
        return 0.039; // Gemini 2.5 Flash Image
    }
}
