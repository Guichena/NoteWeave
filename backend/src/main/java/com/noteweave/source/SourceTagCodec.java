package com.noteweave.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;

/** Canonical source tag decoder shared by metadata recall and retrieval projection. */
@Component
public class SourceTagCodec {
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public SourceTagCodec(ObjectMapper objectMapper) {
        this(objectMapper, Metrics.globalRegistry);
    }

    @Autowired
    public SourceTagCodec(ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    public List<String> decode(String tagsJson) {
        Set<String> tags = new LinkedHashSet<>();
        if (tagsJson == null || tagsJson.isBlank()) return List.of();
        try {
            JsonNode root = objectMapper.readTree(tagsJson);
            if (root == null || !root.isArray()) {
                throw invalidTags();
            }
            for (JsonNode node : root) {
                if (node.isTextual()) {
                    add(tags, node.asText());
                } else if (node.isObject()) {
                    String name = node.path("name").asText("");
                    String facet = node.path("facet").asText("");
                    add(tags, facet.isBlank() ? name : facet + ":" + name);
                } else {
                    throw invalidTags();
                }
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw invalidTags();
        }
        return new ArrayList<>(tags);
    }

    private BusinessException invalidTags() {
        meterRegistry.counter("noteweave.source.tags.invalid").increment();
        return new BusinessException(
                "SOURCE_TAG_DATA_INVALID",
                "Stored source tags are invalid",
                HttpStatus.INTERNAL_SERVER_ERROR
        );
    }

    private void add(Set<String> tags, String value) {
        if (value != null && !value.isBlank()) tags.add(value.trim());
    }
}
