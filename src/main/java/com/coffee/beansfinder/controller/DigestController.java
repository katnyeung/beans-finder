package com.coffee.beansfinder.controller;

import com.coffee.beansfinder.dto.MarketPulseDTO;
import com.coffee.beansfinder.dto.WeeklyDigestDTO;
import com.coffee.beansfinder.entity.WeeklyDigest;
import com.coffee.beansfinder.service.DigestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/digest")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Digest", description = "Weekly market digest endpoints")
public class DigestController {

    private final DigestService digestService;

    @GetMapping("/latest")
    @Operation(summary = "Get the latest published weekly digest")
    public ResponseEntity<WeeklyDigestDTO> getLatestDigest() {
        return digestService.getLatestDigest()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/archive")
    @Operation(summary = "Get all published digests for archive view")
    public ResponseEntity<List<WeeklyDigestDTO>> getArchive() {
        return ResponseEntity.ok(digestService.getArchive());
    }

    @GetMapping("/{weekStart}")
    @Operation(summary = "Get digest by week start date (YYYY-MM-DD)")
    public ResponseEntity<WeeklyDigestDTO> getDigestByWeek(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart) {
        return digestService.getDigestByWeek(weekStart)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{weekStart}/versions")
    @Operation(summary = "Get all digest versions for a week (for comparison)")
    public ResponseEntity<List<WeeklyDigestDTO>> getDigestVersions(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart) {
        return ResponseEntity.ok(digestService.getDigestVersions(weekStart));
    }

    @GetMapping("/market-pulse")
    @Operation(summary = "Get current market pulse data for homepage widget")
    public ResponseEntity<MarketPulseDTO> getMarketPulse() {
        return ResponseEntity.ok(digestService.getMarketPulse());
    }

    @PostMapping("/generate")
    @Operation(summary = "Manually trigger digest generation (admin). Use fetchFresh=false to use existing news, force=true to regenerate.")
    public ResponseEntity<Map<String, Object>> generateDigest(
            @RequestParam(defaultValue = "true") boolean fetchFresh,
            @RequestParam(defaultValue = "false") boolean force) {
        log.info("Manual digest generation triggered (fetchFresh={}, force={})", fetchFresh, force);
        try {
            WeeklyDigest digest = digestService.generateWeeklyDigest(fetchFresh, force);
            return ResponseEntity.ok(Map.of(
                    "status", "generated",
                    "id", digest.getId(),
                    "weekStart", digest.getWeekStart().toString(),
                    "weekEnd", digest.getWeekEnd().toString(),
                    "narrativePreview", digest.getNarrative().substring(0, Math.min(200, digest.getNarrative().length())) + "..."
            ));
        } catch (Exception e) {
            log.error("Failed to generate digest: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        }
    }

    @PostMapping("/{id}/publish")
    @Operation(summary = "Publish a draft digest (admin)")
    public ResponseEntity<Map<String, Object>> publishDigest(@PathVariable Long id) {
        log.info("Publishing digest: {}", id);
        try {
            WeeklyDigest digest = digestService.publishDigest(id);
            return ResponseEntity.ok(Map.of(
                    "status", "published",
                    "id", digest.getId(),
                    "weekStart", digest.getWeekStart().toString()
            ));
        } catch (Exception e) {
            log.error("Failed to publish digest {}: {}", id, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        }
    }

    @PostMapping("/fetch-news")
    @Operation(summary = "Manually fetch RSS news (admin)")
    public ResponseEntity<Map<String, Object>> fetchNews() {
        log.info("Manual news fetch triggered");
        try {
            var news = digestService.fetchAndSaveNews();
            return ResponseEntity.ok(Map.of(
                    "status", "fetched",
                    "count", news.size(),
                    "articles", news.stream()
                            .map(n -> Map.of("title", n.getTitle(), "source", n.getSource()))
                            .limit(10)
                            .toList()
            ));
        } catch (Exception e) {
            log.error("Failed to fetch news: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        }
    }
}
