package com.noteweave.retrieval.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.answer.strategy.AnswerMode;
import com.noteweave.answer.strategy.EvidenceBundleSnapshot;
import com.noteweave.answer.strategy.EvidenceBundleSnapshot.EvidenceSnapshot;
import com.noteweave.answer.strategy.RetrievalExecutionTrace;
import com.noteweave.answer.strategy.RetrievalExecutionTrace.SelectedEvidenceTrace;
import com.noteweave.answer.strategy.RetrievalPlan;
import com.noteweave.chat.QaAnswerModeStrategy;
import com.noteweave.chat.QaPassageEvidenceRetriever;
import com.noteweave.retrieval.QaEvidenceRelevancePolicy;
import com.noteweave.retrieval.QaEvidenceSelectionPolicy;
import com.noteweave.retrieval.QaRetrievalStrategyProfile;
import com.noteweave.retrieval.eval.QaAnswerRunShadowExportRequest.CaseRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exports completed QA AnswerRuns from their persisted final bundle, execution trace and citations.
 * This is intentionally separate from the Elasticsearch-only annotation CLI: online ownership,
 * fallback and final budget decisions have already happened before these artifacts are persisted.
 */
@Component
public class QaAnswerRunShadowExportService {

    private final ObjectMapper databaseObjectMapper;
    private final ObjectMapper artifactObjectMapper;
    private final RetrievalExecutionShadowExporter exporter;
    private final QaAnswerRunShadowExportReadRepository readRepository;
    private final QaAnswerRunShadowArtifactValidator artifactValidator;

    public QaAnswerRunShadowExportService(
            ObjectMapper objectMapper,
            RetrievalExecutionShadowExporter exporter,
            QaAnswerRunShadowExportReadRepository readRepository
    ) {
        this.databaseObjectMapper = objectMapper;
        this.artifactObjectMapper = new ObjectMapper().findAndRegisterModules();
        this.exporter = exporter;
        this.readRepository = readRepository;
        this.artifactValidator = new QaAnswerRunShadowArtifactValidator(objectMapper);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RetrievalShadowSnapshot export(
            QaGoldAnnotationRequest annotationRequest,
            QaAnswerRunShadowExportRequest request,
            String salt
    ) {
        if (salt == null || salt.length() < 16) {
            throw new IllegalArgumentException(
                    "Retrieval export salt must contain at least 16 characters");
        }
        Map<String, QaGoldAnnotationRequest.CaseRequest> annotationCases =
                validateRequest(annotationRequest, request);
        QaRetrievalStrategyProfile requestedProfile = requireRequestedProfile(
                request.strategyProfile());
        Map<String, CaseRequest> mappedCases = new LinkedHashMap<>();
        request.cases().forEach(item -> mappedCases.put(item.caseId(), item));
        List<String> runIds = annotationRequest.cases().stream()
                .map(item -> mappedCases.get(item.id()).answerRunId())
                .toList();
        Map<String, QaAnswerRunShadowExportReadRepository.RunRow> runs =
                readRepository.loadRuns(runIds);
        Map<String, String> retrievalSummaryByRun = readRepository.loadRetrievalSummaries(runIds);
        Map<String, List<QaAnswerRunShadowExportReadRepository.CitationRow>> citationsByRun =
                readRepository.loadCitations(runIds);

        List<RetrievalExecutionShadowExporter.CaseExecution> executions = new ArrayList<>();
        for (QaGoldAnnotationRequest.CaseRequest annotationCase : annotationRequest.cases()) {
            CaseRequest requestedCase = mappedCases.get(annotationCase.id());
            QaAnswerRunShadowExportReadRepository.RunRow run =
                    require(runs, requestedCase.answerRunId(), "AnswerRun");
            String summaryJson = require(
                    retrievalSummaryByRun, requestedCase.answerRunId(), "retrieval.summary");
            RetrievalPlan plan = readPlan(run);
            EvidenceBundleSnapshot bundle = artifactValidator.readBundle(run);
            QaAnswerRunShadowArtifactValidator.SummarySnapshot summary =
                    artifactValidator.readSummary(run.id(), summaryJson);
            RetrievalExecutionTrace trace = summary.trace();
            QaRetrievalStrategyProfile actualProfile = validateRun(
                    annotationCases.get(annotationCase.id()), run, plan, bundle, summary);
            if (actualProfile != requestedProfile) {
                throw new IllegalStateException(
                        "AnswerRun strategy profile does not match the requested export cohort");
            }
            Map<String, List<String>> citationLabels = validateCitations(
                    run,
                    bundle,
                    citationsByRun.getOrDefault(run.id(), List.of())
            );
            executions.add(new RetrievalExecutionShadowExporter.CaseExecution(
                    requestedCase.caseId(), trace, bundle, citationLabels));
        }
        return exporter.export(
                request.snapshotVersion(), requestedProfile.profileVersion(), executions, salt);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ExportResult exportAndWrite(
            Path annotationRequestPath,
            Path runMapPath,
            Path outputPath,
            String salt
    ) throws Exception {
        Path annotationInput = annotationRequestPath.toAbsolutePath().normalize();
        Path runMapInput = runMapPath.toAbsolutePath().normalize();
        Path output = outputPath.toAbsolutePath().normalize();
        if (output.equals(annotationInput) || output.equals(runMapInput)) {
            throw new IllegalArgumentException("QA shadow output must not overwrite an input artifact");
        }
        QaGoldAnnotationRequest annotationRequest = artifactObjectMapper.readValue(
                annotationInput.toFile(), QaGoldAnnotationRequest.class);
        QaAnswerRunShadowExportRequest request = artifactObjectMapper.readValue(
                runMapInput.toFile(), QaAnswerRunShadowExportRequest.class);
        RetrievalShadowSnapshot snapshot = export(annotationRequest, request, salt);
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        artifactObjectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), snapshot);
        return new ExportResult(snapshot, request.cases().size());
    }

