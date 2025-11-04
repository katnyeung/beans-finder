package com.coffee.beansfinder.controller;

import com.coffee.beansfinder.scheduler.CoffeeCrawlerScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST API for crawler operations
 */
@RestController
@RequestMapping("/api/crawler")
@RequiredArgsConstructor
@Slf4j
public class CrawlerController {

    private final CoffeeCrawlerScheduler scheduler;

    /**
     * Manually trigger a crawl
     * POST /api/crawler/trigger
     */
    @PostMapping("/trigger")
    public ResponseEntity<Map<String, String>> triggerCrawl() {
        log.info("Manual crawl triggered via API");

        try {
            // Run in separate thread to avoid blocking the request
            new Thread(() -> scheduler.triggerManualCrawl()).start();

            return ResponseEntity.ok(Map.of(
                "status", "started",
                "message", "Crawl has been triggered and is running in the background"
            ));
        } catch (Exception e) {
            log.error("Error triggering crawl", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "status", "error",
                "message", e.getMessage()
            ));
        }
    }
}
