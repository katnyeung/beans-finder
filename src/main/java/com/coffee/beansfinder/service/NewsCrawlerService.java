package com.coffee.beansfinder.service;

import com.coffee.beansfinder.entity.CoffeeNews;
import com.coffee.beansfinder.repository.CoffeeNewsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class NewsCrawlerService {

    private final CoffeeNewsRepository newsRepository;

    @org.springframework.beans.factory.annotation.Value("${news.crawl.enabled:false}")
    private boolean crawlEnabled;

    // RSS feed sources
    private static final List<String[]> RSS_FEEDS = List.of(
            new String[]{"perfect_daily_grind", "https://perfectdailygrind.com/feed/"},
            new String[]{"daily_coffee_news", "https://dailycoffeenews.com/feed/"},
            new String[]{"sprudge", "https://sprudge.com/feed"},
            new String[]{"barista_magazine", "https://www.baristamagazine.com/feed/"}
    );

    /**
     * Daily scheduled crawl at 5:00 AM (before Instagram content generation)
     */
    // TODO: Investigate network issues on production server - DNS/firewall blocking external domains
    // @Scheduled(cron = "${news.crawl.cron:0 0 5 * * *}")
    public void dailyCrawlJob() {
        log.info("=== Starting scheduled news crawl ===");
        try {
            int totalArticles = crawlAllFeeds();
            log.info("News crawl completed. Total new articles: {}", totalArticles);
        } catch (Exception e) {
            log.error("News crawl failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Crawl all configured RSS feeds
     */
    public int crawlAllFeeds() {
        int totalNew = 0;
        for (String[] feed : RSS_FEEDS) {
            try {
                List<CoffeeNews> articles = fetchRssFeed(feed[0], feed[1]);
                totalNew += articles.size();
                log.info("Crawled {}: {} new articles", feed[0], articles.size());
                Thread.sleep(1000); // Rate limiting
            } catch (Exception e) {
                log.error("Failed to crawl {}: {}", feed[0], e.getMessage());
            }
        }
        return totalNew;
    }

    /**
     * Fetch and save articles from a single RSS feed
     */
    public List<CoffeeNews> fetchRssFeed(String source, String feedUrl) {
        List<CoffeeNews> newArticles = new ArrayList<>();
        LocalDateTime oneWeekAgo = LocalDateTime.now().minusDays(7);

        try {
            log.info("Fetching RSS feed: {}", feedUrl);

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();

            URL url = new URL(feedUrl);
            try (InputStream is = url.openStream()) {
                Document doc = builder.parse(is);
                NodeList items = doc.getElementsByTagName("item");

                log.info("Found {} items in {}", items.getLength(), source);

                for (int i = 0; i < Math.min(items.getLength(), 15); i++) {
                    Element item = (Element) items.item(i);

                    String title = getElementText(item, "title");
                    String link = getElementText(item, "link");
                    String pubDateStr = getElementText(item, "pubDate");
                    String description = getElementText(item, "description");

                    if (title == null || link == null) continue;

                    // Skip if already exists
                    if (newsRepository.existsByLink(link)) {
                        continue;
                    }

                    LocalDateTime publishedAt = parsePubDate(pubDateStr);

                    // Skip if older than 1 week
                    if (publishedAt != null && publishedAt.isBefore(oneWeekAgo)) {
                        continue;
                    }

                    String summary = cleanDescription(description);
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
                    newArticles.add(news);
                    log.debug("Saved: {}", title);
                }
            }
        } catch (Exception e) {
            log.error("Error fetching {}: {}", feedUrl, e.getMessage());
        }

        return newArticles;
    }

    /**
     * Get recent news articles
     */
    public List<CoffeeNews> getRecentNews(int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        return newsRepository.findByFetchedAtAfter(since);
    }

    /**
     * Get news statistics
     */
    public NewsStats getStats() {
        long total = newsRepository.count();
        long lastWeek = newsRepository.findByFetchedAtAfter(LocalDateTime.now().minusDays(7)).size();
        return new NewsStats(total, lastWeek);
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
            DateTimeFormatter formatter = DateTimeFormatter.RFC_1123_DATE_TIME;
            ZonedDateTime zdt = ZonedDateTime.parse(pubDateStr, formatter);
            return zdt.toLocalDateTime();
        } catch (Exception e) {
            log.debug("Failed to parse date: {}", pubDateStr);
            return LocalDateTime.now();
        }
    }

    private String cleanDescription(String description) {
        if (description == null || description.isEmpty()) {
            return null;
        }

        String clean = Jsoup.parse(description).text();

        // Remove "The post ... appeared first on ..." boilerplate
        int postIndex = clean.indexOf("The post ");
        if (postIndex > 50) {
            clean = clean.substring(0, postIndex).trim();
        }

        if (clean.length() > 500) {
            clean = clean.substring(0, 497) + "...";
        }

        return clean.isEmpty() ? null : clean;
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
            return String.valueOf(input.hashCode());
        }
    }

    public record NewsStats(long totalArticles, long lastWeekArticles) {}
}
