package com.coffee.beansfinder.controller;

import com.coffee.beansfinder.entity.InstagramPost;
import com.coffee.beansfinder.repository.InstagramPostRepository;
import com.coffee.beansfinder.service.ImageUploadService;
import com.coffee.beansfinder.service.InstagramContentService;
import com.coffee.beansfinder.service.InstagramPublisherService;
import com.coffee.beansfinder.service.RedditCrawlerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/instagram")
@Tag(name = "Instagram Admin", description = "Admin endpoints for Instagram content management")
@RequiredArgsConstructor
@Slf4j
public class InstagramAdminController {

    private final InstagramPostRepository postRepository;
    private final InstagramContentService contentService;
    private final InstagramPublisherService publisherService;
    private final RedditCrawlerService redditCrawlerService;
    private final ImageUploadService imageUploadService;

    // ==================== Draft Management ====================

    @GetMapping("/drafts")
    @Operation(summary = "Get all draft posts")
    public ResponseEntity<List<InstagramPost>> getDrafts() {
        return ResponseEntity.ok(postRepository.findByStatusOrderByCreatedAtDesc("draft"));
    }

    @GetMapping("/posts/{id}")
    @Operation(summary = "Get a single post by ID")
    public ResponseEntity<InstagramPost> getPost(@PathVariable Long id) {
        return postRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/posts/{id}/caption")
    @Operation(summary = "Update post caption")
    public ResponseEntity<InstagramPost> updateCaption(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {

        return postRepository.findById(id)
                .map(post -> {
                    post.setCaption(body.get("caption"));
                    return ResponseEntity.ok(postRepository.save(post));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ==================== Image Management ====================

    @PostMapping("/posts/{id}/regenerate-image")
    @Operation(summary = "Regenerate image for a post using DALL-E")
    public ResponseEntity<InstagramPost> regenerateImage(@PathVariable Long id) {
        try {
            InstagramPost post = contentService.regenerateImage(id);
            return ResponseEntity.ok(post);
        } catch (Exception e) {
            log.error("Failed to regenerate image for post {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    // ==================== Workflow Actions ====================

    @PostMapping("/posts/{id}/approve")
    @Operation(summary = "Approve a draft post")
    public ResponseEntity<?> approve(@PathVariable Long id) {
        return postRepository.findById(id)
                .map(post -> {
                    if (!"draft".equals(post.getStatus())) {
                        Map<String, String> error = new HashMap<>();
                        error.put("error", "Can only approve posts with status 'draft'");
                        error.put("currentStatus", post.getStatus());
                        return ResponseEntity.badRequest().body(error);
                    }
                    post.setStatus("approved");
                    return ResponseEntity.ok(postRepository.save(post));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/posts/{id}/schedule")
    @Operation(summary = "Schedule a post for publishing")
    public ResponseEntity<InstagramPost> schedule(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {

        return postRepository.findById(id)
                .map(post -> {
                    String scheduledAtStr = body.get("scheduledAt");
                    if (scheduledAtStr == null || scheduledAtStr.isEmpty()) {
                        return ResponseEntity.badRequest().<InstagramPost>build();
                    }

                    LocalDateTime scheduledAt = LocalDateTime.parse(scheduledAtStr,
                            DateTimeFormatter.ISO_LOCAL_DATE_TIME);

                    post.setStatus("scheduled");
                    post.setScheduledAt(scheduledAt);
                    return ResponseEntity.ok(postRepository.save(post));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/posts/{id}/reject")
    @Operation(summary = "Reject a post")
    public ResponseEntity<InstagramPost> reject(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {

        return postRepository.findById(id)
                .map(post -> {
                    post.setStatus("rejected");
                    post.setRejectionReason(body.get("reason"));
                    return ResponseEntity.ok(postRepository.save(post));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/posts/{id}/cancel")
    @Operation(summary = "Cancel a draft and release Reddit posts for reuse")
    public ResponseEntity<?> cancel(@PathVariable Long id) {
        return postRepository.findById(id)
                .map(post -> {
                    // Release Reddit posts back to available pool
                    if (post.getSourceIds() != null && !post.getSourceIds().isEmpty()) {
                        try {
                            com.fasterxml.jackson.databind.JsonNode sources =
                                new com.fasterxml.jackson.databind.ObjectMapper().readTree(post.getSourceIds());
                            if (sources.has("reddit")) {
                                java.util.List<String> redditIds = new java.util.ArrayList<>();
                                for (com.fasterxml.jackson.databind.JsonNode node : sources.get("reddit")) {
                                    redditIds.add(node.asText());
                                }
                                redditCrawlerService.releasePostsAsUnused(redditIds);
                            }
                        } catch (Exception e) {
                            log.warn("Failed to parse sourceIds for post {}: {}", id, e.getMessage());
                        }
                    }

                    post.setStatus("cancelled");
                    return ResponseEntity.ok(postRepository.save(post));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/posts/{id}/publish-now")
    @Operation(summary = "Publish a post immediately")
    public ResponseEntity<?> publishNow(@PathVariable Long id) {
        try {
            publisherService.publishNow(id);
            return postRepository.findById(id)
                    .map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            log.error("Failed to publish post {}: {}", id, e.getMessage());
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @DeleteMapping("/posts/{id}")
    @Operation(summary = "Delete a post")
    public ResponseEntity<Void> deletePost(@PathVariable Long id) {
        if (postRepository.existsById(id)) {
            postRepository.deleteById(id);
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }

    // ==================== List Views ====================

    @GetMapping("/scheduled")
    @Operation(summary = "Get all scheduled posts")
    public ResponseEntity<List<InstagramPost>> getScheduled() {
        return ResponseEntity.ok(postRepository.findByStatusOrderByScheduledAtAsc("scheduled"));
    }

    @GetMapping("/posted")
    @Operation(summary = "Get all posted content")
    public ResponseEntity<List<InstagramPost>> getPosted() {
        return ResponseEntity.ok(postRepository.findByStatusOrderByPostedAtDesc("posted"));
    }

    @GetMapping("/rejected")
    @Operation(summary = "Get all rejected posts")
    public ResponseEntity<List<InstagramPost>> getRejected() {
        return ResponseEntity.ok(postRepository.findByStatusOrderByCreatedAtDesc("rejected"));
    }

    // ==================== Manual Triggers ====================

    @PostMapping("/generate")
    @Operation(summary = "Manually trigger content generation")
    public ResponseEntity<?> triggerGeneration(
            @RequestParam(defaultValue = "false") boolean force) {
        try {
            InstagramPost post = contentService.generatePost(force);
            return ResponseEntity.ok(post);
        } catch (Exception e) {
            log.error("Content generation failed: {}", e.getMessage());
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @PostMapping("/crawl-reddit")
    @Operation(summary = "Manually trigger Reddit crawl")
    public ResponseEntity<Map<String, Object>> triggerRedditCrawl() {
        try {
            int count = redditCrawlerService.crawlAllSubreddits();
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("postsCrawled", count);
            result.put("cacheStats", redditCrawlerService.getCacheStats());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Reddit crawl failed: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @GetMapping("/reddit-cache")
    @Operation(summary = "Get Reddit cache statistics")
    public ResponseEntity<RedditCrawlerService.CacheStats> getRedditCacheStats() {
        return ResponseEntity.ok(redditCrawlerService.getCacheStats());
    }

    @PostMapping("/reddit-cache/clear-used")
    @Operation(summary = "Clear used posts tracking (allows re-using posts)")
    public ResponseEntity<Map<String, Object>> clearUsedPosts() {
        redditCrawlerService.clearUsedPosts();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Used posts tracking cleared");
        result.put("cacheStats", redditCrawlerService.getCacheStats());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/status")
    @Operation(summary = "Get Instagram integration status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("instagramConfigured", publisherService.isConfigured());
        status.put("redditCacheStats", redditCrawlerService.getCacheStats());

        Map<String, Long> postCounts = new HashMap<>();
        postCounts.put("draft", (long) postRepository.findByStatus("draft").size());
        postCounts.put("approved", (long) postRepository.findByStatus("approved").size());
        postCounts.put("scheduled", (long) postRepository.findByStatus("scheduled").size());
        postCounts.put("posted", (long) postRepository.findByStatus("posted").size());
        postCounts.put("rejected", (long) postRepository.findByStatus("rejected").size());
        status.put("postCounts", postCounts);

        return ResponseEntity.ok(status);
    }

    @GetMapping("/verify-token")
    @Operation(summary = "Verify Instagram access token is valid")
    public ResponseEntity<?> verifyToken() {
        try {
            String result = publisherService.verifyToken();
            Map<String, Object> response = new HashMap<>();
            response.put("valid", true);
            response.put("response", result);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Token verification failed: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("valid", false);
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @PostMapping("/test-upload")
    @Operation(summary = "Test image upload to production server")
    public ResponseEntity<?> testImageUpload(@RequestParam String localPath) {
        Map<String, Object> result = new HashMap<>();
        result.put("configured", imageUploadService.isConfigured());
        result.put("inputPath", localPath);

        if (!imageUploadService.isConfigured()) {
            result.put("error", "Image upload not configured. Check IMAGE_UPLOAD_ENABLED and IMAGE_UPLOAD_API_URL");
            return ResponseEntity.badRequest().body(result);
        }

        try {
            String publicUrl = imageUploadService.uploadAndGetPublicUrl(localPath);
            result.put("success", true);
            result.put("publicUrl", publicUrl);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Test upload failed: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }
}
