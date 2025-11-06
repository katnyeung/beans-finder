package com.coffee.beansfinder.graph.node;

import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

@Node("RoastLevel")
public class RoastLevelNode {

    @Id
    private String level; // "Light", "Medium", "Dark", "Omni", "Unknown"

    private String description;

    // Constructors
    public RoastLevelNode() {
    }

    public RoastLevelNode(String level) {
        this.level = level;
    }

    // Getters and Setters
    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RoastLevelNode that = (RoastLevelNode) o;
        return level != null && level.equals(that.level);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "RoastLevelNode{" +
                "level='" + level + '\'' +
                ", description='" + description + '\'' +
                '}';
    }
}
