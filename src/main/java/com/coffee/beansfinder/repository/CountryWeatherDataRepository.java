package com.coffee.beansfinder.repository;

import com.coffee.beansfinder.entity.CountryWeatherData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CountryWeatherDataRepository extends JpaRepository<CountryWeatherData, Long> {

    /**
     * Find all weather data for a specific country, ordered by year and month.
     * Used for displaying year-over-year monthly trends.
     */
    List<CountryWeatherData> findByCountryCodeOrderByYearAscMonthAsc(String countryCode);

    /**
     * Find weather data for a specific country and year.
     */
    List<CountryWeatherData> findByCountryCodeAndYearOrderByMonthAsc(String countryCode, Integer year);

    /**
     * Check if weather data exists for a country.
     */
    boolean existsByCountryCode(String countryCode);

    /**
     * Delete all weather data for a specific country (used for refresh).
     */
    void deleteByCountryCode(String countryCode);

    /**
     * Get all unique country codes that have weather data.
     */
    @Query("SELECT DISTINCT c.countryCode FROM CountryWeatherData c ORDER BY c.countryCode")
    List<String> findDistinctCountryCodes();

    /**
     * Get distinct years available for a country.
     */
    @Query("SELECT DISTINCT c.year FROM CountryWeatherData c WHERE c.countryCode = :countryCode ORDER BY c.year")
    List<Integer> findDistinctYearsByCountryCode(@Param("countryCode") String countryCode);
}