    private Map<String, QaGoldAnnotationRequest.CaseRequest> validateRequest(
            QaGoldAnnotationRequest annotationRequest,
            QaAnswerRunShadowExportRequest request
    ) {
        if (annotationRequest == null
                || !QaGoldAnnotationDraftCapture.REQUEST_SCHEMA_VERSION.equals(
                annotationRequest.schemaVersion())
                || annotationRequest.datasetVersion().isBlank()
                || annotationRequest.candidatePoolSize() != 12
                || annotationRequest.cases().isEmpty()) {
            throw new IllegalArgumentException("QA annotation request is invalid for online shadow export");
        }
        if (request == null
                || !QaAnswerRunShadowExportRequest.SCHEMA_VERSION.equals(request.schemaVersion())) {
            throw new IllegalArgumentException("QA AnswerRun shadow request schema is unsupported");
        }
        if (request.snapshotVersion().isBlank()
                || request.strategyProfile().isBlank()
                || request.cases().isEmpty()) {
            throw new IllegalArgumentException(
                    "QA AnswerRun shadow request requires snapshotVersion, strategyProfile and cases");
        }
        requireRequestedProfile(request.strategyProfile());
        Set<String> caseIds = new LinkedHashSet<>();
        Set<String> runIds = new LinkedHashSet<>();
        for (CaseRequest requestedCase : request.cases()) {
            if (requestedCase == null
                    || requestedCase.caseId().isBlank()
                    || requestedCase.answerRunId().isBlank()
                    || !caseIds.add(requestedCase.caseId())
                    || !runIds.add(requestedCase.answerRunId())) {
                throw new IllegalArgumentException(
                        "QA AnswerRun shadow case and AnswerRun ids must be unique and non-blank");
            }
        }
        Map<String, QaGoldAnnotationRequest.CaseRequest> annotationCases = new LinkedHashMap<>();
        for (QaGoldAnnotationRequest.CaseRequest item : annotationRequest.cases()) {
            if (item == null || item.id().isBlank() || item.workspaceId().isBlank()
                    || item.query().isBlank() || item.topK() <= 0
                    || item.topK() > QaEvidenceSelectionPolicy.DEFAULT_EVIDENCE_LIMIT
                    || item.allowedSourceIds().isEmpty()
                    || item.allowedSourceIds().stream().anyMatch(String::isBlank)
                    || annotationCases.putIfAbsent(item.id(), item) != null) {
                throw new IllegalArgumentException("QA annotation request cases are invalid");
            }
        }
        if (!caseIds.equals(annotationCases.keySet())) {
            throw new IllegalArgumentException(
                    "QA annotation request and AnswerRun map case ids must match exactly");
        }
        return Map.copyOf(annotationCases);
    }

