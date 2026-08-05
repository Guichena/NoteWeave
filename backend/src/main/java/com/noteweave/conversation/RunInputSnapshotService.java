package com.noteweave.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.ChatService;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.memory.MemoryControlPackResponse;
import com.noteweave.memory.MemoryReferenceResponse;
import com.noteweave.security.WorkspaceAccessGuard;
import com.noteweave.security.WorkspacePermission;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RunInputSnapshotService {

    private static final String COMPILER_VERSION = "segment-projection-v1";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final ConversationContextProjectionService contextProjectionService;

    public RunInputSnapshotService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            WorkspaceAccessGuard workspaceAccessGuard,
            ConversationContextProjectionService contextProjectionService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.contextProjectionService = contextProjectionService;
    }

    public void recordAnswerSnapshot(
            String workspaceId,
            TurnReceipt receipt,
            SubmitTurnCommand command,
            ChatService.PreparedAnswerMaterial material
    ) {
        if (exists("ANSWER", receipt.answerRunId())) {
            return;
        }
        JsonNode frozen = frozenPreparation(receipt.submissionId());
        int cutoff = jdbcTemplate.queryForObject(
                "select message_seq from conversation_message where id = ?", Integer.class, receipt.messageId());
        EffectiveRetrievalConfig effectiveConfig = command.effectiveRetrievalConfig();
        Map<String, Object> retrievalConfig = new LinkedHashMap<>();
        retrievalConfig.put("strategy", effectiveConfig.strategy());
        retrievalConfig.put("channels", effectiveConfig.channels());
        retrievalConfig.put("source_scope", effectiveConfig.sourceScope());
        retrievalConfig.put("grounding_refs", effectiveConfig.groundingRefs());
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("history_head_message_id", nullableText(frozen, "history_head_message_id"));
        snapshot.put("conversation_lock_version", frozen.path("conversation_lock_version").asInt());
        snapshot.put("expected_history_head_message_id", nullableText(frozen, "expected_history_head_message_id"));
        snapshot.put("retrieval_plan_version", material.retrievalPlan().version());
        snapshot.put("retrieval_plan", material.retrievalPlan());
        appendContextProjection(
                snapshot,
                material.contextProjection(),
                material.chatControlPack() == null
                        ? List.of()
                        : material.chatControlPack().memoryReferences()
        );
        try {
            jdbcTemplate.update("""
                    insert into run_input_snapshot(
                        id, workspace_id, execution_kind, answer_run_id,
                        conversation_id, query_message_id, assistant_message_id,
                        requested_turn_mode, history_head_message_id, conversation_cutoff_seq,
                        retrieval_config_json, snapshot_json, compiler_version, prompt_version, token_budget_json
                    ) values (?, ?, 'ANSWER', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Ids.newId(), workspaceId, receipt.answerRunId(), command.conversationId(),
                    receipt.messageId(), receipt.assistantMessageId(), command.requestedTurnMode(),
                    nullableText(frozen, "history_head_message_id"), cutoff,
                    objectMapper.writeValueAsString(retrievalConfig), objectMapper.writeValueAsString(snapshot),
                    COMPILER_VERSION, material.promptVersion(), objectMapper.writeValueAsString(Map.of(
                            "maximum_output_tokens", material.maximumOutputTokens())));
        } catch (DuplicateKeyException ignored) {
            // A recovery retry found the snapshot written by the original transaction B.
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot persist run input snapshot", ex);
        }
    }

    public void recordResearchSnapshot(
            String workspaceId,
            TurnReceipt receipt,
            SubmitTurnCommand command
    ) {
        if (exists("RESEARCH", receipt.researchRunId())) {
            return;
        }
        JsonNode frozen = frozenPreparation(receipt.submissionId());
        int cutoff = jdbcTemplate.queryForObject(
                "select message_seq from conversation_message where id = ?", Integer.class, receipt.messageId());
        EffectiveRetrievalConfig effectiveConfig = command.effectiveRetrievalConfig();
        Map<String, Object> retrievalConfig = new LinkedHashMap<>();
        retrievalConfig.put("strategy", effectiveConfig.strategy());
        retrievalConfig.put("channels", effectiveConfig.channels());
        retrievalConfig.put("source_scope", effectiveConfig.sourceScope());
        retrievalConfig.put("grounding_refs", effectiveConfig.groundingRefs());
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("history_head_message_id", nullableText(frozen, "history_head_message_id"));
        snapshot.put("conversation_lock_version", frozen.path("conversation_lock_version").asInt());
        snapshot.put("expected_history_head_message_id", nullableText(frozen, "expected_history_head_message_id"));
        snapshot.put("research_profile", "balanced");
        appendContextProjection(
                snapshot,
                contextProjectionService.select(workspaceId, command.conversationId(), cutoff),
                researchMemoryReferences(workspaceId, receipt.researchRunId())
        );
        try {
            jdbcTemplate.update("""
                    insert into run_input_snapshot(
                        id, workspace_id, execution_kind, research_run_id,
                        conversation_id, query_message_id, assistant_message_id,
                        requested_turn_mode, history_head_message_id, conversation_cutoff_seq,
                        retrieval_config_json, snapshot_json, compiler_version, prompt_version, token_budget_json
                    ) values (?, ?, 'RESEARCH', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, null, ?)
                    """, Ids.newId(), workspaceId, receipt.researchRunId(), command.conversationId(),
                    receipt.messageId(), receipt.assistantMessageId(), command.requestedTurnMode(),
                    nullableText(frozen, "history_head_message_id"), cutoff,
                    objectMapper.writeValueAsString(retrievalConfig), objectMapper.writeValueAsString(snapshot),
                    "research-input-v1", objectMapper.writeValueAsString(Map.of("maximum_output_tokens", 0)));
        } catch (DuplicateKeyException ignored) {
            // An idempotent retry found the original Research snapshot.
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot persist run input snapshot", ex);
        }
    }

    public RunInputSnapshotResponse get(String workspaceId, String executionKind, String runId) {
        workspaceAccessGuard.requirePermission(workspaceId, WorkspacePermission.WORKSPACE_READ);
        if (!"ANSWER".equals(executionKind) && !"RESEARCH".equals(executionKind)) {
            throw new BusinessException("RUN_INPUT_SNAPSHOT_KIND_INVALID", "Unsupported execution kind", HttpStatus.BAD_REQUEST);
        }
        String runColumn = "ANSWER".equals(executionKind) ? "answer_run_id" : "research_run_id";
        List<RunInputSnapshotResponse> rows = jdbcTemplate.query("""
                select id, execution_kind, conversation_id, query_message_id, assistant_message_id,
                       requested_turn_mode, history_head_message_id, conversation_cutoff_seq,
                       retrieval_config_json, snapshot_json, compiler_version, prompt_version,
                       token_budget_json, replay_availability, created_at
                from run_input_snapshot
                where workspace_id = ? and execution_kind = ? and %s = ?
                """.formatted(runColumn), (rs, rowNum) -> new RunInputSnapshotResponse(
                rs.getString("id"), rs.getString("execution_kind"), runId,
                rs.getString("conversation_id"), rs.getString("query_message_id"),
                rs.getString("assistant_message_id"), rs.getString("requested_turn_mode"),
                rs.getString("history_head_message_id"), rs.getInt("conversation_cutoff_seq"),
                readJson(rs.getString("retrieval_config_json")), readJson(rs.getString("snapshot_json")),
                rs.getString("compiler_version"), rs.getString("prompt_version"),
                readJson(rs.getString("token_budget_json")), rs.getString("replay_availability"),
                rs.getTimestamp("created_at").toInstant()
        ), workspaceId, executionKind, runId);
        if (rows.isEmpty()) {
            throw new BusinessException("RUN_INPUT_SNAPSHOT_NOT_FOUND", "Run input snapshot does not exist", HttpStatus.NOT_FOUND);
        }
        return rows.get(0);
    }

    private boolean exists(String executionKind, String runId) {
        String runColumn = "ANSWER".equals(executionKind) ? "answer_run_id" : "research_run_id";
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from run_input_snapshot
                where execution_kind = ? and %s = ?
                """.formatted(runColumn), Integer.class, executionKind, runId);
        return count != null && count > 0;
    }

    private void appendContextProjection(
            Map<String, Object> snapshot,
            ConversationContextProjectionService.Projection projection,
            List<MemoryReferenceResponse> memoryReferences
    ) {
        snapshot.put("segment_summary_refs", projection.segmentSummaryRefs());
        snapshot.put("recent_message_refs", projection.recentMessageRefs());
        snapshot.put("memory_revision_refs", memoryReferences == null ? List.of() : List.copyOf(memoryReferences));
    }

    private List<MemoryReferenceResponse> researchMemoryReferences(String workspaceId, String researchRunId) {
        String controlPackJson = jdbcTemplate.queryForObject("""
                select control_pack_json
                from research_run
                where workspace_id = ? and id = ?
                """, String.class, workspaceId, researchRunId);
        if (controlPackJson == null || controlPackJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(controlPackJson, MemoryControlPackResponse.class).memoryReferences();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored research control pack JSON is invalid", ex);
        }
    }

    private JsonNode frozenPreparation(String submissionId) {
        String json = jdbcTemplate.queryForObject(
                "select preparation_json from turn_submission where id = ?", String.class, submissionId);
        return readJson(json);
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored snapshot JSON is invalid", ex);
        }
    }

    private String nullableText(JsonNode node, String field) {
        return node.path(field).isNull() || node.path(field).isMissingNode() ? null : node.path(field).asText();
    }
}
