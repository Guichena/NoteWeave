package com.noteweave.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.noteweave.artifact.ArtifactContextV2ShadowSnapshotService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class RunReplayRedactionService {

    private static final String EMPTY_CONTENT_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ContextV2ShadowSnapshotService shadowSnapshots;
    private final ArtifactContextV2ShadowSnapshotService artifactShadowSnapshots;

    public RunReplayRedactionService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this(jdbcTemplate, objectMapper, null, null);
    }

    @Autowired
    public RunReplayRedactionService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                     ContextV2ShadowSnapshotService shadowSnapshots,
                                     ArtifactContextV2ShadowSnapshotService artifactShadowSnapshots) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.shadowSnapshots = shadowSnapshots;
        this.artifactShadowSnapshots = artifactShadowSnapshots;
    }

    public void redactDeletedSource(String workspaceId, String sourceId) {
        jdbcTemplate.update("""
                update answer_run_evidence_manifest
                set excerpt = '', content_hash = ?, character_cost = 0
                where workspace_id = ? and source_id = ?
                """, EMPTY_CONTENT_SHA256, workspaceId, sourceId);
        jdbcTemplate.update("""
                update run_input_snapshot
                set replay_availability = 'METADATA_ONLY'
                where workspace_id = ? and execution_kind = 'ANSWER'
                  and replay_availability = 'FULL'
                  and answer_run_id in (
                    select answer_run_id
                    from answer_run_evidence_manifest
                    where workspace_id = ? and source_id = ?
                  )
                """, workspaceId, workspaceId, sourceId);
        redactArtifactSnapshots(workspaceId, sourceId);
        jdbcTemplate.update("""
                update research_evidence_manifest_item
                set excerpt = '', content_hash = ?
                where source_id = ?
                  and manifest_id in (
                    select id from research_evidence_manifest where workspace_id = ?
                  )
                """, EMPTY_CONTENT_SHA256, sourceId, workspaceId);
        jdbcTemplate.update("""
                update run_input_snapshot
                set replay_availability = 'METADATA_ONLY'
                where workspace_id = ? and execution_kind = 'RESEARCH'
                  and replay_availability = 'FULL'
                  and research_run_id in (
                    select manifest.research_run_id
                    from research_evidence_manifest manifest
                    join research_evidence_manifest_item item on item.manifest_id = manifest.id
                    where manifest.workspace_id = ? and item.source_id = ?
                  )
                """, workspaceId, workspaceId, sourceId);
    }

    public void redactDeletedConversationMessage(String workspaceId, String messageId) {
        redactFrozenResearchProjection("MESSAGE", messageId);
        String reference = "%" + messageId + "%";
        jdbcTemplate.update("""
                update run_input_snapshot set replay_availability = 'METADATA_ONLY'
                where workspace_id = ? and replay_availability = 'FULL'
                  and (query_message_id = ? or assistant_message_id = ? or snapshot_json like ?)
                """, workspaceId, messageId, messageId, reference);
        if (shadowSnapshots != null) shadowSnapshots.redactReference("MESSAGE", messageId);
    }

    public void redactDeletedSummaryRevision(String revisionId) {
        redactFrozenResearchProjection("TOPIC_SUMMARY", revisionId);
        jdbcTemplate.update("""
                update run_input_snapshot set replay_availability = 'METADATA_ONLY'
                where replay_availability = 'FULL' and snapshot_json like ?
                """, "%" + revisionId + "%");
        if (shadowSnapshots != null) shadowSnapshots.redactReference("TOPIC_SUMMARY", revisionId);
    }

    public void redactDeletedMemoryRevision(String revisionId) {
        redactFrozenResearchProjection("MEMORY_REVISION", revisionId);
        jdbcTemplate.update("""
                update run_input_snapshot set replay_availability = 'METADATA_ONLY'
                where replay_availability = 'FULL' and snapshot_json like ?
                """, "%" + revisionId + "%");
        jdbcTemplate.update("""
                update artifact_run_input_snapshot
                set control_pack_json = null, replay_availability = 'METADATA_ONLY'
                where id in (
                    select r.input_snapshot_id from artifact_job_run r
                    join memory_usage_log u on u.target_id = r.task_id
                    where u.target_type = 'ARTIFACT_JOB_RUN' and u.memory_revision_id = ?
                )
                """, revisionId);
        jdbcTemplate.update("""
                update artifact_job_run set control_pack_json = null
                where task_id in (
                    select target_id from memory_usage_log
                    where target_type = 'ARTIFACT_JOB_RUN' and memory_revision_id = ?
                )
                """, revisionId);
        jdbcTemplate.update("""
                update artifact_job set control_pack_json = null
                where task_id in (
                    select target_id from memory_usage_log
                    where target_type = 'ARTIFACT_JOB_RUN' and memory_revision_id = ?
                )
                """, revisionId);
        if (shadowSnapshots != null) shadowSnapshots.redactReference("MEMORY_REVISION", revisionId);
        if (artifactShadowSnapshots != null) artifactShadowSnapshots.redactMemoryRevision(revisionId);
    }

    /** Remove only frozen v2 projection text; preserve Research output and audit records. */
    private void redactFrozenResearchProjection(String refType, String refId) {
        List<ResearchProjectionRow> rows = jdbcTemplate.query("""
                select id, snapshot_json from run_input_snapshot
                where execution_kind = 'RESEARCH' and replay_availability = 'FULL'
                  and compiler_version = ? and snapshot_json like ?
                """, (rs, index) -> new ResearchProjectionRow(rs.getString(1), rs.getString(2)),
                ContextWindowPlannerV2.COMPILER_VERSION, "%" + refId + "%");
        for (ResearchProjectionRow row : rows) {
            ObjectNode snapshot;
            ContextProjectionV2 projection;
            try {
                snapshot = (ObjectNode) objectMapper.readTree(row.snapshotJson());
                projection = objectMapper.treeToValue(snapshot.path("context_v2_projection"),
                        ContextProjectionV2.class);
            } catch (JsonProcessingException | ClassCastException ex) {
                throw new IllegalStateException("Stored Research Context projection is invalid", ex);
            }
            boolean referenced = switch (refType) {
                case "MESSAGE" -> projection.rawTail().stream()
                        .anyMatch(message -> refId.equals(message.messageId()))
                        || projection.constraints().stream()
                        .anyMatch(rule -> refId.equals(rule.sourceMessageId()));
                case "TOPIC_SUMMARY" -> projection.topicSummaries().stream()
                        .anyMatch(summary -> refId.equals(summary.revisionId()));
                case "MEMORY_REVISION" -> projection.memoryRevisions().stream()
                        .anyMatch(memory -> refId.equals(memory.revisionId()));
                default -> false;
            };
            if (!referenced) continue;
            ContextProjectionV2 redacted = projection.redacted();
            snapshot.set("context_v2_projection", objectMapper.valueToTree(redacted));
            try {
                String redactedJson = objectMapper.writeValueAsString(redacted);
                snapshot.put("context_v2_projection_sha256", sha256(redactedJson));
                snapshot.put("context_v2_redacted_reason", "SENSITIVE_REFERENCE_REDACTED");
                jdbcTemplate.update("""
                        update run_input_snapshot
                        set snapshot_json = ?, replay_availability = 'METADATA_ONLY'
                        where id = ? and replay_availability = 'FULL'
                        """, objectMapper.writeValueAsString(snapshot), row.id());
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("Cannot redact Research Context projection", ex);
            }
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private record ResearchProjectionRow(String id, String snapshotJson) {}

    private void redactArtifactSnapshots(String workspaceId, String sourceId) {
        DeletedSourceIdentity deleted = loadDeletedSourceIdentity(workspaceId, sourceId);
        List<String> references = new ArrayList<>();
        references.add(sourceId);
        references.addAll(deleted.snapshotIds());
        if (deleted.generatedRefId() != null && !deleted.generatedRefId().isBlank()) {
            references.add(deleted.generatedRefId());
        }
        String predicate = String.join(" or ", references.stream()
                .map(ignored -> "source_scope_snapshot_json like ? or upstream_refs_json like ?")
                .toList());
        List<Object> parameters = new ArrayList<>();
        parameters.add(workspaceId);
        for (String reference : references) {
            parameters.add("%" + reference + "%");
            parameters.add("%" + reference + "%");
        }
        List<ArtifactSnapshotRow> rows = jdbcTemplate.query("""
                select id, source_scope_snapshot_json, upstream_refs_json
                from artifact_run_input_snapshot
                where workspace_id = ? and (%s)
                """.formatted(predicate), (rs, rowNum) -> new ArtifactSnapshotRow(
                rs.getString("id"), rs.getString("source_scope_snapshot_json"),
                rs.getString("upstream_refs_json")), parameters.toArray());
        for (ArtifactSnapshotRow row : rows) {
            boolean sourceScopeMatch = artifactSourceScopeContains(row.sourceScopeJson(), sourceId);
            boolean upstreamMatch = artifactUpstreamReferences(
                    row.upstreamRefsJson(), sourceId, deleted.generatedRefId(), deleted.snapshotIds());
            if (!sourceScopeMatch && !upstreamMatch) {
                continue;
            }
            String redactedSourceScope = sourceScopeMatch
                    ? redactArtifactSourceBody(row.sourceScopeJson(), sourceId)
                    : row.sourceScopeJson();
            jdbcTemplate.update("""
                    update artifact_run_input_snapshot
                    set source_scope_snapshot_json = ?, replay_availability = 'METADATA_ONLY'
                    where id = ?
                    """, redactedSourceScope, row.id());
        }
    }

    private DeletedSourceIdentity loadDeletedSourceIdentity(String workspaceId, String sourceId) {
        String generatedRefId = jdbcTemplate.query("""
                select generated_ref_id from source where workspace_id = ? and id = ?
                """, rs -> rs.next() ? rs.getString("generated_ref_id") : null, workspaceId, sourceId);
        List<String> snapshotIds = jdbcTemplate.query(
                "select id from source_snapshot where source_id = ?",
                (rs, rowNum) -> rs.getString("id"), sourceId);
        return new DeletedSourceIdentity(generatedRefId, Set.copyOf(snapshotIds));
    }

    private boolean artifactSourceScopeContains(String json, String sourceId) {
        ArrayNode array = readArtifactArray(json, "source scope");
        for (JsonNode item : array) {
            requireArtifactObject(item, "source scope");
            if (sourceId.equals(item.path("source_id").asText())) {
                return true;
            }
        }
        return false;
    }

    private boolean artifactUpstreamReferences(
            String json,
            String sourceId,
            String generatedRefId,
            Set<String> snapshotIds
    ) {
        ArrayNode array = readArtifactArray(json, "upstream refs");
        for (JsonNode item : array) {
            requireArtifactObject(item, "upstream refs");
            String refType = item.path("ref_type").asText();
            String refId = item.path("ref_id").asText();
            String revisionId = item.path("revision_id").asText();
            if ("SOURCE_SNAPSHOT".equals(refType)
                    && sourceId.equals(refId)
                    && snapshotIds.contains(revisionId)) {
                return true;
            }
            if ("RESEARCH_REPORT".equals(refType)
                    && generatedRefId != null
                    && generatedRefId.equals(refId)
                    && snapshotIds.contains(revisionId)) {
                return true;
            }
        }
        return false;
    }

    private String redactArtifactSourceBody(String json, String sourceId) {
        ArrayNode array = readArtifactArray(json, "source scope");
        for (JsonNode item : array) {
            requireArtifactObject(item, "source scope");
            ObjectNode source = (ObjectNode) item;
            if (sourceId.equals(source.path("source_id").asText())) {
                source.put("summary", "");
                source.put("sample_text", "");
            }
        }
        try {
            return objectMapper.writeValueAsString(array);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored Artifact input snapshot JSON is invalid", ex);
        }
    }

    private ArrayNode readArtifactArray(String json, String field) {
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("Stored Artifact " + field + " JSON is empty");
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!(root instanceof ArrayNode array)) {
                throw new IllegalStateException("Stored Artifact " + field + " JSON must be an array");
            }
            return array;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored Artifact " + field + " JSON is invalid", ex);
        }
    }

    private void requireArtifactObject(JsonNode item, String field) {
        if (!(item instanceof ObjectNode)) {
            throw new IllegalStateException("Stored Artifact " + field + " JSON contains a non-object item");
        }
    }

    private record ArtifactSnapshotRow(String id, String sourceScopeJson, String upstreamRefsJson) {
    }

    private record DeletedSourceIdentity(String generatedRefId, Set<String> snapshotIds) {
    }
}
