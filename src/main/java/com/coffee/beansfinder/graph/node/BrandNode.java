package com.coffee.beansfinder.graph.node;

import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

import java.time.LocalDateTime;

@Node("Brand")
public class BrandNode {

    @Id
    private String name; // Natural key from CoffeeBrand.name

    private Long brandId; // PostgreSQL ID for reference
    private String website;
    private String country;
    private String description;
    private LocalDateTime createdDate;

    // Constructors
    public BrandNode() {
    }

    public BrandNode(String name) {
        this.name = name;
        this.createdDate = LocalDateTime.now();
    }

    // Getters and Setters
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getBrandId() {
        return brandId;
    }

    public void setBrandId(Long brandId) {
        this.brandId = brandId;
    }

    public String getWebsite() {
        return website;
    }

    public void setWebsite(String website) {
        this.website = website;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDateTime getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(LocalDateTime createdDate) {
        this.createdDate = createdDate;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BrandNode brandNode = (BrandNode) o;
        return name != null && name.equals(brandNode.name);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "BrandNode{" +
                "name='" + name + '\'' +
                ", brandId=" + brandId +
                ", country='" + country + '\'' +
                '}';
    }
}
