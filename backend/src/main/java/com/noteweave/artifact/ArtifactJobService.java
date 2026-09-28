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
import com.noteweave.research.ResearchGeneratedSourceReadGate;
import com.noteweave.task.TaskService;
import com.noteweave.worker.WorkerContextSnapshotResponse;
import com.noteweave.worker.WorkerFailRequest;
import com.noteweave.worker.WorkerSourceScopeItemResponse;
import com.noteweave.worker.WorkerTaskCallbackService.CompletionOutcome;
import com.noteweave.workspace.WorkspaceService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ArtifactJobService {

    private final ObjectMapper objectMapper;
    private final WorkspaceService workspaceService;
    private final TaskService taskService;
    private final ArtifactJobReadRepository artifactJobReadRepository;
    private final ArtifactJobWriteRepository artifactJobWriteRepository;
    private final ArtifactPayloadReadModelAssembler artifactPayloadReadModelAssembler;
    private final MemoryCompilerService memoryCompilerService;
    private final ArtifactSkillCatalogService artifactSkillCatalogService;
    private final ArtifactVideoMaterialService videoMaterialService;
    private final GeneratedSourceService generatedSourceService;
    private final KnowledgeCommandService knowledgeCommandService;
    private final ArtifactContextV2ShadowSnapshotService contextV2ShadowSnapshots;
    private final ArtifactMemoryRevisionGuard memoryRevisionGuard;
    private final ResearchGeneratedSourceReadGate generatedSourceGate;
    private final ArtifactExportService exportService;

    public ArtifactJobService(
            ObjectMapper objectMapper,
            WorkspaceService workspaceService,
            TaskService taskService,
            ArtifactJobReadRepository artifactJobReadRepository,
            ArtifactJobWriteRepository artifactJobWriteRepository,
            ArtifactPayloadReadModelAssembler artifactPayloadReadModelAssembler,
            MemoryCompilerService memoryCompilerService,
            ArtifactSkillCatalogService artifactSkillCatalogService,
            ArtifactVideoMaterialService videoMaterialService,
            GeneratedSourceService generatedSourceService,
            KnowledgeCommandService knowledgeCommandService,
            ArtifactContextV2ShadowSnapshotService contextV2ShadowSnapshots,
            ArtifactMemoryRevisionGuard memoryRevisionGuard,
            ResearchGeneratedSourceReadGate generatedSourceGate,
            ArtifactExportService exportService
    ) {
        this.objectMapper = objectMapper;
        this.workspaceService = workspaceService;
        this.taskService = taskService;
        this.artifactJobReadRepository = artifactJobReadRepository;
        this.artifactJobWriteRepository = artifactJobWriteRepository;
        this.artifactPayloadReadModelAssembler = artifactPayloadReadModelAssembler;
        this.memoryCompilerService = memoryCompilerService;
        this.artifactSkillCatalogService = artifactSkillCatalogService;
        this.videoMaterialService = videoMaterialService;
        this.generatedSourceService = generatedSourceService;
        this.knowledgeCommandService = knowledgeCommandService;
        this.contextV2ShadowSnapshots = contextV2ShadowSnapshots;
        this.memoryRevisionGuard = memoryRevisionGuard;
        this.generatedSourceGate = generatedSourceGate;
        this.exportService = exportService;
    }

    @Transactional
    public ArtifactJobResponse createJob(String workspaceId, CreateArtifactJobRequest request) {
        return createJobInternal(workspaceId, request, null);
    }

    /** Called only after the parent coordinator rechecks its frozen actor's current ACL. */
    ArtifactJobResponse createJobForVerifiedActor(String workspaceId,
            CreateArtifactJobRequest request, String actorUserId) {
        if (actorUserId == null || actorUserId.isBlank()) {
            throw new IllegalArgumentException("Verified Artifact actor is required");
        }
        return createJobInternal(workspaceId, request, actorUserId);
    }

    private ArtifactJobResponse createJobInternal(String workspaceId,
            CreateArtifactJobRequest request, String actorUserId) {
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
        MemoryControlPackResponse controlPack = actorUserId == null
                ? memoryCompilerService.compileArtifactControlPack(workspaceId, skillKey)
                : memoryCompilerService.compileArtifactControlPackForActor(
                        workspaceId, skillKey, actorUserId);
        artifactJobWriteRepository.persistInitialJob(
                artifactJobId,
                workspaceId,
                taskId,
                skillKey,
                userRequirement,
                Json.write(objectMapper, inputs),
                Json.write(objectMapper, sourceScopeIds),
                Json.write(objectMapper, controlPack),
                Json.write(objectMapper, sourceScopeSnapshot),
                Json.write(objectMapper, upstreamRefs),
                Json.write(objectMapper, Map.of(
                        "task_id", taskId,
                        "task_type", "ARTIFACT_JOB",
                        "workspace_id", workspaceId,
                        "target_type", "ARTIFACT_JOB",
                        "target_id", artifactJobId,
                        "payload_version", "v1",
                        "trace_id", artifactJobId,
                        "created_at", System.currentTimeMillis()
                )),
                artifactSkillCatalogService.catalogDigest()
        );
        if (actorUserId == null) {
            contextV2ShadowSnapshots.freeze(workspaceId, taskId, userRequirement, skillKey);
        } else {
            contextV2ShadowSnapshots.freezeForActor(
                    workspaceId, taskId, userRequirement, skillKey, actorUserId);
        }
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
        ArtifactRegenerationRow row = loadRegenerationRow(workspaceId, artifactJobId);
        ArtifactSkillDefinition skill = artifactSkillCatalogService.resolveSkill(row.skillKey());
        String requirement = request.userRequirement() == null || request.userRequirement().isBlank()
                ? row.userRequirement()
                : request.userRequirement().trim();
        Map<String, Object> inputs = request.inputs() == null
                ? artifactPayloadReadModelAssembler.readInputs(row.inputsJson())
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
        artifactJobWriteRepository.persistRegeneration(
                taskId,
                artifactJobId,
                nextRunNo,
                "REGENERATE",
                sourceVersionNo,
                requirement,
                Json.write(objectMapper, inputs),
                row.sourceScopeJson(),
                row.controlPackJson(),
                row.inputSnapshotId(),
                workspaceId,
                Json.write(objectMapper, Map.of(
                        "task_id", taskId,
                        "task_type", "ARTIFACT_JOB",
                        "workspace_id", workspaceId,
                        "target_type", "ARTIFACT_JOB",
                        "target_id", artifactJobId,
                        "payload_version", "v1",
                        "trace_id", artifactJobId + ":regenerate-v" + sourceVersionNo,
                        "created_at", System.currentTimeMillis()
                )),
                artifactSkillCatalogService.catalogDigest()
        );
        contextV2ShadowSnapshots.freeze(workspaceId, taskId, requirement, row.skillKey());
        memoryCompilerService.logPackUsage(
                workspaceId,
                "ARTIFACT",
                "ARTIFACT_JOB_RUN",
                taskId,
                artifactPayloadReadModelAssembler.readControlPack(row.controlPackJson())
        );
        return new ArtifactJobResponse(artifactJobId, taskId, row.skillKey(), "QUEUED");
    }

    /** Retry a failed Job from its last frozen input without requiring a published Version. */
    @Transactional
    ArtifactJobResponse retryFailedJob(String workspaceId, String artifactJobId) {
        requireWorkspace(workspaceId);
        List<String> statuses = artifactJobWriteRepository.lockJobStatuses(artifactJobId, workspaceId);
        if (statuses.size() != 1 || !"FAILED".equals(statuses.get(0))) {
            throw new BusinessException("ARTIFACT_JOB_RETRY_CONFLICT",
                    "仅失败的产物任务可按冻结输入重试", HttpStatus.CONFLICT);
        }
        ArtifactRegenerationRow row = loadRegenerationRow(workspaceId, artifactJobId);
        if (row.inputSnapshotId() == null || row.controlPackJson().isBlank()) {
            throw new BusinessException("ARTIFACT_JOB_RETRY_INPUT_UNAVAILABLE",
                    "冻结输入已不可用于重试", HttpStatus.CONFLICT);
        }
        if (!artifactJobWriteRepository.hasFullInputSnapshot(
                row.inputSnapshotId(), workspaceId, artifactJobId)) {
            throw new BusinessException("ARTIFACT_JOB_RETRY_INPUT_UNAVAILABLE",
                    "冻结输入已不可用于重试", HttpStatus.CONFLICT);
        }
        artifactSkillCatalogService.resolveSkill(row.skillKey());
        String taskId = taskService.createTask(workspaceId, "ARTIFACT_JOB", "ARTIFACT_JOB",
                artifactJobId, "QUEUED", "失败产物按冻结输入重试");
        artifactJobWriteRepository.persistRegeneration(taskId, artifactJobId,
                row.latestRunNo() + 1, "RETRY", null, row.userRequirement(), row.inputsJson(),
                row.sourceScopeJson(), row.controlPackJson(), row.inputSnapshotId(), workspaceId,
                Json.write(objectMapper, Map.of(
                        "task_id", taskId, "task_type", "ARTIFACT_JOB",
                        "workspace_id", workspaceId, "target_type", "ARTIFACT_JOB",
                        "target_id", artifactJobId, "payload_version", "v1",
                        "trace_id", artifactJobId + ":retry-" + (row.latestRunNo() + 1),
                        "created_at", System.currentTimeMillis())),
                artifactSkillCatalogService.catalogDigest());
        contextV2ShadowSnapshots.freeze(workspaceId, taskId, row.userRequirement(), row.skillKey());
        memoryCompilerService.logPackUsage(workspaceId, "ARTIFACT", "ARTIFACT_JOB_RUN", taskId,
                artifactPayloadReadModelAssembler.readControlPack(row.controlPackJson()));
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
        exportService.requireVersionReadable(workspaceId, artifactJobId, sourceVersionNo);
        ArtifactRollbackRow source = loadRollbackRow(workspaceId, artifactJobId, sourceVersionNo);
        int nextVersionNo = artifactJobWriteRepository.lockNextVersionNo(workspaceId, artifactJobId);
        String versionId = Ids.newId();
        String requestedTitle = request == null || request.title() == null ? "" : request.title().trim();
        String title = requestedTitle.isBlank() ? source.title() + "（回滚副本）" : requestedTitle;
        Map<String, Object> payload = new LinkedHashMap<>(
                artifactPayloadReadModelAssembler.readInputs(source.resultPayloadJson()));
        payload.put("rollback_of_version_no", sourceVersionNo);
        payload.put("rollback_mode", "APPEND_ONLY_COPY");
        artifactJobWriteRepository.appendRollbackVersion(
                versionId,
                artifactJobId,
                source.skillKey(),
                nextVersionNo,
                title,
                source.contentMarkdown(),
                Json.write(objectMapper, payload),
                "append-only rollback of version " + sourceVersionNo,
                source.citationsJson(),
                source.originTaskId(),
                workspaceId
        );
        videoMaterialService.linkPublishedVersion(versionId, source.materialBundleId());
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
        return artifactJobReadRepository.listJobs(workspaceId).stream()
                .map(row -> new ArtifactJobSummaryResponse(
                row.artifactJobId(),
                row.workspaceId(),
                row.taskId(),
                row.skillKey(),
                row.status(),
                row.taskStatus(),
                row.progressPhase(),
                row.progressMessage(),
                row.resultTitle(),
                taskService.loadWaitContext(
                        row.taskId(), row.taskStatus(), row.progressPhase()
                ),
                row.latestVersionNo(), row.createdAt(), row.updatedAt()
        )).toList();
    }

    public ArtifactJobDetailResponse getJob(String workspaceId, String artifactJobId) {
        requireWorkspace(workspaceId);
        ArtifactJobDetailRow row = artifactJobReadRepository.getJob(workspaceId, artifactJobId);
        return new ArtifactJobDetailResponse(
                    row.artifactJobId(),
                    row.workspaceId(),
                    row.taskId(),
                    row.skillKey(),
                    row.userRequirement(),
                    artifactPayloadReadModelAssembler.readInputs(row.inputsJson()),
                    row.status(),
                    row.taskStatus(),
                    row.progressPhase(),
                    row.progressMessage(),
                    row.resultTitle(),
                    taskService.loadWaitContext(
                            row.taskId(), row.taskStatus(), row.progressPhase()
                    ),
                    row.latestVersionNo(), row.createdAt(), row.updatedAt()
        );
    }

    public List<ArtifactVersionSummaryResponse> listVersions(String workspaceId, String artifactJobId) {
        requireWorkspace(workspaceId);
        requireArtifactJob(workspaceId, artifactJobId);
        return artifactJobReadRepository.listVersions(workspaceId, artifactJobId).stream()
                .filter(row -> exportService.versionVisibleForListing(
                        workspaceId, artifactJobId, row.versionNo()))
                .map(row -> new ArtifactVersionSummaryResponse(
                        row.versionId(), row.artifactJobId(), row.skillKey(), row.versionNo(),
                        row.title(), row.createdAt()))
                .toList();
    }

    public ArtifactVersionDetailResponse getVersionDetail(
            String workspaceId,
            String artifactJobId,
            int versionNo
    ) {
        requireWorkspace(workspaceId);
        requireArtifactJob(workspaceId, artifactJobId);
        exportService.requireVersionReadable(workspaceId, artifactJobId, versionNo);
        ArtifactVersionDetailRow row = artifactJobReadRepository.getVersionDetail(
                workspaceId, artifactJobId, versionNo);
        return new ArtifactVersionDetailResponse(
                row.versionId(),
                row.artifactJobId(),
                row.skillKey(),
                row.versionNo(),
                row.title(),
                row.contentMarkdown(),
                row.traceSummary(),
                artifactPayloadReadModelAssembler.readCitations(row.citationsJson()),
                artifactPayloadReadModelAssembler.readRuntimeTrace(row.resultPayloadJson()),
                artifactJobReadRepository.loadArtifactFiles(row.versionId()),
                row.createdAt()
        );
    }

    @Transactional
    public ArtifactSavedSourceResponse saveVersionAsSource(
            String workspaceId,
            String artifactJobId,
            int versionNo
    ) {
        requireWorkspace(workspaceId);
        exportService.requireVersionReadable(workspaceId, artifactJobId, versionNo);
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
        exportService.requireVersionReadable(workspaceId, artifactJobId, versionNo);
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
        ArtifactJobTaskRow row = findByTaskId(taskId);
        requireFrozenSourcesVisible(row);
        memoryRevisionGuard.requireActive(taskId);
        String activeRequirement = contextV2ShadowSnapshots.activeRequirement(
                taskId, row.workspaceId(), row.inputSnapshotId(), row.userRequirement());
        Map<String, Object> inputs = artifactPayloadReadModelAssembler.readInputs(row.inputsJson());
        return new ArtifactWorkerInputResponse(
                row.taskId(),
                row.workspaceId(),
                row.artifactJobId(),
                row.inputSnapshotId(),
                catalogDigestFrom(row.compilerVersion()),
                row.replayAvailability(),
                readCapturedSourceScope(row.workspaceId(), row.sourceScopeJson()),
                readUpstreamRefs(row.upstreamRefsJson()),
                new WorkerContextSnapshotResponse(row.contextSnapshotId() == null ? "" : row.contextSnapshotId()),
                artifactPayloadReadModelAssembler.readControlPack(row.controlPackJson()),
                ArtifactWorkerInputPayload.skillFirst(
                        row.skillKey(),
                        blankIfNull(row.styleProfileKey()),
                        blankIfNull(row.contextSnapshotId()),
                        row.userRequirement(),
                        activeRequirement == null ? row.userRequirement() : activeRequirement,
                        inputs
                ),
                contextV2ShadowSnapshots.readForWorker(taskId)
        );
    }

    @Transactional
    public void markRunning(String taskId) {
        artifactJobWriteRepository.updateStatusByTaskId(taskId, "RUNNING");
    }

    @Transactional
    public void markWaiting(String taskId, String waitStatus) {
        artifactJobWriteRepository.updateStatusByTaskId(
                taskId,
                blankToNull(waitStatus) == null ? "WAITING_FOR_PROVIDER" : waitStatus.trim()
        );
    }

    @Transactional
    public CompletionOutcome completeFromWorker(String taskId, com.noteweave.worker.WorkerCompleteRequest request) {
        ArtifactJobTaskRow row = findByTaskId(taskId);
        String markdown = extractMarkdown(request.resultPayload());
        requireVerifiedCandidate(request);
        requireFrozenSourcesVisible(row);
        memoryRevisionGuard.requireActive(taskId);
        contextV2ShadowSnapshots.activeRequirement(
                taskId, row.workspaceId(), row.inputSnapshotId(), row.userRequirement());
        String materialBundleId = videoMaterialService.validateCandidateReference(
                taskId, request.resultPayload());
        ArtifactCandidate candidate = ArtifactCandidate.from(taskId, row.inputSnapshotId(),
                catalogDigestFrom(row.compilerVersion()), expectedArtifactType(row), request, markdown);
        int claimed = artifactJobWriteRepository.claimCompletion(row.artifactJobId(), taskId);
        if (claimed != 1) {
            throw new BusinessException(
                    "ARTIFACT_JOB_TERMINAL_CONFLICT",
                    "Artifact job can no longer accept a completion callback",
                    HttpStatus.CONFLICT
            );
        }
        int nextVersionNo = artifactJobWriteRepository.lockNextVersionNo(row.workspaceId(), row.artifactJobId());
        String versionId = artifactJobWriteRepository.reservedVersionId(taskId);
        artifactJobWriteRepository.appendCompletedVersion(
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
        videoMaterialService.linkPublishedVersion(versionId, materialBundleId);
        artifactJobWriteRepository.recordCandidateReceipt(taskId, candidate.candidateId(),
                candidate.digest(), versionId, row.inputSnapshotId());
        artifactJobWriteRepository.completeJob(row.artifactJobId(), request.resultTitle(), nextVersionNo);
        return new CompletionOutcome("ARTIFACT_VERSIONED", "产物版本已生成：" + request.resultTitle(), versionId);
    }

    public ArtifactSourceWindowPageResponse readSourceWindows(
            String taskId, String sourceId, String sourceSnapshotId,
            String cursor, int maxWindows, int maxBytes) {
        if (maxWindows < 1 || maxWindows > 32 || maxBytes < 1024 || maxBytes > 262_144) {
            throw new BusinessException("ARTIFACT_WINDOW_BUDGET_INVALID",
                    "资料窗口预算超出允许范围", HttpStatus.BAD_REQUEST);
        }
        ArtifactJobTaskRow run = findByTaskId(taskId);
        WorkerSourceScopeItemResponse frozen = readCapturedSourceScope(
                run.workspaceId(), run.sourceScopeJson()).stream()
                .filter(source -> sourceId.equals(source.sourceId())
                        && sourceSnapshotId.equals(source.sourceSnapshotId()))
                .findFirst().orElseThrow(() -> new BusinessException(
                        "ARTIFACT_WINDOW_SCOPE_DENIED", "资料不属于该 Run 的冻结范围", HttpStatus.FORBIDDEN));
        requireFrozenSourcesVisible(run);
        memoryRevisionGuard.requireActive(taskId);
        int afterChunkNo = -1;
        int afterWindowNo = -1;
        if (cursor != null && !cursor.isBlank()) {
            String[] parts = cursor.split(":", -1);
            try {
                if (parts.length != 2) throw new NumberFormatException("cursor shape");
                afterChunkNo = Integer.parseInt(parts[0]);
                afterWindowNo = Integer.parseInt(parts[1]);
                if (afterChunkNo < 0 || afterWindowNo < 0) throw new NumberFormatException("negative cursor");
            } catch (NumberFormatException ex) {
                throw new BusinessException("ARTIFACT_WINDOW_CURSOR_INVALID",
                        "资料窗口游标无效", HttpStatus.BAD_REQUEST);
            }
        }
        List<ArtifactJobReadRepository.ArtifactSourceWindowRow> rows =
                artifactJobReadRepository.readSourceWindows(run.workspaceId(), frozen.sourceId(),
                        frozen.sourceSnapshotId(), afterChunkNo, afterWindowNo, maxWindows + 1);
        java.util.ArrayList<ArtifactSourceWindowResponse> selected = new java.util.ArrayList<>();
        int remainingBytes = maxBytes;
        boolean budgetExhausted = false;
        for (ArtifactJobReadRepository.ArtifactSourceWindowRow window : rows) {
            if (selected.size() == maxWindows) break;
            byte[] bytes = window.content().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (bytes.length > remainingBytes) {
                if (selected.isEmpty()) {
                    throw new BusinessException("ARTIFACT_WINDOW_BUDGET_INSUFFICIENT",
                            "单个资料窗口超过本页字节预算", HttpStatus.CONFLICT);
                }
                budgetExhausted = true;
                break;
            }
            remainingBytes -= bytes.length;
            selected.add(new ArtifactSourceWindowResponse(window.windowId(), window.chunkNo(),
                    window.windowNo(), window.heading(), window.locationInfo(), window.content(),
                    java.util.HexFormat.of().formatHex(sha256Bytes(bytes))));
        }
        boolean more = rows.size() > selected.size();
        ArtifactSourceWindowResponse last = selected.isEmpty() ? null : selected.get(selected.size() - 1);
        return new ArtifactSourceWindowPageResponse(sourceId, sourceSnapshotId, List.copyOf(selected),
                more ? last.chunkNo() + ":" + last.windowNo() : "", budgetExhausted);
    }

    private byte[] sha256Bytes(byte[] bytes) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public void validateCandidateReplay(String taskId, com.noteweave.worker.WorkerCompleteRequest request) {
        ArtifactJobTaskRow row = findByTaskId(taskId);
        ArtifactCandidate candidate = ArtifactCandidate.from(taskId, row.inputSnapshotId(),
                catalogDigestFrom(row.compilerVersion()), expectedArtifactType(row), request,
                extractMarkdown(request.resultPayload()));
        List<Map<String, Object>> receipts = artifactJobWriteRepository.candidateReceipts(taskId);
        if (receipts.size() != 1 || !candidate.candidateId().equals(receipts.get(0).get("candidate_id"))
                || !candidate.digest().equals(receipts.get(0).get("candidate_digest"))) {
            throw new BusinessException("ARTIFACT_CANDIDATE_CONFLICT",
                    "Task already committed a different candidate", HttpStatus.CONFLICT);
        }
    }

    private void requireVerifiedCandidate(com.noteweave.worker.WorkerCompleteRequest request) {
        if (request.resultPayload() == null || !request.resultPayload().containsKey("candidate")) {
            return; // Legacy Worker callbacks remain readable during rollout.
        }
        Object raw = request.resultPayload().get("verification");
        if (!(raw instanceof Map<?, ?> verification)
                || !("PASS".equals(verification.get("status"))
                || "WARN".equals(verification.get("status")))) {
            throw new BusinessException("ARTIFACT_CONTENT_NOT_VERIFIED",
                    "Candidate content did not pass final verification", HttpStatus.CONFLICT);
        }
    }

    private void requireFrozenSourcesVisible(ArtifactJobTaskRow row) {
        for (WorkerSourceScopeItemResponse source : readCapturedSourceScope(
                row.workspaceId(), row.sourceScopeJson())) {
            if (source.sourceSnapshotId() == null || source.sourceSnapshotId().isBlank()) {
                throw new BusinessException("ARTIFACT_SOURCE_SNAPSHOT_MISSING",
                        "产物输入缺少冻结 Source Snapshot", HttpStatus.CONFLICT);
            }
            if (!artifactJobWriteRepository.hasReadableSourceSnapshot(
                    row.workspaceId(), source.sourceId(), source.sourceSnapshotId())) {
                throw new BusinessException("ARTIFACT_SOURCE_REVOKED",
                        "冻结资料已删除、撤权或不可用", HttpStatus.CONFLICT);
            }
            generatedSourceGate.requireReadable(row.workspaceId(),
                    source.generatedBy(), source.generatedRefId());
        }
        for (ArtifactUpstreamRefRequest ref : readUpstreamRefs(row.upstreamRefsJson())) {
            if (!artifactJobReadRepository.validUpstreamRef(
                    row.workspaceId(), ref.refType(), ref.refId(), ref.revisionId())) {
                throw new BusinessException("ARTIFACT_UPSTREAM_REVOKED",
                        "上游引用已删除、撤权或不可用", HttpStatus.CONFLICT);
            }
            requireGeneratedUpstreamReadable(row.workspaceId(), ref);
        }
    }

    @Transactional
    public void markFailed(String taskId, WorkerFailRequest request) {
        int updated = artifactJobWriteRepository.markFailed(taskId);
        if (updated != 1) {
            throw new BusinessException(
                    "ARTIFACT_JOB_TERMINAL_CONFLICT",
                    "Artifact job can no longer accept a failure callback",
                    HttpStatus.CONFLICT
            );
        }
    }

    private ArtifactJobTaskRow findByTaskId(String taskId) {
        return artifactJobReadRepository.findByTaskId(taskId);
    }

    private ArtifactRegenerationRow loadRegenerationRow(String workspaceId, String artifactJobId) {
        return artifactJobReadRepository.loadRegenerationRow(workspaceId, artifactJobId);
    }

    private ArtifactRollbackRow loadRollbackRow(String workspaceId, String artifactJobId, int versionNo) {
        return artifactJobReadRepository.loadRollbackRow(workspaceId, artifactJobId, versionNo);
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
        java.util.ArrayList<WorkerSourceScopeItemResponse> captured = new java.util.ArrayList<>();
        for (String sourceId : sourceIds) {
            List<WorkerSourceScopeItemResponse> matches = loadSourceScopeItem(workspaceId, sourceId);
            if (matches.isEmpty()) {
                throw new BusinessException(
                        "ARTIFACT_SOURCE_SCOPE_UNAVAILABLE",
                        "Artifact source scope contains a missing, cross-workspace, or non-ready Source");
            }
            captured.add(matches.get(0));
        }
        return List.copyOf(captured);
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
            if (!artifactJobReadRepository.validUpstreamRef(
                    workspaceId, ref.refType(), ref.refId(), ref.revisionId())) {
                throw new BusinessException("ARTIFACT_UPSTREAM_REF_INVALID",
                        "Artifact upstream ref does not belong to this workspace or revision");
            }
            requireGeneratedUpstreamReadable(workspaceId, ref);
        }
        return List.copyOf(refs);
    }

    private List<WorkerSourceScopeItemResponse> loadSourceScopeItem(String workspaceId, String sourceId) {
        List<WorkerSourceScopeItemResponse> items = artifactJobReadRepository.loadSourceScopeItem(
                workspaceId, sourceId);
        for (WorkerSourceScopeItemResponse item : items) {
            generatedSourceGate.requireReadable(workspaceId, item.generatedBy(), item.generatedRefId());
        }
        return items;
    }

    private void requireGeneratedUpstreamReadable(String workspaceId, ArtifactUpstreamRefRequest ref) {
        if ("RESEARCH_REPORT".equals(ref.refType())) {
            generatedSourceGate.requireReadable(workspaceId, "research_agent", ref.refId());
        } else if ("SOURCE_SNAPSHOT".equals(ref.refType())
                && !generatedSourceGate.readableSourceIds(workspaceId, List.of(ref.refId()))
                        .contains(ref.refId())) {
            throw new BusinessException("ARTIFACT_UPSTREAM_REVOKED",
                    "上游引用已删除、撤权或不可用", HttpStatus.CONFLICT);
        }
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
            List<WorkerSourceScopeItemResponse> value = objectMapper.readValue(json, new TypeReference<>() {
            });
            if (value == null || value.stream().anyMatch(java.util.Objects::isNull)) {
                throw new BusinessException(
                        "ARTIFACT_INPUT_SNAPSHOT_PARSE_FAILED",
                        "Artifact input snapshot must contain source objects");
            }
            return value;
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
            List<ArtifactUpstreamRefRequest> value = objectMapper.readValue(json, new TypeReference<>() {
            });
            if (value == null || value.stream().anyMatch(java.util.Objects::isNull)) {
                throw new BusinessException(
                        "ARTIFACT_UPSTREAM_REFS_PARSE_FAILED",
                        "Artifact upstream refs must be an array");
            }
            return value;
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
            List<String> value = objectMapper.readValue(json, new TypeReference<>() {
            });
            if (value == null || value.stream().anyMatch(item -> item == null || item.isBlank())) {
                throw new BusinessException(
                        "ARTIFACT_SOURCE_SCOPE_PARSE_FAILED",
                        "Artifact source scope must be a non-empty string array");
            }
            return value;
        } catch (JsonProcessingException ex) {
            throw new BusinessException("ARTIFACT_SOURCE_SCOPE_PARSE_FAILED", "产物资料范围解析失败");
        }
    }

    private void requireArtifactJob(String workspaceId, String artifactJobId) {
        artifactJobReadRepository.requireJob(workspaceId, artifactJobId);
    }

    private ArtifactVersionSourceRow loadVersionForSource(
            String workspaceId,
            String artifactJobId,
            int versionNo
    ) {
        return artifactJobReadRepository.loadVersionForSource(workspaceId, artifactJobId, versionNo);
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

    private String expectedArtifactType(ArtifactJobTaskRow row) {
        String frozenDigest = catalogDigestFrom(row.compilerVersion());
        return frozenDigest.equals(artifactSkillCatalogService.catalogDigest())
                ? artifactSkillCatalogService.resolveActionKey(row.skillKey()) : "";
    }

    private static String catalogDigestFrom(String compilerVersion) {
        String prefix = "artifact-input-v1@sha256:";
        return compilerVersion != null && compilerVersion.startsWith(prefix)
                ? compilerVersion.substring(prefix.length()) : "";
    }

}
