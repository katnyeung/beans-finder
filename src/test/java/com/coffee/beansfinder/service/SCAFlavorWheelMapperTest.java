package com.coffee.beansfinder.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SCAFlavorWheelMapperTest {

    private SCAFlavorWheelMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new SCAFlavorWheelMapper();
    }

    @Test
    void testMapFruityNote() {
        Map<String, String> result = mapper.mapTastingNote("Nashi pear");

        assertEquals("fruity", result.get("category"));
        assertEquals("Nashi pear", result.get("specific"));
        assertNotNull(result.get("subcategory"));
    }

    @Test
    void testMapFloralNote() {
        Map<String, String> result = mapper.mapTastingNote("oolong");

        assertEquals("floral", result.get("category"));
        assertEquals("oolong", result.get("specific"));
    }

    @Test
    void testMapChocolateNote() {
        Map<String, String> result = mapper.mapTastingNote("dark chocolate");

        assertEquals("nutty", result.get("category"));
        assertEquals("dark chocolate", result.get("specific"));
    }

    @Test
    void testMapMultipleNotes() {
        List<String> notes = Arrays.asList("Nashi pear", "oolong", "honey");
        Map<String, Object> result = mapper.mapTastingNotes(notes);

        assertTrue(result.containsKey("fruity"));
        assertTrue(result.containsKey("floral"));
        assertTrue(result.containsKey("sweet"));
    }

    @Test
    void testMapUnknownNote() {
        Map<String, String> result = mapper.mapTastingNote("unknown flavor");

        assertEquals("other", result.get("category"));
        assertEquals("unclassified", result.get("subcategory"));
    }

    @Test
    void testGetPrimaryCategory() {
        String category = mapper.getPrimaryCategory("blueberry");
        assertEquals("fruity", category);
    }

    @Test
    void testCaseInsensitiveMapping() {
        Map<String, String> result1 = mapper.mapTastingNote("CHOCOLATE");
        Map<String, String> result2 = mapper.mapTastingNote("chocolate");

        assertEquals(result1.get("category"), result2.get("category"));
    }

    @Test
    void testGetAllCategories() {
        var categories = mapper.getAllCategories();

        assertTrue(categories.contains("fruity"));
        assertTrue(categories.contains("floral"));
        assertTrue(categories.contains("sweet"));
        assertTrue(categories.contains("nutty"));
        assertEquals(9, categories.size());
    }
}
