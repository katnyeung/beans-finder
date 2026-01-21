package com.coffee.beansfinder.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

/**
 * Service to upload images to production server via HTTP API.
 * Simple solution: Local calls production API endpoint to upload image.
 */
@Service
@Slf4j
public class ImageUploadService {

    private final ObjectMapper objectMapper;
    private final RestTemplateBuilder restTemplateBuilder;
    private RestTemplate uploadRestTemplate;

    public ImageUploadService(ObjectMapper objectMapper, RestTemplateBuilder restTemplateBuilder) {
        this.objectMapper = objectMapper;
        this.restTemplateBuilder = restTemplateBuilder;
    }

    @PostConstruct
    public void init() {
        // Create RestTemplate with timeout for large uploads
        this.uploadRestTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(30))
                .setReadTimeout(Duration.ofSeconds(60))
                .build();
        log.info("ImageUploadService initialized with 30s connect / 60s read timeout");
    }

    @Value("${image.upload.enabled:false}")
    private boolean uploadEnabled;

    @Value("${image.upload.api.url:}")
    private String uploadApiUrl; // e.g., https://graphee.link/api/images/upload-base64

    @Value("${image.upload.api.key:}")
    private String uploadApiKey; // Simple API key for auth

    /**
     * Check if upload is configured
     */
    public boolean isConfigured() {
        boolean configured = uploadEnabled && uploadApiUrl != null && !uploadApiUrl.isEmpty();
        log.info("ImageUpload config check: enabled={}, url={}, configured={}",
                uploadEnabled, uploadApiUrl, configured);
        return configured;
    }

    /**
     * Upload a local image via HTTP to production server
     *
     * @param localPath Local path like /instagram-images/abc.png
     * @return Public URL accessible by Instagram
     */
    public String uploadAndGetPublicUrl(String localPath) {
        if (!isConfigured()) {
            log.warn("Image upload not configured, returning local path");
            return localPath;
        }

        try {
            String filename = Paths.get(localPath).getFileName().toString();
            Path localFile = findLocalFile(filename);

            if (localFile == null) {
                log.error("Local file not found for: {}", localPath);
                return localPath;
            }

            // Read file as bytes
            byte[] imageBytes = Files.readAllBytes(localFile);
            String base64Image = java.util.Base64.getEncoder().encodeToString(imageBytes);

            // Build request
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (uploadApiKey != null && !uploadApiKey.isEmpty()) {
                headers.set("X-API-Key", uploadApiKey);
            }

            // Send as JSON with base64 image
            String requestBody = objectMapper.writeValueAsString(java.util.Map.of(
                    "filename", filename,
                    "imageBase64", base64Image
            ));

            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

            log.info("Uploading image to production: {} -> {}", filename, uploadApiUrl);

            ResponseEntity<String> response = uploadRestTemplate.postForEntity(uploadApiUrl, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode result = objectMapper.readTree(response.getBody());
                String publicUrl = result.path("url").asText();

                if (publicUrl != null && !publicUrl.isEmpty()) {
                    log.info("Image uploaded successfully: {}", publicUrl);
                    return publicUrl;
                }
            }

            log.error("Upload failed: {}", response.getBody());
            return localPath;

        } catch (Exception e) {
            log.error("Image upload failed: {}", e.getMessage(), e);
            return localPath;
        }
    }

    private Path findLocalFile(String filename) {
        Path[] searchPaths = {
                Paths.get("./instagram-images", filename),
                Paths.get("src/main/resources/static/instagram-images", filename)
        };

        for (Path path : searchPaths) {
            if (Files.exists(path)) {
                return path;
            }
        }
        return null;
    }
}
