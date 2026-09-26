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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Freezes independent Artifact Context at Run creation; v1 remains the Worker input. */
@Service
public class ArtifactContextV2ShadowSnapshotService {
    private static final int SHADOW_BUDGET = 32_768;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ConversationContextCompilerV2Service compiler;
    private final ContextV2RolloutService rollout;
    private final CurrentUserProvider users;

    public ArtifactContextV2ShadowSnapshotService(JdbcTemplate jdbc, ObjectMapper mapper,
            ConversationContextCompilerV2Service compiler, ContextV2RolloutService rollout,
            CurrentUserProvider users) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.compiler = compiler;
        this.rollout = rollout;
        this.users = users;
    }

    public void freeze(String workspaceId, String taskId, String requirement, String skillKey) {
        if (!rollout.shadowEnabled(workspaceId)) return;
        String inputSnapshotId = jdbc.query("""
                select r.input_snapshot_id from artifact_job_run r
                join artifact_run_input_snapshot s on s.id = r.input_snapshot_id
                where r.task_id = ? and s.workspace_id = ? and s.user_requirement = ?
                """, rs -> rs.next() ? rs.getString(1) : null,
                taskId, workspaceId, requirement);
        if (inputSnapshotId == null) {
            throw new IllegalStateException("Artifact Run input snapshot is missing or changed");
        }
        ContextProjectionV2 projection = compiler.compile(workspaceId, users.requireUserId(),
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
        jdbc.update("""
                insert into artifact_context_v2_shadow_snapshot(
                    id, workspace_id, input_snapshot_id, status, projection_json, projection_sha256)
                values (?, ?, ?, 'READY', ?, ?)
                """, id, workspaceId, inputSnapshotId, json, sha256(json));
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
}
