package com.coffee.beansfinder.controller;

import com.coffee.beansfinder.scheduler.CrawlerScheduler;
import com.coffee.beansfinder.service.CrawlerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for managing crawler operations
 */
@RestController
@RequestMapping("/api/crawler")
@RequiredArgsConstructor
@Slf4j
public class CrawlerController {

    private final CrawlerScheduler crawlerScheduler;
    private final CrawlerService crawlerService;

    /**
     * Trigger manual crawl of all brands
     */
    @PostMapping("/trigger")
    public String triggerCrawl() {
        log.info("Manual crawl triggered via API");
        crawlerScheduler.triggerManualCrawl();
        return "Crawl triggered successfully";
    }

    /**
     * Retry failed products
     */
    @PostMapping("/retry-failed")
    public String retryFailed() {
        log.info("Retry failed products triggered via API");
        crawlerService.retryFailedProducts();
        return "Retry triggered successfully";
    }
}
