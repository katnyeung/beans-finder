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

    @JsonProperty("in_stock")
    private Boolean inStock;

    @JsonProperty("product_url")
    private String productUrl;

    @JsonProperty("raw_description")
    private String rawDescription;
}
