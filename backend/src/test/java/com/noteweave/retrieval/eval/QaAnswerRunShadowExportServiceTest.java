package com.noteweave.retrieval.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.noteweave.answer.strategy.AnswerMode;
import com.noteweave.answer.strategy.EvidenceBundleSnapshot;
import com.noteweave.answer.strategy.EvidenceBundleSnapshot.EvidenceSnapshot;
import com.noteweave.answer.strategy.RetrievalExecutionTrace;
import com.noteweave.answer.strategy.RetrievalExecutionTrace.SelectedEvidenceTrace;
import com.noteweave.answer.strategy.RetrievalExecutionTrace.StepTrace;
import com.noteweave.answer.strategy.RetrievalPlan;
import com.noteweave.chat.QaPassageEvidenceRetriever;
import com.noteweave.retrieval.QaEvidenceRelevancePolicy;
import com.noteweave.retrieval.QaEvidenceSelectionPolicy;
import com.noteweave.retrieval.QaRetrievalStrategyProfile;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class QaAnswerRunShadowExportServiceTest {

    private static final String PLAN_VERSION = "qa-passage-v1";
    private static final String SALT = "stage5-online-answer-run-shadow-salt";

    private EmbeddedDatabase database;
    private CountingJdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;
    private RetrievalSnapshotSanitizer sanitizer;
    private QaAnswerRunShadowExportService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbcTemplate = new CountingJdbcTemplate(database);
        objectMapper = new ObjectMapper().findAndRegisterModules()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        sanitizer = new RetrievalSnapshotSanitizer();
        service = new QaAnswerRunShadowExportService(
                objectMapper,
                new RetrievalExecutionShadowExporter(sanitizer),
                new QaAnswerRunShadowExportReadRepository(jdbcTemplate)
        );
        createSchema();
        seedOwnership();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void shouldBatchExportFinalQaBundlesWithSemanticCitationLabels() throws Exception {
        seedAnswerableRun(
                "run-answer", "workspace-1", "message-answer", "chunk-1", 12,
                "  What does AlphaSpec require?  ");
        seedRefusalRun(
                "run-refusal", "workspace-1", "message-refusal",
                "What is the unknown banana theorem?");
        jdbcTemplate.resetQueryCount();

        QaGoldAnnotationRequest annotationRequest = annotationRequest(List.of(
                annotationCase("case-answer", "What does AlphaSpec require?"),
                annotationCase("case-refusal", "What is the unknown banana theorem?")
        ));
        RetrievalShadowSnapshot snapshot = service.export(annotationRequest, new QaAnswerRunShadowExportRequest(
                QaAnswerRunShadowExportRequest.SCHEMA_VERSION,
                "qa-online-shadow-1",
                QaRetrievalStrategyProfile.V2.profileVersion(),
                List.of(
                        new QaAnswerRunShadowExportRequest.CaseRequest("case-answer", "run-answer"),
                        new QaAnswerRunShadowExportRequest.CaseRequest("case-refusal", "run-refusal")
                )
        ), SALT);

        assertThat(jdbcTemplate.queryCount()).isEqualTo(3);
        assertThat(snapshot.snapshotVersion()).isEqualTo("qa-online-shadow-1");
        assertThat(snapshot.strategyProfile())
                .isEqualTo(QaRetrievalStrategyProfile.V2.profileVersion());
        assertThat(snapshot.cases()).hasSize(2);
        assertThat(snapshot.cases().get(0)).satisfies(item -> {
            assertThat(item.caseId()).isEqualTo(
                    sanitizer.pseudonym("case", "case-answer", SALT));
            assertThat(item.candidateCount()).isEqualTo(12);
            assertThat(item.rankedEvidence()).singleElement().satisfies(ranked -> {
                assertThat(ranked.evidenceId()).isEqualTo(
                        sanitizer.pseudonym("evidence", "chunk-1", SALT));
                assertThat(ranked.sourceId()).isEqualTo(
                        sanitizer.pseudonym("source", "source-1", SALT));
                assertThat(ranked.citationIds()).containsExactly(
                        sanitizer.pseudonym(
                                "citation", "citation-label:chunk-1", SALT));
            });
        });
        assertThat(snapshot.cases().get(1).caseId()).isEqualTo(
                sanitizer.pseudonym("case", "case-refusal", SALT));
        assertThat(snapshot.cases().get(1).rankedEvidence()).isEmpty();
    }

    @Test
    void shouldExportTheSingleV2PlanTuple() throws Exception {
        seedAnswerableRun(
                "run-answer", "workspace-1", "message-answer", "chunk-1", 12,
                "What does AlphaSpec require?");
        rewriteRunProfile("run-answer", QaRetrievalStrategyProfile.V2);

        RetrievalShadowSnapshot v2 = service.export(
                annotationRequest(List.of(annotationCase(
                        "case-answer", "What does AlphaSpec require?"))),
                runMap("run-answer", QaRetrievalStrategyProfile.V2), SALT);
        assertThat(v2.strategyProfile())
                .isEqualTo(QaRetrievalStrategyProfile.V2.profileVersion());
    }

    @Test
    void shouldRejectUnsupportedOrDriftedNonV2StrategyTuples() throws Exception {
        seedAnswerableRun(
                "run-answer", "workspace-1", "message-answer", "chunk-1", 12,
                "What does AlphaSpec require?");
        ObjectNode plan = (ObjectNode) objectMapper.readTree(jdbcTemplate.queryForObject(
                "select retrieval_plan_json from answer_run where id = 'run-answer'",
                String.class));
        plan.put("version", "qa-passage-baseline-v1");
        ObjectNode filters = (ObjectNode) plan.path("steps").get(0).path("filters");
        filters.put("strategy_profile", "qa-retrieval-baseline-v1");
        filters.put("relevance_policy", "qa-lexical-sufficiency-v1");
        filters.put("strategy_v2_enabled", "false");
        jdbcTemplate.update(
                "update answer_run set retrieval_plan_version = ?, retrieval_plan_json = ? where id = 'run-answer'",
                "qa-passage-baseline-v1", objectMapper.writeValueAsString(plan));

        assertThatThrownBy(() -> service.export(
                annotationRequest(List.of(annotationCase(
                        "case-answer", "What does AlphaSpec require?"))),
                runMap("run-answer", QaRetrievalStrategyProfile.V2), SALT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported QA strategy tuple");

        plan.put("version", QaRetrievalStrategyProfile.V2.planVersion());
        filters.put("strategy_profile", QaRetrievalStrategyProfile.V2.profileVersion());
        filters.put("strategy_v2_enabled", "true");
        filters
                .put("relevance_policy", "qa-unknown-policy");
        jdbcTemplate.update(
                "update answer_run set retrieval_plan_version = ?, retrieval_plan_json = ? where id = 'run-answer'",
                QaRetrievalStrategyProfile.V2.planVersion(), objectMapper.writeValueAsString(plan));

        assertThatThrownBy(() -> service.export(
                annotationRequest(List.of(annotationCase(
                        "case-answer", "What does AlphaSpec require?"))),
                runMap("run-answer", QaRetrievalStrategyProfile.V2), SALT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported QA strategy tuple");
    }

    @Test
    void shouldReadCamelCaseArtifactsAndWriteSanitizedCamelCaseShadow() throws Exception {
        seedAnswerableRun(
                "run-answer", "workspace-1", "message-answer", "chunk-1", 12,
                "What does AlphaSpec require?");
        QaGoldAnnotationRequest annotationRequest = annotationRequest(List.of(annotationCase(
                "case-answer", "What does AlphaSpec require?")));
        QaAnswerRunShadowExportRequest runMap = runMap("run-answer");
        ObjectMapper artifactMapper = new ObjectMapper().findAndRegisterModules();
        Path annotationPath = tempDir.resolve("annotation.json");
        Path runMapPath = tempDir.resolve("run-map.json");
        Path outputPath = tempDir.resolve("shadow.json");
        artifactMapper.writerWithDefaultPrettyPrinter().writeValue(annotationPath.toFile(), annotationRequest);
        artifactMapper.writerWithDefaultPrettyPrinter().writeValue(runMapPath.toFile(), runMap);

        service.exportAndWrite(annotationPath, runMapPath, outputPath, SALT);

        String rawOutput = Files.readString(outputPath);
        assertThat(artifactMapper.readTree(rawOutput).has("snapshotVersion")).isTrue();
        assertThat(artifactMapper.readTree(rawOutput).has("snapshot_version")).isFalse();
        assertThat(rawOutput)
                .doesNotContain("run-answer")
                .doesNotContain("workspace-1")
                .doesNotContain("source-1")
                .doesNotContain("chunk-1")
                .doesNotContain("citation-1")
                .doesNotContain("What does AlphaSpec require?");
    }

    @Test
    void shouldFailClosedWhenPersistedCitationDoesNotMatchFinalEvidence() throws Exception {
        seedAnswerableRun(
                "run-answer", "workspace-1", "message-answer", "chunk-1", 7,
                "What does AlphaSpec require?");
        jdbcTemplate.update("insert into source_snapshot(id, source_id) values ('snapshot-stale', 'source-1')");
        jdbcTemplate.update(
                "update citation set source_snapshot_id = 'snapshot-stale' where id = 'citation-1'");

        assertThatThrownBy(() -> service.export(
                annotationRequest(List.of(annotationCase(
                        "case-answer", "What does AlphaSpec require?"))),
                runMap("run-answer"), SALT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("citation identity/order");
    }

    @Test
    void shouldFailClosedWhenRetrievalSummaryIsDuplicated() throws Exception {
        seedAnswerableRun(
                "run-answer", "workspace-1", "message-answer", "chunk-1", 7,
                "What does AlphaSpec require?");
        String payload = jdbcTemplate.queryForObject(
                "select payload_json from answer_event where answer_run_id = 'run-answer'",
                String.class);
        jdbcTemplate.update("""
                insert into answer_event(id, workspace_id, answer_run_id, seq, event_type, payload_json)
                values ('event-duplicate', 'workspace-1', 'run-answer', 2, 'retrieval.summary', ?)
                """, payload);

        assertThatThrownBy(() -> service.export(
                annotationRequest(List.of(annotationCase(
                        "case-answer", "What does AlphaSpec require?"))),
                runMap("run-answer"), SALT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one retrieval.summary");
    }

    @Test
    void shouldFailClosedWhenAnnotationScopeDoesNotMatchPersistedPlan() throws Exception {
        seedAnswerableRun(
                "run-answer", "workspace-1", "message-answer", "chunk-1", 7,
                "What does AlphaSpec require?");
        QaGoldAnnotationRequest mismatched = annotationRequest(List.of(
                new QaGoldAnnotationRequest.CaseRequest(
                        "case-answer",
                        "workspace-1",
                        "What does AlphaSpec require?",
                        List.of("source-2"),
                        6)
        ));

        assertThatThrownBy(() -> service.export(mismatched, runMap("run-answer"), SALT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match the QA annotation case");
    }

    @Test
    void shouldFailClosedWhenBackendCandidateMeasurementIsMissing() throws Exception {
        seedAnswerableRun(
                "run-answer", "workspace-1", "message-answer", "chunk-1", 7,
                "What does AlphaSpec require?");
        String payload = jdbcTemplate.queryForObject(
                "select payload_json from answer_event where answer_run_id = 'run-answer'",
                String.class);
        ObjectNode root = (ObjectNode) objectMapper.readTree(payload);
        ObjectNode measurements = (ObjectNode) root.path("execution_trace")
                .path("steps").get(0).path("measurements");
        measurements.remove("primary_hit_count");
        jdbcTemplate.update(
                "update answer_event set payload_json = ? where answer_run_id = 'run-answer'",
                objectMapper.writeValueAsString(root));

        assertThatThrownBy(() -> service.export(
                annotationRequest(List.of(annotationCase(
                        "case-answer", "What does AlphaSpec require?"))),
                runMap("run-answer"), SALT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("backend candidate count");
    }

    @Test
    void shouldRequireBackendPoolToCoverPreBudgetStepCandidates() throws Exception {
        seedAnswerableRun(
                "run-answer", "workspace-1", "message-answer", "chunk-1", 7,
                "What does AlphaSpec require?");
        ObjectNode bundle = (ObjectNode) objectMapper.readTree(jdbcTemplate.queryForObject(
                "select evidence_bundle_json from answer_run where id = 'run-answer'",
                String.class));
        bundle.putArray("evidence");
        jdbcTemplate.update(
                "update answer_run set evidence_bundle_json = ? where id = 'run-answer'",
                objectMapper.writeValueAsString(bundle));
        ObjectNode summary = answerSummary();
        summary.put("selected_evidence_count", 0);
        summary.put("selected_evidence_characters", 0);
        ObjectNode trace = (ObjectNode) summary.path("execution_trace");
        trace.put("selected_evidence_count", 0);
        trace.put("selected_evidence_characters", 0);
        trace.putArray("selected_evidence");
        ObjectNode measurements = (ObjectNode) trace.path("steps").get(0).path("measurements");
        measurements.put("primary_hit_count", 0);
        updateSummary(summary);

        assertThatThrownBy(() -> exportSingleAnswerableRun())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("backend candidate count");
    }

    @Test
    void shouldFailClosedWhenPersistedBundleOmitsRequiredSchema() throws Exception {
        seedAnswerableRun(
                "run-answer", "workspace-1", "message-answer", "chunk-1", 7,
                "What does AlphaSpec require?");
        ObjectNode bundle = (ObjectNode) objectMapper.readTree(jdbcTemplate.queryForObject(
                "select evidence_bundle_json from answer_run where id = 'run-answer'",
                String.class));
        bundle.remove("schema_version");
        jdbcTemplate.update(
                "update answer_run set evidence_bundle_json = ? where id = 'run-answer'",
                objectMapper.writeValueAsString(bundle));

        assertThatThrownBy(() -> exportSingleAnswerableRun())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EvidenceBundle cannot be decoded")
                .hasRootCauseMessage(
                        "Persisted evidence_bundle JSON field is missing or invalid: schema_version");
    }

    @Test
    void shouldRejectFractionalSummaryCounts() throws Exception {
        seedAnswerableRun(
                "run-answer", "workspace-1", "message-answer", "chunk-1", 7,
                "What does AlphaSpec require?");
        ObjectNode summary = answerSummary();
        summary.put("candidate_count", 1.5d);
        updateSummary(summary);

        assertThatThrownBy(() -> exportSingleAnswerableRun())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("candidate_count");
    }

    @Test
    void shouldFailClosedWhenStepCountsDriftFromTopLevelTrace() throws Exception {
        seedAnswerableRun(
                "run-answer", "workspace-1", "message-answer", "chunk-1", 7,
                "What does AlphaSpec require?");
        ObjectNode summary = answerSummary();
        ObjectNode step = (ObjectNode) summary.path("execution_trace").path("steps").get(0);
        step.put("raw_candidate_count", 2);
        ((ObjectNode) step.path("measurements")).put("selected_count", 2);
        updateSummary(summary);

        assertThatThrownBy(() -> exportSingleAnswerableRun())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("measurements are incomplete");
    }

    @Test
    void shouldFailClosedWhenFinalBundleExceedsCharacterBudget() throws Exception {
        seedAnswerableRun(
                "run-answer", "workspace-1", "message-answer", "chunk-1", 7,
                "What does AlphaSpec require?");
        ObjectNode bundle = (ObjectNode) objectMapper.readTree(jdbcTemplate.queryForObject(
                "select evidence_bundle_json from answer_run where id = 'run-answer'",
                String.class));
        ((ObjectNode) bundle.path("evidence").get(0)).put("character_cost", 8_001);
        jdbcTemplate.update(
                "update answer_run set evidence_bundle_json = ? where id = 'run-answer'",
                objectMapper.writeValueAsString(bundle));
        ObjectNode summary = answerSummary();
        summary.put("selected_evidence_characters", 8_001);
        ObjectNode trace = (ObjectNode) summary.path("execution_trace");
        trace.put("selected_evidence_characters", 8_001);
        ((ObjectNode) trace.path("selected_evidence").get(0)).put("character_cost", 8_001);
        updateSummary(summary);

        assertThatThrownBy(() -> exportSingleAnswerableRun())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trace counts do not match final EvidenceBundle");
    }

    @Test
    void shouldFailClosedWhenStepDegradationDriftsFromBundle() throws Exception {
        seedAnswerableRun(
                "run-answer", "workspace-1", "message-answer", "chunk-1", 7,
                "What does AlphaSpec require?");
        ObjectNode summary = answerSummary();
        ObjectNode step = (ObjectNode) summary.path("execution_trace").path("steps").get(0);
        step.put("degraded", true);
        step.putArray("degradation_reasons").add("tampered_reason");
        updateSummary(summary);

        assertThatThrownBy(() -> exportSingleAnswerableRun())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("measurements are incomplete");
    }

    @Test
    void shouldRejectUnsupportedManifestAndNonCompletedRuns() throws Exception {
        seedAnswerableRun(
                "run-answer", "workspace-1", "message-answer", "chunk-1", 7,
                "What does AlphaSpec require?");
        jdbcTemplate.update("update answer_run set status = 'GENERATING' where id = 'run-answer'");

        QaGoldAnnotationRequest annotationRequest = annotationRequest(List.of(annotationCase(
                "case-answer", "What does AlphaSpec require?")));
        assertThatThrownBy(() -> service.export(annotationRequest, runMap("run-answer"), SALT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("completed QA AnswerRuns");
        assertThatThrownBy(() -> service.export(annotationRequest, new QaAnswerRunShadowExportRequest(
                "unsupported", "snapshot", QaRetrievalStrategyProfile.V2.profileVersion(), List.of(
                new QaAnswerRunShadowExportRequest.CaseRequest("case", "run-answer"))), SALT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema");
        assertThatThrownBy(() -> service.export(new QaGoldAnnotationRequest(
                        QaGoldAnnotationDraftCapture.REQUEST_SCHEMA_VERSION,
                        "dataset",
                        12,
                        List.of(new QaGoldAnnotationRequest.CaseRequest(
                                "case-answer", "workspace-1", "query", List.of(), 6))),
                runMap("run-answer"), SALT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cases are invalid");
    }

    private QaAnswerRunShadowExportRequest runMap(String runId) {
        return runMap(runId, QaRetrievalStrategyProfile.V2);
    }

    private QaAnswerRunShadowExportRequest runMap(
            String runId,
            QaRetrievalStrategyProfile profile
    ) {
        return new QaAnswerRunShadowExportRequest(
                QaAnswerRunShadowExportRequest.SCHEMA_VERSION,
                "qa-online-shadow-test",
                profile.profileVersion(),
                List.of(new QaAnswerRunShadowExportRequest.CaseRequest("case-answer", runId))
        );
    }

    private void rewriteRunProfile(
            String runId,
            QaRetrievalStrategyProfile profile
    ) throws Exception {
        ObjectNode plan = (ObjectNode) objectMapper.readTree(jdbcTemplate.queryForObject(
                "select retrieval_plan_json from answer_run where id = ?",
                String.class, runId));
        plan.put("version", profile.planVersion());
        ObjectNode filters = (ObjectNode) plan.path("steps").get(0).path("filters");
        filters.put(QaRetrievalStrategyProfile.FILTER_PROFILE, profile.profileVersion());
        filters.put(QaRetrievalStrategyProfile.FILTER_RELEVANCE_POLICY,
                profile.relevancePolicyVersion());
        filters.put(QaRetrievalStrategyProfile.FILTER_SELECTION_POLICY,
                profile.selectionPolicyVersion());
        filters.put(QaRetrievalStrategyProfile.FILTER_V2_ENABLED,
                Boolean.toString(profile.v2Enabled()));

        ObjectNode bundle = (ObjectNode) objectMapper.readTree(jdbcTemplate.queryForObject(
                "select evidence_bundle_json from answer_run where id = ?",
                String.class, runId));
        bundle.put("retrieval_plan_version", profile.planVersion());
        jdbcTemplate.update("""
                update answer_run
                set retrieval_plan_version = ?, retrieval_plan_json = ?, evidence_bundle_json = ?
                where id = ?
                """, profile.planVersion(), objectMapper.writeValueAsString(plan),
                objectMapper.writeValueAsString(bundle), runId);

        ObjectNode summary = (ObjectNode) objectMapper.readTree(jdbcTemplate.queryForObject(
                "select payload_json from answer_event where answer_run_id = ?",
                String.class, runId));
        summary.put("plan_version", profile.planVersion());
        summary.put("strategy_profile", profile.profileVersion());
        summary.put("relevance_policy", profile.relevancePolicyVersion());
        summary.put("selection_policy", profile.selectionPolicyVersion());
        ObjectNode trace = (ObjectNode) summary.path("execution_trace");
        trace.put("plan_version", profile.planVersion());
        ((ObjectNode) trace.path("steps").get(0).path("measurements"))
                .put("strategy_v2_enabled", profile.v2Enabled() ? 1 : 0);
        jdbcTemplate.update(
                "update answer_event set payload_json = ? where answer_run_id = ?",
                objectMapper.writeValueAsString(summary), runId);
    }

    private RetrievalShadowSnapshot exportSingleAnswerableRun() {
        return service.export(
                annotationRequest(List.of(annotationCase(
                        "case-answer", "What does AlphaSpec require?"))),
                runMap("run-answer"),
                SALT
        );
    }

    private ObjectNode answerSummary() throws Exception {
        return (ObjectNode) objectMapper.readTree(jdbcTemplate.queryForObject(
                "select payload_json from answer_event where answer_run_id = 'run-answer'",
                String.class));
    }

    private void updateSummary(ObjectNode summary) throws Exception {
        jdbcTemplate.update(
                "update answer_event set payload_json = ? where answer_run_id = 'run-answer'",
                objectMapper.writeValueAsString(summary));
    }

    private QaGoldAnnotationRequest annotationRequest(
            List<QaGoldAnnotationRequest.CaseRequest> cases
    ) {
        return new QaGoldAnnotationRequest(
                QaGoldAnnotationDraftCapture.REQUEST_SCHEMA_VERSION,
                "stage5-online-shadow-test",
                12,
                cases
        );
    }

    private QaGoldAnnotationRequest.CaseRequest annotationCase(String caseId, String query) {
        return new QaGoldAnnotationRequest.CaseRequest(
                caseId, "workspace-1", query, List.of("source-1"), 6);
    }

    private void seedAnswerableRun(
            String runId,
            String workspaceId,
            String messageId,
            String chunkId,
            int primaryHitCount,
            String query
    ) throws Exception {
        EvidenceSnapshot evidence = new EvidenceSnapshot(
                1,
                "passage:" + chunkId,
                "PASSAGE",
                "source-1",
                "snapshot-1",
                chunkId,
                "",
                "",
                143.0,
                143.0,
                143.0,
                "workspace-source:source-1",
                Instant.parse("2026-07-15T12:00:00Z"),
                "fulltext:bm25",
                120
        );
        EvidenceBundleSnapshot bundle = new EvidenceBundleSnapshot(
                EvidenceBundleSnapshot.SCHEMA_VERSION,
                "bundle-1",
                PLAN_VERSION,
                false,
                List.of(),
                Instant.parse("2026-07-15T12:00:01Z"),
                List.of(evidence)
        );
        RetrievalExecutionTrace trace = new RetrievalExecutionTrace(
                RetrievalExecutionTrace.SCHEMA_VERSION,
                PLAN_VERSION,
                5_000,
                1,
                1,
                1,
                120,
                List.of(new StepTrace(
                        0,
                        "QA_PASSAGE",
                        12,
                        1,
                        1,
                        4_000,
                        false,
                        List.of(),
                        Map.of(
                                "primary_hit_count", (long) primaryHitCount,
                                "mysql_fallback_used", 0L,
                                "mysql_candidate_count", 0L,
                                "selected_count", 1L)
                )),
                List.of(new SelectedEvidenceTrace(
                        1, evidence.evidenceId(), evidence.kind(),
                        evidence.rawScore(), evidence.fusedScore(), evidence.rerankScore(),
                        evidence.characterCost()))
        );
        insertRun(runId, workspaceId, messageId, query, bundle, trace);
        jdbcTemplate.update("""
                insert into citation(
                    id, workspace_id, source_id, source_snapshot_id, source_chunk_id
                ) values ('citation-1', ?, 'source-1', 'snapshot-1', ?)
                """, workspaceId, chunkId);
        jdbcTemplate.update("""
                insert into message_citation(id, message_id, citation_id, sort_order)
                values ('message-citation-1', ?, 'citation-1', 0)
                """, messageId);
    }

    private void seedRefusalRun(
            String runId,
            String workspaceId,
            String messageId,
            String query
    ) throws Exception {
        EvidenceBundleSnapshot bundle = new EvidenceBundleSnapshot(
                EvidenceBundleSnapshot.SCHEMA_VERSION,
                "bundle-refusal",
                PLAN_VERSION,
                true,
                List.of("qa_primary_no_scoped_hits", "qa_mysql_fallback"),
                Instant.parse("2026-07-15T12:00:01Z"),
                List.of()
        );
        RetrievalExecutionTrace trace = new RetrievalExecutionTrace(
                RetrievalExecutionTrace.SCHEMA_VERSION,
                PLAN_VERSION,
                2_000,
                0,
                0,
                0,
                0,
                List.of(new StepTrace(
                        0, "QA_PASSAGE", 12, 0, 0, 1_500, true,
                        List.of("qa_primary_no_scoped_hits", "qa_mysql_fallback"),
                        Map.of(
                                "primary_hit_count", 0L,
                                "mysql_fallback_used", 1L,
                                "mysql_candidate_count", 80L,
                                "selected_count", 0L))),
                List.of()
        );
        insertRun(runId, workspaceId, messageId, query, bundle, trace);
    }

    private void insertRun(
            String runId,
            String workspaceId,
            String messageId,
            String query,
            EvidenceBundleSnapshot bundle,
            RetrievalExecutionTrace trace
    ) throws Exception {
        String conversationId = "conversation-" + runId;
        String queryMessageId = "query-" + runId;
        RetrievalPlan plan = qaPlan(workspaceId);
        jdbcTemplate.update("""
                insert into conversation_message(
                    id, conversation_id, workspace_id, role, answer_mode, content
                ) values (?, ?, ?, 'USER', 'QA', ?)
                """, queryMessageId, conversationId, workspaceId, query);
        jdbcTemplate.update("""
                insert into conversation_message(
                    id, conversation_id, workspace_id, role, answer_mode, content
                ) values (?, ?, ?, 'ASSISTANT', 'QA', 'answer')
                """, messageId, conversationId, workspaceId);
        jdbcTemplate.update("""
                insert into answer_run(
                    id, workspace_id, conversation_id, query_message_id, answer_message_id,
                    mode, status, retrieval_plan_version, retrieval_plan_json, evidence_bundle_json
                ) values (?, ?, ?, ?, ?, 'QA', 'COMPLETED', ?, ?, ?)
                """, runId, workspaceId, conversationId, queryMessageId, messageId, PLAN_VERSION,
                objectMapper.writeValueAsString(plan),
                objectMapper.writeValueAsString(bundle));
        jdbcTemplate.update("""
                insert into answer_event(id, workspace_id, answer_run_id, seq, event_type, payload_json)
                values (?, ?, ?, 1, 'retrieval.summary', ?)
                """, "event-" + runId, workspaceId, runId,
                objectMapper.writeValueAsString(Map.ofEntries(
                        Map.entry("trace_schema_version", trace.schemaVersion()),
                        Map.entry("plan_version", trace.planVersion()),
                        Map.entry("retrieval_latency_micros", trace.totalLatencyMicros()),
                        Map.entry("candidate_count", trace.rawCandidateCount()),
                        Map.entry("admitted_candidate_count", trace.admittedCandidateCount()),
                        Map.entry("selected_evidence_count", trace.selectedEvidenceCount()),
                        Map.entry("selected_evidence_characters", trace.selectedEvidenceCharacters()),
                        Map.entry("degraded", bundle.degraded()),
                        Map.entry("degradation_reasons", bundle.degradationReasons()),
                        Map.entry("execution_trace", trace)
                )));
    }

    private RetrievalPlan qaPlan(String workspaceId) {
        return new RetrievalPlan(
                PLAN_VERSION,
                AnswerMode.QA,
                List.of(new RetrievalPlan.Step(
                        QaPassageEvidenceRetriever.CHANNEL,
                        12,
                        1.0,
                        Map.of(
                                "workspace_id", workspaceId,
                                "snapshot_status", "ACTIVE",
                                "source_ids", "source-1",
                                "relevance_policy", QaEvidenceRelevancePolicy.POLICY_VERSION,
                                "selection_policy", QaEvidenceSelectionPolicy.POLICY_VERSION)
                )),
                new RetrievalPlan.Budget(
                        QaEvidenceSelectionPolicy.DEFAULT_BUNDLE_EVIDENCE_LIMIT,
                        QaEvidenceSelectionPolicy.DEFAULT_BUNDLE_CHARACTER_LIMIT,
                        0,
                        0)
        );
    }

    private void createSchema() {
        jdbcTemplate.execute("""
                create table answer_run(
                    id varchar(64) primary key,
                    workspace_id varchar(64) not null,
                    conversation_id varchar(64) not null,
                    query_message_id varchar(64) not null,
                    mode varchar(32) not null,
                    status varchar(32) not null,
                    answer_message_id varchar(64),
                    retrieval_plan_version varchar(64) not null,
                    retrieval_plan_json clob,
                    evidence_bundle_json clob
                )
                """);
        jdbcTemplate.execute("""
                create table conversation_message(
                    id varchar(64) primary key,
                    conversation_id varchar(64) not null,
                    workspace_id varchar(64) not null,
                    role varchar(32) not null,
                    answer_mode varchar(32),
                    content clob not null
                )
                """);
        jdbcTemplate.execute("""
                create table answer_event(
                    id varchar(64) primary key,
                    workspace_id varchar(64) not null,
                    answer_run_id varchar(64) not null,
                    seq bigint not null,
                    event_type varchar(64) not null,
                    payload_json clob
                )
                """);
        jdbcTemplate.execute("""
                create table citation(
                    id varchar(64) primary key,
                    workspace_id varchar(64) not null,
                    source_id varchar(64) not null,
                    source_snapshot_id varchar(64) not null,
                    source_chunk_id varchar(64) not null
                )
                """);
        jdbcTemplate.execute("""
                create table message_citation(
                    id varchar(64) primary key,
                    message_id varchar(64) not null,
                    citation_id varchar(64) not null,
                    sort_order int not null
                )
                """);
        jdbcTemplate.execute("""
                create table source(
                    id varchar(64) primary key,
                    workspace_id varchar(64) not null
                )
                """);
        jdbcTemplate.execute("""
                create table source_snapshot(
                    id varchar(64) primary key,
                    source_id varchar(64) not null
                )
                """);
        jdbcTemplate.execute("""
                create table source_chunk(
                    id varchar(64) primary key,
                    workspace_id varchar(64) not null,
                    source_id varchar(64) not null,
                    source_snapshot_id varchar(64) not null
                )
                """);
    }

    private void seedOwnership() {
        jdbcTemplate.update(
                "insert into source(id, workspace_id) values ('source-1', 'workspace-1')");
        jdbcTemplate.update(
                "insert into source_snapshot(id, source_id) values ('snapshot-1', 'source-1')");
        jdbcTemplate.update("""
                insert into source_chunk(id, workspace_id, source_id, source_snapshot_id)
                values ('chunk-1', 'workspace-1', 'source-1', 'snapshot-1')
                """);
    }

    private static final class CountingJdbcTemplate extends JdbcTemplate {
        private int queryCount;

        private CountingJdbcTemplate(EmbeddedDatabase database) {
            super(database);
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            queryCount++;
            return super.query(sql, rowMapper, args);
        }

        int queryCount() {
            return queryCount;
        }

        void resetQueryCount() {
            queryCount = 0;
        }
    }
}
