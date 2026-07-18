package com.noteweave.artifact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import com.noteweave.memory.MemoryCompilerService;
import com.noteweave.memory.MemoryControlPackResponse;
import com.noteweave.knowledge.KnowledgeItemRequest;
import com.noteweave.knowledge.KnowledgeItemResponse;
import com.noteweave.knowledge.KnowledgeCommandService;
import com.noteweave.source.GeneratedSourceResult;
import com.noteweave.source.GeneratedSourceService;
import com.noteweave.task.TaskService;
import com.noteweave.worker.WorkerContextSnapshotResponse;
import com.noteweave.worker.WorkerFailRequest;
import com.noteweave.worker.WorkerSourceScopeItemResponse;
import com.noteweave.worker.WorkerTaskCallbackService.CompletionOutcome;
import com.noteweave.workspace.WorkspaceService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final ArtifactSkillCatalogService artifactSkillCatalogService;
    private final GeneratedSourceService generatedSourceService;
    private final KnowledgeCommandService knowledgeCommandService;

    public ArtifactJobService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            WorkspaceService workspaceService,
            TaskService taskService,
            MemoryCompilerService memoryCompilerService,
            ArtifactSkillCatalogService artifactSkillCatalogService,
            GeneratedSourceService generatedSourceService,
            KnowledgeCommandService knowledgeCommandService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.workspaceService = workspaceService;
        this.taskService = taskService;
        this.memoryCompilerService = memoryCompilerService;
        this.artifactSkillCatalogService = artifactSkillCatalogService;
        this.generatedSourceService = generatedSourceService;
        this.knowledgeCommandService = knowledgeCommandService;
    }

    @Transactional
    public ArtifactJobResponse createJob(String workspaceId, CreateArtifactJobRequest request) {
        requireWorkspace(workspaceId);
        ArtifactSkillDefinition skill = artifactSkillCatalogService.resolveSkill(request.skillKey());
        String artifactJobId = Ids.newId();
        String skillKey = skill.skillKey();
        String userRequirement = request.userRequirement().trim();
        Map<String, Object> inputs = artifactSkillCatalogService.validateAndNormalizeInputs(
                skill,
                request.inputs()
        );
        List<WorkerSourceScopeItemResponse> sourceScopeSnapshot = captureRequestedSourceScope(
                workspaceId, request.sourceScopeSourceIds());
        List<String> sourceScopeIds = sourceScopeSnapshot.stream()
                .map(WorkerSourceScopeItemResponse::sourceId)
                .toList();
        List<ArtifactUpstreamRefRequest> upstreamRefs = validateUpstreamRefs(
                workspaceId, request.upstreamRefs());
        String taskId = taskService.createTask(
                workspaceId,
                "ARTIFACT_JOB",
                "ARTIFACT_JOB",
                artifactJobId,
                "QUEUED",
                "右侧产物 Skill 任务已创建"
        );
        MemoryControlPackResponse controlPack = memoryCompilerService.compileArtifactControlPack(workspaceId, skillKey);
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
                Json.write(objectMapper, inputs),
                Json.write(objectMapper, sourceScopeIds),
                Json.write(objectMapper, controlPack)
        );
        String inputSnapshotId = Ids.newId();
        jdbcTemplate.update("""
                insert into artifact_run_input_snapshot(
                    id, workspace_id, artifact_job_id, user_requirement, inputs_json,
                    source_scope_snapshot_json, upstream_refs_json, control_pack_json, compiler_version
                ) values (?, ?, ?, ?, ?, ?, ?, ?, 'artifact-input-v1')
                """, inputSnapshotId, workspaceId, artifactJobId, userRequirement,
                Json.write(objectMapper, inputs), Json.write(objectMapper, sourceScopeSnapshot),
                Json.write(objectMapper, upstreamRefs),
                Json.write(objectMapper, controlPack));
        jdbcTemplate.update("""
                insert into artifact_job_run(
                    task_id, artifact_job_id, run_no, trigger_type, source_version_no,
                    user_requirement, inputs_json, source_scope_json, control_pack_json, input_snapshot_id
                ) values (?, ?, 1, 'INITIAL', null, ?, ?, ?, ?, ?)
                """,
                taskId,
                artifactJobId,
                userRequirement,
                Json.write(objectMapper, inputs),
                Json.write(objectMapper, sourceScopeIds),
                Json.write(objectMapper, controlPack),
                inputSnapshotId
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
        memoryCompilerService.logPackUsage(
                workspaceId,
                "ARTIFACT",
                "ARTIFACT_JOB_RUN",
                taskId,
                controlPack
        );
        return new ArtifactJobResponse(artifactJobId, taskId, skillKey, "QUEUED");
    }

    @Transactional
    public ArtifactJobResponse regenerateVersion(
            String workspaceId,
            String artifactJobId,
            int sourceVersionNo,
            RegenerateArtifactVersionRequest request
    ) {
        requireWorkspace(workspaceId);
        getVersionDetail(workspaceId, artifactJobId, sourceVersionNo);
        RegenerationRow row = loadRegenerationRow(workspaceId, artifactJobId);
        ArtifactSkillDefinition skill = artifactSkillCatalogService.resolveSkill(row.skillKey());
        String requirement = request.userRequirement() == null || request.userRequirement().isBlank()
                ? row.userRequirement()
                : request.userRequirement().trim();
        Map<String, Object> inputs = request.inputs() == null
                ? readInputs(row.inputsJson())
                : artifactSkillCatalogService.validateAndNormalizeInputs(skill, request.inputs());
        int nextRunNo = row.latestRunNo() + 1;
        String taskId = taskService.createTask(
                workspaceId,
                "ARTIFACT_JOB",
                "ARTIFACT_JOB",
                artifactJobId,
                "QUEUED",
                "产物版本再生成任务已创建"
        );
        jdbcTemplate.update("""
                insert into artifact_job_run(
                    task_id, artifact_job_id, run_no, trigger_type, source_version_no,
                    user_requirement, inputs_json, source_scope_json, control_pack_json, input_snapshot_id
                ) values (?, ?, ?, 'REGENERATE', ?, ?, ?, ?, ?, ?)
                """,
                taskId, artifactJobId, nextRunNo, sourceVersionNo, requirement,
                Json.write(objectMapper, inputs), row.sourceScopeJson(), row.controlPackJson(), row.inputSnapshotId());
        jdbcTemplate.update("""
                update artifact_job
                set task_id = ?, user_requirement = ?, inputs_json = ?, status = 'QUEUED', updated_at = current_timestamp
                where id = ? and workspace_id = ?
                """, taskId, requirement, Json.write(objectMapper, inputs), artifactJobId, workspaceId);
        memoryCompilerService.logPackUsage(
                workspaceId,
                "ARTIFACT",
                "ARTIFACT_JOB_RUN",
                taskId,
                readControlPack(row.controlPackJson())
        );
        enqueueArtifactTask(taskId, workspaceId, artifactJobId, "regenerate-v" + sourceVersionNo);
        return new ArtifactJobResponse(artifactJobId, taskId, row.skillKey(), "QUEUED");
    }

    @Transactional
    public ArtifactVersionDetailResponse rollbackVersion(
            String workspaceId,
            String artifactJobId,
            int sourceVersionNo,
            RollbackArtifactVersionRequest request
    ) {
        requireWorkspace(workspaceId);
        RollbackRow source = loadRollbackRow(workspaceId, artifactJobId, sourceVersionNo);
        int nextVersionNo = jdbcTemplate.queryForObject(
                "select latest_version_no from artifact_job where id = ? and workspace_id = ? for update",
                Integer.class,
                artifactJobId,
                workspaceId
        ) + 1;
        String versionId = Ids.newId();
        String requestedTitle = request == null || request.title() == null ? "" : request.title().trim();
        String title = requestedTitle.isBlank() ? source.title() + "（回滚副本）" : requestedTitle;
        Map<String, Object> payload = new LinkedHashMap<>(readInputs(source.resultPayloadJson()));
        payload.put("rollback_of_version_no", sourceVersionNo);
        payload.put("rollback_mode", "APPEND_ONLY_COPY");
        jdbcTemplate.update("""
                insert into artifact_version(
                    id, artifact_job_id, skill_key, version_no, title, content_markdown,
                    result_payload_json, trace_summary, citations_json, origin_task_id
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                versionId, artifactJobId, source.skillKey(), nextVersionNo, title, source.contentMarkdown(),
                Json.write(objectMapper, payload),
                "append-only rollback of version " + sourceVersionNo,
                source.citationsJson(), source.originTaskId());
        jdbcTemplate.update("""
                update artifact_job
                set latest_version_no = ?, result_title = ?, status = 'COMPLETED', updated_at = current_timestamp
                where id = ? and workspace_id = ?
                """, nextVersionNo, title, artifactJobId, workspaceId);
        return getVersionDetail(workspaceId, artifactJobId, nextVersionNo);
    }

    public ArtifactVersionComparisonResponse compareVersions(
            String workspaceId,
            String artifactJobId,
            int fromVersionNo,
            int toVersionNo
    ) {
        ArtifactVersionDetailResponse from = getVersionDetail(workspaceId, artifactJobId, fromVersionNo);
        ArtifactVersionDetailResponse to = getVersionDetail(workspaceId, artifactJobId, toVersionNo);
        Map<String, Integer> fromLines = lineCounts(from.contentMarkdown());
        Map<String, Integer> toLines = lineCounts(to.contentMarkdown());
        int unchanged = 0;
        int removed = 0;
        int added = 0;
        for (Map.Entry<String, Integer> entry : fromLines.entrySet()) {
            int matched = Math.min(entry.getValue(), toLines.getOrDefault(entry.getKey(), 0));
            unchanged += matched;
            removed += entry.getValue() - matched;
        }
        for (Map.Entry<String, Integer> entry : toLines.entrySet()) {
            added += Math.max(0, entry.getValue() - fromLines.getOrDefault(entry.getKey(), 0));
        }
        boolean titleChanged = !from.title().equals(to.title());
        return new ArtifactVersionComparisonResponse(
                fromVersionNo,
                toVersionNo,
                titleChanged,
                added,
                removed,
                unchanged,
                "v%d → v%d：新增 %d 行，删除 %d 行，保留 %d 行%s".formatted(
                        fromVersionNo, toVersionNo, added, removed, unchanged,
                        titleChanged ? "，标题已变化" : ""
                )
        );
    }

    public List<ArtifactJobSummaryResponse> listJobs(String workspaceId) {
        requireWorkspace(workspaceId);
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
                """, (rs, rowNum) -> new ArtifactJobSummaryResponse(
                rs.getString("id"),
                rs.getString("workspace_id"),
                rs.getString("task_id"),
                blankIfNull(rs.getString("skill_key")),
                rs.getString("status"),
                blankIfNull(rs.getString("task_status")),
                blankIfNull(rs.getString("progress_phase")),
                blankIfNull(rs.getString("progress_message")),
                blankIfNull(rs.getString("result_title")),
                taskService.loadWaitContext(
                        rs.getString("task_id"),
                        rs.getString("task_status"),
                        rs.getString("progress_phase")
                ),
                rs.getInt("latest_version_no"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        ), workspaceId);
    }

    public ArtifactJobDetailResponse getJob(String workspaceId, String artifactJobId) {
        requireWorkspace(workspaceId);
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
            return new ArtifactJobDetailResponse(
                    rs.getString("id"),
                    rs.getString("workspace_id"),
                    rs.getString("task_id"),
                    blankIfNull(rs.getString("skill_key")),
                    blankIfNull(rs.getString("user_requirement")),
                    readInputs(blankIfNull(rs.getString("inputs_json"))),
                    blankIfNull(rs.getString("status")),
                    blankIfNull(rs.getString("task_status")),
                    blankIfNull(rs.getString("progress_phase")),
                    blankIfNull(rs.getString("progress_message")),
                    blankIfNull(rs.getString("result_title")),
                    taskService.loadWaitContext(
                            rs.getString("task_id"),
                            rs.getString("task_status"),
                            rs.getString("progress_phase")
                    ),
                    rs.getInt("latest_version_no"),
                    toInstant(rs.getTimestamp("created_at")),
                    toInstant(rs.getTimestamp("updated_at"))
            );
        }, workspaceId, artifactJobId);
    }

    public List<ArtifactVersionSummaryResponse> listVersions(String workspaceId, String artifactJobId) {
        requireWorkspace(workspaceId);
        requireArtifactJob(workspaceId, artifactJobId);
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
                """, (rs, rowNum) -> new ArtifactVersionSummaryResponse(
                rs.getString("id"),
                rs.getString("artifact_job_id"),
                blankIfNull(rs.getString("skill_key")),
                rs.getInt("version_no"),
                rs.getString("title"),
                toInstant(rs.getTimestamp("created_at"))
        ), workspaceId, artifactJobId);
    }

    public ArtifactVersionDetailResponse getVersionDetail(
            String workspaceId,
            String artifactJobId,
            int versionNo
    ) {
        requireWorkspace(workspaceId);
        requireArtifactJob(workspaceId, artifactJobId);
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
            return new ArtifactVersionDetailResponse(
                    rs.getString("id"),
                    rs.getString("artifact_job_id"),
                    blankIfNull(rs.getString("skill_key")),
                    rs.getInt("version_no"),
                    rs.getString("title"),
                    blankIfNull(rs.getString("content_markdown")),
                    blankIfNull(rs.getString("trace_summary")),
                    readCitations(blankIfNull(rs.getString("citations_json"))),
                    readRuntimeTrace(blankIfNull(rs.getString("result_payload_json"))),
                    loadArtifactFiles(rs.getString("id")),
                    toInstant(rs.getTimestamp("created_at"))
            );
        }, workspaceId, artifactJobId, versionNo);
    }

    @Transactional
    public ArtifactSavedSourceResponse saveVersionAsSource(
            String workspaceId,
            String artifactJobId,
            int versionNo
    ) {
        requireWorkspace(workspaceId);
        ArtifactVersionSourceRow row = loadVersionForSource(workspaceId, artifactJobId, versionNo);
        if (row.contentMarkdown().isBlank()) {
            throw new BusinessException("ARTIFACT_VERSION_CONTENT_EMPTY", "产物版本正文为空，不能保存为资料");
        }
        GeneratedSourceResult source = generatedSourceService.saveMarkdown(
                workspaceId,
                row.title(),
                row.contentMarkdown(),
                "GENERATED_ARTIFACT",
                "artifact_agent",
                row.versionId()
        );
        return new ArtifactSavedSourceResponse(
                source.sourceId(),
                artifactJobId,
                row.versionId(),
                source.status(),
                source.parseStatus(),
                source.indexStatus(),
                source.generatedBy(),
                source.generatedRefId()
        );
    }

    @Transactional
    public KnowledgeItemResponse writeVersionToKnowledge(
            String workspaceId,
            String artifactJobId,
            int versionNo,
            ArtifactKnowledgeWritebackRequest request
    ) {
        requireWorkspace(workspaceId);
        ArtifactVersionSourceRow row = loadVersionForSource(workspaceId, artifactJobId, versionNo);
        if (row.contentMarkdown().isBlank()) {
            throw new BusinessException("ARTIFACT_VERSION_CONTENT_EMPTY", "产物版本正文为空，不能写回知识库");
        }
        String requestedTitle = request.title() == null ? "" : request.title().trim();
        String title = requestedTitle.isBlank() ? row.title() : requestedTitle;
        return knowledgeCommandService.createItem(
                workspaceId,
                new KnowledgeItemRequest(request.itemType(), title, row.contentMarkdown(), null)
        );
    }

    public ArtifactWorkerInputResponse getWorkerInput(String taskId) {
        JobRow row = findByTaskId(taskId);
        Map<String, Object> inputs = readInputs(row.inputsJson());
        return new ArtifactWorkerInputResponse(
                row.taskId(),
                row.workspaceId(),
                row.artifactJobId(),
                row.inputSnapshotId(),
                row.replayAvailability(),
                readCapturedSourceScope(row.workspaceId(), row.sourceScopeJson()),
                readUpstreamRefs(row.upstreamRefsJson()),
                new WorkerContextSnapshotResponse(row.contextSnapshotId() == null ? "" : row.contextSnapshotId()),
                readControlPack(row.controlPackJson()),
                ArtifactWorkerInputPayload.skillFirst(
                        row.skillKey(),
                        blankIfNull(row.styleProfileKey()),
                        blankIfNull(row.contextSnapshotId()),
                        row.userRequirement(),
                        row.userRequirement(),
                        inputs
                )
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
    public void markWaiting(String taskId, String waitStatus) {
        jdbcTemplate.update("""
                update artifact_job
                set status = ?, updated_at = current_timestamp
                where task_id = ?
                """, blankToNull(waitStatus) == null ? "WAITING_FOR_PROVIDER" : waitStatus.trim(), taskId);
    }

    @Transactional
    public CompletionOutcome completeFromWorker(String taskId, com.noteweave.worker.WorkerCompleteRequest request) {
        JobRow row = findByTaskId(taskId);
        int nextVersionNo = row.latestVersionNo() + 1;
        String versionId = Ids.newId();
        String markdown = extractMarkdown(request.resultPayload());
        jdbcTemplate.update("""
                insert into artifact_version(
                    id, artifact_job_id, skill_key, version_no, title, content_markdown, result_payload_json,
                    trace_summary, citations_json, origin_task_id
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                versionId,
                row.artifactJobId(),
                row.skillKey(),
                nextVersionNo,
                request.resultTitle(),
                markdown,
                Json.write(objectMapper, request.resultPayload() == null ? Map.of() : request.resultPayload()),
                blankToNull(request.traceSummary()),
                Json.write(objectMapper, request.citations() == null ? List.of() : request.citations()),
                taskId
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
            return new JobRow(
                    rs.getString("id"),
                    rs.getString("workspace_id"),
                    rs.getString("task_id"),
                    blankIfNull(rs.getString("skill_key")),
                    rs.getString("style_profile_key"),
                    rs.getString("context_snapshot_id"),
                    rs.getString("input_snapshot_id"),
                    blankIfNull(rs.getString("user_requirement")),
                    blankIfNull(rs.getString("inputs_json")),
                    blankIfNull(rs.getString("source_scope_snapshot_json")),
                    blankIfNull(rs.getString("upstream_refs_json")),
                    rs.getString("control_pack_json"),
                    rs.getString("replay_availability"),
                    rs.getInt("latest_version_no")
            );
        }, taskId);
    }

    private RegenerationRow loadRegenerationRow(String workspaceId, String artifactJobId) {
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
            return new RegenerationRow(
                    blankIfNull(rs.getString("skill_key")),
                    blankIfNull(rs.getString("user_requirement")),
                    blankIfNull(rs.getString("inputs_json")),
                    blankIfNull(rs.getString("source_scope_json")),
                    blankIfNull(rs.getString("control_pack_json")),
                    rs.getString("input_snapshot_id"),
                    rs.getInt("latest_run_no")
            );
        }, workspaceId, artifactJobId);
    }

    private RollbackRow loadRollbackRow(String workspaceId, String artifactJobId, int versionNo) {
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
            return new RollbackRow(
                    blankIfNull(rs.getString("skill_key")),
                    blankIfNull(rs.getString("title")),
                    blankIfNull(rs.getString("content_markdown")),
                    blankIfNull(rs.getString("result_payload_json")),
                    blankIfNull(rs.getString("citations_json")),
                    blankIfNull(rs.getString("origin_task_id"))
            );
        }, workspaceId, artifactJobId, versionNo);
    }

    private void enqueueArtifactTask(String taskId, String workspaceId, String artifactJobId, String traceSuffix) {
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
                        "trace_id", artifactJobId + ":" + traceSuffix,
                        "created_at", System.currentTimeMillis()
                ))
        );
    }

    private Map<String, Integer> lineCounts(String markdown) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String line : blankIfNull(markdown).split("\\R", -1)) {
            counts.merge(line, 1, Integer::sum);
        }
        return counts;
    }

    private List<WorkerSourceScopeItemResponse> loadSourceScopeSnapshot(
            String workspaceId,
            String sourceScopeJson
    ) {
        List<String> sourceIds = readSourceScopeIds(sourceScopeJson);
        if (sourceIds.isEmpty()) {
            return List.of();
        }
        return sourceIds.stream()
                .map(sourceId -> loadSourceScopeItem(workspaceId, sourceId))
                .flatMap(List::stream)
                .toList();
    }

    private List<WorkerSourceScopeItemResponse> captureRequestedSourceScope(
            String workspaceId,
            List<String> requestedSourceIds
    ) {
        java.util.ArrayList<WorkerSourceScopeItemResponse> captured = new java.util.ArrayList<>();
        for (String sourceId : requestedSourceIds) {
            List<WorkerSourceScopeItemResponse> matches = loadSourceScopeItem(workspaceId, sourceId);
            if (matches.isEmpty()) {
                throw new BusinessException("ARTIFACT_SOURCE_SCOPE_INVALID",
                        "Artifact source scope contains a missing, cross-workspace, or non-ready Source");
            }
            captured.add(matches.get(0));
        }
        return List.copyOf(captured);
    }

    private List<ArtifactUpstreamRefRequest> validateUpstreamRefs(
            String workspaceId,
            List<ArtifactUpstreamRefRequest> refs
    ) {
        for (ArtifactUpstreamRefRequest ref : refs) {
            if (!Set.of("SOURCE_SNAPSHOT", "RESEARCH_REPORT").contains(ref.refType())) {
                throw new BusinessException("ARTIFACT_UPSTREAM_REF_INVALID",
                        "Artifact upstream ref type is unsupported");
            }
            Integer matches = "SOURCE_SNAPSHOT".equals(ref.refType())
                    ? jdbcTemplate.queryForObject("""
                    select count(*) from source s join source_snapshot ss on ss.source_id = s.id
                    where s.workspace_id = ? and s.id = ? and ss.id = ? and s.status = 'READY'
                      and ss.parse_status = 'PARSED' and ss.index_status = 'INDEXED'
                    """, Integer.class, workspaceId, ref.refId(), ref.revisionId())
                    : jdbcTemplate.queryForObject("""
                    select count(*)
                    from research_run rr
                    join source s on s.workspace_id = rr.workspace_id
                      and s.generated_by = 'research_agent' and s.generated_ref_id = rr.id
                    join source_snapshot ss on ss.source_id = s.id
                    where rr.workspace_id = ? and rr.id = ? and ss.id = ? and s.status = 'READY'
                      and ss.parse_status = 'PARSED' and ss.index_status = 'INDEXED'
                    """, Integer.class, workspaceId, ref.refId(), ref.revisionId());
            if (matches == null || matches == 0) {
                throw new BusinessException("ARTIFACT_UPSTREAM_REF_INVALID",
                        "Artifact upstream ref does not belong to this workspace or revision");
            }
        }
        return List.copyOf(refs);
    }

    private List<WorkerSourceScopeItemResponse> loadSourceScopeItem(String workspaceId, String sourceId) {
        return jdbcTemplate.query("""
                select s.id, s.title, s.source_type, coalesce(s.summary, '') as summary,
                       coalesce(s.generated_by, '') as generated_by,
                       coalesce(s.generated_ref_id, '') as generated_ref_id,
                       ss.id as source_snapshot_id, ss.version_no as source_snapshot_version_no,
                       ss.sha256 as source_snapshot_sha256,
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
                  and ss.parse_status = 'PARSED' and ss.index_status = 'INDEXED'
                """, (rs, rowNum) -> {
            String generatedBy = blankIfNull(rs.getString("generated_by"));
            String generatedRefId = blankIfNull(rs.getString("generated_ref_id"));
            return new WorkerSourceScopeItemResponse(
                    rs.getString("id"),
                    rs.getString("title"),
                    rs.getString("summary"),
                    rs.getString("sample_text"),
                    generatedBy,
                    generatedRefId,
                    blankIfNull(rs.getString("source_type")),
                    "",
                    buildSourceScopeMetadata(generatedBy, generatedRefId,
                            rs.getString("source_snapshot_id"),
                            rs.getInt("source_snapshot_version_no"),
                            rs.getString("source_snapshot_sha256"))
            );
        }, workspaceId, sourceId);
    }

    private List<WorkerSourceScopeItemResponse> readCapturedSourceScope(String workspaceId, String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return List.of();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(json);
            if (root.isArray() && !root.isEmpty() && root.get(0).isTextual()) {
                return loadSourceScopeSnapshot(workspaceId, json);
            }
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            throw new BusinessException("ARTIFACT_INPUT_SNAPSHOT_PARSE_FAILED",
                    "Artifact input snapshot cannot be parsed");
        }
    }

    private List<ArtifactUpstreamRefRequest> readUpstreamRefs(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            throw new BusinessException("ARTIFACT_UPSTREAM_REFS_PARSE_FAILED",
                    "Artifact upstream refs cannot be parsed");
        }
    }

    private List<String> readSourceScopeIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            throw new BusinessException("ARTIFACT_SOURCE_SCOPE_PARSE_FAILED", "产物资料范围解析失败");
        }
    }

    private Map<String, Object> buildSourceScopeMetadata(String generatedBy, String generatedRefId) {
        if (!"research_agent".equals(generatedBy) || generatedRefId.isBlank()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> researchArtifact = new LinkedHashMap<>();
        researchArtifact.put("artifact_id", generatedRefId);
        researchArtifact.put("artifact_type", "DEEP_RESEARCH_REPORT");
        researchArtifact.put("generated_by", generatedBy);
        researchArtifact.put("generated_ref_id", generatedRefId);
        return Map.of("research_artifact", researchArtifact);
    }

    private Map<String, Object> buildSourceScopeMetadata(
            String generatedBy,
            String generatedRefId,
            String sourceSnapshotId,
            int sourceSnapshotVersionNo,
            String sourceSnapshotSha256
    ) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(
                buildSourceScopeMetadata(generatedBy, generatedRefId));
        metadata.put("source_snapshot_id", sourceSnapshotId);
        metadata.put("source_snapshot_version_no", sourceSnapshotVersionNo);
        metadata.put("source_snapshot_sha256", sourceSnapshotSha256);
        return Map.copyOf(metadata);
    }

    private MemoryControlPackResponse readControlPack(String json) {
        try {
            return objectMapper.readValue(json, MemoryControlPackResponse.class);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("ARTIFACT_CONTROL_PACK_PARSE_FAILED", "产物控制包解析失败");
        }
    }

    private Map<String, Object> readInputs(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            throw new BusinessException("ARTIFACT_INPUTS_PARSE_FAILED", "产物 Skill 输入解析失败");
        }
    }

    private List<Map<String, Object>> readCitations(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            throw new BusinessException("ARTIFACT_CITATIONS_PARSE_FAILED", "产物引用解析失败");
        }
    }

    private ArtifactRuntimeTraceResponse readRuntimeTrace(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            throw new BusinessException("ARTIFACT_RESULT_PAYLOAD_PARSE_FAILED", "产物结果载荷解析失败");
        }
        Object nestedRuntimeTrace = payload.get("runtime_trace");
        Map<String, Object> runtimeTracePayload;
        if (nestedRuntimeTrace instanceof Map<?, ?> nestedMap) {
            runtimeTracePayload = normalizeObjectMap(nestedMap);
        } else {
            LinkedHashMap<String, Object> runtimeTrace = new LinkedHashMap<>();
            copyRuntimeTraceField(payload, runtimeTrace, "verification");
            copyRuntimeTraceField(payload, runtimeTrace, "generation_trace");
            copyRuntimeTraceField(payload, runtimeTrace, "export_trace");
            copyRuntimeTraceField(payload, runtimeTrace, "node_traces");
            copyRuntimeTraceField(payload, runtimeTrace, "capability_union_trace");
            copyRuntimeTraceField(payload, runtimeTrace, "approval_trace");
            copyRuntimeTraceField(payload, runtimeTrace, "evidence_coverage");
            copyRuntimeTraceField(payload, runtimeTrace, "writeback_preview");
            copyRuntimeTraceField(payload, runtimeTrace, "output_contract_trace");
            copyRuntimeTraceField(payload, runtimeTrace, "lifecycle_trace");
            copyRuntimeTraceField(payload, runtimeTrace, "acquisition_callback_trace");
            runtimeTracePayload = runtimeTrace;
        }
        runtimeTracePayload = normalizeAndStripLegacyActionKeys(runtimeTracePayload);
        if (runtimeTracePayload.isEmpty()) {
            return null;
        }
        return new ArtifactRuntimeTraceResponse(
                convertTraceValue(
                        runtimeTracePayload.get("verification"),
                        ArtifactVerificationTraceResponse.class,
                        "ARTIFACT_RUNTIME_VERIFICATION_PARSE_FAILED",
                        "产物运行时 verification trace 解析失败"
                ),
                convertTraceValue(
                        runtimeTracePayload.get("generation_trace"),
                        ArtifactGenerationTraceResponse.class,
                        "ARTIFACT_RUNTIME_GENERATION_TRACE_PARSE_FAILED",
                        "产物运行时 generation trace 解析失败"
                ),
                convertTraceValue(
                        runtimeTracePayload.get("export_trace"),
                        ArtifactExportTraceResponse.class,
                        "ARTIFACT_RUNTIME_EXPORT_TRACE_PARSE_FAILED",
                        "产物运行时 export trace 解析失败"
                ),
                convertTraceValue(
                        runtimeTracePayload.get("approval_trace"),
                        ArtifactApprovalTraceResponse.class,
                        "ARTIFACT_RUNTIME_APPROVAL_TRACE_PARSE_FAILED",
                        "产物运行时 approval trace 解析失败"
                ),
                convertTraceValue(
                        runtimeTracePayload.get("capability_union_trace"),
                        ArtifactCapabilityUnionTraceResponse.class,
                        "ARTIFACT_RUNTIME_CAPABILITY_UNION_PARSE_FAILED",
                        "产物运行时 capability union trace 解析失败"
                ),
                convertTraceList(
                        runtimeTracePayload.get("node_traces"),
                        ArtifactNodeTraceResponse.class,
                        "ARTIFACT_RUNTIME_NODE_TRACES_PARSE_FAILED",
                        "产物运行时 node traces 解析失败"
                ),
                convertTraceValue(
                        runtimeTracePayload.get("evidence_coverage"),
                        ArtifactEvidenceCoverageTraceResponse.class,
                        "ARTIFACT_RUNTIME_EVIDENCE_COVERAGE_PARSE_FAILED",
                        "产物运行时 evidence coverage trace 解析失败"
                ),
                convertTraceValue(
                        runtimeTracePayload.get("writeback_preview"),
                        ArtifactWritebackPreviewTraceResponse.class,
                        "ARTIFACT_RUNTIME_WRITEBACK_PREVIEW_PARSE_FAILED",
                        "产物运行时 writeback preview trace 解析失败"
                ),
                convertTraceValue(
                        runtimeTracePayload.get("output_contract_trace"),
                        ArtifactOutputContractTraceResponse.class,
                        "ARTIFACT_RUNTIME_OUTPUT_CONTRACT_PARSE_FAILED",
                        "产物运行时 output contract trace 解析失败"
                ),
                convertTraceValue(
                        runtimeTracePayload.get("lifecycle_trace"),
                        ArtifactLifecycleTraceResponse.class,
                        "ARTIFACT_RUNTIME_LIFECYCLE_PARSE_FAILED",
                        "产物运行时 lifecycle trace 解析失败"
                ),
                convertTraceValue(
                        runtimeTracePayload.get("acquisition_callback_trace"),
                        ArtifactAcquisitionCallbackTraceResponse.class,
                        "ARTIFACT_RUNTIME_ACQUISITION_CALLBACK_PARSE_FAILED",
                        "产物运行时 acquisition callback trace 解析失败"
                )
        );
    }

    private void copyRuntimeTraceField(
            Map<String, Object> payload,
            Map<String, Object> runtimeTrace,
            String fieldName
    ) {
        if (!payload.containsKey(fieldName)) {
            return;
        }
        Object value = payload.get(fieldName);
        if (value == null) {
            return;
        }
        runtimeTrace.put(fieldName, value);
    }

    private Map<String, Object> normalizeObjectMap(Map<?, ?> rawMap) {
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            normalized.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return normalized;
    }

    private Map<String, Object> normalizeAndStripLegacyActionKeys(Map<String, Object> rawMap) {
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : rawMap.entrySet()) {
            if ("action_checks".equals(entry.getKey())) {
                normalized.put("contract_checks", stripLegacyActionKeys(entry.getValue()));
                continue;
            }
            if (Set.of(
                    "action_key",
                    "action_scope",
                    "action_basis",
                    "skill_graph_basis",
                    "requested_action_key",
                    "resolved_action_key",
                    "explicit_requested_action_key",
                    "effective_action_key")
                    .contains(entry.getKey())) {
                continue;
            }
            normalized.put(entry.getKey(), stripLegacyActionKeys(entry.getValue()));
        }
        return normalized;
    }

    private Object stripLegacyActionKeys(Object value) {
        if (value instanceof Map<?, ?> rawMap) {
            return normalizeAndStripLegacyActionKeys(normalizeObjectMap(rawMap));
        }
        if (value instanceof List<?> rawList) {
            return rawList.stream()
                    .map(this::stripLegacyActionKeys)
                    .toList();
        }
        return value;
    }

    private <T> T convertTraceValue(
            Object value,
            Class<T> targetType,
            String errorCode,
            String errorMessage
    ) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.convertValue(value, targetType);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(errorCode, errorMessage);
        }
    }

    private <T> List<T> convertTraceList(
            Object value,
            Class<T> elementType,
            String errorCode,
            String errorMessage
    ) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> rawList)) {
            throw new BusinessException(errorCode, errorMessage);
        }
        try {
            return rawList.stream()
                    .map(item -> objectMapper.convertValue(item, elementType))
                    .toList();
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(errorCode, errorMessage);
        }
    }

    private void requireArtifactJob(String workspaceId, String artifactJobId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from artifact_job
                where workspace_id = ? and id = ?
                """, Integer.class, workspaceId, artifactJobId);
        if (count == null || count == 0) {
            throw new BusinessException("ARTIFACT_JOB_NOT_FOUND", "产物任务不存在");
        }
    }

    private ArtifactVersionSourceRow loadVersionForSource(
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
                    blankIfNull(rs.getString("content_markdown"))
            );
        }, workspaceId, artifactJobId, versionNo);
    }

    private List<ArtifactFileMetadataResponse> loadArtifactFiles(String artifactVersionId) {
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
                toInstant(rs.getTimestamp("created_at"))
        ), artifactVersionId);
    }

    private void requireWorkspace(String workspaceId) {
        if (!workspaceService.exists(workspaceId)) {
            throw new BusinessException("WORKSPACE_NOT_FOUND", "工作台不存在");
        }
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

    private static String blankIfNull(String value) {
        return value == null ? "" : value;
    }

    private Instant toInstant(Timestamp value) {
        return value == null ? Instant.now() : value.toInstant();
    }

    private record JobRow(
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

    private record ArtifactVersionSourceRow(String versionId, String title, String contentMarkdown) {
    }

    private record RegenerationRow(
            String skillKey,
            String userRequirement,
            String inputsJson,
            String sourceScopeJson,
            String controlPackJson,
            String inputSnapshotId,
            int latestRunNo
    ) {
    }

    private record RollbackRow(
            String skillKey,
            String title,
            String contentMarkdown,
            String resultPayloadJson,
            String citationsJson,
            String originTaskId
    ) {
    }
}
