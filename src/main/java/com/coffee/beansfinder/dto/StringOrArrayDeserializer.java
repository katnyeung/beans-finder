package com.coffee.beansfinder.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

/**
 * Custom deserializer that handles both String and Array inputs
 * Converts arrays to comma-separated strings
 */
public class StringOrArrayDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.getCodec().readTree(p);

        if (node.isArray()) {
            // Convert array to comma-separated string
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(node.get(i).asText());
            }
            return sb.toString();
        } else if (node.isTextual()) {
            // Return as-is if it's already a string
            return node.asText();
        } else if (node.isNull()) {
            return null;
        } else {
            // Fallback: convert to string
            return node.asText();
        }
    }
}
