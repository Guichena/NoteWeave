package com.noteweave.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import com.noteweave.conversation.ConversationResearchProjectionService;
import com.noteweave.infra.LocalObjectStorage;
import com.noteweave.storage.ObjectStorage;
import com.noteweave.knowledge.WikiIngestService;
import com.noteweave.memory.MemoryCompilerService;
import com.noteweave.memory.MemoryControlPackResponse;
import com.noteweave.source.SourceParseService;
import com.noteweave.source.SourceCatalogVersionService;
import com.noteweave.task.TaskService;
import com.noteweave.task.WaitContextResponse;
import com.noteweave.worker.WorkerFailRequest;
import com.noteweave.worker.WorkerSourceScopeItemResponse;
import com.noteweave.worker.WorkerTaskCallbackService.CompletionOutcome;
import com.noteweave.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResearchRunService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final WorkspaceService workspaceService;
    private final TaskService taskService;
    private final MemoryCompilerService memoryCompilerService;
    private final ObjectStorage storage;
    private final SourceParseService sourceParseService;
    private final WikiIngestService wikiIngestService;
    private final SourceCatalogVersionService sourceCatalogVersionService;
    private final ResearchAgentProjectionService researchAgentProjectionService;
    private final ResearchAgentRunBootstrapService researchAgentRunBootstrapService;
    private final ConversationResearchProjectionService conversationResearchProjectionService;
    private final ResearchAgentExternalEvidencePolicy externalEvidencePolicy;
    private final ResearchCollectionService researchCollectionService;

    public ResearchRunService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            WorkspaceService workspaceService,
            TaskService taskService,
            MemoryCompilerService memoryCompilerService,
            ObjectStorage storage,
            SourceParseService sourceParseService,
            WikiIngestService wikiIngestService,
            SourceCatalogVersionService sourceCatalogVersionService,
            ResearchAgentProjectionService researchAgentProjectionService,
            ResearchAgentRunBootstrapService researchAgentRunBootstrapService,
            ConversationResearchProjectionService conversationResearchProjectionService,
            ResearchAgentExternalEvidencePolicy externalEvidencePolicy,
            ResearchCollectionService researchCollectionService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.workspaceService = workspaceService;
        this.taskService = taskService;
        this.memoryCompilerService = memoryCompilerService;
        this.storage = storage;
        this.sourceParseService = sourceParseService;
        this.wikiIngestService = wikiIngestService;
        this.sourceCatalogVersionService = sourceCatalogVersionService;
        this.researchAgentProjectionService = researchAgentProjectionService;
        this.researchAgentRunBootstrapService = researchAgentRunBootstrapService;
        this.conversationResearchProjectionService = conversationResearchProjectionService;
        this.externalEvidencePolicy = externalEvidencePolicy;
        this.researchCollectionService = researchCollectionService;
    }

    @Transactional
    public ResearchRunResponse createRun(String workspaceId, CreateResearchRunRequest request) {
        requireWorkspace(workspaceId);
        String researchRunId = Ids.newId();
        String profileKey = normalizeToken(request.profile());
        ResearchIntentResponse researchIntent = normalizeResearchIntent(request);
        String taskId = taskService.createTask(
                workspaceId,
                "RESEARCH_RUN",
                "RESEARCH_RUN",
                researchRunId,
                "QUEUED",
                "Deep Research 任务已创建"
        );
        MemoryControlPackResponse controlPack = memoryCompilerService.compileResearchControlPack(workspaceId, profileKey);
        ResearchAcquisitionPolicy acquisitionPolicy = ResearchAcquisitionPolicy.compile(request, externalEvidencePolicy);
        List<String> sourceScopeIds = resolveRequestedSourceScopeIds(workspaceId, acquisitionPolicy.seedSourceIds());
        jdbcTemplate.update("""
                insert into research_run(
                    id, workspace_id, task_id, question, profile_key,
                    research_intent_json, source_scope_json, control_pack_json, retrieval_mode,
                    status, agent_execution_mode
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'RUNNING', 'INCREMENTAL_V1')
                """,
                researchRunId,
                workspaceId,
                taskId,
                request.question().trim(),
                profileKey,
                Json.write(objectMapper, researchIntent),
                Json.write(objectMapper, sourceScopeIds),
                Json.write(objectMapper, controlPack),
                acquisitionPolicy.mode().name()
        );
        researchAgentRunBootstrapService.bootstrap(
                researchRunId, request.question().trim(), researchIntent);
        jdbcTemplate.update("""
                update task set task_status = 'RUNNING', progress_phase = 'AGENT_COORDINATING',
                    progress_message = 'Research Agent matrix initialized', updated_at = current_timestamp
                where id = ? and task_status = 'QUEUED'
                """, taskId);
        insertTrace(researchRunId, "RUN_CREATED", "Research run 已入队", Map.of(
                "question", request.question().trim(),
                "profile_key", profileKey,
                "research_intent", objectMapper.convertValue(researchIntent, new TypeReference<Map<String, Object>>() {
                }),
                "retrieval_mode", acquisitionPolicy.mode().name(),
                "source_scope_count", sourceScopeIds.size()
        ));
        memoryCompilerService.logPackUsage(
                workspaceId,
                "RESEARCH",
                "RESEARCH_RUN",
                researchRunId,
                controlPack
        );
        return new ResearchRunResponse(researchRunId, taskId, "RUNNING");
    }

    public List<ResearchRunSummaryResponse> listRuns(String workspaceId) {
        requireWorkspace(workspaceId);
        return jdbcTemplate.query("""
                select id, workspace_id, task_id, question, profile_key, source_scope_json,
                       resumed_from_research_run_id, resumed_from_checkpoint_no, status,
                       final_report_title, final_report_markdown, report_source_id, created_at, updated_at
                from research_run
                where workspace_id = ?
                order by updated_at desc, created_at desc, id desc
                """, (rs, rowNum) -> {
            String researchRunId = rs.getString("id");
            List<ResearchTraceResponse> traces = loadTraces(researchRunId);
            ResearchReportStructureResponse reportStructure = buildReportStructure(researchRunId, traces);
            ResearchClosedLoopStateResponse closedLoopState = buildClosedLoopState(researchRunId, traces);
            ResearchArtifactCandidateResponse researchArtifactCandidate = buildResearchArtifactCandidate(traces);
            ResearchResumeCheckpointSummaryResponse resumeCheckpoint = loadResumeCheckpointSummary(
                    workspaceId,
                    blankToNull(rs.getString("resumed_from_research_run_id")),
                    (Integer) rs.getObject("resumed_from_checkpoint_no")
            );
            ResearchCounterfactualSummaryResponse counterfactualSummary = buildCounterfactualSummary(reportStructure, closedLoopState);
            ResearchIntentAlignmentResponse researchIntentAlignment = buildResearchIntentAlignment(reportStructure, closedLoopState);
            Map<String, Object> intentCompletionContract = buildIntentCompletionContract(reportStructure, closedLoopState);
            ResearchRecoveryTargetsResponse recoveryTargets = buildRecoveryTargets(reportStructure, closedLoopState);
            int sourceScopeCount = readSourceScopeIds(rs.getString("source_scope_json")).size();
            SaveResearchReportSourceResponse savedReportSource = blankToNull(rs.getString("report_source_id")) == null
                    ? null
                    : loadSavedReportSource(workspaceId, rs.getString("report_source_id"));
            ResearchReportFileResponse reportFile = buildReportFileResponse(
                    workspaceId,
                    researchRunId,
                    rs.getString("final_report_markdown")
            );
            ResearchRunArtifactResponse researchArtifact = buildResearchArtifact(
                    researchRunId,
                    rs.getString("final_report_title"),
                    researchArtifactCandidate,
                    reportFile,
                    savedReportSource
            );
            Map<String, Object> verifierGatedSummary = buildVerifierGatedSummary(
                    closedLoopState.stateLedger().rows(),
                    recoveryTargetsMap(recoveryTargets)
            );
            String localVerifierReason = extractDecisionReason(closedLoopState.localVerifier());
            String globalVerifierReason = extractDecisionReason(closedLoopState.globalVerifier());
            String finalLoopReason = blankIfNull(stringValue(closedLoopState.loopDecisionPayload().get("reason")));
            String recoveryMode = counterfactualSummary == null
                    ? blankIfNull(reportStructure == null ? "" : reportStructure.recoveryMode())
                    : blankIfNull(counterfactualSummary.recoveryMode());
            ResearchVerifierSummaryResponse verifierSummary = new ResearchVerifierSummaryResponse(
                    blankIfNull(closedLoopState.localVerifierStatus()),
                    localVerifierReason,
                    blankIfNull(closedLoopState.globalVerifierDecision()),
                    globalVerifierReason,
                    blankIfNull(closedLoopState.finalLoopDecision()),
                    finalLoopReason,
                    researchIntentAlignment == null ? "" : blankIfNull(researchIntentAlignment.status()),
                    researchIntentAlignment == null ? "" : blankIfNull(researchIntentAlignment.reasonCode()),
                    recoveryTargets,
                    readVerifierGatedSummaryResponse(verifierGatedSummary)
            );
            return new ResearchRunSummaryResponse(
                    researchRunId,
                    rs.getString("task_id"),
                    rs.getString("question"),
                    rs.getString("profile_key"),
                    rs.getString("status"),
                    blankIfNull(rs.getString("final_report_title")),
                    blankIfNull(rs.getString("resumed_from_research_run_id")),
                    (Integer) rs.getObject("resumed_from_checkpoint_no"),
                    sourceScopeCount,
                    closedLoopState.checkpoints().size(),
                    blankIfNull(closedLoopState.activeBranchId()),
                    blankIfNull(closedLoopState.localVerifierStatus()),
                    localVerifierReason,
                    closedLoopState.ledgerRowCount(),
                    closedLoopState.stateLedger().verifiedRowCount(),
                    closedLoopState.stateLedger().conflictedRowCount(),
                    intValue(verifierGatedSummary.get("blocked_row_count")),
                    intValue(verifierGatedSummary.get("guardrailed_row_count")),
                    intValue(verifierGatedSummary.get("recovery_targeted_blocked_row_count")),
                    intValue(verifierGatedSummary.get("uncovered_blocked_row_count")),
                    intValue(verifierGatedSummary.get("requirement_partial_blocked_row_count")),
                    blankIfNull(closedLoopState.globalVerifierDecision()),
                    globalVerifierReason,
                    blankIfNull(closedLoopState.finalLoopDecision()),
                    finalLoopReason,
                    recoveryMode,
                    researchIntentAlignment == null ? "" : blankIfNull(researchIntentAlignment.status()),
                    researchIntentAlignment == null ? "" : blankIfNull(researchIntentAlignment.reasonCode()),
                    researchIntentAlignment == null ? 0 : researchIntentAlignment.satisfiedConstraintCount(),
                    researchIntentAlignment == null ? 0 : researchIntentAlignment.totalConstraintCount(),
                    intValue(intentCompletionContract.get("satisfied_requirement_count")),
                    intValue(intentCompletionContract.get("total_requirement_count")),
                    intValue(intentCompletionContract.get("pending_requirement_count")),
                    extractStringList(intentCompletionContract.get("missing_requirement_labels")),
                    resumeCheckpoint,
                    researchArtifactCandidate,
                    reportFile,
                    researchArtifact,
                    buildResearchProcessSummary(
                            reportStructure,
                            closedLoopState,
                            verifierSummary,
                            sourceScopeCount,
                            researchArtifactCandidate
                    ),
                    closedLoopState.harnessControlState(),
                    closedLoopState.auditSummaries(),
                    closedLoopState.toolboxSummary(),
                    savedReportSource,
                    loadRunWaitContext(rs.getString("task_id"), rs.getString("status")),
                    recoveryTargets,
                    counterfactualSummary,
                    toInstant(rs.getTimestamp("created_at")),
                    toInstant(rs.getTimestamp("updated_at"))
            );
        }, workspaceId);
    }

    @Transactional
    public ResearchRunResponse resumeFromCheckpoint(String workspaceId, String researchRunId, int checkpointNo) {
        requireWorkspace(workspaceId);
        ResumeSourceRunRow sourceRun = loadResumeSourceRun(workspaceId, researchRunId);
        loadCheckpointRow(workspaceId, researchRunId, checkpointNo);

        String resumedResearchRunId = Ids.newId();
        String taskId = taskService.createTask(
                workspaceId,
                "RESEARCH_RUN",
                "RESEARCH_RUN",
                resumedResearchRunId,
                "QUEUED",
                "Deep Research 恢复任务已创建"
        );
        jdbcTemplate.update("""
                insert into research_run(
                    id, workspace_id, task_id, question, profile_key,
                    research_intent_json, source_scope_json, control_pack_json, retrieval_mode, status,
                    resumed_from_research_run_id, resumed_from_checkpoint_no
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?)
                """,
                resumedResearchRunId,
                workspaceId,
                taskId,
                sourceRun.question(),
                sourceRun.profileKey(),
                sourceRun.researchIntentJson(),
                sourceRun.sourceScopeJson(),
                sourceRun.controlPackJson(),
                sourceRun.retrievalMode().name(),
                researchRunId,
                checkpointNo
        );
        jdbcTemplate.update("""
                insert into task_outbox(id, task_id, topic, message_key, payload_json, status)
                values (?, ?, 'noteweave.research.run', ?, ?, 'READY')
                """,
                Ids.newId(),
                taskId,
                resumedResearchRunId,
                Json.write(objectMapper, Map.of(
                        "task_id", taskId,
                        "task_type", "RESEARCH_RUN",
                        "workspace_id", workspaceId,
                        "target_type", "RESEARCH_RUN",
                        "target_id", resumedResearchRunId,
                        "payload_version", "v1",
                        "trace_id", resumedResearchRunId,
                        "created_at", System.currentTimeMillis()
                )));
        insertTrace(resumedResearchRunId, "RUN_CREATED", "Research resume run 已入队", Map.of(
                "question", sourceRun.question(),
                "profile_key", sourceRun.profileKey(),
                "research_intent", objectMapper.convertValue(
                        readResearchIntent(sourceRun.researchIntentJson()),
                        new TypeReference<Map<String, Object>>() {
                        }
                ),
                "source_scope_count", readSourceScopeIds(sourceRun.sourceScopeJson()).size(),
                "retrieval_mode", sourceRun.retrievalMode().name(),
                "resumed_from_research_run_id", researchRunId,
                "resumed_from_checkpoint_no", checkpointNo
        ));
        insertTrace(resumedResearchRunId, "RUN_RESUMED_FROM_CHECKPOINT", "Research run 从 checkpoint 恢复创建", Map.of(
                "resumed_from_research_run_id", researchRunId,
                "resumed_from_checkpoint_no", checkpointNo
        ));
        memoryCompilerService.logPackUsage(
                workspaceId,
                "RESEARCH",
                "RESEARCH_RUN",
                resumedResearchRunId,
                readControlPack(sourceRun.controlPackJson())
        );
        return new ResearchRunResponse(resumedResearchRunId, taskId, "QUEUED");
    }

    public ResearchWorkerInputResponse getWorkerInput(String taskId) {
        RunRow row = findByTaskId(taskId);
        CallbackLease callbackLease = jdbcTemplate.queryForObject("""
                select attempt_no, fencing_token
                from research_run
                where id = ?
                """, (rs, rowNum) -> new CallbackLease(
                rs.getInt("attempt_no"), rs.getLong("fencing_token")), row.researchRunId());
        return new ResearchWorkerInputResponse(
                row.taskId(),
                row.workspaceId(),
                row.researchRunId(),
                callbackLease.attemptNo(),
                callbackLease.fencingToken(),
                loadSourceScopeSnapshot(row.workspaceId(), row.sourceScopeJson()),
                row.retrievalMode().name(),
                readControlPack(row.controlPackJson()),
                new ResearchWorkerInputPayload(
                        row.question(),
                        row.profileKey(),
                        readResearchIntent(row.researchIntentJson()),
                        loadResumeCheckpointPayload(row)
                )
        );
    }

    public ResearchRunDetailResponse getRunDetail(String workspaceId, String researchRunId) {
        requireWorkspace(workspaceId);
        DetailRow row = jdbcTemplate.query("""
                select id, workspace_id, task_id, question, profile_key, research_intent_json,
                       source_scope_json, control_pack_json, resumed_from_research_run_id,
                       resumed_from_checkpoint_no, status, final_report_title,
                       final_report_markdown, trace_summary, report_source_id, created_at, updated_at
                from research_run
                where workspace_id = ? and id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("RESEARCH_RUN_NOT_FOUND", "研究任务不存在");
            }
            return new DetailRow(
                    rs.getString("id"),
                    rs.getString("workspace_id"),
                    rs.getString("task_id"),
                    rs.getString("question"),
                    rs.getString("profile_key"),
                    rs.getString("research_intent_json"),
                    rs.getString("source_scope_json"),
                    rs.getString("control_pack_json"),
                    rs.getString("resumed_from_research_run_id"),
                    (Integer) rs.getObject("resumed_from_checkpoint_no"),
                    rs.getString("status"),
                    rs.getString("final_report_title"),
                    rs.getString("final_report_markdown"),
                    rs.getString("trace_summary"),
                    rs.getString("report_source_id"),
                    toInstant(rs.getTimestamp("created_at")),
                    toInstant(rs.getTimestamp("updated_at"))
            );
        }, workspaceId, researchRunId);

        List<ResearchTraceResponse> traces = loadTraces(row.researchRunId());
        enrichTracePayloads(traces);
        ResearchReportStructureResponse reportStructure = buildReportStructure(row.researchRunId(), traces);
        ResearchClosedLoopStateResponse closedLoopState = buildClosedLoopState(row.researchRunId(), traces);
        ResearchArtifactCandidateResponse researchArtifactCandidate = buildResearchArtifactCandidate(traces);
        ResearchResumeContextSummaryResponse resumeContextSummary = buildResumeContextSummary(traces);
        ResearchResumeCheckpointSummaryResponse resumeCheckpoint = loadResumeCheckpointSummary(
                row.workspaceId(),
                blankIfNull(row.resumedFromResearchRunId()).isBlank() ? null : row.resumedFromResearchRunId(),
                row.resumedFromCheckpointNo()
        );
        ResearchVerifierSummaryResponse verifierSummary = buildVerifierSummary(reportStructure, closedLoopState);
        List<WorkerSourceScopeItemResponse> sourceScope = loadSourceScopeSnapshot(row.workspaceId(), row.sourceScopeJson());
        SaveResearchReportSourceResponse savedReportSource = blankToNull(row.reportSourceId()) != null
                ? loadSavedReportSource(row.workspaceId(), row.reportSourceId())
                : null;
        ResearchReportFileResponse reportFile = buildReportFileResponse(
                row.workspaceId(),
                row.researchRunId(),
                row.finalReportMarkdown()
        );
        ResearchRunArtifactResponse researchArtifact = buildResearchArtifact(
                row.researchRunId(),
                row.finalReportTitle(),
                researchArtifactCandidate,
                reportFile,
                savedReportSource
        );
        return new ResearchRunDetailResponse(
                row.researchRunId(),
                row.workspaceId(),
                row.taskId(),
                row.question(),
                row.profileKey(),
                readResearchIntent(row.researchIntentJson()),
                blankIfNull(row.resumedFromResearchRunId()),
                row.resumedFromCheckpointNo(),
                row.status(),
                blankIfNull(row.finalReportTitle()),
                blankIfNull(row.finalReportMarkdown()),
                reportStructure,
                buildCounterfactualSummary(reportStructure, closedLoopState),
                researchArtifactCandidate,
                reportFile,
                researchArtifact,
                buildResearchProcessSummary(
                        reportStructure,
                        closedLoopState,
                        verifierSummary,
                        sourceScope.size(),
                        researchArtifactCandidate
                ),
                closedLoopState.harnessControlState(),
                closedLoopState.auditSummaries(),
                closedLoopState.toolboxSummary(),
                resumeContextSummary,
                resumeCheckpoint,
                verifierSummary,
                blankIfNull(row.traceSummary()),
                sourceScope,
                readControlPack(row.controlPackJson()),
                savedReportSource,
                loadRunWaitContext(row.taskId(), row.status()),
                closedLoopState,
                researchAgentProjectionService.project(row.researchRunId()),
                traces,
                row.createdAt(),
                row.updatedAt()
        );
    }

    public List<ResearchCheckpointSummaryResponse> listCheckpoints(String workspaceId, String researchRunId) {
        requireWorkspace(workspaceId);
        requireResearchRun(workspaceId, researchRunId);
        return loadPersistedCheckpoints(researchRunId).stream()
                .map(this::toCheckpointSummaryResponse)
                .toList();
    }

    public ResearchCheckpointResponse getCheckpoint(String workspaceId, String researchRunId, int checkpointNo) {
        requireWorkspace(workspaceId);
        CheckpointRow row = loadCheckpointRow(workspaceId, researchRunId, checkpointNo);
        RunArtifactView artifactView = loadRunArtifactView(workspaceId, researchRunId);
        Map<String, Object> payload = readPayloadMap(new String(storage.read("noteweave-derived", row.objectKey()), StandardCharsets.UTF_8));
        Map<String, Object> summary = readPayloadMap(row.summaryJson());
        enrichResearchSourceProvenance(payload, loadSourceOrigins(collectSourceIds(payload)));
        enrichPayloadLoopRoundsWithSourceSamples(payload);
        enrichResearchSourceProvenance(summary, loadSourceOrigins(collectSourceIds(summary)));
        ResearchProcessSummaryResponse researchProcessSummary = buildCheckpointProcessSummary(payload, summary);
        return new ResearchCheckpointResponse(
                row.checkpointNo(),
                row.snapshotType(),
                row.objectKey(),
                row.payloadSha256(),
                row.contentSize(),
                blankIfNull(row.activeBranchKey()),
                blankIfNull(row.finalLoopDecision()),
                readCheckpointSnapshotSummaryResponse(summary),
                firstNonNullCounterfactualSummary(summary, payload),
                artifactView.reportFile(),
                artifactView.researchArtifact(),
                artifactView.savedReportSource(),
                researchProcessSummary,
                castMapOrEmpty(payload.get("harness_control_state")),
                castMapOrEmpty(payload.get("audit_summaries")),
                castMapOrEmpty(payload.get("toolbox_summary")),
                payload,
                row.createdAt()
        );
    }

    private ResearchCheckpointSummaryResponse toCheckpointSummaryResponse(Map<String, Object> checkpoint) {
        return new ResearchCheckpointSummaryResponse(
                intValue(checkpoint.get("checkpoint_no")),
                blankIfNull(stringValue(checkpoint.get("snapshot_type"))),
                blankIfNull(stringValue(checkpoint.get("active_branch_id"))),
                blankIfNull(stringValue(checkpoint.get("final_loop_decision"))),
                blankIfNull(stringValue(checkpoint.get("local_verifier_status"))),
                blankIfNull(stringValue(checkpoint.get("global_verifier_decision"))),
                intValue(checkpoint.get("verified_row_count")),
                intValue(checkpoint.get("conflicted_row_count")),
                readCheckpointSnapshotSummaryResponse(castMapOrEmpty(checkpoint.get("summary"))),
                readCounterfactualSummary(castMapOrEmpty(checkpoint.get("counterfactual_summary"))),
                readRecoveryTargetsResponse(castMapOrEmpty(checkpoint.get("recovery_targets"))),
                (Instant) checkpoint.get("created_at")
        );
    }

    private ResearchResumeCheckpointSummaryResponse loadResumeCheckpointSummary(
            String workspaceId,
            String sourceResearchRunId,
            Integer checkpointNo
    ) {
        if (sourceResearchRunId == null || sourceResearchRunId.isBlank() || checkpointNo == null) {
            return null;
        }
        CheckpointRow checkpointRow = loadCheckpointRow(workspaceId, sourceResearchRunId, checkpointNo);
        Map<String, Object> summary = readPayloadMap(checkpointRow.summaryJson());
        enrichResearchSourceProvenance(summary, loadSourceOrigins(collectSourceIds(summary)));
        ResearchCheckpointSnapshotSummaryResponse summaryResponse = readCheckpointSnapshotSummaryResponse(summary);
        ResearchCounterfactualSummaryResponse counterfactualSummary = readCounterfactualSummary(
                castMapOrEmpty(summary.get("counterfactual_summary"))
        );
        ResearchRecoveryTargetsResponse recoveryTargets = readRecoveryTargetsResponse(
                castMapOrEmpty(summary.get("recovery_targets"))
        );
        RunArtifactView artifactView = loadRunArtifactView(workspaceId, sourceResearchRunId);
        return new ResearchResumeCheckpointSummaryResponse(
                sourceResearchRunId,
                checkpointNo,
                checkpointRow.snapshotType(),
                blankIfNull(checkpointRow.activeBranchKey()),
                blankIfNull(checkpointRow.finalLoopDecision()),
                artifactView.reportFile(),
                artifactView.researchArtifact(),
                artifactView.savedReportSource(),
                summaryResponse,
                counterfactualSummary,
                recoveryTargets,
                checkpointRow.createdAt()
        );
    }

    private ResearchCheckpointSnapshotSummaryResponse readCheckpointSnapshotSummaryResponse(Map<String, Object> summary) {
        Map<String, Object> safeSummary = castMapOrEmpty(summary);
        return new ResearchCheckpointSnapshotSummaryResponse(
                intValue(safeSummary.get("checkpoint_no")),
                blankIfNull(stringValue(safeSummary.get("snapshot_type"))),
                nonEmptyMapOrNull(safeSummary.get("loop_decision")),
                nonEmptyMapOrNull(safeSummary.get("local_verifier")),
                nonEmptyMapOrNull(safeSummary.get("global_verifier")),
                readCheckpointStateLedgerSummaryResponse(castMapOrEmpty(safeSummary.get("state_ledger"))),
                nonEmptyMapOrNull(safeSummary.get("research_intent_alignment")),
                blankIfNull(stringValue(safeSummary.get("research_intent_alignment_status"))),
                blankIfNull(stringValue(safeSummary.get("research_intent_alignment_reason"))),
                intValue(safeSummary.get("intent_constraint_count")),
                intValue(safeSummary.get("intent_satisfied_constraint_count")),
                nonEmptyMapOrNull(safeSummary.get("intent_completion_contract")),
                intValue(safeSummary.get("intent_requirement_count")),
                intValue(safeSummary.get("intent_satisfied_requirement_count")),
                intValue(safeSummary.get("intent_pending_requirement_count")),
                extractStringList(safeSummary.get("missing_intent_requirements")),
                readCounterfactualSummary(castMapOrEmpty(safeSummary.get("counterfactual_summary"))),
                readRecoveryTargetsResponse(castMapOrEmpty(safeSummary.get("recovery_targets"))),
                readVerifierGatedSummaryResponse(nonEmptyMapOrNull(safeSummary.get("verifier_gated_summary")))
        );
    }

    private ResearchCheckpointStateLedgerSummaryResponse readCheckpointStateLedgerSummaryResponse(Map<String, Object> stateLedger) {
        Map<String, Object> safeLedger = castMapOrEmpty(stateLedger);
        return new ResearchCheckpointStateLedgerSummaryResponse(
                blankIfNull(stringValue(safeLedger.get("active_branch_id"))),
                intValue(safeLedger.get("row_count")),
                intValue(safeLedger.get("column_count")),
                intValue(safeLedger.get("branch_count")),
                intValue(safeLedger.get("cell_count")),
                intValue(safeLedger.get("verified_row_count")),
                intValue(safeLedger.get("conflicted_row_count")),
                intValue(safeLedger.get("requirement_ready_row_count")),
                intValue(safeLedger.get("requirement_partial_row_count")),
                intValue(safeLedger.get("read_window_count")),
                intValue(safeLedger.get("evidence_card_count")),
                extractListOfMaps(safeLedger.get("verified_row_samples")),
                extractListOfMaps(safeLedger.get("conflicted_row_samples")),
                extractListOfMaps(safeLedger.get("evidence_card_samples")),
                extractListOfMaps(safeLedger.get("read_window_samples")),
                intValue(safeLedger.get("blocked_row_count")),
                intValue(safeLedger.get("guardrailed_row_count")),
                intValue(safeLedger.get("recovery_targeted_blocked_row_count")),
                intValue(safeLedger.get("uncovered_blocked_row_count")),
                intValue(safeLedger.get("requirement_partial_blocked_row_count")),
                extractListOfMaps(safeLedger.get("blocked_row_samples")),
                extractListOfMaps(safeLedger.get("guardrailed_row_samples")),
                extractListOfMaps(safeLedger.get("need_more_evidence_row_samples")),
                nonEmptyMapOrNull(safeLedger.get("intent_completion_contract")),
                intValue(safeLedger.get("intent_requirement_count")),
                intValue(safeLedger.get("intent_satisfied_requirement_count")),
                intValue(safeLedger.get("intent_pending_requirement_count")),
                extractStringList(safeLedger.get("missing_intent_requirements"))
        );
    }

    private ResearchStateLedgerResponse readStateLedgerResponse(Map<String, Object> stateLedger) {
        Map<String, Object> safeLedger = castMapOrEmpty(stateLedger);
        return new ResearchStateLedgerResponse(
                blankIfNull(stringValue(safeLedger.get("active_branch_id"))),
                extractListOfMaps(safeLedger.get("columns")),
                extractListOfMaps(safeLedger.get("branch_sessions")),
                extractListOfMaps(safeLedger.get("branches")),
                extractListOfMaps(safeLedger.get("rows")),
                extractListOfMaps(safeLedger.get("cells")),
                extractListOfMaps(safeLedger.get("verifier_decisions")),
                extractListOfMaps(safeLedger.get("required_finding_contract")),
                extractListOfMaps(safeLedger.get("required_finding_progress")),
                intValue(safeLedger.get("verified_row_count")),
                intValue(safeLedger.get("conflicted_row_count")),
                intValue(safeLedger.get("requirement_ready_row_count")),
                intValue(safeLedger.get("requirement_partial_row_count")),
                readRecoveryTargetsResponse(castMapOrEmpty(safeLedger.get("recovery_targets"))),
                nonEmptyMapOrNull(safeLedger.get("intent_completion_contract"))
        );
    }

    private ResearchArtifactCandidateResponse readResearchArtifactCandidateResponse(Map<String, Object> artifact) {
        if (artifact == null || artifact.isEmpty()) {
            return null;
        }
        Map<String, Object> safeArtifact = castMapOrEmpty(artifact);
        return new ResearchArtifactCandidateResponse(
                blankIfNull(stringValue(safeArtifact.get("artifact_type"))),
                blankIfNull(stringValue(safeArtifact.get("artifact_version"))),
                blankIfNull(stringValue(safeArtifact.get("title"))),
                blankIfNull(stringValue(safeArtifact.get("question"))),
                blankIfNull(stringValue(safeArtifact.get("generated_by"))),
                blankIfNull(stringValue(safeArtifact.get("generated_ref_type"))),
                blankIfNull(stringValue(safeArtifact.get("generated_ref_id"))),
                blankIfNull(stringValue(safeArtifact.get("answer_status"))),
                blankIfNull(stringValue(safeArtifact.get("confidence_label"))),
                blankIfNull(stringValue(safeArtifact.get("coverage_label"))),
                blankIfNull(stringValue(safeArtifact.get("source_basis"))),
                blankIfNull(stringValue(safeArtifact.get("answer_text"))),
                blankIfNull(stringValue(safeArtifact.get("content_markdown"))),
                nonEmptyMapOrNull(safeArtifact.get("report_structure")),
                nonEmptyMapOrNull(safeArtifact.get("source_foundation")),
                nonEmptyMapOrNull(safeArtifact.get("research_intent")),
                nonEmptyMapOrNull(safeArtifact.get("closed_loop_state")),
                readResumeContextSummaryResponse(nonEmptyMapOrNull(safeArtifact.get("resume_context_summary"))),
                intValue(safeArtifact.get("citation_count")),
                extractListOfMaps(safeArtifact.get("citations"))
        );
    }

    private ResearchResumeContextSummaryResponse readResumeContextSummaryResponse(Map<String, Object> summary) {
        if (summary == null || summary.isEmpty()) {
            return null;
        }
        Map<String, Object> safeSummary = castMapOrEmpty(summary);
        return new ResearchResumeContextSummaryResponse(
                blankIfNull(stringValue(safeSummary.get("source_research_run_id"))),
                intValue(safeSummary.get("checkpoint_no")),
                blankIfNull(stringValue(safeSummary.get("snapshot_type"))),
                blankIfNull(stringValue(safeSummary.get("active_branch_id"))),
                blankIfNull(stringValue(safeSummary.get("final_loop_decision"))),
                intValue(safeSummary.get("restored_search_hit_count")),
                intValue(safeSummary.get("restored_read_window_count")),
                intValue(safeSummary.get("restored_evidence_card_count")),
                intValue(safeSummary.get("restored_loop_round_count")),
                intValue(safeSummary.get("restored_tool_trace_count"))
        );
    }

    private CheckpointRow loadCheckpointRow(String workspaceId, String researchRunId, int checkpointNo) {
        return jdbcTemplate.query("""
                select rec.checkpoint_no, rec.snapshot_type, rec.object_key, rec.payload_sha256,
                       rec.content_size, rec.active_branch_key, rec.final_loop_decision,
                       rec.summary_json, rec.created_at
                from research_execution_checkpoint rec
                join research_run rr on rr.id = rec.research_run_id
                where rr.workspace_id = ? and rr.id = ? and rec.checkpoint_no = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("RESEARCH_CHECKPOINT_NOT_FOUND", "研究检查点不存在");
            }
            return new CheckpointRow(
                    rs.getInt("checkpoint_no"),
                    rs.getString("snapshot_type"),
                    rs.getString("object_key"),
                    rs.getString("payload_sha256"),
                    rs.getLong("content_size"),
                    rs.getString("active_branch_key"),
                    rs.getString("final_loop_decision"),
                    rs.getString("summary_json"),
                    toInstant(rs.getTimestamp("created_at"))
            );
        }, workspaceId, researchRunId, checkpointNo);
    }

    @Transactional
    public SaveResearchReportSourceResponse saveReportAsSource(String workspaceId, String researchRunId) {
        requireWorkspace(workspaceId);
        SaveReportRow row = loadSaveReportRow(workspaceId, researchRunId);
        if (row.reportSourceId() != null && !row.reportSourceId().isBlank()) {
            return loadSavedReportSource(workspaceId, row.reportSourceId());
        }
        if (!"COMPLETED".equals(row.status()) || row.finalReportMarkdown() == null || row.finalReportMarkdown().isBlank()) {
            throw new BusinessException("RESEARCH_REPORT_NOT_READY", "研究报告尚未完成，不能写入资料池");
        }

        byte[] reportBytes = row.finalReportMarkdown().getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256(reportBytes);
        FileObjectRef fileObject = getOrCreateGeneratedFileObject(
                workspaceId,
                researchRunId,
                row.finalReportTitle(),
                sha256,
                reportBytes
        );
        String sourceId = Ids.newId();
        String snapshotId = Ids.newId();
        ResearchReportFileResponse reportFile = writeResearchReportFile(workspaceId, researchRunId, reportBytes);
        jdbcTemplate.update("""
                insert into source(
                    id, workspace_id, file_object_id, title, source_type, status,
                    parse_status, index_status, generated_by, generated_ref_id, created_by, updated_by
                )
                values (?, ?, ?, ?, 'GENERATED_RESEARCH_REPORT', 'PROCESSING', 'PENDING', 'PENDING',
                        'research_agent', ?, 'SYSTEM:RESEARCH', 'SYSTEM:RESEARCH')
                """,
                sourceId,
                workspaceId,
                fileObject.id(),
                reportTitle(row),
                researchRunId
        );
        sourceCatalogVersionService.bump(workspaceId);
        jdbcTemplate.update("""
                insert into source_snapshot(id, source_id, file_object_id, version_no, object_key, sha256, parse_status, index_status)
                values (?, ?, ?, 1, ?, ?, 'PENDING', 'PENDING')
                """, snapshotId, sourceId, fileObject.id(), reportFile.objectKey(), sha256);

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

    @Transactional
    public void markRunning(String taskId, String phase, String message, Map<String, Object> metrics) {
        RunRow row = findByTaskId(taskId);
        jdbcTemplate.update("""
                update research_run
                set status = 'RUNNING', updated_at = current_timestamp
                where task_id = ?
                """, taskId);
        insertTrace(row.researchRunId(), "PROGRESS", message, Map.of(
                "phase", phase == null ? "" : phase,
                "metrics", metrics == null ? Map.of() : metrics
        ));
    }

    @Transactional
    public void markWaiting(String taskId, String phase, String message, Map<String, Object> metrics) {
        RunRow row = findByTaskId(taskId);
        String waitStatus = blankToNull(phase) == null ? "WAITING_FOR_PROVIDER" : phase.trim();
        jdbcTemplate.update("""
                update research_run
                set status = ?, updated_at = current_timestamp
                where task_id = ?
                """, waitStatus, taskId);
        insertTrace(row.researchRunId(), "WAITING", message, Map.of(
                "phase", phase == null ? "" : phase,
                "metrics", metrics == null ? Map.of() : metrics
        ));
    }

    @Transactional
    public void persistRuntimeCheckpoint(String taskId, Map<String, Object> progressPayload) {
        RunRow row = findByTaskId(taskId);
        Map<String, Object> candidate = castMapOrEmpty(progressPayload.get("research_checkpoint_candidate"));
        if (candidate.isEmpty()) {
            return;
        }
        persistExecutionCheckpoint(
                row.workspaceId(),
                row.researchRunId(),
                candidate,
                candidate,
                castMapOrEmpty(candidate.get("state_ledger")),
                extractListOfMaps(candidate.get("evidence_cards")),
                extractListOfMaps(candidate.get("read_windows"))
        );
    }

    @Transactional
    public CompletionOutcome completeFromWorker(String taskId, com.noteweave.worker.WorkerCompleteRequest request) {
        RunRow row = findByTaskId(taskId);
        if (isIncrementalExecutionMode(row.researchRunId())) {
            throw new BusinessException("RESEARCH_AGENT_ATOMIC_COMPLETION_REQUIRED",
                    "Incremental research runs can only be finalized from the canonical agent ledger");
        }
        Map<String, Object> resultPayload = request.resultPayload() == null ? Map.of() : request.resultPayload();
        String reportMarkdown = extractReport(resultPayload);
        byte[] reportBytes = reportMarkdown.getBytes(StandardCharsets.UTF_8);
        if (!reportMarkdown.isBlank()) {
            writeResearchReportFile(row.workspaceId(), row.researchRunId(), reportBytes);
        }
        jdbcTemplate.update("""
                update research_run
                set status = 'COMPLETED',
                    final_report_title = ?,
                    final_report_markdown = ?,
                    trace_summary = ?,
                    updated_at = current_timestamp
                where id = ?
                """,
                request.resultTitle(),
                reportMarkdown,
                blankToNull(request.traceSummary()),
                row.researchRunId()
        );
        persistFinalEvidenceManifest(row, reportMarkdown, request.citations());
        researchCollectionService.materialize(row.researchRunId());
        projectCompletedReportCard(row.researchRunId(), row.workspaceId(), request.resultTitle());
        persistClosedLoopState(row.workspaceId(), row.researchRunId(), resultPayload);
        persistClosedLoopTraces(row.researchRunId(), request.resultTitle(), resultPayload);
        insertTrace(row.researchRunId(), "FINAL_REPORT", request.resultTitle(), Map.of(
                "result_type", request.resultType(),
                "result_payload", resultPayload,
                "trace_summary", request.traceSummary() == null ? "" : request.traceSummary(),
                "citations", request.citations() == null ? List.of() : request.citations()
        ));
        return new CompletionOutcome("RESEARCH_REPORTED", "研究报告已生成：" + request.resultTitle(), row.researchRunId());
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
                throw new BusinessException("RESEARCH_EVIDENCE_MANIFEST_NOT_FOUND", "Research evidence manifest does not exist");
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

    private void persistFinalEvidenceManifest(
            RunRow row,
            String reportMarkdown,
            List<Map<String, Object>> citations
    ) {
        Integer existing = jdbcTemplate.queryForObject("""
                select count(*) from research_evidence_manifest where research_run_id = ?
                """, Integer.class, row.researchRunId());
        if (existing != null && existing > 0) {
            return;
        }
        String manifestId = Ids.newId();
        jdbcTemplate.update("""
                insert into research_evidence_manifest(
                    id, workspace_id, research_run_id, report_content_hash
                ) values (?, ?, ?, ?)
                """, manifestId, row.workspaceId(), row.researchRunId(),
                sha256((reportMarkdown == null ? "" : reportMarkdown).getBytes(StandardCharsets.UTF_8)));
        int rank = 0;
        for (Map<String, Object> citation : citations == null ? List.<Map<String, Object>>of() : citations) {
            if (citation == null) {
                continue;
            }
            String excerpt = citationText(citation, "quote_text", "excerpt", "claim_text");
            if (excerpt.isBlank()) {
                continue;
            }
            rank++;
            String evidenceId = citationText(citation, "citation_id", "evidence_id");
            if (evidenceId.isBlank()) {
                evidenceId = "citation-" + rank;
            }
            jdbcTemplate.update("""
                    insert into research_evidence_manifest_item(
                        id, manifest_id, rank_no, evidence_id, source_id, source_snapshot_id,
                        passage_id, title, excerpt, content_hash, location_info
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Ids.newId(), manifestId, rank, evidenceId,
                    blankToNull(citationText(citation, "source_id")),
                    blankToNull(citationText(citation, "source_snapshot_id")),
                    blankToNull(citationText(citation, "passage_id", "source_chunk_id")),
                    blankToNull(citationText(citation, "title", "source_title")), excerpt,
                    sha256(excerpt.getBytes(StandardCharsets.UTF_8)),
                    blankToNull(citationText(citation, "location", "location_info")));
        }
    }

    private String citationText(Map<String, Object> citation, String... fieldNames) {
        for (String fieldName : fieldNames) {
            String value = stringValue(citation.get(fieldName));
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private void projectCompletedReportCard(String researchRunId, String workspaceId, String reportTitle) {
        ResearchConversationProjection target = jdbcTemplate.query("""
                select conversation_id, answer_message_id
                from research_run where workspace_id = ? and id = ?
                """, rs -> rs.next()
                ? new ResearchConversationProjection(rs.getString(1), rs.getString(2))
                : null, workspaceId, researchRunId);
        if (target != null) {
            conversationResearchProjectionService.projectCompletedReport(
                    workspaceId, target.conversationId(), target.messageId(), researchRunId, reportTitle);
        }
    }

    @Transactional
    public void markFailed(String taskId, WorkerFailRequest request) {
        RunRow row = findByTaskId(taskId);
        jdbcTemplate.update("""
                update research_run
                set status = 'FAILED', updated_at = current_timestamp
                where task_id = ?
                """, taskId);
        insertTrace(row.researchRunId(), "FAILED", request.errorMessage(), Map.of(
                "phase", request.phase(),
                "error_code", request.errorCode(),
                "retryable", request.retryable()
        ));
    }

    @Transactional
    public void setAgentExecutionMode(String researchRunId, String mode) {
        String normalized = mode == null ? "" : mode.trim().toUpperCase();
        if (!List.of("SEQUENTIAL_V1", "SEQUENTIAL_V2", "LOCAL_PARALLEL", "INCREMENTAL_V1").contains(normalized)) {
            throw new BusinessException("RESEARCH_AGENT_EXECUTION_MODE_INVALID", "Unsupported research agent execution mode");
        }
        Map<String, String> current = jdbcTemplate.query("""
                select agent_execution_mode, status from research_run where id = ? for update
                """, rs -> rs.next() ? Map.of("mode", rs.getString(1), "status", rs.getString(2)) : null, researchRunId);
        if (current == null || List.of("COMPLETED", "FAILED", "CANCELLED").contains(current.get("status"))) {
            throw new BusinessException("RESEARCH_AGENT_EXECUTION_MODE_NOT_SWITCHABLE", "Research run is missing or terminal");
        }
        if ("INCREMENTAL_V1".equals(current.get("mode")) && !"INCREMENTAL_V1".equals(normalized)) {
            Integer activeTasks = jdbcTemplate.queryForObject("""
                    select count(*) from research_agent_task
                    where research_run_id = ? and status in ('PENDING', 'CLAIMED', 'RUNNING', 'RETRY_WAIT', 'EXPIRED')
                    """, Integer.class, researchRunId);
            if (activeTasks != null && activeTasks > 0) {
                throw new BusinessException("RESEARCH_AGENT_EXECUTION_MODE_ACTIVE_TASKS",
                        "Cancel or finish active research agent tasks before leaving INCREMENTAL_V1");
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
            if ((incrementalHistory != null && incrementalHistory > 0)
                    || (checkpoints != null && checkpoints > 0)
                    || (advancements != null && advancements > 0)) {
                throw new BusinessException("RESEARCH_AGENT_EXECUTION_MODE_INCREMENTAL_HISTORY",
                        "Incremental execution history cannot be handed back to a legacy mode");
            }
        }
        jdbcTemplate.update("update research_run set agent_execution_mode = ?, updated_at = current_timestamp where id = ?",
                normalized, researchRunId);
    }

    private boolean isIncrementalExecutionMode(String researchRunId) {
        String mode = jdbcTemplate.query("select agent_execution_mode from research_run where id = ?",
                rs -> rs.next() ? rs.getString(1) : "SEQUENTIAL_V1", researchRunId);
        return "INCREMENTAL_V1".equalsIgnoreCase(mode);
    }

    private SaveReportRow loadSaveReportRow(String workspaceId, String researchRunId) {
        return jdbcTemplate.query("""
                select id, workspace_id, status, final_report_title, final_report_markdown, report_source_id
                from research_run
                where workspace_id = ? and id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("RESEARCH_RUN_NOT_FOUND", "研究任务不存在");
            }
                return new SaveReportRow(
                        rs.getString("id"),
                        rs.getString("workspace_id"),
                        rs.getString("status"),
                        rs.getString("final_report_title"),
                        rs.getString("final_report_markdown"),
                        rs.getString("report_source_id")
                );
        }, workspaceId, researchRunId);
    }

    private RunArtifactView loadRunArtifactView(String workspaceId, String researchRunId) {
        SaveReportRow row = loadSaveReportRow(workspaceId, researchRunId);
        SaveResearchReportSourceResponse savedReportSource = blankToNull(row.reportSourceId()) == null
                ? null
                : loadSavedReportSource(workspaceId, row.reportSourceId());
        ResearchReportFileResponse reportFile = buildReportFileResponse(
                workspaceId,
                researchRunId,
                row.finalReportMarkdown()
        );
        ResearchRunArtifactResponse researchArtifact = buildResearchArtifact(
                researchRunId,
                row.finalReportTitle(),
                null,
                reportFile,
                savedReportSource
        );
        return new RunArtifactView(reportFile, researchArtifact, savedReportSource);
    }

    private ResumeSourceRunRow loadResumeSourceRun(String workspaceId, String researchRunId) {
        return jdbcTemplate.query("""
                select id, workspace_id, question, profile_key, research_intent_json,
                       source_scope_json, control_pack_json, retrieval_mode
                from research_run
                where workspace_id = ? and id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("RESEARCH_RUN_NOT_FOUND", "研究任务不存在");
            }
            return new ResumeSourceRunRow(
                    rs.getString("id"),
                    rs.getString("workspace_id"),
                    rs.getString("question"),
                    rs.getString("profile_key"),
                    rs.getString("research_intent_json"),
                    rs.getString("source_scope_json"),
                    rs.getString("control_pack_json"),
                    ResearchRetrievalMode.valueOf(rs.getString("retrieval_mode"))
            );
        }, workspaceId, researchRunId);
    }

    private SaveResearchReportSourceResponse loadSavedReportSource(String workspaceId, String sourceId) {
        return jdbcTemplate.query("""
                select id, title, source_type, status, parse_status, index_status,
                       coalesce(generated_by, '') as generated_by,
                       coalesce(generated_ref_id, '') as generated_ref_id
                from source
                where workspace_id = ? and id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("SOURCE_NOT_FOUND", "资料不存在");
            }
            return new SaveResearchReportSourceResponse(
                    rs.getString("id"),
                    rs.getString("title"),
                    rs.getString("source_type"),
                    rs.getString("status"),
                    rs.getString("parse_status"),
                    rs.getString("index_status"),
                    rs.getString("generated_by"),
                    rs.getString("generated_ref_id")
            );
        }, workspaceId, sourceId);
    }

    private void requireResearchRun(String workspaceId, String researchRunId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from research_run
                where workspace_id = ? and id = ?
                """, Integer.class, workspaceId, researchRunId);
        if (count == null || count <= 0) {
            throw new BusinessException("RESEARCH_RUN_NOT_FOUND", "研究任务不存在");
        }
    }

    private RunRow findByTaskId(String taskId) {
        return jdbcTemplate.query("""
                select id, workspace_id, task_id, question, profile_key, research_intent_json,
                       source_scope_json, control_pack_json, retrieval_mode,
                       resumed_from_research_run_id, resumed_from_checkpoint_no
                from research_run
                where task_id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("RESEARCH_RUN_NOT_FOUND", "研究任务不存在");
            }
            return new RunRow(
                    rs.getString("id"),
                    rs.getString("workspace_id"),
                    rs.getString("task_id"),
                    rs.getString("question"),
                    rs.getString("profile_key"),
                    rs.getString("research_intent_json"),
                    rs.getString("source_scope_json"),
                    rs.getString("control_pack_json"),
                    ResearchRetrievalMode.valueOf(rs.getString("retrieval_mode")),
                    rs.getString("resumed_from_research_run_id"),
                    (Integer) rs.getObject("resumed_from_checkpoint_no")
            );
        }, taskId);
    }

    private ResearchResumeCheckpointPayload loadResumeCheckpointPayload(RunRow row) {
        if (row.resumedFromResearchRunId() == null || row.resumedFromResearchRunId().isBlank()
                || row.resumedFromCheckpointNo() == null) {
            return null;
        }
        CheckpointRow checkpointRow = loadCheckpointRow(
                row.workspaceId(),
                row.resumedFromResearchRunId(),
                row.resumedFromCheckpointNo()
        );
        Map<String, Object> payload = readPayloadMap(new String(storage.read("noteweave-derived", checkpointRow.objectKey()), StandardCharsets.UTF_8));
        enrichResearchSourceProvenance(payload, loadSourceOrigins(collectSourceIds(payload)));
        enrichPayloadLoopRoundsWithSourceSamples(payload);
        return new ResearchResumeCheckpointPayload(
                row.resumedFromResearchRunId(),
                row.resumedFromCheckpointNo(),
                checkpointRow.snapshotType(),
                blankIfNull(checkpointRow.activeBranchKey()),
                blankIfNull(checkpointRow.finalLoopDecision()),
                payload
        );
    }

    private void insertTrace(String researchRunId, String type, String message, Map<String, Object> payload) {
        jdbcTemplate.update("""
                insert into research_trace(id, research_run_id, trace_type, trace_message, payload_json)
                values (?, ?, ?, ?, ?)
                """, Ids.newId(), researchRunId, type, message, Json.write(objectMapper, payload));
    }

    private List<ResearchTraceResponse> loadTraces(String researchRunId) {
        return jdbcTemplate.query("""
                select id, trace_type, trace_message, payload_json, created_at
                from research_trace
                where research_run_id = ?
                order by created_at asc, id asc
                """, (rs, rowNum) -> new ResearchTraceResponse(
                rs.getString("id"),
                rs.getString("trace_type"),
                rs.getString("trace_message"),
                readPayloadMap(rs.getString("payload_json")),
                toInstant(rs.getTimestamp("created_at"))
        ), researchRunId);
    }

    private void enrichTracePayloads(List<ResearchTraceResponse> traces) {
        if (traces == null || traces.isEmpty()) {
            return;
        }
        List<String> sourceIds = new ArrayList<>();
        for (ResearchTraceResponse trace : traces) {
            sourceIds.addAll(collectSourceIds(trace.payload()));
        }
        Map<String, SourceOriginRef> sourceOrigins = loadSourceOrigins(sourceIds);
        if (sourceOrigins.isEmpty()) {
            return;
        }
        for (ResearchTraceResponse trace : traces) {
            enrichResearchSourceProvenance(trace.payload(), sourceOrigins);
        }
    }

    private List<WorkerSourceScopeItemResponse> loadSourceScopeSnapshot(String workspaceId, String sourceScopeJson) {
        List<String> sourceIds = readSourceScopeIds(sourceScopeJson);
        if (sourceIds.isEmpty()) {
            return List.of();
        }
        return sourceIds.stream()
                .map(sourceId -> loadSourceScopeItem(workspaceId, sourceId))
                .flatMap(List::stream)
                .toList();
    }

    private List<WorkerSourceScopeItemResponse> loadSourceScopeItem(String workspaceId, String sourceId) {
        return jdbcTemplate.query("""
                select s.id, s.title, s.source_type, coalesce(s.summary, '') as summary,
                       coalesce(s.generated_by, '') as generated_by,
                       coalesce(s.generated_ref_id, '') as generated_ref_id,
                       coalesce((
                           select sw.content
                           from source_chunk sc
                           join source_window sw on sw.source_chunk_id = sc.id
                           where sc.source_id = s.id
                           order by sc.chunk_no asc, sw.window_no asc
                           limit 1
                       ), '') as sample_text
                from source s
                where s.workspace_id = ? and s.id = ? and s.status = 'READY'
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
                    buildSourceScopeMetadata(generatedBy, generatedRefId)
            );
        }, workspaceId, sourceId);
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

    private List<String> resolveRequestedSourceScopeIds(String workspaceId, List<String> requestedSourceScopeIds) {
        if (requestedSourceScopeIds == null || requestedSourceScopeIds.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> orderedIds = new LinkedHashSet<>();
        for (String sourceId : requestedSourceScopeIds) {
            String normalizedSourceId = blankToNull(sourceId);
            if (normalizedSourceId != null) {
                orderedIds.add(normalizedSourceId);
            }
        }
        if (orderedIds.isEmpty()) {
            return List.of();
        }
        if (orderedIds.size() > 20) {
            throw new BusinessException("RESEARCH_SOURCE_SCOPE_TOO_LARGE", "研究资料范围最多只能选择 20 份资料");
        }
        List<String> resolved = new ArrayList<>();
        for (String sourceId : orderedIds) {
            if (loadSourceScopeItem(workspaceId, sourceId).isEmpty()) {
                throw new BusinessException("RESEARCH_SOURCE_SCOPE_INVALID", "研究资料范围包含不存在或未就绪的资料");
            }
            resolved.add(sourceId);
        }
        return resolved;
    }

    private List<String> readSourceScopeIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            throw new BusinessException("RESEARCH_SOURCE_SCOPE_PARSE_FAILED", "研究资料范围解析失败");
        }
    }

    private MemoryControlPackResponse readControlPack(String json) {
        try {
            return objectMapper.readValue(json, MemoryControlPackResponse.class);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("RESEARCH_CONTROL_PACK_PARSE_FAILED", "研究控制包解析失败");
        }
    }

    private ResearchIntentResponse readResearchIntent(String json) {
        if (json == null || json.isBlank()) {
            return defaultResearchIntent();
        }
        try {
            return normalizeResearchIntent(objectMapper.readValue(json, ResearchIntentResponse.class));
        } catch (JsonProcessingException ex) {
            throw new BusinessException("RESEARCH_INTENT_PARSE_FAILED", "研究意图解析失败");
        }
    }

    private ResearchIntentResponse readResearchIntent(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return defaultResearchIntent();
        }
        return normalizeResearchIntent(objectMapper.convertValue(payload, ResearchIntentResponse.class));
    }

    private ResearchIntentAlignmentResponse readResearchIntentAlignment(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        return objectMapper.convertValue(payload, ResearchIntentAlignmentResponse.class);
    }

    private Map<String, Object> readPayloadMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (JsonProcessingException ex) {
            throw new BusinessException("RESEARCH_TRACE_PAYLOAD_PARSE_FAILED", "研究轨迹载荷解析失败");
        }
    }

    private void persistClosedLoopTraces(String researchRunId, String resultTitle, Map<String, Object> resultPayload) {
        insertStructuredTrace(
                researchRunId,
                "HARNESS_SUMMARY",
                resultTitle,
                "harness_summary",
                resultPayload.get("harness_summary")
        );
        insertStructuredTrace(
                researchRunId,
                "LOOP_RUNTIME",
                resultTitle,
                null,
                buildLoopRuntimePayload(resultPayload)
        );
        insertStructuredTrace(
                researchRunId,
                "STATE_LEDGER",
                resultTitle,
                "state_ledger",
                resultPayload.get("state_ledger")
        );
        insertStructuredTrace(
                researchRunId,
                "LOCAL_VERIFIER",
                resultTitle,
                "local_verifier",
                resultPayload.get("local_verifier")
        );
        insertStructuredTrace(
                researchRunId,
                "GLOBAL_VERIFIER",
                resultTitle,
                "global_verifier",
                resultPayload.get("global_verifier")
        );
        insertStructuredTrace(
                researchRunId,
                "BRANCH_DECISIONS",
                resultTitle,
                "branch_decisions",
                resultPayload.get("branch_decisions")
        );
        insertStructuredTrace(
                researchRunId,
                "REPORT_STRUCTURE",
                resultTitle,
                "report_structure",
                resultPayload.get("report_structure")
        );
        insertStructuredTrace(
                researchRunId,
                "COUNTERFACTUAL_SUMMARY",
                resultTitle,
                "counterfactual_summary",
                resultPayload.get("counterfactual_summary")
        );
        insertStructuredTrace(
                researchRunId,
                "RESEARCH_CHECKPOINT",
                resultTitle,
                "research_checkpoint_candidate",
                resultPayload.get("research_checkpoint_candidate")
        );
        insertStructuredTrace(
                researchRunId,
                "HARNESS_CONTROL_STATE",
                resultTitle,
                "harness_control_state",
                resultPayload.get("harness_control_state")
        );
        insertStructuredTrace(
                researchRunId,
                "AUDIT_SUMMARIES",
                resultTitle,
                "audit_summaries",
                resultPayload.get("audit_summaries")
        );
        insertStructuredTrace(
                researchRunId,
                "TOOLBOX_SUMMARY",
                resultTitle,
                "toolbox_summary",
                resultPayload.get("toolbox_summary")
        );
    }

    private void persistClosedLoopState(String workspaceId, String researchRunId, Map<String, Object> resultPayload) {
        Map<String, Object> stateLedger = castMapOrEmpty(resultPayload.get("state_ledger"));
        Map<String, Object> localVerifier = castMapOrEmpty(resultPayload.get("local_verifier"));
        Map<String, Object> globalVerifier = castMapOrEmpty(resultPayload.get("global_verifier"));
        List<Map<String, Object>> branchDecisions = extractListOfMaps(resultPayload.get("branch_decisions"));
        List<Map<String, Object>> cells = extractListOfMaps(stateLedger.get("cells"));
        List<Map<String, Object>> evidenceCards = extractListOfMaps(resultPayload.get("evidence_cards"));
        List<Map<String, Object>> readWindows = extractListOfMaps(resultPayload.get("read_windows"));
        Map<String, Object> checkpointCandidate = castMapOrEmpty(resultPayload.get("research_checkpoint_candidate"));

        jdbcTemplate.update("delete from research_cell_evidence where research_run_id = ?", researchRunId);
        jdbcTemplate.update("delete from source_evidence where research_run_id = ?", researchRunId);
        jdbcTemplate.update("delete from research_cell where research_run_id = ?", researchRunId);
        jdbcTemplate.update("delete from research_verifier_decision where research_run_id = ?", researchRunId);
        jdbcTemplate.update("delete from research_row where research_run_id = ?", researchRunId);
        jdbcTemplate.update("delete from research_branch where research_run_id = ?", researchRunId);

        Map<String, String> branchIdMap = persistResearchBranches(
                researchRunId,
                extractListOfMaps(stateLedger.get("branches")),
                branchDecisions
        );
        Map<String, String> rowIdMap = persistResearchRows(
                researchRunId,
                extractListOfMaps(stateLedger.get("rows")),
                branchIdMap
        );
        Map<String, String> cellIdMap = persistResearchCells(
                researchRunId,
                cells,
                rowIdMap,
                branchIdMap
        );
        persistVerifierDecisions(
                researchRunId,
                extractListOfMaps(stateLedger.get("verifier_decisions")),
                extractListOfMaps(localVerifier.get("decision_records")),
                extractListOfMaps(globalVerifier.get("decision_records")),
                branchIdMap
        );
        Map<String, String> evidenceIdMap = persistSourceEvidence(
                researchRunId,
                evidenceCards,
                readWindows
        );
        persistCellEvidence(
                researchRunId,
                cells,
                cellIdMap,
                evidenceIdMap
        );
        int finalCheckpointNo = intValue(firstNonNull(checkpointCandidate.get("checkpoint_no"), 1));
        if (!checkpointExists(researchRunId, finalCheckpointNo)) {
            persistExecutionCheckpoint(
                    workspaceId,
                    researchRunId,
                    checkpointCandidate,
                    resultPayload,
                    stateLedger,
                    evidenceCards,
                    readWindows
            );
        }
    }

    private void insertStructuredTrace(
            String researchRunId,
            String traceType,
            String resultTitle,
            String key,
            Object value
    ) {
        if (value == null) {
            return;
        }
        Map<String, Object> payload;
        if (key == null) {
            if (!(value instanceof Map<?, ?> mapValue)) {
                return;
            }
            payload = castMap(mapValue);
        } else {
            payload = Map.of(key, value);
        }
        if (payload.isEmpty()) {
            return;
        }
        insertTrace(researchRunId, traceType, resultTitle, payload);
    }

    private Map<String, String> persistResearchBranches(
            String researchRunId,
            List<Map<String, Object>> branches,
            List<Map<String, Object>> branchDecisions
    ) {
        LinkedHashMap<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (Map<String, Object> branch : branches) {
            String branchId = stringValue(branch.get("branch_id"));
            if (!branchId.isBlank()) {
                merged.put(branchId, new LinkedHashMap<>(branch));
            }
        }
        for (Map<String, Object> branchDecision : branchDecisions) {
            String branchId = stringValue(branchDecision.get("branch_id"));
            if (branchId.isBlank()) {
                continue;
            }
            merged.computeIfAbsent(branchId, ignored -> new LinkedHashMap<>()).putAll(branchDecision);
        }
        for (Map<String, Object> branch : merged.values()) {
            String branchId = stringValue(branch.get("branch_id"));
            if (branchId.isBlank()) {
                continue;
            }
            String persistedBranchId = Ids.newId();
            jdbcTemplate.update("""
                    insert into research_branch(
                        id, research_run_id, branch_key, parent_branch_id, branch_reason, branch_status,
                        hypothesis_summary, target_evidence_ids_json, created_round
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    persistedBranchId,
                    researchRunId,
                    branchId,
                    blankToNull(stringValue(branch.get("parent_branch_id"))),
                    defaultIfBlank(stringValue(branch.get("branch_reason")), "UNSPECIFIED"),
                    defaultIfBlank(
                            stringValue(firstNonNull(branch.get("status"), branch.get("branch_status"))),
                            "ACTIVE_BRANCH"
                    ),
                    blankToNull(stringValue(firstNonNull(branch.get("hypothesis_summary"), branch.get("decision")))),
                    writeJson(extractStringList(firstNonNull(branch.get("target_evidence_ids"), branch.get("target_evidence_ids_json")))),
                    intValue(firstNonNull(branch.get("created_round"), 1))
            );
            branch.put("_persisted_id", persistedBranchId);
        }
        LinkedHashMap<String, String> branchIdMap = new LinkedHashMap<>();
        merged.forEach((branchKey, branch) -> branchIdMap.put(branchKey, stringValue(branch.get("_persisted_id"))));
        return branchIdMap;
    }

    private Map<String, String> persistResearchRows(
            String researchRunId,
            List<Map<String, Object>> rows,
            Map<String, String> branchIdMap
    ) {
        LinkedHashMap<String, String> rowIdMap = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String rowId = stringValue(row.get("row_id"));
            if (rowId.isBlank()) {
                continue;
            }
            String persistedRowId = Ids.newId();
            jdbcTemplate.update("""
                    insert into research_row(
                        id, research_run_id, row_key, branch_id, source_id, source_title, search_query,
                        read_focus, evidence_id, row_status, relation_type, support_score,
                        conflict_score, support_level, verification_status, verifier_note, repair_hint
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    persistedRowId,
                    researchRunId,
                    rowId,
                    blankToNull(branchIdMap.get(stringValue(row.get("branch_id")))),
                    blankToNull(stringValue(row.get("source_id"))),
                    blankToNull(stringValue(row.get("source_title"))),
                    blankToNull(stringValue(row.get("search_query"))),
                    blankToNull(stringValue(row.get("read_focus"))),
                    blankToNull(stringValue(row.get("evidence_id"))),
                    defaultIfBlank(stringValue(row.get("row_status")), "CANDIDATE_READY"),
                    blankToNull(stringValue(row.get("relation_type"))),
                    decimalValue(row.get("support_score")),
                    decimalValue(row.get("conflict_score")),
                    blankToNull(stringValue(row.get("support_level"))),
                    blankToNull(stringValue(row.get("verification_status"))),
                    blankToNull(stringValue(row.get("verifier_note"))),
                    blankToNull(stringValue(row.get("repair_hint")))
            );
            rowIdMap.put(rowId, persistedRowId);
        }
        return rowIdMap;
    }

    private Map<String, String> persistResearchCells(
            String researchRunId,
            List<Map<String, Object>> cells,
            Map<String, String> rowIdMap,
            Map<String, String> branchIdMap
    ) {
        LinkedHashMap<String, String> cellIdMap = new LinkedHashMap<>();
        for (Map<String, Object> cell : cells) {
            String cellId = stringValue(firstNonNull(cell.get("cell_id"), cell.get("id")));
            String rowId = stringValue(firstNonNull(cell.get("row_id"), cell.get("research_row_id")));
            if (cellId.isBlank() || rowId.isBlank()) {
                continue;
            }
            String persistedRowId = rowIdMap.get(rowId);
            if (persistedRowId == null || persistedRowId.isBlank()) {
                continue;
            }
            String persistedCellId = Ids.newId();
            jdbcTemplate.update("""
                    insert into research_cell(
                        id, research_run_id, research_row_id, cell_key, branch_id, column_key,
                        candidate_value, cell_status, confidence_score, evidence_refs_json,
                        last_verifier_decision, repair_count
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    persistedCellId,
                    researchRunId,
                    persistedRowId,
                    cellId,
                    blankToNull(branchIdMap.get(stringValue(cell.get("branch_id")))),
                    defaultIfBlank(stringValue(cell.get("column_key")), "unknown"),
                    blankToNull(stringValue(cell.get("candidate_value"))),
                    defaultIfBlank(stringValue(firstNonNull(cell.get("status"), cell.get("cell_status"))), "CANDIDATE_READY"),
                    decimalValue(firstNonNull(cell.get("confidence"), cell.get("confidence_score"))),
                    writeJson(extractStringList(firstNonNull(cell.get("evidence_refs"), cell.get("evidence_refs_json")))),
                    blankToNull(stringValue(cell.get("last_verifier_decision"))),
                    intValue(cell.get("repair_count"))
            );
            cellIdMap.put(cellId, persistedCellId);
        }
        return cellIdMap;
    }

    private Map<String, String> persistSourceEvidence(
            String researchRunId,
            List<Map<String, Object>> evidenceCards,
            List<Map<String, Object>> readWindows
    ) {
        LinkedHashMap<String, Map<String, Object>> readWindowMap = new LinkedHashMap<>();
        for (Map<String, Object> readWindow : readWindows) {
            String windowId = stringValue(readWindow.get("window_id"));
            if (!windowId.isBlank()) {
                readWindowMap.put(windowId, readWindow);
            }
        }
        LinkedHashMap<String, String> evidenceIdMap = new LinkedHashMap<>();
        for (Map<String, Object> evidenceCard : evidenceCards) {
            String evidenceId = stringValue(firstNonNull(evidenceCard.get("evidence_id"), evidenceCard.get("id")));
            if (evidenceId.isBlank() || evidenceIdMap.containsKey(evidenceId)) {
                continue;
            }
            Map<String, Object> readWindow = readWindowMap.getOrDefault(
                    stringValue(evidenceCard.get("window_id")),
                    Map.of()
            );
            String persistedEvidenceId = Ids.newId();
            jdbcTemplate.update("""
                    insert into source_evidence(
                        id, research_run_id, evidence_key, window_id, source_id, source_title, source_url,
                        provider, adapter, search_query, read_focus, quote_text, claim_text, relation_type,
                        support_score, conflict_score, snapshot_status, snapshot_key
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    persistedEvidenceId,
                    researchRunId,
                    evidenceId,
                    blankToNull(stringValue(firstNonNull(evidenceCard.get("window_id"), readWindow.get("window_id")))),
                    blankToNull(stringValue(firstNonNull(evidenceCard.get("source_id"), readWindow.get("source_id")))),
                    blankToNull(stringValue(firstNonNull(evidenceCard.get("source_title"), readWindow.get("source_title")))),
                    blankToNull(stringValue(readWindow.get("url"))),
                    blankToNull(stringValue(readWindow.get("provider"))),
                    blankToNull(stringValue(readWindow.get("adapter"))),
                    blankToNull(stringValue(firstNonNull(evidenceCard.get("search_query"), readWindow.get("query")))),
                    blankToNull(stringValue(readWindow.get("read_focus"))),
                    blankToNull(stringValue(firstNonNull(evidenceCard.get("quote_text"), evidenceCard.get("quote")))),
                    blankToNull(stringValue(evidenceCard.get("claim_text"))),
                    blankToNull(stringValue(evidenceCard.get("relation_type"))),
                    decimalValue(evidenceCard.get("support_score")),
                    decimalValue(evidenceCard.get("conflict_score")),
                    blankToNull(stringValue(readWindow.get("snapshot_status"))),
                    blankToNull(stringValue(readWindow.get("snapshot_key")))
            );
            evidenceIdMap.put(evidenceId, persistedEvidenceId);
        }
        return evidenceIdMap;
    }

    private void persistCellEvidence(
            String researchRunId,
            List<Map<String, Object>> cells,
            Map<String, String> cellIdMap,
            Map<String, String> evidenceIdMap
    ) {
        for (Map<String, Object> cell : cells) {
            String cellId = stringValue(firstNonNull(cell.get("cell_id"), cell.get("id")));
            String persistedCellId = cellIdMap.get(cellId);
            if (persistedCellId == null || persistedCellId.isBlank()) {
                continue;
            }
            LinkedHashSet<String> evidenceRefs = new LinkedHashSet<>(extractStringList(
                    firstNonNull(cell.get("evidence_refs"), cell.get("evidence_refs_json"))
            ));
            for (String evidenceRef : evidenceRefs) {
                String persistedEvidenceId = evidenceIdMap.get(evidenceRef);
                if (persistedEvidenceId == null || persistedEvidenceId.isBlank()) {
                    continue;
                }
                jdbcTemplate.update("""
                        insert into research_cell_evidence(
                            id, research_run_id, research_cell_id, source_evidence_id, evidence_key
                        ) values (?, ?, ?, ?, ?)
                        """,
                        Ids.newId(),
                        researchRunId,
                        persistedCellId,
                        persistedEvidenceId,
                        evidenceRef
                );
            }
        }
    }

    private void persistExecutionCheckpoint(
            String workspaceId,
            String researchRunId,
            Map<String, Object> checkpointCandidate,
            Map<String, Object> resultPayload,
            Map<String, Object> stateLedger,
            List<Map<String, Object>> evidenceCards,
            List<Map<String, Object>> readWindows
    ) {
        if (checkpointCandidate.isEmpty()) {
            return;
        }
        LinkedHashMap<String, Object> checkpointPayload = new LinkedHashMap<>(checkpointCandidate);
        checkpointPayload.put("state_ledger", stateLedger);
        checkpointPayload.put("local_verifier", castMapOrEmpty(resultPayload.get("local_verifier")));
        checkpointPayload.put("global_verifier", castMapOrEmpty(resultPayload.get("global_verifier")));
        checkpointPayload.put("branch_decisions", extractListOfMaps(resultPayload.get("branch_decisions")));
        checkpointPayload.put("loop_rounds", extractListOfMaps(resultPayload.get("loop_rounds")));
        checkpointPayload.put("loop_decision", castMapOrEmpty(resultPayload.get("loop_decision")));
        checkpointPayload.put("harness_control_state", castMapOrEmpty(resultPayload.get("harness_control_state")));
        checkpointPayload.put("audit_summaries", castMapOrEmpty(resultPayload.get("audit_summaries")));
        checkpointPayload.put("toolbox_summary", castMapOrEmpty(resultPayload.get("toolbox_summary")));
        checkpointPayload.put("report_structure", castMapOrEmpty(resultPayload.get("report_structure")));
        checkpointPayload.put("search_hits", extractListOfMaps(resultPayload.get("search_hits")));
        checkpointPayload.put("read_windows", readWindows);
        checkpointPayload.put("evidence_cards", evidenceCards);
        Map<String, Object> checkpointSummary = buildCheckpointSummary(
                checkpointCandidate,
                checkpointPayload,
                evidenceCards,
                readWindows
        );
        String checkpointJson = Json.write(objectMapper, checkpointPayload);
        byte[] checkpointBytes = checkpointJson.getBytes(StandardCharsets.UTF_8);
        int checkpointNo = intValue(firstNonNull(checkpointCandidate.get("checkpoint_no"), 1));
        String payloadSha256 = sha256(checkpointBytes);
        List<String> existingHashes = jdbcTemplate.query(
                "select payload_sha256 from research_execution_checkpoint where research_run_id = ? and checkpoint_no = ?",
                (rs, rowNum) -> rs.getString("payload_sha256"),
                researchRunId,
                checkpointNo
        );
        if (!existingHashes.isEmpty()) {
            if (payloadSha256.equals(existingHashes.get(0))) {
                return;
            }
            throw new IllegalStateException(
                    "immutable research checkpoint conflict for run=" + researchRunId + " checkpoint=" + checkpointNo
            );
        }
        String objectKey = "workspace/%s/research/%s/checkpoints/checkpoint-%03d-%s.json"
                .formatted(workspaceId, researchRunId, checkpointNo, payloadSha256.substring(0, 12));
        storage.write("noteweave-derived", objectKey, checkpointBytes);
        jdbcTemplate.update("""
                insert into research_execution_checkpoint(
                    id, research_run_id, checkpoint_no, snapshot_type, object_key, payload_sha256,
                    content_size, active_branch_key, final_loop_decision, summary_json
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                Ids.newId(),
                researchRunId,
                checkpointNo,
                defaultIfBlank(stringValue(checkpointCandidate.get("snapshot_type")), "RESEARCH_LOOP_CHECKPOINT"),
                objectKey,
                payloadSha256,
                checkpointBytes.length,
                blankToNull(stringValue(stateLedger.get("active_branch_id"))),
                blankToNull(stringValue(castMapOrEmpty(resultPayload.get("loop_decision")).get("decision"))),
                writeJson(checkpointSummary)
        );
    }

    private boolean checkpointExists(String researchRunId, int checkpointNo) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from research_execution_checkpoint where research_run_id = ? and checkpoint_no = ?",
                Integer.class,
                researchRunId,
                checkpointNo
        );
        return count != null && count > 0;
    }

    private void persistVerifierDecisions(
            String researchRunId,
            List<Map<String, Object>> ledgerVerifierDecisions,
            List<Map<String, Object>> localDecisionRecords,
            List<Map<String, Object>> globalDecisionRecords,
            Map<String, String> branchIdMap
    ) {
        List<Map<String, Object>> decisions = !ledgerVerifierDecisions.isEmpty()
                ? ledgerVerifierDecisions
                : mergeDecisionRecords(localDecisionRecords, globalDecisionRecords);
        for (Map<String, Object> decision : decisions) {
            String decisionScope = stringValue(decision.get("decision_scope"));
            String decisionType = stringValue(decision.get("decision_type"));
            String reasonCode = defaultIfBlank(stringValue(decision.get("reason_code")), decisionType);
            if (decisionScope.isBlank() || decisionType.isBlank()) {
                continue;
            }
            jdbcTemplate.update("""
                    insert into research_verifier_decision(
                        id, research_run_id, branch_id, decision_scope, decision_type, reason_code,
                        target_id, evidence_ids_json, action_text, decision_status, notes_json
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    Ids.newId(),
                    researchRunId,
                    blankToNull(branchIdMap.get(resolveDecisionBranchId(decision))),
                    decisionScope,
                    decisionType,
                    reasonCode,
                    blankToNull(stringValue(decision.get("target_id"))),
                    writeJson(extractStringList(firstNonNull(decision.get("evidence_ids"), decision.get("evidence_ids_json")))),
                    blankToNull(stringValue(firstNonNull(decision.get("action"), decision.get("action_text")))),
                    blankToNull(stringValue(firstNonNull(decision.get("status"), decision.get("decision_status")))),
                    writeJson(extractStringList(firstNonNull(decision.get("notes"), decision.get("notes_json"))))
            );
        }
    }

    private Map<String, Object> buildLoopRuntimePayload(Map<String, Object> resultPayload) {
        Map<String, Object> payload = new LinkedHashMap<>();
        Object loopRounds = resultPayload.get("loop_rounds");
        if (loopRounds instanceof List<?> listValue) {
            payload.put("loop_rounds", listValue);
        }
        Object loopDecision = resultPayload.get("loop_decision");
        if (loopDecision instanceof Map<?, ?> mapValue) {
            payload.put("loop_decision", castMap(mapValue));
        }
        return payload.isEmpty() ? Map.of() : payload;
    }

    private ResearchClosedLoopStateResponse buildClosedLoopState(String researchRunId, List<ResearchTraceResponse> traces) {
        Map<String, Object> harnessSummary = extractNestedMap(traces, "HARNESS_SUMMARY", "harness_summary");
        Map<String, Object> checkpointCandidate = extractNestedMap(traces, "RESEARCH_CHECKPOINT", "research_checkpoint_candidate");
        Map<String, Object> finalResultPayload = extractNestedMap(traces, "FINAL_REPORT", "result_payload");
        Map<String, Object> harnessControlState = extractStructuredResultMap(
                traces,
                "HARNESS_CONTROL_STATE",
                "harness_control_state",
                finalResultPayload.get("harness_control_state"),
                checkpointCandidate.get("harness_control_state")
        );
        Map<String, Object> auditSummaries = extractStructuredResultMap(
                traces,
                "AUDIT_SUMMARIES",
                "audit_summaries",
                harnessSummary.get("audit_summaries"),
                finalResultPayload.get("audit_summaries"),
                checkpointCandidate.get("audit_summaries")
        );
        Map<String, Object> toolboxSummary = extractStructuredResultMap(
                traces,
                "TOOLBOX_SUMMARY",
                "toolbox_summary",
                harnessSummary.get("toolbox_summary"),
                finalResultPayload.get("toolbox_summary"),
                checkpointCandidate.get("toolbox_summary")
        );
        Map<String, Object> localVerifier = extractNestedMap(traces, "LOCAL_VERIFIER", "local_verifier");
        Map<String, Object> globalVerifier = extractNestedMap(traces, "GLOBAL_VERIFIER", "global_verifier");
        Map<String, Object> recoveryTargets = extractRecoveryTargets(traces);
        ResearchCounterfactualSummaryResponse counterfactualSummary = readCounterfactualSummary(
                extractNestedMap(traces, "COUNTERFACTUAL_SUMMARY", "counterfactual_summary")
        );
        List<Map<String, Object>> persistedBranches = loadPersistedBranches(researchRunId);
        List<Map<String, Object>> persistedRows = loadPersistedRows(researchRunId);
        List<Map<String, Object>> persistedCells = loadPersistedCells(researchRunId);
        List<Map<String, Object>> persistedSourceEvidence = loadPersistedSourceEvidence(researchRunId);
        List<Map<String, Object>> persistedVerifierDecisions = loadPersistedVerifierDecisions(researchRunId, persistedSourceEvidence);
        List<Map<String, Object>> persistedCheckpoints = loadPersistedCheckpoints(researchRunId);
        List<Map<String, Object>> persistedCellEvidence = loadPersistedCellEvidence(researchRunId);
        List<Map<String, Object>> branchDecisions = extractNestedListOfMaps(traces, "BRANCH_DECISIONS", "branch_decisions");
        Map<String, Object> loopRuntime = extractTracePayload(traces, "LOOP_RUNTIME");
        List<Map<String, Object>> loopRounds = extractListOfMaps(loopRuntime.get("loop_rounds"));
        enrichLoopRoundsWithSourceSamples(loopRounds, persistedSourceEvidence);
        Map<String, Object> loopDecision = castMapOrEmpty(loopRuntime.get("loop_decision"));
        Map<String, Object> stateLedger = mergeStateLedgerSnapshot(
                extractNestedMap(traces, "STATE_LEDGER", "state_ledger"),
                persistedBranches,
                persistedRows,
                persistedCells,
                persistedVerifierDecisions
        );
        ResearchStateLedgerResponse stateLedgerResponse = readStateLedgerResponse(stateLedger);
        List<ResearchCheckpointSummaryResponse> checkpointResponses = persistedCheckpoints.stream()
                .map(this::toCheckpointSummaryResponse)
                .toList();
        if (counterfactualSummary == null) {
            counterfactualSummary = buildCounterfactualSummary(
                    Map.of(),
                    List.of(),
                    branchDecisions,
                    persistedBranches,
                    persistedRows,
                    stringValue(stateLedger.get("active_branch_id")),
                    stringValue(localVerifier.get("status")),
                    stringValue(globalVerifier.get("decision")),
                    ""
            );
        }

        return new ResearchClosedLoopStateResponse(
                stringValue(stateLedger.get("active_branch_id")),
                stringValue(localVerifier.get("status")),
                stringValue(globalVerifier.get("decision")),
                stringValue(loopDecision.get("decision")),
                loopRounds.size(),
                persistedRows.size(),
                persistedBranches.size(),
                persistedVerifierDecisions.size(),
                harnessSummary,
                checkpointCandidate,
                harnessControlState,
                auditSummaries,
                toolboxSummary,
                counterfactualSummary,
                readRecoveryTargetsResponse(recoveryTargets),
                stateLedgerResponse,
                localVerifier,
                globalVerifier,
                persistedBranches,
                persistedRows,
                persistedCells,
                persistedVerifierDecisions,
                checkpointResponses,
                persistedSourceEvidence,
                persistedCellEvidence,
                branchDecisions,
                loopRounds,
                loopDecision
        );
    }

    private ResearchReportStructureResponse buildReportStructure(String researchRunId, List<ResearchTraceResponse> traces) {
        Map<String, Object> reportStructure = extractNestedMap(traces, "REPORT_STRUCTURE", "report_structure");
        if (reportStructure.isEmpty()) {
            Map<String, Object> finalResultPayload = extractNestedMap(traces, "FINAL_REPORT", "result_payload");
            reportStructure = castMapOrEmpty(finalResultPayload.get("report_structure"));
        }
        if (reportStructure.isEmpty()) {
            return null;
        }
        enrichResearchSourceProvenance(reportStructure, loadSourceOrigins(collectSourceIds(reportStructure)));
        return new ResearchReportStructureResponse(
                castMapOrEmpty(reportStructure.get("research_question")),
                readResearchIntent(castMapOrEmpty(reportStructure.get("research_intent"))),
                castMapOrEmpty(reportStructure.get("intent_completion_contract")),
                readResearchIntentAlignment(castMapOrEmpty(reportStructure.get("research_intent_alignment"))),
                extractStringList(reportStructure.get("key_findings")),
                extractListOfMaps(reportStructure.get("verified_findings")),
                extractListOfMaps(reportStructure.get("evidence_ledger")),
                castMapOrEmpty(reportStructure.get("closed_loop_state")),
                readCounterfactualSummary(castMapOrEmpty(reportStructure.get("counterfactual_summary"))),
                castMapOrEmpty(reportStructure.get("conflict_and_counterfactual_review")),
                castMapOrEmpty(reportStructure.get("recovery_status")),
                castMapOrEmpty(reportStructure.get("final_answer")),
                castMapOrEmpty(reportStructure.get("source_foundation")),
                extractStringList(reportStructure.get("next_actions")),
                castMapOrEmpty(reportStructure.get("resume_checkpoint")),
                stringValue(reportStructure.get("recovery_mode")),
                extractStringList(reportStructure.get("control_notes"))
        );
    }

    private ResearchArtifactCandidateResponse buildResearchArtifactCandidate(List<ResearchTraceResponse> traces) {
        Map<String, Object> finalResultPayload = extractNestedMap(traces, "FINAL_REPORT", "result_payload");
        return readResearchArtifactCandidateResponse(nonEmptyMapOrNull(firstNonNull(
                finalResultPayload.get("research_artifact_candidate"),
                castMapOrEmpty(finalResultPayload.get("research_checkpoint_candidate")).get("research_artifact_candidate")
        )));
    }

    private ResearchResumeContextSummaryResponse buildResumeContextSummary(List<ResearchTraceResponse> traces) {
        Map<String, Object> finalResultPayload = extractNestedMap(traces, "FINAL_REPORT", "result_payload");
        return readResumeContextSummaryResponse(nonEmptyMapOrNull(firstNonNull(
                finalResultPayload.get("resume_context_summary"),
                firstNonNull(
                        castMapOrEmpty(finalResultPayload.get("research_artifact_candidate")).get("resume_context_summary"),
                        castMapOrEmpty(finalResultPayload.get("research_checkpoint_candidate")).get("resume_context_summary")
                )
        )));
    }

    private ResearchVerifierSummaryResponse buildVerifierSummary(
            ResearchReportStructureResponse reportStructure,
            ResearchClosedLoopStateResponse closedLoopState
    ) {
        if (closedLoopState == null) {
            return null;
        }
        Map<String, Object> localVerifier = closedLoopState.localVerifier();
        Map<String, Object> globalVerifier = closedLoopState.globalVerifier();
        Map<String, Object> loopDecisionPayload = closedLoopState.loopDecisionPayload();
        ResearchIntentAlignmentResponse researchIntentAlignment = buildResearchIntentAlignment(reportStructure, closedLoopState);
        ResearchRecoveryTargetsResponse recoveryTargets = buildRecoveryTargets(reportStructure, closedLoopState);
        Map<String, Object> verifierGatedSummary = buildVerifierGatedSummary(
                closedLoopState.stateLedger().rows(),
                recoveryTargetsMap(recoveryTargets)
        );
        return new ResearchVerifierSummaryResponse(
                blankIfNull(closedLoopState.localVerifierStatus()),
                extractDecisionReason(localVerifier),
                blankIfNull(closedLoopState.globalVerifierDecision()),
                extractDecisionReason(globalVerifier),
                blankIfNull(closedLoopState.finalLoopDecision()),
                blankIfNull(stringValue(loopDecisionPayload.get("reason"))),
                researchIntentAlignment == null ? "" : blankIfNull(researchIntentAlignment.status()),
                researchIntentAlignment == null ? "" : blankIfNull(researchIntentAlignment.reasonCode()),
                recoveryTargets,
                readVerifierGatedSummaryResponse(verifierGatedSummary)
        );
    }

    private ResearchProcessSummaryResponse buildResearchProcessSummary(
            ResearchReportStructureResponse reportStructure,
            ResearchClosedLoopStateResponse closedLoopState,
            ResearchVerifierSummaryResponse verifierSummary,
            int sourceScopeCount,
            ResearchArtifactCandidateResponse researchArtifactCandidate
    ) {
        return new ResearchProcessSummaryResponse(
                sourceScopeCount,
                buildSearchReadTimeline(closedLoopState),
                buildSourceEvidenceSummary(reportStructure, researchArtifactCandidate),
                buildAuditSummary(closedLoopState, verifierSummary)
        );
    }

    private ResearchProcessSummaryResponse buildCheckpointProcessSummary(
            Map<String, Object> checkpointPayload,
            Map<String, Object> checkpointSummary
    ) {
        return new ResearchProcessSummaryResponse(
                estimateCheckpointSourceScopeCount(checkpointPayload),
                buildCheckpointSearchReadTimeline(checkpointPayload),
                buildCheckpointSourceEvidenceSummary(checkpointPayload),
                buildCheckpointAuditSummary(checkpointPayload, checkpointSummary)
        );
    }

    private ResearchSearchReadTimelineResponse buildSearchReadTimeline(ResearchClosedLoopStateResponse closedLoopState) {
        List<Map<String, Object>> loopRounds = closedLoopState == null ? List.of() : closedLoopState.loopRounds();
        List<ResearchLoopRoundSummaryResponse> roundResponses = new ArrayList<>();
        LinkedHashSet<String> allQueries = new LinkedHashSet<>();
        int totalSearchHitCount = 0;
        int totalReadWindowCount = 0;
        int totalEvidenceCardCount = 0;
        for (Map<String, Object> round : loopRounds) {
            List<String> searchQueries = extractStringList(round.get("search_queries"));
            allQueries.addAll(searchQueries);
            totalSearchHitCount += intValue(round.get("search_hit_count"));
            totalReadWindowCount += intValue(round.get("read_window_count"));
            totalEvidenceCardCount += intValue(round.get("evidence_card_count"));
            roundResponses.add(new ResearchLoopRoundSummaryResponse(
                    intValue(round.get("round_no")),
                    intValue(round.get("search_hit_count")),
                    intValue(round.get("read_window_count")),
                    intValue(round.get("evidence_card_count")),
                    searchQueries,
                    extractStringList(round.get("evidence_ids")),
                    blankIfNull(stringValue(round.get("branch_decision"))),
                    blankIfNull(stringValue(round.get("global_decision")))
            ));
        }
        return new ResearchSearchReadTimelineResponse(
                loopRounds.size(),
                totalSearchHitCount,
                totalReadWindowCount,
                totalEvidenceCardCount,
                new ArrayList<>(allQueries),
                closedLoopState == null ? "" : blankIfNull(closedLoopState.finalLoopDecision()),
                closedLoopState == null ? "" : blankIfNull(stringValue(closedLoopState.loopDecisionPayload().get("reason"))),
                closedLoopState == null ? "" : blankIfNull(stringValue(closedLoopState.loopDecisionPayload().get("terminal_disposition"))),
                closedLoopState != null && booleanValue(closedLoopState.loopDecisionPayload().get("handoff_required")),
                closedLoopState == null ? "" : blankIfNull(stringValue(closedLoopState.loopDecisionPayload().get("abandon_reason"))),
                roundResponses
        );
    }

    private ResearchSearchReadTimelineResponse buildCheckpointSearchReadTimeline(Map<String, Object> checkpointPayload) {
        List<Map<String, Object>> loopRounds = extractListOfMaps(checkpointPayload.get("loop_rounds"));
        Map<String, Object> loopDecision = castMapOrEmpty(checkpointPayload.get("loop_decision"));
        List<ResearchLoopRoundSummaryResponse> roundResponses = new ArrayList<>();
        LinkedHashSet<String> allQueries = new LinkedHashSet<>();
        int totalSearchHitCount = 0;
        int totalReadWindowCount = 0;
        int totalEvidenceCardCount = 0;
        for (Map<String, Object> round : loopRounds) {
            List<String> searchQueries = extractStringList(round.get("search_queries"));
            allQueries.addAll(searchQueries);
            totalSearchHitCount += intValue(round.get("search_hit_count"));
            totalReadWindowCount += intValue(round.get("read_window_count"));
            totalEvidenceCardCount += intValue(round.get("evidence_card_count"));
            roundResponses.add(new ResearchLoopRoundSummaryResponse(
                    intValue(round.get("round_no")),
                    intValue(round.get("search_hit_count")),
                    intValue(round.get("read_window_count")),
                    intValue(round.get("evidence_card_count")),
                    searchQueries,
                    extractStringList(round.get("evidence_ids")),
                    blankIfNull(stringValue(round.get("branch_decision"))),
                    blankIfNull(stringValue(round.get("global_decision")))
            ));
        }
        return new ResearchSearchReadTimelineResponse(
                loopRounds.size(),
                totalSearchHitCount,
                totalReadWindowCount,
                totalEvidenceCardCount,
                new ArrayList<>(allQueries),
                blankIfNull(stringValue(loopDecision.get("decision"))),
                blankIfNull(stringValue(loopDecision.get("reason"))),
                blankIfNull(stringValue(loopDecision.get("terminal_disposition"))),
                booleanValue(loopDecision.get("handoff_required")),
                blankIfNull(stringValue(loopDecision.get("abandon_reason"))),
                roundResponses
        );
    }

    private ResearchSourceEvidenceSummaryResponse buildSourceEvidenceSummary(
            ResearchReportStructureResponse reportStructure,
            ResearchArtifactCandidateResponse researchArtifactCandidate
    ) {
        Map<String, Object> sourceFoundation = reportStructure == null ? Map.of() : reportStructure.sourceFoundation();
        Map<String, Object> finalAnswer = reportStructure == null ? Map.of() : reportStructure.finalAnswer();
        String sourceBasis = blankIfNull(stringValue(finalAnswer.get("source_basis")));
        if (sourceBasis.isBlank() && researchArtifactCandidate != null) {
            sourceBasis = blankIfNull(researchArtifactCandidate.sourceBasis());
        }
        return new ResearchSourceEvidenceSummaryResponse(
                sourceBasis,
                blankIfNull(stringValue(sourceFoundation.get("primary_quality"))),
                blankIfNull(stringValue(sourceFoundation.get("quality_mix_label"))),
                blankIfNull(stringValue(sourceFoundation.get("read_strategy_mix_label"))),
                blankIfNull(stringValue(sourceFoundation.get("fetch_foundation_label"))),
                blankIfNull(stringValue(sourceFoundation.get("orchestration_foundation_label"))),
                reportStructure == null ? 0 : reportStructure.verifiedFindings().size(),
                researchArtifactCandidate == null ? 0 : researchArtifactCandidate.citationCount()
        );
    }

    private ResearchSourceEvidenceSummaryResponse buildCheckpointSourceEvidenceSummary(Map<String, Object> checkpointPayload) {
        Map<String, Object> reportStructure = castMapOrEmpty(checkpointPayload.get("report_structure"));
        Map<String, Object> sourceFoundation = castMapOrEmpty(reportStructure.get("source_foundation"));
        Map<String, Object> finalAnswer = castMapOrEmpty(reportStructure.get("final_answer"));
        return new ResearchSourceEvidenceSummaryResponse(
                blankIfNull(stringValue(finalAnswer.get("source_basis"))),
                blankIfNull(stringValue(sourceFoundation.get("primary_quality"))),
                blankIfNull(stringValue(sourceFoundation.get("quality_mix_label"))),
                blankIfNull(stringValue(sourceFoundation.get("read_strategy_mix_label"))),
                blankIfNull(stringValue(sourceFoundation.get("fetch_foundation_label"))),
                blankIfNull(stringValue(sourceFoundation.get("orchestration_foundation_label"))),
                extractListOfMaps(reportStructure.get("verified_findings")).size(),
                0
        );
    }

    private ResearchAuditSummaryResponse buildAuditSummary(
            ResearchClosedLoopStateResponse closedLoopState,
            ResearchVerifierSummaryResponse verifierSummary
    ) {
        ResearchVerifierGatedSummaryResponse verifierGatedSummary = verifierSummary == null
                ? null
                : verifierSummary.verifierGatedSummary();
        ResearchRecoveryTargetsResponse recoveryTargets = verifierSummary == null
                ? null
                : verifierSummary.recoveryTargets();
        ResearchCounterfactualSummaryResponse counterfactualSummary = closedLoopState == null
                ? null
                : closedLoopState.counterfactualSummary();
        return new ResearchAuditSummaryResponse(
                verifierSummary == null ? "" : blankIfNull(verifierSummary.localVerifierStatus()),
                verifierSummary == null ? "" : blankIfNull(verifierSummary.globalVerifierDecision()),
                verifierSummary == null ? "" : blankIfNull(verifierSummary.finalLoopDecision()),
                counterfactualSummary != null && counterfactualSummary.hasCounterfactualRecheck(),
                counterfactualSummary == null ? 0 : counterfactualSummary.counterfactualBranchCount(),
                closedLoopState == null ? 0 : closedLoopState.checkpoints().size(),
                verifierGatedSummary == null ? 0 : verifierGatedSummary.blockedRowCount(),
                closedLoopState == null ? 0 : closedLoopState.stateLedger().conflictedRowCount(),
                verifierGatedSummary == null ? 0 : verifierGatedSummary.guardrailedRowCount(),
                recoveryTargets == null ? 0 : recoveryTargets.requirementCount()
        );
    }

    private ResearchAuditSummaryResponse buildCheckpointAuditSummary(
            Map<String, Object> checkpointPayload,
            Map<String, Object> checkpointSummary
    ) {
        Map<String, Object> safeSummary = castMapOrEmpty(checkpointSummary);
        Map<String, Object> localVerifier = castMapOrEmpty(firstNonNull(
                safeSummary.get("local_verifier"),
                checkpointPayload.get("local_verifier")
        ));
        Map<String, Object> globalVerifier = castMapOrEmpty(firstNonNull(
                safeSummary.get("global_verifier"),
                checkpointPayload.get("global_verifier")
        ));
        Map<String, Object> loopDecision = castMapOrEmpty(firstNonNull(
                safeSummary.get("loop_decision"),
                checkpointPayload.get("loop_decision")
        ));
        ResearchCounterfactualSummaryResponse counterfactualSummary = firstNonNullCounterfactualSummary(
                safeSummary,
                checkpointPayload
        );
        ResearchRecoveryTargetsResponse recoveryTargets = readRecoveryTargetsResponse(
                rawRecoveryTargets(checkpointPayload)
        );
        ResearchVerifierGatedSummaryResponse verifierGatedSummary = readVerifierGatedSummaryResponse(
                castMapOrEmpty(safeSummary.get("verifier_gated_summary"))
        );
        ResearchCheckpointStateLedgerSummaryResponse stateLedgerSummary = readCheckpointStateLedgerSummaryResponse(
                castMapOrEmpty(safeSummary.get("state_ledger"))
        );
        return new ResearchAuditSummaryResponse(
                blankIfNull(stringValue(localVerifier.get("status"))),
                blankIfNull(stringValue(globalVerifier.get("decision"))),
                blankIfNull(stringValue(loopDecision.get("decision"))),
                counterfactualSummary != null && counterfactualSummary.hasCounterfactualRecheck(),
                counterfactualSummary == null ? 0 : counterfactualSummary.counterfactualBranchCount(),
                intValue(firstNonNull(safeSummary.get("checkpoint_no"), 1)),
                verifierGatedSummary == null ? 0 : verifierGatedSummary.blockedRowCount(),
                stateLedgerSummary == null ? 0 : stateLedgerSummary.conflictedRowCount(),
                verifierGatedSummary == null ? 0 : verifierGatedSummary.guardrailedRowCount(),
                recoveryTargets == null ? 0 : recoveryTargets.requirementCount()
        );
    }

    private int estimateCheckpointSourceScopeCount(Map<String, Object> checkpointPayload) {
        LinkedHashSet<String> sourceIds = new LinkedHashSet<>();
        for (Map<String, Object> readWindow : extractListOfMaps(checkpointPayload.get("read_windows"))) {
            String sourceId = blankIfNull(stringValue(readWindow.get("source_id")));
            if (!sourceId.isBlank()) {
                sourceIds.add(sourceId);
            }
        }
        for (Map<String, Object> evidenceCard : extractListOfMaps(checkpointPayload.get("evidence_cards"))) {
            String sourceId = blankIfNull(stringValue(evidenceCard.get("source_id")));
            if (!sourceId.isBlank()) {
                sourceIds.add(sourceId);
            }
        }
        return sourceIds.size();
    }

    private ResearchCounterfactualSummaryResponse buildCounterfactualSummary(
            ResearchReportStructureResponse reportStructure,
            ResearchClosedLoopStateResponse closedLoopState
    ) {
        if (reportStructure != null && reportStructure.counterfactualSummary() != null) {
            return reportStructure.counterfactualSummary();
        }
        Map<String, Object> conflictReview = reportStructure == null
                ? Map.of()
                : reportStructure.conflictAndCounterfactualReview();
        String recoveryMode = reportStructure == null ? "" : reportStructure.recoveryMode();
        List<Map<String, Object>> reportBranchDecisions = extractListOfMaps(conflictReview.get("branch_decisions"));
        return buildCounterfactualSummary(
                conflictReview,
                reportBranchDecisions,
                closedLoopState == null ? List.of() : closedLoopState.branchDecisions(),
                closedLoopState == null ? List.of() : closedLoopState.branches(),
                closedLoopState == null ? List.of() : closedLoopState.rows(),
                closedLoopState == null ? "" : closedLoopState.activeBranchId(),
                closedLoopState == null ? "" : closedLoopState.localVerifierStatus(),
                closedLoopState == null ? "" : closedLoopState.globalVerifierDecision(),
                recoveryMode
        );
    }

    private ResearchIntentAlignmentResponse buildResearchIntentAlignment(
            ResearchReportStructureResponse reportStructure,
            ResearchClosedLoopStateResponse closedLoopState
    ) {
        if (reportStructure != null && reportStructure.researchIntentAlignment() != null) {
            return reportStructure.researchIntentAlignment();
        }
        Map<String, Object> localVerifier = closedLoopState == null ? Map.of() : closedLoopState.localVerifier();
        Map<String, Object> globalVerifier = closedLoopState == null ? Map.of() : closedLoopState.globalVerifier();
        return readResearchIntentAlignment(castMapOrEmpty(firstNonNull(
                globalVerifier.get("research_intent_alignment"),
                localVerifier.get("research_intent_alignment")
        )));
    }

    private Map<String, Object> buildIntentCompletionContract(
            ResearchReportStructureResponse reportStructure,
            ResearchClosedLoopStateResponse closedLoopState
    ) {
        if (reportStructure != null && !reportStructure.intentCompletionContract().isEmpty()) {
            return reportStructure.intentCompletionContract();
        }
        if (closedLoopState == null) {
            return Map.of();
        }
        Map<String, Object> direct = castMapOrEmpty(closedLoopState.stateLedger().intentCompletionContract());
        if (!direct.isEmpty()) {
            return direct;
        }
        return castMapOrEmpty(castMapOrEmpty(
                castMapOrEmpty(closedLoopState.checkpointCandidate()).get("state_ledger")
        ).get("intent_completion_contract"));
    }

    private ResearchRecoveryTargetsResponse buildRecoveryTargets(
            ResearchReportStructureResponse reportStructure,
            ResearchClosedLoopStateResponse closedLoopState
    ) {
        if (closedLoopState != null && closedLoopState.recoveryTargets() != null) {
            return closedLoopState.recoveryTargets();
        }
        return readRecoveryTargetsResponse(buildRecoveryTargetsMap(reportStructure));
    }

    private Map<String, Object> buildRecoveryTargetsMap(ResearchReportStructureResponse reportStructure) {
        if (reportStructure == null) {
            return Map.of();
        }
        Map<String, Object> closedLoopStatePayload = reportStructure.closedLoopState();
        Map<String, Object> direct = castMapOrEmpty(firstNonNull(
                closedLoopStatePayload.get("recovery_targets"),
                reportStructure.recoveryStatus().get("recovery_targets")
        ));
        return direct.isEmpty() ? Map.of() : direct;
    }

    private ResearchCounterfactualSummaryResponse buildCounterfactualSummary(Map<String, Object> checkpointPayload) {
        ResearchCounterfactualSummaryResponse directSummary = readCounterfactualSummary(castMapOrEmpty(
                firstNonNull(
                        checkpointPayload.get("counterfactual_summary"),
                        castMapOrEmpty(checkpointPayload.get("report_structure")).get("counterfactual_summary")
                )
        ));
        if (directSummary != null) {
            return directSummary;
        }
        Map<String, Object> reportStructure = castMapOrEmpty(checkpointPayload.get("report_structure"));
        Map<String, Object> conflictReview = castMapOrEmpty(reportStructure.get("conflict_and_counterfactual_review"));
        Map<String, Object> stateLedger = castMapOrEmpty(checkpointPayload.get("state_ledger"));
        Map<String, Object> localVerifier = castMapOrEmpty(checkpointPayload.get("local_verifier"));
        Map<String, Object> globalVerifier = castMapOrEmpty(checkpointPayload.get("global_verifier"));
        return buildCounterfactualSummary(
                conflictReview,
                extractListOfMaps(conflictReview.get("branch_decisions")),
                extractListOfMaps(checkpointPayload.get("branch_decisions")),
                extractListOfMaps(stateLedger.get("branches")),
                extractListOfMaps(stateLedger.get("rows")),
                stringValue(stateLedger.get("active_branch_id")),
                stringValue(firstNonNull(
                        localVerifier.get("status"),
                        conflictReview.get("local_verifier_status")
                )),
                stringValue(firstNonNull(
                        globalVerifier.get("decision"),
                        conflictReview.get("global_verifier_decision")
                )),
                stringValue(firstNonNull(
                        reportStructure.get("recovery_mode"),
                        conflictReview.get("recovery_mode")
                ))
        );
    }

    private ResearchCounterfactualSummaryResponse buildCounterfactualSummary(
            Map<String, Object> conflictReview,
            List<Map<String, Object>> reportBranchDecisions,
            List<Map<String, Object>> stateBranchDecisions,
            List<Map<String, Object>> branches,
            List<Map<String, Object>> rows,
            String activeBranchId,
            String localVerifierStatus,
            String globalVerifierDecision,
            String recoveryMode
    ) {
        List<Map<String, Object>> decisionCandidates = !reportBranchDecisions.isEmpty()
                ? reportBranchDecisions
                : stateBranchDecisions;
        LinkedHashMap<String, Map<String, Object>> branchMap = new LinkedHashMap<>();
        for (Map<String, Object> branch : branches) {
            String branchId = stringValue(firstNonNull(branch.get("branch_id"), branch.get("branch_key")));
            if (!branchId.isBlank()) {
                branchMap.put(branchId, branch);
            }
        }

        LinkedHashSet<String> counterfactualBranchIds = new LinkedHashSet<>();
        LinkedHashSet<String> counterfactualSessionIds = new LinkedHashSet<>();
        LinkedHashSet<String> activeCounterfactualBranchIds = new LinkedHashSet<>();
        LinkedHashSet<String> activeCounterfactualSessionIds = new LinkedHashSet<>();
        LinkedHashSet<String> branchReasons = new LinkedHashSet<>();
        LinkedHashSet<String> targetEvidenceIds = new LinkedHashSet<>();
        List<ResearchCounterfactualBranchResponse> counterfactualBranches = new ArrayList<>();

        for (Map<String, Object> decision : decisionCandidates) {
            String decisionType = stringValue(firstNonNull(decision.get("decision"), decision.get("decision_type")));
            if (!"COUNTERFACTUAL_RECHECK".equals(decisionType)) {
                continue;
            }
            String branchId = stringValue(firstNonNull(
                    decision.get("branch_id"),
                    firstNonNull(decision.get("target_id"), decision.get("branch_key"))
            ));
            Map<String, Object> branch = branchId.isBlank()
                    ? Map.of()
                    : branchMap.getOrDefault(branchId, Map.of());
            String sessionId = blankIfNull(stringValue(firstNonNull(decision.get("session_id"), branch.get("session_id"))));
            String parentSessionId = blankIfNull(stringValue(firstNonNull(decision.get("parent_session_id"), branch.get("parent_session_id"))));
            String executionMode = blankIfNull(stringValue(firstNonNull(decision.get("execution_mode"), branch.get("execution_mode"))));
            List<String> siblingBranchIds = extractStringList(firstNonNull(
                    decision.get("sibling_branch_ids"), branch.get("sibling_branch_ids")
            ));
            List<String> branchTargetEvidenceIds = extractStringList(firstNonNull(
                    decision.get("target_evidence_ids"),
                    firstNonNull(
                            decision.get("evidence_ids"),
                            firstNonNull(branch.get("target_evidence_ids"), branch.get("target_evidence_ids_json"))
                    )
            ));
            String branchReason = defaultIfBlank(
                    stringValue(firstNonNull(decision.get("branch_reason"), branch.get("branch_reason"))),
                    "COUNTERFACTUAL_RECHECK"
            );
            String branchStatus = defaultIfBlank(
                    stringValue(firstNonNull(decision.get("branch_status"), firstNonNull(branch.get("status"), branch.get("branch_status")))),
                    "ACTIVE_BRANCH"
            );
            if (!branchId.isBlank()) {
                counterfactualBranchIds.add(branchId);
            }
            if (!sessionId.isBlank()) {
                counterfactualSessionIds.add(sessionId);
            }
            if (!branchReason.isBlank()) {
                branchReasons.add(branchReason);
            }
            targetEvidenceIds.addAll(branchTargetEvidenceIds);
            if ((!branchId.isBlank() && branchId.equals(activeBranchId)) || isActiveBranchStatus(branchStatus)) {
                if (!branchId.isBlank()) {
                    activeCounterfactualBranchIds.add(branchId);
                }
                if (!sessionId.isBlank()) {
                    activeCounterfactualSessionIds.add(sessionId);
                }
            }
            counterfactualBranches.add(new ResearchCounterfactualBranchResponse(
                    branchId,
                    sessionId,
                    blankIfNull(stringValue(firstNonNull(branch.get("parent_branch_id"), decision.get("parent_branch_id")))),
                    parentSessionId,
                    branchReason,
                    branchStatus,
                    executionMode,
                    siblingBranchIds,
                    decisionType,
                    blankIfNull(stringValue(decision.get("verifier_scope"))),
                    blankIfNull(stringValue(firstNonNull(branch.get("hypothesis_summary"), decision.get("hypothesis_summary")))),
                    branchTargetEvidenceIds
            ));
        }

        List<Map<String, Object>> conflictedRows = extractListOfMaps(conflictReview.get("conflicted_rows"));
        if (conflictedRows.isEmpty()) {
            conflictedRows = rows.stream()
                    .filter(this::isCounterfactualRow)
                    .toList();
        }
        boolean hasCounterfactualRecheck = !counterfactualBranches.isEmpty()
                || "COUNTERFACTUAL_RECHECK".equals(recoveryMode);
        return new ResearchCounterfactualSummaryResponse(
                hasCounterfactualRecheck,
                counterfactualBranches.size(),
                conflictedRows.size(),
                blankIfNull(localVerifierStatus),
                blankIfNull(globalVerifierDecision),
                blankIfNull(recoveryMode),
                List.copyOf(counterfactualBranchIds),
                List.copyOf(counterfactualSessionIds),
                List.copyOf(activeCounterfactualBranchIds),
                List.copyOf(activeCounterfactualSessionIds),
                List.copyOf(branchReasons),
                List.copyOf(targetEvidenceIds),
                counterfactualBranches
        );
    }

    private ResearchCounterfactualSummaryResponse readCounterfactualSummary(Map<String, Object> payload) {
        if (payload.isEmpty()) {
            return null;
        }
        return new ResearchCounterfactualSummaryResponse(
                booleanValue(payload.get("has_counterfactual_recheck")),
                intValue(payload.get("counterfactual_branch_count")),
                intValue(payload.get("conflicted_row_count")),
                blankIfNull(stringValue(payload.get("local_verifier_status"))),
                blankIfNull(stringValue(payload.get("global_verifier_decision"))),
                blankIfNull(stringValue(payload.get("recovery_mode"))),
                extractStringList(payload.get("counterfactual_branch_ids")),
                extractStringList(payload.get("counterfactual_session_ids")),
                extractStringList(payload.get("active_counterfactual_branch_ids")),
                extractStringList(payload.get("active_counterfactual_session_ids")),
                extractStringList(payload.get("branch_reasons")),
                extractStringList(payload.get("target_evidence_ids")),
                extractCounterfactualBranches(payload.get("branches"))
        );
    }

    private List<ResearchCounterfactualBranchResponse> extractCounterfactualBranches(Object value) {
        return extractListOfMaps(value).stream()
                .map(branch -> new ResearchCounterfactualBranchResponse(
                        blankIfNull(stringValue(branch.get("branch_id"))),
                        blankIfNull(stringValue(branch.get("session_id"))),
                        blankIfNull(stringValue(branch.get("parent_branch_id"))),
                        blankIfNull(stringValue(branch.get("parent_session_id"))),
                        blankIfNull(stringValue(branch.get("branch_reason"))),
                        blankIfNull(stringValue(branch.get("branch_status"))),
                        blankIfNull(stringValue(branch.get("execution_mode"))),
                        extractStringList(branch.get("sibling_branch_ids")),
                        blankIfNull(stringValue(branch.get("decision"))),
                        blankIfNull(stringValue(branch.get("verifier_scope"))),
                        blankIfNull(stringValue(branch.get("hypothesis_summary"))),
                        extractStringList(branch.get("target_evidence_ids"))
                ))
                .toList();
    }

    private Map<String, Object> extractTracePayload(List<ResearchTraceResponse> traces, String traceType) {
        return traces.stream()
                .filter(trace -> traceType.equals(trace.traceType()))
                .reduce((first, second) -> second)
                .map(ResearchTraceResponse::payload)
                .orElse(Map.of());
    }

    private String extractDecisionReason(Map<String, Object> verifierPayload) {
        String directReason = blankIfNull(stringValue(firstNonNull(
                verifierPayload.get("reason_code"),
                verifierPayload.get("reason")
        )));
        if (!directReason.isBlank()) {
            return directReason;
        }
        List<Map<String, Object>> decisionRecords = extractListOfMaps(verifierPayload.get("decision_records"));
        if (decisionRecords.isEmpty()) {
            return "";
        }
        Map<String, Object> latestRecord = decisionRecords.get(decisionRecords.size() - 1);
        return blankIfNull(stringValue(firstNonNull(
                latestRecord.get("reason_code"),
                latestRecord.get("reason")
        )));
    }

    private Map<String, Object> extractNestedMap(List<ResearchTraceResponse> traces, String traceType, String key) {
        Map<String, Object> payload = extractTracePayload(traces, traceType);
        return castMapOrEmpty(payload.get(key));
    }

    private Map<String, Object> extractStructuredResultMap(
            List<ResearchTraceResponse> traces,
            String traceType,
            String key,
            Object... fallbackCandidates
    ) {
        Map<String, Object> direct = extractNestedMap(traces, traceType, key);
        if (!direct.isEmpty()) {
            return direct;
        }
        for (Object candidate : fallbackCandidates) {
            Map<String, Object> fallback = castMapOrEmpty(candidate);
            if (!fallback.isEmpty()) {
                return fallback;
            }
        }
        return Map.of();
    }

    private List<Map<String, Object>> extractNestedListOfMaps(List<ResearchTraceResponse> traces, String traceType, String key) {
        Map<String, Object> payload = extractTracePayload(traces, traceType);
        return extractListOfMaps(payload.get(key));
    }

    private List<Map<String, Object>> extractListOfMaps(Object value) {
        if (!(value instanceof List<?> listValue)) {
            return List.of();
        }
        return listValue.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(this::castMap)
                .toList();
    }

    private Map<String, Object> castMapOrEmpty(Object value) {
        if (!(value instanceof Map<?, ?> mapValue)) {
            return Map.of();
        }
        return castMap(mapValue);
    }

    private Map<String, Object> nonEmptyMapOrNull(Object value) {
        Map<String, Object> mapValue = castMapOrEmpty(value);
        return mapValue.isEmpty() ? null : mapValue;
    }

    private ResearchRecoveryTargetsResponse readRecoveryTargetsResponse(Map<String, Object> recoveryTargets) {
        if (recoveryTargets == null || recoveryTargets.isEmpty()) {
            return emptyRecoveryTargetsResponse();
        }
        return new ResearchRecoveryTargetsResponse(
                extractStringList(recoveryTargets.get("requirement_ids")),
                extractStringList(recoveryTargets.get("requirement_types")),
                extractStringList(recoveryTargets.get("requirement_labels")),
                extractStringList(recoveryTargets.get("target_columns")),
                extractStringList(recoveryTargets.get("target_queries")),
                extractStringList(recoveryTargets.get("target_sources")),
                intValue(recoveryTargets.get("requirement_count")),
                intValue(recoveryTargets.get("query_count")),
                intValue(recoveryTargets.get("source_count")),
                intValue(recoveryTargets.get("column_count"))
        );
    }

    private ResearchRecoveryTargetsResponse emptyRecoveryTargetsResponse() {
        return new ResearchRecoveryTargetsResponse(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0,
                0,
                0,
                0
        );
    }

    private ResearchVerifierGatedSummaryResponse readVerifierGatedSummaryResponse(Map<String, Object> verifierGatedSummary) {
        if (verifierGatedSummary == null || verifierGatedSummary.isEmpty()) {
            return emptyVerifierGatedSummaryResponse();
        }
        return new ResearchVerifierGatedSummaryResponse(
                intValue(verifierGatedSummary.get("blocked_row_count")),
                intValue(verifierGatedSummary.get("guardrailed_row_count")),
                intValue(verifierGatedSummary.get("recovery_targeted_blocked_row_count")),
                intValue(verifierGatedSummary.get("uncovered_blocked_row_count")),
                intValue(verifierGatedSummary.get("requirement_partial_blocked_row_count")),
                extractListOfMaps(verifierGatedSummary.get("blocked_row_samples")),
                extractListOfMaps(verifierGatedSummary.get("guardrailed_row_samples")),
                extractListOfMaps(verifierGatedSummary.get("need_more_evidence_row_samples"))
        );
    }

    private ResearchVerifierGatedSummaryResponse emptyVerifierGatedSummaryResponse() {
        return new ResearchVerifierGatedSummaryResponse(
                0,
                0,
                0,
                0,
                0,
                List.of(),
                List.of(),
                List.of()
        );
    }

    private Map<String, Object> recoveryTargetsMap(ResearchRecoveryTargetsResponse recoveryTargets) {
        if (recoveryTargets == null) {
            return Map.of();
        }
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("requirement_ids", recoveryTargets.requirementIds());
        value.put("requirement_types", recoveryTargets.requirementTypes());
        value.put("requirement_labels", recoveryTargets.requirementLabels());
        value.put("target_columns", recoveryTargets.targetColumns());
        value.put("target_queries", recoveryTargets.targetQueries());
        value.put("target_sources", recoveryTargets.targetSources());
        value.put("requirement_count", recoveryTargets.requirementCount());
        value.put("query_count", recoveryTargets.queryCount());
        value.put("source_count", recoveryTargets.sourceCount());
        value.put("column_count", recoveryTargets.columnCount());
        return value;
    }

    private boolean isCounterfactualRow(Map<String, Object> row) {
        String rowStatus = stringValue(row.get("row_status"));
        String verificationStatus = stringValue(row.get("verification_status"));
        return "CONFLICTED".equals(rowStatus)
                || "COUNTERFACTUAL_REQUIRED".equals(verificationStatus)
                || "COUNTERFACTUAL_RECHECK".equals(verificationStatus);
    }

    private boolean isActiveBranchStatus(String branchStatus) {
        return "ACTIVE_BRANCH".equals(branchStatus) || "ACTIVE".equals(branchStatus);
    }

    private Map<String, Object> castMap(Map<?, ?> mapValue) {
        LinkedHashMap<String, Object> casted = new LinkedHashMap<>();
        mapValue.forEach((key, value) -> casted.put(String.valueOf(key), value));
        return casted;
    }

    private List<Map<String, Object>> mergeDecisionRecords(
            List<Map<String, Object>> localDecisionRecords,
            List<Map<String, Object>> globalDecisionRecords
    ) {
        List<Map<String, Object>> merged = new ArrayList<>(localDecisionRecords);
        merged.addAll(globalDecisionRecords);
        return merged;
    }

    private String resolveDecisionBranchId(Map<String, Object> decision) {
        String explicitBranchId = stringValue(decision.get("branch_id"));
        if (!explicitBranchId.isBlank()) {
            return explicitBranchId;
        }
        String scope = stringValue(decision.get("decision_scope"));
        String targetId = stringValue(decision.get("target_id"));
        if ("BRANCH".equalsIgnoreCase(scope) && !targetId.isBlank()) {
            return targetId;
        }
        return "";
    }

    private List<Map<String, Object>> loadPersistedBranches(String researchRunId) {
        return jdbcTemplate.query("""
                select branch_key, parent_branch_id, branch_reason, branch_status,
                       hypothesis_summary, target_evidence_ids_json, created_round
                from research_branch
                where research_run_id = ?
                order by created_at asc, id asc
                """, (rs, rowNum) -> {
            LinkedHashMap<String, Object> branch = new LinkedHashMap<>();
            branch.put("branch_id", rs.getString("branch_key"));
            branch.put("parent_branch_id", blankIfNull(rs.getString("parent_branch_id")));
            branch.put("branch_reason", rs.getString("branch_reason"));
            branch.put("status", rs.getString("branch_status"));
            branch.put("hypothesis_summary", blankIfNull(rs.getString("hypothesis_summary")));
            branch.put("target_evidence_ids", readStringList(rs.getString("target_evidence_ids_json")));
            branch.put("created_round", rs.getInt("created_round"));
            return branch;
        }, researchRunId);
    }

    private List<Map<String, Object>> loadPersistedRows(String researchRunId) {
        return jdbcTemplate.query("""
                select rr.row_key, rb.branch_key, rr.source_id, rr.source_title, rr.search_query, rr.read_focus,
                       coalesce(s.generated_by, '') as generated_by,
                       coalesce(s.generated_ref_id, '') as generated_ref_id,
                       evidence_id, row_status, relation_type, support_score, conflict_score,
                       support_level, verification_status, verifier_note, repair_hint
                from research_row rr
                left join research_branch rb on rb.id = rr.branch_id
                left join source s on s.id = rr.source_id
                where rr.research_run_id = ?
                order by rr.created_at asc, rr.id asc
                """, (rs, rowNum) -> {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("row_id", rs.getString("row_key"));
            row.put("branch_id", blankIfNull(rs.getString("branch_key")));
            row.put("source_id", blankIfNull(rs.getString("source_id")));
            row.put("source_title", blankIfNull(rs.getString("source_title")));
            row.put("generated_by", blankIfNull(rs.getString("generated_by")));
            row.put("generated_ref_id", blankIfNull(rs.getString("generated_ref_id")));
            row.put("search_query", blankIfNull(rs.getString("search_query")));
            row.put("read_focus", blankIfNull(rs.getString("read_focus")));
            row.put("evidence_id", blankIfNull(rs.getString("evidence_id")));
            row.put("row_status", rs.getString("row_status"));
            row.put("relation_type", blankIfNull(rs.getString("relation_type")));
            row.put("support_score", rs.getBigDecimal("support_score"));
            row.put("conflict_score", rs.getBigDecimal("conflict_score"));
            row.put("support_level", blankIfNull(rs.getString("support_level")));
            row.put("verification_status", blankIfNull(rs.getString("verification_status")));
            row.put("verifier_note", blankIfNull(rs.getString("verifier_note")));
            row.put("repair_hint", blankIfNull(rs.getString("repair_hint")));
            return row;
        }, researchRunId);
    }

    private List<Map<String, Object>> loadPersistedCells(String researchRunId) {
        return jdbcTemplate.query("""
                select rc.cell_key, rr.row_key, rb.branch_key, rc.column_key, rc.candidate_value,
                       rc.cell_status, rc.confidence_score, rc.evidence_refs_json,
                       last_verifier_decision, repair_count
                from research_cell rc
                join research_row rr on rr.id = rc.research_row_id
                left join research_branch rb on rb.id = rc.branch_id
                where rc.research_run_id = ?
                order by rc.created_at asc, rc.id asc
                """, (rs, rowNum) -> {
            LinkedHashMap<String, Object> cell = new LinkedHashMap<>();
            cell.put("cell_id", rs.getString("cell_key"));
            cell.put("row_id", rs.getString("row_key"));
            cell.put("branch_id", blankIfNull(rs.getString("branch_key")));
            cell.put("column_key", rs.getString("column_key"));
            cell.put("candidate_value", blankIfNull(rs.getString("candidate_value")));
            cell.put("status", rs.getString("cell_status"));
            cell.put("confidence", rs.getBigDecimal("confidence_score"));
            cell.put("evidence_refs", readStringList(rs.getString("evidence_refs_json")));
            cell.put("last_verifier_decision", blankIfNull(rs.getString("last_verifier_decision")));
            cell.put("repair_count", rs.getInt("repair_count"));
            return cell;
        }, researchRunId);
    }

    private List<Map<String, Object>> loadPersistedVerifierDecisions(
            String researchRunId,
            List<Map<String, Object>> sourceEvidence
    ) {
        Map<String, Map<String, Object>> sourceEvidenceById = buildSourceEvidenceById(sourceEvidence);
        return jdbcTemplate.query("""
                select rvd.id, rb.branch_key, rvd.decision_scope, rvd.decision_type, rvd.reason_code,
                       rvd.target_id, rvd.evidence_ids_json, rvd.action_text, rvd.decision_status, rvd.notes_json
                from research_verifier_decision rvd
                left join research_branch rb on rb.id = rvd.branch_id
                where rvd.research_run_id = ?
                order by rvd.created_at asc, rvd.id asc
                """, (rs, rowNum) -> {
            LinkedHashMap<String, Object> decision = new LinkedHashMap<>();
            List<String> evidenceIds = readStringList(rs.getString("evidence_ids_json"));
            decision.put("decision_id", rs.getString("id"));
            decision.put("branch_id", blankIfNull(rs.getString("branch_key")));
            decision.put("decision_scope", rs.getString("decision_scope"));
            decision.put("decision_type", rs.getString("decision_type"));
            decision.put("reason_code", rs.getString("reason_code"));
            decision.put("target_id", blankIfNull(rs.getString("target_id")));
            decision.put("evidence_ids", evidenceIds);
            decision.put("action", blankIfNull(rs.getString("action_text")));
            decision.put("status", blankIfNull(rs.getString("decision_status")));
            decision.put("notes", readStringList(rs.getString("notes_json")));
            List<Map<String, Object>> sourceSamples = buildEvidenceSourceSamples(evidenceIds, sourceEvidenceById, 2);
            decision.put("source_samples", sourceSamples);
            decision.put("source_sample_count", sourceSamples.size());
            return decision;
        }, researchRunId);
    }

    private Map<String, Map<String, Object>> buildSourceEvidenceById(List<Map<String, Object>> sourceEvidence) {
        if (sourceEvidence == null || sourceEvidence.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Map<String, Object>> evidenceById = new LinkedHashMap<>();
        for (Map<String, Object> evidence : sourceEvidence) {
            String evidenceId = stringValue(evidence.get("evidence_id"));
            if (!evidenceId.isBlank()) {
                evidenceById.put(evidenceId, evidence);
            }
        }
        return evidenceById;
    }

    private List<Map<String, Object>> buildEvidenceSourceSamples(
            List<String> evidenceIds,
            Map<String, Map<String, Object>> sourceEvidenceById,
            int limit
    ) {
        if (evidenceIds == null || evidenceIds.isEmpty() || sourceEvidenceById.isEmpty() || limit <= 0) {
            return List.of();
        }
        LinkedHashMap<String, Map<String, Object>> orderedSamples = new LinkedHashMap<>();
        for (String evidenceId : evidenceIds) {
            String normalizedEvidenceId = blankToNull(evidenceId);
            if (normalizedEvidenceId == null || orderedSamples.size() >= limit) {
                continue;
            }
            Map<String, Object> evidence = sourceEvidenceById.get(normalizedEvidenceId);
            if (evidence == null) {
                continue;
            }
            String sourceKey = defaultIfBlank(
                    stringValue(evidence.get("source_id")),
                    "evidence:" + normalizedEvidenceId
            );
            if (orderedSamples.containsKey(sourceKey)) {
                continue;
            }
            LinkedHashMap<String, Object> sample = new LinkedHashMap<>();
            sample.put("evidence_id", normalizedEvidenceId);
            sample.put("source_id", blankIfNull(stringValue(evidence.get("source_id"))));
            sample.put("source_title", blankIfNull(stringValue(evidence.get("source_title"))));
            sample.put("generated_by", blankIfNull(stringValue(evidence.get("generated_by"))));
            sample.put("generated_ref_id", blankIfNull(stringValue(evidence.get("generated_ref_id"))));
            sample.put("search_query", blankIfNull(stringValue(evidence.get("search_query"))));
            sample.put("read_focus", blankIfNull(stringValue(evidence.get("read_focus"))));
            sample.put("relation_type", blankIfNull(stringValue(evidence.get("relation_type"))));
            orderedSamples.put(sourceKey, sample);
        }
        return new ArrayList<>(orderedSamples.values());
    }

    private void enrichLoopRoundsWithSourceSamples(
            List<Map<String, Object>> loopRounds,
            List<Map<String, Object>> sourceEvidence
    ) {
        if (loopRounds == null || loopRounds.isEmpty() || sourceEvidence == null || sourceEvidence.isEmpty()) {
            return;
        }
        Map<String, Map<String, Object>> sourceEvidenceById = buildSourceEvidenceById(sourceEvidence);
        for (Map<String, Object> loopRound : loopRounds) {
            List<Map<String, Object>> sourceSamples = buildLoopRoundSourceSamples(loopRound, sourceEvidenceById, sourceEvidence, 2);
            loopRound.put("source_samples", sourceSamples);
            loopRound.put("source_sample_count", sourceSamples.size());
        }
    }

    private void enrichPayloadLoopRoundsWithSourceSamples(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return;
        }
        if (!(payload.get("loop_rounds") instanceof List<?> rawLoopRounds) || rawLoopRounds.isEmpty()) {
            return;
        }
        List<Map<String, Object>> evidenceRecords = buildLoopRoundPayloadEvidenceRecords(payload);
        if (evidenceRecords.isEmpty()) {
            return;
        }
        Map<String, Map<String, Object>> sourceEvidenceById = buildSourceEvidenceById(evidenceRecords);
        for (Object item : rawLoopRounds) {
            if (!(item instanceof Map<?, ?> rawLoopRound)) {
                continue;
            }
            Map<String, Object> loopRound = castMap(rawLoopRound);
            List<Map<String, Object>> sourceSamples = buildLoopRoundSourceSamples(loopRound, sourceEvidenceById, evidenceRecords, 2);
            @SuppressWarnings("unchecked")
            Map<Object, Object> mutableLoopRound = (Map<Object, Object>) rawLoopRound;
            mutableLoopRound.put("source_samples", sourceSamples);
            mutableLoopRound.put("source_sample_count", sourceSamples.size());
        }
    }

    private List<Map<String, Object>> buildLoopRoundPayloadEvidenceRecords(Map<String, Object> payload) {
        List<Map<String, Object>> evidenceRecords = new ArrayList<>();
        for (Map<String, Object> evidenceCard : extractListOfMaps(payload.get("evidence_cards"))) {
            LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("evidence_id", blankIfNull(stringValue(evidenceCard.get("evidence_id"))));
            normalized.put("source_id", blankIfNull(stringValue(evidenceCard.get("source_id"))));
            normalized.put("source_title", blankIfNull(stringValue(evidenceCard.get("source_title"))));
            normalized.put("generated_by", blankIfNull(stringValue(evidenceCard.get("generated_by"))));
            normalized.put("generated_ref_id", blankIfNull(stringValue(evidenceCard.get("generated_ref_id"))));
            normalized.put("search_query", blankIfNull(stringValue(firstNonNull(
                    evidenceCard.get("search_query"),
                    evidenceCard.get("query")
            ))));
            normalized.put("read_focus", blankIfNull(stringValue(evidenceCard.get("read_focus"))));
            normalized.put("relation_type", blankIfNull(stringValue(evidenceCard.get("relation_type"))));
            evidenceRecords.add(normalized);
        }
        for (Map<String, Object> readWindow : extractListOfMaps(payload.get("read_windows"))) {
            LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("evidence_id", blankIfNull(stringValue(readWindow.get("evidence_id"))));
            normalized.put("source_id", blankIfNull(stringValue(readWindow.get("source_id"))));
            normalized.put("source_title", blankIfNull(stringValue(readWindow.get("source_title"))));
            normalized.put("generated_by", blankIfNull(stringValue(readWindow.get("generated_by"))));
            normalized.put("generated_ref_id", blankIfNull(stringValue(readWindow.get("generated_ref_id"))));
            normalized.put("search_query", blankIfNull(stringValue(firstNonNull(
                    readWindow.get("search_query"),
                    readWindow.get("query")
            ))));
            normalized.put("read_focus", blankIfNull(stringValue(readWindow.get("read_focus"))));
            normalized.put("relation_type", blankIfNull(stringValue(readWindow.get("relation_type"))));
            evidenceRecords.add(normalized);
        }
        return evidenceRecords;
    }

    private List<Map<String, Object>> buildLoopRoundSourceSamples(
            Map<String, Object> loopRound,
            Map<String, Map<String, Object>> sourceEvidenceById,
            List<Map<String, Object>> sourceEvidence,
            int limit
    ) {
        if (loopRound == null || limit <= 0) {
            return List.of();
        }
        List<String> evidenceIds = new ArrayList<>();
        evidenceIds.addAll(extractStringList(loopRound.get("evidence_ids")));
        evidenceIds.addAll(extractStringList(loopRound.get("target_evidence_ids")));
        List<Map<String, Object>> directSamples = buildEvidenceSourceSamples(evidenceIds, sourceEvidenceById, limit);
        if (!directSamples.isEmpty()) {
            return directSamples;
        }

        LinkedHashSet<String> queries = new LinkedHashSet<>(extractStringList(loopRound.get("search_queries")));
        queries.addAll(extractStringList(loopRound.get("queries")));
        String query = blankToNull(stringValue(firstNonNull(loopRound.get("search_query"), loopRound.get("query"))));
        if (query != null) {
            queries.add(query);
        }
        if (queries.isEmpty()) {
            return List.of();
        }

        LinkedHashMap<String, Map<String, Object>> orderedSamples = new LinkedHashMap<>();
        for (String candidateQuery : queries) {
            for (Map<String, Object> evidence : sourceEvidence) {
                String evidenceQuery = blankToNull(stringValue(evidence.get("search_query")));
                if (evidenceQuery == null || !evidenceQuery.equals(candidateQuery)) {
                    continue;
                }
                String evidenceId = blankToNull(stringValue(evidence.get("evidence_id")));
                String sampleKey = defaultIfBlank(
                        stringValue(evidence.get("source_id")),
                        evidenceId == null ? candidateQuery : evidenceId
                );
                if (orderedSamples.containsKey(sampleKey)) {
                    continue;
                }
                LinkedHashMap<String, Object> sample = new LinkedHashMap<>();
                sample.put("evidence_id", evidenceId);
                sample.put("source_id", blankIfNull(stringValue(evidence.get("source_id"))));
                sample.put("source_title", blankIfNull(stringValue(evidence.get("source_title"))));
                sample.put("generated_by", blankIfNull(stringValue(evidence.get("generated_by"))));
                sample.put("generated_ref_id", blankIfNull(stringValue(evidence.get("generated_ref_id"))));
                sample.put("search_query", evidenceQuery);
                sample.put("read_focus", blankIfNull(stringValue(evidence.get("read_focus"))));
                sample.put("relation_type", blankIfNull(stringValue(evidence.get("relation_type"))));
                orderedSamples.put(sampleKey, sample);
                if (orderedSamples.size() >= limit) {
                    return new ArrayList<>(orderedSamples.values());
                }
            }
        }
        return new ArrayList<>(orderedSamples.values());
    }

    private List<Map<String, Object>> loadPersistedCheckpoints(String researchRunId) {
        return jdbcTemplate.query("""
                select checkpoint_no, snapshot_type, object_key, payload_sha256, content_size,
                       active_branch_key, final_loop_decision, summary_json, created_at
                from research_execution_checkpoint
                where research_run_id = ?
                order by checkpoint_no asc, created_at asc, id asc
                """, (rs, rowNum) -> {
            LinkedHashMap<String, Object> checkpoint = new LinkedHashMap<>();
            Map<String, Object> summary = readPayloadMap(rs.getString("summary_json"));
            enrichResearchSourceProvenance(summary, loadSourceOrigins(collectSourceIds(summary)));
            Map<String, Object> loopDecision = castMapOrEmpty(summary.get("loop_decision"));
            Map<String, Object> localVerifier = castMapOrEmpty(summary.get("local_verifier"));
            Map<String, Object> globalVerifier = castMapOrEmpty(summary.get("global_verifier"));
            Map<String, Object> stateLedger = castMapOrEmpty(summary.get("state_ledger"));
            Map<String, Object> researchIntentAlignment = castMapOrEmpty(summary.get("research_intent_alignment"));
            Map<String, Object> counterfactualSummary = castMapOrEmpty(summary.get("counterfactual_summary"));
            Map<String, Object> recoveryTargets = castMapOrEmpty(summary.get("recovery_targets"));
            checkpoint.put("checkpoint_no", rs.getInt("checkpoint_no"));
            checkpoint.put("snapshot_type", rs.getString("snapshot_type"));
            checkpoint.put("object_key", rs.getString("object_key"));
            checkpoint.put("payload_sha256", rs.getString("payload_sha256"));
            checkpoint.put("content_size", rs.getLong("content_size"));
            checkpoint.put("active_branch_id", blankIfNull(rs.getString("active_branch_key")));
            checkpoint.put("final_loop_decision", blankIfNull(rs.getString("final_loop_decision")));
            checkpoint.put("summary", summary);
            checkpoint.put("loop_decision", loopDecision);
            checkpoint.put("local_verifier", localVerifier);
            checkpoint.put("global_verifier", globalVerifier);
            checkpoint.put("state_ledger", stateLedger);
            checkpoint.put("research_intent_alignment", researchIntentAlignment.isEmpty() ? null : researchIntentAlignment);
            checkpoint.put("counterfactual_summary", counterfactualSummary.isEmpty() ? null : counterfactualSummary);
            checkpoint.put("recovery_targets", recoveryTargets.isEmpty() ? null : recoveryTargets);
            checkpoint.put("local_verifier_status", blankIfNull(stringValue(localVerifier.get("status"))));
            checkpoint.put("global_verifier_decision", blankIfNull(stringValue(globalVerifier.get("decision"))));
            checkpoint.put("research_intent_alignment_status", blankIfNull(stringValue(summary.get("research_intent_alignment_status"))));
            checkpoint.put("research_intent_alignment_reason", blankIfNull(stringValue(summary.get("research_intent_alignment_reason"))));
            checkpoint.put("intent_constraint_count", intValue(summary.get("intent_constraint_count")));
            checkpoint.put("intent_satisfied_constraint_count", intValue(summary.get("intent_satisfied_constraint_count")));
            checkpoint.put("intent_requirement_count", intValue(summary.get("intent_requirement_count")));
            checkpoint.put("intent_satisfied_requirement_count", intValue(summary.get("intent_satisfied_requirement_count")));
            checkpoint.put("intent_pending_requirement_count", intValue(summary.get("intent_pending_requirement_count")));
            checkpoint.put("missing_intent_requirements", extractStringList(summary.get("missing_intent_requirements")));
            checkpoint.put("verified_row_count", intValue(stateLedger.get("verified_row_count")));
            checkpoint.put("conflicted_row_count", intValue(stateLedger.get("conflicted_row_count")));
            checkpoint.put("created_at", toInstant(rs.getTimestamp("created_at")));
            return checkpoint;
        }, researchRunId);
    }

    private Map<String, Object> buildCheckpointSummary(
            Map<String, Object> checkpointCandidate,
            Map<String, Object> checkpointPayload,
            List<Map<String, Object>> evidenceCards,
            List<Map<String, Object>> readWindows
    ) {
        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        Map<String, Object> loopDecision = castMapOrEmpty(checkpointPayload.get("loop_decision"));
        Map<String, Object> localVerifier = castMapOrEmpty(checkpointPayload.get("local_verifier"));
        Map<String, Object> globalVerifier = castMapOrEmpty(checkpointPayload.get("global_verifier"));
        Map<String, Object> stateLedger = castMapOrEmpty(checkpointPayload.get("state_ledger"));
        summary.put("checkpoint_no", intValue(firstNonNull(checkpointCandidate.get("checkpoint_no"), 1)));
        summary.put("snapshot_type", defaultIfBlank(
                stringValue(checkpointCandidate.get("snapshot_type")),
                "RESEARCH_LOOP_CHECKPOINT"
        ));
        if (!loopDecision.isEmpty()) {
            summary.put("loop_decision", loopDecision);
        }
        if (!localVerifier.isEmpty()) {
            summary.put("local_verifier", localVerifier);
        }
        if (!globalVerifier.isEmpty()) {
            summary.put("global_verifier", globalVerifier);
        }
        summary.put("state_ledger", buildCheckpointStateLedgerSummary(stateLedger, evidenceCards, readWindows));
        Map<String, Object> researchIntentAlignment = rawResearchIntentAlignment(checkpointPayload);
        if (!researchIntentAlignment.isEmpty()) {
            summary.put("research_intent_alignment", researchIntentAlignment);
            summary.put("research_intent_alignment_status", blankIfNull(stringValue(researchIntentAlignment.get("status"))));
            summary.put("research_intent_alignment_reason", blankIfNull(stringValue(researchIntentAlignment.get("reason_code"))));
            summary.put("intent_constraint_count", intValue(researchIntentAlignment.get("total_constraint_count")));
            summary.put("intent_satisfied_constraint_count", intValue(researchIntentAlignment.get("satisfied_constraint_count")));
        }
        Map<String, Object> intentCompletionContract = rawIntentCompletionContract(checkpointPayload);
        if (!intentCompletionContract.isEmpty()) {
            summary.put("intent_completion_contract", intentCompletionContract);
            summary.put("intent_requirement_count", intValue(intentCompletionContract.get("total_requirement_count")));
            summary.put("intent_satisfied_requirement_count", intValue(intentCompletionContract.get("satisfied_requirement_count")));
            summary.put("intent_pending_requirement_count", intValue(intentCompletionContract.get("pending_requirement_count")));
            summary.put("missing_intent_requirements", extractStringList(intentCompletionContract.get("missing_requirement_labels")));
        }
        Map<String, Object> counterfactualSummary = rawCounterfactualSummary(checkpointPayload);
        if (!counterfactualSummary.isEmpty()) {
            summary.put("counterfactual_summary", counterfactualSummary);
        }
        Map<String, Object> recoveryTargets = rawRecoveryTargets(checkpointPayload);
        if (!recoveryTargets.isEmpty()) {
            summary.put("recovery_targets", recoveryTargets);
        }
        Map<String, Object> verifierGatedSummary = buildVerifierGatedSummary(
                extractListOfMaps(stateLedger.get("rows")),
                recoveryTargets
        );
        if (!verifierGatedSummary.isEmpty()) {
            summary.put("verifier_gated_summary", verifierGatedSummary);
        }
        enrichResearchSourceProvenance(summary, loadSourceOrigins(collectSourceIds(summary)));
        return summary;
    }

    private Map<String, Object> buildCheckpointStateLedgerSummary(
            Map<String, Object> stateLedger,
            List<Map<String, Object>> evidenceCards,
            List<Map<String, Object>> readWindows
    ) {
        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        List<Map<String, Object>> rows = extractListOfMaps(stateLedger.get("rows"));
        int verifiedRowCount = intValue(stateLedger.get("verified_row_count"));
        int conflictedRowCount = intValue(stateLedger.get("conflicted_row_count"));
        if (verifiedRowCount <= 0 && !rows.isEmpty()) {
            verifiedRowCount = (int) rows.stream()
                    .filter(row -> "VERIFIED".equals(stringValue(row.get("row_status"))))
                    .count();
        }
        if (conflictedRowCount <= 0 && !rows.isEmpty()) {
            conflictedRowCount = (int) rows.stream()
                    .filter(row -> "CONFLICTED".equals(stringValue(row.get("row_status"))))
                    .count();
        }
        summary.put("active_branch_id", blankIfNull(stringValue(stateLedger.get("active_branch_id"))));
        summary.put("row_count", rows.size());
        summary.put("column_count", extractListOfMaps(stateLedger.get("columns")).size());
        summary.put("branch_count", extractListOfMaps(stateLedger.get("branches")).size());
        summary.put("cell_count", extractListOfMaps(stateLedger.get("cells")).size());
        summary.put("verified_row_count", verifiedRowCount);
        summary.put("conflicted_row_count", conflictedRowCount);
        summary.put("requirement_ready_row_count", intValue(stateLedger.get("requirement_ready_row_count")));
        summary.put("requirement_partial_row_count", intValue(stateLedger.get("requirement_partial_row_count")));
        summary.put("read_window_count", readWindows.size());
        summary.put("evidence_card_count", evidenceCards.size());
        summary.put("verified_row_samples", buildCheckpointRowSamples(rows, "VERIFIED", 2));
        summary.put("conflicted_row_samples", buildCheckpointRowSamples(rows, "CONFLICTED", 2));
        summary.put("evidence_card_samples", buildCheckpointEvidenceSamples(evidenceCards, 2));
        summary.put("read_window_samples", buildCheckpointReadWindowSamples(readWindows, 2));
        summary.putAll(buildVerifierGatedSummary(rows, castMapOrEmpty(stateLedger.get("recovery_targets"))));
        Map<String, Object> intentCompletionContract = castMapOrEmpty(stateLedger.get("intent_completion_contract"));
        if (!intentCompletionContract.isEmpty()) {
            summary.put("intent_completion_contract", intentCompletionContract);
            summary.put("intent_requirement_count", intValue(intentCompletionContract.get("total_requirement_count")));
            summary.put("intent_satisfied_requirement_count", intValue(intentCompletionContract.get("satisfied_requirement_count")));
            summary.put("intent_pending_requirement_count", intValue(intentCompletionContract.get("pending_requirement_count")));
            summary.put("missing_intent_requirements", extractStringList(intentCompletionContract.get("missing_requirement_labels")));
        }
        return summary;
    }

    private Map<String, Object> buildVerifierGatedSummary(
            List<Map<String, Object>> rows,
            Map<String, Object> recoveryTargets
    ) {
        if (rows == null || rows.isEmpty()) {
            return Map.of(
                    "blocked_row_count", 0,
                    "guardrailed_row_count", 0,
                    "recovery_targeted_blocked_row_count", 0,
                    "uncovered_blocked_row_count", 0,
                    "requirement_partial_blocked_row_count", 0,
                    "blocked_row_samples", List.of(),
                    "guardrailed_row_samples", List.of(),
                    "need_more_evidence_row_samples", List.of()
            );
        }
        List<Map<String, Object>> blockedRows = rows.stream()
                .filter(this::isVerifierGatedRow)
                .toList();
        int targetedBlockedRowCount = (int) blockedRows.stream()
                .filter(row -> matchesRecoveryTargets(row, recoveryTargets))
                .count();
        int uncoveredBlockedRowCount = Math.max(blockedRows.size() - targetedBlockedRowCount, 0);
        int requirementPartialBlockedRowCount = (int) blockedRows.stream()
                .filter(row -> "PARTIAL".equals(stringValue(row.get("requirement_completion_status"))))
                .count();
        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("blocked_row_count", blockedRows.size());
        summary.put("guardrailed_row_count", (int) blockedRows.stream()
                .filter(row -> !isConflictedRow(row))
                .count());
        summary.put("recovery_targeted_blocked_row_count", targetedBlockedRowCount);
        summary.put("uncovered_blocked_row_count", uncoveredBlockedRowCount);
        summary.put("requirement_partial_blocked_row_count", requirementPartialBlockedRowCount);
        summary.put("blocked_row_samples", buildCheckpointRowsByPredicate(rows, this::isVerifierGatedRow, 2));
        summary.put("guardrailed_row_samples", buildCheckpointRowsByPredicate(
                rows,
                row -> isVerifierGatedRow(row) && !isConflictedRow(row),
                2
        ));
        summary.put("need_more_evidence_row_samples", buildCheckpointRowsByPredicate(
                rows,
                row -> "NEED_MORE_EVIDENCE".equals(stringValue(row.get("row_status"))),
                2
        ));
        return summary;
    }

    private List<Map<String, Object>> buildCheckpointRowSamples(
            List<Map<String, Object>> rows,
            String rowStatus,
            int limit
    ) {
        return buildCheckpointRowsByPredicate(rows, row -> rowStatus.equals(stringValue(row.get("row_status"))), limit);
    }

    private List<Map<String, Object>> buildCheckpointRowsByPredicate(
            List<Map<String, Object>> rows,
            java.util.function.Predicate<Map<String, Object>> predicate,
            int limit
    ) {
        if (rows == null || rows.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<Map<String, Object>> samples = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (!predicate.test(row)) {
                continue;
            }
            samples.add(buildCheckpointRowSample(row));
            if (samples.size() >= limit) {
                break;
            }
        }
        return samples;
    }

    private Map<String, Object> buildCheckpointRowSample(Map<String, Object> row) {
        LinkedHashMap<String, Object> sample = new LinkedHashMap<>();
        sample.put("row_id", blankIfNull(stringValue(row.get("row_id"))));
        sample.put("source_id", blankIfNull(stringValue(row.get("source_id"))));
        sample.put("source_title", blankIfNull(stringValue(row.get("source_title"))));
        sample.put("evidence_id", blankIfNull(stringValue(row.get("evidence_id"))));
        sample.put("search_query", blankIfNull(stringValue(row.get("search_query"))));
        sample.put("claim_text", blankIfNull(stringValue(row.get("claim_text"))));
        sample.put("row_status", blankIfNull(stringValue(row.get("row_status"))));
        sample.put("support_level", blankIfNull(stringValue(row.get("support_level"))));
        sample.put("verification_status", blankIfNull(stringValue(row.get("verification_status"))));
        sample.put("requirement_completion_status", blankIfNull(stringValue(row.get("requirement_completion_status"))));
        sample.put("repair_hint", blankIfNull(stringValue(row.get("repair_hint"))));
        sample.put("branch_id", blankIfNull(stringValue(row.get("branch_id"))));
        sample.put("matched_requirement_ids", extractStringList(row.get("matched_requirement_ids")));
        sample.put("ready_requirement_ids", extractStringList(row.get("ready_requirement_ids")));
        sample.put("missing_columns", extractStringList(row.get("missing_columns")));
        return sample;
    }

    private boolean isVerifierGatedRow(Map<String, Object> row) {
        return !isVerifiedRow(row);
    }

    private boolean isVerifiedRow(Map<String, Object> row) {
        return "VERIFIED".equals(stringValue(row.get("row_status")));
    }

    private boolean isConflictedRow(Map<String, Object> row) {
        return "CONFLICTED".equals(stringValue(row.get("row_status")));
    }

    private boolean matchesRecoveryTargets(Map<String, Object> row, Map<String, Object> recoveryTargets) {
        if (recoveryTargets == null || recoveryTargets.isEmpty()) {
            return false;
        }
        List<String> targetRequirementIds = extractStringList(recoveryTargets.get("requirement_ids"));
        List<String> targetColumns = extractStringList(recoveryTargets.get("target_columns"));
        List<String> targetQueries = extractStringList(recoveryTargets.get("target_queries"));
        List<String> targetSources = extractStringList(recoveryTargets.get("target_sources"));
        List<String> rowRequirementIds = new ArrayList<>(extractStringList(row.get("matched_requirement_ids")));
        rowRequirementIds.addAll(extractStringList(row.get("ready_requirement_ids")));
        if (rowRequirementIds.stream().anyMatch(targetRequirementIds::contains)) {
            return true;
        }
        if (extractStringList(row.get("missing_columns")).stream().anyMatch(targetColumns::contains)) {
            return true;
        }
        String searchQuery = blankToNull(stringValue(row.get("search_query")));
        if (matchesTargetText(searchQuery, targetQueries)) {
            return true;
        }
        String readFocus = blankToNull(stringValue(row.get("read_focus")));
        if (matchesTargetText(readFocus, targetQueries)) {
            return true;
        }
        String sourceTitle = blankToNull(stringValue(row.get("source_title")));
        return matchesTargetText(sourceTitle, targetSources);
    }

    private boolean matchesTargetText(String value, List<String> targets) {
        if (value == null || value.isBlank() || targets == null || targets.isEmpty()) {
            return false;
        }
        for (String target : targets) {
            if (target == null || target.isBlank()) {
                continue;
            }
            if (target.contains(value) || value.contains(target)) {
                return true;
            }
        }
        return false;
    }

    private List<Map<String, Object>> buildCheckpointEvidenceSamples(
            List<Map<String, Object>> evidenceCards,
            int limit
    ) {
        if (evidenceCards == null || evidenceCards.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<Map<String, Object>> samples = new ArrayList<>();
        for (Map<String, Object> evidenceCard : evidenceCards) {
            LinkedHashMap<String, Object> sample = new LinkedHashMap<>();
            sample.put("evidence_id", blankIfNull(stringValue(evidenceCard.get("evidence_id"))));
            sample.put("source_id", blankIfNull(stringValue(evidenceCard.get("source_id"))));
            sample.put("source_title", blankIfNull(stringValue(evidenceCard.get("source_title"))));
            sample.put("window_id", blankIfNull(stringValue(evidenceCard.get("window_id"))));
            sample.put("claim_text", blankIfNull(stringValue(evidenceCard.get("claim_text"))));
            sample.put("relation_type", blankIfNull(stringValue(evidenceCard.get("relation_type"))));
            samples.add(sample);
            if (samples.size() >= limit) {
                break;
            }
        }
        return samples;
    }

    private List<Map<String, Object>> buildCheckpointReadWindowSamples(
            List<Map<String, Object>> readWindows,
            int limit
    ) {
        if (readWindows == null || readWindows.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<Map<String, Object>> samples = new ArrayList<>();
        for (Map<String, Object> readWindow : readWindows) {
            LinkedHashMap<String, Object> sample = new LinkedHashMap<>();
            sample.put("window_id", blankIfNull(stringValue(readWindow.get("window_id"))));
            sample.put("source_id", blankIfNull(stringValue(readWindow.get("source_id"))));
            sample.put("source_title", blankIfNull(stringValue(readWindow.get("source_title"))));
            sample.put("query", blankIfNull(stringValue(readWindow.get("query"))));
            sample.put("read_focus", blankIfNull(stringValue(readWindow.get("read_focus"))));
            sample.put("retention_reason", blankIfNull(stringValue(readWindow.get("retention_reason"))));
            sample.put("snapshot_status", blankIfNull(stringValue(readWindow.get("snapshot_status"))));
            samples.add(sample);
            if (samples.size() >= limit) {
                break;
            }
        }
        return samples;
    }

    private Map<String, Object> rawRecoveryTargets(Map<String, Object> checkpointPayload) {
        Map<String, Object> reportStructure = castMapOrEmpty(checkpointPayload.get("report_structure"));
        Map<String, Object> closedLoopState = castMapOrEmpty(reportStructure.get("closed_loop_state"));
        Map<String, Object> direct = castMapOrEmpty(firstNonNull(
                closedLoopState.get("recovery_targets"),
                castMapOrEmpty(reportStructure.get("recovery_status")).get("recovery_targets")
        ));
        if (!direct.isEmpty()) {
            return direct;
        }
        Map<String, Object> stopContract = castMapOrEmpty(checkpointPayload.get("stop_contract"));
        if (stopContract.isEmpty()) {
            return Map.of();
        }
        return buildRecoveryTargetsFromStopContract(stopContract);
    }

    private Map<String, Object> rawIntentCompletionContract(Map<String, Object> checkpointPayload) {
        Map<String, Object> stateLedger = castMapOrEmpty(checkpointPayload.get("state_ledger"));
        Map<String, Object> direct = castMapOrEmpty(firstNonNull(
                checkpointPayload.get("intent_completion_contract"),
                stateLedger.get("intent_completion_contract")
        ));
        if (!direct.isEmpty()) {
            return direct;
        }
        return castMapOrEmpty(castMapOrEmpty(
                castMapOrEmpty(checkpointPayload.get("report_structure")).get("intent_completion_contract")
        ));
    }

    private Map<String, Object> rawResearchIntentAlignment(Map<String, Object> checkpointPayload) {
        Map<String, Object> direct = castMapOrEmpty(firstNonNull(
                checkpointPayload.get("research_intent_alignment"),
                castMapOrEmpty(castMapOrEmpty(checkpointPayload.get("report_structure")).get("research_intent_alignment"))
        ));
        if (!direct.isEmpty()) {
            return direct;
        }
        Map<String, Object> globalVerifier = castMapOrEmpty(checkpointPayload.get("global_verifier"));
        Map<String, Object> localVerifier = castMapOrEmpty(checkpointPayload.get("local_verifier"));
        return castMapOrEmpty(firstNonNull(
                globalVerifier.get("research_intent_alignment"),
                localVerifier.get("research_intent_alignment")
        ));
    }

    private ResearchCounterfactualSummaryResponse firstNonNullCounterfactualSummary(
            Map<String, Object> checkpointSummary,
            Map<String, Object> checkpointPayload
    ) {
        ResearchCounterfactualSummaryResponse summary = readCounterfactualSummary(
                castMapOrEmpty(checkpointSummary.get("counterfactual_summary"))
        );
        return summary != null ? summary : buildCounterfactualSummary(checkpointPayload);
    }

    private Map<String, Object> rawCounterfactualSummary(Map<String, Object> checkpointPayload) {
        Map<String, Object> directSummary = castMapOrEmpty(firstNonNull(
                checkpointPayload.get("counterfactual_summary"),
                castMapOrEmpty(castMapOrEmpty(checkpointPayload.get("report_structure")).get("counterfactual_summary"))
        ));
        if (!directSummary.isEmpty()) {
            return directSummary;
        }
        ResearchCounterfactualSummaryResponse derivedSummary = buildCounterfactualSummary(checkpointPayload);
        if (derivedSummary == null) {
            return Map.of();
        }
        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("has_counterfactual_recheck", derivedSummary.hasCounterfactualRecheck());
        summary.put("counterfactual_branch_count", derivedSummary.counterfactualBranchCount());
        summary.put("conflicted_row_count", derivedSummary.conflictedRowCount());
        summary.put("local_verifier_status", blankIfNull(derivedSummary.localVerifierStatus()));
        summary.put("global_verifier_decision", blankIfNull(derivedSummary.globalVerifierDecision()));
        summary.put("recovery_mode", blankIfNull(derivedSummary.recoveryMode()));
        summary.put("counterfactual_branch_ids", derivedSummary.counterfactualBranchIds());
        summary.put("active_counterfactual_branch_ids", derivedSummary.activeCounterfactualBranchIds());
        summary.put("branch_reasons", derivedSummary.branchReasons());
        summary.put("target_evidence_ids", derivedSummary.targetEvidenceIds());
        summary.put("branches", derivedSummary.branches().stream()
                .map(branch -> Map.ofEntries(
                        Map.entry("branch_id", blankIfNull(branch.branchId())),
                        Map.entry("parent_branch_id", blankIfNull(branch.parentBranchId())),
                        Map.entry("branch_reason", blankIfNull(branch.branchReason())),
                        Map.entry("branch_status", blankIfNull(branch.branchStatus())),
                        Map.entry("decision", blankIfNull(branch.decision())),
                        Map.entry("verifier_scope", blankIfNull(branch.verifierScope())),
                        Map.entry("hypothesis_summary", blankIfNull(branch.hypothesisSummary())),
                        Map.entry("target_evidence_ids", branch.targetEvidenceIds())
                ))
                .toList());
        return summary;
    }

    private Map<String, Object> extractRecoveryTargets(List<ResearchTraceResponse> traces) {
        Map<String, Object> reportStructure = extractNestedMap(traces, "REPORT_STRUCTURE", "report_structure");
        if (reportStructure.isEmpty()) {
            Map<String, Object> finalResultPayload = extractNestedMap(traces, "FINAL_REPORT", "result_payload");
            reportStructure = castMapOrEmpty(finalResultPayload.get("report_structure"));
            if (reportStructure.isEmpty()) {
                return buildRecoveryTargetsFromStopContract(castMapOrEmpty(finalResultPayload.get("stop_contract")));
            }
        }
        Map<String, Object> closedLoopState = castMapOrEmpty(reportStructure.get("closed_loop_state"));
        Map<String, Object> direct = castMapOrEmpty(firstNonNull(
                closedLoopState.get("recovery_targets"),
                castMapOrEmpty(reportStructure.get("recovery_status")).get("recovery_targets")
        ));
        if (!direct.isEmpty()) {
            return direct;
        }
        return Map.of();
    }

    private Map<String, Object> buildRecoveryTargetsFromStopContract(Map<String, Object> stopContract) {
        if (stopContract.isEmpty()) {
            return Map.of();
        }
        List<String> requirementIds = extractStringList(stopContract.get("recovery_target_requirement_ids"));
        List<String> requirementTypes = extractStringList(stopContract.get("recovery_target_requirement_types"));
        List<String> requirementLabels = extractStringList(stopContract.get("recovery_target_requirement_labels"));
        List<String> targetColumns = extractStringList(stopContract.get("recovery_target_columns"));
        List<String> targetQueries = extractStringList(stopContract.get("recovery_target_queries"));
        List<String> targetSources = extractStringList(stopContract.get("recovery_target_sources"));
        if (requirementIds.isEmpty()
                && requirementTypes.isEmpty()
                && requirementLabels.isEmpty()
                && targetColumns.isEmpty()
                && targetQueries.isEmpty()
                && targetSources.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> recoveryTargets = new LinkedHashMap<>();
        recoveryTargets.put("requirement_ids", requirementIds);
        recoveryTargets.put("requirement_types", requirementTypes);
        recoveryTargets.put("requirement_labels", requirementLabels);
        recoveryTargets.put("target_columns", targetColumns);
        recoveryTargets.put("target_queries", targetQueries);
        recoveryTargets.put("target_sources", targetSources);
        recoveryTargets.put("requirement_count", requirementIds.size());
        recoveryTargets.put("query_count", targetQueries.size());
        recoveryTargets.put("source_count", targetSources.size());
        recoveryTargets.put("column_count", targetColumns.size());
        return recoveryTargets;
    }

    private void enrichResearchSourceProvenance(Object value, Map<String, SourceOriginRef> sourceOrigins) {
        if (value == null || sourceOrigins.isEmpty()) {
            return;
        }
        if (value instanceof Map<?, ?> mapValue) {
            enrichResearchSourceMap(mapValue, sourceOrigins);
            for (Object nested : new ArrayList<>(mapValue.values())) {
                enrichResearchSourceProvenance(nested, sourceOrigins);
            }
            return;
        }
        if (value instanceof List<?> listValue) {
            for (Object item : listValue) {
                enrichResearchSourceProvenance(item, sourceOrigins);
            }
        }
    }

    private void enrichResearchSourceMap(Map<?, ?> rawMap, Map<String, SourceOriginRef> sourceOrigins) {
        String sourceId = stringValue(rawMap.get("source_id"));
        if (sourceId.isBlank()) {
            return;
        }
        SourceOriginRef origin = sourceOrigins.get(sourceId);
        if (origin == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<Object, Object> mutable = (Map<Object, Object>) rawMap;
        if (stringValue(mutable.get("source_title")).isBlank() && !origin.title().isBlank()) {
            mutable.put("source_title", origin.title());
        }
        if (stringValue(mutable.get("generated_by")).isBlank() && !origin.generatedBy().isBlank()) {
            mutable.put("generated_by", origin.generatedBy());
        }
        if (stringValue(mutable.get("generated_ref_id")).isBlank() && !origin.generatedRefId().isBlank()) {
            mutable.put("generated_ref_id", origin.generatedRefId());
        }
    }

    private List<String> collectSourceIds(Object value) {
        LinkedHashSet<String> sourceIds = new LinkedHashSet<>();
        collectSourceIds(value, sourceIds);
        return new ArrayList<>(sourceIds);
    }

    private void collectSourceIds(Object value, LinkedHashSet<String> sourceIds) {
        if (value instanceof Map<?, ?> mapValue) {
            String sourceId = stringValue(mapValue.get("source_id"));
            if (!sourceId.isBlank()) {
                sourceIds.add(sourceId);
            }
            for (Object nested : mapValue.values()) {
                collectSourceIds(nested, sourceIds);
            }
            return;
        }
        if (value instanceof List<?> listValue) {
            for (Object item : listValue) {
                collectSourceIds(item, sourceIds);
            }
        }
    }

    private Map<String, SourceOriginRef> loadSourceOrigins(List<String> sourceIds) {
        if (sourceIds == null || sourceIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(sourceIds.size(), "?"));
        return jdbcTemplate.query("""
                select id, title,
                       coalesce(generated_by, '') as generated_by,
                       coalesce(generated_ref_id, '') as generated_ref_id
                from source
                where id in (%s)
                """.formatted(placeholders), rs -> {
            LinkedHashMap<String, SourceOriginRef> origins = new LinkedHashMap<>();
            while (rs.next()) {
                origins.put(rs.getString("id"), new SourceOriginRef(
                        blankIfNull(rs.getString("title")),
                        blankIfNull(rs.getString("generated_by")),
                        blankIfNull(rs.getString("generated_ref_id"))
                ));
            }
            return origins;
        }, sourceIds.toArray());
    }

    private List<Map<String, Object>> loadPersistedSourceEvidence(String researchRunId) {
        return jdbcTemplate.query("""
                select se.evidence_key, se.window_id, se.source_id, se.source_title, se.source_url, se.provider, se.adapter,
                       coalesce(s.generated_by, '') as generated_by,
                       coalesce(s.generated_ref_id, '') as generated_ref_id,
                       se.search_query, se.read_focus, se.quote_text, se.claim_text, se.relation_type,
                       se.support_score, se.conflict_score, se.snapshot_status, se.snapshot_key
                from source_evidence se
                left join source s on s.id = se.source_id
                where se.research_run_id = ?
                order by se.created_at asc, se.id asc
                """, (rs, rowNum) -> {
            LinkedHashMap<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("evidence_id", rs.getString("evidence_key"));
            evidence.put("window_id", blankIfNull(rs.getString("window_id")));
            evidence.put("source_id", blankIfNull(rs.getString("source_id")));
            evidence.put("source_title", blankIfNull(rs.getString("source_title")));
            evidence.put("generated_by", blankIfNull(rs.getString("generated_by")));
            evidence.put("generated_ref_id", blankIfNull(rs.getString("generated_ref_id")));
            evidence.put("source_url", blankIfNull(rs.getString("source_url")));
            evidence.put("provider", blankIfNull(rs.getString("provider")));
            evidence.put("adapter", blankIfNull(rs.getString("adapter")));
            evidence.put("search_query", blankIfNull(rs.getString("search_query")));
            evidence.put("read_focus", blankIfNull(rs.getString("read_focus")));
            evidence.put("quote_text", blankIfNull(rs.getString("quote_text")));
            evidence.put("claim_text", blankIfNull(rs.getString("claim_text")));
            evidence.put("relation_type", blankIfNull(rs.getString("relation_type")));
            evidence.put("support_score", rs.getBigDecimal("support_score"));
            evidence.put("conflict_score", rs.getBigDecimal("conflict_score"));
            evidence.put("snapshot_status", blankIfNull(rs.getString("snapshot_status")));
            evidence.put("snapshot_key", blankIfNull(rs.getString("snapshot_key")));
            return evidence;
        }, researchRunId);
    }

    private List<Map<String, Object>> loadPersistedCellEvidence(String researchRunId) {
        return jdbcTemplate.query("""
                select rc.cell_key, rr.row_key, rce.evidence_key, se.source_id, se.source_title, se.source_url,
                       coalesce(s.generated_by, '') as generated_by,
                       coalesce(s.generated_ref_id, '') as generated_ref_id
                from research_cell_evidence rce
                join research_cell rc on rc.id = rce.research_cell_id
                join research_row rr on rr.id = rc.research_row_id
                join source_evidence se on se.id = rce.source_evidence_id
                left join source s on s.id = se.source_id
                where rce.research_run_id = ?
                order by rce.created_at asc, rce.id asc
                """, (rs, rowNum) -> {
            LinkedHashMap<String, Object> cellEvidence = new LinkedHashMap<>();
            cellEvidence.put("cell_id", rs.getString("cell_key"));
            cellEvidence.put("row_id", rs.getString("row_key"));
            cellEvidence.put("evidence_id", rs.getString("evidence_key"));
            cellEvidence.put("source_id", blankIfNull(rs.getString("source_id")));
            cellEvidence.put("source_title", blankIfNull(rs.getString("source_title")));
            cellEvidence.put("generated_by", blankIfNull(rs.getString("generated_by")));
            cellEvidence.put("generated_ref_id", blankIfNull(rs.getString("generated_ref_id")));
            cellEvidence.put("source_url", blankIfNull(rs.getString("source_url")));
            return cellEvidence;
        }, researchRunId);
    }

    private Map<String, Object> mergeStateLedgerSnapshot(
            Map<String, Object> stateLedger,
            List<Map<String, Object>> persistedBranches,
            List<Map<String, Object>> persistedRows,
            List<Map<String, Object>> persistedCells,
            List<Map<String, Object>> persistedVerifierDecisions
    ) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>(stateLedger);
        merged.put("branches", persistedBranches);
        merged.put("rows", persistedRows);
        merged.put("cells", persistedCells);
        merged.put("verifier_decisions", persistedVerifierDecisions);
        if (!persistedBranches.isEmpty() && stringValue(merged.get("active_branch_id")).isBlank()) {
            merged.put("active_branch_id", stringValue(persistedBranches.get(persistedBranches.size() - 1).get("branch_id")));
        }
        merged.put("verified_row_count", persistedRows.stream()
                .filter(row -> "VERIFIED".equals(stringValue(row.get("row_status"))))
                .count());
        merged.put("conflicted_row_count", persistedRows.stream()
                .filter(row -> "CONFLICTED".equals(stringValue(row.get("row_status"))))
                .count());
        return merged;
    }

    private Object firstNonNull(Object first, Object fallback) {
        return first != null ? first : fallback;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        String text = stringValue(value).trim();
        return "true".equalsIgnoreCase(text) || "1".equals(text);
    }

    private Double decimalValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private List<String> extractStringList(Object value) {
        if (value instanceof List<?> listValue) {
            return listValue.stream()
                    .map(String::valueOf)
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text);
        }
        return List.of();
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> listValue && listValue.isEmpty()) {
            return null;
        }
        return Json.write(objectMapper, value);
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private void requireWorkspace(String workspaceId) {
        if (!workspaceService.exists(workspaceId)) {
            throw new BusinessException("WORKSPACE_NOT_FOUND", "工作台不存在");
        }
    }

    private String normalizeToken(String value) {
        return value == null ? "" : value.trim().toUpperCase().replace('-', '_').replace(' ', '_');
    }

    private ResearchIntentResponse normalizeResearchIntent(CreateResearchRunRequest request) {
        return normalizeResearchIntent(new ResearchIntentResponse(
                blankIfNull(blankToNull(request.researchGoal())),
                blankIfNull(blankToNull(request.deliverableFormat())),
                normalizeConstraintList(request.constraints()),
                blankIfNull(blankToNull(request.timeRange())),
                defaultIfBlank(normalizeToken(request.depth()), "STANDARD"),
                defaultIfBlank(normalizeToken(request.researchType()), "AUTO")
        ));
    }

    private ResearchIntentResponse normalizeResearchIntent(ResearchIntentResponse intent) {
        if (intent == null) {
            return defaultResearchIntent();
        }
        return new ResearchIntentResponse(
                blankIfNull(blankToNull(intent.researchGoal())),
                blankIfNull(blankToNull(intent.deliverableFormat())),
                normalizeConstraintList(intent.constraints()),
                blankIfNull(blankToNull(intent.timeRange())),
                defaultIfBlank(normalizeToken(intent.depth()), "STANDARD"),
                defaultIfBlank(normalizeToken(intent.researchType()), "AUTO")
        );
    }

    private List<String> normalizeConstraintList(List<String> constraints) {
        if (constraints == null || constraints.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String constraint : constraints) {
            String value = blankToNull(constraint);
            if (value != null) {
                normalized.add(value);
            }
        }
        return List.copyOf(normalized);
    }

    private ResearchIntentResponse defaultResearchIntent() {
        return new ResearchIntentResponse("", "", List.of(), "", "STANDARD", "AUTO");
    }

    private String extractReport(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return "";
        }
        Object reportMarkdown = payload.get("report_markdown");
        if (reportMarkdown instanceof String text && !text.isBlank()) {
            return text;
        }
        Object markdown = payload.get("markdown");
        if (markdown instanceof String text && !text.isBlank()) {
            return text;
        }
        return Json.write(objectMapper, payload);
    }

    private ResearchReportFileResponse writeResearchReportFile(String workspaceId, String researchRunId, byte[] reportBytes) {
        String objectKey = researchReportObjectKey(workspaceId, researchRunId);
        storage.write("noteweave-source", objectKey, reportBytes);
        return new ResearchReportFileResponse(
                objectKey,
                "final.md",
                "text/markdown",
                reportBytes.length,
                sha256(reportBytes)
        );
    }

    private ResearchReportFileResponse buildReportFileResponse(String workspaceId, String researchRunId, String reportMarkdown) {
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

    private ResearchRunArtifactResponse buildResearchArtifact(
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
                candidate == null || candidate.artifactType().isBlank() ? "DEEP_RESEARCH_REPORT" : candidate.artifactType(),
                candidate == null || candidate.artifactVersion().isBlank() ? "v1" : candidate.artifactVersion(),
                artifactTitle(finalReportTitle, candidate, researchRunId),
                candidate == null || candidate.generatedBy().isBlank() ? "research_agent" : candidate.generatedBy(),
                candidate == null || candidate.generatedRefType().isBlank() ? "RESEARCH_RUN" : candidate.generatedRefType(),
                candidate == null || candidate.generatedRefId().isBlank() ? researchRunId : candidate.generatedRefId(),
                candidate == null ? 0 : candidate.citationCount(),
                reportFile,
                savedReportSource
        );
    }

    private String artifactTitle(String finalReportTitle, ResearchArtifactCandidateResponse candidate, String researchRunId) {
        if (candidate != null && !candidate.title().isBlank()) {
            return candidate.title();
        }
        if (finalReportTitle != null && !finalReportTitle.isBlank()) {
            return finalReportTitle;
        }
        return "Research Report " + researchRunId;
    }

    private String researchReportObjectKey(String workspaceId, String researchRunId) {
        return "workspace/%s/research/%s/report/final.md".formatted(workspaceId, researchRunId);
    }

    private FileObjectRef getOrCreateGeneratedFileObject(
            String workspaceId,
            String researchRunId,
            String title,
            String sha256,
            byte[] bytes
    ) {
        List<FileObjectRef> existing = jdbcTemplate.query("""
                select id, object_key from file_object where workspace_id = ? and sha256 = ?
                """, (rs, rowNum) -> new FileObjectRef(rs.getString("id"), rs.getString("object_key")), workspaceId, sha256);
        if (!existing.isEmpty()) {
            FileObjectRef ref = existing.get(0);
            if (!storage.exists("noteweave-source", ref.objectKey())) {
                storage.write("noteweave-source", ref.objectKey(), bytes);
            }
            jdbcTemplate.update("update file_object set ref_count = ref_count + 1 where id = ?", ref.id());
            return ref;
        }
        String fileObjectId = Ids.newId();
        String objectKey = "workspace/%s/file_object/%s-%s.md".formatted(workspaceId, sha256, sanitize(title));
        storage.write("noteweave-source", objectKey, bytes);
        jdbcTemplate.update("""
                insert into file_object(id, workspace_id, object_key, sha256, file_size, mime_type, ref_count)
                values (?, ?, ?, ?, ?, 'text/markdown', 1)
                """, fileObjectId, workspaceId, objectKey, sha256, bytes.length);
        return new FileObjectRef(fileObjectId, objectKey);
    }

    private String reportTitle(SaveReportRow row) {
        if (row.finalReportTitle() != null && !row.finalReportTitle().isBlank()) {
            return row.finalReportTitle();
        }
        return "Research Report " + row.researchRunId();
    }

    private String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
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

    private String blankIfNull(String value) {
        return value == null ? "" : value;
    }

    private WaitContextResponse loadRunWaitContext(String taskId, String status) {
        String normalizedStatus = blankIfNull(status);
        return taskService.loadWaitContext(
                taskId,
                "WAITING".equalsIgnoreCase(normalizedStatus) ? "WAITING" : "",
                normalizedStatus
        );
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record RunRow(
            String researchRunId,
            String workspaceId,
            String taskId,
            String question,
            String profileKey,
            String researchIntentJson,
            String sourceScopeJson,
            String controlPackJson,
            ResearchRetrievalMode retrievalMode,
            String resumedFromResearchRunId,
            Integer resumedFromCheckpointNo
    ) {
    }

    private record CallbackLease(int attemptNo, long fencingToken) {
    }

    private record DetailRow(
            String researchRunId,
            String workspaceId,
            String taskId,
            String question,
            String profileKey,
            String researchIntentJson,
            String sourceScopeJson,
            String controlPackJson,
            String resumedFromResearchRunId,
            Integer resumedFromCheckpointNo,
            String status,
            String finalReportTitle,
            String finalReportMarkdown,
            String traceSummary,
            String reportSourceId,
            Instant createdAt,
            Instant updatedAt
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

    private record RunArtifactView(
            ResearchReportFileResponse reportFile,
            ResearchRunArtifactResponse researchArtifact,
            SaveResearchReportSourceResponse savedReportSource
    ) {
    }

    private record ResearchConversationProjection(String conversationId, String messageId) {
    }

    private record ResumeSourceRunRow(
            String researchRunId,
            String workspaceId,
            String question,
            String profileKey,
            String researchIntentJson,
            String sourceScopeJson,
            String controlPackJson,
            ResearchRetrievalMode retrievalMode
    ) {
    }

    private record CheckpointRow(
            int checkpointNo,
            String snapshotType,
            String objectKey,
            String payloadSha256,
            long contentSize,
            String activeBranchKey,
            String finalLoopDecision,
            String summaryJson,
            Instant createdAt
    ) {
    }

    private record FileObjectRef(String id, String objectKey) {
    }

    private record SourceOriginRef(String title, String generatedBy, String generatedRefId) {
    }
}
