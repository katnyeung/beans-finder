package com.coffee.beansfinder.service;

import com.coffee.beansfinder.entity.CoffeeProduct;
import com.coffee.beansfinder.entity.PriceHistory;
import com.coffee.beansfinder.repository.PriceHistoryRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class PriceHistoryService {

    private final PriceHistoryRepository priceHistoryRepository;
    private final ObjectMapper objectMapper;

    // Pattern to extract bag size from text (e.g., "250g", "1kg", "12oz")
    private static final Pattern BAG_SIZE_PATTERN = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(g|kg|oz)\\b",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Record price snapshot for a product.
     * Called during crawl - records all price variants.
     */
    @Transactional
    public void recordPrice(CoffeeProduct product) {
        if (product == null || product.getId() == null) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        Long brandId = product.getBrand() != null ? product.getBrand().getId() : null;
        String origin = product.getOrigin();

        // Record price variants if available
        if (product.getPriceVariantsJson() != null && !product.getPriceVariantsJson().isEmpty()) {
            recordPriceVariants(product, brandId, origin, now);
        } else if (product.getPrice() != null) {
            // Fallback: record base price only
            String bagSize = extractBagSize(product.getProductName());
            BigDecimal pricePer100g = calculatePricePer100g(product.getPrice(), bagSize);

            PriceHistory history = PriceHistory.builder()
                    .productId(product.getId())
                    .brandId(brandId)
                    .origin(origin)
                    .price(product.getPrice())
                    .currency(product.getCurrency() != null ? product.getCurrency() : "GBP")
                    .bagSize(bagSize)
                    .pricePer100g(pricePer100g)
                    .recordedAt(now)
                    .build();

            priceHistoryRepository.save(history);
            log.debug("Recorded price history for product {}: {} {}",
                    product.getId(), product.getPrice(), product.getCurrency());
        }
    }

    /**
     * Record each price variant as a separate row
     */
    private void recordPriceVariants(CoffeeProduct product, Long brandId, String origin, LocalDateTime now) {
        try {
            List<Map<String, Object>> variants = objectMapper.readValue(
                    product.getPriceVariantsJson(),
                    new TypeReference<List<Map<String, Object>>>() {}
            );

            for (Map<String, Object> variant : variants) {
                String size = (String) variant.get("size");
                Object priceObj = variant.get("price");

                if (priceObj != null) {
                    BigDecimal price = new BigDecimal(priceObj.toString());
                    BigDecimal pricePer100g = calculatePricePer100g(price, size);

                    PriceHistory history = PriceHistory.builder()
                            .productId(product.getId())
                            .brandId(brandId)
                            .origin(origin)
                            .price(price)
                            .currency(product.getCurrency() != null ? product.getCurrency() : "GBP")
                            .bagSize(size)
                            .pricePer100g(pricePer100g)
                            .recordedAt(now)
                            .build();

                    priceHistoryRepository.save(history);
                }
            }

            log.debug("Recorded {} price variants for product {}", variants.size(), product.getId());

        } catch (Exception e) {
            log.warn("Failed to parse price variants for product {}: {}",
                    product.getId(), e.getMessage());

            // Fallback to base price
            if (product.getPrice() != null) {
                String bagSize = extractBagSize(product.getProductName());
                BigDecimal pricePer100g = calculatePricePer100g(product.getPrice(), bagSize);

                PriceHistory history = PriceHistory.builder()
                        .productId(product.getId())
                        .brandId(brandId)
                        .origin(origin)
                        .price(product.getPrice())
                        .currency(product.getCurrency() != null ? product.getCurrency() : "GBP")
                        .bagSize(bagSize)
                        .pricePer100g(pricePer100g)
                        .recordedAt(now)
                        .build();

                priceHistoryRepository.save(history);
            }
        }
    }

    /**
     * Get price history for a product (for chart display)
     */
    public List<PriceHistory> getPriceHistory(Long productId) {
        return priceHistoryRepository.findByProductIdOrderByRecordedAtAsc(productId);
    }

    /**
     * Get price history grouped by bag size (for multi-line chart)
     */
    public Map<String, List<PriceHistory>> getPriceHistoryBySize(Long productId) {
        List<PriceHistory> history = getPriceHistory(productId);

        return history.stream()
                .collect(Collectors.groupingBy(
                        h -> h.getBagSize() != null ? h.getBagSize() : "default"
                ));
    }

    /**
     * Check if product has enough price history to show chart (at least 2 data points)
     */
    public boolean hasEnoughHistory(Long productId) {
        return priceHistoryRepository.countByProductId(productId) >= 2;
    }

    /**
     * Extract bag size from product name or text
     */
    private String extractBagSize(String text) {
        if (text == null) return null;

        Matcher matcher = BAG_SIZE_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(0);
        }
        return null;
    }

    /**
     * Calculate price per 100g for normalization
     */
    private BigDecimal calculatePricePer100g(BigDecimal price, String bagSize) {
        if (price == null || bagSize == null) {
            return null;
        }

        try {
            Matcher matcher = BAG_SIZE_PATTERN.matcher(bagSize);

            if (matcher.find()) {
                double amount = Double.parseDouble(matcher.group(1));
                String unit = matcher.group(2).toLowerCase();

                // Convert to grams
                double grams = switch (unit) {
                    case "kg" -> amount * 1000;
                    case "oz" -> amount * 28.3495;
                    default -> amount; // assume grams
                };

                if (grams > 0) {
                    // Calculate price per 100g
                    return price.multiply(BigDecimal.valueOf(100))
                            .divide(BigDecimal.valueOf(grams), 2, RoundingMode.HALF_UP);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to calculate price per 100g for {}: {}", bagSize, e.getMessage());
        }

        return null;
    }
}
