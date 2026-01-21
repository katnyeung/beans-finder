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

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class DalleImageService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${openai.api.key}")
    private String apiKey;

    @Value("${dalle.model:dall-e-3}")
    private String model;

    @Value("${dalle.size:1024x1024}")
    private String size;

    @Value("${dalle.quality:standard}")
    private String quality;

    @Value("${dalle.image.storage.path:src/main/resources/static/instagram-images}")
    private String imageStoragePath;

    @Value("${dalle.image.base.url:/instagram-images}")
    private String imageBaseUrl;

    private static final String DALLE_API_URL = "https://api.openai.com/v1/images/generations";

    /**
     * Generate an image using DALL-E 3 and save it locally
     *
     * @param prompt The image generation prompt
     * @return Local URL of the saved image (permanent)
     */
    public String generateImage(String prompt) {
        log.info("Generating DALL-E image with prompt: {}...", prompt.substring(0, Math.min(100, prompt.length())));

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("prompt", prompt);
            requestBody.put("size", size);
            requestBody.put("quality", quality);
            requestBody.put("n", 1);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            String response = restTemplate.postForObject(DALLE_API_URL, entity, String.class);

            if (response != null) {
                JsonNode root = objectMapper.readTree(response);
                JsonNode data = root.path("data");

                if (data.isArray() && data.size() > 0) {
                    String dalleUrl = data.get(0).path("url").asText();
                    log.info("DALL-E image generated: {}", dalleUrl.substring(0, Math.min(80, dalleUrl.length())));

                    // Download and save locally as backup
                    String localPath = downloadAndSaveImage(dalleUrl);
                    if (localPath != null) {
                        log.info("Image also saved locally: {}", localPath);
                    }

                    // Return DALL-E URL for Instagram publishing (expires in ~1 hour)
                    // Use localPath for long-term storage/display on your site
                    return dalleUrl;
                }
            }

            log.error("DALL-E response did not contain image URL");
            return null;

        } catch (Exception e) {
            log.error("DALL-E image generation failed: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Download image from URL and save to local storage
     *
     * @param imageUrl The temporary DALL-E image URL
     * @return Local URL path (e.g., /instagram-images/abc123.png)
     */
    private String downloadAndSaveImage(String imageUrl) {
        try {
            // Ensure storage directory exists
            Path storagePath = Paths.get(imageStoragePath);
            if (!Files.exists(storagePath)) {
                Files.createDirectories(storagePath);
                log.info("Created image storage directory: {}", storagePath);
            }

            // Generate unique filename
            String filename = UUID.randomUUID().toString() + ".png";
            Path targetPath = storagePath.resolve(filename);

            // Download image
            try (InputStream in = new URL(imageUrl).openStream()) {
                Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }

            log.info("Image saved to: {}", targetPath);
            return imageBaseUrl + "/" + filename;

        } catch (Exception e) {
            log.error("Failed to download and save image: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Calculate estimated cost for image generation
     * DALL-E 3 pricing: $0.040 per image (1024x1024, standard quality)
     */
    public double getEstimatedCost() {
        if ("hd".equals(quality)) {
            return 0.080; // HD quality
        }
        return 0.040; // Standard quality
    }
}
