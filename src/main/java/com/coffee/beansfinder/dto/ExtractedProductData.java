package com.coffee.beansfinder.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtractedProductData {

    @JsonProperty("product_name")
    private String productName;

    @JsonDeserialize(using = StringOrArrayDeserializer.class)
    private String origin;

    @JsonDeserialize(using = StringOrArrayDeserializer.class)
    private String region;

    @JsonDeserialize(using = StringOrArrayDeserializer.class)
    private String process;

    @JsonDeserialize(using = StringOrArrayDeserializer.class)
    private String producer;

    @JsonDeserialize(using = StringOrArrayDeserializer.class)
    private String variety;

    @JsonDeserialize(using = StringOrArrayDeserializer.class)
    private String altitude;

    @JsonProperty("tasting_notes")
    private List<String> tastingNotes;

    private BigDecimal price;

    @JsonProperty("price_variants")
    private List<PriceVariant> priceVariants;

    @JsonProperty("in_stock")
    private Boolean inStock;

    @JsonProperty("product_url")
    private String productUrl;

    @JsonProperty("raw_description")
    private String rawDescription;

    /**
     * 9-dimensional flavor profile [0.0-1.0] for SCA category intensities
     * Indices: 0=fruity, 1=floral, 2=sweet, 3=nutty, 4=spices, 5=roasted, 6=green, 7=sour, 8=other
     */
    @JsonProperty("flavor_profile")
    private List<Double> flavorProfile;

    /**
     * 4-dimensional character axes [-1.0 to +1.0] for coffee character spectrum
     * Indices: 0=acidity (flat↔bright), 1=body (light↔full), 2=roast (light↔dark), 3=complexity (clean↔funky)
     */
    @JsonProperty("character_axes")
    private List<Double> characterAxes;

    /**
     * Price variant for different sizes (e.g., 250g, 1kg)
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PriceVariant {
        private String size;
        private BigDecimal price;
    }
}
