package com.noteweave.retrieval.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 工作台内复用向量，同时核对完整增强输入和实际发送输入，避免跨资料误用上下文。 */
@Repository
public class SourceEmbeddingCacheRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SourceEmbeddingCacheRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Optional<List<Float>> find(CacheKey key, String fullInputHash,
                                      String effectiveInputHash, String model, int dimensions) {
        List<String> rows = jdbc.query("""
                select vector_json from source_embedding_cache
                where workspace_id = ? and embedding_version = ? and chunker_version = ?
                  and context_prompt_version = ? and content_hash = ?
                  and full_input_hash = ? and effective_input_hash = ?
                  and embedding_model = ? and embedding_dimensions = ?
                """, (rs, index) -> rs.getString("vector_json"), key.workspaceId(), key.embeddingVersion(),
                key.chunkerVersion(), key.contextPromptVersion(), key.contentHash(), fullInputHash,
                effectiveInputHash, model, dimensions);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonNode array = mapper.readTree(rows.get(0));
            if (array == null || !array.isArray() || array.size() != dimensions || dimensions <= 0) {
                return Optional.empty();
            }
            List<Float> vector = new ArrayList<>(dimensions);
            for (JsonNode value : array) {
                if (!value.isNumber() || !Double.isFinite(value.doubleValue())
                        || !Float.isFinite(value.floatValue())) {
                    return Optional.empty();
                }
                vector.add(value.floatValue());
            }
            return Optional.of(List.copyOf(vector));
        } catch (Exception invalidCache) {
            // 旧缓存或损坏缓存没有可复用向量时，重新调用真实向量服务。
            return Optional.empty();
        }
    }

    public void put(CacheKey key, String fullInputHash, String effectiveInputHash,
                    String model, int dimensions, List<Float> vector) {
        if (model == null || model.isBlank() || dimensions <= 0 || vector == null
                || vector.size() != dimensions || vector.stream().anyMatch(v -> v == null || !Float.isFinite(v))) {
            throw new IllegalArgumentException("缓存向量与模型契约不一致");
        }
        String json;
        try {
            json = mapper.writeValueAsString(vector);
        } catch (Exception ex) {
            throw new IllegalStateException("无法保存向量缓存", ex);
        }
        int updated = update(key, fullInputHash, effectiveInputHash, model, dimensions, json);
        if (updated == 0) {
            try {
                jdbc.update("""
                        insert into source_embedding_cache(id, workspace_id, embedding_version, chunker_version,
                            context_prompt_version, content_hash, full_input_hash, effective_input_hash,
                            embedding_model, embedding_dimensions, vector_json)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, UUID.randomUUID().toString(), key.workspaceId(), key.embeddingVersion(),
                        key.chunkerVersion(), key.contextPromptVersion(), key.contentHash(), fullInputHash,
                        effectiveInputHash, model, dimensions, json);
            } catch (DuplicateKeyException concurrentInsert) {
                update(key, fullInputHash, effectiveInputHash, model, dimensions, json);
            }
        }
    }

    private int update(CacheKey key, String fullHash, String effectiveHash,
                        String model, int dimensions, String json) {
        return jdbc.update("""
                update source_embedding_cache set full_input_hash = ?, effective_input_hash = ?,
                    embedding_model = ?, embedding_dimensions = ?, vector_json = ?, updated_at = current_timestamp
                where workspace_id = ? and embedding_version = ? and chunker_version = ?
                  and context_prompt_version = ? and content_hash = ?
                """, fullHash, effectiveHash, model, dimensions, json, key.workspaceId(), key.embeddingVersion(),
                key.chunkerVersion(), key.contextPromptVersion(), key.contentHash());
    }

    public record CacheKey(String workspaceId, String embeddingVersion, String chunkerVersion,
                            String contextPromptVersion, String contentHash) {
        public CacheKey {
            if (workspaceId == null || workspaceId.isBlank() || embeddingVersion == null || embeddingVersion.isBlank()
                    || chunkerVersion == null || chunkerVersion.isBlank()
                    || contextPromptVersion == null || contextPromptVersion.isBlank()
                    || contentHash == null || contentHash.isBlank()) {
                throw new IllegalArgumentException("向量缓存键不能为空");
            }
        }
    }
}
