package com.coffee.beansfinder.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExtractionResponse {

    @JsonProperty("product_name")
    private String productName;

    private String origin;
    private String region;
    private String process;
    private String producer;
    private String variety;
    private String altitude;

    @JsonProperty("tasting_notes")
    @Builder.Default
    private List<String> tastingNotes = new ArrayList<>();

    @JsonProperty("sca_mapping")
    private Map<String, Object> scaMapping;

    private BigDecimal price;

    @JsonProperty("in_stock")
    @Builder.Default
    private Boolean inStock = true;
}
