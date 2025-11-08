package com.coffee.beansfinder.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "location_coordinates",
        uniqueConstraints = @UniqueConstraint(columnNames = {"location_name", "country", "region"}))
public class LocationCoordinates {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "VARCHAR(255)")
    private String locationName;

    @Column(nullable = false, columnDefinition = "VARCHAR(100)")
    private String country;

    @Column(columnDefinition = "VARCHAR(100)")
    private String region;

    private Double latitude;

    private Double longitude;

    @Column(columnDefinition = "TEXT")
    private String boundingBox; // JSON string: {"minLat": x, "maxLat": y, "minLon": z, "maxLon": w}

    @Column(columnDefinition = "VARCHAR(50)")
    private String source; // 'nominatim', 'manual', 'seeded'

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdDate;

    @PrePersist
    protected void onCreate() {
        createdDate = LocalDateTime.now();
    }

    // Constructors
    public LocationCoordinates() {}

    public LocationCoordinates(String locationName, String country, String region) {
        this.locationName = locationName;
        this.country = country;
        this.region = region;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getLocationName() {
        return locationName;
    }

    public void setLocationName(String locationName) {
        this.locationName = locationName;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    public String getBoundingBox() {
        return boundingBox;
    }

    public void setBoundingBox(String boundingBox) {
        this.boundingBox = boundingBox;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public LocalDateTime getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(LocalDateTime createdDate) {
        this.createdDate = createdDate;
    }
}
