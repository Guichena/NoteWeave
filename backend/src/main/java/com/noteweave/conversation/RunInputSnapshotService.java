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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
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
    private final ContextV2RolloutService contextV2Rollout;

    public RunInputSnapshotService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            WorkspaceAccessGuard workspaceAccessGuard,
            ConversationContextProjectionService contextProjectionService,
            ContextV2RolloutService contextV2Rollout
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.contextProjectionService = contextProjectionService;
        this.contextV2Rollout = contextV2Rollout;
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
        List<MemoryReferenceResponse> memoryRefs = material.chatControlPack() == null
                ? List.of() : material.chatControlPack().memoryReferences();
        String compilerVersion = COMPILER_VERSION;
        Map<String, Object> tokenBudget = new LinkedHashMap<>();
        tokenBudget.put("maximum_output_tokens", material.maximumOutputTokens());
        if (material.frozenContextV2() == null) {
            appendContextProjection(snapshot, material.contextProjection(), memoryRefs);
            if (contextV2Rollout.activeEnabled(workspaceId)) {
                String failure = jdbcTemplate.query("""
                        select status, failure_code from context_v2_shadow_snapshot
                        where workspace_id = ? and answer_run_id = ?
                        """, rs -> rs.next()
                        ? ("FAILED".equals(rs.getString(1)) ? rs.getString(2) : "SNAPSHOT_UNAVAILABLE")
                        : "SHADOW_NOT_RECORDED", workspaceId, receipt.answerRunId());
                snapshot.put("context_v2_fallback_code", failure);
            }
        } else {
            var frozenV2 = material.frozenContextV2();
            String persistedJson = jdbcTemplate.query("""
                    select projection_json from context_v2_shadow_snapshot
                    where id = ? and workspace_id = ? and answer_run_id = ?
                      and conversation_id = ? and query_message_id = ?
                      and status = 'READY' and projection_sha256 = ?
                    """, rs -> rs.next() ? rs.getString(1) : null, frozenV2.snapshotId(), workspaceId,
                    receipt.answerRunId(), command.conversationId(), receipt.messageId(),
                    frozenV2.projectionSha256());
            if (persistedJson == null || !sha256(persistedJson).equals(frozenV2.projectionSha256())) {
                throw new BusinessException("CONTEXT_V2_SNAPSHOT_CHANGED",
                        "生成所用的 Context v2 冻结快照已改变", HttpStatus.CONFLICT);
            }
            ContextProjectionV2 projection = frozenV2.projection();
            compilerVersion = projection.compilerVersion();
            snapshot.put("context_v2_snapshot_id", frozenV2.snapshotId());
            snapshot.put("context_v2_projection_sha256", frozenV2.projectionSha256());
            snapshot.put("context_v2_raw_message_refs", projection.rawTail().stream()
                    .map(message -> Map.of("message_id", message.messageId(),
                            "message_seq", message.seq(), "content_sha256", message.contentSha256()))
                    .toList());
            snapshot.put("context_v2_topic_summary_refs", projection.topicSummaries().stream()
                    .map(summary -> Map.of("segment_id", summary.segmentId(),
                            "summary_revision_id", summary.revisionId(),
                            "content_sha256", summary.contentSha256()))
                    .toList());
            snapshot.put("context_v2_constraint_refs", projection.constraints().stream()
                    .map(rule -> Map.of("constraint_id", rule.constraintId(),
                            "source_message_id", rule.sourceMessageId(), "scope", rule.scope()))
                    .toList());
            snapshot.put("memory_revision_refs", memoryRefs);
            tokenBudget.put("context_v2_budget_tokens", projection.tokenBudget());
            tokenBudget.put("context_v2_selected_tokens", projection.selectedTokens());
        }
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
                    compilerVersion, material.promptVersion(), objectMapper.writeValueAsString(tokenBudget));
        } catch (DuplicateKeyException ignored) {
            // A recovery retry found the snapshot written by the original transaction B.
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot persist run input snapshot", ex);
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public void recordResearchSnapshot(
            String workspaceId,
            TurnReceipt receipt,
            SubmitTurnCommand command,
            String contextSnapshotId,
            ContextProjectionV2 projection,
            String fallbackCode
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
        List<MemoryReferenceResponse> memoryRefs = researchMemoryReferences(workspaceId, receipt.researchRunId());
        String compilerVersion = "research-input-v1";
        String promptVersion = null;
        Map<String, Object> tokenBudget = new LinkedHashMap<>();
        tokenBudget.put("maximum_output_tokens", 0);
        if (projection == null) {
            appendContextProjection(snapshot,
                    contextProjectionService.select(workspaceId, command.conversationId(), cutoff),
                    memoryRefs);
            if (fallbackCode != null) snapshot.put("context_v2_fallback_code", fallbackCode);
        } else {
            if (contextSnapshotId == null || !workspaceId.equals(projection.workspaceId())
                    || !command.conversationId().equals(projection.conversationId())
                    || cutoff != projection.cutoffSeq()
                    || !command.content().equals(projection.currentInput())
                    || !"FULL".equals(projection.replayAvailability())) {
                throw new BusinessException("CONTEXT_V2_RESEARCH_SNAPSHOT_INVALID",
                        "Research execution Context identity is invalid", HttpStatus.CONFLICT);
            }
            String linkedId = jdbcTemplate.queryForObject("""
                    select context_snapshot_id from research_run
                    where id = ? and workspace_id = ? and query_message_id = ?
                    """, String.class, receipt.researchRunId(), workspaceId, receipt.messageId());
            if (!contextSnapshotId.equals(linkedId)) {
                throw new BusinessException("CONTEXT_V2_RESEARCH_SNAPSHOT_INVALID",
                        "Research execution Context is not linked to this Run", HttpStatus.CONFLICT);
            }
            compilerVersion = projection.compilerVersion();
            promptVersion = "research-context-question-v2-a1";
            snapshot.put("context_v2_projection", projection);
            snapshot.put("context_v2_projection_sha256", sha256(writeJson(projection)));
            snapshot.put("context_v2_raw_message_refs", projection.rawTail().stream()
                    .map(message -> Map.of("message_id", message.messageId(),
                            "message_seq", message.seq(), "content_sha256", message.contentSha256()))
                    .toList());
            snapshot.put("context_v2_topic_summary_refs", projection.topicSummaries().stream()
                    .map(summary -> Map.of("segment_id", summary.segmentId(),
                            "summary_revision_id", summary.revisionId(),
                            "content_sha256", summary.contentSha256()))
                    .toList());
            snapshot.put("context_v2_constraint_refs", projection.constraints().stream()
                    .map(rule -> Map.of("constraint_id", rule.constraintId(),
                            "source_message_id", rule.sourceMessageId(), "scope", rule.scope()))
                    .toList());
            snapshot.put("memory_revision_refs", memoryRefs);
            tokenBudget.put("context_v2_budget_tokens", projection.tokenBudget());
            tokenBudget.put("context_v2_selected_tokens", projection.selectedTokens());
        }
        try {
            jdbcTemplate.update("""
                    insert into run_input_snapshot(
                        id, workspace_id, execution_kind, research_run_id,
                        conversation_id, query_message_id, assistant_message_id,
                        requested_turn_mode, history_head_message_id, conversation_cutoff_seq,
                        retrieval_config_json, snapshot_json, compiler_version, prompt_version, token_budget_json
                    ) values (?, ?, 'RESEARCH', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, contextSnapshotId == null ? Ids.newId() : contextSnapshotId,
                    workspaceId, receipt.researchRunId(), command.conversationId(),
                    receipt.messageId(), receipt.assistantMessageId(), command.requestedTurnMode(),
                    nullableText(frozen, "history_head_message_id"), cutoff,
                    objectMapper.writeValueAsString(retrievalConfig), objectMapper.writeValueAsString(snapshot),
                    compilerVersion, promptVersion, objectMapper.writeValueAsString(tokenBudget));
        } catch (DuplicateKeyException ignored) {
            // An idempotent retry found the original Research snapshot.
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot persist run input snapshot", ex);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize frozen Research Context", ex);
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