    private QaRetrievalStrategyProfile requireRequestedProfile(String profileVersion) {
        if (QaRetrievalStrategyProfile.V2.profileVersion().equals(profileVersion)) {
            return QaRetrievalStrategyProfile.V2;
        }
        throw new IllegalArgumentException(
                "QA AnswerRun shadow request strategyProfile is unsupported");
    }

    private RetrievalPlan readPlan(QaAnswerRunShadowExportReadRepository.RunRow run) {
        if (run.planJson().isBlank()) {
            throw new IllegalStateException("AnswerRun has no persisted RetrievalPlan");
        }
        try {
            return databaseObjectMapper.readValue(run.planJson(), RetrievalPlan.class);
        } catch (Exception ex) {
            throw new IllegalStateException("AnswerRun RetrievalPlan cannot be decoded", ex);
        }
    }

    private QaRetrievalStrategyProfile validateRun(
            QaGoldAnnotationRequest.CaseRequest annotationCase,
            QaAnswerRunShadowExportReadRepository.RunRow run,
            RetrievalPlan plan,
            EvidenceBundleSnapshot bundle,
            QaAnswerRunShadowArtifactValidator.SummarySnapshot summary
    ) {
        RetrievalExecutionTrace trace = summary.trace();
        if (!"QA".equals(run.mode()) || !"COMPLETED".equals(run.status())) {
            throw new IllegalStateException("Only completed QA AnswerRuns can be exported");
        }
        if (run.workspaceId().isBlank()
                || run.conversationId().isBlank()
                || run.queryMessageId().isBlank()
                || run.answerMessageId().isBlank()
                || !"USER".equals(run.queryRole())
                || !"QA".equals(run.queryAnswerMode())
                || !"ASSISTANT".equals(run.answerRole())
                || !"QA".equals(run.answerAnswerMode())
                || !annotationCase.workspaceId().equals(run.workspaceId())
                || !annotationCase.query().equals(run.queryText().trim())) {
            throw new IllegalStateException("AnswerRun identity does not match the annotation case");
        }
        QaRetrievalStrategyProfile profile = validatePlan(annotationCase, run, plan);
        if (!EvidenceBundleSnapshot.SCHEMA_VERSION.equals(bundle.schemaVersion())
                || !RetrievalExecutionTrace.SCHEMA_VERSION.equals(trace.schemaVersion())
                || run.planVersion().isBlank()
                || !run.planVersion().equals(bundle.retrievalPlanVersion())
                || !run.planVersion().equals(trace.planVersion())
                || !run.planVersion().equals(summary.planVersion())
                || !trace.schemaVersion().equals(summary.traceSchemaVersion())) {
            throw new IllegalStateException(
                    "AnswerRun plan/trace/bundle schema is inconsistent");
        }
        validateStrategyAudit(run, profile, summary, trace);
        List<EvidenceSnapshot> evidence = bundle.evidence();
        List<SelectedEvidenceTrace> selected = trace.selectedEvidence();
        long selectedCharacters = evidence.stream().mapToLong(EvidenceSnapshot::characterCost).sum();
        if (evidence.size() > QaEvidenceSelectionPolicy.DEFAULT_BUNDLE_EVIDENCE_LIMIT
                || selectedCharacters > QaEvidenceSelectionPolicy.DEFAULT_BUNDLE_CHARACTER_LIMIT
                || trace.selectedEvidenceCount() != evidence.size()
                || selected.size() != evidence.size()
                || trace.selectedEvidenceCharacters() != selectedCharacters
                || summary.retrievalLatencyMicros() != trace.totalLatencyMicros()
                || summary.candidateCount() != trace.rawCandidateCount()
                || summary.admittedCandidateCount() != trace.admittedCandidateCount()
                || summary.selectedEvidenceCount() != trace.selectedEvidenceCount()
                || summary.selectedEvidenceCharacters() != trace.selectedEvidenceCharacters()
                || summary.degraded() != bundle.degraded()
                || !summary.degradationReasons().equals(bundle.degradationReasons())
                || bundle.degraded() == bundle.degradationReasons().isEmpty()
                || trace.admittedCandidateCount() < evidence.size()
                || trace.rawCandidateCount() < evidence.size()) {
            throw new IllegalStateException(
                    "AnswerRun trace counts do not match final EvidenceBundle");
        }
        Set<String> evidenceIds = new LinkedHashSet<>();
        Set<String> passageIds = new LinkedHashSet<>();
        for (int index = 0; index < evidence.size(); index++) {
            EvidenceSnapshot item = evidence.get(index);
            SelectedEvidenceTrace selectedItem = selected.get(index);
            if (item.rank() != index + 1
                    || item.evidenceId() == null || item.evidenceId().isBlank()
                    || !evidenceIds.add(item.evidenceId())
                    || !"PASSAGE".equals(item.kind())
                    || item.sourceId() == null || item.sourceId().isBlank()
                    || item.sourceSnapshotId() == null || item.sourceSnapshotId().isBlank()
                    || item.passageId() == null || item.passageId().isBlank()
                    || !("passage:" + item.passageId()).equals(item.evidenceId())
                    || !passageIds.add(item.passageId())
                    || !annotationCase.allowedSourceIds().contains(item.sourceId())
                    || !("workspace-source:" + item.sourceId()).equals(item.accessScope())
                    || item.freshAt() == null
                    || selectedItem.rank() != index + 1
                    || !item.evidenceId().equals(selectedItem.evidenceId())
                    || !item.kind().equals(selectedItem.kind())
                    || !Double.isFinite(item.rawScore())
                    || !Double.isFinite(item.fusedScore())
                    || !Double.isFinite(item.rerankScore())
                    || Double.compare(item.rawScore(), selectedItem.rawScore()) != 0
                    || Double.compare(item.fusedScore(), selectedItem.fusedScore()) != 0
                    || Double.compare(item.rerankScore(), selectedItem.rerankScore()) != 0
                    || item.characterCost() != selectedItem.characterCost()) {
                throw new IllegalStateException(
                        "AnswerRun final evidence identity/order drifted from trace");
            }
        }
        validateQaCandidateCount(
                run.id(), trace, bundle.degraded(), bundle.degradationReasons());
        return profile;
    }

