package com.coffee.beansfinder.repository;

import com.coffee.beansfinder.entity.LocationCoordinates;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LocationCoordinatesRepository extends JpaRepository<LocationCoordinates, Long> {

    @Query(value = "SELECT * FROM location_coordinates WHERE " +
            "LOWER(location_name::text) = LOWER(CAST(:locationName AS text)) AND " +
            "LOWER(country::text) = LOWER(CAST(:country AS text)) AND " +
            "(:region IS NULL OR LOWER(COALESCE(region, '')::text) = LOWER(CAST(:region AS text)))",
            nativeQuery = true)
    Optional<LocationCoordinates> findByLocationNameAndCountryAndRegion(
            @Param("locationName") String locationName,
            @Param("country") String country,
            @Param("region") String region
    );

    @Query(value = "SELECT * FROM location_coordinates WHERE " +
            "LOWER(country::text) = LOWER(CAST(:country AS text)) AND region IS NULL",
            nativeQuery = true)
    Optional<LocationCoordinates> findByCountryOnly(@Param("country") String country);
}
