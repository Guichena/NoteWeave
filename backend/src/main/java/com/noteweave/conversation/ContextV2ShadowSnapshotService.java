package com.noteweave.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.Ids;
import com.noteweave.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Shadow only: freezes v2 Context before the pending assistant answer is generated. */
@Service
public class ContextV2ShadowSnapshotService {
    private static final int SHADOW_BUDGET_BYTES = 32_768;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ConversationTopicProjectionV2Service topics;
    private final ConversationContextCompilerV2Service compiler;
    private final ContextV2RolloutService rollout;

    public ContextV2ShadowSnapshotService(JdbcTemplate jdbc, ObjectMapper mapper,
                                          ConversationTopicProjectionV2Service topics,
                                          ConversationContextCompilerV2Service compiler,
                                          ContextV2RolloutService rollout) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.topics = topics;
        this.compiler = compiler;
        this.rollout = rollout;
    }

    @Transactional
    public void freezeAnswer(String workspaceId, String actorId, String conversationId,
                             String queryMessageId, String answerRunId,
                             String currentInput, String taskPurpose) {
        if (!rollout.shadowEnabled(workspaceId) || exists(answerRunId)) return;
        Integer cutoff = jdbc.queryForObject("""
                select m.message_seq from answer_run r
                join conversation_message m on m.id = r.query_message_id
                where r.id = ? and r.workspace_id = ? and r.conversation_id = ?
                  and m.id = ? and m.workspace_id = ? and m.conversation_id = ?
                """, Integer.class, answerRunId, workspaceId, conversationId,
                queryMessageId, workspaceId, conversationId);
        if (cutoff == null) throw new IllegalArgumentException("Answer Run query identity is invalid");
        topics.refreshForInputCutoff(workspaceId, conversationId, cutoff);
        ContextProjectionV2 projection = compiler.compile(workspaceId, actorId, conversationId,
                cutoff, currentInput, taskPurpose, SHADOW_BUDGET_BYTES);
        for (ContextProjectionV2.MemoryRevision memory : projection.memoryRevisions()) {
            List<String> valid = jdbc.query("""
                    select i.id from memory_item i
                    join memory_runtime_revision r on r.id = i.current_revision_id
                    where i.id = ? and r.id = ? and i.workspace_id = ?
                      and i.status = 'ACTIVE' and r.status = 'ACTIVE'
                      and r.valid_from <= current_timestamp
                      and (r.valid_until is null or r.valid_until > current_timestamp)
                    for update
                    """, (rs, index) -> rs.getString(1),
                    memory.memoryId(), memory.revisionId(), workspaceId);
            if (valid.size() != 1) {
                throw new BusinessException("CONTEXT_MEMORY_CHANGED",
                        "Memory revision changed while freezing Context", HttpStatus.CONFLICT);
            }
        }
        String snapshotId = Ids.newId();
        String json = write(projection);
        jdbc.update("""
                insert into context_v2_shadow_snapshot(id, workspace_id, answer_run_id,
                    conversation_id, query_message_id, cutoff_seq, status,
                    projection_json, projection_sha256)
                values (?, ?, ?, ?, ?, ?, 'READY', ?, ?)
                """, snapshotId, workspaceId, answerRunId, conversationId,
                queryMessageId, cutoff, json, sha256(json));
        Set<Ref> refs = new LinkedHashSet<>();
        refs.add(new Ref("MESSAGE", queryMessageId));
        projection.rawTail().forEach(message -> refs.add(new Ref("MESSAGE", message.messageId())));
        projection.constraints().forEach(rule -> refs.add(new Ref("MESSAGE", rule.sourceMessageId())));
        projection.topicSummaries().forEach(summary ->
                refs.add(new Ref("TOPIC_SUMMARY", summary.revisionId())));
        projection.memoryRevisions().forEach(memory ->
                refs.add(new Ref("MEMORY_REVISION", memory.revisionId())));
        for (Ref ref : refs) {
            jdbc.update("""
                    insert into context_v2_shadow_ref(snapshot_id, ref_type, ref_id)
                    values (?, ?, ?)
                    """, snapshotId, ref.type(), ref.id());
        }
    }

    /** Read the exact projection frozen before generation when this Workspace consumes v2. */
    public FrozenAnswer readReadyForAnswer(String workspaceId, String conversationId,
                                           String queryMessageId) {
        if (!rollout.activeEnabled(workspaceId)) return null;
        List<FrozenRow> rows = jdbc.query("""
                select s.id, s.status, s.projection_json, s.projection_sha256,
                       s.cutoff_seq, t.actor_user_id
                from context_v2_shadow_snapshot s
                join turn_submission t on t.answer_run_id = s.answer_run_id
                where s.workspace_id = ? and s.conversation_id = ?
                  and s.query_message_id = ?
                """, (rs, index) -> new FrozenRow(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getInt(5), rs.getString(6)),
                workspaceId, conversationId, queryMessageId);
        if (rows.isEmpty() || "FAILED".equals(rows.get(0).status())) return null;
        if (rows.size() != 1 || !"READY".equals(rows.get(0).status())) {
            throw new BusinessException("CONTEXT_V2_SNAPSHOT_UNAVAILABLE",
                    "冻结的 Context v2 已失效", HttpStatus.CONFLICT);
        }
        FrozenRow row = rows.get(0);
        if (row.json() == null || !sha256(row.json()).equals(row.sha256())) {
            throw new BusinessException("CONTEXT_V2_SNAPSHOT_CORRUPT",
                    "冻结的 Context v2 摘要不匹配", HttpStatus.CONFLICT);
        }
        try {
            ContextProjectionV2 projection = mapper.readValue(row.json(), ContextProjectionV2.class);
            if (!workspaceId.equals(projection.workspaceId())
                    || !conversationId.equals(projection.conversationId())
                    || !row.actorId().equals(projection.actorId())
                    || row.cutoffSeq() != projection.cutoffSeq()
                    || !"FULL".equals(projection.replayAvailability())
                    || projection.rawTail().stream().noneMatch(message ->
                    queryMessageId.equals(message.messageId()))) {
                throw new BusinessException("CONTEXT_V2_SNAPSHOT_CORRUPT",
                        "冻结的 Context v2 身份不匹配", HttpStatus.CONFLICT);
            }
            return new FrozenAnswer(row.id(), row.sha256(), projection);
        } catch (BusinessException ex) {
            throw ex;
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw new BusinessException("CONTEXT_V2_SNAPSHOT_CORRUPT",
                    "冻结的 Context v2 无法读取", HttpStatus.CONFLICT);
        }
    }

    @Transactional
    public void recordFailure(String workspaceId, String conversationId,
                              String queryMessageId, String answerRunId, String failureCode) {
        if (!rollout.shadowEnabled(workspaceId) || exists(answerRunId)) return;
        Integer cutoff = jdbc.queryForObject("""
                select message_seq from conversation_message
                where id = ? and workspace_id = ? and conversation_id = ?
                """, Integer.class, queryMessageId, workspaceId, conversationId);
        if (cutoff == null) return;
        try {
            jdbc.update("""
                    insert into context_v2_shadow_snapshot(id, workspace_id, answer_run_id,
                        conversation_id, query_message_id, cutoff_seq, status, failure_code)
                    values (?, ?, ?, ?, ?, ?, 'FAILED', ?)
                    """, Ids.newId(), workspaceId, answerRunId, conversationId,
                    queryMessageId, cutoff, failureCode);
        } catch (DuplicateKeyException ignored) {
            // The successful freeze won a concurrent replay.
        }
    }

    @Transactional
    public int redactReference(String refType, String refId) {
        List<Snapshot> snapshots = jdbc.query("""
                select s.id, s.projection_json from context_v2_shadow_snapshot s
                join context_v2_shadow_ref r on r.snapshot_id = s.id
                where r.ref_type = ? and r.ref_id = ? and s.status = 'READY'
                for update
                """, (rs, index) -> new Snapshot(rs.getString(1), rs.getString(2)), refType, refId);
        int redacted = 0;
        for (Snapshot snapshot : snapshots) {
            ContextProjectionV2 projection;
            try {
                projection = mapper.readValue(snapshot.json(), ContextProjectionV2.class);
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("Stored v2 Context projection is invalid", ex);
            }
            String json = write(projection.redacted());
            redacted += jdbc.update("""
                    update context_v2_shadow_snapshot
                    set status = 'REDACTED', projection_json = ?, projection_sha256 = ?
                    where id = ? and status = 'READY'
                    """, json, sha256(json), snapshot.id());
        }
        return redacted;
    }

    private boolean exists(String answerRunId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from context_v2_shadow_snapshot where answer_run_id = ?
                """, Integer.class, answerRunId);
        return count != null && count > 0;
    }

    private String write(ContextProjectionV2 projection) {
        try {
            return mapper.writeValueAsString(projection);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize v2 Context projection", ex);
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

    private record Ref(String type, String id) {}
    private record Snapshot(String id, String json) {}
    private record FrozenRow(String id, String status, String json, String sha256,
                             int cutoffSeq, String actorId) {}
    public record FrozenAnswer(String snapshotId, String projectionSha256,
                               ContextProjectionV2 projection) {}
}
