package com.coffee.beansfinder.service;

import com.coffee.beansfinder.dto.RedditPost;
import com.coffee.beansfinder.entity.CoffeeNews;
import com.coffee.beansfinder.entity.CoffeeProduct;
import com.coffee.beansfinder.entity.InstagramPost;
import com.coffee.beansfinder.repository.CoffeeNewsRepository;
import com.coffee.beansfinder.repository.CoffeeProductRepository;
import com.coffee.beansfinder.repository.InstagramPostRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class InstagramContentService {

    private final RedditCrawlerService redditCrawlerService;
    private final CoffeeNewsRepository newsRepository;
    private final CoffeeProductRepository productRepository;
    private final InstagramPostRepository postRepository;
    private final GrokService grokService;
    private final DalleImageService dalleImageService;
    private final GeminiImageService geminiImageService;
    private final ObjectMapper objectMapper;

    @Value("${instagram.caption.prompt.path:prompts/instagram_caption_prompt.txt}")
    private String captionPromptPath;

    @Value("${instagram.reddit.posts.per.generation:10}")
    private int redditPostsPerGeneration;

    @Value("${instagram.image.provider:dalle}")
    private String imageProvider; // "dalle" or "gemini"

    private String captionPromptTemplate;

    @PostConstruct
    public void loadPromptTemplate() {
        try {
            ClassPathResource resource = new ClassPathResource(captionPromptPath);
            captionPromptTemplate = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            log.info("Loaded Instagram caption prompt template ({} chars)", captionPromptTemplate.length());
        } catch (Exception e) {
            log.error("Failed to load caption prompt template: {}", e.getMessage());
        }
    }

    /**
     * Bi-weekly scheduled generation: Tuesday and Friday at 10:00 AM
     */
    // TODO: Investigate network issues on production server - DNS/firewall blocking external domains
    // @Scheduled(cron = "${instagram.generation.cron:0 0 10 * * TUE,FRI}")
    public void biWeeklyGenerationJob() {
        log.info("=== Starting scheduled Instagram content generation ===");
        try {
            InstagramPost post = generatePost();
            log.info("Generated Instagram post: {} (status: {})", post.getId(), post.getStatus());
        } catch (Exception e) {
            log.error("Instagram content generation failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Generate a new Instagram post from Reddit + CoffeeNews + Product data
     */
    public InstagramPost generatePost() {
        return generatePost(false);
    }

    /**
     * Generate a new Instagram post with optional force flag
     * @param force Skip duplicate check if true
     */
    public InstagramPost generatePost(boolean force) {
        log.info("Starting Instagram post generation... (force={})", force);

        // Step 1: Get top Reddit posts from cache
        List<RedditPost> redditPosts = redditCrawlerService.getTopPosts(redditPostsPerGeneration);
        log.info("Got {} top Reddit posts (configured: {})", redditPosts.size(), redditPostsPerGeneration);

        // Step 2: Get recent coffee news
        LocalDateTime oneWeekAgo = LocalDateTime.now().minusDays(7);
        List<CoffeeNews> coffeeNews = newsRepository.findByFetchedAtAfter(oneWeekAgo)
                .stream()
                .limit(5)
                .collect(Collectors.toList());
        log.info("Got {} recent coffee news articles", coffeeNews.size());

        // Step 3: Get recent products from database (fewer, just for recommendations)
        LocalDateTime twoWeeksAgo = LocalDateTime.now().minusDays(14);
        List<CoffeeProduct> recentProducts = productRepository.findByCreatedDateAfterOrderByCreatedDateDesc(twoWeeksAgo)
                .stream()
                .limit(5)
                .collect(Collectors.toList());
        log.info("Got {} recent products", recentProducts.size());

        // Step 4: Build content hash for deduplication (skip if force=true)
        String contentHash = buildContentHash(redditPosts, coffeeNews);
        if (!force && postRepository.existsByContentHash(contentHash)) {
            log.warn("Content hash already exists, skipping duplicate generation");
            throw new RuntimeException("Duplicate content detected. Use force=true to override.");
        }

        // Step 5: Build prompt and call Grok for caption generation
        String prompt = buildPrompt(redditPosts, coffeeNews, recentProducts);
        String grokResponse;
        try {
            // Use higher temperature (0.7) for more creative meme-style content
            grokResponse = grokService.callGrokWithTemperature(
                    "You are a coffee trend analyst. Analyze the data and generate an Instagram post. Always respond with valid JSON only.",
                    prompt,
                    0.7
            );
        } catch (Exception e) {
            log.error("Grok API call failed: {}", e.getMessage());
            throw new RuntimeException("Failed to generate caption: " + e.getMessage());
        }

        // Step 5: Parse Grok response
        String caption;
        String imagePrompt;
        String sourceType = "mixed";

        String topic = "coffee";
        try {
            JsonNode json = objectMapper.readTree(grokResponse);
            caption = json.path("caption").asText();
            imagePrompt = json.path("imagePrompt").asText();
            topic = json.path("topic").asText("coffee");

            // Log the topic and top sources for visibility
            if (json.has("topSources")) {
                log.info("Top sources from Grok: {}", json.path("topSources"));
            }
            log.info("Generated post topic: {}", topic);

            // Append hashtags if separate (and not already in caption)
            if (json.has("hashtags") && !caption.contains("#")) {
                StringBuilder hashtagStr = new StringBuilder();
                for (JsonNode tag : json.path("hashtags")) {
                    hashtagStr.append("#").append(tag.asText()).append(" ");
                }
                caption = caption + "\n\n" + hashtagStr.toString().trim();
            }
        } catch (Exception e) {
            log.error("Failed to parse Grok response: {}", e.getMessage());
            throw new RuntimeException("Failed to parse caption response");
        }

        // Step 6: Build source IDs JSON (skip image generation - do it separately after prompt tuning)
        String sourceIds = buildSourceIds(redditPosts, coffeeNews);

        // Step 7: Save WITHOUT image (generate image separately to save cost during prompt tuning)
        InstagramPost post = InstagramPost.builder()
                .caption(caption)
                .imageUrl(null) // Image generated separately
                .imagePrompt(imagePrompt)
                .sourceType(sourceType)
                .sourceIds(sourceIds)
                .contentHash(contentHash)
                .generationCost(BigDecimal.valueOf(0.001)) // Grok cost only
                .status("draft")
                .build();

        post = postRepository.save(post);
        log.info("Instagram post saved with ID: {}", post.getId());

        // Mark Reddit posts as used to avoid duplicates next time
        List<String> usedPostIds = redditPosts.stream()
                .map(RedditPost::getId)
                .collect(Collectors.toList());
        redditCrawlerService.markPostsAsUsed(usedPostIds);

        return post;
    }

    /**
     * Regenerate image for an existing post
     */
    public InstagramPost regenerateImage(Long postId) {
        InstagramPost post = postRepository.findById(postId)
                .orElseThrow(() -> new RuntimeException("Post not found: " + postId));

        if (post.getImagePrompt() == null || post.getImagePrompt().isEmpty()) {
            throw new RuntimeException("No image prompt available for regeneration");
        }

        String newImageUrl;
        double cost;

        if ("gemini".equalsIgnoreCase(imageProvider) && geminiImageService.isConfigured()) {
            log.info("Using Gemini for image generation");
            newImageUrl = geminiImageService.generateImage(post.getImagePrompt());
            cost = geminiImageService.getEstimatedCost();
        } else {
            log.info("Using DALL-E for image generation");
            newImageUrl = dalleImageService.generateImage(post.getImagePrompt());
            cost = dalleImageService.getEstimatedCost();
        }

        post.setImageUrl(newImageUrl);
        post.setGenerationCost(post.getGenerationCost().add(BigDecimal.valueOf(cost)));

        return postRepository.save(post);
    }

    private String buildPrompt(List<RedditPost> redditPosts, List<CoffeeNews> coffeeNews, List<CoffeeProduct> products) {
        // Sort Reddit posts by score (highest first) for better context prioritization
        List<RedditPost> sortedPosts = redditPosts.stream()
                .sorted((a, b) -> Integer.compare(b.getScore(), a.getScore()))
                .collect(Collectors.toList());

        StringBuilder redditSection = new StringBuilder();
        for (RedditPost post : sortedPosts) {
            redditSection.append("### ").append(post.getTitle())
                    .append(" [").append(post.getPermalink()).append("]")
                    .append(" (score: ").append(post.getScore())
                    .append(", ").append(post.getNumComments()).append(" comments)\n");

            // Include post body
            if (post.getSelftext() != null && !post.getSelftext().isEmpty()) {
                String text = post.getSelftext();
                if (text.length() > 400) {
                    text = text.substring(0, 400) + "...";
                }
                redditSection.append("**Post:** ").append(text).append("\n");
            }

            // Include top comments - this is where the real insights are!
            if (post.getTopComments() != null && !post.getTopComments().isEmpty()) {
                redditSection.append("**Top Comments:**\n");
                for (RedditPost.RedditComment comment : post.getTopComments()) {
                    String body = comment.getBody();
                    if (body.length() > 300) {
                        body = body.substring(0, 300) + "...";
                    }
                    redditSection.append("- (score ").append(comment.getScore()).append(") ")
                            .append(body).append("\n");
                }
            }
            redditSection.append("\n");
        }

        StringBuilder newsSection = new StringBuilder();
        for (CoffeeNews news : coffeeNews) {
            newsSection.append("### ").append(news.getTitle())
                    .append(" (").append(news.getSource()).append(")\n");
            if (news.getSummary() != null && !news.getSummary().isEmpty()) {
                String summary = news.getSummary();
                if (summary.length() > 500) {
                    summary = summary.substring(0, 500) + "...";
                }
                newsSection.append(summary).append("\n\n");
            }
        }

        // Build product data section
        StringBuilder productSection = new StringBuilder();
        for (CoffeeProduct product : products) {
            productSection.append("- **").append(product.getProductName()).append("**");
            if (product.getBrand() != null) {
                productSection.append(" from ").append(product.getBrand().getName());
            }
            if (product.getOrigin() != null) {
                productSection.append(" (").append(product.getOrigin()).append(")");
            }
            if (product.getPrice() != null) {
                productSection.append(" - £").append(product.getPrice());
            }
            if (product.getRoastLevel() != null) {
                productSection.append(" [").append(product.getRoastLevel()).append("]");
            }
            productSection.append("\n");
        }

        return captionPromptTemplate
                .replace("{{REDDIT_POSTS}}", redditSection.toString())
                .replace("{{COFFEE_NEWS}}", newsSection.toString())
                .replace("{{PRODUCT_DATA}}", productSection.toString());
    }

    private String buildContentHash(List<RedditPost> redditPosts, List<CoffeeNews> coffeeNews) {
        try {
            StringBuilder content = new StringBuilder();
            for (RedditPost post : redditPosts) {
                content.append(post.getId());
            }
            for (CoffeeNews news : coffeeNews) {
                content.append(news.getId());
            }

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return String.valueOf(System.currentTimeMillis());
        }
    }

    private String buildSourceIds(List<RedditPost> redditPosts, List<CoffeeNews> coffeeNews) {
        try {
            java.util.Map<String, Object> sources = new java.util.HashMap<>();
            sources.put("reddit", redditPosts.stream().map(RedditPost::getId).collect(Collectors.toList()));
            sources.put("news", coffeeNews.stream().map(CoffeeNews::getId).collect(Collectors.toList()));
            return objectMapper.writeValueAsString(sources);
        } catch (Exception e) {
            return "{}";
        }
    }
}
