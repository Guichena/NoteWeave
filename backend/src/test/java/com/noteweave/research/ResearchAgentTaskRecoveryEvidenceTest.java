package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MA4 task-level recovery evidence (D-39 phase 1).
 *
 * Scenario under test: one DEEP_CELL task is claimed, the worker dies midway, the lease
 * expires, and the SAME task is re-claimed by another worker under a new lease_epoch /
 * fencing_token. This class first records what the current implementation really does,
 * then guards the required behaviour.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ResearchAgentTaskRecoveryEvidenceTest {

    private static final Map<String, Long> RESERVED = reservation();

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MockMvc mockMvc;
    @Autowired private ResearchAgentTaskService taskService;
    @Autowired private ResearchAgentCommandOutboxService outboxService;
    @Autowired private ResearchBudgetAndCheckpointService budgetService;
    @Autowired private ResearchAgentPermitService permitService;
    @Autowired private ResearchExternalSnapshotArchiveService archiveService;
    @Autowired private ResearchAgentCompletionService completionService;
    @Autowired private ResearchAgentCompletionCanonicalizer canonicalizer;

    // The permit service must fail closed when Redis is unavailable; tests replace the
    // limiter so that the lease/authority path is observable without Redis.
    @org.springframework.boot.test.mock.mockito.MockBean private ResearchAgentRateLimitService rateLimitService;

    // ------------------------------------------------------------------ //
    // (a) does a re-execution repeat the provider-facing tool round?      //
    // ------------------------------------------------------------------ //

    @Test
    void shouldGrantASecondFullToolPermitSetToTheReclaimedTask() {
        Fixture fixture = fixture("worker-a");
        for (String tool : List.of("search", "fetch", "read", "extract")) {
            permit("worker-a", fixture, tool);
        }

        Fixture reclaimed = expireAndReclaim(fixture, "worker-b");

        // The server authorizes the whole provider round again for the new lease: search,
        // fetch, read and extract are all re-granted, so every provider call is repeated.
        for (String tool : List.of("search", "fetch", "read", "extract")) {
            assertThat(permitService.requirePermit(command("worker-b", reclaimed, tool)).status())
                    .isEqualTo("GRANTED");
        }
        assertThatThrownBy(() -> permitService.requirePermit(command("worker-a", fixture, "search")))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_TASK_STALE_LEASE");
    }

    // ------------------------------------------------------------------ //
    // (b) is any usage charged twice, and does a restart reset the task?  //
    // ------------------------------------------------------------------ //

    @Test
    void shouldKeepOneTaskBudgetReservationAcrossRestartAndSettleItExactlyOnce() {
        Fixture fixture = fixture("worker-a");
        Map<String, Long> reservedBefore = reservedOf(fixture.taskId());

        Fixture reclaimed = expireAndReclaim(fixture, "worker-b");
        Map<String, Long> reservedAfterRestart = reservedOf(fixture.taskId());

        // A restart must not reset or re-create the task-level reservation.
        assertThat(count("research_budget_reservation", "research_agent_task_id", fixture.taskId())).isEqualTo(1);
        assertThat(reservedAfterRestart).isEqualTo(reservedBefore);
        assertThat(stateOf(fixture.taskId())).isEqualTo("RESERVED");

        completionService.complete(reclaimed.taskId(), envelope(reclaimed, attemptUsage()));

        assertThat(stateOf(fixture.taskId())).isEqualTo("SETTLED");
        assertThat(consumedOf(fixture.taskId())).isEqualTo(Map.of(
                "llm_calls", 1L, "search_calls", 1L, "fetch_calls", 1L, "read_calls", 1L, "extract_calls", 1L,
                "evidence_cards", 1L, "candidates_submitted", 1L,
                "evidence_appended", 1L, "candidate_merges_accepted", 1L, "candidate_merges_rejected", 0L));
        assertThat(consumedOf(fixture.taskId()).keySet()).isEqualTo(RESERVED.keySet());
        assertThat(count("research_budget_reservation", "research_agent_task_id", fixture.taskId())).isEqualTo(1);
        assertThat(count("research_agent_execution", "research_agent_task_id", fixture.taskId())).isEqualTo(1);
    }

    @Test
    void shouldChargeTheAbandonedAttemptsAuthorizedRoundsToTheSameReservation() {
        Fixture fixture = fixture("worker-a");
        for (String tool : List.of("search", "fetch", "read", "extract")) {
            permit("worker-a", fixture, tool);
        }
        Fixture reclaimed = expireAndReclaim(fixture, "worker-b");
        completionService.complete(reclaimed.taskId(), envelope(reclaimed, attemptUsage()));

        // The four provider rounds the abandoned attempt was authorized to spend are still
        // charged here, so the re-execution cannot re-spend a budget it already burned.
        Map<String, Long> consumed = consumedOf(fixture.taskId());
        assertThat(consumed.get("search_calls")).isEqualTo(2L);
        assertThat(consumed.get("fetch_calls")).isEqualTo(2L);
        assertThat(consumed.get("read_calls")).isEqualTo(2L);
        assertThat(consumed.get("extract_calls")).isEqualTo(2L);
        assertThat(consumed.get("llm_calls")).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_agent_tool_grant where research_agent_task_id = ? and lease_epoch = ?",
                Integer.class, fixture.taskId(), fixture.leaseEpoch())).isEqualTo(4);
        assertThat(count("research_agent_execution", "research_agent_task_id", fixture.taskId())).isEqualTo(1);
        conserved(fixture.taskId());
    }

    @Test
    void shouldFailClosedWhenEarlierAuthorizedRoundsExhaustTheReservation() {
        Map<String, Long> tight = new LinkedHashMap<>(RESERVED);
        tight.put("search_calls", 1L);
        Fixture fixture = fixture("worker-a", Map.copyOf(tight));
        permit("worker-a", fixture, "search");
        Fixture reclaimed = expireAndReclaim(fixture, "worker-b");

        // Prior authorized round (1) + this attempt (1) exceeds the reservation (1).
        assertThatThrownBy(() -> completionService.complete(reclaimed.taskId(), envelope(reclaimed, attemptUsage())))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_COMPLETION_BUDGET_EXCEEDED");

        assertThat(stateOf(fixture.taskId())).isEqualTo("RESERVED");
        assertThat(count("research_agent_execution", "research_agent_task_id", fixture.taskId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select status from research_agent_task where id = ?", String.class, fixture.taskId()))
                .isEqualTo("CLAIMED");
    }

    @Test
    void shouldServeTheArchivedInventoryOnlyToTheCurrentLeaseHolder() throws Exception {
        Fixture fixture = fixture("worker-a");
        archiveService.archive(archiveCommand("worker-a", fixture, "https://example.com/recovery/a"));
        Fixture reclaimed = expireAndReclaim(fixture, "worker-b");

        mockMvc.perform(post("/internal/research-agent/external-snapshots/archived")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"task_id":"%s","worker_instance_id":"worker-b","lease_epoch":%d,"fencing_token":%d}
                                """.formatted(reclaimed.taskId(), reclaimed.leaseEpoch(), reclaimed.fencingToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].window_id").value("window-0"))
                .andExpect(jsonPath("$.data[0].source_url").value("https://example.com/recovery/a"));

        assertThat(archiveService.reusableWindows(new ResearchExternalSnapshotArchiveService.ReuseCommand(
                reclaimed.taskId(), "worker-b", reclaimed.leaseEpoch(), reclaimed.fencingToken())))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.sourceUrl()).isEqualTo("https://example.com/recovery/a");
                    assertThat(row.contentText()).isEqualTo("external archived text for recovery");
                    assertThat(row.contentSha256())
                            .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest("external archived text for recovery".getBytes(StandardCharsets.UTF_8))));
                });

        // The dead attempt's lease can no longer read what it archived.
        assertThatThrownBy(() -> archiveService.reusableWindows(
                new ResearchExternalSnapshotArchiveService.ReuseCommand(
                        fixture.taskId(), "worker-a", fixture.leaseEpoch(), fixture.fencingToken())))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_TASK_STALE_LEASE");
    }

    private void conserved(String taskId) {
        Map<String, Long> reserved = reservedOf(taskId);
        Map<String, Long> consumed = consumedOf(taskId);
        Map<String, Long> released = longJson("released_json", taskId);
        assertThat(reserved.keySet()).isEqualTo(consumed.keySet());
        reserved.forEach((key, value) -> assertThat(consumed.get(key) + released.get(key)).isEqualTo(value));
    }

    // ------------------------------------------------------------------ //
    // (c) is a late callback from the old execution rejected and inert?   //
    // ------------------------------------------------------------------ //

    @Test
    void shouldRejectTheOldExecutionCompletionArrivingBeforeTheRecoveredOne() {
        Fixture fixture = fixture("worker-a");
        Fixture reclaimed = expireAndReclaim(fixture, "worker-b");

        Map<String, Object> cellBefore = canonicalCell(reclaimed);
        String taskStatusBefore = jdbcTemplate.queryForObject(
                "select status from research_agent_task where id = ?", String.class, reclaimed.taskId());
        Map<String, Long> reservedBefore = reservedOf(reclaimed.taskId());

        assertThatThrownBy(() -> completionService.complete(reclaimed.taskId(), envelope(fixture, attemptUsage())))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_TASK_STALE_LEASE");

        assertThat(canonicalCell(reclaimed)).isEqualTo(cellBefore);
        assertThat(jdbcTemplate.queryForObject(
                "select status from research_agent_task where id = ?", String.class, reclaimed.taskId()))
                .isEqualTo(taskStatusBefore);
        assertThat(reservedOf(reclaimed.taskId())).isEqualTo(reservedBefore);
        assertThat(stateOf(reclaimed.taskId())).isEqualTo("RESERVED");
        assertThat(count("research_agent_execution", "research_agent_task_id", reclaimed.taskId())).isZero();
    }

    @Test
    void shouldRejectTheOldExecutionCompletionArrivingAfterTheRecoveredOne() {
        Fixture fixture = fixture("worker-a");
        Fixture reclaimed = expireAndReclaim(fixture, "worker-b");
        completionService.complete(reclaimed.taskId(), envelope(reclaimed, attemptUsage()));

        Map<String, Object> cellBefore = canonicalCell(reclaimed);
        String taskStatusBefore = jdbcTemplate.queryForObject(
                "select status from research_agent_task where id = ?", String.class, reclaimed.taskId());
        Map<String, Long> consumedBefore = consumedOf(reclaimed.taskId());
        int reservationsBefore = count("research_budget_reservation", "research_agent_task_id", reclaimed.taskId());

        // The committed completion anchors the task, so the stale envelope is rejected upstream
        // of the lease check with ALREADY_COMMITTED rather than STALE_LEASE.
        assertThatThrownBy(() -> completionService.complete(reclaimed.taskId(), envelope(fixture, attemptUsage())))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_COMPLETION_ALREADY_COMMITTED");

        assertThat(canonicalCell(reclaimed)).isEqualTo(cellBefore);
        assertThat(jdbcTemplate.queryForObject(
                "select status from research_agent_task where id = ?", String.class, reclaimed.taskId()))
                .isEqualTo(taskStatusBefore);
        assertThat(consumedOf(reclaimed.taskId())).isEqualTo(consumedBefore);
        assertThat(count("research_budget_reservation", "research_agent_task_id", reclaimed.taskId()))
                .isEqualTo(reservationsBefore);
        assertThat(count("research_agent_execution", "research_agent_task_id", reclaimed.taskId())).isEqualTo(1);
    }

    private Map<String, Object> canonicalCell(Fixture fixture) {
        return jdbcTemplate.queryForMap(
                "select cell_status, cell_version, candidate_value, plan_revision, entity_set_version, active_task_id "
                        + "from research_cell where research_run_id = ? and cell_key = ?",
                fixture.runId(), fixture.cellKey());
    }

    // ------------------------------------------------------------------ //
    // archive survival: the external content archived by the dead attempt //
    // ------------------------------------------------------------------ //

    @Test
    void shouldSurviveTheRestartAndReplayIdenticalExternalArchiveIdempotently() {
        Fixture fixture = fixture("worker-a");
        archiveService.archive(archiveCommand("worker-a", fixture, "https://example.com/recovery/a"));

        Fixture reclaimed = expireAndReclaim(fixture, "worker-b");
        ResearchExternalSnapshotArchiveService.ArchiveReceipt replay =
                archiveService.archive(archiveCommand("worker-b", reclaimed, "https://example.com/recovery/a"));

        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(count("research_external_snapshot", "research_agent_task_id", fixture.taskId())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ //
    // helpers                                                             //
    // ------------------------------------------------------------------ //

    private Map<String, Long> attemptUsage() {
        return Map.of(
                "llm_calls", 1L, "search_calls", 1L, "fetch_calls", 1L, "read_calls", 1L, "extract_calls", 1L,
                "evidence_cards", 1L, "candidates_submitted", 1L);
    }

    private Fixture fixture(String worker) {
        return fixture(worker, RESERVED);
    }

    private Fixture fixture(String worker, Map<String, Long> reserved) {
        String workspaceId = Ids.newId();
        String parentTaskId = Ids.newId();
        String runId = Ids.newId();
        jdbcTemplate.update("insert into workspace(id, owner_id, name, status) values (?, 'local-user', ?, 'ACTIVE')",
                workspaceId, "recovery-" + runId);
        jdbcTemplate.update("""
                insert into task(id, workspace_id, task_type, task_status, target_type, target_id)
                values (?, ?, 'RESEARCH_RUN', 'RUNNING', 'RESEARCH_RUN', ?)
                """, parentTaskId, workspaceId, runId);
        jdbcTemplate.update("""
                insert into research_run(id, workspace_id, task_id, question, profile_key, source_scope_json,
                    status, agent_execution_mode)
                values (?, ?, ?, 'recovery question', 'DEFAULT', '[]', 'RUNNING', 'INCREMENTAL_V1')
                """, runId, workspaceId, parentTaskId);
        String rowId = Ids.newId();
        jdbcTemplate.update(
                "insert into research_row(id, research_run_id, row_key, row_status) values (?, ?, 'entity-1', 'CANDIDATE_READY')",
                rowId, runId);
        String cellKey = "entity-1:field-0";
        jdbcTemplate.update("""
                insert into research_cell(id, research_run_id, research_row_id, cell_key, column_key,
                    candidate_value, cell_status, repair_count, cell_version, plan_revision, entity_set_version)
                values (?, ?, ?, ?, ?, ?, 'CANDIDATE_READY', 0, 0, 1, 1)
                """, Ids.newId(), runId, rowId, cellKey, "field-0", "old-0");
        Map<String, Object> source = Map.of("source_id", "source-0", "source_title", "Source 0",
                "sample_text", "prefix trusted quote 0 suffix");
        Map<String, Object> snapshotBudget = new LinkedHashMap<>();
        reserved.forEach(snapshotBudget::put);
        String taskId = taskService.createTask(new ResearchAgentTaskService.CreateTaskCommand(
                runId, "recovery-task-" + runId, "recovery-idem-" + runId, 1, "DEEP_CELL", "entity-1", "main",
                1, 1, List.of(cellKey), snapshotBudget,
                List.of(new ResearchAgentTaskService.TargetCellBinding(cellKey, 0)),
                new ResearchAgentTaskService.TaskExecutionContext("research-fake",
                        Map.of("source_scope", List.of(source)), Map.of("query", "recovery question")))).taskId();
        outboxService.enqueue(taskId);
        budgetService.reserve(new ResearchBudgetAndCheckpointService.ReserveCommand(
                runId, taskId, "recovery-budget-" + taskId, reserved));
        ResearchAgentTaskService.ClaimedTask claim = taskService.claimTask(
                new ResearchAgentTaskService.ClaimCommand(taskId, worker, 300));
        return new Fixture(runId, taskId, cellKey, worker, claim.leaseEpoch(), claim.fencingToken(),
                claim.snapshotDigest(), "deep-cell:" + taskId + ":" + claim.leaseEpoch() + ":" + claim.fencingToken());
    }

    private Fixture expireAndReclaim(Fixture first, String worker) {
        jdbcTemplate.update("""
                update research_agent_task
                set lease_expires_at = timestampadd(second, -1, current_timestamp)
                where id = ?
                """, first.taskId());
        assertThat(taskService.expireLeases()).isGreaterThanOrEqualTo(1);
        ResearchAgentTaskService.ClaimedTask claim = taskService.claimTask(
                new ResearchAgentTaskService.ClaimCommand(first.taskId(), worker, 300));
        assertThat(claim.leaseEpoch()).isEqualTo(first.leaseEpoch() + 1);
        assertThat(claim.fencingToken()).isEqualTo(first.fencingToken() + 1);
        return new Fixture(first.runId(), first.taskId(), first.cellKey(), worker, claim.leaseEpoch(),
                claim.fencingToken(), claim.snapshotDigest(),
                "deep-cell:" + first.taskId() + ":" + claim.leaseEpoch() + ":" + claim.fencingToken());
    }

    private void permit(String worker, Fixture fixture, String tool) {
        assertThat(permitService.requirePermit(command(worker, fixture, tool)).status()).isEqualTo("GRANTED");
    }

    private ResearchAgentPermitService.PermitCommand command(String worker, Fixture fixture, String tool) {
        return new ResearchAgentPermitService.PermitCommand(
                fixture.taskId(), worker, fixture.leaseEpoch(), fixture.fencingToken(), tool);
    }

    private ResearchExternalSnapshotArchiveService.ArchiveCommand archiveCommand(
            String worker, Fixture fixture, String url) {
        return new ResearchExternalSnapshotArchiveService.ArchiveCommand(
                fixture.taskId(), worker, fixture.leaseEpoch(), fixture.fencingToken(), "window-0", "source-9",
                "External Source", url, "search-provider", "external_url", "external archived text for recovery");
    }

    private ResearchAgentCompletionEnvelope envelope(Fixture fixture, Map<String, Long> usage) {
        String evidenceKey = "ev-" + fixture.taskId().substring(0, 8) + "-" + fixture.leaseEpoch();
        List<ResearchAgentCompletionEnvelope.Evidence> evidence = List.of(
                new ResearchAgentCompletionEnvelope.Evidence(
                        evidenceKey, "window-0", "source-0", "Source 0", "recovery question", "field-0",
                        "trusted quote 0", "value-0", "SUPPORTS", 900_000, 0, "WORKSPACE"));
        List<ResearchAgentCompletionEnvelope.Candidate> proposals = List.of(
                new ResearchAgentCompletionEnvelope.Candidate(
                        "cand-" + fixture.taskId().substring(0, 8) + "-" + fixture.leaseEpoch(),
                        fixture.cellKey(), 0, "value-0", List.of(evidenceKey), 900_000));
        return signed(new ResearchAgentCompletionEnvelope(
                "research-agent-completion.v1", fixture.taskId(), fixture.worker(), fixture.leaseEpoch(),
                fixture.fencingToken(), fixture.executionKey(), fixture.snapshotDigest(), "CANDIDATES_PROPOSED",
                usage, Map.of("search_hits", 1L, "documents", 1L, "windows", 1L),
                "sha256:" + "3".repeat(64), evidence, proposals, null));
    }

    private ResearchAgentCompletionEnvelope signed(ResearchAgentCompletionEnvelope envelope) {
        return envelope.withEnvelopeDigest(canonicalizer.digest(envelope));
    }

    private Map<String, Long> reservedOf(String taskId) {
        return longJson("reserved_json", taskId);
    }

    private Map<String, Long> consumedOf(String taskId) {
        return longJson("consumed_json", taskId);
    }

    private String stateOf(String taskId) {
        return jdbcTemplate.queryForObject(
                "select state from research_budget_reservation where research_agent_task_id = ?",
                String.class, taskId);
    }

    private Map<String, Long> longJson(String column, String taskId) {
        String raw = jdbcTemplate.queryForObject(
                "select " + column + " from research_budget_reservation where research_agent_task_id = ?",
                String.class, taskId);
        Map<?, ?> parsed;
        try {
            parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(raw, Map.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("budget ledger payload is not readable JSON", exception);
        }
        Map<String, Long> result = new LinkedHashMap<>();
        parsed.forEach((key, value) -> result.put(String.valueOf(key), ((Number) value).longValue()));
        return result;
    }

    private int count(String table, String column, String value) {
        Integer found = jdbcTemplate.queryForObject(
                "select count(*) from " + table + " where " + column + " = ?", Integer.class, value);
        return found == null ? 0 : found;
    }

    private static Map<String, Long> reservation() {
        Map<String, Long> reserved = new LinkedHashMap<>();
        reserved.put("llm_calls", 4L);
        reserved.put("search_calls", 4L);
        reserved.put("fetch_calls", 4L);
        reserved.put("read_calls", 4L);
        reserved.put("extract_calls", 4L);
        reserved.put("evidence_cards", 4L);
        reserved.put("candidates_submitted", 4L);
        reserved.put("evidence_appended", 4L);
        reserved.put("candidate_merges_accepted", 4L);
        reserved.put("candidate_merges_rejected", 4L);
        return Map.copyOf(reserved);
    }

    private record Fixture(
            String runId,
            String taskId,
            String cellKey,
            String worker,
            int leaseEpoch,
            long fencingToken,
            String snapshotDigest,
            String executionKey
    ) { }
}
