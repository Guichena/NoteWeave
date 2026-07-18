package com.noteweave.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import com.noteweave.conversation.RunReplayRedactionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Keeps the legacy object/version API projected into the canonical runtime tables. */
@Service
public class LegacyMemoryRuntimeBridge {

    private final JdbcTemplate jdbcTemplate;
    private final RunReplayRedactionService replayRedactionService;
    private final ObjectMapper objectMapper;

    public LegacyMemoryRuntimeBridge(
            JdbcTemplate jdbcTemplate,
            RunReplayRedactionService replayRedactionService,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.replayRedactionService = replayRedactionService;
        this.objectMapper = objectMapper;
    }

    public void synchronizeActiveVersion(
            String workspaceId,
            String memoryObjectId,
            MemoryVersionResponse version
    ) {
        LegacyObject object = loadObject(workspaceId, memoryObjectId);
        ensureItem(workspaceId, memoryObjectId, object);
        Integer revisionCount = jdbcTemplate.queryForObject("""
                select count(*) from memory_runtime_revision where id = ?
                """, Integer.class, version.memoryVersionId());
        if (revisionCount == null || revisionCount == 0) {
            jdbcTemplate.update("""
                    insert into memory_runtime_revision(
                        id, memory_item_id, workspace_id, version_no, status, confidence,
                        valid_from, valid_until, normalized_value_json, display_text, provenance_type, provenance_ref,
                        content_hash, supersedes_revision_id
                    ) values (?, ?, ?, ?, 'ACTIVE', ?, ?, null, ?, ?,
                              'LEGACY_MEMORY_VERSION', ?, ?, ?)
                    """,
                    version.memoryVersionId(), memoryObjectId, workspaceId, version.versionNo(),
                    version.riskScore(), java.sql.Timestamp.from(version.validFrom()),
                    canonicalPayload(version, object.utilityScore()), version.canonicalStatement(),
                    version.memoryVersionId(), version.memoryVersionId(),
                    version.supersedesVersionId());
        }
        jdbcTemplate.update("update memory_runtime_revision set normalized_value_json = ? where id = ?",
                canonicalPayload(version, object.utilityScore()), version.memoryVersionId());
        jdbcTemplate.update("""
                update memory_runtime_revision
                set status = 'SUPERSEDED', valid_until = current_timestamp
                where memory_item_id = ? and id <> ? and status = 'ACTIVE'
                """, memoryObjectId, version.memoryVersionId());
        jdbcTemplate.update("""
                update memory_item
                set current_revision_id = ?, status = 'ACTIVE', lock_version = lock_version + 1,
                    last_confirmed_at = current_timestamp, updated_at = current_timestamp
                where id = ?
                """, version.memoryVersionId(), memoryObjectId);
        writeEvent(memoryObjectId, "LEGACY_VERSION_SYNC", version.memoryVersionId());
    }

    public void markDeleted(String workspaceId, String memoryObjectId, String revisionId) {
        ensureItem(workspaceId, memoryObjectId, loadObject(workspaceId, memoryObjectId));
        jdbcTemplate.update("""
                update memory_item
                set status = 'DELETED', lock_version = lock_version + 1, updated_at = current_timestamp
                where id = ?
                """, memoryObjectId);
        jdbcTemplate.update("""
                update memory_runtime_revision
                set status = 'REJECTED', valid_until = current_timestamp
                where id = ? and status = 'ACTIVE'
                """, revisionId);
        writeEvent(memoryObjectId, "LEGACY_OBJECT_REVOKED", revisionId);
        replayRedactionService.redactDeletedMemoryRevision(revisionId);
    }

    private LegacyObject loadObject(String workspaceId, String memoryObjectId) {
        return jdbcTemplate.queryForObject("""
                select memory_scope, user_id, utility_score
                from memory_object
                where workspace_id = ? and id = ?
                """, (rs, rowNum) -> new LegacyObject(
                rs.getString("memory_scope"), rs.getString("user_id"),
                rs.getDouble("utility_score")), workspaceId, memoryObjectId);
    }

    private void ensureItem(String workspaceId, String memoryObjectId, LegacyObject object) {
        Integer existing = jdbcTemplate.queryForObject(
                "select count(*) from memory_item where id = ?", Integer.class, memoryObjectId);
        if (existing != null && existing > 0) {
            return;
        }
        String scopeRef = "USER".equals(object.memoryScope()) ? object.ownerUserId() : workspaceId;
        jdbcTemplate.update("""
                insert into memory_item(
                    id, workspace_id, owner_user_id, memory_scope, scope_ref_key, slot_key,
                    slot_schema_version, status, legacy_memory_object_id, lock_version, utility_score
                ) values (?, ?, ?, ?, ?, ?, 'legacy-object-v1', 'ACTIVE', ?, 0, ?)
                """, memoryObjectId, workspaceId, object.ownerUserId(), object.memoryScope(), scopeRef,
                "legacy:" + memoryObjectId, memoryObjectId, object.utilityScore());
    }

    private void writeEvent(String itemId, String eventType, String idempotencyKey) {
        Integer existing = jdbcTemplate.queryForObject("""
                select count(*) from memory_event
                where memory_item_id = ? and event_type = ? and idempotency_key = ?
                """, Integer.class, itemId, eventType, idempotencyKey);
        if (existing == null || existing == 0) {
            jdbcTemplate.update("""
                    insert into memory_event(id, memory_item_id, event_type, idempotency_key)
                    values (?, ?, ?, ?)
                    """, Ids.newId(), itemId, eventType, idempotencyKey);
        }
    }

    private String canonicalPayload(MemoryVersionResponse version, double utilityScore) {
        return Json.write(objectMapper, java.util.Map.of(
                "task_neighborhoods", version.taskNeighborhoods(),
                "compile_hints", version.compileHints(),
                "utility_score", utilityScore
        ));
    }

    private record LegacyObject(String memoryScope, String ownerUserId, double utilityScore) {
    }
}
