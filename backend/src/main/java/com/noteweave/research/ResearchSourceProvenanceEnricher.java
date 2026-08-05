package com.noteweave.research;

import static com.noteweave.research.ResearchReadModelMapper.blankIfNull;
import static com.noteweave.research.ResearchReadModelMapper.stringValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Enriches an arbitrary Research read-model tree with canonical source origin
 * metadata using one batched source lookup.
 */
@Service
public final class ResearchSourceProvenanceEnricher {

    private final JdbcTemplate jdbcTemplate;

    public ResearchSourceProvenanceEnricher(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void enrich(Object value) {
        LinkedHashSet<String> sourceIds = new LinkedHashSet<>();
        collectSourceIds(value, sourceIds);
        Map<String, SourceOrigin> origins = loadSourceOrigins(new ArrayList<>(sourceIds));
        if (!origins.isEmpty()) {
            enrichValue(value, origins);
        }
    }

    private void collectSourceIds(Object value, LinkedHashSet<String> sourceIds) {
        if (value instanceof Map<?, ?> mapValue) {
            String sourceId = stringValue(mapValue.get("source_id"));
            if (!sourceId.isBlank()) {
                sourceIds.add(sourceId);
            }
            mapValue.values().forEach(nested -> collectSourceIds(nested, sourceIds));
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            iterable.forEach(item -> collectSourceIds(item, sourceIds));
        }
    }

    private void enrichValue(Object value, Map<String, SourceOrigin> origins) {
        if (value instanceof Map<?, ?> mapValue) {
            enrichMap(mapValue, origins);
            new ArrayList<>(mapValue.values()).forEach(nested -> enrichValue(nested, origins));
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            iterable.forEach(item -> enrichValue(item, origins));
        }
    }

    private void enrichMap(Map<?, ?> rawMap, Map<String, SourceOrigin> origins) {
        SourceOrigin origin = origins.get(stringValue(rawMap.get("source_id")));
        if (origin == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<Object, Object> mutable = (Map<Object, Object>) rawMap;
        putIfBlank(mutable, "source_title", origin.title());
        putIfBlank(mutable, "generated_by", origin.generatedBy());
        putIfBlank(mutable, "generated_ref_id", origin.generatedRefId());
    }

    private void putIfBlank(Map<Object, Object> target, String key, String value) {
        if (stringValue(target.get(key)).isBlank() && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private Map<String, SourceOrigin> loadSourceOrigins(List<String> sourceIds) {
        if (sourceIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(sourceIds.size(), "?"));
        return jdbcTemplate.query("""
                select id, title,
                       coalesce(generated_by, '') as generated_by,
                       coalesce(generated_ref_id, '') as generated_ref_id
                from source
                where id in (%s)
                """.formatted(placeholders), rs -> {
            LinkedHashMap<String, SourceOrigin> origins = new LinkedHashMap<>();
            while (rs.next()) {
                origins.put(rs.getString("id"), new SourceOrigin(
                        blankIfNull(rs.getString("title")),
                        blankIfNull(rs.getString("generated_by")),
                        blankIfNull(rs.getString("generated_ref_id"))
                ));
            }
            return origins;
        }, sourceIds.toArray());
    }

    private record SourceOrigin(String title, String generatedBy, String generatedRefId) {
    }
}
