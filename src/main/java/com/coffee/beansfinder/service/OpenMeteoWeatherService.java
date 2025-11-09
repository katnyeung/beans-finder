package com.coffee.beansfinder.service;

import com.coffee.beansfinder.entity.CountryWeatherData;
import com.coffee.beansfinder.repository.CountryWeatherDataRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for fetching historical weather data from Open-Meteo API.
 * Aggregates daily data into monthly averages for year-over-year comparison.
 */
@Service
public class OpenMeteoWeatherService {

    private static final Logger log = LoggerFactory.getLogger(OpenMeteoWeatherService.class);
    private static final String OPEN_METEO_API_URL = "https://archive-api.open-meteo.com/v1/archive";

    // Date range: 2020-2025 (5+ years)
    private static final LocalDate START_DATE = LocalDate.of(2020, 1, 1);
    private static final LocalDate END_DATE = LocalDate.now(); // Up to current date

    @Autowired
    private CountryWeatherDataRepository weatherRepository;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Fetch and cache weather data for a country using its coordinates.
     * @param countryName Name of the country
     * @param countryCode ISO 3166-1 alpha-2 code (e.g., "CO")
     * @param latitude Latitude of country centroid
     * @param longitude Longitude of country centroid
     * @param force Force refresh even if data exists
     * @return List of monthly weather data records
     */
    @Transactional
    public List<CountryWeatherData> fetchAndCacheWeatherData(
            String countryName,
            String countryCode,
            double latitude,
            double longitude,
            boolean force
    ) {
        log.info("WEATHER FETCH: Starting for country={}, code={}, lat={}, lon={}, force={}",
                countryName, countryCode, latitude, longitude, force);

        // Check cache first
        if (!force && weatherRepository.existsByCountryCode(countryCode)) {
            log.info("WEATHER CACHE: Found existing data for {}, returning cached", countryCode);
            return weatherRepository.findByCountryCodeOrderByYearAscMonthAsc(countryCode);
        }

        try {
            // Build API URL
            String url = buildOpenMeteoUrl(latitude, longitude);
            log.debug("WEATHER API CALL: {}", url);

            // Fetch from Open-Meteo
            String response = restTemplate.getForObject(url, String.class);
            log.trace("WEATHER RAW RESPONSE: {}", response);

            // Parse JSON
            JsonNode root = objectMapper.readTree(response);
            JsonNode daily = root.path("daily");

            // Extract arrays (only 3 metrics: temperature, precipitation, solar radiation)
            List<String> dates = extractStringArray(daily.path("time"));
            List<Double> temps = extractDoubleArray(daily.path("temperature_2m_mean"));
            List<Double> rainfall = extractDoubleArray(daily.path("precipitation_sum"));
            List<Double> solarRadiation = extractDoubleArray(daily.path("shortwave_radiation_sum"));

            log.info("WEATHER DATA POINTS: Fetched {} daily records for {}", dates.size(), countryCode);

            // Aggregate daily → monthly
            Map<YearMonth, MonthlyData> monthlyAggregates = aggregateToMonthly(
                    dates, temps, rainfall, solarRadiation
            );

            log.info("WEATHER AGGREGATION: Created {} monthly records for {}", monthlyAggregates.size(), countryCode);

            // Delete old data if refreshing
            if (force) {
                weatherRepository.deleteByCountryCode(countryCode);
                log.info("WEATHER CACHE: Cleared old data for {}", countryCode);
            }

            // Save to database
            List<CountryWeatherData> weatherRecords = new ArrayList<>();
            for (Map.Entry<YearMonth, MonthlyData> entry : monthlyAggregates.entrySet()) {
                YearMonth ym = entry.getKey();
                MonthlyData data = entry.getValue();

                CountryWeatherData record = new CountryWeatherData(countryName, countryCode, ym.year, ym.month);
                record.setAvgTemperature(data.avgTemp);
                record.setTotalRainfall(data.totalRain);
                record.setAvgSoilMoisture(null); // Not available in daily API
                record.setAvgSolarRadiation(data.avgSolar);
                record.setLastUpdated(LocalDateTime.now());
                record.setSource("open-meteo");

                weatherRecords.add(record);
            }

            List<CountryWeatherData> saved = weatherRepository.saveAll(weatherRecords);
            log.info("WEATHER SAVE: Saved {} monthly records for {} to database", saved.size(), countryCode);

            return saved;

        } catch (Exception e) {
            log.error("WEATHER ERROR: Failed to fetch/parse data for {}: {}", countryCode, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Get cached weather data for a country.
     */
    public List<CountryWeatherData> getWeatherData(String countryCode) {
        return weatherRepository.findByCountryCodeOrderByYearAscMonthAsc(countryCode);
    }

    /**
     * Check if weather data exists for a country.
     */
    public boolean hasWeatherData(String countryCode) {
        return weatherRepository.existsByCountryCode(countryCode);
    }

    /**
     * Build Open-Meteo API URL with parameters.
     * Note: Using temperature_2m_mean (average), precipitation_sum, and shortwave_radiation_sum
     * Soil moisture is NOT available in daily aggregations, only hourly data
     */
    private String buildOpenMeteoUrl(double latitude, double longitude) {
        return String.format(
                "%s?latitude=%.4f&longitude=%.4f&start_date=%s&end_date=%s" +
                "&daily=temperature_2m_mean,precipitation_sum,shortwave_radiation_sum" +
                "&timezone=auto",
                OPEN_METEO_API_URL,
                latitude,
                longitude,
                START_DATE.toString(),
                END_DATE.toString()
        );
    }

    /**
     * Aggregate daily data into monthly averages.
     * Only 3 metrics: temperature (avg), rainfall (sum), solar radiation (avg)
     */
    private Map<YearMonth, MonthlyData> aggregateToMonthly(
            List<String> dates,
            List<Double> temps,
            List<Double> rainfall,
            List<Double> solarRadiation
    ) {
        Map<YearMonth, MonthlyData> monthlyData = new LinkedHashMap<>();

        for (int i = 0; i < dates.size(); i++) {
            LocalDate date = LocalDate.parse(dates.get(i));
            YearMonth ym = new YearMonth(date.getYear(), date.getMonthValue());

            MonthlyData data = monthlyData.computeIfAbsent(ym, k -> new MonthlyData());

            // Add daily values
            if (i < temps.size() && temps.get(i) != null) {
                data.addTemp(temps.get(i));
            }
            if (i < rainfall.size() && rainfall.get(i) != null) {
                data.addRain(rainfall.get(i));
            }
            if (i < solarRadiation.size() && solarRadiation.get(i) != null) {
                data.addSolar(solarRadiation.get(i));
            }
        }

        // Calculate averages
        monthlyData.values().forEach(MonthlyData::calculateAverages);

        return monthlyData;
    }

    /**
     * Extract string array from JSON node.
     */
    private List<String> extractStringArray(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(n -> result.add(n.asText()));
        }
        return result;
    }

    /**
     * Extract double array from JSON node.
     */
    private List<Double> extractDoubleArray(JsonNode node) {
        List<Double> result = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(n -> result.add(n.isNull() ? null : n.asDouble()));
        }
        return result;
    }

    // Helper classes
    private static class YearMonth {
        int year;
        int month;

        YearMonth(int year, int month) {
            this.year = year;
            this.month = month;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            YearMonth that = (YearMonth) o;
            return year == that.year && month == that.month;
        }

        @Override
        public int hashCode() {
            return Objects.hash(year, month);
        }
    }

    private static class MonthlyData {
        List<Double> temps = new ArrayList<>();
        List<Double> rain = new ArrayList<>();
        List<Double> solar = new ArrayList<>();

        Double avgTemp;
        Double totalRain;
        Double avgSolar;

        void addTemp(double v) { temps.add(v); }
        void addRain(double v) { rain.add(v); }
        void addSolar(double v) { solar.add(v); }

        void calculateAverages() {
            avgTemp = temps.isEmpty() ? null : temps.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            totalRain = rain.isEmpty() ? null : rain.stream().mapToDouble(Double::doubleValue).sum();
            avgSolar = solar.isEmpty() ? null : solar.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        }
    }
}
