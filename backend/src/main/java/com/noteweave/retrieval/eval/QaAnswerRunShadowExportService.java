package com.noteweave.retrieval.eval;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
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

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper databaseObjectMapper;
    private final ObjectMapper artifactObjectMapper;
    private final RetrievalExecutionShadowExporter exporter;

    public QaAnswerRunShadowExportService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            RetrievalExecutionShadowExporter exporter
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.databaseObjectMapper = objectMapper;
        this.artifactObjectMapper = new ObjectMapper().findAndRegisterModules();
        this.exporter = exporter;
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
        Map<String, RunRow> runs = loadRuns(runIds);
        Map<String, String> retrievalSummaryByRun = loadRetrievalSummaries(runIds);
        Map<String, List<CitationRow>> citationsByRun = loadCitations(runIds);

        List<RetrievalExecutionShadowExporter.CaseExecution> executions = new ArrayList<>();
        for (QaGoldAnnotationRequest.CaseRequest annotationCase : annotationRequest.cases()) {
            CaseRequest requestedCase = mappedCases.get(annotationCase.id());
            RunRow run = require(runs, requestedCase.answerRunId(), "AnswerRun");
            String summaryJson = require(
                    retrievalSummaryByRun, requestedCase.answerRunId(), "retrieval.summary");
            RetrievalPlan plan = readPlan(run);
            EvidenceBundleSnapshot bundle = readBundle(run);
            SummarySnapshot summary = readSummary(run.id(), summaryJson);
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

    private Map<String, RunRow> loadRuns(List<String> runIds) {
        if (runIds.size() > 500) {
            throw new IllegalArgumentException("QA AnswerRun shadow export supports at most 500 cases per batch");
        }
        List<RunRow> rows = jdbcTemplate.query("""
                        select r.id, r.workspace_id, r.conversation_id, r.query_message_id,
                               r.answer_message_id, r.mode, r.status,
                               r.retrieval_plan_version, r.retrieval_plan_json,
                               r.evidence_bundle_json,
                               qm.role as query_role, qm.answer_mode as query_answer_mode,
                               qm.content as query_text,
                               am.role as answer_role, am.answer_mode as answer_answer_mode
                        from answer_run r
                        join conversation_message qm
                          on qm.id = r.query_message_id
                         and qm.workspace_id = r.workspace_id
                         and qm.conversation_id = r.conversation_id
                        join conversation_message am
                          on am.id = r.answer_message_id
                         and am.workspace_id = r.workspace_id
                         and am.conversation_id = r.conversation_id
                        where r.id in (%s)
                        """.formatted(placeholders(runIds.size())),
                (rs, rowNum) -> new RunRow(
                        rs.getString("id"),
                        rs.getString("workspace_id"),
                        rs.getString("conversation_id"),
                        rs.getString("query_message_id"),
                        text(rs.getString("answer_message_id")),
                        rs.getString("mode"),
                        rs.getString("status"),
                        text(rs.getString("retrieval_plan_version")),
                        text(rs.getString("retrieval_plan_json")),
                        text(rs.getString("evidence_bundle_json")),
                        text(rs.getString("query_role")),
                        text(rs.getString("query_answer_mode")),
                        text(rs.getString("query_text")),
                        text(rs.getString("answer_role")),
                        text(rs.getString("answer_answer_mode"))),
                runIds.toArray());
        Map<String, RunRow> byId = new LinkedHashMap<>();
        rows.forEach(row -> {
            if (byId.putIfAbsent(row.id(), row) != null) {
                throw new IllegalStateException("Duplicate AnswerRun row in export batch");
            }
        });
        requireAll(runIds, byId, "AnswerRun");
        return Map.copyOf(byId);
    }

    private Map<String, String> loadRetrievalSummaries(List<String> runIds) {
        List<EventRow> rows = jdbcTemplate.query("""
                        select e.answer_run_id, e.payload_json
                        from answer_event e
                        join answer_run r
                          on r.id = e.answer_run_id and r.workspace_id = e.workspace_id
                        where e.answer_run_id in (%s) and e.event_type = 'retrieval.summary'
                        order by e.answer_run_id asc, e.seq asc
                        """.formatted(placeholders(runIds.size())),
                (rs, rowNum) -> new EventRow(
                        rs.getString("answer_run_id"), text(rs.getString("payload_json"))),
                runIds.toArray());
        Map<String, String> byRun = new LinkedHashMap<>();
        for (EventRow row : rows) {
            if (row.payloadJson().isBlank() || byRun.putIfAbsent(row.answerRunId(), row.payloadJson()) != null) {
                throw new IllegalStateException(
                        "Each AnswerRun must have exactly one retrieval.summary event");
            }
        }
        requireAll(runIds, byRun, "retrieval.summary");
        return Map.copyOf(byRun);
    }

    private Map<String, List<CitationRow>> loadCitations(List<String> runIds) {
        List<CitationRow> rows = jdbcTemplate.query("""
                        select r.id as answer_run_id,
                               c.id as citation_id,
                               c.workspace_id as citation_workspace_id,
                               c.source_id,
                               c.source_snapshot_id,
                               c.source_chunk_id,
                               mc.sort_order,
                               s.workspace_id as source_workspace_id,
                               ss.source_id as snapshot_source_id,
                               sc.workspace_id as chunk_workspace_id,
                               sc.source_id as chunk_source_id,
                               sc.source_snapshot_id as chunk_snapshot_id
                        from answer_run r
                        join message_citation mc on mc.message_id = r.answer_message_id
                        join citation c on c.id = mc.citation_id
                        join source s on s.id = c.source_id
                        join source_snapshot ss on ss.id = c.source_snapshot_id
                        join source_chunk sc on sc.id = c.source_chunk_id
                        where r.id in (%s)
                        order by r.id asc, mc.sort_order asc, mc.id asc
                        """.formatted(placeholders(runIds.size())),
                (rs, rowNum) -> new CitationRow(
                        rs.getString("answer_run_id"),
                        rs.getString("citation_id"),
                        rs.getString("citation_workspace_id"),
                        rs.getString("source_id"),
                        rs.getString("source_snapshot_id"),
                        rs.getString("source_chunk_id"),
                        rs.getInt("sort_order"),
                        rs.getString("source_workspace_id"),
                        rs.getString("snapshot_source_id"),
                        rs.getString("chunk_workspace_id"),
                        rs.getString("chunk_source_id"),
                        rs.getString("chunk_snapshot_id")),
                runIds.toArray());
        Map<String, List<CitationRow>> mutable = new LinkedHashMap<>();
        rows.forEach(row -> mutable.computeIfAbsent(
                row.answerRunId(), ignored -> new ArrayList<>()).add(row));
        Map<String, List<CitationRow>> immutable = new LinkedHashMap<>();
        mutable.forEach((runId, citations) -> immutable.put(runId, List.copyOf(citations)));
        return Map.copyOf(immutable);
    }

    private RetrievalPlan readPlan(RunRow run) {
        if (run.planJson().isBlank()) {
            throw new IllegalStateException("AnswerRun has no persisted RetrievalPlan");
        }
        try {
            return databaseObjectMapper.readValue(run.planJson(), RetrievalPlan.class);
        } catch (Exception ex) {
            throw new IllegalStateException("AnswerRun RetrievalPlan cannot be decoded", ex);
        }
    }

    private EvidenceBundleSnapshot readBundle(RunRow run) {
        if (run.bundleJson().isBlank()) {
            throw new IllegalStateException("AnswerRun has no persisted EvidenceBundle");
        }
        try {
            JsonNode root = databaseObjectMapper.readTree(run.bundleJson());
            validateBundleShape(root);
            return databaseObjectMapper.treeToValue(root, EvidenceBundleSnapshot.class);
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "AnswerRun EvidenceBundle cannot be decoded", ex);
        }
    }

    private SummarySnapshot readSummary(String runId, String summaryJson) {
        try {
            JsonNode root = databaseObjectMapper.readTree(summaryJson);
            JsonNode trace = root.path("execution_trace");
            if (trace.isMissingNode() || trace.isNull()) {
                throw new IllegalStateException(
                        "AnswerRun retrieval.summary has no execution_trace");
            }
            validateTraceShape(trace);
            return new SummarySnapshot(
                    requiredText(root, "trace_schema_version", runId),
                    requiredText(root, "plan_version", runId),
                    optionalText(root, "strategy_profile", runId),
                    optionalText(root, "relevance_policy", runId),
                    optionalText(root, "selection_policy", runId),
                    requiredLong(root, "retrieval_latency_micros", runId),
                    requiredInt(root, "candidate_count", runId),
                    requiredInt(root, "admitted_candidate_count", runId),
                    requiredInt(root, "selected_evidence_count", runId),
                    requiredInt(root, "selected_evidence_characters", runId),
                    requiredBoolean(root, "degraded", runId),
                    requiredStrings(root, "degradation_reasons", runId),
                    databaseObjectMapper.treeToValue(trace, RetrievalExecutionTrace.class)
            );
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "AnswerRun retrieval.summary cannot be decoded", ex);
        }
    }

    private void validateBundleShape(JsonNode root) {
        requireObject(root, "evidence_bundle");
        if (!EvidenceBundleSnapshot.SCHEMA_VERSION.equals(
                requireText(root, "schema_version", true, "evidence_bundle"))) {
            throw invalidJson("evidence_bundle", "schema_version");
        }
        requireText(root, "bundle_id", true, "evidence_bundle");
        requireText(root, "retrieval_plan_version", true, "evidence_bundle");
        requireBoolean(root, "degraded", "evidence_bundle");
        requireStringArray(root, "degradation_reasons", "evidence_bundle");
        requirePresent(root, "created_at", "evidence_bundle");
        JsonNode evidence = requireArray(root, "evidence", "evidence_bundle");
        for (JsonNode item : evidence) {
            requireObject(item, "evidence");
            requirePositiveInt(item, "rank", "evidence");
            requireText(item, "evidence_id", true, "evidence");
            requireText(item, "kind", true, "evidence");
            requireText(item, "source_id", true, "evidence");
            requireText(item, "source_snapshot_id", true, "evidence");
            requireText(item, "passage_id", true, "evidence");
            requireText(item, "knowledge_item_id", false, "evidence");
            requireText(item, "knowledge_version_id", false, "evidence");
            requireFiniteNumber(item, "raw_score", "evidence");
            requireFiniteNumber(item, "fused_score", "evidence");
            requireFiniteNumber(item, "rerank_score", "evidence");
            requireText(item, "access_scope", true, "evidence");
            requirePresent(item, "fresh_at", "evidence");
            requireText(item, "selection_reason", false, "evidence");
            requireNonNegativeInt(item, "character_cost", "evidence");
        }
    }

    private void validateTraceShape(JsonNode trace) {
        requireObject(trace, "execution_trace");
        if (!RetrievalExecutionTrace.SCHEMA_VERSION.equals(
                requireText(trace, "schema_version", true, "execution_trace"))) {
            throw invalidJson("execution_trace", "schema_version");
        }
        requireText(trace, "plan_version", true, "execution_trace");
        requireNonNegativeLong(trace, "total_latency_micros", "execution_trace");
        requireNonNegativeInt(trace, "raw_candidate_count", "execution_trace");
        requireNonNegativeInt(trace, "admitted_candidate_count", "execution_trace");
        requireNonNegativeInt(trace, "selected_evidence_count", "execution_trace");
        requireNonNegativeInt(trace, "selected_evidence_characters", "execution_trace");
        JsonNode steps = requireArray(trace, "steps", "execution_trace");
        for (JsonNode step : steps) {
            requireObject(step, "execution_step");
            requireNonNegativeInt(step, "step_index", "execution_step");
            requireText(step, "channel", true, "execution_step");
            requireNonNegativeInt(step, "candidate_limit", "execution_step");
            requireNonNegativeInt(step, "raw_candidate_count", "execution_step");
            requireNonNegativeInt(step, "admitted_candidate_count", "execution_step");
            requireNonNegativeLong(step, "latency_micros", "execution_step");
            requireBoolean(step, "degraded", "execution_step");
            requireStringArray(step, "degradation_reasons", "execution_step");
            JsonNode measurements = requireObjectField(step, "measurements", "execution_step");
            measurements.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
                    throw invalidJson("execution_step.measurements", entry.getKey());
                }
            });
        }
        JsonNode selected = requireArray(trace, "selected_evidence", "execution_trace");
        for (JsonNode item : selected) {
            requireObject(item, "selected_evidence");
            requirePositiveInt(item, "rank", "selected_evidence");
            requireText(item, "evidence_id", true, "selected_evidence");
            requireText(item, "kind", true, "selected_evidence");
            requireFiniteNumber(item, "raw_score", "selected_evidence");
            requireFiniteNumber(item, "fused_score", "selected_evidence");
            requireFiniteNumber(item, "rerank_score", "selected_evidence");
            requireNonNegativeInt(item, "character_cost", "selected_evidence");
        }
    }

    private void requireObject(JsonNode value, String artifact) {
        if (value == null || !value.isObject()) {
            throw invalidJson(artifact, "root");
        }
    }

    private JsonNode requireObjectField(JsonNode root, String field, String artifact) {
        JsonNode value = root.get(field);
        if (value == null || !value.isObject()) {
            throw invalidJson(artifact, field);
        }
        return value;
    }

    private JsonNode requireArray(JsonNode root, String field, String artifact) {
        JsonNode value = root.get(field);
        if (value == null || !value.isArray()) {
            throw invalidJson(artifact, field);
        }
        return value;
    }

    private String requireText(JsonNode root, String field, boolean nonBlank, String artifact) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || (nonBlank && value.textValue().isBlank())) {
            throw invalidJson(artifact, field);
        }
        return value.textValue();
    }

    private void requirePresent(JsonNode root, String field, String artifact) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            throw invalidJson(artifact, field);
        }
    }

    private void requireBoolean(JsonNode root, String field, String artifact) {
        JsonNode value = root.get(field);
        if (value == null || !value.isBoolean()) {
            throw invalidJson(artifact, field);
        }
    }

    private void requireStringArray(JsonNode root, String field, String artifact) {
        JsonNode values = requireArray(root, field, artifact);
        for (JsonNode value : values) {
            if (!value.isTextual()) {
                throw invalidJson(artifact, field);
            }
        }
    }

    private void requirePositiveInt(JsonNode root, String field, String artifact) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber()
                || !value.canConvertToInt() || value.intValue() <= 0) {
            throw invalidJson(artifact, field);
        }
    }

    private void requireNonNegativeInt(JsonNode root, String field, String artifact) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber()
                || !value.canConvertToInt() || value.intValue() < 0) {
            throw invalidJson(artifact, field);
        }
    }

    private void requireNonNegativeLong(JsonNode root, String field, String artifact) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber()
                || !value.canConvertToLong() || value.longValue() < 0) {
            throw invalidJson(artifact, field);
        }
    }

    private void requireFiniteNumber(JsonNode root, String field, String artifact) {
        JsonNode value = root.get(field);
        if (value == null || !value.isNumber() || !Double.isFinite(value.doubleValue())) {
            throw invalidJson(artifact, field);
        }
    }

    private IllegalStateException invalidJson(String artifact, String field) {
        return new IllegalStateException(
                "Persisted " + artifact + " JSON field is missing or invalid: " + field);
    }

    private String requiredText(JsonNode root, String field, String runId) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalidSummary(runId, field);
        }
        return value.textValue();
    }

    private String optionalText(JsonNode root, String field, String runId) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        if (!value.isTextual()) {
            throw invalidSummary(runId, field);
        }
        return value.textValue();
    }

    private int requiredInt(JsonNode root, String field, String runId) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber()
                || !value.canConvertToInt() || value.intValue() < 0) {
            throw invalidSummary(runId, field);
        }
        return value.intValue();
    }

    private long requiredLong(JsonNode root, String field, String runId) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber()
                || !value.canConvertToLong() || value.longValue() < 0) {
            throw invalidSummary(runId, field);
        }
        return value.longValue();
    }

    private boolean requiredBoolean(JsonNode root, String field, String runId) {
        JsonNode value = root.get(field);
        if (value == null || !value.isBoolean()) {
            throw invalidSummary(runId, field);
        }
        return value.booleanValue();
    }

    private List<String> requiredStrings(JsonNode root, String field, String runId) {
        JsonNode value = root.get(field);
        if (value == null || !value.isArray()) {
            throw invalidSummary(runId, field);
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                throw invalidSummary(runId, field);
            }
            result.add(item.textValue());
        }
        return List.copyOf(result);
    }

    private IllegalStateException invalidSummary(String runId, String field) {
        return new IllegalStateException(
                "AnswerRun retrieval.summary field is invalid: " + field);
    }

    private QaRetrievalStrategyProfile validateRun(
            QaGoldAnnotationRequest.CaseRequest annotationCase,
            RunRow run,
            RetrievalPlan plan,
            EvidenceBundleSnapshot bundle,
            SummarySnapshot summary
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
            RunRow run,
            QaRetrievalStrategyProfile profile,
            SummarySnapshot summary,
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
            RunRow run,
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
            RunRow run,
            EvidenceBundleSnapshot bundle,
            List<CitationRow> citations
    ) {
        if (citations.size() != bundle.evidence().size()) {
            throw new IllegalStateException(
                    "AnswerRun citation count does not match final EvidenceBundle");
        }
        Map<String, List<String>> labels = new LinkedHashMap<>();
        for (int index = 0; index < bundle.evidence().size(); index++) {
            EvidenceSnapshot evidence = bundle.evidence().get(index);
            CitationRow citation = citations.get(index);
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

    private void requireAll(List<String> ids, Map<String, ?> values, String label) {
        for (String id : ids) {
            if (!values.containsKey(id)) {
                throw new IllegalStateException(label + " is missing for one requested AnswerRun");
            }
        }
    }

    private String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    public record ExportResult(RetrievalShadowSnapshot snapshot, int caseCount) {
    }

    private record RunRow(
            String id,
            String workspaceId,
            String conversationId,
            String queryMessageId,
            String answerMessageId,
            String mode,
            String status,
            String planVersion,
            String planJson,
            String bundleJson,
            String queryRole,
            String queryAnswerMode,
            String queryText,
            String answerRole,
            String answerAnswerMode
    ) {
    }

    private record EventRow(String answerRunId, String payloadJson) {
    }

    private record CitationRow(
            String answerRunId,
            String citationId,
            String workspaceId,
            String sourceId,
            String sourceSnapshotId,
            String sourceChunkId,
            int sortOrder,
            String sourceWorkspaceId,
            String snapshotSourceId,
            String chunkWorkspaceId,
            String chunkSourceId,
            String chunkSnapshotId
    ) {
    }

    private record SummarySnapshot(
            String traceSchemaVersion,
            String planVersion,
            String strategyProfile,
            String relevancePolicy,
            String selectionPolicy,
            long retrievalLatencyMicros,
            int candidateCount,
            int admittedCandidateCount,
            int selectedEvidenceCount,
            int selectedEvidenceCharacters,
            boolean degraded,
            List<String> degradationReasons,
            RetrievalExecutionTrace trace
    ) {
    }
}
