package com.noteweave.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RunReplayRedactionService {

    private static final String EMPTY_CONTENT_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RunReplayRedactionService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
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
        String reference = "%" + messageId + "%";
        jdbcTemplate.update("""
                update run_input_snapshot set replay_availability = 'METADATA_ONLY'
                where workspace_id = ? and replay_availability = 'FULL'
                  and (query_message_id = ? or assistant_message_id = ? or snapshot_json like ?)
                """, workspaceId, messageId, messageId, reference);
    }

    public void redactDeletedSummaryRevision(String revisionId) {
        jdbcTemplate.update("""
                update run_input_snapshot set replay_availability = 'METADATA_ONLY'
                where replay_availability = 'FULL' and snapshot_json like ?
                """, "%" + revisionId + "%");
    }

    public void redactDeletedMemoryRevision(String revisionId) {
        jdbcTemplate.update("""
                update run_input_snapshot set replay_availability = 'METADATA_ONLY'
                where replay_availability = 'FULL' and snapshot_json like ?
                """, "%" + revisionId + "%");
    }

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
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!(root instanceof ArrayNode array)) {
                return false;
            }
            for (JsonNode item : array) {
                if (sourceId.equals(item.path("source_id").asText())) {
                    return true;
                }
            }
            return false;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored Artifact source scope JSON is invalid", ex);
        }
    }

    private boolean artifactUpstreamReferences(
            String json,
            String sourceId,
            String generatedRefId,
            Set<String> snapshotIds
    ) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!(root instanceof ArrayNode array)) {
                return false;
            }
            for (JsonNode item : array) {
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
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored Artifact upstream refs JSON is invalid", ex);
        }
    }

    private String redactArtifactSourceBody(String json, String sourceId) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!(root instanceof ArrayNode array)) {
                return json;
            }
            for (JsonNode item : array) {
                if (item instanceof ObjectNode source
                        && sourceId.equals(source.path("source_id").asText())) {
                    source.put("summary", "");
                    source.put("sample_text", "");
                }
            }
            return objectMapper.writeValueAsString(array);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored Artifact input snapshot JSON is invalid", ex);
        }
    }

    private record ArtifactSnapshotRow(String id, String sourceScopeJson, String upstreamRefsJson) {
    }

    private record DeletedSourceIdentity(String generatedRefId, Set<String> snapshotIds) {
    }
}
