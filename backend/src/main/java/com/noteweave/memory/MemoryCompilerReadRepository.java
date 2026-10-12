package com.noteweave.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** Owns the persisted memory read model and canonical payload decoding for compilation. */
final class MemoryCompilerReadRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    MemoryCompilerReadRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    List<StateRow> loadStateRows(String workspaceId) {
        return jdbcTemplate.query("""
                select i.id, r.id as revision_id, i.utility_score,
                       i.memory_scope, i.owner_user_id, i.updated_at,
                       i.semantic_slot_key, i.evidence_alpha, i.evidence_beta,
                       i.last_reinforced_at, i.text_embedding_hash,
                       r.normalized_value_json, r.display_text,
                       r.status, r.valid_from, r.valid_until
                from memory_item i
                join memory_runtime_revision r
                  on r.id = i.current_revision_id
                 and r.memory_item_id = i.id
                where i.workspace_id = ? and i.status = 'ACTIVE'
                  and i.review_status = 'APPROVED'
                  and r.status = 'ACTIVE'
                  and r.valid_from <= current_timestamp
                  and (r.valid_until is null or r.valid_until > current_timestamp)
                """, (rs, rowNum) -> {
            CanonicalPayload payload = readCanonicalPayload(
                    rs.getString("normalized_value_json"), rs.getString("display_text"));
            return new StateRow(
                    rs.getString("id"),
                    rs.getString("revision_id"),
                    rs.getDouble("utility_score"),
                    rs.getString("memory_scope"),
                    rs.getString("owner_user_id"),
                    rs.getTimestamp("updated_at").toInstant(),
                    payload.taskNeighborhoods(),
                    rs.getString("status"),
                    timestampText(rs.getTimestamp("valid_from")),
                    timestampText(rs.getTimestamp("valid_until")),
                    rs.getString("semantic_slot_key"),
                    payload.compileHints(),
                    nullableDouble(rs, "evidence_alpha", 1.5),
                    nullableDouble(rs, "evidence_beta", 1.5),
                    rs.getTimestamp("last_reinforced_at") == null
                            ? null : rs.getTimestamp("last_reinforced_at").toInstant(),
                    rs.getString("display_text"),
                    rs.getString("text_embedding_hash")
            );
        }, workspaceId);
    }

    List<ObjectRow> loadObjectRows(String workspaceId) {
        return jdbcTemplate.query("""
                select i.id, r.id as revision_id, i.utility_score,
                       i.memory_scope, i.owner_user_id, i.updated_at,
                       i.semantic_slot_key, i.evidence_alpha, i.evidence_beta,
                       i.last_reinforced_at, i.text_embedding, i.text_embedding_hash,
                       r.normalized_value_json, r.display_text, r.status
                from memory_item i
                join memory_runtime_revision r
                  on r.id = i.current_revision_id
                 and r.memory_item_id = i.id
                where i.workspace_id = ? and i.status = 'ACTIVE'
                  and i.review_status = 'APPROVED'
                  and r.status = 'ACTIVE'
                  and r.valid_from <= current_timestamp
                  and (r.valid_until is null or r.valid_until > current_timestamp)
                """, (rs, rowNum) -> {
            CanonicalPayload payload = readCanonicalPayload(
                    rs.getString("normalized_value_json"), rs.getString("display_text"));
            return new ObjectRow(
                    rs.getString("id"),
                    rs.getString("revision_id"),
                    rs.getDouble("utility_score"),
                    rs.getString("memory_scope"),
                    rs.getString("owner_user_id"),
                    rs.getTimestamp("updated_at").toInstant(),
                    payload.taskNeighborhoods(),
                    payload.compileHints(),
                    rs.getString("status"),
                    rs.getString("semantic_slot_key"),
                    rs.getString("display_text"),
                    nullableDouble(rs, "evidence_alpha", 1.5),
                    nullableDouble(rs, "evidence_beta", 1.5),
                    rs.getTimestamp("last_reinforced_at") == null
                            ? null : rs.getTimestamp("last_reinforced_at").toInstant(),
                    rs.getString("text_embedding"),
                    rs.getString("text_embedding_hash")
            );
        }, workspaceId);
    }

    List<MemoryReferenceResponse> hydrateReferences(String workspaceId, List<String> memoryObjectIds) {
        if (memoryObjectIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", memoryObjectIds.stream()
                .map(ignored -> "?").toList());
        List<Object> parameters = new ArrayList<>();
        parameters.add(workspaceId);
        parameters.addAll(memoryObjectIds);
        List<MemoryReferenceResponse> rows = jdbcTemplate.query("""
                select i.id, r.id as revision_id, i.utility_score,
                       r.normalized_value_json, r.display_text
                from memory_item i
                join memory_runtime_revision r
                  on r.id = i.current_revision_id
                 and r.memory_item_id = i.id
                where i.workspace_id = ? and i.id in (%s)
                """.formatted(placeholders), (rs, rowNum) -> new MemoryReferenceResponse(
                rs.getString("id"),
                rs.getString("revision_id"),
                rs.getDouble("utility_score")
        ), parameters.toArray());
        Map<String, MemoryReferenceResponse> byId = rows.stream()
                .collect(java.util.stream.Collectors.toMap(
                        MemoryReferenceResponse::memoryObjectId,
                        item -> item));
        return memoryObjectIds.stream()
                .map(byId::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private CanonicalPayload readCanonicalPayload(String json, String displayText) {
        List<String> fallbackStyle = displayText == null || displayText.isBlank()
                ? List.of() : List.of(displayText);
        if (json == null || json.isBlank()) {
            return new CanonicalPayload(
                    List.of("COMMON"),
                    new MemorySignalService.MemoryCompileHints(
                            fallbackStyle, List.of(), List.of(), List.of(), List.of(), List.of()),
                    null);
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            List<String> neighborhoods = readStringListNode(root.path("task_neighborhoods"));
            if (neighborhoods.isEmpty()) {
                neighborhoods = List.of("COMMON");
            }
            JsonNode hintsNode = readNestedJson(root.path("compile_hints"));
            MemorySignalService.MemoryCompileHints hints = hintsNode.isMissingNode() || hintsNode.isNull()
                    ? new MemorySignalService.MemoryCompileHints(
                            fallbackStyle, List.of(), List.of(), List.of(), List.of(), List.of())
                    : objectMapper.treeToValue(hintsNode, MemorySignalService.MemoryCompileHints.class);
            JsonNode utilityNode = root.path("utility_score");
            Double utilityScore = utilityNode.isNumber() ? utilityNode.doubleValue() : null;
            return new CanonicalPayload(neighborhoods, hints, utilityScore);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    "MEMORY_CANONICAL_PAYLOAD_PARSE_FAILED", "Canonical Memory payload parse failed");
        }
    }

    private List<String> readStringListNode(JsonNode node) throws JsonProcessingException {
        JsonNode value = readNestedJson(node);
        if (!value.isArray()) {
            return List.of();
        }
        return objectMapper.treeToValue(value, new TypeReference<>() {
        });
    }

    private JsonNode readNestedJson(JsonNode node) throws JsonProcessingException {
        if (node.isTextual()) {
            return objectMapper.readTree(node.textValue());
        }
        return node;
    }

    private String timestampText(java.sql.Timestamp timestamp) {
        return timestamp == null ? "" : timestamp.toInstant().toString();
    }

    private double nullableDouble(java.sql.ResultSet resultSet, String column, double fallback)
            throws java.sql.SQLException {
        double value = resultSet.getDouble(column);
        return resultSet.wasNull() ? fallback : value;
    }

    record ObjectRow(
            String memoryObjectId,
            String memoryVersionId,
            double utilityScore,
            String memoryScope,
            String ownerUserId,
            Instant updatedAt,
            List<String> taskNeighborhoods,
            MemorySignalService.MemoryCompileHints compileHints,
            String status,
            String semanticSlotKey,
            String displayText,
            double evidenceAlpha,
            double evidenceBeta,
            Instant lastReinforcedAt,
            String textEmbedding,
            String textEmbeddingHash
    ) {
    }

    record StateRow(
            String memoryObjectId,
            String memoryVersionId,
            double utilityScore,
            String memoryScope,
            String ownerUserId,
            Instant updatedAt,
            List<String> taskNeighborhoods,
            String status,
            String validFrom,
            String validTo,
            String semanticSlotKey,
            MemorySignalService.MemoryCompileHints compileHints,
            double evidenceAlpha,
            double evidenceBeta,
            Instant lastReinforcedAt,
            String displayText,
            String textEmbeddingHash
    ) {
    }

    private record CanonicalPayload(
            List<String> taskNeighborhoods,
            MemorySignalService.MemoryCompileHints compileHints,
            Double persistedUtilityScore
    ) {
    }
}
