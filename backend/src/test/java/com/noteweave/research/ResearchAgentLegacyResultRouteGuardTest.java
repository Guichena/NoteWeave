package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.noteweave.common.Ids;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ResearchAgentLegacyResultRouteGuardTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MeterRegistry meterRegistry;

    @MockBean private ResearchAgentEvidenceIngestionService evidenceService;
    @MockBean private ResearchAgentCandidateIngressService candidateService;
    @MockBean private ResearchAgentTaskService taskService;

    private TaskFixture atomic;
    private TaskFixture legacy;

    @BeforeEach
    void setUp() {
        atomic = insertTask(true);
        legacy = insertTask(false);
        reset(evidenceService, candidateService, taskService);
        when(evidenceService.appendWorkspaceEvidence(any()))
                .thenReturn(new ResearchAgentEvidenceIngestionService.BatchReceipt(1, 0));
        when(candidateService.appendAndVerify(any()))
                .thenReturn(new ResearchAgentCandidateIngressService.CandidateBatchReceipt(1, 0));
        when(taskService.submitExecution(any()))
                .thenReturn(new ResearchAgentTaskService.ExecutionReceipt(Ids.newId(), false));
        when(taskService.claimTask(any()))
                .thenReturn(new ResearchAgentTaskService.ClaimedTask(
                        atomic.taskId(), 1, 1, Instant.now().plusSeconds(30), "[]", "{}", null, null));
        when(taskService.heartbeat(any()))
                .thenReturn(new ResearchAgentTaskService.ClaimedTask(
                        atomic.taskId(), 1, 1, Instant.now().plusSeconds(30), "[]", "{}", null, null));
    }

    @Test
    void shouldRejectAtomicEvidenceRouteFromRawTaskScopeBeforeBindingOrPrimitive() throws Exception {
        Map<String, Object> before = stateDigest(atomic);

        assertAtomicRequired("/internal/research-agent/workspace-evidence-batches",
                "{\"task_id\":\"" + atomic.taskId() + "\"}");
        assertAtomicRequired("/internal/research-agent/workspace-evidence-batches",
                "{\"task_id\":\"" + atomic.taskId() + "\",\"worker_instance_id\":");
        assertAtomicRequired("/internal/research-agent/workspace-evidence-batches",
                "{,\"task_id\":\"" + atomic.taskId() + "\"}");
        assertAtomicRequired("/internal/research-agent/workspace-evidence-batches", validEvidence(atomic.taskId()));

        verifyNoInteractions(evidenceService);
        assertThat(stateDigest(atomic)).isEqualTo(before);
    }

    @Test
    void shouldRejectAtomicCandidateRouteFromRawTaskScopeBeforeBindingOrPrimitive() throws Exception {
        Map<String, Object> before = stateDigest(atomic);

        assertAtomicRequired("/internal/research-agent/candidate-batches",
                "{\"task_id\":\"" + atomic.taskId() + "\"}");
        assertAtomicRequired("/internal/research-agent/candidate-batches",
                "{\"task_id\":\"" + atomic.taskId() + "\",\"worker_instance_id\":");
        assertAtomicRequired("/internal/research-agent/candidate-batches",
                "{,\"task_id\":\"" + atomic.taskId() + "\"}");
        assertAtomicRequired("/internal/research-agent/candidate-batches", validCandidate(atomic.taskId()));

        verifyNoInteractions(candidateService);
        assertThat(stateDigest(atomic)).isEqualTo(before);
    }

    @Test
    void shouldNotLetDuplicateTaskIdMoveAtomicScopePastTheFirstLayer() throws Exception {
        Map<String, Object> before = stateDigest(atomic);
        String duplicateTaskId = "{\"task_id\":\"" + legacy.taskId() + "\",\"task_id\":\""
                + atomic.taskId() + "\"}";

        assertAtomicRequired("/internal/research-agent/workspace-evidence-batches", duplicateTaskId);
        assertAtomicRequired("/internal/research-agent/candidate-batches", duplicateTaskId);

        verifyNoInteractions(evidenceService, candidateService);
        assertThat(stateDigest(atomic)).isEqualTo(before);
    }

    @Test
    void shouldNormalizeUuidCaseBeforeDatabaseScopeLookup() throws Exception {
        Map<String, Object> before = stateDigest(atomic);

        assertAtomicRequired("/internal/research-agent/workspace-evidence-batches",
                "{\"task_id\":\"" + atomic.taskId().toUpperCase(java.util.Locale.ROOT) + "\"}");
        assertAtomicRequired("/internal/research-agent-tasks/"
                + atomic.taskId().toUpperCase(java.util.Locale.ROOT) + "/submit", "");

        verifyNoInteractions(evidenceService, taskService);
        assertThat(stateDigest(atomic)).isEqualTo(before);
    }

    @Test
    void shouldKeepRawBodyBoundedWithoutLettingAtomicScopeReachThePrimitive() throws Exception {
        Map<String, Object> before = stateDigest(atomic);
        String oversizedAtomic = "{\"task_id\":\"" + atomic.taskId() + "\",\"padding\":\""
                + "x".repeat(ResearchAgentCompletionEnvelopeParser.MAX_PAYLOAD_BYTES) + "\"}";
        String oversizedLegacy = "{\"task_id\":\"" + legacy.taskId() + "\",\"padding\":\""
                + "x".repeat(ResearchAgentCompletionEnvelopeParser.MAX_PAYLOAD_BYTES) + "\"}";

        assertAtomicRequired("/internal/research-agent/workspace-evidence-batches", oversizedAtomic);
        mockMvc.perform(post("/internal/research-agent/candidate-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversizedLegacy))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESEARCH_AGENT_LEGACY_RESULT_INVALID"));

        verifyNoInteractions(evidenceService, candidateService);
        assertThat(stateDigest(atomic)).isEqualTo(before);
    }

    @Test
    void shouldPreserveRawUtf8RejectionAndStillFenceAnIdentifiedAtomicTask() throws Exception {
        Map<String, Object> before = stateDigest(atomic);
        byte[] prefix = ("{\"task_id\":\"" + atomic.taskId() + "\",\"padding\":\"")
                .getBytes(StandardCharsets.UTF_8);
        byte[] invalidUtf8 = java.util.Arrays.copyOf(prefix, prefix.length + 3);
        invalidUtf8[prefix.length] = (byte) 0xc3;
        invalidUtf8[prefix.length + 1] = '"';
        invalidUtf8[prefix.length + 2] = '}';

        mockMvc.perform(post("/internal/research-agent/workspace-evidence-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidUtf8))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESEARCH_AGENT_ATOMIC_COMPLETION_REQUIRED"));

        verifyNoInteractions(evidenceService);
        assertThat(stateDigest(atomic)).isEqualTo(before);
    }

    @Test
    void shouldNotInterceptClaimHeartbeatOrAtomicCompletionRoutes() throws Exception {
        double before = legacyRouteCount();

        mockMvc.perform(post("/internal/research-agent-tasks/{taskId}/claim", atomic.taskId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"worker_instance_id\":\"worker-1\",\"lease_seconds\":30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
        mockMvc.perform(post("/internal/research-agent-tasks/{taskId}/heartbeat", atomic.taskId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"worker_instance_id":"worker-1","lease_epoch":1,
                                 "fencing_token":1,"lease_seconds":30}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
        mockMvc.perform(post("/internal/research-agent-tasks/{taskId}/complete", atomic.taskId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESEARCH_AGENT_COMPLETION_INVALID"));

        verify(taskService).claimTask(any());
        verify(taskService).heartbeat(any());
        assertThat(legacyRouteCount()).isEqualTo(before);
    }

    @Test
    void shouldCountEachLegacyResultRequestOnceWithOnlyBoundedRouteAndTaskSchemaTags() throws Exception {
        double atomicEvidence = legacyRouteCount("evidence", "atomic_v1");
        double legacyCandidate = legacyRouteCount("candidate", "legacy_non_snapshot");
        double unknownSubmit = legacyRouteCount("submit", "unknown");
        double missingEvidence = legacyRouteCount("evidence", "missing_or_invalid");

        assertAtomicRequired("/internal/research-agent/workspace-evidence-batches",
                validEvidence(atomic.taskId()));
        mockMvc.perform(post("/internal/research-agent/candidate-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCandidate(legacy.taskId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
        String unknownTaskId = Ids.newId();
        mockMvc.perform(post("/internal/research-agent-tasks/{taskId}/submit", unknownTaskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validSubmit()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
        mockMvc.perform(post("/internal/research-agent/workspace-evidence-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(legacyRouteCount("evidence", "atomic_v1") - atomicEvidence).isEqualTo(1.0);
        assertThat(legacyRouteCount("candidate", "legacy_non_snapshot") - legacyCandidate).isEqualTo(1.0);
        assertThat(legacyRouteCount("submit", "unknown") - unknownSubmit).isEqualTo(1.0);
        assertThat(legacyRouteCount("evidence", "missing_or_invalid") - missingEvidence).isEqualTo(1.0);

        double beforeDuplicate = legacyRouteCount();
        double duplicateAtomicCandidate = legacyRouteCount("candidate", "atomic_v1");
        assertAtomicRequired("/internal/research-agent/candidate-batches",
                "{\"task_id\":\"" + legacy.taskId() + "\",\"task_id\":\""
                        + atomic.taskId() + "\"}");
        assertThat(legacyRouteCount() - beforeDuplicate).isEqualTo(1.0);
        assertThat(legacyRouteCount("candidate", "atomic_v1") - duplicateAtomicCandidate).isEqualTo(1.0);

        assertThat(meterRegistry.getMeters().stream()
                .filter(meter -> "research.agent.legacy.result.route.total".equals(meter.getId().getName())))
                .allSatisfy(meter -> {
                    assertThat(meter.getId().getTag("route"))
                            .isIn("evidence", "candidate", "submit");
                    assertThat(meter.getId().getTag("task_schema"))
                            .isIn("atomic_v1", "legacy_non_snapshot", "unknown", "missing_or_invalid");
                    assertThat(meter.getId().getTags())
                            .extracting(io.micrometer.core.instrument.Tag::getKey)
                            .containsOnly("route", "task_schema");
                    assertThat(meter.getId().getTags())
                            .extracting(io.micrometer.core.instrument.Tag::getValue)
                            .doesNotContain(atomic.taskId(), legacy.taskId(), unknownTaskId);
                });
    }

    @Test
    void shouldMarkAllRetiredMappingsAndServicePrimitivesDeprecatedForRemoval() throws Exception {
        assertDeprecatedForRemoval(ResearchAgentEvidenceInternalController.class.getDeclaredMethod(
                "append", ResearchAgentEvidenceInternalController.BatchRequest.class));
        assertDeprecatedForRemoval(ResearchAgentCandidateInternalController.class.getDeclaredMethod(
                "append", ResearchAgentCandidateInternalController.BatchRequest.class));
        assertDeprecatedForRemoval(ResearchAgentTaskInternalController.class.getDeclaredMethod(
                "submit", String.class, ResearchAgentTaskInternalController.SubmitRequest.class));
        assertDeprecatedForRemoval(ResearchAgentEvidenceIngestionService.class.getDeclaredMethod(
                "appendWorkspaceEvidence", ResearchAgentEvidenceIngestionService.EvidenceBatchCommand.class));
        assertDeprecatedForRemoval(ResearchAgentCandidateIngressService.class.getDeclaredMethod(
                "appendAndVerify", ResearchAgentCandidateIngressService.CandidateBatchCommand.class));
        assertDeprecatedForRemoval(ResearchAgentTaskService.class.getDeclaredMethod(
                "submitExecution", ResearchAgentTaskService.SubmitCommand.class));
    }

    @Test
    void shouldRejectAtomicSubmitFromPathEvenWhenBodyIsEmptyMalformedOrIncomplete() throws Exception {
        Map<String, Object> before = stateDigest(atomic);
        String path = "/internal/research-agent-tasks/" + atomic.taskId() + "/submit";

        assertAtomicRequired(path, "");
        assertAtomicRequired(path, "{");
        assertAtomicRequired(path, "{}");
        assertAtomicRequired(path, validSubmit());

        verifyNoInteractions(taskService);
        assertThat(stateDigest(atomic)).isEqualTo(before);
    }

    @Test
    void shouldPreserveLegacyValidationAndPrimitiveRoutes() throws Exception {
        mockMvc.perform(post("/internal/research-agent/workspace-evidence-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"task_id\":\"" + legacy.taskId() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/internal/research-agent/candidate-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"task_id\":\"" + legacy.taskId() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/internal/research-agent/workspace-evidence-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validEvidence(legacy.taskId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
        mockMvc.perform(post("/internal/research-agent/candidate-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCandidate(legacy.taskId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
        mockMvc.perform(post("/internal/research-agent-tasks/{taskId}/submit", legacy.taskId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validSubmit()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        verify(evidenceService).appendWorkspaceEvidence(any());
        verify(candidateService).appendAndVerify(any());
        verify(taskService).submitExecution(any());
    }

    private void assertAtomicRequired(String path, String body) throws Exception {
        mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESEARCH_AGENT_ATOMIC_COMPLETION_REQUIRED"));
    }

    private TaskFixture insertTask(boolean snapshotReady) {
        String workspaceId = Ids.newId();
        String parentTaskId = Ids.newId();
        String runId = Ids.newId();
        String taskId = Ids.newId();
        jdbcTemplate.update(
                "insert into workspace(id, owner_id, name, status) values (?, 'local-user', ?, 'ACTIVE')",
                workspaceId, "legacy-route-guard-" + taskId);
        jdbcTemplate.update("""
                insert into task(id, workspace_id, task_type, task_status, target_type, target_id)
                values (?, ?, 'RESEARCH_RUN', 'RUNNING', 'RESEARCH_RUN', ?)
                """, parentTaskId, workspaceId, runId);
        jdbcTemplate.update("""
                insert into research_run(id, workspace_id, task_id, question, profile_key, source_scope_json,
                    status, agent_execution_mode)
                values (?, ?, ?, 'q', 'DEFAULT', '[]', 'RUNNING', 'INCREMENTAL_V1')
                """, runId, workspaceId, parentTaskId);
        jdbcTemplate.update("""
                insert into research_agent_task(
                    id, research_run_id, task_key, idempotency_key, wave_no, role, entity_id, branch_id,
                    plan_revision, entity_set_version, target_cells_json, budget_json,
                    target_bindings_json, execution_context_json, snapshot_schema_version, status)
                values (?, ?, ?, ?, 1, 'DEEP_CELL', 'entity-1', 'main', 1, 1, '["entity-1:method"]', '{}',
                    ?, ?, ?, 'PENDING')
                """, taskId, runId, "task-" + taskId, "idem-" + taskId,
                snapshotReady ? "[{\"cell_id\":\"entity-1:method\",\"expected_version\":0}]" : null,
                snapshotReady ? "{\"provider_key\":\"fake\"}" : null,
                snapshotReady ? "research-agent-task-snapshot.v1" : null);
        return new TaskFixture(taskId, runId);
    }

    private Map<String, Object> stateDigest(TaskFixture fixture) {
        Map<String, Object> digest = new LinkedHashMap<>();
        digest.put("task", jdbcTemplate.queryForMap("""
                select status, lease_epoch, fencing_token, worker_instance_id, lease_expires_at,
                       terminal_at, updated_at
                from research_agent_task where id = ?
                """, fixture.taskId()));
        digest.put("run", jdbcTemplate.queryForMap("""
                select status, agent_execution_mode, updated_at from research_run where id = ?
                """, fixture.runId()));
        digest.put("executions", count("select count(*) from research_agent_execution where research_agent_task_id = ?",
                fixture.taskId()));
        digest.put("completions", count("select count(*) from research_agent_completion where research_agent_task_id = ?",
                fixture.taskId()));
        digest.put("budget", count("select count(*) from research_budget_reservation where research_agent_task_id = ?",
                fixture.taskId()));
        digest.put("outbox", count("select count(*) from research_agent_outbox where research_agent_task_id = ?",
                fixture.taskId()));
        digest.put("delivery_failures", count(
                "select count(*) from research_agent_delivery_failure where research_agent_task_id = ?", fixture.taskId()));
        digest.put("evidence", count("select count(*) from source_evidence where research_run_id = ?", fixture.runId()));
        digest.put("candidates", count("select count(*) from research_agent_candidate where research_run_id = ?", fixture.runId()));
        digest.put("merges", count("select count(*) from research_cell_merge where research_run_id = ?", fixture.runId()));
        digest.put("cells", count("select count(*) from research_cell where research_run_id = ?", fixture.runId()));
        digest.put("cell_evidence", count("""
                select count(*) from research_cell_evidence rce
                join research_cell rc on rc.id = rce.research_cell_id
                where rc.research_run_id = ?
                """, fixture.runId()));
        return digest;
    }

    private long count(String sql, String id) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, id);
        return value == null ? 0L : value;
    }

    private void assertDeprecatedForRemoval(java.lang.reflect.Method method) {
        Deprecated annotation = method.getAnnotation(Deprecated.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.forRemoval()).isTrue();
    }

    private double legacyRouteCount(String route, String taskSchema) {
        io.micrometer.core.instrument.Counter counter = meterRegistry
                .find("research.agent.legacy.result.route.total")
                .tags("route", route, "task_schema", taskSchema)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    private double legacyRouteCount() {
        return meterRegistry.find("research.agent.legacy.result.route.total").counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count)
                .sum();
    }

    private String validEvidence(String taskId) {
        return """
                {"task_id":"%s","worker_instance_id":"worker-1","lease_epoch":1,"fencing_token":1,
                 "evidence":[{"evidence_key":"e-1","source_id":"s-1","quote_text":"quote",
                 "claim_text":"claim","relation_type":"SUPPORTS","support_score":1.0,
                 "conflict_score":0.0,"snapshot_status":"WORKSPACE"}]}
                """.formatted(taskId);
    }

    private String validCandidate(String taskId) {
        return """
                {"task_id":"%s","worker_instance_id":"worker-1","lease_epoch":1,"fencing_token":1,
                 "execution_id":"execution-1","candidates":[{"candidate_id":"candidate-1",
                 "idempotency_key":"candidate-idem-1","cell_key":"entity-1:method","base_cell_version":0,
                 "candidate_value":"claim","evidence_keys":["e-1"],"confidence":1.0}]}
                """.formatted(taskId);
    }

    private String validSubmit() {
        return """
                {"worker_instance_id":"worker-1","lease_epoch":1,"fencing_token":1,
                 "execution_key":"execution-1","termination_reason":"DONE","usage":{},"trace_digest":"sha256:test"}
                """;
    }

    private record TaskFixture(String taskId, String runId) { }
}
