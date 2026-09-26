package com.noteweave.artifact;

import com.noteweave.common.BusinessException;
import com.noteweave.worker.WorkerSourceScopeItemResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Owns artifact job/version read SQL and its stable database-to-row mappings. */
@Repository
class ArtifactJobReadRepository {
    private final JdbcTemplate jdbcTemplate;

    ArtifactJobReadRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    List<ArtifactJobSummaryRow> listJobs(String workspaceId) {
        return jdbcTemplate.query("""
                select aj.id,
                       aj.workspace_id,
                       aj.task_id,
                       coalesce(aj.skill_key, '') as skill_key,
                       aj.status,
                       t.task_status,
                       t.progress_phase,
                       t.progress_message,
                       coalesce(aj.result_title, '') as result_title,
                       aj.latest_version_no,
                       aj.created_at,
                       aj.updated_at
                from artifact_job aj
                join task t on t.id = aj.task_id
                where aj.workspace_id = ?
                order by aj.updated_at desc, aj.id desc
                limit 20
                """, (rs, rowNum) -> new ArtifactJobSummaryRow(
                rs.getString("id"),
                rs.getString("workspace_id"),
                rs.getString("task_id"),
                blank(rs.getString("skill_key")),
                rs.getString("status"),
                blank(rs.getString("task_status")),
                blank(rs.getString("progress_phase")),
                blank(rs.getString("progress_message")),
                blank(rs.getString("result_title")),
                rs.getInt("latest_version_no"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at"))
        ), workspaceId);
    }

    ArtifactJobDetailRow getJob(String workspaceId, String artifactJobId) {
        return jdbcTemplate.query("""
                select aj.id,
                       aj.workspace_id,
                       aj.task_id,
                       coalesce(aj.skill_key, '') as skill_key,
                       coalesce(aj.user_requirement, '') as user_requirement,
                       coalesce(aj.inputs_json, '') as inputs_json,
                       aj.status,
                       t.task_status,
                       t.progress_phase,
                       t.progress_message,
                       coalesce(aj.result_title, '') as result_title,
                       aj.latest_version_no,
                       aj.created_at,
                       aj.updated_at
                from artifact_job aj
                join task t on t.id = aj.task_id
                where aj.workspace_id = ? and aj.id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("ARTIFACT_JOB_NOT_FOUND", "产物任务不存在");
            }
            return new ArtifactJobDetailRow(
                    rs.getString("id"),
                    rs.getString("workspace_id"),
                    rs.getString("task_id"),
                    blank(rs.getString("skill_key")),
                    blank(rs.getString("user_requirement")),
                    blank(rs.getString("inputs_json")),
                    blank(rs.getString("status")),
                    blank(rs.getString("task_status")),
                    blank(rs.getString("progress_phase")),
                    blank(rs.getString("progress_message")),
                    blank(rs.getString("result_title")),
                    rs.getInt("latest_version_no"),
                    instant(rs.getTimestamp("created_at")),
                    instant(rs.getTimestamp("updated_at"))
            );
        }, workspaceId, artifactJobId);
    }

    List<ArtifactVersionSummaryRow> listVersions(String workspaceId, String artifactJobId) {
        return jdbcTemplate.query("""
                select av.id,
                       av.artifact_job_id,
                       coalesce(av.skill_key, '') as skill_key,
                       av.version_no,
                       av.title,
                       av.created_at
                from artifact_version av
                join artifact_job aj on aj.id = av.artifact_job_id
                where aj.workspace_id = ? and av.artifact_job_id = ?
                order by av.version_no desc, av.created_at desc
                """, (rs, rowNum) -> new ArtifactVersionSummaryRow(
                rs.getString("id"),
                rs.getString("artifact_job_id"),
                blank(rs.getString("skill_key")),
                rs.getInt("version_no"),
                rs.getString("title"),
                instant(rs.getTimestamp("created_at"))
        ), workspaceId, artifactJobId);
    }

    ArtifactVersionDetailRow getVersionDetail(String workspaceId, String artifactJobId, int versionNo) {
        return jdbcTemplate.query("""
                select av.id,
                       av.artifact_job_id,
                       coalesce(av.skill_key, '') as skill_key,
                       av.version_no,
                       av.title,
                       av.content_markdown,
                       coalesce(av.result_payload_json, '{}') as result_payload_json,
                       coalesce(av.trace_summary, '') as trace_summary,
                       coalesce(av.citations_json, '[]') as citations_json,
                       av.created_at
                from artifact_version av
                join artifact_job aj on aj.id = av.artifact_job_id
                where aj.workspace_id = ? and av.artifact_job_id = ? and av.version_no = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("ARTIFACT_VERSION_NOT_FOUND", "产物版本不存在");
            }
            return new ArtifactVersionDetailRow(
                    rs.getString("id"),
                    rs.getString("artifact_job_id"),
                    blank(rs.getString("skill_key")),
                    rs.getInt("version_no"),
                    rs.getString("title"),
                    blank(rs.getString("content_markdown")),
                    blank(rs.getString("trace_summary")),
                    blank(rs.getString("result_payload_json")),
                    blank(rs.getString("citations_json")),
                    instant(rs.getTimestamp("created_at"))
            );
        }, workspaceId, artifactJobId, versionNo);
    }

    List<ArtifactFileMetadataResponse> loadArtifactFiles(String artifactVersionId) {
        return jdbcTemplate.query("""
                select id, file_format, file_name, media_type, storage_backend, bucket_name,
                       object_key, size_bytes, checksum_sha256, status, created_at
                       , coalesce(error_message, '') as error_message
                from artifact_file
                where artifact_version_id = ?
                order by file_format asc, created_at asc
                """, (rs, rowNum) -> new ArtifactFileMetadataResponse(
                rs.getString("id"),
                rs.getString("file_format"),
                rs.getString("file_name"),
                rs.getString("media_type"),
                rs.getString("storage_backend"),
                rs.getString("bucket_name"),
                rs.getString("object_key"),
                rs.getLong("size_bytes"),
                rs.getString("checksum_sha256"),
                rs.getString("status"),
                rs.getString("error_message"),
                instant(rs.getTimestamp("created_at"))
        ), artifactVersionId);
    }

    ArtifactJobTaskRow findByTaskId(String taskId) {
        return jdbcTemplate.query("""
                select aj.id, aj.workspace_id, r.task_id, aj.skill_key, aj.style_profile_key, aj.context_snapshot_id,
                       s.id as input_snapshot_id, s.user_requirement, s.inputs_json,
                       s.source_scope_snapshot_json, s.upstream_refs_json, s.control_pack_json,
                       s.replay_availability, aj.latest_version_no
                from artifact_job_run r
                join artifact_job aj on aj.id = r.artifact_job_id
                join artifact_run_input_snapshot s on s.id = r.input_snapshot_id
                where r.task_id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("ARTIFACT_JOB_NOT_FOUND", "产物任务不存在");
            }
            return new ArtifactJobTaskRow(
                    rs.getString("id"),
                    rs.getString("workspace_id"),
                    rs.getString("task_id"),
                    blank(rs.getString("skill_key")),
                    rs.getString("style_profile_key"),
                    rs.getString("context_snapshot_id"),
                    rs.getString("input_snapshot_id"),
                    blank(rs.getString("user_requirement")),
                    blank(rs.getString("inputs_json")),
                    blank(rs.getString("source_scope_snapshot_json")),
                    blank(rs.getString("upstream_refs_json")),
                    blank(rs.getString("control_pack_json")),
                    rs.getString("replay_availability"),
                    rs.getInt("latest_version_no")
            );
        }, taskId);
    }

    ArtifactRegenerationRow loadRegenerationRow(String workspaceId, String artifactJobId) {
        return jdbcTemplate.query("""
                select aj.skill_key, aj.user_requirement, aj.inputs_json, aj.source_scope_json,
                       aj.control_pack_json,
                       (select r.input_snapshot_id from artifact_job_run r
                        where r.artifact_job_id = aj.id order by r.run_no desc limit 1) as input_snapshot_id,
                       coalesce((select max(r.run_no) from artifact_job_run r where r.artifact_job_id = aj.id), 0) as latest_run_no
                from artifact_job aj
                where aj.workspace_id = ? and aj.id = ?
                for update
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("ARTIFACT_JOB_NOT_FOUND", "产物任务不存在");
            }
            return new ArtifactRegenerationRow(
                    blank(rs.getString("skill_key")),
                    blank(rs.getString("user_requirement")),
                    blank(rs.getString("inputs_json")),
                    blank(rs.getString("source_scope_json")),
                    blank(rs.getString("control_pack_json")),
                    rs.getString("input_snapshot_id"),
                    rs.getInt("latest_run_no")
            );
        }, workspaceId, artifactJobId);
    }

    ArtifactRollbackRow loadRollbackRow(String workspaceId, String artifactJobId, int versionNo) {
        return jdbcTemplate.query("""
                select av.skill_key, av.title, av.content_markdown, av.result_payload_json,
                       av.citations_json, coalesce(av.origin_task_id, aj.task_id) as origin_task_id
                from artifact_version av
                join artifact_job aj on aj.id = av.artifact_job_id
                where aj.workspace_id = ? and aj.id = ? and av.version_no = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("ARTIFACT_VERSION_NOT_FOUND", "产物版本不存在");
            }
            return new ArtifactRollbackRow(
                    blank(rs.getString("skill_key")),
                    blank(rs.getString("title")),
                    blank(rs.getString("content_markdown")),
                    blank(rs.getString("result_payload_json")),
                    blank(rs.getString("citations_json")),
                    blank(rs.getString("origin_task_id"))
            );
        }, workspaceId, artifactJobId, versionNo);
    }

    boolean validUpstreamRef(String workspaceId, String refType, String refId, String revisionId) {
        Integer matches = "SOURCE_SNAPSHOT".equals(refType)
                ? jdbcTemplate.queryForObject("""
                select count(*) from source s join source_snapshot ss on ss.source_id = s.id
                where s.workspace_id = ? and s.id = ? and ss.id = ? and s.status = 'READY'
                  and ss.parse_status = 'PARSED' and ss.index_status = 'INDEXED'
                """, Integer.class, workspaceId, refId, revisionId)
                : jdbcTemplate.queryForObject("""
                select count(*)
                from research_run rr
                join source s on s.workspace_id = rr.workspace_id
                  and s.generated_by = 'research_agent' and s.generated_ref_id = rr.id
                join source_snapshot ss on ss.source_id = s.id
                where rr.workspace_id = ? and rr.id = ? and ss.id = ? and s.status = 'READY'
                  and ss.parse_status = 'PARSED' and ss.index_status = 'INDEXED'
                """, Integer.class, workspaceId, refId, revisionId);
        return matches != null && matches > 0;
    }

    List<WorkerSourceScopeItemResponse> loadSourceScopeItem(String workspaceId, String sourceId) {
        return jdbcTemplate.query("""
                select s.id, s.title, s.source_type, coalesce(s.summary, '') as summary,
                       coalesce(s.generated_by, '') as generated_by,
                       coalesce(s.generated_ref_id, '') as generated_ref_id,
                       ss.id as source_snapshot_id, ss.version_no as source_snapshot_version_no,
                       ss.sha256 as source_snapshot_sha256,
                       coalesce((
                           select sw.id
                           from source_chunk sc
                           join source_window sw on sw.source_chunk_id = sc.id
                           where sc.source_id = s.id and sc.source_snapshot_id = ss.id
                           order by sc.chunk_no asc, sw.window_no asc
                           limit 1
                       ), '') as source_window_id,
                       coalesce((
                           select sw.content
                           from source_chunk sc
                           join source_window sw on sw.source_chunk_id = sc.id
                           where sc.source_id = s.id and sc.source_snapshot_id = ss.id
                           order by sc.chunk_no asc, sw.window_no asc
                           limit 1
                       ), '') as sample_text
                from source s
                join source_snapshot ss on ss.source_id = s.id
                  and ss.version_no = (select max(latest.version_no) from source_snapshot latest where latest.source_id = s.id)
                where s.workspace_id = ? and s.id = ? and s.status = 'READY'
                  and ss.parse_status = 'PARSED' and ss.index_status in ('INDEXED', 'DISABLED')
                """, (rs, rowNum) -> {
            String generatedBy = blank(rs.getString("generated_by"));
            String generatedRefId = blank(rs.getString("generated_ref_id"));
            return new WorkerSourceScopeItemResponse(
                    rs.getString("id"),
                    rs.getString("title"),
                    rs.getString("summary"),
                    rs.getString("sample_text"),
                    blank(rs.getString("source_snapshot_id")),
                    blank(rs.getString("source_window_id")),
                    generatedBy,
                    generatedRefId,
                    blank(rs.getString("source_type")),
                    "",
                    sourceScopeMetadata(
                            generatedBy,
                            generatedRefId,
                            rs.getString("source_snapshot_id"),
                            rs.getInt("source_snapshot_version_no"),
                            rs.getString("source_snapshot_sha256")
                    )
            );
        }, workspaceId, sourceId);
    }

    void requireJob(String workspaceId, String artifactJobId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from artifact_job
                where workspace_id = ? and id = ?
                """, Integer.class, workspaceId, artifactJobId);
        if (count == null || count == 0) {
            throw new BusinessException("ARTIFACT_JOB_NOT_FOUND", "产物任务不存在");
        }
    }

    ArtifactVersionSourceRow loadVersionForSource(
            String workspaceId,
            String artifactJobId,
            int versionNo
    ) {
        return jdbcTemplate.query("""
                select av.id, av.title, av.content_markdown
                from artifact_version av
                join artifact_job aj on aj.id = av.artifact_job_id
                where aj.workspace_id = ? and av.artifact_job_id = ? and av.version_no = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("ARTIFACT_VERSION_NOT_FOUND", "产物版本不存在");
            }
            return new ArtifactVersionSourceRow(
                    rs.getString("id"),
                    rs.getString("title"),
                    blank(rs.getString("content_markdown"))
            );
        }, workspaceId, artifactJobId, versionNo);
    }

    private static Map<String, Object> sourceScopeMetadata(
            String generatedBy,
            String generatedRefId,
            String sourceSnapshotId,
            int sourceSnapshotVersionNo,
            String sourceSnapshotSha256
    ) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        if ("research_agent".equals(generatedBy) && !generatedRefId.isBlank()) {
            LinkedHashMap<String, Object> researchArtifact = new LinkedHashMap<>();
            researchArtifact.put("artifact_id", generatedRefId);
            researchArtifact.put("artifact_type", "DEEP_RESEARCH_REPORT");
            researchArtifact.put("generated_by", generatedBy);
            researchArtifact.put("generated_ref_id", generatedRefId);
            metadata.put("research_artifact", researchArtifact);
        }
        metadata.put("source_snapshot_id", sourceSnapshotId);
        metadata.put("source_snapshot_version_no", sourceSnapshotVersionNo);
        metadata.put("source_snapshot_sha256", sourceSnapshotSha256);
        return Map.copyOf(metadata);
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }

    private static Instant instant(Timestamp value) {
        return value == null ? Instant.now() : value.toInstant();
    }
}

record ArtifactJobTaskRow(
        String artifactJobId,
        String workspaceId,
        String taskId,
        String skillKey,
        String styleProfileKey,
        String contextSnapshotId,
        String inputSnapshotId,
        String userRequirement,
        String inputsJson,
        String sourceScopeJson,
        String upstreamRefsJson,
        String controlPackJson,
        String replayAvailability,
        int latestVersionNo
) {
}

record ArtifactRegenerationRow(
        String skillKey,
        String userRequirement,
        String inputsJson,
        String sourceScopeJson,
        String controlPackJson,
        String inputSnapshotId,
        int latestRunNo
) {
}

record ArtifactRollbackRow(
        String skillKey,
        String title,
        String contentMarkdown,
        String resultPayloadJson,
        String citationsJson,
        String originTaskId
) {
}

record ArtifactVersionSourceRow(String versionId, String title, String contentMarkdown) {
}

record ArtifactJobSummaryRow(
        String artifactJobId,
        String workspaceId,
        String taskId,
        String skillKey,
        String status,
        String taskStatus,
        String progressPhase,
        String progressMessage,
        String resultTitle,
        int latestVersionNo,
        Instant createdAt,
        Instant updatedAt
) {
}

record ArtifactJobDetailRow(
        String artifactJobId,
        String workspaceId,
        String taskId,
        String skillKey,
        String userRequirement,
        String inputsJson,
        String status,
        String taskStatus,
        String progressPhase,
        String progressMessage,
        String resultTitle,
        int latestVersionNo,
        Instant createdAt,
        Instant updatedAt
) {
}

record ArtifactVersionSummaryRow(
        String versionId,
        String artifactJobId,
        String skillKey,
        int versionNo,
        String title,
        Instant createdAt
) {
}

record ArtifactVersionDetailRow(
        String versionId,
        String artifactJobId,
        String skillKey,
        int versionNo,
        String title,
        String contentMarkdown,
        String traceSummary,
        String resultPayloadJson,
        String citationsJson,
        Instant createdAt
) {
}
