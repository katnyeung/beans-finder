package com.coffee.beansfinder.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SCAFlavorMapping {

    @Builder.Default
    private List<String> fruity = new ArrayList<>();

    @Builder.Default
    private List<String> floral = new ArrayList<>();

    @Builder.Default
    private List<String> sweet = new ArrayList<>();

    @Builder.Default
    private List<String> nutty = new ArrayList<>();

    @Builder.Default
    private List<String> spices = new ArrayList<>();

    @Builder.Default
    private List<String> roasted = new ArrayList<>();

    @Builder.Default
    private List<String> green = new ArrayList<>();

    @Builder.Default
    private List<String> sour = new ArrayList<>();

    @Builder.Default
    private List<String> other = new ArrayList<>();
}
