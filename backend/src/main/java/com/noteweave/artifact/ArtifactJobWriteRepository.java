package com.noteweave.artifact;

import com.noteweave.common.Ids;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Owns artifact job command SQL while transaction ownership stays with ArtifactJobService. */
@Repository
class ArtifactJobWriteRepository {
    private final JdbcTemplate jdbcTemplate;

    ArtifactJobWriteRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    List<Map<String, Object>> candidateReceipts(String taskId) {
        return jdbcTemplate.query("""
                select candidate_id, candidate_digest from artifact_candidate_receipt where task_id = ?
                """, (rs, index) -> Map.<String, Object>of(
                "candidate_id", rs.getString("candidate_id"),
                "candidate_digest", rs.getString("candidate_digest")), taskId);
    }

    List<String> lockJobStatuses(String artifactJobId, String workspaceId) {
        return jdbcTemplate.query("""
                select status from artifact_job where id = ? and workspace_id = ? for update
                """, (rs, index) -> rs.getString(1), artifactJobId, workspaceId);
    }

    boolean hasFullInputSnapshot(String inputSnapshotId, String workspaceId, String artifactJobId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from artifact_run_input_snapshot
                where id = ? and workspace_id = ? and artifact_job_id = ?
                  and replay_availability = 'FULL'
                """, Integer.class, inputSnapshotId, workspaceId, artifactJobId);
        return count != null && count == 1;
    }

    boolean hasReadableSourceSnapshot(String workspaceId, String sourceId, String snapshotId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from source s
                join source_snapshot ss on ss.source_id = s.id
                where s.workspace_id = ? and s.id = ? and s.status = 'READY'
                  and ss.id = ? and ss.parse_status = 'PARSED'
                  and ss.index_status in ('INDEXED', 'DISABLED')
                """, Integer.class, workspaceId, sourceId, snapshotId);
        return count != null && count == 1;
    }

    void persistInitialJob(
            String artifactJobId,
            String workspaceId,
            String taskId,
            String skillKey,
            String userRequirement,
            String inputsJson,
            String sourceScopeJson,
            String controlPackJson,
            String sourceScopeSnapshotJson,
            String upstreamRefsJson,
            String taskPayloadJson,
            String catalogDigest
    ) {
        jdbcTemplate.update("""
                insert into artifact_job(
                    id, workspace_id, task_id, skill_key, action_key, style_profile_key, context_snapshot_id,
                    user_requirement, inputs_json, source_scope_json, control_pack_json, status
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED')
                """,
                artifactJobId,
                workspaceId,
                taskId,
                skillKey,
                null,
                null,
                null,
                userRequirement,
                inputsJson,
                sourceScopeJson,
                controlPackJson
        );
        String inputSnapshotId = Ids.newId();
        jdbcTemplate.update("""
                insert into artifact_run_input_snapshot(
                    id, workspace_id, artifact_job_id, user_requirement, inputs_json,
                    source_scope_snapshot_json, upstream_refs_json, control_pack_json, compiler_version
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                inputSnapshotId,
                workspaceId,
                artifactJobId,
                userRequirement,
                inputsJson,
                sourceScopeSnapshotJson,
                upstreamRefsJson,
                controlPackJson,
                "artifact-input-v1@sha256:" + catalogDigest
        );
        jdbcTemplate.update("""
                insert into artifact_job_run(
                    task_id, artifact_job_id, run_no, trigger_type, source_version_no,
                    user_requirement, inputs_json, source_scope_json, control_pack_json, input_snapshot_id,
                    reserved_version_id
                ) values (?, ?, 1, 'INITIAL', null, ?, ?, ?, ?, ?, ?)
                """,
                taskId,
                artifactJobId,
                userRequirement,
                inputsJson,
                sourceScopeJson,
                controlPackJson,
                inputSnapshotId,
                Ids.newId()
        );
        enqueueTask(taskId, workspaceId, artifactJobId, taskPayloadJson);
    }

    void persistRegeneration(
            String taskId,
            String artifactJobId,
            int runNo,
            String triggerType,
            Integer sourceVersionNo,
            String userRequirement,
            String inputsJson,
            String sourceScopeJson,
            String controlPackJson,
            String inputSnapshotId,
            String workspaceId,
            String taskPayloadJson,
            String catalogDigest
    ) {
        String regeneratedSnapshotId = Ids.newId();
        jdbcTemplate.update("""
                insert into artifact_run_input_snapshot(
                    id, workspace_id, artifact_job_id, user_requirement, inputs_json,
                    source_scope_snapshot_json, upstream_refs_json, control_pack_json, compiler_version
                )
                select ?, workspace_id, artifact_job_id, ?, ?,
                       source_scope_snapshot_json, upstream_refs_json, control_pack_json, ?
                from artifact_run_input_snapshot
                where id = ? and workspace_id = ? and artifact_job_id = ?
                """, regeneratedSnapshotId, userRequirement, inputsJson,
                "artifact-input-v1@sha256:" + catalogDigest, inputSnapshotId, workspaceId, artifactJobId);
        jdbcTemplate.update("""
                insert into artifact_job_run(
                    task_id, artifact_job_id, run_no, trigger_type, source_version_no,
                    user_requirement, inputs_json, source_scope_json, control_pack_json, input_snapshot_id,
                    reserved_version_id
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                taskId,
                artifactJobId,
                runNo,
                triggerType,
                sourceVersionNo,
                userRequirement,
                inputsJson,
                sourceScopeJson,
                controlPackJson,
                regeneratedSnapshotId,
                Ids.newId()
        );
        jdbcTemplate.update("""
                update artifact_job
                set task_id = ?, user_requirement = ?, inputs_json = ?, status = 'QUEUED', updated_at = current_timestamp
                where id = ? and workspace_id = ?
                """, taskId, userRequirement, inputsJson, artifactJobId, workspaceId);
        enqueueTask(taskId, workspaceId, artifactJobId, taskPayloadJson);
    }

    int lockNextVersionNo(String workspaceId, String artifactJobId) {
        Integer latestVersionNo = jdbcTemplate.queryForObject(
                "select latest_version_no from artifact_job where id = ? and workspace_id = ? for update",
                Integer.class,
                artifactJobId,
                workspaceId
        );
        return (latestVersionNo == null ? 0 : latestVersionNo) + 1;
    }

    /**
     * NR15：Job 当前登记任务是否仍在活动（任务表状态判定，而非仅 Job 镜像状态）。
     * 必须在 lockNextVersionNo 的行锁事务内调用，避免检查与覆盖之间的竞态。
     * 返回活动 taskId；无活动任务返回 null。
     */
    String activeTaskId(String workspaceId, String artifactJobId) {
        java.util.List<String> ids = jdbcTemplate.queryForList("""
                select j.task_id from artifact_job j
                join task t on t.id = j.task_id
                where j.id = ? and j.workspace_id = ? and j.task_id is not null
                  and (t.task_status in ('QUEUED', 'PENDING', 'RUNNING')
                       or t.task_status like 'WAITING%')
                """, String.class, artifactJobId, workspaceId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    void appendRollbackVersion(
            String versionId,
            String artifactJobId,
            String skillKey,
            int versionNo,
            String title,
            String contentMarkdown,
            String resultPayloadJson,
            String traceSummary,
            String citationsJson,
            String originTaskId,
            String workspaceId
    ) {
        jdbcTemplate.update("""
                insert into artifact_version(
                    id, artifact_job_id, skill_key, version_no, title, content_markdown,
                    result_payload_json, trace_summary, citations_json, origin_task_id
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                versionId,
                artifactJobId,
                skillKey,
                versionNo,
                title,
                contentMarkdown,
                resultPayloadJson,
                traceSummary,
                citationsJson,
                originTaskId
        );
        jdbcTemplate.update("""
                update artifact_job
                set latest_version_no = ?, result_title = ?, status = 'COMPLETED', updated_at = current_timestamp
                where id = ? and workspace_id = ?
                """, versionNo, title, artifactJobId, workspaceId);
    }

    /**
     * 追加用户编辑版本：与回滚同构，但保留原 originTaskId 并由调用方在
     * payload 里写入编辑元数据（edit_request_id、edit_event_id、摘要与章节差异）。
     */
    void appendEditVersion(
            String versionId,
            String artifactJobId,
            String skillKey,
            int versionNo,
            String title,
            String contentMarkdown,
            String resultPayloadJson,
            String traceSummary,
            String citationsJson,
            String originTaskId,
            String workspaceId
    ) {
        jdbcTemplate.update("""
                insert into artifact_version(
                    id, artifact_job_id, skill_key, version_no, title, content_markdown,
                    result_payload_json, trace_summary, citations_json, origin_task_id
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                versionId,
                artifactJobId,
                skillKey,
                versionNo,
                title,
                contentMarkdown,
                resultPayloadJson,
                traceSummary,
                citationsJson,
                originTaskId
        );
        jdbcTemplate.update("""
                update artifact_job
                set latest_version_no = ?, result_title = ?, updated_at = current_timestamp
                where id = ? and workspace_id = ?
                """, versionNo, title, artifactJobId, workspaceId);
    }

    void updateStatusByTaskId(String taskId, String status) {
        jdbcTemplate.update("""
                update artifact_job
                set status = ?, updated_at = current_timestamp
                where task_id = ?
                """, status, taskId);
    }

    int claimCompletion(String artifactJobId, String taskId) {
        return jdbcTemplate.update("""
                update artifact_job
                set status = 'FINALIZING', updated_at = current_timestamp
                where id = ? and task_id = ?
                  and (status in ('QUEUED', 'RUNNING') or status like 'WAITING_FOR_%')
                """, artifactJobId, taskId);
    }

    void appendCompletedVersion(
            String versionId,
            String artifactJobId,
            String skillKey,
            int versionNo,
            String title,
            String contentMarkdown,
            String resultPayloadJson,
            String traceSummary,
            String citationsJson,
            String originTaskId
    ) {
        jdbcTemplate.update("""
                insert into artifact_version(
                    id, artifact_job_id, skill_key, version_no, title, content_markdown, result_payload_json,
                    trace_summary, citations_json, origin_task_id
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                versionId,
                artifactJobId,
                skillKey,
                versionNo,
                title,
                contentMarkdown,
                resultPayloadJson,
                traceSummary,
                citationsJson,
                originTaskId
        );
    }

    String reservedVersionId(String taskId) {
        return jdbcTemplate.queryForObject(
                "select reserved_version_id from artifact_job_run where task_id = ?",
                String.class, taskId);
    }

    void recordCandidateReceipt(String taskId, String candidateId, String digest,
                                String versionId, String inputSnapshotId) {
        jdbcTemplate.update("""
                insert into artifact_candidate_receipt(
                    task_id, candidate_id, candidate_digest, artifact_version_id, input_snapshot_id
                ) values (?, ?, ?, ?, ?)
                """, taskId, candidateId, digest, versionId, inputSnapshotId);
    }

    int completeJob(String artifactJobId, String resultTitle, int versionNo) {
        return jdbcTemplate.update("""
                update artifact_job
                set status = 'COMPLETED',
                    result_title = ?,
                    latest_version_no = ?,
                    updated_at = current_timestamp
                where id = ? and status = 'FINALIZING'
                """, resultTitle, versionNo, artifactJobId);
    }

    int markFailed(String taskId) {
        return jdbcTemplate.update("""
                update artifact_job
                set status = 'FAILED', updated_at = current_timestamp
                where task_id = ? and (status in ('QUEUED', 'RUNNING') or status like 'WAITING_FOR_%')
                """, taskId);
    }

    private void enqueueTask(String taskId, String workspaceId, String artifactJobId, String taskPayloadJson) {
        jdbcTemplate.update("""
                insert into task_outbox(id, task_id, topic, message_key, payload_json, status)
                values (?, ?, 'noteweave.artifact.job', ?, ?, 'READY')
                """,
                Ids.newId(),
                taskId,
                artifactJobId,
                taskPayloadJson
        );
    }
}
