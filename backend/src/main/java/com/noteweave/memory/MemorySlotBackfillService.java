package com.noteweave.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.noteweave.chat.ChatLlmClient;
import com.noteweave.common.Json;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 一次性补历史记忆的语义槽位。默认关闭，不随启动扫描全库。
 * 补不出或模型不可用时保持 semantic_slot_key 为空，接口显示 unknown。
 */
@Service
public class MemorySlotBackfillService {
    private static final Logger log = LoggerFactory.getLogger(MemorySlotBackfillService.class);
    private static final String PROMPT = """
            把这条记忆偏好归入槽位。只输出一个 JSON 对象：
            {"slot":"answer.style","value":"取值","polarity":"REQUIRE 或 FORBID"}
            slot 使用小写前缀加点号，例如 answer.language、format.structure、terminology.term。
            无法归类时 slot 留空。不要解释。""";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ChatLlmClient llmClient;
    private final boolean enabled;

    public MemorySlotBackfillService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ChatLlmClient llmClient,
            @Value("${noteweave.memory.slot-backfill-enabled:false}") boolean enabled
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.llmClient = llmClient;
        this.enabled = enabled;
    }

    public int backfill(String workspaceId) {
        if (!enabled || workspaceId == null || workspaceId.isBlank()
                || llmClient == null || !llmClient.isEnabled()) {
            return 0;
        }
        List<Row> rows = jdbcTemplate.query("""
                select i.id, r.id as revision_id, r.display_text, r.normalized_value_json
                from memory_item i
                join memory_runtime_revision r on r.id = i.current_revision_id
                where i.workspace_id = ?
                  and i.semantic_slot_key is null
                  and i.status not in ('DELETED', 'SUPERSEDED')
                order by i.created_at, i.id
                limit 50
                """, (rs, rowNum) -> new Row(
                rs.getString("id"),
                rs.getString("revision_id"),
                rs.getString("display_text"),
                rs.getString("normalized_value_json")
        ), workspaceId);
        int updated = 0;
        for (Row row : rows) {
            if (fill(row)) {
                updated++;
            }
        }
        return updated;
    }

    private boolean fill(Row row) {
        try {
            String output = llmClient.streamChat(PROMPT, "记忆：" + row.displayText(), 300, token -> { });
            Assignment assignment = parse(output, row.displayText());
            if (assignment == null) {
                return false;
            }
            String payload = mergedPayload(row.payload(), assignment);
            jdbcTemplate.update("""
                    update memory_runtime_revision set normalized_value_json = ? where id = ?
                    """, payload, row.revisionId());
            jdbcTemplate.update("""
                    update memory_item set semantic_slot_key = ?, updated_at = current_timestamp
                    where id = ? and semantic_slot_key is null
                    """, assignment.slot(), row.itemId());
            return true;
        } catch (RuntimeException ex) {
            log.warn("记忆槽位回填失败: {}", ex.toString());
            return false;
        }
    }

    private Assignment parse(String output, String displayText) {
        if (output == null) {
            return null;
        }
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(output.substring(start, end + 1));
            MemorySlots.Assignment assignment = MemorySlots.complete(
                    MemorySlots.categoryOf(displayText),
                    displayText,
                    "PREFERENCE",
                    root.path("slot").asText(null),
                    root.path("value").asText(null),
                    root.path("polarity").asText(null));
            if (MemorySlots.normalizeSlot(root.path("slot").asText("")) == null) {
                return null;
            }
            return new Assignment(assignment.slot(), assignment.value(), assignment.polarity());
        } catch (Exception ex) {
            return null;
        }
    }

    private String mergedPayload(String payload, Assignment assignment) {
        try {
            JsonNode root = payload == null || payload.isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(payload);
            ObjectNode object = root instanceof ObjectNode node ? node : objectMapper.createObjectNode();
            object.put("slot", assignment.slot());
            object.put("slot_value", assignment.value());
            object.put("polarity", assignment.polarity());
            return Json.write(objectMapper, object);
        } catch (JsonProcessingException ex) {
            return payload;
        }
    }

    private record Row(String itemId, String revisionId, String displayText, String payload) {
    }

    private record Assignment(String slot, String value, String polarity) {
    }
}
