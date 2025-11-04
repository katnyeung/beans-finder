package com.coffee.beansfinder.scheduler;

import com.coffee.beansfinder.config.CrawlerProperties;
import com.coffee.beansfinder.entity.CoffeeProduct;
import com.coffee.beansfinder.service.CoffeeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Scheduled task to crawl and update coffee products
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "coffee.crawler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CoffeeCrawlerScheduler {

    private final CoffeeService coffeeService;
    private final CrawlerProperties properties;

    /**
     * Scheduled task that runs according to cron expression
     * Default: Daily at 2 AM
     */
    @Scheduled(cron = "${coffee.crawler.cron-schedule:0 0 2 * * ?}")
    public void scheduledCrawl() {
        if (!properties.isEnabled()) {
            log.debug("Crawler is disabled, skipping scheduled crawl");
            return;
        }

        log.info("Starting scheduled coffee product crawl...");

        try {
            // Get products that need updating (older than configured days)
            List<CoffeeProduct> outdatedProducts = coffeeService.getProductsNeedingUpdate(
                properties.getUpdateIntervalDays()
            );

            log.info("Found {} products needing update", outdatedProducts.size());

            int successCount = 0;
            int failCount = 0;

            for (CoffeeProduct product : outdatedProducts) {
                try {
                    log.info("Updating product: {} - {}", product.getBrand(), product.getProductName());

                    coffeeService.createOrUpdateProduct(
                        product.getBrand(),
                        product.getProductName(),
                        product.getSellerUrl()
                    );

                    successCount++;

                    // Add delay between requests to respect rate limits
                    if (properties.getDelaySeconds() > 0) {
                        Thread.sleep(properties.getDelaySeconds() * 1000L);
                    }

                } catch (Exception e) {
                    log.error("Failed to update product: {} - {}",
                        product.getBrand(), product.getProductName(), e);
                    failCount++;
                }
            }

            log.info("Scheduled crawl completed. Success: {}, Failed: {}", successCount, failCount);

        } catch (Exception e) {
            log.error("Error during scheduled crawl", e);
        }
    }

    /**
     * Manual trigger for testing (can be called via API)
     */
    public void triggerManualCrawl() {
        log.info("Manual crawl triggered");
        scheduledCrawl();
    }
}
