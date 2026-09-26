package com.noteweave.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.security.WorkspaceAccessGuard;
import com.noteweave.security.WorkspacePermission;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Admin-only reference diff; absent gold labels are not interpreted as correctness judgments. */
@Service
public class ContextV2ShadowDiffService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final WorkspaceAccessGuard access;

    public ContextV2ShadowDiffService(JdbcTemplate jdbc, ObjectMapper mapper,
                                      WorkspaceAccessGuard access) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.access = access;
    }

    public ShadowDiff get(String workspaceId, String answerRunId) {
        access.requirePermission(workspaceId, WorkspacePermission.WORKSPACE_ADMIN);
        Integer runExists = jdbc.queryForObject("""
                select count(*) from answer_run where workspace_id = ? and id = ?
                """, Integer.class, workspaceId, answerRunId);
        if (runExists == null || runExists != 1) {
            throw new BusinessException("ANSWER_RUN_NOT_FOUND", "Answer Run not found", HttpStatus.NOT_FOUND);
        }
        V1Row v1 = jdbc.query("""
                select compiler_version, snapshot_json, replay_availability
                from run_input_snapshot where workspace_id = ? and answer_run_id = ?
                """, rs -> rs.next() ? new V1Row(rs.getString(1), rs.getString(2), rs.getString(3)) : null,
                workspaceId, answerRunId);
        ShadowRow shadow = jdbc.query("""
                select status, projection_json, projection_sha256, failure_code
                from context_v2_shadow_snapshot where workspace_id = ? and answer_run_id = ?
                """, rs -> rs.next() ? new ShadowRow(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getString(4)) : null, workspaceId, answerRunId);
        if (shadow == null || !"READY".equals(shadow.status()) && !"REDACTED".equals(shadow.status())) {
            return new ShadowDiff(answerRunId, v1 == null ? "PENDING" : v1.compilerVersion(),
                    shadow == null ? "NOT_RECORDED" : shadow.status(), "",
                    shadow == null ? "SHADOW_NOT_RECORDED" : shadow.failureCode(),
                    v1 == null ? "PENDING" : v1.replayAvailability(), "",
                    RefDiff.empty(), RefDiff.empty(), RefDiff.empty(), 0,
                    "GOLD_LABELS_UNAVAILABLE");
        }
        if (v1 == null) {
            return new ShadowDiff(answerRunId, "PENDING", shadow.status(), "", "V1_NOT_READY",
                    "PENDING", "", RefDiff.empty(), RefDiff.empty(), RefDiff.empty(), 0,
                    "GOLD_LABELS_UNAVAILABLE");
        }
        if (shadow.json() == null || !sha256(shadow.json()).equals(shadow.sha256())) {
            throw new BusinessException("CONTEXT_V2_SHADOW_CORRUPT",
                    "Frozen v2 Context digest does not match", HttpStatus.CONFLICT);
        }
        JsonNode v1Snapshot;
        ContextProjectionV2 v2;
        try {
            v1Snapshot = mapper.readTree(v1.snapshotJson());
            v2 = mapper.readValue(shadow.json(), ContextProjectionV2.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored Context snapshot JSON is invalid", ex);
        }
        return new ShadowDiff(answerRunId, v1.compilerVersion(), shadow.status(),
                v2.compilerVersion(), "", v1.replayAvailability(), v2.replayAvailability(),
                compare(refs(v1Snapshot.path("recent_message_refs"), "message_id"),
                        v2.rawTail().stream().map(ContextProjectionV2.RawMessage::messageId).toList()),
                compare(refs(v1Snapshot.path("segment_summary_refs"), "summary_revision_id"),
                        v2.topicSummaries().stream().map(ContextProjectionV2.TopicSummary::revisionId).toList()),
                compare(refs(v1Snapshot.path("memory_revision_refs"), "memory_version_id"),
                        v2.memoryRevisions().stream().map(ContextProjectionV2.MemoryRevision::revisionId).toList()),
                v2.constraints().size(), "GOLD_LABELS_UNAVAILABLE");
    }

    public ShadowCohort recent(String workspaceId, int limit) {
        access.requirePermission(workspaceId, WorkspacePermission.WORKSPACE_ADMIN);
        if (limit < 1 || limit > 100) {
            throw new BusinessException("CONTEXT_V2_COHORT_LIMIT_INVALID",
                    "Context cohort limit must be between 1 and 100", HttpStatus.BAD_REQUEST);
        }
        List<String> runIds = jdbc.queryForList("""
                select id from answer_run where workspace_id = ?
                order by created_at desc, id desc limit ?
                """, String.class, workspaceId, limit);
        int ready = 0;
        int redacted = 0;
        int failed = 0;
        int notRecorded = 0;
        int runsWithReferenceDifference = 0;
        int onlyV1Messages = 0;
        int onlyV2Messages = 0;
        int onlyV1Summaries = 0;
        int onlyV2Summaries = 0;
        int onlyV1Memories = 0;
        int onlyV2Memories = 0;
        Map<String, Integer> gapCounts = new LinkedHashMap<>();
        for (String runId : runIds) {
            ShadowDiff diff = get(workspaceId, runId);
            switch (diff.shadowStatus()) {
                case "READY" -> ready++;
                case "REDACTED" -> redacted++;
                case "FAILED" -> failed++;
                default -> notRecorded++;
            }
            if (!diff.gapCode().isBlank()) gapCounts.merge(diff.gapCode(), 1, Integer::sum);
            onlyV1Messages += diff.messages().onlyV1().size();
            onlyV2Messages += diff.messages().onlyV2().size();
            onlyV1Summaries += diff.summaries().onlyV1().size();
            onlyV2Summaries += diff.summaries().onlyV2().size();
            onlyV1Memories += diff.memories().onlyV1().size();
            onlyV2Memories += diff.memories().onlyV2().size();
            if (!diff.messages().onlyV1().isEmpty() || !diff.messages().onlyV2().isEmpty()
                    || !diff.summaries().onlyV1().isEmpty() || !diff.summaries().onlyV2().isEmpty()
                    || !diff.memories().onlyV1().isEmpty() || !diff.memories().onlyV2().isEmpty()) {
                runsWithReferenceDifference++;
            }
        }
        return new ShadowCohort(runIds.size(), ready, redacted, failed, notRecorded,
                runsWithReferenceDifference, onlyV1Messages, onlyV2Messages,
                onlyV1Summaries, onlyV2Summaries, onlyV1Memories, onlyV2Memories,
                Map.copyOf(gapCounts), "GOLD_LABELS_UNAVAILABLE");
    }

    private static Set<String> refs(JsonNode array, String field) {
        Set<String> ids = new TreeSet<>();
        if (array.isArray()) for (JsonNode item : array) {
            String id = item.path(field).asText();
            if (!id.isBlank()) ids.add(id);
        }
        return ids;
    }

    private static RefDiff compare(Set<String> v1, List<String> v2Values) {
        Set<String> v2 = new TreeSet<>(v2Values);
        Set<String> v1Only = new TreeSet<>(v1);
        v1Only.removeAll(v2);
        Set<String> v2Only = new TreeSet<>(v2);
        v2Only.removeAll(v1);
        Set<String> shared = new TreeSet<>(v1);
        shared.retainAll(v2);
        return new RefDiff(List.copyOf(v1Only), List.copyOf(v2Only), List.copyOf(shared));
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public record RefDiff(List<String> onlyV1, List<String> onlyV2, List<String> shared) {
        static RefDiff empty() { return new RefDiff(List.of(), List.of(), List.of()); }
    }

    public record ShadowDiff(String answerRunId, String v1CompilerVersion, String shadowStatus,
                             String v2CompilerVersion, String gapCode,
                             String v1ReplayAvailability, String v2ReplayAvailability,
                             RefDiff messages, RefDiff summaries, RefDiff memories,
                             int v2ConstraintCount, String accuracyStatus) {}

    public record ShadowCohort(int sampledRuns, int ready, int redacted, int failed,
                               int notRecorded, int runsWithReferenceDifference,
                               int onlyV1Messages, int onlyV2Messages,
                               int onlyV1Summaries, int onlyV2Summaries,
                               int onlyV1Memories, int onlyV2Memories,
                               Map<String, Integer> gapCounts, String accuracyStatus) {}

    private record V1Row(String compilerVersion, String snapshotJson, String replayAvailability) {}
    private record ShadowRow(String status, String json, String sha256, String failureCode) {}
}
