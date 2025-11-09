package com.coffee.beansfinder.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Entity for storing historical weather data for coffee-growing countries.
 * Data is organized by month and year to show year-over-year climate trends.
 */
@Entity
@Table(name = "country_weather_data",
       indexes = {
           @Index(name = "idx_country_year_month", columnList = "countryCode,year,month")
       })
public class CountryWeatherData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String countryName;

    @Column(nullable = false, length = 2)
    private String countryCode; // ISO 3166-1 alpha-2 (e.g., "CO", "ET", "BR")

    @Column(nullable = false)
    private Integer year; // 2020-2025

    @Column(nullable = false)
    private Integer month; // 1-12

    @Column
    private Double avgTemperature; // Average monthly temperature in °C

    @Column
    private Double totalRainfall; // Total monthly rainfall in mm

    @Column
    private Double avgSoilMoisture; // Average soil moisture (0-1 scale)

    @Column
    private Double avgSolarRadiation; // Average solar radiation in W/m²

    @Column(nullable = false)
    private LocalDateTime lastUpdated;

    @Column(length = 50)
    private String source = "open-meteo"; // Data source tracking

    // Constructors
    public CountryWeatherData() {
        this.lastUpdated = LocalDateTime.now();
    }

    public CountryWeatherData(String countryName, String countryCode, Integer year, Integer month) {
        this.countryName = countryName;
        this.countryCode = countryCode;
        this.year = year;
        this.month = month;
        this.lastUpdated = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCountryName() {
        return countryName;
    }

    public void setCountryName(String countryName) {
        this.countryName = countryName;
    }

    public String getCountryCode() {
        return countryCode;
    }

    public void setCountryCode(String countryCode) {
        this.countryCode = countryCode;
    }

    public Integer getYear() {
        return year;
    }

    public void setYear(Integer year) {
        this.year = year;
    }

    public Integer getMonth() {
        return month;
    }

    public void setMonth(Integer month) {
        this.month = month;
    }

    public Double getAvgTemperature() {
        return avgTemperature;
    }

    public void setAvgTemperature(Double avgTemperature) {
        this.avgTemperature = avgTemperature;
    }

    public Double getTotalRainfall() {
        return totalRainfall;
    }

    public void setTotalRainfall(Double totalRainfall) {
        this.totalRainfall = totalRainfall;
    }

    public Double getAvgSoilMoisture() {
        return avgSoilMoisture;
    }

    public void setAvgSoilMoisture(Double avgSoilMoisture) {
        this.avgSoilMoisture = avgSoilMoisture;
    }

    public Double getAvgSolarRadiation() {
        return avgSolarRadiation;
    }

    public void setAvgSolarRadiation(Double avgSolarRadiation) {
        this.avgSolarRadiation = avgSolarRadiation;
    }

    public LocalDateTime getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(LocalDateTime lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
