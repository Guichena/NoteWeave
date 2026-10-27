package com.noteweave.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * artifact_edit_event 窄表的 JDBC 实现（表结构由 B 的 V205 迁移创建）。
 *
 * <p>event_json 是唯一完整载荷：既是 B 投递器直发 Kafka 的消息体，也是
 * changedSectionIds/changedFragments/diffStats 等差异合同字段的持久形式；
 * beforeDigest/afterDigest 等全文摘要内嵌其中。payload_digest 为 event_json
 * 的 SHA-256。A 只写业务列并把 status 初始化为 READY；attempt_count/lease 等
 * 投递列由 B 的 ArtifactEditOutboxDispatcher 维护，B 不重新生成 payload。</p>
 */
@Repository
public class JdbcArtifactEditEventStore implements ArtifactEditEventStore {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcArtifactEditEventStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void insertEvent(ArtifactEditEventRecord event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Artifact edit event must be written in the version transaction");
        }
        String eventJson = writeJson(toEventPayload(event));
        jdbcTemplate.update("""
                insert into artifact_edit_event(
                    id, workspace_id, artifact_job_id, request_id, schema_version,
                    base_version_id, new_version_id, origin_task_id, acting_user_id,
                    event_json, request_digest, payload_digest,
                    status, attempt_count, next_attempt_at, created_at
                ) values (?, ?, ?, ?, 2, ?, ?, ?, ?, ?, ?, ?, 'READY', 0, current_timestamp, current_timestamp)
                """,
                event.editEventId(),
                event.workspaceId(),
                event.artifactJobId(),
                event.requestId(),
                event.baseVersionId(),
                event.artifactVersionId(),
                event.originTaskId(),
                event.actingUserId(),
                eventJson,
                event.requestDigest(),
                ArtifactVersionDiff.sha256(eventJson));
    }

    @Override
    public ArtifactEditEventRecord findByRequestId(String workspaceId, String artifactJobId, String requestId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, workspace_id, artifact_job_id, request_id, request_digest,
                       base_version_id, new_version_id, origin_task_id, acting_user_id, event_json
                from artifact_edit_event
                where workspace_id = ? and artifact_job_id = ? and request_id = ?
                order by created_at desc
                limit 1
                """, workspaceId, artifactJobId, requestId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        Map<String, Object> payload = readJson((String) row.get("event_json"));
        Map<String, Object> diffStats = payload.get("diffStats") instanceof Map<?, ?> map
                ? castMap(map) : Map.of();
        return new ArtifactEditEventRecord(
                (String) row.get("id"),
                (String) row.get("workspace_id"),
                (String) row.get("artifact_job_id"),
                (String) row.get("request_id"),
                (String) row.get("request_digest"),
                (String) row.get("new_version_id"),
                (String) row.get("base_version_id"),
                payload.get("baseVersionNo") instanceof Number number ? number.intValue() : 0,
                (String) row.get("origin_task_id"),
                (String) row.get("acting_user_id"),
                (String) payload.get("beforeDigest"),
                (String) payload.get("afterDigest"),
                readStrings(payload.get("changedSectionIds")),
                readFragments(payload.get("changedFragments")),
                diffStats.get("changedChars") instanceof Number number ? number.intValue() : 0,
                diffStats.get("totalChars") instanceof Number number ? number.intValue() : 0,
                (String) diffStats.get("algorithm"),
                (String) payload.get("payload"),
                (String) payload.get("eventPayloadDigest"),
                (String) payload.get("createdAt"));
    }

    /** 组装投递载荷：schemaVersion=2 差异合同 + 受限版本载荷摘要。 */
    private Map<String, Object> toEventPayload(ArtifactEditEventRecord event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("editEventId", event.editEventId());
        payload.put("workspaceId", event.workspaceId());
        payload.put("artifactJobId", event.artifactJobId());
        payload.put("artifactVersionId", event.artifactVersionId());
        payload.put("baseVersionId", event.baseVersionId());
        payload.put("baseVersionNo", event.baseVersionNo());
        payload.put("originTaskId", event.originTaskId());
        payload.put("actingUserId", event.actingUserId());
        payload.put("requestId", event.requestId());
        payload.put("beforeDigest", event.beforeDigest());
        payload.put("afterDigest", event.afterDigest());
        payload.put("changedSectionIds", event.changedSectionIds());
        payload.put("changedFragments", event.changedFragments());
        Map<String, Object> diffStats = new LinkedHashMap<>();
        diffStats.put("changedChars", event.changedChars());
        diffStats.put("totalChars", event.totalChars());
        diffStats.put("algorithm", event.diffAlgorithm());
        payload.put("diffStats", diffStats);
        // NR06：事件不再内嵌完整版本载荷（长 Markdown 会撑爆事件容量）；
        // 消费者只用身份与差异合同字段，载荷身份由 eventPayloadDigest 携带
        payload.put("eventPayloadDigest", event.eventPayloadDigest());
        payload.put("createdAt", event.createdAt());
        payload.put("schemaVersion", 2);
        return payload;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Artifact edit event payload serialization failed", ex);
        }
    }

    private Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() { });
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private Map<String, Object> castMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private List<String> readStrings(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private List<ArtifactVersionDiffV2.Fragment> readFragments(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(item -> item instanceof Map<?, ?>)
                .map(item -> {
                    Map<?, ?> map = (Map<?, ?>) item;
                    return new ArtifactVersionDiffV2.Fragment(
                            text(map.get("sectionId")),
                            text(map.get("beforeExcerpt")),
                            text(map.get("afterExcerpt")),
                            text(map.get("beforeDigest")),
                            text(map.get("afterDigest")),
                            Boolean.TRUE.equals(map.get("beforeTruncated")),
                            Boolean.TRUE.equals(map.get("afterTruncated")),
                            map.get("beforeChars") instanceof Number number ? number.intValue() : 0,
                            map.get("afterChars") instanceof Number number ? number.intValue() : 0);
                })
                .toList();
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
