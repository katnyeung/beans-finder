package com.coffee.beansfinder.graph.repository;

import com.coffee.beansfinder.dto.FlavorCountDTO;
import com.coffee.beansfinder.graph.node.FlavorNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public interface FlavorNodeRepository extends Neo4jRepository<FlavorNode, String> {
    // name is the @Id, so use findById() instead of custom finder

    /**
     * Find all flavors in a specific SCA category
     */
    List<FlavorNode> findByScaCategory(String scaCategory);

    /**
     * Find all flavors with product counts - returns raw map data
     * Spring Data Neo4j struggles with custom projections, so we return maps
     */
    @Query(value = "MATCH (f:Flavor) " +
           "OPTIONAL MATCH (f)<-[:HAS_FLAVOR]-(p:Product) " +
           "WITH f.name as flavorName, " +
           "     f.scaCategory as category, " +
           "     COUNT(DISTINCT p) as productCount " +
           "WHERE productCount > 0 AND flavorName IS NOT NULL " +
           "RETURN {flavorName: flavorName, category: category, productCount: productCount} as data " +
           "ORDER BY category, productCount DESC")
    List<Map<String, Object>> findAllFlavorsWithProductCountsAsMap();

    /**
     * Find correlated flavors for a given flavor (co-occurrence analysis)
     * Returns flavors that appear together with the target flavor
     */
    @Query(value = "MATCH (f1:Flavor {name: $flavorName})<-[:HAS_FLAVOR]-(p:Product)-[:HAS_FLAVOR]->(f2:Flavor) " +
           "WHERE f1.name <> f2.name " +
           "WITH f2.name as correlatedFlavor, " +
           "     f2.scaCategory as category, " +
           "     COUNT(DISTINCT p) as coOccurrenceCount " +
           "MATCH (f1:Flavor {name: $flavorName})<-[:HAS_FLAVOR]-(totalP:Product) " +
           "WITH correlatedFlavor, category, coOccurrenceCount, COUNT(DISTINCT totalP) as totalF1Products " +
           "WITH correlatedFlavor, category, coOccurrenceCount, totalF1Products, " +
           "     ROUND((coOccurrenceCount * 100.0) / totalF1Products) as percentage " +
           "WHERE coOccurrenceCount >= 2 " +
           "RETURN {correlatedFlavor: correlatedFlavor, category: category, coOccurrenceCount: coOccurrenceCount, percentage: percentage} as data " +
           "ORDER BY percentage DESC " +
           "LIMIT 20")
    List<Map<String, Object>> findCorrelatedFlavors(@Param("flavorName") String flavorName);

    /**
     * Get top flavors aggregated by country for all coffee origins
     * Returns country-level flavor distribution for map visualization
     */
    @Query(value = "MATCH (p:Product)-[:FROM_ORIGIN]->(o:Origin) " +
           "MATCH (p)-[:HAS_FLAVOR]->(f:Flavor) " +
           "WHERE o.country IS NOT NULL " +
           "WITH o.country as country, f.name as flavorName, f.scaCategory as category, COUNT(DISTINCT p) as productCount " +
           "WITH country, collect({flavor: flavorName, category: category, productCount: productCount}) as allFlavors, SUM(productCount) as totalProducts " +
           "UNWIND allFlavors as flavorData " +
           "WITH country, flavorData.flavor as flavor, flavorData.category as category, flavorData.productCount as productCount, totalProducts " +
           "WITH country, flavor, category, productCount, totalProducts, " +
           "     ROUND((productCount * 100.0) / totalProducts) as percentage " +
           "ORDER BY country, productCount DESC " +
           "WITH country, collect({flavor: flavor, category: category, productCount: productCount, percentage: percentage})[0..5] as topFlavors " +
           "RETURN {country: country, topFlavors: topFlavors} as data")
    List<Map<String, Object>> findTopFlavorsByCountry();
}
