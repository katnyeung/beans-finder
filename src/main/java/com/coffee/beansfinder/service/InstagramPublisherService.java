package com.coffee.beansfinder.service;

import com.coffee.beansfinder.entity.InstagramCredentials;
import com.coffee.beansfinder.entity.InstagramPost;
import com.coffee.beansfinder.repository.InstagramPostRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class InstagramPublisherService {

    private final InstagramPostRepository postRepository;
    private final InstagramOAuthService oAuthService;
    private final ImageUploadService imageUploadService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // Instagram Platform API (Direct Login) uses graph.instagram.com
    // Note: graph.facebook.com is for the OLD Facebook-linked flow
    // The new Instagram Platform API (2024+) uses Instagram endpoints directly
    private static final String INSTAGRAM_GRAPH_URL = "https://graph.instagram.com";

    @Value("${app.base.url:https://graphee.link}")
    private String appBaseUrl;

    /**
     * Check for scheduled posts and publish if due.
     * Currently manual-only - call via admin API when ready to test.
     */
    public void publishScheduledPosts() {
        if (!oAuthService.hasValidCredentials()) {
            log.debug("Instagram not connected, skipping publisher check");
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        List<InstagramPost> duePosts = postRepository.findByStatusAndScheduledAtBefore("scheduled", now);

        if (duePosts.isEmpty()) {
            log.debug("No scheduled posts due for publishing");
            return;
        }

        log.info("Found {} posts due for publishing", duePosts.size());

        for (InstagramPost post : duePosts) {
            try {
                publishPost(post);
            } catch (Exception e) {
                log.error("Failed to publish post {}: {}", post.getId(), e.getMessage());
            }
        }
    }

    /**
     * Publish a single post to Instagram using Platform API
     *
     * Instagram Platform API flow:
     * 1. Create a media container with the image URL and caption
     * 2. Publish the container
     */
    public void publishPost(InstagramPost post) {
        log.info("Publishing Instagram post: {}", post.getId());

        // Get credentials from OAuth service
        InstagramCredentials credentials = oAuthService.getCurrentCredentials()
                .orElseThrow(() -> new RuntimeException("Instagram not connected. Please authorize via /api/instagram/oauth/authorize"));

        if (credentials.isTokenExpired()) {
            throw new RuntimeException("Instagram token expired. Please refresh or re-authorize.");
        }

        if (post.getImageUrl() == null || post.getImageUrl().isEmpty()) {
            throw new RuntimeException("Post has no image URL");
        }

        // Ensure image URL is absolute (Instagram needs a public URL)
        String imageUrl = post.getImageUrl();
        if (imageUrl.startsWith("/")) {
            // Local path - need to upload to public server first
            if (imageUploadService.isConfigured()) {
                log.info("Uploading local image to public server...");
                imageUrl = imageUploadService.uploadAndGetPublicUrl(imageUrl);
                log.info("Image uploaded, public URL: {}", imageUrl);
            } else {
                // Fallback: assume running on production, convert to production URL
                imageUrl = appBaseUrl + imageUrl;
                log.info("No upload configured, using production URL: {}", imageUrl);
            }
        } else if (imageUrl.startsWith("http")) {
            // Already absolute URL (e.g., DALL-E URL) - use as-is
            log.info("Using absolute URL for Instagram: {}...", imageUrl.substring(0, Math.min(60, imageUrl.length())));
        }

        try {
            String userId = credentials.getInstagramUserId();
            String accessToken = credentials.getAccessToken();

            // Clean token - remove whitespace AND any non-alphanumeric chars from ends
            // Fixes common copy-paste issues like trailing > from HTML
            if (accessToken != null) {
                accessToken = accessToken.trim();
                accessToken = accessToken.replaceAll("^[^a-zA-Z0-9]+", "");
                accessToken = accessToken.replaceAll("[^a-zA-Z0-9]+$", "");
            }

            // Debug logging
            log.info("Instagram publish - User ID: {}, Token length: {}, Token prefix: {}...",
                    userId,
                    accessToken != null ? accessToken.length() : 0,
                    accessToken != null && accessToken.length() > 20 ? accessToken.substring(0, 20) : "null");

            // Step 1: Create media container
            String containerId = createMediaContainer(userId, accessToken, imageUrl, post.getCaption());
            log.info("Created media container: {}", containerId);

            // Wait a moment for Instagram to process the image
            Thread.sleep(5000);

            // Step 2: Publish the container
            String mediaId = publishMediaContainer(userId, accessToken, containerId);
            log.info("Published media: {}", mediaId);

            // Update post status
            post.setStatus("posted");
            post.setPostedAt(LocalDateTime.now());
            post.setInstagramMediaId(mediaId);
            postRepository.save(post);

            log.info("Successfully published post {} to Instagram (media ID: {})", post.getId(), mediaId);

        } catch (Exception e) {
            log.error("Failed to publish post {}: {}", post.getId(), e.getMessage(), e);
            throw new RuntimeException("Instagram publishing failed: " + e.getMessage());
        }
    }

    /**
     * Create a media container on Instagram
     * Uses Instagram Platform API: POST /{ig-user-id}/media
     */
    private String createMediaContainer(String userId, String accessToken, String imageUrl, String caption) throws Exception {
        String url = String.format("%s/%s/media", INSTAGRAM_GRAPH_URL, userId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("image_url", imageUrl);
        params.add("caption", caption);
        params.add("access_token", accessToken);

        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(params, headers);

        String response = restTemplate.postForObject(url, entity, String.class);
        JsonNode json = objectMapper.readTree(response);

        if (json.has("id")) {
            return json.get("id").asText();
        } else if (json.has("error")) {
            throw new RuntimeException("Instagram API error: " + json.get("error").get("message").asText());
        }

        throw new RuntimeException("Unexpected response from Instagram API");
    }

    /**
     * Publish the media container
     * Uses Instagram Platform API: POST /{ig-user-id}/media_publish
     */
    private String publishMediaContainer(String userId, String accessToken, String containerId) throws Exception {
        String url = String.format("%s/%s/media_publish", INSTAGRAM_GRAPH_URL, userId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("creation_id", containerId);
        params.add("access_token", accessToken);

        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(params, headers);

        String response = restTemplate.postForObject(url, entity, String.class);
        JsonNode json = objectMapper.readTree(response);

        if (json.has("id")) {
            return json.get("id").asText();
        } else if (json.has("error")) {
            throw new RuntimeException("Instagram API error: " + json.get("error").get("message").asText());
        }

        throw new RuntimeException("Unexpected response from Instagram API");
    }

    /**
     * Publish a post immediately (manual trigger)
     */
    public void publishNow(Long postId) {
        InstagramPost post = postRepository.findById(postId)
                .orElseThrow(() -> new RuntimeException("Post not found: " + postId));

        if (!"approved".equals(post.getStatus()) && !"scheduled".equals(post.getStatus())) {
            throw new RuntimeException("Post must be approved or scheduled before publishing");
        }

        publishPost(post);
    }

    /**
     * Check if Instagram API is configured
     */
    public boolean isConfigured() {
        return oAuthService.hasValidCredentials();
    }

    /**
     * Verify token is valid by making a simple API call
     * Returns user info if valid, throws exception if invalid
     */
    public String verifyToken() {
        InstagramCredentials credentials = oAuthService.getCurrentCredentials()
                .orElseThrow(() -> new RuntimeException("No Instagram credentials found"));

        String userId = credentials.getInstagramUserId();
        String accessToken = credentials.getAccessToken().trim();
        // Clean token - remove non-alphanumeric chars from ends
        accessToken = accessToken.replaceAll("^[^a-zA-Z0-9]+", "");
        accessToken = accessToken.replaceAll("[^a-zA-Z0-9]+$", "");

        String url = String.format("%s/%s?fields=id,username,account_type&access_token=%s",
                INSTAGRAM_GRAPH_URL, userId, accessToken);

        log.info("Verifying Instagram token for user: {}", userId);

        try {
            String response = restTemplate.getForObject(url, String.class);
            log.info("Token verification response: {}", response);
            return response;
        } catch (Exception e) {
            log.error("Token verification failed: {}", e.getMessage());
            throw new RuntimeException("Token verification failed: " + e.getMessage());
        }
    }
}
