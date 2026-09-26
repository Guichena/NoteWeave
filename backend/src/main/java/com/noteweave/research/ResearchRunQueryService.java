package com.noteweave.research;

import static com.noteweave.research.ResearchReadModelMapper.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.storage.ObjectStorage;
import com.noteweave.task.TaskService;
import com.noteweave.task.WaitContextResponse;
import com.noteweave.worker.WorkerSourceScopeItemResponse;
import com.noteweave.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ResearchRunQueryService {

    private static final int DEFAULT_RUN_LIST_LIMIT = 50;
    private static final int MAX_RUN_LIST_LIMIT = 100;

    private final WorkspaceService workspaceService;
    private final TaskService taskService;
    private final ObjectStorage storage;
    private final ResearchArtifactService researchArtifactService;
    private final ResearchAgentProjectionService researchAgentProjectionService;
    private final ResearchRunReadRepository researchRunReadRepository;
    private final ResearchCheckpointStore researchCheckpointStore;
    private final ResearchSourceProvenanceEnricher sourceProvenanceEnricher;
    private final ResearchSourceScopeLoader sourceScopeLoader;
    private final ResearchEvidenceSampleAssembler researchEvidenceSampleAssembler;
    private final ResearchCheckpointReadModelAssembler researchCheckpointReadModelAssembler;
    private final ResearchProcessSummaryAssembler researchProcessSummaryAssembler;
    private final ResearchCounterfactualSummaryAssembler researchCounterfactualSummaryAssembler;
    private final ResearchClosedLoopStateAssembler researchClosedLoopStateAssembler;
    private final ResearchCheckpointProcessAssembler researchCheckpointProcessAssembler;
    private final ResearchReportReadModelAssembler researchReportReadModelAssembler;
    private final ResearchRunQueryPayloadReader payloadReader;

    public ResearchRunQueryService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            WorkspaceService workspaceService,
            TaskService taskService,
            ObjectStorage storage,
            ResearchArtifactService researchArtifactService,
            ResearchAgentProjectionService researchAgentProjectionService,
            ResearchRunListQueryRepository researchRunListQueryRepository,
            ResearchCheckpointStore researchCheckpointStore,
            ResearchSourceProvenanceEnricher sourceProvenanceEnricher,
            ResearchSourceScopeLoader sourceScopeLoader
    ) {
        this.workspaceService = workspaceService;
        this.taskService = taskService;
        this.storage = storage;
        this.researchArtifactService = researchArtifactService;
        this.researchAgentProjectionService = researchAgentProjectionService;
        this.researchRunReadRepository = new ResearchRunReadRepository(jdbcTemplate, objectMapper);
        this.researchCheckpointStore = researchCheckpointStore;
        this.sourceProvenanceEnricher = sourceProvenanceEnricher;
        this.sourceScopeLoader = sourceScopeLoader;
        this.researchEvidenceSampleAssembler = new ResearchEvidenceSampleAssembler();
        this.researchCheckpointReadModelAssembler = new ResearchCheckpointReadModelAssembler(
                objectMapper, sourceProvenanceEnricher);
        this.researchProcessSummaryAssembler = new ResearchProcessSummaryAssembler();
        this.researchCounterfactualSummaryAssembler = new ResearchCounterfactualSummaryAssembler();
        this.researchClosedLoopStateAssembler = new ResearchClosedLoopStateAssembler(
                this.researchEvidenceSampleAssembler,
                this.researchCheckpointReadModelAssembler,
                this.researchCounterfactualSummaryAssembler
        );
        this.researchCheckpointProcessAssembler = new ResearchCheckpointProcessAssembler(
                new ResearchVerifierGatedSummaryAssembler(),
                this.researchCounterfactualSummaryAssembler
        );
        this.researchReportReadModelAssembler = new ResearchReportReadModelAssembler(objectMapper);
        this.payloadReader = new ResearchRunQueryPayloadReader(objectMapper);
    }

    @Autowired
    public ResearchRunQueryService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            WorkspaceService workspaceService,
            TaskService taskService,
            ObjectStorage storage,
            ResearchArtifactService researchArtifactService,
            ResearchAgentProjectionService researchAgentProjectionService,
            ResearchRunReadRepository researchRunReadRepository,
            ResearchCheckpointStore researchCheckpointStore,
            ResearchSourceProvenanceEnricher sourceProvenanceEnricher,
            ResearchSourceScopeLoader sourceScopeLoader,
            ResearchEvidenceSampleAssembler researchEvidenceSampleAssembler,
            ResearchCheckpointReadModelAssembler researchCheckpointReadModelAssembler,
            ResearchProcessSummaryAssembler researchProcessSummaryAssembler,
            ResearchCounterfactualSummaryAssembler researchCounterfactualSummaryAssembler,
            ResearchClosedLoopStateAssembler researchClosedLoopStateAssembler,
            ResearchCheckpointProcessAssembler researchCheckpointProcessAssembler,
            ResearchReportReadModelAssembler researchReportReadModelAssembler
    ) {
        this.workspaceService = workspaceService;
        this.taskService = taskService;
        this.storage = storage;
        this.researchArtifactService = researchArtifactService;
        this.researchAgentProjectionService = researchAgentProjectionService;
        this.researchRunReadRepository = researchRunReadRepository;
        this.researchCheckpointStore = researchCheckpointStore;
        this.sourceProvenanceEnricher = sourceProvenanceEnricher;
        this.sourceScopeLoader = sourceScopeLoader;
        this.researchEvidenceSampleAssembler = researchEvidenceSampleAssembler;
        this.researchCheckpointReadModelAssembler = researchCheckpointReadModelAssembler;
        this.researchProcessSummaryAssembler = researchProcessSummaryAssembler;
        this.researchCounterfactualSummaryAssembler = researchCounterfactualSummaryAssembler;
        this.researchClosedLoopStateAssembler = researchClosedLoopStateAssembler;
        this.researchCheckpointProcessAssembler = researchCheckpointProcessAssembler;
        this.researchReportReadModelAssembler = researchReportReadModelAssembler;
        this.payloadReader = new ResearchRunQueryPayloadReader(objectMapper);
    }

    public List<ResearchRunSummaryResponse> listRuns(String workspaceId) {
        return listRuns(workspaceId, DEFAULT_RUN_LIST_LIMIT, 0);
    }

    public List<ResearchRunSummaryResponse> listRuns(String workspaceId, int limit, int offset) {
        requireWorkspace(workspaceId);
        if (limit <= 0 || offset < 0) {
            throw new BusinessException("RESEARCH_RUN_LIST_PAGE_INVALID", "研究运行列表分页参数无效");
        }
        List<ResearchRunListRow> rows = researchRunReadRepository.listRows(
                workspaceId,
                Math.min(limit, MAX_RUN_LIST_LIMIT),
                offset
        );
        ResearchRunListReadModel readModel = loadResearchRunListReadModel(workspaceId, rows);
        return rows.stream().map(row -> {
            String researchRunId = row.researchRunId();
            List<ResearchTraceResponse> traces = readModel.traces(researchRunId);
            ResearchReportStructureResponse reportStructure = researchReportReadModelAssembler.buildReportStructure(traces);
            ResearchClosedLoopStateResponse closedLoopState = buildClosedLoopState(
                    traces,
                    readModel.closedLoopData(researchRunId)
            );
            ResearchArtifactCandidateResponse researchArtifactCandidate =
                    researchReportReadModelAssembler.buildResearchArtifactCandidate(traces);
            ResearchResumeCheckpointSummaryResponse resumeCheckpoint = buildResumeCheckpointSummary(row, readModel);
            ResearchCounterfactualSummaryResponse counterfactualSummary = buildCounterfactualSummary(reportStructure, closedLoopState);
            ResearchIntentAlignmentResponse researchIntentAlignment = buildResearchIntentAlignment(reportStructure, closedLoopState);
            Map<String, Object> intentCompletionContract = buildIntentCompletionContract(reportStructure, closedLoopState);
            ResearchRecoveryTargetsResponse recoveryTargets = buildRecoveryTargets(reportStructure, closedLoopState);
            int sourceScopeCount = sourceScopeLoader.count(row.sourceScopeJson());
            SaveResearchReportSourceResponse savedReportSource = readModel.savedReportSource(row.reportSourceId());
            ResearchReportFileResponse reportFile = researchArtifactService.buildReportFileResponse(
                    workspaceId,
                    researchRunId,
                    row.finalReportMarkdown()
            );
            ResearchRunArtifactResponse researchArtifact = researchArtifactService.buildResearchArtifact(
                    researchRunId,
                    row.finalReportTitle(),
                    researchArtifactCandidate,
                    reportFile,
                    savedReportSource
            );
            Map<String, Object> verifierGatedSummary = researchCheckpointProcessAssembler.buildVerifierGatedSummary(
                    closedLoopState.stateLedger().rows(),
                    payloadReader.recoveryTargetsMap(recoveryTargets)
            );
            String localVerifierReason = payloadReader.extractDecisionReason(closedLoopState.localVerifier());
            String globalVerifierReason = payloadReader.extractDecisionReason(closedLoopState.globalVerifier());
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
                    row.taskId(),
                    row.question(),
                    row.profileKey(),
                    row.status(),
                    row.finalReportTitle(),
                    row.resumedFromResearchRunId(),
                    row.resumedFromCheckpointNo(),
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
                    researchProcessSummaryAssembler.build(
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
                    readModel.waitContext(row.taskId()),
                    recoveryTargets,
                    counterfactualSummary,
                    row.createdAt(),
                    row.updatedAt()
            );
        }).toList();
    }

    private ResearchRunListReadModel loadResearchRunListReadModel(
            String workspaceId,
            List<ResearchRunListRow> rows
    ) {
        if (rows.isEmpty()) {
            return ResearchRunListReadModel.empty();
        }
        List<String> researchRunIds = rows.stream().map(ResearchRunListRow::researchRunId).toList();
        ResearchRunReadBundle readBundle = researchRunReadRepository.loadBatch(researchRunIds);
        Map<String, List<ResearchTraceResponse>> traces = readBundle.tracesByRunId();
        Map<String, List<Map<String, Object>>> branches = readBundle.branchesByRunId();
        Map<String, List<Map<String, Object>>> ledgerRows = readBundle.rowsByRunId();
        Map<String, List<Map<String, Object>>> cells = readBundle.cellsByRunId();
        Map<String, List<Map<String, Object>>> sourceEvidence = readBundle.sourceEvidenceByRunId();
        Map<String, List<Map<String, Object>>> verifierDecisions = readBundle.verifierDecisionsByRunId();
        Map<String, List<Map<String, Object>>> checkpoints = loadPersistedCheckpointsByRunIds(researchRunIds);
        Map<String, List<Map<String, Object>>> cellEvidence = readBundle.cellEvidenceByRunId();

        sourceProvenanceEnricher.enrich(List.of(traces, checkpoints));

        Map<String, ResearchClosedLoopData> closedLoopData = new LinkedHashMap<>();
        for (String researchRunId : researchRunIds) {
            closedLoopData.put(researchRunId, new ResearchClosedLoopData(
                    values(branches, researchRunId),
                    values(ledgerRows, researchRunId),
                    values(cells, researchRunId),
                    values(sourceEvidence, researchRunId),
                    values(verifierDecisions, researchRunId),
                    values(checkpoints, researchRunId),
                    values(cellEvidence, researchRunId)
            ));
        }
        Map<String, SaveResearchReportSourceResponse> savedReportSources = researchArtifactService.loadSavedReportSources(
                workspaceId,
                rows.stream().map(ResearchRunListRow::reportSourceId).filter(id -> !id.isBlank()).toList()
        );
        Map<String, String> taskStatuses = new LinkedHashMap<>();
        rows.forEach(row -> taskStatuses.put(row.taskId(), row.status()));
        return new ResearchRunListReadModel(
                traces,
                closedLoopData,
                checkpoints,
                rows.stream().collect(java.util.stream.Collectors.toMap(
                        ResearchRunListRow::researchRunId,
                        row -> row,
                        (left, right) -> left,
                        LinkedHashMap::new
                )),
                savedReportSources,
                taskService.loadWaitContexts(taskStatuses)
        );
    }

    private Map<String, List<Map<String, Object>>> loadPersistedCheckpointsByRunIds(
            List<String> researchRunIds
    ) {
        Map<String, List<ResearchCheckpointRecord>> recordsByRunId =
                researchCheckpointStore.findAllByRunIds(researchRunIds);
        LinkedHashMap<String, List<Map<String, Object>>> checkpointsByRunId = new LinkedHashMap<>();
        recordsByRunId.forEach((runId, records) -> checkpointsByRunId.put(
                runId,
                records.stream()
                        .map(record -> researchCheckpointReadModelAssembler.toPersistedCheckpoint(record, false))
                        .toList()
        ));
        return Map.copyOf(checkpointsByRunId);
    }

    private <T> List<T> values(Map<String, List<T>> valuesByRunId, String researchRunId) {
        return valuesByRunId.getOrDefault(researchRunId, List.of());
    }

    private ResearchResumeCheckpointSummaryResponse buildResumeCheckpointSummary(
            ResearchRunListRow row,
            ResearchRunListReadModel readModel
    ) {
        if (row.resumedFromResearchRunId().isBlank() || row.resumedFromCheckpointNo() == null) {
            return null;
        }
        Map<String, Object> checkpoint = readModel.checkpoint(
                row.resumedFromResearchRunId(),
                row.resumedFromCheckpointNo()
        );
        if (checkpoint == null) {
            throw new BusinessException("RESEARCH_CHECKPOINT_NOT_FOUND", "Research checkpoint 不存在");
        }
        ResearchRunListRow sourceRun = readModel.run(row.resumedFromResearchRunId());
        if (sourceRun == null) {
            throw new BusinessException("RESEARCH_RUN_NOT_FOUND", "研究任务不存在");
        }
        SaveResearchReportSourceResponse savedReportSource = readModel.savedReportSource(sourceRun.reportSourceId());
        ResearchReportFileResponse reportFile = researchArtifactService.buildReportFileResponse(
                sourceRun.workspaceId(),
                sourceRun.researchRunId(),
                sourceRun.finalReportMarkdown()
        );
        ResearchRunArtifactResponse researchArtifact = researchArtifactService.buildResearchArtifact(
                sourceRun.researchRunId(),
                sourceRun.finalReportTitle(),
                null,
                reportFile,
                savedReportSource
        );
        Map<String, Object> summary = castMapOrEmpty(checkpoint.get("summary"));
        return new ResearchResumeCheckpointSummaryResponse(
                sourceRun.researchRunId(),
                row.resumedFromCheckpointNo(),
                stringValue(checkpoint.get("snapshot_type")),
                blankIfNull(stringValue(checkpoint.get("active_branch_id"))),
                blankIfNull(stringValue(checkpoint.get("final_loop_decision"))),
                reportFile,
                researchArtifact,
                savedReportSource,
                readCheckpointSnapshotSummaryResponse(summary),
                readCounterfactualSummary(castMapOrEmpty(summary.get("counterfactual_summary"))),
                readRecoveryTargetsResponse(castMapOrEmpty(summary.get("recovery_targets"))),
                (Instant) checkpoint.get("created_at")
        );
    }

    public ResearchRunDetailResponse getRunDetail(String workspaceId, String researchRunId) {
        requireWorkspace(workspaceId);
        ResearchRunDetailRow row = researchRunReadRepository.findDetail(workspaceId, researchRunId);

        ResearchRunReadBundle readBundle = researchRunReadRepository.loadBatch(List.of(row.researchRunId()));
        List<ResearchTraceResponse> traces = values(readBundle.tracesByRunId(), row.researchRunId());
        sourceProvenanceEnricher.enrich(traces);
        ResearchReportStructureResponse reportStructure = researchReportReadModelAssembler.buildReportStructure(traces);
        ResearchClosedLoopStateResponse closedLoopState = buildClosedLoopState(
                traces,
                new ResearchClosedLoopData(
                        values(readBundle.branchesByRunId(), row.researchRunId()),
                        values(readBundle.rowsByRunId(), row.researchRunId()),
                        values(readBundle.cellsByRunId(), row.researchRunId()),
                        values(readBundle.sourceEvidenceByRunId(), row.researchRunId()),
                        values(readBundle.verifierDecisionsByRunId(), row.researchRunId()),
                        values(loadPersistedCheckpointsByRunIds(List.of(row.researchRunId())), row.researchRunId()),
                        values(readBundle.cellEvidenceByRunId(), row.researchRunId())
                )
        );
        ResearchArtifactCandidateResponse researchArtifactCandidate =
                researchReportReadModelAssembler.buildResearchArtifactCandidate(traces);
        ResearchResumeContextSummaryResponse resumeContextSummary =
                researchReportReadModelAssembler.buildResumeContextSummary(traces);
        ResearchResumeCheckpointSummaryResponse resumeCheckpoint = loadResumeCheckpointSummary(
                row.workspaceId(),
                blankIfNull(row.resumedFromResearchRunId()).isBlank() ? null : row.resumedFromResearchRunId(),
                row.resumedFromCheckpointNo()
        );
        ResearchVerifierSummaryResponse verifierSummary = buildVerifierSummary(reportStructure, closedLoopState);
        List<WorkerSourceScopeItemResponse> sourceScope = sourceScopeLoader.load(
                row.workspaceId(), row.sourceScopeJson());
        SaveResearchReportSourceResponse savedReportSource = blankToNull(row.reportSourceId()) != null
                ? researchArtifactService.loadSavedReportSource(row.workspaceId(), row.reportSourceId())
                : null;
        ResearchReportFileResponse reportFile = researchArtifactService.buildReportFileResponse(
                row.workspaceId(),
                row.researchRunId(),
                row.finalReportMarkdown()
        );
        ResearchRunArtifactResponse researchArtifact = researchArtifactService.buildResearchArtifact(
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
                researchReportReadModelAssembler.readResearchIntent(row.researchIntentJson()),
                blankIfNull(row.resumedFromResearchRunId()),
                row.resumedFromCheckpointNo(),
                row.status(),
                blankIfNull(row.completionTerminalState()),
                blankIfNull(row.finalReportTitle()),
                blankIfNull(row.finalReportMarkdown()),
                reportStructure,
                buildCounterfactualSummary(reportStructure, closedLoopState),
                researchArtifactCandidate,
                reportFile,
                researchArtifact,
                researchProcessSummaryAssembler.build(
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
                payloadReader.readControlPack(row.controlPackJson()),
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
                .map(researchCheckpointReadModelAssembler::toSummary)
                .toList();
    }

    public ResearchCheckpointResponse getCheckpoint(String workspaceId, String researchRunId, int checkpointNo) {
        requireWorkspace(workspaceId);
        ResearchCheckpointRecord row = researchCheckpointStore.get(workspaceId, researchRunId, checkpointNo);
        ResearchArtifactService.RunArtifactView artifactView =
                researchArtifactService.loadRunArtifactView(workspaceId, researchRunId);
        byte[] checkpointPayload = storage.read("noteweave-derived", row.objectKey());
        ResearchCheckpointIntegrity.verify(row, checkpointPayload);
        Map<String, Object> payload = payloadReader.readPayloadMap(new String(checkpointPayload, StandardCharsets.UTF_8));
        Map<String, Object> summary = payloadReader.readPayloadMap(row.summaryJson());
        sourceProvenanceEnricher.enrich(payload);
        researchEvidenceSampleAssembler.enrichPayloadLoopRoundsWithSourceSamples(payload);
        sourceProvenanceEnricher.enrich(summary);
        ResearchProcessSummaryResponse researchProcessSummary = researchCheckpointProcessAssembler.buildProcessSummary(
                payload, summary);
        return new ResearchCheckpointResponse(
                row.checkpointNo(),
                row.snapshotType(),
                row.objectKey(),
                row.payloadSha256(),
                row.contentSize(),
                blankIfNull(row.activeBranchKey()),
                blankIfNull(row.finalLoopDecision()),
                readCheckpointSnapshotSummaryResponse(summary),
                researchCheckpointProcessAssembler.firstNonNullCounterfactualSummary(summary, payload),
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

    private ResearchResumeCheckpointSummaryResponse loadResumeCheckpointSummary(
            String workspaceId,
            String sourceResearchRunId,
            Integer checkpointNo
    ) {
        if (sourceResearchRunId == null || sourceResearchRunId.isBlank() || checkpointNo == null) {
            return null;
        }
        ResearchCheckpointRecord checkpointRow = researchCheckpointStore.get(
                workspaceId, sourceResearchRunId, checkpointNo);
        Map<String, Object> summary = payloadReader.readPayloadMap(checkpointRow.summaryJson());
        sourceProvenanceEnricher.enrich(summary);
        ResearchCheckpointSnapshotSummaryResponse summaryResponse = readCheckpointSnapshotSummaryResponse(summary);
        ResearchCounterfactualSummaryResponse counterfactualSummary = readCounterfactualSummary(
                castMapOrEmpty(summary.get("counterfactual_summary"))
        );
        ResearchRecoveryTargetsResponse recoveryTargets = readRecoveryTargetsResponse(
                castMapOrEmpty(summary.get("recovery_targets"))
        );
        ResearchArtifactService.RunArtifactView artifactView =
                researchArtifactService.loadRunArtifactView(workspaceId, sourceResearchRunId);
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

    private void requireResearchRun(String workspaceId, String researchRunId) {
        researchRunReadRepository.requireRun(workspaceId, researchRunId);
    }

    private ResearchClosedLoopStateResponse buildClosedLoopState(
            List<ResearchTraceResponse> traces,
            ResearchClosedLoopData data
    ) {
        return researchClosedLoopStateAssembler.build(
                traces,
                data,
                payloadReader.extractRecoveryTargets(traces, researchCheckpointProcessAssembler)
        );
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
        Map<String, Object> verifierGatedSummary = researchCheckpointProcessAssembler.buildVerifierGatedSummary(
                closedLoopState.stateLedger().rows(),
                payloadReader.recoveryTargetsMap(recoveryTargets)
        );
        return new ResearchVerifierSummaryResponse(
                blankIfNull(closedLoopState.localVerifierStatus()),
                    payloadReader.extractDecisionReason(localVerifier),
                blankIfNull(closedLoopState.globalVerifierDecision()),
                    payloadReader.extractDecisionReason(globalVerifier),
                blankIfNull(closedLoopState.finalLoopDecision()),
                blankIfNull(stringValue(loopDecisionPayload.get("reason"))),
                researchIntentAlignment == null ? "" : blankIfNull(researchIntentAlignment.status()),
                researchIntentAlignment == null ? "" : blankIfNull(researchIntentAlignment.reasonCode()),
                recoveryTargets,
                readVerifierGatedSummaryResponse(verifierGatedSummary)
        );
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
        return researchCounterfactualSummaryAssembler.build(
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
        return researchReportReadModelAssembler.readResearchIntentAlignment(castMapOrEmpty(firstNonNull(
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
        return researchCounterfactualSummaryAssembler.build(
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

    private List<Map<String, Object>> loadPersistedCheckpoints(String researchRunId) {
        return researchCheckpointStore.findAll(researchRunId).stream()
                .map(row -> researchCheckpointReadModelAssembler.toPersistedCheckpoint(row, true))
                .toList();
    }

    private Map<String, Object> rawCounterfactualSummary(Map<String, Object> checkpointPayload) {
        Map<String, Object> directSummary = castMapOrEmpty(firstNonNull(
                checkpointPayload.get("counterfactual_summary"),
                castMapOrEmpty(castMapOrEmpty(checkpointPayload.get("report_structure")).get("counterfactual_summary"))
        ));
        if (!directSummary.isEmpty()) {
            return directSummary;
        }
        ResearchCounterfactualSummaryResponse derivedSummary = researchCheckpointProcessAssembler
                .buildCounterfactualSummary(checkpointPayload);
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

    private void requireWorkspace(String workspaceId) {
        if (!workspaceService.exists(workspaceId)) {
            throw new BusinessException("WORKSPACE_NOT_FOUND", "工作台不存在");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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

    private record ResearchRunListReadModel(
            Map<String, List<ResearchTraceResponse>> tracesByRunId,
            Map<String, ResearchClosedLoopData> closedLoopDataByRunId,
            Map<String, List<Map<String, Object>>> checkpointsByRunId,
            Map<String, ResearchRunListRow> runsById,
            Map<String, SaveResearchReportSourceResponse> savedReportSourcesById,
            Map<String, WaitContextResponse> waitContextsByTaskId
    ) {
        private static ResearchRunListReadModel empty() {
            return new ResearchRunListReadModel(
                    Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of()
            );
        }

        private List<ResearchTraceResponse> traces(String researchRunId) {
            return tracesByRunId.getOrDefault(researchRunId, List.of());
        }

        private ResearchClosedLoopData closedLoopData(String researchRunId) {
            return closedLoopDataByRunId.getOrDefault(researchRunId, ResearchClosedLoopData.empty());
        }

        private ResearchRunListRow run(String researchRunId) {
            return runsById.get(researchRunId);
        }

        private Map<String, Object> checkpoint(String researchRunId, int checkpointNo) {
            return checkpointsByRunId.getOrDefault(researchRunId, List.of()).stream()
                    .filter(item -> checkpointNo == intRecordValue(item.get("checkpoint_no")))
                    .findFirst()
                    .orElse(null);
        }

        private SaveResearchReportSourceResponse savedReportSource(String sourceId) {
            if (sourceId == null || sourceId.isBlank()) {
                return null;
            }
            SaveResearchReportSourceResponse source = savedReportSourcesById.get(sourceId);
            if (source == null) {
                throw new BusinessException("SOURCE_NOT_FOUND", "资料不存在");
            }
            return source;
        }

        private WaitContextResponse waitContext(String taskId) {
            return waitContextsByTaskId.get(taskId);
        }

        private static int intRecordValue(Object value) {
            return value instanceof Number number ? number.intValue() : 0;
        }
    }

}
