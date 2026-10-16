package com.noteweave.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.retrieval.provider.EmbeddingClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 把记忆展示文本的向量写在 memory_item 上。嵌入模型关闭或调用失败时保持原值。 */
@Component
public class MemoryTextEmbeddingStore {

    private static final Logger log = LoggerFactory.getLogger(MemoryTextEmbeddingStore.class);

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingClient embeddingClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public MemoryTextEmbeddingStore(
            JdbcTemplate jdbcTemplate,
            ObjectProvider<EmbeddingClient> embeddingClientProvider,
            ObjectMapper objectMapper
    ) {
        this(jdbcTemplate, embeddingClientProvider.getIfAvailable(), objectMapper);
    }

    MemoryTextEmbeddingStore(JdbcTemplate jdbcTemplate, EmbeddingClient embeddingClient, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingClient = embeddingClient;
        this.objectMapper = objectMapper;
    }

    public void refresh(String workspaceId, String itemId, String displayText) {
        try {
            if (!canEmbed() || displayText == null || displayText.isBlank() || itemId == null) {
                return;
            }
            String text = displayText.strip();
            String hash = sha256(text);
            String current = jdbcTemplate.query("""
                    select text_embedding_hash from memory_item
                    where workspace_id = ? and id = ?
                    """, rs -> rs.next() ? rs.getString(1) : null, workspaceId, itemId);
            if (hash.equals(current)) {
                return;
            }
            List<Float> vector = embeddingClient.embedDocuments(List.of(text)).singleVector();
            jdbcTemplate.update("""
                    update memory_item
                    set text_embedding = ?, text_embedding_hash = ?
                    where workspace_id = ? and id = ?
                    """, objectMapper.writeValueAsString(vector), hash, workspaceId, itemId);
        } catch (Exception ex) {
            log.warn("记忆文本向量未写入: {}", ex.toString());
        }
    }

    public List<Float> vector(
            String workspaceId,
            String itemId,
            String displayText,
            String storedJson,
            String storedHash
    ) {
        if (displayText == null || displayText.isBlank()) {
            return List.of();
        }
        String hash = sha256(displayText.strip());
        if (hash.equals(storedHash)) {
            return parse(storedJson);
        }
        if (!canEmbed()) {
            return parse(storedJson);
        }
        refresh(workspaceId, itemId, displayText);
        String json = jdbcTemplate.query("""
                select text_embedding from memory_item
                where workspace_id = ? and id = ?
                """, rs -> rs.next() ? rs.getString(1) : null, workspaceId, itemId);
        return parse(json);
    }

    private boolean canEmbed() {
        return embeddingClient != null && embeddingClient.isEnabled();
    }

    private List<Float> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Double> values = objectMapper.readValue(json, new TypeReference<>() {
            });
            List<Float> vector = new ArrayList<>(values.size());
            for (Double value : values) {
                vector.add(value == null ? 0f : value.floatValue());
            }
            return List.copyOf(vector);
        } catch (Exception ex) {
            log.warn("记忆文本向量无法解析: {}", ex.toString());
            return List.of();
        }
    }

    private String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
