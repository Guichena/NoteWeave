package com.noteweave.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import com.noteweave.knowledge.WikiIngestService;
import com.noteweave.source.SourceCatalogVersionService;
import com.noteweave.source.SourceParseService;
import com.noteweave.storage.ObjectStorage;
import com.noteweave.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResearchArtifactService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final WorkspaceService workspaceService;
    private final ObjectStorage storage;
    private final SourceParseService sourceParseService;
    private final WikiIngestService wikiIngestService;
    private final SourceCatalogVersionService sourceCatalogVersionService;

    public ResearchArtifactService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            WorkspaceService workspaceService,
            ObjectStorage storage,
            SourceParseService sourceParseService,
            WikiIngestService wikiIngestService,
            SourceCatalogVersionService sourceCatalogVersionService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.workspaceService = workspaceService;
        this.storage = storage;
        this.sourceParseService = sourceParseService;
        this.wikiIngestService = wikiIngestService;
        this.sourceCatalogVersionService = sourceCatalogVersionService;
    }

    @Transactional
    public SaveResearchReportSourceResponse saveReportAsSource(String workspaceId, String researchRunId) {
        requireWorkspace(workspaceId);
        SaveReportRow row = loadSaveReportRow(workspaceId, researchRunId);
        if (row.reportSourceId() != null && !row.reportSourceId().isBlank()) {
            return loadSavedReportSource(workspaceId, row.reportSourceId());
        }
        if (!"COMPLETED".equals(row.status())
                || row.finalReportMarkdown() == null
                || row.finalReportMarkdown().isBlank()) {
            throw new BusinessException(
                    "RESEARCH_REPORT_NOT_READY",
                    "Research report is not complete and cannot be saved as a source"
            );
        }

        byte[] reportBytes = row.finalReportMarkdown().getBytes(StandardCharsets.UTF_8);
        String contentHash = sha256(reportBytes);
        FileObjectRef fileObject = getOrCreateGeneratedFileObject(
                workspaceId,
                researchRunId,
                row.finalReportTitle(),
                contentHash,
                reportBytes
        );
        String sourceId = Ids.newId();
        String snapshotId = Ids.newId();
        ResearchReportFileResponse reportFile = writeResearchReportFile(
                workspaceId, researchRunId, reportBytes);
        jdbcTemplate.update("""
                insert into source(
                    id, workspace_id, file_object_id, title, source_type, status,
                    parse_status, index_status, generated_by, generated_ref_id, created_by, updated_by
                )
                values (?, ?, ?, ?, 'GENERATED_RESEARCH_REPORT', 'PROCESSING', 'PENDING', 'PENDING',
                        'research_agent', ?, 'SYSTEM:RESEARCH', 'SYSTEM:RESEARCH')
                """, sourceId, workspaceId, fileObject.id(), reportTitle(row), researchRunId);
        sourceCatalogVersionService.bump(workspaceId);
        jdbcTemplate.update("""
                insert into source_snapshot(
                    id, source_id, file_object_id, version_no, object_key, sha256, parse_status, index_status
                ) values (?, ?, ?, 1, ?, ?, 'PENDING', 'PENDING')
                """, snapshotId, sourceId, fileObject.id(), reportFile.objectKey(), contentHash);

        sourceParseService.parseAndIndex(workspaceId, sourceId, snapshotId, reportBytes);
        wikiIngestService.enqueueAndRunSourceIngestIfEnabled(workspaceId, sourceId);
        jdbcTemplate.update("""
                update research_run
                set report_source_id = ?, updated_at = current_timestamp
                where id = ?
                """, sourceId, researchRunId);
        insertTrace(researchRunId, "REPORT_SAVED_AS_SOURCE", reportTitle(row), Map.of(
                "source_id", sourceId,
                "generated_by", "research_agent"
        ));
        return loadSavedReportSource(workspaceId, sourceId);
    }

    public ResearchEvidenceManifestResponse evidenceManifest(String workspaceId, String researchRunId) {
        requireWorkspace(workspaceId);
        requireResearchRun(workspaceId, researchRunId);
        ResearchEvidenceManifestResponse header = jdbcTemplate.query("""
                select id, report_content_hash, created_at
                from research_evidence_manifest
                where workspace_id = ? and research_run_id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException(
                        "RESEARCH_EVIDENCE_MANIFEST_NOT_FOUND",
                        "Research evidence manifest does not exist"
                );
            }
            return new ResearchEvidenceManifestResponse(
                    rs.getString("id"), researchRunId, rs.getString("report_content_hash"), List.of(),
                    rs.getTimestamp("created_at").toInstant());
        }, workspaceId, researchRunId);
        List<ResearchEvidenceManifestResponse.Evidence> evidence = jdbcTemplate.query("""
                select rank_no, evidence_id, source_id, source_snapshot_id, passage_id,
                       title, excerpt, content_hash, location_info
                from research_evidence_manifest_item
                where manifest_id = ?
                order by rank_no asc
                """, (rs, rowNum) -> new ResearchEvidenceManifestResponse.Evidence(
                rs.getInt("rank_no"), rs.getString("evidence_id"), rs.getString("source_id"),
                rs.getString("source_snapshot_id"), rs.getString("passage_id"), rs.getString("title"),
                rs.getString("excerpt"), rs.getString("content_hash"), rs.getString("location_info")
        ), header.manifestId());
        return new ResearchEvidenceManifestResponse(
                header.manifestId(), header.runId(), header.reportContentHash(), evidence, header.createdAt());
    }

    public Map<String, SaveResearchReportSourceResponse> loadSavedReportSources(
            String workspaceId,
            List<String> sourceIds
    ) {
        List<String> distinctSourceIds = sourceIds.stream().distinct().toList();
        if (distinctSourceIds.isEmpty()) {
            return Map.of();
        }
        List<Object> parameters = new java.util.ArrayList<>();
        parameters.add(workspaceId);
        parameters.addAll(distinctSourceIds);
        return jdbcTemplate.query("""
                select id, title, source_type, status, parse_status, index_status,
                       coalesce(generated_by, '') as generated_by,
                       coalesce(generated_ref_id, '') as generated_ref_id
                from source
                where workspace_id = ? and id in (%s)
                """.formatted(placeholders(distinctSourceIds.size())), rs -> {
            LinkedHashMap<String, SaveResearchReportSourceResponse> results = new LinkedHashMap<>();
            while (rs.next()) {
                SaveResearchReportSourceResponse source = mapSavedReportSource(rs);
                results.put(source.sourceId(), source);
            }
            return results;
        }, parameters.toArray());
    }

    public SaveResearchReportSourceResponse loadSavedReportSource(String workspaceId, String sourceId) {
        return jdbcTemplate.query("""
                select id, title, source_type, status, parse_status, index_status,
                       coalesce(generated_by, '') as generated_by,
                       coalesce(generated_ref_id, '') as generated_ref_id
                from source
                where workspace_id = ? and id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("SOURCE_NOT_FOUND", "Source does not exist");
            }
            return mapSavedReportSource(rs);
        }, workspaceId, sourceId);
    }

    public RunArtifactView loadRunArtifactView(String workspaceId, String researchRunId) {
        SaveReportRow row = loadSaveReportRow(workspaceId, researchRunId);
        SaveResearchReportSourceResponse savedReportSource = blankToNull(row.reportSourceId()) == null
                ? null
                : loadSavedReportSource(workspaceId, row.reportSourceId());
        ResearchReportFileResponse reportFile = buildReportFileResponse(
                workspaceId, researchRunId, row.finalReportMarkdown());
        ResearchRunArtifactResponse researchArtifact = buildResearchArtifact(
                researchRunId, row.finalReportTitle(), null, reportFile, savedReportSource);
        return new RunArtifactView(reportFile, researchArtifact, savedReportSource);
    }

    public ResearchReportFileResponse buildReportFileResponse(
            String workspaceId,
            String researchRunId,
            String reportMarkdown
    ) {
        if (reportMarkdown == null || reportMarkdown.isBlank()) {
            return null;
        }
        byte[] reportBytes = reportMarkdown.getBytes(StandardCharsets.UTF_8);
        return new ResearchReportFileResponse(
                researchReportObjectKey(workspaceId, researchRunId),
                "final.md",
                "text/markdown",
                reportBytes.length,
                sha256(reportBytes)
        );
    }

    public ResearchRunArtifactResponse buildResearchArtifact(
            String researchRunId,
            String finalReportTitle,
            ResearchArtifactCandidateResponse candidate,
            ResearchReportFileResponse reportFile,
            SaveResearchReportSourceResponse savedReportSource
    ) {
        if (candidate == null && reportFile == null && savedReportSource == null) {
            return null;
        }
        return new ResearchRunArtifactResponse(
                researchRunId,
                candidate == null || candidate.artifactType().isBlank()
                        ? "DEEP_RESEARCH_REPORT" : candidate.artifactType(),
                candidate == null || candidate.artifactVersion().isBlank()
                        ? "v1" : candidate.artifactVersion(),
                artifactTitle(finalReportTitle, candidate, researchRunId),
                candidate == null || candidate.generatedBy().isBlank()
                        ? "research_agent" : candidate.generatedBy(),
                candidate == null || candidate.generatedRefType().isBlank()
                        ? "RESEARCH_RUN" : candidate.generatedRefType(),
                candidate == null || candidate.generatedRefId().isBlank()
                        ? researchRunId : candidate.generatedRefId(),
                candidate == null ? 0 : candidate.citationCount(),
                reportFile,
                savedReportSource
        );
    }

    private SaveReportRow loadSaveReportRow(String workspaceId, String researchRunId) {
        return jdbcTemplate.query("""
                select id, workspace_id, status, final_report_title, final_report_markdown, report_source_id
                from research_run
                where workspace_id = ? and id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("RESEARCH_RUN_NOT_FOUND", "Research run does not exist");
            }
            return new SaveReportRow(
                    rs.getString("id"), rs.getString("workspace_id"), rs.getString("status"),
                    rs.getString("final_report_title"), rs.getString("final_report_markdown"),
                    rs.getString("report_source_id")
            );
        }, workspaceId, researchRunId);
    }

    private SaveResearchReportSourceResponse mapSavedReportSource(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new SaveResearchReportSourceResponse(
                rs.getString("id"), rs.getString("title"), rs.getString("source_type"),
                rs.getString("status"), rs.getString("parse_status"), rs.getString("index_status"),
                rs.getString("generated_by"), rs.getString("generated_ref_id")
        );
    }

    private ResearchReportFileResponse writeResearchReportFile(
            String workspaceId,
            String researchRunId,
            byte[] reportBytes
    ) {
        String objectKey = researchReportObjectKey(workspaceId, researchRunId);
        storage.write("noteweave-source", objectKey, reportBytes);
        return new ResearchReportFileResponse(
                objectKey, "final.md", "text/markdown", reportBytes.length, sha256(reportBytes));
    }

    private FileObjectRef getOrCreateGeneratedFileObject(
            String workspaceId,
            String researchRunId,
            String title,
            String contentHash,
            byte[] bytes
    ) {
        List<FileObjectRef> existing = jdbcTemplate.query("""
                select id, object_key from file_object where workspace_id = ? and sha256 = ?
                """, (rs, rowNum) -> new FileObjectRef(
                rs.getString("id"), rs.getString("object_key")), workspaceId, contentHash);
        if (!existing.isEmpty()) {
            FileObjectRef ref = existing.get(0);
            if (!storage.exists("noteweave-source", ref.objectKey())) {
                storage.write("noteweave-source", ref.objectKey(), bytes);
            }
            jdbcTemplate.update("update file_object set ref_count = ref_count + 1 where id = ?", ref.id());
            return ref;
        }
        String fileObjectId = Ids.newId();
        String objectKey = "workspace/%s/file_object/%s-%s.md"
                .formatted(workspaceId, contentHash, sanitize(title));
        storage.write("noteweave-source", objectKey, bytes);
        jdbcTemplate.update("""
                insert into file_object(id, workspace_id, object_key, sha256, file_size, mime_type, ref_count)
                values (?, ?, ?, ?, ?, 'text/markdown', 1)
                """, fileObjectId, workspaceId, objectKey, contentHash, bytes.length);
        return new FileObjectRef(fileObjectId, objectKey);
    }

    private void insertTrace(String researchRunId, String type, String message, Map<String, Object> payload) {
        jdbcTemplate.update("""
                insert into research_trace(id, research_run_id, trace_type, trace_message, payload_json)
                values (?, ?, ?, ?, ?)
                """, Ids.newId(), researchRunId, type, message, Json.write(objectMapper, payload));
    }

    private void requireWorkspace(String workspaceId) {
        if (!workspaceService.exists(workspaceId)) {
            throw new BusinessException("WORKSPACE_NOT_FOUND", "Workspace does not exist");
        }
    }

    private void requireResearchRun(String workspaceId, String researchRunId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from research_run where workspace_id = ? and id = ?
                """, Integer.class, workspaceId, researchRunId);
        if (count == null || count <= 0) {
            throw new BusinessException("RESEARCH_RUN_NOT_FOUND", "Research run does not exist");
        }
    }

    private String artifactTitle(
            String finalReportTitle,
            ResearchArtifactCandidateResponse candidate,
            String researchRunId
    ) {
        if (candidate != null && !candidate.title().isBlank()) {
            return candidate.title();
        }
        if (finalReportTitle != null && !finalReportTitle.isBlank()) {
            return finalReportTitle;
        }
        return "Research Report " + researchRunId;
    }

    private String reportTitle(SaveReportRow row) {
        return row.finalReportTitle() == null || row.finalReportTitle().isBlank()
                ? "Research Report " + row.researchRunId()
                : row.finalReportTitle();
    }

    private String researchReportObjectKey(String workspaceId, String researchRunId) {
        return "workspace/%s/research/%s/report/final.md".formatted(workspaceId, researchRunId);
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private String sanitize(String value) {
        String normalized = value == null ? "research-report" : value;
        return normalized.replaceAll("[^\\p{IsHan}a-zA-Z0-9._-]+", "-");
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    public record RunArtifactView(
            ResearchReportFileResponse reportFile,
            ResearchRunArtifactResponse researchArtifact,
            SaveResearchReportSourceResponse savedReportSource
    ) {
    }

    private record SaveReportRow(
            String researchRunId,
            String workspaceId,
            String status,
            String finalReportTitle,
            String finalReportMarkdown,
            String reportSourceId
    ) {
    }

    private record FileObjectRef(String id, String objectKey) {
    }
}