    private void validateStrategyAudit(
            QaAnswerRunShadowExportReadRepository.RunRow run,
            QaRetrievalStrategyProfile profile,
            QaAnswerRunShadowArtifactValidator.SummarySnapshot summary,
            RetrievalExecutionTrace trace
    ) {
        boolean legacyV2 = isLegacyV2PlanVersion(run.planVersion());
        boolean summaryMatches = profile.profileVersion().equals(summary.strategyProfile())
                && profile.relevancePolicyVersion().equals(summary.relevancePolicy())
                && profile.selectionPolicyVersion().equals(summary.selectionPolicy());
        boolean legacySummaryAbsent = summary.strategyProfile().isBlank()
                && summary.relevancePolicy().isBlank()
                && summary.selectionPolicy().isBlank();
        if ((!legacyV2 && !summaryMatches)
                || (legacyV2 && !legacySummaryAbsent && !summaryMatches)
                || trace.steps().size() != 1) {
            throw new IllegalStateException(
                    "AnswerRun strategy profile audit is inconsistent");
        }
        Long persistedFlag = trace.steps().get(0).measurements().get("strategy_v2_enabled");
        long expectedFlag = profile.v2Enabled() ? 1L : 0L;
        if ((!legacyV2 && !Long.valueOf(expectedFlag).equals(persistedFlag))
                || (legacyV2 && persistedFlag != null && persistedFlag != expectedFlag)) {
            throw new IllegalStateException(
                    "AnswerRun strategy profile measurement is inconsistent");
        }
    }

