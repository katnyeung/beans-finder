package com.coffee.beansfinder.scheduler;

import com.coffee.beansfinder.service.CrawlerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler for automated crawling tasks
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CrawlerScheduler {

    private final CrawlerService crawlerService;

    /**
     * Daily crawl job - runs every day at 2 AM
     * Crawls brands that haven't been updated in 14 days
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void scheduledDailyCrawl() {
        log.info("=== Starting scheduled daily crawl ===");

        try {
            crawlerService.crawlAllBrands();
            log.info("=== Daily crawl completed successfully ===");
        } catch (Exception e) {
            log.error("=== Daily crawl failed: {} ===", e.getMessage(), e);
        }
    }

    /**
     * Retry failed products - runs every 6 hours
     */
    @Scheduled(cron = "0 0 */6 * * *")
    public void scheduledRetryFailedProducts() {
        log.info("=== Starting retry of failed products ===");

        try {
            crawlerService.retryFailedProducts();
            log.info("=== Retry job completed ===");
        } catch (Exception e) {
            log.error("=== Retry job failed: {} ===", e.getMessage(), e);
        }
    }

    /**
     * Manual trigger for immediate crawl (can be called via REST API)
     */
    public void triggerManualCrawl() {
        log.info("=== Manual crawl triggered ===");
        crawlerService.crawlAllBrands();
    }
}
