package com.noteweave.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.ChatLlmClient;
import com.noteweave.retrieval.provider.EmbeddingClient;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 先按语义槽位判定，其余再查记忆向量并交给模型。 */
@Service
public class MemoryConflictService {
    private static final Logger log = LoggerFactory.getLogger(MemoryConflictService.class);
    private static final String INDEX_UNAVAILABLE = "MEMORY_CONFLICT_INDEX_UNAVAILABLE";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final MemoryVectorIndex vectorIndex;
    private final ObjectProvider<EmbeddingClient> embeddingClient;
    private final ObjectProvider<ChatLlmClient> llmClient;

    public MemoryConflictService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            MemoryVectorIndex vectorIndex,
            ObjectProvider<EmbeddingClient> embeddingClient,
            ObjectProvider<ChatLlmClient> llmClient
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.vectorIndex = vectorIndex;
        this.embeddingClient = embeddingClient;
        this.llmClient = llmClient;
    }

    public MemorySlotConflict.Outcome assess(
            String workspaceId,
            String taskNeighborhood,
            MemorySlots.Assignment assignment
    ) {
        if (assignment == null || "unknown".equals(assignment.slot())) {
            return MemorySlotConflict.none();
        }
        List<MemorySlotConflict.Peer> peers = sameSlot(workspaceId, taskNeighborhood, assignment.slot());
        MemorySlotConflict.Outcome exact = MemorySlotConflict.exact(assignment, peers);
        if (exact.blocking() || "EXISTING_EQUIVALENT".equals(exact.legacyStatus())) {
            return exact;
        }
        return semantic(workspaceId, taskNeighborhood, assignment);
    }

    private MemorySlotConflict.Outcome semantic(
            String workspaceId,
            String taskNeighborhood,
            MemorySlots.Assignment assignment
    ) {
        EmbeddingClient embedding = embeddingClient.getIfAvailable();
        if (!vectorIndex.available() || embedding == null || !embedding.isEnabled()) {
            return MemorySlotConflict.degraded(INDEX_UNAVAILABLE);
        }
        MemoryVectorIndex.SearchResult result;
        try {
            List<Float> vector = embedding.embedQuery(assignment.value()).singleVector();
            result = vectorIndex.search(workspaceId, taskNeighborhood, vector, 5);
        } catch (RuntimeException ex) {
            log.warn("记忆冲突的向量检索不可用: {}", ex.toString());
            return MemorySlotConflict.degraded(INDEX_UNAVAILABLE);
        }
        if (!result.available()) {
            return MemorySlotConflict.degraded(INDEX_UNAVAILABLE);
        }
        if (result.hits().isEmpty()) {
            return MemorySlotConflict.none();
        }
        ChatLlmClient client = llmClient.getIfAvailable();
        return MemoryConflictJudge.judge(client, assignment.value(), assignment, result.hits());
    }

    private List<MemorySlotConflict.Peer> sameSlot(String workspaceId, String taskNeighborhood, String slot) {
        List<StoredPeer> rows = jdbcTemplate.query("""
                select i.id, i.semantic_slot_key, r.display_text, r.normalized_value_json
                from memory_item i
                join memory_runtime_revision r
                  on r.id = i.current_revision_id and r.memory_item_id = i.id
                where i.workspace_id = ?
                  and i.status = 'ACTIVE'
                  and r.status = 'ACTIVE'
                  and i.semantic_slot_key = ?
                limit 100
                """, (rs, rowNum) -> new StoredPeer(
                rs.getString("id"),
                rs.getString("semantic_slot_key"),
                rs.getString("display_text"),
                rs.getString("normalized_value_json")
        ), workspaceId, slot);
        List<MemorySlotConflict.Peer> peers = new ArrayList<>();
        for (StoredPeer row : rows) {
            PeerView view = read(row);
            if (!view.neighborhoods().contains(taskNeighborhood)) {
                continue;
            }
            peers.add(new MemorySlotConflict.Peer(
                    row.itemId(), row.displayText(), view.slot(), view.value(), view.polarity(), true));
        }
        return peers;
    }

    private PeerView read(StoredPeer row) {
        JsonNode root = readJson(row.payload());
        String type = text(root, "candidate_type", "PREFERENCE");
        String statement = text(root, "statement", row.displayText());
        String slot = text(root, "slot", row.slot());
        String polarity = text(root, "polarity", "");
        String value = text(root, "slot_value", "");
        MemorySlots.Assignment completed = MemorySlots.complete(
                MemorySlots.categoryOf(statement), statement, type, slot, value, polarity);
        List<String> neighborhoods = neighborhoods(root);
        return new PeerView(completed.slot(), completed.value(), completed.polarity(), neighborhoods);
    }

    private List<String> neighborhoods(JsonNode root) {
        JsonNode array = root == null ? null : root.get("task_neighborhoods");
        if (array == null || !array.isArray()) {
            return List.of("COMMON");
        }
        try {
            List<String> values = objectMapper.treeToValue(array, new TypeReference<>() { });
            return values == null || values.isEmpty() ? List.of("COMMON") : values;
        } catch (JsonProcessingException ex) {
            return List.of("COMMON");
        }
    }

    private JsonNode readJson(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(payload);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private static String text(JsonNode root, String field, String fallback) {
        if (root == null) {
            return fallback == null ? "" : fallback;
        }
        String value = root.path(field).asText("");
        return value.isBlank() ? (fallback == null ? "" : fallback) : value;
    }

    private record StoredPeer(String itemId, String slot, String displayText, String payload) {
    }

    private record PeerView(String slot, String value, String polarity, List<String> neighborhoods) {
    }
}