    private QaRetrievalStrategyProfile validatePlan(
            QaGoldAnnotationRequest.CaseRequest annotationCase,
            QaAnswerRunShadowExportReadRepository.RunRow run,
            RetrievalPlan plan
    ) {
        if (plan == null
                || !run.planVersion().equals(plan.version())
                || plan.mode() != AnswerMode.QA
                || plan.steps().size() != 1
                || plan.budget() == null) {
            throw new IllegalStateException("AnswerRun RetrievalPlan is not QA-compatible");
        }
        RetrievalPlan.Step step = plan.steps().get(0);
        Map<String, String> filters = step.filters();
        String expectedSourceIds = String.join(",", annotationCase.allowedSourceIds().stream()
                .sorted().toList());
        String actualSourceIds = text(filters.get("source_ids"));
        QaRetrievalStrategyProfile profile = resolvePlanProfile(plan, step);
        if (!QaPassageEvidenceRetriever.CHANNEL.equals(step.channel())
                || step.candidateLimit() != QaRetrievalStrategyProfile.CANDIDATE_LIMIT
                || Double.compare(step.weight(), QaRetrievalStrategyProfile.STEP_WEIGHT) != 0
                || !annotationCase.workspaceId().equals(filters.get("workspace_id"))
                || !"ACTIVE".equals(filters.get("snapshot_status"))
                || !expectedSourceIds.equals(actualSourceIds)
                || plan.budget().maxEvidence() != profile.maxEvidence()
                || plan.budget().maxEvidenceCharacters() != profile.maxEvidenceCharacters()
                || plan.budget().maxGraphHops() != 0
                || plan.budget().maxGraphNodes() != 0
                || plan.budget().maxGraphEdges() != 60
                || plan.budget().maxGraphCharacters() != 4_000) {
            throw new IllegalStateException(
                    "AnswerRun RetrievalPlan does not match the QA annotation case");
        }
        return profile;
    }

    private QaRetrievalStrategyProfile resolvePlanProfile(
            RetrievalPlan plan,
            RetrievalPlan.Step step
    ) {
        try {
            return QaRetrievalStrategyProfile.fromPlan(plan, step);
        } catch (IllegalArgumentException ex) {
            Map<String, String> filters = step.filters();
            boolean legacyV2 = isLegacyV2PlanVersion(plan.version())
                    && QaPassageEvidenceRetriever.CHANNEL.equals(step.channel())
                    && step.candidateLimit() == QaRetrievalStrategyProfile.CANDIDATE_LIMIT
                    && Double.compare(step.weight(), QaRetrievalStrategyProfile.STEP_WEIGHT) == 0
                    && QaEvidenceRelevancePolicy.POLICY_VERSION_V2.equals(
                    filters.get(QaRetrievalStrategyProfile.FILTER_RELEVANCE_POLICY))
                    && QaEvidenceSelectionPolicy.POLICY_VERSION.equals(
                    filters.get(QaRetrievalStrategyProfile.FILTER_SELECTION_POLICY))
                    && text(filters.get(QaRetrievalStrategyProfile.FILTER_PROFILE)).isBlank()
                    && text(filters.get(QaRetrievalStrategyProfile.FILTER_V2_ENABLED)).isBlank()
                    && plan.budget().maxEvidence()
                    == QaRetrievalStrategyProfile.V2.maxEvidence()
                    && plan.budget().maxEvidenceCharacters()
                    == QaRetrievalStrategyProfile.V2.maxEvidenceCharacters()
                    && plan.budget().maxGraphHops() == 0
                    && plan.budget().maxGraphNodes() == 0
                    && plan.budget().maxGraphEdges() == 60
                    && plan.budget().maxGraphCharacters() == 4_000;
            if (legacyV2) {
                return QaRetrievalStrategyProfile.V2;
            }
            throw new IllegalStateException(
                    "AnswerRun RetrievalPlan has an unsupported QA strategy tuple", ex);
        }
    }

    private boolean isLegacyV2PlanVersion(String planVersion) {
        return "qa-passage-v1".equals(planVersion);
    }

