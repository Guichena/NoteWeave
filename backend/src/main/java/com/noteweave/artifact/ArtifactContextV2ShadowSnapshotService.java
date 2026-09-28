package com.noteweave.artifact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.conversation.ContextProjectionV2;
import com.noteweave.conversation.ContextV2RolloutService;
import com.noteweave.conversation.ConversationContextCompilerV2Service;
import com.noteweave.security.CurrentUserProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Freezes independent Artifact Context at Run creation and selects active consumption per Run. */
@Service
public class ArtifactContextV2ShadowSnapshotService {
    private static final int SHADOW_BUDGET = 32_768;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ConversationContextCompilerV2Service compiler;
    private final ContextV2RolloutService rollout;
    private final CurrentUserProvider users;
    private final boolean globalArtifactActiveEnabled;

    public ArtifactContextV2ShadowSnapshotService(JdbcTemplate jdbc, ObjectMapper mapper,
            ConversationContextCompilerV2Service compiler, ContextV2RolloutService rollout,
            CurrentUserProvider users,
            @Value("${noteweave.context.v2.artifact-active-enabled:false}")
            boolean globalArtifactActiveEnabled) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.compiler = compiler;
        this.rollout = rollout;
        this.users = users;
        this.globalArtifactActiveEnabled = globalArtifactActiveEnabled;
    }

    public void freeze(String workspaceId, String taskId, String requirement, String skillKey) {
        if (!rollout.shadowEnabled(workspaceId)) return;
        freezeForActor(workspaceId, taskId, requirement, skillKey, users.requireUserId());
    }

    void freezeForActor(String workspaceId, String taskId, String requirement,
                        String skillKey, String actorUserId) {
        if (!rollout.shadowEnabled(workspaceId)) return;
        if (actorUserId == null || actorUserId.isBlank()) {
            throw new IllegalArgumentException("Artifact actor is required");
        }
        String inputSnapshotId = jdbc.query("""
                select r.input_snapshot_id from artifact_job_run r
                join artifact_run_input_snapshot s on s.id = r.input_snapshot_id
                where r.task_id = ? and s.workspace_id = ? and s.user_requirement = ?
                """, rs -> rs.next() ? rs.getString(1) : null,
                taskId, workspaceId, requirement);
        if (inputSnapshotId == null) {
            throw new IllegalStateException("Artifact Run input snapshot is missing or changed");
        }
        ContextProjectionV2 projection = compiler.compile(workspaceId, actorUserId,
                "", 0, requirement, "ARTIFACT:" + skillKey, SHADOW_BUDGET);
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
                        "Memory revision changed while freezing Artifact Context", HttpStatus.CONFLICT);
            }
        }
        String json = write(projection);
        String id = Ids.newId();
        String consumptionMode = globalArtifactActiveEnabled && rollout.activeEnabled(workspaceId)
                ? "ACTIVE" : "SHADOW";
        jdbc.update("""
                insert into artifact_context_v2_shadow_snapshot(
                    id, workspace_id, input_snapshot_id, status, consumption_mode,
                    projection_json, projection_sha256)
                values (?, ?, ?, 'READY', ?, ?, ?)
                """, id, workspaceId, inputSnapshotId, consumptionMode, json, sha256(json));
        for (ContextProjectionV2.MemoryRevision memory : projection.memoryRevisions()) {
            jdbc.update("""
                    insert into artifact_context_v2_shadow_ref(snapshot_id, ref_type, ref_id)
                    values (?, 'MEMORY_REVISION', ?)
                    """, id, memory.revisionId());
        }
    }

    public int redactMemoryRevision(String revisionId) {
        List<Snapshot> snapshots = jdbc.query("""
                select s.id, s.projection_json from artifact_context_v2_shadow_snapshot s
                join artifact_context_v2_shadow_ref r on r.snapshot_id = s.id
                where r.ref_type = 'MEMORY_REVISION' and r.ref_id = ? and s.status = 'READY'
                for update
                """, (rs, index) -> new Snapshot(rs.getString(1), rs.getString(2)), revisionId);
        int redacted = 0;
        for (Snapshot snapshot : snapshots) {
            try {
                String json = write(mapper.readValue(snapshot.json(), ContextProjectionV2.class).redacted());
                redacted += jdbc.update("""
                        update artifact_context_v2_shadow_snapshot
                        set status = 'REDACTED', projection_json = ?, projection_sha256 = ?
                        where id = ? and status = 'READY'
                        """, json, sha256(json), snapshot.id());
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("Stored Artifact Context projection is invalid", ex);
            }
        }
        return redacted;
    }

    public ArtifactContextV2ShadowInputResponse readForWorker(String taskId) {
        List<Snapshot> snapshots = jdbc.query("""
                select s.id, s.projection_json from artifact_context_v2_shadow_snapshot s
                join artifact_job_run r on r.input_snapshot_id = s.input_snapshot_id
                where r.task_id = ? and s.status = 'READY'
                """, (rs, index) -> new Snapshot(rs.getString(1), rs.getString(2)), taskId);
        if (snapshots.isEmpty()) return null;
        if (snapshots.size() != 1) {
            throw new IllegalStateException("Artifact Context shadow identity is duplicated");
        }
        Snapshot snapshot = snapshots.get(0);
        String storedSha = jdbc.queryForObject("""
                select projection_sha256 from artifact_context_v2_shadow_snapshot where id = ?
                """, String.class, snapshot.id());
        if (!sha256(snapshot.json()).equals(storedSha)) {
            throw new IllegalStateException("Artifact Context shadow digest mismatch");
        }
        try {
            ContextProjectionV2 projection = mapper.readValue(snapshot.json(), ContextProjectionV2.class);
            return new ArtifactContextV2ShadowInputResponse(snapshot.id(), storedSha,
                    projection.compilerVersion(), projection.memoryRevisions().stream()
                    .map(ContextProjectionV2.MemoryRevision::revisionId).toList(),
                    projection.replayAvailability());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored Artifact Context projection is invalid", ex);
        }
    }

    /** Returns the Run's frozen active requirement, or null for v1 and shadow Runs. */
    public String activeRequirement(String taskId, String workspaceId,
                                    String inputSnapshotId, String originalRequirement) {
        List<ActiveSnapshot> rows = jdbc.query("""
                select s.id, s.status, s.projection_json, s.projection_sha256
                from artifact_context_v2_shadow_snapshot s
                join artifact_job_run r on r.input_snapshot_id = s.input_snapshot_id
                where r.task_id = ? and s.input_snapshot_id = ? and s.workspace_id = ?
                  and s.consumption_mode = 'ACTIVE'
                """, (rs, index) -> new ActiveSnapshot(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getString(4)), taskId, inputSnapshotId, workspaceId);
        if (rows.isEmpty()) return null;
        if (rows.size() != 1) throw unavailable();
        ActiveSnapshot row = rows.get(0);
        if (!"READY".equals(row.status()) || !sha256(row.json()).equals(row.sha256())) {
            throw unavailable();
        }
        try {
            ContextProjectionV2 projection = mapper.readValue(row.json(), ContextProjectionV2.class);
            if (!"FULL".equals(projection.replayAvailability())
                    || !workspaceId.equals(projection.workspaceId())
                    || !originalRequirement.equals(projection.currentInput())
                    || !projection.conversationId().isEmpty() || projection.cutoffSeq() != 0) {
                throw unavailable();
            }
            StringBuilder brief = new StringBuilder(projection.currentInput());
            if (!projection.memoryRevisions().isEmpty()) {
                brief.append("\n\n已冻结的用户偏好（仅作表达与格式控制；事实与引用仍须来自 Source）：");
                projection.memoryRevisions().forEach(memory -> brief.append("\n- ").append(memory.text()));
            }
            return brief.toString();
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw unavailable();
        }
    }

    private static BusinessException unavailable() {
        return new BusinessException("ARTIFACT_CONTEXT_V2_UNAVAILABLE",
                "Frozen Artifact Context is unavailable", HttpStatus.CONFLICT);
    }

    private String write(ContextProjectionV2 projection) {
        try {
            return mapper.writeValueAsString(projection);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize Artifact Context projection", ex);
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

    private record Snapshot(String id, String json) {}
    private record ActiveSnapshot(String id, String status, String json, String sha256) {}
}
