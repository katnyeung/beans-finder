package com.coffee.beansfinder.service;

import com.coffee.beansfinder.dto.RedditPost;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class RedditCrawlerService {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Value("${instagram.reddit.subreddits:pourover,Coffee,espresso}")
    private String subredditsConfig;

    @Value("${reddit.crawl.enabled:false}")
    private boolean crawlEnabled;

    private static final String REDIS_KEY_PREFIX = "reddit:posts:";
    private static final Duration CACHE_TTL = Duration.ofDays(7);
    private static final String USER_AGENT = "GrapheeCuriousAI/1.0 (Coffee Discovery Bot; contact@graphee.link)";

    // In-memory fallback cache when Redis is unavailable
    private final java.util.concurrent.ConcurrentHashMap<String, RedditPost> memoryCache = new java.util.concurrent.ConcurrentHashMap<>();
    private boolean redisAvailable = true;

    // Track used posts to avoid duplicates in content generation
    private final java.util.Set<String> usedPostIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Daily scheduled crawl at 6:00 AM
     */
    // TODO: Investigate network issues on production server - DNS/firewall blocking external domains
    // @Scheduled(cron = "${instagram.reddit.crawl.cron:0 0 6 * * *}")
    public void dailyCrawlJob() {
        log.info("=== Starting scheduled Reddit crawl ===");
        try {
            int totalPosts = crawlAllSubreddits();
            log.info("Reddit crawl completed. Total posts cached: {}", totalPosts);
        } catch (Exception e) {
            log.error("Reddit crawl failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Crawl all configured subreddits
     */
    public int crawlAllSubreddits() {
        String[] subreddits = subredditsConfig.split(",");
        int totalPosts = 0;

        for (String subreddit : subreddits) {
            try {
                List<RedditPost> posts = crawlSubreddit(subreddit.trim());
                totalPosts += posts.size();
                log.info("Crawled r/{}: {} posts", subreddit.trim(), posts.size());

                // Rate limiting - Reddit recommends 1 request per 2 seconds
                Thread.sleep(2000);
            } catch (Exception e) {
                log.error("Failed to crawl r/{}: {}", subreddit, e.getMessage());
            }
        }

        return totalPosts;
    }

    /**
     * Crawl a single subreddit and cache posts in Redis
     */
    public List<RedditPost> crawlSubreddit(String subreddit) {
        List<RedditPost> posts = new ArrayList<>();
        String url = String.format("https://www.reddit.com/r/%s/hot.json?limit=25", subreddit);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", USER_AGENT);

            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode children = root.path("data").path("children");

                for (JsonNode child : children) {
                    JsonNode data = child.path("data");

                    // Skip stickied posts and non-text posts
                    if (data.path("stickied").asBoolean(false)) {
                        continue;
                    }

                    RedditPost post = RedditPost.builder()
                            .id(data.path("id").asText())
                            .title(data.path("title").asText())
                            .selftext(data.path("selftext").asText(""))
                            .author(data.path("author").asText())
                            .subreddit(subreddit)
                            .score(data.path("score").asInt(0))
                            .numComments(data.path("num_comments").asInt(0))
                            .url(data.path("url").asText())
                            .permalink("https://reddit.com" + data.path("permalink").asText())
                            .createdAt(LocalDateTime.ofInstant(
                                    Instant.ofEpochSecond(data.path("created_utc").asLong()),
                                    ZoneId.systemDefault()))
                            .build();

                    // Fetch top comments for posts with good engagement (>5 comments, >10 score)
                    if (post.getNumComments() > 5 && post.getScore() > 10) {
                        try {
                            Thread.sleep(1000); // Rate limit
                            List<RedditPost.RedditComment> comments = fetchTopComments(subreddit, post.getId());
                            post.setTopComments(comments);
                        } catch (Exception e) {
                            log.warn("Failed to fetch comments for post {}: {}", post.getId(), e.getMessage());
                        }
                    }

                    posts.add(post);

                    // Cache in Redis (with fallback to memory)
                    cachePost(subreddit, post);
                }
            }
        } catch (Exception e) {
            log.error("Error crawling r/{}: {}", subreddit, e.getMessage());
        }

        return posts;
    }

    /**
     * Cache post - tries Redis first, falls back to memory
     */
    private void cachePost(String subreddit, RedditPost post) {
        String key = subreddit + ":" + post.getId();

        // Always store in memory cache
        memoryCache.put(key, post);

        // Try Redis if available
        if (redisAvailable) {
            try {
                String redisKey = REDIS_KEY_PREFIX + key;
                redisTemplate.opsForValue().set(redisKey, objectMapper.writeValueAsString(post), CACHE_TTL);
            } catch (Exception e) {
                log.warn("Redis unavailable, using memory cache only: {}", e.getMessage());
                redisAvailable = false;
            }
        }
    }

    /**
     * Fetch top comments for a post
     */
    private List<RedditPost.RedditComment> fetchTopComments(String subreddit, String postId) {
        List<RedditPost.RedditComment> comments = new ArrayList<>();
        String url = String.format("https://www.reddit.com/r/%s/comments/%s.json?limit=10&sort=top", subreddit, postId);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", USER_AGENT);

            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());

                // Reddit returns array: [post, comments]
                if (root.isArray() && root.size() > 1) {
                    JsonNode commentsNode = root.get(1).path("data").path("children");

                    for (JsonNode commentNode : commentsNode) {
                        if (!"t1".equals(commentNode.path("kind").asText())) {
                            continue; // Skip non-comment nodes
                        }

                        JsonNode commentData = commentNode.path("data");
                        String body = commentData.path("body").asText("");

                        // Skip deleted/removed comments and very short ones
                        if (body.isEmpty() || body.equals("[deleted]") || body.equals("[removed]") || body.length() < 20) {
                            continue;
                        }

                        RedditPost.RedditComment comment = RedditPost.RedditComment.builder()
                                .author(commentData.path("author").asText())
                                .body(body.length() > 500 ? body.substring(0, 500) + "..." : body)
                                .score(commentData.path("score").asInt(0))
                                .build();

                        comments.add(comment);

                        if (comments.size() >= 5) {
                            break; // Top 5 comments only
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error fetching comments for {}/{}: {}", subreddit, postId, e.getMessage());
        }

        return comments;
    }

    /**
     * Get all cached posts - tries Redis first, falls back to memory
     */
    public List<RedditPost> getAllCachedPosts() {
        List<RedditPost> allPosts = new ArrayList<>();

        // Try Redis first
        if (redisAvailable) {
            try {
                Set<String> keys = redisTemplate.keys(REDIS_KEY_PREFIX + "*");
                if (keys != null && !keys.isEmpty()) {
                    for (String key : keys) {
                        String json = redisTemplate.opsForValue().get(key);
                        if (json != null) {
                            RedditPost post = objectMapper.readValue(json, RedditPost.class);
                            allPosts.add(post);
                        }
                    }
                }
                // Sort by score descending
                return allPosts.stream()
                        .sorted(Comparator.comparingInt(RedditPost::getScore).reversed())
                        .collect(Collectors.toList());
            } catch (Exception e) {
                log.warn("Redis unavailable, falling back to memory cache: {}", e.getMessage());
                redisAvailable = false;
            }
        }

        // Fallback to memory cache
        log.info("Using memory cache ({} posts)", memoryCache.size());
        return memoryCache.values().stream()
                .sorted(Comparator.comparingInt(RedditPost::getScore).reversed())
                .collect(Collectors.toList());
    }

    /**
     * Get top N posts for content generation
     * Prioritizes: unused posts > high engagement > newer posts
     */
    public List<RedditPost> getTopPosts(int limit) {
        return getAllCachedPosts().stream()
                // Filter out already used posts first
                .filter(p -> !usedPostIds.contains(p.getId()))
                // Sort by engagement quality (comments matter more than just upvotes)
                .sorted(Comparator
                        .comparingInt((RedditPost p) -> p.getNumComments())
                        .thenComparingInt(RedditPost::getScore)
                        .reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * Mark posts as used (called after successful content generation)
     */
    public void markPostsAsUsed(List<String> postIds) {
        usedPostIds.addAll(postIds);
        log.info("Marked {} posts as used. Total used: {}", postIds.size(), usedPostIds.size());
    }

    /**
     * Release posts back to available (e.g., when draft is cancelled)
     */
    public void releasePostsAsUnused(List<String> postIds) {
        usedPostIds.removeAll(postIds);
        log.info("Released {} posts back to available. Total used: {}", postIds.size(), usedPostIds.size());
    }

    /**
     * Clear used posts tracking (e.g., after a week)
     */
    public void clearUsedPosts() {
        int count = usedPostIds.size();
        usedPostIds.clear();
        log.info("Cleared {} used post IDs", count);
    }

    /**
     * Get count of available (unused) posts
     */
    public int getAvailablePostCount() {
        return (int) getAllCachedPosts().stream()
                .filter(p -> !usedPostIds.contains(p.getId()))
                .count();
    }

    /**
     * Get cache statistics
     */
    public CacheStats getCacheStats() {
        String[] subreddits = subredditsConfig.split(",");
        java.util.Map<String, Long> bySubreddit = new java.util.HashMap<>();
        int totalPosts = 0;

        // Try Redis first
        if (redisAvailable) {
            try {
                Set<String> keys = redisTemplate.keys(REDIS_KEY_PREFIX + "*");
                totalPosts = keys != null ? keys.size() : 0;

                for (String subreddit : subreddits) {
                    Set<String> subKeys = redisTemplate.keys(REDIS_KEY_PREFIX + subreddit.trim() + ":*");
                    bySubreddit.put(subreddit.trim(), subKeys != null ? (long) subKeys.size() : 0L);
                }

                return CacheStats.builder()
                        .totalPosts(totalPosts)
                        .availablePosts(totalPosts - usedPostIds.size())
                        .usedPosts(usedPostIds.size())
                        .postsBySubreddit(bySubreddit)
                        .cacheType("redis")
                        .build();
            } catch (Exception e) {
                log.warn("Redis unavailable for stats: {}", e.getMessage());
                redisAvailable = false;
            }
        }

        // Fallback to memory cache stats
        totalPosts = memoryCache.size();
        for (String subreddit : subreddits) {
            String prefix = subreddit.trim() + ":";
            long count = memoryCache.keySet().stream()
                    .filter(k -> k.startsWith(prefix))
                    .count();
            bySubreddit.put(subreddit.trim(), count);
        }

        return CacheStats.builder()
                .totalPosts(totalPosts)
                .availablePosts(getAvailablePostCount())
                .usedPosts(usedPostIds.size())
                .postsBySubreddit(bySubreddit)
                .cacheType("memory")
                .build();
    }

    @lombok.Data
    @lombok.Builder
    public static class CacheStats {
        private int totalPosts;
        private int availablePosts; // Posts not yet used for content
        private int usedPosts;
        private java.util.Map<String, Long> postsBySubreddit;
        private String cacheType; // "redis" or "memory"
    }
}
