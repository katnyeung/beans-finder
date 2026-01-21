package com.coffee.beansfinder.service;

import com.coffee.beansfinder.dto.Crawl4AIResponse;
import com.coffee.beansfinder.dto.DigestAnalysis;
import com.coffee.beansfinder.dto.DigestAnalysis.DigestQuery;
import com.coffee.beansfinder.dto.DigestAnalysis.DigestQueryType;
import com.coffee.beansfinder.dto.MarketPulseDTO;
import com.coffee.beansfinder.dto.WeeklyDigestDTO;
import com.coffee.beansfinder.entity.CoffeeNews;
import com.coffee.beansfinder.entity.CoffeeProduct;
import com.coffee.beansfinder.entity.WeeklyDigest;
import com.coffee.beansfinder.repository.CoffeeNewsRepository;
import com.coffee.beansfinder.repository.CoffeeProductRepository;
import com.coffee.beansfinder.repository.WeeklyDigestRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.jsoup.Jsoup;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class DigestService {

    private final CoffeeNewsRepository newsRepository;
    private final WeeklyDigestRepository digestRepository;
    private final CoffeeProductRepository productRepository;
    private final GrokService grokService;
    private final PlaywrightScraperService playwrightService;
    private final CrawlClientService crawlClientService;
    private final ObjectMapper objectMapper;

    // RSS feed URLs - expanded sources for better coverage
    private static final List<String[]> RSS_FEEDS = List.of(
            new String[]{"perfect_daily_grind", "https://perfectdailygrind.com/feed/"},
            new String[]{"daily_coffee_news", "https://dailycoffeenews.com/feed/"},
            new String[]{"sprudge", "https://sprudge.com/feed"},
            new String[]{"barista_magazine", "https://www.baristamagazine.com/feed/"},
            new String[]{"roast_magazine", "https://dailycoffeenews.com/category/roast-magazine/feed/"}
    );

    private String analysisPromptTemplate;
    private String narrativePromptTemplate;
    private String rankingPromptTemplate;

    @PostConstruct
    public void loadPromptTemplates() {
        try {
            ClassPathResource analysisResource = new ClassPathResource("prompts/digest_analysis_prompt.txt");
            analysisPromptTemplate = StreamUtils.copyToString(analysisResource.getInputStream(), StandardCharsets.UTF_8);
            log.info("Loaded analysis prompt template ({} chars)", analysisPromptTemplate.length());

            ClassPathResource narrativeResource = new ClassPathResource("prompts/digest_narrative_prompt.txt");
            narrativePromptTemplate = StreamUtils.copyToString(narrativeResource.getInputStream(), StandardCharsets.UTF_8);
            log.info("Loaded narrative prompt template ({} chars)", narrativePromptTemplate.length());

            ClassPathResource rankingResource = new ClassPathResource("prompts/digest_ranking_prompt.txt");
            rankingPromptTemplate = StreamUtils.copyToString(rankingResource.getInputStream(), StandardCharsets.UTF_8);
            log.info("Loaded ranking prompt template ({} chars)", rankingPromptTemplate.length());
        } catch (Exception e) {
            log.error("Failed to load prompt templates: {}", e.getMessage());
        }
    }

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 30000; // 30 seconds between retries

    /**
     * Weekly scheduled job - runs every Saturday at 8:00 AM
     * Includes retry logic to handle Neon serverless cold starts
     */
    // TODO: Investigate network issues on production server - DNS/firewall blocking external domains
    // @Scheduled(cron = "0 0 8 * * SAT")
    public void generateWeeklyDigestScheduled() {
        log.info("=== Starting scheduled weekly digest generation ===");

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                // Warmup query to wake up Neon serverless database
                log.info("Attempt {}/{}: Warming up database connection...", attempt, MAX_RETRIES);
                warmupDatabase();

                // Generate digest
                WeeklyDigest digest = generateWeeklyDigest();
                log.info("Weekly digest generated successfully for week starting {}", digest.getWeekStart());
                return; // Success - exit retry loop

            } catch (Exception e) {
                log.error("Attempt {}/{} failed: {}", attempt, MAX_RETRIES, e.getMessage());

                if (attempt < MAX_RETRIES) {
                    log.info("Retrying in {} seconds...", RETRY_DELAY_MS / 1000);
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.error("Retry interrupted");
                        return;
                    }
                } else {
                    log.error("All {} attempts failed. Weekly digest generation aborted.", MAX_RETRIES, e);
                }
            }
        }
    }

    /**
     * Simple warmup query to wake up Neon serverless database from cold start
     * Uses a lightweight query with extended timeout tolerance
     */
    private void warmupDatabase() {
        try {
            // Simple count query to establish connection - Neon needs ~5-10s to wake up
            long count = productRepository.count();
            log.info("Database warmup successful - {} products in database", count);
        } catch (Exception e) {
            log.warn("Database warmup query failed, proceeding anyway: {}", e.getMessage());
            // Don't throw - let the main operation try with its own connection attempt
        }
    }

    /**
     * Manual trigger for digest generation (fetches fresh news from RSS)
     */
    public WeeklyDigest generateWeeklyDigest() {
        return generateWeeklyDigest(true, false);
    }

    /**
     * Manual trigger for digest generation
     * @param fetchFresh if true, fetches new RSS feeds; if false, uses existing news in database
     */
    public WeeklyDigest generateWeeklyDigest(boolean fetchFresh) {
        return generateWeeklyDigest(fetchFresh, false);
    }

    /**
     * Manual trigger for digest generation
     * @param fetchFresh if true, fetches new RSS feeds; if false, uses existing news in database
     * @param force if true, generates new draft even if one exists for this week
     */
    public WeeklyDigest generateWeeklyDigest(boolean fetchFresh, boolean force) {
        LocalDate today = LocalDate.now();
        LocalDate weekStart = today.with(DayOfWeek.MONDAY);
        LocalDate weekEnd = weekStart.plusDays(6);

        // Check if already exists (return latest version if not forcing)
        Optional<WeeklyDigest> existing = digestRepository.findLatestVersionByWeekStart(weekStart);
        if (existing.isPresent() && !force) {
            log.info("Digest already exists for week starting {} (v{}), returning existing",
                    weekStart, existing.get().getVersion());
            return existing.get();
        }

        // Get next version number for this week
        Integer maxVersion = digestRepository.findMaxVersionByWeekStart(weekStart);
        int nextVersion = maxVersion + 1;
        if (force) {
            log.info("Force mode: creating version {} for week starting {}", nextVersion, weekStart);
        }

        log.info("Generating digest for week {} to {} (fetchFresh={})", weekStart, weekEnd, fetchFresh);

        // Step 1: Get news articles (fetch fresh or use existing)
        List<CoffeeNews> newsArticles = fetchFresh ? fetchAndSaveNews() : getExistingNews();
        log.info("Using {} news articles", newsArticles.size());

        // Step 1b: Score and rank news articles by keyword relevance
        List<CoffeeNews> scoredNews = scoreAndRankNews(newsArticles);
        log.info("Keyword-scored news - top articles: {}",
                scoredNews.stream().limit(5).map(CoffeeNews::getTitle).collect(Collectors.joining(", ")));

        // Step 1c: LLM-based ranking to surface most interesting/important news
        List<CoffeeNews> rankedNews = rankNewsByInterest(scoredNews);
        log.info("LLM-ranked news - top articles: {}",
                rankedNews.stream().limit(5).map(CoffeeNews::getTitle).collect(Collectors.joining(", ")));

        // Step 2: LLM Call 2 - Analyze news to decide what queries to run
        DigestAnalysis analysis = analyzeNewsForQueries(rankedNews);
        log.info("Analysis decided on {} queries: {}",
                analysis.getQueries().size(),
                analysis.getQueries().stream().map(q -> q.getType().name()).collect(Collectors.joining(", ")));

        // Step 3: Execute queries based on LLM decision
        Map<String, Object> queryResults = executeQueries(analysis.getQueries());
        log.info("Query results: {}", queryResults.keySet());

        // Step 4: LLM Call 3 - Generate narrative (use top 12 ranked articles)
        List<CoffeeNews> topNews = rankedNews.stream().limit(12).collect(Collectors.toList());
        String narrative = generateNarrative(topNews, queryResults);
        log.info("Generated narrative ({} chars)", narrative.length());

        // Step 5: Save new digest version
        WeeklyDigest digest = WeeklyDigest.builder()
                .weekStart(weekStart)
                .weekEnd(weekEnd)
                .version(nextVersion)
                .narrative(narrative)
                .metrics(objectMapper.valueToTree(queryResults).toString())
                .newsArticleIds(objectMapper.valueToTree(
                        rankedNews.stream().map(CoffeeNews::getId).collect(Collectors.toList())).toString())
                .generationCost(new BigDecimal("0.003"))
                .status("draft")
                .build();

        log.info("Saving digest version {} for week {}", nextVersion, weekStart);
        return digestRepository.save(digest);
    }

    /**
     * Get existing news from database without fetching fresh RSS
     * Excludes articles already used in previous digest
     */
    public List<CoffeeNews> getExistingNews() {
        Set<Long> usedArticleIds = getPreviousDigestArticleIds();
        log.info("Excluding {} articles from previous digest", usedArticleIds.size());

        // Return articles from last 14 days (wider window for existing news)
        LocalDateTime twoWeeksAgo = LocalDateTime.now().minusDays(14);
        List<CoffeeNews> allRecent = newsRepository.findByFetchedAtAfter(twoWeeksAgo);

        log.info("Found {} existing news articles in database", allRecent.size());

        return allRecent.stream()
                .filter(article -> !usedArticleIds.contains(article.getId()))
                .collect(Collectors.toList());
    }

    /**
     * Fetch RSS feeds and save new articles, then return all recent articles
     * Excludes articles already used in previous digest to prevent content repeats
     */
    public List<CoffeeNews> fetchAndSaveNews() {
        // First, fetch and save any new articles from all RSS feeds
        for (String[] feed : RSS_FEEDS) {
            fetchRssFeed(feed[0], feed[1]);
        }

        // Get article IDs used in the previous digest
        Set<Long> usedArticleIds = getPreviousDigestArticleIds();
        log.info("Excluding {} articles from previous digest", usedArticleIds.size());

        // Return articles from last 7 days, excluding already-used ones
        LocalDateTime oneWeekAgo = LocalDateTime.now().minusDays(7);
        List<CoffeeNews> allRecent = newsRepository.findByFetchedAtAfter(oneWeekAgo);

        return allRecent.stream()
                .filter(article -> !usedArticleIds.contains(article.getId()))
                .collect(Collectors.toList());
    }

    /**
     * Get article IDs from ALL previous weeks' digests to avoid repeating content
     * Excludes current week's digests so we only look at truly previous weeks
     */
    private Set<Long> getPreviousDigestArticleIds() {
        Set<Long> usedIds = new HashSet<>();
        LocalDate currentWeekStart = LocalDate.now().with(DayOfWeek.MONDAY);

        try {
            // Get all digests and filter to previous weeks only
            List<WeeklyDigest> allDigests = digestRepository.findAllOrderByWeekStartDesc();

            for (WeeklyDigest digest : allDigests) {
                // Skip current week's digests - we want to exclude PREVIOUS weeks only
                if (digest.getWeekStart().equals(currentWeekStart)) {
                    continue;
                }

                if (digest.getNewsArticleIds() != null) {
                    try {
                        List<Long> ids = objectMapper.readValue(
                                digest.getNewsArticleIds(),
                                new TypeReference<List<Long>>() {});
                        usedIds.addAll(ids);
                    } catch (Exception e) {
                        log.debug("Failed to parse article IDs for digest {}: {}", digest.getId(), e.getMessage());
                    }
                }
            }

            log.info("Found {} article IDs from previous weeks' digests", usedIds.size());
        } catch (Exception e) {
            log.warn("Failed to get previous digest article IDs: {}", e.getMessage());
        }
        return usedIds;
    }

    private List<CoffeeNews> fetchRssFeed(String source, String feedUrl) {
        List<CoffeeNews> articles = new ArrayList<>();
        LocalDateTime oneWeekAgo = LocalDateTime.now().minusDays(7);

        try {
            log.info("Fetching RSS feed from: {}", feedUrl);

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();

            URL url = new URL(feedUrl);
            try (InputStream is = url.openStream()) {
                Document doc = builder.parse(is);
                NodeList items = doc.getElementsByTagName("item");

                log.info("Found {} items in RSS feed", items.getLength());

                for (int i = 0; i < Math.min(items.getLength(), 20); i++) { // Limit to 20 per feed
                    Element item = (Element) items.item(i);

                    String title = getElementText(item, "title");
                    String link = getElementText(item, "link");
                    String pubDateStr = getElementText(item, "pubDate");
                    String description = getElementText(item, "description");

                    if (title == null || link == null) continue;

                    // Check if already exists
                    if (newsRepository.existsByLink(link)) {
                        continue;
                    }

                    // Parse publish date
                    LocalDateTime publishedAt = parsePubDate(pubDateStr);

                    // Skip if older than 1 week
                    if (publishedAt != null && publishedAt.isBefore(oneWeekAgo)) {
                        continue;
                    }

                    // Clean description - strip HTML tags and limit to 500 chars
                    String summary = cleanDescription(description);

                    // Create content hash
                    String contentHash = hashString(title + link);

                    CoffeeNews news = CoffeeNews.builder()
                            .source(source)
                            .title(title)
                            .link(link)
                            .summary(summary)
                            .publishedAt(publishedAt)
                            .contentHash(contentHash)
                            .build();

                    news = newsRepository.save(news);
                    articles.add(news);
                    log.info("Saved news: {}", title);

                    // Fetch full article content - try Crawl4AI first, then Playwright, then Jsoup
                    try {
                        String articleContent = null;

                        // Try Crawl4AI first (best markdown extraction)
                        if (crawlClientService.isEnabled()) {
                            Crawl4AIResponse crawlResponse = crawlClientService.crawlSmart(link);
                            if (crawlResponse != null && crawlResponse.isSuccess()
                                    && crawlResponse.getMarkdown() != null
                                    && crawlResponse.getMarkdown().length() > 200) {
                                articleContent = crawlResponse.getMarkdown();
                                log.info("Crawl4AI fetched article: {} chars", articleContent.length());
                            }
                        }

                        // Fallback to Playwright if Crawl4AI fails
                        if (articleContent == null || articleContent.length() < 200) {
                            log.info("Trying Playwright fallback for: {}", link);
                            articleContent = playwrightService.extractArticleContent(link);
                        }

                        // Fallback to Jsoup if Playwright also fails
                        if (articleContent == null || articleContent.length() < 200) {
                            log.info("Trying Jsoup fallback for: {}", link);
                            articleContent = fetchArticleWithJsoup(link);
                        }

                        if (articleContent != null && articleContent.length() > (summary != null ? summary.length() : 0)) {
                            news.setSummary(articleContent);
                            newsRepository.save(news);
                            log.info("Updated with full article content ({} chars)", articleContent.length());
                        }
                    } catch (Exception e) {
                        log.warn("Failed to fetch article content for {}: {}", title, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch RSS feed {}: {}", feedUrl, e.getMessage());
        }

        return articles;
    }

    /**
     * Score and rank news articles by relevance to coffee topics
     * Higher scores = more relevant to our audience
     *
     * Scoring factors:
     * - Origin keywords (coffee-producing countries)
     * - Process keywords (fermentation, processing methods)
     * - Trend keywords (market-relevant terms)
     * - Variety keywords (coffee cultivars)
     * - Urgent keywords (breaking news indicators) - HIGH BONUS
     * - Negative keywords (press releases, promotions) - PENALTY
     * - Source authority (trusted sources get bonus)
     * - Recency boost (articles < 3 days old)
     */
    private List<CoffeeNews> scoreAndRankNews(List<CoffeeNews> articles) {
        // Positive keywords with scores
        Map<String, Integer> originKeywords = Map.ofEntries(
                Map.entry("ethiopia", 10), Map.entry("colombian", 10), Map.entry("brazil", 10),
                Map.entry("kenya", 8), Map.entry("guatemala", 8), Map.entry("costa rica", 8),
                Map.entry("burundi", 7), Map.entry("rwanda", 7), Map.entry("yemen", 9),
                Map.entry("panama", 8), Map.entry("gesha", 9), Map.entry("geisha", 9)
        );

        Map<String, Integer> processKeywords = Map.of(
                "anaerobic", 8, "natural", 6, "washed", 5, "honey", 6,
                "ferment", 7, "carbonic", 7, "experimental", 6
        );

        Map<String, Integer> trendKeywords = Map.of(
                "price", 8, "shortage", 9, "harvest", 7, "crop", 6,
                "climate", 7, "sustainability", 6, "award", 7, "championship", 8,
                "trend", 6, "specialty", 5
        );

        Map<String, Integer> varietyKeywords = Map.of(
                "gesha", 9, "bourbon", 6, "typica", 5, "sl28", 7,
                "pacamara", 7, "caturra", 5, "heirloom", 6
        );

        // URGENT keywords - breaking news indicators (HIGH bonus)
        Map<String, Integer> urgentKeywords = Map.ofEntries(
                Map.entry("shortage", 15),
                Map.entry("crisis", 15),
                Map.entry("frost", 12),
                Map.entry("drought", 12),
                Map.entry("price surge", 12),
                Map.entry("supply chain", 10),
                Map.entry("recall", 15),
                Map.entry("warning", 10),
                Map.entry("emergency", 12),
                Map.entry("record high", 10),
                Map.entry("record low", 10),
                Map.entry("world champion", 12),
                Map.entry("cup of excellence", 10)
        );

        // NEGATIVE keywords - press releases, promotions (PENALTY)
        Map<String, Integer> negativeKeywords = Map.ofEntries(
                Map.entry("press release", -10),
                Map.entry("announces", -5),
                Map.entry("partnership", -5),
                Map.entry("sponsored", -15),
                Map.entry("webinar", -8),
                Map.entry("event registration", -10),
                Map.entry("now available", -5),
                Map.entry("launches new", -3),
                Map.entry("proud to announce", -8),
                Map.entry("excited to share", -6),
                Map.entry("sign up", -8),
                Map.entry("register now", -10),
                Map.entry("free download", -8)
        );

        // SOURCE AUTHORITY weights (trusted sources get bonus)
        Map<String, Integer> sourceBonus = Map.of(
                "daily_coffee_news", 5,      // Primary industry source
                "perfect_daily_grind", 4,
                "roast_magazine", 4,
                "sprudge", 3,
                "barista_magazine", 3
        );

        // Recency threshold (articles < 3 days old get bonus)
        LocalDateTime threeDaysAgo = LocalDateTime.now().minusDays(3);

        // Score each article
        List<Map.Entry<CoffeeNews, Integer>> scoredArticles = articles.stream()
                .map(article -> {
                    int score = 0;
                    String text = ((article.getTitle() != null ? article.getTitle() : "") + " " +
                            (article.getSummary() != null ? article.getSummary() : "")).toLowerCase();

                    // Score based on positive keyword matches
                    for (Map.Entry<String, Integer> kw : originKeywords.entrySet()) {
                        if (text.contains(kw.getKey())) score += kw.getValue();
                    }
                    for (Map.Entry<String, Integer> kw : processKeywords.entrySet()) {
                        if (text.contains(kw.getKey())) score += kw.getValue();
                    }
                    for (Map.Entry<String, Integer> kw : trendKeywords.entrySet()) {
                        if (text.contains(kw.getKey())) score += kw.getValue();
                    }
                    for (Map.Entry<String, Integer> kw : varietyKeywords.entrySet()) {
                        if (text.contains(kw.getKey())) score += kw.getValue();
                    }

                    // Add urgent keyword bonus (breaking news)
                    for (Map.Entry<String, Integer> kw : urgentKeywords.entrySet()) {
                        if (text.contains(kw.getKey())) score += kw.getValue();
                    }

                    // Apply negative keyword penalties
                    for (Map.Entry<String, Integer> kw : negativeKeywords.entrySet()) {
                        if (text.contains(kw.getKey())) score += kw.getValue();
                    }

                    // Source authority bonus
                    if (article.getSource() != null) {
                        score += sourceBonus.getOrDefault(article.getSource().toLowerCase(), 0);
                    }

                    // Recency boost (+8 for articles < 3 days old)
                    if (article.getPublishedAt() != null && article.getPublishedAt().isAfter(threeDaysAgo)) {
                        score += 8;
                    }

                    // Bonus for longer content (more detailed articles)
                    if (article.getSummary() != null && article.getSummary().length() > 500) {
                        score += 5;
                    }

                    return Map.entry(article, score);
                })
                .sorted((a, b) -> b.getValue().compareTo(a.getValue())) // Sort descending by score
                .collect(Collectors.toList());

        log.info("News scores (with urgency/penalties): {}", scoredArticles.stream()
                .limit(5)
                .map(e -> e.getKey().getTitle() + "=" + e.getValue())
                .collect(Collectors.joining(", ")));

        return scoredArticles.stream()
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * LLM-based news ranking to surface the most interesting/important articles
     * Uses Grok to evaluate interestingness based on editorial criteria
     *
     * @param scoredNews Pre-scored articles (keyword scoring already applied)
     * @return Reordered list with most interesting articles first
     */
    private List<CoffeeNews> rankNewsByInterest(List<CoffeeNews> scoredNews) {
        if (rankingPromptTemplate == null) {
            log.warn("Ranking prompt template not loaded, using keyword-scored order");
            return scoredNews;
        }

        // Take top 20 keyword-scored articles for LLM ranking (cost optimization)
        List<CoffeeNews> candidateArticles = scoredNews.stream().limit(20).collect(Collectors.toList());

        // Build news list with IDs for prompt
        StringBuilder newsList = new StringBuilder();
        for (CoffeeNews article : candidateArticles) {
            newsList.append("ID: ").append(article.getId()).append("\n")
                    .append("Title: ").append(article.getTitle()).append("\n")
                    .append("Source: ").append(article.getSource()).append("\n");
            if (article.getSummary() != null && !article.getSummary().isEmpty()) {
                String content = article.getSummary();
                if (content.length() > 500) {
                    content = content.substring(0, 500) + "...";
                }
                newsList.append("Content: ").append(content).append("\n");
            }
            newsList.append("\n");
        }

        String prompt = rankingPromptTemplate.replace("{{NEWS}}", newsList.toString());

        try {
            // Call Grok with low temperature for consistent ranking
            String response = grokService.callGrokWithTemperature(
                    "You are a coffee news editor. Respond with valid JSON only.",
                    prompt,
                    0.2  // Low temperature for deterministic ranking
            );

            // Parse ranking result
            NewsRankingResult result = objectMapper.readValue(response, NewsRankingResult.class);

            if (result == null || result.getRankings() == null || result.getRankings().isEmpty()) {
                log.warn("Empty ranking result from LLM, using keyword-scored order");
                return scoredNews;
            }

            // Log top themes identified by LLM
            if (result.getTopThemes() != null && !result.getTopThemes().isEmpty()) {
                log.info("LLM identified themes: {}", String.join(", ", result.getTopThemes()));
            }

            // Build ID-to-article map for reordering
            Map<Long, CoffeeNews> articleMap = candidateArticles.stream()
                    .collect(Collectors.toMap(CoffeeNews::getId, a -> a, (a, b) -> a));

            // Create reordered list based on LLM rankings
            List<CoffeeNews> reorderedNews = new ArrayList<>();
            Set<Long> skipIds = result.getSkipArticles() != null
                    ? new HashSet<>(result.getSkipArticles())
                    : new HashSet<>();

            // Add articles in LLM-ranked order
            for (NewsRankingResult.ArticleRanking ranking : result.getRankings()) {
                CoffeeNews article = articleMap.get(ranking.getId());
                if (article != null && !skipIds.contains(ranking.getId())) {
                    reorderedNews.add(article);
                    log.debug("Rank {}: {} - {}", ranking.getRank(), article.getTitle(), ranking.getReason());
                }
            }

            // Log skipped articles
            if (!skipIds.isEmpty()) {
                log.info("LLM flagged {} articles to skip (promotional/non-newsworthy)", skipIds.size());
            }

            // Add any remaining articles not in the ranking (fallback)
            for (CoffeeNews article : candidateArticles) {
                if (!reorderedNews.contains(article) && !skipIds.contains(article.getId())) {
                    reorderedNews.add(article);
                }
            }

            log.info("LLM reranked news - top 3: {}",
                    reorderedNews.stream().limit(3).map(CoffeeNews::getTitle).collect(Collectors.joining(", ")));

            return reorderedNews;

        } catch (Exception e) {
            log.error("Failed to LLM-rank news: {}, using keyword-scored order", e.getMessage());
            return scoredNews;
        }
    }

    /**
     * DTO for LLM ranking response
     */
    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class NewsRankingResult {
        private List<ArticleRanking> rankings;
        private List<String> topThemes;
        private List<Long> skipArticles;

        @lombok.Data
        @lombok.NoArgsConstructor
        @lombok.AllArgsConstructor
        public static class ArticleRanking {
            private Long id;
            private Integer rank;
            private String reason;
        }
    }

    private String getElementText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent();
        }
        return null;
    }

    private LocalDateTime parsePubDate(String pubDateStr) {
        if (pubDateStr == null) return null;
        try {
            // RSS format: "Mon, 16 Dec 2024 08:00:00 +0000"
            DateTimeFormatter formatter = DateTimeFormatter.RFC_1123_DATE_TIME;
            ZonedDateTime zdt = ZonedDateTime.parse(pubDateStr, formatter);
            return zdt.toLocalDateTime();
        } catch (Exception e) {
            log.debug("Failed to parse date: {}", pubDateStr);
            return LocalDateTime.now();
        }
    }

    private String hashString(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return input.hashCode() + "";
        }
    }

    /**
     * Fetch article content using Jsoup (simpler than Playwright, less likely to be blocked)
     */
    private String fetchArticleWithJsoup(String url) {
        try {
            org.jsoup.nodes.Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.5")
                    .header("Accept-Encoding", "gzip, deflate")
                    .header("Connection", "keep-alive")
                    .header("Upgrade-Insecure-Requests", "1")
                    .referrer("https://www.google.com/")
                    .followRedirects(true)
                    .timeout(15000)
                    .get();

            StringBuilder content = new StringBuilder();

            // Get title
            String title = doc.select("h1").first() != null ? doc.select("h1").first().text() : "";
            if (!title.isEmpty()) {
                content.append("TITLE: ").append(title).append("\n\n");
            }

            // Get meta description
            String metaDesc = doc.select("meta[name=description]").attr("content");
            if (metaDesc.isEmpty()) {
                metaDesc = doc.select("meta[property=og:description]").attr("content");
            }
            if (!metaDesc.isEmpty()) {
                content.append("SUMMARY: ").append(metaDesc).append("\n\n");
            }

            // Try multiple selectors for article content
            String[] selectors = {
                    ".entry-content p",
                    ".post-content p",
                    ".article-content p",
                    ".td-post-content p",
                    "article p",
                    ".content p"
            };

            for (String selector : selectors) {
                org.jsoup.select.Elements paragraphs = doc.select(selector);
                if (paragraphs.size() > 2) {
                    StringBuilder articleText = new StringBuilder();
                    for (org.jsoup.nodes.Element p : paragraphs) {
                        String text = p.text().trim();
                        if (text.length() > 30) {
                            articleText.append(text).append(" ");
                        }
                    }
                    if (articleText.length() > 200) {
                        content.append(articleText.toString().trim());
                        break;
                    }
                }
            }

            String result = content.toString().trim();

            // Limit to 5000 chars
            if (result.length() > 5000) {
                result = result.substring(0, 5000) + "...";
            }

            log.info("Jsoup extracted {} chars from {}", result.length(), url);
            return result.length() > 100 ? result : null;

        } catch (Exception e) {
            log.warn("Jsoup failed to fetch {}: {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * Clean HTML from RSS description and limit length
     */
    private String cleanDescription(String description) {
        if (description == null || description.isEmpty()) {
            return null;
        }

        // Strip HTML tags
        String clean = description
                .replaceAll("<[^>]*>", "")  // Remove HTML tags
                .replaceAll("&[a-zA-Z]+;", " ")  // Remove HTML entities like &nbsp;
                .replaceAll("&#\\d+;", " ")  // Remove numeric HTML entities
                .replaceAll("\\[&#8230;\\]", "...")  // Replace ellipsis entity
                .replaceAll("\\s+", " ")  // Normalize whitespace
                .trim();

        // Remove "The post ... appeared first on ..." boilerplate
        int postIndex = clean.indexOf("The post ");
        if (postIndex > 50) {
            clean = clean.substring(0, postIndex).trim();
        }

        // Limit to 500 chars
        if (clean.length() > 500) {
            clean = clean.substring(0, 497) + "...";
        }

        return clean.isEmpty() ? null : clean;
    }

    /**
     * LLM Call 1: Analyze news headlines and content to decide what queries to run
     * Uses Grok for better reasoning capabilities
     */
    private DigestAnalysis analyzeNewsForQueries(List<CoffeeNews> news) {
        if (analysisPromptTemplate == null) {
            log.warn("Analysis prompt template not loaded, using default queries");
            return getDefaultAnalysis();
        }

        // Build news summary for prompt - include title AND content
        StringBuilder newsSummary = new StringBuilder();
        for (CoffeeNews article : news) {
            newsSummary.append("### ").append(article.getTitle()).append("\n");
            if (article.getSummary() != null && !article.getSummary().isEmpty()) {
                // Truncate long content to keep prompt size reasonable
                String content = article.getSummary();
                if (content.length() > 1000) {
                    content = content.substring(0, 1000) + "...";
                }
                newsSummary.append(content).append("\n\n");
            } else {
                newsSummary.append("(No content available)\n\n");
            }
        }

        // Use simple replacement instead of String.format to avoid % issues
        String prompt = analysisPromptTemplate.replace("{{NEWS}}", newsSummary.toString());

        try {
            // Use low temperature (0.1) for more deterministic, data-focused output
            String response = grokService.callGrokWithTemperature(
                "You are a coffee market analyst. Always respond with valid JSON only.",
                prompt,
                0.1
            );
            return objectMapper.readValue(response, DigestAnalysis.class);
        } catch (Exception e) {
            log.error("Failed to analyze news: {}", e.getMessage());
            return getDefaultAnalysis();
        }
    }

    private DigestAnalysis getDefaultAnalysis() {
        return DigestAnalysis.builder()
                .queries(List.of(
                        DigestAnalysis.DigestQuery.builder()
                                .type(DigestQueryType.NEW_PRODUCTS_SUMMARY)
                                .params(Map.of())
                                .build(),
                        DigestAnalysis.DigestQuery.builder()
                                .type(DigestQueryType.ORIGIN_ACTIVITY)
                                .params(Map.of("origins", List.of("Ethiopia", "Colombia", "Brazil")))
                                .build(),
                        DigestAnalysis.DigestQuery.builder()
                                .type(DigestQueryType.PROCESS_TREND)
                                .params(Map.of())
                                .build()
                ))
                .reasoning("Default queries - analysis failed")
                .build();
    }

    /**
     * Execute queries based on LLM decision
     * Includes base metrics needed for insights charts
     */
    private Map<String, Object> executeQueries(List<DigestQuery> queries) {
        Map<String, Object> results = new HashMap<>();

        // Base metrics for insights charts (needed for UI)
        results.put("originActivity", queryOriginActivity(Map.of()));
        results.put("topFlavors", queryTopFlavors());
        results.put("processTrend", queryProcessTrend());
        results.put("newProducts", queryNewProducts());

        // Execute LLM-decided queries (may override some)
        for (DigestQuery query : queries) {
            try {
                switch (query.getType()) {
                    case NEW_PRODUCTS_SUMMARY -> results.put("newProducts", queryNewProducts());
                    case ORIGIN_ACTIVITY -> results.put("originActivity", queryOriginActivity(query.getParams()));
                    case ORIGIN_PRICE_TREND -> results.put("originPrices", queryOriginPrices());
                    case PROCESS_TREND -> results.put("processTrend", queryProcessTrend());
                    case BRAND_NEW_PRODUCTS -> results.put("brandProducts", queryBrandNewProducts(query.getParams()));
                    case PRICE_CHANGES -> results.put("priceChanges", queryPriceChanges());
                    case VARIETY_TREND -> results.put("varietyTrend", queryVarietyTrend());
                    default -> log.warn("Unknown query type: {}", query.getType());
                }
            } catch (Exception e) {
                log.error("Failed to execute query {}: {}", query.getType(), e.getMessage());
            }
        }

        return results;
    }

    private Map<String, Object> queryNewProducts() {
        LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);
        List<CoffeeProduct> products = productRepository.findByDeletedAtIsNull();

        long totalActive = products.size();
        long newThisWeek = products.stream()
                .filter(p -> p.getCreatedDate() != null && p.getCreatedDate().isAfter(weekAgo))
                .count();

        return Map.of(
                "totalActive", totalActive,
                "newThisWeek", newThisWeek
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> queryOriginActivity(Map<String, Object> params) {
        List<CoffeeProduct> products = productRepository.findByDeletedAtIsNull();

        // Group by origin
        Map<String, Long> originCounts = products.stream()
                .filter(p -> p.getOrigin() != null)
                .collect(Collectors.groupingBy(CoffeeProduct::getOrigin, Collectors.counting()));

        // If specific origins requested, filter
        List<String> requestedOrigins = (List<String>) params.get("origins");
        if (requestedOrigins != null && !requestedOrigins.isEmpty()) {
            originCounts = originCounts.entrySet().stream()
                    .filter(e -> requestedOrigins.stream()
                            .anyMatch(o -> e.getKey().toLowerCase().contains(o.toLowerCase())))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }

        // Sort by count
        List<Map<String, Object>> topOrigins = originCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .map(e -> Map.of("origin", (Object) e.getKey(), "count", (Object) e.getValue()))
                .collect(Collectors.toList());

        return Map.of("topOrigins", topOrigins);
    }

    private Map<String, Object> queryOriginPrices() {
        List<CoffeeProduct> products = productRepository.findByDeletedAtIsNull();

        Map<String, Double> avgPrices = products.stream()
                .filter(p -> p.getOrigin() != null && p.getPrice() != null)
                .collect(Collectors.groupingBy(
                        CoffeeProduct::getOrigin,
                        Collectors.averagingDouble(p -> p.getPrice().doubleValue())));

        List<Map<String, Object>> pricesByOrigin = avgPrices.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(10)
                .map(e -> Map.of(
                        "origin", (Object) e.getKey(),
                        "avgPrice", (Object) String.format("%.2f", e.getValue())))
                .collect(Collectors.toList());

        return Map.of("pricesByOrigin", pricesByOrigin);
    }

    private Map<String, Object> queryProcessTrend() {
        List<CoffeeProduct> products = productRepository.findByDeletedAtIsNull();

        Map<String, Long> processCounts = products.stream()
                .filter(p -> p.getProcess() != null)
                .collect(Collectors.groupingBy(
                        p -> normalizeProcess(p.getProcess()),
                        Collectors.counting()));

        List<Map<String, Object>> topProcesses = processCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .map(e -> Map.of("process", (Object) e.getKey(), "count", (Object) e.getValue()))
                .collect(Collectors.toList());

        return Map.of("topProcesses", topProcesses);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> queryBrandNewProducts(Map<String, Object> params) {
        LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);
        List<CoffeeProduct> products = productRepository.findByDeletedAtIsNull();

        // New products this week, grouped by brand
        Map<String, Long> brandCounts = products.stream()
                .filter(p -> p.getCreatedDate() != null && p.getCreatedDate().isAfter(weekAgo))
                .filter(p -> p.getBrand() != null)
                .collect(Collectors.groupingBy(
                        p -> p.getBrand().getName(),
                        Collectors.counting()));

        List<Map<String, Object>> topBrands = brandCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .map(e -> Map.of("brand", (Object) e.getKey(), "newProducts", (Object) e.getValue()))
                .collect(Collectors.toList());

        return Map.of("topBrands", topBrands);
    }

    private Map<String, Object> queryPriceChanges() {
        // For MVP, just return average price
        List<CoffeeProduct> products = productRepository.findByDeletedAtIsNull();

        double avgPrice = products.stream()
                .filter(p -> p.getPrice() != null)
                .mapToDouble(p -> p.getPrice().doubleValue())
                .average()
                .orElse(0.0);

        return Map.of("averagePrice", String.format("%.2f", avgPrice));
    }

    private Map<String, Object> queryTopFlavors() {
        List<CoffeeProduct> products = productRepository.findByDeletedAtIsNull();

        // Count flavors from tastingNotesJson (stored as JSON string)
        Map<String, Long> flavorCounts = new HashMap<>();

        for (CoffeeProduct product : products) {
            String notesJson = product.getTastingNotesJson();
            if (notesJson != null && !notesJson.isEmpty()) {
                try {
                    // Parse JSON array string
                    List<String> notes = objectMapper.readValue(notesJson,
                            new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
                    for (String note : notes) {
                        if (note != null && !note.isEmpty()) {
                            String normalized = note.toLowerCase().trim();
                            flavorCounts.merge(normalized, 1L, Long::sum);
                        }
                    }
                } catch (Exception e) {
                    // Skip if parsing fails
                }
            }
        }

        // Sort by count and take top 10
        List<Map<String, Object>> topFlavors = flavorCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .map(e -> Map.of(
                        "flavor", (Object) capitalize(e.getKey()),
                        "count", (Object) e.getValue()))
                .collect(Collectors.toList());

        return Map.of("topFlavors", topFlavors);
    }

    private Map<String, Object> queryVarietyTrend() {
        List<CoffeeProduct> products = productRepository.findByDeletedAtIsNull();

        Map<String, Long> varietyCounts = products.stream()
                .filter(p -> p.getVariety() != null && !p.getVariety().isEmpty())
                .collect(Collectors.groupingBy(
                        p -> p.getVariety().toLowerCase().trim(),
                        Collectors.counting()));

        List<Map<String, Object>> topVarieties = varietyCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .map(e -> Map.of(
                        "variety", (Object) capitalize(e.getKey()),
                        "count", (Object) e.getValue()))
                .collect(Collectors.toList());

        return Map.of("topVarieties", topVarieties);
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private String normalizeProcess(String process) {
        if (process == null) return "unknown";
        String lower = process.toLowerCase();
        if (lower.contains("washed")) return "Washed";
        if (lower.contains("natural")) return "Natural";
        if (lower.contains("honey")) return "Honey";
        if (lower.contains("anaerobic")) return "Anaerobic";
        return process;
    }

    private Map<String, Object> queryRoastLevelTrend() {
        List<CoffeeProduct> products = productRepository.findByDeletedAtIsNull();

        Map<String, Long> roastCounts = products.stream()
                .filter(p -> p.getRoastLevel() != null && !p.getRoastLevel().isEmpty())
                .collect(Collectors.groupingBy(
                        p -> capitalize(p.getRoastLevel().toLowerCase().trim()),
                        Collectors.counting()));

        List<Map<String, Object>> roastLevels = roastCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> Map.of(
                        "roastLevel", (Object) e.getKey(),
                        "count", (Object) e.getValue()))
                .collect(Collectors.toList());

        return Map.of("roastLevels", roastLevels);
    }

    /**
     * LLM Call 2: Generate narrative from news + query results
     * Uses Grok for natural language generation
     */
    private String generateNarrative(List<CoffeeNews> news, Map<String, Object> queryResults) {
        if (narrativePromptTemplate == null) {
            log.warn("Narrative prompt template not loaded");
            return "Weekly digest generation failed - template not loaded.";
        }

        // Build news summary with full content
        StringBuilder newsSummary = new StringBuilder();
        for (CoffeeNews article : news) {
            newsSummary.append("### ").append(article.getTitle())
                    .append(" (").append(article.getSource()).append(")\n");
            if (article.getSummary() != null && !article.getSummary().isEmpty()) {
                String content = article.getSummary();
                if (content.length() > 800) {
                    content = content.substring(0, 800) + "...";
                }
                newsSummary.append(content).append("\n\n");
            }
        }

        // Build data summary
        String dataSummary;
        try {
            dataSummary = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(queryResults);
        } catch (Exception e) {
            dataSummary = queryResults.toString();
        }

        // Use simple replacement instead of String.format to avoid % issues
        String prompt = narrativePromptTemplate
                .replace("{{NEWS}}", newsSummary.toString())
                .replace("{{DATA}}", dataSummary);

        try {
            // Use higher temperature (0.7) for variety between versions
            String response = grokService.callGrokWithTemperature(
                "You are a coffee writer. Use ONLY the actual numbers from the DATA section. Always respond with valid JSON only.",
                prompt,
                0.7
            );
            JsonNode json = objectMapper.readTree(response);
            return json.has("narrative") ? json.get("narrative").asText() : response;
        } catch (Exception e) {
            log.error("Failed to generate narrative: {}", e.getMessage());
            return "Weekly digest generation encountered an error.";
        }
    }

    // ==================== PUBLIC API METHODS ====================

    /**
     * Get latest published digest
     */
    public Optional<WeeklyDigestDTO> getLatestDigest() {
        return digestRepository.findLatestByStatus("published")
                .map(this::toDTO);
    }

    /**
     * Get digest by week start date (returns latest version - published preferred, then draft)
     */
    public Optional<WeeklyDigestDTO> getDigestByWeek(LocalDate weekStart) {
        // Try to find published first
        List<WeeklyDigest> digests = digestRepository.findByWeekStartOrderByVersionDesc(weekStart);
        if (digests.isEmpty()) {
            return Optional.empty();
        }

        // Prefer published, otherwise return latest draft
        Optional<WeeklyDigest> published = digests.stream()
                .filter(d -> "published".equals(d.getStatus()))
                .findFirst();

        return Optional.of(toDTO(published.orElse(digests.get(0))));
    }

    /**
     * Get all digests for archive (published only, or latest draft if no published)
     */
    public List<WeeklyDigestDTO> getArchive() {
        // Get all digests grouped by week, preferring published versions
        List<WeeklyDigest> all = digestRepository.findAllOrderByWeekStartDesc();

        // Group by weekStart, keep published or latest draft for each week
        Map<LocalDate, WeeklyDigest> byWeek = new LinkedHashMap<>();
        for (WeeklyDigest d : all) {
            WeeklyDigest existing = byWeek.get(d.getWeekStart());
            if (existing == null) {
                byWeek.put(d.getWeekStart(), d);
            } else if ("published".equals(d.getStatus()) && !"published".equals(existing.getStatus())) {
                // Replace draft with published
                byWeek.put(d.getWeekStart(), d);
            } else if (d.getVersion() > existing.getVersion() && existing.getStatus().equals(d.getStatus())) {
                // Same status, keep higher version
                byWeek.put(d.getWeekStart(), d);
            }
        }

        return byWeek.values().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * Publish a draft digest
     */
    public WeeklyDigest publishDigest(Long digestId) {
        WeeklyDigest digest = digestRepository.findById(digestId)
                .orElseThrow(() -> new RuntimeException("Digest not found: " + digestId));
        digest.setStatus("published");
        return digestRepository.save(digest);
    }

    /**
     * Get market pulse for homepage widget
     */
    public MarketPulseDTO getMarketPulse() {
        List<CoffeeProduct> products = productRepository.findByDeletedAtIsNull();
        LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);

        int totalProducts = products.size();
        int newThisWeek = (int) products.stream()
                .filter(p -> p.getCreatedDate() != null && p.getCreatedDate().isAfter(weekAgo))
                .count();

        // Top origins
        List<MarketPulseDTO.TrendingItem> trendingOrigins = products.stream()
                .filter(p -> p.getOrigin() != null)
                .collect(Collectors.groupingBy(CoffeeProduct::getOrigin, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(3)
                .map(e -> MarketPulseDTO.TrendingItem.builder()
                        .name(e.getKey())
                        .count(e.getValue().intValue())
                        .changeFromLastWeek(0) // TODO: Calculate actual change
                        .build())
                .collect(Collectors.toList());

        // Top processes
        List<MarketPulseDTO.TrendingItem> trendingProcesses = products.stream()
                .filter(p -> p.getProcess() != null)
                .collect(Collectors.groupingBy(p -> normalizeProcess(p.getProcess()), Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(3)
                .map(e -> MarketPulseDTO.TrendingItem.builder()
                        .name(e.getKey())
                        .count(e.getValue().intValue())
                        .changeFromLastWeek(0)
                        .build())
                .collect(Collectors.toList());

        return MarketPulseDTO.builder()
                .totalProducts(totalProducts)
                .newProductsThisWeek(newThisWeek)
                .trendingOrigins(trendingOrigins)
                .trendingProcesses(trendingProcesses)
                .snapshotDate(LocalDate.now())
                .digestUrl("/insights.html")
                .build();
    }

    private WeeklyDigestDTO toDTO(WeeklyDigest digest) {
        List<WeeklyDigestDTO.NewsArticleDTO> newsArticles = new ArrayList<>();

        try {
            List<Long> articleIds = objectMapper.readValue(
                    digest.getNewsArticleIds(),
                    new TypeReference<List<Long>>() {});

            List<CoffeeNews> news = newsRepository.findByIdIn(articleIds);
            newsArticles = news.stream()
                    .map(n -> WeeklyDigestDTO.NewsArticleDTO.builder()
                            .id(n.getId())
                            .source(n.getSource())
                            .title(n.getTitle())
                            .link(n.getLink())
                            .summary(n.getSummary())
                            .build())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Failed to parse news article IDs: {}", e.getMessage());
        }

        Map<String, Object> metrics = new HashMap<>();
        try {
            metrics = objectMapper.readValue(digest.getMetrics(), new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse metrics: {}", e.getMessage());
        }

        return WeeklyDigestDTO.builder()
                .id(digest.getId())
                .weekStart(digest.getWeekStart())
                .weekEnd(digest.getWeekEnd())
                .version(digest.getVersion())
                .narrative(digest.getNarrative())
                .metrics(metrics)
                .newsArticles(newsArticles)
                .generatedAt(digest.getGeneratedAt())
                .status(digest.getStatus())
                .build();
    }

    /**
     * Get all digest versions for a week (for comparison)
     */
    public List<WeeklyDigestDTO> getDigestVersions(LocalDate weekStart) {
        return digestRepository.findByWeekStartOrderByVersionDesc(weekStart)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }
}
