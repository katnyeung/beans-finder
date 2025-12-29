package com.coffee.beansfinder.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;

/**
 * Dynamic sitemap.xml generator for SEO
 * Reads from cache files to avoid database queries
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class SitemapController {

    private final ObjectMapper objectMapper;

    private static final String BASE_URL = "https://graphee.link";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public String generateSitemap() {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");

        String today = LocalDate.now().format(DATE_FORMAT);

        // Static pages
        addUrl(xml, "/", "1.0", "daily", today);
        addUrl(xml, "/products.html", "0.9", "daily", today);
        addUrl(xml, "/brands.html", "0.9", "daily", today);
        addUrl(xml, "/chat.html", "0.8", "weekly", today);
        addUrl(xml, "/map.html", "0.8", "weekly", today);
        addUrl(xml, "/flavor-wheel.html", "0.8", "weekly", today);
        addUrl(xml, "/discover.html", "0.8", "weekly", today);
        addUrl(xml, "/discounts.html", "0.7", "weekly", today);

        // Read brands from map-data.json cache
        try {
            ClassPathResource mapData = new ClassPathResource("static/cache/map-data.json");
            try (InputStream is = mapData.getInputStream()) {
                JsonNode root = objectMapper.readTree(is);
                JsonNode brands = root.get("brands");
                if (brands != null && brands.isArray()) {
                    for (JsonNode brand : brands) {
                        Long brandId = brand.get("id").asLong();
                        addUrl(xml, "/products.html?brandId=" + brandId, "0.7", "weekly", today);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to read map-data.json for sitemap: {}", e.getMessage());
        }

        // Read product IDs from discover-flavor-explorer.json cache
        Set<Long> productIds = new HashSet<>();
        try {
            ClassPathResource flavorData = new ClassPathResource("static/cache/discover-flavor-explorer.json");
            try (InputStream is = flavorData.getInputStream()) {
                JsonNode root = objectMapper.readTree(is);
                JsonNode productsByFlavor = root.get("productsByFlavor");
                if (productsByFlavor != null) {
                    productsByFlavor.fields().forEachRemaining(entry -> {
                        JsonNode products = entry.getValue();
                        if (products.isArray()) {
                            for (JsonNode product : products) {
                                if (product.has("id")) {
                                    productIds.add(product.get("id").asLong());
                                }
                            }
                        }
                    });
                }
            }
        } catch (Exception e) {
            log.warn("Failed to read discover-flavor-explorer.json for sitemap: {}", e.getMessage());
        }

        // Add all unique product URLs
        for (Long productId : productIds) {
            addUrl(xml, "/product-detail.html?id=" + productId, "0.6", "weekly", today);
        }

        xml.append("</urlset>");
        return xml.toString();
    }

    private void addUrl(StringBuilder xml, String path, String priority, String changefreq, String lastmod) {
        xml.append("  <url>\n");
        xml.append("    <loc>").append(escapeXml(BASE_URL + path)).append("</loc>\n");
        xml.append("    <lastmod>").append(lastmod).append("</lastmod>\n");
        xml.append("    <changefreq>").append(changefreq).append("</changefreq>\n");
        xml.append("    <priority>").append(priority).append("</priority>\n");
        xml.append("  </url>\n");
    }

    private String escapeXml(String input) {
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }
}
