package com.coffee.beansfinder.controller;

import com.coffee.beansfinder.entity.CoffeeBrand;
import com.coffee.beansfinder.entity.CoffeeProduct;
import com.coffee.beansfinder.graph.node.BrandNode;
import com.coffee.beansfinder.graph.node.OriginNode;
import com.coffee.beansfinder.graph.node.ProducerNode;
import com.coffee.beansfinder.graph.repository.BrandNodeRepository;
import com.coffee.beansfinder.graph.repository.OriginNodeRepository;
import com.coffee.beansfinder.graph.repository.ProducerNodeRepository;
import com.coffee.beansfinder.repository.CoffeeBrandRepository;
import com.coffee.beansfinder.repository.CoffeeProductRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/map")
@RequiredArgsConstructor
@Tag(name = "Map API", description = "Endpoints for geolocation and map visualization")
public class MapController {

    private final CoffeeBrandRepository brandRepository;
    private final CoffeeProductRepository productRepository;
    private final BrandNodeRepository brandNodeRepository;
    private final OriginNodeRepository originNodeRepository;
    private final ProducerNodeRepository producerNodeRepository;

    @GetMapping("/brands")
    @Operation(summary = "Get all brands with coordinates")
    public ResponseEntity<List<BrandMapData>> getAllBrandsWithCoordinates() {
        List<CoffeeBrand> brands = brandRepository.findAll().stream()
                .filter(brand -> brand.getLatitude() != null && brand.getLongitude() != null)
                .collect(Collectors.toList());

        List<BrandMapData> mapData = brands.stream()
                .map(brand -> new BrandMapData(
                        brand.getId(),
                        brand.getName(),
                        brand.getCountry(),
                        brand.getLatitude(),
                        brand.getLongitude(),
                        brand.getWebsite(),
                        countProductsForBrand(brand.getId())
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(mapData);
    }

    @GetMapping("/origins")
    @Operation(summary = "Get all coffee origins with coordinates")
    public ResponseEntity<List<OriginMapData>> getAllOriginsWithCoordinates() {
        List<OriginNode> origins = (List<OriginNode>) originNodeRepository.findAll();

        List<OriginMapData> mapData = origins.stream()
                .filter(origin -> origin.getLatitude() != null && origin.getLongitude() != null)
                .map(origin -> new OriginMapData(
                        origin.getId(),
                        origin.getCountry(),
                        origin.getRegion(),
                        origin.getLatitude(),
                        origin.getLongitude(),
                        countProductsFromOrigin(origin.getCountry())
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(mapData);
    }

    @GetMapping("/producers")
    @Operation(summary = "Get all producers with coordinates")
    public ResponseEntity<List<ProducerMapData>> getAllProducersWithCoordinates() {
        List<ProducerNode> producers = (List<ProducerNode>) producerNodeRepository.findAll();

        List<ProducerMapData> mapData = producers.stream()
                .filter(producer -> producer.getLatitude() != null && producer.getLongitude() != null)
                .map(producer -> new ProducerMapData(
                        producer.getId(),
                        producer.getName(),
                        producer.getCountry(),
                        producer.getRegion(),
                        producer.getCity(),
                        producer.getLatitude(),
                        producer.getLongitude()
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(mapData);
    }

    @GetMapping("/connections/{brandId}")
    @Operation(summary = "Get connections for a brand (brand -> origins -> producers)")
    public ResponseEntity<BrandConnectionData> getBrandConnections(@PathVariable Long brandId) {
        CoffeeBrand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new RuntimeException("Brand not found: " + brandId));

        // Get all products for this brand
        List<CoffeeProduct> products = productRepository.findByBrandId(brandId);

        // Extract unique origins from products
        Set<String> originCountries = products.stream()
                .map(CoffeeProduct::getOrigin)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // Get origin nodes with coordinates
        List<OriginMapData> origins = new ArrayList<>();
        for (String country : originCountries) {
            originNodeRepository.findById(country)
                    .filter(origin -> origin.getLatitude() != null)
                    .ifPresent(origin -> origins.add(new OriginMapData(
                            origin.getId(),
                            origin.getCountry(),
                            origin.getRegion(),
                            origin.getLatitude(),
                            origin.getLongitude(),
                            countProductsFromOrigin(origin.getCountry())
                    )));
        }

        // Extract producers from products (if available)
        Set<String> producerNames = products.stream()
                .map(CoffeeProduct::getProducer)
                .filter(Objects::nonNull)
                .filter(name -> !name.isBlank())
                .collect(Collectors.toSet());

        List<ProducerMapData> producers = new ArrayList<>();
        for (String producerName : producerNames) {
            producerNodeRepository.findByNameContainingIgnoreCase(producerName)
                    .stream()
                    .filter(producer -> producer.getLatitude() != null)
                    .forEach(producer -> producers.add(new ProducerMapData(
                            producer.getId(),
                            producer.getName(),
                            producer.getCountry(),
                            producer.getRegion(),
                            producer.getCity(),
                            producer.getLatitude(),
                            producer.getLongitude()
                    )));
        }

        BrandConnectionData connectionData = new BrandConnectionData(
                new BrandMapData(
                        brand.getId(),
                        brand.getName(),
                        brand.getCountry(),
                        brand.getLatitude(),
                        brand.getLongitude(),
                        brand.getWebsite(),
                        products.size()
                ),
                origins,
                producers
        );

        return ResponseEntity.ok(connectionData);
    }

    @GetMapping("/data")
    @Operation(summary = "Get complete map dataset (all brands, origins, producers)")
    public ResponseEntity<CompleteMapData> getCompleteMapData() {
        // Get all brands with coordinates
        List<BrandMapData> brands = brandRepository.findAll().stream()
                .filter(brand -> brand.getLatitude() != null && brand.getLongitude() != null)
                .map(brand -> new BrandMapData(
                        brand.getId(),
                        brand.getName(),
                        brand.getCountry(),
                        brand.getLatitude(),
                        brand.getLongitude(),
                        brand.getWebsite(),
                        countProductsForBrand(brand.getId())
                ))
                .collect(Collectors.toList());

        // Get all origins with coordinates
        List<OriginMapData> origins = ((List<OriginNode>) originNodeRepository.findAll()).stream()
                .filter(origin -> origin.getLatitude() != null && origin.getLongitude() != null)
                .map(origin -> new OriginMapData(
                        origin.getId(),
                        origin.getCountry(),
                        origin.getRegion(),
                        origin.getLatitude(),
                        origin.getLongitude(),
                        countProductsFromOrigin(origin.getCountry())
                ))
                .collect(Collectors.toList());

        // Get all producers with coordinates
        List<ProducerMapData> producers = ((List<ProducerNode>) producerNodeRepository.findAll()).stream()
                .filter(producer -> producer.getLatitude() != null && producer.getLongitude() != null)
                .map(producer -> new ProducerMapData(
                        producer.getId(),
                        producer.getName(),
                        producer.getCountry(),
                        producer.getRegion(),
                        producer.getCity(),
                        producer.getLatitude(),
                        producer.getLongitude()
                ))
                .collect(Collectors.toList());

        // Build connections: for each brand, find its origins and producers
        List<ConnectionLine> connections = new ArrayList<>();
        for (BrandMapData brand : brands) {
            List<CoffeeProduct> products = productRepository.findByBrandId(brand.getId());

            // Brand -> Origins
            Set<String> originCountries = products.stream()
                    .map(CoffeeProduct::getOrigin)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            for (String country : originCountries) {
                origins.stream()
                        .filter(origin -> origin.getCountry().equalsIgnoreCase(country))
                        .forEach(origin -> connections.add(new ConnectionLine(
                                "brand-origin",
                                brand.getId().toString(),
                                origin.getId(),
                                brand.getLatitude(),
                                brand.getLongitude(),
                                origin.getLatitude(),
                                origin.getLongitude()
                        )));
            }

            // Origins -> Producers (if producers exist)
            Set<String> producerNames = products.stream()
                    .map(CoffeeProduct::getProducer)
                    .filter(Objects::nonNull)
                    .filter(name -> !name.isBlank())
                    .collect(Collectors.toSet());

            for (String producerName : producerNames) {
                producers.stream()
                        .filter(producer -> producer.getName().toLowerCase().contains(producerName.toLowerCase()))
                        .forEach(producer -> {
                            // Find matching origin for this producer
                            origins.stream()
                                    .filter(origin -> origin.getCountry().equalsIgnoreCase(producer.getCountry()))
                                    .findFirst()
                                    .ifPresent(origin -> connections.add(new ConnectionLine(
                                            "origin-producer",
                                            origin.getId(),
                                            producer.getId(),
                                            origin.getLatitude(),
                                            origin.getLongitude(),
                                            producer.getLatitude(),
                                            producer.getLongitude()
                                    )));
                        });
            }
        }

        CompleteMapData mapData = new CompleteMapData(brands, origins, producers, connections);
        return ResponseEntity.ok(mapData);
    }

    // Helper methods
    private long countProductsForBrand(Long brandId) {
        return productRepository.countByBrandId(brandId);
    }

    private long countProductsFromOrigin(String country) {
        return productRepository.findAll().stream()
                .filter(p -> country.equalsIgnoreCase(p.getOrigin()))
                .count();
    }

    // DTOs
    @Data
    @AllArgsConstructor
    public static class BrandMapData {
        private Long id;
        private String name;
        private String country;
        private Double latitude;
        private Double longitude;
        private String website;
        private long productCount;
    }

    @Data
    @AllArgsConstructor
    public static class OriginMapData {
        private String id;
        private String country;
        private String region;
        private Double latitude;
        private Double longitude;
        private long productCount;
    }

    @Data
    @AllArgsConstructor
    public static class ProducerMapData {
        private String id;
        private String name;
        private String country;
        private String region;
        private String city;
        private Double latitude;
        private Double longitude;
    }

    @Data
    @AllArgsConstructor
    public static class BrandConnectionData {
        private BrandMapData brand;
        private List<OriginMapData> origins;
        private List<ProducerMapData> producers;
    }

    @Data
    @AllArgsConstructor
    public static class ConnectionLine {
        private String type; // "brand-origin" or "origin-producer"
        private String fromId;
        private String toId;
        private Double fromLat;
        private Double fromLon;
        private Double toLat;
        private Double toLon;
    }

    @Data
    @AllArgsConstructor
    public static class CompleteMapData {
        private List<BrandMapData> brands;
        private List<OriginMapData> origins;
        private List<ProducerMapData> producers;
        private List<ConnectionLine> connections;
    }
}
