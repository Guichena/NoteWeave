package com.noteweave.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import com.noteweave.memory.MemoryCompilerService;
import com.noteweave.memory.MemoryControlPackResponse;
import com.noteweave.storage.ObjectStorage;
import com.noteweave.task.TaskService;
import com.noteweave.workspace.WorkspaceService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns the write-side lifecycle for Research runs. */
@Service
public class ResearchRunCommandService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final WorkspaceService workspaceService;
    private final TaskService taskService;
    private final MemoryCompilerService memoryCompilerService;
    private final ResearchAgentRunBootstrapService researchAgentRunBootstrapService;
    private final ResearchAgentExternalEvidencePolicy externalEvidencePolicy;
    private final ResearchCheckpointStore researchCheckpointStore;
    private final ObjectStorage storage;

    public ResearchRunCommandService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            WorkspaceService workspaceService,
            TaskService taskService,
            MemoryCompilerService memoryCompilerService,
            ResearchAgentRunBootstrapService researchAgentRunBootstrapService,
            ResearchAgentExternalEvidencePolicy externalEvidencePolicy,
            ResearchCheckpointStore researchCheckpointStore,
            ObjectStorage storage
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.workspaceService = workspaceService;
        this.taskService = taskService;
        this.memoryCompilerService = memoryCompilerService;
        this.researchAgentRunBootstrapService = researchAgentRunBootstrapService;
        this.externalEvidencePolicy = externalEvidencePolicy;
        this.researchCheckpointStore = researchCheckpointStore;
        this.storage = storage;
    }

    @Transactional
    public ResearchRunResponse createRun(String workspaceId, CreateResearchRunRequest request) {
        requireWorkspace(workspaceId);
        String researchRunId = Ids.newId();
        String profileKey = ResearchIntentPolicy.normalizeProfile(request.profile());
        ResearchIntentResponse researchIntent = ResearchIntentPolicy.normalize(request);
        String taskId = taskService.createTask(
                workspaceId, "RESEARCH_RUN", "RESEARCH_RUN", researchRunId, "QUEUED",
                "Deep Research 任务已创建"
        );
        MemoryControlPackResponse controlPack = memoryCompilerService.compileResearchControlPack(
                workspaceId, profileKey);
        ResearchAcquisitionPolicy acquisitionPolicy = ResearchAcquisitionPolicy.compile(
                request, externalEvidencePolicy);
        List<String> sourceScopeIds = resolveRequestedSourceScopeIds(
                workspaceId, acquisitionPolicy.seedSourceIds());
        jdbcTemplate.update("""
                insert into research_run(
                    id, workspace_id, task_id, question, profile_key,
                    research_intent_json, source_scope_json, control_pack_json, retrieval_mode,
                    status, agent_execution_mode
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'RUNNING', 'INCREMENTAL_V1')
                """,
                researchRunId, workspaceId, taskId, request.question().trim(), profileKey,
                Json.write(objectMapper, researchIntent),
                Json.write(objectMapper, sourceScopeIds),
                Json.write(objectMapper, controlPack),
                acquisitionPolicy.mode().name()
        );
        researchAgentRunBootstrapService.bootstrap(
                researchRunId, request.question().trim(), researchIntent);
        startCoordinatingTask(taskId, "Research Agent matrix initialized");
        insertTrace(researchRunId, "RUN_CREATED", "Research run 已入队", Map.of(
                "question", request.question().trim(),
                "profile_key", profileKey,
                "research_intent", objectMapper.convertValue(
                        researchIntent, new TypeReference<Map<String, Object>>() { }),
                "retrieval_mode", acquisitionPolicy.mode().name(),
                "source_scope_count", sourceScopeIds.size()
        ));
        memoryCompilerService.logPackUsage(
                workspaceId, "RESEARCH", "RESEARCH_RUN", researchRunId, controlPack);
        return new ResearchRunResponse(researchRunId, taskId, "RUNNING");
    }

    @Transactional
    public ResearchRunResponse resumeFromCheckpoint(
            String workspaceId,
            String researchRunId,
            int checkpointNo
    ) {
        requireWorkspace(workspaceId);
        ResumeSourceRun sourceRun = loadResumeSourceRun(workspaceId, researchRunId);
        ResearchCheckpointRecord checkpoint = researchCheckpointStore.get(workspaceId, researchRunId, checkpointNo);
        ResearchCheckpointIntegrity.verify(
                checkpoint,
                storage.read("noteweave-derived", checkpoint.objectKey())
        );

        String resumedResearchRunId = Ids.newId();
        String taskId = taskService.createTask(
                workspaceId, "RESEARCH_RUN", "RESEARCH_RUN", resumedResearchRunId, "QUEUED",
                "Deep Research 恢复任务已创建"
        );
        jdbcTemplate.update("""
                insert into research_run(
                    id, workspace_id, task_id, question, profile_key,
                    research_intent_json, source_scope_json, control_pack_json, retrieval_mode, status,
                    resumed_from_research_run_id, resumed_from_checkpoint_no, agent_execution_mode
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'RUNNING', ?, ?, 'INCREMENTAL_V1')
                """,
                resumedResearchRunId, workspaceId, taskId, sourceRun.question(), sourceRun.profileKey(),
                sourceRun.researchIntentJson(), sourceRun.sourceScopeJson(), sourceRun.controlPackJson(),
                sourceRun.retrievalMode().name(), researchRunId, checkpointNo
        );
        ResearchIntentResponse resumedIntent = readResearchIntent(sourceRun.researchIntentJson());
        researchAgentRunBootstrapService.bootstrap(
                resumedResearchRunId, sourceRun.question(), resumedIntent);
        startCoordinatingTask(taskId, "Research checkpoint restored into canonical agent coordination");
        insertTrace(resumedResearchRunId, "RUN_CREATED", "Research resume run 已入队", Map.of(
                "question", sourceRun.question(),
                "profile_key", sourceRun.profileKey(),
                "research_intent", objectMapper.convertValue(
                        resumedIntent, new TypeReference<Map<String, Object>>() { }),
                "source_scope_count", readStringList(sourceRun.sourceScopeJson()).size(),
                "retrieval_mode", sourceRun.retrievalMode().name(),
                "resumed_from_research_run_id", researchRunId,
                "resumed_from_checkpoint_no", checkpointNo
        ));
        insertTrace(
                resumedResearchRunId,
                "RUN_RESUMED_FROM_CHECKPOINT",
                "Research run 从 checkpoint 恢复创建",
                Map.of(
                        "resumed_from_research_run_id", researchRunId,
                        "resumed_from_checkpoint_no", checkpointNo
                )
        );
        memoryCompilerService.logPackUsage(
                workspaceId,
                "RESEARCH",
                "RESEARCH_RUN",
                resumedResearchRunId,
                readControlPack(sourceRun.controlPackJson())
        );
        return new ResearchRunResponse(resumedResearchRunId, taskId, "RUNNING");
    }

    @Transactional
    public void setAgentExecutionMode(String researchRunId, String mode) {
        String normalized = mode == null ? "" : mode.trim().toUpperCase();
        if (!List.of("SEQUENTIAL_V1", "SEQUENTIAL_V2", "LOCAL_PARALLEL", "INCREMENTAL_V1")
                .contains(normalized)) {
            throw new BusinessException(
                    "RESEARCH_AGENT_EXECUTION_MODE_INVALID",
                    "Unsupported research agent execution mode"
            );
        }
        Map<String, String> current = jdbcTemplate.query("""
                select agent_execution_mode, status from research_run where id = ? for update
                """, rs -> rs.next()
                ? Map.of("mode", rs.getString(1), "status", rs.getString(2))
                : null, researchRunId);
        if (current == null || List.of("COMPLETED", "FAILED", "CANCELLED").contains(current.get("status"))) {
            throw new BusinessException(
                    "RESEARCH_AGENT_EXECUTION_MODE_NOT_SWITCHABLE",
                    "Research run is missing or terminal"
            );
        }
        if ("INCREMENTAL_V1".equals(current.get("mode")) && !"INCREMENTAL_V1".equals(normalized)) {
            requireNoIncrementalWork(researchRunId);
        }
        jdbcTemplate.update("""
                update research_run set agent_execution_mode = ?, updated_at = current_timestamp where id = ?
                """, normalized, researchRunId);
    }

    private void requireNoIncrementalWork(String researchRunId) {
        Integer activeTasks = jdbcTemplate.queryForObject("""
                select count(*) from research_agent_task
                where research_run_id = ?
                  and status in ('PENDING', 'CLAIMED', 'RUNNING', 'RETRY_WAIT', 'EXPIRED')
                """, Integer.class, researchRunId);
        if (activeTasks != null && activeTasks > 0) {
            throw new BusinessException(
                    "RESEARCH_AGENT_EXECUTION_MODE_ACTIVE_TASKS",
                    "Cancel or finish active research agent tasks before leaving INCREMENTAL_V1"
            );
        }
        Integer incrementalHistory = jdbcTemplate.queryForObject("""
                select count(*) from research_agent_task where research_run_id = ?
                """, Integer.class, researchRunId);
        Integer checkpoints = jdbcTemplate.queryForObject("""
                select count(*) from research_agent_checkpoint where research_run_id = ?
                """, Integer.class, researchRunId);
        Integer advancements = jdbcTemplate.queryForObject("""
                select count(*) from research_agent_run_advancement where research_run_id = ?
                """, Integer.class, researchRunId);
        int historyCount = zeroIfNull(incrementalHistory)
                + zeroIfNull(checkpoints)
                + zeroIfNull(advancements);
        if (historyCount > 0) {
            throw new BusinessException(
                    "RESEARCH_AGENT_EXECUTION_MODE_INCREMENTAL_HISTORY",
                    "Incremental execution history cannot be handed back to a legacy mode"
            );
        }
    }

    private int zeroIfNull(Integer value) {
        return value == null ? 0 : value;
    }

    private void startCoordinatingTask(String taskId, String progressMessage) {
        taskService.startTask(taskId);
        jdbcTemplate.update("""
                update task set progress_phase = 'AGENT_COORDINATING',
                    progress_message = ?, updated_at = current_timestamp
                where id = ? and task_status = 'RUNNING'
                """, progressMessage, taskId);
    }

    private List<String> resolveRequestedSourceScopeIds(
            String workspaceId,
            List<String> requestedSourceScopeIds
    ) {
        if (requestedSourceScopeIds == null || requestedSourceScopeIds.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> orderedIds = new LinkedHashSet<>();
        for (String sourceId : requestedSourceScopeIds) {
            if (sourceId != null && !sourceId.isBlank()) {
                orderedIds.add(sourceId.trim());
            }
        }
        if (orderedIds.size() > 20) {
            throw new BusinessException(
                    "RESEARCH_SOURCE_SCOPE_TOO_LARGE",
                    "研究资料范围最多只能选择 20 份资料"
            );
        }
        List<String> resolved = new ArrayList<>();
        for (String sourceId : orderedIds) {
            Integer count = jdbcTemplate.queryForObject("""
                    select count(*) from source
                    where workspace_id = ? and id = ? and status = 'READY'
                    """, Integer.class, workspaceId, sourceId);
            if (count == null || count == 0) {
                throw new BusinessException(
                        "RESEARCH_SOURCE_SCOPE_INVALID",
                        "研究资料范围包含不存在或未就绪的资料"
                );
            }
            resolved.add(sourceId);
        }
        return List.copyOf(resolved);
    }

    private ResumeSourceRun loadResumeSourceRun(String workspaceId, String researchRunId) {
        return jdbcTemplate.query("""
                select question, profile_key, research_intent_json,
                       source_scope_json, control_pack_json, retrieval_mode
                from research_run
                where workspace_id = ? and id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("RESEARCH_RUN_NOT_FOUND", "研究任务不存在");
            }
            return new ResumeSourceRun(
                    rs.getString("question"),
                    rs.getString("profile_key"),
                    rs.getString("research_intent_json"),
                    rs.getString("source_scope_json"),
                    rs.getString("control_pack_json"),
                    ResearchRetrievalMode.valueOf(rs.getString("retrieval_mode"))
            );
        }, workspaceId, researchRunId);
    }

    private ResearchIntentResponse readResearchIntent(String json) {
        if (json == null || json.isBlank()) {
            return ResearchIntentPolicy.defaultIntent();
        }
        try {
            return ResearchIntentPolicy.normalize(
                    objectMapper.readValue(json, ResearchIntentResponse.class));
        } catch (JsonProcessingException exception) {
            throw new BusinessException("RESEARCH_INTENT_PARSE_FAILED", "研究意图解析失败");
        }
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new BusinessException("RESEARCH_SOURCE_SCOPE_PARSE_FAILED", "研究资料范围解析失败");
        }
    }

    private MemoryControlPackResponse readControlPack(String json) {
        try {
            return objectMapper.readValue(json, MemoryControlPackResponse.class);
        } catch (JsonProcessingException exception) {
            throw new BusinessException("RESEARCH_CONTROL_PACK_PARSE_FAILED", "研究控制包解析失败");
        }
    }

    private void insertTrace(
            String researchRunId,
            String type,
            String message,
            Map<String, Object> payload
    ) {
        jdbcTemplate.update("""
                insert into research_trace(id, research_run_id, trace_type, trace_message, payload_json)
                values (?, ?, ?, ?, ?)
                """, Ids.newId(), researchRunId, type, message, Json.write(objectMapper, payload));
    }

    private void requireWorkspace(String workspaceId) {
        if (!workspaceService.exists(workspaceId)) {
            throw new BusinessException("WORKSPACE_NOT_FOUND", "工作台不存在");
        }
    }

    private record ResumeSourceRun(
            String question,
            String profileKey,
            String researchIntentJson,
            String sourceScopeJson,
            String controlPackJson,
            ResearchRetrievalMode retrievalMode
    ) { }
}
