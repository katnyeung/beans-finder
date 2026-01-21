package com.coffee.beansfinder.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * API endpoint for uploading images from local dev to production.
 * Used when generating images locally with Gemini for Instagram publishing.
 */
@RestController
@RequestMapping("/api/images")
@Slf4j
public class ImageUploadController {

    @Value("${image.upload.api.key:}")
    private String apiKey;

    @Value("${image.upload.storage.path:./instagram-images}")
    private String storagePath;

    @Value("${app.base.url:https://graphee.link}")
    private String baseUrl;

    /**
     * Upload image via multipart form (for Swagger/manual testing)
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload image file", description = "Upload image file for Instagram publishing")
    public ResponseEntity<?> uploadFile(
            @RequestHeader(value = "X-API-Key", required = false) String providedKey,
            @RequestParam("file") MultipartFile file) {

        if (!checkApiKey(providedKey)) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "File is empty"));
            }

            // Generate unique filename or use original
            String originalFilename = file.getOriginalFilename();
            String extension = getExtension(originalFilename);
            String filename = UUID.randomUUID().toString() + extension;

            // Validate extension
            if (!extension.matches("\\.(png|jpg|jpeg|gif|webp)")) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid file type. Allowed: png, jpg, jpeg, gif, webp"));
            }

            // Save file
            String publicUrl = saveFile(filename, file.getBytes());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "url", publicUrl,
                    "filename", filename
            ));

        } catch (Exception e) {
            log.error("Upload failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Upload image via JSON base64 (for programmatic upload from local dev)
     */
    @Data
    public static class ImageUploadRequest {
        @Schema(description = "Image filename", example = "abc123.png")
        private String filename;

        @Schema(description = "Base64 encoded image data")
        private String imageBase64;
    }

    @PostMapping(value = "/upload-base64", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Upload base64 image", description = "Upload base64 encoded image from local dev")
    public ResponseEntity<?> uploadBase64(
            @RequestHeader(value = "X-API-Key", required = false) String providedKey,
            @RequestBody ImageUploadRequest request) {

        if (!checkApiKey(providedKey)) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        try {
            String filename = request.getFilename();
            String imageBase64 = request.getImageBase64();

            if (filename == null || imageBase64 == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Missing filename or imageBase64"));
            }

            // Sanitize filename
            filename = Paths.get(filename).getFileName().toString();
            if (!filename.matches("[a-zA-Z0-9\\-_.]+\\.(png|jpg|jpeg|gif|webp)")) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid filename"));
            }

            // Decode and save
            byte[] imageBytes = Base64.getDecoder().decode(imageBase64);
            String publicUrl = saveFile(filename, imageBytes);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "url", publicUrl,
                    "filename", filename
            ));

        } catch (Exception e) {
            log.error("Upload failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private boolean checkApiKey(String providedKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            return true; // No auth required
        }
        if (providedKey == null || !apiKey.equals(providedKey)) {
            log.warn("Unauthorized upload attempt");
            return false;
        }
        return true;
    }

    private String saveFile(String filename, byte[] imageBytes) throws Exception {
        Path storageDir = Paths.get(storagePath);
        if (!Files.exists(storageDir)) {
            Files.createDirectories(storageDir);
        }

        Path targetPath = storageDir.resolve(filename);
        Files.write(targetPath, imageBytes);

        log.info("Image uploaded: {} ({} bytes)", targetPath, imageBytes.length);

        return baseUrl + "/instagram-images/" + filename;
    }

    private String getExtension(String filename) {
        if (filename == null) return ".png";
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(lastDot).toLowerCase() : ".png";
    }
}