    private void validateQaCandidateCount(
            String runId,
            RetrievalExecutionTrace trace,
            boolean degraded,
            List<String> degradationReasons
    ) {
        if (trace.steps().size() != 1
                || !QaPassageEvidenceRetriever.CHANNEL.equals(trace.steps().get(0).channel())) {
            throw new IllegalStateException("QA AnswerRun trace must contain one QA_PASSAGE step");
        }
        RetrievalExecutionTrace.StepTrace step = trace.steps().get(0);
        Map<String, Long> measurements = step.measurements();
        Long fallbackUsed = measurements.get("mysql_fallback_used");
        Long selected = measurements.get("selected_count");
        if (step.stepIndex() != 0
                || step.candidateLimit() != 12
                || step.rawCandidateCount() != trace.rawCandidateCount()
                || step.admittedCandidateCount() != trace.admittedCandidateCount()
                || step.admittedCandidateCount() > step.rawCandidateCount()
                || step.admittedCandidateCount() != step.rawCandidateCount()
                || step.degraded() != degraded
                || !step.degradationReasons().equals(degradationReasons)
                || fallbackUsed == null || (fallbackUsed != 0L && fallbackUsed != 1L)
                || (fallbackUsed == 1L
                && (!degraded || !degradationReasons.contains("qa_mysql_fallback")))
                || (fallbackUsed == 0L && degradationReasons.contains("qa_mysql_fallback"))
                || (fallbackUsed == 0L
                && measurements.getOrDefault("mysql_candidate_count", 0L) != 0L)
                || selected == null || selected < 0 || selected > Integer.MAX_VALUE
                || selected.intValue() != step.rawCandidateCount()) {
            throw new IllegalStateException("QA AnswerRun trace measurements are incomplete");
        }
        Long candidateCount = fallbackUsed == 1L
                ? measurements.get("mysql_candidate_count")
                : measurements.get("primary_hit_count");
        if (candidateCount == null
                || candidateCount < step.rawCandidateCount()
                || candidateCount > Integer.MAX_VALUE) {
            throw new IllegalStateException("QA AnswerRun backend candidate count is invalid");
        }
    }

    private Map<String, List<String>> validateCitations(
            QaAnswerRunShadowExportReadRepository.RunRow run,
            EvidenceBundleSnapshot bundle,
            List<QaAnswerRunShadowExportReadRepository.CitationRow> citations
    ) {
        if (citations.size() != bundle.evidence().size()) {
            throw new IllegalStateException(
                    "AnswerRun citation count does not match final EvidenceBundle");
        }
        Map<String, List<String>> labels = new LinkedHashMap<>();
        for (int index = 0; index < bundle.evidence().size(); index++) {
            EvidenceSnapshot evidence = bundle.evidence().get(index);
            QaAnswerRunShadowExportReadRepository.CitationRow citation = citations.get(index);
            if (citation.sortOrder() != index
                    || citation.citationId() == null || citation.citationId().isBlank()
                    || !run.workspaceId().equals(citation.workspaceId())
                    || !evidence.sourceId().equals(citation.sourceId())
                    || !evidence.sourceSnapshotId().equals(citation.sourceSnapshotId())
                    || !evidence.passageId().equals(citation.sourceChunkId())
                    || !run.workspaceId().equals(citation.sourceWorkspaceId())
                    || !evidence.sourceId().equals(citation.snapshotSourceId())
                    || !run.workspaceId().equals(citation.chunkWorkspaceId())
                    || !evidence.sourceId().equals(citation.chunkSourceId())
                    || !evidence.sourceSnapshotId().equals(citation.chunkSnapshotId())) {
                throw new IllegalStateException(
                        "AnswerRun citation identity/order does not match final evidence");
            }
            labels.put(evidence.passageId(), List.of("citation-label:" + evidence.passageId()));
        }
        return Map.copyOf(labels);
    }

    private <T> T require(Map<String, T> values, String id, String label) {
        T value = values.get(id);
        if (value == null) {
            throw new IllegalStateException(label + " is missing for one requested AnswerRun");
        }
        return value;
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    public record ExportResult(RetrievalShadowSnapshot snapshot, int caseCount) {
    }

}
