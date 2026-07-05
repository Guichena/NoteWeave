package com.noteweave.artifact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import com.noteweave.memory.MemoryCompilerService;
import com.noteweave.memory.MemoryControlPackResponse;
import com.noteweave.task.TaskService;
import com.noteweave.worker.WorkerContextSnapshotResponse;
import com.noteweave.worker.WorkerFailRequest;
import com.noteweave.worker.WorkerSourceScopeItemResponse;
import com.noteweave.worker.WorkerTaskCallbackService.CompletionOutcome;
import com.noteweave.workspace.WorkspaceService;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ArtifactJobService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final WorkspaceService workspaceService;
    private final TaskService taskService;
    private final MemoryCompilerService memoryCompilerService;

    public ArtifactJobService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            WorkspaceService workspaceService,
            TaskService taskService,
            MemoryCompilerService memoryCompilerService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.workspaceService = workspaceService;
        this.taskService = taskService;
        this.memoryCompilerService = memoryCompilerService;
    }

    @Transactional
    public ArtifactJobResponse createJob(String workspaceId, CreateArtifactJobRequest request) {
        requireWorkspace(workspaceId);
        String artifactJobId = Ids.newId();
        String actionKey = normalizeToken(request.actionKey());
        String taskId = taskService.createTask(
                workspaceId,
                "ARTIFACT_JOB",
                "ARTIFACT_JOB",
                artifactJobId,
                "QUEUED",
                "右侧产物生成任务已创建"
        );
        MemoryControlPackResponse controlPack = memoryCompilerService.compileArtifactControlPack(workspaceId, actionKey);
        List<String> sourceScopeIds = loadReadySourceScope(workspaceId).stream()
                .map(WorkerSourceScopeItemResponse::sourceId)
                .toList();
        jdbcTemplate.update("""
                insert into artifact_job(
                    id, workspace_id, task_id, action_key, style_profile_key, context_snapshot_id,
                    source_scope_json, control_pack_json, status
                ) values (?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED')
                """,
                artifactJobId,
                workspaceId,
                taskId,
                actionKey,
                blankToNull(request.styleProfileKey()),
                blankToNull(request.contextSnapshotId()),
                Json.write(objectMapper, sourceScopeIds),
                Json.write(objectMapper, controlPack)
        );
        jdbcTemplate.update("""
                insert into task_outbox(id, task_id, topic, message_key, payload_json, status)
                values (?, ?, 'noteweave.artifact.job', ?, ?, 'READY')
                """,
                Ids.newId(),
                taskId,
                artifactJobId,
                Json.write(objectMapper, Map.of(
                        "task_id", taskId,
                        "task_type", "ARTIFACT_JOB",
                        "workspace_id", workspaceId,
                        "target_type", "ARTIFACT_JOB",
                        "target_id", artifactJobId,
                        "payload_version", "v1",
                        "trace_id", artifactJobId,
                        "created_at", System.currentTimeMillis()
                )));
        return new ArtifactJobResponse(artifactJobId, taskId, "QUEUED");
    }

    public ArtifactWorkerInputResponse getWorkerInput(String taskId) {
        JobRow row = findByTaskId(taskId);
        return new ArtifactWorkerInputResponse(
                row.taskId(),
                row.workspaceId(),
                row.artifactJobId(),
                loadReadySourceScope(row.workspaceId()),
                new WorkerContextSnapshotResponse(row.contextSnapshotId() == null ? "" : row.contextSnapshotId()),
                readControlPack(row.controlPackJson()),
                new ArtifactWorkerInputPayload(row.actionKey(), blankIfNull(row.styleProfileKey()), blankIfNull(row.contextSnapshotId()))
        );
    }

    @Transactional
    public void markRunning(String taskId) {
        jdbcTemplate.update("""
                update artifact_job
                set status = 'RUNNING', updated_at = current_timestamp
                where task_id = ?
                """, taskId);
    }

    @Transactional
    public CompletionOutcome completeFromWorker(String taskId, com.noteweave.worker.WorkerCompleteRequest request) {
        JobRow row = findByTaskId(taskId);
        int nextVersionNo = row.latestVersionNo() + 1;
        String versionId = Ids.newId();
        String markdown = extractMarkdown(request.resultPayload());
        jdbcTemplate.update("""
                insert into artifact_version(
                    id, artifact_job_id, version_no, title, content_markdown, result_payload_json, trace_summary, citations_json
                ) values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                versionId,
                row.artifactJobId(),
                nextVersionNo,
                request.resultTitle(),
                markdown,
                Json.write(objectMapper, request.resultPayload() == null ? Map.of() : request.resultPayload()),
                blankToNull(request.traceSummary()),
                Json.write(objectMapper, request.citations() == null ? List.of() : request.citations())
        );
        jdbcTemplate.update("""
                update artifact_job
                set status = 'COMPLETED',
                    result_title = ?,
                    latest_version_no = ?,
                    updated_at = current_timestamp
                where id = ?
                """, request.resultTitle(), nextVersionNo, row.artifactJobId());
        return new CompletionOutcome("ARTIFACT_VERSIONED", "产物版本已生成：" + request.resultTitle(), versionId);
    }

    @Transactional
    public void markFailed(String taskId, WorkerFailRequest request) {
        jdbcTemplate.update("""
                update artifact_job
                set status = 'FAILED', updated_at = current_timestamp
                where task_id = ?
                """, taskId);
    }

    private JobRow findByTaskId(String taskId) {
        return jdbcTemplate.query("""
                select id, workspace_id, task_id, action_key, style_profile_key, context_snapshot_id,
                       control_pack_json, latest_version_no
                from artifact_job
                where task_id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("ARTIFACT_JOB_NOT_FOUND", "产物任务不存在");
            }
            return new JobRow(
                    rs.getString("id"),
                    rs.getString("workspace_id"),
                    rs.getString("task_id"),
                    rs.getString("action_key"),
                    rs.getString("style_profile_key"),
                    rs.getString("context_snapshot_id"),
                    rs.getString("control_pack_json"),
                    rs.getInt("latest_version_no")
            );
        }, taskId);
    }

    private List<WorkerSourceScopeItemResponse> loadReadySourceScope(String workspaceId) {
        return jdbcTemplate.query("""
                select s.id, s.title, coalesce(s.summary, '') as summary,
                       coalesce((
                           select sw.content
                           from source_chunk sc
                           join source_window sw on sw.source_chunk_id = sc.id
                           where sc.source_id = s.id
                           order by sc.chunk_no asc, sw.window_no asc
                           limit 1
                       ), '') as sample_text
                from source s
                where s.workspace_id = ? and s.status = 'READY'
                order by s.updated_at desc, s.id desc
                limit 20
                """, (rs, rowNum) -> new WorkerSourceScopeItemResponse(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("summary"),
                rs.getString("sample_text")
        ), workspaceId);
    }

    private MemoryControlPackResponse readControlPack(String json) {
        try {
            return objectMapper.readValue(json, MemoryControlPackResponse.class);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("ARTIFACT_CONTROL_PACK_PARSE_FAILED", "产物控制包解析失败");
        }
    }

    private void requireWorkspace(String workspaceId) {
        if (!workspaceService.exists(workspaceId)) {
            throw new BusinessException("WORKSPACE_NOT_FOUND", "工作台不存在");
        }
    }

    private String normalizeToken(String value) {
        return value == null ? "" : value.trim().toUpperCase().replace('-', '_').replace(' ', '_');
    }

    private String extractMarkdown(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return "";
        }
        Object markdown = payload.get("markdown");
        if (markdown instanceof String text && !text.isBlank()) {
            return text;
        }
        Object contentMarkdown = payload.get("content_markdown");
        if (contentMarkdown instanceof String text && !text.isBlank()) {
            return text;
        }
        return Json.write(objectMapper, payload);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String blankIfNull(String value) {
        return value == null ? "" : value;
    }

    private record JobRow(
            String artifactJobId,
            String workspaceId,
            String taskId,
            String actionKey,
            String styleProfileKey,
            String contextSnapshotId,
            String controlPackJson,
            int latestVersionNo
    ) {
    }
}
