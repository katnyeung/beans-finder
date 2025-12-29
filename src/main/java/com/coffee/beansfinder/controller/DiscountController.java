package com.coffee.beansfinder.controller;

import com.coffee.beansfinder.dto.BrandDiscountDTO;
import com.coffee.beansfinder.entity.BrandDiscount;
import com.coffee.beansfinder.entity.CoffeeBrand;
import com.coffee.beansfinder.repository.BrandDiscountRepository;
import com.coffee.beansfinder.repository.CoffeeBrandRepository;
import com.coffee.beansfinder.service.DiscountExtractorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/discounts")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Discounts", description = "Brand discount and promotion endpoints")
public class DiscountController {

    private final BrandDiscountRepository discountRepository;
    private final CoffeeBrandRepository brandRepository;
    private final DiscountExtractorService discountExtractorService;

    @GetMapping
    @Operation(summary = "Get all discounts with brand info")
    public ResponseEntity<List<BrandDiscountDTO>> getAllDiscounts() {
        List<BrandDiscount> discounts = discountRepository.findAllWithBrand();
        List<BrandDiscountDTO> dtos = discounts.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/brand/{brandId}")
    @Operation(summary = "Get discounts for a specific brand")
    public ResponseEntity<List<BrandDiscountDTO>> getDiscountsByBrand(@PathVariable Long brandId) {
        List<BrandDiscount> discounts = discountRepository.findByBrandId(brandId);

        // Get brand info for DTOs
        CoffeeBrand brand = brandRepository.findById(brandId).orElse(null);

        List<BrandDiscountDTO> dtos = discounts.stream()
                .map(d -> toDTO(d, brand))
                .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    @PostMapping("/extract/{brandId}")
    @Operation(summary = "Manually trigger discount extraction for a brand")
    public ResponseEntity<Map<String, Object>> extractDiscounts(
            @PathVariable Long brandId,
            @RequestParam(defaultValue = "false") boolean force) {

        CoffeeBrand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new RuntimeException("Brand not found: " + brandId));

        // Clear hash if force=true to bypass cache
        if (force) {
            brand.setDiscountContentHash(null);
            brandRepository.save(brand);
            log.info("Force flag set, cleared discount hash for brand: {}", brand.getName());
        }

        int count = discountExtractorService.extractAndSave(brand);

        return ResponseEntity.ok(Map.of(
                "brandId", brandId,
                "brandName", brand.getName(),
                "discountsFound", count,
                "status", count == -1 ? "skipped (unchanged)" : "completed"
        ));
    }

    @PostMapping("/extract-all")
    @Operation(summary = "Extract discounts from all approved brands (admin endpoint)")
    public ResponseEntity<Map<String, Object>> extractAllDiscounts(
            @RequestParam(defaultValue = "false") boolean force) {

        List<CoffeeBrand> brands = brandRepository.findByApprovedTrue();
        log.info("Starting discount extraction for {} approved brands (force={})", brands.size(), force);

        int processed = 0;
        int extracted = 0;
        int skipped = 0;
        int failed = 0;

        for (CoffeeBrand brand : brands) {
            if (brand.getWebsite() == null || brand.getWebsite().isBlank()) {
                skipped++;
                continue;
            }

            try {
                // Clear hash if force=true to re-extract
                if (force) {
                    brand.setDiscountContentHash(null);
                }

                int count = discountExtractorService.extractAndSave(brand);
                processed++;

                if (count == -1) {
                    skipped++; // Hash matched, skipped LLM
                } else {
                    extracted += count;
                }

                // Rate limit: 2 seconds between requests
                Thread.sleep(2000);

            } catch (Exception e) {
                log.error("Failed to extract discounts for {}: {}", brand.getName(), e.getMessage());
                failed++;
            }
        }

        log.info("Discount extraction completed: {} processed, {} discounts found, {} skipped, {} failed",
                processed, extracted, skipped, failed);

        return ResponseEntity.ok(Map.of(
                "totalBrands", brands.size(),
                "processed", processed,
                "discountsFound", extracted,
                "skipped", skipped,
                "failed", failed,
                "status", "completed"
        ));
    }

    private BrandDiscountDTO toDTO(BrandDiscount discount) {
        CoffeeBrand brand = discount.getBrand();
        return BrandDiscountDTO.builder()
                .id(discount.getId())
                .brandId(discount.getBrandId())
                .brandName(brand != null ? brand.getName() : null)
                .brandWebsite(brand != null ? brand.getWebsite() : null)
                .title(discount.getTitle())
                .description(discount.getDescription())
                .discountCode(discount.getDiscountCode())
                .createdAt(discount.getCreatedAt())
                .build();
    }

    private BrandDiscountDTO toDTO(BrandDiscount discount, CoffeeBrand brand) {
        return BrandDiscountDTO.builder()
                .id(discount.getId())
                .brandId(discount.getBrandId())
                .brandName(brand != null ? brand.getName() : null)
                .brandWebsite(brand != null ? brand.getWebsite() : null)
                .title(discount.getTitle())
                .description(discount.getDescription())
                .discountCode(discount.getDiscountCode())
                .createdAt(discount.getCreatedAt())
                .build();
    }
}
