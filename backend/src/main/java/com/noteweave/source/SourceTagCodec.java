package com.noteweave.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Canonical source tag decoder shared by metadata recall and retrieval projection. */
@Component
public class SourceTagCodec {
    private final ObjectMapper objectMapper;

    public SourceTagCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<String> decode(String tagsJson) {
        Set<String> tags = new LinkedHashSet<>();
        if (tagsJson == null || tagsJson.isBlank()) return List.of();
        try {
            JsonNode root = objectMapper.readTree(tagsJson);
            if (!root.isArray()) return List.of();
            for (JsonNode node : root) {
                if (node.isTextual()) {
                    add(tags, node.asText());
                } else if (node.isObject()) {
                    String name = node.path("name").asText("");
                    String facet = node.path("facet").asText("");
                    add(tags, facet.isBlank() ? name : facet + ":" + name);
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return new ArrayList<>(tags);
    }

    private void add(Set<String> tags, String value) {
        if (value != null && !value.isBlank()) tags.add(value.trim());
    }
}
